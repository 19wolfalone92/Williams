"""Pure, deterministic rules shared by TC2 execution and research replay.

This module deliberately contains no exchange client, persistence, or I/O code,
so Python Futures execution and the campaign backtester can consume the same
price-bar stop calculation.
"""
from __future__ import annotations

import math
from typing import Any


TC2_CORE_PROFILE = "TC2_THREE_WISE_MEN"
TC2_DECISION_TIMEFRAME = "1h"
TC2_TRAILING_WINDOWS = (3, 5)


def tc2_price_bar_trailing_candidate(
    direction: str,
    lows: list[float],
    highs: list[float],
    tick_size: float,
    trailing_bars: int = 3,
) -> dict[str, Any]:
    """Return one exchange tick beyond the chosen closed price-bar extreme.

    This is the raw source-profile candidate, before exchange price-filter
    rounding and before checking whether it is a safe, tightening stop.
    LONG uses the lowest low; SHORT uses the highest high, over 3 or 5 bars.
    """
    side = str(direction or "").strip().upper()
    if side not in {"LONG", "SHORT"}:
        raise ValueError("TC2 trailing direction must be LONG or SHORT")
    if trailing_bars not in TC2_TRAILING_WINDOWS:
        raise ValueError("TC2 trailing_bars must be 3 or 5")
    try:
        tick = float(tick_size)
    except (TypeError, ValueError, OverflowError) as exc:
        raise ValueError("TC2 tick size must be numeric") from exc
    if not math.isfinite(tick) or tick <= 0.0:
        raise ValueError("TC2 tick size must be finite and positive")
    if len(lows) != len(highs) or len(lows) < trailing_bars:
        raise ValueError("TC2 trailing requires equal-length high/low lists with enough closed bars")

    try:
        selected_lows = [float(value) for value in lows[-trailing_bars:]]
        selected_highs = [float(value) for value in highs[-trailing_bars:]]
    except (TypeError, ValueError, OverflowError) as exc:
        raise ValueError("TC2 trailing contains non-numeric OHLC values") from exc
    if not all(math.isfinite(value) and value > 0.0 for value in selected_lows):
        raise ValueError("TC2 trailing lows contain invalid values")
    if not all(math.isfinite(value) and value > 0.0 for value in selected_highs):
        raise ValueError("TC2 trailing highs contain invalid values")

    extreme = min(selected_lows) if side == "LONG" else max(selected_highs)
    raw_stop = extreme - tick if side == "LONG" else extreme + tick
    if not math.isfinite(raw_stop) or raw_stop <= 0.0:
        raise ValueError("TC2 raw structural stop is invalid")
    return {
        "direction": side,
        "trailing_bars": int(trailing_bars),
        "structural_extreme": extreme,
        "raw_stop_price": raw_stop,
    }
