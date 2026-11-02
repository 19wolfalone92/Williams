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
        "open":[100.0,101.0,101.0,102.0,102.0],
        "high":[101.0,102.0,102.0,103.0,103.0],
        "low":[99.0,100.0,100.0,101.0,101.0],
        "close":[100.0,101.0,101.0,102.0,102.0],
        "fractal_up":[False,False,True,False,False],
        "fractal_down":[False,False,False,True,False],
        "teeth_current":[100.0]*5,
    })
    assert rules._verified_exchange_tick(tick) is None
    assert rules.extract_long_signal_specs("BTCUSDT",df,timeframe="1h",tick_size=tick)==[]
    assert rules.extract_short_signal_specs("BTCUSDT",df,timeframe="1h",tick_size=tick)==[]
    assert rules.extract_ntd_fractal_signal_specs("BTCUSDT",df,side="LONG",timeframe="1h",tick_size=tick)==[]


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



@pytest.mark.parametrize("side",["LONG","SHORT"])
def test_tc2_wm3_actual_source_fractal_and_plateau_stop_are_decimal_exact(side):
    highs=[101.,102.,105.,103.,102.,103.,104.]
    lows =[100., 99., 95., 97., 98., 99.,100.]
    open_=[100.5,100.5,100.,100.,100.,100.5,101.]
    close=[100.5,100.5,101.,100.,100.,101.,101.]
    if side=="SHORT":
        old_h,old_l=highs,lows
        highs=[220-v for v in old_l]
        lows=[220-v for v in old_h]
        open_=[220-v for v in open_]
        close=[220-v for v in close]
    ind=pd.DataFrame({
        "open":open_,"high":highs,"low":lows,"close":close,
        "fractal_up":[False,False,side=="LONG",False,False,False,False],
        "fractal_down":[False,False,side=="SHORT",False,False,False,False],
        "confirmed_up_level":[math.nan]*4+[105.0]+[math.nan]*2,
        "confirmed_down_level":[math.nan]*4+[115.0]+[math.nan]*2,
        "teeth_current":[104. if side=="LONG" else 116.]*7,
    })
    if side=="LONG":
        got=rules.extract_long_signal_specs("BTCUSDT",ind,timeframe="1h",tick_size=.1)
        expected=(105.1,94.9)
    else:
        got=rules.extract_short_signal_specs("BTCUSDT",ind,timeframe="1h",tick_size=.1)
        expected=(114.9,125.1)
    fractals=[x for x in got if x.signal_type==SignalType.FRACTAL]
    assert len(fractals)==1
    assert (fractals[0].trigger_price,fractals[0].protective_reference)==expected
    # An off-grid pivot is not a valid Binance PRICE_FILTER source. Neither
    # side may silently project it onto a neighboring tick.
    if side=="LONG":
        ind.loc[2,"high"]=105.005
    else:
        ind.loc[2,"low"]=114.995
    assert rules._latest_confirmed_fractal(ind,side=side,
        current_teeth=104. if side=="LONG" else 116.,
        current_close=101. if side=="LONG" else 119.,
        tick_size=.1) is None
