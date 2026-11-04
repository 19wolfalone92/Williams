"""NTD source-fidelity: original AO histogram colours and AC second/third bar.

Source: New Trading Dimensions supplied PDF pp.57–66 (AO figures 5-4,
5-8, 5-12, 5-15, 5-19, 5-23) and pp.74–82 (AC figures 6-3,
6-5, 6-8, 6-11, 6-14, 6-18). Research-only: no Binance order authority.
"""
import math

import pandas as pd
import pytest

from campaign_model import SignalType
from williams_signals import (extract_ntd_ao_signal_specs, extract_ntd_ac_signal_specs,
    extract_ntd_zone_add_on_specs)

H1=3_600_000


def frame(oscillator, side, *, signal="ao", flags="correct"):
    values=[float(v) for v in oscillator]
    if side=="SHORT":
        values=[-v for v in values]
    rows=[]
    for i,value in enumerate(values):
        previous=values[i-1] if i else value
        row={
            signal:value,
            "high":101.0,
            "low":99.0,
            "open":100.0,
            "close":100.0,
            "open_time_ms":(i+1)*H1
        }
        if signal=="ao" and flags!="missing":
            row.update({
                "ao_green":value<previous if flags=="inverted" else value>previous,
                "ao_red":value>previous if flags=="inverted" else value<previous,
            })
        rows.append(row)
    return pd.DataFrame(rows)


def get_ao(values,side,flags="correct"):
    return extract_ntd_ao_signal_specs("BTCUSDT",frame(values,side,flags=flags),
        side=side,timeframe="1h",tick_size=.1,include_pending_ao=False)


@pytest.mark.parametrize("side",["LONG","SHORT"])
@pytest.mark.parametrize("flags",["correct","inverted","missing"])
def test_ntd_ao_saucer_respects_real_numerical_colour_not_cached_flags(side,flags):
    # Figure 5-4/5-15: at least one colour-changing trough with a new AO
    # confirming the reversal; cached green/red flags are not source truth.
    # frame() mirrors AO sign for SHORT so same source magnitudes suffice.
    values=[0.7,0.6,0.4,0.5]
    observed=get_ao(values,side,flags)
    assert len(observed)==1
    assert observed[0].signal_type==SignalType.AO_SAUCER
    assert observed[0].side==("BUY" if side=="LONG" else "SELL")
    assert observed[0].trigger_price==(101.1 if side=="LONG" else 98.9)


@pytest.mark.parametrize("side",["LONG","SHORT"])
def test_ntd_ao_zero_line_requires_strict_sign_change_no_exact_zero(side):
    yes=get_ao([0.2,-0.2,0.1],side)
    # For mirrored SHORT this is +0.2 -> -0.1 (valid crossing).
    assert len(yes)==1 and yes[0].signal_type==SignalType.AO_ZERO_LINE
    assert not any(s.signal_type==SignalType.AO_ZERO_LINE
        for s in get_ao([0.2,0.0,0.1],side))
    assert not any(s.signal_type==SignalType.AO_ZERO_LINE
        for s in get_ao([0.2,-0.1,0.0],side))


@pytest.mark.parametrize("side",["LONG","SHORT"])
def test_ntd_ao_twin_peaks_rejects_forged_current_green_and_zero_between_peaks(side):
    legitimate=[-1.0,-5.0,-1.0,-3.0,-2.0]
    observed=get_ao(legitimate,side,flags="inverted")
    assert len(observed)==1
    assert observed[0].signal_type==SignalType.AO_TWIN_PEAKS
    assert not any(s.signal_type==SignalType.AO_TWIN_PEAKS
        for s in get_ao([-1.0,-5.0,0.0,-3.0,-2.0],side))
    assert not any(s.signal_type==SignalType.AO_TWIN_PEAKS
        for s in get_ao([-1.0,-5.0,-1.0,-3.0,-4.0],side))


@pytest.mark.parametrize("side",["LONG","SHORT"])
def test_ntd_ac_zero_cross_alone_cannot_create_an_entry(side):
    # Two or three directional bars must follow a confirmed colour reversal.
    # A numerical zero crossing with an all-green/all-red run but without a
    # visible opposite-colour base never counts as a new source formation.
    only_cross=frame([-0.5,-0.2,0.1,0.2],side,signal="ac")
    assert extract_ntd_ac_signal_specs("BTCUSDT",only_cross,side=side,
        timeframe="1h",tick_size=.1)==[]


@pytest.mark.parametrize("side",["LONG","SHORT"])
@pytest.mark.parametrize("signal_kind,values",[
    ("WITH",[0.8,0.5,0.6,0.7]),  # red base + two green above zero
    ("AGAINST",[-1.0,-1.2,-1.1,-1.0,-0.9]),  # red base + three green below zero
    ("CROSS2",[-0.4,-0.5,-0.2,0.2]),  # on second positive rise
    ("CROSS3",[-0.5,-0.6,-0.4,-0.2,0.1]),  # on third rise
])
def test_ntd_ac_source_number_of_colours_and_signal_bar_tick(side,signal_kind,values):
    # No standalone zero crossing: require a reversal before 2/3 same-colour
    # bars. For SHORT the signs are mirrored by frame().
    df=frame(values,side,signal="ac")
    observed=extract_ntd_ac_signal_specs(
        "BTCUSDT",df,side=side,timeframe="1h",tick_size=.1)
    assert len(observed)==1, (side,signal_kind,observed)
    assert observed[0].signal_type==SignalType.AC_SIGNAL
    assert observed[0].source_candle_index==len(df)-1
    assert observed[0].trigger_price==(101.1 if side=="LONG" else 98.9)
    # A repeated equal AC value has no colour; do not make an entry.
    altered=df.copy()
    altered.loc[len(df)-1,"ac"]=altered.loc[len(df)-2,"ac"]
    assert extract_ntd_ac_signal_specs(
        "BTCUSDT",altered,side=side,timeframe="1h",tick_size=.1)==[]


def test_shared_original_ao_ac_source_contract_matches_kotlin_and_rejects_stale_flags():
    import json
    from pathlib import Path
    corpus=json.loads((Path(__file__).resolve().parents[1] /
        "app/src/test/resources/williams_ntd_source_colour_contract.json").read_text())
    assert corpus["schema"]=="NTD_AO_AC_SOURCE_COLOUR_CONTRACT_V1"
    assert len(corpus["ao"])>=16 and len(corpus["ac"])>=16
    for case in corpus["ao"]:
        values=[float(v) for v in case["values"]]
        # The actual signed AO values are already mirrored in shared fixture.
        # Labels are deliberately inverted. No raw numeric confirmation means
        # the source status must not be accepted from stale cache flags.
        ind=frame(values,"LONG",signal="ao",flags="inverted")
        observed=extract_ntd_ao_signal_specs("ETHUSDT",ind,
            side=case["side"],timeframe="1h",tick_size=.1,
            include_pending_ao=False)
        actual=observed[0].signal_type.value if observed else None
        assert actual==case["expected"],case
        assert len(observed)<=1
        assert all(v.strategy_profile=="NTD_FIVE_DIMENSIONS" for v in observed)
    for case in corpus["ac"]:
        values=[float(v) for v in case["values"]]
        ind=frame(values,"LONG",signal="ac")
        observed=extract_ntd_ac_signal_specs("ETHUSDT",ind,
            side=case["side"],timeframe="1h",tick_size=.1)
        actual=observed[0].signal_type.value if observed else None
        assert actual==case["expected"],case
        assert len(observed)<=1
        assert all(v.strategy_profile=="NTD_FIVE_DIMENSIONS" for v in observed)



@pytest.mark.parametrize("side",["LONG","SHORT"])
def test_ntd_raw_zone_observer_never_inherits_executable_tc2_identity(side):
    sign=1 if side=="LONG" else -1
    highs=[102.0,103.0,104.0]
    lows=[98.0,99.0,100.0]
    close=[100.0,101.0,102.0]
    if side=="SHORT":
        old_h,old_l=highs,lows
        highs=[300-v for v in old_l]
        lows=[300-v for v in old_h]
        close=[300-v for v in close]
    ind=pd.DataFrame({
        "open_time_ms":[H1,2*H1,3*H1],
        "open":close,"high":highs,"low":lows,"close":close,
        "ao":[0.0,sign*1.0,sign*2.0],
        "ac":[0.0,sign*1.0,sign*2.0],
        "long_fractal_outside":[side=="LONG"]*3,
        "short_fractal_outside":[side=="SHORT"]*3,
        "teeth_current":[99.0 if side=="LONG" else 201.0]*3,
    })
    signals=extract_ntd_zone_add_on_specs(
        "BTCUSDT",ind,side=side,timeframe="1h",tick_size=.1)
    assert len(signals)==1
    assert signals[0].signal_type==SignalType.ZONE_ADD_ON
    assert signals[0].strategy_profile=="NTD_FIVE_DIMENSIONS"
    assert signals[0].execution_semantics=="MARKET_ON_CLOSE"
