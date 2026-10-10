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

    @staticmethod
    def _eligible_initial(signals: Iterable[SignalSpec]) -> list[SignalSpec]:
        # Materialize once: callers may pass a generator rather than a list.
        return [
            signal for signal in signals
            if str(signal.direction).upper() in {"LONG", "SHORT"}
            and signal.role.value == "ENTRY"
        ]

    def select_initial(
        self,
        signals: Iterable[SignalSpec],
        *,
        now_ms: int | None = None,
    ) -> SignalSpec | None:
        candidates = self._eligible_initial(signals)
        actionable = [
            signal for signal in candidates
            if PendingSignal.from_spec(signal).actionable(now_ms)
        ]
        # An expired/invalid older setup must not hide a later live setup.
        return min(
            actionable,
            key=lambda signal: (
                int(getattr(signal, "confirmation_time_ms", 0) or signal.signal_bar_time_ms),
                signal.created_at_ms,
            ),
            default=None,
        )

    def compose(
        self,
        signals: Iterable[SignalSpec],
        *,
        campaign_id: str = "",
        now_ms: int | None = None,
    ) -> WilliamsDecision:
        candidates = self._eligible_initial(signals)
        signal = self.select_initial(candidates, now_ms=now_ms)
        if signal is None:
            # Preserve a diagnostic BLOCK when candidate setups exist but all
            # are expired/invalid; distinguish that from a genuine no-signal WAIT.
            invalid = min(
                candidates,
                key=lambda item: (item.signal_bar_time_ms, item.created_at_ms),
                default=None,
            )
            if invalid is None:
                return WilliamsDecision(None, None, None, "WAIT", ())
            pending = PendingSignal.from_spec(invalid, campaign_id=campaign_id)
            trace = DecisionTrace.from_signal(invalid)
            veto = "pending_signal_expired_or_invalid"
            trace.veto(veto)
            return WilliamsDecision(invalid, pending, trace, "BLOCK", (veto,))
        pending = PendingSignal.from_spec(signal, campaign_id=campaign_id)
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
                "initial_entry": "directional conditional entry; never convert missed trigger to MARKET",
                "add_on": "directional conditional entry; same campaign and side",
                "hard_protection": "directional Futures stop; LONG=SELL, SHORT=BUY",
                "trailing": "structural stop only moves to reduce risk",
                "exit": "keep exchange-side protection live while resolving opposite-side reduce-only MARKET; after flat, reconcile/cancel protection, reconcile all fills/fees, then finalize",
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
