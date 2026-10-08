"""Deterministic exchange-order lifecycle state machine."""
from __future__ import annotations

from enum import Enum


class OrderState(str, Enum):
    NEW = "NEW"
    ADMISSION = "ADMISSION"
    SUBMITTING = "SUBMITTING"
    OPEN = "OPEN"
    PARTIALLY_FILLED = "PARTIALLY_FILLED"
    FILLED = "FILLED"
    CANCEL_PENDING = "CANCEL_PENDING"
    CANCELED = "CANCELED"
    EXPIRED = "EXPIRED"
    REJECTED = "REJECTED"
    AMBIGUOUS = "AMBIGUOUS"
    RECONCILE_REQUIRED = "RECONCILE_REQUIRED"


_ALLOWED = {
    OrderState.NEW: {OrderState.ADMISSION, OrderState.RECONCILE_REQUIRED},
    OrderState.ADMISSION: {OrderState.SUBMITTING, OrderState.CANCELED, OrderState.RECONCILE_REQUIRED},
    OrderState.SUBMITTING: {
        OrderState.OPEN, OrderState.PARTIALLY_FILLED, OrderState.FILLED,
        OrderState.REJECTED, OrderState.EXPIRED, OrderState.AMBIGUOUS,
        OrderState.RECONCILE_REQUIRED,
    },
    OrderState.OPEN: {
        OrderState.PARTIALLY_FILLED, OrderState.FILLED,
        OrderState.CANCEL_PENDING, OrderState.EXPIRED,
        OrderState.RECONCILE_REQUIRED,
    },
    OrderState.PARTIALLY_FILLED: {
        OrderState.PARTIALLY_FILLED, OrderState.FILLED,
        OrderState.CANCEL_PENDING, OrderState.CANCELED,
        OrderState.RECONCILE_REQUIRED,
    },
    OrderState.FILLED: set(),
    OrderState.CANCEL_PENDING: {
        OrderState.CANCELED, OrderState.FILLED,
        OrderState.PARTIALLY_FILLED, OrderState.AMBIGUOUS,
        OrderState.RECONCILE_REQUIRED,
    },
    OrderState.CANCELED: set(),
    OrderState.EXPIRED: set(),
    OrderState.REJECTED: set(),
    OrderState.AMBIGUOUS: {OrderState.RECONCILE_REQUIRED},
    OrderState.RECONCILE_REQUIRED: set(),
}


class OrderStateMachine:
    """Reject illegal state jumps and treat ambiguity as terminal until reconciled."""

    def __init__(self, initial: OrderState = OrderState.NEW) -> None:
        self.state = initial

    def transition(self, target: OrderState) -> None:
        if target == self.state:
            return
        if target not in _ALLOWED.get(self.state, set()):
            raise ValueError(
                f"Invalid order transition {self.state.value} -> {target.value}"
            )
        self.state = target

    def observe_exchange_status(self, status: str, executed_qty: float = 0.0) -> OrderState:
        s = str(status or "").upper()
        executed = float(executed_qty or 0.0)
        if s in {"PENDING_NEW", "NEW"}:
            target = OrderState.OPEN
        elif s == "PARTIALLY_FILLED":
            target = OrderState.PARTIALLY_FILLED
        elif s == "FILLED":
            target = OrderState.FILLED
        elif s == "CANCELED":
            target = OrderState.CANCELED
        elif s == "EXPIRED":
            target = OrderState.EXPIRED
        elif s == "REJECTED":
            target = OrderState.REJECTED
        else:
            self.state = OrderState.RECONCILE_REQUIRED
            return self.state

        if target == OrderState.CANCELED and executed > 0.0:
            target = OrderState.CANCELED
        try:
            self.transition(target)
        except ValueError:
            # Exchange truth can legitimately skip intermediate states after a
            # restart.  Reconciliation, not guesswork, is the safe answer.
            self.state = OrderState.RECONCILE_REQUIRED
        return self.state

    @property
    def terminal(self) -> bool:
        return self.state in {
            OrderState.FILLED,
            OrderState.CANCELED,
            OrderState.EXPIRED,
            OrderState.REJECTED,
            OrderState.RECONCILE_REQUIRED,
        }
