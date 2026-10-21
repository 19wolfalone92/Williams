"""Missing candle extremes are unknown evidence, never an untouched trigger."""
import pandas as pd
import pytest

from williams_signals import _latest_confirmed_fractal, _trigger_still_pending


@pytest.mark.parametrize("side,column", [("BUY", "high"), ("SELL", "low")])
@pytest.mark.parametrize("missing", [float("nan"), float("inf"), "unavailable", 0.0, -1.0])
def test_unknown_extreme_after_signal_cannot_authorize_a_stop_entry(side, column, missing):
    frame = pd.DataFrame({"high": [101.0, 104.0, 104.5],
                          "low": [99.0, 96.0, 96.5], "close": [100.0, 100.0, 100.0]})
    trigger = 105.0 if side == "BUY" else 95.0
    assert _trigger_still_pending(frame, 0, side=side, trigger_price=trigger)
    frame[column] = frame[column].astype(object)
    frame.loc[1, column] = missing
    assert not _trigger_still_pending(frame, 0, side=side, trigger_price=trigger)


@pytest.mark.parametrize("side", ["LONG", "SHORT"])
def test_unknown_extreme_after_fractal_confirmation_cannot_revive_wm3(side):
    long = side == "LONG"
    frame = pd.DataFrame({
        "high": [99.0, 100.0, 104.0, 102.0, 101.0, 100.0],
        "low": [96.0, 97.0, 98.0, 97.0, 96.0, 95.0],
        "close": [98.0, 99.0, 100.0, 99.0, 99.0, 98.0],
        "teeth_current": [100.0] * 6,
        "fractal_up": [False, False, True, False, False, False],
        "fractal_down": [False, False, True, False, False, False],
        "confirmed_up_level": [float("nan")] * 4 + [104.0, float("nan")],
        "confirmed_down_level": [float("nan")] * 4 + [96.0, float("nan")],
    })
    if not long:
        # Mirror the complete candle, preserving the confirmation geometry.
        frame["high"], frame["low"] = 200.0 - frame["low"], 200.0 - frame["high"]
        frame["close"] = 200.0 - frame["close"]
    kwargs = dict(side=side, current_teeth=100.0, current_close=98.0 if long else 102.0,
                  tick_size=0.1)
    assert _latest_confirmed_fractal(frame, **kwargs) is not None
    missing_stop = frame.copy()
    missing_stop.loc[1, "low" if long else "high"] = float("nan")
    assert _latest_confirmed_fractal(missing_stop, **kwargs) is None
    frame.loc[5, "high" if long else "low"] = float("nan")
    assert _latest_confirmed_fractal(frame, **kwargs) is None
