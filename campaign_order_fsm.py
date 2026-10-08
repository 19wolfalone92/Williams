"""Canonical 22-state campaign lifecycle FSM.

This FSM is separate from the exchange OrderStateMachine.
RECONCILIATION_REQUIRED and FAULT are global interrupt states.
"""
from __future__ import annotations

from enum import Enum


class CampaignOrderState(str, Enum):
    NO_IDEA = "NO_IDEA"
    CONTEXT_FORMING = "CONTEXT_FORMING"
    CONTEXT_READY = "CONTEXT_READY"
    SETUP_FORMING = "SETUP_FORMING"
    SETUP_IDENTIFIED = "SETUP_IDENTIFIED"
    ARMED = "ARMED"
    TRIGGERED = "TRIGGERED"
    ENTRY_PENDING = "ENTRY_PENDING"
    PARTIALLY_FILLED = "PARTIALLY_FILLED"
    INITIAL_POSITION = "INITIAL_POSITION"
    PROTECTED = "PROTECTED"
    MOMENTUM_CONFIRMED = "MOMENTUM_CONFIRMED"
    EXPANSION_ELIGIBLE = "EXPANSION_ELIGIBLE"
    EXPANSION_PENDING = "EXPANSION_PENDING"
    CAMPAIGN_ACTIVE = "CAMPAIGN_ACTIVE"
    EXHAUSTION_WARNING = "EXHAUSTION_WARNING"
    REDUCTION = "REDUCTION"
    EXIT_PENDING = "EXIT_PENDING"
    EXIT_PARTIAL = "EXIT_PARTIAL"
    CLOSED = "CLOSED"
    RECONCILIATION_REQUIRED = "RECONCILIATION_REQUIRED"
    FAULT = "FAULT"


_INTERRUPT = {
    CampaignOrderState.RECONCILIATION_REQUIRED,
    CampaignOrderState.FAULT,
}


_ALLOWED = {
    CampaignOrderState.NO_IDEA: {
        CampaignOrderState.CONTEXT_FORMING,
        CampaignOrderState.CONTEXT_READY,
        CampaignOrderState.SETUP_FORMING,
        CampaignOrderState.SETUP_IDENTIFIED,
    },
    CampaignOrderState.CONTEXT_FORMING: {
        CampaignOrderState.CONTEXT_READY,
        CampaignOrderState.SETUP_FORMING,
    },
    CampaignOrderState.CONTEXT_READY: {
        CampaignOrderState.SETUP_FORMING,
        CampaignOrderState.SETUP_IDENTIFIED,
    },
    CampaignOrderState.SETUP_FORMING: {
        CampaignOrderState.SETUP_IDENTIFIED,
        CampaignOrderState.CONTEXT_FORMING,
    },
    CampaignOrderState.SETUP_IDENTIFIED: {
        CampaignOrderState.ARMED,
        CampaignOrderState.SETUP_FORMING,
    },
    CampaignOrderState.ARMED: {
        CampaignOrderState.TRIGGERED,
        CampaignOrderState.ENTRY_PENDING,
        CampaignOrderState.CLOSED,
    },
    CampaignOrderState.TRIGGERED: {
        CampaignOrderState.ENTRY_PENDING,
        CampaignOrderState.PARTIALLY_FILLED,
        CampaignOrderState.INITIAL_POSITION,
    },
    CampaignOrderState.ENTRY_PENDING: {
        CampaignOrderState.TRIGGERED,
        CampaignOrderState.PARTIALLY_FILLED,
        CampaignOrderState.INITIAL_POSITION,
        CampaignOrderState.CLOSED,
    },
    CampaignOrderState.PARTIALLY_FILLED: {
        CampaignOrderState.PARTIALLY_FILLED,
        CampaignOrderState.INITIAL_POSITION,
        CampaignOrderState.REDUCTION,
        CampaignOrderState.EXIT_PENDING,
    },
    CampaignOrderState.INITIAL_POSITION: {
        CampaignOrderState.PROTECTED,
        CampaignOrderState.MOMENTUM_CONFIRMED,
        CampaignOrderState.EXHAUSTION_WARNING,
        CampaignOrderState.EXIT_PENDING,
        CampaignOrderState.CAMPAIGN_ACTIVE,
    },
    CampaignOrderState.PROTECTED: {
        CampaignOrderState.MOMENTUM_CONFIRMED,
        CampaignOrderState.EXPANSION_ELIGIBLE,
        CampaignOrderState.EXHAUSTION_WARNING,
        CampaignOrderState.EXIT_PENDING,
        CampaignOrderState.CAMPAIGN_ACTIVE,
    },
    CampaignOrderState.MOMENTUM_CONFIRMED: {
        CampaignOrderState.EXPANSION_ELIGIBLE,
        CampaignOrderState.CAMPAIGN_ACTIVE,
        CampaignOrderState.EXHAUSTION_WARNING,
        CampaignOrderState.EXIT_PENDING,
    },
    CampaignOrderState.EXPANSION_ELIGIBLE: {
        CampaignOrderState.EXPANSION_PENDING,
        CampaignOrderState.CAMPAIGN_ACTIVE,
        CampaignOrderState.EXHAUSTION_WARNING,
        CampaignOrderState.EXIT_PENDING,
    },
    CampaignOrderState.EXPANSION_PENDING: {
        CampaignOrderState.PARTIALLY_FILLED,
        CampaignOrderState.CAMPAIGN_ACTIVE,
        CampaignOrderState.PROTECTED,
        CampaignOrderState.EXIT_PENDING,
    },
    CampaignOrderState.CAMPAIGN_ACTIVE: {
        CampaignOrderState.EXPANSION_ELIGIBLE,
        CampaignOrderState.MOMENTUM_CONFIRMED,
        CampaignOrderState.EXHAUSTION_WARNING,
        CampaignOrderState.REDUCTION,
        CampaignOrderState.EXIT_PENDING,
    },
    CampaignOrderState.EXHAUSTION_WARNING: {
        CampaignOrderState.REDUCTION,
        CampaignOrderState.EXIT_PENDING,
        CampaignOrderState.CAMPAIGN_ACTIVE,
    },
    CampaignOrderState.REDUCTION: {
        CampaignOrderState.CAMPAIGN_ACTIVE,
        CampaignOrderState.EXIT_PENDING,
        CampaignOrderState.EXIT_PARTIAL,
        CampaignOrderState.CLOSED,
    },
    CampaignOrderState.EXIT_PENDING: {
        CampaignOrderState.EXIT_PARTIAL,
        CampaignOrderState.CLOSED,
        CampaignOrderState.RECONCILIATION_REQUIRED,
    },
    CampaignOrderState.EXIT_PARTIAL: {
        CampaignOrderState.EXIT_PARTIAL,
        CampaignOrderState.CAMPAIGN_ACTIVE,
        CampaignOrderState.EXIT_PENDING,
        CampaignOrderState.CLOSED,
    },
    CampaignOrderState.CLOSED: set(),
    CampaignOrderState.RECONCILIATION_REQUIRED: set(),
    CampaignOrderState.FAULT: set(),
}


class CampaignOrderStateMachine:
    """Strict lifecycle with explicit interruption semantics."""

    def __init__(self, initial: CampaignOrderState = CampaignOrderState.NO_IDEA) -> None:
        self.state = initial if isinstance(initial, CampaignOrderState) else CampaignOrderState(initial)

    def transition(self, target: CampaignOrderState) -> None:
        target = target if isinstance(target, CampaignOrderState) else CampaignOrderState(target)
        if target == self.state:
            return
        if target in _INTERRUPT:
            if self.state in {
                CampaignOrderState.CLOSED,
                CampaignOrderState.FAULT,
            }:
                raise ValueError(
                    f"terminal campaign state cannot transition to {target.value}"
                )
            self.state = target
            return
        if self.state in _INTERRUPT:
            raise ValueError(
                f"Campaign FSM requires reconciliation before {target.value}; current={self.state.value}"
            )
        if target not in _ALLOWED.get(self.state, set()):
            raise ValueError(
                f"Invalid campaign transition {self.state.value} -> {target.value}"
            )
        self.state = target

    def require_reconciliation(self, reason: str = "") -> None:
        self.state = CampaignOrderState.RECONCILIATION_REQUIRED

    def fault(self, reason: str = "") -> None:
        if self.state in {
            CampaignOrderState.CLOSED,
            CampaignOrderState.FAULT,
        }:
            raise ValueError("campaign cannot transition to FAULT from terminal state")
        self.state = CampaignOrderState.FAULT

    def reconcile_to(self, target: CampaignOrderState) -> None:
        if self.state is CampaignOrderState.FAULT:
            raise ValueError("FAULT requires explicit manual recovery")
        if self.state is not CampaignOrderState.RECONCILIATION_REQUIRED:
            raise ValueError("reconcile_to requires RECONCILIATION_REQUIRED")
        target = target if isinstance(target, CampaignOrderState) else CampaignOrderState(target)
        if target in _INTERRUPT:
            raise ValueError("reconcile_to target must be operational")
        self.state = target

    @property
    def interrupted(self) -> bool:
        return self.state in _INTERRUPT

    @property
    def terminal(self) -> bool:
        return self.state in {CampaignOrderState.CLOSED, CampaignOrderState.FAULT}


def campaign_order_transition_allowed(current: CampaignOrderState, nxt: CampaignOrderState) -> bool:
    if current == nxt:
        return True
    if nxt in _INTERRUPT:
        return current not in {
            CampaignOrderState.CLOSED,
            CampaignOrderState.FAULT,
        }
    if current in _INTERRUPT:
        return False
    return nxt in _ALLOWED.get(current, set())