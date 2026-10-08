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


def test_point_zero_requires_and_reports_all_five_magic_bullets():
    e = engine(base_interval="1h", min_bars=140)
    data = pd.DataFrame({
        "high": [91, 101, 96, 121, 111, 129, 130, 131, 130],
        "low": [89, 99, 94, 119, 109, 127, 128, 129, 128],
        "squatting_bar": [False, False, False, False, False, True, False, False, False],
        "ao_green": [False, False, False, True, False, False, False, True, True],
        "ao_red": [False, True, False, False, True, False, False, False, False],
    })
    ps = [
        Pivot(DIRECTION_DOWN, 90, 0, 2, 0.0),
        Pivot(DIRECTION_UP, 100, 1, 3, 10.0),
        Pivot(DIRECTION_DOWN, 95, 2, 4, -4.0),
        Pivot(DIRECTION_UP, 120, 3, 5, 20.0),
        Pivot(DIRECTION_DOWN, 110, 4, 6, -5.0),
        Pivot(DIRECTION_UP, 130, 5, 7, 12.0),
    ]
    bullets, count, reason = e._point_zero_bullets(data, ps, DIRECTION_UP)
    assert count == 5
    assert all(bullets.values())
    assert "Point Zero bullets 5/5" in reason


def test_point_zero_rejects_non_w3_w5_divergence():
    e = engine(base_interval="1h", min_bars=140)
    data = pd.DataFrame({
        "high": [91, 101, 96, 121, 111, 129, 130, 131, 130],
        "low": [89, 99, 94, 119, 109, 127, 128, 129, 128],
        "squatting_bar": [False] * 9,
        "ao_green": [False, False, False, True, False, False, False, True, True],
        "ao_red": [False, True, False, False, True, False, False, False, False],
    })
    ps = [
        Pivot(DIRECTION_DOWN, 90, 0, 2, 0.0),
        Pivot(DIRECTION_UP, 100, 1, 3, 30.0),
        Pivot(DIRECTION_DOWN, 95, 2, 4, -4.0),
        Pivot(DIRECTION_UP, 120, 3, 5, 20.0),
        Pivot(DIRECTION_DOWN, 110, 4, 6, -5.0),
        Pivot(DIRECTION_UP, 130, 5, 7, 25.0),
    ]
    bullets, count, _ = e._point_zero_bullets(data, ps, DIRECTION_UP)
    assert bullets["divergence"] is False
    assert count < 5
