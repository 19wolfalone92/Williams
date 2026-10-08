"""Williams campaign coordinator.

This layer is intentionally deterministic and exchange-agnostic. It turns
strategy observations into a durable campaign lifecycle and risk reservations.
The Binance adapter is responsible only for expressing an admitted decision as
an exchange order and verifying the resulting state.
"""

from __future__ import annotations

from dataclasses import asdict
from typing import Any, Iterable
import json
import time
import uuid

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
    def choose_initial_signal(signals: Iterable[SignalSpec]) -> SignalSpec | None:
        candidates = [
            s for s in signals
            if s.role == SignalRole.ENTRY
            and s.side == "BUY"
            and s.trigger_price > 0
        ]
        if not candidates:
            return None
        # Canonical campaign policy: prefer the classic Wise Men order
        # (WM1 -> WM2 -> WM3) when several signals are simultaneously valid.
        # A Fractal may still start a campaign when it is the only valid
        # opportunity, as Williams explicitly allows.
        priority = {
            SignalType.REVERSAL: 0,   # WM1
            SignalType.SUPER_AO: 1,   # WM2
            SignalType.FRACTAL: 2,    # WM3
        }
        return max(
            candidates,
            key=lambda s: (
                -priority.get(s.signal_type, 99),
                s.signal_bar_time_ms,
                s.created_at_ms,
            ),
        )

    @staticmethod
    def should_replace_pending(old: SignalSpec, new: SignalSpec, *, min_ticks: int = 1, tick_size: float = 0.0) -> bool:
        if old.signal_id == new.signal_id:
            return False
        if old.symbol != new.symbol or old.side != new.side:
            return True
        distance = abs(float(new.trigger_price) - float(old.trigger_price))
        threshold = max(0.0, float(tick_size)) * max(1, int(min_ticks))
        return (
            new.signal_bar_time_ms > old.signal_bar_time_ms
            and distance >= threshold
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

    def _position_risk_quote(
        self,
        *,
        quantity: float,
        average_entry_price: float,
        stop_price: float,
        fee_quote: float = 0.0,
    ) -> float:
        """Conservatively mark loss to the active structural stop.

        This is deliberately recomputed from the actual position rather than
        trusting the originally requested tranche risk.  Moving a LONG stop
        upward therefore releases risk capacity; adding size consumes only the
        risk represented by the resulting aggregate position.
        """
        qty = max(0.0, float(quantity))
        entry = max(0.0, float(average_entry_price))
        stop = max(0.0, float(stop_price))
        if qty <= 0.0 or entry <= 0.0 or stop <= 0.0:
            return 0.0
        if str(getattr(self, "side", "LONG")).upper() == "SHORT":
            gross = max(0.0, stop - entry) * qty
        else:
            gross = max(0.0, entry - stop) * qty
        fee_per_side = max(0.0, float(__import__("os").getenv("FEE_BUFFER_PER_SIDE_PCT", "0.001")))
        slippage = max(0.0, float(__import__("os").getenv("RISK_SLIPPAGE_BUFFER_PCT", "0.0015")))
        notional = entry * qty
        return gross + max(0.0, float(fee_quote)) + notional * (2.0 * fee_per_side + slippage)

    @staticmethod
    def _campaign_position_risk(
        campaign: TradingCampaign,
        *,
        fee_quote: float = 0.0,
    ) -> float:
        qty = max(0.0, float(campaign.position_qty or 0.0))
        entry = max(0.0, float(campaign.average_entry_price or 0.0))
        stop = max(0.0, float(campaign.current_stop_price or 0.0))
        if qty <= 0.0 or entry <= 0.0 or stop <= 0.0:
            return 0.0
        if campaign.side.upper() == "SHORT":
            gross = max(0.0, stop - entry) * qty
        else:
            gross = max(0.0, entry - stop) * qty
        import os
        fee_per_side = max(0.0, float(os.getenv("FEE_BUFFER_PER_SIDE_PCT", "0.001")))
        slippage = max(0.0, float(os.getenv("RISK_SLIPPAGE_BUFFER_PCT", "0.0015")))
        notional = entry * qty
        return gross + max(0.0, float(fee_quote)) + notional * (2.0 * fee_per_side + slippage)

    def refresh_open_risk(self, campaign: TradingCampaign, *, fee_quote: float = 0.0) -> float:
        campaign.open_risk_quote = self._campaign_position_risk(
            campaign,
            fee_quote=fee_quote,
        )
        return campaign.open_risk_quote

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
        if campaign.additions >= 4 or campaign.tranche_index >= 5:
            raise ValueError("campaign has reached the five-tranche reverse-pyramid limit")
        if signal.side != campaign.side:
            raise ValueError("add-on side does not match campaign")
        if signal.signal_bar_time_ms <= 0:
            raise ValueError("add-on signal has no valid signal time")
        if campaign.state not in {
            CampaignState.OPEN_INITIAL,
            CampaignState.TREND_ACTIVE,
            CampaignState.TRAILING,
        }:
            raise ValueError(f"Cannot arm add-on from {campaign.state.value}")

        # One Super AO add-on and up to three Fractal add-ons are the
        # canonical five-tranche campaign structure. Reversal bars do not
        # create additional tranches.
        signal_type = signal.signal_type
        if signal_type == SignalType.REVERSAL:
            raise ValueError("WM1 reversal is an entry signal, not a campaign add-on")
        row = self.db.conn.execute(
            "SELECT COUNT(*) AS n FROM campaign_signals "
            "WHERE campaign_id=? AND signal_type=? "
            "AND state IN ('DETECTED','ARMED','TRIGGERED','FILLED')",
            (campaign.campaign_id, signal_type.value),
        ).fetchone()
        count = int(row["n"] or 0)
        if signal_type == SignalType.SUPER_AO and count >= 1:
            raise ValueError("campaign already consumed its WM2 Super AO add-on")
        if signal_type == SignalType.FRACTAL and count >= 3:
            raise ValueError("campaign reached the three Fractal add-on limit")

        # The book continues the same campaign with later Wise-Men signals.
        # A new signal becomes an add-on, never a second independent campaign.
        campaign.current_signal_id = signal.signal_id
        campaign.current_signal_type = signal.signal_type.value
        campaign.pending_risk_quote = max(0.0, float(risk_quote))
        campaign.capital_reserved_quote = max(0.0, float(capital_reserved_quote))
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
        if campaign.state == CampaignState.ADD_ON_PENDING:
            campaign.transition(
                CampaignState.POSITION_EXPANDING,
                reason="exchange confirmed conditional add-on fill",
            )
        if campaign.state != CampaignState.POSITION_EXPANDING:
            raise ValueError(f"Cannot record add-on fill from {campaign.state.value}")
        old_qty = float(campaign.position_qty)
        old_entry = float(campaign.average_entry_price)
        if quantity <= 0 or average_entry_price <= 0:
            raise ValueError("Invalid add-on fill")
        new_qty = old_qty + float(quantity)
        campaign.average_entry_price = (
            (old_qty * old_entry) + (float(quantity) * float(average_entry_price))
        ) / max(new_qty, 1e-12)
        campaign.position_qty = new_qty
        campaign.additions += 1
        campaign.tranche_index = min(5, campaign.tranche_index + 1)
        campaign.pending_risk_quote = 0.0
        campaign.capital_reserved_quote = 0.0
        campaign.tags["last_add_on_fee_quote"] = float(fee_quote)
        self.refresh_open_risk(campaign, fee_quote=fee_quote)
        campaign.next_action = "MONITOR_CAMPAIGN"
        campaign.transition(CampaignState.TREND_ACTIVE, reason="add-on filled and position revalued")
        self.db.save_campaign(campaign)
        self.db.log_campaign_event(
            campaign.campaign_id,
            CampaignEventType.ADD_ON_FILLED.value,
            order_id=fill_order_id,
            payload={
                "quantity": quantity,
                "average_entry_price": average_entry_price,
                "risk_quote": risk_quote,
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
        if quantity <= 0 or average_entry_price <= 0 or initial_stop_price <= 0:
            raise ValueError("Invalid initial fill/protection data")

        campaign.position_qty = float(quantity)
        campaign.average_entry_price = float(average_entry_price)
        campaign.initial_stop_price = float(initial_stop_price)
        campaign.current_stop_price = float(initial_stop_price)
        campaign.structural_stop_source = "INITIAL_SIGNAL"
        campaign.pending_risk_quote = 0.0
        campaign.capital_reserved_quote = 0.0
        campaign.tranche_index = 1
        campaign.next_action = "MONITOR_CAMPAIGN"
        campaign.tags["entry_fee_quote"] = float(fee_quote)
        self.refresh_open_risk(campaign, fee_quote=fee_quote)
        campaign.transition(CampaignState.OPEN_INITIAL, reason="entry fully filled and hard stop initialized")
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
        self.refresh_open_risk(campaign, fee_quote=float(campaign.tags.get("entry_fee_quote", 0.0) or 0.0))
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
