"""Regression cases for admissible, mirrored internal price geometries."""
from dataclasses import replace
import json
from pathlib import Path

import pytest

from williams_complex_waves import detect_complex_wave_patterns
from williams_wave_hierarchy import WaveAnchor
from williams_wave_subdivisions import prove_wave_subdivisions


def anchors(prices, mirror=False):
    return [WaveAnchor("LOW" if (i % 2 == 0) != mirror else "HIGH",
                       400 - price if mirror else price, i, (i + 1) * 1000,
                       (i + 1) * 1000 + 100)
            for i, price in enumerate(prices)]


@pytest.mark.parametrize("mirror", [False, True])
def test_deep_b_keeps_both_internal_grammars_until_a_is_proved(mirror):
    points = anchors([100, 120, 110, 145, 125, 155, 135, 154, 120], mirror)
    result = detect_complex_wave_patterns(points, direction="DOWN" if mirror else "UP", as_of=10000)
    labels = {p.pattern for p in result}
    assert "ZIGZAG_ABC_CANDIDATE" in labels
    assert "REGULAR_FLAT_ABC_CANDIDATE" in labels
    assert all(not p.can_place_orders for p in result)


@pytest.mark.parametrize("mirror", [False, True])
def test_nonextending_third_wave_is_not_a_diagonal_or_a_completed_motive_origin(mirror):
    for prices in ([100, 120, 110, 118, 115, 121],
                   [100, 120, 110, 118, 115, 121, 105, 119, 95]):
        result = detect_complex_wave_patterns(anchors(prices, mirror),
                    direction="DOWN" if mirror else "UP", as_of=10000)
        assert not any("DIAGONAL" in p.pattern or "ABC_CANDIDATE" in p.pattern for p in result)


@pytest.mark.parametrize("mirror", [False, True])
@pytest.mark.parametrize("prices,expected,shape", [
    ([100, 120, 95, 125], (3,), "EXPANDED_FLAT_PRICE_SKELETON"),
    ([100, 120, 95, 115], (3,), "RUNNING_FLAT_PRICE_SKELETON"),
    ([100, 120, 100, 121], (3,), "REGULAR_FLAT_PRICE_SKELETON"),
    ([100, 120, 110, 145, 125, 142], (5,), "TRUNCATED_IMPULSE_PRICE_SKELETON"),
])
def test_internal_correction_can_cross_its_origin_and_fifth_can_truncate(prices, expected, shape, mirror):
    child = anchors(prices, mirror)
    kwargs = dict(parent_duration=500, as_of=10000)
    parent = [child[0], child[-1]]
    proof = prove_wave_subdivisions(parent, child, expected, **kwargs)
    assert proof.status == "PRICE_SUBDIVISIONS_CONFIRMED"
    assert proof.leg_patterns == (shape,)
    if expected==(3,):
        assert prove_wave_subdivisions(parent,child,expected,allow_flat_corrections=False,
                                       **kwargs).status=="UNRESOLVED"
    assert prove_wave_subdivisions(parent, child[:-1], expected, **kwargs).status == "UNRESOLVED"
    assert prove_wave_subdivisions(parent, [replace(p, known_at=10001) for p in child],
                                   expected, **kwargs).status == "UNRESOLVED"
    assert prove_wave_subdivisions(parent, child + [replace(child[-1], price=float("nan"),
                       extreme_at=11000, known_at=12000)], expected, **kwargs) == proof


@pytest.mark.parametrize("mirror", [False, True])
@pytest.mark.parametrize("prices,expected", [
    ([100, 120, 110, 115], (3,)),  # C below A without an expanded B: incomplete.
    ([100, 120, 95, 99], (3,)),    # No net progress to the claimed opposite endpoint.
    ([100, 120, 110, 145, 119, 142], (5,)),  # W4 overlap is not ordinary truncation.
    ([100, 130, 110, 135, 132, 165], (5,)),  # W3 is shortest.
])
def test_new_internal_alternatives_do_not_relax_unrelated_rules(prices, expected, mirror):
    child = anchors(prices, mirror)
    assert prove_wave_subdivisions([child[0], child[-1]], child, expected,
                       parent_duration=500, as_of=10000).status == "UNRESOLVED"


HISTORICAL=json.loads((Path(__file__).parents[1]/"app/src/test/resources/williams_grammar_historical_cases.json").read_text())["cases"]


@pytest.mark.parametrize("case",HISTORICAL,ids=lambda c:c["symbol"]+"_"+c["operation"])
@pytest.mark.parametrize("mirror",[False,True])
def test_actual_historical_counterexamples_share_the_android_contract(case,mirror):
    points=[WaveAnchor(**p) for p in case["parent"]]
    direction=case["candidate"]["direction"]
    if mirror:
        total=2*max(p.price for p in points)
        points=[replace(p,kind="HIGH" if p.kind=="LOW" else "LOW",price=total-p.price) for p in points]
        direction="DOWN" if direction=="UP" else "UP"
    result=detect_complex_wave_patterns(points,direction=direction,as_of=case["as_of"])
    labels={p.pattern for p in result}
    if case["operation"]=="REJECTED_NONEXTENDING_W3":
        assert case["candidate"]["pattern"] not in labels
    else:
        assert {"ZIGZAG_ABC_CANDIDATE","REGULAR_FLAT_ABC_CANDIDATE"}<=labels
    assert all(not p.can_place_orders for p in result)
