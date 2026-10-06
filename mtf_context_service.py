"""Realtime 5-symbol x 4-timeframe closed-candle context service."""
from __future__ import annotations

import json
import logging
import os
import threading
import time

import pandas as pd
import websocket

from binance_client import BinanceSpotClient
from data import fetch_klines
from market_context import ContextCache, TFMarketContext
from hypothesis_engine import build_hypotheses
from strategy import calculate_indicators, config_from_env
from trading_config import TradingConfig
from wave_engine import MultiTimeframeWaveEngine

log = logging.getLogger("williams-mtf")


class MultiTimeframeContextService:
    def __init__(self, context_cache: ContextCache | None = None, db=None):
        self.config = TradingConfig.from_env()
        self.cache = context_cache or ContextCache()
        self.db = db
        self.client = BinanceSpotClient(
            os.getenv("BINANCE_API_KEY", ""),
            os.getenv("BINANCE_API_SECRET", ""),
            os.getenv("TESTNET", "true").lower() == "true",
        )
        self.frames: dict[tuple[str, str], pd.DataFrame] = {}
        self.lock = threading.RLock()
        self.stop_event = threading.Event()
        self.thread: threading.Thread | None = None
        self.bootstrap_thread: threading.Thread | None = None
        self.watchdog_thread: threading.Thread | None = None
        self.ws = None
        self.running = False
        self.last_error = None
        self.last_event_at = None
        self.last_closed_open_ms: dict[tuple[str, str], int] = {}
        self.watchdog_seconds = max(30, int(os.getenv('MTF_WATCHDOG_SECONDS', '90')))

    def configure_credentials(self, key: str, secret: str, testnet: bool = True) -> None:
        with self.lock:
            self.client = BinanceSpotClient(key.strip(), secret.strip(), bool(testnet))
            self.config = TradingConfig.from_env()
            self.last_error = None

    def _bootstrap_one(self, symbol: str, interval: str) -> None:
        df = fetch_klines(
            self.client, symbol, interval, limit=max(220, self.config.wave_lookback)
        )
        if df is None or df.empty:
            return
        if "close_time" in df.columns:
            try:
                close_time = pd.to_datetime(df["close_time"], utc=True)
                now = pd.Timestamp.now(tz="UTC")
                if pd.notna(close_time.iloc[-1]) and close_time.iloc[-1] > now:
                    df = df.iloc[:-1].copy()
            except Exception:
                pass
        frame = df.tail(300).copy()
        with self.lock:
            self.frames[(symbol, interval)] = frame
            try:
                self.last_closed_open_ms[(symbol, interval)] = int(frame.index[-1].timestamp() * 1000)
            except Exception:
                pass
        self._publish(symbol, interval, frame)

    @staticmethod
    def _atr(df: pd.DataFrame, period: int) -> float:
        if len(df) < period + 1:
            return 0.0
        prev = df["close"].shift(1)
        tr = pd.concat(
            [
                df["high"] - df["low"],
                (df["high"] - prev).abs(),
                (df["low"] - prev).abs(),
            ],
            axis=1,
        ).max(axis=1)
        value = tr.rolling(period).mean().iloc[-1]
        return float(value) if pd.notna(value) else 0.0

    def _publish(self, symbol: str, interval: str, frame: pd.DataFrame) -> None:
        if len(frame) < min(100, self.config.wave_min_bars):
            return
        closed = frame.tail(self.config.wave_lookback).copy()
        ind = calculate_indicators(closed, config_from_env())
        last = ind.iloc[-1]
        prev = ind.iloc[-2] if len(ind) > 1 else last
        atr = self._atr(closed, self.config.atr_period)
        price = float(last["close"])
        jaw = float(last.get("jaw_shifted", last.get("jaw", 0.0)) or 0.0)
        teeth = float(last.get("teeth_shifted", last.get("teeth", 0.0)) or 0.0)
        lips = float(last.get("lips_shifted", last.get("lips", 0.0)) or 0.0)
        prev_jaw = float(prev.get("jaw_shifted", prev.get("jaw", jaw)) or jaw)
        prev_price = float(prev.get("close", price))
        price_move_atr = (price - prev_price) / atr if atr > 0 else 0.0
        jaw_move_atr = (jaw - prev_jaw) / atr if atr > 0 else 0.0
        direction_sign = 1.0 if price >= jaw else -1.0
        angulation = direction_sign * (price_move_atr - jaw_move_atr)
        distance = abs(price - jaw) / atr if atr > 0 else 0.0
        bullish = bool(last.get("bullish_alligator", False))
        bearish = bool(last.get("bearish_alligator", False))
        wave_label = "?"
        wave_phase = "UNKNOWN"
        wave_score = 0.0
        exhaustion = 0.0
        invalidation = 0.0
        wave_conf = 0.0
        hypothesis_summary = None

        try:
            report = MultiTimeframeWaveEngine(
                self.client,
                base_interval=interval,
                intervals=(interval,),
                lookback=self.config.wave_lookback,
                min_bars=self.config.wave_min_bars,
                include_micro=False,
            ).analyse(symbol, cache={interval: closed}, include_micro=False)
            wsnap = report.frames.get(interval)
            if wsnap is not None:
                wave_label = wsnap.wave_label
                wave_phase = wsnap.phase
                wave_score = float(wsnap.impulse_score or wsnap.confidence or 0.0)
                exhaustion = float(wsnap.exhaustion_risk or 0.0)
                wave_conf = float(wsnap.confidence or 0.0)
                invalidation = float(wsnap.invalidation_price or 0.0)
                hypothesis_summary = build_hypotheses(
                    symbol,
                    interval,
                    wsnap,
                    bullish=bullish,
                    bearish=bearish,
                )
        except Exception as exc:
            log.debug("wave enrichment %s %s failed: %s", symbol, interval, exc)

        hypotheses = hypothesis_summary.hypotheses if hypothesis_summary else tuple()
        strong = True
        if self.config.no_trade_when_uncertain and hypothesis_summary is not None:
            strong = (
                hypothesis_summary.primary.probability >= self.config.probability_threshold
                and hypothesis_summary.margin >= self.config.probability_margin_threshold
                and hypothesis_summary.entropy <= self.config.entropy_threshold
            )
        long_ok = bullish and bool(last.get("alligator_awake", False)) and self.config.allow_long and strong
        short_ok = bearish and bool(last.get("alligator_awake", False)) and self.config.allow_short and strong
        decision = "LONG" if long_ok else "SHORT" if short_ok else "NO_TRADE"
        if hypothesis_summary is not None and hypothesis_summary.decision in {"UNCERTAIN", "NO_TRADE"}:
            decision = "NO_TRADE"
        operative_interval = interval
        operative_parent_interval = ""
        try:
            with self.lock:
                cached_frames = {
                    tf: data.tail(self.config.wave_lookback).copy()
                    for (sym, tf), data in self.frames.items()
                    if sym == symbol and not data.empty
                }
            if len(cached_frames) >= 2:
                mtf_report = MultiTimeframeWaveEngine(
                    self.client,
                    base_interval=interval,
                    intervals=tuple(sorted(cached_frames.keys(), key=lambda x: MultiTimeframeWaveEngine.INTERVAL_SECONDS.get(x, 0))),
                    lookback=self.config.wave_lookback,
                    min_bars=self.config.wave_min_bars,
                    include_micro=False,
                ).analyse(symbol, cache=cached_frames, include_micro=False)
                operative_interval = mtf_report.operative_interval or interval
                operative_parent_interval = mtf_report.operative_parent_interval or ""
        except Exception as exc:
            log.debug("operative MTF selection %s failed: %s", symbol, exc)

        candle_open_ms = int(pd.Timestamp(frame.index[-1]).timestamp() * 1000)
        context = TFMarketContext(
            symbol=symbol,
            interval=interval,
            version=0,
            candle_open_time_ms=candle_open_ms,
            candle_close_time_ms=candle_open_ms,
            price=price,
            atr=atr,
            jaw=jaw,
            teeth=teeth,
            lips=lips,
            jaw_slope_atr=jaw_move_atr,
            price_slope_atr=price_move_atr,
            angulation=angulation,
            jaw_distance_atr=distance,
            alligator_state="BULLISH" if bullish else "BEARISH" if bearish else "SLEEP",
            wave_label=wave_label,
            wave_phase=wave_phase,
            wave_score=wave_score,
            exhaustion_risk=exhaustion,
            wave_confidence=wave_conf,
            invalidation_long=invalidation if bullish else 0.0,
            invalidation_short=invalidation if bearish else 0.0,
            allow_long=long_ok and decision == "LONG",
            allow_short=short_ok and decision == "SHORT",
            decision=decision,
            long_probability=float(getattr(hypothesis_summary, 'long_probability', 0.0) if hypothesis_summary else (1.0 if long_ok else 0.0)),
            short_probability=float(getattr(hypothesis_summary, 'short_probability', 0.0) if hypothesis_summary else (1.0 if short_ok else 0.0)),
            no_trade_probability=float(getattr(hypothesis_summary, 'no_trade_probability', 1.0) if hypothesis_summary else 1.0),
            calibration_status=str(getattr(hypothesis_summary, 'calibration_status', 'UNCALIBRATED') if hypothesis_summary else 'UNCALIBRATED'),
            operative_interval=operative_interval,
            operative_parent_interval=operative_parent_interval,
            hypotheses=tuple(hypotheses),
            data_bars=len(closed),
        )
        published = self.cache.publish(context)
        persisted_context = published.context(symbol, interval) or context
        try:
            if self.db is None:
                return
            self.db.save_market_context(persisted_context)
            self.db.save_wave_state(
                symbol,
                'LONG',
                {
                    'phase': wave_phase,
                    'wave_label': wave_label,
                    'confidence': wave_conf,
                    'exhaustion_risk': exhaustion,
                    'invalidation': context.invalidation_long,
                    'decision': decision,
                    'long_probability': getattr(hypothesis_summary, 'long_probability', 0.0) if hypothesis_summary else 0.0,
                    'no_trade_probability': getattr(hypothesis_summary, 'no_trade_probability', 1.0) if hypothesis_summary else 1.0,
                    'calibration_status': getattr(hypothesis_summary, 'calibration_status', 'UNCALIBRATED') if hypothesis_summary else 'UNCALIBRATED',
                    'interval': interval,
                    'version': persisted_context.version,
                },
            )
        except Exception as exc:
            self.last_error = f'context persistence: {type(exc).__name__}: {exc}'

    def bootstrap(self) -> None:
        for symbol in self.config.symbols:
            for interval in self.config.structural_timeframes:
                if self.stop_event.is_set():
                    return
                try:
                    self._bootstrap_one(symbol, interval)
                except Exception as exc:
                    self.last_error = f"{symbol}/{interval}: {type(exc).__name__}: {exc}"
                    log.warning("MTF bootstrap failed: %s", self.last_error)

    def _url(self) -> str:
        base = "wss://stream.testnet.binance.vision" if self.client.testnet else "wss://stream.binance.com:9443"
        streams = "/".join(
            f"{symbol.lower()}@kline_{interval}"
            for symbol in self.config.symbols
            for interval in self.config.structural_timeframes
        )
        return f"{base}/stream?streams={streams}"

    def _on_message(self, ws, raw: str) -> None:
        try:
            payload = json.loads(raw)
            data = payload.get("data", payload)
            if str(data.get("e", "")).lower() != "kline":
                return
            k = data["k"]
            if not bool(k.get("x", False)):
                self.last_event_at = int(time.time() * 1000)
                return

            symbol = str(k["s"]).upper()
            interval = str(k["i"]).lower()
            row = {
                "open": float(k["o"]),
                "high": float(k["h"]),
                "low": float(k["l"]),
                "close": float(k["c"]),
                "volume": float(k["v"]),
            }
            open_time = pd.to_datetime(int(k["t"]), unit="ms", utc=True)
            with self.lock:
                frame = self.frames.get((symbol, interval))
                if frame is None or frame.empty:
                    frame = pd.DataFrame([row], index=[open_time])
                elif frame.index[-1] == open_time:
                    frame = frame.copy()
                    for key, value in row.items():
                        frame.at[open_time, key] = value
                else:
                    frame = pd.concat([frame, pd.DataFrame([row], index=[open_time])])
                frame = frame.tail(300)
                self.frames[(symbol, interval)] = frame
                self.last_closed_open_ms[(symbol, interval)] = int(open_time.timestamp() * 1000)
                self.last_event_at = int(time.time() * 1000)
            self._publish(symbol, interval, frame)
        except Exception as exc:
            self.last_error = f"message: {type(exc).__name__}: {exc}"

    def recover_recent(self) -> None:
        """REST gap recovery for the recent tail after WS interruption."""
        for symbol in self.config.symbols:
            for interval in self.config.structural_timeframes:
                if self.stop_event.is_set():
                    return
                try:
                    df = fetch_klines(
                        self.client,
                        symbol,
                        interval,
                        limit=min(20, max(3, self.config.wave_min_bars // 10)),
                    )
                    if df is None or df.empty:
                        continue
                    if "close_time" in df.columns:
                        now = pd.Timestamp.now(tz="UTC")
                        df = df[pd.to_datetime(df["close_time"], utc=True) <= now].copy()
                    if df.empty:
                        continue
                    with self.lock:
                        old = self.frames.get((symbol, interval), pd.DataFrame())
                        merged = pd.concat([old, df])
                        merged = merged[~merged.index.duplicated(keep="last")].sort_index().tail(300)
                        self.frames[(symbol, interval)] = merged
                        self.last_closed_open_ms[(symbol, interval)] = int(merged.index[-1].timestamp() * 1000)
                    self._publish(symbol, interval, merged)
                except Exception as exc:
                    self.last_error = f"REST gap recovery {symbol}/{interval}: {type(exc).__name__}: {exc}"

    def _run(self) -> None:
        delay = 1.0
        while not self.stop_event.is_set():
            try:
                self.ws = websocket.WebSocketApp(
                    self._url(),
                    on_open=lambda ws: threading.Thread(
                        target=self.recover_recent,
                        daemon=True,
                        name="williams-mtf-recovery",
                    ).start(),
                    on_message=self._on_message,
                    on_error=lambda ws, err: setattr(self, "last_error", str(err)),
                    on_close=lambda ws, code, reason: None,
                )
                self.ws.run_forever(ping_interval=20, ping_timeout=10)
            except Exception as exc:
                self.last_error = f"ws: {type(exc).__name__}: {exc}"
            if self.stop_event.is_set():
                break
            time.sleep(delay)
            delay = min(30.0, delay * 2.0)

    def _watchdog(self) -> None:
        while not self.stop_event.wait(15.0):
            if not self.running:
                continue
            last = self.last_event_at
            if last is None:
                continue
            age = (int(time.time() * 1000) - int(last)) / 1000.0
            if age <= self.watchdog_seconds:
                continue
            try:
                self.last_error = f'MTF watchdog: no Binance WS event for {age:.0f}s; recovering'
                self.recover_recent()
                if self.ws is not None:
                    try:
                        self.ws.close()
                    except Exception:
                        pass
            except Exception as exc:
                self.last_error = f'MTF watchdog recovery failed: {type(exc).__name__}: {exc}'

    def start(self) -> None:
        with self.lock:
            if self.running:
                return
            self.stop_event.clear()
            self.running = True
            self.bootstrap_thread = threading.Thread(target=self.bootstrap, daemon=True, name="williams-mtf-bootstrap")
            self.bootstrap_thread.start()
            self.thread = threading.Thread(target=self._run, daemon=True, name="williams-mtf-ws")
            self.thread.start()
            self.watchdog_thread = threading.Thread(target=self._watchdog, daemon=True, name="williams-mtf-watchdog")
            self.watchdog_thread.start()

    def stop(self) -> None:
        self.stop_event.set()
        if self.ws is not None:
            try:
                self.ws.close()
            except Exception:
                pass
        self.running = False

    def snapshot_status(self) -> dict:
        snap = self.cache.snapshot()
        populated = sum(len(frames) for frames in snap.by_symbol.values())
        return {
            "generation": snap.generation,
            "symbols": len(snap.by_symbol),
            "timeframes": len(set(tf for frames in snap.by_symbol.values() for tf in frames)),
            "contexts": populated,
            "ws_running": self.running,
            "last_event_at": self.last_event_at,
            "event_age_seconds": (
                round((int(time.time() * 1000) - int(self.last_event_at)) / 1000.0, 2)
                if self.last_event_at else None
            ),
            "watchdog_seconds": self.watchdog_seconds,
            "healthy": bool(self.running and self.last_error is None),
            "last_error": self.last_error,
        }

    def symbol_snapshot(self, symbol: str) -> dict:
        frames = self.cache.snapshot().by_symbol.get(symbol.upper(), {})
        return {
            tf: {
                "version": ctx.version,
                "candle_close_time_ms": ctx.candle_close_time_ms,
                "price": ctx.price,
                "wave": ctx.wave_label,
                "phase": ctx.wave_phase,
                "confidence": ctx.wave_confidence,
                "exhaustion": ctx.exhaustion_risk,
                "decision": ctx.decision,
                "allow_long": ctx.allow_long,
                "allow_short": ctx.allow_short,
                "long_probability": ctx.long_probability,
                "short_probability": ctx.short_probability,
                "no_trade_probability": ctx.no_trade_probability,
                "calibration_status": ctx.calibration_status,
                "operative_interval": ctx.operative_interval,
                "operative_parent_interval": ctx.operative_parent_interval,
                "angulation": ctx.angulation,
                "jaw_distance_atr": ctx.jaw_distance_atr,
                "invalidation_long": ctx.invalidation_long,
                "invalidation_short": ctx.invalidation_short,
            }
            for tf, ctx in frames.items()
        }
