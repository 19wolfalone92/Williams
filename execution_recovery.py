"""Fail-closed Binance Spot execution-intent recovery.

A missing order lookup is never interpreted as proof that an order was not sent.
Only a matching order plus complete matching per-trade history can resolve an intent.
No order is re-submitted by this module.
"""
from __future__ import annotations

from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
import hashlib
import json
import os
from typing import Any, Callable

from execution_recovery_contract import _issue_verified_reconciliation_evidence


class ExecutionRecoveryBlocked(RuntimeError):
    pass


class ExecutionRecoveryCoordinator:
    UNRESOLVED = {"PENDING", "SUBMITTING", "UNKNOWN", "AMBIGUOUS", "RECONCILE_REQUIRED"}
    ACCEPTED_EXCHANGE_STATUSES = {
        "NEW": "OPEN",
        "PENDING_NEW": "OPEN",
        "PARTIALLY_FILLED": "PARTIALLY_FILLED",
        "FILLED": "FILLED",
        "CANCELED": "CANCELED",
        "EXPIRED": "EXPIRED",
        "REJECTED": "REJECTED",
    }

    def __init__(
        self,
        client,
        db,
        *,
        fill_handler: Callable[[dict[str, Any], dict[str, Any], dict[str, Any]], None] | None = None,
        exchange: str = "BINANCE",
        account_scope: str | None = None,
        execution_scope: str = "spot",
    ):
        self.client = client
        self.db = db
        self.fill_handler = fill_handler
        self.exchange = str(exchange).upper()
        self.execution_scope = str(execution_scope or "spot").strip().lower()
        self.account_scope = db._default_account_scope(self.execution_scope, account_scope)

    @staticmethod
    def _decimal(value, field):
        try:
            result = Decimal(str(value))
        except (InvalidOperation, TypeError, ValueError) as exc:
            raise ExecutionRecoveryBlocked(f"invalid {field} from exchange") from exc
        if not result.is_finite() or result < 0:
            raise ExecutionRecoveryBlocked(f"invalid {field} from exchange")
        return result

    def _record_blocked(self, intent_id, reason, payload=None):
        record = {
            "reason": str(reason),
            "execution_scope": self.execution_scope,
            "account_scope": self.account_scope,
            "at": datetime.now(timezone.utc).isoformat(),
        }
        if payload is not None:
            record["context"] = payload
        try:
            with self.db.transaction():
                self.db.conn.execute(
                    "INSERT INTO execution_events(intent_id,event,payload_json) VALUES(?,?,?)",
                    (str(intent_id), "RECOVERY_BLOCKED", json.dumps(record, default=str, sort_keys=True)),
                )
        except Exception:
            # Failure to log recovery is not permission to unlock or submit.
            pass

    def _query_order(self, intent):
        client_id = str(intent.get("client_order_id") or "")
        order_id = intent.get("order_id")
        if not client_id and not order_id:
            raise ExecutionRecoveryBlocked("durable intent has neither clientOrderId nor orderId")
        # Binance's order lookup should use one unambiguous identity. If orderId
        # exists, clientOrderId is still compared against the returned payload below.
        order = self.client.get_order(
            str(intent["symbol"]).upper(),
            order_id=order_id if order_id else None,
            orig_client_order_id=None if order_id else (client_id if client_id else None),
        )
        if not isinstance(order, dict):
            raise ExecutionRecoveryBlocked("Binance order lookup did not return an object")
        if str(order.get("symbol", "")).upper() != str(intent["symbol"]).upper():
            raise ExecutionRecoveryBlocked("Binance order symbol conflicts with durable intent")
        returned_client_id = str(order.get("clientOrderId") or "")
        if client_id and returned_client_id != client_id:
            raise ExecutionRecoveryBlocked("Binance clientOrderId conflicts with durable intent")
        if order_id and str(order.get("orderId")) != str(order_id):
            raise ExecutionRecoveryBlocked("Binance orderId conflicts with durable intent")
        if order.get("orderId") is None:
            raise ExecutionRecoveryBlocked("Binance response lacks an authoritative orderId")
        if str(order.get("side", "")).upper() != str(intent.get("side", "")).upper():
            raise ExecutionRecoveryBlocked("Binance order side conflicts with durable intent")
        return order

    def _query_trades(self, intent, order):
        executed = self._decimal(order.get("executedQty", "0"), "executedQty")
        # Query trade history even when executedQty is zero: delayed history and
        # cancel/fill races must become a mismatch, never an optimistic unlock.
        method = getattr(self.client, "my_trades", None)
        if not callable(method):
            raise ExecutionRecoveryBlocked("signed myTrades API unavailable; recovery remains locked")
        rows = method(str(intent["symbol"]).upper(), order_id=order.get("orderId"), limit=1000)
        if not isinstance(rows, list):
            raise ExecutionRecoveryBlocked("Binance myTrades response is not a complete JSON array")
        if len(rows) >= 1000:
            raise ExecutionRecoveryBlocked("myTrades reached its page limit; completeness is unproven")
        total_qty = Decimal("0")
        total_quote = Decimal("0")
        seen = set()
        normalized = []
        for item in rows:
            if not isinstance(item, dict):
                raise ExecutionRecoveryBlocked("malformed myTrades row")
            trade_id = item.get("id", item.get("tradeId"))
            if trade_id is None:
                raise ExecutionRecoveryBlocked("myTrades row lacks stable trade ID")
            if str(item.get("symbol", intent["symbol"])).upper() != str(intent["symbol"]).upper():
                raise ExecutionRecoveryBlocked("myTrades symbol conflict")
            if str(item.get("orderId")) != str(order.get("orderId")):
                raise ExecutionRecoveryBlocked("myTrades orderId conflict")
            if str(trade_id) in seen:
                raise ExecutionRecoveryBlocked("duplicate trade ID in one myTrades response")
            seen.add(str(trade_id))
            qty = self._decimal(item.get("qty"), "trade qty")
            price = self._decimal(item.get("price"), "trade price")
            if qty <= 0 or price <= 0:
                raise ExecutionRecoveryBlocked("non-positive executed quantity/price in myTrades")
            if "isBuyer" in item and bool(item["isBuyer"]) != (str(intent.get("side", "")).upper() == "BUY"):
                raise ExecutionRecoveryBlocked("myTrades direction conflicts with the durable order")
            total_qty += qty
            total_quote += qty * price
            normalized.append({
                **item,
                "trade_id": str(trade_id),
                "quantity": str(qty),
                "price": str(price),
            })
        if abs(total_qty - executed) > Decimal("0.000000000001"):
            raise ExecutionRecoveryBlocked(
                f"myTrades quantity {total_qty} does not match order executedQty {executed}"
            )
        reported_quote_raw = order.get("cummulativeQuoteQty")
        if reported_quote_raw not in (None, ""):
            reported_quote = self._decimal(reported_quote_raw, "cummulativeQuoteQty")
            if total_qty > 0 and (
                reported_quote <= 0
                or abs(total_quote - reported_quote) > max(Decimal("0.00000001"), reported_quote * Decimal("0.00000001"))
            ):
                raise ExecutionRecoveryBlocked(
                    f"myTrades quote total {total_quote} does not match order cummulativeQuoteQty {reported_quote}"
                )
        return normalized, total_qty

    def _evidence(self, intent, order, trades, history_qty):
        status = str(order.get("status", "")).upper()
        if status not in self.ACCEPTED_EXCHANGE_STATUSES:
            raise ExecutionRecoveryBlocked(f"unknown Binance order status {status!r}")
        executed = self._decimal(order.get("executedQty", "0"), "executedQty")
        canonical = {
            "exchange": self.exchange,
            "account_scope": str(intent.get("account_scope") or self.account_scope),
            "execution_scope": str(intent.get("execution_scope") or self.execution_scope),
            "symbol": str(intent["symbol"]).upper(),
            "client_order_id": str(intent.get("client_order_id") or ""),
            "order_id": str(order.get("orderId")),
            "status": self.ACCEPTED_EXCHANGE_STATUSES[status],
            "executed_qty": str(executed),
            "history_qty": str(history_qty),
            "trade_ids": sorted(str(row["trade_id"]) for row in trades),
            "observed_at": datetime.now(timezone.utc).isoformat(),
            "order_verified": True,
            "history_complete": True,
            "fill_totals_match": executed == history_qty,
            "order_snapshot": order,
        }
        digest = hashlib.sha256(
            json.dumps(canonical, default=str, sort_keys=True, separators=(",", ":")).encode("utf-8")
        ).hexdigest()
        return _issue_verified_reconciliation_evidence(
            exchange=canonical["exchange"],
            account_scope=canonical["account_scope"],
            execution_scope=canonical["execution_scope"],
            symbol=canonical["symbol"],
            client_order_id=canonical["client_order_id"],
            order_id=canonical["order_id"],
            status=canonical["status"],
            executed_qty=canonical["executed_qty"],
            history_qty=canonical["history_qty"],
            trade_ids=tuple(canonical["trade_ids"]),
            observed_at=canonical["observed_at"],
            evidence_id=digest,
            order_verified=True,
            history_complete=True,
            fill_totals_match=canonical["fill_totals_match"],
        )

    def recover_one(self, intent):
        intent_id = str(intent["intent_id"])
        try:
            if str(intent.get("execution_scope") or "spot").lower() != self.execution_scope:
                return {"intent_id": intent_id, "state": "BLOCKED", "reason": "SCOPE_MISMATCH"}
            if str(intent.get("account_scope") or self.account_scope) != self.account_scope:
                return {"intent_id": intent_id, "state": "BLOCKED", "reason": "ACCOUNT_SCOPE_MISMATCH"}
            if str(intent.get("exchange") or self.exchange).upper() != self.exchange:
                return {"intent_id": intent_id, "state": "BLOCKED", "reason": "EXCHANGE_MISMATCH"}
            order = self._query_order(intent)
            order_status = str(order.get("status", "")).upper()
            if order_status not in self.ACCEPTED_EXCHANGE_STATUSES:
                raise ExecutionRecoveryBlocked(f"unsupported order status {order_status!r}")
            trades, history_qty = self._query_trades(intent, order)

            # The position/campaign/fees/PnL callback runs inside the same SQLite
            # transaction as the unique execution_fills insert. A callback error
            # rolls both back. No callback means filled operations remain blocked.
            if trades and self.fill_handler is None:
                raise ExecutionRecoveryBlocked("campaign financial fill handler is not wired")
            for trade in trades:
                commission = trade.get("commission", "0")
                commission_asset = trade.get("commissionAsset", "")
                fill = {
                    "exchange": self.exchange,
                    "account_scope": self.account_scope,
                    "symbol": str(intent["symbol"]).upper(),
                    "trade_id": str(trade["trade_id"]),
                    "order_id": str(order.get("orderId")),
                    "intent_id": intent_id,
                    "campaign_id": str(intent.get("campaign_id") or ""),
                    "purpose": str(intent.get("purpose") or ""),
                    "side": str(intent.get("side") or "").upper(),
                    "client_order_id": str(intent.get("client_order_id") or ""),
                    "quantity": str(trade["quantity"]),
                    "price": str(trade["price"]),
                    "commission": str(commission),
                    "commission_asset": str(commission_asset or ""),
                    "raw": trade,
                }
                self.db.apply_execution_fill(
                    fill,
                    lambda _db, data, i=intent, o=order, t=fill: self.fill_handler(i, o, t),
                )

            evidence = self._evidence(intent, order, trades, history_qty)
            self.db.resolve_execution_intent(
                intent_id, evidence, scope=self.execution_scope
            )
            return {
                "intent_id": intent_id,
                "state": evidence.status,
                "order_id": evidence.order_id,
                "fills_seen": len(trades),
                "evidence_id": evidence.evidence_id,
            }
        except Exception as exc:
            self._record_blocked(intent_id, f"{type(exc).__name__}: {exc}")
            return {
                "intent_id": intent_id,
                "state": "RECONCILE_REQUIRED",
                "reason": f"{type(exc).__name__}: {exc}",
            }

    def recover(self):
        intents = self.db.list_unresolved_execution_intents(
            scope=self.execution_scope,
            account_scope=self.account_scope,
        )
        return [self.recover_one(row) for row in intents]

    def readiness(self):
        return self.db.execution_readiness(
            scope=self.execution_scope,
            account_scope=self.account_scope,
        )
