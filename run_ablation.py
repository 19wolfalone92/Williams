#!/usr/bin/env python3
"""Signal-family ablation runner for canonical Williams Campaign Backtests."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from campaign_model import SignalType
from run_backtest import _load_history, _trade_metrics, _utc
from williams_campaign_backtester import WilliamsCampaignBacktester
from williams_intraday_spec import IntradayPolicy


VARIANTS = {
    "ALL": None,
    "WM1": {SignalType.REVERSAL},
    "WM2": {SignalType.SUPER_AO},
    "WM3": {SignalType.FRACTAL},
}


def main() -> None:
    parser = argparse.ArgumentParser(description="Williams Core signal-family ablation")
    parser.add_argument("--symbol", default="BTCUSDT")
    parser.add_argument("--start", default="2024-01-01")
    parser.add_argument("--end", default="2026-10-01")
    parser.add_argument("--capital", type=float, default=500.0)
    parser.add_argument("--fee", type=float, default=0.001)
    parser.add_argument("--slippage", type=float, default=0.0005)
    parser.add_argument("--tick-size", type=float, default=0.01)
    parser.add_argument("--variant", choices=sorted(VARIANTS), default="ALL")
    parser.add_argument("--cache-dir", default="data_cache/ablation")
    parser.add_argument("--outdir", default="results/ablation")
    args = parser.parse_args()

    start = _utc(args.start)
    end = _utc(args.end)
    if end <= start:
        raise SystemExit("--end must be after --start")

    import os
    os.environ.setdefault("WILLIAMS_RESEARCH_MAINNET", "true")

    cache_dir = Path(args.cache_dir)
    frames = {
        "1d": _load_history(
            symbol=args.symbol, interval="1d",
            start=start - __import__("pandas").Timedelta(days=120), end=end, cache_dir=cache_dir
        ),
        "4h": _load_history(
            symbol=args.symbol, interval="4h",
            start=start - __import__("pandas").Timedelta(days=60), end=end, cache_dir=cache_dir
        ),
        "1h": _load_history(
            symbol=args.symbol, interval="1h",
            start=start - __import__("pandas").Timedelta(days=30), end=end, cache_dir=cache_dir
        ),
        "15m": _load_history(
            symbol=args.symbol, interval="15m",
            start=start - __import__("pandas").Timedelta(days=7), end=end, cache_dir=cache_dir
        ),
        "5m": _load_history(
            symbol=args.symbol, interval="5m",
            start=start - __import__("pandas").Timedelta(days=1), end=end, cache_dir=cache_dir
        ),
    }

    policy = IntradayPolicy.from_env(
        {"WILLIAMS_STRATEGY_PROFILE": "WILLIAMS_CORE_INTRADAY"}
    )
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
        m5=frames["5m"],
        h4=frames["4h"],
        d1=frames["1d"],
        tick_size=args.tick_size,
        start_at=start,
        allowed_signal_types=VARIANTS[args.variant],
    )
    metrics = _trade_metrics(out, args.capital)
    metrics.update({
        "symbol": args.symbol,
        "variant": args.variant,
        "start": start.isoformat(),
        "end": end.isoformat(),
        "execution_replay": "M5",
    })

    outdir = Path(args.outdir) / args.symbol
    outdir.mkdir(parents=True, exist_ok=True)
    (outdir / f"{args.variant}.json").write_text(
        json.dumps(
            {"metrics": metrics, "trades": out.get("trades", []), "fills": out.get("fills", [])},
            ensure_ascii=False,
            indent=2,
            default=str,
        ),
        encoding="utf-8",
    )
    print(json.dumps(metrics, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
