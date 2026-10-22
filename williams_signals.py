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


def _trigger_still_pending(
    ind: pd.DataFrame,
    signal_index: int,
    *,
    side: str,
    trigger_price: float,
) -> bool:
    """Fail closed if a conditional trigger was touched after signal formation."""
    if ind is None or ind.empty or signal_index < 0 or signal_index >= len(ind):
        return False
    direction = str(side or "").upper()
    if direction not in {"BUY", "SELL"} or not math.isfinite(float(trigger_price)) or trigger_price <= 0:
        return False
    current_close = pd.to_numeric(ind["close"], errors="coerce").iloc[-1] if "close" in ind.columns else float("nan")
    if not math.isfinite(float(current_close)):
        return False
    if direction == "BUY":
        if float(current_close) >= trigger_price:
            return False
        if "high" not in ind.columns:
            return False
        highs = pd.to_numeric(ind.iloc[signal_index + 1:]["high"], errors="coerce")
        if not all(math.isfinite(float(v)) and float(v) > 0 for v in highs):
            return False
        return not bool((highs >= trigger_price).any())
    if float(current_close) <= trigger_price:
        return False
    if "low" not in ind.columns:
        return False
    lows = pd.to_numeric(ind.iloc[signal_index + 1:]["low"], errors="coerce")
    if not all(math.isfinite(float(v)) and float(v) > 0 for v in lows):
        return False
    return not bool((lows <= trigger_price).any())


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
    """TC2 mouth-anchored edge geometry; window retained for API compatibility."""
    from williams_market_geometry import angulation_evidence
    from williams_wave_hierarchy import wave_bars_from_dataframe
    if index < 2 or index >= len(ind) or side not in {"LONG", "SHORT"}:
        return 0.0, False
    prefix = ind.iloc[:index+1]
    def line(name):
        # The displaced line is the one visible beside this candle.
        col = next((c for c in (name+"_current",name+"_shifted") if c in prefix), None)
        return [float(v) for v in prefix[col]] if col else [float("nan")]*len(prefix)
    proof = angulation_evidence(side,wave_bars_from_dataframe(prefix),line("jaw"),line("teeth"),line("lips"))
    return proof["score"], proof["valid"]


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
        # The third same-colour AO bar is the signal bar. If the process
        # was offline for subsequent same-colour bars, preserve that original
        # third bar while it remains inside the signal-age window.
        if streak >= 3:
            third_i = i - (streak - 3)
            if third_i < start:
                continue
            signal_row = ind.iloc[third_i]
            trigger_base = float(
                signal_row["high"] if side == "LONG" else signal_row["low"]
            )
            # TC2 chapter 12 (PDF pp.156-157): when Super AO presents first,
            # the protective stop belongs beyond the lowest/highest of the
            # previous 3-5 CLOSED price bars, not just the third AO bar.
            # Use five bars as in the Android TC2 H1 implementation. Later bars
            # must never change the original risk anchor (no look-ahead).
            if third_i < 4:
                continue
            window = ind.iloc[third_i - 4:third_i + 1]
            extremes = pd.to_numeric(
                window["low" if side == "LONG" else "high"], errors="coerce"
            )
            if len(extremes) != 5 or not all(
                math.isfinite(float(v)) and float(v) > 0 for v in extremes
            ):
                continue
            protective = float(extremes.min() if side == "LONG" else extremes.max())
            return third_i, trigger_base, protective
    return None


def _latest_three_price_bar_signal(
    ind: pd.DataFrame,
    *,
    side: str,
    max_age_bars: int = 3,
) -> tuple[int, float, float] | None:
    """Return the third bar of the latest TC2 three-price-bar add-on setup.

    The three price bars must be bullish with rising AO bars for LONG, or
    bearish with falling AO bars for SHORT. AO direction means bar-to-bar
    change, not whether AO is above/below zero. This is an add-on signal only.
    """
    required = {"open", "high", "low", "close", "ao_green", "ao_red"}
    if ind is None or len(ind) < 3 or not required.issubset(ind.columns):
        return None

    is_long = str(side).upper() == "LONG"
    valid = []
    for _, row in ind.iterrows():
        try:
            candle = float(row["close"]) > float(row["open"]) if is_long else float(row["close"]) < float(row["open"])
            momentum = bool(row["ao_green"] if is_long else row["ao_red"])
            valid.append(bool(candle and momentum))
        except (TypeError, ValueError):
            valid.append(False)

    # Search only recent signal bars; do not resurrect an old add-on after a gap.
    earliest = max(2, len(ind) - max(1, int(max_age_bars)) - 1)
    for end in range(len(ind) - 1, earliest - 1, -1):
        if not all(valid[end - 2:end + 1]):
            continue
        if end > 2 and valid[end - 3]:
            continue
        row = ind.iloc[end]
        trigger_base = float(row["high"] if is_long else row["low"])
        protective = float(row["low"] if is_long else row["high"])
        return end, trigger_base, protective
    return None


def _latest_confirmed_fractal(
    ind: pd.DataFrame,
    *,
    side: str,
    max_age_bars: int | None = None,
    current_teeth: float | None = None,
    current_close: float | None = None,
    tick_size: float = 0.0,
) -> tuple[int, int, float, float, float] | None:
    """Return the newest still-actionable confirmed fractal.

    Do not let a newer fractal inside the Teeth balance line shadow an older
    confirmed fractal whose conditional trigger remains valid. A formation is
    not an entry: the trigger must still be ahead of market price and on the
    valid side of the current Teeth line.
    """
    direction = str(side or "").upper()
    if direction not in {"LONG", "SHORT"}:
        return None
    if ((current_teeth is not None and (not math.isfinite(current_teeth) or current_teeth <= 0)) or
            (current_close is not None and (not math.isfinite(current_close) or current_close <= 0))):
        return None
    level_col = "confirmed_up_level" if direction == "LONG" else "confirmed_down_level"
    fractal_col = "fractal_up" if direction == "LONG" else "fractal_down"
    if level_col not in ind.columns or fractal_col not in ind.columns:
        return None

    start = 0 if max_age_bars is None else max(0, len(ind) - max(3, int(max_age_bars)))
    tick = max(float(tick_size), 0.0)
    center_col = "confirmed_up_center_index" if direction == "LONG" else "confirmed_down_center_index"
    group_start_col = "confirmed_up_group_start_index" if direction == "LONG" else "confirmed_down_group_start_index"
    for confirmation_i in range(len(ind) - 1, start - 1, -1):
        row = ind.iloc[confirmation_i]
        level = row.get(level_col)
        if level is None or pd.isna(level):
            continue
        center_value = row.get(center_col)
        if center_value is None or pd.isna(center_value):
            # Compatibility for older persisted/test frames. New indicator
            # frames always carry the actual confirmation-to-pivot mapping.
            center_i = confirmation_i - 2
        else:
            center_i = int(center_value)
        if center_i < 0 or center_i >= len(ind) or not bool(ind.iloc[center_i].get(fractal_col, False)):
            continue
        center = ind.iloc[center_i]
        # NTD ch4 PDF52: an inside-mouth fractal may become valid later.
        # TC2 ch11 PDF147-149: eligibility belongs to the actual STOP TRIGGER
        # (one tick beyond the extremum), not the pivot itself or its Teeth.
        # Keep the original confirmation timestamp; never backdate a signal.
        group_start_value = row.get(group_start_col)
        group_start_i = (
            int(group_start_value)
            if group_start_value is not None and not pd.isna(group_start_value)
            else center_i
        )
        if group_start_i < 0 or group_start_i > center_i:
            continue
        # TC2 ch12 PDF156: a first fractal entry uses the opposite fractal
        # or a 3-5-bar structural stop. Do not substitute the single up/down
        # fractal bar's other edge. Preserve the whole plateau if it is longer.
        group = ind.iloc[min(group_start_i,max(0,confirmation_i-4)):confirmation_i+1]
        structural_extremes = pd.to_numeric(group["low" if direction == "LONG" else "high"], errors="coerce")
        if not all(math.isfinite(float(v)) and float(v) > 0 for v in structural_extremes):
            continue  # Never infer a structural SL by silently dropping a missing extreme.
        trigger_base = float(center["high"] if direction == "LONG" else center["low"])
        trigger = trigger_base + tick if direction == "LONG" else trigger_base - tick
        protective = float(
            pd.to_numeric(group["low" if direction == "LONG" else "high"], errors="coerce").min()
            if direction == "LONG"
            else pd.to_numeric(group["high"], errors="coerce").max()
        )
        teeth = float(row.get("teeth_current", row.get("teeth_shifted", 0.0)) or 0.0)

        if current_teeth is not None:
            if direction == "LONG" and trigger <= float(current_teeth):
                continue
            if direction == "SHORT" and trigger >= float(current_teeth):
                continue
        # A confirmed fractal can only be acted on after its two right-hand
        # bars close. If any bar from confirmation onward already touched the
        # trigger intrabar, do not revive that old stop order after a scan gap.
        since_confirmation = ind.iloc[confirmation_i:]
        observed_extremes = pd.to_numeric(
            since_confirmation["high" if direction == "LONG" else "low"], errors="coerce"
        )
        if not all(math.isfinite(float(v)) and float(v) > 0 for v in observed_extremes):
            continue  # Missing bars/extremes cannot prove an unconsumed breakout.
        if direction == "LONG":
            if current_close is not None and float(current_close) >= trigger:
                continue
            if not since_confirmation.empty and bool(
                (pd.to_numeric(since_confirmation["high"], errors="coerce") >= trigger).any()
            ):
                continue
        else:
            if current_close is not None and float(current_close) <= trigger:
                continue
            if not since_confirmation.empty and bool(
                (pd.to_numeric(since_confirmation["low"], errors="coerce") <= trigger).any()
            ):
                continue
        return confirmation_i, center_i, trigger_base, protective, teeth
    return None



def extract_ntd_balance_line_signal_specs(
    symbol: str,
    ind: pd.DataFrame,
    *,
    side: str,
    timeframe: str,
    tick_size: float,
    max_age_bars: int = 20,
) -> list[SignalSpec]:
    """NTD ch.8 fifth dimension, isolated research signal, NOT TC2 executable.

    The generator is symmetric and closed-bar-only; there is no exchange
    permission until NTD profile, position provenance and Android parity pass.
    """
    from ntd_balance_line import ntd_balance_line_evidence

    if ind is None or len(ind) < 5:
        return []
    required = {"high","low","jaw_current","teeth_current","lips_current","ao","ac"}
    if not required.issubset(ind.columns):
        return []
    evidence = ntd_balance_line_evidence(
        ind["high"],ind["low"],ind["jaw_current"],
        ind["teeth_current"],ind["lips_current"],ind["ao"],ind["ac"],
        side=side,tick_size=tick_size,lookback=max_age_bars,
    )
    if evidence.status != "CANDIDATE":
        return []
    base = ind.iloc[evidence.base_index]
    signal_time = _row_time_ms(base)
    if signal_time <= 0 or not _trigger_still_pending(
        ind,evidence.base_index,side=("BUY" if side.upper()=="LONG" else "SELL"),
        trigger_price=evidence.trigger_price,
    ):
        return []
    return [SignalSpec.new(
        symbol=symbol,
        side="BUY" if side.upper()=="LONG" else "SELL",
        signal_type=SignalType.BALANCE_LINE,
        # NTD p99: Balance-Line fifth dimension can lead the first fractal,
        # so role means research ENTRY hypothesis only; execution stays blocked.
        role=SignalRole.ENTRY,
        timeframe=timeframe,
        signal_bar_time_ms=signal_time,
        trigger_price=evidence.trigger_price,
        protective_reference=evidence.protective_reference,
        trigger_buffer_ticks=1,
        source_candle_index=evidence.base_index,
        teeth_at_detection=float(base["teeth_current"]),
        alligator_bullish=bool(base.get("bullish_alligator",False)),
        alligator_awake=bool(base.get("alligator_awake",False)),
        reason=(
            "NTD ch8 Balance Line research-only: right-to-left base, "
            f"{evidence.confirmed_steps} reference extremes, {evidence.zone} zone; "
            "NEVER authorize as a TC2 entry or add-on"
        ),
        expires_at_ms=_expiry(signal_time,timeframe,max_age_bars),
    )]


def extract_ntd_ao_signal_specs(
    symbol: str,
    ind: pd.DataFrame,
    *,
    side: str,
    timeframe: str,
    tick_size: float,
    max_age_bars: int = 30,
) -> list[SignalSpec]:
    """Extract the three New Trading Dimensions AO entry formations.

    This is an edition-specific profile, not an extra Boolean gate on TC2.
    It consumes closed-bar indicator data and emits a conditional stop beyond
    the signal candle. A caller must explicitly select the NTD profile.
    """
    direction = str(side or "").upper()
    if direction not in {"LONG", "SHORT"} or ind is None or len(ind) < 3:
        return []
    required = {"ao", "ao_green", "ao_red", "high", "low", "close"}
    if not required.issubset(ind.columns):
        return []

    ao = pd.to_numeric(ind["ao"], errors="coerce").to_numpy(dtype=float)
    if len(ao) < 3 or not all(math.isfinite(v) for v in ao[-3:]):
        return []
    current_i = len(ind) - 1
    current = ind.iloc[current_i]
    tick = max(float(tick_size), 1e-12)
    current_time = _row_time_ms(current)
    high = float(current["high"])
    low = float(current["low"])
    close = float(current["close"])
    if not all(math.isfinite(v) and v > 0 for v in (high, low, close)) or high < low:
        return []

    is_long = direction == "LONG"
    order_side = "BUY" if is_long else "SELL"
    trigger = high + tick if is_long else low - tick
    protective = low if is_long else high
    if trigger <= 0 or (is_long and close >= trigger) or (not is_long and close <= trigger):
        return []

    green = ind["ao_green"].fillna(False).astype(bool).to_numpy()
    red = ind["ao_red"].fillna(False).astype(bool).to_numpy()
    matches: list[tuple[SignalType, str]] = []

    # AO Saucer: three consecutive bars form green-red-green above zero
    # for LONG, mirrored red-green-red below zero for SHORT.
    last3 = ao[-3:]
    if is_long:
        if (
            all(v > 0 for v in last3)
            and last3[0] > last3[1] < last3[2]
            and red[-2] and green[-1]
        ):
            # NTD defines the middle bar as the downswing/red bar and the
            # signal bar as the upswing/green bar. The first bar's colour is
            # not prescribed; its AO value only needs to exceed the middle bar.
            matches.append((SignalType.AO_SAUCER, "NTD AO Saucer above zero; BUY STOP above signal-bar high"))
        # AO Zero-line Cross: a closed bar crosses from non-positive to positive.
        if ao[-2] <= 0 < ao[-1]:
            matches.append((SignalType.AO_ZERO_LINE, "NTD AO crossed above zero; BUY STOP above crossing-bar high"))
        # AO Twin Peaks: two confirmed troughs below zero; second is higher,
        # no zero-line crossing between them, and current AO turns green.
        if green[-1]:
            peaks = [
                i for i in range(max(1, current_i - max(3, int(max_age_bars))), current_i)
                if math.isfinite(ao[i - 1]) and math.isfinite(ao[i + 1])
                and ao[i] < 0 and ao[i] < ao[i - 1] and ao[i] < ao[i + 1]
            ]
            if peaks and peaks[-1] == current_i - 1:
                second = peaks[-1]
                # The previous AO trough must be the immediately preceding
                # confirmed trough. Skipping a disqualifying middle trough
                # manufactures a Twin Peaks formation that never existed.
                first = peaks[-2] if len(peaks) >= 2 else None
                if first is not None and ao[first] < ao[second]:
                    # A green signal bar crossing zero is a ZERO-LINE CROSS,
                    # not a Twin Peaks. Keep the entire formation below zero.
                    if all(math.isfinite(v) and v < 0 for v in ao[first:current_i + 1]):
                        matches.append((SignalType.AO_TWIN_PEAKS, "NTD bullish AO Twin Peaks below zero; BUY STOP above signal-bar high"))
    else:
        if (
            all(v < 0 for v in last3)
            and last3[0] < last3[1] > last3[2]
            and green[-2] and red[-1]
        ):
            # Mirrored bearish Saucer: rising/green middle bar followed by
            # the falling/red signal bar; the first bar's colour is irrelevant.
            matches.append((SignalType.AO_SAUCER, "NTD AO Saucer below zero; SELL STOP below signal-bar low"))
        if ao[-2] >= 0 > ao[-1]:
            matches.append((SignalType.AO_ZERO_LINE, "NTD AO crossed below zero; SELL STOP below crossing-bar low"))
        if red[-1]:
            peaks = [
                i for i in range(max(1, current_i - max(3, int(max_age_bars))), current_i)
                if math.isfinite(ao[i - 1]) and math.isfinite(ao[i + 1])
                and ao[i] > 0 and ao[i] > ao[i - 1] and ao[i] > ao[i + 1]
            ]
            if peaks and peaks[-1] == current_i - 1:
                second = peaks[-1]
                # Only the immediately preceding confirmed AO crest qualifies;
                # an earlier higher crest cannot bypass an unsuitable middle one.
                first = peaks[-2] if len(peaks) >= 2 else None
                if first is not None and ao[first] > ao[second]:
                    # The red signal bar must remain ABOVE zero, otherwise the
                    # current formation is a zero-line crossing.
                    if all(math.isfinite(v) and v > 0 for v in ao[first:current_i + 1]):
                        matches.append((SignalType.AO_TWIN_PEAKS, "NTD bearish AO Twin Peaks above zero; SELL STOP below signal-bar low"))

    # One candle cannot authorize duplicate entries from multiple AO families.
    # The NTD rules are distinct formations; if more than one coincides, retain
    # the most specific twin-peaks/saucer formation ahead of a zero-line cross.
    priority = {
        SignalType.AO_TWIN_PEAKS: 0,
        SignalType.AO_SAUCER: 1,
        SignalType.AO_ZERO_LINE: 2,
    }
    if not matches:
        return []
    signal_type, reason = min(matches, key=lambda item: priority[item[0]])
    return [SignalSpec.new(
        symbol=symbol,
        side=order_side,
        signal_type=signal_type,
        role=SignalRole.ADD_ON,
        timeframe=timeframe,
        signal_bar_time_ms=current_time,
        trigger_price=trigger,
        protective_reference=protective,
        trigger_buffer_ticks=1,
        teeth_at_detection=float(current.get("teeth_current", current.get("teeth_shifted", 0.0)) or 0.0),
        alligator_bullish=bool(current.get("bullish_alligator", False)),
        alligator_awake=bool(current.get("alligator_awake", False)),
        reason=reason,
        source_candle_index=current_i,
        expires_at_ms=_expiry(current_time, timeframe, max_age_bars),
    )]


def extract_ntd_ac_signal_specs(
    symbol: str,
    ind: pd.DataFrame,
    *,
    side: str,
    timeframe: str,
    tick_size: float,
    max_age_bars: int = 10,
) -> list[SignalSpec]:
    """Extract New Trading Dimensions AC acceleration entries.

    AC zero is not treated as a standalone signal. It changes the required
    number of same-direction AC bars: two when AC is with the trade (positive
    for LONG, negative for SHORT), three when it is against the trade. The
    sequence must begin after an opposite-colour AC bar.
    """
    direction = str(side or "").upper()
    if direction not in {"LONG", "SHORT"} or ind is None or len(ind) < 4:
        return []
    required = {"ac", "high", "low", "close"}
    if not required.issubset(ind.columns):
        return []

    ac = pd.to_numeric(ind["ac"], errors="coerce").to_numpy(dtype=float)
    if not math.isfinite(ac[-1]):
        return []
    green = [False] * len(ac)
    red = [False] * len(ac)
    for i in range(1, len(ac)):
        if math.isfinite(ac[i]) and math.isfinite(ac[i - 1]):
            green[i] = ac[i] > ac[i - 1]
            red[i] = ac[i] < ac[i - 1]

    is_long = direction == "LONG"
    order_side = "BUY" if is_long else "SELL"
    start = max(1, len(ind) - max(2, int(max_age_bars)))
    candidates: list[tuple[int, int]] = []

    for i in range(len(ind) - 1, start - 1, -1):
        same = green if is_long else red
        opposite = red if is_long else green
        if not same[i]:
            continue
        with_momentum = ac[i] > 0 if is_long else ac[i] < 0
        needed = 2 if with_momentum else 3
        run = 0
        j = i
        while j >= 0 and same[j]:
            run += 1
            j -= 1
        # The required sequence must follow a visible acceleration reversal.
        # If AC crosses zero on the second or third same-colour bar, that
        # crossing changes the count to two; the crossing bar remains the
        # signal bar, as described in NTD.
        crossed_with_trade = (
            i > 0
            and (
                (is_long and ac[i - 1] <= 0 < ac[i])
                or (not is_long and ac[i - 1] >= 0 > ac[i])
            )
        )
        count_ok = run == needed or (
            with_momentum and run in {2, 3} and crossed_with_trade
        )
        if count_ok and j >= 0 and opposite[j]:
            candidates.append((i, needed))

    if not candidates:
        return []
    signal_i, _ = candidates[0]
    row = ind.iloc[signal_i]
    current = ind.iloc[-1]
    tick = max(float(tick_size), 1e-12)
    high = float(row["high"])
    low = float(row["low"])
    current_close = float(current["close"])
    trigger = high + tick if is_long else low - tick
    if trigger <= 0 or not _trigger_still_pending(
        ind, signal_i, side=order_side, trigger_price=trigger
    ):
        return []

    signal_time = _row_time_ms(row)
    return [SignalSpec.new(
        symbol=symbol,
        side=order_side,
        signal_type=SignalType.AC_SIGNAL,
        role=SignalRole.ADD_ON,
        timeframe=timeframe,
        signal_bar_time_ms=signal_time,
        trigger_price=trigger,
        protective_reference=low if is_long else high,
        trigger_buffer_ticks=1,
        teeth_at_detection=float(row.get("teeth_current", row.get("teeth_shifted", 0.0)) or 0.0),
        alligator_bullish=bool(row.get("bullish_alligator", False)),
        alligator_awake=bool(row.get("alligator_awake", False)),
        reason=(
            "NTD AC acceleration signal; "
            + ("BUY STOP above" if is_long else "SELL STOP below")
            + " the signal bar"
        ),
        source_candle_index=signal_i,
        expires_at_ms=_expiry(signal_time, timeframe, max_age_bars),
    )]


def extract_ntd_zone_add_on_specs(
    symbol: str,
    ind: pd.DataFrame,
    *,
    side: str,
    timeframe: str,
    tick_size: float,
    max_age_bars: int = 1,
    exit_mode: bool = False,
) -> list[SignalSpec]:
    """NTD ch7: two matching zone bars + favourable CLOSE vs previous CLOSE.

    Add only through the fifth zone bar. The book enters at the completed
    close, not at a later stop above/below that bar. Execution is explicitly
    marked MARKET_ON_CLOSE; the active TC2 runtime must refuse this profile.
    """
    direction = str(side or "").upper()
    if direction not in {"LONG","SHORT"} or ind is None or len(ind)<3:
        return []
    required={"high","low","close","ao","ac"}
    gate_col="long_fractal_outside" if direction=="LONG" else "short_fractal_outside"
    if not required.issubset(ind.columns) or (not exit_mode and gate_col not in ind.columns):
        return []
    current,previous=ind.iloc[-1],ind.iloc[-2]
    if not exit_mode and not bool(current.get(gate_col,False)):
        return []
    sign=1 if direction=="LONG" else -1
    run=0
    for i in range(len(ind)-1,0,-1):
        a,b=ind.iloc[i-1],ind.iloc[i]
        values=[float(a["ao"]),float(b["ao"]),float(a["ac"]),float(b["ac"])]
        if not all(math.isfinite(v) for v in values) or sign*(values[1]-values[0])<=0 or sign*(values[3]-values[2])<=0:
            break
        run+=1
    if not 2<=run<=5:
        return []
    high,low,close=float(current["high"]),float(current["low"]),float(current["close"])
    prior_close=float(previous["close"])
    if not all(math.isfinite(v) and v>0 for v in (high,low,close,prior_close)) or high<max(low,close) or close<low:
        return []
    if sign*(close-prior_close)<=0:
        return []
    is_long=direction=="LONG"
    order_side="BUY" if is_long else "SELL"
    trigger=close
    protective=low if is_long else high
    teeth=float(current.get("teeth_current",current.get("teeth_shifted",0.0)) or 0.0)
    signal_time = _row_time_ms(current)
    if signal_time <= 0:
        return []
    return [SignalSpec.new(
        symbol=symbol,
        side=order_side,
        signal_type=SignalType.ZONE_ADD_ON,
        role=SignalRole.ADD_ON,
        timeframe=timeframe,
        signal_bar_time_ms=signal_time,
        trigger_price=trigger,
        protective_reference=protective,
        trigger_buffer_ticks=0,
        execution_semantics="MARKET_ON_CLOSE",
        teeth_at_detection=teeth,
        alligator_bullish=bool(current.get("bullish_alligator", False)),
        alligator_awake=bool(current.get("alligator_awake", False)),
        reason=(
            "NTD Green Zone add-on at favourable completed close; execution unsupported by TC2"
            if is_long else
            "NTD Red Zone add-on at favourable completed close; execution unsupported by TC2"
        ),
        source_candle_index=len(ind) - 1,
        expires_at_ms=_expiry(signal_time, timeframe, max_age_bars)+90_000,
    )]


def extract_ntd_fractal_signal_specs(
    symbol: str,
    ind: pd.DataFrame,
    *,
    side: str,
    timeframe: str,
    tick_size: float,
    max_age_bars: int = 80,
) -> list[SignalSpec]:
    """Return the NTD first external-fractal breakout as a conditional entry.

    The fractal must be confirmed by two right-hand closed bars, lie beyond the
    displaced Teeth/Balance line, and remain untriggered since confirmation.
    AO/AC NTD formations are add-ons, not substitutes for this initial gate.
    """
    direction = str(side or "").upper()
    if direction not in {"LONG", "SHORT"} or ind is None or len(ind) < 5:
        return []
    is_long = direction == "LONG"
    fractal_col = "fractal_up" if is_long else "fractal_down"
    required = {fractal_col, "high", "low", "close"}
    if not required.issubset(ind.columns) or not ({"teeth_current", "teeth_shifted"} & set(ind.columns)):
        return []

    tick = max(float(tick_size), 1e-12)
    current_close = float(ind.iloc[-1].get("close", 0.0) or 0.0)
    if not math.isfinite(current_close) or current_close <= 0:
        return []
    earliest_center = max(2, len(ind) - max(3, int(max_age_bars)) - 2)
    confirmed_col = "fractal_up_confirmed" if is_long else "fractal_down_confirmed"
    center_col = "confirmed_up_center_index" if is_long else "confirmed_down_center_index"
    group_start_col = "confirmed_up_group_start_index" if is_long else "confirmed_down_group_start_index"

    # New indicator frames enumerate confirmed events on the actual bar close;
    # legacy synthetic frames may only have centre markers, so retain a
    # strictly bounded compatibility path for those test/serialized frames.
    confirmations: list[tuple[int, int, int]] = []
    if confirmed_col in ind.columns and center_col in ind.columns:
        for confirmation_i in range(len(ind) - 1, max(-1, earliest_center - 1), -1):
            if not bool(ind.iloc[confirmation_i].get(confirmed_col, False)):
                continue
            center_value = ind.iloc[confirmation_i].get(center_col)
            if center_value is None or pd.isna(center_value):
                continue
            center_i = int(center_value)
            group_value = ind.iloc[confirmation_i].get(group_start_col)
            group_start_i = int(group_value) if group_value is not None and not pd.isna(group_value) else center_i
            confirmations.append((confirmation_i, center_i, group_start_i))
    else:
        confirmations = [
            (center_i + 2, center_i, center_i)
            for center_i in range(len(ind) - 3, earliest_center - 1, -1)
            if bool(ind.iloc[center_i].get(fractal_col, False))
        ]

    # Most recent still-pending external fractal is recoverable after restart.
    # A level that was already crossed after confirmation is deliberately not
    # re-armed: it may have triggered while the process was offline.
    for confirmation_i, center_i, group_start_i in confirmations:
        if confirmation_i >= len(ind) or center_i < 0 or group_start_i < 0 or group_start_i > center_i:
            continue
        center = ind.iloc[center_i]
        confirmation = ind.iloc[confirmation_i]
        level = float(center["high"] if is_long else center["low"])
        teeth = float(confirmation.get("teeth_current", confirmation.get("teeth_shifted", 0.0)) or 0.0)
        if not all(math.isfinite(v) and v > 0 for v in (level, teeth)):
            continue
        trigger = level + tick if is_long else level - tick
        current_teeth=float(ind.iloc[-1].get("teeth_current",ind.iloc[-1].get("teeth_shifted",0.0)) or 0.0)
        if not math.isfinite(current_teeth) or current_teeth<=0 or (
            trigger<=current_teeth if is_long else trigger>=current_teeth
        ):
            continue
        if trigger <= 0:
            continue
        # Include the confirmation candle itself: a fractal only becomes
        # knowable after its second right-hand bar closes, and that bar may
        # already have crossed the level intrabar. Never arm a stale trigger.
        since_confirmation = ind.iloc[confirmation_i:]
        if is_long:
            already_crossed = (
                current_close >= trigger
                or (not since_confirmation.empty and
                    bool((pd.to_numeric(since_confirmation["high"], errors="coerce") >= trigger).any()))
            )
        else:
            already_crossed = (
                current_close <= trigger
                or (not since_confirmation.empty and
                    bool((pd.to_numeric(since_confirmation["low"], errors="coerce") <= trigger).any()))
            )
        if already_crossed:
            continue

        signal_time = _row_time_ms(confirmation)
        if signal_time <= 0:
            continue
        group = ind.iloc[group_start_i:center_i + 1]
        protective = float(
            pd.to_numeric(group["low" if is_long else "high"], errors="coerce").min()
            if is_long
            else pd.to_numeric(group["high"], errors="coerce").max()
        )
        if not math.isfinite(protective) or protective <= 0:
            continue
        return [SignalSpec.new(
            symbol=symbol,
            side="BUY" if is_long else "SELL",
            signal_type=SignalType.FRACTAL,
            role=SignalRole.ENTRY,
            timeframe=timeframe,
            signal_bar_time_ms=signal_time,
            trigger_price=trigger,
            protective_reference=protective,
            trigger_buffer_ticks=1,
            teeth_at_detection=teeth,
            alligator_bullish=bool(confirmation.get("bullish_alligator", False)),
            alligator_awake=bool(confirmation.get("alligator_awake", False)),
            reason=(
                "NTD first external confirmed fractal; conditional "
                + ("BUY STOP above" if is_long else "SELL STOP below")
                + " the fractal extreme"
            ),
            source_candle_index=center_i,
            expires_at_ms=_expiry(signal_time, timeframe, max_age_bars),
        )]
    return []


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
    point_zero_confirmed: bool = False,
    point_zero_score: int = 0,
    require_point_zero_for_reversal: bool = False,
) -> list[SignalSpec]:
    """Extract all presently armable LONG signals from closed candles."""
    if ind is None or ind.empty:
        return []

    specs: list[SignalSpec] = []
    current = ind.iloc[-1]
    current_close = float(current.get("close", 0.0) or 0.0)
    current_teeth = float(current.get("teeth_current", current.get("teeth_shifted", 0.0)) or 0.0)
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
    if reversal is not None and (point_zero_confirmed or not require_point_zero_for_reversal):
        i, trigger_base, protective = reversal
        trigger = trigger_base + tick
        if _trigger_still_pending(ind, i, side="BUY", trigger_price=trigger):
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
                    protective_reference=protective - tick,
                    trigger_buffer_ticks=1,
                    invalidation_price=float(wave_invalidation_price or (protective - tick)),
                    teeth_at_detection=float(row.get("teeth_current", row.get("teeth_shifted", 0.0)) or 0.0),
                    alligator_bullish=bool(row.get("bullish_alligator", False)),
                    alligator_awake=bool(row.get("alligator_awake", False)),
                    angulation_score=score,
                    wave_confidence=float(wave_confidence),
                    wave_exhaustion_risk=float(wave_exhaustion_risk),
                    htf_confirmed=bool(htf_confirmed),
                    point_zero_confirmed=bool(point_zero_confirmed),
                    point_zero_score=int(point_zero_score),
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
        if _trigger_still_pending(ind, i, side="BUY", trigger_price=trigger):
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
                    protective_reference=protective - tick,
                    trigger_buffer_ticks=1,
                    invalidation_price=float(wave_invalidation_price or (protective - tick)),
                    teeth_at_detection=float(row.get("teeth_current", row.get("teeth_shifted", 0.0)) or 0.0),
                    alligator_bullish=bool(row.get("bullish_alligator", False)),
                    alligator_awake=bool(row.get("alligator_awake", False)),
                    wave_confidence=float(wave_confidence),
                    wave_exhaustion_risk=float(wave_exhaustion_risk),
                    htf_confirmed=bool(htf_confirmed),
                    point_zero_confirmed=bool(point_zero_confirmed),
                    point_zero_score=int(point_zero_score),
                    context_versions=versions,
                    reason="WM2 Super AO: third green AO bar; BUY STOP above corresponding price bar",
                    source_candle_index=i,
                    expires_at_ms=_expiry(_row_time_ms(row), timeframe, 20),
                )
            )

    # TC2 continuation: three same-colour price bars with AO moving in
    # the same direction form an add-on, never a standalone initial entry.
    three_bar = _latest_three_price_bar_signal(ind, side="LONG")
    if three_bar is not None:
        i, trigger_base, protective = three_bar
        trigger = trigger_base + tick
        if _trigger_still_pending(ind, i, side="BUY", trigger_price=trigger):
            row = ind.iloc[i]
            specs.append(
                SignalSpec.new(
                    symbol=symbol,
                    side="BUY",
                    signal_type=SignalType.THREE_GREEN_BARS,
                    role=SignalRole.ADD_ON,
                    timeframe=timeframe,
                    signal_bar_time_ms=_row_time_ms(row),
                    trigger_price=trigger,
                    protective_reference=protective - tick,
                    trigger_buffer_ticks=1,
                    teeth_at_detection=float(row.get("teeth_current", row.get("teeth_shifted", 0.0)) or 0.0),
                    alligator_bullish=bool(row.get("bullish_alligator", False)),
                    alligator_awake=bool(row.get("alligator_awake", False)),
                    htf_confirmed=bool(htf_confirmed),
                    context_versions=versions,
                    reason="TC2 continuation add-on: three bullish price bars with rising AO; BUY STOP above third bar",
                    source_candle_index=i,
                    expires_at_ms=_expiry(_row_time_ms(row), timeframe, 3),
                )
            )

    fractal = _latest_confirmed_fractal(ind, side="LONG", current_teeth=current_teeth, current_close=current_close, tick_size=tick)
    if fractal is not None:
        confirmation_i, center_i, trigger_base, protective, teeth = fractal
        # Formation can be anywhere; trigger validity belongs to current
        # price/Teeth.  At arm time the trigger must still be above Teeth.
        trigger = trigger_base + tick
        current_trigger_valid = trigger > max(current_teeth, 0.0)
        if current_close < trigger and current_trigger_valid:
            row = ind.iloc[center_i]
            confirmation_time = _row_time_ms(ind.iloc[confirmation_i])
            specs.append(
                SignalSpec.new(
                    symbol=symbol,
                    side="BUY",
                    signal_type=SignalType.FRACTAL,
                    role=SignalRole.ENTRY,
                    timeframe=timeframe,
                    # The pivot candle is the formation; the signal becomes
                    # knowable only when the second right-hand bar closes.
                    signal_bar_time_ms=confirmation_time,
                    trigger_price=trigger,
                    protective_reference=protective - tick,
                    trigger_buffer_ticks=1,
                    invalidation_price=float(wave_invalidation_price or (protective - tick)),
                    teeth_at_detection=float(teeth),
                    alligator_bullish=bool(current.get("bullish_alligator", False)),
                    alligator_awake=bool(current.get("alligator_awake", False)),
                    wave_confidence=float(wave_confidence),
                    wave_exhaustion_risk=float(wave_exhaustion_risk),
                    htf_confirmed=bool(htf_confirmed),
                    context_versions=versions,
                    reason="WM3 buy fractal; trigger only while price/trigger remains above Teeth",
                    source_candle_index=center_i,
                    expires_at_ms=0,  # TC2 ch11 PDF149: trigger or valid replacement, no age expiry.
                    setup_context={"validity_policy":"UNBROKEN_UNSUPERSEDED_FRACTAL",
                        "h1_closed_at_ms":_row_time_ms(current)+_timeframe_ms(timeframe)},
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
    point_zero_confirmed: bool = False,
    point_zero_score: int = 0,
    require_point_zero_for_reversal: bool = False,
) -> list[SignalSpec]:
    """Extract currently armable SHORT Williams signals from closed candles."""
    if ind is None or ind.empty:
        return []

    current = ind.iloc[-1]
    current_close = float(current.get("close", 0.0) or 0.0)
    current_teeth = float(current.get("teeth_current", current.get("teeth_shifted", 0.0)) or 0.0)
    if current_close <= 0:
        return []

    tick = max(float(tick_size), 1e-12)
    versions = dict(context_versions or {})
    specs: list[SignalSpec] = []

    reversal = _latest_reversal(ind, side="SHORT", max_age_bars=max_reversal_age_bars)
    if reversal is not None and (point_zero_confirmed or not require_point_zero_for_reversal):
        i, trigger_base, protective = reversal
        trigger = trigger_base - tick
        if _trigger_still_pending(ind, i, side="SELL", trigger_price=trigger):
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
                    protective_reference=protective + tick,
                    trigger_buffer_ticks=1,
                    invalidation_price=float(wave_invalidation_price or (protective + tick)),
                    teeth_at_detection=float(row.get("teeth_current", row.get("teeth_shifted", 0.0)) or 0.0),
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
        if _trigger_still_pending(ind, i, side="SELL", trigger_price=trigger):
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
                    protective_reference=protective + tick,
                    trigger_buffer_ticks=1,
                    invalidation_price=float(wave_invalidation_price or (protective + tick)),
                    teeth_at_detection=float(row.get("teeth_current", row.get("teeth_shifted", 0.0)) or 0.0),
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

    # Mirrored TC2 continuation add-on for a short campaign.
    three_bar = _latest_three_price_bar_signal(ind, side="SHORT")
    if three_bar is not None:
        i, trigger_base, protective = three_bar
        trigger = trigger_base - tick
        if _trigger_still_pending(ind, i, side="SELL", trigger_price=trigger):
            row = ind.iloc[i]
            specs.append(
                SignalSpec.new(
                    symbol=symbol,
                    side="SELL",
                    signal_type=SignalType.THREE_GREEN_BARS,
                    role=SignalRole.ADD_ON,
                    timeframe=timeframe,
                    signal_bar_time_ms=_row_time_ms(row),
                    trigger_price=trigger,
                    protective_reference=protective + tick,
                    trigger_buffer_ticks=1,
                    teeth_at_detection=float(row.get("teeth_current", row.get("teeth_shifted", 0.0)) or 0.0),
                    alligator_bullish=bool(row.get("bearish_alligator", False)),
                    alligator_awake=bool(row.get("alligator_awake", False)),
                    htf_confirmed=bool(htf_confirmed),
                    context_versions=versions,
                    reason="TC2 continuation add-on: three bearish price bars with falling AO; SELL STOP below third bar",
                    source_candle_index=i,
                    expires_at_ms=_expiry(_row_time_ms(row), timeframe, 3),
                )
            )

    fractal = _latest_confirmed_fractal(ind, side="SHORT", current_teeth=current_teeth, current_close=current_close, tick_size=tick)
    if fractal is not None:
        confirmation_i, center_i, trigger_base, protective, teeth = fractal
        trigger = trigger_base - tick
        current_trigger_valid = current_teeth > 0 and trigger < current_teeth
        if current_close > trigger and current_trigger_valid:
            row = ind.iloc[center_i]
            confirmation_time = _row_time_ms(ind.iloc[confirmation_i])
            specs.append(
                SignalSpec.new(
                    symbol=symbol,
                    side="SELL",
                    signal_type=SignalType.FRACTAL,
                    role=SignalRole.ENTRY,
                    timeframe=timeframe,
                    # Order chronology uses confirmation time, never the pivot's
                    # earlier timestamp, so Wise-Men sequencing cannot look ahead.
                    signal_bar_time_ms=confirmation_time,
                    trigger_price=trigger,
                    protective_reference=protective + tick,
                    trigger_buffer_ticks=1,
                    invalidation_price=float(wave_invalidation_price or (protective + tick)),
                    teeth_at_detection=float(teeth),
                    alligator_bullish=bool(current.get("bearish_alligator", False)),
                    alligator_awake=bool(current.get("alligator_awake", False)),
                    wave_confidence=float(wave_confidence),
                    wave_exhaustion_risk=float(wave_exhaustion_risk),
                    htf_confirmed=bool(htf_confirmed),
                    context_versions=versions,
                    reason="WM3 sell fractal; trigger only while price/trigger remains below Teeth",
                    source_candle_index=center_i,
                    expires_at_ms=0,  # TC2 ch11 PDF149: trigger or valid replacement, no age expiry.
                    setup_context={"validity_policy":"UNBROKEN_UNSUPERSEDED_FRACTAL",
                        "h1_closed_at_ms":_row_time_ms(current)+_timeframe_ms(timeframe)},
                )
            )

    unique: dict[tuple[str, int], SignalSpec] = {}
    for spec in specs:
        unique[(spec.signal_type.value, spec.signal_bar_time_ms)] = spec
    return sorted(unique.values(), key=lambda x: (x.signal_bar_time_ms, x.signal_type.value))
