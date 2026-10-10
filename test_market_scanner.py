from types import SimpleNamespace

import pandas as pd

from campaign_model import SignalRole, SignalSpec, SignalType
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



def test_wave_enrichment_preserves_fractal_confirmation_chronology():
    signal = SignalSpec.new(
        symbol="BTCUSDT",
        side="BUY",
        direction="LONG",
        signal_type=SignalType.FRACTAL,
        role=SignalRole.ENTRY,
        timeframe="1h",
        signal_bar_time_ms=100,
        confirmation_time_ms=300,
        trigger_price=110.0,
        protective_reference=95.0,
        invalidation_price=95.0,
        wave_invalidation_price=90.0,
        source_candle_index=0,
        expires_at_ms=10**15,
    )
    report = SimpleNamespace(
        frames={},
        wave_score=50.0,
        overall_direction="NEUTRAL",
        setup_position=0,
        setup_phase="UNKNOWN",
        setup=None,
        reason="",
        wave_path="",
        exhaustion_risk=0.0,
        entry_allowed=True,
        entry_block_reason="",
        primary_count="",
        alternative_count="",
        nested_w3=False,
        nested_w3_parent_w5=False,
        operative_interval="1h",
        entry_score=0.0,
        to_dict=lambda: {"status": "test"},
    )
    scanner = MarketScanner(FakeClient(), symbols=[])
    scanner.wave_engine = SimpleNamespace(analyse=lambda *args, **kwargs: report)
    scanner.quant_ranking_enabled = False
    scanner.ai_shadow_enabled = False
    scanner.feature_store = None
    scanner._htf_confirmation = lambda *args, **kwargs: True

    candidate = Candidate(
        symbol="BTCUSDT",
        score=50.0,
        signal=True,
        setup_score=80.0,
        signal_strength=1.0,
        breakout_distance_pct=0.1,
        risk_pct=0.5,
        risk_reward=2.0,
        atr_pct=0.01,
        spread_pct=0.0001,
        htf_confirmed=True,
        setup_state="STRONG_SIGNAL",
        campaign_ready=True,
        direction="LONG",
        campaign_signal_specs=[signal.to_dict()],
    )
    enriched = scanner._apply_wave(candidate, pd.DataFrame())
    restored = SignalSpec(
        **{
            **enriched.campaign_signal_specs[0],
            "signal_type": SignalType(enriched.campaign_signal_specs[0]["signal_type"]),
            "role": SignalRole(enriched.campaign_signal_specs[0]["role"]),
        }
    )
    assert restored.signal_bar_time_ms == 100
    assert restored.confirmation_time_ms == 300
    assert restored.source_candle_index == 0
    assert restored.invalidation_price == 95.0
    assert restored.wave_invalidation_price == 90.0


test_best_prefers_strict_signal()
test_best_prefers_higher_score_when_both_strict()
test_candidate_serialization()



def _wm1_candidate(direction, *, trigger=101.0, stop=99.0, signal_direction=None):
    actual_direction = signal_direction or direction
    signal = {
        "signal_type": "REVERSAL",
        "role": "ENTRY",
        "direction": actual_direction,
        "timeframe": "1h",
        "signal_bar_time_ms": 100,
        "confirmation_time_ms": 200,
        "expires_at_ms": 10_000,
        "trigger_price": trigger,
        "protective_reference": stop,
        "angulation_score": 2.0,
    }
    return Candidate(
        symbol="BTCUSDT",
        score=50,
        signal=True,
        setup_score=80,
        signal_strength=1.0,
        breakout_distance_pct=0.1,
        risk_pct=0.5,
        risk_reward=2.0,
        atr_pct=0.01,
        spread_pct=0.0001,
        htf_confirmed=False,
        setup_state="STRONG_SIGNAL",
        direction=direction,
        entry_signal_type="REVERSAL",
        entry_signal_time_ms=100,
        campaign_signal_specs=[signal],
    )


def test_tc2_early_wm1_admission_supports_both_directions():
    assert MarketScanner._is_tc2_early_wm1(
        _wm1_candidate("LONG", trigger=101.0, stop=99.0), now_ms=1_000
    )
    assert MarketScanner._is_tc2_early_wm1(
        _wm1_candidate("SHORT", trigger=99.0, stop=101.0), now_ms=1_000
    )


def test_tc2_early_wm1_rejects_direction_mismatch_and_invalid_stop_geometry():
    assert not MarketScanner._is_tc2_early_wm1(
        _wm1_candidate("SHORT", trigger=101.0, stop=99.0, signal_direction="LONG"),
        now_ms=1_000,
    )
    assert not MarketScanner._is_tc2_early_wm1(
        _wm1_candidate("SHORT", trigger=101.0, stop=99.0), now_ms=1_000
    )


test_tc2_early_wm1_admission_supports_both_directions()
test_tc2_early_wm1_rejects_direction_mismatch_and_invalid_stop_geometry()

print("MARKET SCANNER TESTS: PASS")
