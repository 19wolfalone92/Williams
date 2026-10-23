"""Actual historical counterexamples and nested endpoints shared with Android."""
from dataclasses import replace
import json
from pathlib import Path
import pytest

from williams_complex_waves import detect_complex_wave_patterns
from williams_wave_hierarchy import WaveAnchor,WaveBar,FRAME_MS,build_wave_hierarchy
from williams_wave_subdivisions import enrich_complex_subdivisions

CASES=json.loads((Path(__file__).parents[1]/"app/src/test/resources/williams_historical_wave_cases.json").read_text())["cases"]


@pytest.mark.parametrize("case",CASES,ids=lambda c:c["symbol"]+"_"+c["frame"]+"_"+c["operation"])
@pytest.mark.parametrize("mirror",[False,True])
def test_historical_geometry_and_actual_nested_child_endpoints(case,mirror):
    parent=[WaveAnchor(**p) for p in case["parent"]]
    child=[WaveAnchor(**p) for p in case.get("child",[])]
    direction=case["candidate"]["direction"]
    if mirror:
        total=2*max(p.price for p in parent+child)
        def flip(p):return replace(p,kind="HIGH" if p.kind=="LOW" else "LOW",price=total-p.price)
        parent=list(map(flip,parent));child=list(map(flip,child))
        direction="DOWN" if direction=="UP" else "UP"
    now=case["as_of"]
    if case["operation"]=="INVALIDATION":
        frames={f:[WaveBar(**b) for b in rows] for f,rows in case["frames"].items()}
        if mirror:
            frames={f:[replace(b,o=total-b.o,h=total-b.l,l=total-b.h,c=total-b.c) for b in rows] for f,rows in frames.items()}
        def target(report):return next(s for s in report.scenarios if s.frame==case["frame"] and len(s.anchors)==len(parent)
                                      and s.anchors[0].extreme_at==parent[0].extreme_at)
        scenario=target(build_wave_hierarchy(frames,now))
        # Its overlapping W4 remains a non-trading diagonal alternative.
        assert scenario.status=="ALTERNATIVE"
        assert scenario.leg_observation_start==parent[-1].extreme_at+FRAME_MS[case["frame"]]
        assert "LOWER_FRAME_OBSERVED_INVALIDATION" not in scenario.invalid_reasons
        b=frames[case["frame"]][-1]
        frames[case["frame"]][-1]=replace(b,l=min(b.l,scenario.invalidation-.01)) if direction=="UP" else replace(b,h=max(b.h,scenario.invalidation+.01))
        broken=target(build_wave_hierarchy(frames,now))
        # All six historical endpoints already exist. A deep following
        # correction cannot erase the completed diagonal alternative.
        assert len(parent)==6
        assert broken.status=="ALTERNATIVE"
        assert "LOWER_FRAME_OBSERVED_INVALIDATION" not in broken.invalid_reasons
        assert broken.to_dict()["invalidation_scope"]=="HISTORICAL_REFERENCE_NOT_ACTIVE_INVALIDATION"
        return
    if case["operation"]=="CLASSIFY":
        labels={p.pattern for p in detect_complex_wave_patterns(parent,direction=direction,as_of=now)}
        assert case["expected_pattern"] in labels
        assert "REGULAR_FLAT_ABC_CANDIDATE" not in labels
        proof=enrich_complex_subdivisions(case["expected_pattern"],parent,child,parent_duration=FRAME_MS[case["frame"]],as_of=now)
        assert proof.status=="UNRESOLVED"
        return
    kwargs=dict(parent_duration=FRAME_MS[case["frame"]],as_of=now)
    pattern=case["candidate"]["pattern"]
    proof=enrich_complex_subdivisions(pattern,parent,child,**kwargs)
    assert proof.status=="PRICE_SUBDIVISIONS_CONFIRMED"
    assert list(proof.observed)==case["proof"]["expected"]
    assert proof.child_known_at<=now
    assert enrich_complex_subdivisions(pattern,parent,child[:-2],**kwargs).status=="UNRESOLVED"
    assert enrich_complex_subdivisions(pattern,parent,[replace(p,known_at=now+1) for p in child],**kwargs).status=="UNRESOLVED"
