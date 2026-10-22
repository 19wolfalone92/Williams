"""Same sourced boundary corpus is consumed by Python and Android JUnit."""
import json
from decimal import Decimal
from pathlib import Path

import pytest

from ntd_balance_line import ntd_balance_line_evidence
from williams_course_campaign import course_target
from williams_edition_rules import edition_fractals, ntd_balance_line_setup,course_thumb_setup
from williams_exit_policy import plan_williams_stop
from williams_tc2_pyramid import (
    POLICY, next_tranche, require_profitable_position, cap_book_tranche, initial_unit_risk_pct,
)
from williams_wave_hierarchy import WaveBar

CONTRACT=json.loads((Path(__file__).resolve().parents[1]/"app/src/test/resources/williams_book_rules_contract.json").read_text())
HOUR=3_600_000

@pytest.mark.parametrize("case",CONTRACT["pyramid"],ids=lambda c:c["id"])
def test_exact_quantity_tranches_from_real_fill_ledger(case):
    plan={"policy":POLICY,"unit_quantity":case["unit"],"completed_quantities":case["fills"]}
    if "blocker" in case:
        with pytest.raises(RuntimeError,match=case["blocker"]):
            next_tranche(plan,owned_quantity=case["owned"],additions=len(case["fills"]))
    else:
        assert next_tranche(plan,owned_quantity=case["owned"],additions=len(case["fills"]))==Decimal(case["next"])

@pytest.mark.parametrize("side,sign",[("BUY",1),("SELL",-1)])
def test_profitable_position_must_be_proven_before_an_addition(side,sign):
    require_profitable_position(side,100,100+sign,100+2*sign)
    for mark in (100,100-sign):
        with pytest.raises(RuntimeError,match="PROFITABLE_POSITION"):
            require_profitable_position(side,100,mark,100+2*sign)
    with pytest.raises(RuntimeError):require_profitable_position(side,float("nan"),101,102)

def test_risk_cap_veto_cannot_clip_a_book_tranche():
    assert cap_book_tranche("0.5","0.500")==0.5
    with pytest.raises(RuntimeError,match="EXCEEDS_RISK_OR_MARGIN_CAP"):
        cap_book_tranche("0.5","0.499")
    assert initial_unit_risk_pct(.002,.006,.0025)==pytest.approx(.0004)
    assert initial_unit_risk_pct(.002,.006,.001)==pytest.approx(.0002)

@pytest.mark.parametrize("case",CONTRACT["course"])
def test_course_targets_need_the_actual_w3_w1_ratio(case):
    assert course_target(case["stage"],case["ratio"])==case["target"]

@pytest.mark.parametrize("case",CONTRACT["thumb"],ids=lambda c:c["id"])
@pytest.mark.parametrize("mirror",[False,True])
def test_thumb_ideal_and_weak_are_not_confused_with_invalid_formation(case,mirror):
    bars=[WaveBar((i+1)*HOUR,(h+l)/2,h,l,(h+l)/2,v)
          for i,(h,l,v) in enumerate(zip(case["highs"],case["lows"],case["volumes"]))]
    if mirror:bars=[WaveBar(b.t,240-b.o,240-b.l,240-b.h,240-b.c,b.v) for b in bars]
    setup=course_thumb_setup("LONG" if mirror else "SHORT",bars,.1)
    assert setup
    assert setup.context["thumb_strength"]==case["strength"]
    assert setup.context["finger_profitunity_colours"]==case["colours"]
    assert setup.context["thumb_admission_basis"]=="COURSE_PDF69_VOLUME_SHORTCUT"

@pytest.mark.parametrize("case",CONTRACT["fractals"],ids=lambda c:c["id"])
@pytest.mark.parametrize("mirror",[False,True])
def test_protection_and_entry_use_the_same_five_six_seven_nine_bar_geometry(case,mirror):
    bars=[WaveBar((i+1)*HOUR,x+1,120,x,x+1,1000) for i,x in enumerate(case["extrema"])]
    if mirror:bars=[WaveBar(b.t,240-b.o,240-b.l,240-b.h,240-b.c,b.v) for b in bars]
    side="SHORT" if mirror else "LONG"
    expected=132.1 if mirror else 107.9
    params=(side,150 if mirror else 90,120,None,115 if mirror else 125,.1,HOUR)
    proposal=plan_williams_stop(*params,bars,window=None)
    assert proposal and proposal["reason"]=="CONFIRMED_H1_FRACTAL"
    assert proposal["stop"]==pytest.approx(expected)
    pivots=edition_fractals(bars)
    assert any(p.center==case["center"] and p.kind==("HIGH" if mirror else "LOW") for p in pivots)
    # Neither a forming last candle nor an equal pivot predating ownership
    # can grant new structural protection.
    assert plan_williams_stop(*params,bars[:-1],window=None) is None
    assert plan_williams_stop(side,params[1],120,None,params[4],.1,4*HOUR,bars,window=None) is None

@pytest.mark.parametrize("case",CONTRACT["balance_line"],ids=lambda c:c["id"])
@pytest.mark.parametrize("mirror",[False,True])
def test_ntd_diagnostic_and_edition_scanners_agree_on_local_base(case,mirror):
    highs=case["highs"];lows=[x-2 for x in highs]
    side="LONG"
    jaw=[90.0]*len(highs);teeth=[89.0]*len(highs);lips=[91.0]*len(highs)
    trigger=case["trigger"]
    if mirror:
        highs,lows=[240-x for x in lows],[240-x for x in highs]
        side="SHORT";jaw,teeth,lips=[150.0]*len(highs),[151.0]*len(highs),[149.0]*len(highs)
        trigger=240-case["trigger"]
    bars=[WaveBar((i+1)*HOUR,(h+l)/2,h,l,(h+l)/2,1000) for i,(h,l) in enumerate(zip(highs,lows))]
    zeros=[0.0]*len(bars)
    evidence=ntd_balance_line_evidence(highs,lows,jaw,teeth,lips,zeros,zeros,side=side,tick_size=.1)
    setup=ntd_balance_line_setup(side,bars,jaw,teeth,lips,zeros,zeros,.1)
    assert evidence.status=="CANDIDATE" and setup
    assert evidence.base_index==setup.context["base_index"]==case["base"]
    assert evidence.reference_index==setup.context["signal_index"]==case["reference"]
    assert evidence.trigger_price==pytest.approx(trigger)
    assert setup.trigger==pytest.approx(trigger)

def test_android_runtime_does_not_promote_observation_sources_to_live_new_risk():
    from pathlib import Path
    runtime = (Path(__file__).resolve().parents[1] /
               "app/src/main/java/com/williamsbot/StandaloneRuntime.kt").read_text(encoding="utf-8")
    assert 'if(!williamsVerifiedNewRiskSource(profile))' in runtime
    assert 'if(williamsProfile !in setOf(williamsTc2Profile,williamsUnifiedProfile))' in runtime
    assert 'williamsVerifiedNewRiskSource(it.strategyProfile)' in runtime
