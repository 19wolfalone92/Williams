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
    return int(values.get(str(timeframe), 0))

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


def _angulation(ind: pd.DataFrame, index: int, *, window: int = 5) -> tuple[float, bool]:
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
    """Fail closed if a later closed candle breached the signal's structural extreme."""
    if source_index < 0 or source_index >= len(ind):
        return False
    if side not in {"LONG", "SHORT"} or not math.isfinite(float(protective_level)) or protective_level <= 0:
        return False
    later = ind.iloc[source_index + 1:]
    if later.empty:
        return True
    column = "low" if side == "LONG" else "high"
    values = pd.to_numeric(later[column], errors="coerce").to_numpy(dtype=float)
    if not all(math.isfinite(float(v)) for v in values):
        return False
    return bool((values > protective_level).all()) if side == "LONG" else bool((values < protective_level).all())


def _trigger_unbroken(
    ind: pd.DataFrame,
    source_index: int,
    *,
    side: str,
    trigger_price: float,
) -> bool:
    """A conditional stop is not re-armed after any later candle traded through it."""
    if source_index < 0 or source_index >= len(ind):
        return False
    if side not in {"LONG", "SHORT"} or not math.isfinite(float(trigger_price)) or trigger_price <= 0:
        return False
    later = ind.iloc[source_index + 1:]
    if later.empty:
        return True
    column = "high" if side == "LONG" else "low"
    values = pd.to_numeric(later[column], errors="coerce").to_numpy(dtype=float)
    if not all(math.isfinite(float(v)) for v in values):
        return False
    return bool((values < trigger_price).all()) if side == "LONG" else bool((values > trigger_price).all())


def _latest_reversal(
    ind: pd.DataFrame,
    *,
    side: str,
    max_age_bars: int = 20,
) -> tuple[int, float, float] | None:
    # "Good angulation" is described qualitatively, while the available
    # editions do not provide a deterministic threshold proving this linear
    # separation approximation author-exact. Default to blocked; the explicit
    # flag is for research/replay only until the source rule is resolved.
    if str(os.getenv("WILLIAMS_ALLOW_APPROXIMATE_WM1_ANGULATION", "false")).lower() != "true":
        return None
    column = "bullish_reversal_bar" if side == "LONG" else "bearish_reversal_bar"
    if column not in ind.columns:
        return None
    start = max(0, len(ind) - max(2, int(max_age_bars)))
    for i in range(len(ind) - 1, start - 1, -1):
        if bool(ind.iloc[i].get(column, False)):
            row = ind.iloc[i]
            score, valid = _angulation(ind, i)
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
        # WM2 is identified by the third bar independently of WM3. Campaign
        # context and the conditional price trigger are checked downstream.
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

    right_value = ind.iloc[-1].get("fractal_right_bars", 2)
    try:
        right = int(right_value)
    except (TypeError, ValueError, OverflowError):
        return None
    if right < 1 or (isinstance(right_value, float) and not right_value.is_integer()):
        return None
    center_index_col = (
        "confirmed_up_center_index" if side == "LONG" else "confirmed_down_center_index"
    )
    has_center_metadata = center_index_col in ind.columns
    start = max(0, len(ind) - max(3, int(max_age_bars)))
    for confirmation_i in range(len(ind) - 1, start - 1, -1):
        row = ind.iloc[confirmation_i]
        level = row.get(level_col)
        if level is None or pd.isna(level):
            continue
        center_i = confirmation_i - right
        if has_center_metadata:
            try:
                stored_center = int(row.get(center_index_col, -1))
            except (TypeError, ValueError, OverflowError):
                continue
            if stored_center >= 0:
                center_i = stored_center
        if center_i < 0 or center_i >= len(ind):
            continue
        # Fractal event is stamped on its actual confirmation row, never on
        # the historical center row as an actionable event.
        if not bool(row.get(fractal_col, False)):
            continue
        center = ind.iloc[center_i]
        trigger_base = float(center["high"] if side == "LONG" else center["low"])
        protective = float(center["low"] if side == "LONG" else center["high"])
        # Teeth belongs to the actual confirmation row, not center+2.
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
    """Extract all presently armable LONG signals from closed canonical H1 candles."""
    if ind is None or ind.empty or str(timeframe).strip().lower() != "1h" or len(ind) < 140:
        return []
    # The live caller should already supply only closed candles. Enforce the
    # invariant again at this public boundary so a second caller cannot build
    # a canonical signal from an unfinished H1 candle or unknown timestamp.
    last_open_ms = _row_time_ms(ind.iloc[-1])
    if last_open_ms <= 0 or last_open_ms + _timeframe_ms("1h") > int(pd.Timestamp.now(tz='UTC').timestamp() * 1000):
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
        ttl_bars = max(1, int(os.getenv("WILLIAMS_PENDING_REVERSAL_BARS", "2")))
        age_bars = len(ind) - 1 - i
        if (
            current_close < trigger
            and age_bars < ttl_bars
            and _trigger_unbroken(ind, i, side="LONG", trigger_price=trigger)
            and _invalidation_intact(ind, i, side="LONG", protective_level=protective)
        ):
            row = ind.iloc[i]
            score, _ = _angulation(ind, i)
            specs.append(
                SignalSpec.new(
                    symbol=symbol,
                    side="BUY",
                    signal_type=SignalType.REVERSAL,
                    role=SignalRole.ENTRY,
                    timeframe=timeframe,
                    signal_bar_time_ms=_row_time_ms(row),
                    confirmation_time_ms=_row_time_ms(row) + _timeframe_ms(timeframe),
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
                    expires_at_ms=(
                        _row_time_ms(row)
                        + _timeframe_ms(timeframe) * (
                            1 + max(1, int(os.getenv("WILLIAMS_PENDING_REVERSAL_BARS", "2")))
                        )
                    ),
                )
            )

    super_ao = _latest_super_ao(ind, side="LONG")
    if super_ao is not None:
        i, trigger_base, protective = super_ao
        trigger = trigger_base + tick
        ttl_bars = max(1, int(os.getenv("WILLIAMS_PENDING_SUPER_AO_BARS", "2")))
        age_bars = len(ind) - 1 - i
        if (
            current_close < trigger
            and age_bars < ttl_bars
            and _trigger_unbroken(ind, i, side="LONG", trigger_price=trigger)
            and _invalidation_intact(ind, i, side="LONG", protective_level=protective)
        ):
            row = ind.iloc[i]
            specs.append(
                SignalSpec.new(
                    symbol=symbol,
                    side="BUY",
                    signal_type=SignalType.SUPER_AO,
                    role=SignalRole.ENTRY,
                    timeframe=timeframe,
                    signal_bar_time_ms=_row_time_ms(row),
                    confirmation_time_ms=_row_time_ms(row) + _timeframe_ms(timeframe),
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
                    expires_at_ms=(
                        _row_time_ms(row)
                        + _timeframe_ms(timeframe) * (
                            1 + max(1, int(os.getenv("WILLIAMS_PENDING_SUPER_AO_BARS", "2")))
                        )
                    ),
                )
            )

    fractal = _latest_confirmed_fractal(ind, side="LONG")
    if fractal is not None:
        confirmation_i, center_i, trigger_base, invalidation_level, teeth = fractal
        # The source assigns activation to a conditional price break; keep the
        # opposite structural extreme and protective trailing stop as separate
        # values. The 3–5 bar stop window is an explicit bot campaign policy.
        trigger = trigger_base + tick
        ttl_bars = max(1, int(os.getenv("WILLIAMS_PENDING_FRACTAL_BARS", "8")))
        try:
            trail_bars = int(os.getenv("WILLIAMS_TC2_TRAILING_BARS", "3"))
        except (TypeError, ValueError, OverflowError):
            trail_bars = 0
        # UNRESOLVED/noncanonical stop windows block rather than being silently
        # converted into a different trading rule.
        if trail_bars not in {3, 4, 5}:
            return specs
        recent_lows = pd.to_numeric(ind["low"].iloc[-trail_bars:], errors="coerce").to_numpy(dtype=float)
        # Keep the raw trailing low here. The Python execution adapter applies the single exchange tick buffer when it builds the protective STOP_LOSS.
        protective_stop = float(recent_lows.min()) if len(recent_lows) == trail_bars and all(math.isfinite(float(v)) for v in recent_lows) else float("nan")
        age_bars = len(ind) - 1 - confirmation_i
        current_trigger_valid = (
            math.isfinite(current_teeth) and current_teeth > 0.0
            and math.isfinite(teeth) and teeth > 0.0
            and math.isfinite(invalidation_level) and invalidation_level > 0.0
            and trigger_base > teeth
            and trigger > current_teeth
            and math.isfinite(protective_stop) and 0.0 < protective_stop < current_close
        )
        if (
            current_close < trigger
            and current_trigger_valid
            and age_bars < ttl_bars
            and _trigger_unbroken(ind, center_i, side="LONG", trigger_price=trigger)
            and _invalidation_intact(ind, center_i, side="LONG", protective_level=invalidation_level)
        ):
            row = ind.iloc[center_i]
            specs.append(
                SignalSpec.new(
                    symbol=symbol,
                    side="BUY",
                    signal_type=SignalType.FRACTAL,
                    role=SignalRole.ENTRY,
                    timeframe=timeframe,
                    signal_bar_time_ms=_row_time_ms(row),
                    confirmation_time_ms=_row_time_ms(ind.iloc[confirmation_i]) + _timeframe_ms(timeframe),
                    trigger_price=trigger,
                    protective_reference=protective_stop,
                    trigger_buffer_ticks=1,
                    invalidation_price=float(invalidation_level),
                    teeth_at_detection=float(teeth),
                    alligator_bullish=bool(current.get("bullish_alligator", False)),
                    alligator_awake=bool(current.get("alligator_awake", False)),
                    wave_confidence=float(wave_confidence),
                    wave_exhaustion_risk=float(wave_exhaustion_risk),
                    htf_confirmed=bool(htf_confirmed),
                    context_versions=versions,
                    reason="WM3 buy fractal; Teeth valid at confirmation/current activation; separate structural invalidation and 3–5 bar protective stop",
                    source_candle_index=center_i,
                    expires_at_ms=(
                        _row_time_ms(ind.iloc[confirmation_i])
                        + _timeframe_ms(timeframe) * (1 + ttl_bars)
                    ),
                )
            )

    # Deduplicate by signal type + signal bar.  The same signal must not
    # create a new order on every scan.
    unique: dict[tuple[str, int], SignalSpec] = {}
    for spec in specs:
        unique[(spec.signal_type.value, spec.signal_bar_time_ms)] = spec
    return sorted(unique.values(), key=lambda x: (x.signal_bar_time_ms, x.signal_type.value))
