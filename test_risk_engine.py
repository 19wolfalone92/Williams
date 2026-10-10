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


def test_non_finite_risk_inputs_are_blocked():
    import math
    import pytest

    engine = RiskEngine(balance_quote=10000)
    for kwargs in (
        {"entry_price": float("nan"), "atr": 10},
        {"entry_price": 100, "atr": float("inf")},
        {"entry_price": 100, "atr": 1, "spread_pct": float("nan")},
        {"entry_price": 100, "atr": 1, "invalidation_price": float("nan")},
        {"entry_price": 100, "atr": 1, "risk_pct_override": float("inf")},
    ):
        result = engine.analyse(
            symbol="BTCUSDT",
            entry_price=kwargs.pop("entry_price"),
            atr=kwargs.pop("atr"),
            **kwargs,
        )
        assert result.allowed is False
        assert math.isfinite(result.position_quote)
        assert result.position_quote == 0.0


def test_risk_engine_rejects_non_finite_or_unsafe_configuration():
    import pytest

    with pytest.raises(ValueError, match="finite"):
        RiskEngine(balance_quote=float("nan"))
    with pytest.raises(ValueError, match="max_position_fraction"):
        RiskEngine(balance_quote=1000, max_position_fraction=1.5)


def test_risk_engine_rejects_prices_that_collapse_stop_distance_to_zero():
    engine = RiskEngine(balance_quote=1000.0)
    result = engine.analyse(
        symbol="BTCUSDT",
        entry_price=1e308,
        atr=1e-300,
        side="LONG",
        risk_pct_override=0.001,
    )
    assert result.allowed is False
    assert result.position_quote == 0.0
    assert "stop" in result.reason.lower() or "distance" in result.reason.lower()


def test_invalid_explicit_structural_stop_fails_closed_instead_of_atr_fallback():
    engine = RiskEngine(balance_quote=10_000)
    cases = [
        ({"side": "LONG", "entry_price": 100, "atr": 2, "invalidation_price": 101},
         "LONG structural invalidation"),
        ({"side": "SHORT", "entry_price": 100, "atr": 2, "invalidation_price": 99},
         "SHORT structural invalidation"),
        ({"side": "LONG", "entry_price": 100, "atr": 2, "invalidation_price": -1},
         "cannot be negative"),
    ]
    for kwargs, expected in cases:
        result = engine.analyse(symbol="TESTUSDT", **kwargs)
        assert result.allowed is False
        assert result.position_quote == 0.0
        assert expected in result.reason


def test_explicit_empty_side_is_blocked_not_defaulted_to_long():
    engine = RiskEngine(balance_quote=10_000)
    result = engine.analyse(
        symbol="BTCUSDT",
        entry_price=100_000,
        atr=500,
        side="",
    )
    assert result.allowed is False
    assert result.position_quote == 0.0
    assert "unsupported side" in result.reason


def test_structural_campaign_profile_can_report_low_reference_rr_without_fixed_target_veto():
    engine = RiskEngine(
        balance_quote=10_000,
        min_rr=1.5,
        require_min_rr=False,
    )
    result = engine.analyse(
        symbol="BTCUSDT",
        entry_price=100,
        atr=10,
        side="LONG",
        invalidation_price=80,
        target_atr_multiplier=1.0,
    )
    assert result.allowed is True
    assert result.risk_reward < 1.5


def test_structural_campaign_does_not_require_positive_hypothetical_short_target():
    engine = RiskEngine(
        balance_quote=10_000,
        max_atr_pct=0.25,
        min_rr=1.5,
        require_min_rr=False,
    )
    result = engine.analyse(
        symbol="LOWPRICEUSDT",
        entry_price=1.0,
        atr=0.3,
        side="SHORT",
        invalidation_price=1.2,
        target_atr_multiplier=4.0,
    )
    assert result.allowed is True
    assert result.take_profit_price == 0.0
    assert result.risk_reward == 0.0
