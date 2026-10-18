import pandas as pd

from strategy import (
    calculate_indicators,
    canonical_indicator_config_from_env,
    config_from_env,
)
from williams_signals import _latest_confirmed_fractal, extract_ntd_fractal_signal_specs


def make_frame(highs, lows=None, volumes=None, trades=None):
    if lows is None:
        lows = [float(h) - 1.0 for h in highs]
    if volumes is None:
        volumes = [1000.0] * len(highs)
    rows = []
    for i, (high, low, volume) in enumerate(zip(highs, lows, volumes)):
        close = (float(high) + float(low)) / 2.0
        row = {
            "open": close,
            "high": float(high),
            "low": float(low),
            "close": close,
            "volume": float(volume),
            "open_time_ms": 1_800_000_000_000 + i * 3_600_000,
        }
        if trades is not None:
            row["trades"] = trades[i]
        rows.append(row)
    return pd.DataFrame(rows)


def test_equal_high_plateau_fractal_waits_for_second_right_bar():
    highs = [9, 10, 11, 12, 12, 11, 10]
    lows = [8, 9, 10, 11, 10, 9, 8]
    cfg = config_from_env({})

    before_confirmation = calculate_indicators(
        make_frame(highs[:6], lows[:6]), cfg
    )
    assert not before_confirmation["fractal_up"].any()
    assert not before_confirmation["fractal_up_confirmed"].any()

    confirmed = calculate_indicators(make_frame(highs, lows), cfg)
    assert bool(confirmed["fractal_up"].iloc[4]) is True
    assert int(confirmed["fractal_up_group_start_index"].iloc[4]) == 3
    assert int(confirmed["fractal_up_confirmation_index"].iloc[4]) == 6
    assert bool(confirmed["fractal_up_confirmed"].iloc[6]) is True
    assert int(confirmed["confirmed_up_center_index"].iloc[6]) == 4
    assert int(confirmed["confirmed_up_group_start_index"].iloc[6]) == 3
    assert float(confirmed["confirmed_up_level"].iloc[6]) == 12.0


def test_equal_low_plateau_fractal_waits_for_confirmation_and_records_stop_group():
    highs = [13, 12, 11, 10, 10, 11, 12]
    lows = [12, 11, 10, 9, 9, 10, 11]
    confirmed = calculate_indicators(make_frame(highs, lows), config_from_env({}))
    assert bool(confirmed["fractal_down"].iloc[4]) is True
    assert bool(confirmed["fractal_down_confirmed"].iloc[6]) is True
    assert float(confirmed["confirmed_down_level"].iloc[6]) == 9.0


def test_profitunity_window_does_not_label_missing_warmup_as_fading():
    frame = make_frame(
        [101, 102, 103, 104],
        lows=[99, 100, 101, 102],
        volumes=[1000, 1100, 900, 1200],
    )
    ind = calculate_indicators(frame, config_from_env({}))
    assert ind["profitunity_window"].iloc[0] == "UNKNOWN"
    assert bool(ind["mfi_proxy_valid"].iloc[0]) is False
    assert ind["profitunity_window"].iloc[1] in {
        "GREEN", "FADING", "FAKE", "SQUAT", "STABLE"
    }


def test_trade_count_proxy_is_explicit_and_never_claimed_as_canonical_mfi():
    frame = make_frame(
        [101, 102, 103],
        lows=[99, 100, 101],
        volumes=[1000, 1000, 1000],
        trades=[100, 120, 90],
    )
    ind = calculate_indicators(frame, config_from_env({}))
    assert set(ind["mfi_proxy_source"]) == {"BINANCE_TRADE_COUNT_PROXY"}
    assert "mfi_proxy" in ind
    assert "mfi_proxy_valid" in ind


def test_wm3_consumers_use_actual_confirmation_and_whole_plateau_stop():
    rows = []
    highs = [9, 10, 11, 12, 12, 11, 10, 9]
    lows = [8, 9, 10, 11, 10, 9, 8, 7]
    for i, (high, low) in enumerate(zip(highs, lows)):
        rows.append({
            "open": (high + low) / 2,
            "high": float(high),
            "low": float(low),
            "close": 9.0 if i == 7 else (high + low) / 2,
            "teeth_shifted": 10.5,
            "fractal_up": i == 4,
            "fractal_down": False,
            "fractal_up_confirmed": i == 6,
            "fractal_down_confirmed": False,
            "confirmed_up_level": 12.0 if i == 6 else float("nan"),
            "confirmed_down_level": float("nan"),
            "confirmed_up_center_index": 4.0 if i == 6 else float("nan"),
            "confirmed_down_center_index": float("nan"),
            "confirmed_up_group_start_index": 3.0 if i == 6 else float("nan"),
            "confirmed_down_group_start_index": float("nan"),
            "open_time_ms": 1_800_000_000_000 + i * 3_600_000,
        })
    ind = pd.DataFrame(rows)

    found = _latest_confirmed_fractal(
        ind, side="LONG", current_teeth=10.5, current_close=9.0, tick_size=0.1
    )
    assert found is not None
    confirmation_i, center_i, trigger_base, protective, _ = found
    assert confirmation_i == 6
    assert center_i == 4
    assert trigger_base == 12.0
    assert protective == 8.0  # minimum low of five closed bars through confirmation (TC2)

    # The middle bar itself must qualify against Teeth when the fractal forms;
    # checking only the later confirmation/current Teeth is insufficient.
    rejected = ind.copy()
    rejected.loc[4, "teeth_shifted"] = 12.1
    assert _latest_confirmed_fractal(
        rejected, side="LONG", current_teeth=10.5,
        current_close=9.0, tick_size=0.1
    ) is None

    specs = extract_ntd_fractal_signal_specs(
        "BTCUSDT", ind, side="LONG", timeframe="1h", tick_size=0.1
    )
    assert len(specs) == 1
    assert specs[0].signal_bar_time_ms == ind.iloc[6]["open_time_ms"]
    assert specs[0].trigger_price == 12.1
    assert specs[0].protective_reference == 10.0


def test_alligator_decision_lines_match_forward_shifted_chart_values():
    highs = [100 + (i * 0.05) ** 2 + 1 for i in range(80)]
    lows = [h - 2.0 for h in highs]
    ind = calculate_indicators(make_frame(highs, lows), config_from_env({}))
    pd.testing.assert_series_equal(ind["jaw_current"], ind["jaw_shifted"], check_names=False)
    pd.testing.assert_series_equal(ind["teeth_current"], ind["teeth_shifted"], check_names=False)
    pd.testing.assert_series_equal(ind["lips_current"], ind["lips_shifted"], check_names=False)
    pd.testing.assert_series_equal(ind["balance_line"], ind["jaw_shifted"], check_names=False)
    pd.testing.assert_series_equal(ind["fractal_balance_line"], ind["teeth_shifted"], check_names=False)
    # Decision values must align with the line plotted at the present candle,
    # not the unshifted SMMA value whose chart coordinate lies in the future.
    assert not ind["jaw_current"].equals(ind["jaw"])


def test_balance_line_and_profitunity_macd_components_are_explicit():
    highs = [100 + (i * 0.05) ** 2 + 1 for i in range(80)]
    lows = [h - 2.0 for h in highs]
    ind = calculate_indicators(make_frame(highs, lows), config_from_env({}))
    pd.testing.assert_series_equal(
        ind["balance_line"], ind["jaw_shifted"], check_names=False
    )
    pd.testing.assert_series_equal(
        ind["fractal_balance_line"], ind["teeth_shifted"], check_names=False
    )
    pd.testing.assert_series_equal(
        ind["profitunity_macd_line"], ind["ao"], check_names=False
    )
    pd.testing.assert_series_equal(
        ind["profitunity_macd_histogram"], ind["ac"], check_names=False
    )
    pd.testing.assert_series_equal(
        ind["profitunity_macd_line"] - ind["profitunity_macd_signal"],
        ind["profitunity_macd_histogram"],
        check_names=False,
    )
    assert set(ind["balance_line_side"].unique()) <= {
        "ABOVE", "BELOW", "ON", "UNKNOWN"
    }


def test_gator_oscillator_uses_alligator_line_differences_and_is_diagnostic_only():
    highs = [100 + (i * 0.05) ** 2 + 1 for i in range(80)]
    lows = [h - 2.0 for h in highs]
    ind = calculate_indicators(make_frame(highs, lows), config_from_env({}))
    pd.testing.assert_series_equal(
        ind["gator_upper"],
        (ind["jaw_current"] - ind["teeth_current"]).abs(),
        check_names=False,
    )
    pd.testing.assert_series_equal(
        ind["gator_lower"],
        -(ind["teeth_current"] - ind["lips_current"]).abs(),
        check_names=False,
    )
    assert set(ind["gator_state"].unique()) <= {
        "UNKNOWN", "SLEEPING", "AWAKENING", "EATING", "SATIATED"
    }
    # No Gator state is promoted into the TC2 signal boolean by this layer.
    assert "gator_state" not in {"long_signal", "short_signal"}


def test_alligator_awake_has_no_arbitrary_percentage_threshold():
    highs = [100 + (i * 0.05) ** 2 + 1 for i in range(80)]
    lows = [h - 2.0 for h in highs]
    frame = make_frame(highs, lows)
    default = calculate_indicators(frame, config_from_env({}))
    altered_threshold = calculate_indicators(
        frame, config_from_env({"MIN_ALLIGATOR_SPREAD_PCT": "0.9"})
    )
    assert default["alligator_state"].tolist() == altered_threshold["alligator_state"].tolist()
    assert default["alligator_awake"].tolist() == altered_threshold["alligator_awake"].tolist()
    assert set(default["alligator_state"].unique()) <= {
        "UNKNOWN", "SLEEPING", "AWAKENING", "EATING", "SATIATED"
    }


def test_futures_canonical_indicator_contract_rejects_period_drift():
    cfg = canonical_indicator_config_from_env({})
    assert cfg["jaw"] == 13
    assert cfg["teeth"] == 8
    assert cfg["lips"] == 5
    assert (cfg["jaw_shift"], cfg["teeth_shift"], cfg["lips_shift"]) == (8, 5, 3)
    assert (cfg["ao_fast"], cfg["ao_slow"], cfg["ac_period"]) == (5, 34, 5)
    assert (cfg["fractal_left"], cfg["fractal_right"], cfg["super_ao_bars"]) == (2, 2, 3)

    import pytest
    with pytest.raises(ValueError, match="Non-canonical Williams indicator parameters"):
        canonical_indicator_config_from_env({"AO_FAST": "3"})
    with pytest.raises(ValueError, match="Non-canonical Williams indicator parameters"):
        canonical_indicator_config_from_env({"ALLIGATOR_JAW": "10"})


def test_wise_men_corrupt_checkpoint_fails_closed():
    import pytest
    from wise_men import WiseMenStateMachine

    class DB:
        def state_get(self, key):
            return "{not-json"

        def state_set(self, key, value):
            raise AssertionError("corrupt checkpoint must not be overwritten")

    with pytest.raises(RuntimeError, match="recovery/reconciliation is required"):
        WiseMenStateMachine(DB(), "BTCUSDT", "LONG")


def test_wise_men_progression_cannot_regress_or_revive_terminal_state():
    from wise_men import WiseMenPhase, WiseMenStateMachine

    class DB:
        def __init__(self):
            self.values = {}

        def state_get(self, key):
            return self.values.get(key)

        def state_set(self, key, value):
            self.values[key] = value

    db = DB()
    machine = WiseMenStateMachine(db, "BTCUSDT", "LONG")
    machine.observe("c1", wm1_triggered=True, level=101.0)
    machine.observe("c2", wm2_triggered=True, level=102.0)
    active = machine.observe("c3", wm3_triggered=True, level=103.0)
    assert active.phase == WiseMenPhase.TREND_ACTIVE

    regressed = machine.observe("c4", wm1_triggered=True, level=99.0)
    assert regressed.phase == WiseMenPhase.TREND_ACTIVE
    assert regressed.wm1_level == 101.0

    terminal = machine.observe("c5", invalidated=True)
    revived = machine.observe("c6", wm3_triggered=True, level=95.0)
    assert terminal.phase == WiseMenPhase.INVALIDATED
    assert revived.phase == WiseMenPhase.INVALIDATED
