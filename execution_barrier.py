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

    def _persist(self, intent: OrderIntent, status: str, reason: str = "", exchange_order=None) -> None:
        """Persist safety-critical order state; failures must reach the caller."""
        if self.db is None:
            raise RuntimeError("durable execution-intent persistence is unavailable: database is not configured")
        save_intent = getattr(self.db, "save_execution_intent", None)
        if not callable(save_intent):
            raise RuntimeError("durable execution-intent persistence is unavailable: save_execution_intent is missing")
        import inspect
        parameters = inspect.signature(save_intent).parameters.values()
        supports_metadata = any(p.name == 'exchange_order' or p.kind == inspect.Parameter.VAR_KEYWORD for p in parameters)
        if supports_metadata:
            save_intent(intent, status, reason, exchange_order=exchange_order)
        else:
            # Keep lightweight legacy test adapters compatible; the production
            # Database implementation accepts and durably stores exchange evidence.
            save_intent(intent, status, reason)

    def _mark_reconciliation_required(self, intent: OrderIntent) -> None:
        """Durably block later order actions until exchange reconciliation completes."""
        if self.db is None or not callable(getattr(self.db, "state_set", None)):
            raise RuntimeError("durable RECONCILE_REQUIRED state storage is unavailable")
        self.db.state_set("position_state", "RECONCILE_REQUIRED")
        if intent.campaign_id:
            self.db.state_set(f"campaign_state:{intent.campaign_id}", "RECONCILE_REQUIRED")

    def _record(self, level: str, event: str, intent: OrderIntent, message: str, raw=None) -> None:
        if self.db is not None and hasattr(self.db, "log_event"):
            try:
                self.db.log_event(level, event, message, raw or {"intent_id": intent.intent_id})
            except Exception:
                pass

    def _validate(self, intent: OrderIntent, snapshot: MarketStateSnapshot) -> str:
        if intent.created_at_ms:
            age = int(time.time() * 1000) - intent.created_at_ms
            if age > intent.max_age_ms:
                return f"stale intent age={age}ms"

        # Campaign orders may be armed from a freshly constructed signal before
        # the context service has assigned persistent versions.  They still
        # require at least one live snapshot; the campaign pre-submit validator
        # is responsible for the exact strategy/risk re-check.
        if not intent.required_context_versions:
            if not intent.purpose.upper().startswith("CAMPAIGN_"):
                return "missing required_context_versions"
        for tf, required in intent.required_context_versions.items():
            ctx = snapshot.context(intent.symbol, tf)
            if ctx is None:
                return f"missing context {intent.symbol} {tf}"
            if int(ctx.version) != int(required):
                return f"stale context {tf}: required={required} current={ctx.version}"

        direction = "long" if intent.side == "BUY" else "short" if intent.side == "SELL" else ""
        if not direction:
            return f"unsupported side {intent.side}"

        # All declared TFs are version dependencies, but the permission
        # decision belongs to one operative/entry timeframe. Higher TFs provide
        # structural context and must not be required to emit a duplicate trigger.
        if intent.purpose.upper() == "ENTRY":
            permission_tf = (intent.permission_interval or "").lower()
            permission_ctx = snapshot.context(intent.symbol, permission_tf) if permission_tf else None
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
            campaign_state = str(
                self.db.state_get(
                    f"campaign_state:{intent.campaign_id}",
                    "CLEAN"
                )
            ) if intent.campaign_id else "CLEAN"
            if campaign_state == "RECONCILE_REQUIRED":
                return "campaign_reconcile_required"

        return ""

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
            # Check the persistence API before claiming the id, then atomically
            # claim it in durable storage. This survives process restarts and
            # prevents two processes from submitting the same intent_id.
            if self.db is None or not callable(getattr(self.db, "save_execution_intent", None)):
                reason = "durable execution-intent persistence is unavailable"
                self._record("ERROR", "execution_blocked", intent, reason)
                return ExecutionResult(intent.intent_id, False, reason=reason)
            unresolved_check = getattr(self.db, "has_unresolved_execution_intents", None)
            if not callable(unresolved_check):
                reason = "durable unresolved-intent inspection is unavailable; refusing submission"
                self._record("ERROR", "execution_blocked", intent, reason)
                return ExecutionResult(intent.intent_id, False, reason=reason)
            try:
                unresolved = unresolved_check()
            except Exception as exc:
                reason = f"unresolved_intent_read_failed: {type(exc).__name__}: {exc}"
                self._record("ERROR", "execution_blocked", intent, reason)
                return ExecutionResult(intent.intent_id, False, reason=reason)
            if unresolved:
                reason = "unresolved PENDING/AMBIGUOUS execution intent exists; reconciliation required before retry"
                self._record("WARNING", "execution_reconcile_required", intent, reason)
                return ExecutionResult(intent.intent_id, False, reason=reason)
            claim = getattr(self.db, "try_claim_state", None)
            if not callable(claim):
                reason = "durable execution-intent claim is unavailable; refusing submission"
                self._record("ERROR", "execution_blocked", intent, reason)
                return ExecutionResult(intent.intent_id, False, reason=reason)
            try:
                claimed = claim(f"execution_intent_claim:{intent.intent_id}", "CLAIMED")
            except Exception as exc:
                reason = f"execution_intent_claim_failed: {type(exc).__name__}: {exc}"
                self._record("ERROR", "execution_blocked", intent, reason)
                return ExecutionResult(intent.intent_id, False, reason=reason)
            if not claimed:
                reason = "duplicate execution intent already claimed; reconciliation required before retry"
                self._record("WARNING", "execution_duplicate_blocked", intent, reason)
                return ExecutionResult(intent.intent_id, False, reason=reason)
            try:
                self._persist(intent, "PENDING")
            except Exception as exc:
                # Fail closed: no exchange call is allowed until durable intent
                # admission succeeds. There is no exchange mutation to reconcile.
                reason = f"execution_intent_persistence_failed: {type(exc).__name__}: {exc}"
                try:
                    order_fsm.transition(OrderState.CANCELED)
                except ValueError:
                    order_fsm.state = OrderState.REJECTED
                self._record("ERROR", "execution_blocked", intent, reason)
                return ExecutionResult(intent.intent_id, False, reason=reason)
            if self.db is not None and hasattr(self.db, "save_execution_event"):
                try:
                    self.db.save_execution_event(intent.intent_id, "ADMISSION_STARTED", dict(intent.required_context_versions))
                except Exception:
                    pass
            try:
                snapshot = self.context_cache.snapshot()
                reason = self._validate(intent, snapshot)
            except Exception as exc:
                reason = f"execution_state_read_failed: {type(exc).__name__}: {exc}"
                try:
                    self._mark_reconciliation_required(intent)
                except Exception as state_exc:
                    reason += (
                        "; reconciliation_state_persistence_failed: "
                        f"{type(state_exc).__name__}: {state_exc}"
                    )
                reason += "; no submission attempted; reconciliation required before retry"
                try:
                    self._persist(intent, "BLOCKED", reason)
                except Exception as persist_exc:
                    reason += (
                        "; blocked_intent_persistence_failed: "
                        f"{type(persist_exc).__name__}: {persist_exc}"
                    )
                self._record("ERROR", "execution_state_read_failed", intent, reason)
                return ExecutionResult(intent.intent_id, False, reason=reason)
            if reason:
                try:
                    order_fsm.transition(OrderState.CANCELED)
                except ValueError:
                    order_fsm.state = OrderState.RECONCILE_REQUIRED
                self._record("WARNING", "execution_blocked", intent, reason)
                self._persist(intent, "BLOCKED", reason)
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
                ambiguity_reason = f"{type(exc).__name__}: {exc}"
                try:
                    self._persist(intent, "AMBIGUOUS", ambiguity_reason)
                except Exception as persist_exc:
                    ambiguity_reason += (
                        "; ambiguous_intent_persistence_failed: "
                        f"{type(persist_exc).__name__}: {persist_exc}"
                    )
                try:
                    self._mark_reconciliation_required(intent)
                except Exception as state_exc:
                    ambiguity_reason += (
                        "; reconciliation_state_persistence_failed: "
                        f"{type(state_exc).__name__}: {state_exc}"
                    )
                ambiguity_reason += "; exchange reconciliation is mandatory before any retry"
                self._record(
                    "ERROR",
                    "execution_ambiguous",
                    intent,
                    "Binance submission failed or timed out; reconciliation required",
                    {"error": ambiguity_reason},
                )
                if "persistence_failed" in ambiguity_reason:
                    raise RuntimeError(
                        "submission outcome is ambiguous and reconciliation must be completed "
                        f"before any retry: {ambiguity_reason}"
                    ) from exc
                raise

            if intent.order_type != "CANCEL":
                status = str(
                    response.get("status", "")
                    if isinstance(response, dict)
                    else ""
                ).upper()
                if intent.order_type.upper() == "OCO":
                    list_status = str(response.get("listOrderStatus", "")).upper() if isinstance(response, dict) else ""
                    list_client_id = str(response.get("listClientOrderId", "")) if isinstance(response, dict) else ""
                    reports = response.get("orderReports", response.get("orders", [])) if isinstance(response, dict) else []
                    if list_client_id == intent.client_order_id and list_status in {"EXECUTING", "ALL_DONE", "REJECT"} and isinstance(reports, list) and len(reports) >= 2:
                        status = "REJECTED" if list_status == "REJECT" else "NEW"
                    else:
                        status = ""
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
                    order_fsm.state = OrderState.OPEN
                else:
                    order_fsm.state = OrderState.RECONCILE_REQUIRED
                    reason = "exchange response did not contain authoritative order state"
                    persistence_errors = []
                    try:
                        self._persist(intent, "AMBIGUOUS", reason)
                    except Exception as persist_exc:
                        persistence_errors.append(
                            "ambiguous_intent_persistence_failed: "
                            f"{type(persist_exc).__name__}: {persist_exc}"
                        )
                    try:
                        self._mark_reconciliation_required(intent)
                    except Exception as state_exc:
                        persistence_errors.append(
                            "reconciliation_state_persistence_failed: "
                            f"{type(state_exc).__name__}: {state_exc}"
                        )
                    if persistence_errors:
                        reason += "; " + "; ".join(persistence_errors)
                    reason += "; exchange reconciliation is mandatory before any retry"
                    self._record(
                        "ERROR",
                        "execution_ambiguous",
                        intent,
                        "Exchange response lacks authoritative order state; reconciliation required",
                        {
                            "response_keys": list(response.keys()) if isinstance(response, dict) else [],
                            "error": reason,
                        },
                    )
                    raise RuntimeError(f"ExecutionBarrier: {reason}")
            try:
                final_status = "REJECTED" if intent.order_type.upper() == "OCO" and isinstance(response, dict) and str(response.get("listOrderStatus", "")).upper() == "REJECT" else "SUBMITTED"
                self._persist(intent, final_status, exchange_order=response if isinstance(response, dict) else None)
            except Exception as exc:
                # The exchange may have accepted/mutated the order. Never retry
                # submit here; preserve the ambiguity for reconciliation.
                order_fsm.state = OrderState.RECONCILE_REQUIRED
                reason = (
                    "post_submit_persistence_failed: "
                    f"{type(exc).__name__}: {exc}"
                )
                try:
                    self._persist(intent, "AMBIGUOUS", reason)
                except Exception as ambiguous_exc:
                    reason += (
                        "; ambiguous_intent_persistence_failed: "
                        f"{type(ambiguous_exc).__name__}: {ambiguous_exc}"
                    )
                try:
                    self._mark_reconciliation_required(intent)
                except Exception as state_exc:
                    reason += (
                        "; reconciliation_state_persistence_failed: "
                        f"{type(state_exc).__name__}: {state_exc}"
                    )
                reason += "; exchange reconciliation is mandatory before any retry"
                self._record(
                    "ERROR",
                    "execution_reconcile_required",
                    intent,
                    reason,
                    {"response_received": True},
                )
                return ExecutionResult(
                    intent.intent_id, False, response=response, reason=reason
                )
            if final_status == "REJECTED":
                self._record("WARNING", "execution_rejected", intent, "Binance returned an authoritative OCO rejection")
                return ExecutionResult(intent.intent_id, False, response=response, reason="Binance rejected the OCO order list")
            if self.db is not None and hasattr(self.db, "save_execution_event"):
                try:
                    self.db.save_execution_event(intent.intent_id, "BINANCE_SUBMITTED", {"symbol": intent.symbol, "side": intent.side})
                except Exception:
                    pass
            self._record("INFO", "execution_submitted", intent, "Binance accepted request", {
                "intent_id": intent.intent_id,
            })
            return ExecutionResult(intent.intent_id, True, response=response)
