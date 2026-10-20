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
from functools import wraps
import os
import time
import uuid
import math
import json
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
from williams_decision_trace import build_decision_trace
from williams_signal_lifecycle import SignalJournal,signal_facts
from williams_profiles import extract_profile_signals,addition_signal_time_allowed,observe_edition_signals
from williams_execution_policy import EXECUTION_PROFILE,new_exposure_blocker,execution_policy_snapshot,structural_fractal,h1_signal_expiry,closed_h1_history_blocker,strategy_market_close_allowed,market_exit_blocker
from williams_course_campaign import course_active_context,course_next_stage,TARGETS as COURSE_TARGETS
from williams_edition_rules import TC2,NTD,COURSE,UNIFIED,PROFILES,profile_allows_source,unified_direction_conflict,market_on_close_reason,course_airbag_stop,course_two_fractal_stop,ntd_management_plan
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
from williams_wave_hierarchy import (
    FRAME_MS, REQUIRED_FRAMES, WaveHierarchy, build_wave_hierarchy,
    correction_entry_context, correction_price_allowed, correction_bounds_valid, wave_bars_from_dataframe,
    suggested_analytical_frames,
)
from williams_exit_policy import zone_trailing_activation, plan_williams_stop
from williams_scenario_lifecycle import ScenarioSignal, scenario_opposition


def closed_strategy_frame(frame, interval, now_ms):
    """Use exchange close times, including the final row if it really is closed."""
    import pandas as pd
    if "close_time" in frame.columns:
        stamps = pd.to_datetime(frame["close_time"], utc=True, errors="coerce")
        return frame.loc[stamps <= pd.Timestamp(now_ms,unit="ms",tz="UTC")].copy()
    if "open_time_ms" in frame.columns and interval in FRAME_MS:
        stamps=pd.to_numeric(frame["open_time_ms"],errors="coerce")
        return frame.loc[stamps.notna() & (stamps>0) & (stamps+FRAME_MS[interval]<=now_ms)].copy()
    if isinstance(frame.index,pd.DatetimeIndex) and interval in FRAME_MS:
        closed = frame.index.asi8 // 1_000_000 + FRAME_MS[interval] <= now_ms
        return frame.loc[closed].copy()
    # Legacy offline fixtures without exchange timestamps are never accepted
    # by the wave hierarchy. Keep their established forming-last-row contract.
    return frame.iloc[:-1].copy() if len(frame)>1 else frame.copy()

def serialized_campaign_operation(method):
    """Keep journal changes and exchange outcomes in one account-wide operation."""
    @wraps(method)
    def locked(self, *args, **kwargs):
        with self.execution_barrier.context_cache.execution_lock:
            try:
                result=method(self, *args, **kwargs)
            except Exception as failure:
                self._record_execution_decision(method.__name__,args,None,failure)
                raise
            self._record_execution_decision(method.__name__,args,result,None)
            return result
    return locked



def _env_bool(name: str, default: bool = False) -> bool:
    """Parse the same conventional boolean values used by TradingConfig."""
    raw = os.getenv(name, str(default)).strip().lower()
    return raw in {"1", "true", "yes", "on"}


def _signal_profile_entry_block_reason(profile: str) -> str:
    profile = str(profile or "").strip().upper()
    if profile not in PROFILES:
        return "WILLIAMS_SIGNAL_PROFILE must be one of " + ", ".join(PROFILES)
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
    trade_mode: str = "TREND"
    correction_context: dict = field(default_factory=dict)
    wave_hierarchy: dict = field(default_factory=dict)

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
            "trade_mode": self.trade_mode,
            "correction_context": dict(self.correction_context),
            "wave_hierarchy": dict(self.wave_hierarchy),
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
    structural=structural_fractal(signal.get("signal_type"),signal.get("strategy_profile",TC2))
    if structural and expires_at==0:
        closed_at=int((signal.get("setup_context") or {}).get("h1_closed_at_ms",0) or 0)
        if closed_at<=0 or not 0<=now-closed_at<3_600_000:
            return "pending_structural_fractal_h1_snapshot_stale_or_unverified"
    if expires_at <= 0 and not structural:
        return "pending_signal_expiry_missing"
    if expires_at>0 and now >= expires_at:
        return "pending_signal_expired"
    signal_at = int(signal.get("signal_bar_time_ms",0) or 0)
    if signal_at>0 and not structural and now-signal_at>=5*3_600_000:
        return "pending_h1_signal_exceeded_project_freshness_limit"
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
        self.decision_traces: dict[str,dict] = {}
        self.latest_edition_observations: dict[str,list] = {}
        self.wave_reports: dict[str, WaveHierarchy] = {}
        # Uncoupled edition-specific NTD evidence, not executable TC2 signals.
        self.latest_ntd_research: dict[str, dict] = {}
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
            closed = closed_strategy_frame(frame,frame_name,int(time.time()*1000)+int(getattr(self.client,"time_offset_ms",0)))
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

    def wave_hierarchy(self, symbol: str, h1_frame=None, *, refresh=False) -> WaveHierarchy:
        now = int(time.time()*1000) + int(getattr(self.client, "time_offset_ms", 0))
        cutoff = now // FRAME_MS["15m"] * FRAME_MS["15m"]
        previous = self.wave_reports.get(symbol)
        if previous is not None and previous.as_of == cutoff and not refresh:
            return previous
        frames, failures = {}, {}
        for frame_name in REQUIRED_FRAMES:
            try:
                frame = h1_frame if frame_name == "1h" and h1_frame is not None else fetch_klines(
                    self.client, symbol, frame_name, limit=240)
                frames[frame_name] = wave_bars_from_dataframe(frame)
            except (ValueError, TypeError, KeyError, OverflowError) as exc:
                failures[frame_name] = str(exc)
            except Exception as exc:
                failures[frame_name] = type(exc).__name__ + ": " + str(exc)
        report = build_wave_hierarchy(frames, cutoff)
        for recommended in suggested_analytical_frames(report):
            if recommended in frames:
                continue
            try:
                frames[recommended] = wave_bars_from_dataframe(fetch_klines(self.client,symbol,recommended,limit=240))
            except Exception as exc:
                failures[recommended] = type(exc).__name__ + ": " + str(exc)
        report = build_wave_hierarchy(frames,cutoff)
        report.frame_errors.update(failures)
        self.wave_reports[symbol] = report
        return report

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
        closed = closed_strategy_frame(frame,self.interval,int(time.time()*1000)+int(getattr(self.client,"time_offset_ms",0)))
        ind = calculate_indicators(closed, self.config)
        # Do not mix NTD fifth-dimension entries into TC2 exchange order gate.
        # A diagnostic error must not abort existing position protection.
        try:
            from williams_ntd_research import ntd_five_dimensions_research
            self.latest_ntd_research[symbol] = ntd_five_dimensions_research(
                symbol, ind, self.interval, tick)
        except Exception as exc:
            self.latest_ntd_research[symbol] = {
                "mode":"NTD_FIVE_DIMENSIONS_RESEARCH_ONLY",
                "can_place_orders":False,"reason":"NTD_RESEARCH_DIAGNOSTIC_ERROR",
                "error_type":type(exc).__name__,
            }
        wave_snapshot = self.wave_engine.build_snapshot(
            self.interval,
            closed,
            degree="EXECUTION",
        )
        point_zero_confirmed = bool(wave_snapshot.point_zero_confirmed)
        point_zero_score = int(wave_snapshot.point_zero_score)

        profile = EXECUTION_PROFILE
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
            long_specs=extract_profile_signals(symbol,ind,profile=profile,side="LONG",timeframe=self.interval,tick_size=tick)
            short_specs=extract_profile_signals(symbol,ind,profile=profile,side="SHORT",timeframe=self.interval,tick_size=tick)
        observed,observations=observe_edition_signals(symbol,ind,timeframe=self.interval,tick_size=tick)
        self.latest_edition_observations[symbol]=observations
        raw_signals=long_specs+short_specs+[s for s in observed if s.strategy_profile!=EXECUTION_PROFILE]
        trace_bars=wave_bars_from_dataframe(ind) if {"open","high","low","close","volume"}.issubset(ind.columns) else []
        hierarchy=self.wave_hierarchy(symbol,frame)
        def last_value(name):
            col=next((n for n in (name+"_current",name+"_shifted",name) if n in ind),None)
            return float(ind[col].iloc[-1]) if col else float("nan")
        self.decision_traces[symbol]=build_decision_trace(symbol,profile,trace_bars,int(time.time()*1000),
            {name:last_value(name) for name in ("ao","ac","jaw","teeth","lips")},raw_signals,hierarchy.to_dict())
        self.decision_traces[symbol]["edition_observations"]=observations
        failed={o["profile"]:o for o in observations if o["state"]=="ANALYSIS_FAILED"}
        for book in self.decision_traces[symbol]["books"]:
            if book["profile"] in failed:
                book.update(state="ANALYSIS_FAILED",error_type=failed[book["profile"]]["error_type"])
        observer=getattr(self,"signal_observer",None)
        if observer:
            try:observer(symbol,signal_facts(raw_signals,trace_bars,FRAME_MS[self.interval]))
            except Exception:pass  # Diagnostics cannot alter trading decisions.
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

        if self.interval=="1h":
            history_error=closed_h1_history_blocker(trace_bars,int(time.time()*1000)+int(getattr(self.client,"time_offset_ms",0)))
            if history_error:
                self.last_signal_profile_error=history_error
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
        hierarchy = self.wave_hierarchy(symbol, frame)
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
            # The earliest still-unconsumed presenting Wise Man initiates a
            # campaign (TC2); each extractor already rejects consumed triggers.
            signal = min(initial_pool or signal_pool, key=lambda s: (s.signal_bar_time_ms, s.created_at_ms))
            correction = correction_entry_context(hierarchy, side, signal.signal_bar_time_ms, signal.signal_type.value)
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
                    trade_mode="CORRECTION" if correction["allowed"] else "TREND",
                    correction_context=correction,
                    wave_hierarchy=hierarchy.to_dict(),
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
        profile = os.getenv("WILLIAMS_SIGNAL_PROFILE",UNIFIED).strip().upper()
        if profile not in PROFILES:
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
            "decision_traces":dict(self.decision_traces),
            "execution_policy":execution_policy_snapshot(),
            "edition_observations":dict(self.latest_edition_observations),
            "wave_hierarchy": {s: r.to_dict() for s,r in self.wave_reports.items()},
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
        self.scanner.signal_observer=lambda symbol,facts: SignalJournal(self.db).observe(symbol,facts,
            int(time.time()*1000)+int(getattr(self.client,"time_offset_ms",0)),self.config.entry_time_policy)
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
        if not all(math.isfinite(v) for v in (step,minimum,maximum)) or step<=0 or minimum<0 or maximum<0:
            raise RuntimeError("Invalid Futures LOT_SIZE quantity grid")
        market=f.get("MARKET_LOT_SIZE") or {}
        market_step=Decimal(str(market.get("stepSize",0) or 0))
        if market_step>0:
            lot_step=Decimal(str(step))
            scale=10**max(0,-lot_step.as_tuple().exponent,-market_step.as_tuple().exponent)
            step=float(Decimal(math.lcm(int(lot_step*scale),int(market_step*scale)))/Decimal(scale))
            minimum=max(minimum,float(market.get("minQty",0) or 0))
            market_max=float(market.get("maxQty",0) or 0)
            if market_max>0:maximum=min(maximum,market_max) if maximum>0 else market_max
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
        try:
            entry = float(position["entryPrice"])
            expected_entry = float(campaign.average_entry_price)
            if not (math.isfinite(entry) and math.isfinite(expected_entry) and entry > 0 and
                    math.isclose(entry, expected_entry, rel_tol=1e-8, abs_tol=1e-8)):
                return False
        except (KeyError, TypeError, ValueError):
            return False
        if campaign.side == "BUY":
            return qty > 0 and math.isclose(qty, expected, rel_tol=1e-12, abs_tol=1e-12)
        return campaign.side == "SELL" and qty < 0 and math.isclose(abs(qty), expected, rel_tol=1e-12, abs_tol=1e-12)

    def _liquidation_guard(self, campaign, position):
        return self._liquidation_status(campaign, position) == "PASS"

    def _liquidation_status(self, campaign, position, stop=None):
        from futures_leverage import liquidation_protection_status
        return liquidation_protection_status(campaign.side,
            campaign.current_stop_price if stop is None else stop,
            position.get("liquidationPrice"), os.getenv("FUTURES_LIQUIDATION_BUFFER_PCT", "0.01"))

    def _record_liquidation_status(self, campaign, status):
        campaign.tags["liquidation_guard_status"] = status
        campaign.tags["liquidation_guard_audited_at_ms"] = int(time.time() * 1000)
        if status == "UNKNOWN":
            campaign.reconciliation_state = "REQUIRED"
            campaign.health = "RED"
        self.db.save_campaign(campaign)
        self._set_state(campaign.symbol, "RECONCILE_REQUIRED" if status == "UNKNOWN" else "OPEN")

    def _fail_safe_flatten(self, campaign, reason):
        """Flatten when the structural stop cannot safely sit before liquidation."""
        symbol = campaign.symbol
        # The exit revalidates exact ownership at the execution boundary. Latching
        # RECONCILE_REQUIRED before it would block the very reduce-only close.
        try:
            if reason.startswith("LIQUIDATION_GUARD") and self._liquidation_status(campaign, self._position(symbol)) != "UNSAFE":
                raise RuntimeError("Liquidation breach is no longer confirmed; automatic exit blocked")
            return self._exit_market(campaign, reason, risk_emergency=True)
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

    def _require_entry_time(self):
        now=int(time.time()*1000)+int(getattr(self.client,"time_offset_ms",0))
        if not self.config.entry_time_policy.allows(now):
            raise RuntimeError("ENTRY_TIME_POLICY_BLOCKED: "+str(self.config.entry_time_policy.to_dict()))

    def _require_new_risk_controls(self):
        for flag in ("kill_switch_latched", "entry_admission_paused"):
            if str(self.db.state_get(flag, "false")).strip().lower() not in {"false", "0", ""}:
                raise RuntimeError(f"New Futures exposure blocked: {flag}")

    def _futures_pre_submit_checks(self, intent):
        """Final exchange-state checks immediately before a Futures mutation."""
        if not intent.campaign_id:
            return

        campaign = self.engine.load_campaign(intent.campaign_id)
        if campaign is None:
            raise RuntimeError(f"{intent.symbol}: campaign {intent.campaign_id} not found")
        safety_management = intent.purpose in {"CAMPAIGN_FLAT_CLEANUP", "CAMPAIGN_CANCEL_ENTRY", "CAMPAIGN_EXIT", "CAMPAIGN_PROTECTION", "CAMPAIGN_TRAIL"}
        if campaign.state == CampaignState.RECONCILE_REQUIRED and not safety_management:
            raise RuntimeError(f"{intent.symbol}: campaign requires reconciliation")
        if campaign.symbol != intent.symbol or campaign.side != intent.side and intent.purpose.upper() in {
            "CAMPAIGN_ENTRY", "CAMPAIGN_ADD_ON"
        }:
            raise RuntimeError(f"{intent.symbol}: order/campaign direction mismatch")

        purpose = intent.purpose.upper()
        if purpose in {"CAMPAIGN_ENTRY","CAMPAIGN_ADD_ON"}:self._require_entry_time()
        if purpose == "CAMPAIGN_CANCEL_ENTRY":
            if campaign.tags.get("pending_order_client_id") != intent.client_order_id:
                raise RuntimeError("Entry cancellation lacks durable campaign ownership")
            order = self.client.get_order(intent.symbol, orig_client_order_id=intent.client_order_id,
                conditional=campaign.tags.get("pending_order_type","STOP_MARKET")!="MARKET")
            if (order.get("clientOrderId") != intent.client_order_id or order.get("symbol") != intent.symbol
                    or order.get("side") != campaign.side):
                raise RuntimeError("Entry cancellation exchange identity does not match campaign")
            return

        position = self._position(intent.symbol)
        pos_qty = abs(self._signed_position_qty(position))

        if purpose == "CAMPAIGN_FLAT_CLEANUP":
            if not math.isfinite(pos_qty) or pos_qty != 0:
                raise RuntimeError("campaign cleanup requires an exchange-confirmed flat position")
            return

        if purpose == "CAMPAIGN_ENTRY":
            if new_exposure_blocker(getattr(campaign,"tags",{}).get("strategy_profile",TC2)):
                raise RuntimeError("WILLIAMS_SOURCE_OBSERVATION_ONLY")
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
            if campaign.tags.get("trade_mode") == "CORRECTION":
                checked = correction_entry_context(self.scanner.wave_hierarchy(intent.symbol, refresh=True),
                    intent.side, int(campaign.tags.get("signal_bar_time_ms", 0)), campaign.current_signal_type)
                prior = campaign.tags.get("correction_context", {})
                mark = float(self.client.ticker_price(intent.symbol).get("price", 0) or 0)
                if (not checked["allowed"] or checked.get("parent_scenario_id") != prior.get("parent_scenario_id")
                        or not correction_price_allowed(checked, mark)):
                    raise RuntimeError("CORRECTION_PARENT_INVALIDATED_BEFORE_SUBMIT")
            pending = campaign.tags.get("pending_signal")
            price_row = self.client.ticker_price(intent.symbol)
            current_price = float(price_row.get("price", 0) or 0)
            if intent.order_type=="MARKET":
                payload=(pending or {}).get("signal",{})
                parent=campaign.tags.get("ntd_reentry_owner_id","")
                if (campaign.tags.get("strategy_profile")!=NTD or payload.get("signal_type")!="ZONE_ADD_ON" or
                    not parent or self.db.state_get("ntd_reentry_consumed:"+parent)!=campaign.campaign_id):
                    raise RuntimeError("NTD market reentry has no owned profitable cycle")
                reason=market_on_close_reason(int(payload.get("signal_bar_time_ms",0)),FRAME_MS.get(payload.get("timeframe"),0),
                    int(time.time()*1000)+int(getattr(self.client,"time_offset_ms",0)),float(payload.get("trigger_price",0)),current_price)
            else:
                reason = _pending_entry_preflight_reason(pending, intent, current_price, int(time.time() * 1000))
            if reason:
                raise RuntimeError(f"{intent.symbol}: entry preflight blocked: {reason}")
        elif purpose == "CAMPAIGN_ADD_ON":
            if new_exposure_blocker(getattr(campaign,"tags",{}).get("strategy_profile",TC2)):
                raise RuntimeError("WILLIAMS_SOURCE_OBSERVATION_ONLY")
            if campaign.state != CampaignState.ADD_ON_PENDING:
                raise RuntimeError(
                    f"{intent.symbol}: add-on mutation from invalid campaign state {campaign.state.value}"
                )
            if not self._position_matches(campaign, position):
                raise RuntimeError(
                    f"{intent.symbol}: add-on blocked because exchange position no longer matches campaign"
                )
            payload=(campaign.tags.get("pending_signal") or {}).get("signal") or {}
            if payload.get("strategy_profile",TC2)!=campaign.tags.get("strategy_profile",TC2):
                raise RuntimeError("Add-on edition changed before submission")
            if intent.order_type=="MARKET":
                if campaign.tags.get("strategy_profile")!=NTD or payload.get("signal_type")!="ZONE_ADD_ON" or payload.get("execution_semantics")!="MARKET_ON_CLOSE":
                    raise RuntimeError("MARKET add-on requires an owned NTD close event")
                reason=market_on_close_reason(int(payload.get("signal_bar_time_ms",0)),FRAME_MS.get(payload.get("timeframe"),0),
                    int(time.time()*1000)+int(getattr(self.client,"time_offset_ms",0)),float(payload.get("trigger_price",0)),
                    float(self.client.ticker_price(intent.symbol).get("price",0) or 0))
                if reason:raise RuntimeError(reason)
        elif purpose in {"CAMPAIGN_PROTECTION", "CAMPAIGN_TRAIL"}:
            if pos_qty <= 0 or not self._position_matches(campaign, position):
                raise RuntimeError(
                    f"{intent.symbol}: protection mutation requires an exact active campaign position"
                )
            stop = campaign.tags.get("pending_protection_price", campaign.current_stop_price) if purpose == "CAMPAIGN_TRAIL" else campaign.current_stop_price
            if self._liquidation_status(campaign, position, stop) == "UNSAFE":
                raise RuntimeError(
                    f"{intent.symbol}: liquidation safety buffer is violated"
                )
        elif purpose == "CAMPAIGN_EXIT":
            if pos_qty <= 0 or not self._position_matches(campaign, position):
                raise RuntimeError(
                    f"{intent.symbol}: exit requires the exact active campaign position"
                )
            if str(campaign.tags.get("pending_exit_reason", "")).startswith("LIQUIDATION_GUARD") and self._liquidation_status(campaign, position) != "UNSAFE":
                raise RuntimeError("Liquidation breach is no longer confirmed before exit submission")

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

    def _audit_confirmed_futures_risk(self,campaign):
        """Measure protected exposure after fills; never mistake its plan for actual risk."""
        equity=self._equity()
        allowance=2*float(os.getenv("FEE_BUFFER_PER_SIDE_PCT","0.001"))+float(os.getenv("RISK_SLIPPAGE_BUFFER_PCT","0.0015"))
        qty=float(campaign.position_qty);entry=float(campaign.average_entry_price);stop=float(campaign.current_stop_price)
        if not all(math.isfinite(v) and v>0 for v in (equity,qty,entry,stop)):
            raise RuntimeError("ACTUAL_FUTURES_RISK_INPUTS_UNPROVEN")
        gross=qty*max(0,(entry-stop) if campaign.side=="BUY" else (stop-entry))
        measured=gross+qty*entry*allowance
        campaign.open_risk_quote=measured
        reason=""
        for fill in campaign.tags.get("verified_entry_risk_components",[]):
            values=[float(fill[k]) for k in ("quantity","price","stop","equity")]
            if not all(math.isfinite(v) and v>0 for v in values):reason="ACTUAL_ENTRY_RISK_UNPROVEN";break
            q,p,s,e=values
            loss=q*(abs(p-s)+p*allowance)
            if loss>e*.0025+1e-9:reason="ACTUAL_ENTRY_RISK_EXCEEDS_0_25_PERCENT";break
        others=sum(float(row["open_risk_quote"] or 0)+float(row["pending_risk_quote"] or 0)
            for row in self.db.open_campaigns() if row["campaign_id"]!=campaign.campaign_id)
        if not reason and measured+others+float(campaign.pending_risk_quote or 0)>equity*self.config.max_total_risk_pct+1e-9:
            reason="ACTUAL_PORTFOLIO_RISK_EXCEEDS_0_60_PERCENT"
        campaign.tags["actual_risk_audit"]={"status":"BLOCKED" if reason else "PASS","reason":reason,
            "measured_quote":measured,"equity":equity,"checked_at":int(time.time()*1000)}
        if reason:
            campaign.reconciliation_state="REQUIRED";campaign.health="RED"
            self._set_state(campaign.symbol,"RECONCILE_REQUIRED")
        self.db.save_campaign(campaign)
        return not reason

    def decision_trace_snapshot(self):
        now=int(time.time()*1000)
        out={}
        for symbol in self.config.symbols:
            trace=dict(getattr(self.scanner,"decision_traces",{}).get(symbol,{"symbol":symbol,"state":"NOT_ANALYSED"}))
            try:trace["signal_history"]=SignalJournal(self.db).view(symbol,now)
            except Exception:trace["signal_history"]={"state":"HISTORY_UNAVAILABLE","can_place_orders":False}
            out[symbol]=trace
        return out

    def _record_execution_decision(self,stage,args,result,failure):
        if stage not in {"_arm_entry","_arm_add_on","_trail","_exit_market"} or not args:return
        try:
            symbol=getattr(args[0],"symbol","")
            if not symbol:return
            event={"stage":stage,"observed_at_ms":int(time.time()*1000),"outcome":result,
                "error_type":type(failure).__name__ if failure else None,
                "blocker":str(failure)[:1000] if failure else None,"execution_proof":"SEE_DURABLE_CAMPAIGN_AND_EXCHANGE_LEDGER"}
            import re
            if event["blocker"]:
                event["blocker"]=re.sub(r"(?i)(api[_-]?key|api[_-]?secret|signature|X-MBX-APIKEY)([\s=:]+)[^&\s]+",r"\1\2[REDACTED]",event["blocker"])
            self.db.log_event("WARNING" if failure else "INFO","decision_trace_execution",stage,{"symbol":symbol,"decision":event})
            signal=getattr(args[0],"signal",None) if stage=="_arm_entry" else args[1] if stage=="_arm_add_on" and len(args)>1 else None
            if signal is not None:
                admitted=not failure and isinstance(result,dict) and bool(result.get("client_order_id"))
                SignalJournal(self.db).decision(symbol,stage,event["blocker"] or str(result),event["observed_at_ms"],signal.signal_id,admitted)
        except Exception:
            pass  # Observation failure never turns a financial mutation into a retry.

    def _ntd_reentry_source(self,signal):
        row=self.db.conn.execute("SELECT campaign_id FROM campaigns WHERE symbol=? ORDER BY created_at DESC,rowid DESC LIMIT 1",(signal.symbol,)).fetchone()
        previous=self.engine.load_campaign(row["campaign_id"]) if row else None
        if (previous is None or previous.state!=CampaignState.CLOSED or previous.side!=signal.side or
            previous.tags.get("strategy_profile")!=NTD or self.db.state_get("ntd_reentry_consumed:"+previous.campaign_id)):
            return None
        proof=previous.tags.get("verified_trade_result",{})
        if (proof.get("source")!="BINANCE_USER_TRADES" or not proof.get("fees_known") or
            not math.isfinite(float(proof.get("net_pnl",float("nan")))) or float(proof["net_pnl"])<=0 or
            signal.signal_bar_time_ms<=int(proof.get("closed_at",0))):return None
        return previous

    def _record_ntd_trade_result(self,campaign):
        if campaign.tags.get("strategy_profile")!=NTD:return False
        try:
            owned={}
            for row in self.db.conn.execute("SELECT * FROM campaign_orders WHERE campaign_id=? ORDER BY id",(campaign.campaign_id,)):
                if row["order_type"] in {"MARKET","STOP_MARKET"}:owned[row["client_order_id"]]=dict(row)
            seen=set();opened=closed=net=0.0
            for cid,row in owned.items():
                if not cid:continue
                order=self.client.get_order(campaign.symbol,orig_client_order_id=cid,conditional=row["order_type"]=="STOP_MARKET")
                qty=float(order.get("executedQty",0) or 0)
                if qty<=0:continue
                fills=self.client.my_trades(campaign.symbol,order_id=order.get("orderId"),limit=1000)
                if len(fills)>=1000 or not math.isclose(sum(float(f["qty"]) for f in fills),qty,rel_tol=1e-12,abs_tol=1e-12):return False
                for fill in fills:
                    key=str(fill["id"])
                    if key in seen:continue
                    seen.add(key)
                    amount=float(fill["qty"]);fee=float(fill["commission"]);pnl=float(fill["realizedPnl"])
                    if not all(math.isfinite(v) for v in (amount,fee,pnl)) or amount<=0 or fee<0 or fill["commissionAsset"]!="USDT":return False
                    if order["side"]==campaign.side:opened+=amount
                    else:closed+=amount
                    net+=pnl-fee
            if len(seen)<2 or opened<=0 or not math.isclose(opened,closed,rel_tol=1e-12,abs_tol=1e-12):return False
            campaign.tags["verified_trade_result"]={"source":"BINANCE_USER_TRADES","fees_known":True,"net_pnl":net,
                "closed_at":int(time.time()*1000),"fill_count":len(seen)}
            self.db.save_campaign(campaign);return True
        except Exception:
            return False  # Incomplete accounting never grants a re-entry cycle.

    @serialized_campaign_operation
    def _arm_entry(self, candidate: FuturesCandidate):
        self._require_new_risk_controls()
        self._require_entry_time()
        if self.dry_run:
            self.db.log_event("INFO", "futures_dry_run_entry_blocked", "DRY_RUN=true; conditional entry not submitted", {"symbol": candidate.symbol})
            return {"state": "DRY_RUN_BLOCKED", "symbol": candidate.symbol}
        signal = candidate.signal
        if new_exposure_blocker(signal.strategy_profile):
            raise RuntimeError(new_exposure_blocker(signal.strategy_profile))
        selected_profile=EXECUTION_PROFILE
        if not profile_allows_source(selected_profile,signal.strategy_profile):
            raise RuntimeError("Williams signal source changed before Futures entry")
        reentry=self._ntd_reentry_source(signal) if signal.strategy_profile==NTD else None
        market_close=bool(reentry and signal.signal_type==SignalType.ZONE_ADD_ON and signal.execution_semantics=="MARKET_ON_CLOSE")
        if signal.execution_semantics != "STOP_ENTRY" and not market_close:
            raise RuntimeError("Unsupported signal execution semantics: " + signal.execution_semantics)
        if reentry:signal=__import__("dataclasses").replace(signal,role=SignalRole.ENTRY)
        if signal.role!=SignalRole.ENTRY or _signal_profile_entry_block_reason(signal.strategy_profile):
            raise RuntimeError("Invalid edition-specific initiating signal")
        if signal.strategy_profile==NTD and signal.signal_type not in {SignalType.FRACTAL,SignalType.AO_SAUCER,SignalType.AO_ZERO_LINE,SignalType.AO_TWIN_PEAKS,SignalType.AC_SIGNAL,SignalType.ZONE_ADD_ON,SignalType.BALANCE_LINE}:
            raise RuntimeError("Signal does not belong to NTD")
        if signal.strategy_profile==NTD and signal.signal_type!=SignalType.FRACTAL and not reentry:
            raise RuntimeError("NTD must initiate with its first external fractal")
        if signal.strategy_profile==NTD and not reentry and not signal.setup_context.get("ntd_initial",{}).get("eligible",False):
            raise RuntimeError("NTD_SLEEP_OR_FIRST_BREAKOUT_NOT_PROVEN")
        if signal.strategy_profile==COURSE and signal.signal_type not in {SignalType.COURSE_HUMP,SignalType.COURSE_THUMB,SignalType.COURSE_SQUAT}:
            raise RuntimeError("Course requires a Course initiating formation")
        symbol = signal.symbol
        if candidate.trade_mode == "CORRECTION":
            verified = correction_entry_context(self.scanner.wave_hierarchy(symbol, refresh=True),
                candidate.side, signal.signal_bar_time_ms, signal.signal_type.value)
            if not verified["allowed"]:
                raise RuntimeError("CORRECTION_CONTEXT_INVALIDATED: " + verified["reason"])
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
        if signal.strategy_profile==COURSE:
            higher=closed_strategy_frame(fetch_klines(self.client,symbol,"6h",limit=80),"6h",
                int(time.time()*1000)+int(getattr(self.client,"time_offset_ms",0)))
            higher_bars=wave_bars_from_dataframe(higher)
            if len(higher_bars)<40 or any(b.t-a.t!=FRAME_MS["6h"] for a,b in zip(higher_bars,higher_bars[1:])):
                raise RuntimeError("COURSE_AIRBAG_HISTORY_NOT_PROVEN")
            airbag=course_airbag_stop("LONG" if signal.side=="BUY" else "SHORT",higher_bars,self._tick(symbol))
            if airbag is None:raise RuntimeError("COURSE_AIRBAG_UNAVAILABLE")
            stop=min(stop,airbag) if signal.side=="BUY" else max(stop,airbag)
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
        if market_close:
            reason=market_on_close_reason(signal.signal_bar_time_ms,FRAME_MS.get(signal.timeframe,0),
                int(time.time()*1000)+int(getattr(self.client,"time_offset_ms",0)),signal.trigger_price,current)
            if reason:raise RuntimeError(reason)
            trigger=current
        self._ensure_symbol_config(symbol)
        if not market_close and signal.side == "BUY" and current >= trigger:
            raise RuntimeError("LONG trigger already crossed")
        if not market_close and signal.side == "SELL" and current <= trigger:
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
        if reentry:
            if not self.db.try_claim_state("ntd_reentry_consumed:"+reentry.campaign_id,campaign.campaign_id):
                raise RuntimeError("NTD profit cycle was already consumed")
            campaign.tags["ntd_reentry_owner_id"]=reentry.campaign_id
        campaign.initial_stop_price = stop
        campaign.current_stop_price = stop
        campaign.tags["signal_bar_time_ms"] = int(signal.signal_bar_time_ms)
        campaign.tags["last_signal_time_ms"] = max(int(campaign.tags.get("last_signal_time_ms",0)),int(signal.signal_bar_time_ms))
        campaign.tags["directional_context_at_entry"] = dict(candidate.directional_context)
        campaign.tags["scanner_score_at_entry"] = float(candidate.score)
        campaign.tags["trade_mode"] = candidate.trade_mode
        campaign.tags["strategy_profile"] = signal.strategy_profile
        campaign.tags["setup_context"] = dict(signal.setup_context)
        if signal.strategy_profile==COURSE:
            context=course_active_context(self.scanner.wave_hierarchy(symbol),signal.side)
            if context and context["phase"] in COURSE_TARGETS:
                campaign.tags["course_plan"]={"stage":context["phase"],"origin_at":context["origin_at"],
                    "unit_quantity":qty/COURSE_TARGETS[context["phase"]],"unit_is_actual":False,"policy":"COURSE_BOOK_EXAMPLE_RATIOS_RISK_CAPPED"}
        campaign.tags["pending_order_type"] = "MARKET" if market_close else "STOP_MARKET"
        campaign.tags["correction_context"] = dict(candidate.correction_context)
        campaign.pending_risk_quote = risk_quote
        campaign.capital_reserved_quote = notional / self.config.leverage_for(symbol)
        campaign.tags["futures_leverage"] = self.config.leverage_for(symbol)
        campaign.tags["pending_order_quantity"] = qty
        campaign.tags["pending_risk_equity"]=equity
        campaign.tags["pending_order_trigger"] = trigger
        campaign.tags["last_signal_time_ms"] = max(int(campaign.tags.get("last_signal_time_ms",0)),int(signal.signal_bar_time_ms))
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
            "MARKET" if market_close else "STOP_MARKET",
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
                    "MARKET" if market_close else "STOP_MARKET",
                    quantity=self.client.decimal_format(qty),
                    stop_price=None if market_close else self.client.decimal_format(trigger),
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
                    order_type="MARKET" if market_close else "STOP_MARKET",
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
            try:SignalJournal(self.db).order(symbol,signal.signal_id,result,int(time.time()*1000))
            except Exception:pass
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

    @serialized_campaign_operation
    def _arm_add_on(self, campaign, signal):
        self._require_new_risk_controls()
        self._require_entry_time()
        if signal.symbol!=campaign.symbol or signal.timeframe!=campaign.execution_timeframe:
            raise RuntimeError("Add-on symbol/timeframe does not match the campaign")
        admitted_key=f"campaign_signal_admitted:{campaign.campaign_id}:{signal.signal_id}"
        if self.db.state_get(admitted_key,""):
            raise RuntimeError("This campaign signal was already admitted; reconcile instead of resubmitting")
        profile=campaign.tags.get("strategy_profile",TC2)
        if new_exposure_blocker(profile) or new_exposure_blocker(signal.strategy_profile):
            raise RuntimeError("WILLIAMS_SOURCE_OBSERVATION_ONLY")
        if signal.strategy_profile!=profile:
            raise RuntimeError("Add-on profile does not match the durable campaign edition")
        if profile==NTD and not addition_signal_time_allowed(signal,campaign):
            raise RuntimeError("NTD addition must be a distinct formation in the owned fractal cycle")
        market_close=(profile==NTD and signal.signal_type==SignalType.ZONE_ADD_ON and signal.execution_semantics=="MARKET_ON_CLOSE")
        if signal.execution_semantics != "STOP_ENTRY" and not market_close:
            raise RuntimeError("Unsupported signal execution semantics: " + signal.execution_semantics)
        if self.dry_run:
            self.db.log_event("INFO", "futures_dry_run_add_on_blocked", "DRY_RUN=true; conditional add-on not submitted", {"symbol": campaign.symbol})
            return {"state": "DRY_RUN_BLOCKED", "symbol": campaign.symbol}
        if signal.side != campaign.side:
            raise RuntimeError("add-on direction mismatch")
        trigger = self._normalize_price(
            signal.symbol,
            signal.trigger_price,
            upward=signal.side == "BUY",
        )
        current = float(self.client.ticker_price(signal.symbol).get("price", 0) or 0)
        if market_close:
            reason=market_on_close_reason(signal.signal_bar_time_ms,FRAME_MS.get(signal.timeframe,0),
                int(time.time()*1000)+int(getattr(self.client,"time_offset_ms",0)),signal.trigger_price,current)
            if reason:raise RuntimeError(reason)
            trigger=current
        elif (signal.side == "BUY" and current >= trigger) or (signal.side == "SELL" and current <= trigger):
            raise RuntimeError("add-on trigger already crossed")
        self._ensure_symbol_config(signal.symbol)

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
        if profile==COURSE and campaign.tags.get("course_plan"):
            plan=campaign.tags["course_plan"]
            context=course_active_context(self.scanner.wave_hierarchy(signal.symbol),signal.side,plan["origin_at"])
            next_stage=course_next_stage(plan["stage"],context["phase"],True) if context else None
            if next_stage not in COURSE_TARGETS:raise RuntimeError("COURSE_STAGE_REQUIRES_CONFIRMED_NEW_FORMATION")
            target=self._normalize_qty(signal.symbol,plan["unit_quantity"]*COURSE_TARGETS[next_stage])
            admitted=self._normalize_qty(signal.symbol,min(qty,max(0,target-campaign.position_qty)))
            if admitted<=0:raise RuntimeError("COURSE_TARGET_QUANTITY_ALREADY_REACHED")
            ratio=admitted/qty;qty=admitted;risk_quote*=ratio;notional*=ratio
            campaign.tags["course_pending_add_stage"]=next_stage
            campaign.tags["course_pending_add_target"]=target
        self._validate_entry_leverage(signal.symbol, qty, trigger, stop, signal.side)
        if not self.db.try_claim_state(admitted_key,"true"):
            raise RuntimeError("This campaign signal is already owned by another admission")
        campaign.tags["pending_order_quantity"] = qty
        campaign.tags["pending_order_stop"]=stop
        campaign.tags["pending_risk_equity"]=equity
        campaign.tags["pending_order_trigger"] = trigger
        campaign.tags["pending_order_type"] = "MARKET" if market_close else "STOP_MARKET"
        campaign.pending_risk_quote = risk_quote
        campaign.capital_reserved_quote = notional / self.config.leverage_for(signal.symbol)
        campaign.tags["pending_order_client_id"] = self._client_id("ADD")
        campaign.tags["last_signal_time_ms"] = max(int(campaign.tags.get("last_signal_time_ms",0)),int(signal.signal_bar_time_ms))
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
            "MARKET" if market_close else "STOP_MARKET",
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
                "MARKET" if market_close else "STOP_MARKET",
                quantity=self.client.decimal_format(qty),
                stop_price=None if market_close else self.client.decimal_format(trigger),
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
                order_type="MARKET" if market_close else "STOP_MARKET",
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

    @serialized_campaign_operation
    def cancel_pending_entries(self, only_symbol=None):
        """Cancel only durably owned entry intents, then resolve any racing fill."""
        errors = []
        for symbol, cid in list(self._pending_entries()):
            if only_symbol is not None and symbol != only_symbol:
                continue
            try:
                campaign = self._active_campaign(symbol)
                if campaign is None or campaign.tags.get("pending_order_client_id") != cid:
                    raise RuntimeError("Pending entry lacks a matching durable campaign")
                conditional=campaign.tags.get("pending_order_type","STOP_MARKET")!="MARKET"
                order = self.client.get_order(symbol, orig_client_order_id=cid, conditional=conditional)
                if str(order.get("status", "")).upper() not in {"FILLED", "CANCELED", "CANCELLED", "EXPIRED", "EXPIRED_IN_MATCH", "REJECTED"}:
                    intent = OrderIntent.new(symbol, campaign.side, "CANCEL", {}, client_order_id=cid,
                        purpose="CAMPAIGN_CANCEL_ENTRY", campaign_id=campaign.campaign_id)
                    self._submit(intent, lambda: self.client.cancel_order(symbol, order_id=order["orderId"]))
                confirmed = self.client.get_order(symbol, orig_client_order_id=cid, conditional=conditional)
                if str(confirmed.get("status", "")).upper() not in {"FILLED", "CANCELED", "CANCELLED", "EXPIRED", "EXPIRED_IN_MATCH", "REJECTED"}:
                    raise RuntimeError("Entry cancellation is not terminal on the exchange")
            except Exception as exc:
                self._set_state(symbol, "RECONCILE_REQUIRED")
                errors.append(f"{symbol}: {exc}")
        self._reconcile_pending()
        pending = [(symbol, cid) for symbol, cid in self._pending_entries() if only_symbol is None or symbol == only_symbol]
        return {"ok": not errors and not pending, "errors": errors, "pending_entries": len(pending)}

    @serialized_campaign_operation
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

    @serialized_campaign_operation
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


    @serialized_campaign_operation
    def _exit_market(self, campaign, reason, *, partial_quantity=None, risk_emergency=False):
        """Durable reduce-only market exit; protection is cancelled only after flat."""
        blocker = market_exit_blocker(campaign.tags.get("strategy_profile", TC2), reason,
                                     risk_emergency=risk_emergency)
        if blocker:
            raise RuntimeError(blocker)
        symbol = campaign.symbol
        # An armed addition can reopen exposure after a successful market close.
        # Cancel/reconcile it first; a racing fill changes the quantity we must close.
        if campaign.state == CampaignState.ADD_ON_PENDING:
            cancelled = self.cancel_pending_entries(symbol)
            if not cancelled["ok"]:
                raise RuntimeError(f"{symbol}: cannot exit while a pending addition is unresolved")
            campaign = self.engine.load_campaign(campaign.campaign_id)
            if campaign is None:
                raise RuntimeError("Campaign disappeared during pending-addition cancellation")
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

        is_partial=partial_quantity is not None
        if is_partial and (campaign.tags.get("strategy_profile")!=COURSE or reason!="COURSE_STAGE_TRIM"):
            raise RuntimeError("Partial reduction requires an owned Course campaign stage")
        qty = self._full_market_close_qty(symbol, float(partial_quantity) if is_partial else pos_qty)
        if is_partial and not 0<qty<pos_qty:raise RuntimeError("Course reduction must leave positive owned exposure")
        campaign.tags["pending_exit_is_course_reduction"]=is_partial
        campaign.tags["pending_exit_authority"] = ("VERIFIED_RISK_REDUCTION" if risk_emergency else
                                                  "OPERATOR_CONFIRMED" if reason == "MANUAL_EXIT" else
                                                  "EMERGENCY_KILL" if reason == "KILL_SWITCH" else
                                                  "PINNED_EDITION_RULE")

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
            if is_partial:
                confirmed=self.client.get_order(symbol,orig_client_order_id=cid)
                executed=float(confirmed.get("executedQty",0) or 0)
                if str(confirmed.get("status","")).upper()!="FILLED" or not math.isclose(executed,qty,rel_tol=1e-12,abs_tol=1e-12):
                    raise RuntimeError("Course reduction fill is not terminal and complete")
                self._accept_terminal_exit_remainder(campaign,confirmed,verify,executed,remaining)
                return {"state":campaign.state.value,"symbol":symbol,"partial_reduction":True,"remaining_quantity":remaining}
            if not math.isfinite(remaining) or remaining > 0:
                self._set_state(symbol, "RECONCILE_REQUIRED")
                raise RuntimeError(
                    f"{symbol}: Futures exit left residual position {remaining}"
                )

            result = self.client.get_order(symbol, orig_client_order_id=cid)
            if (str(result.get("status", "")).upper() != "FILLED" or
                    result.get("symbol") != symbol or result.get("side") != side or
                    result.get("clientOrderId") != cid or
                    not math.isclose(float(result.get("executedQty", "nan")), qty, rel_tol=1e-12, abs_tol=1e-12)):
                raise RuntimeError("Futures exit is not confirmed filled for the exact campaign quantity")

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
            self._record_ntd_trade_result(campaign)
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


    @serialized_campaign_operation
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
                    if durable_order_id:
                        raise RuntimeError("Pending exit exchange order id changed")
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
                if (order.get("symbol") != symbol or order.get("clientOrderId") != cid or
                        order.get("side") != ("SELL" if campaign.side == "BUY" else "BUY") or
                        not all(math.isfinite(x) for x in (executed, remaining)) or executed < 0):
                    raise RuntimeError("Pending exit identity/quantity cannot be verified")

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
                    if campaign.tags.get("pending_exit_is_course_reduction",False) and campaign.tags.get("strategy_profile")==COURSE:
                        requested=float(campaign.tags.get("pending_exit_quantity",0) or 0)
                        if not math.isclose(executed,requested,rel_tol=1e-12,abs_tol=1e-12):
                            raise RuntimeError("Course reduction executed quantity differs from its durable request")
                        self._accept_terminal_exit_remainder(campaign,order,position,executed,remaining)
                        results.append({"symbol":symbol,"state":campaign.state.value,"partial_reduction":True,"remaining_quantity":remaining})
                        continue
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

                if status in {"CANCELED", "CANCELLED", "EXPIRED", "EXPIRED_IN_MATCH", "REJECTED"}:
                    if remaining > 0:
                        self._accept_terminal_exit_remainder(campaign, order, position, executed, remaining)
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

    def _accept_terminal_exit_remainder(self, campaign, order, position, executed, remaining):
        """Attribute a partial exit before releasing its reservation or changing quantity."""
        original = float(campaign.position_qty)
        signed = self._signed_position_qty(position)
        if (not math.isfinite(original) or original <= 0 or executed > original or
                not math.isclose(original - executed, remaining, rel_tol=1e-12, abs_tol=1e-12) or
                (signed > 0) != (campaign.side == "BUY") or position.get("positionSide", "BOTH") != "BOTH"):
            raise RuntimeError("Terminal exit does not explain the exact remaining campaign position")
        partial = None
        trade = self.db.open_trade(campaign.symbol)
        if executed > 0:
            if trade is None:
                raise RuntimeError("Partial exit has no open campaign trade")
            fills = self.client.my_trades(campaign.symbol, order_id=order["orderId"], limit=1000)
            total = sum(float(fill.get("qty", "nan")) for fill in fills)
            quote = sum(float(fill.get("qty", "nan")) * float(fill.get("price", "nan")) for fill in fills)
            fees = sum(float(fill.get("commission", 0)) for fill in fills)
            if (not all(math.isfinite(x) for x in (total, quote, fees)) or quote <= 0 or
                    not math.isclose(total, executed, rel_tol=1e-12, abs_tol=1e-12) or
                    any(float(fill.get("commission", 0)) != 0 and fill.get("commissionAsset") != "USDT" for fill in fills)):
                raise RuntimeError("Partial exit fills/quote commissions are incomplete")
            entry = float(campaign.average_entry_price)
            price = quote / total
            pnl = (price - entry) * executed * (1 if campaign.side == "BUY" else -1)
            partial = dict(entry_time=trade["entry_time"], exit_time=datetime.now(timezone.utc).isoformat(),
                symbol=campaign.symbol, side=trade["side"], entry_price=entry, exit_price=price, quantity=executed,
                pnl=pnl, pnl_pct=pnl / (entry * executed), fees=fees + float(trade.get("fees") or 0) * executed / original,
                reason="PARTIAL_CAMPAIGN_EXIT:" + order["clientOrderId"])
        with self.db.transaction():
            if partial is not None:
                if self.db.conn.execute("SELECT 1 FROM trades WHERE reason=?", (partial["reason"],)).fetchone():
                    raise RuntimeError("Partial exit was already accounted; reconciliation required")
                self.db.save_trade(**partial)
                campaign.realized_pnl_quote += partial["pnl"] - partial["fees"]
                self.db.update_trade_position(trade["id"], remaining, campaign.average_entry_price)
                self.db.conn.execute("UPDATE trades SET fees=? WHERE id=?", (float(trade.get("fees") or 0) * remaining / original, trade["id"]))
            if campaign.tags.get("pending_exit_is_course_reduction",False):
                requested=float(campaign.tags.get("pending_exit_quantity",0) or 0)
                pending_stage=campaign.tags.pop("pending_course_stage",None)
                if pending_stage and math.isclose(executed,requested,rel_tol=1e-12,abs_tol=1e-12):
                    campaign.tags.setdefault("course_plan",{})["stage"]=pending_stage
                campaign.tags.pop("pending_exit_is_course_reduction",None)
            campaign.open_risk_quote *= remaining / original
            campaign.position_qty = remaining
            if campaign.state == CampaignState.EXIT_PENDING:
                campaign.transition(CampaignState.TREND_ACTIVE, reason="terminal exit remainder confirmed")
            campaign.tags.pop("pending_exit_client_id", None)
            campaign.tags.pop("pending_exit_order_id", None)
            self.db.state_delete(f"exit_client_order_id:{campaign.symbol}")
            self._set_state(campaign.symbol, "OPEN")
            self.db.save_campaign(campaign)


    @serialized_campaign_operation
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

                conditional=campaign.tags.get("pending_order_type","STOP_MARKET")!="MARKET"
                order = self.client.get_order(symbol, orig_client_order_id=cid, conditional=conditional)
                status = str(order.get("status", "")).upper()
                executed = float(order.get("executedQty", order.get("cumQty", 0)) or 0)

                pending = campaign.tags.get("pending_signal") or {}
                signal_payload = pending.get("signal") if isinstance(pending, dict) else {}
                expires_at_ms = int(
                    (signal_payload or {}).get("expires_at_ms", 0) or 0
                )
                try:SignalJournal(self.db).order(symbol,(signal_payload or {}).get("signal_id",campaign.current_signal_id),order,int(time.time()*1000))
                except Exception:pass
                signal_at_ms=int((signal_payload or {}).get("signal_bar_time_ms",0) or 0)
                if signal_at_ms>0 and not structural_fractal((signal_payload or {}).get("signal_type"),campaign.tags.get("strategy_profile",TC2)):
                    cap=signal_at_ms+5*3_600_000
                    expires_at_ms=min(expires_at_ms,cap) if expires_at_ms>0 else cap
                outside_entry_time=not self.config.entry_time_policy.allows(int(time.time()*1000)+int(getattr(self.client,"time_offset_ms",0)))
                invalid_correction=False
                invalid_edition=new_exposure_blocker(getattr(campaign,"tags",{}).get("strategy_profile",TC2)) is not None
                profile=campaign.tags.get("strategy_profile",TC2)
                if (status in {"NEW","PENDING_NEW"} and executed<=0 and not invalid_edition and conditional
                        and structural_fractal((signal_payload or {}).get("signal_type"),profile)):
                    closed=closed_strategy_frame(fetch_klines(self.client,symbol,campaign.execution_timeframe,limit=220),
                        campaign.execution_timeframe,int(time.time()*1000)+int(getattr(self.client,"time_offset_ms",0)))
                    indicators=calculate_indicators(closed,canonical_indicator_config_from_env())
                    history_error=closed_h1_history_blocker(wave_bars_from_dataframe(closed),int(time.time()*1000)+int(getattr(self.client,"time_offset_ms",0)))
                    setups=extract_profile_signals(symbol,indicators,profile=profile,
                        side="LONG" if campaign.side=="BUY" else "SHORT",timeframe=campaign.execution_timeframe,tick_size=self._tick(symbol))
                    invalid_edition=bool(history_error) or not any(s.signal_type.value==(signal_payload or {}).get("signal_type") and
                        math.isclose(s.trigger_price,float((signal_payload or {}).get("trigger_price",0)),rel_tol=1e-12) for s in setups)
                if status in {"NEW","PENDING_NEW"} and executed<=0 and campaign.tags.get("trade_mode")=="CORRECTION":
                    proof=correction_entry_context(self.scanner.wave_hierarchy(symbol),campaign.side,
                        signal_at_ms,campaign.current_signal_type)
                    prior=campaign.tags.get("correction_context",{})
                    mark=float(self.client.ticker_price(symbol).get("price",0) or 0)
                    invalid_correction=(not proof["allowed"] or proof.get("parent_scenario_id")!=prior.get("parent_scenario_id")
                                        or not correction_price_allowed(proof,mark))
                if (
                    status in {"NEW", "PENDING_NEW"}
                    and executed <= 0
                    and (outside_entry_time or invalid_correction or invalid_edition or expires_at_ms>0 and int(time.time()*1000)>=expires_at_ms)
                ):
                    cancel_reason="WILLIAMS_SOURCE_OBSERVATION_ONLY" if new_exposure_blocker(profile) else "ENTRY_TIME_POLICY_BLOCKED" if outside_entry_time else "CORRECTION_CONTEXT_INVALIDATED" if invalid_correction else "FORMATION_INVALIDATED" if invalid_edition else "SIGNAL_EXPIRED"
                    order_id = order.get("orderId")
                    if order_id is None:
                        self._set_pending_signal_state(campaign, SignalState.RECONCILE_REQUIRED)
                        self._set_state(symbol, "RECONCILE_REQUIRED")
                        raise RuntimeError("expired pending order has no exchange orderId")
                    try:
                        cancel_intent=OrderIntent.new(symbol,campaign.side,"CANCEL",{},client_order_id=cid,
                            purpose="CAMPAIGN_CANCEL_ENTRY",campaign_id=campaign.campaign_id)
                        self._submit(cancel_intent,lambda:self.client.cancel_order(symbol,order_id=order_id))
                        order = self.client.get_order(symbol, orig_client_order_id=cid, conditional=conditional)
                        status = str(order.get("status", "")).upper()
                        executed = float(order.get("executedQty", 0) or 0)
                        if status not in {"CANCELED", "EXPIRED", "EXPIRED_IN_MATCH", "FILLED"}:
                            raise RuntimeError("expired entry cancellation has no terminal exchange confirmation")
                        try:SignalJournal(self.db).order(symbol,(signal_payload or {}).get("signal_id",campaign.current_signal_id),order,int(time.time()*1000),cancel_reason)
                        except Exception:pass
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
                            "reason": cancel_reason,
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
                            order = self.client.get_order(symbol, orig_client_order_id=cid, conditional=conditional)
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

                    execution_price=avg
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
                        if self._liquidation_status(campaign, position) == "UNSAFE":
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
                        campaign.tags.setdefault("position_open_observed_at_ms", int(time.time()*1000))
                        campaign.current_stop_price = stop
                        campaign.open_risk_quote = float(campaign.pending_risk_quote or 0)
                        campaign.pending_risk_quote = 0.0
                        campaign.capital_reserved_quote = 0.0
                        campaign.transition(CampaignState.OPEN_INITIAL, reason="initial entry filled")
                        self.db.set_campaign_signal_state(signal_id, SignalState.FILLED.value)
                        self._record_trade_if_missing(campaign, order, avg, pos_qty)
                        if self._liquidation_status(campaign, position) == "UNSAFE":
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

                    components=campaign.tags.setdefault("verified_entry_risk_components",[])
                    if not any(fill["client_id"]==cid for fill in components):
                        components.append({"client_id":cid,"quantity":executed,"price":execution_price,
                            "stop":float(campaign.tags.get("pending_order_stop",campaign.initial_stop_price)),
                            "equity":float(campaign.tags.get("pending_risk_equity") or self._equity())})
                    self.db.state_delete(f"entry_client_order_id:{symbol}")
                    campaign.tags.pop("pending_order_id", None)
                    self._record_liquidation_status(campaign, self._liquidation_status(campaign, position))
                    self._audit_confirmed_futures_risk(campaign)
                    results.append({"symbol": symbol, "campaign_id": campaign.campaign_id, "state": self.state(symbol), "filled_quantity": executed})
                    continue

                if status in {"CANCELED", "CANCELLED", "EXPIRED", "EXPIRED_IN_MATCH", "REJECTED"}:
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
            if str(o.get("status", "")).upper() not in {"FILLED", "CANCELED", "CANCELLED", "EXPIRED", "EXPIRED_IN_MATCH"}:
                continue
            try:
                quantity = float(o.get("executedQty", "nan"))
            except (TypeError, ValueError, OverflowError):
                continue
            if (o.get("symbol") != campaign.symbol or
                    o.get("side") != ("SELL" if campaign.side == "BUY" else "BUY") or
                    not math.isfinite(quantity) or quantity <= 0 or
                    not math.isclose(quantity, campaign.position_qty, rel_tol=1e-12, abs_tol=1e-12)):
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
        self._record_ntd_trade_result(campaign)
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

    @serialized_campaign_operation
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
                if self._liquidation_status(campaign, position) == "UNSAFE":
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
                course_plan=campaign.tags.get("course_plan")
                if course_plan and not course_plan.get("unit_is_actual",False) and campaign.additions==0:
                    course_plan["unit_quantity"]=campaign.position_qty/COURSE_TARGETS[course_plan["stage"]]
                    course_plan["unit_is_actual"]=True
                pending_stage=campaign.tags.get("course_pending_add_stage")
                if pending_stage and math.isclose(campaign.position_qty,float(campaign.tags.get("course_pending_add_target",0)),rel_tol=1e-9,abs_tol=1e-9):
                    campaign.tags["course_plan"]["stage"]=pending_stage
                    campaign.tags.pop("course_pending_add_stage",None);campaign.tags.pop("course_pending_add_target",None)
                campaign.reconciliation_state = "CLEAN"
                campaign.health = "GREEN"
                self.db.save_campaign(campaign)
                self._record_liquidation_status(campaign, self._liquidation_status(campaign, position))
                self._audit_confirmed_futures_risk(campaign)
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
        closed = closed_strategy_frame(frame,campaign.execution_timeframe,
            int(time.time()*1000)+int(getattr(self.client,"time_offset_ms",0)))
        window = max(3, min(5, int(os.getenv("CAMPAIGN_TRAIL_BARS", "5"))))
        tick = self._tick(symbol)
        try:
            current_price = float(self.client.ticker_price(symbol)["price"])
            if not math.isfinite(current_price) or current_price <= 0:
                raise RuntimeError("Invalid market price; retain confirmed protection")

            # NTD exits must not leak into the TC2 Three Wise Men profile.
            # They are edition-specific rules, not universal Williams filters.
            # TC2 keeps structural stop ownership. Opposing hypotheses and
            # correction boundaries remain diagnostic observations.
            profile = getattr(campaign,"tags",{}).get("strategy_profile",TC2)
            ntd_profile = profile == "NTD_FIVE_DIMENSIONS"
            tags = getattr(campaign,"tags",{})
            if tags.get("trade_mode") == "CORRECTION":
                proof = tags.get("correction_context",{})
                if not correction_bounds_valid(proof):
                    raise RuntimeError("CORRECTION_PROOF_MISSING; retain exchange protection")
                if not correction_price_allowed(proof,current_price):
                    if strategy_market_close_allowed(profile):
                        return self._exit_market(campaign,"CORRECTION_PARENT_PRICE_INVALIDATED")
                    tags["tc2_correction_boundary_advisory"] = {
                        "policy": "TC2_PROTECTIVE_STOP_ONLY", "mark_price": current_price,
                        "observed_at_ms": int(time.time()*1000), "market_close_authorized": False}
                    if hasattr(self, "db"):
                        self.db.save_campaign(campaign)
            if profile==COURSE:
                plan=tags.get("course_plan")
                if plan and hasattr(self,"scanner"):
                    context=course_active_context(self.scanner.wave_hierarchy(symbol),campaign.side,plan["origin_at"])
                    stage=course_next_stage(plan["stage"],context["phase"]) if context else None
                    if stage in {"W2","W4"}:
                        target=plan["unit_quantity"]*COURSE_TARGETS[stage]
                        reduction=self._normalize_qty(symbol,max(0,campaign.position_qty-target))
                        if 0<reduction<campaign.position_qty:
                            tags["pending_course_stage"]=stage;self.db.save_campaign(campaign)
                            return self._exit_market(campaign,"COURSE_STAGE_TRIM",partial_quantity=reduction)
                higher=closed_strategy_frame(fetch_klines(self.client,symbol,"6h",limit=80),"6h",
                    int(time.time()*1000)+int(getattr(self.client,"time_offset_ms",0)))
                airbag=course_airbag_stop("LONG" if campaign.side=="BUY" else "SHORT",wave_bars_from_dataframe(higher),tick)
                structural=course_two_fractal_stop("LONG" if campaign.side=="BUY" else "SHORT",wave_bars_from_dataframe(closed),tick)
                if airbag and (current_price<=airbag if campaign.side=="BUY" else current_price>=airbag):
                    return self._exit_market(campaign,"COURSE_HIGHER_FRAME_AIRBAG_TRIGGERED")
                opposing=self._triggered_opposite_from_history(campaign,closed,tick,current_price)
                if opposing is not None:return self._exit_market(campaign,"COURSE_OPPOSITE_FORMATION_TRIGGERED")
                usable=[p for p in (airbag,structural) if p is not None and (
                    campaign.current_stop_price<p<current_price-max(2*tick,current_price*.00025) if campaign.side=="BUY"
                    else current_price+max(2*tick,current_price*.00025)<p<campaign.current_stop_price)]
                proposed=(max(usable) if campaign.side=="BUY" else min(usable)) if usable else None
                if proposed:self._replace_protection(campaign,proposed)
                return {"state":campaign.state.value,"current_stop":campaign.current_stop_price,"proposal":proposed,
                        "action":"COURSE_AIRBAG_AND_TWO_FRACTAL_STOP"}
            if not ntd_profile:
                try:
                    opposing = self._triggered_opposite_from_history(campaign,closed,tick,current_price)
                except Exception as advisory_error:
                    if profile != TC2:
                        raise
                    opposing = None
                    tags["tc2_opposition_advisory_error"] = type(advisory_error).__name__
                if opposing is not None and not strategy_market_close_allowed(profile):
                    tags["tc2_opposing_signal_advisory"] = {
                        "policy": "TC2_PROTECTIVE_STOP_ONLY",
                        "signal_id": str(getattr(opposing, "signal_id", "")),
                        "observed_at_ms": int(time.time()*1000), "market_close_authorized": False}
                    if hasattr(self, "db"):
                        self.db.save_campaign(campaign)
                if opposing is not None and strategy_market_close_allowed(profile):
                    reason = "OPPOSITE_WILLIAMS_LEVEL_TRIGGERED"
                    if hasattr(self,"scanner"):
                        report = self.scanner.wave_hierarchy(symbol)
                        ctx = correction_entry_context(report,opposing.side,opposing.signal_bar_time_ms,opposing.signal_type.value)
                        if ctx["allowed"]:
                            reason = "OPPOSITE_WILLIAMS_TRIGGER_IN_HIGHER_CORRECTION"
                    return self._exit_market(campaign,reason)
                proposal = plan_williams_stop(campaign.side,campaign.current_stop_price,
                    float(getattr(campaign,"average_entry_price",0) or 0),
                    getattr(campaign,"initial_stop_price",None),current_price,tick,
                    int(tags.get("position_open_observed_at_ms",0) or 0),wave_bars_from_dataframe(closed),
                    window=window,fee_allowance=2*float(os.getenv("FEE_BUFFER_PER_SIDE_PCT","0.001"))+
                        float(os.getenv("RISK_SLIPPAGE_BUFFER_PCT","0.0015")))
                if proposal is not None:
                    self._replace_protection(campaign,proposal["stop"])
                    if hasattr(campaign,"tags"):
                        campaign.tags["last_stop_proposal_reason"] = proposal["reason"]
                        if hasattr(self,"db"):
                            self.db.save_campaign(campaign)
                return {"state":campaign.state.value,"current_stop":campaign.current_stop_price,
                        "proposal":proposal,"action":"TRAIL" if proposal else "HOLD_NO_TIGHTER_SAFE_STOP"}

            indicator_frame=calculate_indicators(closed,canonical_indicator_config_from_env())
            def values(name):
                column=next((x for x in (name+"_current",name+"_shifted",name) if x in indicator_frame),None)
                return [float(v) for v in indicator_frame[column]] if column else [float("nan")]*len(closed)
            management=ntd_management_plan("LONG" if campaign.side=="BUY" else "SHORT",wave_bars_from_dataframe(closed),
                values("teeth"),values("lips"),values("ao"),values("ac"),tick,
                int(tags.get("position_open_observed_at_ms",0) or 0),tags.get("edition_management",{}))
            tags["edition_management"]=management["state"]
            if management["state"].get("zone_active_at"):
                tags["ntd_five_colour_trail_active"]=True
            if hasattr(self,"db") and hasattr(campaign,"campaign_id"):self.db.save_campaign(campaign)
            if management["exit_reason"]:return self._exit_market(campaign,management["exit_reason"])
            opposing=self._triggered_opposite_from_history(campaign,closed,tick,current_price)
            if opposing is not None:return self._exit_market(campaign,"NTD_OPPOSITE_SIGNAL_TRIGGERED")
            proposed=management["stop"]
            if proposed is not None:
                if (current_price<=proposed if campaign.side=="BUY" else current_price>=proposed):
                    return self._exit_market(campaign,"NTD_FIVE_ZONE_PROTECTION_CROSSED")
                if (proposed>campaign.current_stop_price if campaign.side=="BUY" else proposed<campaign.current_stop_price):
                    self._replace_protection(campaign,proposed)
        except Exception as exc:
            self._set_state(symbol, "RECONCILE_REQUIRED")
            self.db.log_event("ERROR", "futures_trailing_error", str(exc), {"symbol": symbol})
            raise
        return {"state": campaign.state.value, "current_stop": campaign.current_stop_price}

    def _triggered_opposite_from_history(self,campaign,closed,tick,mark):
        opened = int(getattr(campaign,"tags",{}).get("position_open_observed_at_ms",0) or 0)
        interval = campaign.execution_timeframe
        if opened <= 0 or interval not in FRAME_MS or len(closed)<45:
            return None
        now = int(time.time()*1000)+int(getattr(self.client,"time_offset_ms",0))
        closed=closed_strategy_frame(closed,interval,now)
        opposite = "LONG" if campaign.side=="SELL" else "SHORT"
        profile=getattr(campaign,"tags",{}).get("strategy_profile",TC2)
        # The source pinned at entry owns management even in unified scan mode.
        exit_profile=profile
        extract = extract_long_signal_specs if opposite=="LONG" else extract_short_signal_specs
        candidates={}
        for omitted in range(min(5,len(closed)-45)+1):
            prefix = closed if omitted==0 else closed.iloc[:-omitted]
            ind = calculate_indicators(prefix,canonical_indicator_config_from_env())
            signals=(extract(campaign.symbol,ind,timeframe=interval,tick_size=tick) if exit_profile==TC2 else
                extract_profile_signals(campaign.symbol,ind,profile=exit_profile,side=opposite,timeframe=interval,tick_size=tick,exit_mode=True))
            for signal in signals:
                if signal.role != SignalRole.ENTRY or (signal.strategy_profile==NTD and signal.signal_type!=SignalType.FRACTAL):
                    continue  # NTD dimensions add; exits use management or a real external opposite fractal.
                candidates[signal.signal_id]=signal
        evidence=scenario_opposition("SHORT" if campaign.side=="SELL" else "LONG",opened,
            [ScenarioSignal(s.signal_id,"LONG" if s.side=="BUY" else "SHORT",s.strategy_profile,
                s.execution_semantics,s.signal_bar_time_ms,s.trigger_price,
                s.invalidation_price if s.invalidation_price>0 else None,s.expires_at_ms or now+FRAME_MS[interval])
                for s in candidates.values()],wave_bars_from_dataframe(closed),mark,now,FRAME_MS[interval])
        if evidence is None:
            return None
        source,event=evidence
        campaign.tags["last_opposition_evidence"]={"signal_id":source.id,"direction":source.direction,
            "strategy_profile":source.profile,"event":event.to_dict()}
        return candidates[source.id]

    def manage_open_positions(self):
        """Protection and triggered exits keep running independently of scanner admission."""
        if self.dry_run:
            return
        for row in list(self.db.open_campaigns()):
            campaign = self.engine.load_campaign(row["campaign_id"])
            if campaign is None or campaign.position_qty<=0 or campaign.state not in {
                CampaignState.OPEN_INITIAL,CampaignState.TREND_ACTIVE
            }:
                continue
            try:
                self._trail(campaign)
            except Exception as exc:
                self.db.log_event("ERROR","futures_independent_management_failed",str(exc),{"symbol":campaign.symbol})

    def process(self):
        if self.preflight_report is None:
            self.setup()
        elif not self.recovered:
            self.recover()
            self.recovered = True

        # Position protection and pending exits do not depend on scanner success.
        self.recover()
        self.manage_open_positions()
        # DRY_RUN suppresses order mutations, not market-data collection.
        # Always scan first so Testnet diagnostics can prove the configured
        # universe and strategy pipeline are operational without placing orders.
        candidates = self.scanner.scan()
        for symbol,report in getattr(self.scanner,"wave_reports",{}).items():
            key = "wave_hierarchy:"+symbol
            proof = report.to_dict()
            previous = self.db.state_get(key)
            encoded = json.dumps(proof,sort_keys=True)
            if previous != encoded:
                revision_key = "wave_revisions:"+symbol
                history = json.loads(self.db.state_get(revision_key,"[]"))
                summary = {"as_of":report.as_of,"scenarios":[{"id":s.scenario_id,"status":s.status,
                    "w4_zero":s.w4_zero,"last_confirmed_leg":s.label,"active_label":s.active_label} for s in report.scenarios],"links":report.links}
                with self.db.transaction():
                    self.db.state_set(key,encoded)
                    self.db.state_set(revision_key,json.dumps((history+[summary])[-32:]))
        scan_status = getattr(self.scanner, "last_scan_status", {})
        try:
            scan_status["entry_time_policy"]=self.config.entry_time_policy.to_dict()
            scan_status["decision_traces"]=self.decision_trace_snapshot()
        except Exception:pass
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
            # manage_open_positions() evaluates causal, source-owned opposition.
            # A scanner candidate or a current quote alone is not an exit event.

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
                    if signal.strategy_profile!=campaign.tags.get("strategy_profile",TC2):
                        continue
                    if not addition_signal_time_allowed(signal,campaign):
                        continue
                    if signal.execution_semantics=="MARKET_ON_CLOSE":
                        try:
                            close_reason=market_on_close_reason(signal.signal_bar_time_ms,FRAME_MS.get(signal.timeframe,0),
                                int(time.time()*1000)+int(getattr(self.client,"time_offset_ms",0)),signal.trigger_price,
                                float(self.client.ticker_price(symbol).get("price",0) or 0))
                        except (ValueError,TypeError,RuntimeError):continue
                        if close_reason is not None:continue
                    if self.db.state_get(f"campaign_signal_admitted:{campaign.campaign_id}:{signal.signal_id}") is not None:continue
                    if signal.signal_type == SignalType.REVERSAL and not allow_reversal:
                        continue
                    eligible.append(signal)

            if eligible and symbol not in pending_symbols:
                signal = min(eligible, key=lambda s: (s.execution_semantics!="MARKET_ON_CLOSE",s.signal_bar_time_ms, s.created_at_ms))
                try:
                    result = self._arm_add_on(campaign, signal)
                    campaign.tags["last_signal_time_ms"] = max(int(campaign.tags.get("last_signal_time_ms",0)),signal.signal_bar_time_ms)
                    self.db.save_campaign(campaign)
                except Exception as exc:
                    self.db.log_event("WARNING", "futures_add_on_blocked", str(exc), {"symbol": symbol})

        active_symbols = {str(r["symbol"]).upper() for r in self.db.open_trades()}
        unified=False  # Observers cannot veto or authorize the TC2 execution pool.
        conflicts={symbol for symbol,rows in by_symbol.items()
                   if unified and unified_direction_conflict(
                       c.side for c in rows if c.signal.role==SignalRole.ENTRY)}
        for symbol in sorted(conflicts):
            self.db.log_event("WARNING","williams_unified_direction_conflict",
                "Both LONG and SHORT entries present; no exposure admitted",{"symbol":symbol})
        for candidate in candidates:
            skip_reason=("OPPOSING_ENTRIES_BEFORE_CONTEXT" if candidate.symbol in conflicts else
                "SYMBOL_RESERVED_BY_POSITION" if candidate.symbol in active_symbols else
                "POSITION_LIMIT" if len(self.open_positions())>=self.max_open_positions else
                "SYMBOL_RESERVED_BY_PENDING_ORDER" if candidate.symbol in pending_symbols or candidate.symbol in self._locks else
                "EXCHANGE_RECONCILIATION_REQUIRED" if self.unresolved_symbols() else "")
            if skip_reason:
                try:SignalJournal(self.db).decision(candidate.symbol,"PAIR_GATE",skip_reason,int(time.time()*1000),candidate.signal.signal_id)
                except Exception:pass
            if candidate.symbol in conflicts:
                continue
            if candidate.symbol in active_symbols:
                continue
            if candidate.signal.strategy_profile==NTD and self._ntd_reentry_source(candidate.signal):
                valid=[s for s in candidate.all_signals if self._ntd_reentry_source(s) and (
                    s.execution_semantics=="STOP_ENTRY" or market_on_close_reason(s.signal_bar_time_ms,FRAME_MS.get(s.timeframe,0),
                    int(time.time()*1000),s.trigger_price,float(self.client.ticker_price(s.symbol).get("price",0) or 0)) is None)]
                if valid:candidate=__import__("dataclasses").replace(candidate,signal=min(valid,key=lambda s:s.signal_bar_time_ms))
            if candidate.signal.role != SignalRole.ENTRY and not (candidate.signal.strategy_profile==NTD and self._ntd_reentry_source(candidate.signal)):
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
