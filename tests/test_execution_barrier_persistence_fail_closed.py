"""Persistence-failure safety tests for ExecutionBarrier (mocks only)."""
from unittest.mock import Mock

from execution_barrier import ExecutionBarrier, OrderIntent
from market_context import ContextCache


class FailingIntentDB:
    def __init__(self, fail_status):
        self.fail_status = fail_status
        self.saved_statuses = []
        self.events = []

    def save_execution_intent(self, intent, status, reason):
        self.saved_statuses.append(status)
        if status == self.fail_status:
            raise OSError(f"simulated database failure at {status}")

    def log_event(self, *args, **kwargs):
        self.events.append((args, kwargs))

    def save_execution_event(self, *args, **kwargs):
        pass


def campaign_intent():
    return OrderIntent.new(
        symbol="BTCUSDT",
        side="BUY",
        order_type="LIMIT",
        required_context_versions={},
        purpose="CAMPAIGN_ENTRY",
    )


def test_pending_persistence_failure_blocks_submit():
    db = FailingIntentDB("PENDING")
    submit = Mock(return_value={"status": "NEW", "executedQty": "0"})
    barrier = ExecutionBarrier(ContextCache(), db)

    result = barrier.execute(campaign_intent(), submit)

    assert result.accepted is False
    assert "execution_intent_persistence_failed" in result.reason
    submit.assert_not_called()
    assert db.saved_statuses == ["PENDING"]


def test_post_submit_persistence_failure_requires_reconciliation_without_retry():
    db = FailingIntentDB("SUBMITTED")
    submit = Mock(return_value={"status": "NEW", "executedQty": "0", "orderId": 123})
    barrier = ExecutionBarrier(ContextCache(), db)

    result = barrier.execute(campaign_intent(), submit)

    assert result.accepted is False
    assert "post_submit_persistence_failed" in result.reason
    assert result.response["orderId"] == 123
    submit.assert_called_once()
    assert db.saved_statuses == ["PENDING", "SUBMITTED", "AMBIGUOUS"]
    assert any(args[1] == "execution_reconcile_required" for args, _ in db.events)
