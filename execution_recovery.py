"""Fail-closed exchange reconciliation for durable execution intents.

This module performs reads only. It never retries or submits an exchange mutation.
An intent is resolved only when the exact Binance order/list identity and a known
exchange state are observed and that evidence is durably committed with the
resolution event. Missing/ambiguous exchange evidence leaves the intent blocked.
"""
from __future__ import annotations

from typing import Any


_ORDER_STATES = {
    "NEW", "PARTIALLY_FILLED", "FILLED", "CANCELED", "REJECTED",
    "EXPIRED", "EXPIRED_IN_MATCH",
}
_LIST_STATES = {"EXECUTING", "ALL_DONE", "REJECT"}
_UNRESOLVED_STATES = {"PENDING", "AMBIGUOUS", "SUBMITTING", "RECONCILE_REQUIRED"}


class ExecutionRecoveryResolver:
    """Resolve durable intent outcomes against exact exchange identities."""

    def __init__(self, db, client):
        self.db = db
        self.client = client

    @staticmethod
    def _text(value: Any) -> str:
        return str(value if value is not None else "").strip()

    @staticmethod
    def _known_order(row: Any, symbol: str, client_order_id: str = "", order_id: str = "") -> bool:
        if not isinstance(row, dict):
            return False
        if str(row.get("symbol", "")).upper() != symbol.upper():
            return False
        status = str(row.get("status", "")).upper()
        if status not in _ORDER_STATES or row.get("orderId") is None:
            return False
        if client_order_id and str(row.get("clientOrderId", "")) != client_order_id:
            return False
        if order_id and str(row.get("orderId", "")) != order_id:
            return False
        return True

    def _get_order(self, symbol: str, *, order_id: str = "", client_order_id: str = ""):
        if not order_id and not client_order_id:
            return None
        # Binance accepts orderId OR origClientOrderId. Prefer the numeric
        # exchange order ID when known, then independently validate both IDs.
        lookup_cid = client_order_id if not order_id else ""
        row = self.client.get_order(
            symbol,
            order_id=order_id if order_id else None,
            orig_client_order_id=lookup_cid if lookup_cid else None,
        )
        if self._known_order(row, symbol, client_order_id, order_id):
            return row
        return None

    def _lookup(self, intent: dict[str, Any]) -> tuple[str, list[dict[str, Any]], dict[str, Any]] | None:
        symbol = self._text(intent.get("symbol")).upper()
        cid = self._text(intent.get("client_order_id"))
        kind = self._text(intent.get("mutation_kind") or "ORDER").upper()
        target_order_id = self._text(intent.get("target_order_id"))
        target_client_id = self._text(intent.get("target_client_order_id"))
        target_list_id = self._text(intent.get("target_order_list_id"))
        target_list_cid = self._text(intent.get("target_list_client_order_id"))
        replacement_cid = self._text(intent.get("replacement_client_order_id"))

        if not symbol:
            return None

        if kind in {"ORDER", "EMERGENCY_EXIT"}:
            row = self._get_order(symbol, client_order_id=cid)
            if row is None:
                return None
            return "RECONCILED", [row], {
                "mutation_kind": kind, "exchange_status": row["status"],
                "order_id": str(row["orderId"]), "client_order_id": cid,
            }

        if kind == "OCO_CREATE":
            list_cid = target_list_cid or cid
            if not list_cid:
                return None
            payload = self.client.order_list(
                symbol,
                order_list_id=target_list_id or None,
                list_client_order_id=list_cid if not target_list_id else None,
            )
            if not isinstance(payload, dict):
                return None
            if str(payload.get("symbol", "")).upper() != symbol:
                return None
            if str(payload.get("listClientOrderId", "")) != list_cid:
                return None
            if payload.get("orderListId") is None:
                return None
            reports = payload.get("orders") or payload.get("orderReports") or []
            if len(reports) < 2:
                # A list header without authoritative leg identities is insufficient.
                return None
            rows = []
            for item in reports:
                order_id = self._text(item.get("orderId"))
                leg_cid = self._text(item.get("clientOrderId"))
                row = self._get_order(symbol, order_id=order_id, client_order_id=leg_cid)
                if row is None:
                    return None
                row = dict(row)
                row["orderListId"] = payload["orderListId"]
                rows.append(row)
            return "RECONCILED", rows, {
                "mutation_kind": kind,
                "order_list_id": str(payload["orderListId"]),
                "list_client_order_id": list_cid,
                "list_status": self._text(payload.get("listStatusType")),
            }

        if kind == "CANCEL_ORDER":
            row = self._get_order(
                symbol,
                order_id=target_order_id,
                client_order_id=target_client_id,
            )
            if row is None:
                return None
            return "RECONCILED", [row], {
                "mutation_kind": kind, "target_order_id": str(row["orderId"]),
                "target_client_order_id": self._text(row.get("clientOrderId")),
                "exchange_status": row["status"],
                # The observed state is recorded; this resolver never repeats cancel.
                "cancel_reissued": False,
            }

        if kind == "CANCEL_OCO":
            if not target_list_id and not target_list_cid:
                return None
            payload = self.client.order_list(
                symbol,
                order_list_id=target_list_id or None,
                list_client_order_id=target_list_cid if not target_list_id else None,
            )
            if not isinstance(payload, dict):
                return None
            if str(payload.get("symbol", "")).upper() != symbol:
                return None
            if target_list_id and str(payload.get("orderListId", "")) != target_list_id:
                return None
            if target_list_cid and str(payload.get("listClientOrderId", "")) != target_list_cid:
                return None
            list_state = self._text(payload.get("listStatusType")).upper()
            if list_state not in _LIST_STATES or payload.get("orderListId") is None:
                return None
            reports = payload.get("orders") or payload.get("orderReports") or []
            rows = []
            for item in reports:
                order_id = self._text(item.get("orderId"))
                leg_cid = self._text(item.get("clientOrderId"))
                row = self._get_order(symbol, order_id=order_id, client_order_id=leg_cid)
                if row is None:
                    return None
                row = dict(row)
                row["orderListId"] = payload["orderListId"]
                rows.append(row)
            return "RECONCILED", rows, {
                "mutation_kind": kind,
                "order_list_id": str(payload["orderListId"]),
                "list_client_order_id": self._text(payload.get("listClientOrderId")),
                "list_status": list_state,
                "cancel_reissued": False,
            }

        if kind == "CANCEL_REPLACE":
            old = self._get_order(
                symbol,
                order_id=target_order_id,
                client_order_id=target_client_id,
            )
            new_cid = replacement_cid or cid
            new = self._get_order(symbol, client_order_id=new_cid)
            # Both legs must be independently visible. A missing replacement is
            # not proof of rejection and therefore cannot resolve the intent.
            if old is None or new is None:
                return None
            return "RECONCILED", [old, new], {
                "mutation_kind": kind,
                "cancelled_order_id": str(old["orderId"]),
                "cancelled_order_status": old["status"],
                "replacement_order_id": str(new["orderId"]),
                "replacement_client_order_id": new_cid,
                "replacement_status": new["status"],
                "cancel_reissued": False,
            }

        # Unknown mutation kinds are deliberately not guessed.
        return None

    def resolve_pending(self) -> dict[str, Any]:
        rows = self.db.unresolved_execution_intents()
        resolved, blocked = [], []
        for intent in rows:
            intent_id = self._text(intent.get("intent_id"))
            try:
                evidence = self._lookup(intent)
                if evidence is None:
                    blocked.append({
                        "intent_id": intent_id,
                        "reason": "authoritative_exchange_evidence_unavailable",
                    })
                    continue
                status, orders, payload = evidence
                payload = {
                    **payload,
                    "intent_id": intent_id,
                    "exchange_evidence_count": len(orders),
                }
                # Orders, intent terminal state, and reconciliation event must
                # commit together. Any local write failure leaves the DB fail-closed.
                with self.db.transaction():
                    for order in orders:
                        self.db.save_order(order)
                    self.db.resolve_execution_intent(intent_id, status, payload)
                resolved.append({"intent_id": intent_id, "status": status})
            except Exception as exc:
                # Do not persist raw exception text; it can contain response data.
                blocked.append({
                    "intent_id": intent_id,
                    "reason": f"reconciliation_failed:{type(exc).__name__}",
                })
        return {
            "ok": not blocked,
            "resolved": resolved,
            "blocked": blocked,
            "mutations_sent": 0,
        }
