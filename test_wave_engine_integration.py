from types import SimpleNamespace

import pandas as pd

from market_scanner import Candidate, MarketScanner
from wave_engine import DIRECTION_UP, MultiTimeframeWaveReport, WaveSnapshot


class FakeClient:
    pass


def make_candidate(signal=True, base_score=70.0):
    return Candidate(
        symbol="BTCUSDT",
        score=base_score,
        signal=signal,
        setup_score=100.0 if signal else 85.0,
        signal_strength=1.0 if signal else 0.8,
        breakout_distance_pct=0.1,
        risk_pct=2.0,
        risk_reward=2.0,
        atr_pct=0.03,
        spread_pct=0.0005,
        htf_confirmed=False,
        setup_state="STRONG_SIGNAL" if signal else "SETUP_READY",
        reason="test",
        base_score=base_score,
    )


def make_report(htf_confirmed=True, wave_score=90.0):
    context = WaveSnapshot(
        interval="4h",
        wave_degree="MEDIUM_HIGH",
        direction=DIRECTION_UP,
        phase="IMPULSE",
        position=5,
        wave_label="W5",
        confidence=75.0,
        structural_confidence=75.0,
        impulse_score=80.0,
        exhaustion_risk=30.0,
        alligator_bullish=htf_confirmed,
        ao=1.0 if htf_confirmed else -1.0,
        data_bars=200,
        data_ok=True,
    )
    setup = WaveSnapshot(
        interval="1h",
        wave_degree="MEDIUM",
        direction=DIRECTION_UP,
        phase="IMPULSE",
        position=3,
        wave_label="W3",
        confidence=80.0,
        structural_confidence=82.0,
        impulse_score=90.0,
        exhaustion_risk=10.0,
        alligator_bullish=True,
        ao=2.0,
        data_bars=200,
        data_ok=True,
    )
    return MultiTimeframeWaveReport(
        frames={"4h": context, "1h": setup},
        overall_direction=DIRECTION_UP,
        alignment_score=90.0,
        wave_score=wave_score,
        exhaustion_risk=20.0,
        nested_w3=True,
        nested_w3_parent_w5=True,
        nested_w3_count=1,
        wave_path="4h:W5 > 1h:W3",
        htf_confirmed=htf_confirmed,
        setup_position=3,
        setup_phase="IMPULSE",
        reason="nested W3 inside parent W5 detected - W5 context is not a veto",
    )


def test_wave_score_is_added_as_bounded_adjustment(monkeypatch):
    monkeypatch.setenv("SCAN_ALL_USDT", "false")
    scanner = MarketScanner(FakeClient(), symbols=["BTCUSDT"], interval="1h")
    closed = pd.DataFrame({"close": [100.0]})
    candidate = make_candidate(signal=True, base_score=70.0)
    scanner._analyse_base = lambda symbol, metadata=None: (candidate, closed)
    scanner.wave_engine.analyse = lambda symbol, cache=None: make_report(True, 90.0)
    scanner._resolve_symbols = lambda: ["BTCUSDT"]

    result = scanner.scan()

    assert len(result) == 1
    got = result[0]
    assert got.score == 78.0
    assert got.base_score == 70.0
    assert got.wave_score == 90.0
    assert got.wave_adjustment == 8.0
    assert got.nested_w3 is True
    assert got.nested_w3_parent_w5 is True
    assert got.wave_path == "4h:W5 > 1h:W3"


def test_strict_candidate_without_htf_confirmation_is_removed(monkeypatch):
    monkeypatch.setenv("SCAN_ALL_USDT", "false")
    monkeypatch.setenv("REQUIRE_HTF_CONFIRMATION", "true")
    scanner = MarketScanner(FakeClient(), symbols=["BTCUSDT"], interval="1h")
    closed = pd.DataFrame({"close": [100.0]})
    candidate = make_candidate(signal=True, base_score=70.0)
    scanner._analyse_base = lambda symbol, metadata=None: (candidate, closed)
    scanner.wave_engine.analyse = lambda symbol, cache=None: make_report(False, 80.0)
    scanner._resolve_symbols = lambda: ["BTCUSDT"]

    result = scanner.scan()
    assert result == []


def test_watch_candidate_can_be_ranked_with_wave_context_without_creating_signal(monkeypatch):
    monkeypatch.setenv("SCAN_ALL_USDT", "false")
    monkeypatch.setenv("REQUIRE_HTF_CONFIRMATION", "true")
    scanner = MarketScanner(FakeClient(), symbols=["BTCUSDT"], interval="1h")
    closed = pd.DataFrame({"close": [100.0]})
    candidate = make_candidate(signal=False, base_score=60.0)
    scanner._analyse_base = lambda symbol, metadata=None: (candidate, closed)
    scanner.wave_engine.analyse = lambda symbol, cache=None: make_report(True, 95.0)
    scanner._resolve_symbols = lambda: ["BTCUSDT"]

    result = scanner.scan()
    assert len(result) == 1
    assert result[0].signal is False
    assert result[0].score == 69.0
    assert result[0].wave_score == 95.0



def test_strict_signal_is_blocked_when_wave_analysis_is_unavailable(monkeypatch):
    monkeypatch.setenv("NO_TRADE_WHEN_UNCERTAIN", "true")
    monkeypatch.setenv("REQUIRE_HTF_CONFIRMATION", "false")
    scanner = MarketScanner(FakeClient(), symbols=["BTCUSDT"], interval="1h")
    closed = pd.DataFrame({"close": [100.0]})
    candidate = make_candidate(signal=True, base_score=70.0)
    scanner._analyse_base = lambda symbol, metadata=None: (candidate, closed)

    def fail_wave(symbol, cache=None):
        raise RuntimeError("wave service unavailable")

    scanner.wave_engine.analyse = fail_wave
    assert scanner.analyse("BTCUSDT") is None
