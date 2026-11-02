"""TC2 PDF 165 (printed p.150) exact 3/5 bar stop alignment with Kotlin.

These are safe read-only proposal tests, NOT proof of successful Binance STOP.
"""
from types import SimpleNamespace

import pytest

from williams_exit_policy import plan_williams_stop, tc2_exact_structural_stop

H1 = 3_600_000


@pytest.mark.parametrize("side,extreme,tick,expected", [
    ("LONG", 79524.8, .1, 79524.7),
    ("BUY", 79524.8, .1, 79524.7),
    ("SHORT", 2297.00, .01, 2297.01),
    ("SELL", 2297.00, .01, 2297.01),
    ("SHORT", 56.21, .01, 56.22),
    ("LONG", 56.21, .01, 56.20),
])
def test_book_tick_is_exactly_one_exchange_grid_step(side, extreme, tick, expected):
    assert tc2_exact_structural_stop(side, extreme, tick) == expected


@pytest.mark.parametrize("side,extreme,tick", [
    ("LONG", 79524.85, .1), ("SHORT", 2297.005, .01),
    ("LONG", float("nan"), .1), ("SHORT", float("inf"), .1),
    ("LONG", 100.0, 0), ("SHORT", 100.0, -1),
    ("LONG", -100.0, .1), ("UNKNOWN", 100.0, .1),
])
def test_off_grid_or_corrupt_book_prices_do_not_authorize_stop(side, extreme, tick):
    assert tc2_exact_structural_stop(side, extreme, tick) is None


def bars(highs, lows):
    return [SimpleNamespace(t=(i + 1) * H1, h=h, l=l) for i, (h,l) in enumerate(zip(highs,lows))]


def test_five_bar_book_trail_matches_android_exact_tick_both_directions():
    long = plan_williams_stop("LONG", 79520.0, 79530.0, 79520.0, 79600.0, .1,
        1, bars([v+3 for v in [79524.8,79525.8,79526.8,79527.8,79528.8]],
                [79524.8,79525.8,79526.8,79527.8,79528.8]))
    assert long == {"stop": 79524.7, "reason": "TC2_LAST_5_CLOSED_BARS"}

    highs = [2297.0,2296.9,2296.8,2296.7,2296.6]
    short = plan_williams_stop("SHORT", 2301.0, 2295.0, 2301.0, 2280.0, .01,
        1, bars(highs,[v-2 for v in highs]))
    assert short == {"stop": 2297.01, "reason": "TC2_LAST_5_CLOSED_BARS"}


def test_unaligned_h1_timestamp_and_off_grid_source_keep_old_signed_stop():
    longs = bars([103.,104.,105.,106.,107.],[99.,100.,101.,102.,103.])
    assert plan_williams_stop("LONG", 90., 100., 90., 130., .1, 1, longs)
    longs[3].t += 60_000
    assert plan_williams_stop("LONG", 90., 100., 90., 130., .1, 1, longs) is None
    longs[3].t -= 60_000
    longs[0].l = 99.005
    assert plan_williams_stop("LONG", 90., 100., 90., 130., .1, 1, longs) is None


def test_full_fractal_is_decimal_exact_and_not_retroactive():
    lows=[109.0,108.0,107.0,103.2,107.0,108.0,109.0]
    points=bars([x+2 for x in lows],lows)
    proposal=plan_williams_stop("LONG", 90.0, 100.0, 90.0, 130.0, .1,
        H1,points,window=None)
    assert proposal == {"stop":103.1,"reason":"CONFIRMED_H1_FRACTAL"}
    points[3].l=103.25
    assert plan_williams_stop("LONG",90.0,100.0,90.0,130.0,.1,H1,points,window=None) is None
    points[3].l=103.2
    assert plan_williams_stop("LONG",90.0,100.0,90.0,130.0,.1,H1,points[:-2],window=None) is None
