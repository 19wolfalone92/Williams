"""Canonical identity rules for Williams-managed Binance Spot orders.

The production campaign executor uses WILLV5_* clientOrderIds.  Older runtime
paths used WILLV4_* and the Android native runtime uses W5*.  Recovery must
recognize all of them so a restart can never classify an older managed order
as foreign.
"""
from __future__ import annotations

from typing import Any

ENTRY_PREFIXES = (
    "WILLV5_ENTRY_",
    "WILLV4_ENTRY_",
    "W5E_",
    "W4B_",
)

PROTECTION_PREFIXES = (
    "WILLV5_STOP_",
    "W5S_",
)

EXIT_PREFIXES = (
    "WILLV5_EXIT_",
    "WILLV4_OCO_",
    "WILLV4_EMERGENCY_",
    "WILLV4_MANUAL_",
    "W5X_",
)

MANAGED_PREFIXES = ENTRY_PREFIXES + PROTECTION_PREFIXES + EXIT_PREFIXES


def client_order_id(order: dict[str, Any] | None) -> str:
    return str((order or {}).get("clientOrderId") or "").strip()


def order_list_client_id(order_list: dict[str, Any] | None) -> str:
    return str((order_list or {}).get("listClientOrderId") or "").strip()


def is_entry_id(value: str) -> bool:
    return str(value or "").startswith(ENTRY_PREFIXES)


def is_protection_id(value: str) -> bool:
    return str(value or "").startswith(PROTECTION_PREFIXES)


def is_exit_id(value: str) -> bool:
    return str(value or "").startswith(EXIT_PREFIXES)


def is_managed_id(value: str) -> bool:
    return str(value or "").startswith(MANAGED_PREFIXES)


def is_managed_order(
    order: dict[str, Any] | None,
    *,
    related_order_lists: list[dict[str, Any]] | None = None,
) -> bool:
    cid = client_order_id(order)
    if is_managed_id(cid):
        return True
    list_id = str((order or {}).get("orderListId") or "").strip()
    if not list_id:
        return False
    for item in related_order_lists or []:
        if (
            str(item.get("orderListId") or "").strip() == list_id
            and is_exit_id(order_list_client_id(item))
        ):
            return True
    return False


def classification(order: dict[str, Any] | None) -> str:
    cid = client_order_id(order)
    if is_entry_id(cid):
        return "ENTRY"
    if is_protection_id(cid):
        return "PROTECTION"
    if is_exit_id(cid):
        return "EXIT"
    return "FOREIGN"
