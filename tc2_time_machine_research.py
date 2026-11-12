"""Read-only TC2 H1 scenario stress tester inspired by Day Trader Skill Time Machine.

24/7 crypto: chronological contiguous closed H1 blocks, not shuffled individual
5-minute bars or equity-market session slots. Uses no Binance keys, positions,
orders or production execution imports. This is NOT a TC2 PnL backtest: it
holds the *initial* structural stop, ignores subsequent Williams exits/entries,
funding and liquidity. Use williams_paper_replay for strategy economics.
"""
from __future__ import annotations

from dataclasses import dataclass
import math
import random
from statistics import mean
from typing import Sequence

HOUR_MS = 3_600_000


@dataclass(frozen=True)
class H1Bar:
    open_time_ms: int
    open: float
    high: float
    low: float
    close: float


@dataclass(frozen=True)
class StopEntry:
    side: str
    trigger: float
    stop: float

    def validate(self) -> None:
        if self.side not in ("LONG", "SHORT"):
            raise ValueError("side must be LONG or SHORT")
        if not all(math.isfinite(float(x)) and x > 0 for x in (self.trigger, self.stop)):
            raise ValueError("prices must be finite and strictly positive")
        if self.side == "LONG" and self.stop >= self.trigger:
            raise ValueError("LONG stop must be below trigger")
        if self.side == "SHORT" and self.stop <= self.trigger:
            raise ValueError("SHORT stop must be above trigger")


def validate_h1(bars: Sequence[H1Bar], *, asof_ms: int) -> None:
    if not bars:
        raise ValueError("need closed H1 history")
    for i, b in enumerate(bars):
        if b.open_time_ms + HOUR_MS > asof_ms:
            raise ValueError("future or unclosed H1 candle supplied")
        if i and b.open_time_ms - bars[i - 1].open_time_ms != HOUR_MS:
            raise ValueError("H1 history must be ordered, unique and contiguous")
        prices = (b.open, b.high, b.low, b.close)
        if not all(math.isfinite(float(p)) and p > 0 for p in prices):
            raise ValueError("invalid non-finite or nonpositive OHLC")
        if b.high < max(b.open, b.close, b.low) or b.low > min(b.open, b.close, b.high):
            raise ValueError("inconsistent H1 OHLC")


def _net_r(side: str, entry: float, exit_price: float, trigger: float, stop: float,
           fee_rate: float) -> float:
    direction = 1 if side == "LONG" else -1
    risk = abs(trigger - stop)
    return (direction * (exit_price - entry) - fee_rate * (entry + exit_price)) / risk


def evaluate_stop_entry_path(
    bars: Sequence[H1Bar],
    order: StopEntry,
    *,
    max_pending_bars: int = 4,
    fee_rate: float = 0.0004,
    slippage_rate: float = 0.0005,
) -> dict:
    """Conservative OHLC fill path. Same-bar entry+stop => assume stop was hit.

    Research-only; slippage is bounded assumption and no stop execution is
    guaranteed during a real exchange outage or extreme liquidity gap.
    """
    order.validate()
    if max_pending_bars <= 0 or not bars:
        raise ValueError("need future bars and positive pending horizon")
    if any(not math.isfinite(x) or x < 0 or x >= 0.1
           for x in (fee_rate, slippage_rate)):
        raise ValueError("invalid modeled costs")
    long = order.side == "LONG"
    fill = None
    for i, b in enumerate(bars):
        if fill is None:
            if i >= max_pending_bars:
                return {"status": "UNFILLED", "r": 0.0, "exit_bar": None}
            if (b.high < order.trigger if long else b.low > order.trigger):
                continue
            raw = max(b.open, order.trigger) if long else min(b.open, order.trigger)
            fill = raw * (1 + slippage_rate if long else 1 - slippage_rate)
        # A position can cross its SL in its entry candle; worst-case ordering.
        if (b.low <= order.stop if long else b.high >= order.stop):
            raw_exit = min(b.open, order.stop) if long else max(b.open, order.stop)
            actual_exit = raw_exit * (1 - slippage_rate if long else 1 + slippage_rate)
            return {"status": "STOP", "r": _net_r(order.side, fill, actual_exit,
                                                  order.trigger, order.stop, fee_rate),
                    "exit_bar": i}
    if fill is None:
        return {"status": "UNFILLED", "r": 0.0, "exit_bar": None}
    last_exit = bars[-1].close * (1 - slippage_rate if long else 1 + slippage_rate)
    return {"status": "HORIZON", "r": _net_r(order.side, fill, last_exit,
                                            order.trigger, order.stop, fee_rate),
            "exit_bar": len(bars) - 1}


def _normalized_moves(bars: Sequence[H1Bar]) -> list[tuple[float, float, float, float]]:
    # Preserve full candle geometry and *within-block* serial dependency.
    moves = []
    for previous, b in zip(bars, bars[1:]):
        moves.append((b.open / previous.close, b.high / previous.close,
                      b.low / previous.close, b.close / previous.close))
    return moves


def bootstrap_tc2_h1(
    observed_closed_h1: Sequence[H1Bar],
    order: StopEntry,
    *,
    asof_ms: int,
    seed: int = 19,
    replays: int = 10_000,
    horizon_bars: int = 24,
    block_bars: int = 8,
    embargo_bars: int = 24,
    max_pending_bars: int = 4,
    fee_rate: float = 0.0004,
    slippage_rate: float = 0.0005,
) -> dict:
    """Stress-test an already-approved TC2 stop-entry plan; never trade on it.

    Excludes the most recent embargo_bars from the training pool. For true
    out-of-sample tests invoke separately at each historical decision time,
    passing ONLY candles known then, with an untouched later test period.
    """
    validate_h1(observed_closed_h1, asof_ms=asof_ms)
    order.validate()
    if (replays < 1 or replays > 100_000 or horizon_bars < 1 or horizon_bars > 240 or
            block_bars < 2 or block_bars > 48 or embargo_bars < horizon_bars or
            max_pending_bars < 1):
        raise ValueError("invalid simulation bounds or purge/embargo")
    if not all(math.isfinite(x) and 0 <= x < 0.1 for x in (fee_rate, slippage_rate)):
        raise ValueError("invalid modeled costs")
    cutoff = len(observed_closed_h1) - embargo_bars
    training = observed_closed_h1[:cutoff]
    moves = _normalized_moves(training)
    if len(moves) < block_bars * 2:
        raise ValueError("insufficient embargoed historic closed H1 for block bootstrap")
    candidates = list(range(0, len(moves) - block_bars + 1))
    rng = random.Random(seed)
    outcomes = []
    counts = {"STOP": 0, "HORIZON": 0, "UNFILLED": 0}
    for _ in range(replays):
        price = observed_closed_h1[-1].close
        simulation = []
        while len(simulation) < horizon_bars:
            start = rng.choice(candidates)
            for o, h, lo, c in moves[start:start + block_bars]:
                if len(simulation) >= horizon_bars:
                    break
                bar = H1Bar(
                    open_time_ms=observed_closed_h1[-1].open_time_ms +
                        HOUR_MS * (len(simulation) + 1),
                    open=price * o, high=price * h, low=price * lo, close=price * c)
                simulation.append(bar)
                price = bar.close
        outcome = evaluate_stop_entry_path(simulation, order,
            max_pending_bars=max_pending_bars,
            fee_rate=fee_rate, slippage_rate=slippage_rate)
        counts[outcome["status"]] += 1
        outcomes.append(outcome["r"])
    ranked = sorted(outcomes)
    def quantile(p: float) -> float:
        return ranked[min(len(ranked) - 1, int(p * (len(ranked) - 1)))]
    return {
        "schema": "WILLIAMS_TC2_RESEARCH_TIME_MACHINE_V1",
        "mode": "READ_ONLY_STRESS_NOT_ENTRY_OR_EXIT_AUTHORITY",
        "exchange_orders_placed": 0,
        "seed": seed,
        "replays": replays,
        "side": order.side,
        "trigger": order.trigger,
        "structural_stop": order.stop,
        "known_through_ms": asof_ms,
        "training_last_open_time_ms": training[-1].open_time_ms,
        "embargo_bars": embargo_bars,
        "training_bars": len(training),
        "bootstrap_block_bars": block_bars,
        "horizon_bars": horizon_bars,
        "pending_bars": max_pending_bars,
        "cost_assumptions": {"fee_rate_per_side": fee_rate,
                             "slippage_rate_per_side": slippage_rate},
        "outcomes": counts,
        "stop_rate_all_replays": counts["STOP"] / replays,
        "unfilled_rate": counts["UNFILLED"] / replays,
        "mean_r": mean(outcomes),
        "median_r": quantile(0.5),
        "p05_r": quantile(0.05),
        "p95_r": quantile(0.95),
        "warnings": [
            "Monte Carlo resampling is not a predictive probability or TC2 trading recommendation.",
            "Block seams do not preserve long-range volatility/trend dependence.",
            "No intrabar ordering, financing, funding, liquidation, live spread or execution guarantee.",
            "Only the initial structural STOP is modeled; no TC2 trailing/opposite exit.",
            "Do not compare simulated in-sample R to independent historical returns as an edge."
        ],
    }


__all__ = ["H1Bar", "StopEntry", "validate_h1", "evaluate_stop_entry_path",
           "bootstrap_tc2_h1"]
