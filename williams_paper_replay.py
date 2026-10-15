"""Exchange-free causal paper replay for Williams Futures campaigns.

Accepts SignalSpec events created from closed H1 candles. Simulates stop entries,
structural stops, monotonic 3–5 bar trailing, two-way LONG/SHORT PnL, fees and
adverse slippage. No fixed take-profit, no market chase, no exchange/UI calls.
"""
from __future__ import annotations

from dataclasses import dataclass
import math
from typing import Mapping, Sequence

import pandas as pd
from campaign_model import SignalRole, SignalSpec


@dataclass(frozen=True)
class PaperReplayConfig:
    starting_equity_usdt: float = 5_000.0
    risk_pct_per_entry: float = 0.002
    fee_rate_per_side: float = 0.0004
    slippage_rate_per_side: float = 0.0005
    leverage: float = 2.0
    margin_utilization: float = 0.95
    tick_size: float = 0.1
    trailing_lookback_bars: int = 5
    max_pending_bars: int = 20
    exit_on_opposite_signal: bool = True

    def validate(self) -> None:
        positive = (self.starting_equity_usdt, self.risk_pct_per_entry, self.leverage, self.tick_size)
        if any(not math.isfinite(float(x)) or float(x) <= 0 for x in positive):
            raise ValueError("starting equity, risk, leverage and tick size must be positive")
        if self.risk_pct_per_entry > 0.0025:
            raise ValueError("paper replay entry risk cannot exceed 0.25%")
        if not 0 < self.margin_utilization <= 1:
            raise ValueError("margin utilization must be in (0, 1]")
        if not 0 <= self.fee_rate_per_side < 0.1 or not 0 <= self.slippage_rate_per_side < 0.1:
            raise ValueError("fee/slippage assumption is invalid")
        if self.trailing_lookback_bars not in (3, 4, 5):
            raise ValueError("trailing lookback must be 3, 4 or 5 closed bars")
        if self.max_pending_bars < 1:
            raise ValueError("max pending bars must be positive")


def _move(side: str, entry: float, exit_price: float) -> float:
    return exit_price - entry if side == "BUY" else entry - exit_price


def _valid_signal(signal: SignalSpec, close: float) -> bool:
    if signal.role != SignalRole.ENTRY:
        return False
    try:
        trigger, stop = float(signal.trigger_price), float(signal.protective_reference)
    except (TypeError, ValueError):
        return False
    if min(trigger, stop) <= 0 or not pd.notna(trigger + stop):
        return False
    side = signal.side.upper()
    return (side == "BUY" and stop < trigger and close < trigger) or (
        side == "SELL" and stop > trigger and close > trigger
    )


def _entry_fill(side: str, row, trigger: float, slippage: float) -> float | None:
    o, h, l = float(row.open), float(row.high), float(row.low)
    if side == "BUY":
        if h < trigger:
            return None
        return max(o, trigger) * (1 + slippage)
    if l > trigger:
        return None
    return min(o, trigger) * (1 - slippage)


def _stop_fill(side: str, row, stop: float, slippage: float) -> float | None:
    o, h, l = float(row.open), float(row.high), float(row.low)
    if side == "BUY":
        if o <= stop:
            return o * (1 - slippage)
        return stop * (1 - slippage) if l <= stop else None
    if o >= stop:
        return o * (1 + slippage)
    return stop * (1 + slippage) if h >= stop else None


def run_williams_paper_replay(
    candles: pd.DataFrame,
    signals_by_bar: Mapping[int, Sequence[SignalSpec]],
    config: PaperReplayConfig | None = None,
) -> tuple[pd.DataFrame, pd.DataFrame, dict]:
    """Replay candles causally; signals at bar i can first fill on bar i+1.

    When entry and stop are both inside one OHLC candle, stop-first is assumed.
    This avoids optimistic intrabar ordering and does not substitute a market
    entry after the original conditional trigger has already been crossed.
    """
    cfg = config or PaperReplayConfig()
    cfg.validate()
    required = {"open", "high", "low", "close"}
    if required - set(candles.columns):
        raise ValueError("candles must contain open/high/low/close")
    if candles.empty:
        return pd.DataFrame(), pd.DataFrame(), {
            "trades": 0, "starting_equity_usdt": cfg.starting_equity_usdt,
            "ending_equity_usdt": cfg.starting_equity_usdt,
        }
    work = candles.copy()
    for col in required:
        work[col] = pd.to_numeric(work[col], errors="coerce")
    valid = work[list(required)].notna().all(axis=1) & (work[list(required)] > 0).all(axis=1)
    valid &= work.high >= work[["open", "close", "low"]].max(axis=1)
    valid &= work.low <= work[["open", "close", "high"]].min(axis=1)
    if not bool(valid.all()):
        raise ValueError("invalid OHLC data in paper replay")

    balance = cfg.starting_equity_usdt
    pending = None
    position = None
    used: set[str] = set()
    equity_rows, trade_rows = [], []
    fee, slip = cfg.fee_rate_per_side, cfg.slippage_rate_per_side

    def close_pos(exit_px: float, timestamp, i: int, reason: str) -> None:
        nonlocal position, balance
        p = position
        gross = _move(p["side"], p["entry"], exit_px) * p["qty"]
        exit_fee = exit_px * p["qty"] * fee
        balance += gross - exit_fee
        fees = p["entry_fee"] + exit_fee
        net = gross - fees
        trade_rows.append({
            "symbol": p["symbol"], "direction": "LONG" if p["side"] == "BUY" else "SHORT",
            "signal_type": p["signal_type"], "signal_id": p["signal_id"],
            "entry_time": p["entry_time"], "exit_time": timestamp,
            "trigger_price": p["trigger"], "entry_price": p["entry"],
            "exit_price": exit_px, "initial_stop": p["initial_stop"], "qty": p["qty"],
            "gross_pnl_usdt": gross, "fees_usdt": fees, "net_pnl_usdt": net,
            "initial_risk_usdt": p["initial_risk"], "r_multiple": net / p["initial_risk"],
            "entry_slippage_pct": ((p["entry"] - p["trigger"]) / p["trigger"]
                if p["side"] == "BUY" else (p["trigger"] - p["entry"]) / p["trigger"]),
            "exit_slippage_pct": (abs(exit_px - p["unadjusted_exit"]) / p["unadjusted_exit"]
                if p.get("unadjusted_exit", 0) > 0 else None),
            "exit_reason": reason, "bars_held": max(0, i - p["entry_i"]),
        })
        position = None

    for i, (timestamp, row) in enumerate(work.iterrows()):
        close = float(row.close)
        candidates = [
            x for x in signals_by_bar.get(i, ())
            if isinstance(x, SignalSpec) and _valid_signal(x, close)
        ]
        exited = False
        entered = False

        if position is not None:
            stop_px = _stop_fill(position["side"], row, position["stop"], slip)
            if stop_px is not None:
                close_pos(stop_px, timestamp, i, "STRUCTURAL_STOP")
                exited = True
            elif cfg.exit_on_opposite_signal and any(x.side.upper() != position["side"] for x in candidates):
                px = close * (1 - slip if position["side"] == "BUY" else 1 + slip)
                close_pos(px, timestamp, i, "OPPOSITE_WILLIAMS_SIGNAL")
                exited = True
            else:
                # The new stop is effective only from the next candle onward.
                frame = work.iloc[max(0, i - cfg.trailing_lookback_bars + 1):i + 1]
                if position["side"] == "BUY":
                    proposal = float(frame.low.min()) - cfg.tick_size
                    if position["stop"] < proposal < close:
                        position["stop"] = proposal
                else:
                    proposal = float(frame.high.max()) + cfg.tick_size
                    if close < proposal < position["stop"]:
                        position["stop"] = proposal

        if position is None and pending is not None and not exited:
            if i - pending["created_i"] > cfg.max_pending_bars:
                pending = None
            elif i > pending["created_i"]:
                entry = _entry_fill(pending["side"], row, pending["trigger"], slip)
                if entry is not None:
                    stop_exit = pending["stop"] * (1 - slip if pending["side"] == "BUY" else 1 + slip)
                    loss_per_unit = (
                        entry - stop_exit if pending["side"] == "BUY" else stop_exit - entry
                    ) + (entry + stop_exit) * fee
                    if loss_per_unit <= 0 or not pd.notna(loss_per_unit):
                        pending = None
                    else:
                        risk_budget = balance * cfg.risk_pct_per_entry
                        qty_risk = risk_budget / loss_per_unit
                        qty_margin = balance * cfg.leverage * cfg.margin_utilization / entry
                        qty = min(qty_risk, qty_margin)
                        if qty <= 0 or not pd.notna(qty):
                            pending = None
                        else:
                            entry_fee = entry * qty * fee
                            position = {
                                "symbol": pending["symbol"], "side": pending["side"],
                                "signal_id": pending["signal_id"], "signal_type": pending["signal_type"],
                                "trigger": pending["trigger"], "entry": entry, "qty": qty,
                                "entry_fee": entry_fee, "initial_risk": loss_per_unit * qty,
                                "initial_stop": pending["stop"], "stop": pending["stop"],
                                "entry_time": timestamp, "entry_i": i,
                            }
                            balance -= entry_fee
                            pending = None
                            entered = True
                            # Conservative fill assumption when OHLC spans entry and stop.
                            same_bar_stop = _stop_fill(position["side"], row, position["stop"], slip)
                            if same_bar_stop is not None:
                                close_pos(same_bar_stop, timestamp, i, "SAME_BAR_STOP_FIRST")
                                exited = True

        # Signal is known only at this bar's close. Never fill it retroactively.
        if position is None and not entered and not exited:
            fresh = [x for x in candidates if x.signal_id not in used]
            if fresh:
                signal = fresh[0]  # extractor order is the strategy's priority order
                if pending is None or signal.signal_bar_time_ms > pending["signal_time_ms"]:
                    used.add(signal.signal_id)
                    pending = {
                        "symbol": signal.symbol, "side": signal.side.upper(),
                        "signal_id": signal.signal_id, "signal_type": signal.signal_type.value,
                        "signal_time_ms": signal.signal_bar_time_ms, "created_i": i,
                        "trigger": float(signal.trigger_price), "stop": float(signal.protective_reference),
                    }

        unrealized = _move(position["side"], position["entry"], close) * position["qty"] if position else 0.0
        equity_rows.append({
            "time": timestamp, "balance_usdt": balance, "unrealized_pnl_usdt": unrealized,
            "equity_usdt": balance + unrealized,
            "position": ("LONG" if position["side"] == "BUY" else "SHORT") if position else "FLAT",
            "pending_signal_id": pending["signal_id"] if pending else "",
        })

    if position is not None:
        last_time, last_close = work.index[-1], float(work.close.iloc[-1])
        exit_px = last_close * (1 - slip if position["side"] == "BUY" else 1 + slip)
        position["unadjusted_exit"] = last_close
        close_pos(exit_px, last_time, len(work) - 1, "END_OF_DATA")
        # Keep the realized ending equity consistent with the forced end-of-data close.
        if equity_rows:
            equity_rows[-1]["balance_usdt"] = balance
            equity_rows[-1]["unrealized_pnl_usdt"] = 0.0
            equity_rows[-1]["equity_usdt"] = balance
            equity_rows[-1]["position"] = "FLAT"

    equity = pd.DataFrame(equity_rows).set_index("time")
    trades = pd.DataFrame(trade_rows)
    if trades.empty:
        trades = pd.DataFrame(columns=[
            "symbol", "direction", "signal_type", "signal_id", "entry_time", "exit_time",
            "trigger_price", "entry_price", "exit_price", "initial_stop", "qty",
            "gross_pnl_usdt", "fees_usdt", "net_pnl_usdt", "initial_risk_usdt",
            "r_multiple", "entry_slippage_pct", "exit_slippage_pct", "exit_reason", "bars_held",
        ])
    pnl = pd.to_numeric(trades.get("net_pnl_usdt", pd.Series(dtype=float)), errors="coerce").fillna(0)
    wins, losses = int((pnl > 0).sum()), int((pnl < 0).sum())
    gross_win, gross_loss = float(pnl[pnl > 0].sum()), abs(float(pnl[pnl < 0].sum()))
    curve = pd.to_numeric(equity.equity_usdt, errors="coerce")
    drawdown = curve / curve.cummax() - 1.0
    ending = float(balance)
    metrics = {
        "starting_equity_usdt": cfg.starting_equity_usdt, "ending_equity_usdt": ending,
        "net_pnl_usdt": ending - cfg.starting_equity_usdt,
        "net_return_pct": (ending / cfg.starting_equity_usdt - 1) * 100,
        "max_drawdown_pct": float(drawdown.min() * 100) if len(drawdown) else 0.0,
        "trades": len(trades), "wins": wins, "losses": losses,
        "win_rate_pct": wins / len(trades) * 100 if len(trades) else 0.0,
        "profit_factor": gross_win / gross_loss if gross_loss else (float("inf") if wins else 0.0),
        "fees_usdt": float(trades.fees_usdt.sum()) if len(trades) else 0.0,
        "avg_r_multiple": float(trades.r_multiple.mean()) if len(trades) else 0.0,
        "fee_rate_per_side": fee, "adverse_slippage_rate_per_side": slip,
        "risk_pct_per_entry": cfg.risk_pct_per_entry,
        "methodology": "CAUSAL_H1_STOP_ENTRY_STRUCTURAL_STOP_NO_FIXED_TP_STOP_FIRST_INTRABAR",
    }
    return equity, trades, metrics
