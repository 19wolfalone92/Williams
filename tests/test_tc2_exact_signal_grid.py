"""Cross-language TC2 one-tick and no-ghost-tick regression.

Book source: Trading Chaos 2 (Russian supplied), PDF122, 137, 145–151.
Actual open exchange position STOPs still require a separate signed Demo test.
"""
import math

import pandas as pd
import pytest

import williams_signals as rules
from campaign_model import SignalType


@pytest.mark.parametrize("side,entry_base,stop_base,tick,entry,stop", [
    ("LONG", 79524.8, 79523.2, .1, 79524.9, 79523.1),
    ("SHORT", 79523.2, 79524.8, .1, 79523.1, 79524.9),
    ("LONG", 2297.00, 2294.31, .01, 2297.01, 2294.30),
    ("SHORT", 2294.31, 2297.00, .01, 2294.30, 2297.01),
])
def test_tc2_price_bar_exact_one_tick_parity_with_android(side,entry_base,stop_base,tick,entry,stop):
    assert rules._tc2_exact_price_bar_levels(side,entry_base,stop_base,tick)==(entry,stop)


@pytest.mark.parametrize("side,entry,stop,tick", [
    ("LONG", 79524.85, 79523.2, .1),
    ("SHORT", 79523.2, 79524.85, .1),
    ("LONG", 79524.8, 79523.25, .1),
    ("SHORT", 79523.25, 79524.8, .1),
    ("LONG", 79524.8, 79523.2, 0.0),
    ("SHORT", 79523.2, 79524.8, float("nan")),
    ("LONG", 100.0, 105.0, .1),
    ("SHORT", 105.0, 100.0, .1),
])
def test_tc2_bad_source_or_stop_side_cannot_create_conditional_entry(side,entry,stop,tick):
    assert rules._tc2_exact_price_bar_levels(side,entry,stop,tick) is None


@pytest.mark.parametrize("tick", [
    None, "", 0.0, -0.1, float("nan"), float("inf"), -float("inf"), "not-a-number"
])
def test_unknown_binance_tick_never_invents_positive_buffer_or_stop(tick):
    # Even a cached signal must never create a fictitious tick when Binance
    # PRICE_FILTER has not been obtained or is corrupt.
    df=pd.DataFrame({
        "open":[100.0,101.0,101.0,102.0],
        "high":[101.0,102.0,102.0,103.0],
        "low":[99.0,100.0,100.0,101.0],
        "close":[100.0,101.0,101.0,102.0],
        "fractal_up":[False,False,True,False],
        "fractal_down":[False,False,False,True],
        "teeth_current":[100.0]*4,
    })
    assert rules._verified_exchange_tick(tick) is None
    assert rules.extract_long_signal_specs("BTCUSDT",df,timeframe="1h",tick_size=tick)==[]
    assert rules.extract_short_signal_specs("BTCUSDT",df,timeframe="1h",tick_size=tick)==[]
    assert rules.extract_ntd_first_fractal_specs("BTCUSDT",df,side="LONG",timeframe="1h",tick_size=tick)==[]


@pytest.mark.parametrize("side,signal_type", [
    ("LONG",SignalType.SUPER_AO),
    ("SHORT",SignalType.SUPER_AO),
])
def test_tc2_wm2_uses_source_exact_price_bar_for_actual_signal(monkeypatch,side,signal_type):
    signal=(2, 2297.0, 2294.31) if side=="LONG" else (2,2294.31,2297.0)
    monkeypatch.setattr(rules,"_latest_super_ao",lambda *_a,**_k: signal)
    monkeypatch.setattr(rules,"_latest_reversal",lambda *_a,**_k: None)
    monkeypatch.setattr(rules,"_latest_three_price_bar_signal",lambda *_a,**_k: None)
    monkeypatch.setattr(rules,"_latest_confirmed_fractal",lambda *_a,**_k: None)
    monkeypatch.setattr(rules,"_trigger_still_pending",lambda *_a,**_k: True)
    frame=pd.DataFrame({
        "open":[2295.,2295.,2295.,2295.],
        "high":[2297.,2297.,2297.,2297.],
        "low":[2294.31,2294.31,2294.31,2294.31],
        "close":[2295.,2295.,2295.,2295.],
        "teeth_current":[2295.]*4,
    })
    call=rules.extract_long_signal_specs if side=="LONG" else rules.extract_short_signal_specs
    got=[v for v in call("ETHUSDT",frame,timeframe="1h",tick_size=.01)
         if v.signal_type==signal_type]
    assert len(got)==1
    assert got[0].trigger_price==(2297.01 if side=="LONG" else 2294.30)
    assert got[0].protective_reference==(2294.30 if side=="LONG" else 2297.01)
