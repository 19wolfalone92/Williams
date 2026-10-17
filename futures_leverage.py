"""Validated leverage settings and conservative isolated-margin admission.

The 125x ceiling is an application setting, not a claim about exchange limits.
Account-specific Binance brackets remain authoritative for every entry.
"""
from __future__ import annotations

import math
import re

MAX_CONFIGURED_LEVERAGE = 125


def liquidation_protection_status(side, stop, liquidation_price, buffer=0.01):
    """Missing exchange data blocks admission, but cannot authorize an exit."""
    try:
        stop, liquidation_price, buffer = float(stop), float(liquidation_price), float(buffer)
    except (TypeError, ValueError, OverflowError):
        return "UNKNOWN"
    if (side not in {"BUY", "SELL"} or
            not all(math.isfinite(x) for x in (stop, liquidation_price, buffer)) or
            stop <= 0 or liquidation_price <= 0 or not 0 <= buffer < 1):
        return "UNKNOWN"
    gap = (stop - liquidation_price) / stop if side == "BUY" else (liquidation_price - stop) / stop
    return "PASS" if gap > 0 and gap + 1e-12 >= buffer else "UNSAFE"


def parse_leverage(value) -> int:
    if isinstance(value, bool) or not re.fullmatch(r"[0-9]+", str(value).strip()):
        raise ValueError("FUTURES_LEVERAGE must be an integer in 1..125")
    result = int(str(value).strip())
    if not 1 <= result <= MAX_CONFIGURED_LEVERAGE:
        raise ValueError("FUTURES_LEVERAGE must be an integer in 1..125")
    return result


def parse_symbol_leverages(raw: str, allowed_symbols) -> tuple[tuple[str, int], ...]:
    result = {}
    if not str(raw).strip():
        return ()
    for item in str(raw).split(","):
        parts = item.strip().split(":")
        if len(parts) != 2:
            raise ValueError("FUTURES_SYMBOL_LEVERAGES requires SYMBOL:LEVERAGE pairs")
        symbol = parts[0].strip().upper()
        if symbol not in allowed_symbols or symbol in result:
            raise ValueError("FUTURES_SYMBOL_LEVERAGES contains an unknown or duplicate symbol")
        result[symbol] = parse_leverage(parts[1])
    return tuple(sorted(result.items()))


def leverage_bracket(payload, symbol: str, notional: float) -> tuple[int, float]:
    """Return the worst applicable leverage/MMR at the entry or stop notional.

    Floors are inclusive and caps exclusive. Missing, overlapping, gapped or
    malformed tiers fail closed. Ignore `cum` to overestimate maintenance margin.
    Binance's returned notional thresholds are used as supplied.
    """
    if not math.isfinite(notional) or notional <= 0:
        raise ValueError("Invalid Futures notional for leverage brackets")
    rows = payload if isinstance(payload, list) else [payload]
    matches = [row for row in rows if isinstance(row, dict) and row.get("symbol") == symbol]
    if len(matches) != 1:
        raise ValueError("Missing or ambiguous Futures leverage bracket symbol")
    tiers = matches[0].get("brackets")
    if not isinstance(tiers, list) or not tiers:
        raise ValueError("Missing Futures leverage brackets")
    previous_cap = 0.0
    selected = None
    worst_mmr = 0.0
    for tier in tiers:
        try:
            floor = float(tier["notionalFloor"])
            cap = float(tier["notionalCap"])
            mmr = float(tier["maintMarginRatio"])
            raw_leverage = tier["initialLeverage"]
            if isinstance(raw_leverage, bool) or not re.fullmatch(r"[0-9]+", str(raw_leverage)):
                raise ValueError()
            maximum = int(raw_leverage)
        except (KeyError, TypeError, ValueError, OverflowError) as exc:
            raise ValueError("Malformed Futures leverage bracket") from exc
        if (not all(math.isfinite(x) for x in (floor, cap, mmr))
                or floor != previous_cap or cap <= floor or not 0 <= mmr < 1 or maximum < 1):
            raise ValueError("Invalid Futures leverage bracket limits")
        worst_mmr = max(worst_mmr, mmr)
        if floor <= notional < cap:
            selected = (maximum, worst_mmr)
        previous_cap = cap
    if selected is None:
        raise ValueError("FUTURES_LEVERAGE_NOTIONAL_CAP: exposure exceeds exchange brackets")
    return selected


def validate_isolated_leverage(payload, symbol, leverage, notional, entry, stop,
                               cost_fraction=0.0035, liquidation_buffer=0.01):
    leverage = parse_leverage(leverage)
    if (not all(math.isfinite(x) for x in (notional, entry, stop, cost_fraction, liquidation_buffer))
            or min(notional, entry, stop) <= 0 or cost_fraction < 0 or liquidation_buffer < 0):
        raise ValueError("Invalid Futures leverage risk inputs")
    stop_notional = notional * stop / entry
    maximum, mmr = leverage_bracket(payload, symbol, max(notional, stop_notional))
    if leverage > maximum:
        raise ValueError(f"FUTURES_LEVERAGE_LIMIT: {symbol} allows at most {maximum}x for this exposure")
    loss_and_costs = notional * (abs(entry - stop) / entry + cost_fraction)
    maintenance = max(notional, stop_notional) * (mmr + liquidation_buffer)
    if notional / leverage <= loss_and_costs + maintenance:
        raise ValueError("FUTURES_LEVERAGE_STOP_BUFFER: isolated margin cannot cover structural stop, costs and maintenance")
    return maximum, mmr
