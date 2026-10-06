import pytest

from ai_shadow import AIOrderIntent
from derivatives_features import build_derivatives_features
from quant_models import (
    GMMRegimeModel,
    HMMRegimeModel,
    IsotonicProbabilityCalibrator,
    LightGBMDirectionModel,
)
from shadow_execution import ShadowExecutionSimulator


def test_derivatives_features():
    value = build_derivatives_features(
        {
            "funding_rate": 0.001,
            "open_interest": 1000,
            "long_short_ratio": 1.2,
            "liquidation_notional": 500,
            "liquidation_cluster_proximity": 0.7,
        },
        {"funding_rate": 0.0005, "open_interest": 900},
    )
    assert value.funding_rate_delta == 0.0005
    assert value.open_interest_delta > 0
    assert 0.0 <= value.liquidation_cluster_proximity <= 1.0


def test_shadow_execution_has_no_order_path():
    intent = AIOrderIntent(
        intent_id="test",
        timestamp_ms=1,
        engine_version="test",
        symbol="BTCUSDT",
        interval="1h",
        regime="TRENDING_EXPANSION",
        direction="LONG",
        confidence=0.8,
        suggested_size_fraction=0.005,
        invalidation=97.0,
        target=107.0,
        reason="test",
        feature_attribution={"obi": 0.2},
    )
    fill = ShadowExecutionSimulator(seed=1).simulate(
        intent, reference_price=100.0, expected_reward_pct=0.01
    )
    assert fill.executed
    assert fill.fill_price > fill.reference_price
    assert fill.latency_ms >= 100


def test_optional_models_fail_closed_without_dependencies():
    X = [[0.1, 0.2]]
    with pytest.raises((RuntimeError, ValueError)):
        GMMRegimeModel(n_components=3).fit(X)
    with pytest.raises((RuntimeError, ValueError)):
        HMMRegimeModel(n_components=3).fit(X)
    with pytest.raises((RuntimeError, ValueError)):
        LightGBMDirectionModel().fit(X, [0])
    with pytest.raises((RuntimeError, ValueError)):
        IsotonicProbabilityCalibrator().fit([0.2], [0])
