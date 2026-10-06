"""Orchestration layer for optional Williams ML/shadow models.

This module connects the feature vector to GMM/HMM regime models, LightGBM
direction, isotonic calibration and optional SHAP explanation. It never has
exchange credentials and never submits orders.
"""
from __future__ import annotations

from typing import Any, Mapping, Sequence

import numpy as np

from feature_store import MarketFeatureVector
from quant_models import (
    GMMRegimeModel,
    HMMRegimeModel,
    IsotonicProbabilityCalibrator,
    LightGBMDirectionModel,
)
from shap_explain import explain_prediction

FEATURE_COLUMNS = (
    "return_1",
    "return_5",
    "atr_pct",
    "volume_zscore",
    "dollar_bar_rate",
    "volume_bar_rate",
    "obi",
    "spread_pct",
    "trade_flow_imbalance",
    "alligator_spread_pct",
    "ao_acceleration",
    "wave_score",
    "wave_confidence",
    "wave_exhaustion_risk",
    "wave3_probability",
    "wave5_probability",
    "wave_parent_position",
    "wave_parent_confidence",
    "wave_nested_w3_probability",
    "wave_tf_agreement",
    "funding_rate",
    "funding_rate_delta",
    "open_interest_delta",
    "long_short_ratio",
    "liquidation_cluster_proximity",
)


def feature_row(vector: MarketFeatureVector) -> list[float]:
    payload = vector.to_dict()
    return [float(payload.get(name, 0.0) or 0.0) for name in FEATURE_COLUMNS]


def feature_matrix(vectors: Sequence[MarketFeatureVector]) -> np.ndarray:
    if not vectors:
        return np.empty((0, len(FEATURE_COLUMNS)), dtype=float)
    return np.asarray([feature_row(v) for v in vectors], dtype=float)


class ShadowMLPipeline:
    def __init__(self, model_version: str = "shadow-ml-1"):
        self.model_version = model_version
        self.gmm = None
        self.hmm = None
        self.direction = None
        self.calibrator = None

    def fit_gmm(self, vectors: Sequence[MarketFeatureVector]):
        self.gmm = GMMRegimeModel()
        self.gmm.fit(feature_matrix(vectors))
        return self

    def fit_hmm(self, vectors: Sequence[MarketFeatureVector]):
        self.hmm = HMMRegimeModel()
        self.hmm.fit(feature_matrix(vectors))
        return self

    def fit_direction(self, vectors: Sequence[MarketFeatureVector], labels: Sequence[int]):
        self.direction = LightGBMDirectionModel()
        self.direction.fit(feature_matrix(vectors), labels)
        return self

    def fit_calibrator(self, raw_probabilities: Sequence[float], labels: Sequence[int]):
        self.calibrator = IsotonicProbabilityCalibrator()
        self.calibrator.fit(raw_probabilities, labels)
        return self

    def predict(self, vector: MarketFeatureVector) -> dict[str, Any]:
        X = np.asarray([feature_row(vector)], dtype=float)
        payload: dict[str, Any] = {
            "model_version": self.model_version,
            "regime": vector.regime,
            "direction": "HOLD",
            "raw_probability": 0.0,
            "calibrated_probability": 0.0,
        }
        if self.gmm is not None:
            payload["gmm_regime"] = self.gmm.predict(X)[0]
            payload["regime"] = payload["gmm_regime"]
        if self.direction is not None:
            raw = float(self.direction.predict_proba(X)[0, 1])
            calibrated = (
                float(self.calibrator.transform([raw])[0])
                if self.calibrator is not None
                else raw
            )
            payload["raw_probability"] = raw
            payload["calibrated_probability"] = calibrated
            payload["direction"] = "LONG" if calibrated >= 0.60 else "HOLD"
        if self.hmm is not None:
            payload["hmm_state"] = int(self.hmm.predict_states(X)[0])
        return payload

    def explain(self, vector: MarketFeatureVector) -> Mapping[str, Any]:
        if self.direction is None or self.direction.model is None:
            return {"available": False, "reason": "direction model not fitted"}
        result = explain_prediction(
            self.direction.model,
            [feature_row(vector)],
            feature_names=FEATURE_COLUMNS,
        )
        result["available"] = True
        return result


__all__ = ["FEATURE_COLUMNS", "feature_row", "feature_matrix", "ShadowMLPipeline"]
