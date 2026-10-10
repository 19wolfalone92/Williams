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
    # Unknown timeframes must fail closed; never silently assign the M5 TTL.
    return _TF_MS.get(str(timeframe), 0)


def _configured_ttl_bars(env_name: str, default: int) -> int:
    """Return a configured positive TTL; malformed/non-positive values fail closed."""
    raw = os.getenv(env_name)
    if raw is None:
        return default
    try:
        value = int(raw.strip())
    except (TypeError, ValueError, AttributeError):
        return 0
    return value if value > 0 else 0


def default_expiry_ms(signal: SignalSpec) -> int:
    # Respect an explicit expiry even when it is already in the past; never
    # revive an expired signal by falling back to a fresh TTL.
    if signal.expires_at_ms > 0:
        return int(signal.expires_at_ms)
    # An unknown confirmation time cannot be assigned a source-correct TTL.
    if signal.confirmation_time_ms <= 0:
        return 0

    if signal.signal_type.value == "REVERSAL":
        bars = _configured_ttl_bars("WILLIAMS_PENDING_REVERSAL_BARS", 2)
    elif signal.signal_type.value == "SUPER_AO":
        bars = _configured_ttl_bars("WILLIAMS_PENDING_SUPER_AO_BARS", 2)
    else:
        bars = _configured_ttl_bars("WILLIAMS_PENDING_FRACTAL_BARS", 8)
    if bars <= 0:
        return 0

    # An unknown timeframe has no approved duration. Return no expiry so
    # PendingSignal.actionable() fails closed instead of silently using M5.
    bar_ms = _bar_ms(signal.timeframe)
    if bar_ms <= 0:
        return 0

    # TTL begins after the signal becomes confirmed/actionable, not at the
    # historical center candle nor at processing time.
    return int(signal.confirmation_time_ms + bar_ms * bars)


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
    confirmation_time_ms: int = 0
    invalidation_price: float = 0.0
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
            confirmation_time_ms=int(signal.confirmation_time_ms or 0),
            trigger_price=float(signal.trigger_price),
            protective_reference=float(signal.protective_reference),
            invalidation_price=float(signal.invalidation_price or 0.0),
            created_at_ms=int(signal.created_at_ms),
            expires_at_ms=default_expiry_ms(signal),
            state=state.value,
            replacement_of=replacement_of,
        )

    def is_expired(self, now_ms: int | None = None) -> bool:
        now = int(now_ms if now_ms is not None else time.time() * 1000)
        # Missing expiry is not interpreted as "never expires".
        return self.expires_at_ms <= 0 or now >= self.expires_at_ms

    def actionable(self, now_ms: int | None = None) -> bool:
        return (
            self.state in {
                SignalState.DETECTED.value,
                SignalState.ARMED.value,
            }
            and self.confirmation_time_ms > 0
            and self.expires_at_ms > 0
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
    old_event_time = old.confirmation_time_ms or old.signal_bar_time_ms
    new_event_time = new.confirmation_time_ms or new.signal_bar_time_ms
    # A newer confirmed fractal supersedes the older WM3 candidate even when
    # its trigger price is near the previous one.
    if old.signal_type == "FRACTAL" and new.signal_type == "FRACTAL" and new_event_time > old_event_time:
        return True
    return (
        new_event_time > old_event_time
        and abs(new.trigger_price - old.trigger_price) >= max(0.0, min_price_delta)
    )
