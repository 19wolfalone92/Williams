"""Tests of the live scanner's causal directional scoring integration."""
from types import SimpleNamespace

import pandas as pd
import pytest

import futures_williams_runtime as fwr
from campaign_model import SignalRole, SignalSpec, SignalType
from futures_williams_runtime import FuturesWilliamsScanner, FuturesCandidate
from williams_directional_context import evaluate_direction


def snapshot(*, direction="UP", position=3, phase="IMPULSE", divergence=None,
             count=200, data_ok=True, exhaustion=0.0):
    visual = {
        "impulse_up": {"model": "IMPULSE_CANDIDATE"},
        "impulse_down": {"model": "UNCLASSIFIED"},
        "impulse_correction_up": {"stage": "B_PIVOT_CONFIRMED", "status": "STRUCTURAL_HYPOTHESIS",
                                  "b_retracement_of_a": 0.26},
        "ao_divergence_bearish": divergence or {"status": "NOT_DETECTED"},
        "ao_divergence_bullish": {"status": "NOT_DETECTED"},
    }
    return SimpleNamespace(
        data_ok=data_ok, data_bars=count, direction=direction, phase=phase,
        wave_label=f"W{position}", primary_count=f"W{position}",
        alternative_count="W5_ALTERNATIVE", position=position,
        exhaustion_risk=exhaustion, structure={"visual_wave_evidence": visual},
        point_zero_confirmed=False, point_zero_score=0,
    )


def test_long_short_receive_opposite_bounded_structural_scores():
    snap = snapshot()
    indicator = {"bullish_alligator": True, "bearish_alligator": False,
                 "ao": 4.0, "ac": 2.0}
    long = evaluate_direction(side="LONG", snapshot=snap, last_indicator=indicator,
                              closed_bar_count=200, htf_confirmed=True)
    short = evaluate_direction(side="SHORT", snapshot=snap, last_indicator=indicator,
                               closed_bar_count=200, htf_confirmed=False)
    assert long.score_adjustment > 0
    assert short.score_adjustment < 0
    assert long.bias == "LONG"
    assert short.bias == "LONG"  # contrarian to the proposed SHORT setup
    assert long.correction_stage == "B_PIVOT_CONFIRMED"
    assert long.calibration_status == "HEURISTIC_UNCALIBRATED"
    assert long.can_place_orders is False
    assert long.can_create_entry_signal is False
    assert abs(long.score_adjustment) <= 8


def test_mirrored_bearish_structure_supports_short():
    snap = snapshot(direction="DOWN", position=3)
    indicator = {"bearish_alligator": True, "bullish_alligator": False,
                 "ao": -4.0, "ac": -2.0}
    short = evaluate_direction(side="SHORT", snapshot=snap, last_indicator=indicator,
                               closed_bar_count=200, htf_confirmed=True)
    long = evaluate_direction(side="LONG", snapshot=snap, last_indicator=indicator,
                              closed_bar_count=200, htf_confirmed=False)
    assert short.bias == "SHORT"
    assert short.score_adjustment > 0
    assert long.score_adjustment < 0


def test_fresh_divergence_is_not_an_order_or_forced_opposite_trend():
    divergence = {"status": "DETECTED", "confirmed_through_index": 193}
    snap = snapshot(divergence=divergence)
    indicator = {"bullish_alligator": True, "bearish_alligator": False,
                 "ao": 3.0, "ac": -0.5}
    short = evaluate_direction(side="SHORT", snapshot=snap, last_indicator=indicator,
                               closed_bar_count=200, htf_confirmed=False)
    assert short.ao_divergence == "ALIGNED"
    assert "RECENT_AO_DIVERGENCE_FAVORS_REVERSAL_SIDE_NOT_AN_ENTRY" in short.reasons
    assert short.can_place_orders is False
    # Equal price highs do not automatically satisfy the structural module's
    # strict higher-high divergence condition; this test supplies a detected
    # and already-confirmed divergence, not a synthetic price alert.
    snap.structure["visual_wave_evidence"]["ao_divergence_bearish"]["confirmed_through_index"] = 100
    old = evaluate_direction(side="SHORT", snapshot=snap, last_indicator=indicator,
                             closed_bar_count=200, htf_confirmed=False)
    assert old.ao_divergence == "NONE"


def test_correction_ratios_not_hard_gate_or_directional_predictor():
    snap = snapshot()
    ind = {"bullish_alligator": True, "ao": 2, "ac": 1}
    first = evaluate_direction(side="LONG", snapshot=snap, last_indicator=ind,
                               closed_bar_count=200, htf_confirmed=True)
    snap.structure["visual_wave_evidence"]["impulse_correction_up"]["b_retracement_of_a"] = 0.8
    second = evaluate_direction(side="LONG", snapshot=snap, last_indicator=ind,
                                closed_bar_count=200, htf_confirmed=True)
    assert first.score_adjustment == second.score_adjustment
    assert second.correction_stage == "B_PIVOT_CONFIRMED"


def test_data_missing_and_unconfirmed_evidence_cannot_suggest_order():
    ind = {"bullish_alligator": True, "ao": 5, "ac": 5}
    for snap in (None, snapshot(count=100, data_ok=False), SimpleNamespace(data_ok=True, structure={})):
        outcome = evaluate_direction(side="LONG", snapshot=snap, last_indicator=ind,
                                     closed_bar_count=100, htf_confirmed=True)
        assert outcome.score_adjustment == 0
        assert outcome.bias == "WAIT"
        assert outcome.can_place_orders is False


def test_wave5_caution_is_small_penalty_not_block():
    snap = snapshot(direction="UP", position=5, exhaustion=95)
    ind = {"bullish_alligator": True, "ao": 4, "ac": 1}
    result = evaluate_direction(side="LONG", snapshot=snap, last_indicator=ind,
                                closed_bar_count=200, htf_confirmed=True)
    assert "ESTIMATED_W5_EXHAUSTION_CAUTION" in result.reasons
    assert result.score_adjustment > 0


def test_futures_scanner_initializes_its_wave_dependency_without_network():
    class Client:
        pass
    scanner = FuturesWilliamsScanner(Client(), interval="1h")
    assert scanner.wave_engine.client is scanner.client
    assert scanner.wave_engine.base_interval == "1h"
    assert scanner.latest_directional_view == {}


def test_scanner_ranks_only_real_long_and_short_williams_signals(monkeypatch):
    class Client:
        def exchange_info(self, symbol):
            return {"symbols": [{"filters": [
                {"filterType": "PRICE_FILTER", "tickSize": "0.01"}
            ]}]}

    scanner = FuturesWilliamsScanner(Client(), interval="1h")
    series = pd.DataFrame({
        "high": [100 + (i % 5) for i in range(221)],
        "low": [98 + (i % 5) for i in range(221)],
        "close": [99 + (i % 5) for i in range(221)],
        "volume": [1000] * 221,
    })
    monkeypatch.setattr(fwr, "fetch_klines", lambda *a, **k: series)
    monkeypatch.setattr(fwr, "calculate_indicators", lambda df, cfg: df.assign(
        bullish_alligator=True, bearish_alligator=False, ao=3.0, ac=1.0
    ))
    scanner.wave_engine.build_snapshot = lambda *a, **k: snapshot()
    monkeypatch.setattr(scanner, "_spread", lambda *a: 0.0001)
    monkeypatch.setattr(scanner, "_htf", lambda *a: (True, False))
    monkeypatch.setenv("ALLOW_LONG", "true")
    monkeypatch.setenv("ALLOW_SHORT", "true")
    monkeypatch.setenv("WILLIAMS_SIGNAL_PROFILE", "TC2_THREE_WISE_MEN")

    def signal(side):
        return SignalSpec.new(
            symbol="BTCUSDT", side=side, signal_type=SignalType.FRACTAL,
            role=SignalRole.ENTRY, timeframe="1h", signal_bar_time_ms=100,
            trigger_price=(106 if side == "BUY" else 97),
            protective_reference=(96 if side == "BUY" else 107),
        )

    monkeypatch.setattr(fwr, "extract_long_signal_specs", lambda *a, **k: [signal("BUY")])
    monkeypatch.setattr(fwr, "extract_short_signal_specs", lambda *a, **k: [signal("SELL")])
    results = scanner.analyse("BTCUSDT")
    assert len(results) == 2
    by_side = {c.side: c for c in results}
    assert by_side["LONG"].score > by_side["SHORT"].score
    assert by_side["LONG"].directional_context["bias"] == "LONG"
    assert by_side["SHORT"].directional_context["can_place_orders"] is False
    assert by_side["SHORT"].htf_confirmed is False
    assert "directional_context" in by_side["LONG"].to_dict()
    assert set(scanner.latest_directional_view["BTCUSDT"]) == {"LONG", "SHORT"}


def test_scanner_williams_signals_absent_never_creates_orders(monkeypatch):
    class Client:
        def exchange_info(self, symbol):
            return {"symbols": [{"filters": [
                {"filterType": "PRICE_FILTER", "tickSize": "0.01"}]}]}
    scanner = FuturesWilliamsScanner(Client(), interval="1h")
    frame = pd.DataFrame({"high": [102.0]*221, "low": [98.0]*221,
                          "close": [100.0]*221, "volume": [1000]*221})
    monkeypatch.setattr(fwr, "fetch_klines", lambda *a, **k: frame)
    monkeypatch.setattr(fwr, "calculate_indicators", lambda df, cfg: df.assign(
        bullish_alligator=True, bearish_alligator=False, ao=3.0, ac=1.0))
    scanner.wave_engine.build_snapshot = lambda *a, **k: snapshot()
    monkeypatch.setattr(scanner, "_spread", lambda *a: 0.0001)
    monkeypatch.setattr(scanner, "_htf", lambda *a: (True, False))
    monkeypatch.setattr(fwr, "extract_long_signal_specs", lambda *a, **k: [])
    monkeypatch.setattr(fwr, "extract_short_signal_specs", lambda *a, **k: [])
    assert scanner.analyse("BTCUSDT") == []
    assert scanner.latest_directional_view["BTCUSDT"]["LONG"]["can_create_entry_signal"] is False


def test_unknown_side_rejected():
    with pytest.raises(ValueError, match="LONG or SHORT"):
        evaluate_direction(side="BUY", snapshot=snapshot(), last_indicator={},
                           closed_bar_count=200, htf_confirmed=True)
