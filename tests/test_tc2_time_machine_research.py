"""Offline TC2 H1 Time Machine contract and conservative OHLC regressions."""
from __future__ import annotations

import pytest

from tc2_time_machine_research import (
    HOUR_MS, H1Bar, StopEntry, bootstrap_tc2_h1, evaluate_stop_entry_path,
    validate_h1,
)


def bar(i, o, h, l, c):
    return H1Bar(1_780_000_000_000 + i * HOUR_MS, o, h, l, c)


def closed_history(n=170):
    # Fully closed, contiguous 24/7 H1 series with alternating trend blocks.
    history = []
    price = 100.0
    for i in range(n):
        open_price = price
        price *= (1.008 if i % 13 < 5 else 0.994)
        history.append(bar(i, open_price, max(open_price, price) * 1.002,
                           min(open_price, price) * 0.998, price))
    return history


def test_long_same_bar_fill_and_stop_is_conservative():
    result = evaluate_stop_entry_path(
        [bar(1, 99.0, 101.0, 94.0, 97.0)],
        StopEntry("LONG", 100.0, 95.0),
        fee_rate=0.0004, slippage_rate=0.0005)
    assert result["status"] == "STOP"
    assert result["exit_bar"] == 0
    assert result["r"] < -1.0  # costs deepen the modeled stop loss


def test_short_same_bar_fill_and_stop_is_conservative():
    result = evaluate_stop_entry_path(
        [bar(1, 101.0, 106.0, 99.0, 103.0)],
        StopEntry("SHORT", 100.0, 105.0))
    assert result["status"] == "STOP"
    assert result["r"] < -1.0


def test_pending_never_turns_into_market_chase_or_fake_profit():
    x = evaluate_stop_entry_path([bar(i, 100, 102, 98, 100) for i in range(3)],
                                 StopEntry("LONG", 110, 95), max_pending_bars=2)
    assert x == {"status": "UNFILLED", "r": 0.0, "exit_bar": None}


def test_reproducible_monte_carlo_without_any_exchange_calls():
    history = closed_history()
    asof = history[-1].open_time_ms + HOUR_MS
    kwargs = dict(asof_ms=asof, seed=19, replays=77, horizon_bars=12,
                  block_bars=6, embargo_bars=16)
    long = StopEntry("LONG", history[-1].close * 1.002,
                     history[-1].close * 0.94)
    first = bootstrap_tc2_h1(history, long, **kwargs)
    assert first == bootstrap_tc2_h1(history, long, **kwargs)
    assert first["mode"] == "READ_ONLY_STRESS_NOT_ENTRY_OR_EXIT_AUTHORITY"
    assert first["exchange_orders_placed"] == 0
    assert sum(first["outcomes"].values()) == 77
    assert first["training_last_open_time_ms"] == history[-17].open_time_ms
    assert first["known_through_ms"] == asof
    assert first["p05_r"] <= first["median_r"] <= first["p95_r"]


def test_no_future_candle_and_no_unfinished_candle_leakage():
    history = closed_history(120)
    asof = history[-1].open_time_ms + HOUR_MS
    plan = StopEntry("SHORT", 95, 110)
    base = bootstrap_tc2_h1(history, plan, asof_ms=asof, replays=8)
    assert base["training_bars"] == 96
    with pytest.raises(ValueError, match="future or unclosed"):
        bootstrap_tc2_h1(history + [bar(120, 101, 105, 99, 103)],
                         plan, asof_ms=asof, replays=8)


def test_rejects_missing_h1_gaps_malformed_ohlc_and_impossible_stop():
    history = closed_history(140)
    asof = history[-1].open_time_ms + HOUR_MS
    invalid = history[:25] + history[26:]
    with pytest.raises(ValueError, match="contiguous"):
        validate_h1(invalid, asof_ms=asof)
    bad = history[:-1] + [bar(139, 100, 98, 99, 101)]
    with pytest.raises(ValueError, match="OHLC"):
        validate_h1(bad, asof_ms=asof)
    with pytest.raises(ValueError, match="stop must"):
        bootstrap_tc2_h1(history, StopEntry("LONG", 100, 110), asof_ms=asof)


def test_no_pool_or_invalid_purging_produces_fake_probabilities():
    history = closed_history(20)
    with pytest.raises(ValueError, match="insufficient"):
        bootstrap_tc2_h1(history, StopEntry("LONG", 100, 95),
                         asof_ms=history[-1].open_time_ms + HOUR_MS,
                         embargo_bars=12, horizon_bars=10)
    history = closed_history(150)
    with pytest.raises(ValueError, match="bounds"):
        bootstrap_tc2_h1(history, StopEntry("LONG", 100, 95),
                         asof_ms=history[-1].open_time_ms + HOUR_MS,
                         embargo_bars=4, horizon_bars=10)
