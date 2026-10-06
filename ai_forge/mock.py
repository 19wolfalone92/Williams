from __future__ import annotations
import hashlib
from .models import AgentDecision

def mock_decisions(task):
    seed=int(hashlib.sha256(task.encode()).hexdigest()[:8],16)
    pattern=["PROCEED","PROCEED","PROCEED","HOLD","PROCEED"] if seed%5 else ["HOLD","HOLD","PROCEED","HOLD","REJECT"]
    names=[("GPT","mock-gpt"),("Gemini","mock-gemini"),("DeepSeek","mock-deepseek"),("Grok","mock-grok"),("Mistral","mock-mistral")]
    return [AgentDecision(provider=n,model=m,decision=d,confidence=0.82 if d=="PROCEED" else 0.68,rationale="Deterministic CI mock response; no external model was contacted.",risk_flags=["mock_mode"],latency_ms=1) for (n,m),d in zip(names,pattern,strict=True)]
