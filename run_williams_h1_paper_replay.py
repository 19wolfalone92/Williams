"""Canonical H1 historical replay for the Williams USD-M Futures strategy.

This is a read-only research tool. Historical candles are retrieved from the public
USD-M Futures market-data API; no account credentials or order endpoints are used.
Strategy signals are regenerated causally from each closed-bar prefix and then
passed to the exchange-free execution simulator.
"""
from __future__ import annotations

import argparse
import json
import math
import time
from pathlib import Path
from typing import Sequence

import numpy as np
import pandas as pd
import requests

from strategy import calculate_indicators, canonical_indicator_config_from_env
from williams_signals import extract_long_signal_specs, extract_short_signal_specs
from williams_paper_replay import PaperReplayConfig, run_williams_paper_replay

FUTURES_REST = "https://fapi.binance.com"
APPROVED_SYMBOLS = (
    "BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT",
    "DOGEUSDT", "ADAUSDT", "LINKUSDT", "AVAXUSDT", "LTCUSDT",
)
KLINE_COLUMNS = (
    "open_time", "open", "high", "low", "close", "volume",
    "close_time", "quote_volume", "trades", "taker_buy_base",
    "taker_buy_quote", "ignore",
)


def fetch_usdm_h1_history(
    symbol: str,
    start_ms: int,
    end_ms: int,
    *,
    session=requests,
    base_url: str = FUTURES_REST,
) -> pd.DataFrame:
    """Fetch public closed USD-M Futures H1 candles without Spot endpoints."""
    symbol = str(symbol).upper()
    if symbol not in APPROVED_SYMBOLS:
        raise ValueError(f"Symbol is outside the approved USD-M universe: {symbol}")
    if start_ms < 0 or end_ms <= start_ms:
        raise ValueError("Historical replay needs a valid [start, end) time range")

    rows = []
    cursor = int(start_ms)
    while cursor < end_ms:
        response = session.get(
            base_url + "/fapi/v1/klines",
            params={
                "symbol": symbol,
                "interval": "1h",
                "startTime": cursor,
                "endTime": int(end_ms - 1),
                "limit": 1500,
            },
            timeout=20,
        )
        response.raise_for_status()
        batch = response.json()
        if not batch:
            break
        rows.extend(batch)
        next_cursor = int(batch[-1][0]) + 1
        if next_cursor <= cursor:
            raise RuntimeError("USD-M H1 history cursor did not advance")
        cursor = next_cursor
        if len(batch) < 1500:
            break
        time.sleep(0.05)

    if not rows:
        return pd.DataFrame(columns=["open", "high", "low", "close", "volume", "quote_volume", "trades"])

    frame = pd.DataFrame(rows, columns=KLINE_COLUMNS)
    for col in ("open", "high", "low", "close", "volume", "quote_volume", "trades"):
        frame[col] = pd.to_numeric(frame[col], errors="coerce")
    frame["open_time"] = pd.to_datetime(frame["open_time"], unit="ms", utc=True)
    frame["close_time"] = pd.to_numeric(frame["close_time"], errors="coerce")
    frame = frame.dropna(subset=["open", "high", "low", "close", "close_time"])
    # Exclude the currently forming candle: the strategy can only see closed H1 bars.
    frame = frame[frame["close_time"] < int(time.time() * 1000)]
    frame = frame[(frame["open_time"].astype("int64") // 1_000_000 >= start_ms)]
    frame = frame[(frame["open_time"].astype("int64") // 1_000_000 < end_ms)]
    frame = frame.drop_duplicates("open_time", keep="last").sort_values("open_time")
    return frame.set_index("open_time")[[
        "open", "high", "low", "close", "volume", "quote_volume", "trades"
    ]]


def futures_tick_size(symbol: str, *, session=requests, base_url: str = FUTURES_REST) -> float:
    """Read the current tickSize from USD-M exchangeInfo; never guess it."""
    response = session.get(base_url + "/fapi/v1/exchangeInfo", timeout=20)
    response.raise_for_status()
    payload = response.json()
    row = next(
        (x for x in payload.get("symbols", []) if str(x.get("symbol", "")).upper() == symbol),
        None,
    )
    if not row or row.get("status") != "TRADING":
        raise RuntimeError(f"{symbol} is absent or not TRADING in USD-M exchangeInfo")
    for item in row.get("filters", []):
        if item.get("filterType") == "PRICE_FILTER":
            tick = float(item.get("tickSize", "nan"))
            if math.isfinite(tick) and tick > 0:
                return tick
            break
    raise RuntimeError(f"{symbol} USD-M PRICE_FILTER tickSize is missing/invalid")


def _adjust_local_indicator_indices(frame: pd.DataFrame, offset: int) -> pd.DataFrame:
    """Convert indicator row references from the full frame to a causal slice."""
    if offset == 0:
        return frame
    result = frame.copy()
    for column in result.columns:
        if not column.endswith("_index"):
            continue
        values = pd.to_numeric(result[column], errors="coerce")
        result[column] = values.where(values.isna(), values - offset)
    return result


def extract_causal_h1_signals(
    indicators: pd.DataFrame,
    *,
    symbol: str,
    tick_size: float,
    window_bars: int = 180,
) -> dict[int, list]:
    """Build signals from a closed prefix only; later candles cannot alter an earlier decision."""
    if window_bars < 60:
        raise ValueError("Causal signal window must contain at least 60 H1 bars")
    signals: dict[int, list] = {}
    for i in range(len(indicators)):
        start = max(0, i + 1 - window_bars)
        window = _adjust_local_indicator_indices(indicators.iloc[start:i + 1], start)
        long = extract_long_signal_specs(
            symbol, window, timeframe="1h", tick_size=tick_size
        )
        short = extract_short_signal_specs(
            symbol, window, timeframe="1h", tick_size=tick_size
        )
        if long or short:
            # The canonical extractors define their preferred ordering. Preserve LONG
            # then SHORT order deterministically if both sides arise on the same close.
            signals[i] = list(long) + list(short)
    return signals


def _market_regime_frame(candles: pd.DataFrame) -> pd.DataFrame:
    """Label entry context by trailing 30-day trend and relative 14-day volatility."""
    returns = candles["close"].pct_change()
    trend = candles["close"].pct_change(24 * 30)
    volatility = returns.rolling(24 * 14, min_periods=24 * 7).std() * math.sqrt(24 * 365)
    # Rolling trailing thresholds avoid using future volatility to classify an older trade.
    low_q = volatility.rolling(24 * 90, min_periods=24 * 30).quantile(0.25)
    high_q = volatility.rolling(24 * 90, min_periods=24 * 30).quantile(0.75)
    trend_label = np.select(
        [trend > 0.05, trend < -0.05],
        ["TREND_UP_30D", "TREND_DOWN_30D"],
        default="RANGE_OR_WEAK_TREND",
    )
    vol_label = np.select(
        [volatility <= low_q, volatility >= high_q],
        ["LOW_VOL", "HIGH_VOL"],
        default="NORMAL_VOL",
    )
    return pd.DataFrame({
        "trend_30d_pct": trend * 100.0,
        "realized_vol_14d_annualized": volatility,
        "trend_regime": trend_label,
        "volatility_regime": vol_label,
    }, index=candles.index)


def _metrics_for_trades(trades: pd.DataFrame) -> dict:
    if trades.empty:
        return {
            "trades": 0, "wins": 0, "losses": 0, "win_rate_pct": 0.0,
            "net_pnl_usdt": 0.0, "fees_usdt": 0.0, "avg_r_multiple": 0.0,
            "profit_factor": 0.0,
        }
    pnl = pd.to_numeric(trades["net_pnl_usdt"], errors="coerce").dropna()
    win_sum = float(pnl[pnl > 0].sum())
    loss_sum = abs(float(pnl[pnl < 0].sum()))
    return {
        "trades": int(len(pnl)),
        "wins": int((pnl > 0).sum()),
        "losses": int((pnl < 0).sum()),
        "win_rate_pct": float((pnl > 0).mean() * 100.0),
        "net_pnl_usdt": float(pnl.sum()),
        "fees_usdt": float(pd.to_numeric(trades["fees_usdt"], errors="coerce").fillna(0).sum()),
        "avg_r_multiple": float(pd.to_numeric(trades["r_multiple"], errors="coerce").mean()),
        "profit_factor": win_sum / loss_sum if loss_sum > 0 else (float("inf") if win_sum > 0 else 0.0),
    }


def run_symbol_replay(
    symbol: str,
    candles: pd.DataFrame,
    *,
    tick_size: float,
    output_dir: Path,
    config: PaperReplayConfig,
    window_bars: int = 180,
) -> dict:
    if len(candles) < 60:
        raise ValueError(f"{symbol}: need at least 60 closed H1 bars; got {len(candles)}")
    cfg = canonical_indicator_config_from_env()
    indicators = calculate_indicators(candles, cfg)
    indicators = indicators.iloc[54:].copy()
    # Indicators were calculated before trimming warmup, so translate stored row
    # references into the trimmed frame's coordinate system.
    for column in indicators.columns:
        if column.endswith("_index"):
            values = pd.to_numeric(indicators[column], errors="coerce")
            indicators[column] = values.where(values.isna(), values - 54)
    candles = candles.loc[indicators.index].copy()
    signals = extract_causal_h1_signals(
        indicators, symbol=symbol, tick_size=tick_size, window_bars=window_bars
    )
    # Indicator warmup truncation changes the row coordinates, so all signal event
    # keys are now the indices of the matching closed-candle frame above.
    equity, trades, metrics = run_williams_paper_replay(candles, signals, config)

    regimes = _market_regime_frame(candles)
    if not trades.empty:
        trades["entry_time"] = pd.to_datetime(trades["entry_time"], utc=True)
        regime_records = []
        for _, trade in trades.iterrows():
            ts = trade["entry_time"]
            row = regimes.loc[ts] if ts in regimes.index else None
            regime_records.append({
                "trend_regime": str(row["trend_regime"]) if row is not None else "UNKNOWN",
                "volatility_regime": str(row["volatility_regime"]) if row is not None else "UNKNOWN",
                "trend_30d_pct": float(row["trend_30d_pct"]) if row is not None and pd.notna(row["trend_30d_pct"]) else None,
                "realized_vol_14d_annualized": float(row["realized_vol_14d_annualized"]) if row is not None and pd.notna(row["realized_vol_14d_annualized"]) else None,
            })
        for key in ("trend_regime", "volatility_regime", "trend_30d_pct", "realized_vol_14d_annualized"):
            trades[key] = [x[key] for x in regime_records]

    output_dir.mkdir(parents=True, exist_ok=True)
    trades_path = output_dir / f"{symbol}_trades.csv"
    equity_path = output_dir / f"{symbol}_equity.csv"
    trades.to_csv(trades_path, index=False)
    equity.to_csv(equity_path)
    # JSON output must never silently emit NaN/Infinity (e.g. profit factor with
    # no losing trades). Preserve an unbounded PF as null plus an explicit flag.
    if not math.isfinite(float(metrics.get("profit_factor", 0.0))):
        metrics["profit_factor_unbounded"] = True
        metrics["profit_factor"] = None
    for key, value in list(metrics.items()):
        if isinstance(value, (int, float, np.number)) and not math.isfinite(float(value)):
            metrics[key] = None
    metrics.update({
        "symbol": symbol,
        "timeframe": "H1",
        "bars": int(len(candles)),
        "bars_start_utc": candles.index[0].isoformat(),
        "bars_end_utc": candles.index[-1].isoformat(),
        "signals_created": int(sum(len(x) for x in signals.values())),
        "entry_risk_pct": config.risk_pct_per_entry,
        "fee_rate_per_side": config.fee_rate_per_side,
        "adverse_slippage_rate_per_side": config.slippage_rate_per_side,
        "tick_size_source": "Binance USD-M current exchangeInfo",
        "ohlc_source": "public Binance USD-M Futures historical klines; closed H1 candles only",
        "execution_model": "independent causal paper replay; stop-entry, structural stop, no fixed TP; conservative stop-first on intrabar ambiguity",
        "trade_metrics": _metrics_for_trades(trades),
        "trades_csv": str(trades_path),
        "equity_csv": str(equity_path),
    })
    metrics_path = output_dir / f"{symbol}_metrics.json"
    metrics_path.write_text(json.dumps(metrics, indent=2, allow_nan=False), encoding="utf-8")
    return metrics


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--symbols", default=",".join(APPROVED_SYMBOLS))
    parser.add_argument("--start", default="2023-01-01", help="UTC start date, inclusive")
    parser.add_argument("--end", default=pd.Timestamp.now(tz="UTC").date().isoformat(), help="UTC end date, exclusive")
    parser.add_argument("--outdir", default="results/williams_h1_paper_replay")
    parser.add_argument("--equity", type=float, default=5000.0)
    parser.add_argument("--risk-pct", type=float, default=0.002)
    parser.add_argument("--fee", type=float, default=0.0004, help="Per-side commission allowance, fraction of notional")
    parser.add_argument("--slippage", type=float, default=0.0005, help="Adverse per-side slippage, fraction of notional")
    parser.add_argument("--leverage", type=float, default=2.0)
    parser.add_argument("--window-bars", type=int, default=180)
    args = parser.parse_args(argv)

    symbols = [x.strip().upper() for x in args.symbols.split(",") if x.strip()]
    if not symbols or len(set(symbols)) != len(symbols) or any(x not in APPROVED_SYMBOLS for x in symbols):
        parser.error("--symbols must be a unique subset of the approved ten USD-M contracts")
    start_ms = int(pd.Timestamp(args.start, tz="UTC").timestamp() * 1000)
    end_ms = int(pd.Timestamp(args.end, tz="UTC").timestamp() * 1000)
    config = PaperReplayConfig(
        starting_equity_usdt=args.equity,
        risk_pct_per_entry=args.risk_pct,
        fee_rate_per_side=args.fee,
        slippage_rate_per_side=args.slippage,
        leverage=args.leverage,
    )
    config.validate()
    output = Path(args.outdir)
    output.mkdir(parents=True, exist_ok=True)
    reports = []
    session = requests.Session()
    for symbol in symbols:
        print(f"[H1 PAPER] {symbol}: downloading closed USD-M Futures history", flush=True)
        candles = fetch_usdm_h1_history(symbol, start_ms, end_ms, session=session)
        if candles.empty:
            raise RuntimeError(f"{symbol}: Binance returned no closed H1 candles for requested range")
        tick = futures_tick_size(symbol, session=session)
        report = run_symbol_replay(
            symbol, candles, tick_size=tick, output_dir=output, config=config,
            window_bars=args.window_bars,
        )
        reports.append(report)
        print(json.dumps(report, indent=2, allow_nan=False), flush=True)

    summary = {
        "scope": "independent single-symbol paper simulations; not a portfolio-combined account simulation",
        "symbols": symbols,
        "start_utc_inclusive": args.start,
        "end_utc_exclusive": args.end,
        "all_symbols": reports,
        "total_trades": sum(x["trade_metrics"]["trades"] for x in reports),
        "total_net_pnl_usdt_independent_accounts": sum(x["trade_metrics"]["net_pnl_usdt"] for x in reports),
    }
    summary_path = output / "summary.json"
    summary_path.write_text(json.dumps(summary, indent=2, allow_nan=False), encoding="utf-8")
    print(f"[H1 PAPER] summary: {summary_path}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
