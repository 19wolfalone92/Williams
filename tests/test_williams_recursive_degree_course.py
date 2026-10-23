"""Real book schematic branches + causal 6h/1h/15m synthetic degree contract.

No synthetic pivot list is called a digitized historical chart. Assertions only
prove that the same explicit source rules are applied consistently to known pivots.
"""
from dataclasses import replace
import json
from pathlib import Path

import pytest

from williams_wave_hierarchy import FRAME_MS, WaveAnchor
from williams_wave_subdivisions import (
    course_checklist_leg, trace_recursive_price_grammar
)

FIXTURE = Path(__file__).parents[1] / "app/src/test/resources/williams_recursive_degree_course_contract.json"


def source():
    return json.loads(FIXTURE.read_text(encoding="utf-8"))


def anchors(items, mirror=False):
    return [WaveAnchor(
        "LOW" if (mirror and a["kind"]=="HIGH") else
        "HIGH" if (mirror and a["kind"]=="LOW") else a["kind"],
        (400.0-a["price"] if mirror else a["price"]),i,
        a["extreme_at"],a["known_at"]
    ) for i,a in enumerate(items)]


def report(mirror=False,depth=2,missing_fine=False,poison_future=False):
    data=source()
    case=data["patterns"][0]
    senior=anchors(case["parent"],mirror)
    junior=anchors(case["children"],mirror)
    finer=anchors(case["grandchildren"],mirror)
    if missing_fine:finer=finer[:-1]
    if poison_future:
        last=finer[-1]
        finer.append(replace(last,extreme_at=data["as_of"]+FRAME_MS["15m"],
                             known_at=data["as_of"]+2*FRAME_MS["15m"],price=float("nan")))
    return trace_recursive_price_grammar(
        case["pattern"],senior,
        {"6h":senior,"1h":junior,"15m":finer},
        FRAME_MS,case["top_frame"],data["as_of"],depth=depth
    )


@pytest.mark.parametrize("mirror",[False,True])
def test_three_real_frame_resolutions_prove_two_price_levels_but_not_unique_wave_count(mirror):
    one=report(mirror,depth=1)
    two=report(mirror,depth=2)
    assert one["status"]=="OBSERVED_DEPTH_PRICE_GRAMMAR_CONFIRMED",one
    assert two["status"]=="OBSERVED_DEPTH_PRICE_GRAMMAR_CONFIRMED",two
    assert two["frame_path"]==["6h","1h","15m"]
    assert two["observed_node_count"]==4
    assert len(two["branches"])==3
    assert [x["internal_proof"]["expected"] for x in two["branches"]]==[
        (5,3,5,3,5),(5,3,5),(5,3,5,3,5)]
    assert two["unique_elliott_genealogy_proven"] is False
    assert not two["can_place_orders"] and not two["can_close"] and not two["can_reverse"]
    assert report(mirror,depth=3)["status"]=="PARTIAL_PRICE_GRAMMAR"
    assert report(mirror,depth=2,missing_fine=True)["status"]=="PARTIAL_PRICE_GRAMMAR"
    assert report(mirror,depth=2,poison_future=True)==two


def test_course_59_60_schematic_branch_contract_and_negative_wave_three():
    fixture=source()
    assert fixture["source_kind"]=="RULE_BOUNDARY_SYNTHETIC_NOT_BOOK_OHLC"
    for row in fixture["checklist"]:
        assert course_checklist_leg(row["leg"],row["swings"])==row["expected"]
    assert course_checklist_leg("W3",3)=="RECOUNT_W3_WRONG_WAVE_NUMBER"


def test_would_be_flat_must_not_inherit_unverified_zigzag_structure():
    data=source()
    c=data["patterns"][0]
    p=anchors(c["parent"])
    mids=anchors(c["children"])
    bad=trace_recursive_price_grammar(
        "REGULAR_FLAT_ABC_CANDIDATE",p,{"6h":p,"1h":mids},
        FRAME_MS,"6h",data["as_of"],depth=1)
    assert bad["status"]=="UNRESOLVED"
    assert bad["internal_proof"]["reason"]=="SUBDIVISION_COUNT_FAILED"


def test_work_budget_and_missing_required_frames_are_explicit():
    assert report(depth=2)["observed_node_count"]<=64
    data=source();c=data["patterns"][0]
    p=anchors(c["parent"])
    missing=trace_recursive_price_grammar(c["pattern"],p,{"6h":p},FRAME_MS,"6h",data["as_of"],depth=1)
    assert missing["status"]=="UNRESOLVED"
    assert missing["reason"]=="CHILD_TIMEFRAME_UNAVAILABLE"
