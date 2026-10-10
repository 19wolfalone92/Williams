"""Durable PendingSignal semantics for Williams conditional entries.

A PendingSignal is the bridge between a detected Williams structure and an
exchange conditional order.  It carries expiry/replacement semantics so the
bot cannot keep a stale signal armed forever.
"""
from __future__ import annotations

from dataclasses import dataclass, asdict
import os
import time

from campaign_model import SignalSpec, SignalState


_TF_MS = {
    "1m": 60_000,
    "3m": 180_000,
    "5m": 300_000,
    "15m": 900_000,
    "30m": 1_800_000,
    "1h": 3_600_000,
    "2h": 7_200_000,
    "4h": 14_400_000,
    "6h": 21_600_000,
    "8h": 28_800_000,
    "12h": 43_200_000,
    "1d": 86_400_000,
    "3d": 259_200_000,
    "1w": 604_800_000,
    "1M": 2_592_000_000,
}


def _bar_ms(timeframe: str) -> int:
    return _TF_MS.get(str(timeframe), 300_000)


def default_expiry_ms(signal: SignalSpec) -> int:
    # Preserve any explicit expiry, including one that is already in the past.
    # Recomputing from created_at would silently revive an expired signal.
    if signal.expires_at_ms != 0:
        return int(signal.expires_at_ms)

    if signal.signal_type.value == "REVERSAL":
        bars = max(1, int(os.getenv("WILLIAMS_PENDING_REVERSAL_BARS", "2")))
    elif signal.signal_type.value == "SUPER_AO":
        bars = max(1, int(os.getenv("WILLIAMS_PENDING_SUPER_AO_BARS", "2")))
    else:
        bars = max(1, int(os.getenv("WILLIAMS_PENDING_FRACTAL_BARS", "8")))

    return max(
        signal.created_at_ms + _bar_ms(signal.timeframe) * bars,
        signal.signal_bar_time_ms + _bar_ms(signal.timeframe) * bars,
    )


@dataclass(frozen=True)
class PendingSignal:
    signal_id: str
    campaign_id: str
    symbol: str
    side: str
    signal_type: str
    role: str
    timeframe: str
    signal_bar_time_ms: int
    trigger_price: float
    protective_reference: float
    created_at_ms: int
    expires_at_ms: int
    # For a fractal, actionable confirmation can occur well after the center bar.
    confirmation_time_ms: int = 0
    state: str = SignalState.DETECTED.value
    replacement_of: str = ""

    @classmethod
    def from_spec(
        cls,
        signal: SignalSpec,
        *,
        campaign_id: str = "",
        state: SignalState = SignalState.DETECTED,
        replacement_of: str = "",
    ) -> "PendingSignal":
        return cls(
            signal_id=signal.signal_id,
            campaign_id=campaign_id,
            symbol=signal.symbol,
            side=signal.side,
            signal_type=signal.signal_type.value,
            role=signal.role.value,
            timeframe=signal.timeframe,
            signal_bar_time_ms=signal.signal_bar_time_ms,
            trigger_price=float(signal.trigger_price),
            protective_reference=float(signal.protective_reference),
            created_at_ms=int(signal.created_at_ms),
            expires_at_ms=default_expiry_ms(signal),
            confirmation_time_ms=int(
                getattr(signal, "confirmation_time_ms", 0) or signal.signal_bar_time_ms
            ),
            state=state.value,
            replacement_of=replacement_of,
        )

    def is_expired(self, now_ms: int | None = None) -> bool:
        now = int(now_ms if now_ms is not None else time.time() * 1000)
        # Expiry is mandatory for an actionable exchange-side conditional.
        # Zero/negative timestamps are malformed, not "never expires".
        return self.expires_at_ms <= 0 or now >= self.expires_at_ms

    def actionable(self, now_ms: int | None = None) -> bool:
        return (
            self.state in {
                SignalState.DETECTED.value,
                SignalState.ARMED.value,
            }
            and not self.is_expired(now_ms)
            and self.trigger_price > 0
            and self.protective_reference > 0
        )

    def to_dict(self) -> dict:
        return asdict(self)


def should_replace(old: PendingSignal, new: PendingSignal, min_price_delta: float = 0.0) -> bool:
    if old.signal_id == new.signal_id:
        return False
    if old.symbol != new.symbol or old.side != new.side:
        return True
    if old.is_expired() and not new.is_expired():
        return True
    old_actionable_time = int(old.confirmation_time_ms or old.signal_bar_time_ms)
    new_actionable_time = int(new.confirmation_time_ms or new.signal_bar_time_ms)
    return (
        new_actionable_time > old_actionable_time
        and abs(new.trigger_price - old.trigger_price) >= max(0.0, min_price_delta)
    )
