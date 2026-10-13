from equity_breaker import EquityCircuitBreaker


class MemoryRiskDB:
    def __init__(self):
        self.state = {}
        self.pnl = 0.0
        self.fail_latch_write = False

    def state_get(self, key):
        return self.state.get(key)

    def state_set(self, key, value):
        if key == "daily_risk_tripped" and value == "1" and self.fail_latch_write:
            raise OSError("simulated latch persistence failure")
        self.state[key] = value

    def pnl_today_all(self):
        return self.pnl


def test_daily_loss_latch_survives_recovery_and_process_restart():
    db = MemoryRiskDB()
    first = EquityCircuitBreaker(max_daily_loss_pct=0.03)
    assert first.check(db, 10_000.0) == (True, "ok")

    db.pnl = -300.0
    allowed, reason = first.check(db, 9_700.0)
    assert not allowed
    assert "tripped" in reason
    assert db.state["daily_risk_tripped"] == "1"

    # PnL recovers, but the daily latch remains set, including across instances.
    db.pnl = -50.0
    restarted = EquityCircuitBreaker(max_daily_loss_pct=0.03)
    allowed, reason = restarted.check(db, 9_950.0)
    assert not allowed
    assert "already tripped" in reason


def test_new_utc_day_resets_baseline_and_latch():
    db = MemoryRiskDB()
    db.state.update({
        "daily_risk_day": "1900-01-01",
        "daily_start_equity": "10000",
        "daily_risk_tripped": "1",
    })
    allowed, reason = EquityCircuitBreaker().check(db, 9_000.0)
    assert allowed, reason
    assert db.state["daily_start_equity"] == "9000.0"
    assert db.state["daily_risk_tripped"] == "0"


def test_latch_persistence_failure_blocks_entries():
    db = MemoryRiskDB()
    breaker = EquityCircuitBreaker(max_daily_loss_pct=0.03)
    assert breaker.check(db, 10_000.0) == (True, "ok")
    db.pnl = -300.0
    db.fail_latch_write = True
    allowed, reason = breaker.check(db, 9_700.0)
    assert not allowed
    assert "latch persistence failed" in reason


class MissingDailyPnLDB:
    def __init__(self):
        self.state = {}

    def state_get(self, key):
        return self.state.get(key)

    def state_set(self, key, value):
        self.state[key] = value


def test_missing_global_daily_pnl_fails_closed():
    allowed, reason = EquityCircuitBreaker().check(MissingDailyPnLDB(), 10_000.0)
    assert not allowed
    assert "daily PnL unavailable" in reason
