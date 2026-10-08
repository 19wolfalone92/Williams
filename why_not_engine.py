"""Deterministic explanation engine for Williams decisions.

WhyNotEngine explains exactly which pre-price requirement prevented an
armable hypothesis.  It does not rank signals and it does not convert the
diagnostic proof score into a threshold.
"""
from __future__ import annotations

from typing import Iterable

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
        pending_actionable: bool = True,
    ) -> tuple[str, ...]:
        reasons: list[str] = []
        if not pending_actionable:
            reasons.append("pending_signal_expired_or_invalid")

        for field in cls.PRE_PRICE_ORDER:
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
        pending_actionable: bool = True,
    ) -> tuple[str, ...]:
        reasons = list(cls.explain_pre_price(
            proof,
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
        pending_actionable: bool = True,
    ) -> bool:
        return not cls.explain_pre_price(
            proof,
            pending_actionable=pending_actionable,
        )
