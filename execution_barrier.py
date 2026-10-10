"""P0 serialized execution barrier for Williams."""
from __future__ import annotations

import time
import uuid
from dataclasses import dataclass
from typing import Any, Callable, Mapping


class ExecutionPersistenceError(RuntimeError):
    """Durable execution evidence could not be written safely."""

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
    target_order_id: str = ""
    target_order_list_id: str = ""
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
        self._poisoned = False

    @staticmethod
    def _risk_reducing(intent: OrderIntent) -> bool:
        purpose = str(intent.purpose or "").upper()
        side = str(intent.side or "").upper()
        order_type = str(intent.order_type or "").upper()
        if side == "SELL" and any(
            token in purpose for token in ("PROTECTION", "EXIT", "EMERGENCY", "STOP")
        ):
            return True
        # Exiting a campaign uses a cancel-then-sell sequence. This narrowly
        # permits cancellation of the campaign's own protection; restart recovery
        # must re-establish protection if the following sell is interrupted.
        return order_type in {"CANCEL", "CANCEL_OCO"} and purpose == "CAMPAIGN_PROTECTION_CANCEL"

    def _persist(self, intent: OrderIntent, status: str, reason: str = "") -> None:
        if self.db is None or not hasattr(self.db, "save_execution_intent"):
            raise ExecutionPersistenceError("durable execution-intent store is unavailable")
        try:
            self.db.save_execution_intent(intent, status, reason)
        except Exception as exc:
            raise ExecutionPersistenceError(
                f"cannot persist execution intent {intent.intent_id} status={status}; exchange mutation blocked"
            ) from exc

    def _record(self, level: str, event: str, intent: OrderIntent, message: str, raw=None) -> None:
        if self.db is None or not hasattr(self.db, "log_event"):
            raise ExecutionPersistenceError("durable execution event journal is unavailable")
        try:
            self.db.log_event(level, event, message, raw or {"intent_id": intent.intent_id})
        except Exception as exc:
            raise ExecutionPersistenceError(
                f"cannot persist execution event {event} for intent {intent.intent_id}"
            ) from exc

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

        risk_reducing = self._risk_reducing(intent)
        if self._poisoned and not risk_reducing:
            return "execution_barrier_poisoned_reconciliation_required"

        if self.db is None or not hasattr(self.db, "state_get"):
            return "durable recovery gate is unavailable"
        recovery_state = str(self.db.state_get("recovery_state", "REQUIRED")).upper()
        if recovery_state != "READY" and not risk_reducing:
            return f"RECOVERY_REQUIRED:{recovery_state}"
        state = str(self.db.state_get("position_state", "RECONCILE_REQUIRED")).upper()
        if state == "RECONCILE_REQUIRED" and not risk_reducing:
            return "RECONCILE_REQUIRED"
        campaign_state = str(
            self.db.state_get(f"campaign_state:{intent.campaign_id}", "CLEAN")
        ).upper() if intent.campaign_id else "CLEAN"
        if campaign_state == "RECONCILE_REQUIRED" and not risk_reducing:
            return "campaign_reconcile_required"

        return ""

    def _assert_fresh_intent_identity(self, intent: OrderIntent) -> None:
        """Prevent reusing a durable intent or clientOrderId after any outcome."""
        if not str(intent.client_order_id or "").strip():
            raise ExecutionPersistenceError(
                "stable clientOrderId is required before any exchange mutation"
            )
        if self.db is None or not hasattr(self.db, "conn"):
            raise ExecutionPersistenceError(
                "durable identity lookup is unavailable; refusing order submission"
            )
        try:
            by_intent = self.db.conn.execute(
                "SELECT status FROM execution_intents WHERE intent_id=? LIMIT 1",
                (intent.intent_id,),
            ).fetchone()
            if by_intent is not None:
                raise ExecutionPersistenceError(
                    "intent ID already exists; reconcile the prior intent instead of resubmitting"
                )
            if intent.client_order_id:
                by_client = self.db.conn.execute(
                    "SELECT intent_id,status FROM execution_intents "
                    "WHERE symbol=? AND client_order_id=? LIMIT 1",
                    (intent.symbol.upper(), intent.client_order_id),
                ).fetchone()
                if by_client is not None:
                    raise ExecutionPersistenceError(
                        "clientOrderId already belongs to a durable intent; reconcile instead of resubmitting"
                    )
        except ExecutionPersistenceError:
            raise
        except Exception as exc:
            raise ExecutionPersistenceError(
                "could not verify intent/clientOrderId uniqueness; refusing order submission"
            ) from exc

    def execute(
        self,
        intent: OrderIntent,
        submit: Callable[[], Any],
        *,
        pre_submit_checks: Callable[[MarketStateSnapshot], None] | None = None,
    ) -> ExecutionResult:
        with self.context_cache.execution_lock:
            # Identity reuse is never a retry mechanism. Even a risk-reducing
            # order can be duplicated if the previous response was ambiguous.
            self._assert_fresh_intent_identity(intent)
            order_fsm = OrderStateMachine()
            order_fsm.transition(OrderState.ADMISSION)

            # This durable write is a hard precondition. If it fails, submit()
            # is never called and no exchange mutation can have occurred.
            self._persist(intent, "PENDING")
            if self.db is None or not hasattr(self.db, "save_execution_event"):
                raise ExecutionPersistenceError("durable execution-event store is unavailable")
            try:
                self.db.save_execution_event(
                    intent.intent_id, "ADMISSION_STARTED",
                    dict(intent.required_context_versions),
                )
            except Exception as exc:
                raise ExecutionPersistenceError(
                    f"cannot persist admission event for intent {intent.intent_id}; submission blocked"
                ) from exc

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

            order_fsm.transition(OrderState.SUBMITTING)
            self._record(
                "INFO", "execution_admitted", intent,
                "Final validation passed; submitting to Binance",
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
                # Keep the durable intent even when the HTTP outcome is unknown.
                # Recovery must query the same clientOrderId; never blind-resubmit.
                self._poisoned = True
                try:
                    self._persist(intent, "AMBIGUOUS", f"{type(exc).__name__}: {exc}")
                    self._record(
                        "ERROR", "execution_ambiguous", intent,
                        "Binance submission failed or timed out; reconciliation required",
                        {"error": f"{type(exc).__name__}: {exc}"},
                    )
                    self.db.save_execution_event(
                        intent.intent_id, "SUBMISSION_AMBIGUOUS",
                        {"error": f"{type(exc).__name__}: {exc}"},
                    )
                except Exception as journal_exc:
                    raise ExecutionPersistenceError(
                        "submission outcome is ambiguous and durable journal update failed"
                    ) from journal_exc
                raise

            if intent.order_type not in {"CANCEL", "CANCEL_OCO"}:
                status = str(response.get("status", "") if isinstance(response, dict) else "").upper()
                if status:
                    order_fsm.observe_exchange_status(status, float(response.get("executedQty", 0) or 0))
                elif (
                    intent.order_type == "OCO"
                    and isinstance(response, dict)
                    and response.get("orderListId") is not None
                    and str(response.get("listClientOrderId") or intent.client_order_id) == intent.client_order_id
                ):
                    order_fsm.state = OrderState.OPEN
                elif isinstance(response, dict) and str(response.get("newOrderResult", "")).upper() == "SUCCESS":
                    order_fsm.state = OrderState.OPEN
                else:
                    self._poisoned = True
                    reason = "exchange response did not contain authoritative order state"
                    try:
                        self._persist(intent, "AMBIGUOUS", reason)
                        self._record(
                            "ERROR", "execution_ambiguous", intent, reason,
                            {"response_keys": list(response.keys()) if isinstance(response, dict) else []},
                        )
                    except Exception as journal_exc:
                        raise ExecutionPersistenceError(
                            "exchange accepted an operation but authoritative state/journal are unresolved"
                        ) from journal_exc
                    raise RuntimeError(f"ExecutionBarrier: {reason}")

            # If a post-response write fails, the pre-submit PENDING intent remains
            # durable. Poison the in-memory barrier until reconciliation/restart.
            try:
                self._persist(intent, "SUBMITTED")
                self.db.save_execution_event(
                    intent.intent_id, "EXCHANGE_RESPONSE",
                    response if isinstance(response, dict) else {"response": str(response)},
                )
                self._record(
                    "INFO", "execution_result", intent,
                    "Exchange response persisted",
                    {
                        "order_state": order_fsm.state.value,
                        "client_order_id": intent.client_order_id,
                        "order_id": response.get("orderId") if isinstance(response, dict) else None,
                    },
                )
            except Exception as exc:
                self._poisoned = True
                raise ExecutionPersistenceError(
                    "exchange operation may have succeeded but its result was not durably recorded; reconciliation required"
                ) from exc

            return ExecutionResult(intent.intent_id, True, response=response)
