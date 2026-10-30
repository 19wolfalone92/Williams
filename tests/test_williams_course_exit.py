"""Source-case mechanics and owner/flat guards, not calibrated wave accuracy."""
from copy import deepcopy
import json
from pathlib import Path

import pytest

from williams_course_exit import (course_exit_plan, course_exit_proof_blocker,
                                   course_closed_motive_handoff, BOOK_EXIT_REASONS)
from williams_wave_hierarchy import WaveBar

H = 3_600_000
CASES = json.loads((Path(__file__).parents[1]/"app/src/test/resources/williams_course_exit_contract.json").read_text())["cases"]


def exit_fixture(c, motive_side="LONG", base=0, mirror_sum=400):
    kind=c["kind"];fault=c.get("fault")
    corrective=kind in {"C_ADD","C_INVALID"}
    side=("SHORT" if motive_side=="LONG" else "LONG") if corrective else motive_side
    direction="UP" if motive_side=="LONG" else "DOWN"
    def flip(p):return p if motive_side=="LONG" else mirror_sum-p
    end=180 if corrective else 157 if fault=="OLD_REACH" else 124 if kind=="W3" else 146 if kind=="W5_STALL" else 150
    as_of=base+(end+3)*H
    count=9 if corrective else 3 if kind=="W3" else 5 if kind=="W5_STALL" else 6
    raw=[100,120,108,148,130,160,120,142,105]
    def anchor(p,t,i,frame):return {"kind":"LOW" if (i%2==0)==(frame[1]=="UP") else "HIGH",
        "price":p,"extreme_at":base+t*H,"known_at":base+t*H+3*frame[0]}
    parent={"scenario_id":"course-parent","frame":"1h","status":"PROVISIONAL","direction":direction,
        "label":"C" if corrective else "W5" if count==6 else "W4" if count==5 else "W2",
        "observed_at":as_of,"scale_frame":"30m","scale_bars":101,
        "target_zone":True,"divergence":True,"squat":True,"terminal_fractal":True,
        "anchors":[anchor(flip(p),100+10*i,i,(H,direction)) for i,p in enumerate(raw[:count])]}
    child_direction=("DOWN" if direction=="UP" else "UP") if corrective else direction
    child_prices=[142,128,135,111,120,105] if corrective else [130,145,138,158] if kind=="W5_STALL" else [130,140,135,155,145,160]
    start=170 if corrective else 140
    child={"scenario_id":"course-child","frame":"5m","status":"PROVISIONAL","direction":child_direction,
        "label":"W3" if kind=="W5_STALL" else "W5","observed_at":as_of,"scale_bars":121,
        "target_zone":True,"divergence":True,"squat":True,"terminal_fractal":True,
        "anchors":[anchor(flip(p),start+2*i,i,(300_000,child_direction)) for i,p in enumerate(child_prices)]}
    if kind=="W3":
        scenarios=[parent]
        peak=127 if fault=="NOT_REACHED" else 131 if fault=="EXTENDED" else 129
        knots=[(0,100),(100,100),(110,120),(120,108),(124,peak),(126,min(peak-1,128))]
    elif kind=="W5_STALL":
        scenarios=[parent,child];knots=[(0,100),(100,100),(110,120),(120,108),(130,148),(140,130),(142,145),(144,138),(146,158),(148,150)]
    else:
        scenarios=[parent,child];knots=[(0,100)]+[(100+10*i,p) for i,p in enumerate(raw)]
    def price(i):
        for (t,p),(u,q) in zip(knots,knots[1:]):
            if t<=i<=u:return p+(q-p)*(i-t)/(u-t)
        return knots[-1][1]
    rows=[]
    for i in range(1,end+3):
        p=flip(price(i));rows.append(WaveBar(base+i*H,p,p+.05,p-.05,p,1000+i))
    contract=161 if kind=="C_INVALID" else 106 if corrective else 119.9 if kind=="W3" else 144.9 if kind=="W5_STALL" else 159
    if fault=="NOT_CROSSED":contract=120.1
    kwargs=dict(hierarchy={"as_of":as_of,"frame_errors":{},"scenarios":scenarios},side=side,
        origin_at=base+100*H,stage="C_ADD" if corrective else "W5" if kind=="W5_STALL" else kind,
        opened_at=base+(155 if corrective else 101)*H,now_ms=as_of+1000,bars=rows,contract=flip(contract),
        correction={"motive_campaign_id":"motive-owner","flat_confirmed_at_ms":base+154*H,"tick":.1} if corrective else None)
    if fault=="CHILD_MISSING":scenarios.remove(child)
    elif fault=="CHILD_AMBIGUOUS":scenarios.append(deepcopy(child))
    elif fault=="PARENT_AMBIGUOUS":scenarios.append(deepcopy(parent))
    elif fault=="PARENT_SIGN_MISSING":parent["divergence"]=False
    elif fault=="CHILD_SIGN_MISSING":child["squat"]=False
    elif fault=="FUTURE_CHILD":child["anchors"][-1]["known_at"]=as_of+1
    elif fault=="PREENTRY":kwargs["opened_at"]=as_of+1
    elif fault=="ORIGIN":kwargs["origin_at"]-=H
    elif fault=="GAP":rows.pop(80)
    elif fault=="H1_ERROR":kwargs["hierarchy"]["frame_errors"]["1h"]="MISSING"
    elif fault=="CHILD_ERROR":kwargs["hierarchy"]["frame_errors"]["5m"]="MISSING"
    elif fault=="STALE":kwargs["now_ms"]+=H
    elif fault=="NO_HANDOFF":kwargs["correction"]=None
    elif fault=="LATE_FLAT":kwargs["correction"]["flat_confirmed_at_ms"]=kwargs["opened_at"]
    elif fault in {"LIVE_INVALIDATED","OTHER_INVALIDATED"}:
        child.update(status="INVALIDATED",invalid_reasons=["LIVE_PRICE_INVALIDATED_COUNT" if fault=="LIVE_INVALIDATED" else "W3_SHORTEST_OR_NEGATIVE_MOTIVE_LEG"])
    return kwargs


@pytest.mark.parametrize("side",["LONG","SHORT"])
@pytest.mark.parametrize("c",CASES,ids=lambda c:c["id"])
def test_shared_source_terminal_and_failed_count_cases_require_causal_pinned_evidence(c,side):
    args=exit_fixture(c,side)
    proof=course_exit_plan(**args)
    actual=proof.get("reason") if proof["status"]=="OBSERVED_COURSE_SOURCE_RULE" else "BLOCKED"
    assert actual==c["expected"],(c,proof)
    assert not proof["can_place_orders"] and not proof["can_close"] and not proof["can_reverse"]
    assert not proof["wave_degree_confirmed"]


@pytest.mark.parametrize("side",["LONG","SHORT"])
@pytest.mark.parametrize("filled,flat,clean",[(a,b,c) for a in (False,True) for b in (False,True) for c in (False,True)])
def test_correction_handoff_requires_all_three_independent_exchange_confirmations(side,filled,flat,clean):
    p=course_closed_motive_handoff("motive",side,100,"COURSE_W5_TERMINAL_CONFIRMED",200,
        signed_exit_filled=filled,signed_flat=flat,orders_clean=clean)
    assert (p is not None)==(filled and flat and clean)
    if p:
        assert p["side"]!=side and not p["can_place_orders"]


def test_durable_exit_proof_cannot_be_replayed_on_another_owner_or_an_old_clock():
    now=200_000;opened=100_000
    p=dict(owner_id="owner",symbol="BTCUSDT",side="LONG",reason="COURSE_W5_TERMINAL_CONFIRMED",
           trigger_price_basis="CONTRACT_PRICE",observed_at_ms=now-1000)
    assert course_exit_proof_blocker(p,"owner","BTCUSDT","LONG",p["reason"],opened,now) is None
    for change in ({"owner_id":"other"},{"symbol":"ETHUSDT"},{"side":"SHORT"},{"reason":"TAKE_PROFIT"},
                   {"trigger_price_basis":"MARK_PRICE"},{"observed_at_ms":now+1},{"observed_at_ms":1}):
        assert course_exit_proof_blocker(p|change,"owner","BTCUSDT","LONG",p["reason"],opened,now)
    assert "CORRECTION_PARENT_PRICE_INVALIDATED" not in BOOK_EXIT_REASONS


@pytest.mark.parametrize("reason",["TAKE_PROFIT","STRATEGY_EXIT","CORRECTION_PARENT_PRICE_INVALIDATED","LIPS_CLOSE_EXIT",""])
def test_course_policy_rejects_unqualified_exit_reasons(reason):
    from williams_execution_policy import market_exit_blocker
    from williams_edition_rules import COURSE
    assert market_exit_blocker(COURSE,reason)=="COURSE_EXIT_REQUIRES_OWN_BOOK_RULE"
    assert market_exit_blocker(COURSE,"MANUAL_EXIT") is None
    assert market_exit_blocker(COURSE,"KILL_SWITCH") is None
