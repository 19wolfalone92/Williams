"""Quality diagnostics must never authorize financial execution."""
from dataclasses import replace

import pandas as pd
import pytest

from campaign_model import SignalRole, SignalSpec, SignalType
from williams_paper_replay import PaperReplayConfig, run_williams_paper_replay
from williams_trade_readiness_audit import audit_williams_h1_trade_quality


def frame(size=12):
    values=[(100,100.4,99.6,100) for _ in range(size)]
    values[-2]=(100,102,99.6,101.8)
    values[-1]=(101.8,103,101,102.7)
    return pd.DataFrame(values,columns=["open","high","low","close"],
        index=pd.date_range("2026-01-01",periods=size,freq="h",tz="UTC"))


def signal(at):
    return SignalSpec.new(symbol="BTCUSDT",side="BUY",signal_type=SignalType.FRACTAL,
        role=SignalRole.ENTRY,timeframe="1h",
        signal_bar_time_ms=at,trigger_price=101,protective_reference=98)


def test_fixed_signals_are_replayed_twice_with_separate_costs_and_no_execution_authority():
    data=frame()
    signals={9:[signal(int(data.index[9].timestamp()*1000))]}
    cfg=PaperReplayConfig()
    baseline=run_williams_paper_replay(data,signals,cfg)
    p=audit_williams_h1_trade_quality(data,signals,cfg,baseline=baseline)
    assert p["holdout_trades"]==1
    assert p["status"]=="INSUFFICIENT_HOLDOUT_TRADES"
    assert p["baseline_all_history"]["trades"]==1
    assert p["stressed_holdout"]["trades"]==1
    assert p["stressed_holdout"]["net_pnl_usdt"] < p["baseline_holdout"]["net_pnl_usdt"]
    assert p["execution_authority"] is False
    assert p["profits_certified"] is False


def test_zero_signals_report_undefined_win_rate_instead_of_unearned_success():
    p=audit_williams_h1_trade_quality(frame(),{},PaperReplayConfig())
    assert p["holdout_trades"]==0
    assert p["baseline_holdout"]["win_rate_pct"] is None
    assert p["positive_holdout_net_pnl_both_cost_models"] is False


def test_invalid_quality_parameters_and_bad_history_fail_closed():
    with pytest.raises(ValueError,match="audit thresholds"):
        audit_williams_h1_trade_quality(frame(),{},PaperReplayConfig(),holdout_fraction=.95)
    with pytest.raises(ValueError,match="fee/slippage"):
        audit_williams_h1_trade_quality(frame(),{},
            PaperReplayConfig(fee_rate_per_side=.09),cost_multiplier=2)
    broken=frame().drop(frame().index[2])
    with pytest.raises(ValueError,match="continuous ordered"):
        audit_williams_h1_trade_quality(broken,{},PaperReplayConfig())
