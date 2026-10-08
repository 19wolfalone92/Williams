"""Stateful Williams proof evaluation.

This layer formalizes the distinction between an armable hypothesis and a
fully price-proven trade.  It never uses a numeric score as a trade gate.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable

from campaign_model import SignalSpec, SignalType
from domain.contracts import ProofVector


PRE_PRICE_FIELDS = (
    "context_pass",
    "behavior_pass",
    "structure_pass",
    "location_pass",
    "angulation_pass",
    "momentum_pass",
    "invalidation_present",
)


@dataclass(frozen=True)
class ProofEvaluation:
    proof_vector: ProofVector

    @property
    def armable(self) -> bool:
        return all(bool(getattr(self.proof_vector, name)) for name in PRE_PRICE_FIELDS)

    @property
    def hypothesis_proven(self) -> bool:
        return self.proof_vector.is_fully_proven

    @property
    def missing_pre_price_proof(self) -> tuple[str, ...]:
        return tuple(
            name
            for name in PRE_PRICE_FIELDS
            if not bool(getattr(self.proof_vector, name))
        )


class WilliamsProofEngine:
    """Builds a canonical ProofVector from an existing SignalSpec."""

    @staticmethod
    def evaluate(signal: SignalSpec) -> ProofEvaluation:
        context_pass = bool(signal.htf_confirmed or signal.alligator_bullish)
        behavior_pass = bool(signal.reason.strip()) or bool(signal.signal_type)
        structure_pass = int(signal.source_candle_index) >= 0
        location_pass = float(signal.teeth_at_detection or 0.0) > 0.0

        if signal.signal_type is SignalType.REVERSAL:
            angulation_pass = float(signal.angulation_score or 0.0) > 0.0
        else:
            # WM2/WM3 are continuation/structural confirmations in an active
            # Williams structure; Alligator wakefulness is a valid production
            # representation of the established trend location.
            angulation_pass = bool(
                signal.alligator_awake or
                float(signal.angulation_score or 0.0) > 0.0
            )

        momentum_pass = bool(
            signal.signal_type is SignalType.SUPER_AO
            or float(signal.wave_confidence or 0.0) > 0.0
        )

        invalidation_present = bool(
            float(signal.invalidation_price or signal.protective_reference or 0.0) > 0.0
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
            )
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
            )
        )
