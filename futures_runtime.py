"""Testnet-first USDⓈ-M Futures runtime for the Williams campaign engine.

This runtime is intentionally separate from the existing Spot runtime. It never
routes a SHORT signal through Spot SELL semantics. It requires a dedicated
Futures API key, durable SQLite intents, current directional market contexts,
and authoritative Futures reconciliation before admitting new exposure.
"""
from __future__ import annotations

import json
import logging
import math
import os
import threading
import time
from dataclasses import replace
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

import pandas as pd

from binance_usdm_futures_client import BinanceUsdmFuturesClient
from campaign_engine import CampaignEngine
from campaign_model import SignalRole, SignalSpec, SignalType, SignalState
from data import fetch_klines
from db import Database
from execution_barrier import ExecutionBarrier
from market_context import ContextCache, TFMarketContext
from market_scanner import Candidate
from portfolio_controller import PortfolioController
from strategy import calculate_indicators, config_from_env
from trading_config import TradingConfig
from futures_campaign_execution import FuturesCampaignExecutionService, FuturesCampaignExecutionError

log = logging.getLogger("williams-futures-runtime")


def _env_bool(key: str, default: bool) -> bool:
    return str(os.getenv(key, str(default))).strip().lower() in {"1", "true", "yes", "on"}


def _interval_seconds(interval: str) -> int:
    value = str(interval).strip()
    if value == "1M":
        return 30 * 24 * 60 * 60
    if not value:
        raise ValueError("empty timeframe")
    unit = value[-1].lower()
    number = int(value[:-1])
    factors = {"m": 60, "h": 3600, "d": 86400, "w": 604800}
    if unit not in factors or number <= 0:
        raise ValueError(f"unsupported Binance timeframe: {value}")
    return number * factors[unit]


def signal_spec_from_dict(raw: dict[str, Any]) -> SignalSpec:
    """Rebuild the canonical domain signal without losing direction metadata."""
    side = str(raw.get("side", "")).upper()
    direction = str(raw.get("direction", "") or "").upper()
    if not direction:
        direction = {
            "BUY": "LONG", "LONG": "LONG",
            "SELL": "SHORT", "SHORT": "SHORT",
        }.get(side, "")
    return SignalSpec(
        signal_id=str(raw["signal_id"]),
        symbol=str(raw["symbol"]).upper(),
        side=side,
        direction=direction,
        signal_type=SignalType(str(raw["signal_type"])),
        role=SignalRole(str(raw["role"])),
        timeframe=str(raw["timeframe"]).lower(),
        signal_bar_time_ms=int(raw["signal_bar_time_ms"]),
        trigger_price=float(raw["trigger_price"]),
        protective_reference=float(raw["protective_reference"]),
        trigger_buffer_ticks=int(raw.get("trigger_buffer_ticks", 1) or 1),
        invalidation_price=float(raw.get("invalidation_price", 0.0) or 0.0),
        teeth_at_detection=float(raw.get("teeth_at_detection", 0.0) or 0.0),
        alligator_bullish=bool(raw.get("alligator_bullish", False)),
        alligator_bearish=bool(raw.get("alligator_bearish", False)),
        alligator_awake=bool(raw.get("alligator_awake", False)),
        angulation_score=float(raw.get("angulation_score", 0.0) or 0.0),
        wave_confidence=float(raw.get("wave_confidence", 0.0) or 0.0),
        wave_exhaustion_risk=float(raw.get("wave_exhaustion_risk", 0.0) or 0.0),
        htf_confirmed=bool(raw.get("htf_confirmed", False)),
        context_versions=dict(raw.get("context_versions", {}) or {}),
        reason=str(raw.get("reason", "")),
        created_at_ms=int(raw.get("created_at_ms", 0) or 0),
        expires_at_ms=int(raw.get("expires_at_ms", 0) or 0),
        source_candle_index=int(raw.get("source_candle_index", -1) or -1),
    )


class FuturesRuntime:
    """Background campaign runtime with LONG and SHORT as first-class directions.

    Account mode and margin/leverage must be preconfigured to one-way, isolated,
    1x. This runtime validates those settings and refuses to trade if they differ;
    it does not silently alter live account configuration.
    """

    def __init__(
        self,
        api_key: str,
        api_secret: str,
        *,
        testnet: bool = True,
        allow_live: bool = False,
        symbols: Iterable[str] | None = None,
        interval: str | None = None,
        db_path: str | None = None,
        max_open_positions: int | None = None,
    ) -> None:
        self.testnet = bool(testnet)
        self.allow_live = bool(allow_live)
        if not self.testnet and not (
            self.allow_live and _env_bool("ALLOW_LIVE", False)
        ):
            raise ValueError("Futures mainnet requires allow_live=True and ALLOW_LIVE=true")

        self.config = TradingConfig.from_env()
        self.interval = str(interval or os.getenv("EXECUTION_TIMEFRAME", "5m")).lower()
        raw_symbols = list(symbols or [
            x.strip().upper()
            for x in os.getenv(
                "FUTURES_SYMBOLS",
                "BTCUSDT,ETHUSDT,BNBUSDT,SOLUSDT,XRPUSDT",
            ).split(",")
            if x.strip()
        ])
        self.symbols = tuple(dict.fromkeys(str(x).strip().upper() for x in raw_symbols if str(x).strip()))
        if not self.symbols:
            raise ValueError("FuturesRuntime needs at least one symbol")
        self.allow_long = _env_bool("ALLOW_LONG", True)
        # Futures long/short is the product contract. Shorting is disabled only
        # by an explicit ALLOW_SHORT=false setting for this Futures runtime.
        self.allow_short = _env_bool("ALLOW_SHORT", True)
        self.require_htf_confirmation = _env_bool("REQUIRE_HTF_CONFIRMATION", True)
        self.max_open_positions = max(1, int(max_open_positions or self.config.max_open_positions or 5))
        self.max_daily_loss_pct = min(
            0.25, max(0.0, float(os.getenv("MAX_DAILY_LOSS_PCT", "0.03")))
        )
        self.poll_seconds = max(10, int(os.getenv("FUTURES_SCAN_SECONDS", "30")))
        self.context_intervals = tuple(dict.fromkeys(
            [str(x).lower() for x in self.config.structural_timeframes]
            + [self.interval]
        ))
        self.db_path = db_path or os.getenv("FUTURES_DB_PATH", "data/williams_futures.sqlite3")
        self.db = Database(self.db_path)
        self.client = BinanceUsdmFuturesClient(
            api_key,
            api_secret,
            testnet=self.testnet,
            allow_live=self.allow_live,
            max_leverage=1,
        )
        self.context_cache = ContextCache()
        self.execution_barrier = ExecutionBarrier(self.context_cache, self.db)
        self.controller = PortfolioController(
            self.client,
            balance_quote=1.0,
            symbols=list(self.symbols),
            interval=self.interval,
        )
        self.scanner = self.controller.scanner
        self.execution = FuturesCampaignExecutionService(
            self.client,
            self.db,
            execution_barrier=self.execution_barrier,
            max_open_positions=self.max_open_positions,
            portfolio_risk_limit_pct=self.config.max_total_risk_pct,
            campaign_risk_limit_pct=min(0.005, self.config.risk_per_trade_pct),
        )
        self._lock = threading.RLock()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._running = False
        self._paused = True
        self._kill_latched = False
        self._last_scan_at_ms = 0
        self._last_scan_summary: dict[str, Any] = {}
        self._last_error = ""
        self._last_account: dict[str, float] = {}
        self._scan_count = 0

    @property
    def running(self) -> bool:
        with self._lock:
            return self._running

    def _account(self) -> dict[str, Any]:
        result = self.client.account()
        if not isinstance(result, dict):
            raise RuntimeError("Futures account endpoint returned an invalid payload")
        available = float(result.get("availableBalance", 0) or 0)
        equity = float(
            result.get("totalMarginBalance")
            or result.get("totalWalletBalance")
            or available
        )
        if not math.isfinite(available) or not math.isfinite(equity) or available < 0 or equity <= 0:
            raise RuntimeError("Futures account has invalid equity/availableBalance")
        self._last_account = {"equity_quote": equity, "available_quote": available}
        self.controller.risk_engine.balance = equity
        return result

    def _daily_loss_allows_entry(self, equity: float) -> tuple[bool, str]:
        day = datetime.now(timezone.utc).date().isoformat()
        day_key = "futures_day_start_date"
        equity_key = "futures_day_start_equity_quote"
        old_day = str(self.db.state_get(day_key, "") or "")
        if old_day != day:
            self.db.state_set(day_key, day)
            self.db.state_set(equity_key, repr(float(equity)))
            return True, "new UTC trading day baseline"
        try:
            baseline = float(self.db.state_get(equity_key, "0") or 0)
        except (TypeError, ValueError):
            baseline = 0.0
        if baseline <= 0:
            self.db.state_set(equity_key, repr(float(equity)))
            return True, "daily baseline initialized"
        loss_fraction = max(0.0, (baseline - float(equity)) / baseline)
        if loss_fraction >= self.max_daily_loss_pct:
            return False, (
                f"daily loss limit reached: {loss_fraction:.2%} >= "
                f"{self.max_daily_loss_pct:.2%}; only protection/recovery/exits remain enabled"
            )
        return True, "within daily loss limit"

    @staticmethod
    def _atr(df: pd.DataFrame, period: int = 14) -> float:
        if df is None or len(df) < period + 1:
            return 0.0
        previous = df["close"].shift(1)
        ranges = pd.concat(
            [
                df["high"] - df["low"],
                (df["high"] - previous).abs(),
                (df["low"] - previous).abs(),
            ],
            axis=1,
        ).max(axis=1)
        value = ranges.rolling(period).mean().iloc[-1]
        return float(value) if pd.notna(value) else 0.0

    def _refresh_symbol_context(self, symbol: str) -> dict[str, pd.DataFrame]:
        """Publish every structural TF from freshly closed Futures candles."""
        frames: dict[str, pd.DataFrame] = {}
        for interval in self.context_intervals:
            frame = fetch_klines(
                self.client,
                symbol,
                interval,
                limit=max(220, self.config.wave_lookback),
            )
            if frame is None or frame.empty:
                raise RuntimeError(f"{symbol}/{interval}: Futures candles unavailable")
            # Never make a decision from the currently forming candle.
            closed = frame.iloc[:-1].copy() if len(frame) > 1 else frame.iloc[0:0].copy()
            if len(closed) < 100:
                raise RuntimeError(f"{symbol}/{interval}: insufficient closed candles ({len(closed)})")
            indicators = calculate_indicators(closed, config_from_env())
            last = indicators.iloc[-1]
            previous = indicators.iloc[-2] if len(indicators) > 1 else last
            price = float(last.get("close", 0) or 0)
            ao = float(last.get("ao", 0) or 0)
            bullish = bool(last.get("bullish_alligator", False))
            bearish = bool(last.get("bearish_alligator", False))
            awake = bool(last.get("alligator_awake", False))
            allow_long = self.allow_long and bullish and awake and ao > 0
            allow_short = self.allow_short and bearish and awake and ao < 0
            atr = self._atr(closed, self.config.atr_period)
            jaw = float(last.get("jaw_shifted", last.get("jaw", 0.0)) or 0.0)
            teeth = float(last.get("teeth_shifted", last.get("teeth", 0.0)) or 0.0)
            lips = float(last.get("lips_shifted", last.get("lips", 0.0)) or 0.0)
            previous_jaw = float(previous.get("jaw_shifted", previous.get("jaw", jaw)) or jaw)
            previous_close = float(previous.get("close", price) or price)
            price_slope = (price - previous_close) / atr if atr > 0 else 0.0
            jaw_slope = (jaw - previous_jaw) / atr if atr > 0 else 0.0
            sign = 1.0 if price >= jaw else -1.0
            angulation = sign * (price_slope - jaw_slope)
            candle_open_ms = int(pd.Timestamp(closed.index[-1]).timestamp() * 1000)
            close_time = (
                pd.to_datetime(closed["close_time"], utc=True).iloc[-1]
                if "close_time" in closed.columns
                else pd.Timestamp(candle_open_ms + _interval_seconds(interval) * 1000, unit="ms", tz="UTC")
            )
            close_ms = int(pd.Timestamp(close_time).timestamp() * 1000)
            state = "BULLISH" if bullish else "BEARISH" if bearish else "SLEEP"
            decision = "LONG" if allow_long else "SHORT" if allow_short else "NO_TRADE"
            context = TFMarketContext(
                symbol=symbol,
                interval=interval,
                version=0,
                candle_open_time_ms=candle_open_ms,
                candle_close_time_ms=close_ms,
                price=price,
                atr=atr,
                jaw=jaw,
                teeth=teeth,
                lips=lips,
                jaw_slope_atr=jaw_slope,
                price_slope_atr=price_slope,
                angulation=angulation,
                jaw_distance_atr=abs(price - jaw) / atr if atr > 0 else 0.0,
                alligator_state=state,
                allow_long=allow_long,
                allow_short=allow_short,
                decision=decision,
                long_probability=1.0 if allow_long else 0.0,
                short_probability=1.0 if allow_short else 0.0,
                no_trade_probability=0.0 if (allow_long or allow_short) else 1.0,
                calibration_status="RULE_BASED_UNCALIBRATED",
                data_bars=len(closed),
            )
            snapshot = self.context_cache.publish(context)
            published = snapshot.context(symbol, interval)
            if published is not None:
                self.db.save_market_context(published)
            frames[interval] = closed
        return frames

    def _directional_signal(self, candidate: Candidate, direction: str, frames: dict[str, pd.DataFrame]) -> SignalSpec:
        raw_specs = list(candidate.campaign_signal_specs or [])
        parsed = []
        for raw in raw_specs:
            try:
                spec = signal_spec_from_dict(raw)
            except Exception as exc:
                log.warning("%s: invalid SignalSpec discarded: %s", candidate.symbol, exc)
                continue
            if spec.role == SignalRole.ENTRY and spec.direction == direction:
                parsed.append(spec)
        signal = CampaignEngine.choose_initial_signal(parsed)
        if signal is None:
            raise FuturesCampaignExecutionError(
                f"{candidate.symbol}: no valid {direction} campaign signal"
            )

        snapshot = self.context_cache.snapshot()
        operative = snapshot.context(signal.symbol, signal.timeframe)
        if operative is None:
            raise FuturesCampaignExecutionError(
                f"{signal.symbol}/{signal.timeframe}: context missing after refresh"
            )
        operative_ok = operative.allow_long if direction == "LONG" else operative.allow_short
        if not operative_ok:
            raise FuturesCampaignExecutionError(
                f"{signal.symbol}/{signal.timeframe}: operative Williams context disallows {direction}"
            )

        seconds = {iv: _interval_seconds(iv) for iv in self.context_intervals}
        parent_intervals = sorted(
            [iv for iv in self.context_intervals if seconds[iv] > _interval_seconds(signal.timeframe)],
            key=lambda iv: seconds[iv],
        )
        parent_ok = not self.require_htf_confirmation
        if self.require_htf_confirmation:
            if not parent_intervals:
                raise FuturesCampaignExecutionError(
                    f"{signal.symbol}: no higher timeframe available for confirmation"
                )
            # The immediate structural parent is mandatory. Larger contexts
            # may be neutral but must not actively contradict the setup.
            immediate_parent = parent_intervals[0]
            parent = snapshot.context(signal.symbol, immediate_parent)
            if parent is None:
                raise FuturesCampaignExecutionError(
                    f"{signal.symbol}/{immediate_parent}: higher-timeframe context missing"
                )
            parent_ok = parent.allow_long if direction == "LONG" else parent.allow_short
            if not parent_ok:
                raise FuturesCampaignExecutionError(
                    f"{signal.symbol}/{immediate_parent}: higher timeframe does not confirm {direction}"
                )
            for interval in parent_intervals[1:]:
                higher = snapshot.context(signal.symbol, interval)
                if higher is None:
                    raise FuturesCampaignExecutionError(
                        f"{signal.symbol}/{interval}: structural context missing"
                    )
                opposite = higher.allow_short if direction == "LONG" else higher.allow_long
                if opposite:
                    raise FuturesCampaignExecutionError(
                        f"{signal.symbol}/{interval}: higher timeframe contradicts {direction}"
                    )

        versions = snapshot.versions(signal.symbol, list(self.context_intervals))
        return replace(
            signal,
            htf_confirmed=bool(parent_ok),
            context_versions=versions,
        )

    def _recover(self) -> list[dict[str, Any]]:
        """Reconcile every Futures campaign before looking for fresh exposure."""
        summaries = []
        for row in self.execution._active_rows():
            if self.execution._row_tags(row).get("execution_mode") != "FUTURES":
                continue
            symbol = str(row.get("symbol", "")).upper()
            try:
                summaries.append(self.execution.reconcile_symbol(symbol))
            except Exception as exc:
                self.db.state_set(
                    f"campaign_state:{row.get('campaign_id')}",
                    "RECONCILE_REQUIRED",
                )
                summaries.append({
                    "symbol": symbol,
                    "state": "RECONCILE_REQUIRED",
                    "reason": f"{type(exc).__name__}: {exc}",
                })
        return summaries

    def scan_once(self) -> dict[str, Any]:
        """One complete account -> reconciliation -> context -> risk -> execution cycle."""
        if self._kill_latched:
            return {"state": "KILL_SWITCH_LATCHED", "new_entries": 0}
        if self._paused:
            return {"state": "PAUSED", "new_entries": 0}
        self.client.sync_time()
        account = self._account()
        equity = self._last_account["equity_quote"]
        daily_ok, daily_reason = self._daily_loss_allows_entry(equity)
        reconciliations = self._recover()
        blocked_reconciliation = any(
            str(item.get("state", "")).upper() == "RECONCILE_REQUIRED"
            for item in reconciliations
        )
        if blocked_reconciliation:
            self._last_scan_summary = {
                "state": "RECONCILE_REQUIRED",
                "reason": "one or more Futures campaigns need reconciliation",
                "reconciliation": reconciliations,
                "new_entries": 0,
            }
            return self._last_scan_summary

        if not daily_ok:
            self._last_scan_summary = {
                "state": "DAILY_RISK_LOCKOUT",
                "reason": daily_reason,
                "reconciliation": reconciliations,
                "new_entries": 0,
            }
            return self._last_scan_summary

        active_count = len([
            row for row in self.execution._active_rows()
            if self.execution._row_tags(row).get("execution_mode") == "FUTURES"
        ])
        if active_count >= self.max_open_positions:
            return {
                "state": "POSITION_CAPACITY",
                "active_campaigns": active_count,
                "new_entries": 0,
                "reconciliation": reconciliations,
            }

        selections = self.controller.select_portfolio(
            open_risk_quote=self.execution.engine.portfolio_reserved_risk_quote(),
            open_positions=active_count,
        )
        results = []
        for selection in selections:
            if self._stop.is_set() or self._kill_latched or self._paused:
                break
            candidate = selection.candidate
            direction = str(selection.risk.side or getattr(candidate, "direction", "") or "").upper()
            if direction not in {"LONG", "SHORT"}:
                continue
            if direction == "LONG" and not self.allow_long:
                continue
            if direction == "SHORT" and not self.allow_short:
                continue
            symbol = candidate.symbol.upper()
            if self.execution._find_active_campaign(symbol) is not None:
                continue
            try:
                frames = self._refresh_symbol_context(symbol)
                signal = self._directional_signal(candidate, direction, frames)
                atr = self._atr(frames.get(self.interval), self.config.atr_period)
                if atr <= 0:
                    raise FuturesCampaignExecutionError(
                        f"{symbol}: ATR unavailable for risk sizing"
                    )
                entry = self.execution.arm_initial_entry(
                    signal,
                    equity_quote=equity,
                    atr=atr,
                    candidate_risk_fraction=float(selection.risk.risk_pct) / 100.0,
                )
                results.append(entry)
                active_count += 1
                if active_count >= self.max_open_positions:
                    break
            except Exception as exc:
                log.warning("Futures campaign not armed for %s/%s: %s", symbol, direction, exc)
                results.append({
                    "symbol": symbol,
                    "direction": direction,
                    "action": "WAIT",
                    "reason": f"{type(exc).__name__}: {exc}",
                })

        self._scan_count += 1
        self._last_scan_at_ms = int(time.time() * 1000)
        self._last_scan_summary = {
            "state": "RUNNING",
            "cycle": self._scan_count,
            "reconciliation": reconciliations,
            "candidates_considered": len(selections),
            "actions": results,
            "active_campaigns": active_count,
            "new_entries": sum(1 for r in results if r.get("action") == "ENTRY_ARMED"),
        }
        return self._last_scan_summary

    def _loop(self) -> None:
        while not self._stop.is_set():
            try:
                self.scan_once()
                with self._lock:
                    self._last_error = ""
            except Exception as exc:
                with self._lock:
                    self._last_error = f"{type(exc).__name__}: {exc}"
                log.exception("Futures runtime cycle failed")
            self._stop.wait(self.poll_seconds)

        with self._lock:
            self._running = False

    def start(self) -> dict[str, Any]:
        with self._lock:
            if self._kill_latched:
                raise RuntimeError("Futures kill switch is latched; explicit recovery/reset is required")
            if self._running:
                self._paused = False
                return self.status()
            self.client.sync_time()
            self.client.ensure_one_way_mode()
            account = self._account()
            self._daily_loss_allows_entry(self._last_account["equity_quote"])
            reconciliation = self._recover()
            if any(str(x.get("state", "")).upper() == "RECONCILE_REQUIRED" for x in reconciliation):
                raise RuntimeError("Futures start blocked: reconciliation required")
            self._paused = False
            self._stop.clear()
            self._running = True
            self._thread = threading.Thread(
                target=self._loop,
                name="williams-usdm-futures-runtime",
                daemon=True,
            )
            self._thread.start()
            self.db.log_event(
                "INFO",
                "futures_runtime_started",
                "Williams USDⓈ-M Futures runtime started",
                {
                    "testnet": self.testnet,
                    "symbols": list(self.symbols),
                    "interval": self.interval,
                    "allow_long": self.allow_long,
                    "allow_short": self.allow_short,
                    "max_leverage": 1,
                    "equity_quote": self._last_account["equity_quote"],
                },
            )
            return self.status()

    def pause(self) -> dict[str, Any]:
        with self._lock:
            self._paused = True
            state = "PAUSED"
            self.db.log_event(
                "WARNING",
                "futures_runtime_paused",
                "New Futures entries paused; open protective orders remain active",
                {},
            )
        return {**self.status(), "state": state}

    def stop(self) -> dict[str, Any]:
        self.pause()
        self._stop.set()
        thread = self._thread
        if thread is not None and thread is not threading.current_thread():
            thread.join(timeout=3.0)
        with self._lock:
            self._running = False
        return {**self.status(), "state": "STOPPED"}

    def kill(self) -> dict[str, Any]:
        """Latch the kill switch, stop new entries and attempt reduce-only exits."""
        with self._lock:
            self._kill_latched = True
            self._paused = True
            self._stop.set()
        results = []
        for row in self.execution._active_rows():
            if self.execution._row_tags(row).get("execution_mode") != "FUTURES":
                continue
            symbol = str(row.get("symbol", "")).upper()
            campaign_id = str(row.get("campaign_id", ""))
            try:
                campaign = self.execution.engine.load_campaign(campaign_id)
                if campaign is None:
                    raise RuntimeError("campaign row disappeared")
                amount = self.execution._position_amount(symbol)
                if abs(amount) <= 1e-12:
                    results.append({"symbol": symbol, "state": "NO_POSITION", "action": "RECONCILE"})
                    continue
                results.append(self.execution.exit_position(campaign, reason="KILL_SWITCH"))
            except Exception as exc:
                results.append({
                    "symbol": symbol,
                    "state": "RECONCILE_REQUIRED",
                    "error": f"{type(exc).__name__}: {exc}",
                })
        with self._lock:
            self._running = False
        self.db.log_event(
            "ERROR",
            "futures_runtime_kill_switch",
            "Kill switch latched; reduce-only exits attempted for managed Futures positions",
            {"results": results},
        )
        return {**self.status(), "state": "KILL_SWITCH_LATCHED", "exit_results": results}

    def recover_and_reset_kill(self) -> dict[str, Any]:
        """Only clear the kill latch after successful exchange reconciliation."""
        self.client.sync_time()
        self.client.ensure_one_way_mode()
        summaries = self._recover()
        unresolved = [
            item for item in summaries
            if str(item.get("state", "")).upper() == "RECONCILE_REQUIRED"
        ]
        active_positions = [
            row for row in self._rows(self.client.position_risk())
            if abs(float(row.get("positionAmt", 0) or 0)) > 1e-12
        ]
        if unresolved:
            raise RuntimeError("Kill reset denied: one or more campaigns need reconciliation")
        if active_positions:
            raise RuntimeError("Kill reset denied: Futures positions are still open")
        with self._lock:
            self._kill_latched = False
            self._paused = True
        return self.status()

    @staticmethod
    def _rows(payload: Any) -> list[dict[str, Any]]:
        if isinstance(payload, list):
            return [x for x in payload if isinstance(x, dict)]
        if isinstance(payload, dict) and isinstance(payload.get("positions"), list):
            return [x for x in payload["positions"] if isinstance(x, dict)]
        return []

    def status(self) -> dict[str, Any]:
        with self._lock:
            return {
                "runtime": "BINANCE_USDM_FUTURES",
                "testnet": self.testnet,
                "running": self._running,
                "paused": self._paused,
                "kill_latched": self._kill_latched,
                "allow_long": self.allow_long,
                "allow_short": self.allow_short,
                "symbols": list(self.symbols),
                "interval": self.interval,
                "max_leverage": 1,
                "max_open_positions": self.max_open_positions,
                "scan_count": self._scan_count,
                "last_scan_at_ms": self._last_scan_at_ms,
                "last_error": self._last_error,
                "last_scan": self._last_scan_summary,
                "account": dict(self._last_account),
                "open_campaigns": [
                    {
                        "campaign_id": row.get("campaign_id"),
                        "symbol": row.get("symbol"),
                        "side": row.get("side"),
                        "state": row.get("state"),
                        "direction": self.execution._row_tags(row).get("direction", ""),
                    }
                    for row in self.execution._active_rows()
                    if self.execution._row_tags(row).get("execution_mode") == "FUTURES"
                ],
            }

