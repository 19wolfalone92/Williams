"""Course PDF78-84 contract proportions on the exchange quantity grid.

These calculations do not establish a wave degree or authorize new risk. A
cap can reject a tranche; it must never silently change a book proportion.
Only a complete, attributed exchange fill can confirm a proposed stage.
"""
from decimal import Decimal, InvalidOperation, localcontext, ROUND_FLOOR
from math import lcm

from williams_course_campaign import course_target

POLICY = "COURSE_EXACT_CONTRACT_UNITS_V1"
_CORRECTION = {"A_ENTRY": 3, "A_REDUCE": 1, "C_ADD": 4, "CORRECTION_EXIT": 0}
_NEXT = {"W1": ("W2",), "W2": ("W3",), "W3": ("W3_EXTENSION", "W4"),
         "W3_EXTENSION": ("W4",), "W4": ("W5",), "W5": ("EXIT",),
         "A_ENTRY": ("A_REDUCE",), "A_REDUCE": ("C_ADD",), "C_ADD": ("CORRECTION_EXIT",)}


def _decimal(value):
    if isinstance(value, bool):
        raise ValueError("COURSE_QUANTITY_MALFORMED")
    try:
        number = Decimal(str(value))
    except (InvalidOperation, ValueError, TypeError):
        raise ValueError("COURSE_QUANTITY_MALFORMED") from None
    # Bound external decimal input before exact multiplication/remainders.
    if not number.is_finite() or len(number.as_tuple().digits) > 40 or abs(number.as_tuple().exponent) > 24:
        raise ValueError("COURSE_QUANTITY_MALFORMED")
    return number


def _plain(value):
    return format(value, "f").rstrip("0").rstrip(".") if "." in format(value, "f") else format(value, "f")


def _units(stage, ratio):
    return _CORRECTION[stage] if stage in _CORRECTION else course_target(stage, ratio)


def course_quantity_step(lot_step, market_step=0):
    """One unit must remain divisible by BOTH entry and reduction lot grids."""
    a, b = _decimal(lot_step), _decimal(market_step)
    if a <= 0 or b < 0:
        raise ValueError("COURSE_QUANTITY_GRID_UNVERIFIED")
    if not b:
        return a
    with localcontext() as context:
        context.prec = 100
        scale = 10 ** max(0, -a.as_tuple().exponent, -b.as_tuple().exponent)
        return Decimal(lcm(int(a * scale), int(b * scale))) / scale


def _blocked(reason):
    return {"status": "BLOCKED", "reason": reason, "policy": POLICY, "can_place_orders": False}


def course_initial_quantity(risk_cap, lot_step, market_step=0, stage="W1"):
    """Allocate THREE whole exchange-grid units below an independently set cap.

    W1 means the illustrated campaign stage, not the current H1 wave label.
    Identifying its parent/child origin remains a separate source obligation.
    """
    try:
        with localcontext() as context:
            context.prec = 100
            if stage not in ("W1", "A_ENTRY"):
                return _blocked("COURSE_INITIAL_STAGE_MUST_START_WITH_THREE_UNITS")
            cap, step = _decimal(risk_cap), course_quantity_step(lot_step, market_step)
            if cap <= 0:
                return _blocked("COURSE_INITIAL_RISK_CAP_UNVERIFIED")
            unit = (cap / (3 * step)).to_integral_value(rounding=ROUND_FLOOR) * step
            if unit <= 0:
                return _blocked("COURSE_THREE_UNITS_BELOW_EXCHANGE_GRID")
            return {"status": "EXACT_COURSE_QUANTITY", "policy": POLICY, "action": "ENTRY",
                    "from_stage": "", "stage": stage, "unit_quantity": _plain(unit),
                    "before_quantity": "0", "quantity": _plain(3 * unit),
                    "target_quantity": _plain(3 * unit), "target_units": 3,
                    "quantity_step": _plain(step), "can_place_orders": False}
    except (ValueError, InvalidOperation):
        return _blocked("COURSE_QUANTITY_OR_GRID_MALFORMED")


def course_stage_quantity(unit_quantity, from_stage, stage, owned_quantity, lot_step,
                          market_step=0, *, risk_cap=None, w3_to_w1_ratio=None):
    """Exact required delta. Never round an established unit, target or delta."""
    try:
        with localcontext() as context:
            context.prec = 100
            if stage not in _NEXT.get(from_stage, ()):
                return _blocked("COURSE_NON_SEQUENTIAL_QUANTITY_STAGE")
            units, target_units = _units(from_stage, w3_to_w1_ratio), _units(stage, w3_to_w1_ratio)
            if units is None or target_units is None:
                return _blocked("COURSE_STAGE_OR_W3_W1_RATIO_UNCONFIRMED")
            unit, owned, step = _decimal(unit_quantity), _decimal(owned_quantity), course_quantity_step(lot_step, market_step)
            if unit <= 0 or owned <= 0:
                return _blocked("COURSE_ACTUAL_UNIT_OR_OWNED_QUANTITY_UNVERIFIED")
            if unit % step:
                return _blocked("COURSE_UNIT_NOT_ON_EXCHANGE_GRID")
            if owned != unit * units:
                return _blocked("COURSE_OWNED_STAGE_QUANTITY_CHANGED")
            target = unit * target_units
            delta = target - owned
            if not delta:
                return _blocked("COURSE_STAGE_HAS_NO_QUANTITY_CHANGE")
            if delta > 0:
                if risk_cap is None or _decimal(risk_cap) < delta:
                    return _blocked("COURSE_FULL_TRANCHE_EXCEEDS_RISK_OR_MARGIN_CAP")
            result = {"status": "EXACT_COURSE_QUANTITY", "policy": POLICY,
                    "action": "ADD" if delta > 0 else "REDUCE", "from_stage": from_stage,
                    "stage": stage, "unit_quantity": _plain(unit), "before_quantity": _plain(owned),
                    "quantity": _plain(abs(delta)), "target_quantity": _plain(target),
                    "target_units": target_units, "quantity_step": _plain(step), "can_place_orders": False}
            if w3_to_w1_ratio is not None:
                result["w3_to_w1_ratio"] = w3_to_w1_ratio
            return result
    except (ValueError, InvalidOperation):
        return _blocked("COURSE_QUANTITY_OR_GRID_MALFORMED")


def course_owned_quantity_blocker(unit_quantity, stage, owned_quantity, lot_step,
                                  market_step=0, w3_to_w1_ratio=None):
    try:
        with localcontext() as context:
            context.prec = 100
            units = _units(stage, w3_to_w1_ratio)
            unit, owned, step = _decimal(unit_quantity), _decimal(owned_quantity), course_quantity_step(lot_step, market_step)
            if units is None or units <= 0 or unit <= 0 or owned <= 0:
                return "COURSE_ACTUAL_UNIT_OR_OWNED_QUANTITY_UNVERIFIED"
            if unit % step:
                return "COURSE_UNIT_NOT_ON_EXCHANGE_GRID"
            if owned != unit * units:
                return "COURSE_OWNED_STAGE_QUANTITY_CHANGED"
    except (ValueError, InvalidOperation):
        return "COURSE_QUANTITY_OR_GRID_MALFORMED"
    return None


def course_fill_blocker(proposal, order, position, symbol, side, *, expected_client_id,
                        expected_order_id=None, protection_verified):
    """Caller supplies signed, identity-attributed matching-engine observations.

    Binance algo parent and actual child have different ids; the caller must
    prove that association before passing the actual expected child identity.
    An ACK, partial/cancelled fill, target-shaped position or stored proposal
    alone cannot confirm a Course stage. No order submission occurs here.
    """
    if not isinstance(proposal, dict) or proposal.get("status") != "EXACT_COURSE_QUANTITY" or proposal.get("policy") != POLICY:
        return "COURSE_EXACT_QUANTITY_PROPOSAL_UNVERIFIED"
    if proposal.get("action") not in ("ENTRY", "ADD", "REDUCE"):
        return "COURSE_EXACT_QUANTITY_PROPOSAL_UNVERIFIED"
    try:
        derived = (course_initial_quantity(proposal["quantity"], proposal["quantity_step"], stage=proposal["stage"])
            if proposal["action"] == "ENTRY" else course_stage_quantity(proposal["unit_quantity"],
                proposal["from_stage"], proposal["stage"], proposal["before_quantity"], proposal["quantity_step"],
                risk_cap=proposal["quantity"], w3_to_w1_ratio=proposal.get("w3_to_w1_ratio")))
        if (derived.get("status") != "EXACT_COURSE_QUANTITY" or proposal.get("can_place_orders") is not False
                or any(proposal.get(k) != derived[k] for k in ("action", "from_stage", "stage", "target_units"))
                or any(_decimal(proposal[k]) != _decimal(derived[k]) for k in
                    ("unit_quantity", "quantity_step", "before_quantity", "quantity", "target_quantity"))):
            return "COURSE_EXACT_QUANTITY_PROPOSAL_UNVERIFIED"
    except (ValueError, InvalidOperation, KeyError, TypeError):
        return "COURSE_EXACT_QUANTITY_PROPOSAL_UNVERIFIED"
    if not isinstance(order, dict) or not isinstance(position, dict) or side not in ("BUY", "SELL"):
        return "COURSE_SIGNED_STAGE_FILL_IDENTITY_UNVERIFIED"
    reduce = proposal.get("action") == "REDUCE"
    expected_side = ("SELL" if side == "BUY" else "BUY") if reduce else side
    if (not expected_client_id or order.get("clientOrderId") != expected_client_id or order.get("symbol") != symbol
            or position.get("symbol") != symbol or order.get("side") != expected_side
            or order.get("positionSide", "BOTH") != "BOTH" or position.get("positionSide") != "BOTH"
            or (expected_order_id is not None and str(order.get("orderId", "")) != str(expected_order_id))
            or not str(order.get("orderId", "")) or str(order.get("reduceOnly")).lower() != str(reduce).lower()):
        return "COURSE_SIGNED_STAGE_FILL_IDENTITY_UNVERIFIED"
    if order.get("status") != "FILLED":
        return "COURSE_STAGE_REQUIRES_FULL_TERMINAL_FILL"
    try:
        with localcontext() as context:
            context.prec = 100
            unit, before, quantity, target, step = (_decimal(proposal[k]) for k in
                ("unit_quantity", "before_quantity", "quantity", "target_quantity", "quantity_step"))
            sign = 1 if side == "BUY" else -1
            actual = _decimal(position.get("positionAmt")) * sign
            if (unit <= 0 or step <= 0 or unit % step or quantity <= 0 or quantity % step
                    or target < 0 or target % step or before < 0 or before % step
                    or target != unit * proposal["target_units"]
                    or target != before + (-quantity if reduce else quantity)
                    or _decimal(order.get("origQty")) != quantity or _decimal(order.get("executedQty")) != quantity
                    or actual != target):
                return "COURSE_STAGE_FILL_OR_POSITION_QUANTITY_MISMATCH"
    except (ValueError, InvalidOperation, TypeError, KeyError):
        return "COURSE_STAGE_FILL_OR_POSITION_QUANTITY_MISMATCH"
    if target > 0 and not protection_verified:
        return "COURSE_STAGE_PROTECTION_NOT_VERIFIED"
    return None
