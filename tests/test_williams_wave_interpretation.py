from copy import deepcopy
import json
from pathlib import Path

import pytest

from williams_wave_interpretation import wave_interpretation
from williams_market_phase import market_phase_context
from williams_market_phase import OBSERVED_MARKET_FRAMES,observed_market_context

PATH=Path(__file__).resolve().parents[1]/'app/src/test/resources/williams_wave_interpretation_contract.json'
CONTRACT=json.loads(PATH.read_text())


@pytest.mark.parametrize('case',CONTRACT['cases'],ids=lambda c:c['id'])
def test_degree_scoped_rule_examples_and_counterexamples(case):
    output=wave_interpretation(case['input'])
    expected=case['expected']
    row=next(s for s in output['scopes'] if s['parent_frame']==expected['parent_frame']
             and s['child_frame']==expected['child_frame'])
    assert row['state']==expected['state']
    assert row['relation_count']==expected['relation_count']
    assert row['explanation_ru']
    for scope in output['scopes']:
        assert not scope['wave_genealogy_confirmed']
        for relation in scope['relations']:
            assert relation['anchors_known_at']<=case['input']['as_of']
            for key in ('wave_genealogy_confirmed','can_place_orders','can_close','can_reverse'):
                assert not relation[key]
    for key in ('wave_genealogy_confirmed','forecast_accuracy_certified','can_place_orders','can_close','can_reverse'):
        assert not output[key]


def test_market_phase_exposes_proofs_and_list_order_cannot_pick_the_winner():
    for case in CONTRACT['cases']:
        hierarchy=deepcopy(case['input'])
        result=wave_interpretation(hierarchy)
        hierarchy['scenarios'].reverse()
        assert wave_interpretation(hierarchy)==result
        assert market_phase_context(hierarchy)['wave_interpretation']==result


def test_missing_history_and_future_snapshots_remain_unknown():
    assert all(s['state']=='HISTORY_UNVERIFIED' for s in wave_interpretation({})['scopes'])
    h=deepcopy(CONTRACT['cases'][0]['input'])
    h['scenarios'][1]['observed_at']+=1
    result=wave_interpretation(h)
    assert 'child' in result['rejected_scenario_ids']
    assert next(s for s in result['scopes'] if s['parent_frame']=='4h' and s['child_frame']=='1h')['relation_count']==0


def test_junior_divergence_and_w4_minimum_keep_their_own_degrees():
    for case in CONTRACT['cases']:
        output=wave_interpretation(case['input'])
        row=next(s for s in output['scopes'] if s['parent_frame']=='4h' and s['child_frame']=='1h')
        if case['id'].startswith('junior-divergence'):
            assert row['relations'][0]['degree_warnings']==['JUNIOR_DIVERGENCE_WITHOUT_PARENT_W4_ZERO_PROOF']
        if case['id'].startswith('ao-minimum'):
            assert row['relations'][0]['parent_w4_minimum_only']
            assert row['relations'][0]['parent_active_leg']=='W4'
            assert row['relations'][0]['parent_scale_100_140']


def test_same_label_different_causal_pivots_remain_competing_not_certified():
    now=1_792_578_000_000
    hour=3_600_000
    def scenario(identifier, last_pivot, observed_at=now):
        points=[(now-12*hour,"LOW",95.0),(now-8*hour,"HIGH",112.0),
                (now-4*hour,"LOW",last_pivot)]
        return {"scenario_id":identifier,"frame":"1h","direction":"UP",
                "active_label":"W3","active_direction":"UP","label":"W2",
                "invalidation":95.0,"leg_low":94.0,"leg_high":113.0,
                "leg_start":points[-1][0],"status":"PROVISIONAL",
                "observed_at":observed_at,
                "anchors":[{"extreme_at":t,"known_at":t+3*hour,
                            "kind":kind,"price":price}
                           for t,kind,price in points]}
    def h1(*scenarios):
        frames=market_phase_context({"as_of":now,"scenarios":list(scenarios)})["frames"]
        return next(f for f in frames if f["frame"]=="1h")
    one=h1(scenario("A",102.0))
    assert one["state"]=="PHASE_CANDIDATE"
    assert one["distinct_structural_hypotheses"]==1
    repeated=h1(scenario("A",102.0),scenario("duplicate",102.0))
    assert repeated["state"]=="PHASE_CANDIDATE"
    disputed=h1(scenario("A",102.0),scenario("B",103.0))
    assert disputed["state"]=="ALTERNATIVES"
    assert disputed["ambiguity_reason"]=="SAME_LABEL_DIFFERENT_PIVOTS_OR_INVALIDATION"
    assert disputed["distinct_structural_hypotheses"]==2
    assert disputed["wave_count_ambiguous"]
    assert not disputed["wave_degree_confirmed"]
    assert not disputed["can_place_orders"]
    stale=h1(scenario("A",102.0),scenario("stale",103.0,now-hour))
    assert stale["state"]=="PHASE_CANDIDATE"


def test_serialized_chain_preserves_the_five_requested_analysis_frames():
    report=observed_market_context({},400*3_600_000)
    assert report['chain']==list(OBSERVED_MARKET_FRAMES)
    assert '4h' in report['chain']


def test_wave_anchor_cannot_be_known_after_only_one_right_candle():
    h=deepcopy(CONTRACT["cases"][0]["input"])
    child=h["scenarios"][1]
    pivot=child["anchors"][0]
    pivot["known_at"]=pivot["extreme_at"]+2*3_600_000
    observed=wave_interpretation(h)
    assert child["scenario_id"] in observed["rejected_scenario_ids"]
    scope=next(s for s in observed["scopes"]
        if s["parent_frame"]=="4h" and s["child_frame"]=="1h")
    assert scope["relation_count"]==0
    assert scope["state"]=="UNRESOLVED"
    assert not observed["can_place_orders"]
