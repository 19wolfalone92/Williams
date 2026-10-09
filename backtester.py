"""Deterministic, long-only OHLC backtester with explicit conservative fills.

This is a research simulator, not an exchange execution emulator. Short-side
simulation is deliberately rejected until its funding/margin/accounting model
is implemented rather than silently ignored.
"""
from dataclasses import dataclass
import math

import numpy as np
import pandas as pd


@dataclass
class Trade:
    entry_time: object
    exit_time: object
    side: str
    entry_price: float
    exit_price: float
    qty: float
    pnl: float
    pnl_pct: float
    reason: str
    fees: float


class Backtester:
    def __init__(
        self,
        starting_capital=1000,
        fee_rate=0.001,
        slippage_rate=0.0005,
        position_fraction=1,
        stop_loss_pct=0.02,
        take_profit_pct=0.04,
        allow_shorts=False,
        intrabar_exit_policy="stop_first",
    ):
        values = {
            "starting_capital": starting_capital,
            "fee_rate": fee_rate,
            "slippage_rate": slippage_rate,
            "position_fraction": position_fraction,
            "stop_loss_pct": stop_loss_pct,
            "take_profit_pct": take_profit_pct,
        }
        parsed = {}
        for name, value in values.items():
            try:
                parsed[name] = float(value)
            except (TypeError, ValueError, OverflowError) as exc:
                raise ValueError(f"{name} must be numeric") from exc
            if not math.isfinite(parsed[name]):
                raise ValueError(f"{name} must be finite")

        if parsed["starting_capital"] <= 0:
            raise ValueError("starting_capital must be positive")
        if not 0 <= parsed["fee_rate"] < 1:
            raise ValueError("fee_rate must be in [0, 1)")
        if not 0 <= parsed["slippage_rate"] < 1:
            raise ValueError("slippage_rate must be in [0, 1)")
        if not 0 < parsed["position_fraction"] <= 1:
            raise ValueError("position_fraction must be in (0, 1]")
        if not 0 < parsed["stop_loss_pct"] < 1:
            raise ValueError("stop_loss_pct must be in (0, 1)")
        if parsed["take_profit_pct"] <= 0:
            raise ValueError("take_profit_pct must be positive")
        if allow_shorts:
            raise NotImplementedError(
                "Short backtesting is not implemented; allow_shorts=True is unsafe"
            )
        if intrabar_exit_policy not in {"stop_first", "target_first"}:
            raise ValueError("intrabar_exit_policy must be stop_first or target_first")

        self.starting_capital = parsed["starting_capital"]
        self.fee = parsed["fee_rate"]
        self.slippage = parsed["slippage_rate"]
        self.position_fraction = parsed["position_fraction"]
        self.stop = parsed["stop_loss_pct"]
        self.target = parsed["take_profit_pct"]
        self.allow_shorts = False
        self.intrabar_policy = intrabar_exit_policy

    def _buy_price(self, price):
        return float(price) * (1 + self.slippage)

    def _sell_price(self, price):
        return float(price) * (1 - self.slippage)

    @staticmethod
    def _validate_frame(df):
        if not isinstance(df, pd.DataFrame):
            raise TypeError("Backtest input must be a pandas DataFrame")
        if df.empty:
            return
        missing = {"open", "high", "low", "close", "long_signal"} - set(df.columns)
        if missing:
            raise ValueError(f"Backtest data missing columns: {sorted(missing)}")
        if not df.index.is_monotonic_increasing or not df.index.is_unique:
            raise ValueError("Backtest index must be strictly increasing and unique")
        prices = df[["open", "high", "low", "close"]].apply(
            pd.to_numeric, errors="coerce"
        )
        values = prices.to_numpy(dtype=float)
        if not np.isfinite(values).all() or (values <= 0).any():
            raise ValueError("OHLC prices must be finite and positive")
        invalid = (
            (prices["high"] < prices[["open", "close", "low"]].max(axis=1))
            | (prices["low"] > prices[["open", "close", "high"]].min(axis=1))
        )
        if bool(invalid.any()):
            raise ValueError("OHLC invariant failed: high/low do not contain open/close")

    def run(self, df):
        self._validate_frame(df)
        if df.empty:
            empty_equity = pd.DataFrame(columns=["equity"])
            empty_equity.index.name = "time"
            empty_equity.attrs["starting_capital"] = self.starting_capital
            empty_trades = pd.DataFrame(columns=list(Trade.__dataclass_fields__))
            return empty_equity, empty_trades

        cash = self.starting_capital
        qty = 0.0
        side = None
        entry_price = None
        entry_time = None
        entry_notional = 0.0
        entry_fee = 0.0
        trades = []
        equity = []
        pending = False

        for i in range(len(df)):
            row = df.iloc[i]
            timestamp = df.index[i]
            open_price, high, low, close = map(
                float, [row.open, row.high, row.low, row.close]
            )

            # A signal observed at the prior candle close can enter only at this
            # candle's open; never fill a newly observed signal on the same bar.
            if pending and side is None:
                spend = cash * self.position_fraction
                fill = self._buy_price(open_price)
                if spend > 0 and fill > 0:
                    entry_fee = spend * self.fee
                    qty = (spend - entry_fee) / fill
                    if qty > 0 and math.isfinite(qty):
                        cash -= spend
                        entry_price = fill
                        entry_notional = qty * entry_price
                        entry_time = timestamp
                        side = "LONG"
                pending = False

            if side == "LONG":
                stop_price = entry_price * (1 - self.stop)
                target_price = entry_price * (1 + self.target)
                stop_hit = low <= stop_price
                target_hit = high >= target_price
                exit_trigger = None
                reason = None

                if stop_hit and target_hit:
                    if self.intrabar_policy == "target_first":
                        exit_trigger, reason = target_price, "TARGET"
                    else:
                        exit_trigger, reason = stop_price, "STOP"
                elif stop_hit:
                    # Gap-through stop: model the open, not the unreachable stop.
                    exit_trigger = min(stop_price, open_price)
                    reason = "STOP"
                elif target_hit:
                    # Conservative limit assumption: never improve beyond target.
                    exit_trigger, reason = target_price, "TARGET"

                if exit_trigger is not None:
                    exit_price = self._sell_price(exit_trigger)
                    proceeds = qty * exit_price
                    exit_fee = proceeds * self.fee
                    cash += proceeds - exit_fee
                    pnl = (exit_price - entry_price) * qty - entry_fee - exit_fee
                    trades.append(
                        Trade(
                            entry_time, timestamp, side, entry_price, exit_price,
                            qty, pnl, pnl / max(entry_notional, 1e-12) * 100,
                            reason, entry_fee + exit_fee,
                        )
                    )
                    qty = 0.0
                    side = None
                    entry_price = None
                    entry_time = None
                    entry_notional = 0.0
                    entry_fee = 0.0

            equity.append((timestamp, cash + qty * close if side == "LONG" else cash))
            if side is None and bool(row.get("long_signal", False)):
                pending = True

        # Synthetic end-of-data close is explicitly labelled END in trade output.
        if side == "LONG":
            exit_price = self._sell_price(float(df.close.iloc[-1]))
            proceeds = qty * exit_price
            exit_fee = proceeds * self.fee
            cash += proceeds - exit_fee
            pnl = (exit_price - entry_price) * qty - entry_fee - exit_fee
            trades.append(
                Trade(
                    entry_time, df.index[-1], side, entry_price, exit_price, qty,
                    pnl, pnl / max(entry_notional, 1e-12) * 100, "END",
                    entry_fee + exit_fee,
                )
            )
            equity[-1] = (df.index[-1], cash)

        equity_frame = pd.DataFrame(equity, columns=["time", "equity"]).set_index("time")
        equity_frame.attrs["starting_capital"] = self.starting_capital
        trades_frame = pd.DataFrame([trade.__dict__ for trade in trades])
        if trades_frame.empty:
            trades_frame = pd.DataFrame(columns=list(Trade.__dataclass_fields__))
        return equity_frame, trades_frame


def calculate_metrics(equity, trades, interval="1h", starting_capital=None):
    if equity is None or equity.empty:
        return {}
    values = pd.to_numeric(equity["equity"], errors="coerce").to_numpy(dtype=float)
    if not np.isfinite(values).all():
        raise ValueError("Equity curve contains non-finite values")
    initial = starting_capital
    if initial is None:
        initial = equity.attrs.get("starting_capital")
    if initial is None:
        raise ValueError("starting_capital is required for a valid return baseline")
    initial = float(initial)
    if not math.isfinite(initial) or initial <= 0:
        raise ValueError("starting_capital must be finite and positive")

    end = float(values[-1])
    total_return = end / initial - 1.0
    curve = pd.Series(values, index=equity.index, dtype=float)
    peak = pd.concat(
        [pd.Series([initial], dtype=float), curve.reset_index(drop=True)],
        ignore_index=True,
    ).cummax()
    drawdown_values = pd.concat(
        [pd.Series([initial], dtype=float), curve.reset_index(drop=True)],
        ignore_index=True,
    ) / peak - 1.0
    max_drawdown = float(drawdown_values.min())

    trades = trades if trades is not None else pd.DataFrame()
    if not trades.empty and "pnl" not in trades.columns:
        raise ValueError("Trade table must contain pnl")
    pnl = pd.to_numeric(trades["pnl"], errors="coerce") if not trades.empty else pd.Series(dtype=float)
    if len(pnl) and not np.isfinite(pnl.to_numpy(dtype=float)).all():
        raise ValueError("Trade table contains non-finite PnL")
    winners = pnl[pnl > 0]
    losers = pnl[pnl < 0]
    gross_profit = float(winners.sum())
    gross_loss = float(losers.sum())
    trade_count = int(len(pnl))
    if trade_count == 0:
        profit_factor = None
    elif gross_loss < 0:
        profit_factor = gross_profit / abs(gross_loss)
    elif gross_profit > 0:
        profit_factor = float("inf")
    else:
        profit_factor = None

    periods_per_year = {
        "1m": 525600, "3m": 175200, "5m": 105120, "15m": 35040,
        "30m": 17520, "1h": 8760, "2h": 4380, "4h": 2190, "1d": 365,
    }.get(str(interval).lower(), 8760)
    # Include the return from initial capital to the first sampled equity point.
    returns = np.concatenate(([values[0] / initial - 1.0], curve.pct_change().dropna().to_numpy()))
    std = float(np.std(returns, ddof=1)) if len(returns) > 1 else 0.0
    sharpe = (
        float(np.sqrt(periods_per_year) * np.mean(returns) / std)
        if len(returns) > 1 and std > 0 and math.isfinite(std)
        else 0.0
    )

    index = pd.to_datetime(equity.index, utc=True, errors="coerce")
    if index.isna().any():
        raise ValueError("Equity timestamps must be valid")
    elapsed_days = (
        max((index[-1] - index[0]).total_seconds() / 86400.0, 0.0)
        if len(index) > 1 else 0.0
    )
    cagr = (
        ((end / initial) ** (365.25 / elapsed_days) - 1.0) * 100.0
        if elapsed_days > 0 and end > 0
        else None
    )
    expectancy_usd = float(pnl.mean()) if trade_count else 0.0
    avg_trade_return_pct = (
        float(pd.to_numeric(trades["pnl_pct"], errors="coerce").mean())
        if trade_count and "pnl_pct" in trades.columns else 0.0
    )

    return {
        "starting_capital": initial,
        "ending_equity": end,
        "total_return_pct": total_return * 100.0,
        "cagr_pct": cagr,
        "max_drawdown_pct": max_drawdown * 100.0,
        "trades": trade_count,
        "wins": int(len(winners)),
        "losses": int(len(losers)),
        "win_rate_pct": len(winners) / trade_count * 100.0 if trade_count else 0.0,
        "profit_factor": profit_factor,
        "gross_profit": gross_profit,
        "gross_loss": gross_loss,
        "avg_trade": expectancy_usd,
        "expectancy_usd": expectancy_usd,
        "expectancy_pct": expectancy_usd / initial * 100.0,
        "avg_trade_return_pct": avg_trade_return_pct,
        "fees": float(pd.to_numeric(trades["fees"], errors="coerce").sum())
        if trade_count and "fees" in trades.columns else 0.0,
        "sharpe": sharpe,
    }
