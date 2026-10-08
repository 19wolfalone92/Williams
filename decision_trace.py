"""Durable, JSON-safe DecisionTrace for the Williams decision boundary."""
from __future__ import annotations

from dataclasses import asdict, dataclass, field
import json
import time
from typing import Any


@dataclass
class DecisionTrace:
    symbol: str
    decision_time_ms: int
    macro_tf: str = "1d"
    context_tf: str = "4h"
    decision_tf: str = "1h"
    execution_tf: str = "15m"
    micro_tf: str = "5m"

    h4_context: str = "UNKNOWN"
    alligator_state: str = "UNKNOWN"
    wm1: str = "UNKNOWN"
    wm2: str = "UNKNOWN"
    wm3: str = "UNKNOWN"
    fractal: str = "UNKNOWN"
    angulation: str = "UNKNOWN"
    momentum_relation: str = "UNKNOWN"

    trigger_price: float = 0.0
    initial_stop: float = 0.0
    campaign_id: str = ""
    campaign_step: int = 0

    williams_valid: bool = False
    risk_allowed: bool = False
    economic_gate: bool = False
    trade_allowed: bool = False
    block_reason: str = ""
    signal_family: str = ""
    extra: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)

    def to_json(self) -> str:
        return json.dumps(self.to_dict(), sort_keys=True, default=str)

    @classmethod
    def now(cls, symbol: str, **kwargs: Any) -> "DecisionTrace":
        return cls(symbol=symbol.upper(), decision_time_ms=int(time.time() * 1000), **kwargs)

    def block(self, reason: str) -> None:
        self.trade_allowed = False
        self.block_reason = str(reason)

    def admit(self) -> None:
        if not self.williams_valid:
            self.block("WILLIAMS_CORE_INVALID")
            return
        if not self.risk_allowed:
            self.block("RISK_GATE_BLOCKED")
            return
        if not self.economic_gate:
            self.block("BLOCKED_BY_EXECUTION_ECONOMICS")
            return
        self.trade_allowed = True
        self.block_reason = ""
