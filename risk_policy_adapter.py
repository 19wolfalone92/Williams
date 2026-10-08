"""Canonical risk-policy adapter for Digital Bill Williams.

This module consumes an immutable WilliamsDecision and produces an immutable
RiskDecision. It never mutates the Williams decision and never submits orders.

RiskPolicy is intentionally a production policy, not a Williams source rule.
"""
from __future__ import annotations

from dataclasses import dataclass
from domain.contracts import RiskDecision, SignalDirection, WilliamsDecision


@dataclass(frozen=True, slots=True)
class RiskPolicy:
    campaign_risk_fraction: float = 0.005
    max_position_fraction: float = 0.25
    max_allowed_slippage: float = 0.0015
    fee_buffer_per_side: float = 0.001
    minimum_notional: float = 0.0

    def __post_init__(self) -> None:
        if not 0.0 <= float(self.campaign_risk_fraction) <= 1.0:
            raise ValueError("campaign_risk_fraction must be in [0,1]")
        if not 0.0 < float(self.max_position_fraction) <= 1.0:
            raise ValueError("max_position_fraction must be in (0,1]")
        if float(self.max_allowed_slippage) < 0:
            raise ValueError("max_allowed_slippage must be >= 0")
        if float(self.fee_buffer_per_side) < 0:
            raise ValueError("fee_buffer_per_side must be >= 0")
        if float(self.minimum_notional) < 0:
            raise ValueError("minimum_notional must be >= 0")


class CanonicalRiskEngine:
    """Translate a Williams hypothesis into a bounded risk admission."""

    def __init__(self, policy: RiskPolicy | None = None) -> None:
        self.policy = policy or RiskPolicy()

    @staticmethod
    def _stop_distance(
        decision: WilliamsDecision,
    ) -> float:
        entry = float(decision.trigger_price)
        invalidation = float(decision.invalidation_price)
        if decision.direction is SignalDirection.LONG:
            return entry - invalidation
        if decision.direction is SignalDirection.SHORT:
            return invalidation - entry
        return 0.0

    def approve(
        self,
        decision: WilliamsDecision,
        *,
        equity_quote: float,
        remaining_campaign_risk_quote: float | None = None,
    ) -> RiskDecision:
        equity = float(equity_quote)
        if equity <= 0:
            return RiskDecision(
                decision, False, 0.0, 0.0,
                self.policy.max_allowed_slippage,
                "equity must be > 0",
            )

        if decision.direction is SignalDirection.FLAT:
            return RiskDecision(
                decision, False, 0.0, 0.0,
                self.policy.max_allowed_slippage,
                "FLAT direction cannot create execution intent",
            )

        distance = self._stop_distance(decision)
        if distance <= 0:
            return RiskDecision(
                decision, False, 0.0, 0.0,
                self.policy.max_allowed_slippage,
                "invalidation must be beyond trigger in trade direction",
            )

        allocation_quote = equity * float(self.policy.campaign_risk_fraction)
        if remaining_campaign_risk_quote is not None:
            allocation_quote = min(
                allocation_quote,
                max(0.0, float(remaining_campaign_risk_quote)),
            )
        if allocation_quote <= 0:
            return RiskDecision(
                decision, False, 0.0, 0.0,
                self.policy.max_allowed_slippage,
                "campaign risk budget exhausted",
            )

        trigger = float(decision.trigger_price)
        cost_buffer = (
            2.0 * float(self.policy.fee_buffer_per_side)
            + float(self.policy.max_allowed_slippage)
        )
        stop_fraction = distance / trigger
        effective_loss_fraction = stop_fraction + cost_buffer
        if effective_loss_fraction <= 0:
            return RiskDecision(
                decision, False, 0.0, 0.0,
                self.policy.max_allowed_slippage,
                "effective loss fraction is invalid",
            )

        risk_based_notional = allocation_quote / effective_loss_fraction
        max_capital = equity * float(self.policy.max_position_fraction)
        notional = min(risk_based_notional, max_capital)
        quantity = notional / trigger if trigger > 0 else 0.0

        if notional <= 0 or quantity <= 0:
            return RiskDecision(
                decision, False, 0.0, 0.0,
                self.policy.max_allowed_slippage,
                "calculated position size is zero",
            )

        if (
            float(self.policy.minimum_notional) > 0
            and notional < float(self.policy.minimum_notional)
        ):
            return RiskDecision(
                decision, False, 0.0, 0.0,
                self.policy.max_allowed_slippage,
                "calculated notional is below minimum",
            )

        return RiskDecision(
            decision,
            True,
            allocation_quote / equity,
            quantity,
            self.policy.max_allowed_slippage,
            None,
        )


def risk_decision_is_pure(decision: WilliamsDecision, result: RiskDecision) -> bool:
    """Identity guard used by tests/audits: Risk cannot replace the source decision."""
    return result.williams_decision is decision
