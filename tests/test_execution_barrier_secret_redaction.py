"""Secret-redaction regression tests for the execution boundary; mock-only."""
from unittest.mock import Mock

import pytest

from execution_barrier import ExecutionBarrier, OrderIntent
from market_context import ContextCache


SYNTHETIC_VALUES = (
    "TEST_API_KEY_000000000000",
    "TEST_API_SECRET_111111111111",
    "signature=TEST_SIGNATURE_222222222222",
    "Authorization: Bearer TEST_AUTH_333333333333",
    "token=TEST_QUERY_444444444444",
    "TEST_HTTP_BODY_SECRET_555555555555",
)


class MemoryDB:
    def __init__(self):
        self.states = {"position_state": "FLAT"}
        self.intents = {}
        self.events = []

    def state_get(self, key, default=None):
        return self.states.get(key, default)

    def state_set(self, key, value):
        self.states[key] = value

    def try_claim_state(self, key, value):
        if key in self.states:
            return False
        self.states[key] = value
        return True

    def has_unresolved_execution_intents(self):
        return any(s in {"PENDING", "AMBIGUOUS"} for s in self.intents.values())

    def save_execution_intent(self, intent, status, reason="", exchange_order=None):
        self.intents[intent.intent_id] = status
        self.reasons = getattr(self, "reasons", [])
        self.reasons.append(reason)

    def save_execution_event(self, *args, **kwargs):
        pass

    def log_event(self, *args, **kwargs):
        self.events.append((args, kwargs))


def intent():
    return OrderIntent.new(
        symbol="BTCUSDT",
        side="BUY",
        order_type="LIMIT",
        required_context_versions={},
        purpose="CAMPAIGN_ENTRY",
    )


@pytest.mark.parametrize("secret", SYNTHETIC_VALUES)
def test_ambiguous_submit_never_persists_or_returns_raw_exception(secret):
    db = MemoryDB()
    submit = Mock(side_effect=TimeoutError(f"HTTP request failed: {secret}"))
    barrier = ExecutionBarrier(ContextCache(), db)

    with pytest.raises(RuntimeError) as caught:
        barrier.execute(intent(), submit)

    assert "submission outcome is ambiguous" in str(caught.value)
    assert secret not in str(caught.value)
    assert db.intents
    assert set(db.intents.values()) == {"AMBIGUOUS"}
    assert db.states["position_state"] == "RECONCILE_REQUIRED"
    assert all(secret not in str(reason) for reason in getattr(db, "reasons", []))
    assert all(secret not in repr(event) for event in db.events)
    submit.assert_called_once()


def test_ambiguous_submit_blocks_followup_mutation_without_blind_retry():
    db = MemoryDB()
    first_submit = Mock(side_effect=TimeoutError("signature=TEST_SIGNATURE_222222222222"))
    barrier = ExecutionBarrier(ContextCache(), db)

    with pytest.raises(RuntimeError, match="submission outcome is ambiguous"):
        barrier.execute(intent(), first_submit)

    second_submit = Mock(return_value={"status": "NEW", "executedQty": "0"})
    result = barrier.execute(intent(), second_submit)

    assert result.accepted is False
    assert "unresolved PENDING/AMBIGUOUS" in result.reason
    first_submit.assert_called_once()
    second_submit.assert_not_called()
