"""Optional SHAP explainability for Williams research models."""
from __future__ import annotations

from typing import Any, Sequence

import numpy as np


def explain_prediction(model: Any, X: Sequence[Sequence[float]], feature_names: Sequence[str] | None = None) -> dict:
    try:
        import shap
    except ImportError as exc:
        raise RuntimeError(
            "SHAP is optional and is not installed. Install requirements-research.txt."
        ) from exc

    values = np.asarray(X, dtype=float)
    if values.ndim == 1:
        values = values.reshape(1, -1)
    explainer = shap.Explainer(model, values)
    explanation = explainer(values)
    raw = np.asarray(explanation.values)

    # Binary classifiers can return [rows, features, classes]. Keep the
    # positive-direction class where applicable.
    if raw.ndim == 3:
        raw = raw[:, :, -1]
    if raw.ndim != 2:
        raise ValueError(f"Unexpected SHAP output shape: {raw.shape}")

    names = list(feature_names or [f"feature_{i}" for i in range(raw.shape[1])])
    if len(names) != raw.shape[1]:
        raise ValueError("feature_names length does not match model input")

    mean_abs = np.mean(np.abs(raw), axis=0)
    order = np.argsort(mean_abs)[::-1]
    top = [
        {"feature": names[int(i)], "mean_abs_shap": float(mean_abs[int(i)])}
        for i in order[: min(10, len(order))]
    ]
    return {
        "feature_names": names,
        "rows": int(values.shape[0]),
        "top_features": top,
        "values": raw.tolist(),
    }


__all__ = ["explain_prediction"]
