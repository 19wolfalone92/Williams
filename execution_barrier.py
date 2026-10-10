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
            return ""

        if not intent.required_context_versions:
            return "missing required_context_versions"

        for tf, required in intent.required_context_versions.items():
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
        if permission_tf in {str(x).lower() for x in intent.required_context_versions}:
            required = next(v for k, v in intent.required_context_versions.items() if str(k).lower() == permission_tf)
            if int(getattr(permission_ctx, "version", -1)) != int(required):
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
        symbol_state = str(
            self.db.state_get(f"position_state:{intent.symbol.upper()}", global_state)
            or "UNKNOWN"
        ).upper()
        if symbol_state in {"UNKNOWN", "RECONCILE_REQUIRED"}:
            return f"{intent.symbol}:{symbol_state}"

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
                self.db.state_get(f"campaign_state:{intent.campaign_id}", "CLEAN")
                or "UNKNOWN"
            ).upper()
            if campaign_state in {"UNKNOWN", "RECONCILE_REQUIRED"}:
                return f"campaign_{campaign_state.lower()}"
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
                self._persist(intent, "AMBIGUOUS", f"{type(exc).__name__}: {exc}")
                self._record(
                    "ERROR",
                    "execution_ambiguous",
                    intent,
                    "Binance submission failed or timed out; reconciliation required",
                    {"error": f"{type(exc).__name__}: {exc}"},
                )
                raise

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
            except Exception as exc:
                # The exchange may already have accepted the request. Mark it as
                # ambiguous and never expose an ordinary retryable failure.
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
