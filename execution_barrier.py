"""P0 serialized execution barrier for Williams."""
from __future__ import annotations

import time
import uuid
from dataclasses import dataclass
from typing import Any, Callable, Mapping

from market_context import ContextCache, MarketStateSnapshot
from order_state_machine import OrderState, OrderStateMachine
from execution_permit import mutation_permit
from preflight_gate import PreflightCheckService


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

    def __init__(self, context_cache: ContextCache, db=None, *, preflight_provider: Callable[[], Mapping[str, Any]] | None = None, max_preflight_age_ms: int = 2_000) -> None:
        self.context_cache = context_cache
        self.db = db
        self.preflight_provider = preflight_provider
        self.max_preflight_age_ms = max(1, int(max_preflight_age_ms))

    def _persist(self, intent: OrderIntent, status: str, reason: str = "") -> bool:
        """Persist execution state; never treat missing/failed storage as success."""
        if self.db is None or not hasattr(self.db, "save_execution_intent"):
            return False
        try:
            result = self.db.save_execution_intent(intent, status, reason)
            return result is not False
        except Exception:
            return False

    def _record(self, level: str, event: str, intent: OrderIntent, message: str, raw=None) -> None:
        if self.db is not None and hasattr(self.db, "log_event"):
            try:
                self.db.log_event(level, event, message, raw or {"intent_id": intent.intent_id})
            except Exception:
                pass

    def _mark_reconcile_required(self, intent: OrderIntent) -> None:
        if self.db is None or not hasattr(self.db, "state_set"):
            return
        try:
            self.db.state_set("position_state", "RECONCILE_REQUIRED")
            if intent.campaign_id:
                self.db.state_set(f"campaign_state:{intent.campaign_id}", "RECONCILE_REQUIRED")
        except Exception:
            pass

    def _verify_preflight(self) -> str:
        if not callable(self.preflight_provider):
            return "mandatory_preflight_provider_missing"
        try:
            report = self.preflight_provider()
        except Exception as exc:
            return f"preflight_exception:{type(exc).__name__}"
        if not isinstance(report, Mapping):
            return "preflight_result_missing_or_invalid"
        if report.get("ready") is not True:
            return "preflight_not_ready"
        if report.get("environment") != "TESTNET" or report.get("product") != "SPOT":
            return "preflight_environment_or_product_mismatch"
        checked_at = report.get("checked_at_ms")
        if not isinstance(checked_at, (int, float)):
            return "preflight_timestamp_missing"
        age_ms = int(time.time() * 1000) - int(checked_at)
        if age_ms < 0 or age_ms > self.max_preflight_age_ms:
            return "preflight_result_stale_or_future"
        checks = report.get("checks")
        if not isinstance(checks, Mapping):
            return "preflight_checks_missing"
        required = set(PreflightCheckService.REQUIRED_CHECKS)
        if not required.issubset(checks.keys()):
            return "preflight_required_checks_missing"
        if any(checks.get(name) != "PASS" for name in required):
            return "preflight_required_check_not_pass"
        if report.get("requires_reconciliation") is not False:
            return "preflight_reconciliation_required_or_unknown"
        return ""

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
            if not self._persist(intent, "PENDING"):
                return ExecutionResult(
                    intent.intent_id,
                    False,
                    reason="execution_intent_persistence_failed",
                )
            if self.db is None or not hasattr(self.db, "save_execution_event"):
                self._persist(intent, "BLOCKED", "execution_event_persistence_unavailable")
                return ExecutionResult(intent.intent_id, False, reason="execution_event_persistence_unavailable")
            try:
                event_result = self.db.save_execution_event(intent.intent_id, "ADMISSION_STARTED", dict(intent.required_context_versions))
                if event_result is False:
                    raise RuntimeError("event persistence returned false")
            except Exception as exc:
                self._persist(intent, "BLOCKED", f"execution_event_persistence_failed:{type(exc).__name__}")
                return ExecutionResult(intent.intent_id, False, reason="execution_event_persistence_failed")
            snapshot = self.context_cache.snapshot()
            reason = self._validate(intent, snapshot)
            if reason:
                try:
                    order_fsm.transition(OrderState.CANCELED)
                except ValueError:
                    order_fsm.state = OrderState.RECONCILE_REQUIRED
                self._record("WARNING", "execution_blocked", intent, reason)
                self._persist(intent, "BLOCKED", reason)
                return ExecutionResult(intent.intent_id, False, reason=reason)

            if intent.purpose.upper() == "ENTRY" and pre_submit_checks is None:
                self._persist(intent, "BLOCKED", "missing_pre_submit_gate")
                return ExecutionResult(
                    intent.intent_id,
                    False,
                    reason="missing_pre_submit_gate",
                )

            if pre_submit_checks is not None:
                try:
                    pre_submit_checks(snapshot)
                except Exception as exc:
                    try:
                        order_fsm.transition(OrderState.CANCELED)
                    except ValueError:
                        order_fsm.state = OrderState.RECONCILE_REQUIRED
                    reason = f"pre_submit_check_failed: {type(exc).__name__}"
                    self._persist(intent, "BLOCKED", reason)
                    self._record("WARNING", "execution_blocked", intent, reason)
                    return ExecutionResult(intent.intent_id, False, reason=reason)

            preflight_reason = self._verify_preflight()
            if preflight_reason:
                self._persist(intent, "BLOCKED", preflight_reason)
                self._record("WARNING", "execution_blocked", intent, preflight_reason)
                return ExecutionResult(intent.intent_id, False, reason=preflight_reason)

            client_order_id = str(intent.client_order_id or "").strip()
            if not client_order_id:
                self._persist(intent, "BLOCKED", "stable_client_order_id_missing")
                return ExecutionResult(intent.intent_id, False, reason="stable_client_order_id_missing")
            if self.db is None or not hasattr(self.db, "try_claim_state"):
                self._persist(intent, "BLOCKED", "durable_idempotency_store_unavailable")
                return ExecutionResult(intent.intent_id, False, reason="durable_idempotency_store_unavailable")
            try:
                claimed = self.db.try_claim_state(
                    f"execution_client_order_id:{client_order_id}",
                    intent.intent_id,
                )
            except Exception:
                claimed = False
            if not claimed:
                self._persist(intent, "BLOCKED", "duplicate_or_previously_claimed_client_order_id")
                return ExecutionResult(intent.intent_id, False, reason="duplicate_or_previously_claimed_client_order_id")

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
                with mutation_permit():
                    response = submit()
            except Exception as exc:
                order_fsm.state = OrderState.AMBIGUOUS
                self._mark_reconcile_required(intent)
                self._persist(intent, "AMBIGUOUS", type(exc).__name__)
                self._record(
                    "ERROR",
                    "execution_ambiguous",
                    intent,
                    "Binance submission failed or timed out; reconciliation required",
                    {"error": type(exc).__name__},
                )
                raise

            if intent.order_type != "CANCEL":
                status = str(response.get("status", "") if isinstance(response, dict) else "").upper()
                if status:
                    order_fsm.observe_exchange_status(status, float(response.get("executedQty", 0) or 0 if isinstance(response, dict) else 0))
                elif isinstance(response, dict) and str(response.get("newOrderResult", "")).upper() == "SUCCESS":
                    order_fsm.state = OrderState.OPEN
                else:
                    order_fsm.state = OrderState.RECONCILE_REQUIRED
                    self._mark_reconcile_required(intent)
                    self._persist(intent, "AMBIGUOUS", "exchange response lacks authoritative order state")
                    self._record("ERROR", "execution_ambiguous", intent, "Exchange accepted an operation without authoritative state", {"response_keys": list(response.keys()) if isinstance(response, dict) else []})
                    raise RuntimeError("ExecutionBarrier: exchange response lacks authoritative order state")
            if not self._persist(intent, "SUBMITTED"):
                self._mark_reconcile_required(intent)
                raise RuntimeError("exchange submission may have succeeded; durable result persistence failed; reconciliation required")
            try:
                event_result = self.db.save_execution_event(intent.intent_id, "BINANCE_SUBMITTED", {"symbol": intent.symbol, "side": intent.side})
                if event_result is False:
                    raise RuntimeError("event persistence returned false")
            except Exception as exc:
                self._mark_reconcile_required(intent)
                raise RuntimeError("exchange submission may have succeeded; submission event persistence failed; reconciliation required") from exc
            self._record("INFO", "execution_submitted", intent, "Binance accepted request", {
                "intent_id": intent.intent_id,
            })
            return ExecutionResult(intent.intent_id, True, response=response)
