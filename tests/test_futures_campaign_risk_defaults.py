"""Regression tests for the approved conservative Futures campaign risk profile."""
from trading_config import TradingConfig
from williams_tc2_pyramid import initial_unit_risk_pct


def test_tc2_initial_unit_risk_matches_android_full_pyramid_reservation():
    # 1:5:4:3:2 totals 15 units; reserve the 5-unit max tranche within 0.25%.
    assert initial_unit_risk_pct(0.002, 0.006, 0.0025) == 0.0004


def test_futures_defaults_use_single_campaign_risk_limits():
    config = TradingConfig.from_env({})

    assert config.market == "futures_usdt"
    assert config.execution_timeframe == "1h"
    assert config.risk_per_trade_pct == 0.0025
    assert config.max_total_risk_pct == 0.006
    assert config.max_daily_loss_pct == 0.01
    assert config.max_open_positions == 10
    assert config.allow_long is True
    assert config.allow_short is True


def test_futures_environment_cannot_raise_approved_risk_caps():
    config = TradingConfig.from_env({
        "MAX_RISK_PER_TRADE_PCT": "0.01",
        "RISK_PER_TRADE_PCT": "0.01",
        "MAX_TOTAL_RISK_PCT": "0.05",
        "MAX_DAILY_LOSS_PCT": "0.10",
        "MAX_OPEN_POSITIONS": "10",
    })

    assert config.risk_per_trade_pct == 0.0025
    assert config.max_total_risk_pct == 0.006
    assert config.max_daily_loss_pct == 0.01
    assert config.max_open_positions == 10


def test_futures_can_reduce_risk_but_not_increase_it():
    config = TradingConfig.from_env({
        "MAX_RISK_PER_TRADE_PCT": "0.001",
        "MAX_TOTAL_RISK_PCT": "0.004",
        "MAX_DAILY_LOSS_PCT": "0.005",
        "MAX_OPEN_POSITIONS": "0",
    })

    assert config.risk_per_trade_pct == 0.001
    assert config.max_total_risk_pct == 0.004
    assert config.max_daily_loss_pct == 0.005
    assert config.max_open_positions == 1



def test_lower_portfolio_cap_also_lowers_entry_and_campaign_caps():
    config = TradingConfig.from_env({
        "MAX_TOTAL_RISK_PCT": "0.001",
        "MAX_RISK_PER_TRADE_PCT": "0.0025",
        "CAMPAIGN_RISK_LIMIT_PCT": "0.006",
        "INITIAL_ENTRY_RISK_PCT": "0.002",
    })

    assert config.max_total_risk_pct == 0.001
    assert config.campaign_risk_limit_pct == 0.001
    assert config.risk_per_trade_pct == 0.001
    assert config.initial_entry_risk_pct == 0.001
