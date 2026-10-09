import pytest

from risk_engine import RiskEngine


def test_good_trade():
    engine = RiskEngine(
        balance_quote=10000,
        risk_per_trade_pct=0.01,
        max_position_fraction=0.25,
        min_rr=1.5,
    )

    result = engine.analyse(
        symbol="BTCUSDT",
        entry_price=100000,
        atr=1000,
        signal_strength=1.0,
        htf_confirmed=True,
        spread_pct=0.0003,
    )

    assert result.allowed is True
    assert result.symbol == "BTCUSDT"
    assert result.stop_price == 98000
    assert result.take_profit_price == 104000
    assert result.risk_reward == 2.0
    assert result.risk_quote == 100
    assert result.position_quote > 0
    assert 0 < result.score <= 100


def test_risk_based_position_size():
    engine = RiskEngine(
        balance_quote=10000,
        risk_per_trade_pct=0.01,
        max_position_fraction=0.25,
    )

    result = engine.analyse(
        symbol="ETHUSDT",
        entry_price=5000,
        atr=50,
        signal_strength=1.0,
        htf_confirmed=True,
        spread_pct=0.0002,
    )

    # ATR = 1% of price.
    # Stop = 2 ATR = 2%.
    # Risk = 100 USDT.
    # Position = 100 / 0.02 = 5000 USDT.
    # Exposure cap = 2500 USDT.
    assert result.allowed is True
    assert result.position_quote == 2500
    assert result.risk_quote == 100


def test_high_atr_is_blocked():
    engine = RiskEngine(
        balance_quote=10000,
        max_atr_pct=0.08,
    )

    result = engine.analyse(
        symbol="SOLUSDT",
        entry_price=100,
        atr=10,
        signal_strength=1.0,
        htf_confirmed=True,
        spread_pct=0.0002,
    )

    assert result.allowed is False
    assert "ATR" in result.reason


def test_bad_spread_is_blocked():
    engine = RiskEngine(balance_quote=10000)

    result = engine.analyse(
        symbol="XRPUSDT",
        entry_price=2,
        atr=0.02,
        signal_strength=1.0,
        htf_confirmed=True,
        spread_pct=0.01,
        max_spread_pct=0.0015,
    )

    assert result.allowed is False
    assert "spread" in result.reason


def test_bad_rr_is_blocked():
    engine = RiskEngine(
        balance_quote=10000,
        min_rr=2.5,
    )

    result = engine.analyse(
        symbol="BNBUSDT",
        entry_price=1000,
        atr=10,
        signal_strength=1.0,
        htf_confirmed=True,
        spread_pct=0.0002,
        stop_atr_multiplier=2.0,
        target_atr_multiplier=4.0,
    )

    # R:R = 2.0, minimum = 2.5.
    assert result.allowed is False
    assert "R:R" in result.reason


def test_score_prefers_stronger_setup():
    engine = RiskEngine(balance_quote=10000)

    strong = engine.analyse(
        symbol="BTCUSDT",
        entry_price=100000,
        atr=500,
        signal_strength=1.0,
        htf_confirmed=True,
        spread_pct=0.0002,
    )

    weak = engine.analyse(
        symbol="ETHUSDT",
        entry_price=5000,
        atr=300,
        signal_strength=0.5,
        htf_confirmed=False,
        spread_pct=0.0012,
    )

    assert strong.allowed is True
    assert weak.allowed is True
    assert strong.score > weak.score


def test_to_dict():
    engine = RiskEngine(balance_quote=10000)

    result = engine.analyse(
        symbol="BTCUSDT",
        entry_price=100000,
        atr=500,
        signal_strength=1.0,
        htf_confirmed=True,
        spread_pct=0.0002,
    )

    data = result.to_dict()

    assert data["symbol"] == "BTCUSDT"
    assert "stop_price" in data
    assert "take_profit_price" in data
    assert "risk_reward" in data
    assert "position_quote" in data
    assert "score" in data


if __name__ == "__main__":
    test_good_trade()
    test_risk_based_position_size()
    test_high_atr_is_blocked()
    test_bad_spread_is_blocked()
    test_bad_rr_is_blocked()
    test_score_prefers_stronger_setup()
    test_to_dict()

    print("RISK ENGINE TESTS: PASS")


def test_extreme_atr_is_blocked():
    engine = RiskEngine(
        balance_quote=10000,
        max_atr_pct=0.08,
    )

    result = engine.analyse(
        symbol="TESTUSDT",
        entry_price=5000,
        atr=1500,
        signal_strength=1.0,
        htf_confirmed=True,
        spread_pct=0.0002,
    )

    assert result.allowed is False
    assert "ATR" in result.reason


test_extreme_atr_is_blocked()


def test_default_risk_is_half_percent():
    engine = RiskEngine(balance_quote=10000)
    result = engine.analyse(
        symbol="BTCUSDT",
        entry_price=100000,
        atr=500,
        signal_strength=1.0,
        htf_confirmed=True,
        spread_pct=0.0002,
    )
    assert result.allowed is True
    assert result.risk_quote == 50
    assert result.risk_pct == 0.5
    print("[PASS] default per-trade risk is 0.5%")


@pytest.mark.parametrize(
    "entry,atr,spread",
    [
        (float("nan"), 1.0, 0.0),
        (100.0, float("inf"), 0.0),
        (100.0, 1.0, float("nan")),
        (100.0, 1.0, float("inf")),
    ],
)
def test_non_finite_market_inputs_are_blocked(entry, atr, spread):
    result = RiskEngine(balance_quote=10_000).analyse(
        "BTCUSDT", entry_price=entry, atr=atr, spread_pct=spread
    )
    assert not result.allowed
    assert result.position_quote == 0.0


def test_risk_override_cannot_exceed_configured_trade_risk():
    result = RiskEngine(
        balance_quote=10_000, risk_per_trade_pct=0.005
    ).analyse(
        "BTCUSDT",
        entry_price=100_000,
        atr=500,
        risk_pct_override=0.01,
    )
    assert not result.allowed
    assert "override exceeds" in result.reason


def test_smaller_risk_override_is_allowed():
    result = RiskEngine(
        balance_quote=10_000, risk_per_trade_pct=0.005
    ).analyse(
        "BTCUSDT",
        entry_price=100_000,
        atr=500,
        risk_pct_override=0.0025,
    )
    assert result.allowed
    assert result.risk_quote == 25.0


@pytest.mark.parametrize(
    "kwargs",
    [
        {"balance_quote": float("nan")},
        {"balance_quote": 10_000, "risk_per_trade_pct": float("inf")},
        {"balance_quote": 10_000, "max_position_fraction": -0.1},
    ],
)
def test_invalid_risk_configuration_is_rejected(kwargs):
    with pytest.raises(ValueError):
        RiskEngine(**kwargs)


@pytest.mark.parametrize(
    "side,invalidation",
    [("LONG", 101.0), ("SHORT", 99.0), ("LONG", -1.0)],
)
def test_wrong_side_structural_invalidation_blocks_trade(side, invalidation):
    result = RiskEngine(balance_quote=10_000).analyse(
        "BTCUSDT",
        entry_price=100.0,
        atr=1.0,
        side=side,
        invalidation_price=invalidation,
    )
    assert not result.allowed
    assert "structural invalidation" in result.reason
