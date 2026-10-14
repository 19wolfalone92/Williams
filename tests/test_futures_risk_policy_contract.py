"""Regression guards for the approved single-campaign Futures risk profile."""
from trading_config import DEFAULT_FUTURES_SYMBOLS, DEFAULT_STRUCTURAL_TFS, TradingConfig


def test_default_futures_profile_is_single_campaign_and_three_percent_daily_loss():
    config = TradingConfig.from_env({})

    assert config.market == "futures_usdt"
    assert config.symbols == DEFAULT_FUTURES_SYMBOLS
    assert len(config.symbols) == 10
    assert config.execution_timeframe == "1h"
    assert config.structural_timeframes == DEFAULT_STRUCTURAL_TFS
    assert config.allow_long is True
    assert config.allow_short is True
    assert config.initial_entry_risk_pct == 0.002
    assert config.campaign_risk_limit_pct == 0.006
    assert config.max_daily_loss_pct == 0.03
    assert config.max_open_positions == 1
    assert config.max_consecutive_losses == 2


def test_environment_cannot_raise_futures_daily_loss_or_campaign_count():
    config = TradingConfig.from_env({
        "MAX_DAILY_LOSS_PCT": "0.10",
        "MAX_OPEN_POSITIONS": "5",
        "MAX_CONSECUTIVE_LOSSES": "10",
    })

    assert config.max_daily_loss_pct == 0.03
    assert config.max_open_positions == 1
    assert config.max_consecutive_losses == 2


def test_long_and_short_remain_enabled_under_canonical_profile():
    config = TradingConfig.from_env({})

    assert config.allow_long
    assert config.allow_short
