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


@pytest.mark.parametrize('fault', ['', 'missing_config', 'duplicate_config', 'missing_mark',
                                  'duplicate_mark', 'object_config', 'object_mark', 'malformed_mark'])
def test_bulk_symbol_reads_keep_external_liabilities_and_reject_ambiguous_identity(monkeypatch, fault):
    from binance_futures_client import BinanceFuturesClient
    from binance_client import BinanceAPIError
    client = BinanceFuturesClient('test-key', 'test-secret', testnet=True)
    required = {'BTCUSDT', 'SUIUSDT', 'SANTOSUSDT'}  # last one is outside the bot universe
    settings = [{'symbol':s, 'leverage':'2', 'marginType':'ISOLATED'} for s in required]
    marks = [{'symbol':s, 'markPrice':'100'} for s in required]
    if fault=='missing_config':settings.pop()
    elif fault=='duplicate_config':settings.append(dict(settings[0]))
    elif fault=='missing_mark':marks.pop()
    elif fault=='duplicate_mark':marks.append(dict(marks[0]))
    elif fault=='object_config':settings={}
    elif fault=='object_mark':marks={}
    elif fault=='malformed_mark':marks.append(None)
    calls=[]
    def read(method, path, params=None, signed=False):
        calls.append((method,path,params,signed))
        return settings if path.endswith('symbolConfig') else marks
    monkeypatch.setattr(client,'_request',read)
    try:
        if fault:
            with pytest.raises(BinanceAPIError):client.half_margin_symbol_configurations(required)
        else:
            result=client.half_margin_symbol_configurations(required)
            assert set(result)==required
            case=copy.deepcopy(CASES[0])
            # This is an unowned external order, not a newly allowed bot pair.
            order={'symbol':'SANTOSUSDT','positionSide':'BOTH','side':'BUY','orderId':123,
                   'clientOrderId':'external','reduceOnly':False,'status':'NEW',
                   'origQty':'120','executedQty':'0','price':'100'}
            observed=margin_observation(case['account'],case['configuration'],[order],result)
            assert observed['used'] >= Decimal('6009')
            assert observed['orders']['external']['symbol']=='SANTOSUSDT'
        assert len(calls)==(1 if fault=='object_config' else 2)
        assert calls[0]==('GET','/fapi/v1/symbolConfig',None,True)
        if len(calls)>1:assert calls[1]==('GET','/fapi/v1/premiumIndex',None,False)
    finally:client.session.close()
