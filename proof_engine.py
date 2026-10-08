"""Stateful Williams proof evaluation.

This layer formalizes the distinction between an armable hypothesis and a
fully price-proven trade.  It never uses a numeric score as a trade gate.
"""
from __future__ import annotations

from dataclasses import dataclass
from campaign_model import SignalSpec, SignalType
from domain.contracts import ProofVector


# The required evidence differs by Wise-Man stage. WM1 is intentionally the
# early hypothesis signal and must not be forced to have WM2 momentum proof.
# WM2/WM3 require momentum/continuation evidence before an add-on is armed.
PRE_PRICE_FIELDS_BY_STAGE = {
    1: (
        "context_pass",
        "behavior_pass",
        "structure_pass",
        "location_pass",
        "angulation_pass",
        "invalidation_present",
    ),
    2: (
        "context_pass",
        "behavior_pass",
        "structure_pass",
        "location_pass",
        "momentum_pass",
        "invalidation_present",
    ),
    3: (
        "context_pass",
        "behavior_pass",
        "structure_pass",
        "location_pass",
        "momentum_pass",
        "invalidation_present",
    ),
}


@dataclass(frozen=True)
class ProofEvaluation:
    proof_vector: ProofVector
    wise_man_stage: int

    @property
    def required_pre_price_fields(self) -> tuple[str, ...]:
        return PRE_PRICE_FIELDS_BY_STAGE.get(
            int(self.wise_man_stage),
            PRE_PRICE_FIELDS_BY_STAGE[1],
        )

    @property
    def armable(self) -> bool:
        return all(
            bool(getattr(self.proof_vector, name))
            for name in self.required_pre_price_fields
        )

    @property
    def hypothesis_proven(self) -> bool:
        return self.proof_vector.is_fully_proven

    @property
    def missing_pre_price_proof(self) -> tuple[str, ...]:
        return tuple(
            name
            for name in self.required_pre_price_fields
            if not bool(getattr(self.proof_vector, name))
        )


class WilliamsProofEngine:
    """Builds a canonical ProofVector from an existing SignalSpec."""

    @staticmethod
    def evaluate(signal: SignalSpec) -> ProofEvaluation:
        context_pass = bool(
            signal.htf_confirmed or signal.alligator_bullish
        )

        behavior_pass = bool(
            signal.behavior_confirmed
        )

        structure_pass = bool(
            signal.structure_confirmed
            and int(signal.source_candle_index) >= 0
        )

        location_pass = bool(
            float(signal.teeth_at_detection or 0.0) > 0.0
            and float(signal.trigger_price or 0.0) >
            float(signal.teeth_at_detection or 0.0)
        )

        angulation_pass = bool(signal.angulation_valid)

        momentum_pass = bool(signal.momentum_confirmed)

        invalidation_present = bool(
            float(
                signal.invalidation_price
                or signal.protective_reference
                or 0.0
            ) > 0.0
        )

        return ProofEvaluation(
            ProofVector(
                context_pass=context_pass,
                behavior_pass=behavior_pass,
                structure_pass=structure_pass,
                location_pass=location_pass,
                angulation_pass=angulation_pass,
                momentum_pass=momentum_pass,
                price_proof_pass=False,
                invalidation_present=invalidation_present,
            ),
            wise_man_stage={
                SignalType.REVERSAL: 1,
                SignalType.SUPER_AO: 2,
                SignalType.FRACTAL: 3,
            }[signal.signal_type],
        )


    @staticmethod
    def with_price_proof(
        evaluation: ProofEvaluation,
        *,
        price_proof_pass: bool,
    ) -> ProofEvaluation:
        p = evaluation.proof_vector
        return ProofEvaluation(
            ProofVector(
                context_pass=p.context_pass,
                behavior_pass=p.behavior_pass,
                structure_pass=p.structure_pass,
                location_pass=p.location_pass,
                angulation_pass=p.angulation_pass,
                momentum_pass=p.momentum_pass,
                price_proof_pass=bool(price_proof_pass),
                invalidation_present=p.invalidation_present,
            ),
            wise_man_stage=evaluation.wise_man_stage,
        )


def price_proof(decision, *, market_price: float) -> ProofEvaluation:
    """Return a new proof evaluation after price has crossed the hypothesis trigger."""
    if decision is None:
        raise ValueError("decision is required")
    price = float(market_price)
    trigger = float(decision.trigger_price)
    if decision.direction.value == "LONG":
        crossed = price >= trigger
    elif decision.direction.value == "SHORT":
        crossed = price <= trigger
    else:
        crossed = False
    return WilliamsProofEngine.with_price_proof(
        ProofEvaluation(
            decision.proof_vector,
            int(decision.wise_man_stage),
        ),
        price_proof_pass=crossed,
    )
