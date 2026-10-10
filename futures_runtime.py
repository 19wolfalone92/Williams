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
from strategy import (
    calculate_indicators,
    canonical_williams_parameter_blockers,
    config_from_env,
)
from trading_config import TradingConfig
from futures_campaign_execution import FuturesCampaignExecutionService, FuturesCampaignExecutionError

log = logging.getLogger("williams-futures-runtime")
CANONICAL_DECISION_TIMEFRAME = "1h"


def _env_bool(key: str, default: bool) -> bool:
    return str(os.getenv(key, str(default))).strip().lower() in {"1", "true", "yes", "on"}


def _read_persistent_kill_latch(db: Database) -> bool:
    """Treat any persisted non-explicitly-cleared latch value as a kill."""
    raw = db.state_get("futures_kill_latched", None)
    if raw is None:
        return False
    return str(raw).strip().lower() not in {"false", "0", "no", "off"}


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


def _assert_fresh_closed_candle(
    symbol: str,
    interval: str,
    close_time_ms: int,
    *,
    now_ms: int | None = None,
) -> None:
    """Reject stale, future-dated, or invalid last-closed-candle timestamps."""
    try:
        close_ms = int(close_time_ms)
    except (TypeError, ValueError, OverflowError) as exc:
        raise RuntimeError(f"{symbol}/{interval}: invalid closed-candle timestamp") from exc
    now = int(time.time() * 1000) if now_ms is None else int(now_ms)
    age_ms = now - close_ms
    max_age_ms = max(120_000, 2 * _interval_seconds(interval) * 1000)
    if close_ms <= 0 or age_ms < -60_000 or age_ms > max_age_ms:
        raise RuntimeError(
            f"{symbol}/{interval}: last closed candle is stale or has an invalid timestamp "
            f"(age_ms={age_ms}, max_age_ms={max_age_ms})"
        )


def choose_initial_williams_signal(
    signals: Iterable[SignalSpec],
    direction: str,
    *,
    now_ms: int | None = None,
) -> SignalSpec | None:
    """Select the earliest live Wise-Man signal as campaign entry.

    Signal extraction labels WM2/WM3 as ADD_ON so they can only add to an
    existing campaign. With no active campaign, any first valid Wise-Man
    signal may start one; promotion is local to this initial-entry decision.
    """
    direction = str(direction or "").upper()
    if direction not in {"LONG", "SHORT"}:
        return None
    now = int(time.time() * 1000) if now_ms is None else int(now_ms)
    candidates = []
    for signal in signals:
        if signal.role not in {SignalRole.ENTRY, SignalRole.ADD_ON}:
            continue
        if str(signal.direction).upper() != direction:
            continue
        try:
            trigger = float(signal.trigger_price)
            expires = int(signal.expires_at_ms or 0)
        except (TypeError, ValueError, OverflowError):
            continue
        if not math.isfinite(trigger) or trigger <= 0:
            continue
        if expires <= 0 or expires <= now:
            continue
        candidates.append(replace(signal, role=SignalRole.ENTRY))
    return CampaignEngine.choose_initial_signal(candidates)


def _loss_streak_allows_entry(
    db: Database,
    *,
    max_consecutive_losses: int,
    cooldown_minutes: int,
    now: datetime | None = None,
) -> tuple[bool, str]:
    """Fail-closed loss-streak and post-loss cooldown gate for new exposure."""
    try:
        max_losses = int(max_consecutive_losses)
        cooldown = int(cooldown_minutes)
        if max_losses < 0 or cooldown < 0:
            raise ValueError("loss guard limits cannot be negative")
        current = now or datetime.now(timezone.utc)
        if current.tzinfo is None:
            raise ValueError("loss guard requires timezone-aware time")
        current = current.astimezone(timezone.utc)
        rows = db.recent_campaign_closes(limit=max(500, max_losses + 2))
        consecutive = 0
        most_recent_loss_at = None
        for row in rows:
            payload_raw = row.get("payload_json")
            if not payload_raw:
                return False, "latest finalized campaign close has no PnL payload; new entries blocked"
            payload = json.loads(payload_raw) if isinstance(payload_raw, str) else payload_raw
            if not isinstance(payload, dict):
                return False, "finalized campaign close has malformed PnL payload; new entries blocked"
            pnl_raw = payload.get(
                "realized_pnl_quote_net_known_fees",
                payload.get("realized_pnl_quote"),
            )
            pnl = float(pnl_raw)
            if not math.isfinite(pnl):
                return False, "finalized campaign close has non-finite PnL; new entries blocked"
            created_raw = str(row.get("created_at") or "").strip()
            if not created_raw:
                return False, "finalized campaign close has no timestamp; new entries blocked"
            try:
                closed_at = datetime.fromisoformat(created_raw.replace("Z", "+00:00"))
                if closed_at.tzinfo is None:
                    closed_at = closed_at.replace(tzinfo=timezone.utc)
                else:
                    closed_at = closed_at.astimezone(timezone.utc)
            except ValueError:
                return False, "finalized campaign close has invalid timestamp; new entries blocked"
            if pnl < 0:
                consecutive += 1
                if most_recent_loss_at is None:
                    most_recent_loss_at = closed_at
            else:
                break
        if max_losses > 0 and consecutive >= max_losses:
            return False, (
                f"consecutive loss limit reached: {consecutive} >= {max_losses}; "
                "new entries blocked"
            )
        if cooldown > 0 and most_recent_loss_at is not None:
            elapsed = (current - most_recent_loss_at).total_seconds()
            if elapsed < 0:
                return False, "latest loss timestamp is in the future; new entries blocked"
            remaining = cooldown * 60 - elapsed
            if remaining > 0:
                return False, (
                    f"post-loss cooldown active for {math.ceil(remaining)} more seconds; "
                    "new entries blocked"
                )
        return True, f"loss-streak guard clear (consecutive_losses={consecutive})"
    except Exception as exc:
        return False, f"loss-streak/cooldown state unavailable; new entries blocked ({type(exc).__name__}: {exc})"


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
        confirmation_time_ms=int(raw.get("confirmation_time_ms", 0) or 0),
        expires_at_ms=int(raw.get("expires_at_ms", 0) or 0),
        source_candle_index=(
            -1 if raw.get("source_candle_index", -1) is None
            else int(raw.get("source_candle_index", -1))
        ),
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
        self.interval = str(interval or os.getenv("EXECUTION_TIMEFRAME", "1h")).lower()
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
            0.25, max(0.0, float(os.getenv("MAX_DAILY_LOSS_PCT", "0.01")))
        )
        self.max_stop_outs_per_utc_day = min(
            100, max(1, int(os.getenv("MAX_STOP_OUTS_PER_UTC_DAY", "2")))
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
            campaign_risk_limit_pct=min(0.006, max(0.0, float(os.getenv("CAMPAIGN_RISK_LIMIT_PCT", "0.006")))),
        )
        self._lock = threading.RLock()
        self._cycle_lock = threading.RLock()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._running = False
        self._paused = True
        self._kill_latched = _read_persistent_kill_latch(self.db)
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
        available_raw = result.get("availableBalance", 0)
        available = float(0 if available_raw is None or str(available_raw).strip() == "" else available_raw)
        # Fall back only when a field is absent, never when an authoritative
        # equity field explicitly reports zero (zero equity must fail closed).
        equity_raw = result.get("totalMarginBalance")
        if equity_raw is None or str(equity_raw).strip() == "":
            equity_raw = result.get("totalWalletBalance")
        if equity_raw is None or str(equity_raw).strip() == "":
            equity_raw = available
        equity = float(equity_raw)
        if not math.isfinite(available) or not math.isfinite(equity) or available < 0 or equity <= 0:
            raise RuntimeError("Futures account has invalid equity/availableBalance")
        self._last_account = {"equity_quote": equity, "available_quote": available}
        self.controller.risk_engine.balance = equity
        return result

    def _assert_configured_symbol_ownership(self) -> None:
        """Fail closed if any configured symbol has unmanaged positions or orders."""
        symbols = tuple(dict.fromkeys(str(symbol).upper() for symbol in self.symbols if str(symbol).strip()))
        if not symbols:
            raise RuntimeError("No configured Futures symbols; account ownership cannot be verified")
        # The execution guard checks account-wide positions and open orders;
        # pass one configured symbol as the reconciliation context only.
        self.execution._assert_no_unmanaged_positions(symbols[0])

    def _daily_loss_allows_entry(self, equity: float) -> tuple[bool, str]:
        # Invalid account equity must never reset the daily baseline or permit
        # new exposure. NaN is particularly dangerous because comparisons with
        # it are false and can otherwise bypass ordinary threshold checks.
        try:
            current_equity = float(equity)
        except (TypeError, ValueError):
            return False, "invalid account equity; new entries blocked"
        if not math.isfinite(current_equity) or current_equity <= 0:
            return False, "account equity must be finite and positive; new entries blocked"

        day = datetime.now(timezone.utc).date().isoformat()
        day_key = "futures_day_start_date"
        equity_key = "futures_day_start_equity_quote"
        old_day = str(self.db.state_get(day_key, "") or "")
        if old_day != day:
            self.db.state_set(day_key, day)
            self.db.state_set(equity_key, repr(current_equity))
            return True, "new UTC trading day baseline"

        raw_baseline = self.db.state_get(equity_key, None)
        try:
            baseline = float(raw_baseline)
        except (TypeError, ValueError):
            baseline = float("nan")
        if not math.isfinite(baseline) or baseline <= 0:
            # Corrupt/missing same-day baseline is not permission to reset the
            # loss counter. Require recovery/reconciliation before new entries.
            return False, "daily equity baseline is missing or invalid; new entries blocked"

        loss_fraction = max(0.0, (baseline - current_equity) / baseline)
        if not math.isfinite(loss_fraction):
            return False, "daily loss calculation is invalid; new entries blocked"
        if loss_fraction >= self.max_daily_loss_pct:
            return False, (
                f"daily loss limit reached: {loss_fraction:.2%} >= "
                f"{self.max_daily_loss_pct:.2%}; only protection/recovery/exits remain enabled"
            )
        return True, "within daily loss limit"

    def _stop_outs_allow_entry(self, *, now: datetime | None = None) -> tuple[bool, str]:
        """Durable UTC-day stop-out circuit breaker; uncertainty blocks entries."""
        current = now or datetime.now(timezone.utc)
        if current.tzinfo is None:
            return False, "stop-out guard requires a timezone-aware UTC timestamp"
        current = current.astimezone(timezone.utc)
        day_start = current.replace(hour=0, minute=0, second=0, microsecond=0)
        start_text = day_start.strftime("%Y-%m-%d %H:%M:%S")
        try:
            count = int(self.db.count_confirmed_stop_exits_since(start_text))
        except Exception as exc:
            return False, f"confirmed stop-out count unavailable; new entries blocked ({type(exc).__name__}: {exc})"
        if count >= self.max_stop_outs_per_utc_day:
            return False, (
                f"UTC daily stop-out limit reached: {count} >= "
                f"{self.max_stop_outs_per_utc_day}; only protection/recovery/exits remain enabled"
            )
        return True, f"confirmed stop-outs today: {count}/{self.max_stop_outs_per_utc_day}"

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
            _assert_fresh_closed_candle(symbol, interval, close_ms)
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
            if spec.direction == direction and spec.role in {SignalRole.ENTRY, SignalRole.ADD_ON}:
                # The same detector output serves two contexts: WM2/WM3 remain
                # ADD_ON when a campaign already exists, but may be the first
                # initial entry when no earlier Wise-Man signal is available.
                parsed.append(replace(spec, role=SignalRole.ENTRY))
        signal = choose_initial_williams_signal(parsed, direction)
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

    def _directional_add_on_signal(
        self,
        candidate: Candidate,
        direction: str,
        campaign,
    ) -> SignalSpec | None:
        """Select only a fresh same-direction WM2/WM3 signal for an open campaign."""
        latest_time = int(campaign.tags.get("last_signal_time_ms", 0) or 0)
        parsed: list[SignalSpec] = []
        for raw in list(candidate.campaign_signal_specs or []):
            try:
                spec = signal_spec_from_dict(raw)
            except Exception as exc:
                log.warning("%s: invalid add-on SignalSpec discarded: %s", candidate.symbol, exc)
                continue
            if spec.signal_type not in {SignalType.SUPER_AO, SignalType.FRACTAL}:
                continue
            if spec.direction != direction or int(spec.signal_bar_time_ms) <= latest_time:
                continue
            if int(spec.expires_at_ms or 0) <= int(time.time() * 1000):
                continue
            parsed.append(replace(spec, role=SignalRole.ADD_ON))
        if not parsed:
            return None
        signal = min(parsed, key=lambda item: (item.signal_bar_time_ms, item.created_at_ms))

        snapshot = self.context_cache.snapshot()
        operative = snapshot.context(signal.symbol, signal.timeframe)
        if operative is None:
            raise FuturesCampaignExecutionError(
                f"{signal.symbol}/{signal.timeframe}: add-on operative context is missing"
            )
        allowed = operative.allow_long if direction == "LONG" else operative.allow_short
        if not allowed:
            raise FuturesCampaignExecutionError(
                f"{signal.symbol}/{signal.timeframe}: operative context disallows add-on {direction}"
            )

        intervals = list(self.context_intervals)
        seconds = {iv: _interval_seconds(iv) for iv in intervals}
        parents = sorted(
            [iv for iv in intervals if seconds[iv] > _interval_seconds(signal.timeframe)],
            key=lambda iv: seconds[iv],
        )
        parent_ok = not self.require_htf_confirmation
        if self.require_htf_confirmation:
            if not parents:
                raise FuturesCampaignExecutionError(
                    f"{signal.symbol}: no higher timeframe available for add-on confirmation"
                )
            parent = snapshot.context(signal.symbol, parents[0])
            if parent is None:
                raise FuturesCampaignExecutionError(
                    f"{signal.symbol}/{parents[0]}: add-on parent context is missing"
                )
            parent_ok = parent.allow_long if direction == "LONG" else parent.allow_short
            if not parent_ok:
                raise FuturesCampaignExecutionError(
                    f"{signal.symbol}/{parents[0]}: higher timeframe does not confirm add-on {direction}"
                )
            for interval in parents[1:]:
                higher = snapshot.context(signal.symbol, interval)
                if higher is None:
                    raise FuturesCampaignExecutionError(
                        f"{signal.symbol}/{interval}: add-on context is missing"
                    )
                opposite = higher.allow_short if direction == "LONG" else higher.allow_long
                if opposite:
                    raise FuturesCampaignExecutionError(
                        f"{signal.symbol}/{interval}: higher timeframe contradicts add-on {direction}"
                    )

        return replace(
            signal,
            role=SignalRole.ADD_ON,
            htf_confirmed=bool(parent_ok),
            context_versions=snapshot.versions(signal.symbol, intervals),
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
                reason = f"{type(exc).__name__}: {exc}"
                campaign_id = str(row.get("campaign_id", "") or "")
                try:
                    campaign = self.execution.engine.load_campaign(campaign_id) if campaign_id else None
                    if campaign is not None:
                        self.execution.engine.mark_reconcile_required(campaign, reason)
                except Exception:
                    # The state keys below still block new exposure if the campaign
                    # row itself cannot be loaded or durably updated.
                    pass
                self.db.state_set(f"campaign_state:{campaign_id}", "RECONCILE_REQUIRED")
                self.db.state_set(f"position_state:{symbol}", "RECONCILE_REQUIRED")
                summaries.append({
                    "symbol": symbol,
                    "state": "RECONCILE_REQUIRED",
                    "reason": reason,
                })
        return summaries

    def _manage_existing_positions(self) -> list[dict[str, Any]]:
        """Run protective management before any new-entry lockout decision."""
        # Pending entries are cancelled only by explicit pause/kill actions.
        # Doing this on every management scan would continually cancel valid
        # conditional entries and prevent the strategy from ever triggering.
        results: list[dict[str, Any]] = []
        for row in self.execution._active_rows():
            if self.execution._row_tags(row).get("execution_mode") != "FUTURES":
                continue
            symbol = str(row.get("symbol", "")).upper()
            campaign_id = str(row.get("campaign_id", ""))
            try:
                campaign = self.execution.engine.load_campaign(campaign_id)
                if campaign is None:
                    results.append({
                        "symbol": symbol,
                        "action": "RECONCILE_REQUIRED",
                        "reason": "active Futures campaign row could not be loaded",
                    })
                    continue

                position = self.execution._position_row(symbol)
                try:
                    amount = float(position.get("positionAmt", 0) or 0.0)
                except (TypeError, ValueError):
                    amount = float("nan")
                if not math.isfinite(amount):
                    reason = "invalid or non-finite exchange position quantity; management deferred"
                    self.execution.engine.mark_reconcile_required(campaign, reason)
                    self.db.state_set(f"campaign_state:{campaign_id}", "RECONCILE_REQUIRED")
                    self.db.state_set(f"position_state:{symbol}", "RECONCILE_REQUIRED")
                    results.append({"symbol": symbol, "action": "RECONCILE_REQUIRED", "reason": reason})
                    continue
                if abs(amount) <= 1e-12:
                    # reconcile_symbol has already attempted to verify entry
                    # or protective-exit history; do not invent a close here.
                    continue

                # Campaign management follows the canonical H1 decision chart,
                # even if an operator accidentally changes the scan interval.
                management_tf = CANONICAL_DECISION_TIMEFRAME
                frame = fetch_klines(
                    self.client,
                    symbol,
                    management_tf,
                    limit=max(160, self.config.wave_lookback),
                )
                if frame is None or frame.empty:
                    raise RuntimeError("Futures candles unavailable for open-position management")
                closed = frame.iloc[:-1].copy() if len(frame) > 1 else frame.iloc[0:0].copy()
                if len(closed) < 100:
                    raise RuntimeError("insufficient closed candles for safe structural management")
                if "close_time" in closed.columns:
                    last_close = pd.to_datetime(closed["close_time"], utc=True, errors="coerce").iloc[-1]
                    if pd.isna(last_close):
                        raise RuntimeError("last closed candle has an invalid close_time")
                    close_ms = int(last_close.timestamp() * 1000)
                else:
                    close_ms = int(pd.Timestamp(closed.index[-1]).timestamp() * 1000) + _interval_seconds(self.interval) * 1000
                _assert_fresh_closed_candle(symbol, management_tf, close_ms)
                indicators = calculate_indicators(closed, config_from_env())
                atr = self._atr(closed, self.config.atr_period)
                result = self.execution.manage_campaign(campaign, indicators, atr=atr)
                results.append(result)
            except Exception as exc:
                self.db.log_event(
                    "ERROR",
                    "futures_campaign_management_error",
                    f"{symbol}: {type(exc).__name__}: {exc}",
                    {"campaign_id": campaign_id},
                )
                results.append({
                    "symbol": symbol,
                    "action": "MANAGEMENT_ERROR",
                    "reason": f"{type(exc).__name__}: {exc}",
                })
        return results

    def scan_once(self) -> dict[str, Any]:
        # Serialize the whole cycle against the kill switch so a kill request
        # cannot race between the entry gate and a new exchange mutation.
        with self._cycle_lock:
            return self._scan_once()

    def _scan_once(self) -> dict[str, Any]:
        """One full cycle; existing exposure is managed before entry lockouts."""
        # Kill switch blocks all new exposure, but it must not disable
        # reconciliation, protective-stop repair, or exits for existing risk.
        kill_latched = self._kill_latched

        preflight_errors: list[str] = []
        indicator_config = config_from_env()
        canonical_blockers = canonical_williams_parameter_blockers(indicator_config)
        if canonical_blockers:
            preflight_errors.append(
                "Non-canonical Williams indicator parameters block new entries: "
                + "; ".join(canonical_blockers)
            )
        try:
            self.client.sync_time()
        except Exception as exc:
            preflight_errors.append(f"Futures time sync failed: {type(exc).__name__}: {exc}")

        try:
            self._account()
            equity = self._last_account["equity_quote"]
        except Exception as exc:
            # Account/equity failure blocks new exposure, but must not prevent
            # attempts to reconcile, repair protection, or reduce existing risk.
            equity = float("nan")
            preflight_errors.append(f"Futures account/equity unavailable: {type(exc).__name__}: {exc}")

        try:
            permissions = self.client.account_permissions()
            can_trade = permissions.get("canTrade") if isinstance(permissions, dict) else None
            if can_trade is not True:
                preflight_errors.append("Futures account canTrade is not explicitly true")
            if permissions.get("multiAssetsMargin") is not False:
                preflight_errors.append("Futures single-asset mode is not explicitly confirmed")
        except Exception as exc:
            preflight_errors.append(
                f"Futures account permissions unavailable: {type(exc).__name__}: {exc}"
            )

        # PortfolioController uses this value to size and rank risk allocations.
        # Never overwrite its last valid equity with NaN/zero from a failed read.
        controller = getattr(self, "controller", None)
        if controller is not None and math.isfinite(equity) and equity > 0:
            controller.balance_quote = equity
            risk_engine = getattr(controller, "risk_engine", None)
            if risk_engine is not None:
                risk_engine.balance = equity
        reconciliations = self._recover()
        management = self._manage_existing_positions()
        # A single cancel attempt is not enough after an unknown Binance
        # response. While paused/killed, keep reconciling/cancelling stable
        # pending entry and add-on IDs on each cycle; normal scans never cancel
        # valid conditional entries.
        if kill_latched or self._paused:
            cancel_reason = "KILL_SWITCH" if kill_latched else "PAUSE"
            management.extend(self._cancel_pending_entries(reason=cancel_reason))
        daily_ok, daily_reason = self._daily_loss_allows_entry(equity)
        stop_outs_ok, stop_outs_reason = self._stop_outs_allow_entry()
        if not stop_outs_ok:
            daily_ok = False
            daily_reason = f"{daily_reason}; {stop_outs_reason}"
        # Do not let an entry-only guard prevent kill/pause handling or the
        # management/reconciliation work above. If this cycle could otherwise
        # admit entries, missing ledger/config is a fail-closed blocker.
        if kill_latched or self._paused or preflight_errors:
            loss_guard_ok, loss_guard_reason = True, "entry already blocked by a higher-priority gate"
        else:
            db_for_loss_guard = getattr(self, "db", None)
            config_for_loss_guard = getattr(self, "config", None)
            if db_for_loss_guard is None:
                loss_guard_ok, loss_guard_reason = False, "durable Futures close ledger unavailable; new entries blocked"
            else:
                loss_guard_ok, loss_guard_reason = _loss_streak_allows_entry(
                    db_for_loss_guard,
                    max_consecutive_losses=int(getattr(config_for_loss_guard, "max_consecutive_losses", 2)),
                    cooldown_minutes=int(getattr(config_for_loss_guard, "cooldown_minutes", 30)),
                )
        if not loss_guard_ok:
            daily_ok = False
            daily_reason = f"{daily_reason}; {loss_guard_reason}"
        if preflight_errors:
            daily_ok = False
            daily_reason = "; ".join(preflight_errors) + "; new entries blocked"
        blocked_reconciliation = any(
            str(item.get("state", "")).upper() == "RECONCILE_REQUIRED"
            for item in reconciliations
        )
        management_blocked = any(
            # WAIT means an active campaign could not complete its management
            # pass (for example, ATR/closed candles unavailable). It is not a
            # successful protection check, so do not compound exposure.
            str(item.get("action", "")).upper() in {
                "MANAGEMENT_ERROR", "RECONCILE_REQUIRED", "WAIT"
            }
            or str(item.get("state", "")).upper() == "RECONCILE_REQUIRED"
            for item in management
        )
        # If any open campaign could not be safely inspected/managed this
        # cycle, do not compound exposure by opening another campaign.
        blocked_reconciliation = blocked_reconciliation or management_blocked
        if kill_latched:
            self._last_scan_summary = {
                "state": "KILL_SWITCH_LATCHED",
                "reason": "new entries disabled; existing positions reconciled and managed",
                "reconciliation": reconciliations,
                "management": management,
                "new_entries": 0,
            }
            return self._last_scan_summary

        if self._paused:
            self._last_scan_summary = {
                "state": "PAUSED",
                "reason": "new entries paused; existing positions remain managed",
                "reconciliation": reconciliations,
                "management": management,
                "new_entries": 0,
            }
            return self._last_scan_summary

        # Repeat ownership checks for every configured symbol on every scan:
        # an orphan order on a non-first symbol must also block new exposure.
        try:
            self._assert_configured_symbol_ownership()
        except Exception as exc:
            blocked_reconciliation = True
            management.append({
                "action": "RECONCILE_REQUIRED",
                "reason": f"account-wide exposure/order ownership check failed: {type(exc).__name__}: {exc}",
            })

        if blocked_reconciliation:
            # A reconciliation/management failure must also cancel already-armed
            # conditional entries. Blocking only the scanner is insufficient:
            # an exchange-side stop-entry on another symbol could still trigger.
            cancellations = self._cancel_pending_entries(reason="RECONCILE_REQUIRED")
            management.extend(cancellations)
            self._last_scan_summary = {
                "state": "RECONCILE_REQUIRED",
                "reason": (
                    "existing-position management failed or a campaign needs reconciliation; "
                    "new exposure is fail-closed while protection/recovery remain enabled"
                ),
                "reconciliation": reconciliations,
                "management": management,
                "pending_order_cancellations": cancellations,
                "new_entries": 0,
            }
            return self._last_scan_summary

        if not daily_ok:
            # Daily loss lockout blocks all new exposure, including already
            # armed conditional entries/add-ons, while open-position management
            # and emergency exits remain available.
            cancellations = self._cancel_pending_entries(reason="DAILY_RISK_LOCKOUT")
            management.extend(cancellations)
            self._last_scan_summary = {
                "state": "DAILY_RISK_LOCKOUT",
                "reason": daily_reason,
                "reconciliation": reconciliations,
                "management": management,
                "pending_order_cancellations": cancellations,
                "new_entries": 0,
            }
            return self._last_scan_summary

        if self.interval != CANONICAL_DECISION_TIMEFRAME:
            # M15/M5 are not allowed to create signals or arm exchange-side
            # conditional orders. Cancel any existing armed entries because
            # their originating signal timeframe is no longer canonical.
            cancellations = self._cancel_pending_entries(reason="NON_CANONICAL_SIGNAL_TIMEFRAME")
            management.extend(cancellations)
            self._last_scan_summary = {
                "state": "SIGNAL_TIMEFRAME_BLOCKED",
                "reason": (
                    f"new entries require canonical H1 decisions; configured interval={self.interval}. "
                    "M15 is context/execution monitoring only; M5 is diagnostics/replay only."
                ),
                "reconciliation": reconciliations,
                "management": management,
                "pending_order_cancellations": cancellations,
                "new_entries": 0,
            }
            return self._last_scan_summary

        active_count = len([
            row for row in self.execution._active_rows()
            if self.execution._row_tags(row).get("execution_mode") == "FUTURES"
        ])
        # Select candidates even at the position cap: existing campaigns may
        # have eligible Wise-Men add-ons, while the execution loop below still
        # enforces capacity for genuinely new campaigns.
        selections = self.controller.select_portfolio(
            open_risk_quote=self.execution.engine.portfolio_reserved_risk_quote(),
            open_positions=active_count,
            include_existing_campaigns=True,
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
            existing_campaign = self.execution._find_active_campaign(symbol)
            if existing_campaign is not None:
                # Existing campaigns do not consume a *new* position slot.
                # Only same-direction, fresh WM2/WM3 signals can add exposure.
                if existing_campaign.position_qty <= 0:
                    continue
                try:
                    frames = self._refresh_symbol_context(symbol)
                    add_signal = self._directional_add_on_signal(
                        candidate, direction, existing_campaign
                    )
                    if add_signal is None:
                        continue
                    add_result = self.execution.arm_add_on(
                        add_signal,
                        equity_quote=equity,
                        candidate_risk_fraction=float(selection.risk.risk_pct) / 100.0,
                        available_quote=float(self._last_account["available_quote"]),
                    )
                    results.append(add_result)
                except Exception as exc:
                    log.warning("Futures add-on not armed for %s/%s: %s", symbol, direction, exc)
                    results.append({
                        "symbol": symbol,
                        "direction": direction,
                        "action": "WAIT_ADD_ON",
                        "reason": f"{type(exc).__name__}: {exc}",
                    })
                continue

            if active_count >= self.max_open_positions:
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
                    available_quote=float(self._last_account["available_quote"]),
                )
                results.append(entry)
                active_count += 1
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
            "management": management,
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
            if self._running:
                # Explicit start never clears a persistent kill switch.
                if not self._kill_latched:
                    self._paused = False
                return self.status()
            startup_blockers = []
            try:
                self.client.sync_time()
            except Exception as exc:
                startup_blockers.append(
                    f"Futures time sync failed: {type(exc).__name__}: {exc}"
                )
            try:
                self.client.ensure_one_way_mode()
            except Exception as exc:
                startup_blockers.append(
                    f"Futures one-way mode is unconfirmed: {type(exc).__name__}: {exc}"
                )
            account: dict[str, Any] = {}
            try:
                account = self._account()
            except Exception as exc:
                startup_blockers.append(
                    f"Futures account/equity unavailable: {type(exc).__name__}: {exc}"
                )
            try:
                permissions = self.client.account_permissions()
                can_trade = permissions.get("canTrade") if isinstance(permissions, dict) else None
                if can_trade is not True:
                    startup_blockers.append("Futures account canTrade is not explicitly true")
                if permissions.get("multiAssetsMargin") is not False:
                    startup_blockers.append("Futures single-asset mode is not explicitly confirmed")
            except Exception as exc:
                startup_blockers.append(
                    f"Futures account permissions unavailable: {type(exc).__name__}: {exc}"
                )
            try:
                reconciliation = self._recover()
            except Exception as exc:
                reconciliation = []
                startup_blockers.append(
                    f"Startup reconciliation failed: {type(exc).__name__}: {exc}"
                )
            startup_blockers.extend(
                f"{item.get('symbol', 'UNKNOWN')}: {item.get('reason', 'reconciliation required')}"
                for item in reconciliation
                if str(item.get("state", "")).upper() == "RECONCILE_REQUIRED"
            )
            # Unknown exposure/orders must block entries, but must not prevent
            # the management-only monitor from starting after a process restart.
            try:
                self._assert_configured_symbol_ownership()
            except Exception as exc:
                startup_blockers.append(f"{type(exc).__name__}: {exc}")

            # Testnet can prepare flat symbols for the initial 1x isolated
            # policy automatically. Mainnet changes require a separate explicit
            # FUTURES_AUTO_PREPARE_SYMBOLS=true opt-in and only touch flat,
            # order-free symbols.
            auto_prepare = (
                _env_bool("FUTURES_AUTO_PREPARE_SYMBOLS", self.testnet)
                and not self._kill_latched
                and not startup_blockers
            )
            if auto_prepare:
                prepared = []
                for symbol in self.symbols:
                    pos_rows = self._rows(self.client.position_risk(symbol))
                    live_position = False
                    if pos_rows:
                        position_row = next(
                            (row for row in pos_rows if str(row.get("symbol", "")).upper() == symbol),
                            None,
                        )
                        if position_row is None:
                            startup_blockers.append(
                                f"{symbol}: symbol-scoped positionRisk returned a different symbol"
                            )
                            continue
                        raw_amount = position_row.get("positionAmt")
                        if raw_amount is None or str(raw_amount).strip() == "":
                            startup_blockers.append(
                                f"{symbol}: symbol-scoped positionRisk omitted positionAmt"
                            )
                            continue
                        try:
                            amount = float(raw_amount)
                        except (TypeError, ValueError):
                            startup_blockers.append(
                                f"{symbol}: symbol-scoped positionRisk has invalid positionAmt"
                            )
                            continue
                        if not math.isfinite(amount):
                            startup_blockers.append(
                                f"{symbol}: symbol-scoped positionRisk has non-finite positionAmt"
                            )
                            continue
                        live_position = abs(amount) > 1e-12
                    has_orders = bool(
                        self.client.open_orders(symbol)
                        or self.client.open_algo_orders(symbol)
                    )
                    if live_position or has_orders:
                        # Do not mutate margin/leverage under existing exposure.
                        continue
                    try:
                        self.client.prepare_symbol(symbol)
                    except Exception as exc:
                        startup_blockers.append(
                            f"{symbol}: symbol preparation/config verification failed: "
                            f"{type(exc).__name__}: {exc}"
                        )
                        # Stop changing other symbols once account policy
                        # verification fails; the monitor will run management-only.
                        break
                    prepared.append(symbol)
                self.db.log_event(
                    "INFO",
                    "futures_symbols_prepared",
                    "Verified/established one-way isolated 1x on flat Futures symbols",
                    {"symbols": prepared, "testnet": self.testnet},
                )
            try:
                self._daily_loss_allows_entry(
                    float(self._last_account.get("equity_quote", float("nan")))
                )
            except Exception as exc:
                startup_blockers.append(
                    f"Daily risk baseline check failed: {type(exc).__name__}: {exc}"
                )
            self._paused = bool(self._kill_latched or startup_blockers)
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
            started = self.status()
            if self._kill_latched:
                started["state"] = "KILL_SWITCH_LATCHED"
                started["warning"] = (
                    "Persistent kill switch remains latched. Management/reconciliation "
                    "monitor is running; new exposure remains disabled."
                )
            elif startup_blockers:
                started["state"] = "MANAGEMENT_ONLY"
                started["startup_blockers"] = startup_blockers
                started["warning"] = (
                    "Startup reconciliation is unresolved. The monitor remains active "
                    "in paused mode; new exposure is blocked."
                )
            else:
                started["state"] = "RUNNING"
            return started

    def _cancel_pending_entries(self, *, reason: str) -> list[dict[str, Any]]:
        results: list[dict[str, Any]] = []
        for row in self.execution._active_rows():
            if self.execution._row_tags(row).get("execution_mode") != "FUTURES":
                continue
            campaign_state = str(row.get("state", "")).upper()
            row_tags = self.execution._row_tags(row)
            pending_add_on = bool(row_tags.get("pending_add_on_client_algo_id")) or campaign_state in {
                "ADD_ON_ARMING", "ADD_ON_PENDING", "POSITION_EXPANDING"
            }
            pending_entry = (
                campaign_state == "ENTRY_PENDING"
                or bool(row_tags.get("entry_fill_reconciliation_pending"))
            )
            if not pending_entry and not pending_add_on:
                continue
            campaign_id = str(row.get("campaign_id", ""))
            try:
                campaign = self.execution.engine.load_campaign(campaign_id)
                if campaign is None:
                    raise RuntimeError("pending-order campaign could not be loaded")
                if pending_add_on:
                    result = self.execution.cancel_pending_add_on(campaign, reason=reason)
                else:
                    result = self.execution.cancel_pending_entry(campaign, reason=reason)
                results.append(result)
            except Exception as exc:
                detail = f"{reason}: pending order cancellation failed: {type(exc).__name__}: {exc}"
                self.db.log_event(
                    "ERROR",
                    "futures_pending_order_cancel_failed",
                    detail,
                    {"campaign_id": campaign_id},
                )
                results.append({
                    "symbol": str(row.get("symbol", "")).upper(),
                    "state": "RECONCILE_REQUIRED",
                    "action": "CANCEL_UNVERIFIED",
                    "reason": detail,
                })
        return results

    def pause(self) -> dict[str, Any]:
        # Serialize with a scan so no conditional entry can be submitted after
        # pause begins. Existing-position management remains enabled.
        with self._cycle_lock:
            with self._lock:
                self._paused = True
            cancellations = self._cancel_pending_entries(reason="PAUSE")
            unresolved = [
                item for item in cancellations
                if str(item.get("state", "")).upper() == "RECONCILE_REQUIRED"
            ]
            active_campaigns = [
                row for row in self.execution._active_rows()
                if self.execution._row_tags(row).get("execution_mode") == "FUTURES"
            ]
            self.db.log_event(
                "WARNING",
                "futures_runtime_paused",
                "New entries paused; pending entries cancelled or flagged for reconciliation",
                {"pending_entry_cancellations": cancellations},
            )
            result = {**self.status(), "state": "PAUSED", "pending_entry_cancellations": cancellations}
            if unresolved or active_campaigns:
                result["management_only_monitor_required"] = True
                result["warning"] = (
                    "Futures campaigns or pending-order cancellations remain active. Keep the "
                    "management monitor alive until exposure is flat and exchange state is reconciled."
                )
            return result

    def stop(self) -> dict[str, Any]:
        paused = self.pause()
        if paused.get("management_only_monitor_required"):
            return {
                **paused,
                "state": "MANAGEMENT_ONLY",
                "thread_alive": bool(self._thread is not None and self._thread.is_alive()),
            }
        self._stop.set()
        thread = self._thread
        if thread is not None and thread is not threading.current_thread():
            thread.join(timeout=3.0)
        with self._lock:
            still_alive = bool(thread is not None and thread.is_alive())
            self._running = still_alive
        return {
            **self.status(),
            "state": "STOPPING" if still_alive else "STOPPED",
            "thread_alive": still_alive,
        }

    def kill(self) -> dict[str, Any]:
        """Latch entries off, attempt exits, and retain a management-only monitor."""
        with self._cycle_lock:
            return self._kill_locked()

    def _kill_locked(self) -> dict[str, Any]:
        with self._lock:
            self._kill_latched = True
            self._paused = True
            # Do not stop the monitor: if an exit fails, reconciliation and
            # protective management must continue while entries remain latched.
        latch_persistence_error = ""
        try:
            self.db.state_set("futures_kill_latched", "true")
        except Exception as exc:
            # Keep the in-memory latch active and continue attempting to reduce
            # risk, but explicitly report that restart durability is degraded.
            latch_persistence_error = f"{type(exc).__name__}: {exc}"
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
                    if campaign.state == CampaignState.ENTRY_PENDING:
                        results.append(
                            self.execution.cancel_pending_entry(campaign, reason="KILL_SWITCH")
                        )
                    else:
                        results.append({
                            "symbol": symbol,
                            "state": "NO_POSITION",
                            "action": "RECONCILE",
                        })
                    continue
                results.append(self.execution.exit_position(campaign, reason="KILL_SWITCH"))
            except Exception as exc:
                results.append({
                    "symbol": symbol,
                    "state": "RECONCILE_REQUIRED",
                    "error": f"{type(exc).__name__}: {exc}",
                })
        with self._lock:
            thread_alive = bool(self._thread is not None and self._thread.is_alive())
            if not thread_alive:
                # A kill invoked after STOPPED still starts a management-only
                # monitor; the latched gate prevents every new entry.
                self._stop.clear()
                self._running = True
                self._thread = threading.Thread(
                    target=self._loop,
                    name="williams-usdm-futures-kill-monitor",
                    daemon=True,
                )
                self._thread.start()
            else:
                # Do not create a duplicate monitor if a slow network operation
                # outlived stop()'s bounded join timeout.
                self._stop.clear()
                self._running = True
        self.db.log_event(
            "ERROR",
            "futures_runtime_kill_switch",
            "Kill switch latched; reduce-only exits attempted for managed Futures positions",
            {"results": results},
        )
        return {
            **self.status(),
            "state": "KILL_SWITCH_LATCHED",
            "exit_results": results,
            "durable_latch_persisted": not bool(latch_persistence_error),
            **({"latch_persistence_error": latch_persistence_error} if latch_persistence_error else {}),
        }

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
        try:
            # A flat position is not enough: orphan conditional entries/orders on
            # any configured symbol can recreate exposure after latch reset.
            self._assert_configured_symbol_ownership()
        except Exception as exc:
            raise RuntimeError(
                f"Kill reset denied: account-wide positions/orders are not clean ({type(exc).__name__}: {exc})"
            ) from exc
        # Persist the explicit reset before clearing the in-memory latch.
        # If SQLite cannot commit, keep the kill switch active.
        self.db.state_set("futures_kill_latched", "false")
        with self._lock:
            self._kill_latched = False
            self._paused = True
        return self.status()

    @staticmethod
    def _rows(payload: Any) -> list[dict[str, Any]]:
        if isinstance(payload, list):
            if any(not isinstance(row, dict) for row in payload):
                raise RuntimeError("Futures positionRisk contains a malformed row")
            return payload
        if isinstance(payload, dict) and "symbol" in payload:
            return [payload]
        raise RuntimeError("Futures positionRisk returned a malformed payload")

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

