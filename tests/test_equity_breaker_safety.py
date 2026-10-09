import pytest

from equity_breaker import EquityCircuitBreaker


class FakeDB:
    def __init__(self, realized=0.0, state=None, fail_pnl=False, fail_state=False):
        self.realized = realized
        self.state = dict(state or {})
        self.fail_pnl = fail_pnl
        self.fail_state = fail_state

    def pnl_today_all(self):
        if self.fail_pnl:
            raise RuntimeError("database unavailable")
        return self.realized

    def pnl_today(self, symbol):
        if self.fail_pnl:
            raise RuntimeError("database unavailable")
        return self.realized

    def state_get(self, key, default=None):
        if self.fail_state:
            raise RuntimeError("state unavailable")
        return self.state.get(key, default)

    def state_set(self, key, value):
        if self.fail_state:
            raise RuntimeError("state unavailable")
        self.state[key] = value


@pytest.mark.parametrize("value", [float("nan"), float("inf"), float("-inf"), -0.01, 1.01])
def test_daily_loss_limit_rejects_invalid_configuration(value):
    with pytest.raises(ValueError):
        EquityCircuitBreaker(value)


def test_daily_loss_breaker_blocks_when_realized_pnl_cannot_be_read():
    allowed, reason = EquityCircuitBreaker().check(
        FakeDB(fail_pnl=True), current_equity_quote=10_000
    )
    assert not allowed
    assert "realized PnL unavailable" in reason


@pytest.mark.parametrize("realized", [float("nan"), float("inf"), float("-inf")])
def test_daily_loss_breaker_blocks_non_finite_realized_pnl(realized):
    allowed, reason = EquityCircuitBreaker().check(
        FakeDB(realized=realized), current_equity_quote=10_000
    )
    assert not allowed
    assert "non-finite realized PnL" in reason


def test_daily_loss_breaker_blocks_corrupt_persisted_baseline():
    db = FakeDB(state={
        "daily_risk_day": __import__("datetime").datetime.now(
            __import__("datetime").timezone.utc
        ).date().isoformat(),
        "daily_start_equity": "nan",
    })
    allowed, reason = EquityCircuitBreaker().check(db, current_equity_quote=10_000)
    assert not allowed
    assert "daily start equity unavailable" in reason


def test_daily_loss_breaker_includes_unrealized_loss_and_fees():
    db = FakeDB(realized=-100)
    allowed, reason = EquityCircuitBreaker(max_daily_loss_pct=0.03).check(
        db,
        current_equity_quote=10_000,
        unrealized_pnl_quote=-150,
        fees_quote=60,
    )
    assert not allowed
    assert "circuit breaker tripped" in reason


def test_daily_loss_breaker_passes_inside_limit_and_persists_baseline():
    db = FakeDB(realized=-100)
    allowed, reason = EquityCircuitBreaker().check(
        db, current_equity_quote=10_000, unrealized_pnl_quote=0, fees_quote=0
    )
    assert allowed
    assert reason == "ok"
    assert float(db.state["daily_start_equity"]) == 10_000
