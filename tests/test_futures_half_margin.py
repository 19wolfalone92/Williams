"""Shared account-wide acceptance cases, consumed by Python and Android."""
import copy
import json
from decimal import Decimal
from pathlib import Path

import pytest

from futures_half_margin import HalfMarginUnknown, margin_observation, assess_half_margin

FIXTURES = Path(__file__).resolve().parents[1] / 'app/src/test/resources/williams_half_margin_contract.json'
CASES = json.loads(FIXTURES.read_text())['cases']


def evaluate(case):
    def observe(data):
        return margin_observation(data['account'], data['configuration'], data['orders'], data['symbols'])
    return assess_half_margin(observe(case), observe(case.get('second', case)), case['proposal'],
                              case['liabilities'], elapsed_ms=case.get('elapsed_ms', 0))


@pytest.mark.parametrize('case', CASES, ids=lambda case:case['id'])
def test_shared_acceptance_contract(case):
    if case['expected'].startswith('UNKNOWN:'):
        with pytest.raises(HalfMarginUnknown, match='FUTURES_HALF_MARGIN_' + case['expected']):
            evaluate(case)
    else:
        result = evaluate(case)
        assert result['allowed'] is (case['expected'] == 'PASS')
        assert result['blocker'] == ('' if case['expected'] == 'PASS' else 'FUTURES_HALF_MARGIN_EXCEEDED')


def test_explicit_arithmetic_and_partial_not_double_counted():
    by_id = {case['id']:case for case in CASES}
    over = evaluate(by_id['440_plus_40_pending_plus_30_rejected'])
    assert Decimal(over['projected_usdt']) == Decimal('510.595735')
    partial = evaluate(by_id['partial_parent_bound_to_child_once'])
    assert Decimal(partial['projected_usdt']) == Decimal('490.425525')
    assert Decimal(partial['residual_usdt']) == 0  # represented in root open-order bucket
    assert Decimal(evaluate(by_id['exact_50_percent_including_costs'])['projected_usdt']) == 500


def test_partial_fill_change_and_duplicate_parent_claim_fail_closed():
    case = copy.deepcopy(next(x for x in CASES if x['id']=='partial_parent_bound_to_child_once'))
    case['liabilities'][0]['executed'] = '1.5'
    with pytest.raises(HalfMarginUnknown, match='LIABILITY_FILL_CHANGED'):
        evaluate(case)
    case['liabilities'][0]['executed'] = '1.6'
    duplicate = copy.deepcopy(case['liabilities'][0])
    duplicate['client_id'] = 'another-parent'
    case['liabilities'].append(duplicate)
    with pytest.raises(HalfMarginUnknown, match='LIABILITY_DUPLICATE_CLAIM'):
        evaluate(case)
