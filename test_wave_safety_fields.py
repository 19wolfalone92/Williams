from wave_engine import MultiTimeframeWaveEngine, WaveSnapshot, DIRECTION_UP, WAVE_STATE_IMPULSE


def test_wave_snapshot_exposes_structural_safety_fields():
    s = WaveSnapshot(
        interval="1h",
        wave_degree="LOW",
        direction=DIRECTION_UP,
        phase=WAVE_STATE_IMPULSE,
        position=3,
        invalidation_price=100.0,
        magic_bullets_count=1,
        scenario_primary="IMPULSE_W3_UP",
        scenario_alternative="NONE",
    )
    payload = s.to_dict()
    assert payload["invalidation_price"] == 100.0
    assert payload["magic_bullets_count"] == 1
    assert payload["scenario_primary"] == "IMPULSE_W3_UP"


def test_magic_bullet_counter():
    count = MultiTimeframeWaveEngine._magic_bullets_count(True, "BEARISH", True, True, False)
    assert count == 4


def test_scenario_labels_keep_w5_as_context_not_veto():
    primary, alternative = MultiTimeframeWaveEngine._scenario_labels(
        5, WAVE_STATE_IMPULSE, DIRECTION_UP, 20.0, ""
    )
    assert primary == "IMPULSE_W5_UP"
    assert alternative == "NONE"


def test_scenario_labels_surface_w5_exhaustion():
    primary, alternative = MultiTimeframeWaveEngine._scenario_labels(
        5, WAVE_STATE_IMPULSE, DIRECTION_UP, 70.0, ""
    )
    assert primary == "IMPULSE_W5_UP"
    assert alternative == "W3_CONTINUATION"
