"""Bidirectional Williams USD-M Futures runtime.

Canonical flow:
    closed candles -> Williams Wise-Men -> initial campaign -> conditional
    trigger -> filled Futures position -> reduce-only protection -> 1:5:4:3:2
    add-ons -> 3/5-bar structural trailing -> structural/opposite-signal exit.

Safety:
- USD-M Futures, one-way position mode, isolated margin.
- DRY_RUN defaults to true; live execution requires ALLOW_LIVE=true.
- Position sizing is based on loss to the structural stop, never on leverage.
- No fixed take-profit is used by the Williams campaign.
- Every order mutation goes through ExecutionBarrier.
- Recovery is position/order-state based, not wallet-inventory based.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
import os
import time
import uuid

from db import Database
from execution_barrier import ExecutionBarrier, OrderIntent
from market_context import ContextCache
from strategy import calculate_indicators, config_from_env
from williams_signals import extract_long_signal_specs, extract_short_signal_specs
from campaign_engine import CampaignEngine
from equity_breaker import EquityCircuitBreaker
from campaign_model import (
    CampaignState,
    SignalRole,
    SignalSpec,
    SignalState,
    SignalType,
    TradingCampaign,
    PendingOrderRecord,
    PendingSignal,
    stop_only_reduces_risk,
)
from data import fetch_klines
from binance_client import BinanceAPIError
from binance_futures_client import BinanceFuturesClient


@dataclass(frozen=True)
class FuturesCandidate:
    symbol: str
    side: str
    signal: SignalSpec
    all_signals: tuple[SignalSpec, ...]
    atr_pct: float
    spread_pct: float
    htf_confirmed: bool
    score: float

    def to_dict(self) -> dict:
        return {
            "symbol": self.symbol,
            "side": self.side,
            "signal": self.signal.to_dict(),
            "signal_type": self.signal.signal_type.value,
            "signal_bar_time_ms": int(self.signal.signal_bar_time_ms),
            "trigger_price": float(self.signal.trigger_price),
            "protective_reference": float(self.signal.protective_reference),
            "score": float(self.score),
            "atr_pct": float(self.atr_pct),
            "spread_pct": float(self.spread_pct),
            "htf_confirmed": bool(self.htf_confirmed),
            "wise_men": [
                {
                    "signal_id": s.signal_id,
                    "signal_type": s.signal_type.value,
                    "side": s.side,
                    "signal_bar_time_ms": int(s.signal_bar_time_ms),
                    "trigger_price": float(s.trigger_price),
                }
                for s in self.all_signals
            ],
        }


class FuturesWilliamsScanner:
    def __init__(self, client, *, interval: str, htf_interval: str = "1h"):
        self.client = client
        self.interval = str(interval).lower()
        self.htf_interval = str(htf_interval).lower()
        self.config = config_from_env()

    @staticmethod
    def _tick(info: dict) -> float:
        rows = info.get("symbols", []) if isinstance(info, dict) else []
        if not rows:
            return 0.0
        filters = {
            f.get("filterType"): f
            for f in rows[0].get("filters", [])
            if isinstance(f, dict)
        }
        return float((filters.get("PRICE_FILTER") or {}).get("tickSize", "0") or 0)

    def universe(self, limit: int = 40) -> list[str]:
        info = self.client.exchange_info()
        rows = info.get("symbols", []) if isinstance(info, dict) else []
        valid = []
        for row in rows:
            if str(row.get("status", "")).upper() != "TRADING":
                continue
            if str(row.get("quoteAsset", "")).upper() != "USDT":
                continue
            if str(row.get("contractType", "")).upper() != "PERPETUAL":
                continue
            symbol = str(row.get("symbol", "")).upper()
            if symbol:
                valid.append(symbol)

        try:
            tickers = self.client.ticker_24hr()
            volume = {
                str(row.get("symbol", "")).upper():
                float(row.get("quoteVolume", 0) or 0)
                for row in tickers or []
                if isinstance(row, dict)
            }
            valid.sort(key=lambda s: volume.get(s, 0.0), reverse=True)
        except Exception:
            valid.sort()
        return valid[: max(1, int(limit))]

    def _spread(self, symbol: str) -> float:
        row = self.client.book_ticker(symbol)
        bid = float(row.get("bidPrice", 0) or 0)
        ask = float(row.get("askPrice", 0) or 0)
        if bid <= 0 or ask <= 0 or ask < bid:
            return float("inf")
        return (ask - bid) / bid

    def _htf(self, symbol: str) -> tuple[bool, bool]:
        if not os.getenv("REQUIRE_HTF_CONFIRMATION", "true").lower() == "true":
            return True, True
        frame = fetch_klines(self.client, symbol, self.htf_interval, limit=180)
        if len(frame) < 90:
            return False, False
        closed = frame.iloc[:-1].copy() if len(frame) > 1 else frame
        ind = calculate_indicators(closed, self.config)
        last = ind.iloc[-1]
        return (
            bool(last.get("bullish_alligator", False)) and float(last.get("ao", 0) or 0) > 0,
            bool(last.get("bearish_alligator", False)) and float(last.get("ao", 0) or 0) < 0,
        )

    def analyse(self, symbol: str) -> list[FuturesCandidate]:
        symbol = str(symbol).upper()
        info = self.client.exchange_info(symbol)
        tick = self._tick(info)
        if tick <= 0:
            return []

        frame = fetch_klines(
            self.client,
            symbol,
            self.interval,
            limit=max(180, int(os.getenv("SCANNER_KLINE_LIMIT", "220"))),
        )
        if len(frame) < 110:
            return []
        closed = frame.iloc[:-1].copy() if len(frame) > 1 else frame
        ind = calculate_indicators(closed, self.config)

        atr = float(
            (closed["high"] - closed["low"]).rolling(
                max(5, int(os.getenv("ATR_PERIOD", "14")))
            ).mean().iloc[-1]
            or 0
        )
        price = float(closed.iloc[-1]["close"] or 0)
        atr_pct = atr / price if price > 0 else 1.0
        max_atr = float(os.getenv("MAX_ATR_PCT", "0.08"))
        if atr <= 0 or atr_pct > max_atr:
            return []

        spread_pct = self._spread(symbol)
        if not (spread_pct <= float(os.getenv("MAX_SPREAD_PCT", "0.0015"))):
            return []

        long_specs = extract_long_signal_specs(
            symbol,
            ind,
            timeframe=self.interval,
            tick_size=tick,
        )
        short_specs = extract_short_signal_specs(
            symbol,
            ind,
            timeframe=self.interval,
            tick_size=tick,
        )
        long_htf, short_htf = self._htf(symbol)
        out: list[FuturesCandidate] = []

        for side, specs, htf_ok in (
            ("LONG", long_specs, long_htf),
            ("SHORT", short_specs, short_htf),
        ):
            if side == "LONG" and not os.getenv("ALLOW_LONG", "true").lower() == "true":
                continue
            if side == "SHORT" and not os.getenv("ALLOW_SHORT", "true").lower() == "true":
                continue
            if not specs:
                continue
            signal_pool = [
                s for s in specs
                if s.side == ("BUY" if side == "LONG" else "SELL")
            ]
            if not signal_pool:
                continue
            # Campaign entry is the first valid presenting Wise Man. Do not
            # require multiple simultaneous Wise-Men confirmations.
            signal = min(signal_pool, key=lambda s: (s.signal_bar_time_ms, s.created_at_ms))
            # Keep both-direction signals visible to the campaign
            # manager. HTF confirmation is an initial-entry gate, not an
            # exit/add-on visibility gate.
            age_bonus = max(
                0.0,
                20.0 - max(0, int((int(time.time() * 1000) - signal.signal_bar_time_ms) / 60000))
            )
            freshness = age_bonus / 20.0
            type_bonus = {
                SignalType.REVERSAL: 3.0,
                SignalType.SUPER_AO: 2.0,
                SignalType.FRACTAL: 1.0,
            }.get(signal.signal_type, 0.0)
            score = 60.0 + freshness * 20.0 + type_bonus
            if signal.alligator_awake:
                score += 10.0
            out.append(
                FuturesCandidate(
                    symbol=symbol,
                    side=side,
                    signal=signal,
                    all_signals=tuple(signal_pool),
                    atr_pct=atr_pct,
                    spread_pct=spread_pct,
                    htf_confirmed=bool(htf_ok),
                    score=min(100.0, score),
                )
            )
        return out

    def scan(self) -> list[FuturesCandidate]:
        result: list[FuturesCandidate] = []
        for symbol in self.universe(int(os.getenv("FUTURES_SCAN_TOP_N", "40"))):
            try:
                result.extend(self.analyse(symbol))
            except Exception:
                continue
        result.sort(key=lambda c: (c.signal.signal_bar_time_ms, -c.score))
        return result


class FuturesWilliamsRuntime:
    """Production-facing bidirectional Williams Futures runtime."""

    is_futures_runtime = True

    def __init__(self, api_key=None, api_secret=None, testnet=None, context_cache=None):
        self.config = __import__("trading_config").TradingConfig.from_env()
        self.symbol = os.getenv("SYMBOL", self.config.symbols[0]).upper()
        self.interval = self.config.execution_timeframe
        self.htf_interval = os.getenv("HTF_INTERVAL", "1h")
        self.position_fraction = float(os.getenv("FUTURES_MAX_MARGIN_FRACTION", "0.25"))
        self.stop_pct = 0.02
        self.target_pct = 0.0
        self.poll_seconds = max(5, int(os.getenv("POLL_SECONDS", "20")))
        self.risk_per_trade_pct = self.config.risk_per_trade_pct
        self.max_daily_loss_pct = self.config.max_daily_loss_pct
        self.max_trades_day = int(os.getenv("MAX_TRADES_PER_DAY", "5"))
        self.max_consecutive_losses = self.config.max_consecutive_losses
        self.cooldown_minutes = self.config.cooldown_minutes
        self.min_risk_reward = 0.0
        self.atr_period = self.config.atr_period
        self.max_atr_pct = self.config.max_atr_pct
        self.max_spread_pct = self.config.max_spread_pct
        self.recovered = False
        self.preflight_report = None
        self.dry_run = self.config.dry_run
        self.active_symbol = self.symbol
        self._last_auto_scan = 0.0

        self.db = Database(
            os.getenv("WILLIAMS_DB_PATH")
            or os.getenv("DB_PATH")
            or "data/trader.sqlite3"
        )
        self.context_cache = context_cache or ContextCache()
        self.execution_barrier = ExecutionBarrier(self.context_cache, self.db)
        self.client = BinanceFuturesClient(
            api_key if api_key is not None else os.getenv("BINANCE_API_KEY", ""),
            api_secret if api_secret is not None else os.getenv("BINANCE_API_SECRET", ""),
            testnet=(
                os.getenv("TESTNET", "true").lower() == "true"
                if testnet is None else bool(testnet)
            ),
        )
        self.db.state_set("active_symbol", self.symbol)

        self.engine = CampaignEngine(
            self.db,
            portfolio_risk_limit_pct=float(os.getenv("MAX_TOTAL_RISK_PCT", "0.01")),
            campaign_risk_limit_pct=float(os.getenv("MAX_RISK_PER_TRADE_PCT", "0.005")),
            initial_risk_fraction_of_campaign=1.0 / 15.0,
        )
        self.scanner = FuturesWilliamsScanner(
            self.client,
            interval=self.interval,
            htf_interval=self.htf_interval,
        )
        self.auto_scan_symbols = []
        self.symbols = list(self.config.symbols)
        self.max_open_positions = max(0, int(os.getenv("MAX_OPEN_POSITIONS", "5")))
        self.max_total_risk_pct = min(0.01, max(0.0, float(os.getenv("MAX_TOTAL_RISK_PCT", "0.01"))))
        self.max_risk_per_trade_pct = min(0.005, max(0.0, float(os.getenv("MAX_RISK_PER_TRADE_PCT", "0.005"))))
        self.scan_workers = 1
        self.wave_top_n = 0
        self.liquidity_preselect = int(os.getenv("FUTURES_SCAN_TOP_N", "40"))
        self._locks: set[str] = set()
        self._initialised_symbols: set[str] = set()
        self.equity_breaker = EquityCircuitBreaker(self.max_daily_loss_pct)

    # Compatibility API used by the existing backend/cockpit.
    def notify(self, message: str):
        self.db.log_event("INFO", "runtime_notification", str(message))

    def state(self, symbol=None):
        symbol = str(symbol or self.symbol).upper()
        return self.db.state_get(f"position_state:{symbol}", "FLAT")

    def _set_state(self, symbol, state):
        symbol = str(symbol).upper()
        self.db.state_set(f"position_state:{symbol}", state)
        self.db.state_set("position_state", state)
        self.db.state_set("active_symbol", symbol)

    def open_trades(self):
        return self.db.open_trades()

    def open_positions(self):
        return self.open_trades()

    def unresolved_symbols(self):
        rows = self.db.conn.execute(
            "SELECT key,value FROM bot_state WHERE key LIKE 'position_state:%'"
        ).fetchall()
        return [
            str(row["key"]).split(":", 1)[1]
            for row in rows
            if str(row["value"]).upper() == "RECONCILE_REQUIRED"
        ]

    def _pending_entries(self):
        rows = self.db.conn.execute(
            "SELECT key,value FROM bot_state WHERE key LIKE 'entry_client_order_id:%'"
        ).fetchall()
        return [
            (str(row["key"]).split(":", 1)[1].upper(), str(row["value"]))
            for row in rows
        ]

    def _equity(self) -> float:
        account = self.client.account()
        try:
            return max(
                float(account.get("totalWalletBalance", 0) or 0)
                + float(account.get("totalUnrealizedProfit", 0) or 0),
                0.0,
            )
        except Exception:
            return max(float(account.get("availableBalance", 0) or 0), 0.0)

    def _available_margin(self) -> float:
        account = self.client.account()
        return max(float(account.get("availableBalance", 0) or 0), 0.0)

    def available_quote(self):
        return self._available_margin()

    def reserved_risk_quote(self):
        return float(self.engine.portfolio_reserved_risk_quote())

    def _daily_entry_guard(self):
        """Block new risk after daily loss, loss streak or cooldown limits."""
        from datetime import datetime, timezone
        trades_today = int(
            self.db.conn.execute(
                "SELECT COUNT(*) FROM trades WHERE entry_time >= date('now')"
            ).fetchone()[0] or 0
        )
        if self.max_trades_day and trades_today >= self.max_trades_day:
            return False, f"MAX_TRADES_PER_DAY reached: {trades_today}"

        recent = self.db.conn.execute(
            "SELECT pnl, exit_time FROM trades "
            "WHERE exit_time IS NOT NULL ORDER BY id DESC LIMIT 20"
        ).fetchall()
        losses = 0
        for row in recent:
            if float(row["pnl"] or 0) < 0:
                losses += 1
            else:
                break
        if self.max_consecutive_losses and losses >= self.max_consecutive_losses:
            return False, f"MAX_CONSECUTIVE_LOSSES reached: {losses}"

        if self.cooldown_minutes and recent and recent[0]["exit_time"]:
            try:
                stamp = datetime.fromisoformat(
                    str(recent[0]["exit_time"]).replace("Z", "+00:00")
                )
                elapsed = (datetime.now(timezone.utc) - stamp).total_seconds()
                if elapsed < self.cooldown_minutes * 60:
                    return False, (
                        f"COOLDOWN active: "
                        f"{self.cooldown_minutes * 60 - elapsed:.0f}s remaining"
                    )
            except ValueError:
                pass

        equity = self._equity()
        unrealized = 0.0
        for symbol in {str(x["symbol"]).upper() for x in self.open_trades()}:
            try:
                unrealized += float(
                    self.client.position(symbol).get("unRealizedProfit", 0) or 0
                )
            except Exception:
                return False, f"Risk gate cannot value {symbol} unrealized PnL"

        return self.equity_breaker.check(
            self.db,
            equity,
            None,
            unrealized,
            0.0,
        )

    def _filters(self, symbol):
        info = self.client.exchange_info(symbol)
        rows = info.get("symbols", [])
        if not rows:
            raise RuntimeError(f"{symbol}: Futures symbol not found")
        return {
            f.get("filterType"): f
            for f in rows[0].get("filters", [])
            if isinstance(f, dict)
        }

    def _tick(self, symbol):
        return float((self._filters(symbol).get("PRICE_FILTER") or {}).get("tickSize", "0") or 0)

    def _normalize_price(self, symbol, price, *, upward=False):
        tick = self._tick(symbol)
        if tick <= 0:
            raise RuntimeError(f"{symbol}: missing Futures tickSize")
        from decimal import Decimal, ROUND_CEILING, ROUND_FLOOR
        q = Decimal(str(price)) / Decimal(str(tick))
        rounding = ROUND_CEILING if upward else ROUND_FLOOR
        return float(q.to_integral_value(rounding=rounding) * Decimal(str(tick)))

    def _normalize_qty(self, symbol, qty):
        f = self._filters(symbol)
        lot = f.get("LOT_SIZE") or f.get("MARKET_LOT_SIZE") or {}
        step = float(lot.get("stepSize", "0") or 0)
        minimum = float(lot.get("minQty", "0") or 0)
        maximum = float(lot.get("maxQty", "0") or 0)
        if step <= 0:
            raise RuntimeError(f"{symbol}: missing Futures stepSize")
        value = float(self.client.decimal_floor(qty, step))
        if maximum > 0:
            value = min(value, float(self.client.decimal_floor(maximum, step)))
        if value < minimum:
            return 0.0
        return value

    def _min_notional(self, symbol):
        f = self._filters(symbol)
        row = f.get("MIN_NOTIONAL") or f.get("NOTIONAL") or {}
        return float(row.get("notional", row.get("minNotional", 0)) or 0)

    def _ensure_symbol_config(self, symbol):
        symbol = str(symbol).upper()
        if symbol in self._initialised_symbols:
            return
        self.client.set_margin_type(symbol, os.getenv("FUTURES_MARGIN_TYPE", "ISOLATED"))
        self.client.set_leverage(
            symbol,
            max(1, min(20, int(os.getenv("FUTURES_LEVERAGE", "2")))),
        )
        self._initialised_symbols.add(symbol)

    def _preflight(self):
        report = {"ready": False, "environment": "TESTNET" if self.client.testnet else "LIVE", "checks": {}, "details": {}}
        if not self.client.api_key or not self.client.api_secret:
            report["checks"]["credentials"] = "FAIL"
            report["details"]["reason"] = "missing Binance API credentials"
            return report
        try:
            self.client.sync_time()
            report["checks"]["server_time"] = "PASS"
            self.client.ping()
            report["checks"]["api_reachable"] = "PASS"

            account = self.client.account()
            report["checks"]["futures_account"] = (
                "PASS" if account.get("status", "TRADING") == "TRADING" else "FAIL"
            )
            if report["checks"]["futures_account"] != "PASS":
                return report

            # Production Futures runtime uses one-way + single-asset mode.
            # Both are account-wide settings and must be changed only when
            # there are no existing positions/orders.
            try:
                multi_mode = self.client.get_multi_assets_mode()
                multi_assets = bool(multi_mode.get("multiAssetsMargin", False))
            except Exception:
                multi_assets = False
            if multi_assets:
                existing_positions = [
                    p for p in (self.client.position_risk() or [])
                    if abs(float(p.get("positionAmt", 0) or 0)) > 0
                ]
                existing_orders = self.client.open_orders()
                if existing_positions or existing_orders:
                    report["checks"]["multi_assets_mode"] = "FAIL"
                    report["details"]["reason"] = "Multi-Assets Mode active with existing position/orders"
                    return report
                self.client.set_multi_assets_mode_single()
                multi_assets = bool(
                    self.client.get_multi_assets_mode().get("multiAssetsMargin", False)
                )
            report["checks"]["multi_assets_mode"] = "PASS" if not multi_assets else "FAIL"
            if multi_assets:
                return report

            mode = self.client.get_position_mode()
            dual = bool(mode.get("dualSidePosition", False))
            if dual:
                existing_positions = [
                    p for p in (self.client.position_risk() or [])
                    if abs(float(p.get("positionAmt", 0) or 0)) > 0
                ]
                if existing_positions or self.client.open_orders():
                    report["checks"]["position_mode"] = "FAIL"
                    report["details"]["reason"] = "Hedge Mode active with existing position/orders"
                    return report
                self.client.set_position_mode_one_way()
                mode = self.client.get_position_mode()
                dual = bool(mode.get("dualSidePosition", False))
            report["checks"]["position_mode"] = "PASS" if not dual else "FAIL"
            if dual:
                return report

            managed_symbols = {
                str(x["symbol"]).upper()
                for x in self.db.open_campaigns()
                if str(x.get("state", "")).upper() not in {"CLOSED", "FLAT"}
            }
            foreign_positions = []
            for position in self.client.position_risk() or []:
                amount = abs(float(position.get("positionAmt", 0) or 0))
                symbol = str(position.get("symbol", "")).upper()
                if amount > 0 and symbol not in managed_symbols:
                    foreign_positions.append({
                        "symbol": symbol,
                        "positionAmt": position.get("positionAmt"),
                        "positionSide": position.get("positionSide"),
                    })
            if foreign_positions:
                report["checks"]["foreign_positions"] = "FAIL"
                report["details"]["foreign_positions"] = foreign_positions
                return report
            report["checks"]["foreign_positions"] = "PASS"

            unknown_orders = []
            for order in self.client.open_orders() or []:
                cid = str(order.get("clientOrderId", ""))
                if not cid.startswith("WILLF_"):
                    unknown_orders.append({
                        "symbol": order.get("symbol"),
                        "orderId": order.get("orderId"),
                        "clientOrderId": cid,
                        "type": order.get("type"),
                    })
            if unknown_orders:
                report["checks"]["foreign_orders"] = "FAIL"
                report["details"]["foreign_orders"] = unknown_orders
                return report
            report["checks"]["foreign_orders"] = "PASS"

            info = self.client.exchange_info(self.symbol)
            if not info.get("symbols"):
                report["checks"]["exchange_info"] = "FAIL"
                return report
            report["checks"]["exchange_info"] = "PASS"
            report["checks"]["market_mode"] = "USD_M_FUTURES"
            report["details"]["leverage"] = max(1, min(20, int(os.getenv("FUTURES_LEVERAGE", "2"))))
            report["details"]["margin_type"] = os.getenv("FUTURES_MARGIN_TYPE", "ISOLATED").upper()
            report["details"]["allow_long"] = self.config.allow_long
            report["details"]["allow_short"] = self.config.allow_short
            report["ready"] = True
            return report
        except Exception as exc:
            report["checks"]["runtime"] = "FAIL"
            report["details"]["reason"] = f"{type(exc).__name__}: {exc}"
            return report

    def setup(self):
        self.preflight_report = self._preflight()
        if not self.preflight_report.get("ready"):
            raise RuntimeError("FUTURES SAFETY GATE BLOCKED: " + str(self.preflight_report))
        self.recover()
        self.recovered = True
        self.notify(
            f"Williams Futures READY | TESTNET={self.client.testnet} | "
            f"LONG={self.config.allow_long} SHORT={self.config.allow_short} | "
            f"LEVERAGE={os.getenv('FUTURES_LEVERAGE', '2')} | "
            f"MARGIN={os.getenv('FUTURES_MARGIN_TYPE', 'ISOLATED')}"
        )

    def _position(self, symbol):
        return self.client.position(symbol)

    @staticmethod
    def _signed_position_qty(position):
        return float(position.get("positionAmt", 0) or 0)

    def _active_campaign(self, symbol):
        rows = self.db.conn.execute(
            "SELECT campaign_id FROM campaigns WHERE symbol=? "
            "AND state NOT IN ('CLOSED','FLAT') ORDER BY updated_at DESC LIMIT 1",
            (str(symbol).upper(),),
        ).fetchall()
        if not rows:
            return None
        return self.engine.load_campaign(str(rows[0]["campaign_id"]))

    def _position_matches(self, campaign, position):
        qty = self._signed_position_qty(position)
        expected = float(campaign.position_qty or 0)
        if expected <= 0:
            return abs(qty) <= 1e-12
        if campaign.side == "BUY":
            return qty > 0 and abs(qty - expected) <= max(expected * 0.01, 1e-12)
        return qty < 0 and abs(abs(qty) - expected) <= max(expected * 0.01, 1e-12)

    def _liquidation_guard(self, campaign, position):
        liq = float(position.get("liquidationPrice", 0) or 0)
        stop = float(campaign.current_stop_price or 0)
        entry = float(position.get("entryPrice", 0) or campaign.average_entry_price or 0)
        if liq <= 0 or stop <= 0 or entry <= 0:
            return True
        buffer = max(0.0, float(os.getenv("FUTURES_LIQUIDATION_BUFFER_PCT", "0.01")))
        if campaign.side == "BUY":
            return liq < stop and ((stop - liq) / stop) >= buffer
        return liq > stop and ((liq - stop) / stop) >= buffer

    def _record_trade_if_missing(self, campaign, order, avg, qty):
        symbol = campaign.symbol
        if self.db.open_trade(symbol) is not None:
            return
        self.db.save_trade(
            entry_time=datetime.fromtimestamp(
                int(order.get("transactTime", order.get("time", int(time.time() * 1000))) or 0) / 1000,
                tz=timezone.utc,
            ).isoformat(),
            symbol=symbol,
            side="LONG" if campaign.side == "BUY" else "SHORT",
            entry_price=avg,
            quantity=qty,
            entry_order_id=str(order.get("orderId", "")),
            entry_client_order_id=str(order.get("clientOrderId", "")),
            stop_price=float(campaign.current_stop_price),
            take_profit_price=None,
            risk_pct=float(campaign.open_risk_quote / max(self._equity(), 1e-12)) * 100.0,
            fees=0.0,
        )

    def _submit(self, intent, fn, checks=None):
        return self.execution_barrier.execute(
            intent,
            fn,
            pre_submit_checks=checks,
        )

    def _client_id(self, prefix):
        return f"WILLF_{prefix}_{uuid.uuid4().hex[:20]}"

    @staticmethod
    def _set_pending_signal_state(campaign, state, *, filled_quantity=None):
        pending = campaign.tags.get("pending_signal")
        if not isinstance(pending, dict):
            return
        pending["state"] = state.value if isinstance(state, SignalState) else str(state)
        if filled_quantity is not None:
            pending["filled_quantity"] = float(filled_quantity)
        pending["updated_at_ms"] = int(time.time() * 1000)
        campaign.tags["pending_signal"] = pending

    def _initial_risk_pct(self):
        campaign_cap = float(os.getenv("MAX_RISK_PER_TRADE_PCT", "0.005"))
        return max(0.0, campaign_cap * 1.0 / 15.0)

    def _next_add_risk_pct(self, campaign):
        cap = float(os.getenv("MAX_RISK_PER_TRADE_PCT", "0.005"))
        weights = (1, 5, 4, 3, 2)
        idx = min(max(int(campaign.tranche_index), 1), len(weights) - 1)
        weight = weights[idx]
        used = float(campaign.open_risk_quote or 0.0) + float(campaign.pending_risk_quote or 0.0)
        remaining = max(0.0, cap * self._equity() - used)
        return min(remaining / max(self._equity(), 1e-12), cap * weight / 15.0)

    def _size_from_risk(self, symbol, trigger, stop, risk_pct):
        equity = self._equity()
        risk_quote = equity * max(0.0, float(risk_pct))
        if equity <= 0 or risk_quote <= 0 or trigger <= 0 or stop <= 0:
            raise RuntimeError("invalid equity/risk/price inputs")
        distance_fraction = abs(trigger - stop) / trigger
        if distance_fraction <= 0:
            raise RuntimeError("zero stop distance")
        fee_buffer = 2.0 * max(0.0, float(os.getenv("FEE_BUFFER_PER_SIDE_PCT", "0.001")))
        slippage_buffer = max(0.0, float(os.getenv("RISK_SLIPPAGE_BUFFER_PCT", "0.0015")))
        effective_loss_fraction = distance_fraction + fee_buffer + slippage_buffer
        leverage = max(1, int(os.getenv("FUTURES_LEVERAGE", "2")))
        max_margin_fraction = max(
            0.01,
            min(0.90, float(os.getenv("FUTURES_MAX_MARGIN_FRACTION", "0.25"))),
        )
        max_notional = equity * max_margin_fraction * leverage
        notional = min(risk_quote / max(effective_loss_fraction, 1e-12), max_notional)
        qty = self._normalize_qty(symbol, notional / trigger)
        if qty <= 0:
            raise RuntimeError("sized quantity below Futures minimum")
        if self._min_notional(symbol) and qty * trigger < self._min_notional(symbol):
            raise RuntimeError("sized notional below Futures minimum")
        return qty, risk_quote, notional

    def _arm_entry(self, candidate: FuturesCandidate):
        signal = candidate.signal
        symbol = signal.symbol
        self._ensure_symbol_config(symbol)
        trigger = self._normalize_price(
            symbol,
            signal.trigger_price,
            upward=signal.side == "BUY",
        )
        stop = self._normalize_price(
            symbol,
            signal.protective_reference,
            upward=signal.side == "SELL",
        )
        if signal.side == "BUY":
            if stop >= trigger:
                raise RuntimeError("LONG protective stop is not below entry trigger")
        else:
            if stop <= trigger:
                raise RuntimeError("SHORT protective stop is not above entry trigger")

        if (
            self.config.require_htf_confirmation
            and not candidate.htf_confirmed
        ):
            raise RuntimeError("HTF confirmation gate blocked initial campaign")

        current = float(self.client.ticker_price(symbol).get("price", 0) or 0)
        if current <= 0:
            raise RuntimeError("invalid current price")
        if signal.side == "BUY" and current >= trigger:
            raise RuntimeError("LONG trigger already crossed")
        if signal.side == "SELL" and current <= trigger:
            raise RuntimeError("SHORT trigger already crossed")

        entry_ok, entry_reason = self._daily_entry_guard()
        if not entry_ok:
            raise RuntimeError(entry_reason)

        qty, risk_quote, notional = self._size_from_risk(
            symbol, trigger, stop, self._initial_risk_pct()
        )
        campaign = self.engine.create_campaign(signal, initial_risk_pct=self._initial_risk_pct())
        campaign.initial_stop_price = stop
        campaign.current_stop_price = stop
        campaign.tags["signal_bar_time_ms"] = int(signal.signal_bar_time_ms)
        campaign.tags["last_signal_time_ms"] = int(signal.signal_bar_time_ms)
        campaign.pending_risk_quote = risk_quote
        campaign.capital_reserved_quote = notional / max(1, int(os.getenv("FUTURES_LEVERAGE", "2")))
        campaign.tags["pending_order_quantity"] = qty
        campaign.tags["pending_order_trigger"] = trigger
        campaign.tags["last_signal_time_ms"] = int(signal.signal_bar_time_ms)
        campaign.tags["signal_bar_time_ms"] = int(signal.signal_bar_time_ms)
        campaign.tags["pending_order_stop"] = stop
        campaign.tags["pending_order_client_id"] = self._client_id("ENTRY")
        self.db.save_campaign(campaign)

        cid = campaign.tags["pending_order_client_id"]
        if not self.db.try_claim_state(f"entry_client_order_id:{symbol}", cid):
            campaign.pending_risk_quote = 0.0
            campaign.capital_reserved_quote = 0.0
            campaign.state = CampaignState.CLOSED
            self.db.save_campaign(campaign)
            raise RuntimeError(f"{symbol}: pending entry already exists")

        pending_signal = PendingSignal(signal=signal)
        pending_signal.transition(SignalState.VALIDATED)
        campaign.tags["pending_signal"] = pending_signal.to_dict()
        self.db.set_campaign_signal_state(signal.signal_id, SignalState.VALIDATED.value)
        self.db.save_campaign(campaign)
        try:
            self.engine.arm_entry(campaign, signal)
        except Exception:
            self.db.state_delete(f"entry_client_order_id:{symbol}")
            campaign.pending_risk_quote = 0.0
            campaign.capital_reserved_quote = 0.0
            campaign.state = CampaignState.CLOSED
            self.db.save_campaign(campaign)
            raise
        self._set_state(symbol, "ENTRY_PENDING")

        intent = OrderIntent.new(
            symbol,
            signal.side,
            "STOP_MARKET",
            required_context_versions={},
            hypothesis_id=f"WILLIAMS_{signal.signal_type.value}",
            invalidation_level=stop,
            quantity=self.client.decimal_format(qty),
            client_order_id=cid,
            purpose="CAMPAIGN_ENTRY",
            permission_interval=signal.timeframe,
            campaign_id=campaign.campaign_id,
            signal_id=signal.signal_id,
            risk_quote=risk_quote,
            capital_reserved_quote=campaign.capital_reserved_quote,
        )
        try:
            result = self._submit(
                intent,
                lambda: self.client.order_safe(
                    symbol,
                    signal.side,
                    "STOP_MARKET",
                    quantity=self.client.decimal_format(qty),
                    stop_price=self.client.decimal_format(trigger),
                    new_client_order_id=cid,
                ),
                lambda _snapshot: None,
            )
            self.db.save_campaign_order(
                PendingOrderRecord(
                    order_id=str(result.get("orderId", "")),
                    client_order_id=cid,
                    symbol=symbol,
                    side=signal.side,
                    order_type="STOP_MARKET",
                    purpose="ENTRY",
                    status=str(result.get("status", "NEW")),
                    stop_price=trigger,
                    quantity=qty,
                    risk_quote=risk_quote,
                    capital_reserved_quote=campaign.capital_reserved_quote,
                    signal_id=signal.signal_id,
                    campaign_id=campaign.campaign_id,
                )
            )
            pending_signal.order_id = str(result.get("orderId", ""))
            pending_signal.client_order_id = cid
            pending_signal.transition(SignalState.ARMED)
            campaign.tags["pending_signal"] = pending_signal.to_dict()
            campaign.tags["pending_order_id"] = str(result.get("orderId", ""))
            self.db.save_campaign(campaign)
            return {"campaign_id": campaign.campaign_id, "order_id": result.get("orderId"), "client_order_id": cid, "trigger_price": trigger, "stop_price": stop, "quantity": qty}
        except Exception:
            self._set_state(symbol, "RECONCILE_REQUIRED")
            raise

    def _arm_add_on(self, campaign, signal):
        if signal.side != campaign.side:
            raise RuntimeError("add-on direction mismatch")
        self._ensure_symbol_config(signal.symbol)
        trigger = self._normalize_price(
            signal.symbol,
            signal.trigger_price,
            upward=signal.side == "BUY",
        )
        current = float(self.client.ticker_price(signal.symbol).get("price", 0) or 0)
        if (signal.side == "BUY" and current >= trigger) or (signal.side == "SELL" and current <= trigger):
            raise RuntimeError("add-on trigger already crossed")

        add_ok, add_reason = self._daily_entry_guard()
        if not add_ok:
            raise RuntimeError(add_reason)

        stop = self._normalize_price(
            signal.symbol,
            campaign.current_stop_price,
            upward=signal.side == "SELL",
        )
        qty, risk_quote, notional = self._size_from_risk(
            signal.symbol,
            trigger,
            stop,
            self._next_add_risk_pct(campaign),
        )
        campaign.tags["pending_order_quantity"] = qty
        campaign.tags["pending_order_trigger"] = trigger
        campaign.pending_risk_quote = risk_quote
        campaign.capital_reserved_quote = notional / max(1, int(os.getenv("FUTURES_LEVERAGE", "2")))
        campaign.tags["pending_order_client_id"] = self._client_id("ADD")
        self.db.save_campaign(campaign)
        cid = campaign.tags["pending_order_client_id"]
        if not self.db.try_claim_state(f"entry_client_order_id:{signal.symbol}", cid):
            raise RuntimeError(f"{signal.symbol}: another pending order exists")

        add_signal = __import__("dataclasses").replace(signal, role=SignalRole.ADD_ON)
        pending_signal = PendingSignal(signal=add_signal)
        pending_signal.transition(SignalState.VALIDATED)
        campaign.tags["pending_signal"] = pending_signal.to_dict()
        self.db.set_campaign_signal_state(add_signal.signal_id, SignalState.VALIDATED.value)
        self.db.save_campaign(campaign)
        self.engine.arm_add_on(
            campaign,
            add_signal,
            risk_quote=risk_quote,
            capital_reserved_quote=campaign.capital_reserved_quote,
        )
        intent = OrderIntent.new(
            signal.symbol,
            signal.side,
            "STOP_MARKET",
            required_context_versions={},
            hypothesis_id=f"WILLIAMS_ADD_{signal.signal_type.value}",
            invalidation_level=stop,
            quantity=self.client.decimal_format(qty),
            client_order_id=cid,
            purpose="CAMPAIGN_ADD_ON",
            campaign_id=campaign.campaign_id,
            signal_id=signal.signal_id,
            risk_quote=risk_quote,
            capital_reserved_quote=campaign.capital_reserved_quote,
        )
        result = self._submit(
            intent,
            lambda: self.client.order_safe(
                signal.symbol,
                signal.side,
                "STOP_MARKET",
                quantity=self.client.decimal_format(qty),
                stop_price=self.client.decimal_format(trigger),
                new_client_order_id=cid,
            ),
            lambda _snapshot: None,
        )
        self.db.save_campaign_order(
            PendingOrderRecord(
                order_id=str(result.get("orderId", "")),
                client_order_id=cid,
                symbol=signal.symbol,
                side=signal.side,
                order_type="STOP_MARKET",
                purpose="ADD_ON",
                status=str(result.get("status", "NEW")),
                stop_price=trigger,
                quantity=qty,
                risk_quote=risk_quote,
                capital_reserved_quote=campaign.capital_reserved_quote,
                signal_id=signal.signal_id,
                campaign_id=campaign.campaign_id,
            )
        )
        pending_signal.order_id = str(result.get("orderId", ""))
        pending_signal.client_order_id = cid
        pending_signal.transition(SignalState.ARMED)
        campaign.tags["pending_signal"] = pending_signal.to_dict()
        campaign.tags["pending_order_id"] = str(result.get("orderId", ""))
        self.db.save_campaign(campaign)
        return {"campaign_id": campaign.campaign_id, "order_id": result.get("orderId"), "client_order_id": cid, "trigger_price": trigger, "quantity": qty}

    def _cancel(self, symbol, order_id, purpose="CAMPAIGN_CANCEL"):
        intent = OrderIntent.new(
            symbol,
            "SELL",
            "CANCEL",
            required_context_versions={},
            client_order_id=self._client_id("CANCEL"),
            purpose="CAMPAIGN_CANCEL",
        )
        return self._submit(
            intent,
            lambda: self.client.cancel_order(symbol, order_id=order_id),
            lambda _snapshot: None,
        )

    def _protect(self, campaign):
        symbol = campaign.symbol
        qty = self._normalize_qty(symbol, abs(float(campaign.position_qty)))
        if qty <= 0:
            raise RuntimeError("cannot protect empty position")
        side = "SELL" if campaign.side == "BUY" else "BUY"
        cid = self._client_id("STOP")
        intent = OrderIntent.new(
            symbol,
            side,
            "STOP_MARKET",
            required_context_versions={},
            client_order_id=cid,
            purpose="CAMPAIGN_PROTECTION",
            campaign_id=campaign.campaign_id,
            signal_id=campaign.current_signal_id,
        )
        result = self._submit(
            intent,
            lambda: self.client.order_safe(
                symbol,
                side,
                "STOP_MARKET",
                quantity=self.client.decimal_format(qty),
                stop_price=self.client.decimal_format(campaign.current_stop_price),
                new_client_order_id=cid,
                reduce_only=True,
            ),
            lambda _snapshot: None,
        )
        campaign.tags["protective_order_id"] = str(result.get("orderId", ""))
        campaign.tags["protective_order_client_id"] = cid
        self.db.save_campaign_order(
            PendingOrderRecord(
                order_id=str(result.get("orderId", "")),
                client_order_id=cid,
                symbol=symbol,
                side=side,
                order_type="STOP_MARKET",
                purpose="PROTECTION",
                status=str(result.get("status", "NEW")),
                stop_price=float(campaign.current_stop_price),
                quantity=qty,
                risk_quote=campaign.open_risk_quote,
                signal_id=campaign.current_signal_id,
                campaign_id=campaign.campaign_id,
            )
        )
        self.db.save_campaign(campaign)
        return result

    def _replace_protection(self, campaign, proposed, *, allow_same_price: bool = False):
        """Place the new reduce-only stop before removing the old stop."""
        current = float(campaign.current_stop_price or 0)
        if not stop_only_reduces_risk(campaign.side, current, proposed):
            if not (allow_same_price and abs(float(proposed) - current) <= max(self._tick(campaign.symbol), 1e-12)):
                return False
        symbol = campaign.symbol
        proposed = self._normalize_price(
            symbol,
            proposed,
            upward=campaign.side == "SELL",
        )
        qty = self._normalize_qty(symbol, abs(float(campaign.position_qty)))
        if qty <= 0:
            raise RuntimeError("cannot replace protection for empty position")
        side = "SELL" if campaign.side == "BUY" else "BUY"
        cid = self._client_id("STOP")
        intent = OrderIntent.new(
            symbol,
            side,
            "STOP_MARKET",
            required_context_versions={},
            client_order_id=cid,
            purpose="CAMPAIGN_TRAIL",
            campaign_id=campaign.campaign_id,
            signal_id=campaign.current_signal_id,
        )
        result = self._submit(
            intent,
            lambda: self.client.order_safe(
                symbol,
                side,
                "STOP_MARKET",
                quantity=self.client.decimal_format(qty),
                stop_price=self.client.decimal_format(proposed),
                new_client_order_id=cid,
                reduce_only=True,
            ),
            lambda _snapshot: None,
        )
        new_order_id = str(result.get("orderId", ""))
        if not new_order_id:
            self._set_state(symbol, "RECONCILE_REQUIRED")
            raise RuntimeError(f"{symbol}: new protective order has no orderId")
        old_order_id = int(campaign.tags.get("protective_order_id", "0") or 0)
        if old_order_id > 0 and str(old_order_id) != new_order_id:
            try:
                self._cancel(symbol, old_order_id, "CAMPAIGN_OLD_STOP_CANCEL")
            except Exception as exc:
                self.db.log_event(
                    "ERROR",
                    "futures_old_stop_cancel_failed",
                    str(exc),
                    {"symbol": symbol, "old_order_id": old_order_id, "new_order_id": new_order_id},
                )
                self._set_state(symbol, "RECONCILE_REQUIRED")
                raise
        self.db.save_campaign_order(
            PendingOrderRecord(
                order_id=new_order_id,
                client_order_id=cid,
                symbol=symbol,
                side=side,
                order_type="STOP_MARKET",
                purpose="PROTECTION",
                status=str(result.get("status", "NEW")),
                stop_price=proposed,
                quantity=qty,
                risk_quote=campaign.open_risk_quote,
                signal_id=campaign.current_signal_id,
                campaign_id=campaign.campaign_id,
            )
        )
        campaign.current_stop_price = proposed
        campaign.structural_stop_source = "3_5_BAR_STRUCTURE"
        campaign.tags["protective_order_id"] = new_order_id
        campaign.tags["protective_order_client_id"] = cid
        self.db.save_campaign(campaign)
        return True


    def _exit_market(self, campaign, reason):
        """Reduce the position first; cancel the protective stop afterwards."""
        symbol = campaign.symbol
        position = self._position(symbol)
        pos_qty = abs(self._signed_position_qty(position))
        qty = self._normalize_qty(symbol, pos_qty)
        if qty <= 0:
            return {"state": "ALREADY_FLAT", "symbol": symbol}

        side = "SELL" if campaign.side == "BUY" else "BUY"
        cid = self._client_id("EXIT")
        intent = OrderIntent.new(
            symbol,
            side,
            "MARKET",
            required_context_versions={},
            quantity=self.client.decimal_format(qty),
            client_order_id=cid,
            purpose="CAMPAIGN_EXIT",
            campaign_id=campaign.campaign_id,
            signal_id=campaign.current_signal_id,
        )
        result = self._submit(
            intent,
            lambda: self.client.order_safe(
                symbol,
                side,
                "MARKET",
                quantity=self.client.decimal_format(qty),
                new_client_order_id=cid,
                reduce_only=True,
            ),
            lambda _snapshot: None,
        )
        self.db.save_order(result)

        verify = self._position(symbol)
        remaining = abs(self._signed_position_qty(verify))
        if remaining > max(float(os.getenv("MIN_RECOVERY_QTY", "0.000001")), qty * 0.01):
            self._set_state(symbol, "RECONCILE_REQUIRED")
            raise RuntimeError(f"{symbol}: Futures exit left residual position {remaining}")

        protective = int(campaign.tags.get("protective_order_id", "0") or 0)
        if protective > 0:
            try:
                self._cancel(symbol, protective, "CAMPAIGN_PROTECTION_CANCEL")
            except Exception as exc:
                self._set_state(symbol, "RECONCILE_REQUIRED")
                raise RuntimeError(f"{symbol}: protective order cancellation ambiguous after flat: {exc}") from exc

        position_price = float(result.get("avgPrice", 0) or result.get("price", 0) or 0)
        if position_price <= 0:
            try:
                fills = self.client.my_trades(symbol, order_id=result.get("orderId"), limit=1000) or []
            except Exception:
                fills = []
            qty_fill = sum(float(x.get("qty", 0) or 0) for x in fills)
            quote_fill = sum(
                float(x.get("qty", 0) or 0) * float(x.get("price", 0) or 0)
                for x in fills
            )
            position_price = quote_fill / qty_fill if qty_fill > 0 and quote_fill > 0 else 0.0
        if position_price <= 0:
            position_price = float(
                self.client.ticker_price(symbol).get("price", 0) or 0
            )

        trade = self.db.open_trade(symbol)
        if trade is not None:
            entry = float(campaign.average_entry_price or trade.get("entry_price") or 0)
            qty_trade = float(campaign.position_qty or trade.get("quantity") or 0)
            pnl_pct = (
                (position_price / entry - 1.0) if campaign.side == "BUY"
                else (entry / position_price - 1.0)
            ) if entry > 0 and position_price > 0 else 0.0
            pnl = (
                (position_price - entry) * qty_trade
                if campaign.side == "BUY"
                else (entry - position_price) * qty_trade
            )
            self.db.close_trade(
                trade["id"],
                datetime.now(timezone.utc).isoformat(),
                position_price,
                pnl,
                pnl_pct,
                reason,
                fees=float(trade.get("fees") or 0),
            )

        campaign.position_qty = 0.0
        campaign.open_risk_quote = 0.0
        campaign.pending_risk_quote = 0.0
        campaign.capital_reserved_quote = 0.0
        campaign.exit_reason = reason
        campaign.next_action = "WAIT"
        campaign.state = CampaignState.CLOSED
        campaign.reconciliation_state = "CLEAN"
        self.db.save_campaign(campaign)
        self.db.state_delete(f"entry_client_order_id:{symbol}")
        self._set_state(symbol, "FLAT")
        return {"state": "CLOSED", "symbol": symbol, "exit_price": position_price, "reason": reason}


    def _reconcile_pending(self):
        results = []
        for symbol, cid in list(self._pending_entries()):
            campaign = None
            try:
                row = self.db.conn.execute(
                    "SELECT campaign_id FROM campaign_orders WHERE client_order_id=? ORDER BY id DESC LIMIT 1",
                    (cid,),
                ).fetchone()
                if row:
                    campaign = self.engine.load_campaign(str(row["campaign_id"]))
                if campaign is None:
                    for item in self.db.open_campaigns():
                        if str(item["symbol"]).upper() != symbol:
                            continue
                        c = self.engine.load_campaign(item["campaign_id"])
                        if c and c.tags.get("pending_order_client_id") == cid:
                            campaign = c
                            break
                if campaign is None:
                    self._set_state(symbol, "RECONCILE_REQUIRED")
                    results.append({"symbol": symbol, "state": "RECONCILE_REQUIRED", "reason": "pending order has no campaign"})
                    continue

                order = self.client.get_order(symbol, orig_client_order_id=cid)
                status = str(order.get("status", "")).upper()
                executed = float(order.get("executedQty", order.get("cumQty", 0)) or 0)
                avg = float(order.get("avgPrice", 0) or 0)
                if status in {"NEW", "PENDING_NEW"} and executed <= 0:
                    results.append({"symbol": symbol, "state": "ENTRY_PENDING", "order_status": status})
                    continue
                if executed > 0 and avg <= 0:
                    rows = self.client.my_trades(symbol, order_id=order.get("orderId"), limit=1000)
                    qty_sum = sum(float(r.get("qty", 0) or 0) for r in rows)
                    quote_sum = sum(
                        float(r.get("qty", 0) or 0) * float(r.get("price", 0) or 0)
                        for r in rows
                    )
                    executed = qty_sum or executed
                    avg = quote_sum / executed if executed > 0 else 0

                if executed > 0:
                    # A conditional order may partially fill and remain open.
                    # Cancel the remainder before adopting the fill, otherwise
                    # the same signal could add quantity later outside the durable
                    # campaign reservation.
                    if status == "PARTIALLY_FILLED" and order.get("orderId") is not None:
                        try:
                            self.client.cancel_order(symbol, order_id=order.get("orderId"))
                        except Exception as exc:
                            self._set_state(symbol, "RECONCILE_REQUIRED")
                            raise RuntimeError(
                                f"{symbol}: partial conditional order cancellation ambiguous: {exc}"
                            ) from exc

                    position = self._position(symbol)
                    pos_qty = abs(self._signed_position_qty(position))
                    avg = float(position.get("entryPrice", avg) or avg)
                    if pos_qty <= 0:
                        self._set_state(symbol, "RECONCILE_REQUIRED")
                        raise RuntimeError("order shows execution but Futures position is flat")

                    purpose_row = self.db.conn.execute(
                        "SELECT purpose,signal_id FROM campaign_orders WHERE client_order_id=? ORDER BY id DESC LIMIT 1",
                        (cid,),
                    ).fetchone()
                    purpose = str(purpose_row["purpose"] if purpose_row else "ENTRY").upper()
                    signal_id = str(purpose_row["signal_id"] if purpose_row else campaign.current_signal_id)

                    self._set_pending_signal_state(
                        campaign, SignalState.FILLED, filled_quantity=executed
                    )
                    if purpose == "ADD_ON" or campaign.state == CampaignState.ADD_ON_PENDING:
                        expected_delta = float(executed)
                        old_qty = float(campaign.position_qty)
                        actual_delta = max(0.0, pos_qty - old_qty)
                        if actual_delta <= 0 or abs(actual_delta - expected_delta) > max(expected_delta * 0.01, 1e-12):
                            self._set_state(symbol, "RECONCILE_REQUIRED")
                            raise RuntimeError(
                                f"{symbol}: add-on fill/position mismatch: executed={expected_delta:.12g} "
                                f"old={old_qty:.12g} actual_delta={actual_delta:.12g}"
                            )
                        if campaign.state == CampaignState.ADD_ON_PENDING:
                            campaign.transition(CampaignState.POSITION_EXPANDING, reason="add-on conditional order filled")
                        old_qty = float(campaign.position_qty)
                        position_avg = float(position.get("entryPrice", 0) or 0)
                        if position_avg > 0:
                            campaign.average_entry_price = position_avg
                        else:
                            campaign.average_entry_price = (
                                (float(campaign.average_entry_price) * old_qty)
                                + (float(avg) * float(executed))
                            ) / max(old_qty + float(executed), 1e-12)
                        campaign.position_qty = pos_qty
                        campaign.additions += 1
                        campaign.tranche_index = min(4, campaign.tranche_index + 1)
                        campaign.open_risk_quote += float(campaign.pending_risk_quote or 0)
                        campaign.pending_risk_quote = 0.0
                        campaign.capital_reserved_quote = 0.0
                        # The add-on increased exposure: resize the single
                        # reduce-only protective order before exposing the
                        # campaign as fully open again.
                        protective_id = int(campaign.tags.get("protective_order_id", "0") or 0)
                        if protective_id > 0:
                            # Quantity changed even when the structural stop price
                            # stays unchanged; replace the stop new-first so the
                            # enlarged campaign is fully protected.
                            self._replace_protection(
                                campaign,
                                float(campaign.current_stop_price),
                                allow_same_price=True,
                            )
                        else:
                            self._protect(campaign)
                        trade = self.db.open_trade(symbol)
                        if trade is not None:
                            self.db.update_trade_position(
                                trade["id"],
                                campaign.position_qty,
                                campaign.average_entry_price,
                            )
                        if not self._liquidation_guard(campaign, position):
                            self._set_state(symbol, "RECONCILE_REQUIRED")
                            raise RuntimeError("liquidation price violates protective-stop safety buffer after add-on")
                        self.db.set_campaign_signal_state(signal_id, SignalState.FILLED.value)
                        self._set_state(symbol, "OPEN")
                        campaign.transition(CampaignState.TREND_ACTIVE, reason="add-on filled and fully reprotected")
                    else:
                        if abs(pos_qty - float(executed)) > max(float(executed) * 0.01, 1e-12):
                            self._set_state(symbol, "RECONCILE_REQUIRED")
                            raise RuntimeError(
                                f"{symbol}: initial fill/position mismatch: executed={float(executed):.12g} "
                                f"position={pos_qty:.12g}"
                            )
                        if campaign.state == CampaignState.ENTRY_PENDING:
                            campaign.transition(CampaignState.ENTRY_TRIGGERED, reason="entry conditional order filled")
                        stop = float(campaign.initial_stop_price)
                        campaign.position_qty = pos_qty
                        campaign.average_entry_price = avg
                        campaign.current_stop_price = stop
                        campaign.open_risk_quote = float(campaign.pending_risk_quote or 0)
                        campaign.pending_risk_quote = 0.0
                        campaign.capital_reserved_quote = 0.0
                        campaign.transition(CampaignState.OPEN_INITIAL, reason="initial entry filled")
                        self.db.set_campaign_signal_state(signal_id, SignalState.FILLED.value)
                        self._record_trade_if_missing(campaign, order, avg, pos_qty)
                        if not self._liquidation_guard(campaign, position):
                            self._set_state(symbol, "RECONCILE_REQUIRED")
                            raise RuntimeError("liquidation price violates protective-stop safety buffer")
                        self._protect(campaign)
                        self._set_state(symbol, "OPEN")

                    self.db.state_delete(f"entry_client_order_id:{symbol}")
                    campaign.tags.pop("pending_order_id", None)
                    self.db.save_campaign(campaign)
                    results.append({"symbol": symbol, "campaign_id": campaign.campaign_id, "state": "OPEN", "filled_quantity": executed})
                    continue

                if status in {"CANCELED", "EXPIRED", "REJECTED"}:
                    self._set_pending_signal_state(campaign, SignalState.CANCELLED)
                    self.db.set_campaign_signal_state(campaign.current_signal_id, SignalState.CANCELLED.value)
                    campaign.pending_risk_quote = 0.0
                    campaign.capital_reserved_quote = 0.0
                    campaign.state = (
                        CampaignState.TREND_ACTIVE
                        if campaign.position_qty > 0
                        else CampaignState.CLOSED
                    )
                    self.db.state_delete(f"entry_client_order_id:{symbol}")
                    self._set_state(symbol, "OPEN" if campaign.position_qty > 0 else "FLAT")
                    self.db.save_campaign(campaign)
                    results.append({"symbol": symbol, "campaign_id": campaign.campaign_id, "state": campaign.state.value, "order_status": status})
                    continue

                self._set_pending_signal_state(campaign, SignalState.RECONCILE_REQUIRED)
                self._set_state(symbol, "RECONCILE_REQUIRED")
                results.append({"symbol": symbol, "state": "RECONCILE_REQUIRED", "order_status": status})
            except Exception as exc:
                self._set_state(symbol, "RECONCILE_REQUIRED")
                results.append({"symbol": symbol, "state": "RECONCILE_REQUIRED", "error": str(exc)})
        return results

    def _finalize_confirmed_exchange_exit(self, campaign, orders):
        """Finalize a flat campaign only when a bot-owned exit fill is proven."""
        filled = []
        for o in (orders or []):
            if str(o.get("status", "")).upper() != "FILLED":
                continue
            cid = str(o.get("clientOrderId", ""))
            if not cid.startswith(("WILLF_STOP_", "WILLF_EXIT_")):
                continue
            # Prefix alone is not sufficient proof; tie the exchange order
            # to this exact campaign using the durable campaign_orders ledger.
            row = self.db.conn.execute(
                "SELECT campaign_id,purpose,order_id FROM campaign_orders "
                "WHERE client_order_id=? ORDER BY id DESC LIMIT 1",
                (cid,),
            ).fetchone()
            if not row or str(row["campaign_id"]) != str(campaign.campaign_id):
                continue
            if str(row["purpose"]).upper() not in {"PROTECTION", "CAMPAIGN_TRAIL", "CAMPAIGN_EXIT"}:
                continue
            if row["order_id"] is not None and o.get("orderId") is not None:
                if str(row["order_id"]) != str(o.get("orderId")):
                    continue
            filled.append(o)
        if not filled:
            return False
        order = max(
            filled,
            key=lambda o: int(o.get("updateTime", o.get("time", o.get("transactTime", 0))) or 0),
        )
        exit_price = float(order.get("avgPrice", order.get("price", 0)) or 0)
        if order.get("orderId") is not None:
            try:
                trades = self.client.my_trades(
                    campaign.symbol,
                    order_id=order.get("orderId"),
                    limit=1000,
                ) or []
            except Exception:
                trades = []
            qty = sum(float(t.get("qty", 0) or 0) for t in trades)
            quote = sum(
                float(t.get("qty", 0) or 0) * float(t.get("price", 0) or 0)
                for t in trades
            )
            if qty > 0 and quote > 0:
                exit_price = quote / qty
        if exit_price <= 0:
            return False

        trade = self.db.open_trade(campaign.symbol)
        if trade is not None:
            entry = float(campaign.average_entry_price or trade.get("entry_price") or 0)
            managed_qty = float(campaign.position_qty or trade.get("quantity") or 0)
            pnl = (
                (exit_price - entry) * managed_qty
                if campaign.side == "BUY"
                else (entry - exit_price) * managed_qty
            )
            pnl_pct = (
                (exit_price / entry - 1.0)
                if campaign.side == "BUY" and entry > 0
                else (entry / exit_price - 1.0)
                if campaign.side == "SELL" and entry > 0 and exit_price > 0
                else 0.0
            )
            reason = (
                "STOP_FILLED"
                if str(order.get("clientOrderId", "")).startswith("WILLF_STOP_")
                else "CAMPAIGN_EXIT_FILLED"
            )
            self.db.close_trade(
                trade["id"],
                datetime.now(timezone.utc).isoformat(),
                exit_price,
                pnl,
                pnl_pct,
                reason,
                fees=float(trade.get("fees") or 0),
            )
        campaign.position_qty = 0.0
        campaign.open_risk_quote = 0.0
        campaign.pending_risk_quote = 0.0
        campaign.capital_reserved_quote = 0.0
        campaign.exit_reason = (
            "STOP_FILLED"
            if str(order.get("clientOrderId", "")).startswith("WILLF_STOP_")
            else "CAMPAIGN_EXIT_FILLED"
        )
        campaign.next_action = "WAIT"
        campaign.reconciliation_state = "CLEAN"
        campaign.state = CampaignState.CLOSED
        self.db.save_campaign(campaign)
        self.db.state_delete(f"entry_client_order_id:{campaign.symbol}")
        self._set_state(campaign.symbol, "FLAT")
        return True

    def recover(self):
        results = self._reconcile_pending()
        unresolved = self.unresolved_symbols()

        for row in self.db.open_campaigns():
            campaign = self.engine.load_campaign(row["campaign_id"])
            if campaign is None or campaign.position_qty <= 0:
                continue
            symbol = campaign.symbol
            position = self._position(symbol)
            qty = abs(self._signed_position_qty(position))
            if qty <= 0:
                orders = self.client.all_orders(symbol, limit=1000)
                if self._finalize_confirmed_exchange_exit(campaign, orders):
                    continue
                campaign.state = CampaignState.RECONCILE_REQUIRED
                self.db.save_campaign(campaign)
                self._set_state(symbol, "RECONCILE_REQUIRED")
                continue
            if not self._position_matches(campaign, position):
                campaign.state = CampaignState.RECONCILE_REQUIRED
                self.db.save_campaign(campaign)
                self._set_state(symbol, "RECONCILE_REQUIRED")
                continue
            try:
                if not self._liquidation_guard(campaign, position):
                    raise RuntimeError("liquidation price violates protective-stop safety buffer")
                orders = self.client.open_orders(symbol)
                managed = [
                    o for o in orders
                    if str(o.get("clientOrderId", "")).startswith("WILLF_STOP_")
                    and str(o.get("type", "")).upper() in {"STOP_MARKET", "STOP_LOSS"}
                ]
                if len(managed) == 0:
                    self._protect(campaign)
                elif len(managed) > 1:
                    self._set_state(symbol, "RECONCILE_REQUIRED")
                    continue
                else:
                    campaign.tags["protective_order_id"] = str(managed[0].get("orderId", ""))
                    campaign.current_stop_price = float(managed[0].get("stopPrice", campaign.current_stop_price) or campaign.current_stop_price)
                campaign.reconciliation_state = "CLEAN"
                campaign.health = "GREEN"
                self.db.save_campaign(campaign)
                self._set_state(symbol, "OPEN")
            except Exception as exc:
                self._set_state(symbol, "RECONCILE_REQUIRED")
                self.db.log_event("ERROR", "futures_recovery_error", str(exc), {"symbol": symbol})
        return {
            "ok": not self.unresolved_symbols() and not self._pending_entries(),
            "results": results,
            "open_positions": len(self.open_positions()),
            "unresolved_symbols": self.unresolved_symbols(),
            "pending_entries": self._pending_entries(),
        }

    def _trail(self, campaign):
        symbol = campaign.symbol
        frame = fetch_klines(self.client, symbol, campaign.execution_timeframe, limit=80)
        if len(frame) < 20:
            return {"state": campaign.state.value, "action": "WAIT_HISTORY"}
        closed = frame.iloc[:-1].copy() if len(frame) > 1 else frame
        window = max(3, min(5, int(os.getenv("CAMPAIGN_TRAIL_BARS", "5"))))
        tick = self._tick(symbol)
        try:
            current_price = float(self.client.ticker_price(symbol)["price"])
            if campaign.side == "BUY":
                recent = [float(x) for x in closed["low"].tail(window) if float(x) > 0]
                proposed = min(recent) - tick if recent else 0.0
                if proposed > campaign.current_stop_price and proposed < current_price:
                    self._replace_protection(campaign, proposed)
            else:
                recent = [float(x) for x in closed["high"].tail(window) if float(x) > 0]
                proposed = max(recent) + tick if recent else 0.0
                if proposed < campaign.current_stop_price and proposed > current_price:
                    self._replace_protection(campaign, proposed)
        except Exception as exc:
            self._set_state(symbol, "RECONCILE_REQUIRED")
            self.db.log_event("ERROR", "futures_trailing_error", str(exc), {"symbol": symbol})
            raise
        return {"state": campaign.state.value, "current_stop": campaign.current_stop_price}

    def _opposite_triggered(self, campaign, candidates):
        opposite = "SELL" if campaign.side == "BUY" else "BUY"
        current = float(self.client.ticker_price(campaign.symbol).get("price", 0) or 0)
        for candidate in candidates:
            signals = list(getattr(candidate, "all_signals", ()) or ())
            if not signals:
                signals = [candidate.signal]
            for signal in signals:
                if signal.symbol != campaign.symbol or signal.side != opposite:
                    continue
                if signal.signal_bar_time_ms <= int(campaign.tags.get("last_signal_time_ms", 0) or 0):
                    continue
                if opposite == "BUY" and current >= signal.trigger_price:
                    return True
                if opposite == "SELL" and current <= signal.trigger_price:
                    return True
        return False

    def process(self):
        if not self.recovered:
            self.recover()
            self.recovered = True

        self._reconcile_pending()
        candidates = self.scanner.scan()
        by_symbol = {}
        for c in candidates:
            by_symbol.setdefault(c.symbol, []).append(c)

        pending_symbols = {s for s, _ in self._pending_entries()}

        for row in list(self.db.open_campaigns()):
            campaign = self.engine.load_campaign(row["campaign_id"])
            if campaign is None or campaign.position_qty <= 0:
                continue
            symbol = campaign.symbol
            symbol_candidates = by_symbol.get(symbol, [])
            if self._opposite_triggered(campaign, symbol_candidates):
                try:
                    self._exit_market(campaign, "OPPOSITE_WISE_MAN")
                except Exception:
                    self._set_state(symbol, "RECONCILE_REQUIRED")
                continue

            latest = int(campaign.tags.get("last_signal_time_ms", 0) or 0)
            eligible = []
            allow_reversal = os.getenv("CAMPAIGN_ALLOW_REVERSAL_ADD", "false").lower() == "true"
            for candidate in symbol_candidates:
                signals = list(getattr(candidate, "all_signals", ()) or ())
                if not signals:
                    signals = [candidate.signal]
                for signal in signals:
                    if signal.side != campaign.side:
                        continue
                    if signal.signal_bar_time_ms <= latest:
                        continue
                    if signal.signal_type == SignalType.REVERSAL and not allow_reversal:
                        continue
                    eligible.append(signal)

            if eligible and symbol not in pending_symbols:
                signal = min(eligible, key=lambda s: (s.signal_bar_time_ms, s.created_at_ms))
                try:
                    result = self._arm_add_on(campaign, signal)
                    campaign.tags["last_signal_time_ms"] = signal.signal_bar_time_ms
                    self.db.save_campaign(campaign)
                except Exception as exc:
                    self.db.log_event("WARNING", "futures_add_on_blocked", str(exc), {"symbol": symbol})
            else:
                self._trail(campaign)

        active_symbols = {str(r["symbol"]).upper() for r in self.db.open_trades()}
        for candidate in candidates:
            if candidate.symbol in active_symbols:
                continue
            if len(self.open_positions()) >= int(os.getenv("MAX_OPEN_POSITIONS", "5")):
                break
            if candidate.symbol in pending_symbols or candidate.symbol in self._locks:
                continue
            if self.unresolved_symbols():
                break
            self._locks.add(candidate.symbol)
            try:
                self._arm_entry(candidate)
            except Exception as exc:
                self.db.log_event("WARNING", "futures_entry_blocked", str(exc), {"symbol": candidate.symbol})
            finally:
                self._locks.discard(candidate.symbol)
                # Do not mark a symbol active merely because an arm attempt
                # failed; recovery/pending state is the authoritative source.
                if self.db.open_trade(candidate.symbol) is not None:
                    active_symbols.add(candidate.symbol)
                if any(s == candidate.symbol for s, _ in self._pending_entries()):
                    pending_symbols.add(candidate.symbol)

        self.recover()

    def manual_sell(self, symbol):
        symbol = str(symbol).upper()
        campaign = self._active_campaign(symbol)
        if campaign is None:
            self.recover()
            return {"sold": False, "symbol": symbol, "reason": "managed Futures campaign not found"}
        try:
            return {"sold": True, **self._exit_market(campaign, "MANUAL_EXIT")}
        except Exception as exc:
            self._set_state(symbol, "RECONCILE_REQUIRED")
            return {"sold": False, "symbol": symbol, "state": "RECONCILE_REQUIRED", "error": str(exc)}

    def portfolio_equity(self):
        return self._equity()
