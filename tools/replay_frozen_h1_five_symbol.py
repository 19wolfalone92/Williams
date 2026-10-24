"""Reproduce 15-symbol Williams H1 trade quality on sealed Binance 2024 candles.

NO network, credentials, model training, or financial orders. The fifteen 480-bar
sets are historical material already used during development, NOT a genuinely
untouched out-of-sample set or human-labeled wave truth. Stop fills and costs
are assumptions; current symbol ticks may differ from Nov-2024 tick filters.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

import pandas as pd
from run_williams_h1_paper_replay import run_symbol_replay
from williams_paper_replay import PaperReplayConfig

# Observed in CURRENT Binance USD-M exchangeInfo on 2026-10-25, not
# historical 2024 exchange metadata. Every report discloses that limitation.
ASSUMED_TICKS_2026_10_25 = {
    "BTCUSDT": 0.10,
    "ETHUSDT": 0.01,
    "BNBUSDT": 0.01,
    "SOLUSDT": 0.01,
    "XRPUSDT": 0.0001,
    "DOGEUSDT": 0.000010,
    "ADAUSDT": 0.00010,
    "LINKUSDT": 0.001,
    "AVAXUSDT": 0.0010,
    "LTCUSDT": 0.01,
    "TRXUSDT": 0.00001,
    "DOTUSDT": 0.000100,
    "UNIUSDT": 0.0010,
    "BCHUSDT": 0.01,
    "NEARUSDT": 0.0010,
}
SOURCE_DIR = ROOT / "app" / "src" / "test" / "resources"
HOUR_MS = 3_600_000
EXPECTED_START = 1731110400000  # 2024-11-09 00:00Z
EXPECTED_END = 1732838400000    # 2024-11-29 00:00Z
EXPECTED_BARS = (EXPECTED_END - EXPECTED_START) // HOUR_MS


def load_sealed_h1(symbol: str) -> tuple[pd.DataFrame, str]:
    if symbol not in ASSUMED_TICKS_2026_10_25:
        raise ValueError("Unknown frozen H1 symbol")
    path = SOURCE_DIR / f"blind_wave_20241109_29_{symbol}.json"
    raw = path.read_bytes()
    payload = json.loads(raw)
    if (payload.get("source") != "BINANCE_PUBLIC_USDM_FUTURES_CLOSED_H1"
            or payload.get("symbol") != symbol or payload.get("interval") != "1h"
            or payload.get("columns") !=
                ["open_time", "open", "high", "low", "close", "base_volume"]
            or payload.get("window_start") != EXPECTED_START
            or payload.get("window_end_exclusive") != EXPECTED_END):
        raise ValueError(f"{symbol}: frozen source provenance mismatch")
    rows = payload.get("rows")
    if not isinstance(rows, list) or len(rows) != EXPECTED_BARS:
        raise ValueError(f"{symbol}: frozen H1 history incomplete")
    expected_times = [EXPECTED_START + i * HOUR_MS for i in range(EXPECTED_BARS)]
    if any(not isinstance(row, list) or len(row) != 6 or
            type(row[0]) is not int or row[0] != expected_times[i]
            for i, row in enumerate(rows)):
        raise ValueError(f"{symbol}: future, gap or duplicate H1 timestamp")
    frame = pd.DataFrame(rows, columns=payload["columns"])
    for column in ("open", "high", "low", "close", "base_volume"):
        frame[column] = pd.to_numeric(frame[column], errors="coerce")
    numeric = frame[["open", "high", "low", "close", "base_volume"]]
    if (not numeric.notna().all().all() or
            not numeric.apply(lambda col: col.map(math.isfinite)).all().all() or
            not (frame[["open", "high", "low", "close"]] > 0).all().all() or
            not (frame["base_volume"] >= 0).all() or
            not (frame.high >= frame[["open", "close", "low"]].max(axis=1)).all() or
            not (frame.low <= frame[["open", "close", "high"]].min(axis=1)).all()):
        raise ValueError(f"{symbol}: malformed OHLCV cannot enter replay")
    frame = frame.rename(columns={"base_volume": "volume"})
    frame.index = pd.to_datetime(frame.pop("open_time"), unit="ms", utc=True)
    return frame, hashlib.sha256(raw).hexdigest()


def run_all(outdir: Path, window_bars: int = 180) -> dict:
    if not 60 <= window_bars <= EXPECTED_BARS:
        raise ValueError("Invalid signal lookback")
    outdir.mkdir(parents=True, exist_ok=True)
    config = PaperReplayConfig(
        starting_equity_usdt=5000, risk_pct_per_entry=0.002,
        fee_rate_per_side=0.0004, slippage_rate_per_side=0.0005,
        trailing_lookback_bars=5, max_pending_bars=20,
    )
    reports = []
    for symbol, tick in ASSUMED_TICKS_2026_10_25.items():
        candles, checksum = load_sealed_h1(symbol)
        result = run_symbol_replay(
            symbol, candles, tick_size=tick, output_dir=outdir, config=config,
            window_bars=window_bars,
        )
        reports.append({
            "symbol": symbol,
            "frozen_source_sha256": checksum,
            "bars": len(candles),
            "tick_assumption": tick,
            "tick_basis": "USD-M exchangeInfo observed 2026-10-25; 2024 precision NOT proven",
            "trade_metrics": result["trade_metrics"],
            "max_drawdown_pct": result.get("max_drawdown_pct"),
            "quality": result["read_only_chronological_quality"],
        })
    summary = {
        "schema": "WILLIAMS_FROZEN_FIFTEEN_SYMBOL_H1_REPLAY_V1",
        "source_period_utc": "[2024-11-09, 2024-11-29)",
        "dataset": "15 x 480 public Binance USD-M genuine closed H1 bars",
        "symbols": list(ASSUMED_TICKS_2026_10_25),
        "replay_bars": EXPECTED_BARS,
        "method": "causal closed-H1 paper fill, strict symbol tick, chronological cost stress x2",
        "known_limitations": [
            "Fifteen 2024 assets / 20 days; NOT untouched independent holdout",
            "Book rules are still unproven against independently human-labeled charts",
            "2026 symbol ticks applied to 2024 candles; historical tick-filter changes not checked",
            "Base-asset volume substitutes unavailable trade-count MFI, explicit proxy",
            "No funding, liquidation, order book, partial fills, device reboot or Demo exchange receipts",
            "Per-symbol independent 5000 USDT simulation; sums are NOT a portfolio backtest",
        ],
        "total_trades_not_portfolio": sum(r["trade_metrics"]["trades"] for r in reports),
        "total_net_pnl_not_portfolio_usdt": sum(r["trade_metrics"]["net_pnl_usdt"] for r in reports),
        "independent_expert_wave_annotations": 0,
        "human_wave_accuracy": None,
        "profits_certified": False,
        "book_fidelity_certified": False,
        "demo_device_certified": False,
        "execution_authority": False,
        "results": reports,
    }
    (outdir / "frozen_fifteen_symbol_summary.json").write_text(
        json.dumps(summary, indent=2, allow_nan=False, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    return summary


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--outdir", type=Path, default=Path("build/frozen-h1-replay"))
    p.add_argument("--window-bars", type=int, default=180)
    args = p.parse_args()
    report = run_all(args.outdir, args.window_bars)
    print(json.dumps({
        "symbols": report["symbols"],
        "total_trades_not_portfolio": report["total_trades_not_portfolio"],
        "total_net_pnl_not_portfolio_usdt": report["total_net_pnl_not_portfolio_usdt"],
        "human_wave_accuracy": report["human_wave_accuracy"],
        "execution_authority": report["execution_authority"],
    }, allow_nan=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
