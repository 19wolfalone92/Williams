"""P0 serialized execution barrier for Williams."""
from __future__ import annotations

import math
import re
import time
import uuid
from dataclasses import dataclass
from typing import Any, Callable, Mapping

from execution_authority import entry_submission_scope
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
    trigger_price: float = 0.0
    quantity: str = ""
    quote_order_quantity: str = ""
    client_order_id: str = ""
    purpose: str = "ENTRY"
    permission_interval: str = ""
    campaign_id: str = ""
    signal_id: str = ""
    signal_expires_at_ms: int = 0
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

    def _persist(self, intent: OrderIntent, status: str, reason: str = "") -> bool:
        """Persist intent state; return False rather than hiding durability failure."""
        if self.db is None or not callable(getattr(self.db, "save_execution_intent", None)):
            return False
        try:
            self.db.save_execution_intent(intent, status, reason)
            return True
        except Exception:
            return False

    def _record(self, level: str, event: str, intent: OrderIntent, message: str, raw=None) -> None:
        if self.db is not None and hasattr(self.db, "log_event"):
            try:
                self.db.log_event(level, event, message, raw or {"intent_id": intent.intent_id})
            except Exception:
                pass

    def _validate(self, intent: OrderIntent, snapshot: MarketStateSnapshot) -> str:
        now_ms = int(time.time() * 1000)
        purpose = str(intent.purpose or "").strip().upper()
        entry_purposes = {"ENTRY", "CAMPAIGN_ENTRY", "CAMPAIGN_ADD_ON"}
        side = str(intent.side or "").strip().upper()
        symbol = str(intent.symbol or "").strip().upper()
        order_type = str(intent.order_type or "").strip().upper()
        client_order_id = str(intent.client_order_id or "").strip()
        allowed_order_types = {
            "MARKET", "LIMIT", "LIMIT_MAKER", "STOP_LOSS",
            "STOP_LOSS_LIMIT", "TAKE_PROFIT", "TAKE_PROFIT_LIMIT", "OCO", "OCO_CANCEL",
        }

        if not str(intent.intent_id or "").strip():
            return "missing intent_id"
        if not re.fullmatch(r"[A-Z0-9]{5,32}", symbol):
            return "invalid symbol format"
        if intent.symbol != symbol:
            return "symbol must be canonical uppercase"
        if side not in {"BUY", "SELL"}:
            return f"unsupported side {side or '<empty>'}"
        if intent.side != side:
            return "side must be canonical uppercase"
        if order_type not in allowed_order_types and order_type != "CANCEL":
            return f"unsupported order_type {order_type or '<empty>'}"
        if str(intent.order_type).strip() != order_type:
            return "order_type must be canonical uppercase"

        for field in ("risk_quote", "capital_reserved_quote", "trigger_price", "invalidation_level"):
            try:
                value = float(getattr(intent, field, 0.0) or 0.0)
            except (TypeError, ValueError, OverflowError):
                return f"invalid {field}"
            if not math.isfinite(value) or value < 0:
                return f"{field} must be finite and non-negative"

        if order_type not in {"CANCEL", "OCO_CANCEL"}:
            quantity_text = str(intent.quantity or "").strip()
            quote_quantity_text = str(intent.quote_order_quantity or "").strip()
            if quantity_text:
                try:
                    quantity_value = float(quantity_text)
                except (TypeError, ValueError, OverflowError):
                    return "invalid base quantity"
                if not math.isfinite(quantity_value) or quantity_value <= 0:
                    return "base quantity must be finite and positive"
            elif purpose not in entry_purposes or not quote_quantity_text:
                return "missing base quantity"
            if quote_quantity_text:
                try:
                    quote_quantity_value = float(quote_quantity_text)
                except (TypeError, ValueError, OverflowError):
                    return "invalid quote order quantity"
                if not math.isfinite(quote_quantity_value) or quote_quantity_value <= 0:
                    return "quote order quantity must be finite and positive"

        if side == "SELL" and order_type in {"STOP_LOSS", "STOP_LOSS_LIMIT", "TAKE_PROFIT", "TAKE_PROFIT_LIMIT"}:
            try:
                stop_reference = float(intent.invalidation_level)
            except (TypeError, ValueError, OverflowError):
                return "protective order requires a valid stop reference"
            if not math.isfinite(stop_reference) or stop_reference <= 0:
                return "protective order requires a positive finite stop reference"

        if isinstance(intent.created_at_ms, bool) or not isinstance(intent.created_at_ms, int) or intent.created_at_ms <= 0:
            return "invalid or missing created_at_ms"
        if isinstance(intent.max_age_ms, bool) or not isinstance(intent.max_age_ms, int) or intent.max_age_ms <= 0:
            return "invalid or missing max_age_ms"
        age = now_ms - intent.created_at_ms
        if age < 0:
            return "intent created_at_ms is in the future"
        if purpose in entry_purposes and age > intent.max_age_ms:
            return f"stale intent age={age}ms"

        direction = "long" if side == "BUY" else "short"
        if not direction:
            return f"unsupported side {intent.side}"

        # In Spot, every autonomous BUY is a new entry or add-on. Unknown
        # purposes must not become an alternate route around entry admission.
        if side == "BUY" and purpose not in entry_purposes:
            return f"BUY intent has unsupported entry purpose {purpose or '<empty>'}"

        if purpose in entry_purposes:
            if side != "BUY":
                return "entry purpose requires BUY for the current Spot-only execution model"
            if not re.fullmatch(r"[A-Za-z0-9_.:/-]{1,36}", client_order_id):
                return "missing or invalid client_order_id"
            if purpose in {"CAMPAIGN_ENTRY", "CAMPAIGN_ADD_ON"} and order_type != "STOP_LOSS":
                return f"campaign entry order_type must be STOP_LOSS for the current Spot model: {order_type}"
            if purpose == "ENTRY" and order_type not in {"MARKET", "STOP_LOSS"}:
                return f"entry order_type is not approved for current Spot execution model: {order_type}"
            quantity = str(intent.quantity or "").strip()
            quote_quantity = str(intent.quote_order_quantity or "").strip()
            if bool(quantity) == bool(quote_quantity):
                return "entry requires exactly one of quantity or quote_order_quantity"
            if order_type == "STOP_LOSS" and (not quantity or quote_quantity):
                return "STOP_LOSS entry requires base quantity, not quote_order_quantity"
            try:
                amount = float(quantity if quantity else quote_quantity)
            except (TypeError, ValueError):
                return "invalid entry quantity"
            if not math.isfinite(amount) or amount <= 0:
                return "entry quantity must be finite and positive"
            if order_type == "STOP_LOSS":
                try:
                    trigger_price = float(intent.trigger_price)
                    invalidation_level = float(intent.invalidation_level)
                except (TypeError, ValueError):
                    return "STOP_LOSS entry trigger/invalidation levels are invalid"
                if (
                    not math.isfinite(trigger_price)
                    or not math.isfinite(invalidation_level)
                    or trigger_price <= 0
                    or invalidation_level <= 0
                    or trigger_price <= invalidation_level
                ):
                    return "STOP_LOSS entry requires trigger_price above positive invalidation_level"
            if not isinstance(intent.required_context_versions, Mapping):
                return "required_context_versions must be a mapping"
            if not intent.required_context_versions:
                return "missing required_context_versions"
            if not isinstance(intent.signal_id, str) or not intent.signal_id.strip():
                return "missing signal_id"
            if (
                isinstance(intent.signal_expires_at_ms, bool)
                or not isinstance(intent.signal_expires_at_ms, int)
                or intent.signal_expires_at_ms <= 0
            ):
                return "missing or invalid absolute signal expiry"
            if now_ms >= intent.signal_expires_at_ms:
                return "signal expired before execution admission"
            if not isinstance(intent.permission_interval, str) or not intent.permission_interval.strip():
                return "missing or invalid permission_interval"
            if str(intent.permission_interval).lower() not in {
                str(tf).lower() for tf in intent.required_context_versions
            }:
                return "permission_interval missing from required_context_versions"
            if purpose.startswith("CAMPAIGN_") and (
                not isinstance(intent.campaign_id, str) or not intent.campaign_id.strip()
            ):
                return "missing campaign_id for campaign entry"

        if purpose in entry_purposes:
            if not isinstance(intent.required_context_versions, Mapping):
                return "required_context_versions must be a mapping"
            for tf, required in intent.required_context_versions.items():
                if not isinstance(tf, str) or not tf.strip():
                    return "invalid context interval key"
                if isinstance(required, bool) or not isinstance(required, int) or required < 0:
                    return f"invalid required context version for {tf}"
                ctx = snapshot.context(symbol, tf)
                if ctx is None:
                    return f"missing context {symbol} {tf}"
                if isinstance(ctx.version, bool) or not isinstance(ctx.version, int) or ctx.version < 0:
                    return f"invalid published context version for {tf}"
                if int(ctx.version) != required:
                    return f"stale context {tf}: required={required} current={ctx.version}"
                # Validate data availability/shape without inventing an unapproved
                # universal maximum context age. A sourced age policy remains open.
                if (
                    isinstance(ctx.candle_close_time_ms, bool)
                    or not isinstance(ctx.candle_close_time_ms, int)
                    or ctx.candle_close_time_ms <= 0
                    or ctx.candle_close_time_ms > now_ms
                ):
                    return f"invalid or future context close timestamp for {tf}"
                try:
                    context_price = float(ctx.price)
                except (TypeError, ValueError):
                    return f"invalid context price for {tf}"
                if not math.isfinite(context_price) or context_price <= 0:
                    return f"invalid context price for {tf}"

        if purpose in entry_purposes:
            permission_tf = (intent.permission_interval or "").lower()
            permission_ctx = snapshot.context(symbol, permission_tf)
            if permission_ctx is None:
                return f"missing permission context {intent.symbol} {permission_tf}"
            if direction == "long" and not permission_ctx.allow_long:
                return f"context {permission_tf} does not allow LONG"
            if direction == "short" and not permission_ctx.allow_short:
                return f"context {permission_tf} does not allow SHORT"

        # Reconciliation state blocks additional exposure, not actions
        # needed to reduce or protect existing exposure. SELL exits, protection,
        # cancellation and reconciliation must remain reachable.
        if purpose in entry_purposes and self.db is not None and hasattr(self.db, "state_get"):
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
            purpose = str(intent.purpose or "").strip().upper()
            order_type = str(intent.order_type or "").strip().upper()
            entry_purposes = {"ENTRY", "CAMPAIGN_ENTRY", "CAMPAIGN_ADD_ON"}
            durability_required = (
                purpose in entry_purposes
                or order_type in {"CANCEL", "OCO", "OCO_CANCEL"}
                or purpose in {
                    "CAMPAIGN_PROTECTION",
                    "CAMPAIGN_PROTECTION_CANCEL",
                    "CAMPAIGN_TRAIL",
                    "CAMPAIGN_EXIT_CANCEL_PROTECTION",
                    "CAMPAIGN_PARTIAL_ENTRY_CANCEL",
                }
            )
            durability_required_before_submit = (
                purpose in entry_purposes or order_type in {"CANCEL", "OCO_CANCEL"}
            )
            durable = self._persist(intent, "PENDING")
            if durability_required_before_submit and not durable:
                reason = "mandatory intent persistence failed; mutation blocked before exchange submission"
                self._record("ERROR", "execution_blocked", intent, reason)
                return ExecutionResult(intent.intent_id, False, reason=reason)
            if self.db is not None and hasattr(self.db, "save_execution_event"):
                try:
                    self.db.save_execution_event(intent.intent_id, "ADMISSION_STARTED", dict(intent.required_context_versions))
                except Exception:
                    pass
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

            # Pre-submit checks can perform network I/O (for example, a
            # closed-candle/fractal refresh). Revalidate time-sensitive entry
            # constraints immediately before the irreversible exchange call.
            reason = self._validate(intent, snapshot)
            if reason:
                try:
                    order_fsm.transition(OrderState.CANCELED)
                except ValueError:
                    order_fsm.state = OrderState.RECONCILE_REQUIRED
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
                if purpose in entry_purposes:
                    with entry_submission_scope():
                        response = submit()
                else:
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

            if str(intent.order_type).strip().upper() == "OCO_CANCEL":
                reports = response.get("orderReports") if isinstance(response, dict) else None
                try:
                    order_list_id = int(response.get("orderListId", -1)) if isinstance(response, dict) else -1
                except (TypeError, ValueError, OverflowError):
                    order_list_id = -1
                list_type = str(response.get("listStatusType", "")).upper() if isinstance(response, dict) else ""
                list_status = str(response.get("listOrderStatus", "")).upper() if isinstance(response, dict) else ""
                reports_valid = isinstance(reports, list) and len(reports) >= 2
                if reports_valid:
                    for row in reports:
                        if not isinstance(row, dict) or str(row.get("status", "")).upper() != "CANCELED":
                            reports_valid = False
                            break
                        try:
                            leg_executed = float(row.get("executedQty", 0) or 0)
                        except (TypeError, ValueError, OverflowError):
                            reports_valid = False
                            break
                        if not math.isfinite(leg_executed) or leg_executed != 0:
                            reports_valid = False
                            break
                if (
                    order_list_id < 0
                    or list_type != "ALL_DONE"
                    or list_status != "ALL_DONE"
                    or not reports_valid
                ):
                    order_fsm.state = OrderState.RECONCILE_REQUIRED
                    reason = "OCO cancellation lacks authoritative no-fill confirmation; reconciliation required"
                    self._persist(intent, "AMBIGUOUS", reason)
                    self._record("ERROR", "execution_ambiguous", intent, reason)
                    raise RuntimeError(f"ExecutionBarrier: {reason}")
                self._persist(intent, "CANCELED", "Binance confirmed OCO cancellation with no fills")
                self._record("INFO", "oco_cancel_confirmed", intent, "Binance confirmed OCO cancellation with no fills")
                return ExecutionResult(intent.intent_id, True, response=response)
            elif str(intent.order_type).strip().upper() == "CANCEL":
                status = str(response.get("status", "")).upper() if isinstance(response, dict) else ""
                try:
                    executed_qty = float(response.get("executedQty", 0) or 0) if isinstance(response, dict) else float("nan")
                except (TypeError, ValueError, OverflowError):
                    executed_qty = float("nan")
                if not status or not math.isfinite(executed_qty) or executed_qty < 0:
                    order_fsm.state = OrderState.RECONCILE_REQUIRED
                    reason = "cancel response lacks authoritative status/fill quantity; reconciliation required"
                    self._persist(intent, "AMBIGUOUS", reason)
                    self._record("ERROR", "execution_ambiguous", intent, reason)
                    raise RuntimeError(f"ExecutionBarrier: {reason}")
                if status in {"CANCELED", "EXPIRED"} and executed_qty == 0:
                    order_fsm.observe_exchange_status(status, executed_qty)
                    self._persist(intent, status, f"Binance confirmed terminal order state {status} with no fills")
                    self._record("INFO", "cancel_confirmed", intent, f"Binance confirmed terminal order state {status} with no fills")
                    return ExecutionResult(intent.intent_id, True, response=response)
                elif status == "REJECTED":
                    self._persist(intent, "REJECTED", "Binance rejected cancellation; original order state must be reconciled")
                    self._record("WARNING", "cancel_rejected", intent, "Binance rejected cancellation")
                    return ExecutionResult(
                        intent.intent_id,
                        False,
                        response=response,
                        reason="Binance rejected cancellation; reconciliation required",
                    )
                else:
                    order_fsm.state = OrderState.RECONCILE_REQUIRED
                    reason = f"cancel did not confirm an unfilled CANCELED order (status={status}, executedQty={executed_qty}); reconciliation required"
                    self._persist(intent, "AMBIGUOUS", reason)
                    self._record("ERROR", "execution_ambiguous", intent, reason)
                    raise RuntimeError(f"ExecutionBarrier: {reason}")
            elif str(intent.order_type).strip().upper() == "OCO":
                reports = response.get("orderReports") if isinstance(response, dict) else None
                try:
                    order_list_id = int(response.get("orderListId", -1)) if isinstance(response, dict) else -1
                except (TypeError, ValueError, OverflowError):
                    order_list_id = -1
                list_type = str(response.get("listStatusType", "")).upper() if isinstance(response, dict) else ""
                list_status = str(response.get("listOrderStatus", "")).upper() if isinstance(response, dict) else ""
                reports_valid = (
                    isinstance(reports, list)
                    and len(reports) >= 2
                    and all(
                        isinstance(row, dict)
                        and row.get("orderId") is not None
                        and str(row.get("status", "")).upper() in {
                            "NEW", "PENDING_NEW", "PARTIALLY_FILLED", "FILLED",
                            "CANCELED", "EXPIRED",
                        }
                        for row in reports
                    )
                )
                if (
                    order_list_id < 0
                    or list_type not in {"EXEC_STARTED", "ALL_DONE"}
                    or list_status not in {"EXECUTING", "ALL_DONE"}
                    or not reports_valid
                ):
                    order_fsm.state = OrderState.RECONCILE_REQUIRED
                    reason = "OCO response lacks authoritative order-list and leg state; reconciliation required"
                    self._persist(intent, "AMBIGUOUS", reason)
                    self._record("ERROR", "execution_ambiguous", intent, reason)
                    raise RuntimeError(f"ExecutionBarrier: {reason}")
                order_fsm.state = OrderState.OPEN
            elif str(intent.order_type).strip().upper() != "CANCEL":
                status = str(
                    response.get("status", "")
                    if isinstance(response, dict)
                    else ""
                ).upper()
                if status:
                    try:
                        executed_qty = float(response.get("executedQty", 0) or 0)
                    except (TypeError, ValueError, OverflowError) as exc:
                        executed_qty = float("nan")
                    if not math.isfinite(executed_qty) or executed_qty < 0:
                        order_fsm.state = OrderState.RECONCILE_REQUIRED
                        reason = "exchange response has invalid executedQty; reconciliation required"
                        self._persist(intent, "AMBIGUOUS", reason)
                        self._record("ERROR", "execution_ambiguous", intent, reason)
                        raise RuntimeError(f"ExecutionBarrier: {reason}")
                    if status in {"NEW", "PENDING_NEW"} and executed_qty > 0:
                        order_fsm.state = OrderState.RECONCILE_REQUIRED
                    elif status == "PARTIALLY_FILLED" and executed_qty <= 0:
                        order_fsm.state = OrderState.RECONCILE_REQUIRED
                    elif status == "FILLED" and executed_qty <= 0:
                        order_fsm.state = OrderState.RECONCILE_REQUIRED
                    elif status in {"CANCELED", "EXPIRED"} and executed_qty > 0:
                        # A terminal order can still have changed inventory. A
                        # partial fill must be reconciled before risk is released.
                        order_fsm.state = OrderState.RECONCILE_REQUIRED
                    else:
                        order_fsm.observe_exchange_status(status, executed_qty)
                    if order_fsm.state == OrderState.REJECTED:
                        reason = "Binance authoritatively rejected the order"
                        self._persist(intent, "REJECTED", reason)
                        self._record("WARNING", "execution_rejected", intent, reason)
                        return ExecutionResult(intent.intent_id, False, response=response, reason=reason)
                    if status in {"CANCELED", "EXPIRED"} and executed_qty == 0:
                        self._persist(intent, status, "exchange confirmed terminal order with no fills")
                        self._record(
                            "WARNING",
                            "execution_terminal_without_fill",
                            intent,
                            f"Binance order status is {status} with no executed quantity",
                        )
                        return ExecutionResult(
                            intent.intent_id,
                            False,
                            response=response,
                            reason=f"Binance order is {status} with no fills",
                        )
                    if order_fsm.state == OrderState.RECONCILE_REQUIRED:
                        reason = f"exchange returned unrecognized or inconsistent order status {status!r}; reconciliation required"
                        self._persist(intent, "AMBIGUOUS", reason)
                        self._record("ERROR", "execution_ambiguous", intent, reason)
                        raise RuntimeError(f"ExecutionBarrier: {reason}")
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
            if not self._persist(intent, "SUBMITTED") and durability_required:
                reason = "exchange response received but durable status update failed; reconciliation required"
                self._record("ERROR", "execution_ambiguous", intent, reason)
                raise RuntimeError(reason)
            if self.db is not None and hasattr(self.db, "save_execution_event"):
                try:
                    self.db.save_execution_event(intent.intent_id, "BINANCE_SUBMITTED", {"symbol": intent.symbol, "side": intent.side})
                except Exception:
                    pass
            self._record("INFO", "execution_submitted", intent, "Binance accepted request", {
                "intent_id": intent.intent_id,
            })
            return ExecutionResult(intent.intent_id, True, response=response)
