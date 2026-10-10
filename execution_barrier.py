"""P0 serialized execution barrier for Williams."""
from __future__ import annotations

import math
import time
import uuid
from dataclasses import dataclass
from typing import Any, Callable, Mapping

from market_context import ContextCache, MarketStateSnapshot
from order_state_machine import OrderState, OrderStateMachine


def _interval_ms(interval: str) -> int:
    value = str(interval or "").strip()
    if value == "1M":
        return 30 * 24 * 60 * 60 * 1000
    if not value:
        return 0
    unit = value[-1].lower()
    try:
        number = int(value[:-1])
    except (TypeError, ValueError):
        return 0
    multiplier = {"m": 60_000, "h": 3_600_000, "d": 86_400_000, "w": 604_800_000}
    return number * multiplier[unit] if number > 0 and unit in multiplier else 0


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
    # Strict direction permission is the default. TC2 WM1 may use a distinct
    # early-reversal admission contract, still requiring fresh H1/H4/D1 context
    # and a proven reversal/angulation signal at this final mutation boundary.
    context_admission_mode: str = "STRICT_DIRECTIONAL"
    signal_type: str = ""
    angulation_score: float = 0.0
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

    def __init__(
        self,
        context_cache: ContextCache,
        db=None,
        *,
        require_durable_intent: bool = True,
    ) -> None:
        self.context_cache = context_cache
        self.db = db
        # Volatile execution is permitted only when explicitly requested by
        # deterministic tests. Production callers must have a durable store.
        self.require_durable_intent = bool(require_durable_intent)

    def _persist(self, intent: OrderIntent, status: str, reason: str = "") -> bool:
        """Persist an intent state and report failure to the caller.

        A failed write is never treated as a successful durable intent. The
        caller must abort before submit or require exchange reconciliation
        after a submit has already been attempted.
        """
        if self.db is None or not callable(
            getattr(self.db, "save_execution_intent", None)
        ):
            return False
        try:
            self.db.save_execution_intent(intent, status, reason)
        except Exception:
            return False
        return True

    def _record(self, level: str, event: str, intent: OrderIntent, message: str, raw=None) -> None:
        if self.db is not None and hasattr(self.db, "log_event"):
            try:
                self.db.log_event(level, event, message, raw or {"intent_id": intent.intent_id})
            except Exception:
                pass

    def _validate(self, intent: OrderIntent, snapshot: MarketStateSnapshot) -> str:
        purpose = intent.purpose.upper()
        entry_purposes = {
            "ENTRY",
            "ADD_ON",
            "CAMPAIGN_ENTRY",
            "CAMPAIGN_ADD_ON",
            "REVERSE_ENTRY",
            "CAMPAIGN_REVERSE_ENTRY",
        }
        is_new_exposure = purpose in entry_purposes

        # Freshness is an admission rule for increasing exposure. A delayed
        # protective action, cancellation, recovery operation, or reduce-only
        # exit must not be stranded merely because its intent aged while waiting
        # for the execution lock. These non-entry paths must enforce their own
        # live-position/price/ownership checks before submission.
        if is_new_exposure and intent.created_at_ms:
            age = int(time.time() * 1000) - intent.created_at_ms
            if age > intent.max_age_ms:
                return f"stale intent age={age}ms"

        # Missing context must block new exposure, not cancellation, protection,
        # exit, or recovery. Any context versions that are supplied are still
        # validated below. Campaign entry paths may be armed before persistent
        # versions exist, but must perform their own final risk/context check.
        if is_new_exposure and not intent.required_context_versions:
            if not purpose.startswith("CAMPAIGN_"):
                return "missing required_context_versions"
        # Market-context versions are admission dependencies for new exposure
        # only. Protective actions, cancellations, exits and recovery must remain
        # available when strategy context is stale or temporarily unavailable.
        if is_new_exposure:
            normalized_versions = {
                ("1M" if str(tf) == "1M" else str(tf).lower()): int(version)
                for tf, version in dict(intent.required_context_versions or {}).items()
            }
            for tf, required in normalized_versions.items():
                ctx = snapshot.context(intent.symbol, tf)
                if ctx is None:
                    return f"missing context {intent.symbol} {tf}"
                if int(ctx.version) != int(required):
                    return f"stale context {tf}: required={required} current={ctx.version}"
                duration = _interval_ms(tf)
                if duration > 0:
                    try:
                        close_ms = int(ctx.candle_close_time_ms)
                        age_ms = int(time.time() * 1000) - close_ms
                    except (TypeError, ValueError, OverflowError):
                        return f"invalid context candle timestamp {tf}"
                    if (
                        close_ms <= 0
                        or age_ms < -60_000
                        or age_ms > max(120_000, 2 * duration)
                    ):
                        return f"stale/invalid closed-candle context {tf}"

        direction = "long" if intent.side == "BUY" else "short" if intent.side == "SELL" else ""
        if not direction:
            return f"unsupported side {intent.side}"
        # Every mutating non-cancel order needs a durable client identity so a
        # timeout/restart can query the same exchange operation instead of
        # submitting a duplicate under a new ID.
        if intent.order_type.upper() != "CANCEL" and not str(intent.client_order_id or "").strip():
            return "missing stable client_order_id"

        # All declared TFs are version dependencies, but the permission
        # decision belongs to one operative/entry timeframe. Higher TFs provide
        # structural context and must not be required to emit a duplicate trigger.
        # New exposure must pass the operative timeframe's directional gate.
        # SELL means opening SHORT only for an ENTRY/ADD-ON intent; on exits it
        # is simply an order side and must not be interpreted as a short signal.
        if is_new_exposure:
            permission_tf = str(intent.permission_interval or "").strip()
            if permission_tf != "1M":
                permission_tf = permission_tf.lower()
            permission_ctx = snapshot.context(intent.symbol, permission_tf) if permission_tf else None
            if permission_ctx is None:
                return f"missing permission context {intent.symbol} {permission_tf}"
            tc2_core_profile = (
                os.getenv("WILLIAMS_STRATEGY_PROFILE", "TC2_THREE_WISE_MEN").strip().upper()
                == "TC2_THREE_WISE_MEN"
            )
            if (
                tc2_core_profile
                and purpose in {"CAMPAIGN_ENTRY", "CAMPAIGN_ADD_ON"}
                and permission_tf != "1h"
            ):
                return "TC2 Three Wise Men campaign mutations require canonical H1 decision context"

            admission_mode = str(
                getattr(intent, "context_admission_mode", "STRICT_DIRECTIONAL") or "STRICT_DIRECTIONAL"
            ).strip().upper()
            if admission_mode == "TC2_WM1_EARLY":
                if purpose != "CAMPAIGN_ENTRY":
                    return "TC2_WM1_EARLY is allowed only for a new initial campaign entry"
                if str(getattr(intent, "signal_type", "") or "").upper() != "REVERSAL":
                    return "TC2_WM1_EARLY requires a REVERSAL signal"
                try:
                    angulation = float(getattr(intent, "angulation_score", 0.0))
                except (TypeError, ValueError, OverflowError):
                    return "TC2_WM1_EARLY angulation evidence is invalid"
                if not math.isfinite(angulation) or angulation <= 0.0:
                    return "TC2_WM1_EARLY requires finite positive angulation evidence"

                # Preserve the actual source's early-reversal capability: H1/H4
                # need not already agree with the new direction. Their contexts
                # must still exist, be fresh, and be pinned by the intent versions.
                # D1 is only a macro airbag and may veto an active opposite regime.
                for tf in ("1h", "4h", "1d"):
                    ctx = snapshot.context(intent.symbol, tf)
                    if ctx is None or tf not in normalized_versions:
                        return f"TC2_WM1_EARLY requires versioned {tf} context"
                    if not ctx.williams_core_ready:
                        return f"TC2_WM1_EARLY requires valid Williams indicator evidence on {tf}"
                macro = snapshot.context(intent.symbol, "1d")
                macro_state = str(macro.alligator_state or "").strip().upper()
                macro_opposes_long = (
                    macro_state == "BEARISH"
                    and bool(macro.alligator_awake)
                    and math.isfinite(float(macro.ao_value))
                    and float(macro.ao_value) < 0.0
                )
                macro_opposes_short = (
                    macro_state == "BULLISH"
                    and bool(macro.alligator_awake)
                    and math.isfinite(float(macro.ao_value))
                    and float(macro.ao_value) > 0.0
                )
                if direction == "long" and macro_opposes_long:
                    return "TC2_WM1_EARLY blocked by active opposite D1 context"
                if direction == "short" and macro_opposes_short:
                    return "TC2_WM1_EARLY blocked by active opposite D1 context"
            elif admission_mode == "STRICT_DIRECTIONAL":
                tc2_core = (
                    os.getenv("WILLIAMS_STRATEGY_PROFILE", "TC2_THREE_WISE_MEN").strip().upper()
                    == "TC2_THREE_WISE_MEN"
                )
                if tc2_core and permission_tf == "1h":
                    # H4 is context only, but it must be present, fresh, and
                    # mathematically valid. It must not become a second H1 signal.
                    for tf in ("1h", "4h", "1d"):
                        ctx = snapshot.context(intent.symbol, tf)
                        if ctx is None or tf not in normalized_versions:
                            return f"TC2 entry requires versioned {tf} context"
                        if not bool(ctx.williams_core_ready):
                            return f"TC2 entry requires valid Williams indicator context on {tf}"

                    state = str(permission_ctx.alligator_state or "").strip().upper()
                    try:
                        ao_value = float(permission_ctx.ao_value)
                        awake = bool(permission_ctx.alligator_awake)
                    except (TypeError, ValueError, OverflowError):
                        return f"context {permission_tf} does not allow {direction.upper()}: invalid Williams evidence"
                    if not math.isfinite(ao_value):
                        return f"context {permission_tf} does not allow {direction.upper()}: non-finite AO"
                    if direction == "long" and not (
                        state == "BULLISH" and awake
                    ):
                        return "context 1h does not allow LONG"
                    if direction == "short" and not (
                        state == "BEARISH" and awake
                    ):
                        return "context 1h does not allow SHORT"

                    # D1 is the macro airbag. Its opposite active Alligator/AO state
                    # may veto a new campaign, but it does not create a signal.
                    macro = snapshot.context(intent.symbol, "1d")
                    if macro is None or not macro.williams_core_ready:
                        return "missing/invalid D1 Williams macro context"
                    macro_state = str(macro.alligator_state or "").strip().upper()
                    try:
                        macro_ao = float(macro.ao_value)
                    except (TypeError, ValueError, OverflowError):
                        return "invalid D1 AO evidence"
                    if not math.isfinite(macro_ao):
                        return "invalid D1 AO evidence"
                    macro_opposes_long = (
                        macro_state == "BEARISH"
                        and bool(macro.alligator_awake)
                        and macro_ao < 0.0
                    )
                    macro_opposes_short = (
                        macro_state == "BULLISH"
                        and bool(macro.alligator_awake)
                        and macro_ao > 0.0
                    )
                    if direction == "long" and macro_opposes_long:
                        return "active opposite D1 macro context blocks LONG"
                    if direction == "short" and macro_opposes_short:
                        return "active opposite D1 macro context blocks SHORT"
                else:
                    # Explicit legacy/profile integrations remain separate from
                    # TC2 and preserve their own directional permission contract.
                    if direction == "long" and not permission_ctx.allow_long:
                        return f"context {permission_tf} does not allow LONG"
                    if direction == "short" and not permission_ctx.allow_short:
                        return f"context {permission_tf} does not allow SHORT"
            else:
                return f"unsupported context admission mode {admission_mode}"

        # Reconciliation blocks any operation that can increase exposure, but
        # must not disable exits, cancellation, protection or recovery. Those
        # paths still need their own reduce-only / ownership guarantees.
        if is_new_exposure and self.db is not None and hasattr(self.db, "state_get"):
            # Reconciliation locks are persisted per symbol by the Futures
            # runtime. Checking only the legacy global key allowed a fresh
            # campaign to miss an orphan-position lock for its own symbol.
            symbol_state = str(
                self.db.state_get(f"position_state:{intent.symbol}", "FLAT")
            ).upper()
            global_state = str(self.db.state_get("position_state", "FLAT")).upper()
            if "RECONCILE_REQUIRED" in {symbol_state, global_state}:
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

            # A stable client ID is the exchange-side idempotency key. If
            # any earlier non-cancel intent with this ID reached PENDING or
            # beyond, do not issue another POST: resolve the original request
            # by querying Binance first. A BLOCKED row is safe to retry because
            # validation guarantees submit() was never called.
            if (
                intent.order_type.upper() != "CANCEL"
                and self.db is not None
                and callable(getattr(self.db, "find_execution_intent_by_client_order_id", None))
                and str(intent.client_order_id or "").strip()
            ):
                try:
                    prior = self.db.find_execution_intent_by_client_order_id(
                        intent.client_order_id,
                        symbol=intent.symbol,
                    )
                except Exception as exc:
                    reason = (
                        "execution_idempotency_lookup_failed: submission aborted; "
                        f"cannot prove client ID is unused ({type(exc).__name__}: {exc})"
                    )
                    self._record("ERROR", "execution_idempotency_lookup_failed", intent, reason)
                    return ExecutionResult(intent.intent_id, False, reason=reason)
                if prior and str(prior.get("status", "")).upper() != "BLOCKED":
                    reason = (
                        "duplicate client_order_id refused; prior durable intent "
                        f"{prior.get('intent_id')} is {prior.get('status') or 'UNKNOWN'}; "
                        "reconcile the existing Binance order before retrying"
                    )
                    self._persist(intent, "IDEMPOTENCY_BLOCKED", reason)
                    self._record("ERROR", "execution_duplicate_client_order_id", intent, reason, {
                        "prior_intent_id": prior.get("intent_id"),
                        "prior_status": prior.get("status"),
                        "client_order_id": intent.client_order_id,
                    })
                    return ExecutionResult(intent.intent_id, False, reason=reason)

            # Durable intent must exist before the first exchange mutation.
            # When storage is unavailable, fail closed and never call submit().
            persisted = self._persist(intent, "PENDING")
            if not persisted and (
                self.db is not None or self.require_durable_intent
            ):
                reason = (
                    "execution_intent_persistence_failed: submission aborted; "
                    "durable PENDING intent could not be confirmed"
                )
                self._record("ERROR", "execution_intent_persistence_failed", intent, reason)
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

            if intent.order_type.upper() != "CANCEL":
                response_status = ""
                if isinstance(response, dict):
                    response_status = str(
                        response.get("status")
                        or response.get("algoStatus")
                        or ""
                    ).upper()

                    # Binance Spot OCO returns an order-list envelope rather
                    # than a top-level order status. Only treat it as accepted
                    # when the response includes authoritative order reports.
                    if not response_status and response.get("orderListId") is not None:
                        reports = response.get("orderReports") or []
                        report_statuses = [
                            str(row.get("status", "")).upper()
                            for row in reports
                            if isinstance(row, dict)
                        ]
                        if report_statuses and all(
                            value in {"NEW", "PARTIALLY_FILLED", "FILLED", "CANCELED", "EXPIRED"}
                            for value in report_statuses
                        ):
                            response_status = (
                                "FILLED"
                                if all(value == "FILLED" for value in report_statuses)
                                else "NEW"
                            )

                if response_status:
                    order_fsm.observe_exchange_status(
                        response_status,
                        float(
                            response.get("executedQty", 0) or 0
                            if isinstance(response, dict)
                            else 0
                        ),
                    )
                    if order_fsm.state == OrderState.RECONCILE_REQUIRED:
                        reason = (
                            f"exchange status {response_status} is not valid "
                            "for the expected order lifecycle"
                        )
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
            submitted_persisted = self._persist(intent, "SUBMITTED")
            if not submitted_persisted and (
                self.db is not None or self.require_durable_intent
            ):
                reason = (
                    "exchange_response_received_but_SUBMITTED_state_persistence_failed; "
                    "reconcile by stable client_order_id before any further mutation"
                )
                self._record(
                    "ERROR",
                    "execution_submission_persistence_failed",
                    intent,
                    reason,
                    {"intent_id": intent.intent_id, "client_order_id": intent.client_order_id},
                )
                raise RuntimeError(f"ExecutionBarrier: {reason}")
            if self.db is not None and hasattr(self.db, "save_execution_event"):
                try:
                    self.db.save_execution_event(intent.intent_id, "BINANCE_SUBMITTED", {"symbol": intent.symbol, "side": intent.side})
                except Exception:
                    pass
            self._record("INFO", "execution_submitted", intent, "Binance accepted request", {
                "intent_id": intent.intent_id,
            })
            return ExecutionResult(intent.intent_id, True, response=response)
