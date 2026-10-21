"""TC2, PDF p.164 / printed p.149: profitable campaign, 1:5:4:3:2 quantities.

The book gives contract/share ratios, not risk percentages. Risk, exchange lot
filters and margin remain separate vetoes: they must never silently resize a
book tranche. A durable policy pin prevents reinterpretation of older campaigns.
"""
from decimal import Decimal, InvalidOperation
import math

POLICY = "TC2_BOOK_QUANTITIES_1_5_4_3_2_V1"
WEIGHTS = (1, 5, 4, 3, 2)


def _positive(value):
    try:
        result = Decimal(str(value))
    except (InvalidOperation, ValueError, TypeError):
        raise RuntimeError("TC2_PYRAMID_INVALID_QUANTITY") from None
    if not result.is_finite() or result <= 0:
        raise RuntimeError("TC2_PYRAMID_INVALID_QUANTITY")
    return result


def initial_unit_risk_pct(initial_cap, campaign_cap, entry_cap):
    """Engineering ceiling for the small first unit; never a Williams formula.

    Reserve nominal room for 15 units and for the largest, five-unit tranche.
    Future price/SL changes can still veto a later book tranche.
    """
    values = (initial_cap, campaign_cap, entry_cap)
    if any(not math.isfinite(v) or v <= 0 for v in values):
        return 0.0
    return min(initial_cap, campaign_cap / sum(WEIGHTS), entry_cap / max(WEIGHTS))


def require_profitable_position(side, actual_entry, current_price, trigger):
    prices = tuple(_positive(v) for v in (actual_entry, current_price, trigger))
    entry, mark, trigger = prices
    if side not in {"BUY", "SELL"}:
        raise RuntimeError("TC2_ADDITION_DIRECTION_UNPROVEN")
    if not (mark > entry and trigger > entry if side == "BUY" else mark < entry and trigger < entry):
        raise RuntimeError("TC2_ADDITION_REQUIRES_PROFITABLE_POSITION")


def next_tranche(plan, *, owned_quantity, additions):
    """Return the exact next quantity, using only exchange-confirmed fills.

    A partial add, a manual/partial reduction, a missing fill, or a changed
    policy blocks further additions. Protection continues independently.
    """
    if not isinstance(plan,dict) or plan.get("policy") != POLICY:
        raise RuntimeError("TC2_PYRAMID_POLICY_UNRECOGNIZED")
    unit = _positive(plan.get("unit_quantity"))
    fills = plan.get("completed_quantities", [])
    if not isinstance(fills, list) or len(fills) != additions or len(fills) > 4:
        raise RuntimeError("TC2_PYRAMID_FILL_LEDGER_UNPROVEN")
    total = unit
    for i, fill in enumerate(fills, 1):
        actual = _positive(fill)
        if actual != unit * WEIGHTS[i]:
            raise RuntimeError("TC2_PYRAMID_PARTIAL_OR_CHANGED_TRANCHE")
        total += actual
    # Campaign storage uses floats; tolerate only their serialization noise,
    # never a meaningful exchange lot or an unowned quantity change.
    if abs(_positive(owned_quantity) - total) > max(Decimal("1e-12"), total * Decimal("1e-12")):
        raise RuntimeError("TC2_PYRAMID_OWNED_QUANTITY_CHANGED")
    if len(fills) == 4:
        raise RuntimeError("TC2_PYRAMID_COMPLETE_15_UNITS")
    return unit * WEIGHTS[len(fills) + 1]


def cap_book_tranche(required, admissible):
    """A cap rejects a book tranche; it cannot turn 5 units into 1.7 units."""
    quantity = _positive(required)
    if quantity > _positive(admissible):
        raise RuntimeError("TC2_PYRAMID_TRANCHE_EXCEEDS_RISK_OR_MARGIN_CAP")
    return float(quantity)
