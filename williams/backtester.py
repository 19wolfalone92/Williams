"""Deterministic H1 -> M15 -> M5 campaign replay engine.

The backtester intentionally models information boundaries rather than
assuming perfect candle fills. H1 creates strategy truth. M15 supplies the
execution clock. M5, when available, supplies a finer path. Ambiguous
same-bar trigger/stop events are recorded instead of silently choosing the
profitable ordering.
"""
from __future__ import annotations
from dataclasses import dataclass, field
from datetime import datetime, timezone
import pandas as pd

from .allocation import size_for_risk
from .campaign import next_step, step_for_first_signal
from .intraday_policy import IntradayPolicy


@dataclass(frozen=True)
class BacktestFill:
    timestamp_ms: int
    price: float
    quantity: float
    reason: str
    signal_id: str = ""
    signal_type: str = ""
    gap: bool = False


@dataclass(frozen=True)
class BacktestExit:
    timestamp_ms: int
    price: float
    reason: str
    quantity: float


@dataclass
class BacktestResult:
    fills: list[BacktestFill] = field(default_factory=list)
    exits: list[BacktestExit] = field(default_factory=list)
    blocked: list[dict] = field(default_factory=list)
    decisions: list[dict] = field(default_factory=list)
    events: list[dict] = field(default_factory=list)


class WilliamsCampaignBacktester:
    def __init__(
        self,
        core,
        policy: IntradayPolicy | None = None,
        *,
        equity: float = 1000.0,
        initial_risk_pct: float = 0.0025,
        campaign_risk_pct: float = 0.006,
        fee_pct: float = 0.001,
        slippage_pct: float = 0.0015,
    ):
        self.core = core
        self.policy = policy or IntradayPolicy()
        self.equity = float(equity)
        self.initial_risk_pct = float(initial_risk_pct)
        self.campaign_risk_pct = float(campaign_risk_pct)
        self.fee_pct = float(fee_pct)
        self.slippage_pct = float(slippage_pct)

    @staticmethod
    def _time_key(frame: pd.DataFrame) -> str | None:
        return next((k for k in ("open_time_ms", "time_ms", "timestamp", "open_time") if k in frame.columns), None)

    @classmethod
    def _time_ms(cls, row, fallback: int) -> int:
        for key in ("open_time_ms", "time_ms", "timestamp", "open_time"):
            value = row.get(key)
            if value is not None and not pd.isna(value):
                try:
                    return int(value)
                except (TypeError, ValueError):
                    pass
        return int(fallback)

    @classmethod
    def _bars_between(cls, frame: pd.DataFrame | None, start_ms: int, end_ms: int) -> list[tuple[int, object]]:
        if frame is None or frame.empty:
            return []
        rows = []
        for i, row in frame.iterrows():
            t = cls._time_ms(row, int(i) if isinstance(i, (int, float)) else 0)
            if start_ms <= t < end_ms:
                rows.append((t, row))
        rows.sort(key=lambda item: item[0])
        return rows

    @staticmethod
    def _trigger_path(rows, trigger: float, side: str = "LONG", same_bar_policy: str = "AMBIGUOUS") -> dict:
        side = str(side).upper()
        for t, row in rows:
            op = float(row.get("open", 0.0))
            hi = float(row.get("high", 0.0))
            lo = float(row.get("low", 0.0))
            if side == "LONG":
                if op >= trigger:
                    return {"status": "FILLED", "timestamp_ms": t, "price": op, "gap": op > trigger}
                if hi >= trigger:
                    return {"status": "FILLED", "timestamp_ms": t, "price": trigger, "gap": False}
            else:
                if op <= trigger:
                    return {"status": "FILLED", "timestamp_ms": t, "price": op, "gap": op < trigger}
                if lo <= trigger:
                    return {"status": "FILLED", "timestamp_ms": t, "price": trigger, "gap": False}
        return {"status": "NOT_TRIGGERED"}

    @staticmethod
    def _stop_path(rows, start_ms: int, stop: float, side: str = "LONG") -> dict:
        side = str(side).upper()
        for t, row in rows:
            op = float(row.get("open", 0.0))
            hi = float(row.get("high", 0.0))
            lo = float(row.get("low", 0.0))
            if t < start_ms:
                continue
            if side == "LONG":
                if op <= stop:
                    return {"status": "STOPPED", "timestamp_ms": t, "price": op, "gap": op < stop}
                if lo <= stop:
                    return {"status": "STOPPED", "timestamp_ms": t, "price": stop, "gap": False}
            else:
                if op >= stop:
                    return {"status": "STOPPED", "timestamp_ms": t, "price": op, "gap": op > stop}
                if hi >= stop:
                    return {"status": "STOPPED", "timestamp_ms": t, "price": stop, "gap": False}
        return {"status": "OPEN"}

    @classmethod
    def _resolve_trigger_and_stop(cls, rows, trigger: float, stop: float, side: str = "LONG") -> dict:
        """Resolve an entry using the ordered micro bars.

        When one M5/OHLC bar contains both trigger and stop before a known fill,
        the exact order is unknowable from OHLC alone; the event is explicitly
        marked ambiguous instead of inventing a favorable path.
        """
        side = str(side).upper()
        for t, row in rows:
            op = float(row.get("open", 0.0)); hi = float(row.get("high", 0.0)); lo = float(row.get("low", 0.0))
            if side == "LONG" and hi >= trigger and lo <= stop and op < trigger:
                return {"status": "AMBIGUOUS_SAME_BAR", "timestamp_ms": t, "price": 0.0}
            if side == "SHORT" and lo <= trigger and hi >= stop and op > trigger:
                return {"status": "AMBIGUOUS_SAME_BAR", "timestamp_ms": t, "price": 0.0}
            found = cls._trigger_path([(t, row)], trigger, side)
            if found["status"] == "FILLED":
                return found
        return {"status": "NOT_TRIGGERED"}

    def replay_pending_entry(
        self,
        *,
        signal_time_ms: int,
        next_h1_time_ms: int,
        trigger: float,
        stop: float,
        m15: pd.DataFrame | None,
        m5: pd.DataFrame | None,
        side: str = "LONG",
    ) -> dict:
        micro = m5 if m5 is not None and not m5.empty else m15
        rows = self._bars_between(micro, signal_time_ms + 1, next_h1_time_ms)
        result = self._resolve_trigger_and_stop(rows, float(trigger), float(stop), side)
        if result["status"] != "FILLED":
            return {"entry": result, "exit": {"status": "NOT_OPEN"}}
        fill_t = int(result["timestamp_ms"])
        post_rows = self._bars_between(micro, fill_t, next_h1_time_ms)
        exit_result = self._stop_path(post_rows, fill_t, float(stop), side)
        return {"entry": result, "exit": exit_result}

    def run(self, h1: pd.DataFrame, m15: pd.DataFrame | None = None, m5: pd.DataFrame | None = None, *, symbol: str = "BTCUSDT", tick_size: float = 0.0) -> BacktestResult:
        out = BacktestResult()
        if h1 is None or len(h1) < 50:
            out.blocked.append({"reason": "INSUFFICIENT_H1_HISTORY"})
            return out
        for i in range(49, len(h1)):
            closed = h1.iloc[: i + 1]
            ev = self.core.evaluate(closed, symbol=symbol, tick_size=tick_size)
            out.decisions.append(ev.decision.to_dict())
            if not ev.signals:
                continue
            signal = ev.signals[0]
            signal_t = int(signal.signal_bar_time_ms)
            if i + 1 >= len(h1):
                continue
            next_h1_t = self._time_ms(h1.iloc[i + 1], signal_t + 3600000)
            start_dt = datetime.fromtimestamp(next_h1_t / 1000.0, tz=timezone.utc)
            if self.policy.must_flat(start_dt):
                out.blocked.append({"symbol": symbol, "signal_id": signal.signal_id, "reason": "EOD"})
                continue
            replay = self.replay_pending_entry(
                signal_time_ms=signal_t,
                next_h1_time_ms=next_h1_t,
                trigger=float(signal.trigger_price),
                stop=float(signal.protective_reference),
                m15=m15,
                m5=m5,
            )
            out.events.append({"signal_id": signal.signal_id, "signal_type": signal.signal_type.value, "replay": replay})
            entry = replay["entry"]
            if entry.get("status") == "AMBIGUOUS_SAME_BAR":
                out.blocked.append({
                    "symbol": symbol,
                    "signal_id": signal.signal_id,
                    "reason": "AMBIGUOUS_SAME_BAR",
                    "timestamp_ms": entry["timestamp_ms"],
                })
                continue
            if entry.get("status") != "FILLED":
                continue
            allocation = size_for_risk(
                equity=self.equity,
                risk_pct=self.initial_risk_pct,
                entry=float(entry["price"]),
                stop=float(signal.protective_reference),
                fee_pct=self.fee_pct,
                slippage_pct=self.slippage_pct,
                max_position_fraction=0.25,
                step=1,
            )
            qty = float(allocation.quantity) if allocation.allowed else 0.0
            out.fills.append(BacktestFill(
                timestamp_ms=int(entry["timestamp_ms"]),
                price=float(entry["price"]),
                quantity=qty,
                reason="H1_CORE_TRIGGER",
                signal_id=signal.signal_id,
                signal_type=signal.signal_type.value,
                gap=bool(entry.get("gap", False)),
            ))
            exit_result = replay["exit"]
            if exit_result.get("status") == "STOPPED":
                out.exits.append(BacktestExit(
                    timestamp_ms=int(exit_result["timestamp_ms"]),
                    price=float(exit_result["price"]),
                    reason="STRUCTURAL_STOP",
                    quantity=qty,
                ))
        return out
