"""Mathematical warmup qualification for the canonical shifted SMMA Alligator."""
import pandas as pd

from strategy import (
    CANONICAL_ALLIGATOR_WARMUP_BARS,
    CANONICAL_ALLIGATOR_ABS_TOLERANCE,
    smma,
    smma_initialization_error_bound,
)


def displayed_smma(values, period, displacement):
    return smma(pd.Series(values, dtype=float), period).shift(displacement).iloc[-1]


def test_r49_500_bar_bound_is_below_absolute_tolerance_for_conservative_price_range():
    # A 1e8 median-price range is deliberately much wider than the current
    # 15-symbol sample. The initial-state error is bounded by that range and
    # decays geometrically under the canonical SMMA recurrence.
    assert CANONICAL_ALLIGATOR_WARMUP_BARS == 500
    for period, displacement in ((13, 8), (8, 5), (5, 3)):
        bound = smma_initialization_error_bound(
            100_000_000.0, period, CANONICAL_ALLIGATOR_WARMUP_BARS, displacement
        )
        assert bound <= CANONICAL_ALLIGATOR_ABS_TOLERANCE
    assert smma_initialization_error_bound(100_000_000.0, 13, 220, 8) > CANONICAL_ALLIGATOR_ABS_TOLERANCE


def test_r49_500_bar_window_matches_long_state_where_220_bar_jaw_does_not():
    # Worst-case bounded transition: the long-history SMMA is at the top of
    # the price range, then the market moves to zero. A 220-bar window forgets
    # too much of that state; 500 bars reduces the residual below 1e-8.
    full_history = [100_000.0] * 1000 + [0.0] * 220
    reference = displayed_smma(full_history, 13, 8)
    warm_500 = displayed_smma(full_history[-500:], 13, 8)
    warm_220 = displayed_smma(full_history[-220:], 13, 8)
    assert abs(warm_500 - reference) <= CANONICAL_ALLIGATOR_ABS_TOLERANCE
    assert abs(warm_220 - reference) > CANONICAL_ALLIGATOR_ABS_TOLERANCE
