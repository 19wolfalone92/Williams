"""Regression tests for write-ahead execution-intent durability.

These tests use an in-memory controlled DB/cache and never call Binance.
"""
import threading

from execution_barrier import ExecutionBarrier, OrderIntent


class _Snapshot:
    def context(self, symbol, interval):
        return None


class _Cache:
    def __init__(self):
        self.execution_lock = threading.RLock()

    def snapshot(self):
        return _Snapshot()


class _FailingIntentDB:
    def find_execution_intent(self, intent_id, client_order_id=""):
        return None

    def unresolved_execution_intents(self):
        return []

    def save_execution_intent(self, intent, status, reason=""):
        raise OSError("synthetic sqlite commit failure")

    def log_event(self, level, event, message, raw=None):
        # Diagnostics are best-effort; they must not trigger exchange mutation.
        return None


class _FailingAdmissionEventDB:
    def find_execution_intent(self, intent_id, client_order_id=""):
        return None

    def unresolved_execution_intents(self):
        return []

    def __init__(self):
        self.intents = []

    def save_execution_intent(self, intent, status, reason=""):
        self.intents.append((intent.intent_id, status, reason))

    def save_execution_event(self, intent_id, event, payload=None):
        if event == "ADMISSION_STARTED":
            raise OSError("synthetic event commit failure")

    def log_event(self, level, event, message, raw=None):
        return None


def _intent():
    return OrderIntent.new(
        symbol="BTCUSDT",
        side="BUY",
        order_type="MARKET",
        required_context_versions={},
        purpose="CAMPAIGN_ENTRY",
        client_order_id="WILL_TEST_DURABLE_INTENT_001",
    )


def test_sqlite_write_ahead_intent_failure_never_calls_submit():
    calls = []
    barrier = ExecutionBarrier(_Cache(), _FailingIntentDB())

    result = barrier.execute(_intent(), lambda: calls.append("submit"))

    assert result.accepted is False
    assert "intent_persistence_failed" in result.reason
    assert calls == []


def test_admission_event_persistence_failure_never_calls_submit():
    calls = []
    db = _FailingAdmissionEventDB()
    barrier = ExecutionBarrier(_Cache(), db)

    result = barrier.execute(_intent(), lambda: calls.append("submit"))

    assert result.accepted is False
    assert "admission_event_persistence_failed" in result.reason
    assert calls == []
    # The write-ahead intent was persisted before the failed event write.
    assert db.intents[0][1] == "PENDING"
    # The barrier attempts to record a terminal blocked state.
    assert db.intents[-1][1] == "BLOCKED"
