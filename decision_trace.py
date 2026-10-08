"""Append-only decision trace records for explainable execution."""
from __future__ import annotations

from dataclasses import dataclass, field, asdict
import time
import uuid
from typing import Any, Mapping


@dataclass(frozen=True)
class DecisionTrace:
    intent_id: str
    stage: str
    decision: str
    symbol: str = ""
    purpose: str = ""
    campaign_id: str = ""
    signal_id: str = ""
    reason: str = ""
    blocker: str = ""
    context_versions: Mapping[str, int] = field(default_factory=dict)
    payload: Mapping[str, Any] = field(default_factory=dict)
    macro_tf: str = "1d"
    context_tf: str = "4h"
    decision_tf: str = "1h"
    execution_tf: str = "15m"
    micro_tf: str = "5m"
    core_valid: bool = False
    trade_allowed: bool = False
    block_reason: str = ""
    trace_id: str = field(default_factory=lambda: uuid.uuid4().hex)
    created_at_ms: int = field(default_factory=lambda: int(time.time() * 1000))

    @classmethod
    def from_strategy_decision(
        cls,
        decision: Any,
        *,
        intent_id: str = "",
        stage: str = "STRATEGY",
        purpose: str = "",
        reason: str = "",
    ) -> "DecisionTrace":
        data = decision.to_dict() if hasattr(decision, "to_dict") else dict(decision)
        return cls(
            intent_id=intent_id,
            stage=stage,
            decision=str(data.get("signal_type", "NONE")),
            symbol=str(data.get("symbol", "")),
            purpose=purpose,
            campaign_id=str(data.get("campaign_id", "")),
            signal_id=str(data.get("trace_id", "")),
            reason=reason,
            blocker=str(data.get("block_reason", "")),
            context_versions=dict(data.get("context_versions", {}) or {}),
            payload=data,
            core_valid=bool(data.get("core_valid", False)),
            trade_allowed=bool(data.get("trade_allowed", False)),
            block_reason=str(data.get("block_reason", "")),
        )

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)
