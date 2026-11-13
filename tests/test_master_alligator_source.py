"""TC2 EN PDF133-135/245-247. Constructed numbers, not book-chart OHLC."""
import numpy as np
import pandas as pd
import pytest

from strategy import smma, calculate_indicators, config_from_env


@pytest.mark.parametrize("period", [5, 8, 13])
@pytest.mark.parametrize("bad", [float("nan"), float("inf"), -float("inf")])
def test_incomplete_sma_seed_never_becomes_a_shorter_average(period, bad):
    for missing_at in range(period):
        values = pd.Series([100.0] * (period + 5))
        values.iloc[missing_at] = bad
        assert smma(values, period).isna().all()


@pytest.mark.parametrize("period", [5, 8, 13])
def test_recursive_history_stops_at_a_missing_price_without_rewriting_prefix(period):
    values = pd.Series([100.0] * period + [110.0, 120.0, np.nan, 130.0])
    result = smma(values, period)
    assert result.iloc[:period - 1].isna().all()
    assert result.iloc[period - 1] == 100.0
    assert result.iloc[period] == pytest.approx((100 * (period - 1) + 110) / period)
    assert result.iloc[-2:].isna().all()
    pd.testing.assert_series_equal(result.iloc[:-2], smma(values.iloc[:-2], period))


def test_alligator_shifts_and_median_input_follow_source_code_without_future_prices():
    mid = pd.Series([100.0 + i * i / 10 for i in range(60)])
    frame = pd.DataFrame({"open": mid + 1, "high": mid + 4,
                          "low": mid - 4, "close": mid + 2, "volume": 100.0})
    result = calculate_indicators(frame, config_from_env({}))
    for name, period, shift in [("jaw", 13, 8), ("teeth", 8, 5), ("lips", 5, 3)]:
        # Independent scalar recurrence, seeded by exactly N median samples.
        expected = [np.nan] * (period - 1) + [sum(mid[:period]) / period]
        for price in mid[period:]:
            expected.append((expected[-1] * (period - 1) + price) / period)
        np.testing.assert_allclose(result[name], expected, equal_nan=True)
        np.testing.assert_allclose(result[name + "_current"],
                                   [np.nan] * shift + expected[:-shift], equal_nan=True)
        for end in (21, 37, 51):
            prefix = calculate_indicators(frame.iloc[:end], config_from_env({}))
            pd.testing.assert_series_equal(prefix[name + "_current"], result[name + "_current"].iloc[:end])
