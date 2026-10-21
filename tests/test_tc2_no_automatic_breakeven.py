"""TC2 position protection is structural; +1R is not an exit signal."""
from types import SimpleNamespace

from williams_exit_policy import plan_williams_stop

H1 = 3_600_000


def candles(highs, lows):
    return [SimpleNamespace(t=i * H1, h=h, l=l)
            for i, (h, l) in enumerate(zip(highs, lows))]


def test_1r_favorable_price_is_not_a_stop_trigger_both_sides_even_if_legacy_enabled():
    long_bars = candles([105, 106, 107, 108, 109], [92, 93, 94, 95, 96])
    short_bars = candles([108, 107, 106, 105, 104], [92, 91, 90, 89, 88])
    for legacy in [False, True]:
        assert plan_williams_stop("BUY", 99, 100, 90, 125, .1, 1,
                                  long_bars, break_even=legacy) is None
        assert plan_williams_stop("SELL", 101, 100, 110, 75, .1, 1,
                                  short_bars, break_even=legacy) is None


def test_book_five_bar_stop_still_tightens_in_both_directions():
    long_bars = candles([105, 106, 107, 108, 109], [92, 93, 94, 95, 96])
    short_bars = candles([108, 107, 106, 105, 104], [92, 91, 90, 89, 88])
    long = plan_williams_stop("BUY", 90, 100, 90, 125, .1, 1, long_bars)
    short = plan_williams_stop("SELL", 110, 100, 110, 75, .1, 1, short_bars)
    assert long["reason"] == "TC2_LAST_5_CLOSED_BARS"
    assert abs(long["stop"] - 91.9) < 1e-8
    assert short["reason"] == "TC2_LAST_5_CLOSED_BARS"
    assert abs(short["stop"] - 108.1) < 1e-8


def test_gap_or_unknown_position_opening_time_prevents_unjustified_move():
    rows = candles([105, 106, 107, 108, 109], [92, 93, 94, 95, 96])
    assert plan_williams_stop("BUY", 90, 100, 90, 125, .1, 0, rows) is None
    rows[2].t += H1
    assert plan_williams_stop("BUY", 90, 100, 90, 125, .1, 1, rows) is None
