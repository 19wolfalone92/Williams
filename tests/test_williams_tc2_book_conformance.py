"""Book-derived invariants: TC2 ch.9 (WM1) and TC1 MFI windows.

Synthetic closed OHLC fixtures; these tests do not claim to cover wave reading,
exchange fills, or every image/case in the Williams books.
"""
import pandas as pd

from strategy import calculate_indicators, canonical_indicator_config_from_env


def _frame(signal_high, signal_low, signal_close):
    rows = []
    for i in range(80):
        high, low, close = (101.0, 99.0, 100.0)
        if i == 70:
            high, low, close = signal_high, signal_low, signal_close
        rows.append({
            "open": 100.0, "high": high, "low": low, "close": close,
            "volume": 1000.0, "open_time_ms": 1800000000000 + i * 3600000,
        })
    return calculate_indicators(pd.DataFrame(rows), canonical_indicator_config_from_env({}))


def test_bullish_wm1_needs_whole_bar_below_mouth_and_red_ao():
    valid = _frame(98.8, 96.0, 98.0)
    row = valid.iloc[70]
    assert bool(row["ao_red"])
    assert row["high"] < min(row["jaw_current"], row["teeth_current"], row["lips_current"])
    assert bool(row["bullish_reversal_bar"])

    touching_mouth = _frame(103.0, 96.0, 102.0)
    touching = touching_mouth.iloc[70]
    assert bool(touching["ao_red"])
    assert not bool(touching["bullish_reversal_bar"])


def test_bearish_wm1_is_not_accidentally_classified_as_long():
    valid = _frame(104.0, 101.2, 102.0)
    row = valid.iloc[70]
    assert bool(row["ao_green"])
    assert row["low"] > max(row["jaw_current"], row["teeth_current"], row["lips_current"])
    assert bool(row["bearish_reversal_bar"])
    assert not bool(row["bullish_reversal_bar"])

    mouth_overlap = _frame(104.0, 99.0, 100.0)
    overlap = mouth_overlap.iloc[70]
    assert bool(overlap["ao_green"])
    assert not bool(overlap["bearish_reversal_bar"])


def test_equal_mfi_with_more_volume_is_not_squat():
    # (H-L)/volume is 0.002 on both bars: MFI is flat, NOT falling.
    rows = pd.DataFrame([
        {"open": 100., "high": 101., "low": 99., "close": 100., "volume": 1000.},
        {"open": 100., "high": 102., "low": 98., "close": 100., "volume": 2000.},
    ])
    calculated = calculate_indicators(rows, canonical_indicator_config_from_env({}))
    assert bool(calculated["volume_up"].iloc[1])
    assert not bool(calculated["mfi_up"].iloc[1])
    assert not bool(calculated["mfi_down"].iloc[1])
    assert calculated["profitunity_window"].iloc[1] == "STABLE"
    assert not bool(calculated["squatting_bar"].iloc[1])
