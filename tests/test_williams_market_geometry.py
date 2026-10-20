"""Regressions against TC2 ch9 and NTD ch3/4/10, mirrored LONG/SHORT."""
from types import SimpleNamespace

import pandas as pd
import pytest

from williams_market_geometry import angulation_evidence, alligator_phases, ntd_initial_context
from williams_signals import _latest_confirmed_fractal
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
    # The extra entry tick must not push an inside-mouth extreme outside Teeth.
    inside=145-.05 if short else 105+.05
    assert _latest_confirmed_fractal(df,side="SHORT" if short else "LONG",current_teeth=inside,
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
