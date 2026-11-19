"""Regression for user-approved Williams Bot risk policy.

3% of the verified UTC daily risk baseline OR three exchange-reconciled
negative Futures closes in one UTC day block *new entries*. Neither change
waives the independent 5% emergency equity latch or signed STOP safety.
"""
from pathlib import Path

from trading_config import TradingConfig


def test_python_daily_loss_cap_is_three_percent_and_cannot_be_raised():
    assert TradingConfig.from_env({}).max_daily_loss_pct == 0.03
    assert TradingConfig.from_env({"MAX_DAILY_LOSS_PCT": "0.10"}).max_daily_loss_pct == 0.03
    assert TradingConfig.from_env({"MAX_DAILY_LOSS_PCT": "0.005"}).max_daily_loss_pct == 0.005
    assert TradingConfig.from_env({}).max_consecutive_losses == 3
    assert TradingConfig.from_env({"MAX_CONSECUTIVE_LOSSES": "99"}).max_consecutive_losses == 3


def test_android_entry_halt_and_three_loss_guard_match_canonical_policy():
    root = Path(__file__).resolve().parents[1]
    base = root / "app/src/main/java/com/williamsbot"
    runtime = (base / "StandaloneRuntime.kt").read_text()
    journal = (base / "TradeJournal.kt").read_text()
    assert "private val futuresDailyLossLimitPct = 0.03" in runtime
    assert "if (lossPct >= futuresDailyLossLimitPct)" in runtime
    assert runtime.count('.put("max_daily_loss_pct", futuresDailyLossLimitPct)') == 3
    assert "FUTURES_DAILY_LOSS_LIMIT: 3% reached" in runtime
    assert '.put("max_loss_trades_per_day", 3)' in runtime
    assert "maximumConsecutiveLosses: Int = 3" in journal
    assert "hardPauseAt: Int = 3" in journal
    assert "dailyLossTrades >= hardPauseAt" in journal
    assert "FUTURES_DAILY_LOSS_TRADE_LIMIT" in journal
