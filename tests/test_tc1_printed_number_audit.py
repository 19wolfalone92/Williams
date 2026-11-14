from copy import deepcopy
import json
from pathlib import Path

import pytest

from tools.audit_tc1_worked_examples import audit, audit_case, main, number, replay

ROOT = Path(__file__).resolve().parents[1]


def test_book_ledgers_keep_prior_realized_profit_and_surviving_entry_lots():
    report = audit()
    chf, soy = report['cases']
    assert chf['body_ledger']['realized_gross_usd'] == '189350.00'
    assert soy['body_ledger']['realized_gross_usd'] == '37737.50'
    for case in (chf, soy):
        events = case['body_ledger']['events']
        assert [e['open_quantity'] for e in events] == [3, 1, 6, 10, 3, 6, 0]
        trim = events[4]
        assert [lot['quantity'] for lot in trim['lot_allocations']] == [1, 5, 1]
        assert [lot['quantity'] for lot in events[-1]['lot_allocations']] == [3, 3]
        assert case['body_ledger']['open_lots'] == []
        assert case['body_ledger']['net_profit_usd'] is None
        assert case['body_ledger']['historical_fills_verified'] is False
        assert case['body_ledger']['margin_path_verified'] is False
    assert report['is_backtest'] is False
    assert report['training_allowed'] is False
    assert report['can_place_orders'] is False
    assert report['all_printed_claims_reconciled'] is False


def test_swiss_hypothetical_stop_is_not_earlier_executed_profit():
    case = audit()['cases'][0]
    assert case['hypothetical_stop']['realized_gross_usd'] == '22225.00'
    actual = {c['claim_id']: c for c in case['printed_claim_checks']}
    assert actual['earlier_actual_vs_hypothetical']['calculated_usd'] == '14600.00'
    assert actual['first_lot_trim']['calculated_usd'] == '27862.50'
    assert actual['final_loss']['calculated_usd'] == '-2062.50'
    assert case['printed_internal_arithmetic'][0]['calculated_usd'] == '194852.50'


def test_soy_first_trim_is_in_total_and_initial_risk_uses_open_exposure():
    case = audit()['cases'][1]
    claims = {c['claim_id']: c for c in case['printed_claim_checks']}
    assert claims['trim_cumulative']['calculated_usd'] == '19812.50'
    assert claims['trim_cumulative']['reported_minus_calculated_usd'] == '-2550.00'
    assert claims['final_total']['reported_minus_calculated_usd'] == '-2550.00'
    assert claims['initial_loss']['calculated_usd'] == '825.00'
    assert claims['final_old_lots']['matches_literal_body_ledger']
    assert claims['final_new_lots']['matches_literal_body_ledger']


def test_explicit_chart_prices_are_separate_variants_not_silently_selected():
    chf, soy = audit()['cases']
    assert chf['explicit_chart_variants'][0]['realized_gross_usd'] == '198100.00'
    assert soy['explicit_chart_variants'][0]['realized_gross_usd'] == '37675.00'
    assert chf['body_ledger']['realized_gross_usd'] == '189350.00'
    assert soy['body_ledger']['realized_gross_usd'] == '37737.50'


@pytest.mark.parametrize('bad', [True, 1.25, 1, '0', '-1', 'NaN', 'Infinity', '-Infinity', 'oops'])
def test_quote_numbers_reject_nonfinite_nonpositive_and_binary_float_values(bad):
    with pytest.raises(ValueError):
        number(bad)


@pytest.mark.parametrize('bad_quantity', [True, 0, -1, 1.5, '3'])
def test_contract_quantity_is_a_positive_integer(bad_quantity):
    with pytest.raises(ValueError, match='CONTRACTS'):
        replay('LONG', '50', [{'event_id': 'a', 'action': 'OPEN',
                               'quantity': bad_quantity, 'price': '100'}])


def test_close_cannot_turn_into_an_unreported_reverse_position():
    events = [{'event_id': 'open', 'action': 'OPEN', 'quantity': 3, 'price': '100'},
              {'event_id': 'close', 'action': 'CLOSE', 'quantity': 5, 'price': '99'}]
    with pytest.raises(ValueError, match='EXCEEDS_OPEN'):
        replay('LONG', '50', events)


def test_short_partial_close_add_and_losing_final_exit_keep_cost_basis():
    events = [{'event_id': 'a', 'action': 'OPEN', 'quantity': 3, 'price': '100.25'},
              {'event_id': 'b', 'action': 'CLOSE', 'quantity': 2, 'price': '99.75'},
              {'event_id': 'c', 'action': 'OPEN', 'quantity': 1, 'price': '99'},
              {'event_id': 'd', 'action': 'CLOSE', 'quantity': 2, 'price': '101'}]
    original = deepcopy(events)
    result = replay('SHORT', '50', events)
    assert events == original
    assert result['events'][1]['realized_gross_usd'] == '50.00'
    assert result['events'][-1]['realized_gross_usd'] == '-137.50'
    assert result['realized_gross_usd'] == '-87.50'


def test_duplicate_event_id_is_not_double_counted():
    event = {'event_id': 'same', 'action': 'OPEN', 'quantity': 1, 'price': '100'}
    with pytest.raises(ValueError, match='DUPLICATE'):
        replay('LONG', '50', [event, event])


def test_report_is_reproducible_and_complete_check_remains_open(tmp_path, capsys):
    saved = json.loads((ROOT / 'research/TC1_PRINTED_NUMBER_AUDIT.json').read_text())
    assert saved == audit()
    assert main(['--output', str(tmp_path / 'report.json'), '--require-reconciled']) == 2
    assert json.loads((tmp_path / 'report.json').read_text()) == saved
    capsys.readouterr()


def test_input_pins_and_page_bounds_are_checked(tmp_path):
    data = json.loads((ROOT / 'research/TC1_EXPLICIT_NUMERIC_EXAMPLES.json').read_text())
    target = tmp_path / 'input.json'
    data['source_sha256'] = '0' * 64
    target.write_text(json.dumps(data))
    with pytest.raises(ValueError, match='SOURCE_PIN'):
        audit(target)
    data['source_sha256'] = '36f6822811088a06254009181480aa1c27af705a76960ecf0342023df803331c'
    data['cases'][0]['source_pages'] = [141]
    target.write_text(json.dumps(data))
    with pytest.raises(ValueError, match='SOURCE_PAGE'):
        audit(target)


def test_unmapped_chart_variant_cannot_be_reported_as_a_recalculation():
    case = json.loads((ROOT / 'research/TC1_EXPLICIT_NUMERIC_EXAMPLES.json').read_text())['cases'][0]
    case['explicit_chart_variants'][0]['event_id'] = 'invented'
    with pytest.raises(ValueError, match='VARIANT_EVENT'):
        audit_case(case)
