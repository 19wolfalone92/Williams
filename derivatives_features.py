"""Optional derivatives/flow feature normalization.

This module normalizes externally supplied funding/OI/positioning/liquidation
data. It intentionally does not call an exchange API.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Mapping, Any


def _f(value: Any, default: float = 0.0) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


@dataclass(frozen=True)
class DerivativesFeatureSet:
    funding_rate: float = 0.0
    funding_rate_delta: float = 0.0
    open_interest: float = 0.0
    open_interest_delta: float = 0.0
    long_short_ratio: float = 0.0
    liquidation_notional: float = 0.0
    liquidation_cluster_proximity: float = 0.0

    def to_dict(self) -> dict:
        return asdict(self)


def build_derivatives_features(
    current: Mapping[str, Any] | None,
    previous: Mapping[str, Any] | None = None,
) -> DerivativesFeatureSet:
    current = current or {}
    previous = previous or {}
    funding = _f(current.get("funding_rate"))
    prev_funding = _f(previous.get("funding_rate"))
    oi = _f(current.get("open_interest"))
    prev_oi = _f(previous.get("open_interest"))
    ratio = max(0.0, _f(current.get("long_short_ratio")))
    liq = max(0.0, _f(current.get("liquidation_notional")))
    proximity = max(0.0, min(1.0, _f(current.get("liquidation_cluster_proximity")))
    )
    oi_delta = (oi / prev_oi - 1.0) if prev_oi > 0 else 0.0
    return DerivativesFeatureSet(
        funding_rate=funding,
        funding_rate_delta=funding - prev_funding,
        open_interest=oi,
        open_interest_delta=oi_delta,
        long_short_ratio=ratio,
        liquidation_notional=liq,
        liquidation_cluster_proximity=proximity,
    )


__all__ = ["DerivativesFeatureSet", "build_derivatives_features"]
