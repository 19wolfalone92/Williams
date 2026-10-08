"""P0 serialized execution barrier for Williams."""
from __future__ import annotations

import time
import uuid
from dataclasses import dataclass
from typing import Any, Callable, Mapping

from market_context import ContextCache, MarketStateSnapshot
from decision_trace import DecisionTrace
from order_state_machine import OrderStateMachine, OrderLifecycleState


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
        if self.db is not None and hasattr(self.db, "save_execution_intent"):
            try:
                self.db.save_execution_intent(intent, status, reason)
            except Exception:
                pass

    def _record(self, level: str, event: str, intent: OrderIntent, message: str, raw=None) -> None:
        if self.db is not None and hasattr(self.db, "log_event"):
            try:
                self.db.log_event(level, event, message, raw or {"intent_id": intent.intent_id})
            except Exception:
                pass

    def _trace(self, intent: OrderIntent, *, stage: str, decision: str, reason: str = "", blocker: str = "", payload=None) -> None:
        if self.db is None or not hasattr(self.db, "save_decision_trace"):
            return
        try:
            self.db.save_decision_trace(DecisionTrace(
                intent_id=intent.intent_id, stage=stage, decision=decision,
                symbol=intent.symbol, purpose=intent.purpose,
                campaign_id=intent.campaign_id, signal_id=intent.signal_id,
                reason=reason, blocker=blocker,
                context_versions=dict(intent.required_context_versions),
                payload=payload or {},
            ))
        except Exception:
            pass

    def _validate(self, intent: OrderIntent, snapshot: MarketStateSnapshot) -> str:
        if intent.created_at_ms:
            age = int(time.time() * 1000) - intent.created_at_ms
            if age > intent.max_age_ms:
                return f"stale intent age={age}ms"

        # Durable exchange/runtime state has precedence over strategy-context
        # validation. When a position/campaign is already unsafe, that is the
        # first blocker the operator must see; missing/stale context is only a
        # secondary admission failure.
        if self.db is not None and hasattr(self.db, "state_get"):
            symbol_state = str(
                self.db.state_get(f"position_state:{intent.symbol}", "FLAT")
            ).upper()
            legacy_state = str(
                self.db.state_get("position_state", "FLAT")
            ).upper()
            if symbol_state == "RECONCILE_REQUIRED" or legacy_state == "RECONCILE_REQUIRED":
                return "RECONCILE_REQUIRED"

            if intent.campaign_id:
                campaign_state = str(
                    self.db.state_get(
                        f"campaign_state:{intent.campaign_id}",
                        "CLEAN",
                    )
                ).upper()
                if campaign_state == "RECONCILE_REQUIRED":
                    return "campaign_reconcile_required"

                if hasattr(self.db, "get_campaign"):
                    campaign = self.db.get_campaign(intent.campaign_id)
                    if (
                        campaign is not None
                        and str(campaign.get("state", "")).upper()
                        == "RECONCILE_REQUIRED"
                    ):
                        return "campaign_reconcile_required"

            if hasattr(self.db, "open_campaigns"):
                for campaign in self.db.open_campaigns():
                    if (
                        str(campaign.get("symbol", "")).upper()
                        == intent.symbol.upper()
                        and str(campaign.get("state", "")).upper()
                        == "RECONCILE_REQUIRED"
                    ):
                        return "symbol_campaign_reconcile_required"

            runtime_enabled = self.db.state_get("runtime_execution_enabled")
            if (
                runtime_enabled is not None
                and str(runtime_enabled).lower() not in {"1", "true", "yes"}
            ):
                return "runtime_execution_disabled"
            paused = self.db.state_get("runtime_paused")
            if paused is not None and str(paused).lower() in {"1", "true", "yes"}:
                return "paused"
            kill_latched = self.db.state_get("kill_switch_latched")
            if (
                kill_latched is not None
                and str(kill_latched).lower() in {"1", "true", "yes"}
            ):
                return "kill_switch_latched"

        direction = (
            "long"
            if intent.side == "BUY"
            else "short"
            if intent.side == "SELL"
            else ""
        )
        if not direction:
            return f"unsupported side {intent.side}"

        purpose = intent.purpose.upper()
        needs_permission = (
            purpose == "ENTRY"
            or purpose in {"CAMPAIGN_ENTRY", "CAMPAIGN_ADD_ON"}
            or purpose.endswith("_ENTRY")
            or purpose.endswith("_ADD_ON")
        )

        # Explicit context versions remain a strict dependency. An empty
        # version map is allowed only for legacy/internal callers when a live
        # permission interval is still supplied; entry/add-on operations are
        # therefore never opened without the current permission context.
        if intent.required_context_versions:
            for tf, required in intent.required_context_versions.items():
                ctx = snapshot.context(intent.symbol, tf)
                if ctx is None:
                    return f"missing context {intent.symbol} {tf}"
                if int(ctx.version) != int(required):
                    return (
                        f"stale context {tf}: "
                        f"required={required} current={ctx.version}"
                    )

        if needs_permission:
            permission_tf = (intent.permission_interval or "").lower()
            if not permission_tf:
                return "missing permission_interval for execution mutation"
            permission_ctx = snapshot.context(intent.symbol, permission_tf)
            if permission_ctx is None:
                return (
                    f"missing permission context "
                    f"{intent.symbol} {permission_tf}"
                )
            if direction == "long" and not permission_ctx.allow_long:
                return f"context {permission_tf} does not allow LONG"
            if direction == "short" and not permission_ctx.allow_short:
                return f"context {permission_tf} does not allow SHORT"

        return ""

    def execute(
        self,
        intent: OrderIntent,
        submit: Callable[[], Any],
        *,
        pre_submit_checks: Callable[[MarketStateSnapshot], None] | None = None,
    ) -> ExecutionResult:
        with self.context_cache.execution_lock:
            state_machine = OrderStateMachine(intent.intent_id)
            state_machine.transition(OrderLifecycleState.ADMISSION)
            self._persist(intent, "PENDING")
            self._trace(intent, stage="ADMISSION", decision="STARTED", payload={"state": state_machine.state.value})
            if self.db is not None and hasattr(self.db, "save_execution_event"):
                try:
                    self.db.save_execution_event(intent.intent_id, "ADMISSION_STARTED", dict(intent.required_context_versions))
                except Exception:
                    pass
            snapshot = self.context_cache.snapshot()
            reason = self._validate(intent, snapshot)
            if reason:
                state_machine.mark_blocked(reason)
                self._trace(intent, stage="VALIDATION", decision="BLOCKED", reason=reason, blocker=reason, payload={"state": state_machine.state.value})
                self._persist(intent, "BLOCKED", reason)
                self._record("WARNING", "execution_blocked", intent, reason)
                return ExecutionResult(intent.intent_id, False, reason=reason)

            if pre_submit_checks is not None:
                try:
                    pre_submit_checks(snapshot)
                except Exception as exc:
                    reason = f"pre_submit_check_failed: {type(exc).__name__}: {exc}"
                    state_machine.mark_blocked(reason)
                    self._trace(intent, stage="PRE_SUBMIT", decision="BLOCKED", reason=reason, blocker=reason, payload={"state": state_machine.state.value})
                    self._persist(intent, "BLOCKED", reason)
                    self._record("WARNING", "execution_blocked", intent, reason)
                    return ExecutionResult(intent.intent_id, False, reason=reason)

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
            state_machine.mark_submission_pending()
            self._trace(intent, stage="PRE_SUBMIT", decision="ADMITTED", payload={"state": state_machine.state.value})
            try:
                response = submit()
            except Exception as exc:
                state_machine.mark_ambiguous(f"{type(exc).__name__}: {exc}")
                self._trace(intent, stage="BINANCE_SUBMIT", decision="AMBIGUOUS", reason=str(exc), blocker="EXCHANGE_OUTCOME_UNKNOWN", payload={"state": state_machine.state.value})
                self._persist(intent, "AMBIGUOUS", f"{type(exc).__name__}: {exc}")
                self._record(
                    "ERROR",
                    "execution_ambiguous",
                    intent,
                    "Binance submission failed or timed out; reconciliation required",
                    {"error": f"{type(exc).__name__}: {exc}"},
                )
                raise

            # A successful mutation response must identify the mutation. A
            # transport-success response without an order/list identity is not
            # evidence that the exchange state is knowable.
            if (
                intent.order_type not in {"CANCEL", "CANCEL_REPLACE"}
                and isinstance(response, dict)
                and response.get("orderId") is None
                and response.get("clientOrderId") is None
                and response.get("orderListId") is None
            ):
                state_machine.mark_reconcile_required("submission response has no exchange identity")
                self._trace(intent, stage="BINANCE_ACK", decision="RECONCILE_REQUIRED", reason="submission response has no exchange identity", blocker="MISSING_EXCHANGE_IDENTITY", payload={"state": state_machine.state.value})
                self._persist(intent, "AMBIGUOUS", "submission response has no exchange identity")
                self._record(
                    "ERROR",
                    "execution_ambiguous",
                    intent,
                    "Binance response contained no order identity; reconciliation required",
                    {"response": response},
                )
                raise RuntimeError(
                    "Execution mutation response is missing exchange identity; reconciliation required"
                )

            try:
                state_machine.transition(OrderLifecycleState.SUBMITTED)
            except ValueError:
                pass
            if isinstance(response, dict) and response.get("status"):
                try:
                    state_machine.record_exchange_status(str(response.get("status")))
                except ValueError:
                    pass
            self._trace(intent, stage="BINANCE_ACK", decision="SUBMITTED", payload={
                "state": state_machine.state.value,
                "status": response.get("status") if isinstance(response, dict) else "",
                "order_id": response.get("orderId") if isinstance(response, dict) else None,
                "order_list_id": response.get("orderListId") if isinstance(response, dict) else None,
            })
            self._persist(intent, "SUBMITTED")
            if self.db is not None and hasattr(self.db, "save_execution_event"):
                try:
                    self.db.save_execution_event(intent.intent_id, "BINANCE_SUBMITTED", {"symbol": intent.symbol, "side": intent.side})
                except Exception:
                    pass
            self._record("INFO", "execution_submitted", intent, "Binance accepted request", {
                "intent_id": intent.intent_id,
            })
            return ExecutionResult(intent.intent_id, True, response=response)
