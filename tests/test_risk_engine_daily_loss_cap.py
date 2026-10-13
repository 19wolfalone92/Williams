from risk_engine import RiskEngine


def test_risk_engine_defaults_to_one_percent_daily_loss_limit():
    engine = RiskEngine(balance_quote=10_000)
    assert engine.max_daily_loss_pct == 0.01


def test_risk_engine_cannot_be_configured_above_one_percent_daily_loss():
    engine = RiskEngine(balance_quote=10_000, max_daily_loss_pct=0.03)
    assert engine.max_daily_loss_pct == 0.01


def test_risk_engine_accepts_lower_daily_loss_limit():
    engine = RiskEngine(balance_quote=10_000, max_daily_loss_pct=0.005)
    assert engine.max_daily_loss_pct == 0.005
