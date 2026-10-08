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

from dataclasses import asdict
from typing import Any
import math

import pandas as pd

from campaign_model import SignalRole, SignalSpec, SignalType


def _tick_buffer(tick_size: float, ticks: int = 1) -> float:
    return max(float(tick_size), 0.0) * max(int(ticks), 1)


def _timeframe_ms(timeframe: str) -> int:
    """Convert the Binance-style operative timeframe to milliseconds."""
    value = str(timeframe or "").strip()
    if not value:
        return 0
    unit = value[-1]
    try:
        number = int(value[:-1])
    except (TypeError, ValueError):
        return 0
    multipliers = {
        "m": 60_000,
        "h": 3_600_000,
        "d": 86_400_000,
        "w": 604_800_000,
    }
    return number * multipliers.get(unit, 0)


def _expiry(signal_bar_time_ms: int, timeframe: str, max_age_bars: int) -> int:
    step = _timeframe_ms(timeframe)
    if signal_bar_time_ms <= 0 or step <= 0:
        return 0
    return int(signal_bar_time_ms + max(1, int(max_age_bars)) * step)


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


def _angulation(ind: pd.DataFrame, index: int, *, window: int = 5, side: str | None = None) -> tuple[float, bool]:
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
    directional_delta = (
        bull_delta if str(side or "").upper() == "LONG"
        else bear_delta if str(side or "").upper() == "SHORT"
        else max(bull_delta, bear_delta)
    )
    score = max(directional_delta, 0.0) / base * 100.0

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
    if str(side or "").upper() == "LONG":
        valid = bull_delta > 0 and bull_slope > 0
    elif str(side or "").upper() == "SHORT":
        valid = bear_delta > 0 and bear_slope > 0
    else:
        valid = (
            (bull_delta > 0 and bull_slope > 0)
            or (bear_delta > 0 and bear_slope > 0)
        )
    return float(max(score, 0.0)), bool(valid)


def _latest_reversal(
    ind: pd.DataFrame,
    *,
    side: str,
    max_age_bars: int = 20,
) -> tuple[int, float, float] | None:
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
            return i, trigger_base, extreme
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
        # The third same-colour AO bar is the signal bar. Chapter 12 also
        # permits Super AO to be the first presenting entry signal, so there
        # is intentionally no prerequisite fractal gate here.
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

    right = 2
    start = max(0, len(ind) - max(3, int(max_age_bars)))
    for confirmation_i in range(len(ind) - 1, start - 1, -1):
        row = ind.iloc[confirmation_i]
        level = row.get(level_col)
        if level is None or pd.isna(level):
            continue
        center_i = confirmation_i - right
        if center_i < 0 or not bool(ind.iloc[center_i].get(fractal_col, False)):
            continue
        center = ind.iloc[center_i]
        trigger_base = float(center["high"] if side == "LONG" else center["low"])
        protective = float(center["low"] if side == "LONG" else center["high"])
        teeth = float(row.get("teeth_shifted", 0.0) or 0.0)
        return confirmation_i, center_i, trigger_base, protective, teeth
    return None


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
    current_time = _row_time_ms(current)
    versions = dict(context_versions or {})

    # Prefer the earliest still-active signal: the book describes the first
    # signal as the initial entry, with later Wise Men becoming adds.
    reversal = _latest_reversal(
        ind,
        side="LONG",
        max_age_bars=max_reversal_age_bars,
    )
    if reversal is not None:
        i, trigger_base, protective = reversal
        trigger = trigger_base + tick
        if current_close < trigger:
            row = ind.iloc[i]
            score, _ = _angulation(ind, i, side="LONG")
            specs.append(
                SignalSpec.new(
                    symbol=symbol,
                    side="BUY",
                    signal_type=SignalType.REVERSAL,
                    role=SignalRole.ENTRY,
                    timeframe=timeframe,
                    signal_bar_time_ms=_row_time_ms(row),
                    trigger_price=trigger,
                    protective_reference=protective,
                    trigger_buffer_ticks=1,
                    invalidation_price=float(wave_invalidation_price or protective),
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
                    expires_at_ms=_expiry(_row_time_ms(row), timeframe, max_reversal_age_bars),
                )
            )

    super_ao = _latest_super_ao(ind, side="LONG")
    if super_ao is not None:
        i, trigger_base, protective = super_ao
        trigger = trigger_base + tick
        if current_close < trigger:
            row = ind.iloc[i]
            specs.append(
                SignalSpec.new(
                    symbol=symbol,
                    side="BUY",
                    signal_type=SignalType.SUPER_AO,
                    role=SignalRole.ENTRY,
                    timeframe=timeframe,
                    signal_bar_time_ms=_row_time_ms(row),
                    trigger_price=trigger,
                    protective_reference=protective,
                    trigger_buffer_ticks=1,
                    invalidation_price=float(wave_invalidation_price or protective),
                    teeth_at_detection=float(row.get("teeth_shifted", 0.0) or 0.0),
                    alligator_bullish=bool(row.get("bullish_alligator", False)),
                    alligator_awake=bool(row.get("alligator_awake", False)),
                    wave_confidence=float(wave_confidence),
                    wave_exhaustion_risk=float(wave_exhaustion_risk),
                    htf_confirmed=bool(htf_confirmed),
                    context_versions=versions,
                    reason="WM2 Super AO: third green AO bar; BUY STOP above corresponding price bar",
                    source_candle_index=i,
                    expires_at_ms=_expiry(_row_time_ms(row), timeframe, 20),
                )
            )

    fractal = _latest_confirmed_fractal(ind, side="LONG")
    if fractal is not None:
        confirmation_i, center_i, trigger_base, protective, teeth = fractal
        # Formation can be anywhere; trigger validity belongs to current
        # price/Teeth.  At arm time the trigger must still be above Teeth.
        trigger = trigger_base + tick
        current_trigger_valid = trigger > max(current_teeth, 0.0)
        if current_close < trigger and current_trigger_valid:
            row = ind.iloc[center_i]
            specs.append(
                SignalSpec.new(
                    symbol=symbol,
                    side="BUY",
                    signal_type=SignalType.FRACTAL,
                    role=SignalRole.ENTRY,
                    timeframe=timeframe,
                    signal_bar_time_ms=_row_time_ms(row),
                    trigger_price=trigger,
                    protective_reference=protective,
                    trigger_buffer_ticks=1,
                    invalidation_price=float(wave_invalidation_price or protective),
                    teeth_at_detection=float(teeth),
                    alligator_bullish=bool(current.get("bullish_alligator", False)),
                    alligator_awake=bool(current.get("alligator_awake", False)),
                    wave_confidence=float(wave_confidence),
                    wave_exhaustion_risk=float(wave_exhaustion_risk),
                    htf_confirmed=bool(htf_confirmed),
                    context_versions=versions,
                    reason="WM3 buy fractal; trigger only while price/trigger remains above Teeth",
                    source_candle_index=center_i,
                    expires_at_ms=_expiry(_row_time_ms(row), timeframe, 80),
                )
            )

    # Deduplicate by signal type + signal bar.  The same signal must not
    # create a new order on every scan.
    unique: dict[tuple[str, int], SignalSpec] = {}
    for spec in specs:
        unique[(spec.signal_type.value, spec.signal_bar_time_ms)] = spec
    return sorted(unique.values(), key=lambda x: (x.signal_bar_time_ms, x.signal_type.value))


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
    """Extract currently armable SHORT Williams signals from closed candles."""
    if ind is None or ind.empty:
        return []

    current = ind.iloc[-1]
    current_close = float(current.get("close", 0.0) or 0.0)
    current_teeth = float(current.get("teeth_shifted", 0.0) or 0.0)
    if current_close <= 0:
        return []

    tick = max(float(tick_size), 1e-12)
    versions = dict(context_versions or {})
    specs: list[SignalSpec] = []

    reversal = _latest_reversal(ind, side="SHORT", max_age_bars=max_reversal_age_bars)
    if reversal is not None:
        i, trigger_base, protective = reversal
        trigger = trigger_base - tick
        if current_close > trigger:
            row = ind.iloc[i]
            score, _ = _angulation(ind, i, side="SHORT")
            specs.append(
                SignalSpec.new(
                    symbol=symbol,
                    side="SELL",
                    signal_type=SignalType.REVERSAL,
                    role=SignalRole.ENTRY,
                    timeframe=timeframe,
                    signal_bar_time_ms=_row_time_ms(row),
                    trigger_price=trigger,
                    protective_reference=protective,
                    trigger_buffer_ticks=1,
                    invalidation_price=float(wave_invalidation_price or protective),
                    teeth_at_detection=float(row.get("teeth_shifted", 0.0) or 0.0),
                    alligator_bullish=bool(row.get("bearish_alligator", False)),
                    alligator_awake=bool(row.get("alligator_awake", False)),
                    angulation_score=score,
                    wave_confidence=float(wave_confidence),
                    wave_exhaustion_risk=float(wave_exhaustion_risk),
                    htf_confirmed=bool(htf_confirmed),
                    context_versions=versions,
                    reason="WM1 bearish reversal + increasing angulation; SELL STOP below signal bar",
                    source_candle_index=i,
                    expires_at_ms=_expiry(_row_time_ms(row), timeframe, 20),
                )
            )

    super_ao = _latest_super_ao(ind, side="SHORT")
    if super_ao is not None:
        i, trigger_base, protective = super_ao
        trigger = trigger_base - tick
        if current_close > trigger:
            row = ind.iloc[i]
            specs.append(
                SignalSpec.new(
                    symbol=symbol,
                    side="SELL",
                    signal_type=SignalType.SUPER_AO,
                    role=SignalRole.ENTRY,
                    timeframe=timeframe,
                    signal_bar_time_ms=_row_time_ms(row),
                    trigger_price=trigger,
                    protective_reference=protective,
                    trigger_buffer_ticks=1,
                    invalidation_price=float(wave_invalidation_price or protective),
                    teeth_at_detection=float(row.get("teeth_shifted", 0.0) or 0.0),
                    alligator_bullish=bool(row.get("bearish_alligator", False)),
                    alligator_awake=bool(row.get("alligator_awake", False)),
                    wave_confidence=float(wave_confidence),
                    wave_exhaustion_risk=float(wave_exhaustion_risk),
                    htf_confirmed=bool(htf_confirmed),
                    context_versions=versions,
                    reason="WM2 Super AO: third red AO bar; SELL STOP below corresponding price bar",
                    source_candle_index=i,
                    expires_at_ms=_expiry(_row_time_ms(row), timeframe, 20),
                )
            )

    fractal = _latest_confirmed_fractal(ind, side="SHORT")
    if fractal is not None:
        confirmation_i, center_i, trigger_base, protective, teeth = fractal
        trigger = trigger_base - tick
        current_trigger_valid = current_teeth > 0 and trigger < current_teeth
        if current_close > trigger and (current_trigger_valid or current_teeth <= 0):
            row = ind.iloc[center_i]
            specs.append(
                SignalSpec.new(
                    symbol=symbol,
                    side="SELL",
                    signal_type=SignalType.FRACTAL,
                    role=SignalRole.ENTRY,
                    timeframe=timeframe,
                    signal_bar_time_ms=_row_time_ms(row),
                    trigger_price=trigger,
                    protective_reference=protective,
                    trigger_buffer_ticks=1,
                    invalidation_price=float(wave_invalidation_price or protective),
                    teeth_at_detection=float(teeth),
                    alligator_bullish=bool(current.get("bearish_alligator", False)),
                    alligator_awake=bool(current.get("alligator_awake", False)),
                    wave_confidence=float(wave_confidence),
                    wave_exhaustion_risk=float(wave_exhaustion_risk),
                    htf_confirmed=bool(htf_confirmed),
                    context_versions=versions,
                    reason="WM3 sell fractal; trigger only while price/trigger remains below Teeth",
                    source_candle_index=center_i,
                    expires_at_ms=_expiry(_row_time_ms(row), timeframe, 80),
                )
            )

    unique: dict[tuple[str, int], SignalSpec] = {}
    for spec in specs:
        unique[(spec.signal_type.value, spec.signal_bar_time_ms)] = spec
    return sorted(unique.values(), key=lambda x: (x.signal_bar_time_ms, x.signal_type.value))
