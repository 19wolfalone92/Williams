#!/usr/bin/env python3
"""Canonical Williams Core historical/event-driven research runner.

This CLI deliberately does not use the legacy Backtester.  It replays the
same MTF chain and Campaign Engine used by the production Testnet path:
1D -> 4H -> 1H -> M15 signal truth -> M5 execution microscope -> campaign.

Historical market data may come from Binance public mainnet data by default
for research; this does not change Binance execution endpoints.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

import pandas as pd
from dotenv import load_dotenv

from data import fetch_klines, save_csv
from stress_suite import run_latency_slippage_stress
from williams_campaign_backtester import WilliamsCampaignBacktester
from williams_intraday_spec import IntradayPolicy


load_dotenv()


def _utc(value: str) -> pd.Timestamp:
    ts = pd.Timestamp(value)
    return ts.tz_localize("UTC") if ts.tzinfo is None else ts.tz_convert("UTC")


def _load_history(
    *,
    symbol: str,
    interval: str,
    start: pd.Timestamp,
    end: pd.Timestamp,
    cache_dir: Path,
) -> pd.DataFrame:
    cache_dir.mkdir(parents=True, exist_ok=True)
    safe = interval.replace("/", "_")
    path = cache_dir / f"{symbol}_{safe}_{start.strftime('%Y%m%d')}_{end.strftime('%Y%m%d')}.csv"
    if path.exists():
        df = pd.read_csv(path, parse_dates=["open_time"])
        df["open_time"] = pd.to_datetime(df["open_time"], utc=True)
        return df.set_index("open_time")
    df = fetch_klines(
        symbol,
        interval,
        start.isoformat(),
        end.isoformat(),
    )
    save_csv(df, path)
    return df


def _trade_metrics(out: dict, starting_equity: float) -> dict:
    trades = list(out.get("trades") or [])
    equity = float(out.get("equity", starting_equity))
    pnls = [float(t.get("pnl_quote", 0.0) or 0.0) for t in trades]
    wins = [p for p in pnls if p > 0]
    losses = [p for p in pnls if p < 0]

    running = float(starting_equity)
    peak = running
    max_dd = 0.0
    for pnl in pnls:
        running += pnl
        peak = max(peak, running)
        if peak > 0:
            max_dd = max(max_dd, (peak - running) / peak)

    gross_profit = sum(wins)
    gross_loss = abs(sum(losses))
    profit_factor = gross_profit / gross_loss if gross_loss > 0 else float("inf") if gross_profit > 0 else 0.0

    return {
        "status": out.get("status", ""),
        "starting_equity": float(starting_equity),
        "final_equity": equity,
        "net_pnl_quote": equity - float(starting_equity),
        "return_pct": (equity / float(starting_equity) - 1.0) * 100.0 if starting_equity else 0.0,
        "total_trades": len(trades),
        "winning_trades": len(wins),
        "losing_trades": len(losses),
        "win_rate_pct": (len(wins) / len(trades) * 100.0) if trades else 0.0,
        "gross_profit_quote": gross_profit,
        "gross_loss_quote": -gross_loss,
        "profit_factor": profit_factor,
        "expectancy_quote": (sum(pnls) / len(pnls)) if pnls else 0.0,
        "max_drawdown_pct": max_dd * 100.0,
        "structural_stopouts": sum(1 for t in trades if t.get("reason") == "STRUCTURAL_STOP"),
        "eod_flats": sum(1 for t in trades if t.get("reason") == "EOD_FLAT"),
        "fills": len(out.get("fills") or []),
        "open_position_at_end": bool(out.get("open_position", False)),
        "risk_stopped": bool(out.get("risk_stopped", False)),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Williams Core event-driven historical backtest")
    parser.add_argument("--symbol", default=os.getenv("SYMBOL", "BTCUSDT"))
    parser.add_argument("--start", default=os.getenv("START_DATE", "2024-01-01"))
    parser.add_argument("--end", default=os.getenv("END_DATE", "2026-10-01"))
    parser.add_argument("--capital", type=float, default=500.0)
    parser.add_argument("--fee", type=float, default=0.001)
    parser.add_argument("--slippage", type=float, default=0.0005)
    parser.add_argument("--tick-size", type=float, default=0.01)
    parser.add_argument("--no-m5", action="store_true", help="Replay without M5 microstructure; ambiguous intrabar paths are rejected conservatively")
    parser.add_argument("--cache-dir", default="data_cache/backtest")
    parser.add_argument("--outdir", default="results/canonical")
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    start = _utc(args.start)
    end = _utc(args.end)
    if not end > start:
        raise SystemExit("--end must be after --start")

    # Research-data routing is independent from execution safety.
    os.environ.setdefault("WILLIAMS_RESEARCH_MAINNET", "true")

    cache_dir = Path(args.cache_dir)
    outdir = Path(args.outdir) / args.symbol
    outdir.mkdir(parents=True, exist_ok=True)

    frames = {
        "1d": _load_history(symbol=args.symbol, interval="1d", start=start - pd.Timedelta(days=120), end=end, cache_dir=cache_dir),
        "4h": _load_history(symbol=args.symbol, interval="4h", start=start - pd.Timedelta(days=60), end=end, cache_dir=cache_dir),
        "1h": _load_history(symbol=args.symbol, interval="1h", start=start - pd.Timedelta(days=30), end=end, cache_dir=cache_dir),
        "15m": _load_history(symbol=args.symbol, interval="15m", start=start - pd.Timedelta(days=7), end=end, cache_dir=cache_dir),
    }
    if args.no_m5:
        m5 = None
    else:
        m5 = _load_history(
            symbol=args.symbol,
            interval="5m",
            start=start - pd.Timedelta(days=1),
            end=end,
            cache_dir=cache_dir,
        )

    policy = IntradayPolicy.from_env({"WILLIAMS_STRATEGY_PROFILE": "WILLIAMS_CORE_INTRADAY"})
    bt = WilliamsCampaignBacktester(
        starting_equity=args.capital,
        fee_pct=args.fee,
        slippage_pct=args.slippage,
        policy=policy,
    )
    out = bt.run(
        args.symbol,
        frames["1h"],
        frames["15m"],
        m5=m5,
        h4=frames["4h"],
        d1=frames["1d"],
        tick_size=args.tick_size,
        start_at=start,
    )
    metrics = _trade_metrics(out, args.capital)

    stress = run_latency_slippage_stress(
        expected_reward_pct=0.02,
        fee_pct=2.0 * args.fee,
        tick_pct=max(args.tick_size / 100.0, 1e-8),
        cases=1000,
        seed=args.seed,
        slippage_ceiling_pct=max(args.slippage, 0.0015),
    )
    metrics.update({
        "symbol": args.symbol,
        "start": start.isoformat(),
        "end": end.isoformat(),
        "execution_replay": "M5" if m5 is not None else "M15_ONLY_CONSERVATIVE",
        "policy": {
            "profile": policy.profile,
            "decision_tf": policy.timeframes.decision_tf,
            "execution_tf": policy.timeframes.execution_tf,
            "initial_risk_pct": policy.risk.initial_risk_pct,
            "campaign_risk_pct": policy.risk.campaign_risk_pct,
            "daily_loss_pct": policy.risk.daily_loss_pct,
            "max_full_stopouts": policy.risk.max_full_stopouts,
        },
        "latency_slippage_stress_pass_rate_pct": stress.pass_rate * 100.0,
        "latency_slippage_stress_worst_net_edge_pct": stress.worst_net_edge_pct * 100.0,
        "latency_slippage_stress_passed": stress.passed,
    })

    payload = {
        "metrics": metrics,
        "trades": out.get("trades", []),
        "fills": out.get("fills", []),
    }
    (outdir / "report.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2, default=str),
        encoding="utf-8",
    )
    pd.DataFrame([metrics]).to_csv(outdir / "metrics.csv", index=False)
    pd.DataFrame(out.get("trades", [])).to_csv(outdir / "trades.csv", index=False)

    print(json.dumps(metrics, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
