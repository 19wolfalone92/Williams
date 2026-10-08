"""Canonical Digital Williams orchestration contract.

This module does not replace the existing strategy/execution implementations.
It provides one deterministic composition boundary and one vocabulary for the
decision trace, pending signal and Binance data contract.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Iterable

from binance_data_contract import BinanceDataContract
from decision_trace import DecisionTrace
from pending_signal import PendingSignal, should_replace
from campaign_model import SignalSpec


@dataclass(frozen=True)
class WilliamsDecision:
    signal: SignalSpec | None
    pending: PendingSignal | None
    trace: DecisionTrace | None
    action: str
    vetoes: tuple[str, ...]

    def to_dict(self) -> dict[str, Any]:
        return {
            "action": self.action,
            "vetoes": list(self.vetoes),
            "signal": self.signal.to_dict() if self.signal else None,
            "pending_signal": self.pending.to_dict() if self.pending else None,
            "decision_trace": self.trace.to_dict() if self.trace else None,
        }


class DigitalWilliamsCore:
    """Single composition point for analysis, proof and campaign intent."""

    VERSION = "1.0.0"

    def __init__(self) -> None:
        self.binance = BinanceDataContract()

    def select_initial(self, signals: Iterable[SignalSpec]) -> SignalSpec | None:
        candidates = [
            s for s in signals
            if s.side == "BUY"
            and s.role.value == "ENTRY"
            and s.trigger_price > 0
        ]
        return min(
            candidates,
            key=lambda s: (s.signal_bar_time_ms, s.created_at_ms),
            default=None,
        )

    def compose(
        self,
        signals: Iterable[SignalSpec],
        *,
        campaign_id: str = "",
        now_ms: int | None = None,
    ) -> WilliamsDecision:
        signal = self.select_initial(signals)
        if signal is None:
            return WilliamsDecision(None, None, None, "WAIT", ())
        pending = PendingSignal.from_spec(signal, campaign_id=campaign_id)
        if not pending.actionable(now_ms):
            veto = "pending_signal_expired_or_invalid"
            trace = DecisionTrace.from_signal(signal)
            trace.veto(veto)
            return WilliamsDecision(signal, pending, trace, "BLOCK", (veto,))
        trace = DecisionTrace.from_signal(signal)
        return WilliamsDecision(signal, pending, trace, "ARM_ENTRY", ())

    def replace_pending(
        self,
        current: PendingSignal,
        candidate: SignalSpec,
        *,
        min_price_delta: float = 0.0,
        campaign_id: str = "",
    ) -> PendingSignal | None:
        replacement = PendingSignal.from_spec(
            candidate,
            campaign_id=campaign_id or current.campaign_id,
            replacement_of=current.signal_id,
        )
        return replacement if should_replace(current, replacement, min_price_delta) else None

    def contract(self) -> dict[str, Any]:
        return {
            "core_version": self.VERSION,
            "sequence": [
                "BEHAVIOR", "CONTEXT", "STRUCTURE", "LOCATION",
                "MOMENTUM", "PRICE_PROOF", "ENTRY", "ADD",
                "CAMPAIGN", "EXHAUSTION", "EXIT",
            ],
            "binance_contract": self.binance.to_dict(),
            "execution_contract": {
                "initial_entry": "BUY_STOP conditional; never convert missed trigger to MARKET",
                "add_on": "BUY_STOP conditional; same campaign",
                "hard_protection": "SELL STOP exchange-side",
                "trailing": "structural stop only moves to reduce risk",
                "exit": "cancel protection -> MARKET SELL -> authoritative fill -> reconcile residual",
                "fixed_take_profit": False,
                "ambiguity": "RECONCILE_REQUIRED; no blind replay",
            },
            "self_healing": {
                "safe_only": True,
                "max_attempts": 3,
                "never": [
                    "invent a signal",
                    "blindly replay ambiguous order mutation",
                    "loosen protective stop",
                    "bypass reconciliation",
                ],
            },
        }
