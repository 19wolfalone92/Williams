"""Deterministic Williams structural stop proposal engine.

The engine proposes protection from the campaign's originating signal, current
3/5-bar structure, Alligator Teeth and wave invalidation. It never submits an
order and never permits a stop that increases LONG risk.
"""

from __future__ import annotations

from dataclasses import dataclass

from campaign_model import SignalType, structural_stop_for_long, stop_only_reduces_risk


@dataclass(frozen=True)
class StopProposal:
    price: float
    source: str
    reason: str
    risk_reducing: bool


class StopEngine:
    def __init__(self, *, buffer: float = 0.0):
        self.buffer = max(0.0, float(buffer))

    def propose_long(
        self,
        *,
        signal_type: SignalType,
        current_stop: float,
        signal_bar_low: float,
        recent_lows: list[float],
        teeth: float = 0.0,
        wave_invalidation: float = 0.0,
        current_price: float = 0.0,
    ) -> StopProposal:
        proposed, source = structural_stop_for_long(
            signal_type=signal_type,
            signal_bar_low=float(signal_bar_low),
            recent_lows=list(recent_lows),
            teeth=float(teeth),
            wave_invalidation=float(wave_invalidation),
            buffer=self.buffer,
        )
        if current_stop > 0 and not stop_only_reduces_risk("LONG", current_stop, proposed):
            return StopProposal(
                price=float(current_stop),
                source="UNCHANGED",
                reason="proposed stop would loosen LONG risk",
                risk_reducing=False,
            )
        if current_price > 0 and proposed >= current_price:
            return StopProposal(
                price=float(current_stop),
                source="UNCHANGED",
                reason="proposed stop is not below current market price",
                risk_reducing=False,
            )
        if current_stop > 0 and proposed <= current_stop:
            return StopProposal(
                price=float(current_stop),
                source="UNCHANGED",
                reason="no risk-reducing structural advance",
                risk_reducing=False,
            )
        return StopProposal(
            price=float(proposed),
            source=str(source),
            reason="structural stop advanced from Williams campaign structure",
            risk_reducing=True,
        )
