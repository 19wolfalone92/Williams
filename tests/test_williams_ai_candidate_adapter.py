import copy
import json
from pathlib import Path
import shutil

import pytest

from tools.export_williams_ai_candidate_packet import main
from williams_ai_candidate_adapter import (
    AUTHORITY, HOUR_MS, OBSERVATION_SCHEMA, PROPOSAL_SCHEMA,
    CandidateAdapter, CandidateValidationError, digest, strict_loads,
)

ROOT = Path(__file__).resolve().parents[1]
RULE = 'TC2.ALLIGATOR.MEDIAN_SMMA_DISPLAY'


@pytest.fixture
def observation():
    start = 1_704_067_200_000  # 2024-01-01 UTC; expressly synthetic, not held out.
    return {'schema': OBSERVATION_SCHEMA, 'symbol': 'BTCUSDT', 'interval': '1h',
            'as_of_ms': start + 50 * HOUR_MS, 'provenance': 'SYNTHETIC',
            'bars': [{'open_time_ms': start + i * HOUR_MS,
                      'open': 100 + i, 'high': 102 + i, 'low': 99 + i,
                      'close': 101 + i, 'volume': 10} for i in range(50)]}


@pytest.fixture
def adapter():
    return CandidateAdapter()


def request_for(adapter, observation, rule=RULE):
    return adapter.build_request(source_id='TC2_EN', rule_ids=[rule], observation=observation)


def proposal_for(request, decision='ABSTAIN'):
    return {'schema': PROPOSAL_SCHEMA, 'request_sha256': request['request_sha256'],
            'observation_sha256': request['observation_sha256'], 'decision': decision,
            'rationale': 'Synthetic research example; no independent chart interpretation.',
            'citations': [copy.deepcopy(request['allowed_citations'][0])],
            'authority': dict(AUTHORITY)}


def test_valid_packet_is_detached_reproducible_and_cannot_certify_or_trade(adapter, observation):
    request = request_for(adapter, observation)
    same = request_for(adapter, copy.deepcopy(observation))
    assert request == same
    observation['bars'][-1]['close'] = 151
    assert request['observation']['bars'][-1]['close'] == 150
    receipt = adapter.validate_proposal(request=request, proposal=proposal_for(request))
    assert receipt['disposition'] == 'RESEARCH_ONLY_UNVERIFIED'
    assert not receipt['semantic_entailment_verified']
    assert not receipt['algorithm_guard_executed']
    assert not receipt['expert_gold']
    assert all(value is False for value in receipt['authority'].values())
    assert RULE in receipt['incomplete_rule_specifications']


@pytest.mark.parametrize('decision', ['ABSTAIN', 'CONFLICT', 'NEEDS_EXPERT_REVIEW'])
def test_all_research_outcomes_keep_authority_disabled(adapter, observation, decision):
    request = request_for(adapter, observation)
    receipt = adapter.validate_proposal(request=request, proposal=proposal_for(request, decision))
    assert receipt['authority'] == AUTHORITY


@pytest.mark.parametrize('decision', ['LONG_CANDIDATE', 'SHORT_CANDIDATE'])
def test_incomplete_atomic_rules_cannot_support_direction_even_with_real_citations(adapter, observation, decision):
    request = request_for(adapter, observation)
    with pytest.raises(CandidateValidationError, match='DIRECTION_REQUIRES_COMPLETE_RULE_SPEC'):
        adapter.validate_proposal(request=request, proposal=proposal_for(request, decision))


@pytest.mark.parametrize('window', ['GREEN', 'FADE', 'FAKE', 'SQUAT'])
@pytest.mark.parametrize('decision', ['LONG_CANDIDATE', 'SHORT_CANDIDATE'])
def test_complete_tc1_observer_specs_cannot_become_operational_tc2_candidates(adapter, observation, window, decision):
    request = adapter.build_request(source_id='TC1_RU', rule_ids=[f'TC1.MFI.WINDOW_{window}'],
                                    observation=observation)
    assert request['rules'][0]['FULL_ATOMIC_SPEC_COMPLETE'] is True
    assert adapter.validate_proposal(request=request, proposal=proposal_for(request))['authority'] == AUTHORITY
    with pytest.raises(CandidateValidationError, match='DIRECTION_REQUIRES_OPERATIONAL_TC2_SOURCE'):
        adapter.validate_proposal(request=request, proposal=proposal_for(request, decision))


@pytest.mark.parametrize('field', list(AUTHORITY))
@pytest.mark.parametrize('value', [True, 1, 0, 'false', None])
def test_authority_requires_literal_false_no_numeric_or_string_coercion(adapter, observation, field, value):
    request = request_for(adapter, observation)
    proposal = proposal_for(request)
    proposal['authority'][field] = value
    with pytest.raises(CandidateValidationError, match='AUTHORITY_ESCALATION'):
        adapter.validate_proposal(request=request, proposal=proposal)


@pytest.mark.parametrize('field,value', [
    ('order_type', 'MARKET'), ('quantity', 0.01), ('stop_price', 99),
    ('confidence', 0.999), ('training_allowed', True), ('future_return', 0.1),
])
def test_no_extra_order_training_or_outcome_fields_can_cross_the_boundary(adapter, observation, field, value):
    request = request_for(adapter, observation)
    proposal = proposal_for(request)
    proposal[field] = value
    with pytest.raises(CandidateValidationError, match='PROPOSAL_FIELDS_INVALID'):
        adapter.validate_proposal(request=request, proposal=proposal)


@pytest.mark.parametrize('change,reason', [
    (lambda o: o['bars'][-1].update(open_time_ms=o['as_of_ms']), 'FORMING_OR_FUTURE_BAR|BARS_NOT_CONTIGUOUS'),
    (lambda o: o.update(as_of_ms=o['as_of_ms']-1), 'FORMING_OR_FUTURE_BAR'),
    (lambda o: o.update(as_of_ms=o['as_of_ms']+HOUR_MS), 'LAST_CLOSED_BAR_STALE'),
    (lambda o: o['bars'][-2].update(open_time_ms=o['bars'][-1]['open_time_ms']), 'BARS_NOT_CONTIGUOUS'),
    (lambda o: o['bars'].pop(25), 'BARS_NOT_CONTIGUOUS'),
    (lambda o: o['bars'].reverse(), 'BARS_NOT_CONTIGUOUS'),
    (lambda o: o['bars'][0].update(open_time_ms=o['bars'][0]['open_time_ms']+1), 'BAR_TIME_NOT_H1_ALIGNED'),
    (lambda o: o.update(as_of_ms=True), 'AS_OF_INVALID'),
    (lambda o: o.update(interval='15m'), 'ONLY_CLOSED_H1_SUPPORTED'),
    (lambda o: o.update(provenance='EXPERT_GOLD'), 'OBSERVATION_PROVENANCE_INVALID'),
    (lambda o: o.update(future_close=999), 'OBSERVATION_FIELDS_INVALID'),
    (lambda o: o.update(bars=o['bars'][:10]), 'BAR_COUNT_INVALID'),
    (lambda o: o['bars'][0].update(high=90), 'OHLC_RANGE_INVALID'),
    (lambda o: o['bars'][0].update(volume=-1), 'BAR_NUMERIC_DATA_INVALID'),
])
def test_causal_observation_rejects_future_missing_reordered_or_promoted_data(adapter, observation, change, reason):
    change(observation)
    with pytest.raises(CandidateValidationError, match=reason):
        request_for(adapter, observation)


@pytest.mark.parametrize('value', [float('nan'), float('inf'), float('-inf'), True, '100', None, 10**1000])
def test_invalid_ohlc_never_coerced_or_imputed(adapter, observation, value):
    observation['bars'][10]['close'] = value
    with pytest.raises(CandidateValidationError, match='BAR_NUMERIC_DATA_INVALID'):
        request_for(adapter, observation)


def test_wrong_edition_or_unreviewed_import_cannot_fall_back_by_family(adapter, observation):
    with pytest.raises(CandidateValidationError, match='RULE_EDITION_MISMATCH'):
        adapter.build_request(source_id='TC2_RU_224', rule_ids=[RULE], observation=observation)
    imported = next(r for r in adapter._rules.values() if not r['source_reviewed_this_run'] and r['SOURCE_ID'])
    with pytest.raises(CandidateValidationError, match='RULE_SOURCE_NOT_REVIEWED'):
        adapter.build_request(source_id=imported['SOURCE_ID'], rule_ids=[imported['RULE_ID']], observation=observation)


@pytest.mark.parametrize('rule', ['TC2.FIBONACCI.EXACT_RATIO_CLAIM', 'TC2.PSYCHOLOGY.HUMAN_ONLY',
                                 'TC2.CONTEXT.TIMEFRAME_PREFERENCES'])
def test_nonmarket_or_contradicted_knowledge_can_be_reviewed_but_not_promoted_to_direction(adapter, observation, rule):
    request = request_for(adapter, observation, rule)
    review = proposal_for(request, 'NEEDS_EXPERT_REVIEW')
    assert not adapter.validate_proposal(request=request, proposal=review)['expert_gold']
    review['decision'] = 'SHORT_CANDIDATE'
    with pytest.raises(CandidateValidationError, match='DIRECTION_FROM_QUARANTINED_OR_NONMARKET_KNOWLEDGE'):
        adapter.validate_proposal(request=request, proposal=review)


@pytest.mark.parametrize('field,value', [('pdf_page', 999), ('source_id', 'TC2_RU_314'),
                                       ('source_sha256', '0'*64), ('rule_id', 'INVENTED'),
                                       ('pdf_page', True)])
def test_citations_are_allowlisted_to_this_exact_source_and_page(adapter, observation, field, value):
    request = request_for(adapter, observation)
    proposal = proposal_for(request)
    proposal['citations'][0][field] = value
    with pytest.raises(CandidateValidationError, match='CITATION_OUTSIDE_REQUEST|CITATION_PAGE_INVALID'):
        adapter.validate_proposal(request=request, proposal=proposal)


@pytest.mark.parametrize('target', ['request_sha256', 'observation_sha256'])
def test_a_proposal_cannot_be_replayed_for_a_different_observation(adapter, observation, target):
    request = request_for(adapter, observation)
    proposal = proposal_for(request)
    proposal[target] = '1'*64
    with pytest.raises(CandidateValidationError, match='PROPOSAL_BOUND_TO_DIFFERENT_REQUEST'):
        adapter.validate_proposal(request=request, proposal=proposal)


@pytest.mark.parametrize('mutation', [
    lambda r: r['source'].update(edition='First edition'),
    lambda r: r['source'].update(source_sha256='1'*64),
    lambda r: r['rules'][0].update(CLASSIFICATION='CANONICAL_OVERRIDE'),
    lambda r: r['allowed_citations'].append({'rule_id':'X','source_id':'TC2_EN','source_sha256':'1'*64,'pdf_page':1}),
    lambda r: r['limits'].update(expert_gold=True),
])
def test_rehashing_forged_request_does_not_bypass_pinned_snapshot(adapter, observation, mutation):
    request = request_for(adapter, observation)
    mutation(request)
    request['request_sha256'] = digest({k:v for k,v in request.items() if k != 'request_sha256'})
    with pytest.raises(CandidateValidationError, match='REQUEST_SNAPSHOT_OR_CONTENT_CHANGED'):
        adapter.validate_proposal(request=request, proposal=proposal_for(request))


def test_old_request_rejected_after_evidence_changes(adapter, observation, tmp_path):
    request = request_for(adapter, observation)
    shutil.copytree(ROOT/'research', tmp_path/'research')
    path = tmp_path/'research'/'MASTER_RULE_REGISTRY.json'
    path.write_text(path.read_text()+'\n')
    changed = CandidateAdapter(tmp_path)
    with pytest.raises(CandidateValidationError, match='REQUEST_SNAPSHOT_OR_CONTENT_CHANGED'):
        changed.validate_proposal(request=request, proposal=proposal_for(request))


@pytest.mark.parametrize('text', ['{"x":1,"x":2}', '{"nested":{"a":false,"a":0}}',
                                  '{"x":NaN}', '{"x":Infinity}', '{"x":1e999}', '{invalid'])
def test_raw_json_rejects_ambiguous_or_nonfinite_wire_data(text):
    with pytest.raises(CandidateValidationError):
        strict_loads(text)


def test_empty_or_duplicate_citations_and_future_schema_reject(adapter, observation):
    request = request_for(adapter, observation)
    proposal = proposal_for(request, 'LONG_CANDIDATE')
    proposal['citations'] = []
    with pytest.raises(CandidateValidationError, match='DIRECTION_WITHOUT_CITATION'):
        adapter.validate_proposal(request=request, proposal=proposal)
    proposal = proposal_for(request)
    proposal['citations'] *= 2
    with pytest.raises(CandidateValidationError, match='DUPLICATE_CITATION'):
        adapter.validate_proposal(request=request, proposal=proposal)
    proposal['schema'] = 'WILLIAMS_AI_RESEARCH_PROPOSAL_V2'
    with pytest.raises(CandidateValidationError, match='PROPOSAL_VERSION_UNSUPPORTED'):
        adapter.validate_proposal(request=request, proposal=proposal)


def test_offline_cli_roundtrip_and_invalid_packet_does_not_replace_receipt(adapter, observation, tmp_path):
    obs_path, req_path, prop_path, out = [tmp_path/f'{name}.json' for name in ('obs','req','proposal','receipt')]
    obs_path.write_text(json.dumps(observation))
    assert main(['build','--source-id','TC2_EN','--rule-id',RULE,
                 '--observation',str(obs_path),'--out',str(req_path)]) == 0
    request = json.loads(req_path.read_text())
    prop_path.write_text(json.dumps(proposal_for(request)))
    assert main(['validate','--request',str(req_path),'--proposal',str(prop_path),'--out',str(out)]) == 0
    original = out.read_text()
    proposal = proposal_for(request); proposal['authority']['can_move_stops'] = True
    prop_path.write_text(json.dumps(proposal))
    assert main(['validate','--request',str(req_path),'--proposal',str(prop_path),'--out',str(out)]) == 2
    assert out.read_text() == original
