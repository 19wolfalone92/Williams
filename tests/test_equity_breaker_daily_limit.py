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


def test_daily_loss_trip_stays_latched_after_pnl_recovers_same_day():
    db = _DB(start_equity=1000.0, realized=-35.0)
    breaker = EquityCircuitBreaker(0.03)

    allowed, reason = breaker.check(db, current_equity_quote=965.0)
    assert allowed is False
    assert "tripped" in reason
    assert db.state["daily_loss_tripped"] == "1"

    # Later unrealized/realized PnL recovery must not reopen entry admission
    # during the same UTC day.
    db.realized = 0.0
    allowed, reason = breaker.check(db, current_equity_quote=1000.0)
    assert allowed is False
    assert "remains latched" in reason


def test_daily_loss_latch_resets_only_when_new_utc_day_is_established():
    db = _DB(start_equity=1000.0, realized=0.0)
    db.state["daily_risk_day"] = "2000-01-01"
    db.state["daily_loss_tripped"] = "1"

    allowed, reason = EquityCircuitBreaker(0.03).check(
        db, current_equity_quote=1200.0
    )

    assert allowed is True
    assert reason == "ok"
    assert db.state["daily_loss_tripped"] == "0"
    assert db.state["daily_start_equity"] == "1200.0"


def test_daily_risk_gate_runs_before_pending_entry_adoption():
    from types import SimpleNamespace
    from portfolio_trader import MultiPositionTrader

    events = []

    class _CampaignExecution:
        def reconcile_active_campaigns(self):
            events.append("active_recovery")
            return []

        def reconcile_pending_entries(self, **kwargs):
            events.append(("pending_recovery", kwargs.get("force_cancel_reason")))
            return [{"state": "ENTRY_CANCELLED_STALE"}]

    trader = MultiPositionTrader.__new__(MultiPositionTrader)
    trader.campaign_engine_enabled = True
    trader.campaign_execution = _CampaignExecution()
    trader.campaign_monitor = SimpleNamespace(
        monitor_all=lambda: events.append("campaign_monitor") or []
    )
    trader._daily_entry_guard = lambda: events.append("daily_guard") or (
        False, "daily loss limit reached"
    )
    trader.db = SimpleNamespace(
        log_event=lambda *args, **kwargs: events.append("risk_event")
    )

    result = trader.scan_and_execute()

    assert result["status"] == "RISK_BLOCKED"
    assert events.index("daily_guard") < next(
        i for i, item in enumerate(events)
        if isinstance(item, tuple) and item[0] == "pending_recovery"
    )
    assert ("pending_recovery", "DAILY_RISK_GATE_BLOCKED") in events


def test_missing_same_day_baseline_fails_closed_without_clearing_trip_latch():
    db = _DB(start_equity=1000.0, realized=0.0)
    db.state["daily_loss_tripped"] = "1"
    del db.state["daily_start_equity"]

    allowed, reason = EquityCircuitBreaker(0.03).check(
        db, current_equity_quote=1200.0
    )

    assert allowed is False
    assert "baseline unavailable" in reason
    assert db.state["daily_loss_tripped"] == "1"


def test_trip_latch_without_day_key_fails_closed():
    db = _DB(start_equity=1000.0, realized=0.0)
    db.state.pop("daily_risk_day", None)
    db.state["daily_loss_tripped"] = "1"

    allowed, reason = EquityCircuitBreaker(0.03).check(
        db, current_equity_quote=1200.0
    )

    assert allowed is False
    assert "baseline unavailable" in reason
    assert db.state["daily_loss_tripped"] == "1"


def test_direct_campaign_entry_api_cannot_bypass_daily_risk_gate():
    from types import SimpleNamespace
    from portfolio_trader import MultiPositionTrader

    calls = []

    class _CampaignExecution:
        def reconcile_pending_entries(self, **kwargs):
            calls.append(kwargs.get("force_cancel_reason"))
            return []

    trader = MultiPositionTrader.__new__(MultiPositionTrader)
    trader.campaign_execution = _CampaignExecution()
    trader._daily_entry_guard = lambda: (False, "daily loss limit reached")
    trader.db = SimpleNamespace(log_event=lambda *args, **kwargs: None)
    selection = SimpleNamespace(candidate=SimpleNamespace(symbol="BTCUSDT"))

    result = trader.execute_campaign([selection])

    assert result[0]["status"] == "RISK_BLOCKED"
    assert result[0]["symbol"] == "BTCUSDT"
    assert calls == ["DAILY_RISK_GATE_BLOCKED"]


@pytest.mark.parametrize("day_key", ["not-a-date", "2999-12-31"])
def test_corrupt_or_future_daily_risk_day_key_fails_closed(day_key):
    db = _DB(start_equity=1000.0, realized=0.0)
    db.state["daily_risk_day"] = day_key
    db.state["daily_loss_tripped"] = "1"

    allowed, reason = EquityCircuitBreaker(0.03).check(
        db, current_equity_quote=1200.0
    )

    assert allowed is False
    assert "baseline unavailable" in reason
    assert db.state["daily_loss_tripped"] == "1"
