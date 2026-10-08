"""Canonical order lifecycle state machine for exchange mutations."""
from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
import time


class OrderLifecycleState(str, Enum):
    CREATED = "CREATED"
    ADMISSION = "ADMISSION"
    BLOCKED = "BLOCKED"
    SUBMISSION_PENDING = "SUBMISSION_PENDING"
    SUBMITTED = "SUBMITTED"
    NEW = "NEW"
    PARTIALLY_FILLED = "PARTIALLY_FILLED"
    FILLED = "FILLED"
    CANCEL_REQUESTED = "CANCEL_REQUESTED"
    CANCELED = "CANCELED"
    EXPIRED = "EXPIRED"
    EXPIRED_IN_MATCH = "EXPIRED_IN_MATCH"
    REJECTED = "REJECTED"
    REPLACEMENT_REQUESTED = "REPLACEMENT_REQUESTED"
    REPLACED = "REPLACED"
    AMBIGUOUS = "AMBIGUOUS"
    RECONCILE_REQUIRED = "RECONCILE_REQUIRED"


_ALLOWED: dict[OrderLifecycleState, set[OrderLifecycleState]] = {
    OrderLifecycleState.CREATED: {OrderLifecycleState.ADMISSION},
    OrderLifecycleState.ADMISSION: {
        OrderLifecycleState.BLOCKED,
        OrderLifecycleState.SUBMISSION_PENDING,
    },
    OrderLifecycleState.BLOCKED: set(),
    OrderLifecycleState.SUBMISSION_PENDING: {
        OrderLifecycleState.SUBMITTED,
        OrderLifecycleState.AMBIGUOUS,
        OrderLifecycleState.RECONCILE_REQUIRED,
    },
    OrderLifecycleState.SUBMITTED: {
        OrderLifecycleState.NEW,
        OrderLifecycleState.PARTIALLY_FILLED,
        OrderLifecycleState.FILLED,
        OrderLifecycleState.CANCELED,
        OrderLifecycleState.EXPIRED,
        OrderLifecycleState.EXPIRED_IN_MATCH,
        OrderLifecycleState.REJECTED,
        OrderLifecycleState.REPLACEMENT_REQUESTED,
        OrderLifecycleState.CANCEL_REQUESTED,
        OrderLifecycleState.AMBIGUOUS,
        OrderLifecycleState.RECONCILE_REQUIRED,
    },
    OrderLifecycleState.NEW: {
        OrderLifecycleState.PARTIALLY_FILLED,
        OrderLifecycleState.FILLED,
        OrderLifecycleState.CANCEL_REQUESTED,
        OrderLifecycleState.REPLACEMENT_REQUESTED,
        OrderLifecycleState.CANCELED,
        OrderLifecycleState.EXPIRED,
        OrderLifecycleState.EXPIRED_IN_MATCH,
        OrderLifecycleState.REJECTED,
        OrderLifecycleState.AMBIGUOUS,
        OrderLifecycleState.RECONCILE_REQUIRED,
    },
    OrderLifecycleState.PARTIALLY_FILLED: {
        OrderLifecycleState.FILLED,
        OrderLifecycleState.CANCEL_REQUESTED,
        OrderLifecycleState.CANCELED,
        OrderLifecycleState.EXPIRED,
        OrderLifecycleState.EXPIRED_IN_MATCH,
        OrderLifecycleState.REPLACEMENT_REQUESTED,
        OrderLifecycleState.AMBIGUOUS,
        OrderLifecycleState.RECONCILE_REQUIRED,
    },
    OrderLifecycleState.FILLED: set(),
    OrderLifecycleState.CANCEL_REQUESTED: {
        OrderLifecycleState.CANCELED,
        OrderLifecycleState.PARTIALLY_FILLED,
        OrderLifecycleState.FILLED,
        OrderLifecycleState.AMBIGUOUS,
        OrderLifecycleState.RECONCILE_REQUIRED,
    },
    OrderLifecycleState.CANCELED: set(),
    OrderLifecycleState.EXPIRED: set(),
    OrderLifecycleState.EXPIRED_IN_MATCH: set(),
    OrderLifecycleState.REJECTED: set(),
    OrderLifecycleState.REPLACEMENT_REQUESTED: {
        OrderLifecycleState.REPLACED,
        OrderLifecycleState.NEW,
        OrderLifecycleState.AMBIGUOUS,
        OrderLifecycleState.RECONCILE_REQUIRED,
    },
    OrderLifecycleState.REPLACED: {
        OrderLifecycleState.NEW,
        OrderLifecycleState.PARTIALLY_FILLED,
        OrderLifecycleState.FILLED,
        OrderLifecycleState.AMBIGUOUS,
        OrderLifecycleState.RECONCILE_REQUIRED,
    },
    OrderLifecycleState.AMBIGUOUS: {OrderLifecycleState.RECONCILE_REQUIRED},
    OrderLifecycleState.RECONCILE_REQUIRED: set(),
}


@dataclass
class OrderStateMachine:
    intent_id: str
    state: OrderLifecycleState = OrderLifecycleState.CREATED
    history: list[dict] = field(default_factory=list)

    def transition(self, next_state: OrderLifecycleState | str, *, reason: str = "") -> OrderLifecycleState:
        nxt = OrderLifecycleState(next_state)
        if nxt != self.state and nxt not in _ALLOWED[self.state]:
            raise ValueError(
                f"Invalid order transition {self.state.value} -> {nxt.value}"
            )
        now = int(time.time() * 1000)
        self.history.append({
            "at_ms": now,
            "from": self.state.value,
            "to": nxt.value,
            "reason": str(reason),
        })
        self.state = nxt
        return self.state

    def mark_blocked(self, reason: str) -> None:
        self.transition(OrderLifecycleState.BLOCKED, reason=reason)

    def mark_submission_pending(self) -> None:
        self.transition(OrderLifecycleState.SUBMISSION_PENDING)

    def mark_ambiguous(self, reason: str) -> None:
        if self.state != OrderLifecycleState.AMBIGUOUS:
            self.transition(OrderLifecycleState.AMBIGUOUS, reason=reason)

    def mark_reconcile_required(self, reason: str) -> None:
        if self.state == OrderLifecycleState.AMBIGUOUS:
            self.transition(OrderLifecycleState.RECONCILE_REQUIRED, reason=reason)
        elif self.state != OrderLifecycleState.RECONCILE_REQUIRED:
            self.transition(OrderLifecycleState.RECONCILE_REQUIRED, reason=reason)

    @staticmethod
    def state_for_exchange_status(status: str) -> OrderLifecycleState:
        value = str(status or "").upper()
        mapping = {
            "NEW": OrderLifecycleState.NEW,
            "PENDING_NEW": OrderLifecycleState.NEW,
            "PARTIALLY_FILLED": OrderLifecycleState.PARTIALLY_FILLED,
            "FILLED": OrderLifecycleState.FILLED,
            "CANCELED": OrderLifecycleState.CANCELED,
            "EXPIRED": OrderLifecycleState.EXPIRED,
            "EXPIRED_IN_MATCH": OrderLifecycleState.EXPIRED_IN_MATCH,
            "REJECTED": OrderLifecycleState.REJECTED,
            "PENDING_CANCEL": OrderLifecycleState.CANCEL_REQUESTED,
            "PENDING_REPLACE": OrderLifecycleState.REPLACEMENT_REQUESTED,
        }
        return mapping.get(value, OrderLifecycleState.RECONCILE_REQUIRED)

    def record_exchange_status(self, status: str) -> OrderLifecycleState:
        nxt = self.state_for_exchange_status(status)
        if nxt == self.state:
            return self.state
        if nxt not in _ALLOWED[self.state]:
            self.state = OrderLifecycleState.RECONCILE_REQUIRED
            self.history.append({
                "at_ms": int(time.time() * 1000),
                "from": self.state.value,
                "to": OrderLifecycleState.RECONCILE_REQUIRED.value,
                "reason": f"exchange status {status} violates local lifecycle",
            })
            return self.state
        return self.transition(nxt, reason=f"exchange status={str(status).upper()}")

    def to_dict(self) -> dict:
        return {
            "intent_id": self.intent_id,
            "state": self.state.value,
            "history": list(self.history),
        }
