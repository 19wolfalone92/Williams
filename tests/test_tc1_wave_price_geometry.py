"""TC1 ch7 original price ratios, shared Kotlin/Python source fixtures.

Never convert a typical Fibonacci zone into a hard TC2 trade filter.
"""
import json
from pathlib import Path

import pytest

from williams_tc1_wave_price_geometry import observe_tc1_wave_price_geometry

HOUR = 3_600_000
ROOT = Path(__file__).resolve().parents[1]
CASES = json.loads((ROOT / "app/src/test/resources/williams_tc1_wave_price_geometry_contract.json").read_text())


def scenario(prices, direction="UP", *, as_of=120*HOUR):
    first = "LOW" if direction == "UP" else "HIGH"
    anchors=[]
    for i, price in enumerate(prices):
        kind = first if i % 2 == 0 else ("HIGH" if first == "LOW" else "LOW")
        time = (60+i*5)*HOUR
        anchors.append(dict(kind=kind, price=price, extreme_at=time,
                            known_at=time+2*HOUR))
    return {"frame":"1h", "direction":direction, "status":"PROVISIONAL",
            "observed_at":as_of, "anchors":anchors}


@pytest.mark.parametrize("case",CASES["cases"],ids=lambda c:c["id"])
def test_tc1_book_geometry_shared_with_android(case):
    report=observe_tc1_wave_price_geometry(scenario(case["prices"], case["direction"]))
    assert report["status"]==case["status"]
    assert report["wave_degree_proven"] is False
    assert report["can_place_orders"] is False
    assert report["can_move_stop"] is False
    assert report["can_close"] is False
    if "w2" in case:
        assert report["w2_retracement"]["fraction"]==pytest.approx(case["w2"])
    if "w3" in case:
        assert report["w3_extension"]["fraction"]==pytest.approx(case["w3"])
    if "w4" in case:
        assert report["w4_retracement"]["fraction"]==pytest.approx(case["w4"])
        assert report["w4_retracement"]["overlaps_w1_price_territory"] == case["overlap"]
    if "w5_origin" in case:
        assert report["w5_projection"]["origin_price"]==case["w5_origin"]
    if "w5_status" in case:
        assert report["w5_projection"]["status"]==case["w5_status"]
    if "b_over_a" in case:
        assert report["abc_b_retracement"]["b_over_a"]==pytest.approx(case["b_over_a"])
        assert report["abc_b_retracement"]["status"]==case["b_status"]
        assert report["abc_b_retracement"]["can_enter_c_wave"] is False


@pytest.mark.parametrize("field,value",[
    ("direction","FORCE_LONG"),("status","EXECUTABLE"),
    ("frame","4x"),("observed_at",0)
])
def test_invalid_evidence_is_not_trading_proof(field,value):
    s=scenario([100,120,110,142,128])
    s[field]=value
    r=observe_tc1_wave_price_geometry(s)
    assert r["status"]=="UNVERIFIED_WAVE_ANCHORS"
    assert not r["can_place_orders"]


def test_unconfirmed_future_or_noncontiguous_price_pivot_refused():
    s=scenario([100,120,110,142,128])
    s["anchors"][-1]["known_at"]=121*HOUR
    assert observe_tc1_wave_price_geometry(s)["status"]=="UNVERIFIED_WAVE_ANCHORS"
    s=scenario([100,120,110,142,128])
    s["anchors"][3]["extreme_at"]=1
    assert observe_tc1_wave_price_geometry(s)["status"]=="UNVERIFIED_WAVE_ANCHORS"
    s=scenario([100,120,110,142,128])
    s["anchors"][-1]["kind"]="HIGH"
    assert observe_tc1_wave_price_geometry(s)["status"]=="UNVERIFIED_WAVE_ANCHORS"


def test_typical_zone_outside_is_not_a_hard_rule():
    r=observe_tc1_wave_price_geometry(scenario([100,120,104,150,125]))
    assert r["w2_retracement"]["status"]=="ABOVE_TYPICAL_BOOK_BAND"
    assert r["w3_extension"]["status"]=="ABOVE_TYPICAL_BOOK_BAND"
    assert r["w4_retracement"]["status"]=="ABOVE_TYPICAL_BOOK_BAND"
    assert not r["w4_retracement"]["hard_trade_veto"]
    assert not r["is_tc2_entry_gate"]
