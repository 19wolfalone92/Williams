"""Fail-closed recovery of persisted Binance execution intents.

This worker performs read-only exchange lookups only. It never submits, retries,
or cancels an order. Missing/mismatched results remain unresolved with backoff.
"""
from __future__ import annotations
import time
import uuid
from typing import Any

KNOWN_STATUSES = {"NEW", "PENDING_NEW", "PARTIALLY_FILLED", "FILLED", "PENDING_CANCEL", "CANCELED", "REJECTED", "EXPIRED", "OCO_EXECUTING", "OCO_ALL_DONE", "OCO_REJECTED"}

class ExecutionRecoveryWorker:
    """Reconcile persisted PENDING/AMBIGUOUS intents through a read-only adapter."""
    def __init__(self, db, exchange, *, lease_seconds=30.0, base_retry_seconds=5.0,
                 max_retry_seconds=300.0, batch_size=10, owner=None):
        self.db = db
        self.exchange = exchange
        self.lease_seconds = max(1.0, float(lease_seconds))
        self.base_retry_seconds = max(1.0, float(base_retry_seconds))
        self.max_retry_seconds = max(self.base_retry_seconds, float(max_retry_seconds))
        self.batch_size = max(1, min(int(batch_size), 100))
        self.owner = owner or uuid.uuid4().hex

    @staticmethod
    def _validate_order(row: dict[str, Any], order: Any) -> tuple[bool, str]:
        if not isinstance(order, dict):
            return False, "exchange lookup returned a non-object response"
        if str(row.get("order_type", "")).upper() == "OCO":
            if not order.get("_oco_normalized"):
                return False, "OCO response was not fully normalized from authoritative legs"
            status = str(order.get("status", "")).upper()
            if status not in {"OCO_EXECUTING", "OCO_ALL_DONE", "OCO_REJECTED"}:
                return False, "exchange order list returned an unknown status"
            if str(order.get("listClientOrderId", "")) != str(row.get("client_order_id", "")):
                return False, "exchange listClientOrderId does not match persisted intent"
            if order.get("orderListId") is None:
                return False, "exchange order list lacks authoritative orderListId"
            return True, status
        status = str(order.get("status", "")).upper()
        if status not in KNOWN_STATUSES:
            return False, "exchange lookup returned an unknown or missing status"
        if str(order.get("symbol", "")).upper() != str(row.get("symbol", "")).upper():
            return False, "exchange order symbol does not match persisted intent"
        if str(order.get("side", "")).upper() != str(row.get("side", "")).upper():
            return False, "exchange order side does not match persisted intent"
        returned_id, saved_id = order.get("orderId"), row.get("order_id")
        if saved_id and returned_id is not None and str(saved_id) != str(returned_id):
            return False, "exchange orderId conflicts with persisted evidence"
        expected_client_id = str(row.get("client_order_id") or "")
        returned_client_id = str(order.get("clientOrderId") or "")
        if expected_client_id and returned_client_id != expected_client_id:
            return False, "exchange clientOrderId does not match persisted intent"
        if returned_id is None and not returned_client_id:
            return False, "exchange response lacks authoritative order identifiers"
        try:
            executed = float(order.get("executedQty", 0) or 0)
            original = float(order.get("origQty", 0) or 0)
            price = float(order.get("price", 0) or 0)
            if executed < 0 or original < 0 or price < 0:
                return False, "exchange response contains negative quantity or price"
        except (TypeError, ValueError):
            return False, "exchange response contains invalid numeric fields"
        if status == "FILLED" and original > 0 and executed + 1e-12 < original:
            return False, "FILLED status conflicts with executed quantity"
        return True, status

    def _lookup(self, row):
        symbol = str(row.get("symbol", "")).upper()
        order_id = row.get("order_id")
        client_id = str(row.get("client_order_id") or "")
        if str(row.get("order_type", "")).upper() == "OCO":
            if not client_id or not callable(getattr(self.exchange, "order_list", None)):
                raise RuntimeError("OCO intent lacks list client ID or order-list lookup capability")
            listing = self.exchange.order_list(symbol, list_client_order_id=client_id)
            if not isinstance(listing, dict) or str(listing.get("listClientOrderId", "")) != client_id:
                raise ValueError("exchange order list does not match persisted list client ID")
            list_status = str(listing.get("listOrderStatus", "")).upper()
            mapping = {"EXECUTING": "OCO_EXECUTING", "ALL_DONE": "OCO_ALL_DONE", "REJECT": "OCO_REJECTED"}
            if list_status not in mapping:
                raise ValueError("exchange order list returned unknown status")
            legs = listing.get("orders")
            if not isinstance(legs, list) or len(legs) < 2:
                raise ValueError("exchange order list lacks both authoritative order legs")
            verified_legs = []
            for leg in legs:
                leg_id = leg.get("orderId") if isinstance(leg, dict) else None
                if leg_id is None:
                    raise ValueError("exchange order list leg lacks orderId")
                found = self.exchange.get_order(symbol, order_id=leg_id)
                if not isinstance(found, dict):
                    raise ValueError("OCO leg lookup returned a non-object response")
                if str(found.get("symbol", "")).upper() != symbol or str(found.get("side", "")).upper() != str(row.get("side", "")).upper():
                    raise ValueError("OCO leg symbol or side conflicts with persisted intent")
                if str(found.get("orderId", "")) != str(leg_id):
                    raise ValueError("OCO leg orderId conflicts with order-list evidence")
                if leg.get("clientOrderId") and str(found.get("clientOrderId", "")) != str(leg.get("clientOrderId")):
                    raise ValueError("OCO leg clientOrderId conflicts with order-list evidence")
                if str(found.get("status", "")).upper() not in KNOWN_STATUSES - {"OCO_EXECUTING", "OCO_ALL_DONE", "OCO_REJECTED"}:
                    raise ValueError("OCO leg has unknown status")
                verified_legs.append(found)
            return {
                "_oco_normalized": True, "symbol": symbol, "side": str(row.get("side", "")).upper(),
                "orderListId": listing.get("orderListId"), "listClientOrderId": client_id,
                "listOrderStatus": list_status, "status": mapping[list_status],
                "executedQty": sum(float(x.get("executedQty", 0) or 0) for x in verified_legs),
                "origQty": sum(float(x.get("origQty", 0) or 0) for x in verified_legs),
                "price": 0, "orders": verified_legs,
            }
        if order_id is not None:
            return self.exchange.get_order(symbol, order_id=order_id)
        if client_id:
            return self.exchange.get_order(symbol, orig_client_order_id=client_id)
        raise RuntimeError("intent has no persisted orderId or clientOrderId; safe lookup is impossible")

    def run_once(self, *, now=None) -> dict[str, int]:
        """Process one bounded batch; every failed lookup stays unresolved."""
        current = time.time() if now is None else float(now)
        stats = {"claimed": 0, "reconciled": 0, "deferred": 0, "lease_lost": 0}
        rows = self.db.list_unresolved_execution_intents(self.batch_size, now=current)
        for row in rows:
            intent_id = str(row["intent_id"])
            if not self.db.claim_execution_intent_recovery(intent_id, self.owner, self.lease_seconds, now=current):
                continue
            stats["claimed"] += 1
            try:
                order = self._lookup(row)
                valid, status_or_reason = self._validate_order(row, order)
                if not valid:
                    raise ValueError(status_or_reason)
                persisted = self.db.complete_execution_intent_recovery(
                    intent_id, self.owner, status_or_reason, order, now=current
                )
                if not persisted:
                    stats["lease_lost"] += 1
                    continue
                stats["reconciled"] += 1
            except Exception as exc:
                try:
                    # Do not persist raw transport/API exception text: it may contain
                    # request details. Validation errors above use fixed safe messages.
                    safe_error = str(exc) if isinstance(exc, ValueError) else type(exc).__name__ + ': reconciliation lookup or persistence failed'
                    deferred = self.db.defer_execution_intent_recovery(
                        intent_id, self.owner, safe_error,
                        self.base_retry_seconds, self.max_retry_seconds, now=current)
                except Exception:
                    deferred = False
                if deferred:
                    stats["deferred"] += 1
                else:
                    stats["lease_lost"] += 1
        return stats
