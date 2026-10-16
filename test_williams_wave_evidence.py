from dataclasses import dataclass

from williams_wave_evidence import (
    abc_evidence, ao_divergence_evidence, collect_visual_wave_evidence, impulse_evidence
)


@dataclass
class Pivot:
    kind: str
    price: float
    center_index: int
    confirmed_index: int


def pivots(prices, direction="UP"):
    start = "DOWN" if direction == "UP" else "UP"
    return [Pivot(start if i % 2 == 0 else direction, float(price), i * 5 + 5, i * 5 + 7)
            for i, price in enumerate(prices)]


def test_impulse_skeleton_and_mirrored_short():
    up = impulse_evidence(pivots([100, 110, 105, 125, 115, 130]), "UP")
    assert up["model"] == "IMPULSE_CANDIDATE"
    assert not up["wave_4_overlaps_wave_1"]
    assert not up["is_entry_signal"]
    down = impulse_evidence(pivots([130, 120, 125, 105, 115, 100], "DOWN"), "DOWN")
    assert down["model"] == "IMPULSE_CANDIDATE"


def test_invalid_second_and_third_and_w3_shortest():
    assert impulse_evidence(pivots([100, 110, 99, 125, 115, 130]), "UP")["status"] == "INVALID"
    assert impulse_evidence(pivots([100, 110, 105, 109, 107, 120]), "UP")["status"] == "INVALID"
    assert impulse_evidence(pivots([100, 120, 115, 122, 121, 160]), "UP")["status"] == "INVALID"


def test_wave4_overlap_diagonal_alternative_and_truncation():
    diag = impulse_evidence(pivots([100, 110, 105, 125, 108, 130]), "UP")
    assert diag["model"] == "DIAGONAL_CANDIDATE"
    assert diag["status"] == "ALTERNATIVE"
    truncated = impulse_evidence(pivots([100, 110, 105, 125, 115, 123]), "UP")
    assert truncated["model"] == "IMPULSE_CANDIDATE"
    assert truncated["wave_5_truncated"]


def test_abc_is_not_asserted_as_complete_5_3_5():
    abc = abc_evidence(pivots([120, 100, 112, 95], "DOWN"))
    assert abc["status"] == "ALTERNATIVE"
    assert abc["model"] == "ABC_UNRESOLVED"
    assert "ZIGZAG" in abc["possible_models"]


def test_ao_peak_can_precede_price_high_without_future_peek():
    pts = pivots([100, 110, 104, 112], "UP")
    ao = [0.0] * 30
    ao[7] = 4.2  # strongest first rally AO is before its price high at 10
    ao[9] = 2.2
    ao[17] = 2.4  # weaker second AO peak before new higher high at 20
    result = ao_divergence_evidence(pts, ao, "UP")
    assert result["status"] == "DETECTED"
    assert result["divergence"] == "BEARISH"
    assert result["ao_leg_extremes"] == [4.2, 2.4]
    assert result["confirmed_through_index"] == 22


def test_bullish_divergence_and_non_divergence():
    pts = pivots([120, 110, 115, 105], "DOWN")
    ao = [0.0] * 30
    ao[7] = -5.0
    ao[17] = -2.0
    result = ao_divergence_evidence(pts, ao, "DOWN")
    assert result["divergence"] == "BULLISH"
    assert result["strength"] == "CLEAR_ZERO_SIDE"
    ao[17] = -6.0
    result = ao_divergence_evidence(pts, ao, "DOWN")
    assert result["status"] == "NOT_DETECTED"


def test_missing_bars_never_forces_signals_or_future_confirmation():
    pts = pivots([100, 110, 104, 112], "UP")
    evidence = collect_visual_wave_evidence(pts, [0.0] * 15, "UP")
    assert evidence["ao_divergence"]["status"] == "INSUFFICIENT_AO"
    assert evidence["impulse"]["status"] == "INCOMPLETE"
    assert evidence["can_place_orders"] is False


def test_bearish_divergence_persists_when_current_direction_turns_down():
    pts = pivots([100, 110, 104, 112], "UP")
    ao = [0.0] * 40
    ao[7], ao[17] = 4.2, 2.4
    evidence = collect_visual_wave_evidence(pts, ao, "DOWN")
    assert evidence["ao_divergence"]["divergence"] == "NONE"
    assert evidence["ao_divergence_bearish"]["divergence"] == "BEARISH"
    assert evidence["impulse_up"]["status"] == "INCOMPLETE"
    assert evidence["can_place_orders"] is False
