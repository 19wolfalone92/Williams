"""P0 serialized execution barrier for Williams."""
from __future__ import annotations

import time
import uuid
from dataclasses import dataclass
from typing import Any, Callable, Mapping


class ExecutionPersistenceError(RuntimeError):
    """Critical execution state could not be durably recorded; fail closed."""


from market_context import ContextCache, MarketStateSnapshot
from order_state_machine import OrderState, OrderStateMachine
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
        self._attempted_intents: set[str] = set()
        self._attempted_client_order_ids: set[str] = set()

    def _persist(self, intent: OrderIntent, status: str, reason: str = "") -> None:
        """Persist safety-critical intent state; never hide a durability failure."""
        if self.db is not None and hasattr(self.db, "save_execution_intent"):
            self.db.save_execution_intent(intent, status, reason)

    @staticmethod
    def _safe_error_reason(prefix: str, exc: BaseException) -> str:
        # Exception text/body can contain credentials, signatures or headers.
        return f"{prefix}: {type(exc).__name__}"

    def _existing_intent(self, intent: OrderIntent):
        if self.db is None or not hasattr(self.db, "find_execution_intent"):
            return None
        return self.db.find_execution_intent(intent.intent_id, intent.client_order_id)

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
            key = str(intent.intent_id)
            client_key = str(intent.client_order_id or "")
            if key in self._attempted_intents or (client_key and client_key in self._attempted_client_order_ids):
                return ExecutionResult(intent.intent_id, False, reason="duplicate_or_unresolved_intent_requires_reconciliation")
            try:
                prior = self._existing_intent(intent)
            except Exception as exc:
                return ExecutionResult(intent.intent_id, False, reason=self._safe_error_reason("intent_lookup_failed", exc))
            if prior:
                prior_status = str(prior.get("status", "UNKNOWN")).upper()
                return ExecutionResult(intent.intent_id, False, reason=f"prior_intent_exists:{prior_status}; reconciliation_required")
            # The durable write-ahead intent is a hard precondition for any
            # exchange mutation. If SQLite cannot commit it, do not call submit.
            try:
                self._persist(intent, "PENDING")
                # Reusing this intent after any later failure requires recovery,
                # even when a subsequent BLOCKED/AMBIGUOUS write also fails.
                self._attempted_intents.add(key)
                if client_key:
                    self._attempted_client_order_ids.add(client_key)
            except Exception as exc:
                reason = f"intent_persistence_failed: {type(exc).__name__}"
                self._record("ERROR", "execution_blocked", intent, reason)
                return ExecutionResult(intent.intent_id, False, reason=reason)

            if self.db is not None and hasattr(self.db, "save_execution_event"):
                try:
                    self.db.save_execution_event(
                        intent.intent_id,
                        "ADMISSION_STARTED",
                        dict(intent.required_context_versions),
                    )
                except Exception as exc:
                    reason = self._safe_error_reason("admission_event_persistence_failed", exc)
                    self._record("ERROR", "execution_blocked", intent, reason)
                    try:
                        self._persist(intent, "BLOCKED", reason)
                    except Exception:
                        raise ExecutionPersistenceError("admission_event_and_blocked_state_persistence_failed") from None
                    return ExecutionResult(intent.intent_id, False, reason=reason)
            snapshot = self.context_cache.snapshot()
            reason = self._validate(intent, snapshot)
            if reason:
                try:
                    order_fsm.transition(OrderState.CANCELED)
                except ValueError:
                    order_fsm.state = OrderState.RECONCILE_REQUIRED
                self._record("WARNING", "execution_blocked", intent, reason)
                try:
                    self._persist(intent, "BLOCKED", reason)
                except Exception:
                    raise ExecutionPersistenceError("validation_blocked_state_persistence_failed") from None
                return ExecutionResult(intent.intent_id, False, reason=reason)

            if pre_submit_checks is not None:
                try:
                    pre_submit_checks(snapshot)
                except Exception as exc:
                    try:
                        order_fsm.transition(OrderState.CANCELED)
                    except ValueError:
                        order_fsm.state = OrderState.RECONCILE_REQUIRED
                    reason = self._safe_error_reason("pre_submit_check_failed", exc)
                    try:
                        self._persist(intent, "BLOCKED", reason)
                    except Exception:
                        raise ExecutionPersistenceError("pre_submit_check_and_blocked_state_persistence_failed") from None
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
            # Once submit is called, this intent/client ID cannot be replayed
            # in-process. On restart, durable PENDING/AMBIGUOUS must reconcile.
            try:
                response = submit()
            except Exception as exc:
                order_fsm.state = OrderState.AMBIGUOUS
                safe_reason = self._safe_error_reason("submit_failed_unknown_result", exc)
                try:
                    self._persist(intent, "AMBIGUOUS", safe_reason)
                except Exception:
                    self._record("CRITICAL", "execution_reconcile_required", intent,
                                 "Submit outcome unknown and ambiguity state persistence failed")
                    raise ExecutionPersistenceError("submit_outcome_unknown_and_ambiguous_state_not_persisted") from None
                self._record(
                    "ERROR", "execution_ambiguous", intent,
                    "Binance submission outcome unknown; reconciliation required",
                    {"error_type": type(exc).__name__, "client_order_id": intent.client_order_id},
                )
                raise RuntimeError("submit_outcome_unknown_reconciliation_required") from None

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
                    order_fsm.state = OrderState.OPEN
                elif (
                    intent.order_type.upper() == "OCO"
                    and isinstance(response, dict)
                    and response.get("orderListId") is not None
                    and isinstance(response.get("orderReports"), list)
                    and len(response.get("orderReports")) >= 2
                ):
                    order_fsm.state = OrderState.OPEN
                else:
                    order_fsm.state = OrderState.RECONCILE_REQUIRED
                    self._persist(
                        intent,
                        "AMBIGUOUS",
                        "exchange response did not contain authoritative order state",
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
            except Exception:
                self._record("CRITICAL", "execution_reconcile_required", intent,
                             "Exchange response received but local submitted state persistence failed")
                raise ExecutionPersistenceError("exchange_response_received_local_state_persistence_failed") from None
            if self.db is not None and hasattr(self.db, "save_execution_event"):
                try:
                    self.db.save_execution_event(intent.intent_id, "BINANCE_SUBMITTED", {"symbol": intent.symbol, "side": intent.side})
                except Exception:
                    self._record("CRITICAL", "execution_reconcile_required", intent,
                                 "Exchange response received but submitted event persistence failed")
                    raise ExecutionPersistenceError("submitted_event_persistence_failed_reconciliation_required") from None
            self._record("INFO", "execution_submitted", intent, "Binance accepted request", {
                "intent_id": intent.intent_id,
            })
            return ExecutionResult(intent.intent_id, True, response=response)
