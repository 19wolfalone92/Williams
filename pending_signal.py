"""Durable domain wrapper for a signal waiting to become an order."""
from __future__ import annotations

from dataclasses import dataclass, field
import time
from typing import Any, Mapping

from campaign_model import SignalRole, SignalSpec, SignalState, SignalType


@dataclass(frozen=True)
class PendingSignal:
    signal_id: str
    symbol: str
    side: str
    signal_type: SignalType
    role: SignalRole
    timeframe: str
    trigger_price: float
    protective_reference: float
    signal_bar_time_ms: int
    state: SignalState = SignalState.DETECTED
    context_versions: Mapping[str, int] = field(default_factory=dict)
    created_at_ms: int = field(default_factory=lambda: int(time.time() * 1000))
    armed_at_ms: int = 0
    expires_at_ms: int = 0
    supersedes_signal_id: str = ""

    @classmethod
    def from_spec(
        cls,
        signal: SignalSpec,
        *,
        state: SignalState = SignalState.DETECTED,
        supersedes_signal_id: str = "",
    ) -> "PendingSignal":
        return cls(
            signal_id=signal.signal_id,
            symbol=signal.symbol,
            side=signal.side,
            signal_type=signal.signal_type,
            role=signal.role,
            timeframe=signal.timeframe,
            trigger_price=signal.trigger_price,
            protective_reference=signal.protective_reference,
            signal_bar_time_ms=signal.signal_bar_time_ms,
            state=state,
            context_versions=dict(signal.context_versions),
            created_at_ms=signal.created_at_ms,
            armed_at_ms=int(time.time() * 1000) if state == SignalState.ARMED else 0,
            expires_at_ms=signal.expires_at_ms,
            supersedes_signal_id=supersedes_signal_id,
        )

    def transition(self, state: SignalState) -> "PendingSignal":
        allowed = {
            SignalState.DETECTED: {SignalState.ARMED, SignalState.INVALIDATED, SignalState.EXPIRED, SignalState.SUPERSEDED},
            SignalState.ARMED: {SignalState.TRIGGERED, SignalState.INVALIDATED, SignalState.EXPIRED, SignalState.REPLACEMENT_REQUESTED, SignalState.SUPERSEDED},
            SignalState.TRIGGERED: {SignalState.FILLED, SignalState.CANCEL_REQUESTED, SignalState.INVALIDATED},
            SignalState.CANCEL_REQUESTED: {SignalState.CANCELLED, SignalState.TRIGGERED, SignalState.FILLED},
            SignalState.REPLACEMENT_REQUESTED: {SignalState.REPLACED, SignalState.INVALIDATED},
            SignalState.REPLACED: {SignalState.ARMED, SignalState.TRIGGERED},
            SignalState.FILLED: set(),
            SignalState.INVALIDATED: set(),
            SignalState.EXPIRED: set(),
            SignalState.CANCELLED: set(),
            SignalState.SUPERSEDED: set(),
        }
        if state != self.state and state not in allowed.get(self.state, set()):
            raise ValueError(f"Invalid pending-signal transition {self.state.value} -> {state.value}")
        return PendingSignal(
            signal_id=self.signal_id,
            symbol=self.symbol,
            side=self.side,
            signal_type=self.signal_type,
            role=self.role,
            timeframe=self.timeframe,
            trigger_price=self.trigger_price,
            protective_reference=self.protective_reference,
            signal_bar_time_ms=self.signal_bar_time_ms,
            state=state,
            context_versions=dict(self.context_versions),
            created_at_ms=self.created_at_ms,
            armed_at_ms=self.armed_at_ms or (int(time.time() * 1000) if state == SignalState.ARMED else 0),
            expires_at_ms=self.expires_at_ms,
            supersedes_signal_id=self.supersedes_signal_id,
        )

    def to_dict(self) -> dict[str, Any]:
        data = {
            "signal_id": self.signal_id,
            "symbol": self.symbol,
            "side": self.side,
            "signal_type": self.signal_type.value,
            "role": self.role.value,
            "timeframe": self.timeframe,
            "trigger_price": self.trigger_price,
            "protective_reference": self.protective_reference,
            "signal_bar_time_ms": self.signal_bar_time_ms,
            "state": self.state.value,
            "context_versions": dict(self.context_versions),
            "created_at_ms": self.created_at_ms,
            "armed_at_ms": self.armed_at_ms,
            "expires_at_ms": self.expires_at_ms,
            "supersedes_signal_id": self.supersedes_signal_id,
        }
        return data


    def supersede(self, new_signal: SignalSpec) -> "PendingSignal":
        return PendingSignal(
            signal_id=self.signal_id,
            symbol=self.symbol,
            side=self.side,
            signal_type=self.signal_type,
            role=self.role,
            timeframe=self.timeframe,
            trigger_price=self.trigger_price,
            protective_reference=self.protective_reference,
            signal_bar_time_ms=self.signal_bar_time_ms,
            state=SignalState.SUPERSEDED,
            context_versions=dict(self.context_versions),
            created_at_ms=self.created_at_ms,
            armed_at_ms=self.armed_at_ms,
            expires_at_ms=self.expires_at_ms,
            supersedes_signal_id=str(new_signal.signal_id),
        )
