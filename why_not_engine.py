"""Deterministic explanation engine for Williams decisions.

WhyNotEngine explains exactly which pre-price requirement prevented an
armable hypothesis.  It does not rank signals and it does not convert the
diagnostic proof score into a threshold.
"""
from __future__ import annotations

from domain.contracts import ProofVector


_LABELS = {
    "context_pass": "context",
    "behavior_pass": "behavior",
    "structure_pass": "structure",
    "location_pass": "location",
    "angulation_pass": "angulation",
    "momentum_pass": "momentum",
    "invalidation_present": "invalidation",
}


class WhyNotEngine:
    PRE_PRICE_ORDER = tuple(_LABELS)

    @classmethod
    def explain_pre_price(
        cls,
        proof: ProofVector,
        *,
        wise_man_stage: int = 1,
        pending_actionable: bool = True,
    ) -> tuple[str, ...]:
        reasons: list[str] = []
        if not pending_actionable:
            reasons.append("pending_signal_expired_or_invalid")

        required = {
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
        }.get(int(wise_man_stage), cls.PRE_PRICE_ORDER)

        for field in required:
            if not bool(getattr(proof, field)):
                reasons.append(f"{_LABELS[field]}_proof_missing")

        # price_proof_pass is intentionally excluded here: before the exchange
        # trigger fires, price proof is expected to remain false.
        return tuple(reasons)

    @classmethod
    def explain_full(
        cls,
        proof: ProofVector,
        *,
        wise_man_stage: int = 1,
        pending_actionable: bool = True,
    ) -> tuple[str, ...]:
        reasons = list(cls.explain_pre_price(
            proof,
            wise_man_stage=wise_man_stage,
            pending_actionable=pending_actionable,
        ))
        if not proof.price_proof_pass:
            reasons.append("price_proof_not_triggered")
        return tuple(dict.fromkeys(reasons))

    @classmethod
    def armable(
        cls,
        proof: ProofVector,
        *,
        wise_man_stage: int = 1,
        pending_actionable: bool = True,
    ) -> bool:
        return not cls.explain_pre_price(
            proof,
            wise_man_stage=wise_man_stage,
            pending_actionable=pending_actionable,
        )
