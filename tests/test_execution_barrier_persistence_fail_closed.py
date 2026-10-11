"""Persistence-failure safety tests for ExecutionBarrier (mocks only)."""
from unittest.mock import Mock

from execution_barrier import ExecutionBarrier, OrderIntent
from market_context import ContextCache


class FailingIntentDB:
    def __init__(self, fail_statuses=()):
        self.fail_statuses = set(fail_statuses)
        self.saved_statuses = []
        self.events = []

    def save_execution_intent(self, intent, status, reason):
        self.saved_statuses.append(status)
        if status in self.fail_statuses:
            raise OSError(f"simulated database failure at {status}")

    def log_event(self, *args, **kwargs):
        self.events.append((args, kwargs))

    def save_execution_event(self, *args, **kwargs):
        pass


class DBWithoutIntentPersistence:
    """Represents the legacy optional-persistence interface."""

    def state_get(self, key, default=None):
        return default


def campaign_intent():
    return OrderIntent.new(
        symbol="BTCUSDT",
        side="BUY",
        order_type="LIMIT",
        required_context_versions={},
        purpose="CAMPAIGN_ENTRY",
    )


def test_pending_persistence_failure_blocks_submit():
    db = FailingIntentDB({"PENDING"})
    submit = Mock(return_value={"status": "NEW", "executedQty": "0"})
    barrier = ExecutionBarrier(ContextCache(), db)

    result = barrier.execute(campaign_intent(), submit)

    assert result.accepted is False
    assert "execution_intent_persistence_failed" in result.reason
    submit.assert_not_called()
    assert db.saved_statuses == ["PENDING"]


def test_missing_db_preserves_optional_persistence_mode():
    submit = Mock(return_value={"status": "NEW", "executedQty": "0"})
    result = ExecutionBarrier(ContextCache(), None).execute(campaign_intent(), submit)

    assert result.accepted is True
    submit.assert_called_once()


def test_db_without_save_method_preserves_legacy_mode():
    submit = Mock(return_value={"status": "NEW", "executedQty": "0"})
    result = ExecutionBarrier(ContextCache(), DBWithoutIntentPersistence()).execute(
        campaign_intent(), submit
    )

    assert result.accepted is True
    submit.assert_called_once()


def test_post_submit_persistence_failure_requires_reconciliation_without_retry():
    db = FailingIntentDB({"SUBMITTED"})
    exchange_response = {"status": "NEW", "executedQty": "0", "orderId": 123}
    submit = Mock(return_value=exchange_response)
    barrier = ExecutionBarrier(ContextCache(), db)

    result = barrier.execute(campaign_intent(), submit)

    assert result.accepted is False
    assert "post_submit_persistence_failed" in result.reason
    assert result.response is exchange_response
    submit.assert_called_once()
    assert db.saved_statuses == ["PENDING", "SUBMITTED", "AMBIGUOUS"]
    assert any(args[1] == "execution_reconcile_required" for args, _ in db.events)


def test_submitted_and_ambiguous_persistence_failures_are_explicit():
    db = FailingIntentDB({"SUBMITTED", "AMBIGUOUS"})
    exchange_response = {"status": "NEW", "executedQty": "0", "orderId": 456}
    submit = Mock(return_value=exchange_response)

    result = ExecutionBarrier(ContextCache(), db).execute(campaign_intent(), submit)

    assert result.accepted is False
    assert "post_submit_persistence_failed" in result.reason
    assert "reconciliation_state_persistence_failed" in result.reason
    assert "mandatory before any retry" in result.reason
    assert result.response is exchange_response
    submit.assert_called_once()
    assert db.saved_statuses == ["PENDING", "SUBMITTED", "AMBIGUOUS"]


def test_exchange_submission_exception_is_not_retried():
    db = FailingIntentDB()
    submit = Mock(side_effect=TimeoutError("exchange response timed out"))

    try:
        ExecutionBarrier(ContextCache(), db).execute(campaign_intent(), submit)
    except TimeoutError:
        pass
    else:
        raise AssertionError("exchange timeout must be propagated")

    submit.assert_called_once()
    assert db.saved_statuses == ["PENDING", "AMBIGUOUS"]
