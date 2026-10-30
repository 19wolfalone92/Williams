"""Read-only, cost-stressed chronological validation for Williams H1.

This is deterministic backtest quality measurement, NOT model training,
independent price execution evidence, or authority to place orders. The
holdout is a chronological reporting segment, not automatically an
untouched sample if humans already tuned the strategy using these dates.
"""
from __future__ import annotations

from dataclasses import replace
from math import isfinite
from typing import Mapping, Sequence

import pandas as pd

from campaign_model import SignalSpec
from williams_paper_replay import PaperReplayConfig, run_williams_paper_replay


def _trade_stats(trades: pd.DataFrame, from_time=None) -> dict:
    rows = trades if from_time is None or trades.empty else trades[
        pd.to_datetime(trades.entry_time, utc=True) >= from_time]
    if rows.empty:
        return {"trades": 0, "wins": 0, "losses": 0, "net_pnl_usdt": 0.0,
                "win_rate_pct": None, "avg_r_multiple": None,
                "profit_factor": None, "max_consecutive_losses": 0}
    pnl = pd.to_numeric(rows.net_pnl_usdt, errors="coerce")
    multiples = pd.to_numeric(rows.r_multiple, errors="coerce")
    # Nonfinite trade results must not produce an apparently successful verdict.
    if not pnl.notna().all() or not pnl.map(isfinite).all() or not multiples.map(isfinite).all():
        return {"trades": len(rows), "status": "INVALID_NONFINITE_TRADE_RESULT"}
    winners = pnl > 0
    loss = pnl < 0
    losing_streak = 0
    worst_streak = 0
    for losing in loss:
        losing_streak = losing_streak + 1 if losing else 0
        worst_streak = max(worst_streak, losing_streak)
    profit = float(pnl[winners].sum())
    damage = -float(pnl[loss].sum())
    return {
        "trades": int(len(rows)), "wins": int(winners.sum()), "losses": int(loss.sum()),
        "net_pnl_usdt": float(pnl.sum()), "win_rate_pct": 100.0*int(winners.sum())/len(rows),
        "avg_r_multiple": float(multiples.mean()),
        "profit_factor": profit/damage if damage else None,
        "profit_factor_unbounded": damage==0 and profit>0,
        "max_consecutive_losses": worst_streak,
    }


def audit_williams_h1_trade_quality(
    candles: pd.DataFrame,
    signals_by_bar: Mapping[int, Sequence[SignalSpec]],
    config: PaperReplayConfig,
    *,
    baseline: tuple | None = None,
    min_holdout_trades: int = 30,
    holdout_fraction: float = 0.25,
    cost_multiplier: float = 2.0,
) -> dict:
    """Measure untouched fixed signals under higher assumed fees/slippage.

    Timestamp data integrity and OHLC causality are checked by the same
    simulator. Performance is only retrospective and NOT automatically
    independent out-of-sample acceptance or investment advice.
    """
    if min_holdout_trades < 1 or not 0.1 <= holdout_fraction <= 0.5 or (
            not isfinite(cost_multiplier) or cost_multiplier < 1):
        raise ValueError("invalid trade quality audit thresholds")
    config.validate()
    original = baseline if baseline is not None else run_williams_paper_replay(
        candles, signals_by_bar, config)
    fee, slip = config.fee_rate_per_side * cost_multiplier, config.slippage_rate_per_side * cost_multiplier
    stressed_config = replace(config, fee_rate_per_side=fee, slippage_rate_per_side=slip)
    stressed_config.validate()
    stressed = run_williams_paper_replay(candles, signals_by_bar, stressed_config)
    if candles.empty:
        return {"status": "INSUFFICIENT_CLOSED_HISTORY", "execution_authority": False,
                "profits_certified": False, "holdout_trades": 0}
    cutoff_index = min(len(candles)-1, int(len(candles)*(1-holdout_fraction)))
    holdout_start = candles.index[cutoff_index]
    base_report = _trade_stats(original[1], holdout_start)
    stress_report = _trade_stats(stressed[1], holdout_start)
    count = base_report["trades"]
    insufficient = count < min_holdout_trades
    # Healthy or negative numbers are displayed without granting authority.
    return {
        "schema": "WILLIAMS_TC2_CAUSAL_COST_STRESS_V1",
        "status": "INSUFFICIENT_HOLDOUT_TRADES" if insufficient else
                  "RETROSPECTIVE_HOLDOUT_NOT_INDEPENDENTLY_CERTIFIED",
        "timeframe": "H1",
        "holdout_start_utc": holdout_start.isoformat(),
        "min_holdout_trades": min_holdout_trades,
        "holdout_trades": count,
        "baseline_assumed_fees": config.fee_rate_per_side,
        "baseline_assumed_slippage": config.slippage_rate_per_side,
        "stress_multiplier": cost_multiplier,
        "baseline_all_history": _trade_stats(original[1]),
        "baseline_holdout": base_report,
        "stressed_holdout": stress_report,
        "positive_holdout_net_pnl_both_cost_models": (
            count >= min_holdout_trades and stress_report["trades"]>=min_holdout_trades and
            base_report.get("net_pnl_usdt",0)>0 and stress_report.get("net_pnl_usdt",0)>0),
        "assumptions": (
            "Paper fill approximations; no order-book/spread/funding/partial-fill/reboot "
            "execution audit. A chronological holdout may be contaminated by earlier tuning."),
        "profits_certified": False,
        "book_fidelity_certified": False,
        "execution_authority": False,
    }
