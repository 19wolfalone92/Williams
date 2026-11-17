"""TC2 EN157: the third AO change fixes the original price/STOP/time anchor.

Constructed chronological candles; no book-chart OHLC or exchange fills.
"""
import math

import pandas as pd
import pytest

from campaign_model import SignalType
from williams_signals import (
    _latest_super_ao,
    _tc2_ao_streak_price_proof,
    extract_long_signal_specs,
    extract_short_signal_specs,
)


def _frame(side):
    ao = [math.nan] * 33 + list(range(7))
    highs = [400.0 - i * 3 for i in range(40)]
    lows = [80.0 + i for i in range(40)]
    if side == "SHORT":
        ao = [-x for x in ao]
        highs, lows = [500.0 - x for x in lows], [500.0 - x for x in highs]
    middle = [(h + l) / 2 for h, l in zip(highs, lows)]
    return pd.DataFrame({
        "open_time_ms": [1_795_000_000_000 + i * 3_600_000 for i in range(40)],
        "open": middle, "close": middle, "high": highs, "low": lows,
        "ao": ao, "ao_green_streak": [0] * 33 + list(range(7)),
        "ao_red_streak": [0] * 33 + list(range(7)),
        "teeth_current": middle,
    })


@pytest.mark.parametrize("side", ["LONG", "SHORT"])
@pytest.mark.parametrize("incorrect_count", [3, 4, 5, 3.5])
def test_underreported_or_fractional_cache_cannot_move_original_third_bar(side, incorrect_count):
    frame = _frame(side)
    column = "ao_green_streak" if side == "LONG" else "ao_red_streak"
    frame[column] = frame[column].astype(float)
    frame.loc[39, column] = incorrect_count
    result = _latest_super_ao(frame, side=side)
    assert result is not None
    assert result[0] == 36
    assert result[1] == frame.iloc[36]["high" if side == "LONG" else "low"]
    assert result[2] == frame.iloc[36]["low" if side == "LONG" else "high"]


@pytest.mark.parametrize("side", ["LONG", "SHORT"])
def test_three_rising_pairs_do_not_prove_that_a_later_bar_was_the_third(side):
    frame = _frame(side)
    assert _tc2_ao_streak_price_proof(frame, 36, 39, side)
    # These each contain at least three genuine changes, but their claimed
    # start occurs inside the same earlier run. They are not the third bar.
    assert not _tc2_ao_streak_price_proof(frame, 37, 39, side)
    assert not _tc2_ao_streak_price_proof(frame, 38, 39, side)
    assert not _tc2_ao_streak_price_proof(frame, 39, 39, side)


@pytest.mark.parametrize("side", ["LONG", "SHORT"])
@pytest.mark.parametrize("bad", [math.inf, -math.inf, math.nan, "1e9999", 3.5])
def test_unusable_cache_cannot_crash_scanner_or_invent_an_ordinal(side, bad):
    frame = _frame(side)
    frame["ao_green_streak" if side == "LONG" else "ao_red_streak"] = bad
    assert _latest_super_ao(frame, side=side) is None


@pytest.mark.parametrize("side", ["LONG", "SHORT"])
def test_actual_signal_keeps_original_tick_stop_identity_and_expiry_after_bad_cache(side):
    frame = _frame(side)
    frame.loc[39, "ao_green_streak" if side == "LONG" else "ao_red_streak"] = 4
    extract = extract_long_signal_specs if side == "LONG" else extract_short_signal_specs
    signals = [s for s in extract("BTCUSDT", frame, timeframe="1h", tick_size=0.1)
               if s.signal_type == SignalType.SUPER_AO]
    assert len(signals) == 1
    signal = signals[0]
    original = frame.iloc[36]
    assert signal.source_candle_index == 36
    assert signal.signal_bar_time_ms == original["open_time_ms"]
    assert signal.expires_at_ms == original["open_time_ms"] + 20 * 3_600_000
    if side == "LONG":
        assert signal.trigger_price == pytest.approx(original["high"] + 0.1)
        assert signal.protective_reference == pytest.approx(original["low"] - 0.1)
    else:
        assert signal.trigger_price == pytest.approx(original["low"] - 0.1)
        assert signal.protective_reference == pytest.approx(original["high"] + 0.1)
