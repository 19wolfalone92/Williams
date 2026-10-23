"""NTD close-event execution bounds; project risk controls, not book formulas."""
from decimal import ROUND_CEILING, ROUND_FLOOR, Decimal
from math import isfinite

from williams_edition_rules import market_on_close_reason

CLOSE_MAX_DEVIATION = .0015
FAMILIES = {"FRACTAL", "AO_SAUCER", "AO_ZERO_LINE", "AO_TWIN_PEAKS",
            "AC_SIGNAL", "ZONE_ADD_ON", "BALANCE_LINE"}


def close_entry_bound(side, reference, tick):
    if side not in {"BUY", "SELL"} or any(not isfinite(v) or v <= 0 for v in (reference, tick)):
        raise RuntimeError("NTD_CLOSE_BOUND_UNVERIFIED")
    factor = Decimal("1.0015") if side == "BUY" else Decimal("0.9985")
    step = Decimal(str(tick))
    bound = (Decimal(str(reference))*factor/step).to_integral_value(
        rounding=ROUND_FLOOR if side == "BUY" else ROUND_CEILING)*step
    if bound <= 0 or (bound < Decimal(str(reference)) if side == "BUY" else bound > Decimal(str(reference))):
        raise RuntimeError("NTD_CLOSE_BOUND_UNVERIFIED")
    return float(bound)


def require_close_entry(side, signal_at, reference, bound, now, contract, mark, quote, tick):
    expected = close_entry_bound(side, reference, tick)
    if not isfinite(bound) or abs(bound-expected) > tick*.11:
        raise RuntimeError("NTD_CLOSE_BOUND_CHANGED")
    for price in (contract, mark):
        reason = market_on_close_reason(signal_at, 3_600_000, now, reference, price)
        if reason:
            raise RuntimeError(reason)
    try:
        bid, ask = float(quote["bidPrice"]), float(quote["askPrice"])
    except (KeyError, ValueError, TypeError):
        raise RuntimeError("NTD_CLOSE_EXECUTABLE_QUOTE_UNVERIFIED") from None
    if any(not isfinite(v) or v <= 0 for v in (bid, ask)) or ask < bid:
        raise RuntimeError("NTD_CLOSE_EXECUTABLE_QUOTE_UNVERIFIED")
    executable = ask if side == "BUY" else bid
    if abs(executable/reference-1) > CLOSE_MAX_DEVIATION or (executable > bound if side == "BUY" else executable < bound):
        raise RuntimeError("NTD_CLOSE_EXECUTABLE_QUOTE_OUTSIDE_BOUND")
