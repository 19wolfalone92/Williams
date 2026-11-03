"""NTD source-fidelity: original AO histogram colours and AC second/third bar.

Source: New Trading Dimensions supplied PDF pp.57–66 (AO figures 5-4,
5-8, 5-12, 5-15, 5-19, 5-23) and pp.74–82 (AC figures 6-3,
6-5, 6-8, 6-11, 6-14, 6-18). Research-only: no Binance order authority.
"""
import math

import pandas as pd
import pytest

from campaign_model import SignalType
from williams_signals import extract_ntd_ao_signal_specs, extract_ntd_ac_signal_specs

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
