"""Deterministic Williams evidence-chain record.

DecisionTrace deliberately records *why* an order was admitted instead of
compressing the system into a Boolean indicator score.
"""
from __future__ import annotations

from dataclasses import dataclass, field, asdict
import hashlib
import json
import time
from typing import Any

from campaign_model import SignalSpec


STAGES = (
    "BEHAVIOR",
    "CONTEXT",
    "STRUCTURE",
    "LOCATION",
    "MOMENTUM",
    "PRICE_PROOF",
    "ENTRY",
    "ADD",
    "CAMPAIGN",
    "EXHAUSTION",
    "EXIT",
)


@dataclass
class DecisionTrace:
    trace_id: str
    symbol: str
    timeframe: str
    signal_id: str
    created_at_ms: int
    stages: dict[str, dict[str, Any]] = field(default_factory=dict)
    vetoes: list[str] = field(default_factory=list)
    final_decision: str = "WAIT"

    @classmethod
    def from_signal(cls, signal: SignalSpec, *, extras: dict[str, Any] | None = None) -> "DecisionTrace":
        extras = dict(extras or {})
        now = int(time.time() * 1000)
        trace_id = hashlib.sha256(
            f"{signal.signal_id}:{now}:{signal.trigger_price}".encode()
        ).hexdigest()[:24]

        trace = cls(
            trace_id=trace_id,
            symbol=signal.symbol,
            timeframe=signal.timeframe,
            signal_id=signal.signal_id,
            created_at_ms=now,
        )
        trace.record(
            "BEHAVIOR",
            detected=True,
            signal_type=signal.signal_type.value,
            reason=signal.reason,
        )
        trace.record(
            "CONTEXT",
            alligator_bullish=signal.alligator_bullish,
            alligator_awake=signal.alligator_awake,
            htf_confirmed=signal.htf_confirmed,
        )
        trace.record(
            "STRUCTURE",
            signal_type=signal.signal_type.value,
            source_candle_index=signal.source_candle_index,
        )
        trace.record(
            "LOCATION",
            teeth_at_detection=signal.teeth_at_detection,
        )
        trace.record(
            "MOMENTUM",
            angulation_score=signal.angulation_score,
            wave_confidence=signal.wave_confidence,
            wave_exhaustion_risk=signal.wave_exhaustion_risk,
        )
        trace.record(
            "PRICE_PROOF",
            trigger_price=signal.trigger_price,
            protective_reference=signal.protective_reference,
            invalidation_price=signal.invalidation_price,
        )
        for stage, payload in extras.items():
            if stage in STAGES and isinstance(payload, dict):
                trace.record(stage, **payload)
        trace.final_decision = "ARMED" if signal.role.value == "ENTRY" else "ADD_ARMED"
        return trace

    def record(self, stage: str, **evidence: Any) -> None:
        key = str(stage).upper()
        if key not in STAGES:
            raise ValueError(f"unknown DecisionTrace stage: {stage}")
        self.stages[key] = dict(evidence)

    def veto(self, reason: str) -> None:
        self.vetoes.append(str(reason))
        self.final_decision = "BLOCKED"

    def to_dict(self) -> dict[str, Any]:
        data = asdict(self)
        data["stage_order"] = list(STAGES)
        data["trace_hash"] = hashlib.sha256(
            json.dumps(data, sort_keys=True, separators=(",", ":")).encode()
        ).hexdigest()
        return data
