from risk_engine import RiskEngine


def test_risk_engine_defaults_to_three_percent_daily_loss_limit():
    engine = RiskEngine(balance_quote=10_000)
    assert engine.max_daily_loss_pct == 0.03


def test_risk_engine_cannot_be_configured_above_three_percent_daily_loss():
    engine = RiskEngine(balance_quote=10_000, max_daily_loss_pct=0.10)
    assert engine.max_daily_loss_pct == 0.03


def test_risk_engine_accepts_lower_daily_loss_limit():
    engine = RiskEngine(balance_quote=10_000, max_daily_loss_pct=0.005)
    assert engine.max_daily_loss_pct == 0.005


def test_trade_risk_configuration_is_capped_even_when_constructed_directly():
    engine = RiskEngine(balance_quote=10_000, risk_per_trade_pct=0.02)
    assert engine.risk_per_trade_pct == 0.0025


def test_risk_override_cannot_increase_position_risk():
    engine = RiskEngine(balance_quote=10_000, risk_per_trade_pct=0.0025)
    result = engine.analyse(
        symbol="BTCUSDT",
        entry_price=100.0,
        atr=1.0,
        side="LONG",
        invalidation_price=98.0,
        risk_pct_override=0.01,
    )
    assert result.allowed
    assert result.risk_quote <= 25.0


def test_non_finite_risk_configuration_fails_closed():
    engine = RiskEngine(
        balance_quote=10_000,
        risk_per_trade_pct=float("nan"),
        max_position_fraction=float("inf"),
        max_daily_loss_pct=float("inf"),
    )
    assert engine.risk_per_trade_pct == 0.0
    assert engine.max_position_fraction == 0.0
    assert engine.max_daily_loss_pct == 0.0


def test_non_finite_risk_override_cannot_create_a_trade():
    engine = RiskEngine(balance_quote=10_000, risk_per_trade_pct=0.0025)
    result = engine.analyse(
        symbol="BTCUSDT",
        entry_price=100.0,
        atr=1.0,
        side="LONG",
        invalidation_price=98.0,
        risk_pct_override=float("nan"),
    )
    assert not result.allowed
