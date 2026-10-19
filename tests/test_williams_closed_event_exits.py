"""Closed-event entries and risk-reducing exits have different time gates."""
from types import SimpleNamespace
import pandas as pd
import pytest

from campaign_model import SignalType
from futures_williams_runtime import FuturesWilliamsRuntime
from strategy import calculate_indicators,canonical_indicator_config_from_env
from williams_edition_rules import NTD,COURSE,opposing_close_event_triggered
from williams_profiles import extract_profile_signals

H=3_600_000


def frame(prices,start=1_700_000_000_000):
    return pd.DataFrame(dict(open_time_ms=[start+i*H for i in range(len(prices))],open=prices,close=prices,
        high=[p+.2 for p in prices],low=[p-.2 for p in prices],volume=[1000]*len(prices)))


@pytest.mark.parametrize("short",[False,True])
def test_ntd_opposite_zone_is_not_a_book_exit_from_an_owned_position(monkeypatch,short):
    prices=[100+i for i in range(75)]+[176,175,174,173,172]
    if short:prices=[400-p for p in prices]
    candles=frame(prices)
    now=int(candles.iloc[-1].open_time_ms)+H+1000
    monkeypatch.setattr("futures_williams_runtime.time.time",lambda:now/1000)
    runtime=object.__new__(FuturesWilliamsRuntime)
    runtime.client=SimpleNamespace(time_offset_ms=0)
    campaign=SimpleNamespace(symbol="BTCUSDT",side="SELL" if short else "BUY",execution_timeframe="1h",
        tags={"strategy_profile":NTD,"position_open_observed_at_ms":now-2*H})
    # The actual indicator pipeline, not a hand-created SignalSpec, supplies
    # the red/green event. No opposite external fractal is available here.
    ind=calculate_indicators(candles,canonical_indicator_config_from_env())
    side="LONG" if short else "SHORT"
    assert not any(s.signal_type==SignalType.ZONE_ADD_ON for s in
        extract_profile_signals("BTCUSDT",ind,profile=NTD,side=side,timeframe="1h",tick_size=.1))
    event=runtime._triggered_opposite_from_history(campaign,candles,.1,prices[-1])
    assert event is None  # NTD ch10: Teeth/Lips, zone stop, or external opposite fractal.
    campaign.tags["position_open_observed_at_ms"]=now
    assert runtime._triggered_opposite_from_history(campaign,candles,.1,prices[-1]) is None


@pytest.mark.parametrize("short",[False,True])
def test_course_opposite_hump_can_reduce_risk_when_entry_momentum_filter_is_not_met(short):
    prices=[50]*75+[105,104,100,102,104,105,106,104,102,101,98,100,102,104,108,110,107,106,107]
    if short:prices=[400-p for p in prices]
    ind=calculate_indicators(frame(prices),canonical_indicator_config_from_env())
    kwargs=dict(profile=COURSE,side="SHORT" if short else "LONG",timeframe="1h",tick_size=.1)
    assert not any(s.signal_type==SignalType.COURSE_HUMP for s in extract_profile_signals("BTCUSDT",ind,**kwargs))
    assert any(s.signal_type==SignalType.COURSE_HUMP for s in extract_profile_signals("BTCUSDT",ind,exit_mode=True,**kwargs))


@pytest.mark.parametrize("opened,now,allowed",[(100,1100,True),(1100,1100,False),(100,1099,False),
    (100,6099,True),(100,6100,False),(0,1100,False)])
def test_close_exit_requires_confirmation_after_position_open_and_has_a_bounded_recovery_window(opened,now,allowed):
    assert opposing_close_event_triggered(100,1000,opened,now)==allowed


@pytest.mark.parametrize("short",[False,True])
def test_ntd_dimensions_share_a_bar_but_never_reuse_the_original_entry(short):
    from campaign_model import SignalSpec,SignalRole
    from williams_profiles import addition_signal_time_allowed
    from williams_edition_rules import TC2
    signal=SignalSpec.new(symbol="BTCUSDT",side="SELL" if short else "BUY",signal_type=SignalType.AC_SIGNAL,
        role=SignalRole.ADD_ON,timeframe="1h",signal_bar_time_ms=10*H,trigger_price=99 if short else 101,
        protective_reference=105 if short else 95,strategy_profile=NTD)
    campaign=SimpleNamespace(origin_signal_id="distinct-original-fractal",tags={"strategy_profile":NTD,"signal_bar_time_ms":10*H,"last_signal_time_ms":11*H})
    assert addition_signal_time_allowed(signal,campaign)
    campaign.origin_signal_id=signal.signal_id
    assert not addition_signal_time_allowed(signal,campaign)
    campaign.origin_signal_id="distinct-original-fractal";campaign.tags["signal_bar_time_ms"]=11*H
    assert not addition_signal_time_allowed(signal,campaign)
    campaign.tags.update(strategy_profile=TC2,signal_bar_time_ms=9*H,last_signal_time_ms=10*H)
    assert not addition_signal_time_allowed(signal,campaign)
