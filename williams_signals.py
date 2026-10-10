"""Deterministic Williams signal extraction for the campaign engine.

The book's signal timing is intentionally separated from order execution.
This module identifies a signal bar/formation and computes the conditional
price at which the market must prove the idea.

The angulation implementation is an engineering approximation of the book's
visual rule: price must be moving away from the Alligator/Jaw more steeply
than the Alligator itself.  It is reported explicitly as an approximation and
never presented as an author-certified formula.
"""

from __future__ import annotations

import os
from dataclasses import asdict
from typing import Any
import math

import pandas as pd

from campaign_model import SignalRole, SignalSpec, SignalType


def _timeframe_ms(timeframe: str) -> int:
    values = {
        "1m": 60_000, "3m": 180_000, "5m": 300_000,
        "15m": 900_000, "30m": 1_800_000, "1h": 3_600_000,
        "2h": 7_200_000, "4h": 14_400_000, "6h": 21_600_000,
        "8h": 28_800_000, "12h": 43_200_000, "1d": 86_400_000,
        "3d": 259_200_000, "1w": 604_800_000, "1M": 2_592_000_000,
    }
    return int(values.get(str(timeframe), 300_000))

def _tick_buffer(tick_size: float, ticks: int = 1) -> float:
    return max(float(tick_size), 0.0) * max(int(ticks), 1)


def _row_time_ms(row: pd.Series) -> int:
    for key in ("open_time_ms", "time_ms", "timestamp", "time", "open_time"):
        value = row.get(key)
        if value is not None and not pd.isna(value):
            try:
                return int(value)
            except (TypeError, ValueError):
                pass
    idx = getattr(row, "name", 0)
    try:
        return int(idx.value // 1_000_000)
    except Exception:
        return int(idx) if isinstance(idx, (int, float)) else 0


def _angulation(
    ind: pd.DataFrame,
    index: int,
    *,
    window: int = 5,
    side: str | None = None,
) -> tuple[float, bool]:
    """Approximate increasing separation of price from the Alligator Jaw."""
    if index < 1 or "jaw_shifted" not in ind.columns:
        return 0.0, False

    start = max(0, index - max(3, int(window)) + 1)
    rows = ind.iloc[start:index + 1].copy()
    if len(rows) < 3 or rows["jaw_shifted"].isna().all():
        return 0.0, False

    jaw = pd.to_numeric(rows["jaw_shifted"], errors="coerce")
    low = pd.to_numeric(rows["low"], errors="coerce")
    high = pd.to_numeric(rows["high"], errors="coerce")
    close = pd.to_numeric(rows["close"], errors="coerce")

    if low.isna().any() or high.isna().any() or jaw.isna().any():
        return 0.0, False

    bullish_distance = (jaw - low).clip(lower=0.0)
    bearish_distance = (high - jaw).clip(lower=0.0)

    # A reversal has to be outside the mouth and the separation must increase.
    bull_delta = float(bullish_distance.iloc[-1] - bullish_distance.iloc[0])
    bear_delta = float(bearish_distance.iloc[-1] - bearish_distance.iloc[0])

    base = max(
        abs(float(close.iloc[-1])),
        abs(float(jaw.iloc[-1])),
        1e-9,
    )
    score = max(bull_delta, bear_delta) / base * 100.0

    # Also require positive regression slope on the separation itself.
    k = len(rows)
    x = list(range(k))

    def slope(values: pd.Series) -> float:
        y = values.to_list()
        xm = sum(x) / k
        ym = sum(y) / k
        denom = sum((v - xm) ** 2 for v in x)
        return 0.0 if denom <= 0 else sum((x[i] - xm) * (y[i] - ym) for i in range(k)) / denom

    bull_slope = slope(bullish_distance)
    bear_slope = slope(bearish_distance)
    side_key = str(side or "").upper()
    if side_key == "LONG":
        valid = bull_delta > 0 and bull_slope > 0
    elif side_key == "SHORT":
        valid = bear_delta > 0 and bear_slope > 0
    else:
        valid = (
            (bull_delta > 0 and bull_slope > 0)
            or (bear_delta > 0 and bear_slope > 0)
        )
    return float(max(score, 0.0)), bool(valid)


def _invalidation_intact(
    ind: pd.DataFrame,
    source_index: int,
    *,
    side: str,
    protective_level: float,
) -> bool:
    """Reject a pending trigger if price already crossed its structural stop."""
    if source_index < 0 or source_index >= len(ind):
        return False
    if not math.isfinite(float(protective_level)) or protective_level <= 0:
        return False
    if side not in {"LONG", "SHORT"}:
        return False
    later = ind.iloc[source_index + 1 :]
    if later.empty:
        return True
    column = "low" if side == "LONG" else "high"
    values = pd.to_numeric(later[column], errors="coerce").to_numpy(dtype=float)
    if not all(math.isfinite(float(value)) for value in values):
        return False
    if side == "LONG":
        return bool((values > protective_level).all())
    return bool((values < protective_level).all())


def _trigger_unbroken(
    ind: pd.DataFrame,
    source_index: int,
    *,
    side: str,
    trigger_price: float,
) -> bool:
    """Reject a stop-entry trigger already crossed by any later closed candle."""
    if source_index < 0 or source_index >= len(ind):
        return False
    if not math.isfinite(float(trigger_price)) or trigger_price <= 0:
        return False
    later = ind.iloc[source_index + 1 :]
    if later.empty:
        return True
    column = "high" if side == "LONG" else "low"
    values = pd.to_numeric(later[column], errors="coerce").to_numpy(dtype=float)
    if not all(math.isfinite(float(value)) for value in values):
        return False
    if side == "LONG":
        return bool((values < trigger_price).all())
    if side == "SHORT":
        return bool((values > trigger_price).all())
    return False


def _latest_reversal(
    ind: pd.DataFrame,
    *,
    side: str,
    max_age_bars: int = 20,
) -> tuple[int, float, float, float] | None:
    column = "bullish_reversal_bar" if side == "LONG" else "bearish_reversal_bar"
    if column not in ind.columns:
        return None
    start = max(0, len(ind) - max(2, int(max_age_bars)))
    for i in range(len(ind) - 1, start - 1, -1):
        if bool(ind.iloc[i].get(column, False)):
            row = ind.iloc[i]
            score, valid = _angulation(ind, i, side=side)
            if not valid:
                continue
            extreme = float(row["low"] if side == "LONG" else row["high"])
            trigger_base = float(row["high"] if side == "LONG" else row["low"])
            return i, trigger_base, extreme, score
    return None


def _latest_super_ao(
    ind: pd.DataFrame,
    *,
    side: str,
    max_age_bars: int = 20,
) -> tuple[int, float, float] | None:
    streak_col = "ao_green_streak" if side == "LONG" else "ao_red_streak"
    if streak_col not in ind.columns:
        return None

    start = max(0, len(ind) - max(2, int(max_age_bars)))
    for i in range(len(ind) - 1, start - 1, -1):
        row = ind.iloc[i]
        try:
            streak = int(row.get(streak_col, 0) or 0)
        except (TypeError, ValueError):
            streak = 0
        # WM2 is its own Wise-Man signal: the third consecutive same-colour
        # AO bar defines the signal bar. Do not make WM2 contingent on a
        # separate fractal/Balance-Line event; context and execution gates
        # are evaluated downstream without changing WM2's identity.
        if streak == 3:
            trigger_base = float(row["high"] if side == "LONG" else row["low"])
            protective = float(row["low"] if side == "LONG" else row["high"])
            return i, trigger_base, protective
    return None


def _latest_confirmed_fractal(
    ind: pd.DataFrame,
    *,
    side: str,
    max_age_bars: int = 80,
) -> tuple[int, int, float, float, float] | None:
    level_col = "confirmed_up_level" if side == "LONG" else "confirmed_down_level"
    fractal_col = "fractal_up" if side == "LONG" else "fractal_down"
    if level_col not in ind.columns or fractal_col not in ind.columns:
        return None

    # Use the same confirmation delay that produced confirmed_*_level.
    # Older/synthetic frames without this metadata retain the canonical 2-bar default.
    right_value = ind.iloc[-1].get("fractal_right_bars", 2)
    try:
        right = int(right_value)
    except (TypeError, ValueError, OverflowError):
        return None
    if right < 1 or isinstance(right_value, float) and not right_value.is_integer():
        return None
    center_index_col = "confirmed_up_center_index" if side == "LONG" else "confirmed_down_center_index"
    has_center_index = center_index_col in ind.columns
    start = max(0, len(ind) - max(3, int(max_age_bars)))
    for confirmation_i in range(len(ind) - 1, start - 1, -1):
        row = ind.iloc[confirmation_i]
        level = row.get(level_col)
        if level is None or pd.isna(level):
            continue
        center_i = confirmation_i - right
        if has_center_index:
            try:
                stored_center = int(row.get(center_index_col, -1))
                if stored_center >= 0:
                    center_i = stored_center
            except (TypeError, ValueError, OverflowError):
                continue
        if center_i < 0 or center_i >= len(ind):
            continue
        center_flag = ind.iloc[center_i].get(fractal_col, False)
        if pd.isna(center_flag) or not bool(center_flag):
            continue
        center = ind.iloc[center_i]
        trigger_base = float(center["high"] if side == "LONG" else center["low"])
        protective = float(center["low"] if side == "LONG" else center["high"])
        teeth = float(row.get("teeth_shifted", 0.0) or 0.0)
        return confirmation_i, center_i, trigger_base, protective, teeth
    return None


def _dedupe_and_sort_signal_specs(specs: list[SignalSpec]) -> list[SignalSpec]:
    """Keep one identity per source formation and order by actionable confirmation."""
    unique: dict[tuple[str, int], SignalSpec] = {}
    for spec in specs:
        unique[(spec.signal_type.value, spec.signal_bar_time_ms)] = spec
    return sorted(
        unique.values(),
        key=lambda item: (
            int(getattr(item, "confirmation_time_ms", 0) or item.signal_bar_time_ms),
            item.signal_type.value,
        ),
    )


def extract_long_signal_specs(
    symbol: str,
    ind: pd.DataFrame,
    *,
    timeframe: str,
    tick_size: float,
    htf_confirmed: bool = False,
    wave_confidence: float = 0.0,
    wave_exhaustion_risk: float = 0.0,
    wave_invalidation_price: float = 0.0,
    context_versions: dict[str, int] | None = None,
    max_reversal_age_bars: int = 20,
) -> list[SignalSpec]:
    """Extract all presently armable LONG signals from closed candles."""
    if ind is None or ind.empty:
        return []

    specs: list[SignalSpec] = []
    current = ind.iloc[-1]
    current_close = float(current.get("close", 0.0) or 0.0)
    current_teeth = float(current.get("teeth_shifted", 0.0) or 0.0)
    if current_close <= 0:
        return []

    tick = max(float(tick_size), 1e-12)
    versions = dict(context_versions or {})

    # Prefer the earliest still-active signal: the book describes the first
    # signal as the initial entry, with later Wise Men becoming adds.
    reversal = _latest_reversal(
        ind,
        side="LONG",
        max_age_bars=max_reversal_age_bars,
    )
    if reversal is not None:
        i, trigger_base, protective, score = reversal
        trigger = trigger_base + tick
        if _trigger_unbroken(ind, i, side="LONG", trigger_price=trigger) and _invalidation_intact(ind, i, side="LONG", protective_level=protective):
            row = ind.iloc[i]
            specs.append(
                SignalSpec.new(
                    symbol=symbol,
                    side="BUY",
                    signal_type=SignalType.REVERSAL,
                    role=SignalRole.ENTRY,
                    timeframe=timeframe,
                    signal_bar_time_ms=_row_time_ms(row),
                    confirmation_time_ms=_row_time_ms(row),
                    trigger_price=trigger,
                    protective_reference=protective,
                    trigger_buffer_ticks=1,
                    invalidation_price=float(protective),
                    wave_invalidation_price=float(wave_invalidation_price or 0.0),
                    teeth_at_detection=float(row.get("teeth_shifted", 0.0) or 0.0),
                    alligator_bullish=bool(row.get("bullish_alligator", False)),
                    alligator_awake=bool(row.get("alligator_awake", False)),
                    angulation_score=score,
                    wave_confidence=float(wave_confidence),
                    wave_exhaustion_risk=float(wave_exhaustion_risk),
                    htf_confirmed=bool(htf_confirmed),
                    context_versions=versions,
                    reason="WM1 bullish reversal + increasing angulation; BUY STOP above signal bar",
                    source_candle_index=i,
                    expires_at_ms=(
                        _row_time_ms(row)
                        + _timeframe_ms(timeframe) * (1 + max(
                            1, int(os.getenv("WILLIAMS_PENDING_REVERSAL_BARS", "2"))
                        ))
                    ),
                )
            )

    super_ao = _latest_super_ao(ind, side="LONG")
    if super_ao is not None:
        i, trigger_base, protective = super_ao
        trigger = trigger_base + tick
        if current_close < trigger and _trigger_unbroken(ind, i, side="LONG", trigger_price=trigger) and _invalidation_intact(ind, i, side="LONG", protective_level=protective):
            row = ind.iloc[i]
            specs.append(
                SignalSpec.new(
                    symbol=symbol,
                    side="BUY",
                    signal_type=SignalType.SUPER_AO,
                    role=SignalRole.ADD_ON,
                    timeframe=timeframe,
                    signal_bar_time_ms=_row_time_ms(row),
                    confirmation_time_ms=_row_time_ms(row),
                    trigger_price=trigger,
                    protective_reference=protective,
                    trigger_buffer_ticks=1,
                    invalidation_price=float(protective),
                    wave_invalidation_price=float(wave_invalidation_price or 0.0),
                    teeth_at_detection=float(row.get("teeth_shifted", 0.0) or 0.0),
                    alligator_bullish=bool(row.get("bullish_alligator", False)),
                    alligator_awake=bool(row.get("alligator_awake", False)),
                    wave_confidence=float(wave_confidence),
                    wave_exhaustion_risk=float(wave_exhaustion_risk),
                    htf_confirmed=bool(htf_confirmed),
                    context_versions=versions,
                    reason="WM2 Super AO: third green AO bar; BUY STOP above corresponding price bar",
                    source_candle_index=i,
                    expires_at_ms=(
                        _row_time_ms(row)
                        + _timeframe_ms(timeframe) * (1 + max(
                            1, int(os.getenv("WILLIAMS_PENDING_SUPER_AO_BARS", "2"))
                        ))
                    ),
                )
            )

    fractal = _latest_confirmed_fractal(ind, side="LONG")
    if fractal is not None:
        confirmation_i, center_i, trigger_base, protective, teeth = fractal
        # Formation can be anywhere; trigger validity belongs to current
        # price/Teeth. At arm time a SHORT trigger must remain below Teeth.
        trigger = trigger_base + tick
        current_trigger_valid = (
            math.isfinite(current_teeth) and current_teeth > 0.0
            and math.isfinite(teeth) and teeth > 0.0
            and trigger > current_teeth
        )
        if _trigger_unbroken(ind, center_i, side="LONG", trigger_price=trigger) and current_trigger_valid and _invalidation_intact(ind, center_i, side="LONG", protective_level=protective):
            row = ind.iloc[center_i]
            specs.append(
                SignalSpec.new(
                    symbol=symbol,
                    side="BUY",
                    signal_type=SignalType.FRACTAL,
                    role=SignalRole.ADD_ON,
                    timeframe=timeframe,
                    signal_bar_time_ms=_row_time_ms(row),
                    confirmation_time_ms=_row_time_ms(ind.iloc[confirmation_i]),
                    trigger_price=trigger,
                    protective_reference=protective,
                    trigger_buffer_ticks=1,
                    invalidation_price=float(protective),
                    wave_invalidation_price=float(wave_invalidation_price or 0.0),
                    teeth_at_detection=float(teeth),
                    alligator_bullish=bool(current.get("bullish_alligator", False)),
                    alligator_awake=bool(current.get("alligator_awake", False)),
                    wave_confidence=float(wave_confidence),
                    wave_exhaustion_risk=float(wave_exhaustion_risk),
                    htf_confirmed=bool(htf_confirmed),
                    context_versions=versions,
                    reason="WM3 buy fractal; trigger only while price/trigger remains above Teeth",
                    source_candle_index=center_i,
                    expires_at_ms=(
                        _row_time_ms(ind.iloc[confirmation_i])
                        + _timeframe_ms(timeframe) * (1 + max(
                            1, int(os.getenv("WILLIAMS_PENDING_FRACTAL_BARS", "8"))
                        ))
                    ),
                )
            )

    return _dedupe_and_sort_signal_specs(specs)


# Mirror the long detector. Direction is explicit and is kept separate from
# the Binance order side used later by the Futures adapter.

# Mirror the LONG detector with mirrored trigger geometry and explicit SHORT direction.

def extract_short_signal_specs(
    symbol: str,
    ind: pd.DataFrame,
    *,
    timeframe: str,
    tick_size: float,
    htf_confirmed: bool = False,
    wave_confidence: float = 0.0,
    wave_exhaustion_risk: float = 0.0,
    wave_invalidation_price: float = 0.0,
    context_versions: dict[str, int] | None = None,
    max_reversal_age_bars: int = 20,
) -> list[SignalSpec]:
    """Extract all presently armable SHORT signals from closed candles."""
    if ind is None or ind.empty:
        return []

    specs: list[SignalSpec] = []
    current = ind.iloc[-1]
    current_close = float(current.get("close", 0.0) or 0.0)
    current_teeth = float(current.get("teeth_shifted", 0.0) or 0.0)
    if current_close <= 0:
        return []

    tick = max(float(tick_size), 1e-12)
    current_time = _row_time_ms(current)
    versions = dict(context_versions or {})

    # Prefer the earliest still-active signal: the book describes the first
    # signal as the initial entry, with later Wise Men becoming adds.
    reversal = _latest_reversal(
        ind,
        side="SHORT",
        max_age_bars=max_reversal_age_bars,
    )
    if reversal is not None:
        i, trigger_base, protective, score = reversal
        trigger = trigger_base - tick
        if _trigger_unbroken(ind, i, side="SHORT", trigger_price=trigger) and _invalidation_intact(ind, i, side="SHORT", protective_level=protective):
            row = ind.iloc[i]
            specs.append(
                SignalSpec.new(
                    symbol=symbol,
                    side="SELL",
                    signal_type=SignalType.REVERSAL,
                    role=SignalRole.ENTRY,
                    timeframe=timeframe,
                    signal_bar_time_ms=_row_time_ms(row),
                    confirmation_time_ms=_row_time_ms(row),
                    trigger_price=trigger,
                    protective_reference=protective,
                    trigger_buffer_ticks=1,
                    invalidation_price=float(protective),
                    wave_invalidation_price=float(wave_invalidation_price or 0.0),
                    teeth_at_detection=float(row.get("teeth_shifted", 0.0) or 0.0),
                    alligator_bullish=bool(row.get("bullish_alligator", False)),
                    alligator_bearish=bool(row.get("bearish_alligator", False)),
                    alligator_awake=bool(row.get("alligator_awake", False)),
                    angulation_score=score,
                    wave_confidence=float(wave_confidence),
                    wave_exhaustion_risk=float(wave_exhaustion_risk),
                    htf_confirmed=bool(htf_confirmed),
                    context_versions=versions,
                    reason="WM1 bearish reversal + increasing bearish separation; SELL STOP below signal bar",
                    source_candle_index=i,
                    expires_at_ms=(
                        _row_time_ms(row)
                        + _timeframe_ms(timeframe) * (1 + max(
                            1, int(os.getenv("WILLIAMS_PENDING_REVERSAL_BARS", "2"))
                        ))
                    ),
                )
            )

    super_ao = _latest_super_ao(ind, side="SHORT")
    if super_ao is not None:
        i, trigger_base, protective = super_ao
        trigger = trigger_base - tick
        if current_close > trigger and _trigger_unbroken(ind, i, side="SHORT", trigger_price=trigger) and _invalidation_intact(ind, i, side="SHORT", protective_level=protective):
            row = ind.iloc[i]
            specs.append(
                SignalSpec.new(
                    symbol=symbol,
                    side="SELL",
                    signal_type=SignalType.SUPER_AO,
                    role=SignalRole.ADD_ON,
                    timeframe=timeframe,
                    signal_bar_time_ms=_row_time_ms(row),
                    confirmation_time_ms=_row_time_ms(row),
                    trigger_price=trigger,
                    protective_reference=protective,
                    trigger_buffer_ticks=1,
                    invalidation_price=float(protective),
                    wave_invalidation_price=float(wave_invalidation_price or 0.0),
                    teeth_at_detection=float(row.get("teeth_shifted", 0.0) or 0.0),
                    alligator_bullish=bool(row.get("bullish_alligator", False)),
                    alligator_bearish=bool(row.get("bearish_alligator", False)),
                    alligator_awake=bool(row.get("alligator_awake", False)),
                    wave_confidence=float(wave_confidence),
                    wave_exhaustion_risk=float(wave_exhaustion_risk),
                    htf_confirmed=bool(htf_confirmed),
                    context_versions=versions,
                    reason="WM2 Super AO: third red AO bar; SELL STOP below corresponding price bar",
                    source_candle_index=i,
                    expires_at_ms=(
                        _row_time_ms(row)
                        + _timeframe_ms(timeframe) * (1 + max(
                            1, int(os.getenv("WILLIAMS_PENDING_SUPER_AO_BARS", "2"))
                        ))
                    ),
                )
            )

    fractal = _latest_confirmed_fractal(ind, side="SHORT")
    if fractal is not None:
        confirmation_i, center_i, trigger_base, protective, teeth = fractal
        # Formation can be anywhere; trigger validity belongs to current
        # price/Teeth.  At arm time the trigger must still be above Teeth.
        trigger = trigger_base - tick
        current_trigger_valid = (
            math.isfinite(current_teeth) and current_teeth > 0.0
            and math.isfinite(teeth) and teeth > 0.0
            and trigger < current_teeth
        )
        if _trigger_unbroken(ind, center_i, side="SHORT", trigger_price=trigger) and current_trigger_valid and _invalidation_intact(ind, center_i, side="SHORT", protective_level=protective):
            row = ind.iloc[center_i]
            specs.append(
                SignalSpec.new(
                    symbol=symbol,
                    side="SELL",
                    signal_type=SignalType.FRACTAL,
                    role=SignalRole.ADD_ON,
                    timeframe=timeframe,
                    signal_bar_time_ms=_row_time_ms(row),
                    confirmation_time_ms=_row_time_ms(ind.iloc[confirmation_i]),
                    trigger_price=trigger,
                    protective_reference=protective,
                    trigger_buffer_ticks=1,
                    invalidation_price=float(protective),
                    wave_invalidation_price=float(wave_invalidation_price or 0.0),
                    teeth_at_detection=float(teeth),
                    alligator_bullish=bool(current.get("bullish_alligator", False)),
                    alligator_bearish=bool(current.get("bearish_alligator", False)),
                    alligator_awake=bool(current.get("alligator_awake", False)),
                    wave_confidence=float(wave_confidence),
                    wave_exhaustion_risk=float(wave_exhaustion_risk),
                    htf_confirmed=bool(htf_confirmed),
                    context_versions=versions,
                    reason="WM3 sell fractal; trigger only while price/trigger remains below Teeth",
                    source_candle_index=center_i,
                    expires_at_ms=(
                        _row_time_ms(ind.iloc[confirmation_i])
                        + _timeframe_ms(timeframe) * (1 + max(
                            1, int(os.getenv("WILLIAMS_PENDING_FRACTAL_BARS", "8"))
                        ))
                    ),
                )
            )

    # Deduplicate by signal type + signal bar.  The same signal must not
    # create a new order on every scan.
    unique: dict[tuple[str, int], SignalSpec] = {}
    for spec in specs:
        unique[(spec.signal_type.value, spec.signal_bar_time_ms)] = spec
    return sorted(unique.values(), key=lambda x: (int(getattr(x, "confirmation_time_ms", 0) or x.signal_bar_time_ms), x.signal_type.value))


# Mirror the long detector. Direction is explicit and is kept separate from
# the Binance order side used later by the Futures adapter.
