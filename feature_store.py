"""Williams quantitative feature layer.

The module is deliberately advisory/shadow-only:
- it never creates, cancels or amends exchange orders;
- it keeps raw Williams strategy decisions authoritative;
- AI/shadow consumers receive an immutable MarketFeatureVector;
- persistence is a small SQLite feature store with an explicit schema.
"""
from __future__ import annotations

import json
import os
import sqlite3
import threading
import time
from dataclasses import asdict, dataclass, field
from typing import Any, Iterable, Mapping, Sequence

import numpy as np
import pandas as pd

from derivatives_features import build_derivatives_features


@dataclass(frozen=True)
class MarketFeatureVector:
    schema_version: int
    timestamp_ms: int
    symbol: str
    interval: str
    source: str
    data_bars: int

    price: float = 0.0
    return_1: float = 0.0
    return_5: float = 0.0
    atr_pct: float = 0.0
    volume_zscore: float = 0.0

    dollar_bar_count: int = 0
    volume_bar_count: int = 0
    dollar_bar_rate: float = 0.0
    volume_bar_rate: float = 0.0

    obi: float = 0.0
    spread_pct: float = 0.0
    trade_flow_imbalance: float = 0.0
    trade_flow_notional: float = 0.0

    alligator_spread_pct: float = 0.0
    ao: float = 0.0
    ao_acceleration: float = 0.0

    wave_score: float = 50.0
    wave_position: int = 0
    wave_confidence: float = 0.0
    wave_exhaustion_risk: float = 0.0
    wave3_probability: float = 0.0
    wave5_probability: float = 0.0
    wave_nested_w3: bool = False
    wave_nested_w3_parent_w5: bool = False
    wave_alignment_score: float = 0.0
    wave_parent_position: int = 0
    wave_parent_confidence: float = 0.0
    wave_parent_exhaustion_risk: float = 0.0
    wave_nested_w3_probability: float = 0.0
    wave_tf_agreement: float = 0.0
    invalidation: float = 0.0
    target: float = 0.0

    funding_rate: float = 0.0
    funding_rate_delta: float = 0.0
    open_interest: float = 0.0
    open_interest_delta: float = 0.0
    long_short_ratio: float = 0.0
    liquidation_notional: float = 0.0
    liquidation_cluster_proximity: float = 0.0

    regime: str = "UNKNOWN"
    regime_score: float = 0.0

    extra: Mapping[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        payload = asdict(self)
        payload["extra"] = dict(self.extra)
        return payload


class FeatureStore:
    """Thread-safe SQLite store for features, shadow intents and stress runs."""

    SCHEMA = """
    CREATE TABLE IF NOT EXISTS feature_vectors (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        timestamp_ms INTEGER NOT NULL,
        symbol TEXT NOT NULL,
        interval TEXT NOT NULL,
        schema_version INTEGER NOT NULL,
        payload_json TEXT NOT NULL
    );
    CREATE INDEX IF NOT EXISTS idx_feature_vectors_symbol_time
      ON feature_vectors(symbol, interval, timestamp_ms DESC);

    CREATE TABLE IF NOT EXISTS shadow_decisions (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        timestamp_ms INTEGER NOT NULL,
        symbol TEXT NOT NULL,
        interval TEXT NOT NULL,
        action TEXT NOT NULL,
        confidence REAL NOT NULL,
        payload_json TEXT NOT NULL
    );
    CREATE INDEX IF NOT EXISTS idx_shadow_symbol_time
      ON shadow_decisions(symbol, timestamp_ms DESC);

    CREATE TABLE IF NOT EXISTS stress_runs (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        timestamp_ms INTEGER NOT NULL,
        scenario TEXT NOT NULL,
        passed INTEGER NOT NULL,
        payload_json TEXT NOT NULL
    );
    """

    def __init__(self, path: str | None = None):
        self.path = path or os.getenv("FEATURE_STORE_PATH", "data/features.sqlite3")
        parent = os.path.dirname(os.path.abspath(self.path))
        if parent:
            os.makedirs(parent, exist_ok=True)
        self._lock = threading.RLock()
        self._conn = sqlite3.connect(
            self.path,
            check_same_thread=False,
            timeout=10.0,
        )
        self._conn.row_factory = sqlite3.Row
        with self._lock:
            self._conn.executescript(self.SCHEMA)
            self._conn.commit()

    def save_feature(self, vector: MarketFeatureVector) -> None:
        payload = json.dumps(vector.to_dict(), default=str, sort_keys=True)
        with self._lock:
            self._conn.execute(
                "INSERT INTO feature_vectors "
                "(timestamp_ms,symbol,interval,schema_version,payload_json) "
                "VALUES (?,?,?,?,?)",
                (
                    int(vector.timestamp_ms),
                    vector.symbol.upper(),
                    vector.interval.lower(),
                    int(vector.schema_version),
                    payload,
                ),
            )
            self._conn.commit()

    def latest(self, symbol: str, interval: str | None = None) -> dict[str, Any] | None:
        sql = (
            "SELECT payload_json FROM feature_vectors "
            "WHERE symbol=? "
        )
        params: list[Any] = [symbol.upper()]
        if interval:
            sql += "AND interval=? "
            params.append(interval.lower())
        sql += "ORDER BY timestamp_ms DESC, id DESC LIMIT 1"
        with self._lock:
            row = self._conn.execute(sql, tuple(params)).fetchone()
        return json.loads(row["payload_json"]) if row else None

    def recent(self, symbol: str | None = None, limit: int = 50) -> list[dict[str, Any]]:
        limit = max(1, min(int(limit), 500))
        if symbol:
            sql = (
                "SELECT payload_json FROM feature_vectors "
                "WHERE symbol=? ORDER BY timestamp_ms DESC, id DESC LIMIT ?"
            )
            params = (symbol.upper(), limit)
        else:
            sql = (
                "SELECT payload_json FROM feature_vectors "
                "ORDER BY timestamp_ms DESC, id DESC LIMIT ?"
            )
            params = (limit,)
        with self._lock:
            rows = self._conn.execute(sql, params).fetchall()
        return [json.loads(r["payload_json"]) for r in rows]

    def save_shadow(self, payload: Mapping[str, Any]) -> None:
        data = dict(payload)
        with self._lock:
            self._conn.execute(
                "INSERT INTO shadow_decisions "
                "(timestamp_ms,symbol,interval,action,confidence,payload_json) "
                "VALUES (?,?,?,?,?,?)",
                (
                    int(data.get("timestamp_ms", int(time.time() * 1000))),
                    str(data.get("symbol", "")).upper(),
                    str(data.get("interval", "")).lower(),
                    str(data.get("action", "HOLD")).upper(),
                    float(data.get("confidence", 0.0)),
                    json.dumps(data, default=str, sort_keys=True),
                ),
            )
            self._conn.commit()

    def recent_shadow(self, limit: int = 50) -> list[dict[str, Any]]:
        limit = max(1, min(int(limit), 500))
        with self._lock:
            rows = self._conn.execute(
                "SELECT payload_json FROM shadow_decisions "
                "ORDER BY timestamp_ms DESC, id DESC LIMIT ?",
                (limit,),
            ).fetchall()
        return [json.loads(r["payload_json"]) for r in rows]

    def save_shadow_execution(self, payload: Mapping[str, Any]) -> None:
        data = dict(payload)
        with self._lock:
            self._conn.execute(
                "CREATE TABLE IF NOT EXISTS shadow_executions ("
                "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                "timestamp_ms INTEGER NOT NULL,"
                "symbol TEXT NOT NULL,"
                "payload_json TEXT NOT NULL)"
            )
            self._conn.execute(
                "INSERT INTO shadow_executions (timestamp_ms,symbol,payload_json) VALUES (?,?,?)",
                (
                    int(data.get("timestamp_ms", int(time.time() * 1000))),
                    str(data.get("symbol", "")).upper(),
                    json.dumps(data, default=str, sort_keys=True),
                ),
            )
            self._conn.commit()

    def recent_shadow_executions(self, limit: int = 50) -> list[dict[str, Any]]:
        limit = max(1, min(int(limit), 500))
        with self._lock:
            self._conn.execute(
                "CREATE TABLE IF NOT EXISTS shadow_executions ("
                "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                "timestamp_ms INTEGER NOT NULL,"
                "symbol TEXT NOT NULL,"
                "payload_json TEXT NOT NULL)"
            )
            rows = self._conn.execute(
                "SELECT payload_json FROM shadow_executions ORDER BY timestamp_ms DESC, id DESC LIMIT ?",
                (limit,),
            ).fetchall()
        return [json.loads(row["payload_json"]) for row in rows]

    def save_stress(self, payload: Mapping[str, Any]) -> None:
        data = dict(payload)
        with self._lock:
            self._conn.execute(
                "INSERT INTO stress_runs "
                "(timestamp_ms,scenario,passed,payload_json) VALUES (?,?,?,?)",
                (
                    int(data.get("timestamp_ms", int(time.time() * 1000))),
                    str(data.get("scenario", "stress")),
                    1 if bool(data.get("passed", False)) else 0,
                    json.dumps(data, default=str, sort_keys=True),
                ),
            )
            self._conn.commit()


def _safe_float(value: Any, default: float = 0.0) -> float:
    try:
        value = float(value)
    except (TypeError, ValueError):
        return default
    return value if np.isfinite(value) else default


def _norm_imbalance(bid_qty: float, ask_qty: float) -> float:
    total = bid_qty + ask_qty
    return (bid_qty - ask_qty) / total if total > 0 else 0.0


def _trades_stats(trades: Sequence[Mapping[str, Any]] | None) -> tuple[float, float]:
    if not trades:
        return 0.0, 0.0
    buy = sell = total_notional = 0.0
    for item in trades:
        price = _safe_float(item.get("price", item.get("p", 0.0)))
        qty = _safe_float(item.get("qty", item.get("q", 0.0)))
        if price <= 0 or qty <= 0:
            continue
        notional = price * qty
        total_notional += notional
        maker = bool(item.get("m", False))
        if bool(item.get("aggressive_buy", not maker)):
            buy += notional
        else:
            sell += notional
    total = buy + sell
    return ((_norm_imbalance(buy, sell) if total > 0 else 0.0), total_notional)


def _bar_from_trade_rows(rows: Sequence[Mapping[str, Any]], threshold_key: str) -> list[dict[str, float]]:
    bars: list[dict[str, float]] = []
    accumulator: dict[str, float] | None = None
    threshold = None
    for item in rows:
        price = _safe_float(item.get("price", item.get("p", 0.0)))
        qty = _safe_float(item.get("qty", item.get("q", 0.0)))
        if price <= 0 or qty <= 0:
            continue
        dollar = price * qty
        amount = dollar if threshold_key == "dollar" else qty
        if accumulator is None:
            accumulator = {
                "open": price,
                "high": price,
                "low": price,
                "close": price,
                "volume": 0.0,
                "dollar_volume": 0.0,
            }
            threshold = None
        accumulator["high"] = max(accumulator["high"], price)
        accumulator["low"] = min(accumulator["low"], price)
        accumulator["close"] = price
        accumulator["volume"] += qty
        accumulator["dollar_volume"] += dollar
        if threshold is None:
            threshold = amount
        if accumulator["dollar_volume"] >= threshold if threshold_key == "dollar" else accumulator["volume"] >= threshold:
            bars.append(accumulator)
            accumulator = None
            threshold = None
    return bars


def build_dollar_bars_from_trades(
    trades: Sequence[Mapping[str, Any]],
    threshold: float,
) -> list[dict[str, float]]:
    if threshold <= 0:
        raise ValueError("dollar bar threshold must be positive")
    bars: list[dict[str, float]] = []
    current: dict[str, float] | None = None
    accumulated = 0.0
    for item in trades:
        price = _safe_float(item.get("price", item.get("p", 0.0)))
        qty = _safe_float(item.get("qty", item.get("q", 0.0)))
        if price <= 0 or qty <= 0:
            continue
        dollar = price * qty
        if current is None:
            current = {"open": price, "high": price, "low": price, "close": price, "volume": 0.0, "dollar_volume": 0.0}
        current["high"] = max(current["high"], price)
        current["low"] = min(current["low"], price)
        current["close"] = price
        current["volume"] += qty
        current["dollar_volume"] += dollar
        accumulated += dollar
        if accumulated >= threshold:
            bars.append(current)
            current = None
            accumulated = 0.0
    return bars


def build_volume_bars_from_trades(
    trades: Sequence[Mapping[str, Any]],
    threshold: float,
) -> list[dict[str, float]]:
    if threshold <= 0:
        raise ValueError("volume bar threshold must be positive")
    bars: list[dict[str, float]] = []
    current: dict[str, float] | None = None
    accumulated = 0.0
    for item in trades:
        price = _safe_float(item.get("price", item.get("p", 0.0)))
        qty = _safe_float(item.get("qty", item.get("q", 0.0)))
        if price <= 0 or qty <= 0:
            continue
        dollar = price * qty
        if current is None:
            current = {"open": price, "high": price, "low": price, "close": price, "volume": 0.0, "dollar_volume": 0.0}
        current["high"] = max(current["high"], price)
        current["low"] = min(current["low"], price)
        current["close"] = price
        current["volume"] += qty
        current["dollar_volume"] += dollar
        accumulated += qty
        if accumulated >= threshold:
            bars.append(current)
            current = None
            accumulated = 0.0
    return bars


def build_dollar_bars(df: pd.DataFrame, threshold: float | None = None) -> pd.DataFrame:
    """Build research dollar bars from OHLCV.

    This is a transparent proxy because candle data has no trade-by-trade ordering.
    Production raw-trade feeds use build_dollar_bars_from_trades().
    """
    if df is None or df.empty:
        return pd.DataFrame()
    work = df.copy()
    dollar = pd.to_numeric(work["close"], errors="coerce") * pd.to_numeric(work["volume"], errors="coerce")
    threshold = float(threshold or max(float(dollar.tail(min(50, len(dollar))).median()), 1e-12))
    rows: list[dict[str, Any]] = []
    acc: list[int] = []
    total = 0.0
    for i, value in enumerate(dollar.fillna(0.0)):
        total += float(value)
        acc.append(i)
        if total >= threshold:
            block = work.iloc[acc]
            rows.append({
                "time": block.index[-1],
                "open": float(block["open"].iloc[0]),
                "high": float(block["high"].max()),
                "low": float(block["low"].min()),
                "close": float(block["close"].iloc[-1]),
                "volume": float(block["volume"].sum()),
                "dollar_volume": float(dollar.iloc[acc].sum()),
            })
            acc = []
            total = 0.0
    return pd.DataFrame(rows)


def build_volume_bars(df: pd.DataFrame, threshold: float | None = None) -> pd.DataFrame:
    if df is None or df.empty:
        return pd.DataFrame()
    work = df.copy()
    volume = pd.to_numeric(work["volume"], errors="coerce").fillna(0.0)
    threshold = float(threshold or max(float(volume.tail(min(50, len(volume))).median()), 1e-12))
    rows: list[dict[str, Any]] = []
    acc: list[int] = []
    total = 0.0
    for i, value in enumerate(volume):
        total += float(value)
        acc.append(i)
        if total >= threshold:
            block = work.iloc[acc]
            rows.append({
                "time": block.index[-1],
                "open": float(block["open"].iloc[0]),
                "high": float(block["high"].max()),
                "low": float(block["low"].min()),
                "close": float(block["close"].iloc[-1]),
                "volume": float(block["volume"].sum()),
                "dollar_volume": float((block["close"] * block["volume"]).sum()),
            })
            acc = []
            total = 0.0
    return pd.DataFrame(rows)


class RegimeBaseline:
    """Deterministic baseline used before HMM/GMM admission.

    It labels market state; it does not override a Williams signal or risk gate.
    """

    LOW_VOL_FLAT = "LOW_VOL_FLAT"
    TRENDING_EXPANSION = "TRENDING_EXPANSION"
    HIGH_NOISE_WASH = "HIGH_NOISE_WASH"
    UNKNOWN = "UNKNOWN"

    def classify(
        self,
        *,
        atr_pct: float,
        alligator_spread_pct: float,
        return_1: float,
        return_5: float,
        volume_zscore: float,
        obi: float,
        ao_acceleration: float,
    ) -> tuple[str, float]:
        atr_pct = max(0.0, float(atr_pct))
        spread = max(0.0, float(alligator_spread_pct))
        direction = abs(float(return_5)) + abs(float(return_1))
        trend_alignment = max(0.0, min(1.0, 0.5 * (1.0 + np.sign(return_5) * np.sign(obi))))
        expansion = min(
            1.0,
            0.35 * min(1.0, spread / 0.003)
            + 0.30 * min(1.0, atr_pct / 0.02)
            + 0.20 * min(1.0, direction / 0.03)
            + 0.15 * trend_alignment,
        )
        if atr_pct < 0.004 and spread < 0.001:
            return self.LOW_VOL_FLAT, round(1.0 - expansion, 4)
        if expansion >= 0.60 and direction >= 0.004:
            return self.TRENDING_EXPANSION, round(expansion, 4)
        noise = (
            0.50 * min(1.0, atr_pct / 0.03)
            + 0.25 * min(1.0, abs(volume_zscore) / 3.0)
            + 0.25 * (1.0 - min(1.0, abs(float(ao_acceleration)) / max(atr_pct, 1e-9)))
        )
        if noise >= 0.65 and abs(float(return_5)) < atr_pct * 0.75:
            return self.HIGH_NOISE_WASH, round(noise, 4)
        return self.UNKNOWN, round(expansion, 4)


def _atr_pct(df: pd.DataFrame, period: int = 14) -> float:
    if df is None or len(df) < 2:
        return 0.0
    prev = df["close"].shift(1)
    tr = pd.concat([
        df["high"] - df["low"],
        (df["high"] - prev).abs(),
        (df["low"] - prev).abs(),
    ], axis=1).max(axis=1)
    atr = tr.rolling(max(2, int(period))).mean().iloc[-1]
    close = _safe_float(df["close"].iloc[-1])
    return _safe_float(atr) / close if close > 0 else 0.0


def _volume_zscore(df: pd.DataFrame, window: int = 30) -> float:
    values = pd.to_numeric(df["volume"], errors="coerce").astype(float)
    if len(values) < 3:
        return 0.0
    recent = values.tail(max(3, int(window)))
    std = float(recent.std(ddof=0))
    if std <= 0:
        return 0.0
    return _safe_float((recent.iloc[-1] - recent.mean()) / std)


def _wave_fields(wave_report: Any) -> dict[str, Any]:
    if wave_report is None:
        return {}
    data = wave_report.to_dict() if hasattr(wave_report, "to_dict") else dict(wave_report)
    frames = data.get("frames", {}) or {}
    setup_tf = str(data.get("entry_interval") or data.get("operative_interval") or "")
    setup = frames.get(setup_tf, {}) if setup_tf else {}
    parent_tf = str(
        data.get("entry_parent_interval")
        or data.get("operative_parent_interval")
        or ""
    )
    parent = frames.get(parent_tf, {}) if parent_tf else {}

    position = int(setup.get("position", data.get("entry_position", 0)) or 0)
    confidence = float(setup.get("confidence", 0.0) or 0.0)
    invalidation = 0.0
    target = 0.0
    if isinstance(setup, dict):
        try:
            invalidation = float(setup.get("invalidation_price", 0.0) or 0.0)
        except (TypeError, ValueError):
            invalidation = 0.0
        try:
            low = float(setup.get("target_zone_low", 0.0) or 0.0)
            high = float(setup.get("target_zone_high", 0.0) or 0.0)
            target = (low + high) / 2.0 if low > 0 and high > 0 else max(low, high, 0.0)
        except (TypeError, ValueError):
            target = 0.0

    nested_w3_prob = 0.0
    if bool(data.get("nested_w3", False)):
        nested_w3_prob = max(
            (
                float(frame.get("confidence", 0.0) or 0.0)
                for frame in frames.values()
                if isinstance(frame, dict) and int(frame.get("position", 0) or 0) == 3
            ),
            default=0.0,
        )

    setup_dir = str(setup.get("direction", "NEUTRAL")) if isinstance(setup, dict) else "NEUTRAL"
    parent_dir = str(parent.get("direction", "NEUTRAL")) if isinstance(parent, dict) else "NEUTRAL"
    tf_agreement = 1.0 if setup_dir == parent_dir and setup_dir not in {"", "NEUTRAL"} else (
        0.5 if setup_dir == "NEUTRAL" or parent_dir == "NEUTRAL" else 0.0
    )

    return {
        "wave_score": float(data.get("wave_score", 50.0) or 50.0),
        "wave_position": position,
        "wave_confidence": confidence,
        "wave_exhaustion_risk": float(data.get("exhaustion_risk", setup.get("exhaustion_risk", 0.0)) or 0.0),
        "wave3_probability": confidence if position == 3 else 0.0,
        "wave5_probability": confidence if position == 5 else 0.0,
        "wave_nested_w3": bool(data.get("nested_w3", False)),
        "wave_nested_w3_parent_w5": bool(data.get("nested_w3_parent_w5", False)),
        "wave_alignment_score": float(data.get("alignment_score", 0.0) or 0.0),
        "wave_parent_position": int(parent.get("position", 0) or 0) if isinstance(parent, dict) else 0,
        "wave_parent_confidence": float(parent.get("confidence", 0.0) or 0.0) if isinstance(parent, dict) else 0.0,
        "wave_parent_exhaustion_risk": float(parent.get("exhaustion_risk", 0.0) or 0.0) if isinstance(parent, dict) else 0.0,
        "wave_nested_w3_probability": nested_w3_prob,
        "wave_tf_agreement": tf_agreement,
        "invalidation": invalidation,
        "target": target,
    }


def build_market_feature_vector(
    symbol: str,
    interval: str,
    candles: pd.DataFrame,
    *,
    wave_report: Any = None,
    order_book: Mapping[str, Any] | None = None,
    trades: Sequence[Mapping[str, Any]] | None = None,
    derivatives: Mapping[str, Any] | None = None,
    previous_derivatives: Mapping[str, Any] | None = None,
    source: str = "ohlcv+market",
) -> MarketFeatureVector:
    if candles is None or candles.empty:
        raise ValueError("candles are required")
    df = candles.copy().tail(500)
    close = pd.to_numeric(df["close"], errors="coerce")
    price = _safe_float(close.iloc[-1])
    ret1 = _safe_float(close.pct_change().iloc[-1])
    ret5 = _safe_float(close.pct_change(5).iloc[-1])
    atr_pct = _atr_pct(df)
    vol_z = _volume_zscore(df)

    dollar_proxy = build_dollar_bars(df)
    volume_proxy = build_volume_bars(df)
    dollar = dollar_proxy
    volume = volume_proxy
    bar_source = "ohlcv_proxy"
    if trades:
        raw_dollars = []
        raw_volumes = []
        for item in trades:
            p = _safe_float(item.get("price", item.get("p", 0.0)))
            q = _safe_float(item.get("qty", item.get("q", 0.0)))
            if p > 0 and q > 0:
                raw_dollars.append(p * q)
                raw_volumes.append(q)
        if raw_dollars and raw_volumes:
            dollar_threshold = max(float(np.median(raw_dollars)) * 20.0, 1e-9)
            volume_threshold = max(float(np.median(raw_volumes)) * 20.0, 1e-9)
            dollar_trade_bars = build_dollar_bars_from_trades(trades, dollar_threshold)
            volume_trade_bars = build_volume_bars_from_trades(trades, volume_threshold)
            if dollar_trade_bars:
                dollar = dollar_trade_bars
            if volume_trade_bars:
                volume = volume_trade_bars
            bar_source = "trade_stream"
    
    bid_qty = ask_qty = 0.0
    spread_pct = 0.0
    if order_book:
        bids = order_book.get("bids", []) or []
        asks = order_book.get("asks", []) or []
        bid_qty = sum(_safe_float(row[1]) for row in bids if len(row) >= 2)
        ask_qty = sum(_safe_float(row[1]) for row in asks if len(row) >= 2)
        bid = _safe_float(order_book.get("bidPrice", order_book.get("bid", 0.0)))
        ask = _safe_float(order_book.get("askPrice", order_book.get("ask", 0.0)))
        mid = (bid + ask) / 2.0
        if mid > 0 and ask >= bid > 0:
            spread_pct = (ask - bid) / mid
        elif bid_qty == 0 and ask_qty == 0:
            bid_qty = _safe_float(order_book.get("bidQty", 0.0))
            ask_qty = _safe_float(order_book.get("askQty", 0.0))
    obi = _norm_imbalance(bid_qty, ask_qty)
    flow_imbalance, flow_notional = _trades_stats(trades)
    wf = _wave_fields(wave_report)

    ao = 0.0
    ao_acc = 0.0
    alligator_spread = 0.0
    if "ao" in df.columns:
        ao = _safe_float(df["ao"].iloc[-1])
        if len(df) > 1:
            ao_acc = _safe_float(df["ao"].iloc[-1] - df["ao"].iloc[-2])
    if "alligator_spread_pct" in df.columns:
        alligator_spread = _safe_float(df["alligator_spread_pct"].iloc[-1])

    derivative_set = build_derivatives_features(derivatives, previous_derivatives)

    regime, regime_score = RegimeBaseline().classify(
        atr_pct=atr_pct,
        alligator_spread_pct=alligator_spread,
        return_1=ret1,
        return_5=ret5,
        volume_zscore=vol_z,
        obi=obi,
        ao_acceleration=ao_acc,
    )

    extra = {
        "dollar_bar_proxy": bar_source == "ohlcv_proxy",
        "volume_bar_proxy": bar_source == "ohlcv_proxy",
        "bar_source": bar_source,
        "trade_samples": len(trades or []),
        "features_version": 1,
        "wave_context_present": bool(wf),
        "derivatives_source_present": bool(derivatives),
    }

    return MarketFeatureVector(
        schema_version=1,
        timestamp_ms=int(time.time() * 1000),
        symbol=symbol.upper(),
        interval=interval.lower(),
        source=source,
        data_bars=int(len(df)),
        price=price,
        return_1=ret1,
        return_5=ret5,
        atr_pct=atr_pct,
        volume_zscore=vol_z,
        dollar_bar_count=len(dollar),
        volume_bar_count=len(volume),
        dollar_bar_rate=float(len(dollar) / max(1, len(df))),
        volume_bar_rate=float(len(volume) / max(1, len(df))),
        obi=obi,
        spread_pct=spread_pct,
        trade_flow_imbalance=flow_imbalance,
        trade_flow_notional=flow_notional,
        alligator_spread_pct=alligator_spread,
        ao=ao,
        ao_acceleration=ao_acc,
        wave_score=wf.get("wave_score", 50.0),
        wave_position=wf.get("wave_position", 0),
        wave_confidence=wf.get("wave_confidence", 0.0),
        wave_exhaustion_risk=wf.get("wave_exhaustion_risk", 0.0),
        wave3_probability=wf.get("wave3_probability", 0.0),
        wave5_probability=wf.get("wave5_probability", 0.0),
        wave_nested_w3=wf.get("wave_nested_w3", False),
        wave_nested_w3_parent_w5=wf.get("wave_nested_w3_parent_w5", False),
        wave_alignment_score=wf.get("wave_alignment_score", 0.0),
        wave_parent_position=wf.get("wave_parent_position", 0),
        wave_parent_confidence=wf.get("wave_parent_confidence", 0.0),
        wave_parent_exhaustion_risk=wf.get("wave_parent_exhaustion_risk", 0.0),
        wave_nested_w3_probability=wf.get("wave_nested_w3_probability", 0.0),
        wave_tf_agreement=wf.get("wave_tf_agreement", 0.0),
        funding_rate=derivative_set.funding_rate,
        funding_rate_delta=derivative_set.funding_rate_delta,
        open_interest=derivative_set.open_interest,
        open_interest_delta=derivative_set.open_interest_delta,
        long_short_ratio=derivative_set.long_short_ratio,
        liquidation_notional=derivative_set.liquidation_notional,
        liquidation_cluster_proximity=derivative_set.liquidation_cluster_proximity,
        invalidation=wf.get("invalidation", 0.0),
        target=wf.get("target", 0.0),
        regime=regime,
        regime_score=regime_score,
        extra=extra,
    )


__all__ = [
    "MarketFeatureVector",
    "FeatureStore",
    "RegimeBaseline",
    "build_dollar_bars",
    "build_volume_bars",
    "build_dollar_bars_from_trades",
    "build_volume_bars_from_trades",
    "build_market_feature_vector",
]
