"""Regression tests for causal Williams fractal confirmation.

Policy under test:
* left-side extrema must be strictly lower/higher than the candidate;
* on the right, equal extremes do not count as qualifying bars and do not
  invalidate the candidate;
* a strictly more extreme right-side high/low invalidates the candidate;
* confirmation is emitted on the row that completes the required count, never
  on the historical centre row.
"""
import numpy as np
import pandas as pd

from strategy import calculate_indicators, config_from_env


def _bars(highs, lows=None):
    if lows is None:
        lows = [float(h) - 5.0 for h in highs]
    highs = np.asarray(highs, dtype=float)
    lows = np.asarray(lows, dtype=float)
    close = (highs + lows) / 2.0
    return pd.DataFrame({
        "open": close,
        "high": highs,
        "low": lows,
        "close": close,
        "volume": np.full(len(highs), 100.0),
    })


def _calculate(df):
    return calculate_indicators(df, config_from_env({}))


def test_equal_right_high_extends_confirmation_instead_of_invalidating():
    highs = [10.0] * 20
    highs[8] = 12.0
    highs[10] = 20.0
    highs[11] = 20.0  # equal neighbouring extreme: neither count nor cancel
    highs[12] = 19.0
    highs[13] = 18.0

    result = _calculate(_bars(highs))

    assert not bool(result.loc[10, "fractal_up"])
    assert not bool(result.loc[11, "fractal_up"])
    assert not bool(result.loc[12, "fractal_up"])
    assert bool(result.loc[13, "fractal_up"])
    assert result.loc[13, "confirmed_up_level"] == 20.0
    assert result.loc[13, "confirmed_up_center_index"] == 10


def test_strictly_higher_right_high_cancels_old_candidate():
    highs = [10.0] * 20
    highs[8] = 12.0
    highs[10] = 20.0
    highs[11] = 21.0  # a strictly higher high cancels centre 10
    highs[12] = 19.0
    highs[13] = 18.0
    result = _calculate(_bars(highs))

    confirmed_centres = result.loc[
        result["fractal_up"], "confirmed_up_center_index"
    ].tolist()
    assert 10 not in confirmed_centres


def test_equal_right_low_extends_confirmation_instead_of_invalidating():
    lows = [10.0] * 20
    lows[8] = 8.0
    lows[10] = 2.0
    lows[11] = 2.0  # equal neighbouring extreme
    lows[12] = 3.0
    lows[13] = 4.0

    highs = [value + 5.0 for value in lows]
    result = _calculate(_bars(highs, lows))

    assert not bool(result.loc[10, "fractal_down"])
    assert not bool(result.loc[11, "fractal_down"])
    assert not bool(result.loc[12, "fractal_down"])
    assert bool(result.loc[13, "fractal_down"])
    assert result.loc[13, "confirmed_down_level"] == 2.0
    assert result.loc[13, "confirmed_down_center_index"] == 10


def test_strictly_lower_right_low_cancels_old_candidate():
    lows = [10.0] * 20
    lows[8] = 8.0
    lows[10] = 2.0
    lows[11] = 1.0  # a strictly lower low cancels centre 10
    lows[12] = 3.0
    lows[13] = 4.0

    highs = [value + 5.0 for value in lows]
    result = _calculate(_bars(highs, lows))

    confirmed_centres = result.loc[
        result["fractal_down"], "confirmed_down_center_index"
    ].tolist()
    assert 10 not in confirmed_centres


def test_fractal_event_is_not_backdated_to_candidate_centre():
    highs = [10.0] * 20
    highs[8] = 12.0
    highs[10] = 20.0
    highs[11] = 19.0
    highs[12] = 18.0

    full = _bars(highs)
    prefix = _calculate(full.iloc[:13].copy())
    complete = _calculate(full)

    assert not bool(complete.loc[10, "fractal_up"])
    assert bool(prefix.loc[12, "fractal_up"])
    assert bool(complete.loc[12, "fractal_up"])
    assert prefix.loc[12, "confirmed_up_level"] == complete.loc[12, "confirmed_up_level"] == 20.0


def test_missing_right_extreme_does_not_confirm_candidate():
    highs = [10.0] * 20
    highs[8] = 12.0
    highs[10] = 20.0
    highs[11] = np.nan
    highs[12] = 19.0
    highs[13] = 18.0

    result = _calculate(_bars(highs))
    confirmed_centres = result.loc[
        result["fractal_up"], "confirmed_up_center_index"
    ].tolist()
    assert 10 not in confirmed_centres
