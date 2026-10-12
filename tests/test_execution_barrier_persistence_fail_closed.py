"""Persistence-failure safety tests for ExecutionBarrier (mocks only)."""
from unittest.mock import Mock

from execution_barrier import ExecutionBarrier, OrderIntent
from market_context import ContextCache


class FailingIntentDB:
    def __init__(self, fail_statuses=()):
        self.fail_statuses = set(fail_statuses)
        self.saved_statuses = []
        self.events = []
        self.states = {"position_state": "FLAT"}
        self.intent_statuses = {}

    def state_get(self, key, default=None):
        return self.states.get(key, default)

    def try_claim_state(self, key, value):
        if key in self.states:
            return False
        self.states[key] = str(value)
        return True

    def has_unresolved_execution_intents(self):
        return False

    def state_set(self, key, value):
        self.states[key] = str(value)

    def has_unresolved_execution_intents(self):
        return any(status in {"PENDING", "AMBIGUOUS"} for status in self.intent_statuses.values())

    def save_execution_intent(self, intent, status, reason):
        self.saved_statuses.append(status)
        if status in self.fail_statuses:
            raise OSError(f"simulated database failure at {status}")
        self.intent_statuses[intent.intent_id] = status

    def log_event(self, *args, **kwargs):
        self.events.append((args, kwargs))

    def save_execution_event(self, *args, **kwargs):
        pass


class DBWithoutIntentPersistence:
    """Represents a legacy DB missing required execution persistence."""

    def state_get(self, key, default=None):
        return default

    def try_claim_state(self, key, value):
        return True

    def has_unresolved_execution_intents(self):
        return False


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


def test_missing_db_blocks_submission_without_claiming_durable_persistence():
    submit = Mock(return_value={"status": "NEW", "executedQty": "0"})
    result = ExecutionBarrier(ContextCache(), None).execute(campaign_intent(), submit)

    assert result.accepted is False
    assert "durable execution-intent persistence is unavailable" in result.reason
    submit.assert_not_called()


def test_db_without_save_method_blocks_submission_without_claiming_durable_persistence():
    submit = Mock(return_value={"status": "NEW", "executedQty": "0"})
    result = ExecutionBarrier(ContextCache(), DBWithoutIntentPersistence()).execute(
        campaign_intent(), submit
    )

    assert result.accepted is False
    assert "durable execution-intent persistence is unavailable" in result.reason
    submit.assert_not_called()


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
    assert "ambiguous_intent_persistence_failed" in result.reason
    assert "mandatory before any retry" in result.reason
    assert result.response is exchange_response
    submit.assert_called_once()
    assert db.saved_statuses == ["PENDING", "SUBMITTED", "AMBIGUOUS"]


def test_exchange_submission_exception_is_not_retried():
    db = FailingIntentDB()
    submit = Mock(side_effect=TimeoutError("exchange response timed out"))

    try:
        ExecutionBarrier(ContextCache(), db).execute(campaign_intent(), submit)
    except RuntimeError as exc:
        assert "submission outcome is ambiguous" in str(exc)
        assert "exchange response timed out" not in str(exc)
    else:
        raise AssertionError("ambiguous submission must be reported without raw transport details")

    submit.assert_called_once()
    assert db.saved_statuses == ["PENDING", "AMBIGUOUS"]


def test_timeout_with_ambiguous_persistence_failure_reports_mandatory_reconciliation():
    db = FailingIntentDB({"AMBIGUOUS"})
    submit = Mock(side_effect=TimeoutError("exchange response timed out"))

    try:
        ExecutionBarrier(ContextCache(), db).execute(campaign_intent(), submit)
    except RuntimeError as exc:
        assert "ambiguous_intent_persistence_failed" in str(exc) and "mandatory before any retry" in str(exc)
        assert "mandatory before any retry" in str(exc)
    else:
        raise AssertionError("failed ambiguity persistence must be explicit")

    submit.assert_called_once()
    assert db.saved_statuses == ["PENDING", "AMBIGUOUS"]


def test_post_submit_persistence_failure_locks_subsequent_execution_until_reconciliation():
    db = FailingIntentDB({"SUBMITTED"})
    submit = Mock(return_value={"status": "NEW", "executedQty": "0", "orderId": 789})
    barrier = ExecutionBarrier(ContextCache(), db)

    first = barrier.execute(campaign_intent(), submit)

    assert first.accepted is False
    assert db.state_get("position_state") == "RECONCILE_REQUIRED"
    submit.assert_called_once()

    second_submit = Mock(return_value={"status": "NEW", "executedQty": "0", "orderId": 790})
    second = barrier.execute(campaign_intent(), second_submit)

    assert second.accepted is False
    assert "unresolved PENDING/AMBIGUOUS execution intent" in second.reason
    second_submit.assert_not_called()



def test_untrusted_exchange_response_persistence_failure_still_locks_retries():
    db = FailingIntentDB({"AMBIGUOUS"})
    submit = Mock(return_value={"orderId": 991})  # no authoritative status
    barrier = ExecutionBarrier(ContextCache(), db)

    try:
        barrier.execute(campaign_intent(), submit)
    except RuntimeError as exc:
        assert "ambiguous_intent_persistence_failed" in str(exc)
        assert "mandatory before any retry" in str(exc)
    else:
        raise AssertionError("non-authoritative exchange response must be rejected")

    assert db.state_get("position_state") == "RECONCILE_REQUIRED"
    submit.assert_called_once()

    retry = Mock(return_value={"status": "NEW", "executedQty": "0", "orderId": 992})
    result = barrier.execute(campaign_intent(), retry)
    assert result.accepted is False
    assert "unresolved PENDING/AMBIGUOUS execution intent" in result.reason
    retry.assert_not_called()


def test_untrusted_exchange_response_marks_reconciliation_even_when_ambiguous_write_fails():
    db = FailingIntentDB({"AMBIGUOUS"})
    submit = Mock(return_value={"orderId": 993})

    try:
        ExecutionBarrier(ContextCache(), db).execute(campaign_intent(), submit)
    except RuntimeError as exc:
        assert "ambiguous_intent_persistence_failed" in str(exc)
    else:
        raise AssertionError("non-authoritative exchange response must be rejected")

    assert db.state_get("position_state") == "RECONCILE_REQUIRED"
    submit.assert_called_once()


def test_duplicate_intent_id_is_claimed_once_and_never_submitted_twice():
    db = FailingIntentDB()
    barrier = ExecutionBarrier(ContextCache(), db)
    intent = campaign_intent()
    first_submit = Mock(return_value={"status": "NEW", "executedQty": "0", "orderId": 1001})
    first = barrier.execute(intent, first_submit)
    assert first.accepted is True
    first_submit.assert_called_once()

    second_submit = Mock(return_value={"status": "NEW", "executedQty": "0", "orderId": 1002})
    second = barrier.execute(intent, second_submit)
    assert second.accepted is False
    assert "duplicate execution intent already claimed" in second.reason
    second_submit.assert_not_called()


def test_state_read_failure_fails_closed_and_records_reconciliation():
    class ReadFailDB(FailingIntentDB):
        def state_get(self, key, default=None):
            raise OSError("simulated state read failure")

    db = ReadFailDB()
    submit = Mock(return_value={"status": "NEW", "executedQty": "0"})
    result = ExecutionBarrier(ContextCache(), db).execute(campaign_intent(), submit)
    assert result.accepted is False
    assert "execution_state_read_failed" in result.reason
    assert db.state_get.__func__ is ReadFailDB.state_get
    assert db.states["position_state"] == "RECONCILE_REQUIRED"
    submit.assert_not_called()


def test_duplicate_claim_survives_barrier_recreation():
    db = FailingIntentDB()
    intent = campaign_intent()
    first_submit = Mock(return_value={"status": "NEW", "executedQty": "0", "orderId": 1003})
    first = ExecutionBarrier(ContextCache(), db).execute(intent, first_submit)
    assert first.accepted is True
    second_submit = Mock(return_value={"status": "NEW", "executedQty": "0", "orderId": 1004})
    second = ExecutionBarrier(ContextCache(), db).execute(intent, second_submit)
    assert second.accepted is False
    second_submit.assert_not_called()



def test_state_write_failure_still_blocks_new_intents_via_unresolved_record():
    class StateWriteFailDB(FailingIntentDB):
        def state_set(self, key, value):
            if key == "position_state" and value == "RECONCILE_REQUIRED":
                raise OSError("simulated state write failure")
            super().state_set(key, value)

    db = StateWriteFailDB({"SUBMITTED", "AMBIGUOUS"})
    submit = Mock(return_value={"status": "NEW", "executedQty": "0", "orderId": 2001})
    first = ExecutionBarrier(ContextCache(), db).execute(campaign_intent(), submit)
    assert first.accepted is False
    assert "reconciliation_state_persistence_failed" in first.reason
    assert db.has_unresolved_execution_intents()

    next_submit = Mock(return_value={"status": "NEW", "executedQty": "0", "orderId": 2002})
    second = ExecutionBarrier(ContextCache(), db).execute(campaign_intent(), next_submit)
    assert second.accepted is False
    assert "unresolved PENDING/AMBIGUOUS execution intent" in second.reason
    next_submit.assert_not_called()


def test_unresolved_intent_read_failure_blocks_submission():
    class ReadFailDB(FailingIntentDB):
        def has_unresolved_execution_intents(self):
            raise OSError("simulated unresolved intent query failure")

    db = ReadFailDB()
    submit = Mock(return_value={"status": "NEW", "executedQty": "0"})
    result = ExecutionBarrier(ContextCache(), db).execute(campaign_intent(), submit)
    assert result.accepted is False
    assert "unresolved_intent_read_failed" in result.reason
    submit.assert_not_called()
