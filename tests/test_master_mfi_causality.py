"""TC1 PDF43-45: signed comparisons, explicit proxy units, causal prefixes.

These are constructed numerical examples, NOT digitized book OHLC or fills.
"""
import itertools

import numpy as np
import pandas as pd
import pytest

from strategy import calculate_indicators, config_from_env


def frame(ranges, volumes, trades=None):
    lows = [100.0] * len(ranges)
    data = {"open": lows, "low": lows, "high": [100.0 + r for r in ranges],
            "close": lows, "volume": volumes,
            "open_time_ms": [1_800_000_000_000 + i * 3_600_000 for i in range(len(ranges))]}
    if trades is not None:
        data["trades"] = trades
    return pd.DataFrame(data)


@pytest.mark.parametrize("volume_delta,mfi_delta", list(itertools.product((-1, 0, 1), repeat=2)))
def test_all_nine_strict_mfi_volume_relationships(volume_delta, mfi_delta):
    volume = 100.0 + volume_delta * 50.0
    mfi = 0.04 + mfi_delta * 0.02
    result = calculate_indicators(frame([4.0, volume * mfi], [100.0, volume]), config_from_env({}))
    expected = {(1, 1): "GREEN", (-1, -1): "FADING", (-1, 1): "FAKE", (1, -1): "SQUAT"}
    window = expected.get((volume_delta, mfi_delta), "STABLE")
    assert result["profitunity_window"].iloc[-1] == window
    assert bool(result["squatting_bar"].iloc[-1]) == (window == "SQUAT")
    assert bool(result["mfi_proxy_valid"].iloc[-1])


@pytest.mark.parametrize("bad", [0.0, -10.0, float("inf"), -float("inf"), float("nan")])
def test_invalid_volume_cannot_create_squat_or_wave_evidence(bad):
    result = calculate_indicators(frame([4.0, 4.0, 4.0], [100.0, bad, 200.0]), config_from_env({}))
    assert result["profitunity_window"].tolist() == ["UNKNOWN"] * 3
    assert not result["mfi_proxy_valid"].any()
    assert not result["squatting_bar"].any()


def test_missing_later_trade_count_never_rewrites_past_mfi():
    source = frame([4.0, 6.0, 8.0, 4.0, 5.0, 8.0], [100, 300, 200, 400, 200, 200],
                   [20, 40, 80, np.nan, 20, 30])
    columns = ["mfi_proxy_source", "mfi_volume_proxy", "mfi_proxy", "mfi_proxy_valid",
               "profitunity_window", "squatting_bar"]
    full = calculate_indicators(source, config_from_env({}))
    for end in range(1, len(source) + 1):
        prefix = calculate_indicators(source.iloc[:end], config_from_env({}))
        pd.testing.assert_frame_equal(prefix[columns], full.iloc[:end][columns])
    assert full["mfi_proxy_source"].iloc[2] == "BINANCE_TRADE_COUNT_PROXY"
    assert full["mfi_proxy_source"].iloc[3] == "BASE_VOLUME_PROXY"
    # Never compare asset units against trade-count units in either direction.
    assert not full["mfi_proxy_valid"].iloc[3:5].any()
    assert full["mfi_proxy_valid"].iloc[5]


def test_unavailable_earlier_count_cannot_make_future_count_change_past_units():
    source = frame([4.0, 8.0, 6.0], [100.0, 200.0, 300.0], [np.nan, np.nan, 30.0])
    before = calculate_indicators(source.iloc[:2], config_from_env({}))
    after = calculate_indicators(source, config_from_env({}))
    pd.testing.assert_series_equal(before["mfi_proxy"], after["mfi_proxy"].iloc[:2])
    assert after["profitunity_window"].iloc[-1] == "UNKNOWN"


@pytest.mark.parametrize("bad_count", [0.0, -1.0, float("inf")])
def test_explicit_bad_trade_count_is_not_replaced_with_positive_asset_volume(bad_count):
    result = calculate_indicators(frame([4.0, 4.0], [100.0, 200.0], [20.0, bad_count]), config_from_env({}))
    assert result["mfi_proxy_source"].iloc[-1] == "BINANCE_TRADE_COUNT_PROXY"
    assert not result["mfi_proxy_valid"].iloc[-1]
    assert result["profitunity_window"].iloc[-1] == "UNKNOWN"


def test_ratio_overflow_is_unknown_instead_of_a_trading_window():
    result = calculate_indicators(frame([4.0, 8.0], [100.0, 1e-320]), config_from_env({}))
    assert not result["mfi_proxy_valid"].iloc[-1]
    assert result["profitunity_window"].iloc[-1] == "UNKNOWN"


def test_strict_python_windows_agree_with_existing_android_reference_fixture():
    # The Android TC1 analyser already uses strict >/<, not negated booleans.
    from williams_tc1_market_facilitation import tc1_mfi_four_window
    source = frame([4, 8], [100, 200])  # exact MFI tie, increasing volume
    rows = [{"t": int(r.open_time_ms), "o": r.open, "h": r.high, "l": r.low,
             "c": r.close, "v": r.volume} for r in source.itertuples()]
    reference = tc1_mfi_four_window(rows, "1h", rows[-1]["t"] + 3_600_000)
    actual = calculate_indicators(source, config_from_env({}))
    assert reference["window"] == "UNCHANGED_OR_TIED"
    assert actual["profitunity_window"].iloc[-1] == "STABLE"
    assert not actual["squatting_bar"].iloc[-1]
