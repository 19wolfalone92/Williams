"""Canonical exchange-outcome to recovery-action matrix."""
from __future__ import annotations

from enum import Enum
from typing import Optional


class RecoveryAction(str, Enum):
    WAIT = "WAIT"
    ADOPT_FILL = "ADOPT_FILL"
    CANCEL_AND_PROTECT = "CANCEL_AND_PROTECT"
    RELEASE_RESERVATION = "RELEASE_RESERVATION"
    RECONCILE_REQUIRED = "RECONCILE_REQUIRED"


class RecoveryMatrix:
    """Single policy table used by restart/ambiguous-order recovery.

    This matrix intentionally covers exchange statuses that are relevant to
    Spot conditional entries, including Binance's EXPIRED_IN_MATCH STP status.
    """

    PENDING_ENTRY: dict[str, RecoveryAction] = {
        "NEW": RecoveryAction.WAIT,
        "PENDING_NEW": RecoveryAction.WAIT,
        "PARTIALLY_FILLED": RecoveryAction.CANCEL_AND_PROTECT,
        "FILLED": RecoveryAction.ADOPT_FILL,
        "CANCELED": RecoveryAction.RELEASE_RESERVATION,
        "EXPIRED": RecoveryAction.RELEASE_RESERVATION,
        "REJECTED": RecoveryAction.RELEASE_RESERVATION,
        "EXPIRED_IN_MATCH": RecoveryAction.RELEASE_RESERVATION,
    }

    @classmethod
    def action_for(
        cls,
        *,
        lifecycle: str,
        exchange_status: str,
        executed_qty: float = 0.0,
    ) -> RecoveryAction:
        status = str(exchange_status or "").upper()
        if lifecycle in {"ENTRY_PENDING", "ENTRY_ARMING", "SIGNAL_DETECTED", "ADD_ON_PENDING", "ADD_ON_ARMING"}:
            return cls.PENDING_ENTRY.get(status, RecoveryAction.RECONCILE_REQUIRED)
        return RecoveryAction.RECONCILE_REQUIRED

    @classmethod
    def explain(
        cls,
        *,
        lifecycle: str,
        exchange_status: str,
        executed_qty: float = 0.0,
    ) -> dict:
        action = cls.action_for(
            lifecycle=lifecycle,
            exchange_status=exchange_status,
            executed_qty=executed_qty,
        )
        return {
            "lifecycle": lifecycle,
            "exchange_status": str(exchange_status or "").upper(),
            "executed_qty": float(executed_qty or 0.0),
            "action": action.value,
        }
