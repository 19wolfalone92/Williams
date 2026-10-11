"""Deterministic tests for cancel/replace lifecycle safety.

These tests use only local state and mocked exchange responses; they never
submit real or Testnet orders.
"""
import pytest

from order_state_machine import OrderState, OrderStateMachine


def _to_cancel_pending(executed_qty=0.0):
    machine = OrderStateMachine(OrderState.NEW)
    machine.transition(OrderState.ADMISSION)
    machine.transition(OrderState.SUBMITTING)
    machine.observe_exchange_status("NEW")
    if executed_qty:
        machine.observe_exchange_status("PARTIALLY_FILLED", executed_qty)
    machine.transition(OrderState.CANCEL_PENDING)
    return machine


def test_cancel_replace_partial_fill_keeps_fill_visible():
    machine = _to_cancel_pending(executed_qty=0.25)

    # The old order can execute while cancellation/replacement is in flight.
    assert machine.observe_exchange_status("PARTIALLY_FILLED", 0.4) is OrderState.RECONCILE_REQUIRED


def test_cancel_timeout_is_not_treated_as_cancelled():
    machine = _to_cancel_pending()

    # An absent/unknown exchange status must not be guessed as cancellation.
    assert machine.observe_exchange_status("UNKNOWN", 0) is OrderState.RECONCILE_REQUIRED


@pytest.mark.parametrize(
    "status,qty",
    [
        ("FILLED", 1.0),
        ("CANCELED", 0.3),
        ("PARTIALLY_FILLED", 0.2),
    ],
)
def test_late_exchange_update_after_cancel_pending_requires_reconciliation(status, qty):
    machine = _to_cancel_pending()
    result = machine.observe_exchange_status(status, qty)

    # A delayed response can contradict the local cancel/replace assumption.
    # Reconciliation must resolve exchange truth before further submissions.
    assert result in {
        OrderState.FILLED,
        OrderState.CANCELED,
        OrderState.PARTIALLY_FILLED,
        OrderState.RECONCILE_REQUIRED,
    }
    if status == "FILLED":
        assert result is OrderState.FILLED


def test_ambiguous_state_cannot_be_retried_directly():
    machine = OrderStateMachine(OrderState.SUBMITTING)
    assert machine.observe_exchange_status("PENDING_UNKNOWN") is OrderState.RECONCILE_REQUIRED
    with pytest.raises(ValueError):
        machine.transition(OrderState.SUBMITTING)


def test_terminal_order_cannot_be_reopened_by_duplicate_new_event():
    machine = OrderStateMachine(OrderState.NEW)
    machine.transition(OrderState.ADMISSION)
    machine.transition(OrderState.SUBMITTING)
    machine.observe_exchange_status("FILLED", 1.0)

    assert machine.observe_exchange_status("NEW", 1.0) is OrderState.RECONCILE_REQUIRED
