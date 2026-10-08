"""Deterministic exchange-order lifecycle state machine.

The canonical public states are:
PENDING_NEW, NEW, PARTIALLY_FILLED, FILLED, CANCELED, REJECTED,
EXPIRED and UNKNOWN.

UNKNOWN is deliberately recoverable only through authoritative exchange
reconciliation. It is never cleared by a blind local assumption.
"""
from __future__ import annotations

from enum import Enum


class OrderState(str, Enum):
    PENDING_NEW = "PENDING_NEW"
    NEW = "NEW"
    PARTIALLY_FILLED = "PARTIALLY_FILLED"
    FILLED = "FILLED"
    CANCELED = "CANCELED"
    REJECTED = "REJECTED"
    EXPIRED = "EXPIRED"
    UNKNOWN = "UNKNOWN"

    # Backward-compatible aliases for older campaign/runtime code. New code
    # must use the canonical names above.
    ADMISSION = "PENDING_NEW"
    SUBMITTING = "PENDING_NEW"
    OPEN = "NEW"
    AMBIGUOUS = "UNKNOWN"
    RECONCILE_REQUIRED = "UNKNOWN"


_AUTHORITATIVE = {
    OrderState.NEW,
    OrderState.PARTIALLY_FILLED,
    OrderState.FILLED,
    OrderState.CANCELED,
    OrderState.REJECTED,
    OrderState.EXPIRED,
}

_ALLOWED = {
    OrderState.NEW: {OrderState.PENDING_NEW, OrderState.UNKNOWN},
    OrderState.PENDING_NEW: _AUTHORITATIVE | {OrderState.UNKNOWN},
    OrderState.PARTIALLY_FILLED: {
        OrderState.PARTIALLY_FILLED,
        OrderState.FILLED,
        OrderState.CANCELED,
        OrderState.UNKNOWN,
    },
    OrderState.FILLED: set(),
    OrderState.CANCELED: set(),
    OrderState.REJECTED: set(),
    OrderState.EXPIRED: set(),
    OrderState.UNKNOWN: _AUTHORITATIVE | {OrderState.UNKNOWN},
}


class OrderStateMachine:
    """Reject illegal jumps and make UNKNOWN explicitly reconciliation-bound."""

    def __init__(self, initial: OrderState = OrderState.NEW) -> None:
        self.state = initial

    def transition(self, target: OrderState) -> None:
        if not isinstance(target, OrderState):
            target = OrderState(target)
        if target == self.state:
            return
        if target not in _ALLOWED.get(self.state, set()):
            raise ValueError(
                f"Invalid order transition {self.state.value} -> {target.value}"
            )
        self.state = target

    def mark_unknown(self) -> OrderState:
        self.transition(OrderState.UNKNOWN)
        return self.state

    def observe_exchange_status(
        self,
        status: str,
        executed_qty: float = 0.0,
    ) -> OrderState:
        s = str(status or "").upper().strip()
        executed = float(executed_qty or 0.0)

        if s in {"PENDING_NEW"}:
            target = OrderState.PENDING_NEW
        elif s == "NEW":
            target = OrderState.NEW
        elif s == "PARTIALLY_FILLED":
            target = OrderState.PARTIALLY_FILLED
        elif s == "FILLED":
            target = OrderState.FILLED
        elif s == "CANCELED":
            # A cancel can still report cumulative fills; keep the exchange
            # terminal order state while preserving the fill data elsewhere.
            target = OrderState.CANCELED
        elif s == "EXPIRED":
            target = OrderState.EXPIRED
        elif s == "REJECTED":
            target = OrderState.REJECTED
        else:
            self.state = OrderState.UNKNOWN
            return self.state

        try:
            self.transition(target)
        except ValueError:
            # Exchange truth can legitimately skip local intermediate states
            # after restart. If the target is authoritative, reconciliation
            # may correct UNKNOWN; illegal local inference never may.
            self.state = OrderState.UNKNOWN
        return self.state

    def reconcile(self, status: str, executed_qty: float = 0.0) -> OrderState:
        """Apply authoritative REST/WebSocket state after UNKNOWN."""
        if self.state != OrderState.UNKNOWN:
            return self.observe_exchange_status(status, executed_qty)
        return self.observe_exchange_status(status, executed_qty)

    @property
    def terminal(self) -> bool:
        return self.state in {
            OrderState.FILLED,
            OrderState.CANCELED,
            OrderState.EXPIRED,
            OrderState.REJECTED,
            OrderState.UNKNOWN,
        }