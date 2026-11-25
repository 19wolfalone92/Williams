"""Unified-account, causal TC2 USD-M Futures portfolio replay.

This is a research simulator. It reuses the production H1 signal extractors,
TC2 stop policy, reverse-pyramid contract, and Model B arithmetic. Exchange
fills, historical contract filters and Android parity remain explicitly
unverified where the archived OHLC data cannot prove them.
"""
from __future__ import annotations

from collections import defaultdict
from decimal import Decimal, ROUND_CEILING, ROUND_FLOOR
from hashlib import sha256
import json
import math
from typing import Any

import numpy as np

from campaign_engine import CampaignEngine
from campaign_model import SignalRole, SignalType
from daily_risk_accounting import calculate_daily_risk_model_b
from williams_edition_rules import TC2
from williams_execution_policy import tc2_opposite_exit_profit_blocker
from williams_exit_policy import plan_williams_stop
from williams_tc2_pyramid import (
    POLICY as TC2_PYRAMID_POLICY,
    cap_book_tranche,
    initial_unit_risk_pct,
    next_tranche as tc2_next_tranche,
)
from williams_wave_hierarchy import wave_bars_from_dataframe

DAY_MS = 86_400_000
H1_MS = 3_600_000
M5_MS = 300_000
RISK_COST_ALLOWANCE = 0.0035  # 2 x 0.10% fee reserve + 0.15% slippage reserve
MAX_TOTAL_RISK_PCT = 0.006
CAMPAIGN_RISK_CAP_PCT = 0.006
PER_ENTRY_RISK_CAP_PCT = 0.0025
INITIAL_ENTRY_RISK_CAP_PCT = 0.002
MAX_CAMPAIGNS = 10
MAX_MARGIN_FRACTION = 0.50
PER_ORDER_MARGIN_FRACTION = 0.25
DEFAULT_LEVERAGE = 2


def _canonical(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), default=str)


def _sign(side: str) -> int:
    if side == "BUY":
        return 1
    if side == "SELL":
        return -1
    raise ValueError(f"unsupported side: {side}")


def _floor_qty(value: float, step: Decimal) -> Decimal:
    if not math.isfinite(value) or value <= 0 or step <= 0:
        return Decimal(0)
    raw = Decimal(str(value))
    return (raw / step).to_integral_value(rounding=ROUND_FLOOR) * step


def _round_price(value: float, tick: Decimal, *, side: str, purpose: str) -> float:
    if not math.isfinite(value) or value <= 0 or tick <= 0:
        raise ValueError("invalid price/tick")
    # Adverse rounding: buy fills round up; sell fills round down.
    round_up = (purpose == "ENTRY" and side == "BUY") or (purpose == "EXIT" and side == "SELL")
    mode = ROUND_CEILING if round_up else ROUND_FLOOR
    q = Decimal(str(value)) / tick
    return float(q.to_integral_value(rounding=mode) * tick)


def entry_fill_price(open_price: float, trigger: float, side: str, slippage: float, tick: Decimal) -> float:
    raw = max(open_price, trigger) if side == "BUY" else min(open_price, trigger)
    adjusted = raw * (1.0 + slippage if side == "BUY" else 1.0 - slippage)
    return _round_price(adjusted, tick, side=side, purpose="ENTRY")


def exit_fill_price(raw_price: float, side: str, slippage: float, tick: Decimal) -> float:
    adjusted = raw_price * (1.0 - slippage if side == "BUY" else 1.0 + slippage)
    return _round_price(adjusted, tick, side=side, purpose="EXIT")


def stop_triggered(side: str, stop: float, mark_open: float, mark_high: float, mark_low: float) -> bool:
    if side == "BUY":
        return mark_open <= stop or mark_low <= stop
    return mark_open >= stop or mark_high >= stop


def stop_fill_raw(side: str, contract_open: float, contract_high: float,
                  contract_low: float, contract_close: float, mark_open: float, stop: float) -> float:
    """Worst observable contract OHLC price after a Mark Price stop trigger."""
    if side == "BUY":
        return contract_open if mark_open <= stop else min(contract_low, contract_close)
    return contract_open if mark_open >= stop else max(contract_high, contract_close)


def active_campaign_slot_count(opened_symbols, pending_initial_symbols) -> int:
    """A pending initial order reserves one slot; a pending add-on does not."""
    return len({str(x).strip().upper() for x in opened_symbols if str(x).strip()} |
               {str(x).strip().upper() for x in pending_initial_symbols if str(x).strip()})


def _signal_type(signal) -> str:
    return str(getattr(getattr(signal, "signal_type", None), "value", getattr(signal, "signal_type", "")))


def _signal_role(signal) -> str:
    return str(getattr(getattr(signal, "role", None), "value", getattr(signal, "role", "")))


def _signal_record(symbol: str, signal, known_at_ms: int, context: dict) -> dict:
    return {
        "symbol": symbol,
        "signal_id": str(signal.signal_id),
        "known_at_ms": int(known_at_ms),
        "signal_bar_time_ms": int(signal.signal_bar_time_ms),
        "direction": "LONG" if signal.side == "BUY" else "SHORT",
        "side": signal.side,
        "signal_type": _signal_type(signal),
        "role": _signal_role(signal),
        "strategy_profile": str(signal.strategy_profile),
        "timeframe": str(signal.timeframe),
        "execution_semantics": str(signal.execution_semantics),
        "trigger_price": float(signal.trigger_price),
        "protective_reference": float(signal.protective_reference),
        "invalidation_price": float(getattr(signal, "invalidation_price", 0.0) or 0.0),
        "expires_at_ms": int(getattr(signal, "expires_at_ms", 0) or 0),
        "angulation_score": float(getattr(signal, "angulation_score", 0.0) or 0.0),
        "wave_confidence": float(getattr(signal, "wave_confidence", 0.0) or 0.0),
        "wave_exhaustion_risk": float(getattr(signal, "wave_exhaustion_risk", 0.0) or 0.0),
        "h1_ao": context.get("h1_ao"),
        "h1_ac": context.get("h1_ac"),
        "h1_jaw": context.get("h1_jaw"),
        "h1_teeth": context.get("h1_teeth"),
        "h1_lips": context.get("h1_lips"),
        "h4_context": _canonical(context.get("h4", {})),
        "d1_context": _canonical(context.get("d1", {})),
        "market_regime": context.get("market_regime", ""),
        "wave_phase": "NOT_INDEPENDENTLY_CLASSIFIED",
        "decision_status": "OBSERVED",
        "block_reason": "",
    }


class PortfolioSimulator:
    """One shared wallet, margin envelope, risk budget and campaign registry."""

    def __init__(
        self,
        *,
        symbols: list[str],
        data: dict,
        timeline: np.ndarray,
        signals_by_index: dict,
        funding_by_index: dict,
        specs: dict,
        initial_capital: float,
        fee_rate: float = 0.0005,
        slippage_rate: float = 0.0005,
        trail_bars: int = 5,
        collect: bool = True,
    ):
        if trail_bars not in (3, 4, 5):
            raise ValueError("TC2 trail window must be 3, 4 or 5 closed H1 bars")
        if initial_capital <= 0 or not math.isfinite(initial_capital):
            raise ValueError("initial capital must be finite and positive")
        self.symbols = list(symbols)
        self.data = data
        self.timeline = timeline
        self.signals_by_index = signals_by_index
        self.funding_by_index = funding_by_index
        self.specs = specs
        self.initial_capital = float(initial_capital)
        self.balance = float(initial_capital)
        self.fee_rate = float(fee_rate)
        self.slippage_rate = float(slippage_rate)
        self.trail_bars = int(trail_bars)
        self.collect = bool(collect)

        self.active: dict[str, dict] = {}
        self.closed: list[dict] = []
        self.completed_summaries: list[dict] = []
        self.signal_records: dict[tuple[str, str], dict] = {}
        self.fills: list[dict] = []
        self.risk_events: list[dict] = []
        self.events: list[dict] = []
        self.equity_rows: list[dict] = []
        self.daily_rows: list[dict] = []
        self.monthly_rows: list[dict] = []

        self.daily_start_equity = self.initial_capital
        self.daily_entry_blocked = False
        self.daily_block_reason = ""
        self.loss_streak = 0
        self.loss_streak_latched = False
        self.emergency_latched = False
        self.emergency_latched_at_ms = None
        self.current_day = None
        self.daily_min_equity = self.initial_capital
        self.daily_max_loss_pct = 0.0
        self.daily_3pct_days = set()
        self.daily_5pct_days = set()
        self.consecutive_loss_days = set()

        self.peak_equity = self.initial_capital
        self.max_drawdown = 0.0
        self.drawdown_started_ms = None
        self.max_drawdown_duration_ms = 0
        self.max_margin_pct = 0.0
        self.max_risk_pct = 0.0
        self.max_active_campaigns = 0
        self.total_funding = 0.0
        self.total_fees = 0.0
        self.total_slippage = 0.0
        self.entry_count = 0
        self.addition_count = 0
        self.stop_count = 0
        self.opposite_exit_count = 0
        self.expired_count = 0
        self.replaced_count = 0
        self.blocked_count = 0
        self.digest = sha256()
        self._last_month = None
        self._last_equity = self.initial_capital

    def _emit(self, timestamp_ms: int, event_type: str, **fields):
        row = {"time_ms": int(timestamp_ms), "event": event_type, **fields}
        self.digest.update((_canonical(row) + "\n").encode())
        if self.collect:
            self.events.append(row)

    def _risk_event(self, timestamp_ms: int, reason: str, **fields):
        row = {"time_ms": int(timestamp_ms), "reason": reason, **fields}
        self.digest.update((_canonical(row) + "\n").encode())
        if self.collect:
            self.risk_events.append(row)

    def _signal_status(self, symbol: str, signal, known_at_ms: int, context: dict,
                       status: str, reason: str = ""):
        key = (symbol, str(signal.signal_id))
        if key not in self.signal_records:
            self.signal_records[key] = _signal_record(symbol, signal, known_at_ms, context)
        row = self.signal_records[key]
        row["decision_status"] = status
        row["block_reason"] = reason
        self.digest.update((_canonical({"signal": key, "status": status, "reason": reason}) + "\n").encode())
        if reason:
            self.blocked_count += 1
        return row

    def _equity(self, index: int, price_field: str = "mark_close") -> float:
        equity = self.balance
        for symbol, campaign in self.active.items():
            if campaign["status"] != "OPEN":
                continue
            mark = float(self.data[symbol]["mark"][price_field][index])
            equity += _sign(campaign["side"]) * campaign["qty"] * (mark - campaign["avg_entry"])
        return equity

    def _margin_used(self, index: int) -> float:
        used = 0.0
        for symbol, campaign in self.active.items():
            leverage = float(self.specs[symbol]["leverage"])
            if campaign["status"] == "OPEN":
                mark = float(self.data[symbol]["mark"]["mark_close"][index])
                used += campaign["qty"] * mark / leverage
            pending = campaign.get("pending")
            if pending:
                used += pending["qty"] * pending["trigger"] / leverage
        return used

    @staticmethod
    def _open_risk(campaign: dict) -> float:
        if campaign["status"] != "OPEN" or campaign["qty"] <= 0:
            return 0.0
        distance = (campaign["avg_entry"] - campaign["stop"] if campaign["side"] == "BUY"
                    else campaign["stop"] - campaign["avg_entry"])
        return campaign["qty"] * max(0.0, distance) + campaign["qty"] * campaign["avg_entry"] * RISK_COST_ALLOWANCE

    def _reserved_risk(self) -> float:
        total = 0.0
        for campaign in self.active.values():
            if campaign["status"] == "OPEN":
                total += self._open_risk(campaign)
            pending = campaign.get("pending")
            if pending:
                total += float(pending["risk_quote"])
        return total

    def _size_from_risk(self, symbol: str, trigger: float, stop: float, risk_pct: float,
                        index: int) -> tuple[Decimal, float, float] | None:
        equity = self._equity(index, "mark_open")
        if equity <= 0 or not all(math.isfinite(x) for x in (equity, trigger, stop, risk_pct)):
            return None
        if risk_pct <= 0 or risk_pct > PER_ENTRY_RISK_CAP_PCT + 1e-12:
            return None
        distance = abs(trigger - stop) / trigger
        effective = distance + RISK_COST_ALLOWANCE
        if effective <= 0:
            return None
        risk_quote = equity * risk_pct
        margin_used = self._margin_used(index)
        available_margin = max(0.0, equity * MAX_MARGIN_FRACTION - margin_used)
        leverage = float(self.specs[symbol]["leverage"])
        max_notional = min(equity * PER_ORDER_MARGIN_FRACTION,
                           available_margin * 0.95) * leverage
        notional = min(risk_quote / effective, max_notional)
        qty = _floor_qty(notional / trigger, self.specs[symbol]["step"])
        if qty <= 0:
            return None
        if qty < self.specs[symbol]["min_qty"] or qty > self.specs[symbol]["max_qty"]:
            return None
        if qty * Decimal(str(trigger)) < self.specs[symbol]["min_notional"]:
            return None
        return qty, risk_quote, float(qty * Decimal(str(trigger)))

    def _entry_block_reason(self) -> str:
        if self.emergency_latched:
            return "FUTURES_DAILY_EMERGENCY_BREAKER_5PCT_LATCHED"
        if self.daily_entry_blocked:
            return self.daily_block_reason or "DAILY_RISK_OR_LOSS_STREAK_BLOCK"
        return ""

    def _new_day(self, index: int, timestamp_ms: int):
        day = timestamp_ms // DAY_MS
        if self.current_day == day:
            return
        if self.current_day is not None:
            self.daily_rows.append({
                "utc_day": int(self.current_day),
                "start_equity": self.daily_start_equity,
                "min_equity": self.daily_min_equity,
                "max_daily_loss_pct": self.daily_max_loss_pct,
                "entry_blocked": self.daily_entry_blocked,
                "block_reason": self.daily_block_reason,
                "loss_streak_at_close": self.loss_streak,
                "emergency_latched": self.emergency_latched,
            })
        self.current_day = day
        self.daily_entry_blocked = False
        self.daily_block_reason = ""
        self.loss_streak = 0
        self.loss_streak_latched = False
        self.daily_min_equity = self._equity(index, "mark_open")
        self.daily_start_equity = self.daily_min_equity
        self.daily_max_loss_pct = 0.0
        self._emit(timestamp_ms, "UTC_DAY_BASELINE", equity=self.daily_start_equity,
                   emergency_latched=self.emergency_latched)
        if self.emergency_latched:
            self.daily_entry_blocked = True
            self.daily_block_reason = "EMERGENCY_BREAKER_REMAINS_LATCHED"

    def _apply_funding(self, index: int, timestamp_ms: int):
        for symbol in self.symbols:
            rows = self.funding_by_index[symbol].get(index, ())
            campaign = self.active.get(symbol)
            if not rows or campaign is None or campaign["status"] != "OPEN":
                continue
            mark = float(self.data[symbol]["mark"]["mark_open"][index])
            for row in rows:
                rate = float(row["rate"])
                payment = -_sign(campaign["side"]) * campaign["qty"] * mark * rate
                self.balance += payment
                campaign["funding"] += payment
                self.total_funding += payment
                self._emit(timestamp_ms, "FUNDING", symbol=symbol, campaign_id=campaign["campaign_id"],
                           rate=rate, mark_price=mark, quantity=campaign["qty"], payment=payment)

    def _campaign_context(self, symbol: str, signal, known_at_ms: int, context: dict) -> dict:
        return _signal_record(symbol, signal, known_at_ms, context)

    def _arm_initial(self, symbol: str, item: dict, index: int):
        signal = item["signal"]
        known_at = int(item["known_at_ms"])
        context = item.get("context", {})
        reason = self._entry_block_reason()
        if reason:
            self._signal_status(symbol, signal, known_at, context, "BLOCKED", reason)
            return
        if len(self.active) >= MAX_CAMPAIGNS:
            self._signal_status(symbol, signal, known_at, context, "BLOCKED", "MAX_10_CAMPAIGNS")
            return
        if signal.strategy_profile != TC2 or _signal_role(signal) != SignalRole.ENTRY.value:
            self._signal_status(symbol, signal, known_at, context, "IGNORED", "NOT_TC2_INITIAL_WISE_MAN")
            return
        if signal.execution_semantics != "STOP_ENTRY":
            self._signal_status(symbol, signal, known_at, context, "BLOCKED", "UNSUPPORTED_EXECUTION_SEMANTICS")
            return
        if signal.expires_at_ms and known_at >= int(signal.expires_at_ms):
            self._signal_status(symbol, signal, known_at, context, "EXPIRED", "SIGNAL_EXPIRED_BEFORE_ARM")
            self.expired_count += 1
            return
        trigger = float(signal.trigger_price)
        stop = float(signal.protective_reference)
        if trigger <= 0 or stop <= 0 or (signal.side == "BUY" and stop >= trigger) or (signal.side == "SELL" and stop <= trigger):
            self._signal_status(symbol, signal, known_at, context, "BLOCKED", "INVALID_INITIAL_STOP_GEOMETRY")
            return
        current = float(self.data[symbol]["contract"]["open"][index])
        if (signal.side == "BUY" and current >= trigger) or (signal.side == "SELL" and current <= trigger):
            self._signal_status(symbol, signal, known_at, context, "BLOCKED", "TRIGGER_ALREADY_CROSSED_NO_MARKET_CHASE")
            return
        equity = self._equity(index, "mark_open")
        risk_pct = initial_unit_risk_pct(INITIAL_ENTRY_RISK_CAP_PCT,
                                         CAMPAIGN_RISK_CAP_PCT,
                                         PER_ENTRY_RISK_CAP_PCT)
        risk_quote = equity * risk_pct
        if self._reserved_risk() + risk_quote > equity * MAX_TOTAL_RISK_PCT + 1e-9:
            self._signal_status(symbol, signal, known_at, context, "BLOCKED", "PORTFOLIO_RISK_CAP_0_60PCT")
            self._risk_event(known_at, "PORTFOLIO_RISK_CAP_0_60PCT", symbol=symbol,
                             reserved=self._reserved_risk(), requested=risk_quote, equity=equity)
            return
        sized = self._size_from_risk(symbol, trigger, stop, risk_pct, index)
        if sized is None:
            self._signal_status(symbol, signal, known_at, context, "BLOCKED", "SIZE_OR_EXCHANGE_FILTER_REJECTED")
            return
        qty, risk_quote, notional = sized
        leverage = float(self.specs[symbol]["leverage"])
        margin = notional / leverage
        if self._margin_used(index) + margin > equity * MAX_MARGIN_FRACTION + 1e-9:
            self._signal_status(symbol, signal, known_at, context, "BLOCKED", "PORTFOLIO_MARGIN_CAP_50PCT")
            return
        campaign = {
            "campaign_id": f"{symbol}:{signal.signal_id}",
            "symbol": symbol,
            "side": signal.side,
            "status": "PENDING_INITIAL",
            "initial_signal": signal,
            "initial_signal_type": _signal_type(signal),
            "initial_signal_time_ms": int(signal.signal_bar_time_ms),
            "last_signal_time_ms": int(signal.signal_bar_time_ms),
            "signal_ids": [str(signal.signal_id)],
            "initial_context": context,
            "pending": {
                "kind": "INITIAL",
                "signal": signal,
                "context": context,
                "known_at_ms": known_at,
                "trigger": trigger,
                "stop": stop,
                "qty": float(qty),
                "risk_quote": risk_quote,
                "margin_quote": margin,
                "expires_at_ms": int(getattr(signal, "expires_at_ms", 0) or 0),
            },
            "qty": 0.0,
            "avg_entry": 0.0,
            "unit_qty": 0.0,
            "completed_quantities": [],
            "additions": 0,
            "initial_stop": stop,
            "stop": stop,
            "stop_source": "INITIAL_SIGNAL",
            "stop_history": [{"time_ms": known_at, "stop": stop, "reason": "INITIAL_SIGNAL"}],
            "entry_time_ms": None,
            "exit_time_ms": None,
            "entry_fee": 0.0,
            "fees": 0.0,
            "funding": 0.0,
            "gross_realized": 0.0,
            "slippage_quote": 0.0,
            "initial_risk_quote": risk_quote,
            "max_unrealized": 0.0,
            "min_unrealized": 0.0,
            "mfe_quote": 0.0,
            "mae_quote": 0.0,
            "opposition_watchers": [],
            "fills": [],
        }
        self.active[symbol] = campaign
        self._signal_status(symbol, signal, known_at, context, "PENDING_INITIAL")
        self._emit(known_at, "INITIAL_STOP_ORDER_ARMED", symbol=symbol,
                   campaign_id=campaign["campaign_id"], trigger=trigger, stop=stop,
                   quantity=float(qty), risk_quote=risk_quote, margin_quote=margin)
        self.max_active_campaigns = max(self.max_active_campaigns, len(self.active))

    def _replace_pending_initial(self, campaign: dict, item: dict, index: int):
        old = campaign["pending"]["signal"]
        new = item["signal"]
        tick = float(self.specs[campaign["symbol"]]["tick"])
        if not CampaignEngine.should_replace_pending(old, new, tick_size=tick):
            self._signal_status(campaign["symbol"], new, item["known_at_ms"], item.get("context", {}),
                                "IGNORED", "PENDING_SIGNAL_NOT_REPLACED")
            return
        old_item = campaign["pending"]
        self._signal_status(campaign["symbol"], old, old_item["known_at_ms"], old_item.get("context", {}),
                            "REPLACED", "NEWER_VALID_WISE_MAN")
        self.replaced_count += 1
        del self.active[campaign["symbol"]]
        self._emit(item["known_at_ms"], "PENDING_INITIAL_REPLACED", symbol=campaign["symbol"],
                   old_signal_id=old.signal_id, new_signal_id=new.signal_id)
        self._arm_initial(campaign["symbol"], item, index)

    def _arm_addon(self, campaign: dict, item: dict, index: int):
        signal = item["signal"]
        symbol = campaign["symbol"]
        known_at = int(item["known_at_ms"])
        context = item.get("context", {})
        if self._entry_block_reason():
            self._signal_status(symbol, signal, known_at, context, "BLOCKED", self._entry_block_reason())
            return
        if signal.strategy_profile != TC2 or signal.side != campaign["side"]:
            self._signal_status(symbol, signal, known_at, context, "IGNORED", "NOT_SAME_TC2_CAMPAIGN")
            return
        if int(signal.signal_bar_time_ms) <= int(campaign["last_signal_time_ms"]):
            self._signal_status(symbol, signal, known_at, context, "IGNORED", "SIGNAL_NOT_NEWER_THAN_CAMPAIGN")
            return
        pending = campaign.get("pending")
        if pending:
            old = pending["signal"]
            tick = float(self.specs[symbol]["tick"])
            if not CampaignEngine.should_replace_pending(old, signal, tick_size=tick):
                self._signal_status(symbol, signal, known_at, context, "IGNORED", "ADD_ON_ALREADY_PENDING")
                return
            self._signal_status(symbol, old, pending["known_at_ms"], pending.get("context", {}),
                                "REPLACED", "NEWER_VALID_ADD_ON")
            self.replaced_count += 1
            campaign["pending"] = None
            pending = None
        if campaign["additions"] >= 4:
            self._signal_status(symbol, signal, known_at, context, "BLOCKED", "TC2_15_UNIT_PYRAMID_COMPLETE")
            return
        trigger = float(signal.trigger_price)
        stop = float(campaign["stop"])
        current = float(self.data[symbol]["contract"]["open"][index])
        entry_price = float(campaign["avg_entry"])
        if (signal.side == "BUY" and (current <= entry_price or trigger <= entry_price or stop >= trigger)) or (
                signal.side == "SELL" and (current >= entry_price or trigger >= entry_price or stop <= trigger)):
            self._signal_status(symbol, signal, known_at, context, "BLOCKED", "TC2_ADDITION_REQUIRES_PROFITABLE_POSITION")
            return
        equity = self._equity(index, "mark_open")
        campaign_used = self._open_risk(campaign) + (float(pending["risk_quote"]) if pending else 0.0)
        campaign_remaining = max(0.0, equity * CAMPAIGN_RISK_CAP_PCT - campaign_used)
        portfolio_remaining = max(0.0, equity * MAX_TOTAL_RISK_PCT - self._reserved_risk())
        risk_pct = min(campaign_remaining / equity, PER_ENTRY_RISK_CAP_PCT,
                       portfolio_remaining / equity) if equity > 0 else 0.0
        sized = self._size_from_risk(symbol, trigger, stop, risk_pct, index)
        if sized is None:
            self._signal_status(symbol, signal, known_at, context, "BLOCKED", "ADD_ON_RISK_OR_MARGIN_SIZE_REJECTED")
            return
        qty_capacity, risk_quote, notional = sized
        plan = {
            "policy": TC2_PYRAMID_POLICY,
            "unit_quantity": str(campaign["unit_qty"]),
            "completed_quantities": [str(x) for x in campaign["completed_quantities"]],
        }
        try:
            required = tc2_next_tranche(plan, owned_quantity=campaign["qty"],
                                        additions=campaign["additions"])
            qty = Decimal(str(cap_book_tranche(required, float(qty_capacity))))
        except Exception as exc:
            self._signal_status(symbol, signal, known_at, context, "BLOCKED", str(exc))
            return
        step = self.specs[symbol]["step"]
        if qty % step != 0 or qty < self.specs[symbol]["min_qty"] or qty > self.specs[symbol]["max_qty"]:
            self._signal_status(symbol, signal, known_at, context, "BLOCKED", "TC2_PYRAMID_QUANTITY_FILTER_CHANGED")
            return
        if qty * Decimal(str(trigger)) < self.specs[symbol]["min_notional"]:
            self._signal_status(symbol, signal, known_at, context, "BLOCKED", "TC2_PYRAMID_BELOW_MIN_NOTIONAL")
            return
        ratio = float(qty) / float(qty_capacity)
        risk_quote *= ratio
        margin = float(qty) * trigger / float(self.specs[symbol]["leverage"])
        if self._reserved_risk() + risk_quote > equity * MAX_TOTAL_RISK_PCT + 1e-9:
            self._signal_status(symbol, signal, known_at, context, "BLOCKED", "PORTFOLIO_RISK_CAP_0_60PCT")
            return
        if self._margin_used(index) + margin > equity * MAX_MARGIN_FRACTION + 1e-9:
            self._signal_status(symbol, signal, known_at, context, "BLOCKED", "PORTFOLIO_MARGIN_CAP_50PCT")
            return
        campaign["pending"] = {
            "kind": "ADD_ON", "signal": signal, "context": context,
            "known_at_ms": known_at, "trigger": trigger, "stop": stop,
            "qty": float(qty), "risk_quote": risk_quote, "margin_quote": margin,
            "expires_at_ms": int(getattr(signal, "expires_at_ms", 0) or 0),
        }
        campaign["last_signal_time_ms"] = int(signal.signal_bar_time_ms)
        campaign["signal_ids"].append(str(signal.signal_id))
        self._signal_status(symbol, signal, known_at, context, "PENDING_ADD_ON")
        self._emit(known_at, "TC2_ADD_ON_STOP_ORDER_ARMED", symbol=symbol,
                   campaign_id=campaign["campaign_id"], signal_id=signal.signal_id,
                   tranche=campaign["additions"] + 1, quantity=float(qty),
                   trigger=trigger, stop=stop, risk_quote=risk_quote, margin_quote=margin)

    def _handle_signals(self, symbol: str, items: list[dict], index: int, timestamp_ms: int):
        if not items:
            return
        items = sorted(items, key=lambda x: (int(x["signal"].signal_bar_time_ms),
                                             str(x["signal"].signal_id)))
        campaign = self.active.get(symbol)
        if campaign and campaign["status"] == "PENDING_INITIAL":
            candidates = [x for x in items if _signal_role(x["signal"]) == SignalRole.ENTRY.value
                          and x["signal"].strategy_profile == TC2]
            if candidates:
                self._replace_pending_initial(campaign, candidates[0], index)
            return
        if campaign and campaign["status"] == "OPEN":
            entry_signals = [x for x in items if _signal_role(x["signal"]) == SignalRole.ENTRY.value]
            sides = {x["signal"].side for x in entry_signals}
            conflict = len(sides) > 1
            if not conflict and not campaign.get("pending"):
                eligible = [x for x in items
                            if x["signal"].side == campaign["side"]
                            and x["signal"].strategy_profile == TC2
                            and int(x["signal"].signal_bar_time_ms) > int(campaign["last_signal_time_ms"])]
                if eligible:
                    self._arm_addon(campaign, eligible[0], index)
            for item in items:
                signal = item["signal"]
                if (not conflict and signal.side != campaign["side"]
                        and _signal_role(signal) == SignalRole.ENTRY.value
                        and _signal_type(signal) in {
                            SignalType.REVERSAL.value, SignalType.SUPER_AO.value, SignalType.FRACTAL.value
                        }):
                    watcher = {
                        "signal": signal, "context": item.get("context", {}),
                        "known_at_ms": int(item["known_at_ms"]),
                    }
                    campaign["opposition_watchers"].append(watcher)
                    self._signal_status(symbol, signal, item["known_at_ms"], item.get("context", {}),
                                        "OPPOSITE_EXIT_WATCH")
            return
        if campaign is not None:
            return
        entries = [x for x in items if _signal_role(x["signal"]) == SignalRole.ENTRY.value
                   and x["signal"].strategy_profile == TC2]
        if len({x["signal"].side for x in entries}) > 1:
            for item in entries:
                self._signal_status(symbol, item["signal"], item["known_at_ms"], item.get("context", {}),
                                    "BLOCKED", "OPPOSING_ENTRIES_BEFORE_CONTEXT")
            return
        if entries:
            self._arm_initial(symbol, entries[0], index)

    def _trail(self, symbol: str, index: int, timestamp_ms: int):
        campaign = self.active.get(symbol)
        if not campaign or campaign["status"] != "OPEN":
            return
        row_index = self.data[symbol]["h1_close_rows"].get(index)
        if row_index is None:
            return
        h1 = self.data[symbol]["h1"]
        closed = h1.iloc[max(0, row_index - 219):row_index + 1]
        try:
            bars = wave_bars_from_dataframe(closed)
            current = float(self.data[symbol]["contract"]["open"][index])
            proposal = plan_williams_stop(
                campaign["side"], campaign["stop"], campaign["avg_entry"],
                campaign["initial_stop"], current, float(self.specs[symbol]["tick"]),
                int(campaign["entry_time_ms"]), bars, window=self.trail_bars,
                fee_allowance=RISK_COST_ALLOWANCE,
            )
        except Exception as exc:
            self._emit(timestamp_ms, "TRAIL_ANALYSIS_UNAVAILABLE", symbol=symbol,
                       campaign_id=campaign["campaign_id"], error=type(exc).__name__)
            return
        if not proposal:
            return
        new_stop = float(proposal["stop"])
        old_stop = float(campaign["stop"])
        tighter = new_stop > old_stop if campaign["side"] == "BUY" else new_stop < old_stop
        if tighter:
            campaign["stop"] = new_stop
            campaign["stop_source"] = str(proposal["reason"])
            campaign["stop_history"].append({
                "time_ms": timestamp_ms, "old_stop": old_stop,
                "stop": new_stop, "reason": str(proposal["reason"]),
            })
            self._emit(timestamp_ms, "PROTECTIVE_STOP_TIGHTENED", symbol=symbol,
                       campaign_id=campaign["campaign_id"], old_stop=old_stop,
                       new_stop=new_stop, reason=str(proposal["reason"]))

    def _close_campaign(self, symbol: str, campaign: dict, raw_exit: float,
                        index: int, timestamp_ms: int, reason: str, detail: str):
        fill = exit_fill_price(raw_exit, campaign["side"], self.slippage_rate,
                               self.specs[symbol]["tick"])
        qty = float(campaign["qty"])
        gross = _sign(campaign["side"]) * qty * (fill - campaign["avg_entry"])
        fee = qty * fill * self.fee_rate
        slippage_base = raw_exit
        slip_quote = abs(fill - slippage_base) * qty
        self.balance += gross - fee
        self.total_fees += fee
        self.total_slippage += slip_quote
        campaign["fees"] += fee
        campaign["gross_realized"] += gross
        campaign["slippage_quote"] += slip_quote
        campaign["exit_time_ms"] = int(timestamp_ms)
        campaign["exit_price"] = fill
        campaign["exit_reason"] = reason
        campaign["exit_detail"] = detail
        campaign["status"] = "CLOSED"
        campaign["final_net_pnl"] = campaign["gross_realized"] + campaign["funding"] - campaign["fees"]
        campaign["final_qty"] = qty
        campaign["qty"] = 0.0
        campaign["pending"] = None
        if reason == "STOP":
            self.stop_count += 1
        elif reason == "OPPOSITE_SIGNAL":
            self.opposite_exit_count += 1
        summary = self._campaign_output(campaign)
        self.completed_summaries.append(summary)
        self.digest.update((_canonical(summary) + "\\n").encode())
        if self.collect:
            self.closed.append(summary)
            self.fills.append({
                "time_ms": timestamp_ms, "symbol": symbol, "campaign_id": campaign["campaign_id"],
                "purpose": "EXIT", "side": "SELL" if campaign["side"] == "BUY" else "BUY",
                "quantity": qty, "price": fill, "fee": fee, "gross_pnl": gross,
                "funding": 0.0, "slippage_quote": slip_quote, "reason": reason, "detail": detail,
            })
        self._emit(timestamp_ms, "CAMPAIGN_CLOSED", symbol=symbol,
                   campaign_id=campaign["campaign_id"], reason=reason, detail=detail,
                   net_pnl=campaign["final_net_pnl"], gross_pnl=gross, fees=campaign["fees"],
                   funding=campaign["funding"], exit_price=fill)
        del self.active[symbol]
        net = float(campaign["final_net_pnl"])
        if net < -1e-12:
            self.loss_streak += 1
        else:
            self.loss_streak = 0
        if self.loss_streak >= 3 and not self.loss_streak_latched:
            self.loss_streak_latched = True
            self.daily_entry_blocked = True
            self.daily_block_reason = "THREE_CONSECUTIVE_LOSSES_UTC_DAY"
            self.consecutive_loss_days.add(self.current_day)
            self._risk_event(timestamp_ms, self.daily_block_reason, symbol=symbol,
                             loss_streak=self.loss_streak, utc_day=self.current_day)

    def _signal_digest_campaign(self, campaign: dict):
        row = self._campaign_output(campaign)
        self.digest.update((_canonical(row) + "\n").encode())

    def _campaign_output(self, campaign: dict) -> dict:
        signal = campaign["initial_signal"]
        context = campaign.get("initial_context", {})
        return {
            "campaign_id": campaign["campaign_id"], "symbol": campaign["symbol"],
            "direction": "LONG" if campaign["side"] == "BUY" else "SHORT",
            "initial_wise_man": campaign["initial_signal_type"],
            "initial_signal_id": str(signal.signal_id),
            "signal_time_ms": campaign["initial_signal_time_ms"],
            "entry_time_ms": campaign.get("entry_time_ms"),
            "exit_time_ms": campaign.get("exit_time_ms"),
            "entry_price": campaign.get("initial_entry_price"),
            "average_entry_price": campaign.get("avg_entry"),
            "exit_price": campaign.get("exit_price"),
            "initial_stop": campaign.get("initial_stop"),
            "final_stop": campaign.get("stop"),
            "stop_history": _canonical(campaign.get("stop_history", [])),
            "stop_source": campaign.get("stop_source"),
            "initial_risk_quote": campaign.get("initial_risk_quote"),
            "quantity": campaign.get("final_qty", campaign.get("qty")),
            "additions": campaign.get("additions", 0),
            "add_on_signal_ids": _canonical(campaign.get("signal_ids", [])[1:]),
            "gross_pnl": campaign.get("gross_realized", 0.0),
            "fees": campaign.get("fees", 0.0),
            "funding": campaign.get("funding", 0.0),
            "net_pnl": campaign.get("final_net_pnl", 0.0),
            "R_multiple": (campaign.get("final_net_pnl", 0.0) /
                          campaign.get("initial_risk_quote", 1.0)) if campaign.get("initial_risk_quote", 0) else None,
            "MFE_usdt": campaign.get("mfe_quote", 0.0),
            "MAE_usdt": campaign.get("mae_quote", 0.0),
            "entry_angulation_score": context.get("angulation_score", 0.0),
            "entry_h1_ao": context.get("h1_ao"),
            "entry_h1_ac": context.get("h1_ac"),
            "entry_h1_alligator": _canonical({
                "jaw": context.get("h1_jaw"), "teeth": context.get("h1_teeth"),
                "lips": context.get("h1_lips"),
            }),
            "entry_h4_context": _canonical(context.get("h4", {})),
            "entry_d1_context": _canonical(context.get("d1", {})),
            "market_regime": context.get("market_regime", ""),
            "exit_reason": campaign.get("exit_reason", "OPEN_AT_END"),
            "exit_detail": campaign.get("exit_detail", ""),
            "status": campaign["status"],
        }

    def _update_excursions(self, campaign: dict, symbol: str, index: int):
        mark = self.data[symbol]["mark"]
        sign = _sign(campaign["side"])
        qty = campaign["qty"]
        entry = campaign["avg_entry"]
        favorable = sign * qty * ((mark["mark_high"][index] if sign == 1 else mark["mark_low"][index]) - entry)
        adverse = sign * qty * ((mark["mark_low"][index] if sign == 1 else mark["mark_high"][index]) - entry)
        campaign["mfe_quote"] = max(campaign["mfe_quote"], favorable, 0.0)
        campaign["mae_quote"] = max(campaign["mae_quote"], -adverse, 0.0)
        close_unrealized = sign * qty * (mark["mark_close"][index] - entry)
        campaign["max_unrealized"] = max(campaign["max_unrealized"], close_unrealized)
        campaign["min_unrealized"] = min(campaign["min_unrealized"], close_unrealized)

    def _process_open_position(self, symbol: str, campaign: dict, index: int, timestamp_ms: int):
        self._update_excursions(campaign, symbol, index)
        contract = self.data[symbol]["contract"]
        mark = self.data[symbol]["mark"]
        if stop_triggered(campaign["side"], campaign["stop"], mark["mark_open"][index],
                          mark["mark_high"][index], mark["mark_low"][index]):
            raw = stop_fill_raw(campaign["side"], contract["open"][index],
                                contract["high"][index], contract["low"][index],
                                contract["close"][index], mark["mark_open"][index],
                                campaign["stop"])
            detail = "INITIAL_STRUCTURAL_STOP" if campaign["stop_source"] == "INITIAL_SIGNAL" else campaign["stop_source"]
            self._close_campaign(symbol, campaign, raw, index, timestamp_ms, "STOP", detail)
            return
        watchers = campaign.get("opposition_watchers", [])
        remaining = []
        for watcher in watchers:
            signal = watcher["signal"]
            expires = int(getattr(signal, "expires_at_ms", 0) or 0)
            if expires and timestamp_ms >= expires:
                self._signal_status(symbol, signal, watcher["known_at_ms"], watcher.get("context", {}),
                                    "EXPIRED", "OPPOSITE_SIGNAL_EXPIRED")
                continue
            trigger = float(signal.trigger_price)
            invalidation = float(getattr(signal, "invalidation_price", 0.0) or signal.protective_reference)
            hit = (contract["high"][index] >= trigger if signal.side == "BUY"
                   else contract["low"][index] <= trigger)
            invalid = (contract["low"][index] <= invalidation if signal.side == "BUY"
                       else contract["high"][index] >= invalidation)
            if hit and invalid:
                self._signal_status(symbol, signal, watcher["known_at_ms"], watcher.get("context", {}),
                                    "AMBIGUOUS", "OPPOSITE_TRIGGER_AND_INVALIDATION_SAME_M5_BAR")
                continue
            if invalid:
                self._signal_status(symbol, signal, watcher["known_at_ms"], watcher.get("context", {}),
                                    "INVALIDATED", "OPPOSITE_SIGNAL_INVALIDATED")
                continue
            if not hit:
                remaining.append(watcher)
                continue
            mark_close = float(mark["mark_close"][index])
            blocker = tc2_opposite_exit_profit_blocker(
                campaign["side"], campaign["avg_entry"], mark_close,
                RISK_COST_ALLOWANCE, float(self.specs[symbol]["tick"]),
            )
            if blocker:
                self._signal_status(symbol, signal, watcher["known_at_ms"], watcher.get("context", {}),
                                    "TRIGGERED_ADVISORY", blocker)
                self._emit(timestamp_ms, "OPPOSITE_SIGNAL_NOT_PROFITABLE", symbol=symbol,
                           campaign_id=campaign["campaign_id"], signal_id=signal.signal_id, blocker=blocker)
                continue
            raw = (min(contract["open"][index], contract["low"][index], contract["close"][index])
                   if campaign["side"] == "BUY" else
                   max(contract["open"][index], contract["high"][index], contract["close"][index]))
            self._signal_status(symbol, signal, watcher["known_at_ms"], watcher.get("context", {}),
                                "TRIGGERED_EXIT", "")
            self._close_campaign(symbol, campaign, raw, index, timestamp_ms,
                                 "OPPOSITE_SIGNAL", _signal_type(signal))
            return
        campaign["opposition_watchers"] = remaining

    def _process_pending(self, symbol: str, campaign: dict, index: int, timestamp_ms: int):
        pending = campaign.get("pending")
        if not pending:
            return
        expires = int(pending.get("expires_at_ms", 0) or 0)
        if expires and timestamp_ms + M5_MS >= expires:
            signal = pending["signal"]
            self._signal_status(symbol, signal, pending["known_at_ms"], pending.get("context", {}),
                                "EXPIRED", "PENDING_ORDER_EXPIRED")
            self.expired_count += 1
            if pending["kind"] == "INITIAL":
                del self.active[symbol]
            else:
                campaign["pending"] = None
            self._emit(timestamp_ms, "PENDING_ORDER_EXPIRED", symbol=symbol,
                       campaign_id=campaign["campaign_id"], kind=pending["kind"])
            return
        contract = self.data[symbol]["contract"]
        trigger = float(pending["trigger"])
        side = campaign["side"]
        hit = (contract["high"][index] >= trigger if side == "BUY"
               else contract["low"][index] <= trigger)
        if pending["kind"] == "INITIAL":
            invalidation = float(pending["stop"])
            invalid = (contract["low"][index] <= invalidation if side == "BUY"
                       else contract["high"][index] >= invalidation)
            if invalid and hit:
                signal = pending["signal"]
                self._signal_status(symbol, signal, pending["known_at_ms"], pending.get("context", {}),
                                    "AMBIGUOUS", "ENTRY_TRIGGER_AND_INVALIDATION_SAME_M5_BAR")
                del self.active[symbol]
                self._emit(timestamp_ms, "PENDING_ENTRY_AMBIGUOUS_CANCELLED", symbol=symbol,
                           campaign_id=campaign["campaign_id"])
                return
            if invalid:
                signal = pending["signal"]
                self._signal_status(symbol, signal, pending["known_at_ms"], pending.get("context", {}),
                                    "INVALIDATED", "STRUCTURAL_INVALIDATION_BEFORE_ENTRY")
                del self.active[symbol]
                return
        if not hit:
            return
        raw = (max(float(contract["open"][index]), trigger) if side == "BUY"
               else min(float(contract["open"][index]), trigger))
        fill = entry_fill_price(raw, trigger, side, self.slippage_rate,
                                self.specs[symbol]["tick"])
        qty = float(pending["qty"])
        fee = qty * fill * self.fee_rate
        slip_quote = abs(fill - raw) * qty
        self.balance -= fee
        self.total_fees += fee
        self.total_slippage += slip_quote
        campaign["fees"] += fee
        campaign["slippage_quote"] += slip_quote
        campaign["fills"].append({"purpose": pending["kind"], "time_ms": timestamp_ms,
                                  "price": fill, "quantity": qty, "signal_id": pending["signal"].signal_id})
        if pending["kind"] == "INITIAL":
            campaign["status"] = "OPEN"
            campaign["qty"] = qty
            campaign["avg_entry"] = fill
            campaign["initial_entry_price"] = fill
            campaign["unit_qty"] = qty
            campaign["entry_time_ms"] = timestamp_ms
            campaign["entry_fee"] = fee
            self.entry_count += 1
            signal_status = "FILLED_INITIAL"
        else:
            old_qty = campaign["qty"]
            campaign["avg_entry"] = (old_qty * campaign["avg_entry"] + qty * fill) / (old_qty + qty)
            campaign["qty"] = old_qty + qty
            campaign["completed_quantities"].append(qty)
            campaign["additions"] += 1
            self.addition_count += 1
            signal_status = "FILLED_ADD_ON"
        campaign["pending"] = None
        self._signal_status(symbol, pending["signal"], pending["known_at_ms"],
                            pending.get("context", {}), signal_status)
        self._emit(timestamp_ms, "ORDER_FILLED", symbol=symbol, campaign_id=campaign["campaign_id"],
                   purpose=pending["kind"], quantity=qty, price=fill, fee=fee,
                   tranche=campaign["additions"])
        if pending["kind"] == "INITIAL":
            mark = self.data[symbol]["mark"]
            if stop_triggered(side, campaign["stop"], mark["mark_open"][index],
                              mark["mark_high"][index], mark["mark_low"][index]):
                raw_exit = stop_fill_raw(side, contract["open"][index], contract["high"][index],
                                         contract["low"][index], contract["close"][index],
                                         mark["mark_open"][index], campaign["stop"])
                self._close_campaign(symbol, campaign, raw_exit, index, timestamp_ms,
                                     "STOP", "SAME_M5_BAR_AS_INITIAL_FILL")

    def _record_equity(self, index: int, timestamp_ms: int):
        equity = self._equity(index, "mark_close")
        model_b = calculate_daily_risk_model_b(
            Decimal(str(self.daily_start_equity)), Decimal(str(equity)), Decimal("0")
        )
        loss_pct = float(model_b["loss_pct"])
        self.daily_min_equity = min(self.daily_min_equity, equity)
        self.daily_max_loss_pct = max(self.daily_max_loss_pct, loss_pct)
        if model_b["entry_blocked"] and not self.daily_entry_blocked:
            self.daily_entry_blocked = True
            self.daily_block_reason = "DAILY_RISK_MODEL_B_3PCT"
            self.daily_3pct_days.add(self.current_day)
            self._risk_event(timestamp_ms, self.daily_block_reason,
                             baseline=self.daily_start_equity, equity=equity, loss_pct=loss_pct)
        if model_b["emergency_breaker_tripped"] and not self.emergency_latched:
            self.emergency_latched = True
            self.emergency_latched_at_ms = timestamp_ms
            self.daily_entry_blocked = True
            self.daily_block_reason = "FUTURES_DAILY_EMERGENCY_BREAKER_5PCT_LATCHED"
            self.daily_5pct_days.add(self.current_day)
            self._risk_event(timestamp_ms, self.daily_block_reason,
                             baseline=self.daily_start_equity, equity=equity, loss_pct=loss_pct)
            # The Python shadow runtime latches and blocks new exposure. The
            # Android owner performs exchange-side flattening; no fabricated
            # Android liquidation is inserted into this Python replay.
            for symbol, campaign in list(self.active.items()):
                pending = campaign.get("pending")
                if pending:
                    self._signal_status(symbol, pending["signal"], pending["known_at_ms"],
                                        pending.get("context", {}), "CANCELLED",
                                        "EMERGENCY_BREAKER_NEW_EXPOSURE_PAUSED")
                    if pending["kind"] == "INITIAL":
                        del self.active[symbol]
                    else:
                        campaign["pending"] = None
        self.peak_equity = max(self.peak_equity, equity)
        drawdown = (equity / self.peak_equity - 1.0) if self.peak_equity > 0 else 0.0
        self.max_drawdown = min(self.max_drawdown, drawdown)
        if drawdown < 0 and self.drawdown_started_ms is None:
            self.drawdown_started_ms = timestamp_ms
        elif drawdown >= 0 and self.drawdown_started_ms is not None:
            self.max_drawdown_duration_ms = max(self.max_drawdown_duration_ms,
                                                timestamp_ms - self.drawdown_started_ms)
            self.drawdown_started_ms = None
        margin = self._margin_used(index)
        risk = self._reserved_risk()
        self.max_margin_pct = max(self.max_margin_pct, margin / equity if equity > 0 else 0.0)
        self.max_risk_pct = max(self.max_risk_pct, risk / equity if equity > 0 else 0.0)
        self._last_equity = equity
        self.digest.update((f"{timestamp_ms}|{self.balance:.12g}|{equity:.12g}|{len(self.active)}|{margin:.12g}|{risk:.12g}\n").encode())
        if self.collect:
            self.equity_rows.append({
                "time_ms": timestamp_ms, "wallet_balance": self.balance,
                "unrealized_pnl": equity - self.balance, "equity": equity,
                "drawdown_pct": drawdown * 100.0, "active_campaigns": len(self.active),
                "margin_used": margin, "margin_pct": margin / equity * 100.0 if equity > 0 else None,
                "reserved_risk": risk, "reserved_risk_pct": risk / equity * 100.0 if equity > 0 else None,
                "daily_loss_pct": loss_pct, "daily_entry_blocked": self.daily_entry_blocked,
                "emergency_latched": self.emergency_latched,
            })

    def _finalize_open(self, index: int, timestamp_ms: int):
        for symbol, campaign in list(self.active.items()):
            if campaign["status"] == "PENDING_INITIAL":
                pending = campaign.get("pending")
                if pending:
                    self._signal_status(symbol, pending["signal"], pending["known_at_ms"],
                                        pending.get("context", {}), "PENDING_AT_END", "")
            elif campaign["status"] == "OPEN":
                campaign["final_net_pnl"] = campaign["gross_realized"] + campaign["funding"] - campaign["fees"]
                campaign["final_qty"] = campaign["qty"]
                campaign["exit_reason"] = "OPEN_AT_END"
                campaign["exit_detail"] = "MARK_TO_MARKET_NOT_FORCE_CLOSED"
                if self.collect:
                    self.closed.append(self._campaign_output(campaign))
                self.digest.update((_canonical(self._campaign_output(campaign)) + "\n").encode())

    def run(self, *, end_index: int | None = None) -> dict:
        stop_index = len(self.timeline) if end_index is None else min(len(self.timeline), int(end_index))
        for index in range(stop_index):
            timestamp_ms = int(self.timeline[index])
            self._new_day(index, timestamp_ms)
            self._apply_funding(index, timestamp_ms)
            # Live process() manages confirmed protection before scanner admission.
            if timestamp_ms % H1_MS == 0:
                for symbol in self.symbols:
                    self._trail(symbol, index, timestamp_ms)
                    self._handle_signals(symbol, self.signals_by_index[symbol].get(index, []),
                                         index, timestamp_ms)
            # Existing protected positions are evaluated before any pending add-on.
            for symbol in self.symbols:
                campaign = self.active.get(symbol)
                if campaign and campaign["status"] == "OPEN":
                    self._process_open_position(symbol, campaign, index, timestamp_ms + M5_MS)
            # Initial and add-on STOP entries are checked on contract-price M5 OHLC.
            for symbol in self.symbols:
                campaign = self.active.get(symbol)
                if campaign and campaign.get("pending"):
                    self._process_pending(symbol, campaign, index, timestamp_ms + M5_MS)
            self._record_equity(index, timestamp_ms + M5_MS)
            self.max_active_campaigns = max(self.max_active_campaigns, len(self.active))
        if self.drawdown_started_ms is not None:
            self.max_drawdown_duration_ms = max(
                self.max_drawdown_duration_ms,
                int(self.timeline[stop_index - 1]) + M5_MS - self.drawdown_started_ms,
            )
        if stop_index:
            self._finalize_open(stop_index - 1, int(self.timeline[stop_index - 1]) + M5_MS)
        final_equity = self._equity(stop_index - 1, "mark_close") if stop_index else self.initial_capital
        closed_rows = self.closed if self.collect else []
        completed = self.completed_summaries
        nets = [float(x["net_pnl"]) for x in completed]
        wins = [x for x in nets if x > 1e-12]
        losses = [x for x in nets if x < -1e-12]
        gross_positive = sum(max(0.0, float(x.get("gross_pnl", 0.0))) for x in completed)
        gross_negative = abs(sum(min(0.0, float(x.get("gross_pnl", 0.0))) for x in completed))
        net_positive = sum(wins)
        net_negative = abs(sum(losses))
        metrics = {
            "initial_capital_usdt": self.initial_capital,
            "final_wallet_balance_usdt": self.balance,
            "final_equity_usdt": final_equity,
            "final_unrealized_pnl_usdt": final_equity - self.balance,
            "total_net_pnl_usdt": final_equity - self.initial_capital,
            "closed_campaign_net_pnl_usdt": sum(nets),
            "gross_profit_usdt": gross_positive,
            "gross_loss_usdt": gross_negative,
            "commission_usdt": self.total_fees,
            "funding_usdt": self.total_funding,
            "slippage_cost_usdt": self.total_slippage,
            "return_pct": (final_equity / self.initial_capital - 1.0) * 100.0,
            "closed_campaigns": len(completed),
            "open_campaigns_at_end": sum(1 for x in self.active.values() if x["status"] == "OPEN"),
            "pending_initial_at_end": sum(1 for x in self.active.values() if x["status"] == "PENDING_INITIAL"),
            "win_rate_pct": len(wins) / len(completed) * 100.0 if completed else None,
            "profit_factor_net": net_positive / net_negative if net_negative else (None if not wins else "UNBOUNDED"),
            "profit_factor_gross": gross_positive / gross_negative if gross_negative else (None if not gross_positive else "UNBOUNDED"),
            "expectancy_usdt": float(np.mean(nets)) if nets else None,
            "average_win_usdt": float(np.mean(wins)) if wins else None,
            "average_loss_usdt": float(np.mean(losses)) if losses else None,
            "average_R_multiple": float(np.mean([float(x["R_multiple"]) for x in completed if x.get("R_multiple") is not None])) if completed else None,
            "maximum_drawdown_pct": self.max_drawdown * 100.0,
            "maximum_drawdown_duration_hours": self.max_drawdown_duration_ms / 3_600_000,
            "max_active_campaigns": self.max_active_campaigns,
            "max_margin_usage_pct": self.max_margin_pct * 100.0,
            "max_reserved_risk_pct": self.max_risk_pct * 100.0,
            "entry_fills": self.entry_count,
            "add_on_fills": self.addition_count,
            "stop_exits": self.stop_count,
            "opposite_signal_exits": self.opposite_exit_count,
            "expired_pending_signals": self.expired_count,
            "replaced_pending_signals": self.replaced_count,
            "blocked_signal_decisions": self.blocked_count,
            "daily_3pct_block_days": len(self.daily_3pct_days),
            "daily_5pct_emergency_days": len(self.daily_5pct_days),
            "three_consecutive_loss_days": len(self.consecutive_loss_days),
            "emergency_latched": self.emergency_latched,
            "emergency_latched_at_ms": self.emergency_latched_at_ms,
            "simulation_digest_sha256": self.digest.hexdigest(),
            "trail_bars": self.trail_bars,
            "fee_rate_per_side": self.fee_rate,
            "slippage_rate_per_side": self.slippage_rate,
            "risk_model": {
                "model": "B", "daily_entry_loss_limit_pct": 3.0,
                "emergency_breaker_pct": 5.0, "portfolio_risk_cap_pct": 0.6,
                "campaign_risk_cap_pct": 0.6, "per_entry_risk_cap_pct": 0.25,
                "initial_unit_risk_pct": initial_unit_risk_pct(
                    INITIAL_ENTRY_RISK_CAP_PCT, CAMPAIGN_RISK_CAP_PCT, PER_ENTRY_RISK_CAP_PCT
                ) * 100.0,
                "margin_limit_pct": 50.0, "leverage": DEFAULT_LEVERAGE,
            },
            "execution_model": {
                "entry_trigger_basis": "CONTRACT_PRICE_M5_OHLC",
                "protective_stop_trigger_basis": "MARK_PRICE_M5_OHLC",
                "stop_fill": "WORST_OBSERVED_CONTRACT_OHLC_WITH_ADVERSE_SLIPPAGE",
                "intrabar_ambiguity": "CONSERVATIVE_CANCEL_FOR_PENDING_ENTRY_TRIGGER_AND_INVALIDATION_SAME_BAR",
                "funding": "ARCHIVED_BINANCE_FUNDING_RATE_AT_MARK_PRICE",
                "partial_fills": "NOT_RECONSTRUCTABLE_FROM_OHLC; NOT SIMULATED",
                "historical_contract_filters": "CURRENT_EXCHANGE_INFO_PROXY_ONLY",
                "liquidation_brackets": "HISTORICAL_BRACKETS_UNAVAILABLE; 2X DEFAULT AND 50% PORTFOLIO MARGIN CAP",
                "utc_midnight_baseline": "M5_MARK_OPEN_PROXY; NOT EXACT_1M_MARK_PRICE",
            },
        }
        if self.collect:
            self.daily_rows.append({
                "utc_day": int(self.current_day) if self.current_day is not None else None,
                "start_equity": self.daily_start_equity,
                "min_equity": self.daily_min_equity,
                "max_daily_loss_pct": self.daily_max_loss_pct,
                "entry_blocked": self.daily_entry_blocked,
                "block_reason": self.daily_block_reason,
                "loss_streak_at_close": self.loss_streak,
                "emergency_latched": self.emergency_latched,
            })
        return {
            "metrics": metrics,
            "campaigns": closed_rows,
            "signals": list(self.signal_records.values()) if self.collect else [],
            "fills": self.fills,
            "risk_events": self.risk_events,
            "events": self.events,
            "equity_curve": self.equity_rows,
            "daily": self.daily_rows,
            "digest": self.digest.hexdigest(),
        }
