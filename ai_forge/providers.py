from __future__ import annotations
import json, time
from abc import ABC, abstractmethod
import requests
from .config import ProviderConfig
from .models import AgentDecision
from .prompts import LUNA_SYSTEM_PROMPT, SYSTEM_PROMPT, build_user_prompt

class ProviderError(RuntimeError):
    pass

class Provider(ABC):
    def __init__(self, cfg: ProviderConfig, timeout: float):
        self.cfg, self.timeout = cfg, timeout

    @abstractmethod
    def _request(self, task, context):
        raise NotImplementedError

    @abstractmethod
    def chat(self, history, message):
        raise NotImplementedError
    def decide(self, task, context):
        started = time.perf_counter()
        try:
            parsed = _parse_decision(self._request(task, context))
            return AgentDecision(provider=self.cfg.name, model=self.cfg.model, latency_ms=max(0, int((time.perf_counter()-started)*1000)), **parsed)
        except Exception as exc:
            return AgentDecision(provider=self.cfg.name, model=self.cfg.model, decision="ESCALATE", confidence=0.0, rationale="Provider unavailable or invalid.", risk_flags=["provider_error"], latency_ms=max(0, int((time.perf_counter()-started)*1000)), ok=False, error=_safe_error(exc))

class OpenAIProvider(Provider):
    def chat(self, history, message):
        messages = [{"role": "system", "content": LUNA_SYSTEM_PROMPT}]
        messages.extend({"role": item["role"], "content": item["content"]} for item in history)
        messages.append({"role": "user", "content": message})
        data = _post_json(f"{self.cfg.base_url}/v1/responses", {"Authorization": f"Bearer {self.cfg.api_key}"}, {
            "model": self.cfg.model, "store": False, "input": messages, "max_output_tokens": 1200
        }, self.timeout)
        if isinstance(data.get("output_text"), str) and data["output_text"].strip():
            return data["output_text"].strip()
        out=[]
        for item in data.get("output",[]):
            for content in item.get("content",[]) if isinstance(item,dict) else []:
                if isinstance(content,dict) and isinstance(content.get("text"),str):
                    out.append(content["text"])
        answer="\n".join(out).strip()
        if not answer:
            raise ProviderError("no text in response")
        return answer

    def _request(self, task, context):
        data = _post_json(f"{self.cfg.base_url}/v1/responses", {"Authorization": f"Bearer {self.cfg.api_key}"}, {
            "model": self.cfg.model, "store": False,
            "input": [
                {"role":"system","content":SYSTEM_PROMPT},
                {"role":"user","content":build_user_prompt(task,context)}
            ]}, self.timeout)
        if isinstance(data.get("output_text"), str) and data["output_text"].strip():
            return data["output_text"]
        out=[]
        for item in data.get("output",[]):
            for content in item.get("content",[]) if isinstance(item,dict) else []:
                if isinstance(content,dict) and isinstance(content.get("text"),str): out.append(content["text"])
        return "\n".join(out)

class ChatCompletionsProvider(Provider):
    def chat(self, history, message):
        messages = [{"role": "system", "content": LUNA_SYSTEM_PROMPT}]
        messages.extend({"role": item["role"], "content": item["content"]} for item in history)
        messages.append({"role": "user", "content": message})
        data = _post_json(f"{self.cfg.base_url}/v1/chat/completions", {"Authorization": f"Bearer {self.cfg.api_key}"}, {
            "model": self.cfg.model, "messages": messages, "temperature": 0.2, "max_tokens": 1200
        }, self.timeout)
        content=data.get("choices",[{}])[0].get("message",{}).get("content")
        if isinstance(content,str) and content.strip():
            return content.strip()
        if isinstance(content,list):
            answer="\n".join(p.get("text","") for p in content if isinstance(p,dict) and isinstance(p.get("text"),str)).strip()
            if answer:
                return answer
        raise ProviderError("no text in response")

    def _request(self, task, context):
        data = _post_json(f"{self.cfg.base_url}/v1/chat/completions", {"Authorization": f"Bearer {self.cfg.api_key}"}, {
            "model":self.cfg.model,
            "messages":[{"role":"system","content":SYSTEM_PROMPT},{"role":"user","content":build_user_prompt(task,context)}],
            "temperature":0.1,"max_tokens":1200}, self.timeout)
        content=data.get("choices",[{}])[0].get("message",{}).get("content")
        if isinstance(content,str): return content
        if isinstance(content,list): return "\n".join(p.get("text","") for p in content if isinstance(p,dict) and isinstance(p.get("text"),str))
        raise ProviderError("no text in response")

class GeminiProvider(Provider):
    def chat(self, history, message):
        contents=[]
        for item in history:
            role = "model" if item["role"] == "assistant" else "user"
            contents.append({"role": role, "parts": [{"text": item["content"]}]})
        contents.append({"role": "user", "parts": [{"text": message}]})
        data = _post_json(f"{self.cfg.base_url}/v1beta/models/{self.cfg.model}:generateContent", {"x-goog-api-key": self.cfg.api_key, "x-goog-api-client": "williams-ai-forge/0.2.0"}, {
            "systemInstruction":{"parts":[{"text":LUNA_SYSTEM_PROMPT}]},
            "contents":contents,
            "generationConfig":{"temperature":0.2,"maxOutputTokens":1200}
        }, self.timeout)
        parts=data.get("candidates",[{}])[0].get("content",{}).get("parts",[])
        answer="\n".join(p.get("text","") for p in parts if isinstance(p,dict) and isinstance(p.get("text"),str)).strip()
        if not answer:
            raise ProviderError("no text in response")
        return answer

    def _request(self, task, context):
        data = _post_json(f"{self.cfg.base_url}/v1beta/models/{self.cfg.model}:generateContent", {"x-goog-api-key": self.cfg.api_key, "x-goog-api-client": "williams-ai-forge/0.1.0"}, {
            "systemInstruction":{"parts":[{"text":SYSTEM_PROMPT}]},
            "contents":[{"role":"user","parts":[{"text":build_user_prompt(task,context)}]}],
            "generationConfig":{"temperature":0.1,"maxOutputTokens":1200,"responseMimeType":"application/json"}
        }, self.timeout)
        parts=data.get("candidates",[{}])[0].get("content",{}).get("parts",[])
        text="\n".join(p.get("text","") for p in parts if isinstance(p,dict) and isinstance(p.get("text"),str))
        if not text: raise ProviderError("no text in response")
        return text

def create_providers(configs, timeout):
    out=[]
    for cfg in configs:
        if not cfg.configured: continue
        if cfg.name in {"GPT","Grok"}: out.append(OpenAIProvider(cfg,timeout))
        elif cfg.name=="Gemini": out.append(GeminiProvider(cfg,timeout))
        else: out.append(ChatCompletionsProvider(cfg,timeout))
    return out

def _post_json(url, headers, payload, timeout, query=None):
    try:
        r=requests.post(url, headers={**headers,"Content-Type":"application/json"}, params=query, json=payload, timeout=timeout)
        if not r.ok: raise ProviderError(f"HTTP {r.status_code}")
        data=r.json()
    except requests.RequestException as exc:
        raise ProviderError("network failure") from exc
    except ValueError as exc:
        raise ProviderError("invalid JSON") from exc
    if not isinstance(data,dict): raise ProviderError("response was not an object")
    return data

def _parse_decision(raw):
    raw=raw.strip()
    if not raw: raise ProviderError("empty response")
    candidates=[raw]
    if "```" in raw: candidates.append(raw.replace("```json","").replace("```","").strip())
    obj=None
    for candidate in candidates:
        try:
            value=json.loads(candidate)
            if isinstance(value,dict): obj=value; break
        except json.JSONDecodeError: pass
    if obj is None:
        start,end=raw.find("{"),raw.rfind("}")
        if start>=0 and end>start:
            try:
                value=json.loads(raw[start:end+1])
                if isinstance(value,dict): obj=value
            except json.JSONDecodeError: pass
    if obj is None: raise ProviderError("invalid council JSON")
    decision=str(obj.get("decision","ESCALATE")).upper()
    if decision not in {"PROCEED","HOLD","REJECT","ESCALATE"}: decision="ESCALATE"
    confidence=max(0.0,min(1.0,float(obj.get("confidence",0.0))))
    flags=obj.get("risk_flags",[])
    if not isinstance(flags,list): flags=[str(flags)]
    return {"decision":decision,"confidence":confidence,"rationale":str(obj.get("rationale","No rationale provided."))[:4000],"risk_flags":[str(x)[:300] for x in flags[:20]]}

def _safe_error(exc):
    return (str(exc).replace("\n"," ").strip() or exc.__class__.__name__)[:300]
