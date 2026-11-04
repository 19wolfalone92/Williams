"""NTD chapter 4 (PDF 43) first real fractal gate, Python / Android.

The first confirmed external fractal does not expire after an arbitrary age.
Its pending STOP and structural protection are one exact Binance price step
beyond the original *closed* OHLC, never floating-point +/- tick. These are
read-only hypotheses; actual Futures Demo signed STOP remains unverified.
"""
import math

import pandas as pd
import pytest

from campaign_model import SignalRole, SignalType
from williams_signals import extract_ntd_fractal_signal_specs

H1 = 3_600_000


def source_frame(side="LONG",count=50):
    assert count>=50
    highs=[100.0]*count
    lows=[98.0]*count
    highs[43]=105.0
    lows[43]=95.0
    closes=[99.0]*count
    if side=="SHORT":
        highs,lows=[300-v for v in lows],[300-v for v in highs]
        closes=[300-v for v in closes]
    return pd.DataFrame({
        "high":highs,"low":lows,"close":closes,
        "open":closes,
        "open_time_ms":[(i+1)*H1 for i in range(count)],
        "fractal_up":[i==43 and side=="LONG" for i in range(count)],
        "fractal_down":[i==43 and side=="SHORT" for i in range(count)],
        "teeth_current":[99.0 if side=="LONG" else 201.0]*count,
    })


def scan(df,side,tick=.1):
    return extract_ntd_fractal_signal_specs(
        "LTCUSDT",df,side=side,timeframe="1h",tick_size=tick)


@pytest.mark.parametrize("side,expected",[
    ("LONG",(105.1,94.9,"BUY")),("SHORT",(194.9,205.1,"SELL"))
])
def test_ntd_first_confirmed_fractal_parity_matches_android_one_price_tick(side,expected):
    result=scan(source_frame(side),side)
    assert len(result)==1
    s=result[0]
    assert s.signal_type==SignalType.FRACTAL
    assert s.role==SignalRole.ENTRY
    assert s.side==expected[2]
    assert (s.trigger_price,s.protective_reference)==expected[:2]
    assert s.source_candle_index==43
    assert s.signal_bar_time_ms==46*H1
    assert s.expires_at_ms==0


@pytest.mark.parametrize("side",["LONG","SHORT"])
def test_ntd_original_fractal_survives_one_hundred_genuine_untriggered_h1_bars(side):
    observed=scan(source_frame(side,150),side)
    assert len(observed)==1
    assert observed[0].source_candle_index==43
    assert observed[0].expires_at_ms==0


@pytest.mark.parametrize("side",["LONG","SHORT"])
def test_ntd_fractal_off_grid_bar_or_structural_extreme_cannot_mint_stop(side):
    frame=source_frame(side)
    broken=frame.copy()
    if side=="LONG":
        broken.loc[43,"high"]=105.05
    else:
        broken.loc[43,"low"]=194.95
    assert scan(broken,side)==[]
    broken=frame.copy()
    if side=="LONG":
        broken.loc[43,"low"]=95.05
    else:
        broken.loc[43,"high"]=204.95
    assert scan(broken,side)==[]


@pytest.mark.parametrize("side",["LONG","SHORT"])
def test_ntd_fractal_breakout_after_confirmation_disarms_old_order(side):
    frame=source_frame(side)
    if side=="LONG":
        frame.loc[49,"high"]=106.0
    else:
        frame.loc[49,"low"]=194.0
    assert scan(frame,side)==[]


@pytest.mark.parametrize("side",["LONG","SHORT"])
@pytest.mark.parametrize("tick",[0.0,-0.1,float("nan"),float("inf")])
def test_ntd_no_exchange_price_filter_never_invents_fractal_entry(side,tick):
    assert scan(source_frame(side),side,tick)==[]



@pytest.mark.parametrize("side,entry,stop",[
    ("LONG",105.1,94.9),("SHORT",194.9,205.1),
])
def test_ntd_profile_cannot_add_second_tick_to_already_protected_fractal(side,entry,stop):
    from williams_profiles import extract_profile_signals
    ind=source_frame(side,50)
    ind["volume"]=100.0
    ind["jaw_current"]=99.0 if side=="LONG" else 201.0
    ind["lips_current"]=99.0 if side=="LONG" else 201.0
    ind["ao"]=0.0
    ind["ac"]=0.0
    ind["long_fractal_outside"]=side=="LONG"
    ind["short_fractal_outside"]=side=="SHORT"
    result=extract_profile_signals("LTCUSDT",ind,profile="NTD_FIVE_DIMENSIONS",
        side=side,timeframe="1h",tick_size=.1,exit_mode=True)
    fractals=[x for x in result if x.signal_type==SignalType.FRACTAL]
    assert len(fractals)==1
    assert fractals[0].trigger_price==entry
    assert fractals[0].protective_reference==stop
    assert fractals[0].strategy_profile=="NTD_FIVE_DIMENSIONS"
    assert fractals[0].signal_id.startswith("NTD_FIVE_DIMENSIONS:")
