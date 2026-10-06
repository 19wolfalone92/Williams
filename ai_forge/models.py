from __future__ import annotations
from typing import Literal
from pydantic import BaseModel, Field

Decision = Literal["PROCEED", "HOLD", "REJECT", "ESCALATE"]

class CouncilRequest(BaseModel):
    task: str = Field(min_length=3, max_length=48_000)
    context: dict[str, object] = Field(default_factory=dict)
    require_all: bool = False

class AgentDecision(BaseModel):
    provider: str
    model: str
    decision: Decision
    confidence: float = Field(ge=0.0, le=1.0)
    rationale: str = Field(min_length=1, max_length=4_000)
    risk_flags: list[str] = Field(default_factory=list, max_length=20)
    latency_ms: int = Field(ge=0)
    ok: bool = True
    error: str | None = None

class CouncilDecision(BaseModel):
    decision: Decision
    verified: bool
    consensus: float = Field(ge=0.0, le=1.0)
    successful_agents: int = Field(ge=0)
    configured_agents: int = Field(ge=0)
    min_required_agents: int = Field(ge=1)
    rationale: str
    agents: list[AgentDecision]
