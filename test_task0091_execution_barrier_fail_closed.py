"""TASK 009.1 regression tests for fail-closed execution-intent persistence.

These tests use a fake cache and fake store only; they never contact Binance.
"""
import threading

import pytest

from execution_barrier import (
    ExecutionBarrier,
    ExecutionOutcomeUnknown,
    ExecutionPersistenceError,
    OrderIntent,
)


class _Snapshot:
    def context(self, symbol, interval):
        return None


class _Cache:
    def __init__(self):
        self.execution_lock = threading.RLock()

    def snapshot(self):
        return _Snapshot()


class _Store:
    def __init__(self, fail_on_status=None):
        self.fail_on_status = fail_on_status
        self.statuses = []
        self.states = {}

    def save_execution_intent(self, intent, status, reason=""):
        self.statuses.append(status)
        if status == self.fail_on_status:
            raise OSError("simulated durable-store failure")

    def state_get(self, key, default=None):
        return self.states.get(key, default)

    def log_event(self, *args, **kwargs):
        pass


def _intent():
    # Campaign purpose permits an empty context-version map, while the test
    # still traverses the actual barrier admission and submit path.
    return OrderIntent.new(
        "BTCUSDT",
        "SELL",
        "MARKET",
        {},
        purpose="CAMPAIGN_EXIT",
        campaign_id="campaign-test",
        client_order_id="WILL_TEST_0091",
    )


def test_intent_persistence_failure_blocks_submit():
    store = _Store(fail_on_status="PENDING")
    barrier = ExecutionBarrier(_Cache(), store)
    calls = []

    with pytest.raises(ExecutionPersistenceError):
        barrier.execute(_intent(), lambda: calls.append("submitted") or {"status": "NEW"})

    assert calls == [], "Binance submit must not run unless PENDING intent is durable"


def test_missing_durable_store_blocks_submit():
    barrier = ExecutionBarrier(_Cache(), None)
    calls = []

    with pytest.raises(ExecutionPersistenceError):
        barrier.execute(_intent(), lambda: calls.append("submitted") or {"status": "NEW"})

    assert calls == [], "no database must never degrade into volatile execution"


def test_result_persistence_failure_after_submit_is_unknown_not_success():
    store = _Store(fail_on_status="SUBMITTED")
    barrier = ExecutionBarrier(_Cache(), store)
    calls = []

    with pytest.raises(ExecutionOutcomeUnknown):
        barrier.execute(
            _intent(),
            lambda: calls.append("submitted") or {"status": "NEW", "executedQty": "0"},
        )

    assert calls == ["submitted"], "submit is called once and never retried by the barrier"
    assert store.statuses == ["PENDING", "SUBMITTED"]
