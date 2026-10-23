"""Eight actual price examples (6 subdivision / 2 negative), not expert labels.

Replays the real historical frozen pivot evidence through current production
recursive degree checker at as_of. These are ENGINE_DISCOVERED candidates:
price-grammar/causality regression ONLY, not independent Elliott accuracy.
"""
from dataclasses import replace
import json
from pathlib import Path
import pytest
from williams_wave_hierarchy import FRAME_MS,WaveAnchor
from williams_wave_subdivisions import trace_recursive_price_grammar

CASES=json.loads((Path(__file__).parents[1]/
 "app/src/test/resources/williams_historical_wave_cases.json").read_text())["cases"]
SUBDIVISIONS=[c for c in CASES if c["operation"]=="SUBDIVISION"]

@pytest.mark.parametrize("case",SUBDIVISIONS,ids=lambda c:c["symbol"]+"_"+c["frame"]+"_"+c["candidate"]["pattern"])
@pytest.mark.parametrize("mirror",[False,True])
def test_real_exchange_pivot_history_is_causal_price_grammar_not_independent_ground_truth(case,mirror):
    parents=[WaveAnchor(**v) for v in case["parent"]]
    children=[WaveAnchor(**v) for v in case["child"]]
    if mirror:
        maximum=2*max(p.price for p in parents+children)
        def flip(p):return replace(p,kind="HIGH" if p.kind=="LOW" else "LOW",price=maximum-p.price)
        parents=[flip(p) for p in parents];children=[flip(p) for p in children]
    kwargs=dict(pattern=case["candidate"]["pattern"],parent=parents,
        pivots_by_frame={case["frame"]:parents,case["child_frame"]:children},
        frame_durations=FRAME_MS,parent_frame=case["frame"],as_of=case["as_of"],depth=1)
    proof=trace_recursive_price_grammar(**kwargs)
    assert proof["status"]=="OBSERVED_DEPTH_PRICE_GRAMMAR_CONFIRMED", (case["symbol"],proof)
    assert proof["internal_proof"]["observed"]==tuple(case["proof"]["expected"])
    assert proof["unique_elliott_genealogy_proven"] is False
    for key in ("can_place_orders","can_close","can_reverse"):
        assert not proof[key]
    poisoned=children+[replace(children[-1],extreme_at=case["as_of"]+FRAME_MS[case["child_frame"]],
        known_at=case["as_of"]+2*FRAME_MS[case["child_frame"]],price=float("nan"))]
    assert trace_recursive_price_grammar(**(kwargs|{"pivots_by_frame":{case["frame"]:parents,
        case["child_frame"]:poisoned}}))==proof
    pre_known=parents[-1].known_at-1
    assert trace_recursive_price_grammar(**(kwargs|{"as_of":pre_known}))["status"]!="OBSERVED_DEPTH_PRICE_GRAMMAR_CONFIRMED"
    invisible=[replace(c,known_at=case["as_of"]+1) for c in children]
    assert trace_recursive_price_grammar(**(kwargs|{"pivots_by_frame":{case["frame"]:parents,
        case["child_frame"]:invisible}}))["status"]!="OBSERVED_DEPTH_PRICE_GRAMMAR_CONFIRMED"
