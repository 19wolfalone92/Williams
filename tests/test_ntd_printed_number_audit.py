from copy import deepcopy
from decimal import Decimal
import json

import pytest

from tools.audit_ntd_explicit_numbers import ROOT, audit, audit_table, compound_illustration, main, number


def dataset():
    return json.loads((ROOT / 'research/NTD_EXPLICIT_NUMERIC_EXAMPLES.json').read_text())


def test_ac_positive_control_reconciles_all_fills_not_signal_prices():
    table = audit()['tables'][0]
    assert table['all_printed_cells_reconciled']
    assert table['calculated_total_points'] == '452.30'
    assert table['calculated_total_gross_usd'] == '226150.00'
    assert all(Decimal(r['signal_to_reported_fill_difference']) > 0 for r in table['rows'])
    assert not table['historical_fills_verified']
    assert table['net_profit_usd'] is None
    assert not table['margin_path_verified']


def test_zone_table_preserves_wrong_cell_while_reconciling_fills_and_dollars():
    table = audit()['tables'][1]
    row = table['rows'][5]
    assert row['reported_points'] == '7.55'
    assert row['calculated_points'] == '78.55'
    assert not row['points_match']
    assert row['gross_match'] and row['cumulative_match']
    assert table['calculated_total_points'] == '904.20'
    assert table['sum_of_printed_row_points'] == '833.20'
    assert table['calculated_total_gross_usd'] == '452100.00'
    assert table['total_points_match'] and table['total_gross_match']
    assert not table['all_printed_cells_reconciled']
    assert not table['historical_contract_units_verified']


def test_substituting_signal_for_fill_inflates_pnl_and_breaks_source_reconciliation():
    source = dataset()['tables'][0]
    wrong = deepcopy(source)
    for row in wrong['rows']:
        row['fill_price'] = row['signal_price']
    correct, inflated = audit_table(source), audit_table(wrong)
    assert Decimal(inflated['calculated_total_gross_usd']) > Decimal(correct['calculated_total_gross_usd'])
    assert not inflated['all_printed_cells_reconciled']


def test_each_reported_fill_contributes_once_to_total():
    table = dataset()['tables'][0]
    full = audit_table(table)
    reduced = deepcopy(table)
    removed = reduced['rows'].pop(1)
    delta = (Decimal(table['exit_price']) - Decimal(removed['fill_price'])) * Decimal('500')
    assert Decimal(full['calculated_total_gross_usd']) - Decimal(audit_table(reduced)['calculated_total_gross_usd']) == delta
    reduced['rows'].append(deepcopy(reduced['rows'][0]))
    with pytest.raises(ValueError, match='DUPLICATE_ROW'):
        audit_table(reduced)


def test_arithmetic_conflicts_are_literal_not_rounded_or_repaired_labels():
    claims = {c['claim_id']: c for c in audit()['arithmetic_claims']}
    assert claims['NTD.SQUARING.POSITIVE_CONTROL']['matches_printed_result']
    assert claims['NTD.SQUARING.UP']['calculated'] == '1.4641'
    assert claims['NTD.SQUARING.DOWN']['calculated'] == '0.6561'
    assert claims['NTD.DIMENSION.ROUNDED_DIFFERENCE']['calculated'] == '0.038'
    assert sum(c['matches_printed_result'] for c in claims.values()) == 1


def test_compounding_separates_profit_from_ending_balance_and_preserves_month_boundary():
    result = audit()['compounding_illustration']
    assert Decimal(result['annual_ending_balance_factor']) == Decimal('3.138428376721')
    assert Decimal(result['annual_profit_percent']) == Decimal('213.8428376721')
    assert Decimal(result['annual_ending_balance_as_percent_of_initial']) == Decimal('313.8428376721')
    assert result['first_whole_month_reaching_target'] == 49
    assert Decimal(result['previous_month_balance_usd']) < 1_000_000
    assert Decimal(result['target_month_balance_usd']) >= 1_000_000
    assert result['arithmetic_average_return_is_not_geometric_return']
    assert not result['forecast']


@pytest.mark.parametrize('bad', [True, 1.1, 0, 'NaN', 'Infinity', '-Infinity', '0', '-1', 'not-a-number'])
def test_numeric_input_rejects_silent_float_nonfinite_and_nonpositive_values(bad):
    with pytest.raises(ValueError):
        number(bad)


@pytest.mark.parametrize('field,value', [
    ('source_sha256', '0' * 64), ('source_id', 'TC2_EN'),
    ('training_allowed', True), ('can_place_orders', True),
    ('independent_second_review', True),
])
def test_changed_provenance_cannot_be_certified(tmp_path, field, value):
    data = dataset()
    data[field] = value
    path = tmp_path / 'numbers.json'
    path.write_text(json.dumps(data))
    with pytest.raises(ValueError):
        audit(path)


def test_numbers_cannot_be_moved_to_an_unseen_diagram_page(tmp_path):
    data = dataset()
    data['tables'][0]['pdf_page'] = 90
    path = tmp_path / 'numbers.json'
    path.write_text(json.dumps(data))
    with pytest.raises(ValueError, match='WITHOUT_VISUAL_RECEIPT'):
        audit(path)


def test_duplicate_case_ids_cannot_hide_a_source_case(tmp_path):
    data = dataset()
    data['tables'][1]['case_id'] = data['tables'][0]['case_id']
    path = tmp_path / 'numbers.json'
    path.write_text(json.dumps(data))
    with pytest.raises(ValueError, match='DUPLICATE_NUMERIC_CASE_ID'):
        audit(path)


def test_compounding_work_is_bounded_for_malformed_research_input():
    spec = dataset()['compounding']
    with pytest.raises(ValueError, match='1200_MONTHS'):
        compound_illustration({**spec, 'constant_monthly_rate': '0.000000000000000001'})


def test_saved_report_reproduces_and_reconciliation_gate_remains_closed(tmp_path, capsys):
    result = audit()
    assert result == json.loads((ROOT / 'research/NTD_PRINTED_NUMBER_AUDIT.json').read_text())
    assert not result['is_backtest'] and not result['expert_gold']
    assert not result['execution_authority'] and not result['training_allowed']
    assert not result['all_printed_claims_reconciled']
    assert main(['--output', str(tmp_path / 'report.json'), '--require-reconciled']) == 2
    assert json.loads(capsys.readouterr().out) == result
