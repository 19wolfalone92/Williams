"""Exact NTD AO Twin Peaks and zero-line separation; research-only until enabled."""
import pandas as pd
import pytest

from campaign_model import SignalType
from williams_signals import extract_ntd_ao_signal_specs


def _signal(series, side):
    # Keep all price/metadata fields realistic and identical: only AO changes.
    values = list(series)
    if side == "SHORT":
        values = [-v for v in values]
    rows = []
    for i, value in enumerate(values):
        previous = values[i - 1] if i else value
        rows.append({
            "ao": value,
            "ao_green": value > previous,
            "ao_red": value < previous,
            "high": 101.0, "low": 99.0, "close": 100.0,
            "open_time_ms": (i + 1) * 3_600_000,
        })
    return extract_ntd_ao_signal_specs(
        "BTCUSDT", pd.DataFrame(rows), side=side, timeframe="1h", tick_size=0.1
    )


@pytest.mark.parametrize("side", ["LONG", "SHORT"])
def test_only_immediately_previous_confirmed_ao_trough_counts(side):
    # Previous bug incorrectly skipped -2 and used an older -5 to make
    # the later -3 seem like a valid higher second bottom.
    ao = [-1.0] * 45 + [-1, -5, -1, -2, -1, -3, -2]
    assert not any(s.signal_type == SignalType.AO_TWIN_PEAKS
                   for s in _signal(ao, side))


@pytest.mark.parametrize("side", ["LONG", "SHORT"])
def test_signal_bar_crossing_zero_is_not_twin_peaks(side):
    ao = [-1.0] * 45 + [-1, -5, -1, -3, 1]
    observed = _signal(ao, side)
    assert len(observed) == 1
    assert observed[0].signal_type == SignalType.AO_ZERO_LINE


@pytest.mark.parametrize("side", ["LONG", "SHORT"])
def test_consecutive_twin_peaks_remain_valid_with_signal_below_zero(side):
    ao = [-1.0] * 45 + [-1, -5, -1, -3, -2]
    observed = _signal(ao, side)
    assert len(observed) == 1
    assert observed[0].signal_type == SignalType.AO_TWIN_PEAKS


@pytest.mark.parametrize("side", ["LONG", "SHORT"])
def test_zero_touch_between_extrema_invalidates_twin_peaks(side):
    ao = [-1.0] * 45 + [-1, -5, -1, 0, -1, -3, -2]
    assert not any(s.signal_type == SignalType.AO_TWIN_PEAKS
                   for s in _signal(ao, side))
