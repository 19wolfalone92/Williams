import pandas as pd

from campaign_engine import CampaignEngine
from campaign_model import SignalRole, SignalSpec, SignalType
from db import Database
from risk_engine import RiskEngine
from williams_campaign_backtester import WilliamsCampaignBacktester
from williams_execution_economics import ExecutionEconomicsGate
from williams_intraday_core import WilliamsIntradayCore
from williams_intraday_spec import IntradayPolicy, CONSERVATIVE_PROFILE


def _h1_frame(rows):
    idx = pd.date_range("2026-10-08 08:00", periods=len(rows), freq="1h", tz="UTC")
    return pd.DataFrame(rows, index=idx, columns=["open", "high", "low", "close"])


def test_structural_risk_uses_market_stop_not_deposit_stop():
    risk = RiskEngine(500.0, risk_per_trade_pct=0.0025, max_position_fraction=1.0)
    out = risk.analyse_structural_stop("BTCUSDT", 100.0, 98.5)
    assert out.allowed is True
    assert out.risk_quote == 1.25
    assert out.take_profit_price == 0.0


def test_economics_gate_separates_trade_feasibility_from_williams_truth():
    gate = ExecutionEconomicsGate(
        fee_pct=0.001, slippage_pct=0.0015, spread_pct_limit=0.0015
    )
    out = gate.evaluate(
        equity_quote=500.0,
        entry_price=100.0,
        stop_price=98.5,
        risk_pct=0.0025,
        spread_pct=0.0005,
        min_notional=100.0,
    )
    assert out.allowed is False
    assert out.block_reason == "MIN_NOTIONAL_INCOMPATIBLE"


def test_campaign_keeps_h1_decision_and_m15_execution(tmp_path):
    db = Database(str(tmp_path / "test.sqlite3"))
    engine = CampaignEngine(
        db,
        portfolio_risk_limit_pct=0.006,
        campaign_risk_limit_pct=0.006,
        initial_risk_fraction_of_campaign=0.25 / 0.60,
    )
    signal = SignalSpec.new(
        symbol="BTCUSDT",
        side="BUY",
        signal_type=SignalType.REVERSAL,
        role=SignalRole.ENTRY,
        timeframe="1h",
        signal_bar_time_ms=1,
        trigger_price=101,
        protective_reference=98,
        execution_timeframe="15m",
    )
    campaign = engine.create_campaign(signal, initial_risk_pct=0.0025)
    assert campaign.decision_timeframe == "1h"
    assert campaign.execution_timeframe == "15m"
    row = db.get_campaign(campaign.campaign_id)
    assert row["decision_timeframe"] == "1h"
    assert row["execution_timeframe"] == "15m"


def test_core_wm1_does_not_require_bullish_alligator_or_positive_ao(monkeypatch):
    base = _h1_frame([(100, 101, 99, 100)] * 59 + [
        (101, 103, 99, 99),
        (98, 100, 95, 97),
        (97, 99, 94, 96),
        (96, 98, 93, 95),
        (95, 97, 92, 94),
        (91, 94, 90, 93),
    ])
    ind = base.copy()
    ind["jaw_shifted"] = 100.0
    ind["teeth_shifted"] = 100.0
    ind["lips_shifted"] = 100.0
    ind["ao"] = [-5.0] * 59 + [-5.0, -4.0, -3.0, -2.0, -3.0, -4.0]
    ind["ao_green_streak"] = [0] * 65
    ind["bullish_alligator"] = False
    ind["bearish_alligator"] = True
    ind["alligator_awake"] = True
    monkeypatch.setattr("williams_intraday_core.calculate_indicators", lambda *_args, **_kwargs: ind)
    decision = WilliamsIntradayCore(IntradayPolicy.from_env({})).evaluate("BTCUSDT", base, tick_size=0.1)
    assert decision.wm1 is True
    assert decision.first_signal_type == "REVERSAL"
    assert decision.signal_specs[0].execution_timeframe == "15m"


def test_backtester_uses_m5_only_to_refine_an_m15_trigger():
    m15 = pd.Series({"open": 100.0, "high": 105.0, "low": 99.0, "close": 104.0}, name=pd.Timestamp("2026-10-08 10:00", tz="UTC"))
    m5 = pd.DataFrame(
        [
            (100.0, 102.0, 99.5, 101.0),
            (101.0, 104.0, 100.8, 103.0),
        ],
        index=pd.date_range("2026-10-08 10:00", periods=2, freq="5min", tz="UTC"),
        columns=["open", "high", "low", "close"],
    )
    when, price = WilliamsCampaignBacktester._resolve_buy_trigger(m15, 103.0, m5)
    assert when == m5.index[1]
    assert price == 103.0


def test_backtester_does_not_use_pre_fill_m5_stop_path():
    m15 = pd.Series({"open": 100.0, "high": 105.0, "low": 95.0, "close": 104.0}, name=pd.Timestamp("2026-10-08 10:00", tz="UTC"))
    m5 = pd.DataFrame(
        [
            (100.0, 102.0, 96.0, 101.0),
            (101.0, 104.0, 100.0, 103.0),
        ],
        index=pd.date_range("2026-10-08 10:00", periods=2, freq="5min", tz="UTC"),
        columns=["open", "high", "low", "close"],
    )
    when, _ = WilliamsCampaignBacktester._resolve_stop(m15, 97.0, m5, after=m5.index[1])
    assert when is None


def test_conservative_profile_disables_wm3_first():
    policy = IntradayPolicy.from_env({"WILLIAMS_STRATEGY_PROFILE": CONSERVATIVE_PROFILE})
    assert policy.wm3_first_allowed is False


def test_decision_trace_is_durable(tmp_path):
    db = Database(str(tmp_path / "trace.sqlite3"))
    trace = {
        "trace_id": "BTCUSDT:1h:123",
        "symbol": "BTCUSDT",
        "decision_time_ms": 123,
        "decision_tf": "1h",
        "execution_tf": "15m",
        "micro_tf": "5m",
        "core_valid": True,
        "trade_allowed": False,
        "block_reason": "BLOCKED_BY_EXECUTION_ECONOMICS",
    }
    db.save_decision_trace(trace)
    rows = db.recent_decision_traces("BTCUSDT", limit=5)
    assert rows[0]["trace_id"] == "BTCUSDT:1h:123"
    assert rows[0]["trade_allowed"] == 0


def test_session_contract_boundaries():
    from datetime import datetime, timezone
    from williams_intraday_spec import SessionContract
    session = SessionContract()
    def dt(h, m):
        return datetime(2026, 10, 8, h, m, tzinfo=timezone.utc)
    assert session.state(dt(7, 59)) == "PRE_SESSION"
    assert session.state(dt(8, 0)) == "ENTRY_WINDOW"
    assert session.state(dt(18, 0)) == "MANAGE_ONLY"
    assert session.state(dt(20, 0)) == "FLAT_REQUIRED"


def test_signal_activation_time_is_separate_from_formation_time():
    from campaign_model import SignalSpec, SignalRole, SignalType
    spec = SignalSpec.new(
        symbol="BTCUSDT",
        side="BUY",
        signal_type=SignalType.FRACTAL,
        role=SignalRole.ENTRY,
        timeframe="1h",
        signal_bar_time_ms=100,
        detected_time_ms=200,
        trigger_price=101,
        protective_reference=98,
    )
    assert spec.signal_bar_time_ms == 100
    assert spec.detected_time_ms == 200
