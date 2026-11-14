import copy
import json
import shutil
import sys

import pytest

from tools.audit_tc2_translation import ROOT, audit, main, printed_arithmetic
from tools.audit_williams_master_research import audit as master_audit


def _read(name):
    return json.loads((ROOT / 'research' / name).read_text())


def _copy(tmp_path):
    shutil.copytree(ROOT / 'research', tmp_path / 'research')
    return tmp_path / 'research'


def _mutate_structure(tmp_path, change):
    folder = _copy(tmp_path)
    data = _read('TC2_EDITION_STRUCTURE_MAP.json')
    change(data)
    (folder / 'TC2_EDITION_STRUCTURE_MAP.json').write_text(json.dumps(data))
    return audit(tmp_path)


def test_complete_physical_navigation_cannot_certify_unread_translation_pages():
    report = master_audit()
    assert report['integrity_valid'], report['errors']
    alignment = report['translation_alignment']
    assert alignment['physical_pages_partitioned'] == 795
    assert alignment['heading_crop_receipts'] == 68
    assert alignment['selected_semantic_anchors'] == 16
    assert not alignment['semantic_alignment_complete']
    assert not alignment['heading_crops_count_as_full_page_reading']
    coverage = report['source_coverage']['per_source']
    assert coverage['TC2_RU_224']['first_pass_receipts'] == 22
    assert coverage['TC2_RU_314']['first_pass_receipts'] == 27
    notes = _read('review_notes.json')
    # This rendered heading crop has no full-page reading receipt. A complete
    # section partition cannot invent one or advance the completion gate.
    assert 'TC2_RU_314:23' not in notes
    assert report['source_coverage']['semantically_reviewed_single_pass'] == 724
    assert not report['execution_safety']['main_merge_allowed']


@pytest.mark.parametrize('flag', [
    'semantic_alignment_complete', 'page_bijection_claimed', 'constant_offset_claimed',
    'all_pages_read_claimed', 'independent_second_review', 'can_place_orders', 'training_allowed',
])
def test_structure_cannot_acquire_semantic_or_trade_authority(tmp_path, flag):
    report = _mutate_structure(tmp_path, lambda data: data.update({flag: True}))
    assert 'STRUCTURE_FALSE_SEMANTIC_OR_AUTHORITY_CLAIM' in report['errors']


def test_overlapping_or_uncovered_page_cannot_be_hidden_by_same_total(tmp_path):
    def change(data):
        segments = data['editions'][0]['segments']
        segments[1]['pdf_span'][1] += 1
        segments[2]['pdf_span'][1] -= 1
    report = _mutate_structure(tmp_path, change)
    assert 'STRUCTURE_PAGE_PARTITION_INVALID' in report['errors']


@pytest.mark.parametrize('bad_span', [[True, 29], [0, 29], [30, 29], [1, 999], [1]])
def test_invalid_section_ranges_fail_closed(tmp_path, bad_span):
    report = _mutate_structure(tmp_path, lambda d: d['editions'][0]['segments'][0].update(pdf_span=bad_span))
    assert 'STRUCTURE_SPAN_INVALID' in report['errors']


def test_heading_identity_must_match_original_acquisition(tmp_path):
    def change(data):
        data['editions'][1]['segments'][1]['heading_review']['acquired_text_sha256'] = '0' * 64
    assert 'STRUCTURE_HEADING_RECEIPT_INVALID' in _mutate_structure(tmp_path, change)['errors']


def test_russian314_chapter10_not_parsed_as_chapter1_and_no_constant_page_offset():
    editions = {e['source_id']: e for e in _read('TC2_EDITION_STRUCTURE_MAP.json')['editions']}
    lookup = {sid: {s['unit_id']: s['pdf_span'] for s in e['segments']} for sid, e in editions.items()}
    assert lookup['TC2_RU_314']['CH10'][0] == 177
    assert lookup['TC2_RU_314']['CH01'][0] == 23
    assert lookup['TC2_EN']['CH10'][0] == 154
    assert lookup['TC2_EN']['CH01'][0] == 30
    assert lookup['TC2_RU_314']['CH12'] == [201, 218]
    assert lookup['TC2_RU_224']['CH12'] == [153, 166]


def test_absent_bibliography_cannot_be_assigned_to_source_code_pages(tmp_path):
    data = _read('TC2_EDITION_STRUCTURE_MAP.json')
    short = next(e for e in data['editions'] if e['source_id'] == 'TC2_RU_224')
    assert short['absent_units'] == ['BIBLIOGRAPHY', 'INDEX']
    assert short['segments'][-1]['unit_id'] == 'APPENDIX7'
    assert short['segments'][-1]['pdf_span'] == [218, 224]
    def change(d):
        next(e for e in d['editions'] if e['source_id'] == 'TC2_RU_224')['absent_units'] = []
    assert 'STRUCTURE_FALSE_ABSENCE_CLAIM' in _mutate_structure(tmp_path, change)['errors']


def test_zero_text_does_not_turn_a_rendered_cover_into_a_blank():
    notes = _read('review_notes.json')
    assert notes['TC2_RU_314:202']['page_kind'] == 'VISUALLY_CONFIRMED_BLANK'
    assert notes['TC2_RU_314:218']['visual_review']
    data = _read('TC2_EDITION_STRUCTURE_MAP.json')
    paratext = next(e for e in data['editions'] if e['source_id'] == 'TC2_RU_314')['segments'][-1]
    assert paratext['pdf_span'] == [311, 314]
    assert paratext['relation'] == 'EDITION_SPECIFIC_PARATEXT_NOT_SEMANTICALLY_ALIGNED'
    assert 'image-only back cover314' in paratext['note']
    assert 'TC2_RU_314:314' not in notes


def test_ao_source_reference_is_appendix7_without_rewriting_acquisition():
    notes = _read('review_notes.json')
    rule = next(r for r in _read('MASTER_RULE_REGISTRY.json')['rules']
                if r['RULE_ID'] == 'TC2.AO.PRINTED_MIDPOINT_CONFLICT')
    assert notes['TC2_EN:247']['section'] == 'APPENDIX7_AO_SOURCE_CODE'
    assert 'appendix7' in rule['ORIGINAL_MEANING']
    assert all('appendix3' not in c for c in rule['CONFLICTS'])


def test_translation_agreement_is_not_independent_corroboration():
    data = _read('TC2_TRANSLATION_CONFLICT_REGISTER.json')
    short = next(c for c in data['conflicts'] if c['conflict_id'] == 'TC2.C12.RU_SHORT_LABEL')
    assert {s['source_id'] for s in short['evidence']} == {'TC2_EN', 'TC2_RU_224', 'TC2_RU_314'}
    assert not short['independent_second_review']
    assert not data['entire_translations_certified']
    assert not short['execution_authority']


def test_conflict_cannot_cite_an_unread_page_with_only_a_heading_crop(tmp_path):
    folder = _copy(tmp_path)
    data = _read('TC2_TRANSLATION_CONFLICT_REGISTER.json')
    next(p for p in data['conflicts'][0]['evidence'] if p['source_id'] == 'TC2_RU_314')['pdf_pages'] = [23]
    (folder / 'TC2_TRANSLATION_CONFLICT_REGISTER.json').write_text(json.dumps(data))
    assert 'TC2_EVIDENCE_WITHOUT_READING_RECEIPT' in audit(tmp_path)['errors']
    assert not master_audit(tmp_path)['integrity_valid']


def test_cross_edition_duplicate_cannot_hide_missing_english_evidence(tmp_path):
    folder = _copy(tmp_path)
    data = _read('TC2_TRANSLATION_CONFLICT_REGISTER.json')
    evidence = data['conflicts'][0]['evidence']
    evidence[0] = copy.deepcopy(evidence[1])
    (folder / 'TC2_TRANSLATION_CONFLICT_REGISTER.json').write_text(json.dumps(data))
    assert 'TC2_EVIDENCE_EDITION_SET_INVALID' in audit(tmp_path)['errors']


def test_gold_printed_totals_reconcile_but_do_not_prove_equal_risk_improvement():
    result = printed_arithmetic(_read('TC2_CH12_EXPLICIT_NUMBERS.json'))['gold']
    assert result['printed_rows_reconcile'] and result['printed_totals_reconcile']
    assert result['weighted_total_usd'] == '47100.00'
    assert result['equal_one_per_entry_total_usd'] == '14660.00'
    assert (result['equal_position_contracts'], result['weighted_position_contracts']) == (4, 13)
    assert result['planned_capacity_contracts'] == 15
    assert not result['fifth_tranche_included_in_result']
    assert not result['actual_fifth_entry_execution_known']
    assert not result['equal_risk_improvement_certified']
    assert result['net_profit_usd'] is None and result['percentage_return'] is None


def test_costco_approximate_prices_do_not_invent_weights_costs_or_exact_erratum():
    result = printed_arithmetic(_read('TC2_CH12_EXPLICIT_NUMBERS.json'))['costco']
    assert result['individual_price_differences_usd'] == ['4.58', '2.99', '1.73']
    assert result['conditional_three_share_combined_gross_usd'] == '9.30'
    assert result['conditional_gross_per_final_share_usd'] == '3.10'
    assert result['printed_minus_conditional_sum_usd'] == '-0.01'
    assert result['discrepancy_status'].endswith('NOT_CERTIFIED_ERRATUM')
    assert result['actual_entry_quantities'] is None
    assert result['inferred_fee_to_force_reconciliation_usd'] is None
    assert result['net_profit_usd'] is None


@pytest.mark.parametrize('value', ['NaN', 'Infinity', '-Infinity', '0', '-1', True, 34.16])
def test_printed_arithmetic_rejects_invalid_or_imprecise_input_types(value):
    data = _read('TC2_CH12_EXPLICIT_NUMBERS.json')
    data['costco']['entry_prices_usd'][0] = value
    with pytest.raises(ValueError):
        printed_arithmetic(data)


@pytest.mark.parametrize('quantity', [True, 0, -1, 1.5])
def test_gold_quantities_cannot_silently_change_exposure(quantity):
    data = _read('TC2_CH12_EXPLICIT_NUMBERS.json')
    data['gold']['executed_quantities'][0] = quantity
    with pytest.raises(ValueError, match='POSITIVE_WHOLE_QUANTITIES'):
        printed_arithmetic(data)


def test_unseen_fifth_gold_entry_and_unknown_costco_costs_cannot_be_certified():
    for section, key in [('gold', 'fifth_tranche_in_printed_profit_table'), ('costco', 'fees_and_slippage_known')]:
        data = _read('TC2_CH12_EXPLICIT_NUMBERS.json')
        data[section][key] = True
        with pytest.raises(ValueError):
            printed_arithmetic(data)


def test_stored_numeric_report_reproduces_and_complete_gate_remains_closed(monkeypatch, capsys):
    assert audit() == _read('TC2_TRANSLATION_AUDIT.json')
    monkeypatch.setattr(sys, 'argv', ['audit_tc2_translation.py', '--require-complete'])
    assert main() == 2
    assert json.loads(capsys.readouterr().out)['integrity_valid']
