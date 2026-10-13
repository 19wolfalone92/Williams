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
        invalidation_price=98000,
    )

    assert result.allowed is True
    assert result.symbol == "BTCUSDT"
    assert result.stop_price == 98000
    assert result.take_profit_price == 104000
    assert result.risk_reward == 2.0
    assert result.risk_quote == 25
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
        invalidation_price=4900,
    )

    # ATR = 1% of price.
    # Stop = 2 ATR = 2%.
    # Futures policy caps risk at 0.25% equity and includes fee/slippage buffers.
    # A 2% structural stop plus execution buffers yields a smaller safe notional.
    assert result.allowed is True
    assert result.position_quote == 1063.82978723
    assert result.risk_quote == 25


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
        invalidation_price=980,
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
        invalidation_price=99000,
    )

    weak = engine.analyse(
        symbol="ETHUSDT",
        entry_price=5000,
        atr=300,
        signal_strength=0.5,
        htf_confirmed=False,
        spread_pct=0.0012,
        invalidation_price=4400,
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
        invalidation_price=99000,
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
        invalidation_price=99000,
    )
    assert result.allowed is True
    assert result.risk_quote == 25
    assert result.risk_pct == 0.25
    print("[PASS] default per-trade risk is capped at 0.25%")


def test_non_finite_market_and_risk_inputs_fail_closed():
    engine = RiskEngine(balance_quote=10000)
    cases = [
        {"entry_price": float("nan"), "atr": 500},
        {"entry_price": 100000, "atr": float("inf")},
        {"entry_price": 100000, "atr": 500, "spread_pct": float("nan")},
        {"entry_price": 100000, "atr": 500, "risk_pct_override": float("nan")},
        {"entry_price": 100000, "atr": 500, "max_spread_pct": float("inf")},
    ]
    for case in cases:
        kwargs = {"symbol": "BTCUSDT", "entry_price": 100000, "atr": 500}
        kwargs.update(case)
        result = engine.analyse(**kwargs)
        assert result.allowed is False
        assert "non-finite" in result.reason


def test_zero_account_equity_fails_closed():
    engine = RiskEngine(balance_quote=0)
    result = engine.analyse(symbol="BTCUSDT", entry_price=100000, atr=500)
    assert result.allowed is False
    assert result.reason == "invalid account equity"

def test_invalid_structural_invalidation_fails_closed_instead_of_using_atr_fallback():
    engine = RiskEngine(balance_quote=10000)

    invalid_long = engine.analyse(
        symbol="BTCUSDT",
        entry_price=100000,
        atr=500,
        side="LONG",
        invalidation_price=100100,
    )
    invalid_short = engine.analyse(
        symbol="BTCUSDT",
        entry_price=100000,
        atr=500,
        side="SHORT",
        invalidation_price=99900,
    )

    assert invalid_long.allowed is False
    assert invalid_long.reason == "structural invalidation stop must be below entry for LONG"
    assert invalid_long.stop_price == 0.0
    assert invalid_long.position_quote == 0.0

    assert invalid_short.allowed is False
    assert invalid_short.reason == "structural invalidation stop must be above entry for SHORT"
    assert invalid_short.stop_price == 0.0
    assert invalid_short.position_quote == 0.0


def test_valid_structural_invalidation_is_used_for_both_directions():
    engine = RiskEngine(balance_quote=10000)

    long_result = engine.analyse(
        symbol="BTCUSDT",
        entry_price=100000,
        atr=500,
        side="LONG",
        invalidation_price=99000,
    )
    short_result = engine.analyse(
        symbol="BTCUSDT",
        entry_price=100000,
        atr=500,
        side="SHORT",
        invalidation_price=101000,
    )

    assert long_result.allowed is True
    assert long_result.stop_price == 99000
    assert short_result.allowed is True
    assert short_result.stop_price == 101000


def test_missing_structural_invalidation_blocks_without_atr_fallback():
    engine = RiskEngine(balance_quote=10000)
    result = engine.analyse(
        symbol="BTCUSDT",
        entry_price=100000,
        atr=500,
        side="LONG",
    )
    assert result.allowed is False
    assert result.stop_price == 0.0
    assert result.position_quote == 0.0
    assert "structural invalidation stop is required" in result.reason

