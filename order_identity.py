"""Canonical identity rules for Williams-managed Binance Spot orders.

The production campaign executor uses WILLV5_* clientOrderIds.  Older runtime
paths used WILLV4_* and the Android native runtime uses W5*.  Recovery must
recognize all of them so a restart can never classify an older managed order
as foreign.
"""
from __future__ import annotations

from typing import Any
import hashlib
import re

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


def deterministic_client_order_id(
    kind: str,
    *parts: object,
    prefix: str = "WILLV5",
    digest_chars: int = 16,
) -> str:
    """Build a stable, compact Binance clientOrderId for one logical mutation.

    The same logical operation produces the same ID across process restarts.
    A different operation key (stage, target order, stop level, quantity, etc.)
    produces a different ID. The returned value is intentionally <= 36 chars.
    """
    clean_kind = re.sub(r"[^A-Z0-9]", "_", str(kind).upper())[:8] or "ORDER"
    clean_prefix = re.sub(r"[^A-Z0-9]", "", str(prefix).upper())[:8] or "WILLV5"
    material = "|".join(str(part) for part in parts)
    digest = hashlib.sha256(material.encode("utf-8")).hexdigest()[
        : max(8, min(int(digest_chars), 20))
    ]
    value = f"{clean_prefix}_{clean_kind}_{digest}"
    return value[:36]




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
