import pandas as pd

from campaign_engine import CampaignEngine
from campaign_model import SignalRole, SignalSpec, SignalType, structural_stop_for_long
from williams_intraday_core import WilliamsIntradayCore
from williams_intraday_spec import IntradayPolicy


def _spec(signal_type, t):
    return SignalSpec.new(
        symbol="BTCUSDT", side="BUY", signal_type=signal_type, role=SignalRole.ENTRY,
        timeframe="15m", signal_bar_time_ms=t, trigger_price=101+t/1000,
        protective_reference=99, execution_timeframe="5m", detected_time_ms=t+1,
    )


def test_first_valid_signal_is_chronological_not_family_order(tmp_path):
    from db import Database
    engine=CampaignEngine(Database(str(tmp_path/"x.sqlite3")))
    chosen=engine.choose_initial_signal([
        _spec(SignalType.SUPER_AO,200),
        _spec(SignalType.REVERSAL,300),
    ])
    assert chosen.signal_type==SignalType.SUPER_AO


def test_same_time_prefers_wm1(tmp_path):
    from db import Database
    engine=CampaignEngine(Database(str(tmp_path/"x.sqlite3")))
    t=1000
    chosen=engine.choose_initial_signal([
        _spec(SignalType.FRACTAL,t),
        _spec(SignalType.SUPER_AO,t),
        _spec(SignalType.REVERSAL,t),
    ])
    assert chosen.signal_type==SignalType.REVERSAL


def test_structural_trail_uses_lowest_recent_low_when_it_is_the_tightest_candidate():
    stop,source=structural_stop_for_long(
        signal_type=SignalType.FRACTAL, signal_bar_low=90,
        recent_lows=[88,87,86,85,84], teeth=0, buffer=0.1,
    )
    assert source=="3_5_BAR_STRUCTURE"
    assert abs(stop-83.9)<1e-9


def test_indicator_core_has_profitunity_zone_columns():
    import numpy as np
    from strategy import calculate_indicators, config_from_env
    n=80
    idx=pd.date_range("2026-10-08",periods=n,freq="15min",tz="UTC")
    f=pd.DataFrame({
        "open":np.linspace(100,120,n),
        "high":np.linspace(101,121,n),
        "low":np.linspace(99,119,n),
        "close":np.linspace(100.5,120.5,n),
        "volume":np.linspace(1000,2000,n),
    },index=idx)
    out=calculate_indicators(f,config_from_env())
    assert {"zone_color","zone_streak","zone_red_streak"}.issubset(out.columns)


def test_timeframe_policy_is_final():
    p=IntradayPolicy.from_env({})
    assert p.timeframes.all==("1d","4h","1h","15m","5m")


def test_core_constructs_m15_signal_specs():
    n=80
    idx=pd.date_range("2026-10-08",periods=n,freq="15min",tz="UTC")
    f=pd.DataFrame({"open":[100.0]*n,"high":[101.0]*n,"low":[99.0]*n,"close":[100.0]*n,"volume":[1000.0]*n},index=idx)
    core=WilliamsIntradayCore(IntradayPolicy.from_env({}))
    decision=core.evaluate("BTCUSDT",f,tick_size=.1)
    assert decision.decision_tf=="15m"
    assert decision.execution_tf=="5m"
