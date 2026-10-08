"""Event-driven Williams Intraday backtester.

Truth is created only on closed H1 candles. M15 is the execution timeframe;
M5 is used only to reconstruct the intrabar path once an M15 bar indicates a
trigger/stop interaction. Fixed percentage take-profit is intentionally absent.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass
from datetime import datetime, timezone
import pandas as pd

from campaign_model import SignalType
from williams_intraday_core import WilliamsIntradayCore
from williams_intraday_spec import IntradayPolicy

@dataclass(frozen=True)
class SimFill:
    time: object
    signal_type: str
    price: float
    quantity: float
    step: int

@dataclass
class SimTrade:
    symbol: str
    entry_time: object
    entry_price: float
    exit_time: object
    exit_price: float
    quantity: float
    pnl_quote: float
    reason: str
    fills: list[dict]

class WilliamsCampaignBacktester:
    def __init__(self, *, starting_equity=500, fee_pct=.001, slippage_pct=.0005,
                 risk_per_initial_entry=None, campaign_risk_pct=None, policy=None):
        self.policy = policy or IntradayPolicy.from_env()
        self.equity = float(starting_equity)
        self.fee_pct = float(fee_pct)
        self.slippage_pct = float(slippage_pct)
        self.initial_risk = risk_per_initial_entry or self.policy.risk.initial_risk_pct
        self.campaign_risk = campaign_risk_pct or self.policy.risk.campaign_risk_pct
        self.core = WilliamsIntradayCore(self.policy)

    @staticmethod
    def _ts(value):
        t = pd.Timestamp(value)
        return t.tz_localize("UTC") if t.tzinfo is None else t.tz_convert("UTC")

    @staticmethod
    def _bars_between(bars, start, end):
        idx = pd.to_datetime(bars.index, utc=True)
        mask = idx > start
        if end is not None:
            mask &= idx <= end
        return bars.loc[mask]

    @staticmethod
    def _micro_slice(m5, start, end):
        if m5 is None or len(m5) == 0:
            return None
        idx = pd.to_datetime(m5.index, utc=True)
        mask = (idx >= start) & (idx < end)
        out = m5.loc[mask]
        return out if len(out) else None

    @classmethod
    def _resolve_buy_trigger(cls, m15_bar, trigger, m5=None):
        start = cls._ts(m15_bar.name)
        end = start + pd.Timedelta(minutes=15)
        o = float(m15_bar["open"])
        h = float(m15_bar["high"])
        if o >= trigger:
            return start, o
        if h < trigger:
            return None, 0.0
        micro = cls._micro_slice(m5, start, end)
        if micro is None:
            return start, trigger
        for ts, bar in micro.iterrows():
            ts = cls._ts(ts)
            if float(bar["open"]) >= trigger:
                return ts, float(bar["open"])
            if float(bar["high"]) >= trigger:
                return ts, trigger
        return start, trigger

    @classmethod
    def _resolve_stop(cls, m15_bar, stop, m5=None, after=None):
        start = cls._ts(m15_bar.name)
        end = start + pd.Timedelta(minutes=15)
        if float(m15_bar["low"]) > stop:
            return None, 0.0
        micro = cls._micro_slice(m5, start, end)
        if micro is None:
            # With only M15 OHLC the order inside the bar is ambiguous. Do not
            # manufacture a stop hit on the same bar as a newly created entry.
            if after is not None and cls._ts(after) >= start:
                return None, 0.0
            return start, stop
        for ts, bar in micro.iterrows():
            ts = cls._ts(ts)
            if after is not None and ts <= cls._ts(after):
                continue
            if float(bar["low"]) <= stop:
                return ts, stop
        return start, stop

    @staticmethod
    def _weighted_add_risk_pct(equity, used_risk, campaign_cap, tranche_index):
        if equity <= 0:
            return 0.0
        weights = (1, 5, 4, 3, 2)
        idx = min(max(int(tranche_index), 0), 4)
        remaining = max(0.0, equity * campaign_cap - used_risk)
        weighted = equity * campaign_cap * (weights[idx] / sum(weights))
        return max(0.0, min(remaining, weighted)) / equity

    def run(self, symbol, h1, m15, m5=None, *, h4=None, d1=None, tick_size=.01):
        if h1 is None or len(h1) < 80:
            return {"status": "INSUFFICIENT_DATA", "equity": self.equity, "trades": [], "fills": []}
        h1 = h1.sort_index()
        m15 = m15.sort_index()
        m5 = m5.sort_index() if m5 is not None else None
        position = None
        pending = None
        fills = []
        trades = []
        last_signal_time = 0

        for i in range(60, len(h1)):
            h1_slice = h1.iloc[:i + 1]
            h1_close = self._ts(h1_slice.index[-1])
            next_h1 = self._ts(h1.index[i + 1]) if i + 1 < len(h1) else None
            session = self.policy.session.state(h1_close.to_pydatetime())

            if session in {"MANAGE_ONLY", "FLAT_REQUIRED"} and position is None:
                pending = None

            decision = self.core.evaluate(symbol, h1_slice, h4=h4, d1=d1, tick_size=tick_size)

            if position is None and pending is None and session == "ENTRY_WINDOW":
                candidates = list(decision.signal_specs)
                chosen = next((x for x in candidates if x.signal_type == SignalType.REVERSAL), None)
                if chosen is None:
                    chosen = next((x for x in candidates if x.signal_type == SignalType.SUPER_AO), None)
                if chosen is None and self.policy.wm3_first_allowed:
                    chosen = next((x for x in candidates if x.signal_type == SignalType.FRACTAL), None)
                if chosen is not None:
                    pending = {"spec": chosen, "step": 1}

            if position is not None and pending is None:
                later = [
                    x for x in decision.signal_specs
                    if x.signal_type in {SignalType.SUPER_AO, SignalType.FRACTAL}
                    and x.signal_bar_time_ms > last_signal_time
                ]
                if later:
                    spec = min(later, key=lambda x: (x.signal_bar_time_ms, x.created_at_ms))
                    pending = {"spec": spec, "step": position["tranche_index"] + 1}

            for ts, bar in self._bars_between(m15, h1_close, next_h1).iterrows():
                ts = self._ts(ts)
                fill_time_this_bar = None

                if pending is not None:
                    spec = pending["spec"]
                    action_time, raw_price = self._resolve_buy_trigger(bar, float(spec.trigger_price), m5)
                    if action_time is not None and action_time <= ts + pd.Timedelta(minutes=15):
                        fill_price = raw_price * (1.0 + self.slippage_pct)
                        stop = float(spec.protective_reference)
                        if position is None:
                            risk_quote = self.equity * self.initial_risk
                            qty = risk_quote / max(fill_price - stop, 1e-12)
                            step = 1
                            signal_type = spec.signal_type.value
                            position = {
                                "entry_time": action_time,
                                "entry_price": fill_price,
                                "stop": stop,
                                "qty": qty,
                                "tranche_index": 1,
                                "fills": [],
                                "last_signal_time": int(spec.signal_bar_time_ms),
                            }
                        else:
                            used = max(0.0, (position["entry_price"] - position["stop"]) * position["qty"])
                            risk_pct = self._weighted_add_risk_pct(
                                self.equity, used, self.campaign_risk, position["tranche_index"]
                            )
                            risk_quote = self.equity * risk_pct
                            step = position["tranche_index"] + 1
                            qty = risk_quote / max(fill_price - position["stop"], 1e-12)
                            old_qty = position["qty"]
                            position["entry_price"] = (
                                old_qty * position["entry_price"] + qty * fill_price
                            ) / max(old_qty + qty, 1e-12)
                            position["qty"] += qty
                            position["tranche_index"] = min(5, step)
                            position["last_signal_time"] = int(spec.signal_bar_time_ms)
                            signal_type = spec.signal_type.value

                        fill = asdict(SimFill(action_time, signal_type, raw_price, qty, step))
                        position["fills"].append(fill)
                        fills.append(fill)
                        pending = None
                        last_signal_time = int(spec.signal_bar_time_ms)
                        fill_time_this_bar = action_time
                        if action_time < ts:
                            # A micro-replay fill already occurred inside this
                            # M15 bar; continue so the same path may also prove
                            # a stop interaction conservatively.
                            pass

                if position is not None:
                    stop_time, stop_px = self._resolve_stop(bar, float(position["stop"]), m5, after=fill_time_this_bar)
                    if stop_time is not None:
                        exit_price = stop_px * (1.0 - self.slippage_pct)
                        qty = float(position["qty"])
                        pnl = (
                            (exit_price - position["entry_price"]) * qty
                            - (position["entry_price"] * qty + exit_price * qty) * self.fee_pct
                        )
                        self.equity += pnl
                        trades.append(asdict(SimTrade(
                            symbol, position["entry_time"], position["entry_price"],
                            stop_time, exit_price, qty, pnl, "STRUCTURAL_STOP", list(position["fills"])
                        )))
                        position = None
                        pending = None

            if position is not None:
                recent = h1_slice.tail(5)
                proposed = min(
                    max(float(x) for x in recent["low"]),
                    float(h1_slice["close"].iloc[-1]) - tick_size,
                )
                if proposed > position["stop"]:
                    position["stop"] = proposed

            if self.policy.session.requires_flat(h1_close.to_pydatetime()):
                pending = None
                if position is not None:
                    exit_price = float(h1_slice["close"].iloc[-1]) * (1.0 - self.slippage_pct)
                    qty = float(position["qty"])
                    pnl = (
                        (exit_price - position["entry_price"]) * qty
                        - (position["entry_price"] * qty + exit_price * qty) * self.fee_pct
                    )
                    self.equity += pnl
                    trades.append(asdict(SimTrade(
                        symbol, position["entry_time"], position["entry_price"],
                        h1_close, exit_price, qty, pnl, "EOD_FLAT", list(position["fills"])
                    )))
                    position = None

        return {
            "status": "OK",
            "equity": self.equity,
            "trades": trades,
            "fills": fills,
            "open_position": position is not None,
        }
