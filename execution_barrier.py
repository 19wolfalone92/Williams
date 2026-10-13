"""P0 serialized execution barrier for Williams."""
from __future__ import annotations

import time
import uuid
import os

from trading_config import execution_timeframe_blocker
from dataclasses import dataclass
from typing import Any, Callable, Mapping

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
    # Immutable signal provenance copied into the final execution intent.
    signal_timeframe: str = ""
    signal_bar_time_ms: int = 0
    confirmation_time_ms: int = 0
    expires_at_ms: int = 0
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
        """Persist execution state or raise; callers must never treat a failed write as success."""
        if self.db is None:
            # Every exchange mutation, including exits and cancellations, must
            # have a durable intent record. Validation-only code must not call
            # execute(); a missing DB is a hard stop at the mutation boundary.
            raise RuntimeError("required execution-intent persistence unavailable: database is not configured")
        writer = getattr(self.db, "save_execution_intent", None)
        if not callable(writer):
            raise RuntimeError("required execution-intent persistence is unavailable")
        try:
            writer(intent, status, reason)
        except Exception as exc:
            raise RuntimeError(
                f"required execution-intent persistence failed ({status}): {type(exc).__name__}"
            ) from exc

    def _persist_event_required(self, intent: OrderIntent, event: str, payload=None) -> None:
        """Required audit event: a failure before submit blocks mutation."""
        if self.db is None:
            raise RuntimeError("required execution-event persistence unavailable: database is not configured")
        writer = getattr(self.db, "save_execution_event", None)
        if not callable(writer):
            raise RuntimeError("required execution-event persistence is unavailable")
        try:
            writer(intent.intent_id, event, payload or {})
        except Exception as exc:
            raise RuntimeError(
                f"required execution-event persistence failed ({event}): {type(exc).__name__}"
            ) from exc

    def _record(self, level: str, event: str, intent: OrderIntent, message: str, raw=None) -> None:
        if self.db is not None and hasattr(self.db, "log_event"):
            try:
                self.db.log_event(level, event, message, raw or {"intent_id": intent.intent_id})
            except Exception:
                pass

    def _claim_signal_once(self, intent: OrderIntent) -> None:
        """Durably reserve one logical entry signal before network mutation."""
        purpose = str(intent.purpose or "").strip().upper()
        if purpose not in {"ENTRY", "CAMPAIGN_ENTRY", "ADD_ON", "CAMPAIGN_ADD_ON"}:
            return
        if self.db is None:
            # Validation-only use may be DB-less; production entry admission
            # must inject durable storage. _validate's caller contract is not
            # itself proof of persistence.
            raise RuntimeError("durable signal idempotency unavailable; refusing entry mutation")

        signal_id = str(intent.signal_id or "").strip()
        if not signal_id:
            raise RuntimeError("durable signal idempotency blocked: missing signal_id")

        canonical = {
            "symbol": str(intent.symbol or "").strip().upper(),
            "side": str(intent.side or "").strip().upper(),
            "timeframe": str(intent.signal_timeframe or "").strip().lower(),
            "signal_bar_time_ms": int(intent.signal_bar_time_ms or 0),
            "confirmation_time_ms": int(intent.confirmation_time_ms or 0),
            "campaign_id": str(intent.campaign_id or "").strip(),
            "signal_type": purpose,
        }
        claim_once = getattr(self.db, "claim_signal_once", None)
        if callable(claim_once):
            try:
                outcome = claim_once(signal_id, canonical)
            except Exception as exc:
                raise RuntimeError(
                    f"durable signal idempotency reservation failed: {type(exc).__name__}"
                ) from exc
            if outcome == "CLAIMED":
                return
            if outcome == "CONFLICT":
                raise RuntimeError("signal identity conflict: canonical payload differs for existing signal_id")
            raise RuntimeError("duplicate signal blocked: durable signal was already claimed")

        # Compatibility for narrow test doubles and legacy DB adapters. The
        # production Database implementation must expose claim_signal_once.
        claim = getattr(self.db, "try_claim_state", None)
        if not callable(claim):
            raise RuntimeError("durable signal idempotency unavailable; refusing entry mutation")
        provenance = "|".join(str(canonical[k]) for k in (
            "symbol", "side", "timeframe", "signal_bar_time_ms",
            "confirmation_time_ms", "campaign_id", "signal_type",
        ))
        try:
            claimed = claim(f"consumed_signal:{signal_id}", provenance)
        except Exception as exc:
            raise RuntimeError(
                f"durable signal idempotency reservation failed: {type(exc).__name__}"
            ) from exc
        if not claimed:
            raise RuntimeError("duplicate signal blocked: durable signal was already claimed")

    def _validate(
        self,
        intent: OrderIntent,
        snapshot: MarketStateSnapshot,
        pre_submit_checks: Callable[[MarketStateSnapshot], None] | None = None,
    ) -> str:
        # H1 is an owner-approved execution contract, not a Williams book rule.
        # Enforce it at the last admission boundary for campaign entries so
        # callers cannot bypass the scanner or TradingConfig validation.
        purpose = str(intent.purpose or "").strip().upper()
        entry_purposes = {"ENTRY", "CAMPAIGN_ENTRY", "ADD_ON", "CAMPAIGN_ADD_ON"}
        if purpose in entry_purposes:
            config_blocker = execution_timeframe_blocker(os.environ)
            if config_blocker:
                return config_blocker
            signal_tf = str(intent.signal_timeframe or "").strip().lower()
            permission_tf = str(intent.permission_interval or "").strip().lower()
            if signal_tf != "1h" or permission_tf != "1h":
                return (
                    f"{purpose} requires signal_timeframe=1h and "
                    "permission_interval=1h"
                )
            now_ms = int(time.time() * 1000)
            signal_bar_ms = int(intent.signal_bar_time_ms or 0)
            confirmation_ms = int(intent.confirmation_time_ms or 0)
            expires_ms = int(intent.expires_at_ms or 0)
            # H1 timestamps are UTC epoch milliseconds. Confirmation cannot
            # precede the close of the source H1 candle and cannot be future-dated.
            if (
                signal_bar_ms <= 0
                or confirmation_ms < signal_bar_ms + 3_600_000
                or confirmation_ms > now_ms
                or expires_ms <= now_ms
                or not str(intent.signal_id or "").strip()
                or not str(intent.campaign_id or "").strip()
            ):
                return f"{purpose} signal provenance is missing, invalid, future-dated, or expired"

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
            purpose = intent.purpose.upper()
            risk_reducing_purposes = {
                "EXIT",
                "CAMPAIGN_EXIT",
                "CAMPAIGN_EMERGENCY_EXIT",
                "CAMPAIGN_EXIT_CANCEL_PROTECTION",
                "CAMPAIGN_PROTECTION_CANCEL",
                "CAMPAIGN_PROTECTION",
                "PROTECTION",
            }
            # Reconciliation blocks new exposure. A narrowly scoped reduction,
            # cancellation, or protection repair may proceed only with an
            # explicit last-moment validator that rechecks exchange state.
            recovery_mutation_allowed = (
                purpose in risk_reducing_purposes
                and callable(pre_submit_checks)
                and intent.side == "SELL"
            )
            state = str(self.db.state_get("position_state", "FLAT"))
            if state == "RECONCILE_REQUIRED" and not recovery_mutation_allowed:
                return "RECONCILE_REQUIRED"
            campaign_state = str(
                self.db.state_get(
                    f"campaign_state:{intent.campaign_id}",
                    "CLEAN"
                )
            ) if intent.campaign_id else "CLEAN"
            if campaign_state == "RECONCILE_REQUIRED" and not recovery_mutation_allowed:
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
            # Durable admission record and event are prerequisites for any entry mutation.
            # If either write/commit fails, execution stops before the submit callback.
            self._persist(intent, "PENDING")
            self._persist_event_required(
                intent, "ADMISSION_STARTED",
                dict(intent.required_context_versions),
            )
            snapshot = self.context_cache.snapshot()
            reason = self._validate(intent, snapshot, pre_submit_checks=pre_submit_checks)
            if (
                not reason
                and str(intent.purpose or "").strip().upper()
                in {"ENTRY", "CAMPAIGN_ENTRY", "ADD_ON", "CAMPAIGN_ADD_ON"}
                and pre_submit_checks is None
            ):
                reason = "entry requires mandatory last-mile signal/market revalidation"
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

            # Atomic, durable one-time signal reservation follows all read-only
            # validation and precedes the irreversible network boundary.
            self._claim_signal_once(intent)
            order_fsm.transition(OrderState.SUBMITTING)
            if str(intent.order_type or "").strip().upper() in {"CANCEL", "CANCEL_OCO"}:
                order_fsm.transition(OrderState.CANCEL_PENDING)
            # Record the irreversible boundary before touching the network.
            # A restart seeing SUBMITTING without an authoritative result must reconcile,
            # never infer that the request was not sent.
            self._persist(intent, "SUBMITTING")
            self._persist_event_required(
                intent, "SUBMIT_ATTEMPT_STARTED",
                {"symbol": intent.symbol, "side": intent.side, "purpose": intent.purpose},
            )
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
                self._persist(intent, "AMBIGUOUS", f"{type(exc).__name__}: {exc}")
                self._record(
                    "ERROR",
                    "execution_ambiguous",
                    intent,
                    "Binance submission failed or timed out; reconciliation required",
                    {"error": f"{type(exc).__name__}: {exc}"},
                )
                raise

            response_type = str(intent.order_type or "").strip().upper()
            response_status = (
                str(response.get("status", "")).upper()
                if isinstance(response, dict)
                else ""
            )

            if response_type == "CANCEL":
                if response_status not in {"CANCELED", "FILLED", "EXPIRED", "REJECTED"}:
                    order_fsm.state = OrderState.RECONCILE_REQUIRED
                    self._persist(intent, "AMBIGUOUS", "cancel response lacks authoritative terminal order status")
                    raise RuntimeError(
                        "ExecutionBarrier: cancel response lacks authoritative terminal order status"
                    )
                order_fsm.observe_exchange_status(
                    response_status,
                    float(response.get("executedQty", 0) or 0),
                )
            elif response_type == "CANCEL_OCO":
                list_id = response.get("orderListId") if isinstance(response, dict) else None
                list_status = str(
                    response.get("listOrderStatus", response.get("listStatusType", ""))
                    if isinstance(response, dict) else ""
                ).upper()
                reports = (
                    response.get("orderReports", response.get("orders", []))
                    if isinstance(response, dict) else []
                )
                report_statuses = [
                    str(row.get("status", "")).upper()
                    for row in reports if isinstance(row, dict)
                ]
                terminal_reports = bool(report_statuses) and all(
                    status in {"CANCELED", "FILLED", "EXPIRED", "REJECTED"}
                    for status in report_statuses
                )
                if list_id is None or not (
                    list_status == "ALL_DONE" or terminal_reports
                ):
                    order_fsm.state = OrderState.RECONCILE_REQUIRED
                    self._persist(intent, "AMBIGUOUS", "OCO cancellation response is not terminal")
                    raise RuntimeError(
                        "ExecutionBarrier: OCO cancellation outcome requires reconciliation"
                    )
                order_fsm.transition(OrderState.CANCELED)
            elif response_type in {"OCO", "ORDER_LIST"}:
                list_id = response.get("orderListId") if isinstance(response, dict) else None
                list_status = str(
                    response.get("listOrderStatus", response.get("listStatusType", ""))
                    if isinstance(response, dict) else ""
                ).upper()
                reports = (
                    response.get("orderReports", response.get("orders", []))
                    if isinstance(response, dict) else []
                )
                report_statuses = [
                    str(row.get("status", "")).upper()
                    for row in reports if isinstance(row, dict)
                ]
                accepted_reports = bool(report_statuses) and all(
                    status in {"NEW", "PARTIALLY_FILLED"}
                    for status in report_statuses
                )
                accepted_list_status = (
                    list_status in {"EXECUTING", "EXEC_STARTED"}
                    and bool(reports)
                    and all(
                        str(row.get("status", "")).upper() in {"NEW", "PARTIALLY_FILLED"}
                        for row in reports if isinstance(row, dict)
                    )
                )
                if list_id is None or not (accepted_reports or accepted_list_status):
                    order_fsm.state = OrderState.RECONCILE_REQUIRED
                    self._persist(intent, "AMBIGUOUS", "OCO creation response lacks authoritative order-list state")
                    raise RuntimeError(
                        "ExecutionBarrier: OCO creation response lacks authoritative order-list state"
                    )
                order_fsm.transition(OrderState.OPEN)
            elif response_status:
                order_fsm.observe_exchange_status(
                    response_status,
                    float(response.get("executedQty", 0) or 0),
                )
            elif (
                isinstance(response, dict)
                and str(response.get("newOrderResult", "")).upper() == "SUCCESS"
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
            # The exchange call has already happened. Any local persistence failure
            # from this point is an ambiguous outcome, never evidence that no order exists.
            try:
                self._persist(intent, "SUBMITTED")
                self._persist_event_required(
                    intent, "BINANCE_SUBMITTED",
                    {"symbol": intent.symbol, "side": intent.side},
                )
            except Exception as exc:
                try:
                    self._persist(
                        intent, "AMBIGUOUS",
                        f"submission returned but outcome persistence failed: {type(exc).__name__}",
                    )
                except Exception:
                    # Preserve the reconciliation-required exception even if SQLite
                    # is still unavailable; callers must not blindly retry the request.
                    pass
                raise RuntimeError(
                    "ExecutionBarrier: submission outcome requires reconciliation; "
                    "local result persistence failed"
                ) from exc
            self._record("INFO", "execution_submitted", intent, "Binance accepted request", {
                "intent_id": intent.intent_id,
            })
            return ExecutionResult(intent.intent_id, True, response=response)
