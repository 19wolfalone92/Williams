"""AI-shadow contract for Williams.

The shadow model has no Binance credentials and no submit/cancel capability.
Its output is an AIOrderIntent consumed only by research/UI/journal layers.
"""
from __future__ import annotations

import time
import uuid
from dataclasses import asdict, dataclass
from typing import Any, Mapping

from feature_store import MarketFeatureVector, RegimeBaseline


@dataclass(frozen=True)
class AIOrderIntent:
    intent_id: str
    timestamp_ms: int
    engine_version: str
    symbol: str
    interval: str
    regime: str
    direction: str
    confidence: float
    suggested_size_fraction: float
    invalidation: float
    target: float
    reason: str
    feature_attribution: Mapping[str, float]
    shadow_only: bool = True

    def __post_init__(self) -> None:
        if not self.shadow_only:
            raise ValueError("AIOrderIntent must remain shadow_only")
        if not 0.0 <= float(self.confidence) <= 1.0:
            raise ValueError("confidence must be in [0,1]")
        if not 0.0 <= float(self.suggested_size_fraction) <= 0.01:
            raise ValueError("shadow size fraction must be <= 1%")

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


class ShadowDecisionEngine:
    """A deterministic baseline placeholder for future ML models."""

    def __init__(self, engine_version: str = "shadow-baseline-1"):
        self.engine_version = engine_version
        self.regime = RegimeBaseline()

    def evaluate(
        self,
        vector: MarketFeatureVector,
        *,
        williams_signal: bool,
        htf_confirmed: bool,
    ) -> AIOrderIntent:
        direction = "HOLD"
        confidence = 0.50
        reason = "shadow baseline"
        score = 0.0

        if williams_signal and htf_confirmed:
            score += 0.30
        if vector.regime == RegimeBaseline.TRENDING_EXPANSION:
            score += 0.25
        elif vector.regime == RegimeBaseline.HIGH_NOISE_WASH:
            score -= 0.20
        if vector.obi > 0.10:
            score += 0.10
        elif vector.obi < -0.10:
            score -= 0.10
        if vector.wave3_probability > 0.55:
            score += 0.15
            reason = "shadow baseline: continuation/W3 context"
        if vector.wave5_probability > 0.55 or vector.wave_exhaustion_risk >= 80.0:
            score -= 0.25
            reason = "shadow baseline: Wave-5 exhaustion risk"

        confidence = max(0.0, min(0.99, 0.50 + score))
        if williams_signal and htf_confirmed and confidence >= 0.65 and vector.regime != RegimeBaseline.HIGH_NOISE_WASH:
            direction = "LONG"
        else:
            direction = "HOLD"

        attribution = {
            "williams_signal": 0.30 if williams_signal else 0.0,
            "htf_confirmed": 0.10 if htf_confirmed else 0.0,
            "regime": 0.25 if vector.regime == RegimeBaseline.TRENDING_EXPANSION else -0.20 if vector.regime == RegimeBaseline.HIGH_NOISE_WASH else 0.0,
            "obi": max(-0.10, min(0.10, vector.obi)),
            "wave3_probability": 0.15 if vector.wave3_probability > 0.55 else 0.0,
            "wave5_exhaustion": -0.25 if vector.wave5_probability > 0.55 or vector.wave_exhaustion_risk >= 80.0 else 0.0,
        }
        return AIOrderIntent(
            intent_id=uuid.uuid4().hex,
            timestamp_ms=int(time.time() * 1000),
            engine_version=self.engine_version,
            symbol=vector.symbol,
            interval=vector.interval,
            regime=vector.regime,
            direction=direction,
            confidence=confidence,
            suggested_size_fraction=min(0.01, max(0.0, 0.005 * confidence)),
            invalidation=0.0,
            target=0.0,
            reason=reason,
            feature_attribution=attribution,
        )


def journal_shadow_decision(store, intent: AIOrderIntent, vector: MarketFeatureVector) -> None:
    payload = intent.to_dict()
    payload["feature_timestamp_ms"] = vector.timestamp_ms
    payload["features"] = vector.to_dict()
    store.save_shadow(payload)


__all__ = ["AIOrderIntent", "ShadowDecisionEngine", "journal_shadow_decision"]
