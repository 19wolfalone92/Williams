"""Immutable, copy-on-write market context and wave hypotheses."""
from __future__ import annotations

import math
import threading
import time
from dataclasses import asdict, dataclass, field
from types import MappingProxyType
from typing import Mapping, Optional


@dataclass(frozen=True)
class WaveHypothesis:
    hypothesis_id: str
    label: str
    direction: str
    probability: float
    secondary_probability: float = 0.0
    invalidation_level: float = 0.0
    target_level: float = 0.0
    confidence: float = 0.0
    exhaustion_risk: float = 0.0
    source: str = "rule_based"

    def __post_init__(self) -> None:
        if not 0.0 <= self.probability <= 1.0:
            raise ValueError("probability must be in [0, 1]")
        if not 0.0 <= self.secondary_probability <= 1.0:
            raise ValueError("secondary_probability must be in [0, 1]")

    @property
    def margin(self) -> float:
        return max(0.0, self.probability - self.secondary_probability)

    @property
    def entropy(self) -> float:
        # Normalized binary entropy. This is a confidence/uncertainty feature,
        # not a statistically calibrated probability claim.
        p = min(1.0, max(0.0, self.probability))
        q = 1.0 - p
        if p <= 0.0 or q <= 0.0:
            return 0.0
        return -(p * math.log(p) + q * math.log(q)) / math.log(2.0)


@dataclass(frozen=True)
class TFMarketContext:
    symbol: str
    interval: str
    version: int
    candle_open_time_ms: int
    candle_close_time_ms: int
    price: float
    atr: float = 0.0
    jaw: float = 0.0
    teeth: float = 0.0
    lips: float = 0.0
    jaw_slope_atr: float = 0.0
    price_slope_atr: float = 0.0
    angulation: float = 0.0
    jaw_distance_atr: float = 0.0
    alligator_state: str = "UNKNOWN"
    wave_label: str = "?"
    wave_phase: str = "UNKNOWN"
    wave_score: float = 0.0
    exhaustion_risk: float = 0.0
    wave_confidence: float = 0.0
    invalidation_long: float = 0.0
    invalidation_short: float = 0.0
    allow_long: bool = False
    allow_short: bool = False
    decision: str = "NO_TRADE"
    long_probability: float = 0.0
    short_probability: float = 0.0
    no_trade_probability: float = 1.0
    calibration_status: str = "UNCALIBRATED"
    operative_interval: str = ""
    operative_parent_interval: str = ""
    hypotheses: tuple[WaveHypothesis, ...] = field(default_factory=tuple)
    data_bars: int = 0
    live_only: bool = False

    def to_dict(self) -> dict:
        return asdict(self)


@dataclass(frozen=True)
class MarketStateSnapshot:
    generation: int
    created_at_ms: int
    by_symbol: Mapping[str, Mapping[str, TFMarketContext]]

    def __post_init__(self) -> None:
        frozen_symbols = {}
        for symbol, frames in self.by_symbol.items():
            frozen_symbols[str(symbol).upper()] = MappingProxyType(dict(frames))
        object.__setattr__(self, "by_symbol", MappingProxyType(frozen_symbols))

    def context(self, symbol: str, interval: str) -> Optional[TFMarketContext]:
        return self.by_symbol.get(symbol.upper(), {}).get(interval.lower())

    def versions(self, symbol: str, intervals: list[str] | tuple[str, ...]) -> dict[str, int]:
        return {
            tf.lower(): int(ctx.version)
            for tf in intervals
            if (ctx := self.context(symbol, tf)) is not None
        }


class ContextCache:
    """Atomic reference swap / copy-on-write context store.

    The same re-entrant execution lock is used by publication and the final
    execution barrier. That prevents a critical context publication from
    occurring between final intent validation and the Binance POST.
    """

    def __init__(self) -> None:
        self._current_market_snapshot = MarketStateSnapshot(
            generation=0,
            created_at_ms=int(time.time() * 1000),
            by_symbol={},
        )
        self._lock = threading.RLock()
        self.execution_lock = self._lock

    def snapshot(self) -> MarketStateSnapshot:
        with self._lock:
            return self._current_market_snapshot

    def publish(self, context: TFMarketContext) -> MarketStateSnapshot:
        with self._lock:
            current = self._current_market_snapshot
            symbol = context.symbol.upper()
            tf = context.interval.lower()
            old_frames = dict(current.by_symbol.get(symbol, {}))
            old = old_frames.get(tf)
            next_version = 1 if old is None else old.version + 1
            if context.version != next_version:
                context = TFMarketContext(
                    **{
                        **context.__dict__,
                        "version": next_version,
                    }
                )
            old_frames[tf] = context
            symbols = {
                key: dict(frames)
                for key, frames in current.by_symbol.items()
            }
            symbols[symbol] = old_frames
            self._current_market_snapshot = MarketStateSnapshot(
                generation=current.generation + 1,
                created_at_ms=int(time.time() * 1000),
                by_symbol=symbols,
            )
            return self._current_market_snapshot
