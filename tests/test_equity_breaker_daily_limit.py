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
    assert reason == "equity is zero"
