"""P0 serialized execution barrier for Digital Bill Williams.

This module is the only local admission door to exchange order mutations.
It validates live context, enforces persistent idempotency, and fail-closes
on ambiguous exchange outcomes until authoritative reconciliation completes.
"""
from __future__ import annotations

import json
import re
import time
import uuid
from dataclasses import dataclass
from typing import Any, Callable, Mapping

from domain.contracts import ExecutionIntent as CanonicalExecutionIntent
from market_context import ContextCache, MarketStateSnapshot
from order_state_machine import OrderState, OrderStateMachine


class ExecutionAmbiguousError(RuntimeError):
    """An exchange mutation outcome is not authoritatively known."""


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
    recv_window: int = 5_000
    time_in_force: str = "GTC"
    reduce_only: bool = False
    related_order_id: str = ""
    related_order_list_id: str = ""

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
            symbol=str(symbol).upper(),
            side=str(side).upper(),
            order_type=str(order_type).upper(),
            required_context_versions=dict(required_context_versions),
            created_at_ms=int(time.time() * 1000),
            **kwargs,
        )

    @classmethod
    def from_canonical(
        cls,
        intent: CanonicalExecutionIntent,
        *,
        required_context_versions: Mapping[str, int] | None = None,
        hypothesis_id: str = "",
        purpose: str = "ENTRY",
        permission_interval: str = "",
        campaign_id: str = "",
        signal_id: str = "",
        quantity: str = "",
        quote_order_quantity: str = "",
        invalidation_level: float | None = None,
        risk_quote: float = 0.0,
        capital_reserved_quote: float = 0.0,
        related_order_id: str = "",
        related_order_list_id: str = "",
    ) -> "OrderIntent":
        decision = intent.risk_decision.williams_decision
        return cls.new(
            decision.symbol,
            "BUY" if decision.direction.value == "LONG" else "SELL",
            intent.order_type,
            required_context_versions or {},
            hypothesis_id=hypothesis_id,
            invalidation_level=decision.invalidation_price if invalidation_level is None else float(invalidation_level),
            quantity=quantity,
            quote_order_quantity=quote_order_quantity,
            client_order_id=intent.client_order_id,
            purpose=purpose,
            permission_interval=permission_interval,
            campaign_id=campaign_id,
            signal_id=signal_id,
            risk_quote=float(risk_quote),
            capital_reserved_quote=float(capital_reserved_quote),
            recv_window=int(intent.recv_window),
            time_in_force=intent.time_in_force,
            reduce_only=bool(intent.reduce_only),
            related_order_id=str(related_order_id or ""),
            related_order_list_id=str(related_order_list_id or ""),
        )


@dataclass(frozen=True)
class ExecutionResult:
    intent_id: str
    accepted: bool
    response: Any = None
    reason: str = ""


class ExecutionBarrier:
    """Serialized, fail-closed execution barrier immediately before Binance."""

    MUTATION_LOCK_KEY = "execution_mutation_lock"

    def __init__(
        self,
        context_cache: ContextCache,
        db=None,
    ) -> None:
        self.context_cache = context_cache
        self.db = db
        self._mutation_lock_reason = ""

    @property
    def mutation_locked(self) -> bool:
        if self._mutation_lock_reason:
            return True
        if self.db is not None and hasattr(self.db, "state_get"):
            return bool(self.db.state_get(self.MUTATION_LOCK_KEY, ""))
        return False

    @property
    def mutation_lock_reason(self) -> str:
        if self._mutation_lock_reason:
            return self._mutation_lock_reason
        if self.db is not None and hasattr(self.db, "state_get"):
            return str(self.db.state_get(self.MUTATION_LOCK_KEY, "") or "")
        return ""

    def _lock_mutations(self, reason: str) -> None:
        self._mutation_lock_reason = str(reason)
        if self.db is not None and hasattr(self.db, "state_set"):
            self.db.state_set(self.MUTATION_LOCK_KEY, self._mutation_lock_reason)

    def _unlock_mutations(self) -> None:
        self._mutation_lock_reason = ""
        if self.db is not None and hasattr(self.db, "state_delete"):
            self.db.state_delete(self.MUTATION_LOCK_KEY)

    def _persist(self, intent: OrderIntent, status: str, reason: str = "") -> None:
        if self.db is not None and hasattr(self.db, "save_execution_intent"):
            try:
                self.db.save_execution_intent(intent, status, reason)
            except Exception:
                pass

    def _record(
        self,
        level: str,
        event: str,
        intent: OrderIntent,
        message: str,
        raw=None,
    ) -> None:
        if self.db is not None and hasattr(self.db, "log_event"):
            try:
                self.db.log_event(
                    level,
                    event,
                    message,
                    raw or {"intent_id": intent.intent_id},
                )
            except Exception:
                pass

    @staticmethod
    def _not_found_exception(exc: BaseException) -> bool:
        code = None
        status = getattr(exc, "status_code", None)
        payload = getattr(exc, "payload", None)
        if isinstance(payload, dict):
            code = payload.get("code")
        if code in {-2013, -2011}:
            return True
        return status == 404

    def _claim_client_order_id(self, intent: OrderIntent) -> bool:
        cid = str(intent.client_order_id or "").strip()
        if not cid or intent.order_type.upper() == "CANCEL":
            return True
        if self.db is None or not hasattr(self.db, "try_claim_state"):
            return False
        return bool(
            self.db.try_claim_state(
                f"execution_client_order_id:{cid}",
                intent.intent_id,
            )
        )

    def _release_client_order_id(self, intent: OrderIntent) -> None:
        cid = str(intent.client_order_id or "").strip()
        if not cid or self.db is None or not hasattr(self.db, "state_get"):
            return
        key = f"execution_client_order_id:{cid}"
        if str(self.db.state_get(key, "") or "") == intent.intent_id:
            self.db.state_delete(key)

    def _validate(self, intent: OrderIntent, snapshot: MarketStateSnapshot) -> str:
        if self.mutation_locked:
            return f"MUTATION_LOCKED_RECONCILIATION_REQUIRED: {self.mutation_lock_reason}"

        if intent.created_at_ms:
            age = int(time.time() * 1000) - intent.created_at_ms
            if age > int(intent.max_age_ms):
                return f"stale intent age={age}ms"

        order_type = str(intent.order_type).upper()
        if order_type != "CANCEL" and not str(intent.client_order_id or "").strip():
            return "missing client_order_id for exchange mutation"
        if int(intent.recv_window) <= 0 or int(intent.recv_window) > 60_000:
            return "invalid recvWindow"

        if not intent.required_context_versions:
            if not intent.purpose.upper().startswith("CAMPAIGN_"):
                return "missing required_context_versions"

        for tf, required in intent.required_context_versions.items():
            ctx = snapshot.context(intent.symbol, tf)
            if ctx is None:
                return f"missing context {intent.symbol} {tf}"
            if int(ctx.version) != int(required):
                return f"stale context {tf}: required={required} current={ctx.version}"

        direction = (
            "long" if intent.side == "BUY"
            else "short" if intent.side == "SELL"
            else ""
        )
        if not direction:
            return f"unsupported side {intent.side}"

        if intent.purpose.upper() in {"ENTRY", "CAMPAIGN_ENTRY"}:
            permission_tf = (intent.permission_interval or "").lower()
            permission_ctx = (
                snapshot.context(intent.symbol, permission_tf)
                if permission_tf else None
            )
            if permission_ctx is None:
                return f"missing permission context {intent.symbol} {permission_tf}"
            if direction == "long" and not permission_ctx.allow_long:
                return f"context {permission_tf} does not allow LONG"
            if direction == "short" and not permission_ctx.allow_short:
                return f"context {permission_tf} does not allow SHORT"

        if self.db is not None and hasattr(self.db, "state_get"):
            state = str(self.db.state_get("position_state", "FLAT"))
            if state == "RECONCILE_REQUIRED":
                return "RECONCILE_REQUIRED"
            if intent.campaign_id:
                campaign_state = str(
                    self.db.state_get(
                        f"campaign_state:{intent.campaign_id}",
                        "CLEAN",
                    )
                )
                if campaign_state == "RECONCILE_REQUIRED":
                    return "campaign_reconcile_required"

        return ""

    @staticmethod
    def _authoritative_status(response: Any) -> str:
        if not isinstance(response, dict):
            return ""
        return str(response.get("status", "") or "").upper().strip()

    @staticmethod
    def _known_order_status(status: str) -> bool:
        return str(status or "").upper().strip() in {
            "PENDING_NEW",
            "NEW",
            "PARTIALLY_FILLED",
            "FILLED",
            "CANCELED",
            "EXPIRED",
            "REJECTED",
        }

    def _verify_response_identity(
        self,
        intent: OrderIntent,
        response: Any,
    ) -> str:
        if not isinstance(response, dict):
            return "exchange response is not an object"

        expected_symbol = str(intent.symbol).upper()
        returned_symbol = str(response.get("symbol", "") or "").upper()
        if returned_symbol and returned_symbol != expected_symbol:
            return (
                f"exchange response symbol mismatch: "
                f"expected={expected_symbol} actual={returned_symbol}"
            )

        cid = str(response.get("clientOrderId", "") or "").strip()
        if (
            intent.order_type.upper() != "CANCEL"
            and cid
            and cid != str(intent.client_order_id).strip()
        ):
            return (
                f"exchange response clientOrderId mismatch: "
                f"expected={intent.client_order_id} actual={cid}"
            )

        side = str(response.get("side", "") or "").upper()
        if side and side != str(intent.side).upper():
            return (
                f"exchange response side mismatch: "
                f"expected={intent.side} actual={side}"
            )
        return ""

    @staticmethod
    def _positive_order_status(status: str) -> bool:
        return str(status or "").upper().strip() in {
            "PENDING_NEW",
            "NEW",
            "PARTIALLY_FILLED",
            "FILLED",
        }

    def execute(
        self,
        intent: OrderIntent,
        submit: Callable[[], Any],
        *,
        pre_submit_checks: Callable[[MarketStateSnapshot], None] | None = None,
    ) -> ExecutionResult:
        with self.context_cache.execution_lock:
            order_fsm = OrderStateMachine()
            self._persist(intent, "PENDING")
            if self.db is not None and hasattr(self.db, "save_execution_event"):
                try:
                    self.db.save_execution_event(
                        intent.intent_id,
                        "ADMISSION_STARTED",
                        dict(intent.required_context_versions),
                    )
                except Exception:
                    pass

            snapshot = self.context_cache.snapshot()
            reason = self._validate(intent, snapshot)
            if reason:
                self._record("WARNING", "execution_blocked", intent, reason)
                self._persist(intent, "BLOCKED", reason)
                return ExecutionResult(intent.intent_id, False, reason=reason)

            if pre_submit_checks is not None:
                try:
                    pre_submit_checks(snapshot)
                except Exception as exc:
                    reason = f"pre_submit_check_failed: {type(exc).__name__}: {exc}"
                    self._persist(intent, "BLOCKED", reason)
                    self._record("WARNING", "execution_blocked", intent, reason)
                    return ExecutionResult(intent.intent_id, False, reason=reason)

            if not self._claim_client_order_id(intent):
                reason = f"duplicate client_order_id: {intent.client_order_id}"
                self._persist(intent, "BLOCKED", reason)
                self._record("ERROR", "execution_idempotency_block", intent, reason)
                return ExecutionResult(intent.intent_id, False, reason=reason)

            order_fsm.transition(OrderState.PENDING_NEW)
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
                    "recv_window": int(intent.recv_window),
                },
            )
            try:
                response = submit()
            except Exception as exc:
                unknown = getattr(exc, "unknown_execution", None)
                if unknown is False:
                    try:
                        order_fsm.transition(OrderState.REJECTED)
                    except ValueError:
                        order_fsm.state = OrderState.UNKNOWN
                    self._release_client_order_id(intent)
                    reason = f"{type(exc).__name__}: {exc}"
                    self._persist(intent, "REJECTED", reason)
                    self._record("WARNING", "execution_rejected", intent, reason)
                    return ExecutionResult(intent.intent_id, False, reason=reason)

                reason = f"{type(exc).__name__}: {exc}"
                self._lock_mutations(
                    f"UNKNOWN exchange mutation for client_order_id={intent.client_order_id}: {reason}"
                )
                order_fsm.mark_unknown()
                self._persist(intent, "UNKNOWN", reason)
                self._record(
                    "ERROR",
                    "execution_unknown",
                    intent,
                    "Exchange mutation outcome is UNKNOWN; REST reconciliation is mandatory before unlock",
                    {"error": reason},
                )
                raise ExecutionAmbiguousError(
                    f"ExecutionBarrier UNKNOWN for {intent.client_order_id}; reconciliation required"
                ) from exc

            if intent.order_type.upper() == "CANCEL":
                self._persist(intent, "SUBMITTED")
                self._record("INFO", "execution_submitted", intent, "Cancel mutation accepted")
                return ExecutionResult(intent.intent_id, True, response=response)

            # Existing campaign code labels cancel/replace orders by purpose,
            # while Binance identifies the operation through response fields.
            if isinstance(response, dict) and (
                "cancelResult" in response or "newOrderResult" in response
            ):
                cancel_result = str(response.get("cancelResult", "")).upper()
                new_result = str(response.get("newOrderResult", "")).upper()
                status_ok = (
                    cancel_result in {"SUCCESS", "FAILURE", "NOT_FOUND"}
                    and new_result in {"SUCCESS", "FAILURE", "NOT_FOUND", ""}
                )
                if not status_ok:
                    reason = "cancelReplace response is not authoritative"
                    self._lock_mutations(reason)
                    order_fsm.mark_unknown()
                    self._persist(intent, "UNKNOWN", reason)
                    self._record("ERROR", "execution_unknown", intent, reason)
                    raise ExecutionAmbiguousError(reason)
            elif intent.order_type.upper() == "CANCEL_REPLACE":
                reason = "cancelReplace response is not authoritative"
                self._lock_mutations(reason)
                order_fsm.mark_unknown()
                self._persist(intent, "UNKNOWN", reason)
                self._record("ERROR", "execution_unknown", intent, reason)
                raise ExecutionAmbiguousError(reason)
            else:
                identity_error = self._verify_response_identity(
                    intent,
                    response,
                )
                if identity_error:
                    reason = identity_error
                    self._lock_mutations(reason)
                    order_fsm.mark_unknown()
                    self._persist(intent, "UNKNOWN", reason)
                    self._record("ERROR", "execution_identity_mismatch", intent, reason)
                    raise ExecutionAmbiguousError(reason)

                status = self._authoritative_status(response)
                if not status or not self._known_order_status(status):
                    reason = "exchange response lacks authoritative order status"
                    self._lock_mutations(reason)
                    order_fsm.mark_unknown()
                    self._persist(intent, "UNKNOWN", reason)
                    self._record(
                        "ERROR",
                        "execution_unknown",
                        intent,
                        reason,
                        {"response_keys": list(response.keys()) if isinstance(response, dict) else []},
                    )
                    raise ExecutionAmbiguousError(reason)
                executed = 0.0
                if isinstance(response, dict):
                    try:
                        executed = float(response.get("executedQty", 0) or 0)
                    except (TypeError, ValueError):
                        executed = 0.0
                state = order_fsm.observe_exchange_status(status, executed)
                if state == OrderState.UNKNOWN:
                    reason = f"unsupported or illegal exchange status: {status}"
                    self._lock_mutations(reason)
                    self._persist(intent, "UNKNOWN", reason)
                    self._record("ERROR", "execution_unknown", intent, reason)
                    raise ExecutionAmbiguousError(reason)
                if not self._positive_order_status(status):
                    self._release_client_order_id(intent)
                    self._persist(intent, status, f"exchange returned terminal status {status}")
                    self._record(
                        "WARNING",
                        "execution_terminal_without_admission",
                        intent,
                        f"Exchange returned terminal status {status}",
                    )
                    return ExecutionResult(
                        intent.intent_id,
                        False,
                        response=response,
                        reason=f"exchange returned terminal status {status}",
                    )

            self._persist(intent, "SUBMITTED")
            if self.db is not None and hasattr(self.db, "save_execution_event"):
                try:
                    self.db.save_execution_event(
                        intent.intent_id,
                        "BINANCE_SUBMITTED",
                        {"symbol": intent.symbol, "side": intent.side},
                    )
                except Exception:
                    pass
            self._record(
                "INFO",
                "execution_submitted",
                intent,
                "Binance accepted request",
                {"intent_id": intent.intent_id},
            )
            return ExecutionResult(intent.intent_id, True, response=response)

    def persisted_unknown_intent(self) -> OrderIntent | None:
        """Reconstruct the currently locked mutation from durable DB state."""
        if not self.mutation_locked:
            return None
        if self.db is None or not hasattr(self.db, "execution_intent_by_client_order_id"):
            return None

        match = re.search(
            r"client_order_id=([^:\s]+)",
            self.mutation_lock_reason,
        )
        if not match:
            return None
        cid = match.group(1)
        row = self.db.execution_intent_by_client_order_id(cid)
        if not row:
            return None

        try:
            versions = json.loads(
                row.get("required_context_versions_json") or "{}"
            )
        except Exception:
            versions = {}

        return OrderIntent(
            intent_id=str(row["intent_id"]),
            symbol=str(row["symbol"]).upper(),
            side=str(row["side"]).upper(),
            order_type=str(row["order_type"]).upper(),
            required_context_versions=dict(versions),
            hypothesis_id=str(row.get("hypothesis_id") or ""),
            invalidation_level=float(row.get("invalidation_level") or 0.0),
            quantity=str(row.get("quantity") or ""),
            quote_order_quantity=str(row.get("quote_order_quantity") or ""),
            client_order_id=str(row.get("client_order_id") or ""),
            purpose=str(row.get("purpose") or "ENTRY"),
            campaign_id=str(row.get("campaign_id") or ""),
            signal_id=str(row.get("signal_id") or ""),
            recv_window=int(row.get("recv_window") or 5000),
            time_in_force=str(row.get("time_in_force") or "GTC"),
            reduce_only=bool(row.get("reduce_only") or 0),
            related_order_id=str(row.get("related_order_id") or ""),
            related_order_list_id=str(row.get("related_order_list_id") or ""),
            created_at_ms=0,
            max_age_ms=0,
        )

    def reconcile_persisted_unknown(
        self,
        query: Callable[[OrderIntent], Any],
    ) -> ExecutionResult:
        """Resolve the durable UNKNOWN state without issuing a mutation."""
        intent = self.persisted_unknown_intent()
        if intent is None:
            return ExecutionResult(
                "",
                False,
                reason="no persisted UNKNOWN execution intent is available",
            )
        return self.reconcile(
            intent,
            lambda: query(intent),
        )

    def reconcile(
        self,
        intent: OrderIntent,
        query: Callable[[], Any],
    ) -> ExecutionResult:
        """Reconcile an UNKNOWN mutation using an authoritative REST query.

        ``query`` must perform a read-only exchange lookup. It must return
        ``None`` when the requested order is authoritatively NOT FOUND. No
        POST/DELETE is performed by this method and the mutation lock is only
        cleared after the read establishes a terminal or open order state.
        """
        with self.context_cache.execution_lock:
            if not self.mutation_locked:
                return ExecutionResult(
                    intent.intent_id,
                    False,
                    reason="no mutation lock is active",
                )
            try:
                response = query()
            except Exception as exc:
                if self._not_found_exception(exc):
                    self._unlock_mutations()
                    self._persist(intent, "RECONCILED_NOT_FOUND", "order not found")
                    self._record(
                        "INFO",
                        "execution_reconciled",
                        intent,
                        "REST reconciliation proved order absent; mutation lock released",
                    )
                    return ExecutionResult(
                        intent.intent_id,
                        True,
                        response=None,
                        reason="NOT_FOUND",
                    )
                reason = f"reconciliation query failed: {type(exc).__name__}: {exc}"
                self._persist(intent, "RECONCILIATION_FAILED", reason)
                self._record("ERROR", "execution_reconcile_failed", intent, reason)
                return ExecutionResult(intent.intent_id, False, reason=reason)

            if response is None:
                self._unlock_mutations()
                self._persist(intent, "RECONCILED_NOT_FOUND", "order not found")
                self._record(
                    "INFO",
                    "execution_reconciled",
                    intent,
                    "REST reconciliation proved order absent; mutation lock released",
                )
                return ExecutionResult(
                    intent.intent_id,
                    True,
                    response=None,
                    reason="NOT_FOUND",
                )

            if isinstance(response, dict):
                identity_error = self._verify_response_identity(intent, response)
                if identity_error:
                    reason = identity_error
                    self._persist(intent, "RECONCILIATION_FAILED", reason)
                    self._record(
                        "ERROR",
                        "execution_reconcile_failed",
                        intent,
                        reason,
                    )
                    return ExecutionResult(
                        intent.intent_id,
                        False,
                        response=response,
                        reason=reason,
                    )

                status = self._authoritative_status(response)
                if intent.order_type.upper() == "CANCEL":
                    if status in {
                        "CANCELED",
                        "EXPIRED",
                        "FILLED",
                        "REJECTED",
                    }:
                        self._unlock_mutations()
                        self._persist(intent, "RECONCILED", status)
                        self._record(
                            "INFO",
                            "execution_reconciled",
                            intent,
                            "Target order is no longer open after ambiguous cancel; mutation lock released",
                            {"status": status},
                        )
                        return ExecutionResult(
                            intent.intent_id,
                            True,
                            response=response,
                            reason=status,
                        )
                    reason = (
                        "ambiguous cancel remains unresolved: target order is still active"
                    )
                    self._persist(intent, "RECONCILIATION_PENDING", reason)
                    self._record(
                        "WARNING",
                        "execution_reconcile_pending",
                        intent,
                        reason,
                        {"status": status},
                    )
                    return ExecutionResult(
                        intent.intent_id,
                        False,
                        response=response,
                        reason=reason,
                    )

                if status in {
                    "PENDING_NEW",
                    "NEW",
                    "PARTIALLY_FILLED",
                    "FILLED",
                    "CANCELED",
                    "EXPIRED",
                    "REJECTED",
                } or {"cancelResult", "newOrderResult"} <= set(response):
                    self._unlock_mutations()
                    self._persist(intent, "RECONCILED", status or "CANCEL_REPLACE_RESULT")
                    self._record(
                        "INFO",
                        "execution_reconciled",
                        intent,
                        "Authoritative REST state acquired; mutation lock released",
                        {"status": status},
                    )
                    return ExecutionResult(
                        intent.intent_id,
                        True,
                        response=response,
                        reason=status or "CANCEL_REPLACE_RESULT",
                    )

            reason = "reconciliation response is not authoritative"
            self._persist(intent, "RECONCILIATION_FAILED", reason)
            self._record("ERROR", "execution_reconcile_failed", intent, reason)
            return ExecutionResult(intent.intent_id, False, reason=reason)
