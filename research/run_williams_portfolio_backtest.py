"""Execute the 24-month, fifteen-symbol unified-account TC2 portfolio replay.

The original 1,440 Binance Vision archives are consumed from immutable GitHub
Actions artifacts. Only eight months of H1 prehistory are fetched for indicator
warm-up; no original 24-month archive is fetched again.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from decimal import Decimal
import hashlib
import json
import math
import os
from pathlib import Path
import sys
import time
import zipfile
import io

import numpy as np
import pandas as pd
import requests

REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
if str(REPOSITORY_ROOT) not in sys.path:
    sys.path.insert(0, str(REPOSITORY_ROOT))

from research.fetch_portfolio_archives import (
    BASE, DATASETS, archive_path, month_bounds, validate,
)
from research.williams_portfolio_engine import PortfolioSimulator
from run_williams_h1_paper_replay import (
    _market_regime_frame,
    extract_causal_h1_signals,
)
from strategy import calculate_indicators, canonical_indicator_config_from_env
from trading_config import DEFAULT_FUTURES_SYMBOLS

START_UTC = "2024-11-01T00:00:00Z"
END_UTC = "2026-11-01T00:00:00Z"
START_MS = 1_730_419_200_000
END_MS = 1_793_491_200_000
PREHISTORY_START = (2024, 3)
PREHISTORY_END = (2024, 11)
EXPECTED_MANIFEST_SHA256 = "21e5f9ce105d5aeb2ffd636bed0925b3994908e23c3dfe63f78d8683e43f3758"
EXPECTED_REPAIR_BUNDLE_SHA256 = "14ee78836f64226f56eff5f1449ece39bca786ef0f634e51cedd465802e632f6"
ORIGINAL_RUN_ID = 45633468011
REPAIR_RUN_ID = 45662218924
REPAIR_ARTIFACT_ID = 15046445768
ORIGINAL_ARCHIVE_COUNT = 1425
REPAIRED_ARCHIVE_COUNT = 15
TOTAL_ARCHIVE_COUNT = 1440
EXPECTED_ARCHIVE_ROWS = 6_602_850
M5_MS = 300_000
H1_MS = 3_600_000
DAY_MS = 86_400_000
FUTURES_REST = "https://fapi.binance.com"


def _sha(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _normalize_ms(value) -> int:
    n = int(value)
    return n // 1000 if n > 10**15 else n


def _month_sequence(start=(2024, 3), end=(2024, 11)):
    y, m = start
    while (y, m) < end:
        yield y, m
        y, m = y + (m == 12), m % 12 + 1


def _download_one_warmup(symbol: str, year: int, month: int, target: Path) -> dict:
    relative = archive_path(symbol, "klines", "1h", year, month)
    url = f"{BASE}/{relative}"
    target.parent.mkdir(parents=True, exist_ok=True)
    for attempt in range(3):
        try:
            checksum_response = requests.get(url + ".CHECKSUM", timeout=30)
            checksum_response.raise_for_status()
            expected = checksum_response.text.split()[0].lower()
            if len(expected) != 64:
                raise ValueError(f"{symbol} {year}-{month:02}: malformed Binance checksum")
            if target.exists():
                raw = target.read_bytes()
            else:
                response = requests.get(url, timeout=45)
                response.raise_for_status()
                raw = response.content
            actual = _sha(raw)
            if actual != expected:
                target.unlink(missing_ok=True)
                if attempt == 2:
                    raise ValueError(f"{symbol} {year}-{month:02}: warm-up SHA-256 mismatch")
                continue
            start_ms, end_ms = month_bounds(year, month)
            facts = validate(raw, "klines", H1_MS, start_ms, end_ms)
            if not facts["complete"]:
                raise ValueError(f"{symbol} {year}-{month:02}: incomplete warm-up H1 archive")
            if not target.exists():
                target.write_bytes(raw)
            return {
                "symbol": symbol, "month": f"{year:04}-{month:02}",
                "path": str(target), "sha256": actual, "bytes": len(raw),
                "rows": facts["rows"], "first_ms": facts["first_ms"],
                "last_ms": facts["last_ms"], "source": url,
            }
        except Exception:
            if attempt == 2:
                raise
            time.sleep(2 ** attempt)
    raise RuntimeError("unreachable warm-up downloader")


def download_warmup(data_root: Path) -> list[dict]:
    jobs = []
    for symbol in DEFAULT_FUTURES_SYMBOLS:
        for year, month in _month_sequence(PREHISTORY_START, PREHISTORY_END):
            target = data_root / "warmup" / symbol / f"{symbol}-1h-{year:04}-{month:02}.zip"
            jobs.append((symbol, year, month, target))
    rows = []
    with ThreadPoolExecutor(max_workers=8) as pool:
        futures = [pool.submit(_download_one_warmup, *job) for job in jobs]
        for future in as_completed(futures):
            rows.append(future.result())
    rows.sort(key=lambda x: (x["symbol"], x["month"]))
    if len(rows) != len(DEFAULT_FUTURES_SYMBOLS) * 8:
        raise RuntimeError("warm-up archive count mismatch")
    return rows


def load_manifest(data_root: Path) -> tuple[dict, str]:
    path = data_root / "qualified" / "portfolio-manifest.json"
    sha_path = data_root / "qualified" / "portfolio-manifest.json.sha256"
    if not path.exists() or not sha_path.exists():
        raise FileNotFoundError("qualified portfolio manifest or SHA file is missing")
    raw = path.read_bytes()
    actual = _sha(raw)
    declared = sha_path.read_text().split()[0].lower()
    if actual != declared or actual != EXPECTED_MANIFEST_SHA256:
        raise ValueError("qualified portfolio manifest SHA-256 mismatch")
    manifest = json.loads(raw)
    if manifest.get("start_utc") != START_UTC or manifest.get("end_exclusive_utc") != END_UTC:
        raise ValueError("qualified portfolio manifest has an unexpected evaluation period")
    if manifest.get("symbols") != list(DEFAULT_FUTURES_SYMBOLS):
        raise ValueError("qualified portfolio manifest symbol universe/order mismatch")
    if len(manifest.get("archives", [])) != TOTAL_ARCHIVE_COUNT:
        raise ValueError("qualified archive manifest does not contain 1,440 entries")
    return manifest, actual


def archive_file_for(data_root: Path, record: dict) -> Path:
    symbol = record["symbol"]
    if record["status"] == "VERIFIED_REPAIRED":
        return data_root / "qualified" / record["repaired_path"]
    return data_root / "original" / f"portfolio-archives-{symbol}" / record["path"]


def verify_archives(data_root: Path, manifest: dict) -> dict:
    verified = 0
    original = 0
    repaired = 0
    total_rows = 0
    repaired_mark_rows = 0
    for record in manifest["archives"]:
        path = archive_file_for(data_root, record)
        if not path.is_file():
            raise FileNotFoundError(f"missing archive: {path}")
        raw = path.read_bytes()
        if len(raw) != int(record["bytes"]) or _sha(raw) != record["sha256"]:
            raise ValueError(f"archive SHA-256/size mismatch: {record['path']}")
        if record["kind"] == "fundingRate":
            step = 0
        else:
            step = H1_MS if record["frame"] == "1h" else M5_MS
        year, month = map(int, record["month"].split("-"))
        start_ms, end_ms = month_bounds(year, month)
        facts = validate(raw, record["kind"], step, start_ms, end_ms)
        if not facts.get("complete") or int(facts["rows"]) != int(record["rows"]):
            raise ValueError(f"archive coverage/row mismatch: {record['path']}")
        if facts.get("first_ms") != record.get("first_ms") or facts.get("last_ms") != record.get("last_ms"):
            raise ValueError(f"archive timestamp boundary mismatch: {record['path']}")
        verified += 1
        total_rows += int(record["rows"])
        if record["status"] == "VERIFIED_REPAIRED":
            repaired += 1
            if record["kind"] == "markPriceKlines" and record["frame"] == "5m":
                repaired_mark_rows += int(record["rows"]) - 8_352
        else:
            original += 1
    if (verified, original, repaired) != (TOTAL_ARCHIVE_COUNT, ORIGINAL_ARCHIVE_COUNT, REPAIRED_ARCHIVE_COUNT):
        raise ValueError("original/repaired archive counts do not match the qualified manifest")
    if total_rows != EXPECTED_ARCHIVE_ROWS or repaired_mark_rows != 4_320:
        raise ValueError(f"archive row totals mismatch: rows={total_rows}, repaired_mark={repaired_mark_rows}")
    return {
        "status": "PASS", "archives_verified": verified,
        "original_archives": original, "repaired_archives": repaired,
        "rows_verified": total_rows, "recovered_mark_price_5m_rows": repaired_mark_rows,
        "manifest_sha256": EXPECTED_MANIFEST_SHA256,
        "repair_bundle_sha256_verified_in_prior_download_session": EXPECTED_REPAIR_BUNDLE_SHA256,
        "original_archives_modified": False,
    }


def _read_inner_csv(path: Path) -> pd.DataFrame:
    with zipfile.ZipFile(path) as archive:
        members = [x for x in archive.namelist() if x.lower().endswith(".csv")]
        if len(members) != 1:
            raise ValueError(f"{path}: expected exactly one CSV member")
        raw = archive.read(members[0])
    first = raw.splitlines()[0].decode("utf-8-sig", errors="replace").split(",")[0].strip().lower()
    header = 0 if first in {"open_time", "opentime", "calc_time", "fundingtime"} else None
    return pd.read_csv(io.BytesIO(raw), header=header)


def _load_klines(paths: list[Path]) -> pd.DataFrame:
    frames = []
    for path in sorted(paths):
        source = _read_inner_csv(path)
        if source.shape[1] < 6:
            raise ValueError(f"{path}: malformed kline archive")
        out = pd.DataFrame({
            "open_time_ms": pd.to_numeric(source.iloc[:, 0], errors="coerce"),
            "open": pd.to_numeric(source.iloc[:, 1], errors="coerce"),
            "high": pd.to_numeric(source.iloc[:, 2], errors="coerce"),
            "low": pd.to_numeric(source.iloc[:, 3], errors="coerce"),
            "close": pd.to_numeric(source.iloc[:, 4], errors="coerce"),
            "volume": pd.to_numeric(source.iloc[:, 5], errors="coerce"),
        })
        out["open_time_ms"] = out["open_time_ms"].map(_normalize_ms)
        if source.shape[1] > 7:
            out["quote_volume"] = pd.to_numeric(source.iloc[:, 7], errors="coerce")
        else:
            out["quote_volume"] = 0.0
        if source.shape[1] > 8:
            out["trades"] = pd.to_numeric(source.iloc[:, 8], errors="coerce")
        else:
            out["trades"] = 0.0
        frames.append(out)
    result = pd.concat(frames, ignore_index=True).sort_values("open_time_ms")
    if result["open_time_ms"].duplicated().any() or result.isna().any().any():
        raise ValueError("duplicate or malformed kline timestamps/values")
    result["open_time_ms"] = result["open_time_ms"].astype("int64")
    result.index = pd.to_datetime(result["open_time_ms"], unit="ms", utc=True)
    return result.drop(columns=["open_time_ms"])


def _load_funding(paths: list[Path]) -> pd.DataFrame:
    frames = []
    for path in sorted(paths):
        source = _read_inner_csv(path)
        if source.shape[1] < 3:
            raise ValueError(f"{path}: malformed funding archive")
        frames.append(pd.DataFrame({
            "time_ms": pd.to_numeric(source.iloc[:, 0], errors="coerce").map(_normalize_ms),
            "interval_hours": pd.to_numeric(source.iloc[:, 1], errors="coerce"),
            "rate": pd.to_numeric(source.iloc[:, 2], errors="coerce"),
        }))
    result = pd.concat(frames, ignore_index=True).sort_values("time_ms")
    if result["time_ms"].duplicated().any() or result.isna().any().any():
        raise ValueError("duplicate or malformed funding events")
    return result


def _decimal_lcm(values: list[Decimal]) -> Decimal:
    scale = max(max(0, -v.as_tuple().exponent) for v in values)
    factor = 10 ** scale
    ints = [int(v * factor) for v in values]
    return Decimal(math.lcm(*ints)) / Decimal(factor)


def fetch_exchange_specs() -> tuple[dict, str, dict]:
    # Immutable public-market metadata snapshot, not historical exchange rules.
    payload = json.loads("{\"source\":\"Binance public USD-M exchangeInfo via connected read-only Binance plugin\",\"serverTime\":1795602962994,\"symbols\":[{\"symbol\":\"ADAUSDT\",\"status\":\"TRADING\",\"filters\":[{\"filterType\":\"PRICE_FILTER\",\"tickSize\":\"0.00010\",\"minPrice\":\"0.00010\",\"maxPrice\":\"20000\"},{\"minQty\":\"1\",\"stepSize\":\"1\",\"maxQty\":\"30000000\",\"filterType\":\"LOT_SIZE\"},{\"stepSize\":\"1\",\"filterType\":\"MARKET_LOT_SIZE\",\"minQty\":\"1\",\"maxQty\":\"3000000\"},{\"notional\":\"5\",\"filterType\":\"MIN_NOTIONAL\"}]},{\"symbol\":\"AVAXUSDT\",\"status\":\"TRADING\",\"filters\":[{\"filterType\":\"PRICE_FILTER\",\"minPrice\":\"0.0010\",\"tickSize\":\"0.0010\",\"maxPrice\":\"2403\"},{\"minQty\":\"1\",\"filterType\":\"LOT_SIZE\",\"maxQty\":\"500000\",\"stepSize\":\"1\"},{\"maxQty\":\"50000\",\"minQty\":\"1\",\"filterType\":\"MARKET_LOT_SIZE\",\"stepSize\":\"1\"},{\"filterType\":\"MIN_NOTIONAL\",\"notional\":\"5\"}]},{\"symbol\":\"BCHUSDT\",\"status\":\"TRADING\",\"filters\":[{\"filterType\":\"PRICE_FILTER\",\"minPrice\":\"4\",\"tickSize\":\"0.01\",\"maxPrice\":\"100000\"},{\"minQty\":\"0.001\",\"maxQty\":\"20000\",\"filterType\":\"LOT_SIZE\",\"stepSize\":\"0.001\"},{\"filterType\":\"MARKET_LOT_SIZE\",\"stepSize\":\"0.001\",\"minQty\":\"0.001\",\"maxQty\":\"2000\"},{\"filterType\":\"MIN_NOTIONAL\",\"notional\":\"20\"}]},{\"symbol\":\"BNBUSDT\",\"status\":\"TRADING\",\"filters\":[{\"maxPrice\":\"100000\",\"tickSize\":\"0.010\",\"minPrice\":\"6.600\",\"filterType\":\"PRICE_FILTER\"},{\"minQty\":\"0.01\",\"maxQty\":\"100000\",\"stepSize\":\"0.01\",\"filterType\":\"LOT_SIZE\"},{\"filterType\":\"MARKET_LOT_SIZE\",\"minQty\":\"0.01\",\"maxQty\":\"2000\",\"stepSize\":\"0.01\"},{\"filterType\":\"MIN_NOTIONAL\",\"notional\":\"5\"}]},{\"symbol\":\"BTCUSDT\",\"status\":\"TRADING\",\"filters\":[{\"tickSize\":\"0.10\",\"filterType\":\"PRICE_FILTER\",\"minPrice\":\"556.80\",\"maxPrice\":\"4529764\"},{\"minQty\":\"0.001\",\"filterType\":\"LOT_SIZE\",\"maxQty\":\"1000\",\"stepSize\":\"0.001\"},{\"stepSize\":\"0.001\",\"minQty\":\"0.001\",\"filterType\":\"MARKET_LOT_SIZE\",\"maxQty\":\"120\"},{\"notional\":\"50\",\"filterType\":\"MIN_NOTIONAL\"}]},{\"symbol\":\"DOGEUSDT\",\"status\":\"TRADING\",\"filters\":[{\"minPrice\":\"0.002440\",\"maxPrice\":\"30\",\"filterType\":\"PRICE_FILTER\",\"tickSize\":\"0.000010\"},{\"minQty\":\"1\",\"stepSize\":\"1\",\"filterType\":\"LOT_SIZE\",\"maxQty\":\"300000000\"},{\"filterType\":\"MARKET_LOT_SIZE\",\"minQty\":\"1\",\"stepSize\":\"1\",\"maxQty\":\"30000000\"},{\"filterType\":\"MIN_NOTIONAL\",\"notional\":\"5\"}]},{\"symbol\":\"DOTUSDT\",\"status\":\"TRADING\",\"filters\":[{\"maxPrice\":\"200\",\"filterType\":\"PRICE_FILTER\",\"minPrice\":\"0.000100\",\"tickSize\":\"0.000100\"},{\"filterType\":\"LOT_SIZE\",\"minQty\":\"0.1\",\"stepSize\":\"0.1\",\"maxQty\":\"3000000\"},{\"maxQty\":\"300000\",\"minQty\":\"0.1\",\"filterType\":\"MARKET_LOT_SIZE\",\"stepSize\":\"0.1\"},{\"notional\":\"5\",\"filterType\":\"MIN_NOTIONAL\"}]},{\"symbol\":\"ETHUSDT\",\"status\":\"TRADING\",\"filters\":[{\"minPrice\":\"39.86\",\"maxPrice\":\"306177\",\"filterType\":\"PRICE_FILTER\",\"tickSize\":\"0.01\"},{\"filterType\":\"LOT_SIZE\",\"stepSize\":\"0.001\",\"minQty\":\"0.001\",\"maxQty\":\"10000\"},{\"filterType\":\"MARKET_LOT_SIZE\",\"stepSize\":\"0.001\",\"minQty\":\"0.001\",\"maxQty\":\"2000\"},{\"notional\":\"20\",\"filterType\":\"MIN_NOTIONAL\"}]},{\"symbol\":\"LINKUSDT\",\"status\":\"TRADING\",\"filters\":[{\"tickSize\":\"0.001\",\"minPrice\":\"0.001\",\"filterType\":\"PRICE_FILTER\",\"maxPrice\":\"100000\"},{\"maxQty\":\"700000\",\"minQty\":\"0.01\",\"filterType\":\"LOT_SIZE\",\"stepSize\":\"0.01\"},{\"minQty\":\"0.01\",\"filterType\":\"MARKET_LOT_SIZE\",\"stepSize\":\"0.01\",\"maxQty\":\"50000\"},{\"notional\":\"20\",\"filterType\":\"MIN_NOTIONAL\"}]},{\"symbol\":\"LTCUSDT\",\"status\":\"TRADING\",\"filters\":[{\"minPrice\":\"0.01\",\"tickSize\":\"0.01\",\"maxPrice\":\"100000\",\"filterType\":\"PRICE_FILTER\"},{\"minQty\":\"0.001\",\"maxQty\":\"170000\",\"stepSize\":\"0.001\",\"filterType\":\"LOT_SIZE\"},{\"stepSize\":\"0.001\",\"maxQty\":\"100000\",\"filterType\":\"MARKET_LOT_SIZE\",\"minQty\":\"0.001\"},{\"filterType\":\"MIN_NOTIONAL\",\"notional\":\"20\"}]},{\"symbol\":\"NEARUSDT\",\"status\":\"TRADING\",\"filters\":[{\"maxPrice\":\"1000\",\"filterType\":\"PRICE_FILTER\",\"minPrice\":\"0.0480\",\"tickSize\":\"0.0010\"},{\"maxQty\":\"5000000\",\"stepSize\":\"1\",\"filterType\":\"LOT_SIZE\",\"minQty\":\"1\"},{\"stepSize\":\"1\",\"filterType\":\"MARKET_LOT_SIZE\",\"maxQty\":\"400000\",\"minQty\":\"1\"},{\"filterType\":\"MIN_NOTIONAL\",\"notional\":\"5\"}]},{\"symbol\":\"SOLUSDT\",\"status\":\"TRADING\",\"filters\":[{\"filterType\":\"PRICE_FILTER\",\"tickSize\":\"0.0100\",\"maxPrice\":\"6857\",\"minPrice\":\"0.4200\"},{\"maxQty\":\"1000000\",\"stepSize\":\"0.01\",\"minQty\":\"0.01\",\"filterType\":\"LOT_SIZE\"},{\"minQty\":\"0.01\",\"stepSize\":\"0.01\",\"filterType\":\"MARKET_LOT_SIZE\",\"maxQty\":\"80000\"},{\"filterType\":\"MIN_NOTIONAL\",\"notional\":\"5\"}]},{\"symbol\":\"SUIUSDT\",\"status\":\"TRADING\",\"filters\":[{\"filterType\":\"PRICE_FILTER\",\"minPrice\":\"0.000100\",\"maxPrice\":\"2000\",\"tickSize\":\"0.000100\"},{\"maxQty\":\"10000000\",\"stepSize\":\"0.1\",\"filterType\":\"LOT_SIZE\",\"minQty\":\"0.1\"},{\"minQty\":\"0.1\",\"filterType\":\"MARKET_LOT_SIZE\",\"stepSize\":\"0.1\",\"maxQty\":\"600000\"},{\"notional\":\"5\",\"filterType\":\"MIN_NOTIONAL\"}]},{\"symbol\":\"TRXUSDT\",\"status\":\"TRADING\",\"filters\":[{\"maxPrice\":\"20000\",\"tickSize\":\"0.00001\",\"minPrice\":\"0.00132\",\"filterType\":\"PRICE_FILTER\"},{\"filterType\":\"LOT_SIZE\",\"minQty\":\"1\",\"maxQty\":\"20000000\",\"stepSize\":\"1\"},{\"maxQty\":\"5000000\",\"filterType\":\"MARKET_LOT_SIZE\",\"stepSize\":\"1\",\"minQty\":\"1\"},{\"filterType\":\"MIN_NOTIONAL\",\"notional\":\"5\"}]},{\"symbol\":\"XRPUSDT\",\"status\":\"TRADING\",\"filters\":[{\"maxPrice\":\"100000\",\"tickSize\":\"0.0001\",\"filterType\":\"PRICE_FILTER\",\"minPrice\":\"0.0143\"},{\"stepSize\":\"0.1\",\"filterType\":\"LOT_SIZE\",\"maxQty\":\"10000000\",\"minQty\":\"0.1\"},{\"stepSize\":\"0.1\",\"maxQty\":\"2000000\",\"minQty\":\"0.1\",\"filterType\":\"MARKET_LOT_SIZE\"},{\"filterType\":\"MIN_NOTIONAL\",\"notional\":\"5\"}]}]}")
    digest = _sha(json.dumps(payload, sort_keys=True, separators=(",", ":")).encode())
    rows = {x["symbol"]: x for x in payload.get("symbols", [])}
    specs = {}
    for symbol in DEFAULT_FUTURES_SYMBOLS:
        row = rows.get(symbol)
        if not row or row.get("status") != "TRADING":
            raise RuntimeError(f"{symbol}: current USD-M symbol is not TRADING")
        filters = {x.get("filterType"): x for x in row.get("filters", [])}
        tick = Decimal(str(filters["PRICE_FILTER"]["tickSize"]))
        lots = [filters[k] for k in ("LOT_SIZE", "MARKET_LOT_SIZE") if k in filters]
        steps = [Decimal(str(x["stepSize"])) for x in lots if Decimal(str(x["stepSize"])) > 0]
        if not tick.is_finite() or tick <= 0 or not steps:
            raise RuntimeError(f"{symbol}: missing current tick/lot filters")
        step = _decimal_lcm(steps)
        min_qty = max(Decimal(str(x.get("minQty", "0"))) for x in lots)
        max_values = [Decimal(str(x.get("maxQty", "0"))) for x in lots]
        positive_max = [x for x in max_values if x > 0]
        max_qty = min(positive_max) if positive_max else Decimal("1E50")
        notionals = []
        for kind in ("MIN_NOTIONAL", "NOTIONAL"):
            item = filters.get(kind)
            if item:
                value = item.get("notional", item.get("minNotional"))
                if value is not None:
                    notionals.append(Decimal(str(value)))
        if not notionals:
            raise RuntimeError(f"{symbol}: current MIN_NOTIONAL/NOTIONAL filter missing")
        min_notional = max(notionals)
        specs[symbol] = {
            "tick": tick, "step": step, "min_qty": min_qty,
            "max_qty": max_qty, "min_notional": min_notional,
            "leverage": 2, "status": row["status"],
            "current_tick_size": str(tick), "current_step_size": str(step),
            "current_min_qty": str(min_qty), "current_min_notional": str(min_notional),
        }
    return specs, digest, payload


def _aggregate_higher(frame: pd.DataFrame, rule: str, duration_ms: int):
    agg = frame.resample(rule, label="left", closed="left", origin="start_day").agg({
        "open": "first", "high": "max", "low": "min", "close": "last",
        "volume": "sum", "quote_volume": "sum", "trades": "sum",
    }).dropna(subset=["open", "high", "low", "close"])
    close_ms = agg.index.as_unit("ms").asi8 + duration_ms
    return agg, close_ms


def _indicator_diff(frame: pd.DataFrame, cfg: dict) -> dict:
    if len(frame) < 221:
        raise RuntimeError("insufficient pre-period bars for 220-bar indicator convergence test")
    full = calculate_indicators(frame, cfg)
    short = calculate_indicators(frame.iloc[-220:], cfg)
    common = short.index[-20:]
    columns = ("jaw_current", "teeth_current", "lips_current", "ao", "ac")
    maximum = 0.0
    per_column = {}
    for column in columns:
        a = pd.to_numeric(full.loc[common, column], errors="coerce").to_numpy()
        b = pd.to_numeric(short.loc[common, column], errors="coerce").to_numpy()
        valid = np.isfinite(a) & np.isfinite(b)
        if not valid.all():
            raise ValueError(f"non-finite warmup comparison for {column}")
        diff = np.abs(a[valid] - b[valid])
        denom = np.maximum(1.0, np.abs(a[valid]))
        rel = float(np.max(diff / denom)) if len(diff) else 0.0
        per_column[column] = rel
        maximum = max(maximum, rel)
    return {"max_relative_difference": maximum, "per_column": per_column,
            "status": "PASS" if maximum <= 1e-5 else "FAIL",
            "compared_tail_bars": 20, "short_warmup_bars": 220, "full_prehistory_bars": len(frame)}


def _feature_at(indicators: pd.DataFrame, close_ms: np.ndarray, known_at_ms: int) -> dict:
    index = int(np.searchsorted(close_ms, known_at_ms, side="right") - 1)
    if index < 0:
        return {"state": "UNAVAILABLE"}
    row = indicators.iloc[index]
    def value(key):
        x = row.get(key)
        try:
            x = float(x)
            return x if math.isfinite(x) else None
        except (TypeError, ValueError, OverflowError):
            return None
    return {
        "state": "CLOSED_BAR_CONTEXT", "as_of_ms": int(close_ms[index]),
        "close": value("close"), "ao": value("ao"), "ac": value("ac"),
        "jaw": value("jaw_current"), "teeth": value("teeth_current"),
        "lips": value("lips_current"), "gator_state": str(row.get("gator_state", "UNKNOWN")),
    }


def _build_symbol_signals(symbol: str, h1: pd.DataFrame, tick: float, cfg: dict,
                          start_ms: int, end_ms: int):
    indicators = calculate_indicators(h1, cfg)
    h4, h4_close = _aggregate_higher(h1, "4h", 4 * H1_MS)
    d1, d1_close = _aggregate_higher(h1, "1D", DAY_MS)
    h4_ind = calculate_indicators(h4, cfg)
    d1_ind = calculate_indicators(d1, cfg)
    regime = _market_regime_frame(h1)
    extracted = extract_causal_h1_signals(indicators, symbol=symbol, tick_size=tick, window_bars=180)
    events = defaultdict(list)
    open_ms = h1.index.as_unit("ms").asi8
    m5_timeline = np.arange(start_ms, end_ms, M5_MS, dtype=np.int64)
    close_rows = (np.searchsorted(open_ms + H1_MS, m5_timeline, side="right") - 1).astype(np.int32)
    for i, bar_open in enumerate(open_ms):
        known_at = int(bar_open + H1_MS)
        index = (known_at - start_ms) // M5_MS
        found = extracted.get(i)
        if not found or not (start_ms <= known_at < end_ms):
            continue
        row = indicators.iloc[i]
        reg = regime.iloc[i]
        def val(key):
            try:
                x = float(row.get(key))
                return x if math.isfinite(x) else None
            except (TypeError, ValueError, OverflowError):
                return None
        h4_ctx = _feature_at(h4_ind, h4_close, known_at)
        d1_ctx = _feature_at(d1_ind, d1_close, known_at)
        market_regime = f"{reg.get('trend_regime', 'UNKNOWN')}|{reg.get('volatility_regime', 'UNKNOWN')}"
        context = {
            "h1_ao": val("ao"), "h1_ac": val("ac"),
            "h1_jaw": val("jaw_current"), "h1_teeth": val("teeth_current"),
            "h1_lips": val("lips_current"), "h1_gator_state": str(row.get("gator_state", "UNKNOWN")),
            "h4": h4_ctx, "d1": d1_ctx, "market_regime": market_regime,
            "wave_phase": "NOT_INDEPENDENTLY_CLASSIFIED",
        }
        for signal in found:
            events[int(index)].append({
                "signal": signal, "known_at_ms": known_at, "context": context,
            })
    return dict(events), close_rows, indicators


def _load_data(data_root: Path, manifest: dict, specs: dict, cfg: dict,
               warmup_rows: list[dict]):
    timeline = np.arange(START_MS, END_MS, M5_MS, dtype=np.int64)
    if len(timeline) != 210_240:
        raise RuntimeError("two-year M5 timeline has unexpected length")
    records_by_symbol = defaultdict(list)
    for record in manifest["archives"]:
        records_by_symbol[record["symbol"]].append(record)
    data, signals_by_index, funding_by_index = {}, {}, {}
    warmup_sha = {(x["symbol"], x["month"]): x["sha256"] for x in warmup_rows}
    cfg_hash = _sha(json.dumps(cfg, sort_keys=True).encode())
    convergence = {}
    total_signals = 0
    funding_events = 0
    max_funding_offset = 0
    for symbol in DEFAULT_FUTURES_SYMBOLS:
        records = records_by_symbol[symbol]
        h1_paths = [archive_file_for(data_root, r) for r in records
                    if r["kind"] == "klines" and r["frame"] == "1h"]
        m5_paths = [archive_file_for(data_root, r) for r in records
                    if r["kind"] == "klines" and r["frame"] == "5m"]
        mark_paths = [archive_file_for(data_root, r) for r in records
                      if r["kind"] == "markPriceKlines" and r["frame"] == "5m"]
        funding_paths = [archive_file_for(data_root, r) for r in records
                         if r["kind"] == "fundingRate"]
        warm_paths = sorted((data_root / "warmup" / symbol).glob(f"{symbol}-1h-2024-*.zip"))
        if len(h1_paths) != 24 or len(m5_paths) != 24 or len(mark_paths) != 24 or len(funding_paths) != 24 or len(warm_paths) != 8:
            raise RuntimeError(f"{symbol}: archive-month count mismatch")
        h1 = _load_klines(warm_paths + h1_paths)
        pre = h1.loc[h1.index < pd.Timestamp(START_UTC)]
        if len(pre) < 220:
            raise RuntimeError(f"{symbol}: fewer than 220 H1 prehistory bars")
        expected_h1 = np.arange(
            int(pd.Timestamp("2024-03-01T00:00:00Z").timestamp() * 1000),
            END_MS, H1_MS, dtype=np.int64,
        )
        actual_h1 = h1.index.as_unit("ms").asi8
        if not np.array_equal(actual_h1, expected_h1):
            raise RuntimeError(f"{symbol}: H1 warm-up/evaluation timeline has gaps or duplicates")
        h1_eval_count = int(((actual_h1 >= START_MS) & (actual_h1 < END_MS)).sum())
        if h1_eval_count != 17_520:
            raise RuntimeError(f"{symbol}: evaluation H1 count mismatch")
        contract_df = _load_klines(m5_paths)
        mark_df = _load_klines(mark_paths)
        if not np.array_equal(contract_df.index.as_unit("ms").asi8, timeline):
            raise RuntimeError(f"{symbol}: contract M5 timeline is incomplete or misaligned")
        if not np.array_equal(mark_df.index.as_unit("ms").asi8, timeline):
            raise RuntimeError(f"{symbol}: Mark Price M5 timeline is incomplete or misaligned")
        funding_df = _load_funding(funding_paths)
        fund_map = defaultdict(list)
        for row in funding_df.itertuples(index=False):
            event_ms = int(row.time_ms)
            if not START_MS <= event_ms < END_MS:
                continue
            idx = int(np.searchsorted(timeline, event_ms, side="right") - 1)
            if idx < 0 or idx >= len(timeline):
                continue
            offset = int(event_ms - timeline[idx])
            if offset < 0 or offset >= M5_MS:
                raise RuntimeError(f"{symbol}: funding event cannot be aligned to containing M5 interval")
            max_funding_offset = max(max_funding_offset, offset)
            fund_map[idx].append({
                "time_ms": event_ms, "rate": float(row.rate),
                "interval_hours": int(row.interval_hours),
            })
            funding_events += 1
        funding_by_index[symbol] = dict(fund_map)
        contract = {k: contract_df[k].to_numpy(dtype=np.float64)
                    for k in ("open", "high", "low", "close")}
        mark = {
            "mark_open": mark_df["open"].to_numpy(dtype=np.float64),
            "mark_high": mark_df["high"].to_numpy(dtype=np.float64),
            "mark_low": mark_df["low"].to_numpy(dtype=np.float64),
            "mark_close": mark_df["close"].to_numpy(dtype=np.float64),
        }
        events, close_rows, indicators = _build_symbol_signals(
            symbol, h1, float(specs[symbol]["tick"]), cfg, START_MS, END_MS
        )
        signals_by_index[symbol] = events
        total_signals += sum(len(v) for v in events.values())
        data[symbol] = {
            "contract": contract, "mark": mark, "h1": h1,
            "h1_close_rows": close_rows, "h1_indicators": indicators,
        }
        pre_h1 = h1.loc[h1.index < pd.Timestamp(START_UTC)]
        pre_h4, _ = _aggregate_higher(pre_h1, "4h", 4 * H1_MS)
        pre_d1, _ = _aggregate_higher(pre_h1, "1D", DAY_MS)
        convergence[symbol] = {
            "H1": _indicator_diff(pre_h1, cfg),
            "H4": _indicator_diff(pre_h4, cfg),
            "D1": _indicator_diff(pre_d1, cfg),
        }
        for tf, result in convergence[symbol].items():
            if result["status"] != "PASS":
                raise RuntimeError(f"{symbol} {tf}: 220-bar warm-up convergence failed")
        print(f"PREPARED {symbol}: H1={len(h1)} signals={sum(len(v) for v in events.values())} funding={len(funding_df)}", flush=True)
    return {
        "timeline": timeline, "data": data,
        "signals_by_index": signals_by_index, "funding_by_index": funding_by_index,
        "convergence": convergence, "total_signals": total_signals,
        "funding_events": funding_events, "max_funding_alignment_ms": max_funding_offset,
        "indicator_config_sha256": cfg_hash,
    }


def _campaign_groups(campaigns: pd.DataFrame, field: str) -> pd.DataFrame:
    if campaigns.empty:
        return pd.DataFrame(columns=[field, "campaigns", "wins", "losses", "net_pnl_usdt", "profit_factor", "win_rate_pct"])
    closed = campaigns[campaigns["status"] == "CLOSED"].copy()
    if closed.empty:
        return pd.DataFrame(columns=[field, "campaigns", "wins", "losses", "net_pnl_usdt", "profit_factor", "win_rate_pct"])
    rows = []
    for key, group in closed.groupby(field, dropna=False):
        values = pd.to_numeric(group["net_pnl"], errors="coerce").fillna(0.0)
        wins = values[values > 1e-12]
        losses = values[values < -1e-12]
        rows.append({
            field: key, "campaigns": len(group), "wins": len(wins), "losses": len(losses),
            "net_pnl_usdt": float(values.sum()),
            "profit_factor": float(wins.sum() / abs(losses.sum())) if len(losses) else ("UNBOUNDED" if len(wins) else None),
            "win_rate_pct": float(len(wins) / len(group) * 100.0),
            "average_R": float(pd.to_numeric(group["R_multiple"], errors="coerce").mean()),
            "average_MFE_usdt": float(pd.to_numeric(group["MFE_usdt"], errors="coerce").mean()),
            "average_MAE_usdt": float(pd.to_numeric(group["MAE_usdt"], errors="coerce").mean()),
        })
    return pd.DataFrame(rows).sort_values(field)


def _monthly_metrics(equity: pd.DataFrame, initial_capital: float) -> pd.DataFrame:
    if equity.empty:
        return pd.DataFrame()
    frame = equity.copy()
    frame["datetime"] = pd.to_datetime(frame["time_ms"], unit="ms", utc=True)
    frame["month"] = (frame["datetime"] - pd.Timedelta(milliseconds=1)).dt.strftime("%Y-%m")
    rows = []
    previous = float(initial_capital)
    for month, group in frame.groupby("month", sort=True):
        end = float(group.iloc[-1]["equity"])
        rows.append({
            "month": month, "start_equity": previous, "end_equity": end,
            "net_change": end - previous,
            "return_pct": (end / previous - 1.0) * 100.0 if previous else None,
            "minimum_equity": float(group["equity"].min()),
            "maximum_drawdown_pct": float(group["drawdown_pct"].min()),
            "campaigns_active_at_month_end": int(group.iloc[-1]["active_campaigns"]),
        })
        previous = end
    return pd.DataFrame(rows)


def _write_charts(outdir: Path, equity: pd.DataFrame, campaigns: pd.DataFrame, data: dict):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    if not equity.empty:
        step = max(1, len(equity) // 30_000)
        dt = pd.to_datetime(equity["time_ms"].iloc[::step], unit="ms", utc=True)
        fig, ax = plt.subplots(figsize=(14, 6))
        ax.plot(dt, equity["equity"].iloc[::step])
        ax.set_title("WILLIAMS BOT — unified 15-symbol portfolio equity (5m mark-to-market)")
        ax.set_ylabel("Equity (USDT)")
        ax.grid(True, alpha=0.25)
        fig.tight_layout()
        fig.savefig(outdir / "equity_curve.png", dpi=140)
        plt.close(fig)

        fig, ax = plt.subplots(figsize=(14, 4))
        ax.plot(dt, equity["drawdown_pct"].iloc[::step])
        ax.set_title("Portfolio drawdown")
        ax.set_ylabel("Drawdown (%)")
        ax.grid(True, alpha=0.25)
        fig.tight_layout()
        fig.savefig(outdir / "drawdown.png", dpi=140)
        plt.close(fig)

    if campaigns.empty:
        return
    closed = campaigns[campaigns["status"] == "CLOSED"].copy()
    if closed.empty:
        return
    winners = closed.sort_values("net_pnl", ascending=False).head(5)
    losers = closed.sort_values("net_pnl", ascending=True).head(5)
    samples = pd.concat([winners, losers]).drop_duplicates("campaign_id")
    sample_dir = outdir / "trade_quality_samples"
    sample_dir.mkdir(parents=True, exist_ok=True)
    for row in samples.to_dict("records"):
        symbol = row["symbol"]
        h1 = data[symbol]["h1"]
        start = int(row["entry_time_ms"] or row["signal_time_ms"]) - 48 * H1_MS
        end = int(row["exit_time_ms"] or row["entry_time_ms"]) + 12 * H1_MS
        ms = h1.index.as_unit("ms").asi8
        view = h1.loc[(ms >= start) & (ms <= end)]
        if view.empty:
            continue
        fig, ax = plt.subplots(figsize=(12, 5))
        ax.plot(view.index, view["close"], label="H1 close")
        ax.axhline(float(row["entry_price"]), linestyle="--", label="entry")
        ax.axhline(float(row["initial_stop"]), linestyle=":", label="initial STOP")
        if row.get("exit_price") is not None and not pd.isna(row.get("exit_price")):
            ax.axhline(float(row["exit_price"]), linestyle="-.", label="exit")
        ax.set_title(f"{symbol} {row['direction']} {row['initial_wise_man']} | net={float(row['net_pnl']):.2f} USDT")
        ax.grid(True, alpha=0.25)
        ax.legend()
        fig.tight_layout()
        safe = str(row["campaign_id"]).replace(":", "_").replace("/", "_")
        fig.savefig(sample_dir / f"{safe}.png", dpi=130)
        plt.close(fig)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-root", type=Path, default=Path("data"))
    parser.add_argument("--outdir", type=Path, default=Path("build/portfolio-backtest"))
    parser.add_argument("--capital", type=float, default=5000.0,
                        help="research-only unified wallet balance in USDT; not the user's account balance")
    parser.add_argument("--smoke-days", type=int, default=30)
    parser.add_argument("--skip-sensitivity", action="store_true")
    args = parser.parse_args()

    started = time.time()
    args.outdir.mkdir(parents=True, exist_ok=True)
    manifest, manifest_sha = load_manifest(args.data_root)
    archive_report = verify_archives(args.data_root, manifest)
    (args.outdir / "archive_validation.json").write_text(json.dumps(archive_report, indent=2) + "\n")
    warmup_rows = download_warmup(args.data_root)
    (args.outdir / "warmup_manifest.json").write_text(json.dumps(warmup_rows, indent=2) + "\n")

    specs, exchange_info_sha, exchange_info = fetch_exchange_specs()
    (args.outdir / "exchange_info_snapshot.json").write_text(json.dumps(exchange_info, indent=2) + "\n")
    cfg = canonical_indicator_config_from_env()
    prepared = _load_data(args.data_root, manifest, specs, cfg, warmup_rows)

    common = {
        "symbols": list(DEFAULT_FUTURES_SYMBOLS), "data": prepared["data"],
        "timeline": prepared["timeline"], "signals_by_index": prepared["signals_by_index"],
        "funding_by_index": prepared["funding_by_index"], "specs": specs,
        "initial_capital": args.capital,
    }
    smoke_end = min(len(prepared["timeline"]), max(1, args.smoke_days) * DAY_MS // M5_MS)
    smoke = PortfolioSimulator(**common, collect=False).run(end_index=smoke_end)
    smoke_metrics = smoke["metrics"]
    if smoke_metrics["max_active_campaigns"] > 10:
        raise AssertionError("smoke test exceeded the ten-campaign limit")
    if not math.isfinite(float(smoke_metrics["final_equity_usdt"])):
        raise AssertionError("smoke test produced non-finite equity")
    print("PORTFOLIO_SMOKE_PASS " + json.dumps({
        "days": args.smoke_days, "entries": smoke_metrics["entry_fills"],
        "add_ons": smoke_metrics["add_on_fills"], "closed": smoke_metrics["closed_campaigns"],
        "funding_events_in_source": prepared["funding_events"],
        "max_active": smoke_metrics["max_active_campaigns"],
    }, sort_keys=True), flush=True)

    baseline = PortfolioSimulator(**common, collect=True).run()
    (args.outdir / "baseline_metrics_unqualified.json").write_text(json.dumps(baseline["metrics"], indent=2, default=str) + "\n")
    repeat = PortfolioSimulator(**common, collect=False).run()
    if baseline["digest"] != repeat["digest"]:
        raise AssertionError("identical-input portfolio replay is not reproducible")
    print("PORTFOLIO_REPRODUCIBILITY_PASS " + baseline["digest"], flush=True)
    bm = baseline["metrics"]
    fees = math.fsum(float(row["fee"]) for row in baseline["fills"])
    gross = math.fsum(float(row["gross_pnl"]) for row in baseline["fills"])
    expected_wallet = args.capital + gross - fees + float(bm["funding_usdt"])
    ledger_ok = (math.isclose(fees, bm["commission_usdt"], rel_tol=1e-10, abs_tol=1e-8)
                 and math.isclose(expected_wallet, bm["final_wallet_balance_usdt"], rel_tol=1e-10, abs_tol=1e-8)
                 and len(baseline["fills"]) == bm["entry_fills"] + bm["add_on_fills"] + bm["closed_campaigns"])
    ledger = {"status": "PASS" if ledger_ok else "FAIL", "computed_wallet": expected_wallet,
              "reported_wallet": bm["final_wallet_balance_usdt"], "computed_fees": fees,
              "reported_fees": bm["commission_usdt"], "fill_count": len(baseline["fills"])}
    (args.outdir / "ledger_invariants.json").write_text(json.dumps(ledger, indent=2) + "\n")
    if not ledger_ok:
        raise AssertionError("portfolio fill ledger invariant failed")
    (args.outdir / "baseline_metrics.json").write_text(json.dumps(bm, indent=2, default=str) + "\n")

    sensitivity = []
    if not args.skip_sensitivity:
        variants = (
            ("TC2_TRAIL_3", 3, 0.0005, 0.0005, args.capital),
            ("FOUR_BAR_ENGINEERING_NON_TC2", 4, 0.0005, 0.0005, args.capital),
            ("COST_STRESS_2X", 5, 0.0006, 0.0010, args.capital),
            ("CAPITAL_SCALE_1000", 5, 0.0005, 0.0005, 1000.0),
            ("CAPITAL_SCALE_10000", 5, 0.0005, 0.0005, 10000.0),
        )
        for name, trail, fee, slip, capital in variants:
            variant_common = dict(common)
            variant_common["initial_capital"] = capital
            result = PortfolioSimulator(**variant_common, fee_rate=fee, slippage_rate=slip,
                                         trail_bars=trail, collect=False).run()
            sensitivity.append({"scenario": name, "research_initial_capital_usdt": capital,
                                **result["metrics"]})
            print(f"SENSITIVITY {name}: net={result['metrics']['total_net_pnl_usdt']:.6f} "
                  f"PF={result['metrics']['profit_factor_net']} DD={result['metrics']['maximum_drawdown_pct']:.4f}%", flush=True)

    campaigns = pd.DataFrame(baseline["campaigns"])
    equity = pd.DataFrame(baseline["equity_curve"])
    signals = pd.DataFrame(baseline["signals"])
    fills = pd.DataFrame(baseline["fills"])
    risk_events = pd.DataFrame(baseline["risk_events"])
    events = pd.DataFrame(baseline["events"])
    daily = pd.DataFrame(baseline["daily"])
    monthly = _monthly_metrics(equity, args.capital)

    if not campaigns.empty:
        campaigns["wise_man"] = campaigns["initial_wise_man"].map({
            "REVERSAL": "WM1", "SUPER_AO": "WM2", "FRACTAL": "WM3",
            "THREE_GREEN_BARS": "TC2_CONTINUATION_ADD_ON",
        }).fillna("UNMAPPED_TC2_SIGNAL")
    for name, frame in (
        ("campaigns.csv", campaigns), ("equity_curve.csv", equity),
        ("signals.csv", signals), ("fills.csv", fills),
        ("risk_events.csv", risk_events), ("events.jsonl", events),
        ("daily_risk.csv", daily), ("monthly_metrics.csv", monthly),
    ):
        path = args.outdir / name
        if name == "events.jsonl":
            with path.open("w") as handle:
                for row in frame.to_dict("records"):
                    handle.write(json.dumps(row, sort_keys=True, default=str) + "\n")
        else:
            frame.to_csv(path, index=False)

    group_frames = {
        "symbol_metrics.csv": _campaign_groups(campaigns, "symbol"),
        "direction_metrics.csv": _campaign_groups(campaigns, "direction"),
        "wise_man_metrics.csv": _campaign_groups(campaigns, "wise_man") if "wise_man" in campaigns else pd.DataFrame(),
        "exit_reason_metrics.csv": _campaign_groups(campaigns, "exit_reason"),
        "market_regime_metrics.csv": _campaign_groups(campaigns, "market_regime"),
    }
    for name, frame in group_frames.items():
        frame.to_csv(args.outdir / name, index=False)

    metrics = baseline["metrics"]
    metrics["source_period_utc"] = f"[{START_UTC},{END_UTC})"
    metrics["symbols"] = list(DEFAULT_FUTURES_SYMBOLS)
    metrics["portfolio_model"] = "ONE_SHARED_USDT_WALLET_15_SYMBOLS_MAX_10_CAMPAIGNS"
    metrics["research_capital_not_user_balance"] = True
    metrics["reproducibility"] = {"status": "PASS", "first_digest": baseline["digest"],
                                  "repeat_digest": repeat["digest"]}
    metrics["archive_validation"] = archive_report
    metrics["smoke_test"] = smoke_metrics
    metrics["sensitivity_scenarios"] = sensitivity
    (args.outdir / "metrics.json").write_text(json.dumps(metrics, indent=2, default=str) + "\n")
    (args.outdir / "sensitivity.json").write_text(json.dumps(sensitivity, indent=2, default=str) + "\n")

    _write_charts(args.outdir, equity, campaigns, prepared["data"])

    run_manifest = {
        "schema": "WILLIAMS_UNIFIED_PORTFOLIO_BACKTEST_V1",
        "status": "COMPLETED_RESEARCH_REPLAY",
        "code_sha": os.getenv("GITHUB_SHA", "LOCAL_UNPINNED"),
        "github_run_id": os.getenv("GITHUB_RUN_ID"),
        "source_period_utc": f"[{START_UTC},{END_UTC})",
        "symbols": list(DEFAULT_FUTURES_SYMBOLS),
        "initial_capital_usdt": args.capital,
        "initial_capital_is_user_balance": False,
        "source_archive_manifest_sha256": manifest_sha,
        "source_archive_bundle_sha256_verified_in_prior_download_session": EXPECTED_REPAIR_BUNDLE_SHA256,
        "original_archive_run_id": ORIGINAL_RUN_ID,
        "repair_archive_run_id": REPAIR_RUN_ID,
        "repair_artifact_id": REPAIR_ARTIFACT_ID,
        "archive_validation": archive_report,
        "warmup": {
            "start_utc": "2024-03-01T00:00:00Z",
            "end_exclusive_utc": "2024-11-01T00:00:00Z",
            "months_per_symbol": 8, "archives": len(warmup_rows),
            "sha256_records": warmup_rows,
            "convergence": prepared["convergence"],
            "status": "PASS",
        },
        "indicator_config": cfg,
        "indicator_config_sha256": prepared["indicator_config_sha256"],
        "signals_generated": prepared["total_signals"],
        "funding_events_loaded": prepared["funding_events"],
        "maximum_funding_alignment_offset_ms": prepared["max_funding_alignment_ms"],
        "current_exchange_info_sha256": exchange_info_sha,
        "current_exchange_filters_are_not_historical_facts": True,
        "exchange_filters": {
            symbol: {
                "tick": str(specs[symbol]["tick"]), "step": str(specs[symbol]["step"]),
                "min_qty": str(specs[symbol]["min_qty"]),
                "max_qty": str(specs[symbol]["max_qty"]),
                "min_notional": str(specs[symbol]["min_notional"]),
                "leverage_assumption": specs[symbol]["leverage"],
            } for symbol in DEFAULT_FUTURES_SYMBOLS
        },
        "execution_assumptions": {
            "baseline_taker_fee_per_side": 0.0005,
            "baseline_slippage_per_side": 0.0005,
            "sizing_cost_buffer": 0.0035,
            "entry_trigger": "contract M5 OHLC",
            "protective_stop_trigger": "Mark Price M5 OHLC",
            "stop_fill": "worst observable contract price in the M5 candle plus adverse slippage",
            "funding": "archived fundingRate events mapped to the containing M5 interval open; Mark Price open proxy and maximum timestamp offset are recorded",
            "utc_midnight_equity": "5m Mark Price open proxy; exact 1m midnight Mark Price not archived",
            "partial_fills": "not simulated; no tick/order-book depth in archive",
            "liquidation": "historical leverage brackets unavailable; 2x production default and shared 50% margin cap used",
            "historical_tick_step_min_notional": "not available; current exchangeInfo used only as a documented proxy",
            "opposite_signal_exit": "causal H1 signal trigger monitored on M5 OHLC; profit-after-cost gate applied; conservative OHLC ambiguity handling",
            "emergency_breaker": "5% Model B latch blocks/cancels new exposure; existing positions remain under STOP; Android-side emergency flatten not represented",
        },
        "model_b": {
            "daily_entry_loss_limit_pct": 3.0,
            "emergency_breaker_pct": 5.0,
            "portfolio_margin_limit_pct": 50.0,
            "portfolio_risk_cap_pct": 0.6,
            "campaign_risk_cap_pct": 0.6,
            "initial_unit_risk_pct": 0.04,
            "per_entry_risk_cap_pct": 0.25,
            "max_consecutive_losses": 3,
        },
        "trade_methodology": {
            "new_campaign_source": "TC2 only",
            "first_entry": "earliest valid WM1/WM2/WM3 signal",
            "pyramiding": "TC2 1:5:4:3:2 exact quantities; risk/margin may veto, never resize",
            "trailing": "production plan_williams_stop, baseline 5 closed H1 bars; re-evaluated every M5 interval; 3-bar source-policy and 4-bar non-TC2 engineering sensitivities included",
            "M15_confirmation": False,
            "D1_H4_directional_gate": False,
            "lookahead_control": "production extract_causal_h1_signals closed-prefix replay; M5 event after H1 close",
            "D1_H4_context": "causally closed H4/D1 indicators logged; not used as directional entry veto",
        },
        "result_metrics": metrics,
        "limitations": [
            "Python historical simulator does not prove Android execution parity.",
            "Historical Binance tick/step/minNotional/leverage brackets are unavailable.",
            "No tick-level order book, exact spread, partial fills, or intra-M5 event order.",
            "5m Mark Price open is a proxy for exact UTC-midnight 1m Mark Price.",
            "Wave phase is not independently classified; no expert-labelled wave accuracy is claimed.",
            "The four-book evidence audit and non-TC2 observational replay remain separate qualification tasks.",
            "Research backtest is not Demo E2E and does not authorize real funds.",
        ],
        "elapsed_seconds": round(time.time() - started, 3),
    }
    (args.outdir / "run_manifest.json").write_text(json.dumps(run_manifest, indent=2, default=str) + "\n")
    print("PORTFOLIO_BACKTEST_COMPLETE " + json.dumps({
        "initial_capital": args.capital,
        "final_equity": metrics["final_equity_usdt"],
        "net_pnl": metrics["total_net_pnl_usdt"],
        "return_pct": metrics["return_pct"],
        "closed_campaigns": metrics["closed_campaigns"],
        "profit_factor": metrics["profit_factor_net"],
        "max_drawdown_pct": metrics["maximum_drawdown_pct"],
        "reproducibility": "PASS",
        "output": str(args.outdir),
    }, sort_keys=True), flush=True)


if __name__ == "__main__":
    main()
