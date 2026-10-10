"""Durable PendingSignal semantics for Williams conditional entries.

A PendingSignal is the bridge between a detected Williams structure and an
exchange conditional order.  It carries expiry/replacement semantics so the
bot cannot keep a stale signal armed forever.
"""
from __future__ import annotations

from dataclasses import dataclass, asdict
import math
import time

from campaign_model import SignalSpec, SignalState


def default_expiry_ms(signal: SignalSpec) -> int:
    """Return only the source signal's declared expiry.

    Missing expiry is a contract failure, not a request to invent a new
    deadline. Strategy detectors set expiry from the signal/confirmation
    candle; all entry selectors and execution adapters must fail closed when
    that explicit timestamp is absent or invalid.
    """
    try:
        return int(signal.expires_at_ms)
    except (TypeError, ValueError, OverflowError):
        return 0


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
    state: str = SignalState.DETECTED.value
    replacement_of: str = ""
    # Appended after legacy defaulted fields to preserve positional compatibility.
    # For a fractal, actionable confirmation can occur well after the center bar.
    confirmation_time_ms: int = 0

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
        now = int(now_ms if now_ms is not None else time.time() * 1000)
        try:
            source_time = int(self.signal_bar_time_ms)
            confirmation_time = int(self.confirmation_time_ms or source_time)
            expiry = int(self.expires_at_ms)
            trigger = float(self.trigger_price)
            protective = float(self.protective_reference)
        except (TypeError, ValueError, OverflowError):
            return False
        return (
            self.state in {
                SignalState.DETECTED.value,
                SignalState.ARMED.value,
            }
            and source_time > 0
            and confirmation_time >= source_time
            and expiry > confirmation_time
            and expiry > 0
            and now < expiry
            and math.isfinite(trigger)
            and trigger > 0.0
            and math.isfinite(protective)
            and protective > 0.0
        )

    def to_dict(self) -> dict:
        return asdict(self)


def should_replace(
    old: PendingSignal,
    new: PendingSignal,
    min_price_delta: float = 0.0,
    *,
    now_ms: int | None = None,
) -> bool:
    if old.signal_id == new.signal_id or not new.actionable(now_ms):
        return False
    if old.symbol != new.symbol or old.side != new.side:
        return True
    if old.is_expired(now_ms):
        return True
    old_actionable_time = int(old.confirmation_time_ms or old.signal_bar_time_ms)
    new_actionable_time = int(new.confirmation_time_ms or new.signal_bar_time_ms)
    try:
        delta = float(min_price_delta)
        price_distance = abs(float(new.trigger_price) - float(old.trigger_price))
    except (TypeError, ValueError, OverflowError):
        return False
    if not math.isfinite(delta) or not math.isfinite(price_distance):
        return False
    return (
        new_actionable_time > old_actionable_time
        and price_distance >= max(0.0, delta)
    )
