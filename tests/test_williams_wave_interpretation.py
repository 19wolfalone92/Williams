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


def test_serialized_chain_preserves_the_five_requested_analysis_frames():
    report=observed_market_context({},400*3_600_000)
    assert report['chain']==list(OBSERVED_MARKET_FRAMES)
    assert '4h' in report['chain']
