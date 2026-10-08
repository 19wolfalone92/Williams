"""Single structural-stop proposal/validation engine."""
from __future__ import annotations

from dataclasses import dataclass
from typing import Sequence

from campaign_model import SignalType, stop_only_reduces_risk, structural_stop_for_long


@dataclass(frozen=True)
class StopProposal:
    price: float
    source: str
    accepted: bool
    reason: str = ""


class StopEngine:
    def propose_long(
        self,
        *,
        signal_type: SignalType,
        signal_bar_low: float,
        recent_lows: Sequence[float],
        teeth: float,
        wave_invalidation: float,
        current_stop: float,
        buffer: float,
        current_price: float,
        use_teeth: bool = False,
    ) -> StopProposal:
        proposed, source = structural_stop_for_long(
            signal_type=signal_type,
            signal_bar_low=signal_bar_low,
            recent_lows=recent_lows,
            teeth=teeth if use_teeth else 0.0,
            wave_invalidation=wave_invalidation,
            buffer=buffer,
        )
        if proposed <= 0:
            return StopProposal(0.0, source, False, "no_structural_stop")
        if not stop_only_reduces_risk("LONG", current_stop, proposed):
            return StopProposal(proposed, source, False, "stop_would_loosen_risk")
        if current_price > 0 and proposed >= current_price:
            return StopProposal(proposed, source, False, "stop_not_below_market")
        return StopProposal(proposed, source, True, "")
