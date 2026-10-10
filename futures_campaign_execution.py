"""USDⓈ-M Futures campaign execution for directional Williams signals.

This module is deliberately separate from the legacy Spot trader. The domain
direction is LONG/SHORT; only this adapter maps that direction to exchange-side
BUY/SELL. All order/algo-order mutations pass through the shared ExecutionBarrier.
"""
from __future__ import annotations

import json
import math
import os
import time
import uuid
from dataclasses import replace
from typing import Any

from campaign_engine import CampaignEngine
from campaign_model import (
    CampaignEventType,
    CampaignState,
    PendingOrderRecord,
    SignalRole,
    SignalSpec,
    SignalState,
    SignalType,
)
from execution_barrier import ExecutionBarrier, OrderIntent
from risk_engine import RiskEngine


class FuturesCampaignExecutionError(RuntimeError):
    pass


OPEN_CAMPAIGN_STATES = {
    CampaignState.ENTRY_PENDING,
    CampaignState.ENTRY_TRIGGERED,
    CampaignState.OPEN_INITIAL,
    CampaignState.ADD_ON_PENDING,
    CampaignState.POSITION_EXPANDING,
    CampaignState.TREND_ACTIVE,
    CampaignState.TRAILING,
    CampaignState.EXHAUSTION_WATCH,
    CampaignState.EXIT_SIGNALLED,
    CampaignState.EXIT_PENDING,
    CampaignState.RECONCILE_REQUIRED,
}


def tc2_price_bar_trailing_candidate(
    direction: str,
    lows: list[float],
    highs: list[float],
    tick_size: float,
    trailing_bars: int = 3,
) -> dict[str, float | int | str]:
    """Calculate a price-bar structural candidate; callers must supply closed bars.

    This is a selected TC2 exit-policy implementation, not a claim that every
    Williams edition prescribes the same trailing window. Exchange tick rounding
    is separate and is applied by the Futures adapter before submission.
    """
    side = str(direction or "").strip().upper()
    if side not in {"LONG", "SHORT"}:
        raise ValueError("TC2 trailing direction must be LONG or SHORT")
    if trailing_bars not in {3, 5}:
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
    if any(low > high for low, high in zip(selected_lows, selected_highs)):
        raise ValueError("TC2 trailing contains a bar with low above high")

    extreme = min(selected_lows) if side == "LONG" else max(selected_highs)
    raw_stop = extreme - tick if side == "LONG" else extreme + tick
    if not math.isfinite(raw_stop) or raw_stop <= 0.0:
        raise ValueError("TC2 raw structural stop is invalid")
    return {
        "direction": side,
        "trailing_bars": trailing_bars,
        "structural_extreme": extreme,
        "raw_stop_price": raw_stop,
    }


def signal_direction(signal: SignalSpec) -> str:
    direction = str(getattr(signal, "direction", "") or "").upper()
    if direction not in {"LONG", "SHORT"}:
        direction = {
            "BUY": "LONG",
            "LONG": "LONG",
            "SELL": "SHORT",
            "SHORT": "SHORT",
        }.get(str(signal.side).upper(), "")
    expected_side = "BUY" if direction == "LONG" else "SELL" if direction == "SHORT" else ""
    if not expected_side:
        raise FuturesCampaignExecutionError("Signal must declare LONG or SHORT direction")
    if str(signal.side).upper() not in {expected_side, direction}:
        raise FuturesCampaignExecutionError(
            f"Signal side/direction conflict: side={signal.side} direction={direction}"
        )
    return direction
