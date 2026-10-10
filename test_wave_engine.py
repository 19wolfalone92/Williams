from dataclasses import replace

import pandas as pd

from wave_engine import (
    DIRECTION_DOWN,
    DIRECTION_UP,
    MultiTimeframeWaveEngine,
    Pivot,
    WaveSnapshot,
    WAVE_STATE_CORRECTION,
)


class FakeClient:
    pass


def engine(**kwargs):
    return MultiTimeframeWaveEngine(FakeClient(), **kwargs)


def pivots(*items):
    return [
        Pivot(kind=kind, price=price, center_index=i, confirmed_index=i + 2)
        for i, (kind, price) in enumerate(items)
    ]


def test_estimates_up_wave_2():
    e = engine(base_interval="1h", min_bars=140)
    position, label, confidence, *_ = e._estimate_position(
        pivots((DIRECTION_DOWN, 90.0), (DIRECTION_UP, 100.0)),
        DIRECTION_UP,
        close=96.0,
        atr=2.0,
    )
    assert position == 2
    assert label == "W2"
    assert confidence >= 40.0


def test_estimates_up_wave_3_from_confirmed_swing_skeleton():
    e = engine(base_interval="1h", min_bars=140)
    position, label, confidence, *_ = e._estimate_position(
        pivots(
            (DIRECTION_DOWN, 90.0),
            (DIRECTION_UP, 100.0),
            (DIRECTION_DOWN, 95.0),
        ),
        DIRECTION_UP,
        close=110.0,
        atr=2.0,
    )
    assert position == 3
    assert label == "W3"
    assert confidence >= 45.0


def test_estimates_up_wave_5():
    e = engine(base_interval="1h", min_bars=140)
    position, label, confidence, *_ = e._estimate_position(
        pivots(
            (DIRECTION_DOWN, 90.0),
            (DIRECTION_UP, 100.0),
            (DIRECTION_DOWN, 95.0),
            (DIRECTION_UP, 115.0),
            (DIRECTION_DOWN, 105.0),
        ),
        DIRECTION_UP,
        close=120.0,
        atr=2.0,
    )
    assert position == 5
    assert label == "W5"
    assert confidence >= 50.0


def test_completed_impulse_followed_by_new_down_leg_is_a_wave():
    e = engine(base_interval="1h", min_bars=140)
    position, label, confidence, *_ = e._estimate_position(
        pivots(
            (DIRECTION_DOWN, 90.0),
            (DIRECTION_UP, 100.0),
            (DIRECTION_DOWN, 95.0),
            (DIRECTION_UP, 115.0),
            (DIRECTION_DOWN, 105.0),
            (DIRECTION_UP, 125.0),
        ),
        DIRECTION_UP,
        close=120.0,
        atr=2.0,
    )
    assert position == 0
    assert label == "A"
    assert confidence >= 70.0


def test_nested_w3_inside_parent_w5_is_detected_and_not_a_veto():
    e = engine(base_interval="1h", intervals=["4h", "1h"])
    parent = WaveSnapshot(
        interval="4h",
        wave_degree="MEDIUM_HIGH",
        direction=DIRECTION_UP,
        phase="IMPULSE",
        position=5,
        wave_label="W5",
        confidence=80.0,
        structural_confidence=78.0,
        impulse_score=85.0,
        exhaustion_risk=35.0,
        alligator_bullish=True,
        ao=2.0,
        data_bars=200,
        data_ok=True,
    )
    child = WaveSnapshot(
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
        ao=3.0,
        data_bars=200,
        data_ok=True,
    )

    report = e._build_report({"4h": parent, "1h": child})

    assert report.nested_w3 is True
    assert report.nested_w3_parent_w5 is True
    assert report.wave_score > 50.0
    assert "4h:W5 > 1h:W3" == report.wave_path
    assert "W5 context is not a veto" in report.reason


def test_no_valid_timeframe_data_keeps_wave_score_neutral():
    e = engine(base_interval="1h", intervals=["4h", "1h"])
    bad = WaveSnapshot(
        interval="1h",
        wave_degree="MEDIUM",
        data_ok=False,
        reason="no data",
    )
    report = e._build_report({"1h": bad})
    assert report.overall_direction == "NEUTRAL"
    assert report.wave_score == 50.0
    assert report.exhaustion_risk == 0.0


def test_default_chain_for_one_hour_contains_nested_context_frames():
    e = engine(base_interval="1h")
    assert e.intervals == [
        "1M", "1w", "3d", "1d", "12h", "8h", "6h", "4h",
        "2h", "1h", "30m", "15m", "5m", "3m", "1m",
    ]


def test_short_data_is_rejected_before_wave_labeling():
    e = engine(base_interval="1h", min_bars=140)
    dates = pd.date_range("2026-01-01", periods=139, freq="h", tz="UTC")
    frame = pd.DataFrame(
        {
            "open": 100.0,
            "high": 101.0,
            "low": 99.0,
            "close": 100.0,
            "close_time": dates,
        },
        index=dates,
    )
    snap = e.build_snapshot("1h", frame)
    assert snap.data_ok is False
    assert snap.wave_label == "?"
    assert snap.data_bars == 139


def test_fractal_pivots_are_confirmed_only_after_right_window():
    e = engine(base_interval="1h", min_bars=5)
    idx = pd.date_range("2026-01-01", periods=8, freq="h", tz="UTC")
    ind = pd.DataFrame(
        {
            "fractal_up": [False, False, False, True, False, False, False, False],
            "fractal_down": [False] * 8,
            "high": [1, 2, 3, 10, 4, 3, 2, 1],
            "low": [0] * 8,
            "close": [1.0] * 8,
            "ao": [0.0] * 8,
            "ac": [0.0] * 8,
        },
        index=idx,
    )
    got = e._confirmed_pivots(ind)
    assert len(got) == 1
    assert got[0].center_index == 3
    assert got[0].confirmed_index == 5


def test_down_wave_3_is_mirrored():
    e = engine(base_interval="1h", min_bars=140)
    position, label, *_ = e._estimate_position(
        pivots(
            (DIRECTION_UP, 110.0),
            (DIRECTION_DOWN, 100.0),
            (DIRECTION_UP, 105.0),
        ),
        DIRECTION_DOWN,
        close=90.0,
        atr=2.0,
    )
    assert position == 3
    assert label == "W3"


def test_w5_exhaustion_does_not_veto_nested_w3_context():
    e = engine(base_interval="1h", intervals=["4h", "1h"])
    parent = WaveSnapshot(
        interval="4h", wave_degree="MEDIUM_HIGH", direction=DIRECTION_UP,
        phase="IMPULSE", position=5, wave_label="W5", confidence=70.0,
        structural_confidence=70.0, impulse_score=70.0, exhaustion_risk=85.0,
        alligator_bullish=True, ao=1.0, data_bars=200, data_ok=True,
    )
    child = WaveSnapshot(
        interval="1h", wave_degree="MEDIUM", direction=DIRECTION_UP,
        phase="IMPULSE", position=3, wave_label="W3", confidence=88.0,
        structural_confidence=88.0, impulse_score=92.0, exhaustion_risk=12.0,
        alligator_bullish=True, ao=3.0, data_bars=200, data_ok=True,
    )
    report = e._build_report({"4h": parent, "1h": child})
    assert report.nested_w3_parent_w5 is True
    assert report.wave_score > 50.0


def test_primary_and_alternative_count_fields_are_serializable():
    snap = WaveSnapshot(
        interval="1h", wave_degree="MEDIUM", direction=DIRECTION_UP,
        position=5, wave_label="W5", primary_count="W5",
        alternative_count="W3_ALTERNATIVE", abc_phase="",
        exhaustion_components={"ao_divergence": 30.0}, data_ok=True,
    )
    payload = snap.to_dict()
    assert payload["primary_count"] == "W5"
    assert payload["alternative_count"] == "W3_ALTERNATIVE"
    assert payload["exhaustion_components"]["ao_divergence"] == 30.0


def _wave_snapshot(interval, position):
    return WaveSnapshot(
        interval=interval,
        wave_degree="MEDIUM",
        direction=DIRECTION_UP,
        phase="IMPULSE",
        position=position,
        wave_label=f"W{position}",
        confidence=85.0,
        structural_confidence=85.0,
        impulse_score=90.0,
        exhaustion_risk=10.0,
        alligator_bullish=True,
        ao=3.0,
        data_bars=220,
        data_ok=True,
    )


def test_w3_can_be_nested_inside_parent_w1_w3_and_w5():
    e = engine(base_interval="5m", intervals=["1d", "4h", "1h", "15m", "5m"])
    for parent_position in (1, 3, 5):
        parent = _wave_snapshot("1d", parent_position)
        child = _wave_snapshot("4h", 3)
        report = e._build_report({"1d": parent, "4h": child})
        assert report.nested_w3 is True
        assert parent_position in report.nested_w3_parent_positions
        if parent_position == 5:
            assert report.nested_w3_parent_w5 is True



def test_monthly_binance_interval_is_not_collapsed_to_one_minute():
    e = engine(base_interval="1h", intervals=["1M", "1m", "1h"])
    assert "1M" in e.intervals
    assert "1m" in e.intervals
    assert e.intervals.index("1M") < e.intervals.index("1m")


def test_equal_high_fractal_confirmation_time_is_not_backdated():
    e = engine(base_interval="1h", min_bars=5)
    idx = pd.date_range("2026-01-01", periods=6, freq="h", tz="UTC")
    ind = pd.DataFrame(
        {
            "fractal_up": [False, False, True, False, False, False],
            "fractal_down": [False] * 6,
            "confirmed_up_level": [float("nan")] * 5 + [10.0],
            "confirmed_down_level": [float("nan")] * 6,
            "confirmed_up_center_index": [-1, -1, -1, -1, -1, 2],
            "confirmed_down_center_index": [-1] * 6,
            "high": [7.0, 8.0, 10.0, 9.0, 10.0, 8.0],
            "low": [5.0, 5.5, 6.0, 6.2, 6.5, 6.7],
            "close": [6.0, 7.0, 9.0, 8.0, 9.0, 7.5],
            "ao": [0.0] * 6,
            "ac": [0.0] * 6,
        },
        index=idx,
    )
    got = e._confirmed_pivots(ind)
    assert len(got) == 1
    assert got[0].kind == DIRECTION_UP
    assert got[0].center_index == 2
    # Tie at index 4 means the second qualifying lower high arrives at index 5.
    assert got[0].confirmed_index == 5


def test_double_direction_fractal_is_not_promoted_to_ordered_wave_pivots():
    e = engine(base_interval="1h", min_bars=5)
    idx = pd.date_range("2026-01-01", periods=8, freq="h", tz="UTC")
    ind = pd.DataFrame(
        {
            "fractal_up": [False, False, True, False, False, False, False, False],
            "fractal_down": [False, False, True, False, False, False, False, False],
            "confirmed_up_level": [float("nan")] * 5 + [10.0, float("nan"), float("nan")],
            "confirmed_down_level": [float("nan")] * 5 + [5.0, float("nan"), float("nan")],
            "confirmed_up_center_index": [-1, -1, -1, -1, -1, 2, -1, -1],
            "confirmed_down_center_index": [-1, -1, -1, -1, -1, 2, -1, -1],
            "high": [8.0, 9.0, 10.0, 9.0, 10.0, 8.0, 7.0, 6.0],
            "low": [6.0, 5.5, 5.0, 6.0, 5.0, 6.0, 6.5, 5.5],
            "close": [7.0, 8.0, 8.0, 7.0, 8.0, 7.0, 6.8, 5.8],
            "ao": [0.0] * 8,
            "ac": [0.0] * 8,
        },
        index=idx,
    )
    assert e._confirmed_pivots(ind) == []
