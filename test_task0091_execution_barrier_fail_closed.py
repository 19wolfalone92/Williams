"""TASK 009.1 regression tests for fail-closed execution-intent persistence.

These tests use a fake cache and fake store only; they never contact Binance.
"""
import threading
from concurrent.futures import ThreadPoolExecutor

import pytest

from db import Database
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
    def __init__(self, fail_on_status=None, fail_on_result=False):
        self.fail_on_status = fail_on_status
        self.fail_on_result = fail_on_result
        self.statuses = []
        self.states = {}

    def execution_readiness(self, scope="spot"):
        if self.states.get(f"execution_lock:{scope}"):
            return {"ready": False, "reason": "EXECUTION_LOCKED"}
        return {"ready": True, "reason": ""}

    def claim_execution_intent(self, intent, scope="spot"):
        self.save_execution_intent(intent, "PENDING")
        return {"claimed": True, "reason": "", "existing": None}

    def save_execution_intent(self, intent, status, reason=""):
        self.statuses.append(status)
        if status == self.fail_on_status:
            raise OSError("simulated durable-store failure")

    def save_execution_event(self, intent_id, event, payload=None):
        if self.fail_on_result and event == "BINANCE_RESULT":
            raise OSError("simulated exchange-result persistence failure")

    def save_execution_result(self, intent, response):
        self.save_execution_event(intent.intent_id, "BINANCE_RESULT", response)
        self.save_execution_intent(intent, "SUBMITTED")

    def mark_execution_unknown(self, intent, reason, scope="spot"):
        self.statuses.append("UNKNOWN")
        self.states[f"execution_lock:{scope}"] = {
            "intent_id": intent.intent_id, "reason": reason
        }

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
    assert store.statuses == ["PENDING", "SUBMITTING", "SUBMITTED", "UNKNOWN"]

def test_exchange_response_persistence_failure_is_unknown_not_success():
    store = _Store(fail_on_result=True)
    barrier = ExecutionBarrier(_Cache(), store)
    calls = []

    with pytest.raises(ExecutionOutcomeUnknown):
        barrier.execute(
            _intent(),
            lambda: calls.append("submitted") or {"status": "NEW", "executedQty": "0"},
        )

    assert calls == ["submitted"], "do not resubmit after result-storage failure"
    assert store.statuses == ["PENDING", "SUBMITTING", "UNKNOWN"], "intent remains unresolved for reconciliation"


def test_timeout_after_possible_submit_persists_unknown_and_blocks_second_mutation(tmp_path):
    db = Database(str(tmp_path / "barrier-timeout.sqlite3"))
    barrier = ExecutionBarrier(_Cache(), db)
    first = _intent()
    calls = []

    def timeout_after_send():
        calls.append("sent")
        raise TimeoutError("simulated response timeout after request may have reached exchange")

    with pytest.raises(ExecutionOutcomeUnknown):
        barrier.execute(first, timeout_after_send)

    assert calls == ["sent"]
    assert db.execution_readiness()["ready"] is False
    row = db.conn.execute(
        "SELECT status FROM execution_intents WHERE intent_id=?", (first.intent_id,)
    ).fetchone()
    assert row["status"] == "UNKNOWN"

    second_calls = []
    result = barrier.execute(_intent(), lambda: second_calls.append("sent"))
    assert result.accepted is False
    assert second_calls == []


def test_concurrent_barrier_requests_with_same_client_order_id_submit_once(tmp_path):
    db = Database(str(tmp_path / "same-client-id.sqlite3"))
    barrier = ExecutionBarrier(_Cache(), db)
    item = _intent()
    calls = []

    def submit():
        calls.append("sent")
        return {"status": "FILLED", "executedQty": "0.01", "orderId": 9001}

    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(barrier.execute, item, submit) for _ in range(2)]
        results = [future.result(timeout=5) for future in futures]

    assert sum(result.accepted for result in results) == 1
    assert calls == ["sent"]
    assert db.conn.execute(
        "SELECT COUNT(*) FROM execution_intents WHERE client_order_id=?",
        (item.client_order_id,),
    ).fetchone()[0] == 1
