"""Double-failure and replay regression tests. No network or real orders are used."""
import threading

import pytest

from execution_barrier import ExecutionBarrier, ExecutionPersistenceError, OrderIntent
from db import Database


class _Snapshot:
    def context(self, symbol, interval):
        return None


class _Cache:
    def __init__(self):
        self.execution_lock = threading.RLock()

    def snapshot(self):
        return _Snapshot()


class _DB:
    def __init__(self, fail_statuses=(), fail_event=False):
        self.rows = {}
        self.events = []
        self.fail_statuses = set(fail_statuses)
        self.fail_event = fail_event

    def unresolved_execution_intents(self):
        unresolved = {"PENDING", "AMBIGUOUS", "SUBMITTING", "RECONCILE_REQUIRED"}
        return [dict(row) for row in self.rows.values() if row["status"] in unresolved]

    def find_execution_intent(self, intent_id, client_order_id=""):
        for row in self.rows.values():
            if row["intent_id"] == intent_id or (
                client_order_id and row["client_order_id"] == client_order_id
            ):
                return dict(row)
        return None

    def save_execution_intent(self, intent, status, reason=""):
        if status in self.fail_statuses:
            raise OSError("SYNTHETIC_DB_SECRET_MARKER")
        self.rows[intent.intent_id] = {
            "intent_id": intent.intent_id,
            "client_order_id": intent.client_order_id,
            "status": status,
            "reason": reason,
        }

    def save_execution_event(self, intent_id, event, payload=None):
        if self.fail_event and event == "ADMISSION_STARTED":
            raise OSError("SYNTHETIC_EVENT_SECRET_MARKER")
        self.events.append((intent_id, event, payload))

    def log_event(self, level, event, message, raw=None):
        self.events.append((None, event, {"message": message, "raw": raw}))


def _intent(client_id="WILL_TASK006_DOUBLE_FAILURE_001"):
    return OrderIntent.new(
        symbol="BTCUSDT",
        side="BUY",
        order_type="MARKET",
        required_context_versions={},
        purpose="CAMPAIGN_ENTRY",
        client_order_id=client_id,
    )


def test_pending_write_failure_never_calls_submit():
    calls = []
    db = _DB(fail_statuses={"PENDING"})
    result = ExecutionBarrier(_Cache(), db).execute(_intent(), lambda: calls.append(1))
    assert not result.accepted
    assert "intent_persistence_failed" in result.reason
    assert calls == []
    assert db.rows == {}


def test_admission_event_and_blocked_write_both_fail_and_retry_is_blocked():
    calls = []
    db = _DB(fail_statuses={"BLOCKED"}, fail_event=True)
    barrier = ExecutionBarrier(_Cache(), db)
    intent = _intent()
    with pytest.raises(ExecutionPersistenceError, match="admission_event_and_blocked_state_persistence_failed"):
        barrier.execute(intent, lambda: calls.append(1))
    assert calls == []
    assert db.rows[intent.intent_id]["status"] == "PENDING"
    retry = barrier.execute(intent, lambda: calls.append(1))
    assert not retry.accepted
    assert "duplicate_or_unresolved_intent" in retry.reason
    assert calls == []


def test_pre_submit_failure_and_blocked_write_failure_never_submit_or_claim_persisted_block():
    calls = []
    db = _DB(fail_statuses={"BLOCKED"})
    barrier = ExecutionBarrier(_Cache(), db)
    intent = _intent("WILL_TASK006_DOUBLE_FAILURE_002")

    def fail_preflight(_snapshot):
        raise ValueError("SYNTHETIC_PREFLIGHT_SECRET_MARKER")

    with pytest.raises(ExecutionPersistenceError, match="pre_submit_check_and_blocked_state_persistence_failed"):
        barrier.execute(intent, lambda: calls.append(1), pre_submit_checks=fail_preflight)
    assert calls == []
    assert db.rows[intent.intent_id]["status"] == "PENDING"
    assert "SYNTHETIC_PREFLIGHT_SECRET_MARKER" not in repr(db.rows)
    retry = ExecutionBarrier(_Cache(), db).execute(intent, lambda: calls.append(1))
    assert not retry.accepted
    assert calls == []


def test_submit_may_have_succeeded_but_ambiguous_write_fails_no_replay_after_restart():
    class FakeExchange:
        def __init__(self):
            self.orders = []

        def submit_then_lose_response(self, intent):
            self.orders.append({
                "symbol": intent.symbol,
                "clientOrderId": intent.client_order_id,
                "status": "NEW",
            })
            raise TimeoutError("SYNTHETIC_NETWORK_SECRET_MARKER")

    exchange = FakeExchange()
    calls = []

    def submit():
        calls.append(1)
        return exchange.submit_then_lose_response(intent)

    db = _DB(fail_statuses={"AMBIGUOUS"})
    intent = _intent("WILL_TASK006_UNKNOWN_003")
    barrier = ExecutionBarrier(_Cache(), db)
    with pytest.raises(ExecutionPersistenceError, match="submit_outcome_unknown_and_ambiguous_state_not_persisted") as exc:
        barrier.execute(intent, submit)

    assert calls == [1]
    assert len(exchange.orders) == 1
    assert exchange.orders[0]["clientOrderId"] == intent.client_order_id
    assert db.rows[intent.intent_id]["status"] == "PENDING"
    assert "SYNTHETIC_NETWORK_SECRET_MARKER" not in str(exc.value)

    # Simulate a process restart: durable PENDING is unresolved, so no second submit.
    restarted = ExecutionBarrier(_Cache(), db)
    retry = restarted.execute(intent, submit)
    assert not retry.accepted
    assert "prior_intent_exists:PENDING" in retry.reason
    assert calls == [1]
    assert len(exchange.orders) == 1

    # A different intent must also be blocked while the original outcome is unresolved.
    other_intent = _intent("WILL_TASK006_NEW_ENTRY_WHILE_UNKNOWN_007")
    other = restarted.execute(other_intent, submit)
    assert not other.accepted
    assert "unresolved_execution_intent_requires_reconciliation" in other.reason
    assert calls == [1]
    assert len(exchange.orders) == 1


def test_exception_text_is_not_persisted_for_unknown_submit_result():
    db = _DB()
    intent = _intent("WILL_TASK006_UNKNOWN_004")
    barrier = ExecutionBarrier(_Cache(), db)

    def timeout():
        raise TimeoutError("SYNTHETIC_NETWORK_SECRET_MARKER")

    with pytest.raises(RuntimeError, match="submit_outcome_unknown_reconciliation_required") as exc:
        barrier.execute(intent, timeout)
    assert "SYNTHETIC_NETWORK_SECRET_MARKER" not in str(exc.value)
    assert db.rows[intent.intent_id]["status"] == "AMBIGUOUS"
    assert "SYNTHETIC_NETWORK_SECRET_MARKER" not in repr(db.rows)
    assert "SYNTHETIC_NETWORK_SECRET_MARKER" not in repr(db.events)



def test_existing_client_order_id_blocks_new_intent_before_submit():
    db = _DB()
    barrier = ExecutionBarrier(_Cache(), db)
    first = _intent("WILL_TASK006_CLIENT_ID_REUSE_005")
    calls = []
    result = barrier.execute(
        first,
        lambda: calls.append(1) or {"status": "FILLED", "executedQty": "1"},
    )
    assert result.accepted
    second = _intent("WILL_TASK006_CLIENT_ID_REUSE_005")
    retry = ExecutionBarrier(_Cache(), db).execute(second, lambda: calls.append(2))
    assert not retry.accepted
    assert "prior_intent_exists:SUBMITTED" in retry.reason
    assert calls == [1]


def test_oco_order_list_response_is_authoritative_and_persisted():
    db = _DB()
    barrier = ExecutionBarrier(_Cache(), db)
    intent = OrderIntent.new(
        symbol="BTCUSDT",
        side="SELL",
        order_type="OCO",
        required_context_versions={},
        purpose="CAMPAIGN_PROTECTION",
        client_order_id="WILL_TASK006_OCO_006",
        quantity="0.1",
    )
    response = {
        "orderListId": 12345,
        "listClientOrderId": intent.client_order_id,
        "orderReports": [
            {"orderId": 11, "clientOrderId": "WILL_TASK006_OCO_006_TP", "status": "NEW"},
            {"orderId": 12, "clientOrderId": "WILL_TASK006_OCO_006_SL", "status": "NEW"},
        ],
    }
    result = barrier.execute(intent, lambda: response)
    assert result.accepted
    assert db.rows[intent.intent_id]["status"] == "SUBMITTED"
    assert len(response["orderReports"]) == 2



def test_sqlite_unresolved_intent_query_requires_submitted_event(tmp_path):
    db = Database(str(tmp_path / "execution-intents.sqlite3"))
    intent = _intent("WILL_TASK006_SQLITE_UNRESOLVED_008")
    db.save_execution_intent(intent, "PENDING")
    unresolved = db.unresolved_execution_intents()
    assert [row["intent_id"] for row in unresolved] == [intent.intent_id]

    db.save_execution_intent(intent, "SUBMITTED")
    # SUBMITTED without the authoritative event remains unresolved.
    assert [row["intent_id"] for row in db.unresolved_execution_intents()] == [intent.intent_id]

    db.save_execution_event(intent.intent_id, "BINANCE_SUBMITTED", {"client_order_id": intent.client_order_id})
    assert db.unresolved_execution_intents() == []
