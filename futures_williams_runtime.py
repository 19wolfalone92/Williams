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

from dataclasses import dataclass, field
from datetime import datetime, timezone
import os
import time
import uuid
import math
from decimal import Decimal

from db import Database
from execution_barrier import ExecutionBarrier, OrderIntent
from market_context import ContextCache
from strategy import (
    calculate_indicators,
    canonical_indicator_config_from_env,
)
from williams_signals import (
    extract_long_signal_specs,
    extract_short_signal_specs,
)
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
from trading_config import DEFAULT_FUTURES_SYMBOLS, TradingConfig
from wave_engine import MultiTimeframeWaveEngine
from williams_directional_context import evaluate_direction


def _env_bool(name: str, default: bool = False) -> bool:
    """Parse the same conventional boolean values used by TradingConfig."""
    raw = os.getenv(name, str(default)).strip().lower()
    return raw in {"1", "true", "yes", "on"}


def _signal_profile_entry_block_reason(profile: str) -> str:
    profile = str(profile or "").strip().upper()
    if profile == "NTD_FIVE_DIMENSIONS":
        return (
            "BLOCKED: NTD_FIVE_DIMENSIONS is not execution-ready; "
            "the standalone Balance Line trade engine is missing/unverified. "
            "Use TC2_THREE_WISE_MEN for execution."
        )
    if profile != "TC2_THREE_WISE_MEN":
        return (
            "WILLIAMS_SIGNAL_PROFILE must be TC2_THREE_WISE_MEN; "
            "NTD_FIVE_DIMENSIONS is research-blocked until Balance Line verification"
        )
    return ""


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
    directional_context: dict = field(default_factory=dict)

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
            "directional_context": dict(self.directional_context),
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


def _requires_directional_htf_confirmation(signal_type: SignalType) -> bool:
    """TC2 WM1 is an early reversal; aligned HTF trend is context, not its entry prerequisite.

    WM2/WM3 continuation-style entries retain the configured directional HTF gate.
    Exchange/risk/trigger/protection gates still apply to every signal.
    """
    return signal_type != SignalType.REVERSAL


def _pending_entry_preflight_reason(pending, intent, current_price: float, now_ms: int | None = None) -> str:
    """Fail closed if a conditional Wise-Man entry is stale or its trigger already passed."""
    now = int(time.time() * 1000) if now_ms is None else int(now_ms)
    if not isinstance(pending, dict):
        return "pending_signal_missing"
    signal = pending.get("signal")
    if not isinstance(signal, dict):
        return "pending_signal_payload_missing"
    if str(pending.get("state", "")).upper() != SignalState.VALIDATED.value:
        return "pending_signal_not_validated"
    if str(signal.get("signal_id", "")) != str(getattr(intent, "signal_id", "")):
        return "pending_signal_id_mismatch"
    side = str(getattr(intent, "side", "")).upper()
    if str(signal.get("side", "")).upper() != side or side not in {"BUY", "SELL"}:
        return "pending_signal_side_mismatch"
    try:
        expires_at = int(signal.get("expires_at_ms", 0) or 0)
        trigger = float(signal.get("trigger_price", 0) or 0)
        protective = float(signal.get("protective_reference", 0) or 0)
        price = float(current_price)
    except (TypeError, ValueError):
        return "pending_signal_price_or_expiry_invalid"
    if expires_at <= 0:
        return "pending_signal_expiry_missing"
    if now >= expires_at:
        return "pending_signal_expired"
    if trigger <= 0 or protective <= 0 or price <= 0:
        return "pending_signal_trigger_stop_or_market_price_invalid"
    if side == "BUY" and price <= protective:
        return "long_setup_invalidated_before_trigger"
    if side == "SELL" and price >= protective:
        return "short_setup_invalidated_before_trigger"
    if side == "BUY" and price >= trigger:
        return "long_trigger_already_crossed; late entry blocked"
    if side == "SELL" and price <= trigger:
        return "short_trigger_already_crossed; late entry blocked"
    return ""


class FuturesWilliamsScanner:
    def __init__(self, client, *, interval: str = "1h", htf_interval: str = "4h"):
        self.client = client
        self.interval = str(interval).lower()
        self.htf_interval = str(htf_interval).lower()
        self.config = canonical_indicator_config_from_env()
        # This dependency was previously referenced by analyse() but never
        # initialized: every real symbol evaluation raised AttributeError.
        self.wave_engine = MultiTimeframeWaveEngine(
            client, base_interval=self.interval, intervals=[self.interval]
        )
        self.latest_directional_view: dict[str, dict] = {}
        self.symbols: list[str] = []
        self.configured_symbols = len(DEFAULT_FUTURES_SYMBOLS)
        self.last_scan_status: dict = {
            "state": "NOT_RUN",
            "reason": None,
            "configured_symbols": self.configured_symbols,
            "evaluated_symbols": 0,
            "symbols": [],
            "failures": {},
        }

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

    def universe(self, limit: int = 10) -> list[str]:
        # Never auto-discover or rank an unrestricted contract list for trading.
        # The approved ten-symbol list is a separate, explicit acceptance input.
        default_symbols = DEFAULT_FUTURES_SYMBOLS
        raw = os.getenv("FUTURES_APPROVED_SYMBOLS", "").strip()
        approved = (
            [item.strip().upper() for item in raw.split(",")]
            if raw else list(default_symbols)
        )
        self.last_universe_error = None
        self.symbols = []
        self.configured_symbols = len(approved)
        if len(approved) != 10 or any(not item for item in approved):
            self.last_universe_error = "FUTURES_APPROVED_SYMBOLS must contain exactly 10 unique symbols"
            return []
        if len(set(approved)) != 10:
            self.last_universe_error = "FUTURES_APPROVED_SYMBOLS contains duplicate symbols"
            return []
        if any(not item.isalnum() or not item.endswith("USDT") for item in approved):
            self.last_universe_error = "FUTURES_APPROVED_SYMBOLS contains an invalid USDⓈ-M USDT symbol"
            return []
        if tuple(approved) != DEFAULT_FUTURES_SYMBOLS:
            self.last_universe_error = (
                "FUTURES_APPROVED_SYMBOLS must match the canonical ten-symbol Futures universe"
            )
            return []

        info = self.client.exchange_info()
        rows = info.get("symbols", []) if isinstance(info, dict) else []
        exchange = {
            str(row.get("symbol", "")).upper(): row
            for row in rows
            if isinstance(row, dict)
        }
        for symbol in approved:
            row = exchange.get(symbol)
            if row is None:
                self.last_universe_error = f"Approved symbol is absent from USDⓈ-M exchangeInfo: {symbol}"
                return []
            if (
                str(row.get("status", "")).upper() != "TRADING"
                or str(row.get("quoteAsset", "")).upper() != "USDT"
                or str(row.get("contractType", "")).upper() != "PERPETUAL"
            ):
                self.last_universe_error = f"Approved symbol is not a trading USDⓈ-M USDT perpetual: {symbol}"
                return []
            filters = {
                str(item.get("filterType", "")).upper(): item
                for item in row.get("filters", [])
                if isinstance(item, dict)
            }
            if not filters.get("PRICE_FILTER") or not filters.get("LOT_SIZE") or not (
                filters.get("MIN_NOTIONAL") or filters.get("NOTIONAL")
            ):
                self.last_universe_error = f"Required exchange filters are missing for {symbol}"
                return []

        # Keep approved order stable; the limit argument can never add or remove symbols.
        # Expose the configured universe for diagnostics even when some symbols
        # have no current entry signal.
        self.symbols = list(approved)
        return list(approved)

    def _spread(self, symbol: str) -> float:
        row = self.client.book_ticker(symbol)
        bid = float(row.get("bidPrice", 0) or 0)
        ask = float(row.get("askPrice", 0) or 0)
        if bid <= 0 or ask <= 0 or ask < bid:
            return float("inf")
        return (ask - bid) / bid

    def _htf(self, symbol: str) -> tuple[bool, bool]:
        """D1 macro airbag + H4 directional context; H1 remains the decision TF."""
        if not _env_bool("REQUIRE_HTF_CONFIRMATION", True):
            return True, True

        def closed_context(frame_name: str):
            frame = fetch_klines(self.client, symbol, frame_name, limit=180)
            if len(frame) < 90:
                return None
            closed = frame.iloc[:-1].copy() if len(frame) > 1 else frame.copy()
            if len(closed) < 45:
                return None
            indicators = calculate_indicators(closed, self.config)
            last = indicators.iloc[-1]
            bullish = bool(last.get("bullish_alligator", False)) and float(last.get("ao", 0) or 0) > 0
            bearish = bool(last.get("bearish_alligator", False)) and float(last.get("ao", 0) or 0) < 0
            return bullish, bearish

        daily = closed_context("1d")
        four_hour = closed_context("4h")
        # Missing context is not bullish or bearish evidence: block both directions.
        if daily is None or four_hour is None:
            return False, False
        d1_bull, d1_bear = daily
        h4_bull, h4_bear = four_hour
        # D1 is an airbag, not an entry trigger. H4 must support the direction,
        # while a clear opposite D1 trend vetoes the trade. No M15/M5 entry path.
        return h4_bull and not d1_bear, h4_bear and not d1_bull

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
        wave_snapshot = self.wave_engine.build_snapshot(
            self.interval,
            closed,
            degree="EXECUTION",
        )
        point_zero_confirmed = bool(wave_snapshot.point_zero_confirmed)
        point_zero_score = int(wave_snapshot.point_zero_score)

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

        profile = os.getenv(
            "WILLIAMS_SIGNAL_PROFILE", "TC2_THREE_WISE_MEN"
        ).strip().upper()
        profile_block_reason = _signal_profile_entry_block_reason(profile)
        if profile_block_reason:
            self.last_signal_profile_error = profile_block_reason
            return []
        if profile == "TC2_THREE_WISE_MEN":
            long_specs = extract_long_signal_specs(
                symbol,
                ind,
                timeframe=self.interval,
                tick_size=tick,
                point_zero_confirmed=point_zero_confirmed,
                point_zero_score=point_zero_score,
                require_point_zero_for_reversal=os.getenv(
                    "REQUIRE_POINT_ZERO_FOR_WM1", "false"
                ).lower() == "true",
            )
            short_specs = extract_short_signal_specs(
                symbol,
                ind,
                timeframe=self.interval,
                tick_size=tick,
                point_zero_confirmed=point_zero_confirmed,
                point_zero_score=point_zero_score,
                require_point_zero_for_reversal=os.getenv(
                    "REQUIRE_POINT_ZERO_FOR_WM1", "false"
                ).lower() == "true",
            )
        else:
            self.last_signal_profile_error = _signal_profile_entry_block_reason(profile)
            return []
        self.last_signal_profile_error = None
        long_htf, short_htf = self._htf(symbol)
        # Rank only existing, valid Williams triggers. Divergence/wave count
        # never manufacture an entry, order, stop, add-on, or exit on their own.
        contexts = {
            side: evaluate_direction(
                side=side, snapshot=wave_snapshot,
                last_indicator=ind.iloc[-1], closed_bar_count=len(ind),
                htf_confirmed=htf_ok,
            ).to_dict()
            for side, htf_ok in (("LONG", long_htf), ("SHORT", short_htf))
        }
        self.latest_directional_view[symbol] = contexts
        out: list[FuturesCandidate] = []

        for side, specs, htf_ok in (
            ("LONG", long_specs, long_htf),
            ("SHORT", short_specs, short_htf),
        ):
            if side == "LONG" and not _env_bool("ALLOW_LONG", True):
                continue
            if side == "SHORT" and not _env_bool("ALLOW_SHORT", True):
                continue
            if not specs:
                continue
            signal_pool = [
                s for s in specs
                if s.side == ("BUY" if side == "LONG" else "SELL")
            ]
            if not signal_pool:
                continue
            # A flat campaign may start only from a Wise-Man ENTRY signal.
            # TC2 three-price-bar setups are continuation add-ons and are still
            # surfaced to an existing campaign, but can never open a new one.
            initial_pool = [s for s in signal_pool if s.role == SignalRole.ENTRY]
            # Never resurrect an older setup while a newer valid event exists.
            # The trigger is conditional; the most recent presenting event is
            # the only eligible initial-entry candidate for this direction.
            signal = max(initial_pool or signal_pool, key=lambda s: (s.signal_bar_time_ms, s.created_at_ms))
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
                SignalType.AO_TWIN_PEAKS: 3.0,
                SignalType.AO_SAUCER: 2.0,
                SignalType.AO_ZERO_LINE: 1.0,
                SignalType.AC_SIGNAL: 1.5,
                SignalType.ZONE_ADD_ON: 1.0,
            }.get(signal.signal_type, 0.0)
            score = 60.0 + freshness * 20.0 + type_bonus
            if signal.alligator_awake:
                score += 10.0
            # Bounded, explainable, explicitly uncalibrated structure overlay.
            # Existing signal expiry, HTF execution gates, protective stops,
            # Futures Risk Engine and campaign state machine stay authoritative.
            score += float(contexts[side]["score_adjustment"])
            out.append(
                FuturesCandidate(
                    symbol=symbol,
                    side=side,
                    signal=signal,
                    all_signals=tuple(signal_pool),
                    atr_pct=atr_pct,
                    spread_pct=spread_pct,
                    htf_confirmed=bool(htf_ok),
                    score=max(0.0, min(100.0, score)),
                    directional_context=contexts[side],
                )
            )
        return out

    def scan(self) -> list[FuturesCandidate]:
        result: list[FuturesCandidate] = []
        self.latest_directional_view = {}
        self.last_scan_status = {
            "state": "RUNNING",
            "reason": None,
            "configured_symbols": 0,
            "evaluated_symbols": 0,
            "symbols": [],
            "failures": {},
        }
        profile = os.getenv(
            "WILLIAMS_SIGNAL_PROFILE", "TC2_THREE_WISE_MEN"
        ).strip().upper()
        if profile not in {"TC2_THREE_WISE_MEN", "NTD_FIVE_DIMENSIONS"}:
            self.last_scan_status = {
                "state": "BLOCKED",
                "reason": "INVALID_WILLIAMS_SIGNAL_PROFILE",
                "signal_profile": profile,
                "configured_symbols": self.configured_symbols,
                "evaluated_symbols": 0,
            }
            return []
        symbols = self.universe()
        if len(symbols) != 10:
            self.last_scan_status = {
                "state": "BLOCKED",
                "reason": self.last_universe_error or "FUTURES_UNIVERSE_NOT_EXACTLY_TEN",
                "configured_symbols": self.configured_symbols,
                "evaluated_symbols": 0,
            }
            return []
        self.symbols = list(symbols)
        evaluated = []
        failures = {}
        for symbol in symbols:
            try:
                result.extend(self.analyse(symbol))
                evaluated.append(symbol)
            except Exception as exc:
                failures[symbol] = type(exc).__name__
                self.latest_directional_view[symbol] = {"error": type(exc).__name__}
        # Prioritize higher-quality, fresher candidates; never let an old
        # signal consume an available campaign slot before a current one.
        result.sort(key=lambda c: (-c.score, -c.signal.signal_bar_time_ms, c.symbol, c.side))
        self.last_scan_status = {
            "state": "COMPLETE" if len(evaluated) == 10 else "PARTIAL",
            "reason": None if len(evaluated) == 10 else "ONE_OR_MORE_SYMBOL_EVALUATIONS_FAILED",
            "configured_symbols": len(symbols),
            "evaluated_symbols": len(evaluated),
            "symbols": evaluated,
            "failures": failures,
            "directional_context": dict(self.latest_directional_view),
        }
        return result


class FuturesWilliamsRuntime:
    """Production-facing bidirectional Williams Futures runtime."""

    is_futures_runtime = True

    def __init__(self, api_key=None, api_secret=None, testnet=None, context_cache=None):
        self.config = TradingConfig.from_env()
        self.symbol = os.getenv("SYMBOL", self.config.symbols[0]).upper()
        self.interval = self.config.execution_timeframe
        self.htf_interval = os.getenv("HTF_INTERVAL", "4h").strip().lower()
        if self.htf_interval != "4h":
            raise ValueError("HTF_INTERVAL must be H4 for the canonical Williams campaign")
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
                _env_bool("TESTNET", True)
                if testnet is None else bool(testnet)
            ),
        )
        self.db.state_set("active_symbol", self.symbol)

        self.engine = CampaignEngine(
            self.db,
            portfolio_risk_limit_pct=self.config.max_total_risk_pct,
            campaign_risk_limit_pct=self.config.campaign_risk_limit_pct,
            initial_risk_fraction_of_campaign=(
                self.config.initial_entry_risk_pct /
                max(self.config.campaign_risk_limit_pct, 1e-12)
            ),
        )
        self.scanner = FuturesWilliamsScanner(
            self.client,
            interval=self.interval,
            htf_interval=self.htf_interval,
        )
        self.auto_scan_symbols = []
        self.symbols = list(self.config.symbols)
        self.max_open_positions = self.config.max_open_positions
        self.max_total_risk_pct = self.config.max_total_risk_pct
        self.max_risk_per_trade_pct = self.config.risk_per_trade_pct
        self.scan_workers = 1
        self.wave_top_n = 0
        self.liquidity_preselect = int(os.getenv("FUTURES_SCAN_TOP_N", "40"))
        self._locks: set[str] = set()
        self._leverage_confirmations: dict[str, dict] = {}
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

    def _pending_exits(self):
        rows = self.db.conn.execute(
            "SELECT key,value FROM bot_state WHERE key LIKE 'exit_client_order_id:%'"
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

    def _portfolio_available_risk_pct(self, equity=None) -> float:
        """Return unreserved portfolio risk capacity as a fraction of equity."""
        equity = float(self._equity() if equity is None else equity)
        if equity <= 0:
            return 0.0
        capacity = equity * max(0.0, float(self.max_total_risk_pct))
        reserved = float(self.engine.portfolio_reserved_risk_quote())
        return max(0.0, (capacity - reserved) / equity)

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
        rows = [row for row in info.get("symbols", [])
                if isinstance(row, dict) and str(row.get("symbol", "")).upper() == str(symbol).upper()]
        if len(rows) != 1:
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

    def _full_market_close_qty(self, symbol, qty):
        value = Decimal(str(qty))
        if not value.is_finite() or value <= 0:
            raise RuntimeError("full close requires a positive finite exchange quantity")
        filters = self._filters(symbol)
        if "LOT_SIZE" not in filters:
            raise RuntimeError("cannot verify full close without LOT_SIZE")
        for kind in ("LOT_SIZE", "MARKET_LOT_SIZE"):
            lot = filters.get(kind)
            if not lot:
                continue
            step = Decimal(str(lot.get("stepSize", "0")))
            minimum = Decimal(str(lot.get("minQty", "0")))
            maximum = Decimal(str(lot.get("maxQty", "0")))
            if (not all(x.is_finite() and x >= 0 for x in (step, minimum, maximum))
                    or kind == "LOT_SIZE" and step <= 0):
                raise RuntimeError(f"invalid {kind} for full close")
            if (step > 0 and value % step != 0) or value < minimum or (maximum > 0 and value > maximum):
                raise RuntimeError(f"full close violates {kind}; refusing a rounded partial close")
        return float(value)

    def _min_notional(self, symbol):
        f = self._filters(symbol)
        row = f.get("MIN_NOTIONAL") or f.get("NOTIONAL") or {}
        return float(row.get("notional", row.get("minNotional", 0)) or 0)

    def _ensure_symbol_config(self, symbol):
        symbol = str(symbol).upper()
        leverage = self.config.leverage_for(symbol)
        position = self._position(symbol)
        amount = float(position.get("positionAmt", "nan"))
        if not math.isfinite(amount):
            raise RuntimeError("Invalid position amount before leverage configuration")
        pending_entry = amount == 0 and bool(self.db.state_get(f"entry_client_order_id:{symbol}"))
        if amount != 0 or pending_entry:
            # Do not change the margin policy of an existing campaign after a
            # restart/config edit. Exits and protection remain independent.
            # V3 positionRisk does not supply leverage/marginType. Read the
            # current signed symbolConfig instead of assuming V2 fields exist.
            configuration = self.client.symbol_configuration(symbol)
            if (str(configuration.get("marginType", "")).upper() != "ISOLATED"
                    or str(configuration.get("leverage", "")) != str(leverage)):
                raise RuntimeError("FUTURES_LEVERAGE_ACTIVE_POSITION_MISMATCH: reconcile before adding exposure")
            self._leverage_confirmations[symbol] = configuration
            return
        self.client.set_margin_type(symbol, self.config.futures_margin_type)
        response = self.client.set_leverage(symbol, leverage)
        if (not isinstance(response, dict) or response.get("symbol") != symbol
                or type(response.get("leverage")) is not int or response.get("leverage") != leverage
                or not math.isfinite(float(response.get("maxNotionalValue", "nan")))
                or float(response["maxNotionalValue"]) <= 0):
            raise RuntimeError("Binance did not confirm requested Futures leverage")
        configuration = self.client.symbol_configuration(symbol)
        if (str(configuration.get("marginType", "")).upper() != "ISOLATED"
                or str(configuration.get("leverage", "")) != str(leverage)):
            raise RuntimeError("Binance symbol configuration does not match requested isolated leverage")
        self._leverage_confirmations[symbol] = configuration

    def _validate_entry_leverage(self, symbol, qty, entry, stop, side):
        from futures_leverage import validate_isolated_leverage
        position = self._position(symbol)
        amount = float(position.get("positionAmt", "nan"))
        position_entry = float(position.get("entryPrice", 0) or 0)
        if (not math.isfinite(amount) or not math.isfinite(position_entry)
                or (amount != 0 and position_entry <= 0)):
            raise RuntimeError("Invalid position before leverage risk assessment")
        if (side not in {"BUY", "SELL"} or (side == "BUY" and stop >= entry)
                or (side == "SELL" and stop <= entry)
                or (amount != 0 and ((amount > 0) != (side == "BUY")))):
            raise RuntimeError("FUTURES_LEVERAGE_DIRECTION_MISMATCH: position or structural stop conflicts with entry")
        # Add-ons must fit the combined exposure's tier, not just the new order.
        total_qty = qty + abs(amount)
        average_entry = (qty * entry + abs(amount) * position_entry) / total_qty
        confirmed = self._leverage_confirmations.get(symbol, {})
        maximum = float(confirmed.get("maxNotionalValue", "nan"))
        if not math.isfinite(maximum) or maximum <= total_qty * max(average_entry, entry, stop):
            raise RuntimeError("FUTURES_LEVERAGE_NOTIONAL_CAP: confirmed symbol limit is insufficient or unknown")
        costs = 2.0 * float(os.getenv("FEE_BUFFER_PER_SIDE_PCT", "0.001")) + float(
            os.getenv("RISK_SLIPPAGE_BUFFER_PCT", "0.0015")
        )
        validate_isolated_leverage(
            self.client.leverage_brackets(symbol), symbol, self.config.leverage_for(symbol),
            total_qty * average_entry, average_entry, stop, costs,
            float(os.getenv("FUTURES_LIQUIDATION_BUFFER_PCT", "0.01")),
        )

    def _preflight(self):
        report = {"ready": False, "environment": "TESTNET" if self.client.testnet else "LIVE", "checks": {}, "details": {}}
        if not self.client.api_key or not self.client.api_secret:
            report["checks"]["credentials"] = "FAIL"
            report["details"]["reason"] = "missing Binance API credentials"
            return report
        try:
            if not self.client.testnet and not self.config.allow_live:
                report["checks"]["live_permission"] = "FAIL"
                report["details"]["reason"] = "LIVE Futures requires ALLOW_LIVE=true"
                return report
            report["checks"]["live_permission"] = "PASS" if self.client.testnet or self.config.allow_live else "FAIL"

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
            except Exception as exc:
                report["checks"]["multi_assets_mode"] = "FAIL"
                report["details"]["reason"] = f"cannot verify Multi-Assets Mode: {type(exc).__name__}: {exc}"
                return report
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
                if self.dry_run:
                    report["checks"]["multi_assets_mode"] = "FAIL"
                    report["details"]["reason"] = "DRY_RUN will not mutate Futures account mode"
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
                if self.dry_run:
                    report["checks"]["position_mode"] = "FAIL"
                    report["details"]["reason"] = "DRY_RUN will not mutate Futures account mode"
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
            report["details"]["leverage"] = self.config.futures_leverage
            report["details"]["symbol_leverages"] = {s: self.config.leverage_for(s) for s in self.config.symbols}
            report["details"]["margin_type"] = self.config.futures_margin_type
            report["details"]["one_way_mode_required"] = self.config.futures_force_one_way
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
            f"LEVERAGE={self.config.futures_leverage} | "
            f"MARGIN={self.config.futures_margin_type} | ONE_WAY={self.config.futures_force_one_way}"
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
        if not math.isfinite(qty) or not math.isfinite(expected) or position.get("positionSide", "BOTH") != "BOTH":
            return False
        if expected <= 0:
            return abs(qty) <= 1e-12
        if campaign.side == "BUY":
            return qty > 0 and math.isclose(qty, expected, rel_tol=1e-12, abs_tol=1e-12)
        return campaign.side == "SELL" and qty < 0 and math.isclose(abs(qty), expected, rel_tol=1e-12, abs_tol=1e-12)

    def _liquidation_guard(self, campaign, position):
        liq = float(position.get("liquidationPrice", 0) or 0)
        stop = float(campaign.current_stop_price or 0)
        entry = float(position.get("entryPrice", 0) or campaign.average_entry_price or 0)
        if not all(math.isfinite(value) and value > 0 for value in (liq, stop, entry)):
            return False
        buffer = max(0.0, float(os.getenv("FUTURES_LIQUIDATION_BUFFER_PCT", "0.01")))
        if campaign.side == "BUY":
            return liq < stop and ((stop - liq) / stop) >= buffer
        return liq > stop and ((liq - stop) / stop) >= buffer

    def _fail_safe_flatten(self, campaign, reason):
        """Flatten when the structural stop cannot safely sit before liquidation."""
        symbol = campaign.symbol
        # The exit revalidates exact ownership at the execution boundary. Latching
        # RECONCILE_REQUIRED before it would block the very reduce-only close.
        try:
            return self._exit_market(campaign, reason)
        finally:
            # The known position may be flat, but the protection/liquidation fault
            # still requires reconciliation before accepting another entry.
            self._set_state(symbol, "RECONCILE_REQUIRED")

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

    def _futures_pre_submit_checks(self, intent):
        """Final exchange-state checks immediately before a Futures mutation."""
        if not intent.campaign_id:
            return

        campaign = self.engine.load_campaign(intent.campaign_id)
        if campaign is None:
            raise RuntimeError(f"{intent.symbol}: campaign {intent.campaign_id} not found")
        if campaign.state == CampaignState.RECONCILE_REQUIRED and intent.purpose != "CAMPAIGN_FLAT_CLEANUP":
            raise RuntimeError(f"{intent.symbol}: campaign requires reconciliation")
        if campaign.symbol != intent.symbol or campaign.side != intent.side and intent.purpose.upper() in {
            "CAMPAIGN_ENTRY", "CAMPAIGN_ADD_ON"
        }:
            raise RuntimeError(f"{intent.symbol}: order/campaign direction mismatch")

        position = self._position(intent.symbol)
        pos_qty = abs(self._signed_position_qty(position))
        purpose = intent.purpose.upper()

        if purpose == "CAMPAIGN_FLAT_CLEANUP":
            if not math.isfinite(pos_qty) or pos_qty != 0:
                raise RuntimeError("campaign cleanup requires an exchange-confirmed flat position")
            return

        if purpose == "CAMPAIGN_ENTRY":
            if campaign.state != CampaignState.ENTRY_PENDING:
                raise RuntimeError(
                    f"{intent.symbol}: entry mutation from invalid campaign state {campaign.state.value}"
                )
            if pos_qty > 0:
                raise RuntimeError(
                    f"{intent.symbol}: campaign entry blocked because a Futures position already exists"
                )
            if intent.side == "BUY" and not self.config.allow_long:
                raise RuntimeError("LONG entries are disabled by configuration")
            if intent.side == "SELL" and not self.config.allow_short:
                raise RuntimeError("SHORT entries are disabled by configuration")
            pending = campaign.tags.get("pending_signal")
            price_row = self.client.ticker_price(intent.symbol)
            current_price = float(price_row.get("price", 0) or 0)
            reason = _pending_entry_preflight_reason(
                pending, intent, current_price, int(time.time() * 1000)
            )
            if reason:
                raise RuntimeError(f"{intent.symbol}: entry preflight blocked: {reason}")
        elif purpose == "CAMPAIGN_ADD_ON":
            if campaign.state != CampaignState.ADD_ON_PENDING:
                raise RuntimeError(
                    f"{intent.symbol}: add-on mutation from invalid campaign state {campaign.state.value}"
                )
            if not self._position_matches(campaign, position):
                raise RuntimeError(
                    f"{intent.symbol}: add-on blocked because exchange position no longer matches campaign"
                )
        elif purpose in {"CAMPAIGN_PROTECTION", "CAMPAIGN_TRAIL"}:
            if pos_qty <= 0 or not self._position_matches(campaign, position):
                raise RuntimeError(
                    f"{intent.symbol}: protection mutation requires an exact active campaign position"
                )
            if not self._liquidation_guard(campaign, position):
                raise RuntimeError(
                    f"{intent.symbol}: liquidation safety buffer is violated"
                )
        elif purpose == "CAMPAIGN_EXIT":
            if pos_qty <= 0 or not self._position_matches(campaign, position):
                raise RuntimeError(
                    f"{intent.symbol}: exit requires the exact active campaign position"
                )

    def _submit(self, intent, fn, checks=None):
        if self.dry_run:
            raise RuntimeError(
                f"DRY_RUN=true blocks Futures order mutation: {intent.purpose}"
            )
        if not self.client.testnet and not self.config.allow_live:
            raise RuntimeError("LIVE Futures order mutation requires ALLOW_LIVE=true")

        def combined_checks(snapshot):
            self._futures_pre_submit_checks(intent)
            if checks is not None:
                checks(snapshot)

        result = self.execution_barrier.execute(
            intent,
            fn,
            pre_submit_checks=combined_checks,
        )
        if not result.accepted:
            raise RuntimeError(f"Futures execution blocked: {result.reason}")
        if result.reason:
            self._set_state(intent.symbol, "RECONCILE_REQUIRED")
            raise RuntimeError(result.reason)
        return result.response

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
        # Keep the actual first entry aligned with Android: 0.20% equity by
        # default, never above the unified 0.25% per-entry ceiling.
        return max(0.0, min(
            float(self.config.initial_entry_risk_pct),
            float(self.config.risk_per_trade_pct),
            0.0025,
        ))

    def _next_add_risk_pct(self, campaign):
        # Add-ons consume the same persisted campaign/portfolio risk budget;
        # this is a ceiling, not a mandatory reverse-pyramid sizing formula.
        cap = max(0.0, min(
            float(self.config.campaign_risk_limit_pct),
            float(self.config.max_total_risk_pct),
            0.006,
        ))
        equity = max(self._equity(), 1e-12)
        used = float(campaign.open_risk_quote or 0.0) + float(campaign.pending_risk_quote or 0.0)
        remaining = max(0.0, cap * equity - used)
        # Even when the legacy campaign engine asks for an add, keep its risk
        # bounded by one per-entry limit and the remaining campaign allocation.
        return min(remaining / equity, float(self.config.risk_per_trade_pct), 0.0025)

    def _size_from_risk(self, symbol, trigger, stop, risk_pct):
        equity = self._equity()
        if not all(math.isfinite(value) for value in (equity, trigger, stop, risk_pct)):
            raise RuntimeError("non-finite equity/risk/price inputs")
        if risk_pct > self.config.risk_per_trade_pct + 1e-12:
            raise RuntimeError("per-entry risk cap exceeded")
        risk_quote = equity * max(0.0, float(risk_pct))
        if equity <= 0 or risk_quote <= 0 or trigger <= 0 or stop <= 0:
            raise RuntimeError("invalid equity/risk/price inputs")
        distance_fraction = abs(trigger - stop) / trigger
        if distance_fraction <= 0:
            raise RuntimeError("zero stop distance")
        fee_side = float(os.getenv("FEE_BUFFER_PER_SIDE_PCT", "0.001"))
        slippage_buffer = float(os.getenv("RISK_SLIPPAGE_BUFFER_PCT", "0.0015"))
        if not all(math.isfinite(value) and value >= 0 for value in (fee_side, slippage_buffer)):
            raise RuntimeError("invalid fee/slippage risk assumptions")
        fee_buffer = 2.0 * fee_side
        effective_loss_fraction = distance_fraction + fee_buffer + slippage_buffer
        leverage = self.config.leverage_for(symbol)
        max_margin_fraction = max(
            0.01,
            min(0.90, float(os.getenv("FUTURES_MAX_MARGIN_FRACTION", "0.25"))),
        )
        available_margin = self._available_margin()
        if not math.isfinite(available_margin) or available_margin <= 0:
            raise RuntimeError("Fresh available Futures margin is invalid or exhausted")
        max_notional = min(equity * max_margin_fraction, available_margin * 0.95) * leverage
        notional = min(risk_quote / max(effective_loss_fraction, 1e-12), max_notional)
        qty = self._normalize_qty(symbol, notional / trigger)
        if not math.isfinite(qty) or qty <= 0:
            raise RuntimeError("sized quantity below Futures minimum")
        if self._min_notional(symbol) and qty * trigger < self._min_notional(symbol):
            raise RuntimeError("sized notional below Futures minimum")
        # Rounding down must also reduce the reported margin reservation.
        return qty, risk_quote, qty * trigger

    def _arm_entry(self, candidate: FuturesCandidate):
        if self.dry_run:
            self.db.log_event("INFO", "futures_dry_run_entry_blocked", "DRY_RUN=true; conditional entry not submitted", {"symbol": candidate.symbol})
            return {"state": "DRY_RUN_BLOCKED", "symbol": candidate.symbol}
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
            and _requires_directional_htf_confirmation(signal.signal_type)
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

        equity = self._equity()
        initial_risk_pct = min(
            self._initial_risk_pct(),
            self._portfolio_available_risk_pct(equity),
        )
        if initial_risk_pct <= 0:
            raise RuntimeError("portfolio risk budget exhausted")
        qty, risk_quote, notional = self._size_from_risk(
            symbol, trigger, stop, initial_risk_pct
        )
        self._validate_entry_leverage(symbol, qty, trigger, stop, signal.side)
        campaign = self.engine.create_campaign(signal, initial_risk_pct=initial_risk_pct)
        campaign.initial_stop_price = stop
        campaign.current_stop_price = stop
        campaign.tags["signal_bar_time_ms"] = int(signal.signal_bar_time_ms)
        campaign.tags["last_signal_time_ms"] = int(signal.signal_bar_time_ms)
        campaign.tags["directional_context_at_entry"] = dict(candidate.directional_context)
        campaign.tags["scanner_score_at_entry"] = float(candidate.score)
        campaign.pending_risk_quote = risk_quote
        campaign.capital_reserved_quote = notional / self.config.leverage_for(symbol)
        campaign.tags["futures_leverage"] = self.config.leverage_for(symbol)
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
        if self.dry_run:
            self.db.log_event("INFO", "futures_dry_run_add_on_blocked", "DRY_RUN=true; conditional add-on not submitted", {"symbol": campaign.symbol})
            return {"state": "DRY_RUN_BLOCKED", "symbol": campaign.symbol}
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
        equity = self._equity()
        add_risk_pct = min(
            self._next_add_risk_pct(campaign),
            self._portfolio_available_risk_pct(equity),
        )
        if add_risk_pct <= 0:
            raise RuntimeError("portfolio risk budget exhausted for add-on")
        qty, risk_quote, notional = self._size_from_risk(
            signal.symbol,
            trigger,
            stop,
            add_risk_pct,
        )
        self._validate_entry_leverage(signal.symbol, qty, trigger, stop, signal.side)
        campaign.tags["pending_order_quantity"] = qty
        campaign.tags["pending_order_trigger"] = trigger
        campaign.pending_risk_quote = risk_quote
        campaign.capital_reserved_quote = notional / self.config.leverage_for(signal.symbol)
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
        qty = abs(float(campaign.position_qty))
        if not math.isfinite(qty) or qty <= 0:
            raise RuntimeError("cannot protect empty position")
        side = "SELL" if campaign.side == "BUY" else "BUY"
        cid = self._client_id("STOP")
        # Ownership must survive a crash/lost response between POST and its reply.
        campaign.tags["protective_order_client_id"] = cid
        self.db.save_campaign_order(PendingOrderRecord(
            order_id="", client_order_id=cid, symbol=symbol, side=side,
            order_type="STOP_MARKET", purpose="PROTECTION", status="PENDING",
            stop_price=float(campaign.current_stop_price), quantity=qty,
            campaign_id=campaign.campaign_id, signal_id=campaign.current_signal_id,
        ))
        self.db.save_campaign(campaign)
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
                stop_price=self.client.decimal_format(campaign.current_stop_price),
                new_client_order_id=cid,
                close_position=True,
                working_type="MARK_PRICE",
            ),
            lambda _snapshot: None,
        )
        result = self.client.get_order(symbol, order_id=result.get("orderId"), conditional=True)
        if not self._protection_matches(campaign, result):
            raise RuntimeError("exchange did not confirm the exact campaign protective stop")
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
        qty = abs(float(campaign.position_qty))
        if not math.isfinite(qty) or qty <= 0:
            raise RuntimeError("cannot replace protection for empty position")
        side = "SELL" if campaign.side == "BUY" else "BUY"
        cid = self._client_id("STOP")
        campaign.tags["pending_protection_client_id"] = cid
        campaign.tags["pending_protection_price"] = proposed
        self.db.save_campaign_order(PendingOrderRecord(
            order_id="", client_order_id=cid, symbol=symbol, side=side,
            order_type="STOP_MARKET", purpose="PROTECTION", status="PENDING",
            stop_price=proposed, quantity=qty, campaign_id=campaign.campaign_id,
            signal_id=campaign.current_signal_id,
        ))
        self.db.save_campaign(campaign)
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
                stop_price=self.client.decimal_format(proposed),
                new_client_order_id=cid,
                close_position=True,
                working_type="MARK_PRICE",
            ),
            lambda _snapshot: None,
        )
        result = self.client.get_order(symbol, order_id=result.get("orderId"), conditional=True)
        if not self._protection_matches(campaign, result):
            raise RuntimeError("replacement protection was not confirmed; retaining old stop")
        new_order_id = str(result.get("orderId", ""))
        if not new_order_id:
            self._set_state(symbol, "RECONCILE_REQUIRED")
            raise RuntimeError(f"{symbol}: new protective order has no orderId")
        # Persist the new stop before touching the old one. Recovery can then
        # prove ownership even if the process crashes during replacement.
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
        old_order_id = str(campaign.tags.get("protective_order_id", "") or "")
        campaign.current_stop_price = proposed
        campaign.structural_stop_source = "3_5_BAR_STRUCTURE"
        campaign.tags["protective_order_id"] = new_order_id
        campaign.tags["protective_order_client_id"] = cid
        campaign.tags.pop("pending_protection_client_id", None)
        campaign.tags.pop("pending_protection_price", None)
        self.db.save_campaign(campaign)

        if old_order_id and old_order_id != "0" and old_order_id != new_order_id:
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
                campaign.reconciliation_state = "REQUIRED"
                campaign.health = "RED"
                self.db.save_campaign(campaign)
                raise
        return True


    def _exit_market(self, campaign, reason):
        """Durable reduce-only market exit; protection is cancelled only after flat."""
        symbol = campaign.symbol
        existing_exit = str(campaign.tags.get("pending_exit_client_id", "") or "")
        if campaign.state in {CampaignState.EXIT_SIGNALLED, CampaignState.EXIT_PENDING} and existing_exit:
            raise RuntimeError(f"{symbol}: exit already pending ({existing_exit})")

        position = self._position(symbol)
        pos_qty = abs(self._signed_position_qty(position))
        if pos_qty <= 0:
            try:
                orders = self.client.all_orders(symbol, limit=1000)
            except Exception as exc:
                campaign.mark_reconcile_required(
                    f"position already flat but exit evidence cannot be read: {exc}"
                )
                self.db.save_campaign(campaign)
                self._set_state(symbol, "RECONCILE_REQUIRED")
                raise RuntimeError(
                    f"{symbol}: cannot verify bot-owned exit while position is flat"
                ) from exc
            if self._finalize_confirmed_exchange_exit(campaign, orders):
                campaign.exit_reason = reason
                self.db.save_campaign(campaign)
                self._set_state(symbol, "FLAT")
                return {"state": "CLOSED", "symbol": symbol, "reason": reason}
            campaign.mark_reconcile_required(
                "position is flat without attributable campaign exit"
            )
            self.db.save_campaign(campaign)
            self._set_state(symbol, "RECONCILE_REQUIRED")
            raise RuntimeError(
                f"{symbol}: position is flat without attributable campaign exit"
            )

        qty = self._full_market_close_qty(symbol, pos_qty)

        if campaign.state != CampaignState.EXIT_SIGNALLED:
            campaign.transition(
                CampaignState.EXIT_SIGNALLED,
                reason=f"exit signalled: {reason}",
            )

        side = "SELL" if campaign.side == "BUY" else "BUY"
        cid = self._client_id("EXIT")
        campaign.tags["pending_exit_client_id"] = cid
        campaign.tags["pending_exit_reason"] = reason
        campaign.tags["pending_exit_quantity"] = qty
        campaign.next_action = "SUBMIT_EXIT"
        campaign.transition(
            CampaignState.EXIT_PENDING,
            reason="reduce-only market exit admitted",
        )
        self.db.state_set(f"exit_client_order_id:{symbol}", cid)
        self.db.save_campaign_order(
            PendingOrderRecord(
                order_id="",
                client_order_id=cid,
                symbol=symbol,
                side=side,
                order_type="MARKET",
                purpose="CAMPAIGN_EXIT",
                status="INTENT_PENDING",
                quantity=qty,
                signal_id=campaign.current_signal_id,
                campaign_id=campaign.campaign_id,
            )
        )
        self.db.save_campaign(campaign)

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
        try:
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
            self.db.save_campaign_order(
                PendingOrderRecord(
                    order_id=str(result.get("orderId", "")),
                    client_order_id=cid,
                    symbol=symbol,
                    side=side,
                    order_type="MARKET",
                    purpose="CAMPAIGN_EXIT",
                    status=str(result.get("status", "NEW")),
                    quantity=qty,
                    signal_id=campaign.current_signal_id,
                    campaign_id=campaign.campaign_id,
                )
            )
            self.db.save_order(result)

            verify = self._position(symbol)
            remaining = abs(self._signed_position_qty(verify))
            if not math.isfinite(remaining) or remaining > 0:
                self._set_state(symbol, "RECONCILE_REQUIRED")
                raise RuntimeError(
                    f"{symbol}: Futures exit left residual position {remaining}"
                )

            self._cleanup_campaign_protection(campaign)

            position_price = float(
                result.get("avgPrice", 0) or result.get("price", 0) or 0
            )
            if position_price <= 0:
                try:
                    fills = self.client.my_trades(
                        symbol, order_id=result.get("orderId"), limit=1000
                    ) or []
                except Exception:
                    fills = []
                qty_fill = sum(float(x.get("qty", 0) or 0) for x in fills)
                quote_fill = sum(
                    float(x.get("qty", 0) or 0) * float(x.get("price", 0) or 0)
                    for x in fills
                )
                position_price = (
                    quote_fill / qty_fill if qty_fill > 0 and quote_fill > 0 else 0.0
                )
            if position_price <= 0:
                position_price = float(
                    self.client.ticker_price(symbol).get("price", 0) or 0
                )

            trade = self.db.open_trade(symbol)
            if trade is not None:
                entry = float(
                    campaign.average_entry_price or trade.get("entry_price") or 0
                )
                qty_trade = float(
                    campaign.position_qty or trade.get("quantity") or 0
                )
                pnl_pct = (
                    (
                        position_price / entry - 1.0
                        if campaign.side == "BUY"
                        else (entry - position_price) / entry
                    )
                    if entry > 0 and position_price > 0
                    else 0.0
                )
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
            campaign.tags.pop("pending_exit_client_id", None)
            campaign.tags.pop("pending_exit_order_id", None)
            self.db.save_campaign(campaign)
            self.db.state_delete(f"exit_client_order_id:{symbol}")
            self.db.state_delete(f"entry_client_order_id:{symbol}")
            self._set_state(symbol, "FLAT")
            return {
                "state": "CLOSED",
                "symbol": symbol,
                "exit_price": position_price,
                "reason": reason,
            }
        except Exception:
            self._set_state(symbol, "RECONCILE_REQUIRED")
            campaign.reconciliation_state = "REQUIRED"
            campaign.health = "RED"
            self.db.save_campaign(campaign)
            raise


    def _reconcile_pending_exits(self):
        results = []
        for symbol, cid in list(self._pending_exits()):
            try:
                row = self.db.conn.execute(
                    "SELECT campaign_id,order_id FROM campaign_orders WHERE client_order_id=? "
                    "ORDER BY id DESC LIMIT 1",
                    (cid,),
                ).fetchone()
                campaign = (
                    self.engine.load_campaign(str(row["campaign_id"]))
                    if row else None
                )
                if campaign is None:
                    for item in self.db.open_campaigns():
                        if str(item["symbol"]).upper() != symbol:
                            continue
                        candidate = self.engine.load_campaign(item["campaign_id"])
                        if candidate and candidate.tags.get("pending_exit_client_id") == cid:
                            campaign = candidate
                            break
                if campaign is None:
                    self._set_state(symbol, "RECONCILE_REQUIRED")
                    results.append({
                        "symbol": symbol,
                        "state": "RECONCILE_REQUIRED",
                        "reason": "pending exit has no campaign",
                    })
                    continue

                order = self.client.get_order(symbol, orig_client_order_id=cid)
                status = str(order.get("status", "")).upper()
                if row is None or not str(row["campaign_id"] or "") or not str(row["campaign_id"]) == str(campaign.campaign_id):
                    self._set_state(symbol, "RECONCILE_REQUIRED")
                    raise RuntimeError("pending exit durable ownership mismatch")
                durable_order_id = str(row["order_id"] or "")
                if not durable_order_id or (
                    order.get("orderId") is not None
                    and durable_order_id != str(order.get("orderId"))
                ):
                    self.db.save_campaign_order(
                        PendingOrderRecord(
                            order_id=str(order.get("orderId", "")),
                            client_order_id=cid,
                            symbol=symbol,
                            side="SELL" if campaign.side == "BUY" else "BUY",
                            order_type="MARKET",
                            purpose="CAMPAIGN_EXIT",
                            status=status,
                            quantity=float(order.get("origQty", 0) or 0),
                            signal_id=campaign.current_signal_id,
                            campaign_id=campaign.campaign_id,
                        )
                    )

                executed = float(
                    order.get("executedQty", order.get("cumQty", 0)) or 0
                )
                position = self._position(symbol)
                remaining = abs(self._signed_position_qty(position))

                if status in {"NEW", "PENDING_NEW", "PARTIALLY_FILLED"}:
                    results.append({
                        "symbol": symbol,
                        "campaign_id": campaign.campaign_id,
                        "state": "EXIT_PENDING",
                        "order_status": status,
                        "executed_quantity": executed,
                        "remaining_position": remaining,
                    })
                    continue

                if status == "FILLED":
                    if not math.isfinite(remaining) or remaining > 0:
                        self._set_state(symbol, "RECONCILE_REQUIRED")
                        campaign.mark_reconcile_required(
                            "exit order filled but position remains"
                        )
                        self.db.save_campaign(campaign)
                        results.append({
                            "symbol": symbol,
                            "state": "RECONCILE_REQUIRED",
                            "reason": "exit filled but residual position remains",
                        })
                        continue
                    if self._finalize_confirmed_exchange_exit(
                        campaign, [order]
                    ):
                        self.db.state_delete(f"exit_client_order_id:{symbol}")
                        campaign.tags.pop("pending_exit_client_id", None)
                        campaign.tags.pop("pending_exit_order_id", None)
                        self.db.save_campaign(campaign)
                        continue
                    campaign.mark_reconcile_required(
                        "filled exit cannot be durably attributed"
                    )
                    self.db.save_campaign(campaign)
                    self._set_state(symbol, "RECONCILE_REQUIRED")
                    continue

                if status in {"CANCELED", "EXPIRED", "REJECTED"}:
                    if remaining > max(
                        float(os.getenv("MIN_RECOVERY_QTY", "0.000001")), 0.0
                    ):
                        if campaign.state == CampaignState.EXIT_PENDING:
                            campaign.transition(
                                CampaignState.TREND_ACTIVE,
                                reason=f"exit order {status.lower()} without fill",
                            )
                        campaign.tags.pop("pending_exit_client_id", None)
                        campaign.tags.pop("pending_exit_order_id", None)
                        self.db.state_delete(f"exit_client_order_id:{symbol}")
                        self._set_state(symbol, "OPEN")
                        self.db.save_campaign(campaign)
                        results.append({
                            "symbol": symbol,
                            "campaign_id": campaign.campaign_id,
                            "state": "TREND_ACTIVE",
                            "order_status": status,
                        })
                    else:
                        campaign.mark_reconcile_required(
                            f"exit order {status.lower()} while position is flat"
                        )
                        self.db.save_campaign(campaign)
                        self._set_state(symbol, "RECONCILE_REQUIRED")
                    continue

                campaign.mark_reconcile_required(
                    f"unknown exit order state {status!r}"
                )
                self.db.save_campaign(campaign)
                self._set_state(symbol, "RECONCILE_REQUIRED")
            except Exception as exc:
                self._set_state(symbol, "RECONCILE_REQUIRED")
                results.append({
                    "symbol": symbol,
                    "state": "RECONCILE_REQUIRED",
                    "error": str(exc),
                })
        return results


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

                order = self.client.get_order(symbol, orig_client_order_id=cid, conditional=True)
                status = str(order.get("status", "")).upper()
                executed = float(order.get("executedQty", order.get("cumQty", 0)) or 0)

                pending = campaign.tags.get("pending_signal") or {}
                signal_payload = pending.get("signal") if isinstance(pending, dict) else {}
                expires_at_ms = int(
                    (signal_payload or {}).get("expires_at_ms", 0) or 0
                )
                if (
                    status in {"NEW", "PENDING_NEW"}
                    and executed <= 0
                    and expires_at_ms > 0
                    and int(time.time() * 1000) >= expires_at_ms
                ):
                    order_id = order.get("orderId")
                    if order_id is None:
                        self._set_pending_signal_state(campaign, SignalState.RECONCILE_REQUIRED)
                        self._set_state(symbol, "RECONCILE_REQUIRED")
                        raise RuntimeError("expired pending order has no exchange orderId")
                    try:
                        self._cancel(symbol, order_id, "CAMPAIGN_SIGNAL_EXPIRED")
                        order = self.client.get_order(symbol, orig_client_order_id=cid, conditional=True)
                        status = str(order.get("status", "")).upper()
                        executed = float(order.get("executedQty", 0) or 0)
                        if status not in {"CANCELED", "EXPIRED", "EXPIRED_IN_MATCH", "FILLED"}:
                            raise RuntimeError("expired entry cancellation has no terminal exchange confirmation")
                    except Exception as exc:
                        self._set_pending_signal_state(campaign, SignalState.RECONCILE_REQUIRED)
                        self._set_state(symbol, "RECONCILE_REQUIRED")
                        raise RuntimeError(
                            f"{symbol}: expired conditional order cancellation ambiguous: {exc}"
                        ) from exc
                    if executed <= 0:
                        self._set_pending_signal_state(campaign, SignalState.EXPIRED)
                        self.db.set_campaign_signal_state(
                            campaign.current_signal_id, SignalState.EXPIRED.value
                        )
                        campaign.pending_risk_quote = 0.0
                        campaign.capital_reserved_quote = 0.0
                        campaign.tags.pop("pending_order_id", None)
                        self.db.state_delete(f"entry_client_order_id:{symbol}")
                        campaign.state = (
                            CampaignState.TREND_ACTIVE
                            if campaign.position_qty > 0
                            else CampaignState.CLOSED
                        )
                        self._set_state(symbol, "OPEN" if campaign.position_qty > 0 else "FLAT")
                        self.db.save_campaign(campaign)
                        results.append({
                            "symbol": symbol,
                            "campaign_id": campaign.campaign_id,
                            "state": campaign.state.value,
                            "reason": "SIGNAL_EXPIRED",
                            "order_status": "CANCELLED",
                        })
                        continue
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
                            self._cancel(symbol, order.get("orderId"), "CAMPAIGN_PARTIAL_REMAINDER_CANCEL")
                            order = self.client.get_order(symbol, orig_client_order_id=cid, conditional=True)
                            status = str(order.get("status", "")).upper()
                            if status not in {"CANCELED", "EXPIRED", "FILLED", "EXPIRED_IN_MATCH"}:
                                raise RuntimeError("partial entry remainder is still active/unknown after cancel")
                            executed = float(order.get("executedQty", 0) or 0)
                            avg = float(order.get("avgPrice", 0) or 0)
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
                        if actual_delta <= 0 or not math.isclose(actual_delta, expected_delta, rel_tol=1e-12, abs_tol=1e-12):
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
                        try:
                            protective_id = str(campaign.tags.get("protective_order_id", "") or "")
                            if protective_id and protective_id != "0":
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
                        except Exception as exc:
                            result = self._fail_safe_flatten(
                                campaign, "PROTECTION_FAILURE_AFTER_ADD_ON"
                            )
                            results.append({
                                "symbol": symbol,
                                "campaign_id": campaign.campaign_id,
                                "state": campaign.state.value,
                                "reason": "PROTECTION_FAILURE_AFTER_ADD_ON",
                                "error": str(exc),
                                "flatten": result,
                            })
                            continue
                        trade = self.db.open_trade(symbol)
                        if trade is not None:
                            self.db.update_trade_position(
                                trade["id"],
                                campaign.position_qty,
                                campaign.average_entry_price,
                            )
                        if not self._liquidation_guard(campaign, position):
                            result = self._fail_safe_flatten(
                                campaign, "LIQUIDATION_GUARD_ADD_ON"
                            )
                            results.append({
                                "symbol": symbol,
                                "campaign_id": campaign.campaign_id,
                                "state": campaign.state.value,
                                "reason": "LIQUIDATION_GUARD_ADD_ON",
                                "flatten": result,
                            })
                            continue
                        self.db.set_campaign_signal_state(signal_id, SignalState.FILLED.value)
                        self._set_state(symbol, "OPEN")
                        campaign.transition(CampaignState.TREND_ACTIVE, reason="add-on filled and fully reprotected")
                    else:
                        if not math.isclose(pos_qty, float(executed), rel_tol=1e-12, abs_tol=1e-12):
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
                            result = self._fail_safe_flatten(
                                campaign, "LIQUIDATION_GUARD_ENTRY"
                            )
                            results.append({
                                "symbol": symbol,
                                "campaign_id": campaign.campaign_id,
                                "state": campaign.state.value,
                                "reason": "LIQUIDATION_GUARD_ENTRY",
                                "flatten": result,
                            })
                            continue
                        try:
                            self._protect(campaign)
                        except Exception as exc:
                            result = self._fail_safe_flatten(
                                campaign, "PROTECTION_FAILURE_AFTER_ENTRY"
                            )
                            results.append({
                                "symbol": symbol,
                                "campaign_id": campaign.campaign_id,
                                "state": campaign.state.value,
                                "reason": "PROTECTION_FAILURE_AFTER_ENTRY",
                                "error": str(exc),
                                "flatten": result,
                            })
                            continue
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

        self._cleanup_campaign_protection(campaign)

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
                else ((entry - exit_price) / entry)
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

    def _cleanup_campaign_protection(self, campaign):
        """Remove even a lost-response stop, using its durable pre-submit owner."""
        owned_ids = {str(row["client_order_id"]) for row in self.db.conn.execute(
            "SELECT client_order_id FROM campaign_orders WHERE campaign_id=? AND purpose IN ('PROTECTION','CAMPAIGN_TRAIL')",
            (campaign.campaign_id,),
        ).fetchall() if row["client_order_id"]}
        for order in self.client.open_orders(campaign.symbol):
            if str(order.get("clientOrderId", "")) not in owned_ids:
                continue
            intent = OrderIntent.new(
                campaign.symbol, "SELL", "CANCEL", {}, purpose="CAMPAIGN_FLAT_CLEANUP",
                campaign_id=campaign.campaign_id, client_order_id=self._client_id("CANCEL"),
            )
            self._submit(intent, lambda order=order: self.client.cancel_order(campaign.symbol, order_id=order["orderId"]))
        if any(str(order.get("clientOrderId", "")) in owned_ids for order in self.client.open_orders(campaign.symbol)):
            raise RuntimeError("campaign protective cleanup is not exchange-confirmed")

    def _protection_matches(self, campaign, order):
        """Require exact durable ownership, trigger, direction and full coverage."""
        if (str(order.get("symbol", "")).upper() != campaign.symbol
                or order.get("positionSide", "BOTH") != "BOTH"
                or str(order.get("side", "")).upper() != ("SELL" if campaign.side == "BUY" else "BUY")
                or str(order.get("type", "")).upper() != "STOP_MARKET"
                or str(order.get("status", "")).upper() != "NEW"):
            return False
        cid = str(order.get("clientOrderId", ""))
        expected = {
            str(campaign.tags.get("protective_order_client_id", "")): campaign.current_stop_price,
            str(campaign.tags.get("pending_protection_client_id", "")): campaign.tags.get("pending_protection_price", 0),
        }
        if not cid or cid not in expected:
            return False
        owner = self.db.conn.execute(
            "SELECT campaign_id,purpose FROM campaign_orders WHERE client_order_id=? ORDER BY id DESC LIMIT 1",
            (cid,),
        ).fetchone()
        if not owner or str(owner["campaign_id"]) != campaign.campaign_id or str(owner["purpose"]).upper() not in {"PROTECTION", "CAMPAIGN_TRAIL"}:
            return False
        try:
            actual_stop = Decimal(str(order.get("stopPrice", "0")))
            expected_stop = Decimal(str(expected[cid]))
            if not actual_stop.is_finite() or actual_stop <= 0 or actual_stop != expected_stop:
                return False
            if str(order.get("priceProtect", False)).lower() == "true":
                return False
            if str(order.get("closePosition", False)).lower() == "true":
                return True
            quantity = Decimal(str(order.get("origQty", "0")))
            return (str(order.get("reduceOnly", False)).lower() == "true"
                    and quantity.is_finite() and quantity >= Decimal(str(campaign.position_qty)))
        except (ValueError, ArithmeticError):
            return False

    def recover(self):
        results = self._reconcile_pending()
        exit_results = self._reconcile_pending_exits()
        results.extend(exit_results)
        unresolved = self.unresolved_symbols()

        for row in self.db.open_campaigns():
            campaign = self.engine.load_campaign(row["campaign_id"])
            if campaign is None or campaign.position_qty <= 0:
                continue
            if campaign.state in {CampaignState.EXIT_SIGNALLED, CampaignState.EXIT_PENDING}:
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
                    self._fail_safe_flatten(
                        campaign, "LIQUIDATION_GUARD_RECOVERY"
                    )
                    continue
                orders = self.client.open_orders(symbol)
                managed = [
                    o for o in orders
                    if self._protection_matches(campaign, o)
                ]
                unexpected_stops = [o for o in orders
                                    if str(o.get("type", "")).upper() in {"STOP", "STOP_MARKET", "STOP_LOSS"}
                                    and o not in managed]
                if unexpected_stops:
                    raise RuntimeError("unverified/mismatched protective stop; refusing adoption or duplicate protection")
                if len(managed) == 0:
                    self._protect(campaign)
                elif len(managed) > 1:
                    self._set_state(symbol, "RECONCILE_REQUIRED")
                    continue
                else:
                    campaign.tags["protective_order_id"] = str(managed[0].get("orderId", ""))
                    campaign.tags["protective_order_client_id"] = str(managed[0].get("clientOrderId", ""))
                    campaign.current_stop_price = float(managed[0].get("stopPrice", campaign.current_stop_price) or campaign.current_stop_price)
                    campaign.tags.pop("pending_protection_client_id", None)
                    campaign.tags.pop("pending_protection_price", None)
                campaign.reconciliation_state = "CLEAN"
                campaign.health = "GREEN"
                self.db.save_campaign(campaign)
                self._set_state(symbol, "OPEN")
            except Exception as exc:
                self._set_state(symbol, "RECONCILE_REQUIRED")
                self.db.log_event("ERROR", "futures_recovery_error", str(exc), {"symbol": symbol})
        return {
            "ok": not self.unresolved_symbols()
            and not self._pending_entries()
            and not self._pending_exits(),
            "results": results,
            "open_positions": len(self.open_positions()),
            "unresolved_symbols": self.unresolved_symbols(),
            "pending_entries": self._pending_entries(),
        }

    def _trail(self, campaign):
        if self.dry_run:
            return {"state": campaign.state.value, "current_stop": campaign.current_stop_price, "dry_run": True}
        symbol = campaign.symbol
        frame = fetch_klines(self.client, symbol, campaign.execution_timeframe, limit=80)
        if len(frame) < 20:
            return {"state": campaign.state.value, "action": "WAIT_HISTORY"}
        closed = frame.iloc[:-1].copy() if len(frame) > 1 else frame
        window = max(3, min(5, int(os.getenv("CAMPAIGN_TRAIL_BARS", "5"))))
        tick = self._tick(symbol)
        try:
            current_price = float(self.client.ticker_price(symbol)["price"])

            # NTD exits must not leak into the TC2 Three Wise Men profile.
            # They are edition-specific rules, not universal Williams filters.
            # TC2 retains its structural campaign trailing and opposite/exhaustion
            # handling below.
            profile = os.getenv(
                "WILLIAMS_SIGNAL_PROFILE", "TC2_THREE_WISE_MEN"
            ).strip().upper()
            ntd_profile = profile == "NTD_FIVE_DIMENSIONS"

            if ntd_profile and {"high", "low", "close"}.issubset(closed.columns) and len(closed) >= 40:
                indicator_frame = calculate_indicators(closed, canonical_indicator_config_from_env())
                last = indicator_frame.iloc[-1]
                close_price = float(last.get("close", 0.0) or 0.0)
                teeth_price = float(last.get("teeth_current", last.get("teeth_shifted", 0.0)) or 0.0)
                if min(close_price, teeth_price) > 0:
                    # NTD uses a close-based stop at the red Teeth line:
                    # intrabar penetration is ignored, but any completed close
                    # beyond the line exits. It is not restricted to a fresh
                    # close-to-close crossing.
                    beyond_teeth = (
                        campaign.side == "BUY" and close_price < teeth_price
                    ) or (
                        campaign.side == "SELL" and close_price > teeth_price
                    )
                    if beyond_teeth:
                        return self._exit_market(campaign, "TEETH_CLOSE_EXIT")

            # NTD's five-price-bar trailing is activated only in the NTD profile.
            # The underlying campaign structural trailing remains available to TC2.
            tags = getattr(campaign, "tags", None)
            if ntd_profile and isinstance(tags, dict) and {"open", "close", "high", "low"}.issubset(closed.columns):
                last_five = closed.tail(5)
                if len(last_five) == 5 and not tags.get("ntd_five_colour_trail_active", False):
                    green_five = bool((last_five["close"] > last_five["open"]).all())
                    red_five = bool((last_five["close"] < last_five["open"]).all())
                    if (campaign.side == "BUY" and green_five) or (campaign.side == "SELL" and red_five):
                        tags["ntd_five_colour_trail_active"] = True
                        tags["ntd_five_colour_trail_started_at_ms"] = int(time.time() * 1000)
                        if hasattr(self, "db") and hasattr(campaign, "campaign_id"):
                            self.db.save_campaign(campaign)

            ntd_trail_active = bool(
                ntd_profile and isinstance(tags, dict) and tags.get("ntd_five_colour_trail_active", False)
            )
            if campaign.side == "BUY":
                recent = [float(x) for x in closed["low"].tail(window) if float(x) > 0]
                proposed = min(recent) - tick if recent else 0.0
                if ntd_trail_active and not closed.empty:
                    latest_low = float(closed.iloc[-1]["low"] or 0.0)
                    if latest_low > 0:
                        proposed = max(proposed, latest_low - tick)
                if proposed > campaign.current_stop_price and proposed < current_price:
                    self._replace_protection(campaign, proposed)
            else:
                recent = [float(x) for x in closed["high"].tail(window) if float(x) > 0]
                proposed = max(recent) + tick if recent else 0.0
                if ntd_trail_active and not closed.empty:
                    latest_high = float(closed.iloc[-1]["high"] or 0.0)
                    if latest_high > 0:
                        proposed = min(proposed, latest_high + tick)
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
                if signal.role == SignalRole.ADD_ON:
                    continue
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
        if self.preflight_report is None:
            self.setup()
        elif not self.recovered:
            self.recover()
            self.recovered = True

        self._reconcile_pending()
        # DRY_RUN suppresses order mutations, not market-data collection.
        # Always scan first so Testnet diagnostics can prove the configured
        # universe and strategy pipeline are operational without placing orders.
        candidates = self.scanner.scan()
        scan_status = getattr(self.scanner, "last_scan_status", {})
        if scan_status.get("state") != "COMPLETE":
            self.db.log_event(
                "ERROR",
                "futures_scanner_blocked",
                str(scan_status.get("reason") or "FUTURES_SCANNER_NOT_COMPLETE"),
                scan_status,
            )
            return []
        if self.dry_run:
            self.db.log_event(
                "INFO",
                "futures_dry_run_cycle",
                "DRY_RUN=true; scanner completed; no strategy orders or exits submitted",
                scan_status,
            )
            return candidates
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
            # TC2 campaign management admits later valid same-direction Wise-Men
            # signals as additions. A reversal add may be disabled explicitly as
            # a risk overlay, but must not be silently excluded by default.
            allow_reversal = _env_bool("CAMPAIGN_ALLOW_REVERSAL_ADD", True)
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
            if candidate.signal.role != SignalRole.ENTRY:
                continue
            if len(self.open_positions()) >= self.max_open_positions:
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
        if self.preflight_report is None:
            self.setup()
        if self.dry_run:
            return {"sold": False, "symbol": symbol, "reason": "DRY_RUN=true; manual exit blocked"}
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
