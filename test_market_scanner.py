from market_scanner import Candidate, MarketScanner


class FakeClient:
    pass


def test_best_prefers_strict_signal():
    scanner = MarketScanner(
        FakeClient(),
        symbols=[],
    )

    setup = Candidate(
        symbol="SOLUSDT",
        score=92,
        signal=False,
        setup_score=86,
        signal_strength=0.8,
        breakout_distance_pct=-0.3,
        risk_pct=2.0,
        risk_reward=2.0,
        atr_pct=0.03,
        spread_pct=0.0005,
        htf_confirmed=True,
        setup_state="SETUP_READY",
    )

    strict = Candidate(
        symbol="BNBUSDT",
        score=80,
        signal=True,
        setup_score=100,
        signal_strength=1.0,
        breakout_distance_pct=0.1,
        risk_pct=2.0,
        risk_reward=2.0,
        atr_pct=0.04,
        spread_pct=0.0007,
        htf_confirmed=True,
        setup_state="STRONG_SIGNAL",
    )

    scanner.scan = lambda: [setup, strict]

    best = scanner.best()

    assert best is strict
    assert best.symbol == "BNBUSDT"


def test_best_prefers_higher_score_when_both_strict():
    scanner = MarketScanner(
        FakeClient(),
        symbols=[],
    )

    a = Candidate(
        symbol="SOLUSDT",
        score=91,
        signal=True,
        setup_score=100,
        signal_strength=1.0,
        breakout_distance_pct=0.1,
        risk_pct=2.0,
        risk_reward=2.0,
        atr_pct=0.03,
        spread_pct=0.0005,
        htf_confirmed=True,
        setup_state="STRONG_SIGNAL",
    )

    b = Candidate(
        symbol="BNBUSDT",
        score=84,
        signal=True,
        setup_score=100,
        signal_strength=1.0,
        breakout_distance_pct=0.2,
        risk_pct=2.0,
        risk_reward=2.0,
        atr_pct=0.04,
        spread_pct=0.0007,
        htf_confirmed=True,
        setup_state="STRONG_SIGNAL",
    )

    scanner.scan = lambda: [b, a]

    best = scanner.best()

    assert best is a


def test_candidate_serialization():
    candidate = Candidate(
        symbol="TESTUSDT",
        score=80,
        signal=False,
        setup_score=85,
        signal_strength=0.8,
        breakout_distance_pct=-0.5,
        risk_pct=2.0,
        risk_reward=2.0,
        atr_pct=0.03,
        spread_pct=0.0005,
        htf_confirmed=True,
        setup_state="SETUP_READY",
        reason="test",
    )

    data = candidate.to_dict()

    assert data["symbol"] == "TESTUSDT"
    assert data["signal"] is False
    assert data["setup_state"] == "SETUP_READY"


test_best_prefers_strict_signal()
test_best_prefers_higher_score_when_both_strict()
test_candidate_serialization()

print("MARKET SCANNER TESTS: PASS")


def test_futures_scanner_does_not_use_fixed_target_rr_as_signal_gate():
    client = FakeClient()
    client.is_usdm_futures = True
    scanner = MarketScanner(client, symbols=[])
    assert scanner.require_min_rr_gate is False


def test_spot_scanner_keeps_legacy_fixed_target_rr_gate():
    client = FakeClient()
    client.is_usdm_futures = False
    scanner = MarketScanner(client, symbols=[])
    assert scanner.require_min_rr_gate is True
