import math
import pytest

from risk_engine import RiskEngine


def engine():
    return RiskEngine(balance_quote=10_000, risk_per_trade_pct=0.005)


@pytest.mark.parametrize("value", [float("nan"), float("inf"), float("-inf")])
def test_constructor_rejects_non_finite_configuration(value):
    with pytest.raises(ValueError):
        RiskEngine(balance_quote=value)


@pytest.mark.parametrize("entry,atr", [
    (float("nan"), 10),
    (float("inf"), 10),
    (100, float("nan")),
    (100, float("inf")),
])
def test_non_finite_market_inputs_fail_closed(entry, atr):
    result = engine().analyse("BTCUSDT", entry_price=entry, atr=atr)
    assert result.allowed is False
    assert result.position_quote == 0
    assert result.risk_quote == 0


@pytest.mark.parametrize("override", [0, -0.001, float("nan"), float("inf"), 0.01])
def test_invalid_or_over_limit_risk_override_fails_closed(override):
    result = engine().analyse(
        "BTCUSDT", entry_price=100, atr=1, risk_pct_override=override
    )
    assert result.allowed is False
    assert result.position_quote == 0
    assert result.risk_quote == 0


def test_lower_risk_override_is_accepted_and_respected():
    result = engine().analyse(
        "BTCUSDT", entry_price=100, atr=1, risk_pct_override=0.002
    )
    assert result.allowed is True
    assert result.risk_quote == pytest.approx(20.0)
    assert result.risk_pct == pytest.approx(0.2)


@pytest.mark.parametrize("kwargs", [
    {"spread_pct": float("nan")},
    {"max_spread_pct": float("inf")},
    {"signal_strength": float("nan")},
    {"stop_atr_multiplier": 0},
    {"target_atr_multiplier": -1},
    {"min_notional": -1},
])
def test_invalid_risk_parameters_fail_closed(kwargs):
    result = engine().analyse("BTCUSDT", entry_price=100, atr=1, **kwargs)
    assert result.allowed is False
    assert result.position_quote == 0


def test_position_respects_configured_risk_and_exposure_caps():
    risk = engine()
    result = risk.analyse("BTCUSDT", entry_price=100, atr=1)
    assert result.allowed is True
    assert result.risk_quote <= risk.balance * risk.risk_per_trade_pct
    assert result.position_quote <= risk.balance * risk.max_position_fraction
    assert math.isfinite(result.position_quote)
