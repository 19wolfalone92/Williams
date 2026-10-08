"""P0 serialized execution barrier for Williams."""
from __future__ import annotations

import time
import uuid
from dataclasses import dataclass
from typing import Any, Callable, Mapping

from market_context import ContextCache, MarketStateSnapshot


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
        # decision belongs to one operative/entry timeframe. Campaign entry
        # purposes are execution mutations too and must not bypass this gate.
        campaign_purpose = intent.purpose.upper()
        needs_permission = (
            campaign_purpose == "ENTRY"
            or campaign_purpose in {"CAMPAIGN_ENTRY", "CAMPAIGN_ADD_ON"}
            or campaign_purpose.endswith("_ENTRY")
            or campaign_purpose.endswith("_ADD_ON")
        )
        if needs_permission:
            permission_tf = (intent.permission_interval or "").lower()
            if not permission_tf:
                return "missing permission_interval for execution mutation"
            permission_ctx = snapshot.context(intent.symbol, permission_tf)
            if permission_ctx is None:
                return f"missing permission context {intent.symbol} {permission_tf}"
            if direction == "long" and not permission_ctx.allow_long:
                return f"context {permission_tf} does not allow LONG"
            if direction == "short" and not permission_ctx.allow_short:
                return f"context {permission_tf} does not allow SHORT"

        if self.db is not None and hasattr(self.db, "state_get"):
            # Canonical state is per symbol. Keep the legacy aggregate mirror
            # as a secondary fail-closed barrier for backward compatibility.
            symbol_state = str(
                self.db.state_get(
                    f"position_state:{intent.symbol}",
                    "FLAT",
                )
            ).upper()
            legacy_state = str(
                self.db.state_get("position_state", "FLAT")
            ).upper()
            if symbol_state == "RECONCILE_REQUIRED" or legacy_state == "RECONCILE_REQUIRED":
                return "RECONCILE_REQUIRED"

            campaign_state = str(
                self.db.state_get(
                    f"campaign_state:{intent.campaign_id}",
                    "CLEAN"
                )
            ).upper() if intent.campaign_id else "CLEAN"
            if campaign_state == "RECONCILE_REQUIRED":
                return "campaign_reconcile_required"

            # Optional runtime latches are read only when explicitly persisted;
            # absence preserves unit-test and headless-library compatibility.
            runtime_enabled = self.db.state_get("runtime_execution_enabled")
            if runtime_enabled is not None and str(runtime_enabled).lower() not in {"1", "true", "yes"}:
                return "runtime_execution_disabled"
            paused = self.db.state_get("runtime_paused")
            if paused is not None and str(paused).lower() in {"1", "true", "yes"}:
                return "paused"
            kill_latched = self.db.state_get("kill_switch_latched")
            if kill_latched is not None and str(kill_latched).lower() in {"1", "true", "yes"}:
                return "kill_switch_latched"

        return ""

    def execute(
        self,
        intent: OrderIntent,
        submit: Callable[[], Any],
        *,
        pre_submit_checks: Callable[[MarketStateSnapshot], None] | None = None,
    ) -> ExecutionResult:
        with self.context_cache.execution_lock:
            self._persist(intent, "PENDING")
            if self.db is not None and hasattr(self.db, "save_execution_event"):
                try:
                    self.db.save_execution_event(intent.intent_id, "ADMISSION_STARTED", dict(intent.required_context_versions))
                except Exception:
                    pass
            snapshot = self.context_cache.snapshot()
            reason = self._validate(intent, snapshot)
            if reason:
                self._record("WARNING", "execution_blocked", intent, reason)
                return ExecutionResult(intent.intent_id, False, reason=reason)

            if pre_submit_checks is not None:
                try:
                    pre_submit_checks(snapshot)
                except Exception as exc:
                    reason = f"pre_submit_check_failed: {type(exc).__name__}: {exc}"
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
            try:
                response = submit()
            except Exception as exc:
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
