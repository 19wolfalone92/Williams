"""Deterministic Williams campaign decision engine.

Strategy observations are deliberately separated from exchange mutations.
This module produces decisions only; an execution adapter must validate risk,
freshness and Binance filters before submitting an OrderIntent.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Optional


class CampaignState(str, Enum):
    FLAT = "FLAT"
    SIGNAL_DETECTED = "SIGNAL_DETECTED"
    ENTRY_PENDING = "ENTRY_PENDING"
    OPEN_INITIAL = "OPEN_INITIAL"
    ADD_ON_PENDING = "ADD_ON_PENDING"
    TREND_ACTIVE = "TREND_ACTIVE"
    TRAILING = "TRAILING"
    EXIT_SIGNALLED = "EXIT_SIGNALLED"
    EXIT_PENDING = "EXIT_PENDING"
    CLOSED = "CLOSED"
    RECONCILE_REQUIRED = "RECONCILE_REQUIRED"


class SignalType(str, Enum):
    REVERSAL = "REVERSAL"
    SUPER_AO = "SUPER_AO"
    FRACTAL = "FRACTAL"
    ADD_ON = "ADD_ON"


class DecisionType(str, Enum):
    WAIT = "WAIT"
    ARM_ENTRY = "ARM_ENTRY"
    REPLACE_ENTRY = "REPLACE_ENTRY"
    INVALIDATE_ENTRY = "INVALIDATE_ENTRY"
    ARM_ADD_ON = "ARM_ADD_ON"
    MOVE_STOP = "MOVE_STOP"
    EXIT = "EXIT"
    BLOCK = "BLOCK"


@dataclass(frozen=True)
class PendingSignal:
    signal_id: str
    campaign_id: str
    symbol: str
    side: str
    signal_type: SignalType
    timeframe: str
    trigger_price: float
    initial_stop: float
    invalidation_price: float
    context_versions: dict[str, int] = field(default_factory=dict)
    risk_reserved_pct: float = 0.0
    state: str = "ARMED"


@dataclass
class TradingCampaign:
    campaign_id: str
    symbol: str
    side: str = "LONG"
    timeframe: str = "5m"
    state: CampaignState = CampaignState.FLAT
    signals: list[PendingSignal] = field(default_factory=list)
    current_stop: float = 0.0
    initial_stop: float = 0.0
    avg_entry: float = 0.0
    quantity: float = 0.0
    reserved_risk_pct: float = 0.0
    max_risk_pct: float = 0.005
    additions: int = 0
    exhaustion: bool = False

    def can_add_risk(self, incremental_risk_pct: float) -> bool:
        return (
            incremental_risk_pct >= 0.0
            and self.reserved_risk_pct + incremental_risk_pct
            <= self.max_risk_pct + 1e-12
        )

    def tighten_stop(self, proposed: float) -> bool:
        """Move protection only toward lower risk."""
        if proposed <= 0.0:
            return False
        if self.side == "LONG":
            if self.current_stop > 0.0 and proposed < self.current_stop:
                return False
        else:
            if self.current_stop > 0.0 and proposed > self.current_stop:
                return False
        changed = abs(proposed - self.current_stop) > 1e-12
        if changed:
            self.current_stop = proposed
        return changed


@dataclass(frozen=True)
class CampaignDecision:
    decision: DecisionType
    reason_code: str
    signal: Optional[PendingSignal] = None
    proposed_stop: float = 0.0


class CampaignEngine:
    """Pure policy layer for Williams campaign sequencing."""

    def detect_entry(
        self,
        campaign: TradingCampaign,
        signal: PendingSignal,
        *,
        current_price: float,
        aggregate_risk_pct: float,
        max_aggregate_risk_pct: float,
        context_fresh: bool,
    ) -> CampaignDecision:
        if campaign.state not in {
            CampaignState.FLAT,
            CampaignState.SIGNAL_DETECTED,
        }:
            return CampaignDecision(
                DecisionType.BLOCK, "campaign_not_flat"
            )
        if not context_fresh:
            return CampaignDecision(DecisionType.BLOCK, "context_stale")
        if signal.trigger_price <= current_price:
            return CampaignDecision(
                DecisionType.BLOCK, "trigger_already_broken"
            )
        if signal.initial_stop <= 0.0 or signal.initial_stop >= signal.trigger_price:
            return CampaignDecision(
                DecisionType.BLOCK, "invalid_initial_stop"
            )
        if aggregate_risk_pct + signal.risk_reserved_pct > max_aggregate_risk_pct + 1e-12:
            return CampaignDecision(
                DecisionType.BLOCK, "aggregate_risk_exhausted"
            )
        if not campaign.can_add_risk(signal.risk_reserved_pct):
            return CampaignDecision(
                DecisionType.BLOCK, "campaign_risk_exhausted"
            )

        campaign.state = CampaignState.ENTRY_PENDING
        campaign.signals.append(signal)
        campaign.initial_stop = signal.initial_stop
        campaign.current_stop = signal.initial_stop
        campaign.reserved_risk_pct += signal.risk_reserved_pct
        return CampaignDecision(DecisionType.ARM_ENTRY, "valid_pending_trigger", signal)

    def replace_entry(
        self,
        campaign: TradingCampaign,
        old: PendingSignal,
        new: PendingSignal,
        *,
        current_price: float,
        min_trigger_move_ticks: float,
    ) -> CampaignDecision:
        if campaign.state != CampaignState.ENTRY_PENDING:
            return CampaignDecision(DecisionType.BLOCK, "entry_not_pending")
        if new.trigger_price <= current_price:
            return CampaignDecision(DecisionType.BLOCK, "replacement_trigger_broken")
        if abs(new.trigger_price - old.trigger_price) < min_trigger_move_ticks:
            return CampaignDecision(DecisionType.WAIT, "replacement_below_threshold")
        if new.initial_stop <= 0.0 or new.initial_stop >= new.trigger_price:
            return CampaignDecision(DecisionType.BLOCK, "invalid_replacement_stop")
        campaign.signals.append(new)
        return CampaignDecision(DecisionType.REPLACE_ENTRY, "new_signal_supersedes_old", new)

    def on_fill(self, campaign: TradingCampaign, quantity: float, avg_entry: float) -> CampaignDecision:
        if campaign.state not in {CampaignState.ENTRY_PENDING, CampaignState.ADD_ON_PENDING}:
            return CampaignDecision(DecisionType.BLOCK, "fill_in_wrong_state")
        if quantity <= 0.0 or avg_entry <= 0.0:
            return CampaignDecision(DecisionType.BLOCK, "invalid_fill")
        campaign.quantity += quantity
        campaign.avg_entry = avg_entry if campaign.quantity == quantity else (
            (campaign.avg_entry * (campaign.quantity - quantity) + avg_entry * quantity)
            / campaign.quantity
        )
        campaign.state = CampaignState.OPEN_INITIAL if campaign.additions == 0 else CampaignState.TREND_ACTIVE
        return CampaignDecision(DecisionType.WAIT, "fill_adopted")

    def propose_stop(self, campaign: TradingCampaign, proposed: float) -> CampaignDecision:
        if campaign.state in {CampaignState.FLAT, CampaignState.CLOSED, CampaignState.RECONCILE_REQUIRED}:
            return CampaignDecision(DecisionType.BLOCK, "campaign_not_active")
        if not campaign.tighten_stop(proposed):
            return CampaignDecision(DecisionType.WAIT, "stop_not_tighter")
        campaign.state = CampaignState.TRAILING
        return CampaignDecision(DecisionType.MOVE_STOP, "structural_stop_tightened", proposed_stop=proposed)

    def exhaustion_exit(self, campaign: TradingCampaign, *, exhaustion: bool, terminal_fractal: bool, divergence: bool) -> CampaignDecision:
        if campaign.quantity <= 0.0:
            return CampaignDecision(DecisionType.BLOCK, "no_position")
        campaign.exhaustion = bool(exhaustion)
        if exhaustion and terminal_fractal and divergence:
            campaign.state = CampaignState.EXIT_SIGNALLED
            return CampaignDecision(DecisionType.EXIT, "wave5_exhaustion_confirmed")
        return CampaignDecision(DecisionType.WAIT, "exhaustion_not_confirmed")
