"""P0 serialized execution barrier for Williams."""
from __future__ import annotations

import time
import uuid
from dataclasses import dataclass
from typing import Any, Callable, Mapping

from market_context import ContextCache, MarketStateSnapshot
from order_state_machine import OrderState, OrderStateMachine


@dataclass(frozen=True)
class OrderIntent:
    intent_id: str
    symbol: str
    side: str
    order_type: str
    required_context_versions: Mapping[str, int]
    hypothesis_id: str = ""
    invalidation_level: float = 0.0
    quantity: str = ""
    quote_order_quantity: str = ""
    client_order_id: str = ""
    purpose: str = "ENTRY"
    permission_interval: str = ""
    campaign_id: str = ""
    signal_id: str = ""
    risk_quote: float = 0.0
    capital_reserved_quote: float = 0.0
    created_at_ms: int = 0
    max_age_ms: int = 15_000

    @classmethod
    def new(
        cls,
        symbol: str,
        side: str,
        order_type: str,
        required_context_versions: Mapping[str, int],
        **kwargs: Any,
    ) -> "OrderIntent":
        return cls(
            intent_id=uuid.uuid4().hex,
            symbol=symbol.upper(),
            side=side.upper(),
            order_type=order_type.upper(),
            required_context_versions=dict(required_context_versions),
            created_at_ms=int(time.time() * 1000),
            **kwargs,
        )


@dataclass(frozen=True)
class ExecutionResult:
    intent_id: str
    accepted: bool
    response: Any = None
    reason: str = ""


class ExecutionBarrier:
    """The final, serialized admission point before Binance REST.

    Context reads, dependency validation, exchange-specific preflight and the
    actual POST happen while the shared execution lock is held. No blind retry
    is performed by this layer.
    """

    def __init__(self, context_cache: ContextCache, db=None) -> None:
        self.context_cache = context_cache
        self.db = db

    def _persist(self, intent: OrderIntent, status: str, reason: str = "") -> None:
        if self.db is None or not callable(getattr(self.db, "save_execution_intent", None)):
            raise RuntimeError("durable execution-intent store is unavailable")
        self.db.save_execution_intent(intent, status, reason)

    def _record(self, level: str, event: str, intent: OrderIntent, message: str, raw=None) -> None:
        if self.db is not None and hasattr(self.db, "log_event"):
            try:
                self.db.log_event(level, event, message, raw or {"intent_id": intent.intent_id})
            except Exception:
                pass

    @staticmethod
    def _is_risk_reducing(intent: OrderIntent) -> bool:
        """Only explicit protection, exit and cancel intents bypass entry gates."""
        purpose = str(intent.purpose or "").upper()
        if str(intent.order_type or "").upper() == "CANCEL":
            return True
        return purpose in {
            "EXIT", "CAMPAIGN_EXIT", "PROTECTION", "CAMPAIGN_PROTECTION",
            "CAMPAIGN_TRAIL", "EMERGENCY_EXIT", "MANUAL_EXIT",
            "CAMPAIGN_EXIT_CANCEL", "EXIT_CANCEL",
        }

    @staticmethod
    def _timeframe_ms(interval: str) -> int:
        return {
            "1m": 60_000, "3m": 180_000, "5m": 300_000,
            "15m": 900_000, "30m": 1_800_000, "1h": 3_600_000,
            "2h": 7_200_000, "4h": 14_400_000, "6h": 21_600_000,
            "8h": 28_800_000, "12h": 43_200_000, "1d": 86_400_000,
            "3d": 259_200_000, "1w": 604_800_000,
        }.get(str(interval).lower(), 0)

    @classmethod
    def _context_fresh(cls, ctx, interval: str, now_ms: int) -> bool:
        close_ms = int(getattr(ctx, "candle_close_time_ms", 0) or 0)
        span_ms = cls._timeframe_ms(interval)
        if close_ms <= 0 or span_ms <= 0:
            return False
        age_ms = now_ms - close_ms
        return -5_000 <= age_ms <= span_ms + 15_000

    def _validate(self, intent: OrderIntent, snapshot: MarketStateSnapshot) -> str:
        now_ms = int(time.time() * 1000)
        side = str(intent.side or "").upper()
        risk_reducing = self._is_risk_reducing(intent)

        if intent.created_at_ms:
            age = now_ms - int(intent.created_at_ms)
            if age < -5_000 or age > int(intent.max_age_ms):
                return f"stale_or_future_intent age_ms={age}"
        if side not in {"BUY", "SELL"}:
            return f"unsupported side {intent.side}"

        # Must never deadlock risk-reducing actions behind an unknown position or
        # stale market permission. Entry/add-on intents remain fail-closed.
        if risk_reducing:
            if str(intent.order_type or "").upper() == "CANCEL":
                return ""
            if side != "SELL":
                return "risk-reducing intent must be SELL or CANCEL"
            return ""

        # Binance Spot cannot open a short. Any SELL that is not explicitly
        # classified as protection/exit is an unsupported exposure mutation.
        if side != "BUY":
            return "non-risk-reducing SELL exposure is not supported by Spot"

        if not intent.required_context_versions:
            return "missing required_context_versions"

        normalized_versions = {
            str(tf).lower(): int(version)
            for tf, version in intent.required_context_versions.items()
        }
        for tf, required in normalized_versions.items():
            normalized_tf = str(tf).lower()
            ctx = snapshot.context(intent.symbol, normalized_tf)
            if ctx is None:
                return f"missing context {intent.symbol} {normalized_tf}"
            if int(getattr(ctx, "version", -1)) != int(required):
                return (
                    f"stale context {normalized_tf}: required={required} "
                    f"current={getattr(ctx, 'version', None)}"
                )
            if not self._context_fresh(ctx, normalized_tf, now_ms):
                return f"stale_or_unverifiable context {intent.symbol} {normalized_tf}"

        permission_tf = str(intent.permission_interval or "").lower()
        if not permission_tf:
            return "missing permission_interval"
        permission_ctx = snapshot.context(intent.symbol, permission_tf)
        if permission_ctx is None:
            return f"missing permission context {intent.symbol} {permission_tf}"
        if not self._context_fresh(permission_ctx, permission_tf, now_ms):
            return f"stale_or_unverifiable permission context {intent.symbol} {permission_tf}"
        if permission_tf not in normalized_versions:
            return "permission_interval missing from required_context_versions"
        if int(getattr(permission_ctx, "version", -1)) != normalized_versions[permission_tf]:
            return "permission context version mismatch"

        direction = "long" if side == "BUY" else "short"
        if direction == "long" and not bool(getattr(permission_ctx, "allow_long", False)):
            return f"context {permission_tf} does not allow LONG"
        if direction == "short" and not bool(getattr(permission_ctx, "allow_short", False)):
            return f"context {permission_tf} does not allow SHORT"

        if self.db is None or not callable(getattr(self.db, "state_get", None)):
            return "missing durable execution state store"
        global_state = str(self.db.state_get("position_state", "UNKNOWN") or "UNKNOWN").upper()
        if global_state in {"UNKNOWN", "RECONCILE_REQUIRED"}:
            return global_state
        # A recorded fill is not yet a completed financial effect. Until the
        # canonical journal explicitly proves APPLIED with confirmed fee
        # evidence, new exposure must remain blocked. This intentionally keeps
        # the gate closed while no production applier has reconciled projections.
        conn = getattr(self.db, "conn", None)
        if conn is not None:
            try:
                pending_effect = conn.execute(
                    """SELECT symbol, exchange_trade_id, effect_status, fee_state
                       FROM financial_effect_journal
                       WHERE effect_status != 'APPLIED' OR fee_state != 'CONFIRMED'
                       LIMIT 1"""
                ).fetchone()
            except Exception as exc:
                return f"cannot inspect financial effect journal: {type(exc).__name__}"
            if pending_effect is not None:
                get = (lambda key, index: pending_effect[key]) if hasattr(pending_effect, "keys") else (lambda key, index: pending_effect[index])
                return (
                    "RECONCILE_REQUIRED: unapplied financial effect "
                    f"{get('symbol', 0)}:{get('exchange_trade_id', 1)} "
                    f"status={get('effect_status', 2)} fee_state={get('fee_state', 3)}"
                )

        # A durable PENDING/AMBIGUOUS intent from a prior process may survive
        # even when writing the portfolio state failed. Never admit a new
        # mutation while any other unresolved intent remains.
        conn = getattr(self.db, "conn", None)
        if conn is not None:
            try:
                unresolved = conn.execute(
                    "SELECT intent_id, status FROM execution_intents "
                    "WHERE status IN ('PENDING', 'AMBIGUOUS', 'RECONCILE_REQUIRED') "
                    "AND intent_id != ? LIMIT 1",
                    (intent.intent_id,),
                ).fetchone()
            except Exception as exc:
                return f"cannot inspect unresolved execution intents: {type(exc).__name__}"
            if unresolved is not None:
                return "RECONCILE_REQUIRED: unresolved execution intent " + str(
                    unresolved["intent_id"] if hasattr(unresolved, "keys") else unresolved[0]
                )
        # Per-symbol state is mandatory. A healthy global portfolio state
        # cannot establish that this particular symbol is flat.
        symbol_state = str(
            self.db.state_get(f"position_state:{intent.symbol.upper()}", "UNKNOWN")
            or "UNKNOWN"
        ).upper()
        if symbol_state in {"UNKNOWN", "RECONCILE_REQUIRED", "SYNC_REQUIRED", "EXIT_PENDING"}:
            return f"{intent.symbol}:{symbol_state}"

        purpose = str(intent.purpose or "").upper()
        if purpose in {"CAMPAIGN_ENTRY", "CAMPAIGN_ADD_ON"} and not intent.campaign_id:
            return f"{purpose} requires campaign_id"
        if purpose in {"CAMPAIGN_ENTRY", "CAMPAIGN_ADD_ON"} and not intent.signal_id:
            return f"{purpose} requires signal_id"
        if purpose == "CAMPAIGN_ENTRY" and symbol_state != "FLAT":
            return f"initial campaign entry requires explicit FLAT state, got {symbol_state}"
        if purpose == "CAMPAIGN_ADD_ON" and symbol_state != "OPEN":
            return f"campaign add-on requires confirmed OPEN state, got {symbol_state}"
        if purpose == "ENTRY" and symbol_state not in {"FLAT", "ENTRY_PENDING"}:
            return f"legacy entry requires FLAT/ENTRY_PENDING state, got {symbol_state}"
        if global_state in {"ENTRY_PENDING", "EXIT_PENDING"} and symbol_state != global_state:
            return f"portfolio transition unresolved: global={global_state} symbol={symbol_state}"

        conn = getattr(self.db, "conn", None)
        if conn is not None:
            try:
                rows = conn.execute(
                    "SELECT key, value FROM bot_state WHERE key LIKE 'position_state:%'"
                ).fetchall()
            except Exception as exc:
                return f"cannot inspect persisted portfolio states: {type(exc).__name__}"
            for row in rows:
                key = row["key"] if hasattr(row, "keys") else row[0]
                value = str(row["value"] if hasattr(row, "keys") else row[1]).upper()
                if value in {"UNKNOWN", "RECONCILE_REQUIRED"}:
                    return f"portfolio state unresolved at {key}"

        if intent.campaign_id:
            campaign_state = str(
                self.db.state_get(f"campaign_state:{intent.campaign_id}", "UNKNOWN")
                or "UNKNOWN"
            ).upper()
            if campaign_state in {"UNKNOWN", "RECONCILE_REQUIRED"}:
                return f"campaign_{campaign_state.lower()}"
        return ""

    @staticmethod
    def _oco_response_authoritative(response: dict) -> bool:
        # List-level status is insufficient without authoritative child states.
        if not response.get("orderListId") or not response.get("listClientOrderId"):
            return False
        if str(response.get("listStatusType", "")).upper() not in {"EXEC_STARTED", "EXECUTING", "ALL_DONE", "REJECT"}:
            return False
        if str(response.get("listOrderStatus", "")).upper() not in {"EXECUTING", "ALL_DONE", "REJECT"}:
            return False
        reports = response.get("orderReports")
        if not isinstance(reports, list) or len(reports) < 2:
            return False
        allowed = {"NEW", "PARTIALLY_FILLED", "FILLED", "PENDING_NEW", "PENDING_CANCEL", "CANCELED", "EXPIRED", "REJECTED"}
        ids = set()
        for report in reports:
            if not isinstance(report, dict):
                return False
            order_id = report.get("orderId")
            if order_id in (None, "", 0, "0") or str(order_id) in ids:
                return False
            ids.add(str(order_id))
            if str(report.get("status", "")).upper() not in allowed:
                return False
        return len(ids) >= 2

    def _mark_reconcile_required(self, intent: OrderIntent, reason: str) -> None:
        # PENDING is itself unresolved if the DB is too broken to update it.
        try:
            self._persist(intent, "AMBIGUOUS", reason)
        except Exception:
            pass
        if self.db is not None and callable(getattr(self.db, "state_set", None)):
            for key in ("position_state", f"position_state:{intent.symbol.upper()}"):
                try:
                    self.db.state_set(key, "RECONCILE_REQUIRED")
                except Exception:
                    pass

    @staticmethod
    def _cancel_replace_response_authoritative(response: dict) -> bool:
        if not isinstance(response, dict):
            return False
        if str(response.get("cancelResult", "")).upper() != "SUCCESS":
            return False
        if str(response.get("newOrderResult", "")).upper() != "SUCCESS":
            return False
        cancel = response.get("cancelResponse")
        replacement = response.get("newOrderResponse")
        if not isinstance(cancel, dict) or not isinstance(replacement, dict):
            return False
        if not cancel.get("orderId") or not replacement.get("orderId"):
            return False
        if str(cancel.get("status", "")).upper() not in {"CANCELED", "FILLED", "EXPIRED", "REJECTED"}:
            return False
        if str(replacement.get("status", "")).upper() not in {"NEW", "PARTIALLY_FILLED", "FILLED"}:
            return False
        return True

    def _query_authoritative_oco(self, exchange, symbol: str, client_id: str) -> dict:
        order_list = exchange.order_list(symbol, list_client_order_id=client_id)
        if not isinstance(order_list, dict):
            raise ValueError("OCO lookup returned a non-object")
        if str(order_list.get("symbol", "")).upper() != symbol.upper():
            raise ValueError("OCO lookup returned a different symbol")
        if str(order_list.get("listClientOrderId", "")) != client_id:
            raise ValueError("OCO lookup returned a different list client ID")
        list_id = order_list.get("orderListId")
        if list_id in (None, "", -1, "-1"):
            raise ValueError("OCO lookup lacks orderListId")
        list_status = str(order_list.get("listStatusType", "")).upper()
        list_order_status = str(order_list.get("listOrderStatus", "")).upper()
        if list_status not in {"EXEC_STARTED", "EXECUTING", "ALL_DONE", "REJECT"}:
            raise ValueError("OCO list status is unknown")
        if list_order_status not in {"EXECUTING", "ALL_DONE", "REJECT"}:
            raise ValueError("OCO list order status is unknown")
        children = order_list.get("orderReports")
        if not isinstance(children, list):
            children = order_list.get("orders")
        if not isinstance(children, list) or len(children) < 2:
            raise ValueError("OCO lookup does not identify both child orders")
        verified = []
        ids = set()
        allowed = {"NEW", "PARTIALLY_FILLED", "FILLED", "CANCELED", "REJECTED", "EXPIRED", "EXPIRED_IN_MATCH"}
        for child in children:
            if not isinstance(child, dict):
                raise ValueError("OCO child descriptor is malformed")
            child_id = child.get("orderId")
            if child_id in (None, "", 0, "0") or str(child_id) in ids:
                raise ValueError("OCO child order ID is missing or duplicated")
            ids.add(str(child_id))
            evidence = child
            if not str(child.get("status", "")).upper():
                evidence = exchange.get_order(symbol, order_id=child_id)
            if (
                not isinstance(evidence, dict)
                or str(evidence.get("symbol", "")).upper() != symbol.upper()
                or str(evidence.get("orderId", "")) != str(child_id)
                or str(evidence.get("status", "")).upper() not in allowed
            ):
                raise ValueError("OCO child order state is not authoritative")
            verified.append(evidence)
        return {"order_list": order_list, "children": verified}

    def reconcile_unresolved_intents(self, exchange) -> dict[str, object]:
        """Query Binance before allowing recovery from durable ambiguous intents.

        This never resubmits an order. A missing lookup, malformed exchange
        evidence, OCO without two child states, or cancel intent remains unresolved.
        Portfolio state is intentionally not reset here; a separate account/
        campaign reconciliation must prove the actual exposure before entries.
        """
        conn = getattr(self.db, "conn", None)
        if conn is None:
            return {"resolved": [], "unresolved": ["durable store unavailable"]}
        try:
            rows = conn.execute(
                "SELECT intent_id, symbol, order_type, purpose, status, client_order_id "
                "FROM execution_intents WHERE status IN ('PENDING', 'AMBIGUOUS', 'RECONCILE_REQUIRED')"
            ).fetchall()
        except Exception as exc:
            return {"resolved": [], "unresolved": [f"intent scan failed: {type(exc).__name__}"]}
        resolved: list[str] = []
        unresolved: list[str] = []
        allowed = {"NEW", "PARTIALLY_FILLED", "FILLED", "CANCELED", "REJECTED", "EXPIRED", "EXPIRED_IN_MATCH"}
        # Any startup discovery of unresolved order intent requires full account/
        # campaign reconciliation even if the individual order lookup succeeds.
        if rows and callable(getattr(self.db, "state_set", None)):
            try:
                self.db.state_set("position_state", "RECONCILE_REQUIRED")
            except Exception:
                pass
            for pending_row in rows:
                try:
                    pending_symbol = str(pending_row["symbol"] or "").upper()
                    if pending_symbol:
                        self.db.state_set(
                            f"position_state:{pending_symbol}",
                            "RECONCILE_REQUIRED",
                        )
                except Exception:
                    pass
        for row in rows:
            intent_id = str(row["intent_id"])
            symbol = str(row["symbol"] or "").upper()
            order_type = str(row["order_type"] or "").upper()
            purpose = str(row["purpose"] or "").upper()
            client_id = str(row["client_order_id"] or "")
            try:
                if not symbol or not client_id:
                    raise ValueError("intent lacks symbol or stable client identifier")
                if order_type in {"OCO", "OCO_SELL", "ORDER_LIST"} or purpose == "CAMPAIGN_EXIT_CANCEL":
                    self._query_authoritative_oco(exchange, symbol, client_id)
                elif purpose in {"CAMPAIGN_TRAIL", "PROTECTION_REPLACE", "CANCEL_REPLACE"}:
                    # Resolve both legs independently from the durable operation journal.
                    # Order-state reconciliation is not financial/protection completion:
                    # keep the global latch and operation pending until account/campaign
                    # reconciliation proves fills, fees, exposure and live protection.
                    operation = (
                        self.db.get_cancel_replace_operation(intent_id)
                        if callable(getattr(self.db, "get_cancel_replace_operation", None))
                        else None
                    )
                    if not operation:
                        raise ValueError("legacy cancelReplace has no durable two-leg operation record")
                    old_id = str(operation.get("old_exchange_order_id") or "")
                    new_client_id = str(operation.get("replacement_client_order_id") or "")
                    if not old_id or not new_client_id:
                        raise ValueError("cancelReplace durable old/new identities are incomplete")
                    old_order = exchange.get_order(symbol, order_id=old_id)
                    new_order = exchange.get_order(symbol, orig_client_order_id=new_client_id)
                    for label, evidence, expected_id, by_client in (
                        ("old", old_order, old_id, False),
                        ("replacement", new_order, new_client_id, True),
                    ):
                        if not isinstance(evidence, dict):
                            raise ValueError(f"cancelReplace {label} lookup is not an object")
                        if str(evidence.get("symbol", "")).upper() != symbol:
                            raise ValueError(f"cancelReplace {label} lookup symbol mismatch")
                        actual_id = str(
                            evidence.get("clientOrderId", "") if by_client
                            else evidence.get("orderId", "")
                        )
                        if actual_id != expected_id:
                            raise ValueError(f"cancelReplace {label} lookup identity mismatch")
                        if str(evidence.get("status", "")).upper() not in allowed:
                            raise ValueError(f"cancelReplace {label} status is not authoritative")
                    self.db.update_cancel_replace_operation(
                        intent_id,
                        {
                            "cancelResult": "SUCCESS",
                            "newOrderResult": "SUCCESS",
                            "cancelResponse": old_order,
                            "newOrderResponse": new_order,
                        },
                        cancel_state=str(old_order.get("status", "UNKNOWN")).upper(),
                        replacement_state=str(new_order.get("status", "UNKNOWN")).upper(),
                        reconcile_required=True,
                        reconciliation={
                            "source": "independent_order_lookups",
                            "old_order": old_order,
                            "replacement_order": new_order,
                            "financial_reconciliation_complete": False,
                            "protection_reconciliation_complete": False,
                        },
                    )
                    conn.execute(
                        "UPDATE execution_intents SET status='RECONCILE_REQUIRED', "
                        "reason=? WHERE intent_id=?",
                        ("both order legs observed; financial/exposure/protection reconciliation still required", intent_id),
                    )
                    conn.commit()
                    unresolved.append(intent_id + ": order legs observed; full financial and protection reconciliation required")
                    continue
                elif order_type == "CANCEL":
                    raise ValueError("cancel intent lacks a durable target-order identity")
                else:
                    evidence = exchange.get_order(symbol, orig_client_order_id=client_id)
                    status = str(evidence.get("status", "")).upper()
                    if (
                        not isinstance(evidence, dict)
                        or str(evidence.get("symbol", "")).upper() != symbol
                        or str(evidence.get("clientOrderId", "")) != client_id
                        or int(evidence.get("orderId", 0) or 0) <= 0
                        or status not in allowed
                    ):
                        raise ValueError("order lookup did not return authoritative matching order")
                conn.execute(
                    "UPDATE execution_intents SET status='RECONCILED', reason=? WHERE intent_id=?",
                    ("authoritative exchange lookup completed; exposure reconciliation still required", intent_id),
                )
                conn.commit()
                resolved.append(intent_id)
            except Exception as exc:
                unresolved.append(intent_id + ": " + str(exc))
        if unresolved and callable(getattr(self.db, "state_set", None)):
            try:
                self.db.state_set("position_state", "RECONCILE_REQUIRED")
            except Exception:
                pass
            for row in rows:
                try:
                    self.db.state_set(f"position_state:{str(row['symbol']).upper()}", "RECONCILE_REQUIRED")
                except Exception:
                    pass
        return {"resolved": resolved, "unresolved": unresolved}

    def execute(
        self,
        intent: OrderIntent,
        submit: Callable[[], Any],
        *,
        pre_submit_checks: Callable[[MarketStateSnapshot], None] | None = None,
    ) -> ExecutionResult:
        with self.context_cache.execution_lock:
            order_fsm = OrderStateMachine()
            order_fsm.transition(OrderState.ADMISSION)
            try:
                # Durable intent is a prerequisite for every exchange mutation.
                self._persist(intent, "PENDING")
            except Exception as exc:
                return ExecutionResult(
                    intent.intent_id,
                    False,
                    reason=f"execution_intent_persistence_failed: {type(exc).__name__}: {exc}",
                )
            if self.db is not None and hasattr(self.db, "save_execution_event"):
                try:
                    self.db.save_execution_event(intent.intent_id, "ADMISSION_STARTED", dict(intent.required_context_versions))
                except Exception:
                    # Secondary event telemetry is best-effort; the intent row is mandatory.
                    pass
            snapshot = self.context_cache.snapshot()
            try:
                reason = self._validate(intent, snapshot)
            except Exception as exc:
                # Malformed context versions, timestamps or state stores are
                # validation failures, not permission to continue to submit().
                reason = f"validation_error:{type(exc).__name__}:{exc}"
                self._record("ERROR", "execution_validation_error", intent, reason)
                try:
                    self._persist(intent, "BLOCKED", reason)
                except Exception as persist_exc:
                    return ExecutionResult(
                        intent.intent_id,
                        False,
                        reason=(
                            "validation and BLOCKED-state persistence failed; "
                            "no exchange request sent: "
                            f"{type(persist_exc).__name__}: {persist_exc}"
                        ),
                    )
                return ExecutionResult(intent.intent_id, False, reason=reason)
            if reason:
                try:
                    order_fsm.transition(OrderState.CANCELED)
                except ValueError:
                    order_fsm.state = OrderState.RECONCILE_REQUIRED
                self._record("WARNING", "execution_blocked", intent, reason)
                try:
                    self._persist(intent, "BLOCKED", reason)
                except Exception as exc:
                    return ExecutionResult(
                        intent.intent_id,
                        False,
                        reason=(
                            "admission blocked but BLOCKED-state persistence failed; "
                            "no exchange request sent: "
                            f"{type(exc).__name__}: {exc}"
                        ),
                    )
                return ExecutionResult(intent.intent_id, False, reason=reason)

            if pre_submit_checks is not None:
                try:
                    pre_submit_checks(snapshot)
                except Exception as exc:
                    try:
                        order_fsm.transition(OrderState.CANCELED)
                    except ValueError:
                        order_fsm.state = OrderState.RECONCILE_REQUIRED
                    reason = f"pre_submit_check_failed: {type(exc).__name__}: {exc}"
                    self._persist(intent, "BLOCKED", reason)
                    self._record("WARNING", "execution_blocked", intent, reason)
                    return ExecutionResult(intent.intent_id, False, reason=reason)

            order_fsm.transition(OrderState.SUBMITTING)
            self._record(
                "INFO",
                "execution_admitted",
                intent,
                "Final P0 validation passed; submitting to Binance",
                {
                    "intent_id": intent.intent_id,
                    "symbol": intent.symbol,
                    "side": intent.side,
                    "required_context_versions": dict(intent.required_context_versions),
                    "hypothesis_id": intent.hypothesis_id,
                    "purpose": intent.purpose,
                    "client_order_id": intent.client_order_id,
                },
            )
            try:
                response = submit()
            except Exception as exc:
                order_fsm.state = OrderState.AMBIGUOUS
                reason = f"{type(exc).__name__}: {exc}"
                self._mark_reconcile_required(intent, reason)
                self._record(
                    "ERROR", "execution_ambiguous", intent,
                    "Binance submission failed or timed out; reconciliation required",
                    {"error": reason},
                )
                raise

            # cancelReplace is non-transactional: require both authoritative legs.
            if isinstance(response, dict) and (
                "cancelResult" in response or "newOrderResult" in response
            ):
                if not self._cancel_replace_response_authoritative(response):
                    reason = "cancelReplace response does not authoritatively prove both old and replacement orders"
                    order_fsm.state = OrderState.RECONCILE_REQUIRED
                    self._mark_reconcile_required(intent, reason)
                    self._record("ERROR", "cancel_replace_ambiguous", intent, reason,
                                 {"response_keys": list(response.keys())})
                    raise RuntimeError("ExecutionBarrier: " + reason)
            if intent.order_type != "CANCEL":
                status = str(
                    response.get("status", "")
                    if isinstance(response, dict)
                    else ""
                ).upper()
                if status:
                    order_fsm.observe_exchange_status(
                        status,
                        float(
                            response.get("executedQty", 0) or 0
                            if isinstance(response, dict)
                            else 0
                        ),
                    )
                elif (
                    isinstance(response, dict)
                    and str(response.get("newOrderResult", "")).upper() == "SUCCESS"
                ):
                    # Binance cancelReplace reports the replacement result in
                    # newOrderResult rather than a top-level order status.
                    order_fsm.state = OrderState.OPEN
                elif (
                    isinstance(response, dict)
                    and str(intent.order_type or "").upper() in {"OCO", "OCO_SELL", "ORDER_LIST"}
                    and self._oco_response_authoritative(response)
                ):
                    # OCO/order-list responses have list-level and child-order
                    # statuses; expecting a normal order.status here falsely
                    # classifies successful protection creation as UNKNOWN.
                    order_fsm.state = OrderState.OPEN
                else:
                    order_fsm.state = OrderState.RECONCILE_REQUIRED
                    self._mark_reconcile_required(
                        intent, "exchange response did not contain authoritative order state"
                    )
                    self._record(
                        "ERROR",
                        "execution_ambiguous",
                        intent,
                        "Exchange accepted an operation without authoritative state",
                        {"response_keys": list(response.keys()) if isinstance(response, dict) else []},
                    )
                    raise RuntimeError(
                        "ExecutionBarrier: exchange response lacks authoritative order state"
                    )
            try:
                self._persist(intent, "SUBMITTED")
            except Exception as exc:
                # The exchange may already have accepted the request. Mark it as
                # ambiguous and never expose an ordinary retryable failure.
                self._mark_reconcile_required(
                    intent,
                    f"exchange outcome persistence failed: {type(exc).__name__}: {exc}",
                )
                self._record(
                    "ERROR",
                    "execution_outcome_persistence_failed",
                    intent,
                    "Exchange mutation may have succeeded; reconciliation required",
                    {"error": f"{type(exc).__name__}: {exc}", "client_order_id": intent.client_order_id},
                )
                raise RuntimeError(
                    "ExecutionBarrier: exchange outcome persistence failed; RECONCILE_REQUIRED"
                ) from exc
            if self.db is not None and hasattr(self.db, "save_execution_event"):
                try:
                    self.db.save_execution_event(intent.intent_id, "BINANCE_SUBMITTED", {"symbol": intent.symbol, "side": intent.side})
                except Exception:
                    pass
            self._record("INFO", "execution_submitted", intent, "Binance accepted request", {
                "intent_id": intent.intent_id,
            })
            return ExecutionResult(intent.intent_id, True, response=response)
