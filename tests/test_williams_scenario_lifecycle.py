"""One deterministic contract is consumed by actual Python and Android policies."""
import json
from pathlib import Path

import pytest

from williams_market_phase import market_phase_context
from williams_scenario_lifecycle import ScenarioSignal, assess_scenario_opposition, evaluate_level_event
from williams_wave_hierarchy import WaveBar

H=3_600_000
CONTRACT=json.loads((Path(__file__).resolve().parents[1]/"app/src/test/resources/williams_scenario_lifecycle_contract.json").read_text())


@pytest.mark.parametrize("case",CONTRACT["level_events"],ids=lambda c:c["name"])
def test_shared_level_event_contract(case):
    args=dict(case["input"])
    args["history"]=[WaveBar(**b) for b in args["history"]]
    assert evaluate_level_event(**args).to_dict()==case["expected"]


@pytest.mark.parametrize("case",CONTRACT["phase_cases"],ids=lambda c:c["name"])
def test_shared_degree_scoped_phase_contract(case):
    result=market_phase_context(case["input"])
    expected=case["expected"]
    assert result["warning_scope"]==expected["warning_scope"]
    assert {r["frame"]:r["state"] for r in result["frames"]}==expected["frame_states"]
    hypotheses={h["scenario_id"]:h for f in result["frames"] for h in f["hypotheses"]}
    for id,signs in expected["signs_by_id"].items():
        assert hypotheses[id]["signs"]==signs
    assert not any(result[k] for k in ("can_place_orders","can_close","can_reverse","wave_genealogy_confirmed","probability_calibrated"))


@pytest.mark.parametrize("short",[False,True])
def test_same_confirmed_opposition_revokes_unfilled_parent_and_exits_older_position(short):
    side="SHORT" if short else "LONG"
    opposite="LONG" if short else "SHORT"
    source=ScenarioSignal("later",opposite,"TC2_THREE_WISE_MEN","STOP_ENTRY",19*H,110 if short else 90,90 if short else 110,24*H)
    bar=WaveBar(20*H,100,112,99,100) if short else WaveBar(20*H,100,101,88,100)
    for started in (18*H,19*H):
        result=assess_scenario_opposition(side,started,[source],[bar],100,21*H)
        assert result["blocker"]=="H1_PARENT_OPPOSITE_TRIGGERED"
        assert result["opposition"][0].id=="later"
    assert assess_scenario_opposition(side,20*H,[source],[bar],100,21*H)["blocker"] is None


def test_ambiguity_holds_new_risk_without_inventing_a_close():
    source=ScenarioSignal("ambiguous","SHORT","TC2_THREE_WISE_MEN","STOP_ENTRY",19*H,90,110,24*H)
    result=assess_scenario_opposition("LONG",18*H,[source],[WaveBar(20*H,100,112,88,100)],100,21*H)
    assert result["blocker"]=="H1_OPPOSITION_AMBIGUOUS"
    assert result["opposition"] is None
    assert result["uncertain"][0][1].state=="AMBIGUOUS"


def test_ntd_close_event_can_revoke_a_parent_but_other_profiles_cannot_invent_close_execution():
    def source(profile):return ScenarioSignal("close","SHORT",profile,"MARKET_ON_CLOSE",19*H,100,None,24*H)
    assert assess_scenario_opposition("LONG",18*H,[source("NTD_FIVE_DIMENSIONS")],[],100,21*H)["opposition"]
    assert assess_scenario_opposition("LONG",18*H,[source("TC2_THREE_WISE_MEN")],[],100,21*H)["opposition"] is None


def test_exchange_open_times_keep_the_last_closed_bar_and_drop_only_a_forming_bar():
    import pandas as pd
    from futures_williams_runtime import closed_strategy_frame
    frame=pd.DataFrame({"open_time_ms":[19*H,20*H,21*H],"close":[100,101,102]})
    assert list(closed_strategy_frame(frame,"1h",21*H).open_time_ms)==[19*H,20*H]
    assert len(closed_strategy_frame(frame,"1h",22*H))==3
