"""Williams campaign coordinator.

This layer is intentionally deterministic and exchange-agnostic. It turns
strategy observations into a durable campaign lifecycle and risk reservations.
The Binance adapter is responsible only for expressing an admitted decision as
an exchange order and verifying the resulting state.
"""

from __future__ import annotations

from dataclasses import asdict
import math
from typing import Any, Iterable
import json
import time
import uuid

from decision_trace import DecisionTrace
from pending_signal import PendingSignal
from campaign_model import (
    CampaignEventType,
    CampaignState,
    SignalRole,
    SignalSpec,
    SignalType,
    SignalState,
    TradingCampaign,
    reverse_pyramid_risk_weight,
    stop_only_reduces_risk,
)


class CampaignEngine:
    def __init__(
        self,
        db,
        *,
        portfolio_risk_limit_pct: float = 0.01,
        campaign_risk_limit_pct: float = 0.005,
        initial_risk_fraction_of_campaign: float = 0.40,
    ) -> None:
        self.db = db
        self.portfolio_risk_limit_pct = max(0.0, min(0.01, float(portfolio_risk_limit_pct)))
        self.campaign_risk_limit_pct = max(0.0, min(0.005, float(campaign_risk_limit_pct)))
        self.initial_risk_fraction = max(
            0.05,
            min(1.0, float(initial_risk_fraction_of_campaign)),
        )

    # ------------------------------------------------------------------
    # Campaign identity / persistence
    # ------------------------------------------------------------------

    @staticmethod
    def new_campaign_id() -> str:
        return uuid.uuid4().hex

    def create_campaign(self, signal: SignalSpec, *, initial_risk_pct: float) -> TradingCampaign:
        campaign = TradingCampaign(
            campaign_id=self.new_campaign_id(),
            symbol=signal.symbol,
            side=signal.side,
            execution_timeframe=signal.timeframe,
            state=CampaignState.SIGNAL_DETECTED,
            origin_signal_id=signal.signal_id,
            current_signal_id=signal.signal_id,
            current_signal_type=signal.signal_type.value,
            wave_position=0,
            wave_confidence=signal.wave_confidence,
            wave_exhaustion_risk=signal.wave_exhaustion_risk,
            htf_confirmed=signal.htf_confirmed,
            next_action="ARM_ENTRY",
            tags={
                "initial_risk_pct": float(initial_risk_pct),
                "entry_trigger": float(signal.trigger_price),
                "initial_stop_price": float(signal.protective_reference),
                "signal_reason": signal.reason,
                "signal_role": signal.role.value,
                "wave_confidence": float(signal.wave_confidence),
                "wave_exhaustion_risk": float(signal.wave_exhaustion_risk),
                "htf_confirmed": bool(signal.htf_confirmed),
                "pending_signal": PendingSignal.from_spec(signal).to_dict(),
                "decision_trace": DecisionTrace.from_signal(signal).to_dict(),
            },
        )
        self.db.save_campaign(campaign)
        self.db.save_campaign_signal(
            signal,
            campaign.campaign_id,
            state=SignalState.DETECTED.value,
        )
        self.db.log_campaign_event(
            campaign.campaign_id,
            CampaignEventType.SIGNAL_DETECTED.value,
            signal_id=signal.signal_id,
            reason=signal.reason,
            payload=signal.to_dict(),
        )
        return campaign

    def load_campaign(self, campaign_id: str) -> TradingCampaign | None:
        row = self.db.get_campaign(campaign_id)
        if row is None:
            return None
        tags = {}
        wave_context = {}
        try:
            tags = json.loads(row.get("tags_json") or "{}")
        except Exception:
            pass
        try:
            wave_context = json.loads(row.get("wave_context_json") or "{}")
        except Exception:
            pass
        return TradingCampaign(
            campaign_id=row["campaign_id"],
            symbol=row["symbol"],
            side=row["side"],
            execution_timeframe=row["execution_timeframe"],
            state=CampaignState(row["state"]),
            origin_signal_id=row.get("origin_signal_id") or "",
            current_signal_id=row.get("current_signal_id") or "",
            current_signal_type=row.get("current_signal_type") or "",
            position_qty=float(row.get("position_qty") or 0),
            average_entry_price=float(row.get("average_entry_price") or 0),
            initial_stop_price=float(
                row.get("initial_stop_price") or tags.get("initial_stop_price") or 0
            ),
            current_stop_price=float(
                row.get("current_stop_price") or tags.get("initial_stop_price") or 0
            ),
            structural_stop_source=row.get("structural_stop_source") or "",
            additions=int(row.get("additions") or 0),
            tranche_index=int(row.get("tranche_index") or 0),
            realized_pnl_quote=float(row.get("realized_pnl_quote") or 0),
            unrealized_pnl_quote=float(row.get("unrealized_pnl_quote") or 0),
            open_risk_quote=float(row.get("open_risk_quote") or 0),
            pending_risk_quote=float(row.get("pending_risk_quote") or 0),
            capital_reserved_quote=float(row.get("capital_reserved_quote") or 0),
            wave_position=int(tags.get("wave_position", 0) or 0),
            wave_phase=str(tags.get("wave_phase", "UNKNOWN")),
            wave_confidence=float(tags.get("wave_confidence", 0.0) or 0.0),
            wave_exhaustion_risk=float(tags.get("wave_exhaustion_risk", 0.0) or 0.0),
            htf_confirmed=bool(tags.get("htf_confirmed", False)),
            health=row.get("health") or "GREEN",
            next_action=row.get("next_action") or "WAIT",
            reconciliation_state=row.get("reconciliation_state") or "CLEAN",
            created_at_ms=int(tags.get("created_at_ms") or int(time.time()*1000)),
            updated_at_ms=int(time.time()*1000),
            exit_reason=row.get("exit_reason") or "",
            tags={**tags, "wave_context": wave_context},
        )

    # ------------------------------------------------------------------
    # Signal ordering / replacement
    # ------------------------------------------------------------------

    @staticmethod
    @staticmethod
    def pending_signal(signal: SignalSpec, *, campaign_id: str = "") -> PendingSignal:
        return PendingSignal.from_spec(signal, campaign_id=campaign_id)

    @staticmethod
    def decision_trace(signal: SignalSpec, *, extras: dict[str, Any] | None = None) -> DecisionTrace:
        return DecisionTrace.from_signal(signal, extras=extras)

    @staticmethod
    def choose_initial_signal(signals: Iterable[SignalSpec]) -> SignalSpec | None:
        candidates = [
            s for s in signals
            if s.role == SignalRole.ENTRY
            and str(s.direction).upper() in {"LONG", "SHORT"}
            and s.trigger_price > 0
        ]
        if not candidates:
            return None
        # Book model: the first signal that became actionable starts the
        # campaign. For WM3, confirmation occurs after the source/center bar,
        # so rank by confirmation chronology rather than historical center time.
        return min(candidates, key=lambda s: (int(getattr(s, "confirmation_time_ms", 0) or s.signal_bar_time_ms), s.created_at_ms))

    @staticmethod
    def should_replace_pending(old: SignalSpec, new: SignalSpec, *, min_ticks: int = 1, tick_size: float = 0.0) -> bool:
        if old.signal_id == new.signal_id:
            return False
        if old.symbol != new.symbol or old.side != new.side:
            return True
        distance = abs(float(new.trigger_price) - float(old.trigger_price))
        threshold = max(0.0, float(tick_size)) * max(1, int(min_ticks))
        old_pending = PendingSignal.from_spec(old)
        new_pending = PendingSignal.from_spec(new)
        if old_pending.is_expired() and not new_pending.is_expired():
            return True
        return (
            new.signal_bar_time_ms > old.signal_bar_time_ms
            and distance >= threshold
            and not new_pending.is_expired()
        )

    # ------------------------------------------------------------------
    # Risk reservation
    # ------------------------------------------------------------------

    def portfolio_reserved_risk_quote(self) -> float:
        return float(self.db.campaign_risk_reserved_quote())

    def portfolio_reserved_capital_quote(self) -> float:
        return float(self.db.campaign_capital_reserved_quote())

    def remaining_portfolio_risk_quote(self, equity_quote: float) -> float:
        capacity = max(0.0, float(equity_quote) * self.portfolio_risk_limit_pct)
        return max(0.0, capacity - self.portfolio_reserved_risk_quote())

    def initial_risk_pct(self) -> float:
        return min(
            self.campaign_risk_limit_pct,
            self.campaign_risk_limit_pct * self.initial_risk_fraction,
        )

    def next_add_on_risk_pct(
        self,
        *,
        campaign_reserved_risk_quote: float,
        equity_quote: float,
        tranche_index: int = 1,
    ) -> float:
        if equity_quote <= 0:
            return 0.0
        campaign_capacity = equity_quote * self.campaign_risk_limit_pct
        remaining = max(
            0.0,
            campaign_capacity - float(campaign_reserved_risk_quote),
        )
        weight = reverse_pyramid_risk_weight(tranche_index)
        # 1:5:4:3:2 is retained as a risk-allocation bias only. The hard
        # campaign cap always dominates the historical ratio.
        total_weight = 1 + 5 + 4 + 3 + 2
        weighted = self.campaign_risk_limit_pct * (weight / total_weight)
        return min(remaining / equity_quote, weighted)

    # ------------------------------------------------------------------
    # State decisions
    # ------------------------------------------------------------------

    def arm_entry(self, campaign: TradingCampaign, signal: SignalSpec) -> TradingCampaign:
        if campaign.state not in {CampaignState.SIGNAL_DETECTED, CampaignState.ENTRY_ARMING}:
            raise ValueError(f"Cannot arm entry from {campaign.state.value}")
        campaign.current_signal_id = signal.signal_id
        campaign.current_signal_type = signal.signal_type.value
        campaign.next_action = "SUBMIT_CONDITIONAL_ENTRY"
        if campaign.state == CampaignState.SIGNAL_DETECTED:
            campaign.transition(CampaignState.ENTRY_ARMING, reason="valid Williams entry signal")
        campaign.transition(CampaignState.ENTRY_PENDING, reason="conditional entry admitted")
        self.db.set_campaign_signal_state(signal.signal_id, SignalState.ARMED.value)
        self.db.save_campaign(campaign)
        self.db.log_campaign_event(
            campaign.campaign_id,
            CampaignEventType.ENTRY_ARMED.value,
            signal_id=signal.signal_id,
            reason=signal.reason,
            payload=signal.to_dict(),
        )
        return campaign

    def arm_add_on(self, campaign: TradingCampaign, signal: SignalSpec, *, risk_quote: float, capital_reserved_quote: float) -> TradingCampaign:
        if campaign.position_qty <= 0:
            raise ValueError("add-on requires an open campaign position")
        signal_direction = str(getattr(signal, "direction", "") or "").upper()
        if signal_direction not in {"LONG", "SHORT"}:
            signal_direction = {
                "BUY": "LONG", "LONG": "LONG",
                "SELL": "SHORT", "SHORT": "SHORT",
            }.get(str(signal.side).upper(), "")
        campaign_direction = str(campaign.tags.get("direction", "") or "").upper()
        if campaign_direction not in {"LONG", "SHORT"}:
            campaign_direction = {
                "BUY": "LONG", "LONG": "LONG",
                "SELL": "SHORT", "SHORT": "SHORT",
            }.get(str(campaign.side).upper(), "")
        if not signal_direction or signal_direction != campaign_direction:
            raise ValueError("add-on direction does not match campaign")
        if signal.role != SignalRole.ADD_ON:
            raise ValueError("later Wise-Men signals must be explicitly classified as ADD_ON")
        if signal.signal_type not in {SignalType.SUPER_AO, SignalType.FRACTAL}:
            raise ValueError("only second/third Wise-Men signals may add to an existing campaign")
        if campaign.additions >= 2:
            raise ValueError("campaign has reached the maximum of two Wise-Men add-ons")
        if signal.signal_bar_time_ms <= 0:
            raise ValueError("add-on signal has no valid signal time")
        if signal.signal_id in {campaign.origin_signal_id, campaign.current_signal_id}:
            raise ValueError("duplicate signal cannot create another add-on")
        if campaign.state not in {
            CampaignState.OPEN_INITIAL,
            CampaignState.TREND_ACTIVE,
            CampaignState.TRAILING,
        }:
            raise ValueError(f"Cannot arm add-on from {campaign.state.value}")

        requested_risk = float(risk_quote)
        requested_capital = float(capital_reserved_quote)
        if not math.isfinite(requested_risk) or requested_risk <= 0:
            raise ValueError("add-on risk reservation must be finite and positive")
        if not math.isfinite(requested_capital) or requested_capital <= 0:
            raise ValueError("add-on capital reservation must be finite and positive")

        # The campaign-level budget is a hard ceiling, not a weighting target.
        # Refuse to arm if the durable budget is missing or the combined open
        # and pending reservations would exceed it.
        budget = float(campaign.tags.get("risk_budget_quote", 0.0) or 0.0)
        if not math.isfinite(budget) or budget <= 0:
            raise ValueError("campaign risk budget is missing or invalid; add-on blocked")
        already_reserved = max(0.0, float(campaign.open_risk_quote)) + max(
            0.0, float(campaign.pending_risk_quote)
        )
        if already_reserved + requested_risk > budget + max(1e-8, budget * 1e-9):
            raise ValueError("add-on would exceed the campaign's remaining risk budget")

        # The book continues the same campaign with later Wise-Men signals.
        # A new signal becomes an add-on, never a second independent campaign.
        campaign.current_signal_id = signal.signal_id
        campaign.current_signal_type = signal.signal_type.value
        campaign.pending_risk_quote = requested_risk
        campaign.capital_reserved_quote = requested_capital
        campaign.next_action = "SUBMIT_ADD_ON"
        campaign.transition(CampaignState.ADD_ON_ARMING, reason=f"{signal.signal_type.value} confirmation")
        campaign.transition(CampaignState.ADD_ON_PENDING, reason="conditional add-on admitted")
        self.db.save_campaign_signal(signal, campaign.campaign_id, state=SignalState.ARMED.value)
        self.db.save_campaign(campaign)
        event = (
            CampaignEventType.WM2_CONFIRMED.value
            if signal.signal_type == SignalType.SUPER_AO
            else CampaignEventType.WM3_CONFIRMED.value
            if signal.signal_type == SignalType.FRACTAL
            else CampaignEventType.ADD_ON_ARMED.value
        )
        self.db.log_campaign_event(
            campaign.campaign_id,
            event,
            signal_id=signal.signal_id,
            reason=signal.reason,
            payload=signal.to_dict(),
        )
        return campaign

    def mark_triggered(self, campaign: TradingCampaign, signal_id: str, order_id: str = "") -> TradingCampaign:
        if campaign.state != CampaignState.ENTRY_PENDING:
            raise ValueError(f"Cannot trigger entry from {campaign.state.value}")
        campaign.transition(CampaignState.ENTRY_TRIGGERED, reason="exchange conditional order triggered")
        campaign.next_action = "VERIFY_FILL"
        self.db.set_campaign_signal_state(signal_id, SignalState.TRIGGERED.value)
        self.db.save_campaign(campaign)
        self.db.log_campaign_event(
            campaign.campaign_id,
            CampaignEventType.ENTRY_TRIGGERED.value,
            signal_id=signal_id,
            order_id=order_id,
        )
        return campaign

    def record_add_on_fill(
        self,
        campaign: TradingCampaign,
        *,
        quantity: float,
        average_entry_price: float,
        fill_order_id: str,
        risk_quote: float,
        fee_quote: float = 0.0,
    ) -> TradingCampaign:
        if campaign.state != CampaignState.POSITION_EXPANDING:
            raise ValueError(f"Cannot record add-on fill from {campaign.state.value}")
        old_qty = float(campaign.position_qty)
        old_entry = float(campaign.average_entry_price)
        fill_qty = float(quantity)
        fill_price = float(average_entry_price)
        fill_risk = float(risk_quote)
        fill_fee = float(fee_quote)
        fill_id = str(fill_order_id or "").strip()

        # Exchange responses and persisted state are external inputs. Reject
        # NaN/Inf explicitly: ordinary <= comparisons do not reject NaN.
        if (
            not math.isfinite(old_qty)
            or not math.isfinite(old_entry)
            or old_qty <= 0
            or old_entry <= 0
            or not math.isfinite(fill_qty)
            or not math.isfinite(fill_price)
            or fill_qty <= 0
            or fill_price <= 0
        ):
            raise ValueError("Invalid add-on fill quantity/price or existing position")
        if not fill_id:
            raise ValueError("add-on fill requires a stable exchange order/fill identifier")
        if not math.isfinite(fill_risk) or fill_risk <= 0:
            raise ValueError("add-on fill risk must be finite and positive")
        if not math.isfinite(fill_fee) or fill_fee < 0:
            raise ValueError("add-on fee must be finite and non-negative")

        # Never silently turn a larger-than-authorized fill into accepted
        # exposure. A fill whose risk exceeds its durable reservation requires
        # reconciliation and operator-safe recovery rather than local booking.
        pending_risk = float(campaign.pending_risk_quote)
        risk_budget = float(campaign.tags.get("risk_budget_quote", 0.0) or 0.0)
        if not math.isfinite(pending_risk) or pending_risk <= 0:
            raise ValueError("add-on fill has no valid pending risk reservation")
        if fill_risk > pending_risk + max(1e-8, pending_risk * 1e-9):
            raise ValueError("actual add-on risk exceeds its pending reservation")
        if not math.isfinite(risk_budget) or risk_budget <= 0:
            raise ValueError("campaign risk budget is missing or invalid at fill time")
        if old_qty and (not math.isfinite(float(campaign.open_risk_quote)) or float(campaign.open_risk_quote) < 0):
            raise ValueError("existing campaign risk is invalid")
        if float(campaign.open_risk_quote) + fill_risk > risk_budget + max(1e-8, risk_budget * 1e-9):
            raise ValueError("actual add-on fill would exceed campaign risk budget")

        new_qty = old_qty + fill_qty
        campaign.average_entry_price = (
            (old_qty * old_entry) + (fill_qty * fill_price)
        ) / max(new_qty, 1e-12)
        campaign.position_qty = new_qty
        campaign.additions += 1
        campaign.tranche_index = min(4, campaign.tranche_index + 1)
        campaign.open_risk_quote += fill_risk
        campaign.pending_risk_quote = 0.0
        campaign.capital_reserved_quote = 0.0
        campaign.tags["last_add_on_fee_quote"] = fill_fee
        campaign.tags["last_add_on_fill_id"] = fill_id
        campaign.tags["last_add_on_exchange_order_id"] = fill_id
        campaign.next_action = "MONITOR_CAMPAIGN"
        campaign.transition(CampaignState.TREND_ACTIVE, reason="add-on filled and position revalued")
        # Clear the durable pending intent in the same campaign save that
        # books the fill. Otherwise a crash after booking but before the caller's
        # cleanup could replay the same child order and double the position.
        for key in (
            "pending_add_on_client_algo_id", "pending_add_on_algo_id",
            "pending_add_on_trigger_price", "pending_add_on_stop_price",
            "pending_add_on_quantity", "pending_add_on_risk_quote",
            "pending_add_on_original_qty", "pending_add_on_original_entry",
            "pending_add_on_direction",
        ):
            campaign.tags.pop(key, None)
        self.db.save_campaign(campaign)
        self.db.log_campaign_event(
            campaign.campaign_id,
            CampaignEventType.ADD_ON_FILLED.value,
            order_id=fill_order_id,
            payload={
                "quantity": fill_qty,
                "average_entry_price": fill_price,
                "risk_quote": fill_risk,
                "fee_quote": fill_fee,
            },
        )
        return campaign

    def record_initial_fill(
        self,
        campaign: TradingCampaign,
        *,
        quantity: float,
        average_entry_price: float,
        initial_stop_price: float,
        fill_order_id: str,
        risk_quote: float,
        fee_quote: float = 0.0,
    ) -> TradingCampaign:
        if campaign.state != CampaignState.ENTRY_TRIGGERED:
            raise ValueError(f"Cannot record initial fill from {campaign.state.value}")
        values = (quantity, average_entry_price, initial_stop_price, risk_quote, fee_quote)
        try:
            normalized = tuple(float(value) for value in values)
        except (TypeError, ValueError) as exc:
            raise ValueError("Invalid initial fill/protection data") from exc
        if not all(math.isfinite(value) for value in normalized):
            raise ValueError("Initial fill/protection values must be finite")
        quantity, average_entry_price, initial_stop_price, risk_quote, fee_quote = normalized
        if quantity <= 0 or average_entry_price <= 0 or initial_stop_price <= 0:
            raise ValueError("Invalid initial fill/protection data")
        if risk_quote <= 0 or fee_quote < 0:
            raise ValueError("Initial fill risk must be positive and fee must be non-negative")
        if not str(fill_order_id or "").strip():
            raise ValueError("Initial fill requires a stable exchange order ID")

        campaign.position_qty = float(quantity)
        campaign.average_entry_price = float(average_entry_price)
        campaign.initial_stop_price = float(initial_stop_price)
        campaign.current_stop_price = float(initial_stop_price)
        campaign.structural_stop_source = "INITIAL_SIGNAL"
        campaign.open_risk_quote = max(0.0, float(risk_quote))
        campaign.pending_risk_quote = 0.0
        campaign.capital_reserved_quote = 0.0
        campaign.tranche_index = 1
        campaign.next_action = "MONITOR_CAMPAIGN"
        campaign.tags["entry_fee_quote"] = float(fee_quote)
        campaign.transition(CampaignState.OPEN_INITIAL, reason="entry fill and hard stop initialized")
        # Persist the entry fill and release the pending-entry reconciliation
        # marker atomically at the campaign-record level.
        campaign.tags.pop("entry_fill_reconciliation_pending", None)
        self.db.save_campaign(campaign)
        self.db.log_campaign_event(
            campaign.campaign_id,
            CampaignEventType.ENTRY_FILLED.value,
            order_id=fill_order_id,
            payload={
                "quantity": quantity,
                "average_entry_price": average_entry_price,
                "risk_quote": risk_quote,
            },
        )
        self.db.log_campaign_event(
            campaign.campaign_id,
            CampaignEventType.PROTECTION_ARMED.value,
            order_id=fill_order_id,
            reason="standalone hard protective stop required",
            payload={"stop_price": initial_stop_price},
        )
        return campaign

    def propose_stop(self, campaign: TradingCampaign, proposed_stop: float, source: str) -> bool:
        if proposed_stop <= 0:
            return False
        if not stop_only_reduces_risk(campaign.side, campaign.current_stop_price, proposed_stop):
            self.db.log_campaign_event(
                campaign.campaign_id,
                CampaignEventType.STOP_LOOSEN_ATTEMPT_BLOCKED.value,
                level="WARNING",
                reason="proposed protective stop would increase risk",
                payload={"current_stop": campaign.current_stop_price, "proposed_stop": proposed_stop},
            )
            return False
        campaign.current_stop_price = float(proposed_stop)
        campaign.structural_stop_source = str(source)
        if campaign.state in {CampaignState.OPEN_INITIAL, CampaignState.TREND_ACTIVE, CampaignState.EXHAUSTION_WATCH}:
            try:
                campaign.transition(CampaignState.TRAILING, reason="structural protective stop advanced")
            except ValueError:
                pass
        campaign.next_action = "MOVE_PROTECTION"
        self.db.save_campaign(campaign)
        self.db.log_campaign_event(
            campaign.campaign_id,
            CampaignEventType.STOP_MOVED.value,
            reason=source,
            payload={"stop_price": proposed_stop},
        )
        return True

    def enter_exhaustion_watch(self, campaign: TradingCampaign, reasons: list[str]) -> TradingCampaign:
        if campaign.state not in {
            CampaignState.OPEN_INITIAL,
            CampaignState.TREND_ACTIVE,
            CampaignState.TRAILING,
        }:
            return campaign
        campaign.transition(CampaignState.EXHAUSTION_WATCH, reason="; ".join(reasons))
        campaign.next_action = "WATCH_EXIT"
        campaign.tags["exhaustion_reasons"] = list(reasons)
        self.db.save_campaign(campaign)
        self.db.log_campaign_event(
            campaign.campaign_id,
            CampaignEventType.EXHAUSTION_WARNING.value,
            reason="; ".join(reasons),
        )
        return campaign

    def request_exit(self, campaign: TradingCampaign, reason: str) -> TradingCampaign:
        if campaign.state == CampaignState.CLOSED:
            return campaign
        if campaign.state != CampaignState.EXIT_SIGNALLED:
            campaign.transition(CampaignState.EXIT_SIGNALLED, reason=reason)
        campaign.exit_reason = str(reason)
        campaign.next_action = "SUBMIT_EXIT"
        self.db.save_campaign(campaign)
        self.db.log_campaign_event(
            campaign.campaign_id,
            CampaignEventType.EXIT_SIGNAL.value,
            reason=reason,
        )
        return campaign

    def mark_reconcile_required(self, campaign: TradingCampaign, reason: str) -> TradingCampaign:
        campaign.mark_reconcile_required(reason)
        campaign.next_action = "RECONCILE"
        self.db.save_campaign(campaign)
        self.db.log_campaign_event(
            campaign.campaign_id,
            CampaignEventType.RECONCILE_REQUIRED.value,
            level="ERROR",
            reason=reason,
        )
        return campaign

    def diagnostic_snapshot(self, campaign_id: str) -> dict[str, Any]:
        row = self.db.get_campaign(campaign_id)
        if row is None:
            return {"found": False, "campaign_id": campaign_id}
        signals = self.db.active_campaign_signals(campaign_id)
        return {
            "found": True,
            "campaign": row,
            "active_signals": signals,
            "portfolio_reserved_risk_quote": self.portfolio_reserved_risk_quote(),
            "portfolio_reserved_capital_quote": self.portfolio_reserved_capital_quote(),
        }
