from types import SimpleNamespace

from hypothesis_engine import build_hypotheses


def test_hypotheses_are_relative_and_sum_to_one():
    snapshot = SimpleNamespace(
        primary_count="W3",
        alternative_count="W5",
        abc_phase="",
        confidence=80,
        structural_confidence=80,
        exhaustion_risk=10,
        phase="IMPULSE",
        invalidation_price=90,
        nested_w3=True,
        wave_label="W3",
    )
    summary = build_hypotheses("BTCUSDT", "1h", snapshot, bullish=True, bearish=False)
    assert summary.hypotheses
    assert abs(sum(h.probability for h in summary.hypotheses) - 1.0) < 1e-6
    assert 0.0 <= summary.entropy <= 1.0
    assert summary.primary.label == "W3"


def test_uncertain_when_hypotheses_are_close():
    snapshot = SimpleNamespace(
        primary_count="W3",
        alternative_count="W5",
        abc_phase="ABC",
        confidence=50,
        structural_confidence=50,
        exhaustion_risk=50,
        phase="TRANSITION",
        invalidation_price=90,
        nested_w3=False,
        wave_label="W3",
    )
    summary = build_hypotheses("BTCUSDT", "1h", snapshot, bullish=True, bearish=False)
    assert summary.decision == "UNCERTAIN"
