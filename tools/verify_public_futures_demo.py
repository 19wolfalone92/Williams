"""Public, unauthenticated Binance USD-M Futures DEMO preflight.

NO API keys, NO order endpoints, NO POST/DELETE, NO real-money host, and
NO permission to infer the operator's private positions or protective STOP.
A successful public probe is not signed Binance account/trade E2E.
"""
from __future__ import annotations

import json
import sys
import time
from urllib.parse import urlencode
from urllib.request import Request, urlopen

DEMO = "https://demo-fapi.binance.com"
SYMBOLS = ("BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT",
           "DOGEUSDT", "ADAUSDT", "LINKUSDT", "AVAXUSDT", "LTCUSDT")
HOUR_MS = 3_600_000


def validate_contracts(exchange: dict) -> None:
    rows = exchange.get("symbols")
    if not isinstance(rows, list):
        raise ValueError("Demo USD-M exchangeInfo symbols missing")
    for symbol in SYMBOLS:
        matches = [x for x in rows if isinstance(x, dict) and x.get("symbol") == symbol]
        if len(matches) != 1:
            raise ValueError(f"{symbol}: missing or ambiguous in Demo exchangeInfo")
        row = matches[0]
        if (row.get("status") != "TRADING" or
                row.get("contractType") != "PERPETUAL" or
                row.get("quoteAsset") != "USDT"):
            raise ValueError(f"{symbol}: not active USDT perpetual")
        filters = {f.get("filterType") for f in row.get("filters", [])
                   if isinstance(f, dict)}
        if not {"PRICE_FILTER", "LOT_SIZE"}.issubset(filters):
            raise ValueError(f"{symbol}: missing tick/quantity filter")


def validate_closed_h1(symbol: str, rows: list, exchange_ms: int) -> dict:
    if len(rows) < 2:
        raise ValueError(f"{symbol}: fewer than two Demo H1 candles")
    valid = []
    previous_open = None
    for row in rows:
        if not isinstance(row, list) or len(row) < 7:
            raise ValueError(f"{symbol}: malformed H1 kline")
        opened = int(row[0])
        closed_at = int(row[6])
        if opened < 0 or opened % HOUR_MS != 0 or closed_at != opened + HOUR_MS - 1:
            raise ValueError(f"{symbol}: H1 candle timestamps invalid")
        if previous_open is not None and opened - previous_open != HOUR_MS:
            raise ValueError(f"{symbol}: H1 history gap/out-of-order bars")
        previous_open = opened
        opn, high, low, close = (float(row[i]) for i in (1, 2, 3, 4))
        if min(opn, high, low, close) <= 0 or high < max(opn, low, close) or low > min(opn, close):
            raise ValueError(f"{symbol}: malformed OHLC extrema")
        if closed_at < exchange_ms:
            valid.append((opened, closed_at))
    if not valid or exchange_ms - valid[-1][1] > 2 * HOUR_MS:
        raise ValueError(f"{symbol}: latest exchange-confirmed closed H1 is stale")
    return {"symbol": symbol, "last_closed_open_ms": valid[-1][0],
            "last_closed_time_ms": valid[-1][1], "closed_bars": len(valid)}


def read_public(path: str, params: dict | None = None) -> object:
    # Endpoint allowlist guards this check even if callers are changed later.
    if path not in {"/fapi/v1/time", "/fapi/v1/exchangeInfo", "/fapi/v1/klines"}:
        raise ValueError("Public Demo probe may only call three read-only Futures routes")
    query = "?" + urlencode(params) if params else ""
    req = Request(DEMO + path + query, method="GET",
                  headers={"Accept": "application/json", "User-Agent": "Williams-Demo-ReadOnly-Preflight/1"})
    with urlopen(req, timeout=15) as result:
        if result.status != 200:
            raise RuntimeError(f"Demo public GET {path} returned HTTP {result.status}")
        return json.load(result)


def run() -> dict:
    start = read_public("/fapi/v1/time")
    if not isinstance(start, dict) or int(start.get("serverTime", 0)) <= 0:
        raise ValueError("Demo server time unavailable")
    info = read_public("/fapi/v1/exchangeInfo")
    if not isinstance(info, dict):
        raise ValueError("Demo exchangeInfo malformed")
    validate_contracts(info)
    klines = {s: read_public("/fapi/v1/klines",
                            {"symbol": s, "interval": "1h", "limit": "3"}) for s in SYMBOLS}
    stop = read_public("/fapi/v1/time")
    if not isinstance(stop, dict) or int(stop.get("serverTime", 0)) < int(start["serverTime"]):
        raise ValueError("Demo server clock regressed or missing")
    now_ms = int(stop["serverTime"])
    checks = [validate_closed_h1(s, klines[s], now_ms) for s in SYMBOLS]
    return {"result": "PUBLIC_DEMO_FUTURES_READ_ONLY_PASS",
            "api_host": DEMO, "signed_account_verified": False,
            "orders_executed": False, "protective_stops_verified": False,
            "physical_android_recovery_verified": False,
            "checked_usdt_perpetuals": len(checks),
            "exchange_time_ms": now_ms, "markets": checks}


if __name__ == "__main__":
    try:
        print(json.dumps(run(), ensure_ascii=False, sort_keys=True))
    except Exception as exc:
        print(f"PUBLIC_DEMO_FUTURES_READ_ONLY_FAIL: {type(exc).__name__}: {exc}", file=sys.stderr)
        sys.exit(1)
