"""P0 serialized execution barrier for Williams."""
from __future__ import annotations

import time
import uuid
from dataclasses import dataclass
from typing import Any, Callable, Mapping

from market_context import ContextCache, MarketStateSnapshot
from order_state_machine import OrderState, OrderStateMachine


class ExecutionPersistenceError(RuntimeError):
    """Critical execution state could not be durably recorded."""

class ExecutionOutcomeUnknown(RuntimeError):
    """An exchange mutation may have occurred but its result was not durably recorded."""


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
    target_client_order_id: str = ""
    purpose: str = "ENTRY"
    execution_scope: str = "spot"
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
        save = getattr(self.db, "save_execution_intent", None) if self.db is not None else None
        if not callable(save):
            raise ExecutionPersistenceError(
                "durable execution-intent store is unavailable; mutation blocked"
            )
        try:
            result = save(intent, status, reason)
            if result is False:
                raise RuntimeError("execution-intent store rejected the write")
        except Exception as exc:
            raise ExecutionPersistenceError(
                f"cannot persist execution intent {intent.intent_id} status={status}; mutation blocked"
            ) from exc

    def _mark_unknown(self, intent: OrderIntent, reason: str) -> None:
        marker = getattr(self.db, "mark_execution_unknown", None) if self.db is not None else None
        if not callable(marker):
            raise ExecutionOutcomeUnknown(
                f"intent {intent.intent_id} is unresolved and no durable UNKNOWN lock is available"
            )
        marker(intent, reason, scope=intent.execution_scope)

    def _persist_exchange_result(self, intent: OrderIntent, response: Any) -> None:
        writer = getattr(self.db, "save_execution_result", None) if self.db is not None else None
        if not callable(writer):
            raise ExecutionOutcomeUnknown(
                f"exchange may have applied intent {intent.intent_id}; "
                "atomic exchange-result writer is unavailable; reconciliation is mandatory"
            )
        try:
            writer(intent, response)
        except Exception as exc:
            try:
                self._mark_unknown(intent, f"exchange result persistence failed: {type(exc).__name__}: {exc}")
            except Exception:
                # Durable SUBMITTING remains unresolved and blocks new admission.
                pass
            raise ExecutionOutcomeUnknown(
                f"exchange may have applied intent {intent.intent_id}; "
                "atomic exchange-result persistence failed; reconciliation is mandatory"
            ) from exc

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

        purpose = str(intent.purpose or "").upper()
        entry_purposes = {
            "ENTRY",
            "CAMPAIGN_ENTRY",
            "CAMPAIGN_ADD_ON",
            "ADD_ON",
        }
        # New exposure must carry versioned context and pass the operative
        # timeframe permission check at the final execution boundary. A
        # campaign-specific pre-submit callback is supplementary, not a
        # substitute for the canonical admission contract.
        if purpose in entry_purposes and not intent.required_context_versions:
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

        # Permission applies to exposure-increasing entry/add-on intents. Do not
        # apply entry-direction permission to SELL exits or protective repairs.
        if purpose in entry_purposes:
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
            symbol_state = str(
                self.db.state_get(
                    f"position_state:{intent.symbol.upper()}",
                    state,
                )
            )
            repair_purpose = str(intent.purpose).upper() in {
                "CAMPAIGN_PROTECTION",
                "CAMPAIGN_ENTRY_CANCEL",
            }
            # A durable scoped PROTECTION_PENDING admission lease has already
            # proved that only these risk-reducing operations may proceed. They
            # must also be allowed to repair a campaign/symbol tagged for recovery;
            # ordinary entries/exits remain blocked by this validation.
            if (
                state == "RECONCILE_REQUIRED" or symbol_state == "RECONCILE_REQUIRED"
            ) and not repair_purpose:
                return "RECONCILE_REQUIRED"
            campaign_state = str(
                self.db.state_get(
                    f"campaign_state:{intent.campaign_id}",
                    "CLEAN"
                )
            ) if intent.campaign_id else "CLEAN"
            if campaign_state == "RECONCILE_REQUIRED" and not repair_purpose:
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
            readiness = getattr(self.db, "execution_readiness", None) if self.db is not None else None
            claim = getattr(self.db, "claim_execution_intent", None) if self.db is not None else None
            if not callable(readiness) or not callable(claim):
                raise ExecutionPersistenceError(
                    "atomic execution claim/readiness store is unavailable; mutation blocked"
                )
            try:
                if hasattr(self.db, "mark_execution_protection_pending"):
                    gate = readiness(scope=intent.execution_scope, purpose=intent.purpose)
                else:
                    gate = readiness(scope=intent.execution_scope)
            except Exception as exc:
                raise ExecutionPersistenceError("execution readiness check failed; mutation blocked") from exc
            if not isinstance(gate, dict) or not gate.get("ready"):
                reason = str((gate or {}).get("reason", "execution readiness unknown"))
                return ExecutionResult(intent.intent_id, False, reason=f"fail_closed:{reason}")
            try:
                if hasattr(self.db, "mark_execution_protection_pending"):
                    claim_result = claim(intent, scope=intent.execution_scope, purpose=intent.purpose)
                else:
                    claim_result = claim(intent, scope=intent.execution_scope)
            except Exception as exc:
                raise ExecutionPersistenceError("atomic intent claim failed; mutation blocked") from exc
            if not isinstance(claim_result, dict) or not claim_result.get("claimed"):
                reason = str((claim_result or {}).get("reason", "intent identity conflict"))
                existing = (claim_result or {}).get("existing")
                return ExecutionResult(
                    intent.intent_id, False,
                    reason=f"intent_not_claimed:{reason}:existing={existing}",
                )
            if self.db is not None and hasattr(self.db, "save_execution_event"):
                try:
                    self.db.save_execution_event(intent.intent_id, "ADMISSION_STARTED", dict(intent.required_context_versions))
                except Exception as exc:
                    raise ExecutionPersistenceError("cannot persist admission event; mutation blocked") from exc
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
            # Persist SUBMITTING before the network call. A crash after this point
            # leaves a durable unresolved row that blocks new entries.
            self._persist(intent, "SUBMITTING")
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
                try:
                    self._mark_unknown(intent, f"{type(exc).__name__}: {exc}")
                except Exception as lock_exc:
                    self._record(
                        "CRITICAL", "execution_unknown_lock_failed", intent,
                        "UNKNOWN lock persistence failed; durable SUBMITTING row must block admission",
                        {"submit_error": repr(exc), "lock_error": repr(lock_exc)},
                    )
                self._record(
                    "ERROR",
                    "execution_ambiguous",
                    intent,
                    "Binance submission may have reached the exchange; reconciliation required",
                    {"error": f"{type(exc).__name__}: {exc}"},
                )
                raise ExecutionOutcomeUnknown(
                    f"submission outcome for {intent.intent_id} is unknown; reconcile before retry"
                ) from exc

            self._persist_exchange_result(intent, response)

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
                    try:
                        self._mark_unknown(intent, "exchange response did not contain authoritative order state")
                    except Exception:
                        pass
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
            if self.db is not None and hasattr(self.db, "save_execution_event"):
                try:
                    self.db.save_execution_event(intent.intent_id, "BINANCE_SUBMITTED", {"symbol": intent.symbol, "side": intent.side})
                except Exception:
                    pass
            self._record("INFO", "execution_submitted", intent, "Binance accepted request", {
                "intent_id": intent.intent_id,
            })
            return ExecutionResult(intent.intent_id, True, response=response)
