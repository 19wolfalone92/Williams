from __future__ import annotations
import hmac,time,uuid
from collections import deque
from fastapi import Depends,FastAPI,Header,HTTPException,Request
from fastapi.responses import JSONResponse
from .config import load_settings
from .council import Council
from .mock import mock_decisions
from .luna import LunaService
from .models import CouncilDecision,CouncilRequest,LunaRequest,LunaResponse
from .providers import create_providers

settings=load_settings()
providers=create_providers(settings.providers,settings.request_timeout_seconds)
council=Council(settings.min_agents,settings.min_consensus)
luna=LunaService(providers,settings.free_mode,settings.request_timeout_seconds)
app=FastAPI(title="AI-Forge Council Core",version="0.1.0",docs_url="/docs" if settings.mock_mode else None)
_rate={}

@app.middleware("http")
async def request_limits(request:Request,call_next):
    if request.url.path.startswith("/v1/"):
        length=request.headers.get("content-length")
        if length and int(length)>settings.max_request_bytes: raise HTTPException(413,"request too large")
    request.state.request_id=uuid.uuid4().hex
    response=await call_next(request)
    response.headers["X-Request-ID"]=request.state.request_id
    return response

def _auth(authorization):
    if not authorization or not authorization.startswith("Bearer "): raise HTTPException(401,"missing bearer token")
    token=authorization[7:].strip()
    if not hmac.compare_digest(token,settings.api_token): raise HTTPException(401,"invalid bearer token")
    now=time.monotonic(); bucket=_rate.setdefault(token[-12:],deque())
    while bucket and now-bucket[0]>=60: bucket.popleft()
    if len(bucket)>=settings.rate_limit_per_minute: raise HTTPException(429,"rate limit exceeded")
    bucket.append(now)
    return token

def require_auth(authorization:str|None=Header(default=None)): return _auth(authorization)

@app.get("/health")
def health():
    return {"ok":True,"service":"ai-forge-core","version":app.version,"mock_mode":settings.mock_mode,"free_mode":settings.free_mode,"mode":"MOCK" if settings.mock_mode else ("FREE" if settings.free_mode else "PROVIDER"),"configured_agents":len(providers) if not settings.mock_mode else 5}

@app.get("/v1/status",dependencies=[Depends(require_auth)])
def status():
    configured={p.cfg.name:True for p in providers}
    if settings.mock_mode: configured={k:True for k in ("GPT","Gemini","DeepSeek","Grok","Mistral")}
    return {"service":"ai-forge-core","version":app.version,"configured_agents":configured,"min_required_agents":settings.min_agents,"min_consensus":settings.min_consensus,"free_mode":settings.free_mode,"luna_mode":"FREE" if settings.free_mode else "PROVIDER"}

@app.post("/v1/council",response_model=CouncilDecision,dependencies=[Depends(require_auth)])
def run_council(payload:CouncilRequest):
    if settings.mock_mode or (settings.free_mode and not providers):
        return council.verify(mock_decisions(payload.task),5,payload.require_all)
    if not providers: raise HTTPException(503,"no AI providers configured")
    return council.verify([p.decide(payload.task,payload.context) for p in providers],len(providers),payload.require_all)

@app.post("/v1/luna",response_model=LunaResponse,dependencies=[Depends(require_auth)])
def run_luna(payload:LunaRequest):
    try:
        answer, mode, provider, model = luna.respond(payload.history, payload.message, payload.context)
        return LunaResponse(answer=answer, mode=mode, provider=provider, model=model)
    except RuntimeError as exc:
        raise HTTPException(503,str(exc)) from exc

@app.exception_handler(Exception)
async def unhandled_error(request:Request,exc:Exception):
    return JSONResponse(500,{"error":"internal server error","request_id":getattr(request.state,"request_id","-")})
