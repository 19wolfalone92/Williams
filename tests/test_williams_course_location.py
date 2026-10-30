"""Course price predicates and causal guards, not independent wave accuracy."""
from copy import deepcopy
from dataclasses import replace
import json
from pathlib import Path

import pytest

from williams_course_location import course_four_sign_evidence, course_price_location, course_stage_locations, course_trim_proof
from williams_wave_hierarchy import WaveBar, build_wave_hierarchy

ROOT = Path(__file__).parents[1]
CONTRACT = json.loads((ROOT/"app/src/test/resources/williams_course_location_contract.json").read_text())
HOUR = 3_600_000


def location_case(c, side):
    n = {"W1": 2, "W2": 2, "W3": 3, "W3_EXTENSION": 3, "W4": 4, "W5": 5, "EXIT": 6}[c["stage"]]
    prices = list(CONTRACT["base_prices"])
    prices[2] = c.get("p2", prices[2]);prices[4] = c.get("p4", prices[4])
    if c.get("fault") == "WRONG_DEGREE":n = 6
    def mirror(p):return p if side == "LONG" else CONTRACT["mirror_sum"]-p
    anchors = [{"kind": "LOW" if (i % 2 == 0) == (side == "LONG") else "HIGH",
                "price": mirror(p), "extreme_at": (100+i*10)*HOUR, "known_at": (103+i*10)*HOUR}
               for i, p in enumerate(prices[:n])]
    if c.get("fault") == "FUTURE":anchors[-1]["known_at"] = 161*HOUR
    if c.get("fault") == "UNORDERED":anchors[-1]["extreme_at"] = anchors[0]["extreme_at"]
    s = {"frame":"1h","status": "PROVISIONAL", "direction": "UP" if side == "LONG" else "DOWN", "observed_at": 160*HOUR,
         "anchors": anchors, "scenario_id": "source-parent", "observed_close_price": mirror(c["price"])}
    if c.get("fault") == "WRONG_SIDE":s["direction"] = "DOWN" if side == "LONG" else "UP"
    return s, mirror(c["price"])


@pytest.mark.parametrize("side", ["LONG", "SHORT"])
@pytest.mark.parametrize("c", CONTRACT["cases"], ids=lambda c:c["id"])
def test_shared_price_locations_use_the_correct_leg_origin_and_closed_pivots(c, side):
    s, price = location_case(c, side)
    result = course_price_location(c["stage"], side, s, price, 160*HOUR)
    if c["inside"] is None:
        assert result["status"] == "UNVERIFIED_PRICE_LOCATION"
    else:
        assert result["price_in_zone"] is c["inside"]
    if "reason" in c:assert result["reason"] == c["reason"]
    for key in ("can_place_orders", "can_close", "can_reverse", "wave_degree_confirmed"):
        assert result[key] is False


def trim_fixture(previous="W3_EXTENSION", side="LONG", base=0):
    def mirror(p):return p if side in ("LONG", "BUY") else 400-p
    direction = "UP" if side in ("LONG", "BUY") else "DOWN"
    end = 110 if previous == "W1" else 130
    start = 100 if previous == "W1" else 120
    def anchor(p, t, i, duration):return {"kind": "LOW" if (i % 2 == 0) == (direction == "UP") else "HIGH",
        "price": mirror(p), "extreme_at": base+t*HOUR, "known_at": base+t*HOUR+3*duration}
    parent = {"scenario_id": "parent", "frame": "1h", "status": "PROVISIONAL", "direction": direction,
        "label": "W1" if previous == "W1" else "W3", "active_label": "W2" if previous == "W1" else "W4",
        "observed_at": base+(end+3)*HOUR,
        "anchors": [anchor(p, 100+i*10, i, HOUR) for i, p in enumerate([100,120] if previous == "W1" else [100,120,108,148])]}
    child = {"scenario_id": "child", "frame": "5m", "status": "PROVISIONAL", "direction": direction, "label": "W5",
        "observed_at": base+(end+3)*HOUR, "scale_bars": 120, "divergence": True,
        "squat": True, "target_zone": True, "terminal_fractal": True,
        "anchors": [anchor(p, start+i*2, i, 300_000) for i, p in enumerate(
            [100,108,104,116,110,120] if previous == "W1" else [108,126,118,144,132,148])]}
    rows = []
    for i in range(end+3):
        p = 100 if i <= 100 else 100+2*(i-100) if i <= 110 else 120-1.2*(i-110) if i <= 120 else 108+4*(min(i,130)-120)
        p = mirror(p);rows.append(WaveBar(base+i*HOUR,p,p+.1,p-.1,p,1000))
    report = {"as_of": base+(end+3)*HOUR, "frame_errors": {}, "scenarios": [parent,child]}
    args = dict(hierarchy=report,side=side,origin_at=base+100*HOUR,previous_stage=previous,
                opened_at=base+101*HOUR,now_ms=base+(end+3)*HOUR+1000,bars=rows)
    return args


@pytest.mark.parametrize("side", ["LONG", "SHORT"])
@pytest.mark.parametrize("previous", ["W1", "W3", "W3_EXTENSION"])
def test_matched_terminal_reductions_need_the_owned_origin_and_a_real_finer_degree(side, previous):
    args = trim_fixture(previous,side)
    proof = course_trim_proof(**args)
    assert proof["status"] == "OBSERVED_SOURCE_PINNED_REDUCTION", proof
    assert proof["target_units"] == (1 if previous == "W1" else 3)
    assert not proof["can_place_orders"] and not proof["wave_degree_confirmed"]
    assert args["opened_at"] < proof["signal_close_ms"] <= args["hierarchy"]["as_of"]
    assert args["hierarchy"]["scenarios"][1]["frame"] == "5m"  # no mandatory M15


@pytest.mark.parametrize("fault", ["LABEL_ONLY", "FUTURE", "EARLY_CONFIRMATION", "PREENTRY", "ORIGIN", "SCALE", "SQUAT", "AMBIGUOUS", "PARENT_AMBIGUOUS", "CHILD_FRAME_ERROR", "H1_ERROR", "GAP", "WEAK_AO"])
@pytest.mark.parametrize("side", ["LONG", "SHORT"])
def test_wave_labels_cannot_bypass_price_degree_time_and_momentum_guards(fault,side):
    args = trim_fixture(side=side);h = args["hierarchy"];child = h["scenarios"][1]
    if fault == "LABEL_ONLY":h["scenarios"] = h["scenarios"][:1]
    elif fault == "FUTURE":child["anchors"][-1]["known_at"] = args["now_ms"]+1
    elif fault == "EARLY_CONFIRMATION":child["anchors"][-1]["known_at"] = child["anchors"][-1]["extreme_at"]+600_000
    elif fault == "PREENTRY":args["opened_at"] = h["as_of"]
    elif fault == "ORIGIN":args["origin_at"] -= HOUR
    elif fault == "SCALE":child["scale_bars"] = 141
    elif fault == "SQUAT":child["squat"] = False
    elif fault == "AMBIGUOUS":h["scenarios"].append(deepcopy(child))
    elif fault == "PARENT_AMBIGUOUS":h["scenarios"].append(deepcopy(h["scenarios"][0]))
    elif fault == "CHILD_FRAME_ERROR":h["frame_errors"]["5m"] = "MISSING"
    elif fault == "H1_ERROR":h["frame_errors"]["1h"] = "MISSING"
    elif fault == "GAP":args["bars"].pop(80)
    elif fault == "WEAK_AO":args["bars"] = [replace(b,o=100,h=100.1,l=99.9,c=100) for b in args["bars"]]
    result = course_trim_proof(**args)
    assert result["status"] == "BLOCKED", (fault,result)
    assert not result["can_place_orders"]


@pytest.mark.parametrize("symbol", ["BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT", "XRPUSDT"])
def test_real_closed_h1_prefixes_cannot_borrow_future_prices_or_certify_course(symbol):
    d = json.loads((ROOT/f"app/src/test/resources/blind_wave_20241109_29_{symbol}.json").read_text())
    bars = [WaveBar(int(r[0]),*[float(v) for v in r[1:]]) for r in d["rows"]]
    count = 0
    for n in (120,180,240,300,360,420,480):
        as_of = bars[n-1].t+HOUR
        prefix = build_wave_hierarchy({"1h":bars[:n]},as_of).to_dict()
        future = bars[:n]+[replace(b,h=float("nan"),c=-1) for b in bars[n:]]
        replay = build_wave_hierarchy({"1h":future},as_of).to_dict()
        assert replay == prefix
        for s in replay["scenarios"]:
            if s["status"] != "PROVISIONAL":continue
            evidence = course_four_sign_evidence(s,as_of)
            assert not evidence["can_close"] and not evidence["wave_degree_confirmed"]
            for location in course_stage_locations(s,as_of):
                assert not location["can_place_orders"]
                if "evidence_known_at" in location:assert location["evidence_known_at"] <= as_of
                count += 1
    assert count > 0
