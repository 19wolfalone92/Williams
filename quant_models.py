"""Optional ML models for Williams research/shadow use only.

No class in this module has exchange credentials or order-submission capability.
Heavy ML dependencies are intentionally optional so the production/Testnet
runtime stays small and deterministic until model artifacts are admitted.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Sequence

import numpy as np


def _require(module_name: str, import_name: str):
    try:
        module = __import__(module_name, fromlist=[import_name])
        return getattr(module, import_name)
    except ImportError as exc:
        raise RuntimeError(
            f"{import_name} is optional and is not installed. "
            f"Install requirements-research.txt to enable this model."
        ) from exc


@dataclass(frozen=True)
class ModelPrediction:
    regime: str = "UNKNOWN"
    raw_probability: float = 0.0
    calibrated_probability: float = 0.0
    direction: str = "HOLD"
    model_version: str = ""


class GMMRegimeModel:
    """Gaussian-mixture regime classifier.

    The component labels are mapped after fitting by ascending mean volatility.
    This mapping is a research convention, not an economic truth.
    """

    def __init__(self, n_components: int = 3, random_state: int = 42):
        self.n_components = max(2, int(n_components))
        self.random_state = int(random_state)
        self.model = None
        self.component_to_regime: dict[int, str] = {}

    def fit(self, X: Sequence[Sequence[float]]):
        GaussianMixture = _require("sklearn.mixture", "GaussianMixture")
        x = np.asarray(X, dtype=float)
        if x.ndim != 2 or len(x) < self.n_components:
            raise ValueError("GMM requires a 2D matrix with at least n_components rows")
        self.model = GaussianMixture(
            n_components=self.n_components,
            random_state=self.random_state,
        ).fit(x)
        order = np.argsort(self.model.means_[:, 0])
        labels = ["LOW_VOL_FLAT", "TRENDING_EXPANSION", "HIGH_NOISE_WASH"]
        self.component_to_regime = {
            int(component): labels[min(i, len(labels) - 1)]
            for i, component in enumerate(order)
        }
        return self

    def predict(self, X: Sequence[Sequence[float]]) -> list[str]:
        if self.model is None:
            raise RuntimeError("GMM model is not fitted")
        components = self.model.predict(np.asarray(X, dtype=float))
        return [self.component_to_regime.get(int(x), "UNKNOWN") for x in components]


class HMMRegimeModel:
    """Gaussian HMM regime classifier; requires optional hmmlearn."""

    def __init__(self, n_components: int = 3, random_state: int = 42, n_iter: int = 200):
        self.n_components = max(2, int(n_components))
        self.random_state = int(random_state)
        self.n_iter = max(10, int(n_iter))
        self.model = None

    def fit(self, X: Sequence[Sequence[float]]):
        GaussianHMM = _require("hmmlearn.hmm", "GaussianHMM")
        x = np.asarray(X, dtype=float)
        if x.ndim != 2 or len(x) < self.n_components * 5:
            raise ValueError("HMM requires a 2D time-series matrix with sufficient history")
        self.model = GaussianHMM(
            n_components=self.n_components,
            covariance_type="diag",
            n_iter=self.n_iter,
            random_state=self.random_state,
        ).fit(x)
        return self

    def predict_states(self, X: Sequence[Sequence[float]]) -> np.ndarray:
        if self.model is None:
            raise RuntimeError("HMM model is not fitted")
        return self.model.predict(np.asarray(X, dtype=float))


class LightGBMDirectionModel:
    """LightGBM binary direction model; never emits an exchange order."""

    def __init__(self, random_state: int = 42, n_estimators: int = 200):
        self.random_state = int(random_state)
        self.n_estimators = max(20, int(n_estimators))
        self.model = None

    def fit(self, X: Sequence[Sequence[float]], y: Sequence[int]):
        LGBMClassifier = _require("lightgbm", "LGBMClassifier")
        x = np.asarray(X, dtype=float)
        target = np.asarray(y, dtype=int)
        if x.ndim != 2 or len(x) != len(target):
            raise ValueError("X/y shape mismatch")
        if len(np.unique(target)) < 2:
            raise ValueError("direction target must contain both classes")
        self.model = LGBMClassifier(
            n_estimators=self.n_estimators,
            random_state=self.random_state,
            objective="binary",
            verbosity=-1,
        )
        self.model.fit(x, target)
        return self

    def predict_proba(self, X: Sequence[Sequence[float]]) -> np.ndarray:
        if self.model is None:
            raise RuntimeError("LightGBM model is not fitted")
        return np.asarray(self.model.predict_proba(np.asarray(X, dtype=float)), dtype=float)


class IsotonicProbabilityCalibrator:
    """Isotonic calibration for raw model probabilities."""

    def __init__(self):
        self.model = None

    def fit(self, raw_probabilities: Sequence[float], labels: Sequence[int]):
        IsotonicRegression = _require("sklearn.isotonic", "IsotonicRegression")
        raw = np.asarray(raw_probabilities, dtype=float)
        y = np.asarray(labels, dtype=float)
        if len(raw) != len(y) or len(raw) < 20:
            raise ValueError("calibration requires matching arrays with at least 20 samples")
        self.model = IsotonicRegression(y_min=0.0, y_max=1.0, out_of_bounds="clip").fit(raw, y)
        return self

    def transform(self, raw_probabilities: Sequence[float]) -> np.ndarray:
        if self.model is None:
            raise RuntimeError("calibrator is not fitted")
        return np.asarray(self.model.transform(np.asarray(raw_probabilities, dtype=float)))


def probability_margin(long_probability: float, short_probability: float) -> float:
    return abs(float(long_probability) - float(short_probability))


__all__ = [
    "ModelPrediction",
    "GMMRegimeModel",
    "HMMRegimeModel",
    "LightGBMDirectionModel",
    "IsotonicProbabilityCalibrator",
    "probability_margin",
]
