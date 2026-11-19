"""Regression: Williams Bot user-approved UTC loss cap is 1%, not legacy 3%.

This asserts the Python implementation and the Android source contract agree
on the *new-entry halt*, separate from the 5% emergency equity kill latch.
"""
from pathlib import Path

from trading_config import TradingConfig


def test_python_default_and_env_cannot_raise_daily_entry_loss_cap():
    assert TradingConfig.from_env({}).max_daily_loss_pct == 0.01
    assert TradingConfig.from_env({"MAX_DAILY_LOSS_PCT": "0.03"}).max_daily_loss_pct == 0.01
    assert TradingConfig.from_env({"MAX_DAILY_LOSS_PCT": "0.005"}).max_daily_loss_pct == 0.005


def test_android_entry_halt_threshold_and_public_reporting_match_one_percent():
    root = Path(__file__).resolve().parents[1]
    android = (root / "app/src/main/java/com/williamsbot/StandaloneRuntime.kt").read_text()
    assert "private val futuresDailyLossLimitPct = 0.01" in android
    assert "if (lossPct >= futuresDailyLossLimitPct)" in android
    assert android.count('.put("max_daily_loss_pct", futuresDailyLossLimitPct)') == 3
    assert "FUTURES_DAILY_LOSS_LIMIT: 1% reached" in android
    assert 'if (lossPct >= 0.03)' not in android
