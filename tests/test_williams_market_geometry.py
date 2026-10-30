"""Regressions against TC2 ch9 and NTD ch3/4/10, mirrored LONG/SHORT."""
from types import SimpleNamespace

import pandas as pd
import pytest

from williams_market_geometry import angulation_evidence, alligator_phases, ntd_initial_context, first_wise_man_ao_blocker
from williams_signals import _latest_confirmed_fractal, _latest_super_ao
from williams_wave_hierarchy import WaveBar

H=3_600_000


def bars(highs):
    return [WaveBar((i+1)*H,h-1,h,h-3,h-1,10) for i,h in enumerate(highs)]


def mirror(series):
    return [WaveBar(b.t,250-b.o,250-b.l,250-b.h,250-b.c,b.v) for b in series]


@pytest.mark.parametrize("short",[False,True])
def test_angulation_starts_at_mouth_and_uses_the_edge_facing_alligator(short):
    series=bars([101,98,94,89,84])
    jaw=[100,99,98,97,96];teeth=[100,99.2,98.4,97.6,96.8];lips=[100,99.5,99,98.5,98]
    if short:
        series=mirror(series);jaw,teeth,lips=([250-v for v in x] for x in (jaw,teeth,lips))
    p=angulation_evidence("SHORT" if short else "LONG",series,jaw,teeth,lips)
    assert p["valid"] and p["anchor_index"]==0 and p["examined_bars"]==5
    assert p["price_slope"]>p["jaw_slope"] if short else p["price_slope"]<p["jaw_slope"]


@pytest.mark.parametrize("short",[False,True])
def test_distance_growth_from_retreating_alligator_does_not_prove_price_angulation(short):
    series=bars([101,102,103,104,105]);jaw=[100,105,110,115,120]
    if short:series=mirror(series);jaw=[250-v for v in jaw]
    proof=angulation_evidence("SHORT" if short else "LONG",series,jaw,jaw,jaw)
    assert not proof["valid"]
    assert proof["reason"]=="PRICE_PATH_NOT_STEEPER"


def test_missing_crossing_and_nan_path_are_explicitly_unverified():
    series=bars([91,88,84,79,74]);line=[100]*5
    assert angulation_evidence("LONG",series,line,line,line)["reason"]=="MOUTH_CROSSING_UNOBSERVED"
    line=[90,float("nan"),90,90,90]
    assert not angulation_evidence("LONG",series,line,line,line)["valid"]



@pytest.mark.parametrize("short",[False,True])
def test_tc2_fig_9_5_parallel_after_departure_has_no_opening_angle(short):
    # Conceptual hand labels from TC2 ch9; not chart-pixel digitizations.
    series=bars([101,96,93,90,87])
    jaw=[100,99,96,93,90]
    if short:
        series=mirror(series);jaw=[250-v for v in jaw]
    p=angulation_evidence("SHORT" if short else "LONG",series,jaw,jaw,jaw)
    assert not p["valid"] and p["reason"]=="PARALLEL_AFTER_MOUTH_EXIT"


@pytest.mark.parametrize("short",[False,True])
def test_tc2_fig_9_11_mouth_touch_invalid_even_if_prior_path_diverges(short):
    series=bars([101,98,94,89,84])
    line=[100,99,98,97,96]
    teeth=[100,99.2,98.4,97.6,96.8]
    lips=[100,99.5,99,98.5,98]
    last=series[-1]
    series[-1]=WaveBar(last.t,96,97,95,96,10)
    if short:
        series=mirror(series);line,teeth,lips=([250-v for v in x] for x in (line,teeth,lips))
    p=angulation_evidence("SHORT" if short else "LONG",series,line,teeth,lips)
    assert not p["valid"] and p["reason"]=="SIGNAL_BAR_TOUCHES_MOUTH"


@pytest.mark.parametrize("short",[False,True])
def test_tc2_angle_requires_continuous_history_and_reusable_evidence(short):
    series=bars([101,98,94,89,84])
    j=[100,99,98,97,96];t=[100,99.2,98.4,97.6,96.8];l=[100,99.5,99,98.5,98]
    if short:
        series=mirror(series);j,t,l=([250-v for v in x] for x in (j,t,l))
    side="SHORT" if short else "LONG"
    proof=angulation_evidence(side,series,j,t,l,expected_step_ms=H)
    assert proof["valid"] and proof["first_outside_index"]==1
    assert proof["outside_gap_growth_pct"]>0
    assert not angulation_evidence(side,series,j,t,l,expected_step_ms=H//4)["valid"]
    missing=[WaveBar(b.t+(H if i>=2 else 0),b.o,b.h,b.l,b.c,b.v) for i,b in enumerate(series)]
    assert angulation_evidence(side,missing,j,t,l,expected_step_ms=H)["reason"]=="CANDLE_PATH_GAP"

@pytest.mark.parametrize("short",[False,True])
def test_ordered_narrowing_is_satiation_and_does_not_license_new_ntd_cycle(short):
    series=bars([113,114,115,116,117])
    jaw=[100,102,104,106,108];teeth=[105,106,107,108,109];lips=[110,110.5,111,111.5,112]
    if short:series=mirror(series);jaw,teeth,lips=([250-v for v in x] for x in (jaw,teeth,lips))
    phases=alligator_phases(series,jaw,teeth,lips)
    assert phases[-1]=="SATIATED" and "SLEEPING" not in phases
    assert not ntd_initial_context(series,jaw,teeth,lips,4,.1)["eligible"]


def test_sleep_awakening_and_eating_have_separate_causal_states():
    series=bars([101,101,101,107,112])
    jaw=[100,100,100,101,102];teeth=[100,100,100,102,104];lips=[100,100,100,103,106]
    assert alligator_phases(series,jaw,teeth,lips)==["UNKNOWN","SLEEPING","SLEEPING","AWAKENING","EATING"]


@pytest.mark.parametrize("short",[False,True])
def test_fractal_inside_at_formation_can_become_valid_without_backdating(short):
    series=bars([101,102,105,103,102,103])
    teeth=[106,106,106,106,106,104]
    if short:series=mirror(series);teeth=[250-v for v in teeth]
    df=pd.DataFrame([dict(open_time_ms=b.t,open=b.o,high=b.h,low=b.l,close=b.c,volume=b.v,
        teeth_current=teeth[i],fractal_up=i==2 and not short,fractal_down=i==2 and short,
        confirmed_up_level=(105 if i==4 and not short else float("nan")),
        confirmed_down_level=(145 if i==4 and short else float("nan"))) for i,b in enumerate(series)])
    f=_latest_confirmed_fractal(df,side="SHORT" if short else "LONG",current_teeth=teeth[-1],
        current_close=series[-1].c,tick_size=.1)
    assert f is not None and f[:2]==(4,2)
    # NTD/TC2: the ACTUAL trigger (pivot +/- one tick) must break beyond Teeth.
    # A level inside Teeth remains legal if the one-tick STOP activation is outside.
    inside=145-.05 if short else 105+.05
    assert _latest_confirmed_fractal(df,side="SHORT" if short else "LONG",current_teeth=inside,
        current_close=series[-1].c,tick_size=.1) is not None
    at_stop=144.9 if short else 105.1
    assert _latest_confirmed_fractal(df,side="SHORT" if short else "LONG",current_teeth=at_stop,
        current_close=series[-1].c,tick_size=.1) is None
    assert _latest_confirmed_fractal(df,side="SHORT" if short else "LONG",current_teeth=float("nan"),
        current_close=series[-1].c,tick_size=.1) is None


def test_unified_scanning_cannot_import_another_books_exit(monkeypatch):
    from futures_williams_runtime import FuturesWilliamsRuntime
    from williams_edition_rules import TC2
    import futures_williams_runtime as runtime_module
    monkeypatch.setenv("WILLIAMS_SIGNAL_PROFILE","WILLIAMS_UNIFIED")
    series=bars([100+i*.1 for i in range(60)])
    df=pd.DataFrame([dict(open_time_ms=b.t,open=b.o,high=b.h,low=b.l,close=b.c,volume=b.v) for b in series])
    monkeypatch.setattr(runtime_module.time,"time",lambda:61*H/1000)
    monkeypatch.setattr(runtime_module,"extract_long_signal_specs",lambda *a,**k:[])
    def forbidden(*a,**k):
        raise AssertionError("A TC2 campaign must not query another book's exits")
    monkeypatch.setattr(runtime_module,"extract_profile_signals",forbidden)
    runtime=object.__new__(FuturesWilliamsRuntime);runtime.client=SimpleNamespace(time_offset_ms=0)
    campaign=SimpleNamespace(symbol="BTCUSDT",side="SELL",execution_timeframe="1h",
        tags={"strategy_profile":TC2,"position_open_observed_at_ms":50*H})
    assert runtime._triggered_opposite_from_history(campaign,df,.1,105) is None


def test_chart_reading_preserves_unknown_wave_and_correlated_oscillator_identity():
    from williams_market_reading import market_reading
    from strategy import calculate_indicators,canonical_indicator_config_from_env
    series=bars([100+i*i*.01 for i in range(80)])
    df=pd.DataFrame([dict(open_time_ms=b.t,open=b.o,high=b.h,low=b.l,close=b.c,volume=b.v) for b in series])
    ind=calculate_indicators(df,canonical_indicator_config_from_env())
    p=market_reading(series,{"frames":[]})
    assert p["wave_phase"]=="UNRESOLVED" and not p["can_place_orders"] and not p["can_close"]
    assert p["ao"]==pytest.approx(float(ind.ao.iloc[-1]))
    assert p["ac"]==pytest.approx(float(ind.ac.iloc[-1]))
    assert p["alligator"]["jaw"]==pytest.approx(float(ind.jaw_current.iloc[-1]))
    assert p["profitunity_macd"]["oscillator"]==p["ao"]
    assert p["profitunity_macd"]["histogram"]==p["ac"]
    assert p["profitunity_macd"]["shares_ao_and_ac"]
    assert market_reading(series[:40]+series[41:],{})["state"]=="HISTORY_UNVERIFIED"


@pytest.mark.parametrize("short",[False,True])
def test_course_momentum_uses_signal_line_even_when_ao_colour_disagrees(short):
    from math import sin
    from williams_edition_rules import course_rhythm
    series=bars([101+i*.2+5*sin(i*.4) for i in range(47)])
    if short:series=mirror(series)
    proof=course_rhythm(series)
    assert proof["momentum"]==("UP" if short else "DOWN")
    assert proof["momentum_basis"]=="OSCILLATOR_VS_SIGNAL_SMA5"


@pytest.mark.parametrize("short",[False,True])
def test_ntd_first_fractal_consumed_when_tick_trigger_not_pivot_clears_teeth(short):
    # NTD ch4 pp.42,52-53: first executable breakout is the STOP price, not the pivot.
    # Sleeping phase precedes the known fractal; later H1 bar touches only +1 tick.
    highs=[100.0,100.0,101.0,100.0,105.0,103.0,102.0,105.1,103.0]
    series=bars(highs)
    jaw=[90.0]*len(series);teeth=[90.0]*len(series);lips=[90.0]*len(series)
    teeth[7]=105.05
    if short:
        series=mirror(series)
        jaw,teeth,lips=([250-v for v in x] for x in (jaw,teeth,lips))
    proof=ntd_initial_context(series,jaw,teeth,lips,4,.1)
    assert not proof["eligible"]
    assert proof["reason"]=="NTD_FIRST_BREAKOUT_ALREADY_OCCURRED"
    assert proof["first_breakout_at_ms"]==series[7].t


@pytest.mark.parametrize("short",[False,True])
def test_python_tc2_wm2_initial_signal_bar_stop_is_distinct_from_trailing(short):
    # TC2 ch12 PDF156 (printed141) distinguishes first-SAO protection
    # from subsequent 3–5-bar trailing. Third AO bar is index 5.
    highs=[110,112,120,115,117,118,119,119]
    lows=[100,99,90,102,101,103,70,65]
    if short:
        highs,lows=([250-x for x in lows],[250-x for x in highs])
    df=pd.DataFrame({
        "high":highs,"low":lows,
        ("ao_red_streak" if short else "ao_green_streak"):[0,0,0,1,2,3,4,5],
    })
    wm2=_latest_super_ao(df,side="SHORT" if short else "LONG",max_age_bars=8)
    assert wm2 is not None and wm2[0]==5
    assert wm2[1]==(lows[5] if short else highs[5])
    assert wm2[2]==(highs[5] if short else lows[5])
    assert wm2[2]!=(max(highs[1:6]) if short else min(lows[1:6]))
    # A later bar's extreme, even one much worse, cannot move initial STOP.
    assert wm2[2] != (max(highs[6:]) if short else min(lows[6:]))


@pytest.mark.parametrize("side,now,previous,expected",[
    ("LONG",-2.0,-1.5,None),  # red AO, even if below zero
    ("LONG",3.0,3.5,None),    # red AO, even if above zero
    ("SHORT",-1.0,-2.0,None),# green AO, even if below zero
    ("SHORT",4.0,3.0,None),  # green AO, even if above zero
    ("LONG",3.5,3.0,"WM1_AO_NOT_RED"),
    ("SHORT",1.0,1.5,"WM1_AO_NOT_GREEN"),
    ("LONG",4.0,4.0,"WM1_AO_NOT_RED"),
    ("SHORT",4.0,4.0,"WM1_AO_NOT_GREEN"),
    ("LONG",float("nan"),1.0,"WM1_AO_UNVERIFIED"),
    ("SHORT",1.0,float("inf"),"WM1_AO_UNVERIFIED"),
    ("LONG",None,1.0,"WM1_AO_UNVERIFIED"),
])
def test_tc2_first_wise_man_ao_colour_must_oppose_prior_trend(side,now,previous,expected):
    # TC2 PDF125 expects AO old-trend colour; PDF131 explicitly requires countertrend context.
    assert first_wise_man_ao_blocker(side,now,previous)==expected


def test_tc2_first_wise_man_reversal_extractor_rejects_nonopposite_ao(monkeypatch):
    import williams_signals as signals
    monkeypatch.setattr(signals, "_angulation", lambda *args,**kwargs:(.3,True))
    ind=pd.DataFrame({
        "bullish_reversal_bar":[False,False,True,False],
        "bearish_reversal_bar":[False,False,True,False],
        "ao":[0.0,2.0,3.0,3.5],
        "high":[100.,102.,104.,105.],
        "low":[98.,100.,101.,102.],
    })
    assert signals._latest_reversal(ind,side="SHORT") is not None
    assert signals._latest_reversal(ind,side="LONG") is None
    ind.loc[2,"ao"]=1.0
    assert signals._latest_reversal(ind,side="LONG") is not None
    assert signals._latest_reversal(ind,side="SHORT") is None
    ind.loc[2,"ao"]=float("nan")
    assert signals._latest_reversal(ind,side="LONG") is None
    assert signals._latest_reversal(ind,side="SHORT") is None
    assert signals._latest_reversal(ind.drop(columns=["ao"]),side="LONG") is None



def test_short_wm1_signal_uses_same_configured_horizon_as_long_wm1(monkeypatch):
    """A custom closed-H1 reversal lifetime may not silently become 20 bars for SELL."""
    import pandas as pd
    import williams_signals as signals
    from campaign_model import SignalType
    at=1_795_000_000_000
    frame=pd.DataFrame([{"open_time_ms":at,"high":101.0,"low":99.0,
                         "open":100.0,"close":100.0,"teeth_current":102.0}])
    monkeypatch.setattr(signals,"_latest_reversal",lambda *a,**kw:(0,99.0,101.0))
    monkeypatch.setattr(signals,"_angulation",lambda *a,**kw:(1.0,True))
    monkeypatch.setattr(signals,"_trigger_still_pending",lambda *a,**kw:True)
    monkeypatch.setattr(signals,"_latest_super_ao",lambda *a,**kw:None)
    monkeypatch.setattr(signals,"_latest_three_price_bar_signal",lambda *a,**kw:None)
    monkeypatch.setattr(signals,"_latest_confirmed_fractal",lambda *a,**kw:None)
    specs=signals.extract_short_signal_specs(
        "BTCUSDT",frame,timeframe="1h",tick_size=0.1,max_reversal_age_bars=6)
    first=[spec for spec in specs if spec.signal_type==SignalType.REVERSAL]
    assert len(first)==1
    assert first[0].expires_at_ms==signals._expiry(at,"1h",6)
    assert first[0].expires_at_ms!=signals._expiry(at,"1h",20)
