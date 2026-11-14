"""Nonfinite configuration cannot enlarge a scripted entry margin allowance."""
from types import SimpleNamespace

import pytest

from futures_williams_runtime import FuturesWilliamsRuntime


def scripted_runtime():
    runtime=object.__new__(FuturesWilliamsRuntime)
    runtime.config=SimpleNamespace(risk_per_trade_pct=.0025,leverage_for=lambda symbol:2)
    runtime._equity=lambda:1000.0
    runtime._available_margin=lambda:1000.0
    runtime._min_notional=lambda symbol:0.0
    runtime._normalize_qty=lambda symbol,quantity:quantity
    return runtime


@pytest.mark.parametrize('value',['NaN','nan','Infinity','-Infinity','not-a-number',''])
def test_unknown_margin_configuration_cannot_produce_an_order_quantity(monkeypatch,value):
    runtime=scripted_runtime()
    monkeypatch.setenv('FUTURES_MAX_MARGIN_FRACTION',value)
    monkeypatch.setenv('FEE_BUFFER_PER_SIDE_PCT','0.001')
    monkeypatch.setenv('RISK_SLIPPAGE_BUFFER_PCT','0.0015')
    quantities=[]
    runtime._normalize_qty=lambda symbol,quantity:quantities.append(quantity) or quantity
    with pytest.raises(RuntimeError,match='^FUTURES_MARGIN_FRACTION_INVALID$'):
        runtime._size_from_risk('BTCUSDT',100.0,99.99,.0025)
    assert quantities==[]


@pytest.mark.parametrize('stop',[99.99,100.01])
def test_valid_fraction_preserves_margin_ceiling_and_both_stop_directions(monkeypatch,stop):
    runtime=scripted_runtime()
    monkeypatch.setenv('FUTURES_MAX_MARGIN_FRACTION','0.25')
    monkeypatch.setenv('FEE_BUFFER_PER_SIDE_PCT','0.001')
    monkeypatch.setenv('RISK_SLIPPAGE_BUFFER_PCT','0.0015')
    quantity,risk,notional=runtime._size_from_risk('BTCUSDT',100.0,stop,.0025)
    assert quantity==5.0
    assert notional==500.0
    assert notional/2==250.0
    assert risk==2.5
