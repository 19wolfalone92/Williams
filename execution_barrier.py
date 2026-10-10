"""Durable, fail-closed order-mutation boundary for Williams Bot 2.1."""
from __future__ import annotations

import json
import time
import uuid
import hashlib
from dataclasses import dataclass
from typing import Any, Callable, Mapping

from market_context import ContextCache, MarketStateSnapshot
from order_state_machine import OrderState, OrderStateMachine


class ExecutionAmbiguousError(RuntimeError):
    """The exchange may have applied a mutation; authoritative reconciliation is required."""


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
    signal_type: str = ""
    risk_quote: float = 0.0
    capital_reserved_quote: float = 0.0
    created_at_ms: int = 0
    max_age_ms: int = 15_000
    operation_kind: str = "PLACE"
    related_order_id: str = ""
    related_order_list_id: str = ""

    @classmethod
    def new(
        cls,
        symbol: str,
        side: str,
        order_type: str,
        required_context_versions: Mapping[str, int],
        **kwargs: Any,
    ) -> "OrderIntent":
        params = dict(kwargs)
        supplied_intent_id = str(params.pop("intent_id", "") or "").strip()
        cid = str(params.get("client_order_id", "") or "").strip()
        operation = str(params.get("operation_kind", "PLACE") or "PLACE").upper()
        if supplied_intent_id:
            intent_id = supplied_intent_id
        elif cid:
            # Same logical exchange request gets the same durable intent ID on
            # every retry/restart. A different request with the same CID will
            # collide on immutable fields and fail closed.
            material = f"{str(symbol).upper()}\\x1f{operation}\\x1f{cid}"
            intent_id = hashlib.sha256(material.encode("utf-8")).hexdigest()[:32]
        else:
            intent_id = uuid.uuid4().hex
        return cls(
            intent_id=intent_id,
            symbol=str(symbol).upper(),
            side=str(side).upper(),
            order_type=str(order_type).upper(),
            required_context_versions=dict(required_context_versions),
            created_at_ms=int(time.time() * 1000),
            **params,
        )


@dataclass(frozen=True)
class ExecutionResult:
    intent_id: str
    accepted: bool
    response: Any = None
    reason: str = ""


class ExecutionBarrier:
    """The single admission door before a Binance order-state mutation.

    A durable PENDING and SUBMITTING record must be committed and read back
    before submit() is called. Ambiguous outcomes retain identity claims and
    block all new exposure until the unresolved intent is reconciled.
    Optional telemetry is deliberately not part of the execution commit path.
    """

    MUTATION_LOCK_KEY = "execution_mutation_lock"
    ENTRY_ADMISSION_LOCK_KEY = "execution_entry_admission_lock"
    NEW_EXPOSURE_PURPOSES = frozenset({
        "ENTRY", "ADD_ON", "CAMPAIGN_ENTRY", "CAMPAIGN_ADD_ON",
        "REVERSE_ENTRY", "CAMPAIGN_REVERSE_ENTRY",
    })
    EXIT_PURPOSES = frozenset({
        "EXIT", "CAMPAIGN_EXIT", "EMERGENCY_EXIT", "MANUAL_EXIT",
        "PROTECTION", "CAMPAIGN_PROTECTION", "CAMPAIGN_TRAIL",
    })
    KNOWN_ORDER_STATES = frozenset({
        "PENDING_NEW", "NEW", "PARTIALLY_FILLED", "FILLED",
        "CANCELED", "CANCELLED", "EXPIRED", "REJECTED",
    })

    def __init__(
        self,
        context_cache: ContextCache,
        db=None,
        *,
        require_durable_intent: bool = True,
    ) -> None:
        self.context_cache = context_cache
        self.db = db
        # False is intended only for isolated deterministic tests without a DB.
        self.require_durable_intent = bool(require_durable_intent)
        self._local_mutation_lock_reason = ""

    @property
    def mutation_locked(self) -> bool:
        if self.db is None:
            return bool(self._local_mutation_lock_reason) or bool(self.require_durable_intent)
        # SQLite is the cross-process source of truth. Clearing the durable lock
        # after verified reconciliation must be observed by long-lived instances.
        durable_reason = str(self.db.state_get(self.MUTATION_LOCK_KEY, "") or "")
        if durable_reason:
            self._local_mutation_lock_reason = durable_reason
            return True
        self._local_mutation_lock_reason = ""
        return False

    @property
    def mutation_lock_reason(self) -> str:
        if self.db is None:
            return self._local_mutation_lock_reason or "durable execution database unavailable"
        durable_reason = str(self.db.state_get(self.MUTATION_LOCK_KEY, "") or "")
        if durable_reason:
            self._local_mutation_lock_reason = durable_reason
            return durable_reason
        self._local_mutation_lock_reason = ""
        return ""

    def clear_mutation_lock_after_reconciliation(
        self,
        *,
        reconciliation_verified=False,
        evidence="",
    ) -> bool:
        """Release the global entry lock only after account/order reconciliation completed."""
        if not reconciliation_verified or not str(evidence or "").strip():
            raise PermissionError("verified reconciliation evidence is required to clear the mutation lock")
        if self.db is None:
            if self.require_durable_intent:
                return False
            self._local_mutation_lock_reason = ""
            return True
        unresolved = getattr(self.db, "has_unresolved_execution_intents", None)
        if not callable(unresolved) or unresolved():
            return False
        lock_value = str(self.db.state_get(self.MUTATION_LOCK_KEY, "") or "")
        if not lock_value:
            self._local_mutation_lock_reason = ""
            return True
        owner_intent_id = lock_value.split(":", 1)[0]
        loader = getattr(self.db, "load_execution_intent", None)
        owner_intent = loader(owner_intent_id) if callable(loader) else None
        owner_status = str((owner_intent or {}).get("status", "")).upper()
        if owner_status in {"PENDING", "SUBMITTING", "AMBIGUOUS", "RECONCILE_REQUIRED"}:
            return False
        if not callable(getattr(self.db, "release_state_claim", None)):
            return False
        self.db.release_state_claim(self.ENTRY_ADMISSION_LOCK_KEY, owner_intent_id)
        self.db.release_state_claim(self.MUTATION_LOCK_KEY, lock_value)
        self._local_mutation_lock_reason = ""
        return True

    @staticmethod
    def _is_new_exposure(intent: OrderIntent) -> bool:
        return str(intent.purpose or "").strip().upper() in ExecutionBarrier.NEW_EXPOSURE_PURPOSES

    def _record(self, level: str, event: str, intent: OrderIntent, message: str, raw=None) -> None:
        """Best-effort telemetry only; never substitutes for a durable intent."""
        logger = getattr(self.db, "log_event", None) if self.db is not None else None
        if callable(logger):
            try:
                logger(
                    level,
                    event,
                    message,
                    raw or {"intent_id": intent.intent_id},
                )
            except Exception:
                pass

    @staticmethod
    def _immutable_identity(intent: OrderIntent) -> tuple:
        return (
            str(intent.symbol).upper(),
            str(intent.side).upper(),
            str(intent.order_type).upper(),
            str(intent.purpose).upper(),
            json.dumps(dict(intent.required_context_versions), sort_keys=True),
            str(intent.client_order_id or ""),
            str(intent.campaign_id or ""),
            str(intent.signal_id or ""),
            str(intent.signal_type or "").upper(),
            str(intent.operation_kind or "PLACE").upper(),
            str(intent.related_order_id or ""),
            str(intent.related_order_list_id or ""),
        )

    def _persist(self, intent: OrderIntent, status: str, reason: str = "") -> bool:
        """Return True only after durable save and post-commit read-back succeed."""
        if self.db is None:
            return not self.require_durable_intent
        save = getattr(self.db, "save_execution_intent", None)
        load = getattr(self.db, "load_execution_intent", None)
        if not callable(save) or not callable(load):
            return False
        try:
            save(intent, str(status).upper(), reason)
            row = load(intent.intent_id)
            if not isinstance(row, dict):
                return False
            expected = self._immutable_identity(intent)
            actual = (
                str(row.get("symbol", "")).upper(),
                str(row.get("side", "")).upper(),
                str(row.get("order_type", "")).upper(),
                str(row.get("purpose", "")).upper(),
                str(row.get("required_context_versions_json", "")),
                str(row.get("client_order_id") or ""),
                str(row.get("campaign_id") or ""),
                str(row.get("signal_id") or ""),
                str(row.get("signal_type") or "").upper(),
                str(row.get("operation_kind") or "PLACE").upper(),
                str(row.get("related_order_id") or ""),
                str(row.get("related_order_list_id") or ""),
            )
            return (
                actual == expected
                and str(row.get("status", "")).upper() == str(status).upper()
                and str(row.get("reason") or "") == str(reason or "")
            )
        except Exception:
            return False

    def _record_exchange_result(
        self,
        intent: OrderIntent,
        response: Any,
        status: str,
        reason: str = "",
    ) -> bool:
        """Persist the actual exchange response, independently of telemetry."""
        if self.db is None:
            return not self.require_durable_intent
        writer = getattr(self.db, "save_execution_result", None)
        if not callable(writer):
            return False
        try:
            return bool(writer(intent.intent_id, response, status, reason))
        except Exception:
            return False

    def _set_reconciliation_lock(self, intent: OrderIntent, reason: str) -> None:
        self._local_mutation_lock_reason = str(reason)
        if self.db is not None:
            try:
                self.db.state_set(
                    self.MUTATION_LOCK_KEY,
                    f"{intent.intent_id}:{reason}",
                )
            except Exception:
                # Durable SUBMITTING intent still blocks new exposure on restart.
                pass
        self._record(
            "ERROR",
            "execution_reconciliation_required",
            intent,
            str(reason),
            {"intent_id": intent.intent_id, "client_order_id": intent.client_order_id},
        )

    def _release_entry_admission(self, intent: OrderIntent) -> None:
        if self.db is None:
            return
        release = getattr(self.db, "release_state_claim", None)
        if callable(release):
            release(self.ENTRY_ADMISSION_LOCK_KEY, intent.intent_id)
        else:
            if self.db.state_get(self.ENTRY_ADMISSION_LOCK_KEY, "") == intent.intent_id:
                self.db.state_delete(self.ENTRY_ADMISSION_LOCK_KEY)

    def _release_identity_claims(self, intent: OrderIntent) -> None:
        if self.db is None:
            return
        release = getattr(self.db, "release_execution_identity", None)
        logical_key = self._logical_key(intent)
        if callable(release):
            release(intent.intent_id, intent.client_order_id, logical_key)

    @staticmethod
    def _logical_key(intent: OrderIntent) -> str:
        if not str(intent.signal_id or "").strip():
            return ""
        return (
            f"{str(intent.symbol).upper()}:"
            f"{str(intent.purpose).upper()}:"
            f"{str(intent.signal_id).strip()}"
        )

    def _claim_entry_admission(self, intent: OrderIntent) -> bool:
        if self.db is None:
            return not self.require_durable_intent
        claim = getattr(self.db, "try_claim_state", None)
        if not callable(claim):
            return False
        return bool(claim(self.ENTRY_ADMISSION_LOCK_KEY, intent.intent_id))

    def _claim_identities(self, intent: OrderIntent) -> bool:
        if self.db is None:
            return not self.require_durable_intent
        claim = getattr(self.db, "claim_execution_identity", None)
        if not callable(claim):
            return False
        if not str(intent.client_order_id or "").strip():
            return False
        return bool(
            claim(
                intent.intent_id,
                intent.client_order_id,
                self._logical_key(intent),
            )
        )

    def _validate(
        self,
        intent: OrderIntent,
        snapshot: MarketStateSnapshot,
        *,
        pre_submit_checks_supplied: bool,
    ) -> str:
        purpose = str(intent.purpose or "").strip().upper()
        operation = str(intent.operation_kind or "PLACE").strip().upper()
        is_entry = self._is_new_exposure(intent)

        # Context/risk reasons are evaluated before reporting the global
        # mutation latch. This remains fail-closed but gives diagnostics the
        # actual stale/missing-context cause instead of masking it as a DB error.

        if is_entry and intent.created_at_ms:
            age = int(time.time() * 1000) - int(intent.created_at_ms)
            if age < 0 or age > int(intent.max_age_ms):
                return f"stale intent age={age}ms"

        if not str(intent.symbol or "").strip():
            return "missing symbol"
        if str(intent.side or "").upper() not in {"BUY", "SELL"}:
            return f"unsupported side {intent.side}"
        if operation not in {"PLACE", "CANCEL", "CANCEL_OCO", "CANCEL_REPLACE"}:
            return f"unsupported mutation operation {operation}"

        if operation in {"CANCEL", "CANCEL_REPLACE"} and not str(intent.related_order_id or "").strip():
            return "cancel target order identity missing"
        if operation == "CANCEL_OCO" and not (
            str(intent.related_order_list_id or "").strip()
            or str(intent.client_order_id or "").strip()
        ):
            return "cancel target order-list identity missing"

        if operation == "PLACE" and str(intent.order_type).upper() != "CANCEL":
            if not str(intent.client_order_id or "").strip():
                return "missing stable client_order_id"

        if is_entry:
            if not str(intent.signal_id or "").strip():
                return "missing stable signal_id; repeated PendingSignal cannot be deduplicated"
            if not pre_submit_checks_supplied:
                return "missing mandatory final risk/position pre-submit checks"
            try:
                risk_quote = float(intent.risk_quote)
            except (TypeError, ValueError):
                return "invalid risk_quote"
            if not (risk_quote > 0.0):
                return "entry blocked: explicit positive risk_quote is mandatory"
            if not intent.required_context_versions:
                return "missing required context versions"
            for tf, required in intent.required_context_versions.items():
                ctx = snapshot.context(intent.symbol, tf)
                if ctx is None:
                    return f"missing context {intent.symbol} {tf}"
                if int(ctx.version) != int(required):
                    return f"stale context {tf}: required={required} current={ctx.version}"

            permission_tf = str(intent.permission_interval or "").strip()
            if permission_tf != "1M":
                permission_tf = permission_tf.lower()
            permission_ctx = snapshot.context(intent.symbol, permission_tf) if permission_tf else None
            if permission_ctx is None:
                return f"missing permission context {intent.symbol} {permission_tf}"
            direction = "long" if str(intent.side).upper() == "BUY" else "short"
            wm1_initial_reversal = (
                purpose == "CAMPAIGN_ENTRY"
                and str(intent.signal_type or "").strip().upper() == "REVERSAL"
            )
            directional_permission = (
                permission_ctx.allow_long if direction == "long"
                else permission_ctx.allow_short
            )
            if not directional_permission and not wm1_initial_reversal:
                return f"context {permission_tf} does not allow {direction.upper()}"
            if wm1_initial_reversal:
                daily_key = next(
                    (
                        tf for tf in intent.required_context_versions
                        if str(tf).strip().lower() == "1d"
                    ),
                    None,
                )
                macro = snapshot.context(intent.symbol, daily_key) if daily_key else None
                if macro is None:
                    return "WM1 reversal admission requires a current versioned D1 macro context"
                macro_permission = macro.allow_long if direction == "long" else macro.allow_short
                if not macro_permission:
                    return f"D1 macro context does not permit WM1 {direction.upper()} reversal"

            if self.mutation_locked:
                return f"MUTATION_LOCKED_RECONCILIATION_REQUIRED: {self.mutation_lock_reason}"
            if self.db is None and self.require_durable_intent:
                return "durable execution database unavailable"
            if self.db is not None:
                state_get = getattr(self.db, "state_get", None)
                if not callable(state_get):
                    return "durable execution state API unavailable"
                global_state = str(state_get("position_state", "FLAT")).upper()
                if global_state == "RECONCILE_REQUIRED":
                    return "RECONCILE_REQUIRED"
                kill_switch = str(state_get("kill_switch_latched", "false")).strip().lower()
                if kill_switch not in {"", "0", "false", "off", "none"}:
                    return "kill switch is latched"
                try:
                    reconcile_lookup = getattr(self.db, "has_reconcile_required_state", None)
                    if not callable(reconcile_lookup):
                        return "global reconciliation-state lookup unavailable"
                    unresolved_state = reconcile_lookup()
                    if unresolved_state:
                        return f"global RECONCILE_REQUIRED state: {unresolved_state}"
                except Exception as exc:
                    return f"global reconciliation-state lookup failed: {type(exc).__name__}: {exc}"
                symbol_state = str(state_get(f"position_state:{intent.symbol}", "FLAT")).upper()
                if symbol_state == "RECONCILE_REQUIRED":
                    return f"{intent.symbol}: RECONCILE_REQUIRED"
                if intent.campaign_id:
                    campaign_state = str(
                        state_get(f"campaign_state:{intent.campaign_id}", "CLEAN")
                    )
                    if campaign_state == "RECONCILE_REQUIRED":
                        return "campaign_reconcile_required"
                unresolved = getattr(self.db, "has_unresolved_execution_intents", None)
                if not callable(unresolved):
                    return "unresolved-intent lookup unavailable"
                try:
                    if unresolved():
                        return "unresolved execution intent exists; reconcile before new exposure"
                except Exception as exc:
                    return f"unresolved-intent lookup failed: {type(exc).__name__}: {exc}"

        # All protection, exit and cancel calls still require durable intent
        # persistence; this branch only avoids stale *strategy* context vetoing
        # a safety action. Its callback must confirm the current owner/order.
        if not is_entry and self.db is not None:
            state_get = getattr(self.db, "state_get", None)
            if callable(state_get) and intent.campaign_id:
                if str(state_get(f"campaign_state:{intent.campaign_id}", "")) == "FOREIGN":
                    return "campaign ownership is not established"
        return ""

    @staticmethod
    def _response_status(response: Any) -> str:
        if not isinstance(response, dict):
            return ""
        return str(
            response.get("status")
            or response.get("algoStatus")
            or response.get("listStatusType")
            or ""
        ).upper().strip()

    @staticmethod
    def _mutation_response_is_authoritative(intent: OrderIntent, response: Any) -> tuple[bool, str, str]:
        """Return (authoritative, persisted_status, reason) without guessing."""
        operation = str(intent.operation_kind or "PLACE").upper()
        if not isinstance(response, dict):
            return False, "AMBIGUOUS", "mutation response is not an object"

        # cancelReplace is non-atomic. Both legs must have explicit outcomes.
        if operation == "CANCEL_REPLACE" or (
            "cancelResult" in response and "newOrderResult" in response
        ):
            cancel_result = str(response.get("cancelResult", "")).upper()
            new_result = str(response.get("newOrderResult", "")).upper()
            cancel_ok = cancel_result in {"SUCCESS", "NOT_FOUND"}
            new_ok = new_result in {"SUCCESS", "NOT_ATTEMPTED"}
            new_response = response.get("newOrderResponse")
            new_status = ExecutionBarrier._response_status(new_response)
            if cancel_ok and new_ok and (new_result != "SUCCESS" or new_status in ExecutionBarrier.KNOWN_ORDER_STATES):
                if new_result == "SUCCESS" and new_status in {"NEW", "PARTIALLY_FILLED", "FILLED"}:
                    return True, "SUBMITTED", ""
                if cancel_result == "SUCCESS" and new_result == "NOT_ATTEMPTED":
                    return True, "SUBMITTED", ""
            return False, "AMBIGUOUS", f"cancelReplace ambiguous: cancel={cancel_result or 'UNKNOWN'} new={new_result or 'UNKNOWN'}"

        # Spot OCO returns order-list fields rather than one order status.
        if response.get("orderListId") is not None and isinstance(response.get("orderReports"), list):
            reports = [row for row in response.get("orderReports", []) if isinstance(row, dict)]
            statuses = [ExecutionBarrier._response_status(row) for row in reports]
            if reports and all(status in ExecutionBarrier.KNOWN_ORDER_STATES for status in statuses):
                if all(status == "FILLED" for status in statuses):
                    return True, "SUBMITTED", ""
                return True, "SUBMITTED", ""
            return False, "AMBIGUOUS", "OCO response lacks authoritative leg statuses"

        status = ExecutionBarrier._response_status(response)
        if operation == "CANCEL":
            executed = 0.0
            try:
                executed = float(response.get("executedQty", 0) or 0)
            except (TypeError, ValueError):
                return False, "AMBIGUOUS", "cancel response contains invalid executedQty"
            if status in {"CANCELED", "CANCELLED", "EXPIRED", "REJECTED"} and executed <= 0:
                return True, "SUBMITTED", ""
            if status in {"FILLED", "PARTIALLY_FILLED"} or executed > 0:
                return False, "AMBIGUOUS", "cancel/fill race: execution evidence exists; reconcile target order"
            return False, "AMBIGUOUS", "cancellation response lacks authoritative terminal target state"

        if operation == "CANCEL_OCO":
            # A list-level ALL_DONE flag alone cannot tell whether one leg filled
            # before cancellation. Require explicit leg states; any filled leg
            # is a cancel/execute race and must be reconciled by order-list ID.
            legs = response.get("orders") or response.get("orderReports") or []
            if isinstance(legs, list) and legs:
                leg_states = [
                    ExecutionBarrier._response_status(row)
                    for row in legs if isinstance(row, dict)
                ]
                if len(leg_states) == len(legs) and all(
                    value in ExecutionBarrier.KNOWN_ORDER_STATES for value in leg_states
                ):
                    if any(value in {"FILLED", "PARTIALLY_FILLED"} for value in leg_states):
                        return False, "AMBIGUOUS", "OCO cancel/fill race; reconcile order-list legs"
                    if all(value in {"CANCELED", "CANCELLED", "EXPIRED", "REJECTED"} for value in leg_states):
                        return True, "SUBMITTED", ""
            if status in {"FILLED", "PARTIALLY_FILLED"}:
                return False, "AMBIGUOUS", "OCO cancel/fill race; reconcile order-list legs"
            return False, "AMBIGUOUS", "OCO cancellation lacks authoritative per-leg state"

        if status in ExecutionBarrier.KNOWN_ORDER_STATES:
            return True, "SUBMITTED", ""
        if str(response.get("newOrderResult", "")).upper() == "SUCCESS":
            return True, "SUBMITTED", ""
        return False, "AMBIGUOUS", "exchange response lacks authoritative order state"

    def execute(
        self,
        intent: OrderIntent,
        submit: Callable[[], Any],
        *,
        pre_submit_checks: Callable[[MarketStateSnapshot], None] | None = None,
    ) -> ExecutionResult:
        with self.context_cache.execution_lock:
            operation = str(intent.operation_kind or "PLACE").upper()
            fsm = OrderStateMachine()
            fsm.transition(OrderState.ADMISSION)
            is_entry = self._is_new_exposure(intent)
            claimed_entry_lock = False
            claimed_identity = False
            submit_started = False

            snapshot = self.context_cache.snapshot()
            reason = self._validate(
                intent,
                snapshot,
                pre_submit_checks_supplied=pre_submit_checks is not None,
            )
            if reason:
                self._record("WARNING", "execution_blocked", intent, reason)
                # Persisting a BLOCKED diagnostic is useful but not allowed to
                # turn a failed admission into an apparent execution.
                self._persist(intent, "BLOCKED", reason)
                return ExecutionResult(intent.intent_id, False, reason=reason)

            if is_entry:
                try:
                    claimed_entry_lock = self._claim_entry_admission(intent)
                except Exception as exc:
                    reason = f"entry admission lock failed: {type(exc).__name__}: {exc}"
                    self._record("ERROR", "execution_entry_lock_failed", intent, reason)
                    return ExecutionResult(intent.intent_id, False, reason=reason)
                if not claimed_entry_lock:
                    reason = "another entry mutation is active or its owner requires reconciliation"
                    self._record("WARNING", "execution_blocked", intent, reason)
                    return ExecutionResult(intent.intent_id, False, reason=reason)

            try:
                if operation in {"PLACE", "CANCEL", "CANCEL_OCO", "CANCEL_REPLACE"}:
                    try:
                        claimed_identity = self._claim_identities(intent)
                    except Exception as exc:
                        reason = f"execution identity claim failed: {type(exc).__name__}: {exc}"
                        self._record("ERROR", "execution_identity_claim_failed", intent, reason)
                        return ExecutionResult(intent.intent_id, False, reason=reason)
                    if not claimed_identity:
                        reason = "duplicate clientOrderId or logical signal claim refused"
                        self._record("ERROR", "execution_duplicate_mutation_blocked", intent, reason)
                        return ExecutionResult(intent.intent_id, False, reason=reason)

                if pre_submit_checks is not None:
                    try:
                        pre_submit_checks(snapshot)
                    except Exception as exc:
                        reason = f"pre_submit_check_failed: {type(exc).__name__}: {exc}"
                        self._persist(intent, "BLOCKED", reason)
                        self._record("WARNING", "execution_blocked", intent, reason)
                        if claimed_identity:
                            self._release_identity_claims(intent)
                        return ExecutionResult(intent.intent_id, False, reason=reason)

                # Re-check signal age and exact context versions after caller
                # risk/ownership checks, immediately before the write-ahead commit.
                # The shared context execution lock prevents a context publish
                # from slipping between this check and the exchange mutation.
                if is_entry:
                    age_ms = int(time.time() * 1000) - int(intent.created_at_ms or 0)
                    if age_ms < 0 or age_ms > int(intent.max_age_ms):
                        reason = f"entry intent expired during final checks: age={age_ms}ms"
                    else:
                        final_snapshot = self.context_cache.snapshot()
                        reason = ""
                        for tf, required in intent.required_context_versions.items():
                            latest_context = final_snapshot.context(intent.symbol, tf)
                            if latest_context is None or int(latest_context.version) != int(required):
                                reason = (
                                    f"final context version mismatch for {tf}: "
                                    f"required={required}, current="
                                    f"{getattr(latest_context, 'version', None)}"
                                )
                                break
                    if reason:
                        self._persist(intent, "BLOCKED", reason)
                        self._record("WARNING", "execution_blocked", intent, reason)
                        if claimed_identity:
                            self._release_identity_claims(intent)
                        return ExecutionResult(intent.intent_id, False, reason=reason)

                # First durable marker proves the logical mutation was admitted.
                # Then SUBMITTING is committed immediately before the network
                # call. A crash from this point onward is treated as ambiguous.
                if not self._persist(intent, "PENDING"):
                    reason = "execution_intent_persistence_failed: no order mutation was sent"
                    self._record("ERROR", "execution_intent_persistence_failed", intent, reason)
                    if claimed_identity:
                        self._release_identity_claims(intent)
                    return ExecutionResult(intent.intent_id, False, reason=reason)

                if not self._persist(intent, "SUBMITTING"):
                    reason = "SUBMITTING durability confirmation failed: no order mutation was sent"
                    self._record("ERROR", "execution_submitting_persistence_failed", intent, reason)
                    if claimed_identity:
                        self._release_identity_claims(intent)
                    return ExecutionResult(intent.intent_id, False, reason=reason)

                # Cancellation updates the already-existing target order.
                # Its lifecycle starts at OPEN -> CANCEL_PENDING; it is not a
                # newly-created order and must not pretend to traverse SUBMITTING.
                if operation in {"CANCEL", "CANCEL_OCO"}:
                    fsm = OrderStateMachine(OrderState.OPEN)
                    fsm.transition(OrderState.CANCEL_PENDING)
                else:
                    fsm.transition(OrderState.SUBMITTING)
                submit_started = True
                self._record(
                    "INFO",
                    "execution_admitted",
                    intent,
                    "Durable intent committed/read back; sending one exchange mutation",
                    {
                        "intent_id": intent.intent_id,
                        "symbol": intent.symbol,
                        "side": intent.side,
                        "operation_kind": intent.operation_kind,
                        "purpose": intent.purpose,
                        "client_order_id": intent.client_order_id,
                        "signal_id": intent.signal_id,
                    },
                )
                try:
                    response = submit()
                except Exception as exc:
                    # Even a transport exception can occur after Binance accepted
                    # the request. Never retry under a fresh clientOrderId.
                    fsm.state = OrderState.AMBIGUOUS
                    reason = f"{type(exc).__name__}: {exc}"
                    self._persist(intent, "AMBIGUOUS", reason)
                    self._set_reconciliation_lock(intent, reason)
                    raise ExecutionAmbiguousError(
                        "Binance mutation outcome is UNKNOWN; reconcile by stable client/order identity"
                    ) from exc

                authoritative, final_status, response_reason = self._mutation_response_is_authoritative(
                    intent, response
                )
                if not authoritative:
                    fsm.state = OrderState.RECONCILE_REQUIRED
                    recorded = self._record_exchange_result(
                        intent, response, "AMBIGUOUS", response_reason
                    )
                    self._persist(intent, "AMBIGUOUS", response_reason)
                    self._set_reconciliation_lock(
                        intent,
                        response_reason if recorded else (
                            f"{response_reason}; mandatory exchange-response persistence failed"
                        ),
                    )
                    raise ExecutionAmbiguousError(
                        f"ExecutionBarrier: {response_reason}; no retry is permitted"
                    )

                # Persist the raw Binance response and extracted exchange fields
                # as mandatory execution state. Leave the intent SUBMITTING until
                # the separate final-state write succeeds, so a failure in that
                # second write remains unresolved after process restart.
                if not self._record_exchange_result(
                    intent, response, "SUBMITTING"
                ):
                    reason = (
                        "Binance response received but mandatory exchange-result "
                        "persistence/read-back failed; reconcile by stable exchange identity"
                    )
                    self._persist(intent, "AMBIGUOUS", reason)
                    self._set_reconciliation_lock(intent, reason)
                    raise ExecutionAmbiguousError(f"ExecutionBarrier: {reason}")

                if not self._persist(intent, final_status):
                    reason = (
                        "exchange response was durably recorded but the final intent "
                        "state could not be confirmed; reconcile before any new entry"
                    )
                    self._persist(intent, "RECONCILE_REQUIRED", reason)
                    self._set_reconciliation_lock(intent, reason)
                    raise ExecutionAmbiguousError(f"ExecutionBarrier: {reason}")

                status = self._response_status(response)
                oco_reports = (
                    response.get("orderReports")
                    if isinstance(response, dict) else None
                )
                if (
                    operation == "PLACE"
                    and isinstance(response, dict)
                    and response.get("orderListId") is not None
                    and isinstance(oco_reports, list)
                    and oco_reports
                ):
                    leg_statuses = [
                        self._response_status(leg)
                        for leg in oco_reports if isinstance(leg, dict)
                    ]
                    leg_fills = []
                    for leg in oco_reports:
                        try:
                            leg_fills.append(float(leg.get("executedQty", 0) or 0))
                        except (TypeError, ValueError):
                            leg_fills.append(-1.0)
                    if (
                        len(leg_statuses) == len(oco_reports)
                        and all(x in {"NEW", "PENDING_NEW"} for x in leg_statuses)
                        and all(x == 0.0 for x in leg_fills)
                    ):
                        fsm.state = OrderState.OPEN
                    else:
                        # Any filled or non-terminal/unknown OCO leg requires
                        # reconciliation of orderList and leg executions.
                        fsm.state = OrderState.RECONCILE_REQUIRED
                elif operation == "CANCEL_REPLACE":
                    cancel_result = str(response.get("cancelResult", "")).upper()
                    new_result = str(response.get("newOrderResult", "")).upper()
                    replacement = response.get("newOrderResponse")
                    replacement_status = self._response_status(replacement)
                    if cancel_result == "SUCCESS" and new_result == "SUCCESS" and replacement_status in {"NEW", "PENDING_NEW"}:
                        fsm.state = OrderState.OPEN
                    elif cancel_result == "SUCCESS" and new_result == "SUCCESS" and replacement_status == "PARTIALLY_FILLED":
                        fsm.state = OrderState.PARTIALLY_FILLED
                    elif cancel_result == "SUCCESS" and new_result == "SUCCESS" and replacement_status == "FILLED":
                        fsm.state = OrderState.FILLED
                    else:
                        fsm.state = OrderState.RECONCILE_REQUIRED
                elif operation == "CANCEL_OCO":
                    # The mutation response was accepted only with explicit
                    # non-filled terminal states for every leg.
                    fsm.state = OrderState.CANCELED
                elif operation == "CANCEL":
                    if status in {"CANCELED", "CANCELLED", "EXPIRED", "REJECTED"}:
                        fsm.observe_exchange_status(
                            "CANCELED" if status == "CANCELLED" else status,
                            float(response.get("executedQty", 0) or 0),
                        )
                    else:
                        fsm.state = OrderState.RECONCILE_REQUIRED
                elif status in {"NEW", "PENDING_NEW", "PARTIALLY_FILLED", "FILLED", "CANCELED", "CANCELLED", "EXPIRED", "REJECTED"}:
                    fsm.observe_exchange_status(
                        "CANCELED" if status == "CANCELLED" else status,
                        float(response.get("executedQty", 0) or 0),
                    )
                else:
                    fsm.state = OrderState.RECONCILE_REQUIRED

                if fsm.state == OrderState.RECONCILE_REQUIRED:
                    self._set_reconciliation_lock(
                        intent,
                        "order state transition needs authoritative reconciliation",
                    )
                    raise ExecutionAmbiguousError(
                        "order state requires authoritative reconciliation"
                    )

                if self.db is not None:
                    save_event = getattr(self.db, "save_execution_event", None)
                    if callable(save_event):
                        try:
                            save_event(intent.intent_id, "BINANCE_MUTATION_CONFIRMED", {
                                "symbol": intent.symbol,
                                "side": intent.side,
                                "status": status,
                                "executedQty": response.get("executedQty", 0) if isinstance(response, dict) else None,
                            })
                        except Exception:
                            pass
                self._record(
                    "INFO",
                    "execution_mutation_confirmed",
                    intent,
                    "Binance returned an authoritative mutation response",
                    {"intent_id": intent.intent_id, "status": status},
                )
                return ExecutionResult(intent.intent_id, True, response=response)
            finally:
                # On pre-submit failures or authoritative results the transient
                # entry mutex may be released. Any ambiguous path deliberately
                # retains it and the persistent reconciliation lock.
                if claimed_entry_lock and not self._local_mutation_lock_reason and not submit_started:
                    try:
                        self._release_entry_admission(intent)
                    except Exception:
                        pass
                elif claimed_entry_lock and submit_started and not self._local_mutation_lock_reason:
                    try:
                        self._release_entry_admission(intent)
                    except Exception:
                        pass
