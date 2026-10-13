"""Fault-injection regression tests for the execution mutation boundary."""
import threading

import pytest

from execution_barrier import ExecutionBarrier, OrderIntent


class _ContextCache:
    def __init__(self):
        self.execution_lock = threading.RLock()


class _FailingIntentDB:
    def save_execution_intent(self, intent, status, reason=""):
        raise OSError("simulated durable-storage failure")


def _exit_intent():
    return OrderIntent.new(
        symbol="BTCUSDT",
        side="SELL",
        order_type="MARKET",
        required_context_versions={},
        purpose="EXIT",
    )


def test_missing_database_blocks_every_exchange_mutation():
    submitted = []
    barrier = ExecutionBarrier(_ContextCache(), db=None)

    with pytest.raises(RuntimeError, match="database is not configured"):
        barrier.execute(_exit_intent(), lambda: submitted.append(True))

    assert submitted == []


def test_intent_persistence_failure_blocks_exchange_mutation():
    submitted = []
    barrier = ExecutionBarrier(_ContextCache(), db=_FailingIntentDB())

    with pytest.raises(RuntimeError, match="persistence failed"):
        barrier.execute(_exit_intent(), lambda: submitted.append(True))

    assert submitted == []

class _FailingEventDB:
    def save_execution_intent(self, intent, status, reason=""):
        return None

    def save_execution_event(self, intent_id, event, payload):
        raise OSError("simulated event-log storage failure")


def test_required_admission_event_failure_blocks_exchange_mutation():
    submitted = []
    barrier = ExecutionBarrier(_ContextCache(), db=_FailingEventDB())

    with pytest.raises(RuntimeError, match="execution-event persistence failed"):
        barrier.execute(_exit_intent(), lambda: submitted.append(True))

    assert submitted == []

class _PersistingDB:
    def __init__(self, state="RECONCILE_REQUIRED"):
        self.state = state
        self.intents = []
        self.events = []

    def state_get(self, key, default=None):
        if key == "position_state":
            return self.state
        return default

    def save_execution_intent(self, intent, status, reason=""):
        self.intents.append((intent.intent_id, status, reason))

    def save_execution_event(self, intent_id, event, payload):
        self.events.append((intent_id, event, payload))

    def claim_signal_once(self, signal_id, canonical_payload):
        return "CLAIMED"

    def log_event(self, *args, **kwargs):
        return None


def test_oco_protection_repair_is_allowed_in_reconcile_only_with_last_moment_check():
    from market_context import ContextCache

    db = _PersistingDB()
    barrier = ExecutionBarrier(ContextCache(), db=db)
    intent = OrderIntent.new(
        symbol="BTCUSDT",
        side="SELL",
        order_type="OCO",
        required_context_versions={},
        purpose="CAMPAIGN_PROTECTION",
        quantity="0.01",
        client_order_id="WAL_OCO_TEST",
    )
    response = {
        "orderListId": 42,
        "listStatusType": "EXEC_STARTED",
        "listOrderStatus": "EXECUTING",
        "orderReports": [{"status": "NEW"}, {"status": "NEW"}],
    }
    result = barrier.execute(
        intent,
        lambda: response,
        pre_submit_checks=lambda _snapshot: None,
    )
    assert result.accepted is True
    assert result.response["orderListId"] == 42


def test_oco_cancellation_requires_terminal_authoritative_response():
    from market_context import ContextCache

    db = _PersistingDB()
    barrier = ExecutionBarrier(ContextCache(), db=db)
    intent = OrderIntent.new(
        symbol="BTCUSDT",
        side="SELL",
        order_type="CANCEL_OCO",
        required_context_versions={},
        purpose="CAMPAIGN_EXIT_CANCEL_PROTECTION",
        client_order_id="42",
    )
    response = {
        "orderListId": 42,
        "listStatusType": "ALL_DONE",
        "listOrderStatus": "ALL_DONE",
        "orders": [{"status": "CANCELED"}, {"status": "CANCELED"}],
    }
    result = barrier.execute(
        intent,
        lambda: response,
        pre_submit_checks=lambda _snapshot: None,
    )
    assert result.accepted is True


def test_unresolved_oco_response_does_not_claim_submission_succeeded():
    from market_context import ContextCache

    db = _PersistingDB(state="OPEN")
    barrier = ExecutionBarrier(ContextCache(), db=db)
    intent = OrderIntent.new(
        symbol="BTCUSDT",
        side="SELL",
        order_type="OCO",
        required_context_versions={},
        purpose="CAMPAIGN_PROTECTION",
        quantity="0.01",
        client_order_id="WAL_OCO_BAD",
    )
    with pytest.raises(RuntimeError, match="OCO creation response"):
        barrier.execute(intent, lambda: {"orderListId": 42})


class _FailsAfterExchangeDB:
    def __init__(self, fail_on="SUBMITTED"):
        self.fail_on = fail_on
        self.intents = []
        self.events = []

    def state_get(self, key, default=None):
        return default

    def save_execution_intent(self, intent, status, reason=""):
        self.intents.append((status, reason))
        if status == self.fail_on:
            raise OSError("simulated post-submit storage failure")

    def save_execution_event(self, intent_id, event, payload):
        self.events.append(event)


def test_post_submit_persistence_failure_is_ambiguous_and_never_retries():
    submitted = []
    db = _FailsAfterExchangeDB(fail_on="SUBMITTED")
    barrier = ExecutionBarrier(_ContextCache(), db=db)

    with pytest.raises(RuntimeError, match="requires reconciliation"):
        barrier.execute(
            _exit_intent(),
            lambda: submitted.append(True) or {"status": "FILLED", "executedQty": "0.01"},
        )

    assert submitted == [True]
    assert ("SUBMITTING", "") in db.intents
    assert any(status == "AMBIGUOUS" for status, _ in db.intents)


def test_post_submit_event_failure_is_ambiguous_and_never_retries():
    submitted = []
    db = _PersistingDB()

    def fail_submitted_event(intent_id, event, payload):
        if event == "BINANCE_SUBMITTED":
            raise OSError("simulated post-submit audit failure")
        db.events.append((intent_id, event, payload))

    db.save_execution_event = fail_submitted_event
    barrier = ExecutionBarrier(_ContextCache(), db=db)

    with pytest.raises(RuntimeError, match="requires reconciliation"):
        barrier.execute(
            _exit_intent(),
            lambda: submitted.append(True) or {"status": "FILLED", "executedQty": "0.01"},
        )

    assert submitted == [True]
    assert any(status == "AMBIGUOUS" for status, _ in db.intents)
