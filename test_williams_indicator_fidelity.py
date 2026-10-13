import pandas as pd

from strategy import calculate_indicators, config_from_env


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
