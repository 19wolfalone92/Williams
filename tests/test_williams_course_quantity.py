"""Book proportions, exact exchange grids and signed partial-fill regressions."""
from copy import deepcopy
from decimal import Decimal
import json
from pathlib import Path

import pytest

from williams_course_quantity import (course_initial_quantity, course_stage_quantity,
    course_owned_quantity_blocker, course_fill_blocker)

CONTRACT = json.loads((Path(__file__).parents[1]/"app/src/test/resources/williams_course_quantity_contract.json").read_text())


@pytest.mark.parametrize("case", CONTRACT["cases"], ids=lambda c:c["id"])
def test_shared_book_proportions_never_round_or_clip_an_established_tranche(case):
    c = case
    if c.get("kind") == "INITIAL":
        result = course_initial_quantity(c["cap"], c["lot"], c.get("market", 0), c.get("stage", "W1"))
    else:
        result = course_stage_quantity(c.get("unit", "0.001"), c["from"], c["stage"], c["owned"],
            c.get("lot", "0.001"), c.get("market", 0), risk_cap=c.get("cap"), w3_to_w1_ratio=c.get("ratio"))
    assert result["can_place_orders"] is False
    if "reason" in c:
        assert result["status"] == "BLOCKED" and result["reason"] == c["reason"]
    else:
        assert result["status"] == "EXACT_COURSE_QUANTITY"
        assert result["quantity"] == c["quantity"]
        if "unit" in c:assert result["unit_quantity"] == c["unit"]
        if "target" in c:assert result["target_quantity"] == c["target"]
        if "action" in c:assert result["action"] == c["action"]


def signed_fill(proposal, side):
    reduce = proposal["action"] == "REDUCE"
    order = {"symbol": "BTCUSDT", "clientOrderId": "actual-child", "orderId": "42", "status": "FILLED",
             "side": ("SELL" if side == "BUY" else "BUY") if reduce else side, "positionSide": "BOTH",
             "reduceOnly": reduce, "origQty": proposal["quantity"], "executedQty": proposal["quantity"]}
    quantity = Decimal(proposal["target_quantity"])*(1 if side == "BUY" else -1)
    return order, {"symbol": "BTCUSDT", "positionSide": "BOTH", "positionAmt": str(quantity)}


@pytest.mark.parametrize("side", ["BUY", "SELL"])
@pytest.mark.parametrize("kind", ["ENTRY", "ADD", "REDUCE", "FLAT"])
@pytest.mark.parametrize("fault", ["NONE", "PARTIAL", "CANCELED_PARTIAL", "WRONG_POSITION", "WRONG_CHILD", "WRONG_SYMBOL",
    "WRONG_SIDE", "WRONG_REDUCE", "WRONG_ORIGINAL_QTY", "UNPROTECTED", "TAMPERED_STAGE", "TAMPERED_ACTION"])
def test_stage_confirmation_requires_its_exact_signed_order_and_protected_position(side, kind, fault):
    proposal = (course_initial_quantity("0.009", "0.001") if kind == "ENTRY" else
        course_stage_quantity("0.001", "W2" if kind == "ADD" else "W1" if kind == "REDUCE" else "C_ADD",
            "W3" if kind == "ADD" else "W2" if kind == "REDUCE" else "CORRECTION_EXIT",
            "0.001" if kind == "ADD" else "0.003" if kind == "REDUCE" else "0.004", "0.001", risk_cap="0.005"))
    order, position = signed_fill(proposal, side)
    protected = fault != "UNPROTECTED"
    if fault in ("PARTIAL", "CANCELED_PARTIAL"):
        order["status"] = "PARTIALLY_FILLED" if fault == "PARTIAL" else "CANCELED"
        order["executedQty"] = str(Decimal(order["executedQty"])/2)
    if fault == "WRONG_POSITION":position["positionAmt"] = "0.000001"
    if fault == "WRONG_CHILD":order["orderId"] = "99"
    if fault == "WRONG_SYMBOL":order["symbol"] = "ETHUSDT"
    if fault == "WRONG_SIDE":order["side"] = "SELL" if order["side"] == "BUY" else "BUY"
    if fault == "WRONG_REDUCE":order["reduceOnly"] = not order["reduceOnly"]
    if fault == "WRONG_ORIGINAL_QTY":order["origQty"] = "0.099"
    if fault == "TAMPERED_STAGE":proposal["stage"] = "W3_EXTENSION"
    if fault == "TAMPERED_ACTION":proposal["action"] = "HOLD"
    blocker = course_fill_blocker(proposal, order, position, "BTCUSDT", side,
        expected_client_id="actual-child", expected_order_id="42", protection_verified=protected)
    assert (blocker is None) is (fault == "NONE" or fault == "UNPROTECTED" and kind == "FLAT"), blocker


def test_the_whole_motive_and_correction_sequences_preserve_one_actual_unit():
    for stages, ratios in [(["W1", "W2", "W3", "W3_EXTENSION", "W4", "W5", "EXIT"], [2.0]*6),
                           (["W1", "W2", "W3", "W4", "W5", "EXIT"], [1.5]*5),
                           (["A_ENTRY", "A_REDUCE", "C_ADD", "CORRECTION_EXIT"], [None]*3)]:
        first = course_initial_quantity("1.007", "0.002", "0.003", stages[0])
        unit, owned = first["unit_quantity"], first["target_quantity"]
        for previous, stage, ratio in zip(stages, stages[1:], ratios):
            proposal = course_stage_quantity(unit, previous, stage, owned, "0.002", "0.003",
                risk_cap="100", w3_to_w1_ratio=ratio)
            assert proposal["status"] == "EXACT_COURSE_QUANTITY"
            assert proposal["unit_quantity"] == unit
            for side in ("BUY", "SELL"):
                order, position = signed_fill(proposal, side)
                assert course_fill_blocker(proposal, order, position, "BTCUSDT", side,
                    expected_client_id="actual-child", expected_order_id="42", protection_verified=True) is None
            owned = proposal["target_quantity"]
        assert Decimal(owned) == 0


def test_sub_lot_noise_and_partial_initial_units_cannot_become_stage_authority():
    assert course_owned_quantity_blocker("0.001", "W1", "0.003", "0.001") is None
    assert course_owned_quantity_blocker("0.001", "W1", "0.0030000000001", "0.001") == "COURSE_OWNED_STAGE_QUANTITY_CHANGED"
    assert course_owned_quantity_blocker("0.0015", "W1", "0.0045", "0.001") == "COURSE_UNIT_NOT_ON_EXCHANGE_GRID"
    assert course_initial_quantity("0.009", "0.001")["can_place_orders"] is False


def test_an_order_ack_or_target_shaped_position_is_not_a_fill():
    proposal = course_initial_quantity("0.009", "0.001")
    order, position = signed_fill(proposal, "BUY")
    for status in ("NEW", "TRIGGERED", "UNKNOWN", "EXPIRED", "CANCELED"):
        candidate = deepcopy(order);candidate["status"] = status
        assert course_fill_blocker(proposal, candidate, position, "BTCUSDT", "BUY",
            expected_client_id="actual-child", expected_order_id="42", protection_verified=True)


@pytest.mark.parametrize("exact", ["0.01234567890123456789", "0.123456789012345678"])
def test_reduction_door_compares_the_original_decimal_not_a_rounded_float(monkeypatch, exact):
    from types import SimpleNamespace
    from futures_williams_runtime import FuturesWilliamsRuntime
    bot = FuturesWilliamsRuntime.__new__(FuturesWilliamsRuntime)
    campaign = SimpleNamespace(tags={"pending_course_stage": "W2"}, position_qty=1.0)
    rounded = float(exact)
    assert Decimal(str(rounded)) != Decimal(exact)
    proof = {"status": "OBSERVED_SOURCE_PINNED_REDUCTION", "stage": "W2", "target_quantity": .3,
             "reduction_quantity": rounded, "exact_quantity": {"quantity": exact}}
    monkeypatch.setattr(bot, "_course_trim_plan", lambda owner:proof)
    with pytest.raises(RuntimeError, match="COURSE_SOURCE_TRIM_STAGE_OR_QUANTITY_CHANGED"):
        bot._revalidate_course_trim(campaign, rounded)
