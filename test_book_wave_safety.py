from market_scanner import Candidate, MarketScanner
from wave_engine import DIRECTION_UP, MultiTimeframeWaveReport, WaveSnapshot


class FakeClient:
    pass


def base_candidate():
    return Candidate(
        symbol="BTCUSDT", score=80.0, signal=True, setup_score=100.0,
        signal_strength=1.0, breakout_distance_pct=0.2, risk_pct=2.0,
        risk_reward=2.0, atr_pct=0.02, spread_pct=0.0005,
        htf_confirmed=True, setup_state="STRONG_SIGNAL", reason="test",
    )


def report(position, exhaustion, nested=False):
    setup = WaveSnapshot(
        interval="1h", wave_degree="MEDIUM", direction=DIRECTION_UP,
        phase="IMPULSE", position=position, wave_label=f"W{position}",
        confidence=80.0, structural_confidence=80.0, impulse_score=85.0,
        exhaustion_risk=exhaustion, alligator_bullish=True, ao=2.0,
        data_bars=200, data_ok=True,
    )
    context = WaveSnapshot(
        interval="4h", wave_degree="MEDIUM_HIGH", direction=DIRECTION_UP,
        phase="IMPULSE", position=5 if nested else 3,
        wave_label="W5" if nested else "W3", confidence=70.0,
        structural_confidence=70.0, impulse_score=75.0,
        exhaustion_risk=70.0 if nested else 10.0,
        alligator_bullish=True, ao=1.0, data_bars=200, data_ok=True,
    )
    return MultiTimeframeWaveReport(
        frames={"4h": context, "1h": setup}, overall_direction=DIRECTION_UP,
        alignment_score=90.0, wave_score=85.0, exhaustion_risk=exhaustion,
        nested_w3=nested, nested_w3_parent_w5=nested,
        nested_w3_count=1 if nested else 0,
        wave_path="4h:W5 > 1h:W3" if nested else "4h:W3 > 1h:W5",
        htf_confirmed=True, setup_position=position, setup_phase="IMPULSE",
        entry_interval="1h" if nested else "",
        entry_position=3 if nested else 0,
        entry_parent_interval="4h" if nested else "",
        entry_parent_position=5 if nested else 0,
        entry_allowed=nested,
        entry_block_reason="" if nested else "W5 exhaustion",
        reason="test",
    )


def test_w5_high_exhaustion_is_blocked(monkeypatch):
    monkeypatch.setenv("WILLIAMS_MODE", "LEGACY")
    monkeypatch.setenv("MAX_WAVE_EXHAUSTION_FOR_ENTRY", "80")
    scanner = MarketScanner(FakeClient(), symbols=["BTCUSDT"], interval="1h")
    scanner.wave_engine.analyse = lambda symbol, cache=None: report(5, 85)
    got = scanner._apply_wave(base_candidate(), None)
    assert got.wave_entry_allowed is False
    assert "W5 exhaustion" in got.wave_block_reason


def test_child_w3_in_parent_w5_is_not_blocked_by_parent_context(monkeypatch):
    monkeypatch.setenv("WILLIAMS_MODE", "LEGACY")
    monkeypatch.setenv("MAX_WAVE_EXHAUSTION_FOR_ENTRY", "80")
    scanner = MarketScanner(FakeClient(), symbols=["BTCUSDT"], interval="1h")
    scanner.wave_engine.analyse = lambda symbol, cache=None: report(3, 20, nested=True)
    got = scanner._apply_wave(base_candidate(), None)
    assert got.wave_entry_allowed is True
    assert got.nested_w3_parent_w5 is True


def test_target_zone_matches_williams_62_to_100_percent_rule():
    scanner = MultiTimeframeWaveReport  # keep import surface explicit for this test module
    from wave_engine import MultiTimeframeWaveEngine, Pivot
    e = MultiTimeframeWaveEngine(FakeClient(), base_interval="1h", min_bars=5)
    pivots = [
        Pivot("DOWN", 100.0, 0, 2),   # W1 start
        Pivot("UP", 110.0, 1, 3),
        Pivot("DOWN", 105.0, 2, 4),
        Pivot("UP", 130.0, 3, 5),   # W3 end
        Pivot("DOWN", 120.0, 4, 6), # W4 end
    ]
    inside, low, high = e._target_zone(pivots, DIRECTION_UP, 5, 138.0)
    assert low == 138.6
    assert high == 150.0
    assert inside is False
