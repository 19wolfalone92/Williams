"""Regression tests for the portfolio daily equity loss circuit breaker."""
from datetime import datetime, timezone

import pytest

from equity_breaker import EquityCircuitBreaker


class _DB:
    def __init__(self, start_equity=1000.0, realized=-20.0):
        self.state = {
            "daily_risk_day": datetime.now(timezone.utc).date().isoformat(),
            "daily_start_equity": str(start_equity),
        }
        self.realized = realized

    def state_get(self, key, default=None):
        return self.state.get(key, default)

    def state_set(self, key, value):
        self.state[key] = str(value)

    def pnl_today_all(self):
        return self.realized


def test_daily_loss_breaker_blocks_at_three_percent_including_unrealized():
    db = _DB(start_equity=1000.0, realized=-20.0)
    allowed, reason = EquityCircuitBreaker(0.03).check(
        db, current_equity_quote=970.0, unrealized_pnl_quote=-10.0
    )
    assert allowed is False
    assert "daily equity loss circuit breaker tripped" in reason


def test_daily_loss_breaker_allows_loss_below_three_percent():
    db = _DB(start_equity=1000.0, realized=-20.0)
    allowed, reason = EquityCircuitBreaker(0.03).check(
        db, current_equity_quote=980.0, unrealized_pnl_quote=-5.0
    )
    assert allowed is True
    assert reason == "ok"


def test_daily_loss_breaker_blocks_when_equity_is_zero():
    db = _DB()
    allowed, reason = EquityCircuitBreaker(0.03).check(
        db, current_equity_quote=0.0
    )
    assert allowed is False
    assert reason == "equity is zero or negative"

class _DBWithoutDailyPnl(_DB):
    pnl_today_all = None


def test_daily_loss_breaker_fails_closed_when_realized_pnl_is_unavailable():
    db = _DBWithoutDailyPnl()
    allowed, reason = EquityCircuitBreaker(0.03).check(
        db, current_equity_quote=1000.0
    )
    assert allowed is False
    assert "daily realized PnL unavailable" in reason


def test_database_reports_realized_pnl_across_all_symbols(tmp_path):
    from db import Database

    db = Database(str(tmp_path / "daily-risk.sqlite3"))
    now = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")
    db.conn.executemany(
        "INSERT INTO trades(entry_time, exit_time, symbol, pnl, fees) VALUES(?,?,?,?,?)",
        [
            (now, now, "BTCUSDT", -12.0, 0.5),
            (now, now, "ETHUSDT", -18.0, 0.5),
            (now, None, "SOLUSDT", -99.0, 0.0),
        ],
    )
    db.conn.commit()
    try:
        assert db.pnl_today_all() == pytest.approx(-30.0)
        assert db.pnl_today("BTCUSDT") == pytest.approx(-12.0)
    finally:
        db.conn.close()


def test_daily_loss_configuration_cannot_widen_the_hard_three_percent_cap():
    assert EquityCircuitBreaker(0.10).max_daily_loss_pct == pytest.approx(0.03)


def test_trading_config_cannot_raise_daily_loss_above_three_percent():
    from trading_config import TradingConfig

    config = TradingConfig.from_env({"MAX_DAILY_LOSS_PCT": "0.10"})
    assert config.max_daily_loss_pct == pytest.approx(0.03)
