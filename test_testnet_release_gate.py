"""Williams USDⓈ-M Futures Testnet release gate.

Offline CI validates the contract.  An authenticated read-only Futures Demo
check is available only with explicit WILLIAMS_TESTNET_E2E=1.  No order
mutation is performed by the read-only gate.
"""
from __future__ import annotations

import math
import os
from dataclasses import dataclass

from binance_futures_client import BinanceFuturesClient


@dataclass(frozen=True)
class GateStep:
    id: str
    phase: str
    description: str
    mode: str  # OFFLINE, TESTNET_READ_ONLY, TESTNET_TRADE


GATE_STEPS = (
    GateStep("P01", "environment", "USD-M Futures Demo endpoint is selected in Testnet mode", "OFFLINE"),
    GateStep("P02", "environment", "Live Futures endpoint requires explicit live configuration", "OFFLINE"),
    GateStep("P03", "auth", "Futures API credentials are accepted by Binance", "TESTNET_READ_ONLY"),
    GateStep("P04", "auth", "Request signature, timestamp and recvWindow are valid", "TESTNET_READ_ONLY"),
    GateStep("P05", "account", "USD-M Futures account is reachable", "TESTNET_READ_ONLY"),
    GateStep("P06", "account", "One-Way position mode is enabled", "TESTNET_READ_ONLY"),
    GateStep("P07", "account", "Multi-Assets mode is disabled for the campaign runtime", "TESTNET_READ_ONLY"),
    GateStep("P08", "market", "exchangeInfo exposes valid Futures symbol filters", "TESTNET_READ_ONLY"),
    GateStep("P09", "market", "Closed-candle market data is fresh and internally consistent", "TESTNET_READ_ONLY"),
    GateStep("P10", "scanner", "Scanner selects active USDⓈ-M USDT perpetuals", "OFFLINE"),
    GateStep("P11", "strategy", "LONG and SHORT Williams signal extractors are present", "OFFLINE"),
    GateStep("P12", "strategy", "First presenting Wise-Man can start a campaign", "OFFLINE"),
    GateStep("P13", "strategy", "Super AO can be the first presenting entry signal", "OFFLINE"),
    GateStep("P14", "strategy", "Later Super AO/fractal signals are add-on candidates", "OFFLINE"),
    GateStep("P15", "risk", "Risk sizing is based on structural stop distance", "OFFLINE"),
    GateStep("P16", "risk", "1:5:4:3:2 campaign allocation remains within the hard risk cap", "OFFLINE"),
    GateStep("P17", "execution", "Every order mutation passes through the execution barrier", "OFFLINE"),
    GateStep("P18", "execution", "Conditional entry uses STOP_MARKET and durable client order id", "OFFLINE"),
    GateStep("P19", "execution", "Actual Futures fills determine campaign position quantity and average entry", "OFFLINE"),
    GateStep("P20", "protection", "Protection is reduce-only and covers the actual campaign quantity", "OFFLINE"),
    GateStep("P21", "protection", "Protection replacement is new-stop-first and recovery-safe", "OFFLINE"),
    GateStep("P22", "protection", "Protective stop cannot be loosened by trailing logic", "OFFLINE"),
    GateStep("P23", "recovery", "Crash after conditional entry is reconciled from Binance order state", "OFFLINE"),
    GateStep("P24", "recovery", "Crash after fill is reconciled from Binance position state", "OFFLINE"),
    GateStep("P25", "recovery", "Bot-owned exit fills are attributable to the exact campaign", "OFFLINE"),
    GateStep("P26", "recovery", "Uncertain position/order state fails closed into RECONCILE_REQUIRED", "OFFLINE"),
    GateStep("P27", "exit", "Williams structural exit uses the last 3/5-bar extreme", "OFFLINE"),
    GateStep("P28", "exit", "No fixed take-profit is required by the canonical campaign", "OFFLINE"),
    GateStep("P29", "android", "Android starts the local autonomous Futures runtime from its foreground service; no remote backend is required", "OFFLINE"),
    GateStep("P30", "release", "Only a clean final gate permits controlled Testnet trading", "TESTNET_TRADE"),
)


APPROVED_FUTURES_SYMBOLS = (
    "BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT",
    "DOGEUSDT", "ADAUSDT", "LINKUSDT", "AVAXUSDT", "LTCUSDT",
)


def _validate_symbol_contract(client, symbol: str) -> dict:
    exchange = client.exchange_info(symbol)
    rows = (exchange or {}).get("symbols", [])
    row = next(
        (item for item in rows if isinstance(item, dict)
         and str(item.get("symbol", "")).upper() == symbol),
        None,
    )
    if row is None:
        raise RuntimeError(f"{symbol}: not present in Futures exchangeInfo")
    if str(row.get("status", "")).upper() != "TRADING":
        raise RuntimeError(f"{symbol}: Futures contract status is not TRADING")
    if str(row.get("contractType", "")).upper() != "PERPETUAL":
        raise RuntimeError(f"{symbol}: contract is not a perpetual")
    if str(row.get("quoteAsset", "")).upper() != "USDT":
        raise RuntimeError(f"{symbol}: contract is not USDT-quoted")
    filters = {
        str(item.get("filterType", "")).upper()
        for item in row.get("filters", []) if isinstance(item, dict)
    }
    if not {"PRICE_FILTER", "LOT_SIZE"}.issubset(filters):
        raise RuntimeError(f"{symbol}: PRICE_FILTER or LOT_SIZE missing from exchangeInfo")
    return {
        "symbol": symbol,
        "status": row["status"],
        "contract_type": row["contractType"],
        "quote_asset": row["quoteAsset"],
        "filters": sorted(filters),
    }


def live_e2e_enabled() -> bool:
    return os.getenv("WILLIAMS_TESTNET_E2E", "").strip() == "1"


def assert_live_opt_in() -> None:
    if not live_e2e_enabled():
        raise RuntimeError(
            "Live Testnet execution is disabled. Set WILLIAMS_TESTNET_E2E=1 explicitly."
        )
    if os.getenv("TESTNET", "true").strip().lower() != "true":
        raise RuntimeError("Release Gate refuses to run: TESTNET must be true.")
    if not os.getenv("BINANCE_API_KEY") or not os.getenv("BINANCE_API_SECRET"):
        raise RuntimeError(
            "Release Gate requires BINANCE_API_KEY and BINANCE_API_SECRET."
        )


def run_testnet_read_only(symbol: str = "BTCUSDT") -> dict:
    """Run authenticated, non-mutating USD-M Futures Demo checks."""
    assert_live_opt_in()
    client = BinanceFuturesClient(
        os.environ["BINANCE_API_KEY"],
        os.environ["BINANCE_API_SECRET"],
        testnet=True,
    )
    if client.base_url != "https://demo-fapi.binance.com":
        raise RuntimeError(
            f"Refusing Futures Demo Gate: unexpected endpoint {client.base_url!r}"
        )
    requested_symbol = symbol.upper()
    if requested_symbol not in APPROVED_FUTURES_SYMBOLS:
        raise RuntimeError(f"{requested_symbol}: outside the approved ten-symbol Futures universe")

    # Synchronize the client's signed-request clock before the account read.
    initial_clock = client.sync_time()
    if not isinstance(initial_clock, dict) or int(initial_clock.get("serverTime", 0) or 0) <= 0:
        raise RuntimeError("Futures Demo exchange clock is missing or invalid")

    account = client.account()
    if not isinstance(account, dict):
        raise RuntimeError("Futures Demo account response is malformed")
    for field in ("availableBalance", "totalWalletBalance", "totalMarginBalance"):
        try:
            value = float(account[field])
        except (KeyError, TypeError, ValueError) as exc:
            raise RuntimeError(f"Futures Demo account field {field} is missing or invalid") from exc
        if not math.isfinite(value) or value < 0:
            raise RuntimeError(f"Futures Demo account field {field} is not a finite non-negative number")
    if float(account["availableBalance"]) <= 0:
        raise RuntimeError("Futures Demo account has no available balance; fund the Testnet account before trade tests")

    position_mode = client.get_position_mode()
    multi_assets = client.get_multi_assets_mode()
    if bool((position_mode or {}).get("dualSidePosition")):
        raise RuntimeError("Futures Demo account is in Hedge Mode; Williams requires One-Way mode.")
    if bool((multi_assets or {}).get("multiAssetsMargin")):
        raise RuntimeError("Futures Demo account has Multi-Assets Mode enabled; Williams requires Single-Asset mode.")

    contracts = [_validate_symbol_contract(client, item) for item in APPROVED_FUTURES_SYMBOLS]
    # Gather fresh market snapshots for every approved contract. Use a second
    # exchange-time read after the market requests so the freshness checks are
    # compared with a clock at least as recent as the market-data responses.
    raw_market_data = [
        (item, client.mark_price(item), client.klines(item, "1h", limit=3))
        for item in APPROVED_FUTURES_SYMBOLS
    ]
    final_clock = client.sync_time()
    try:
        exchange_time_ms = int(final_clock["serverTime"])
    except (KeyError, TypeError, ValueError) as exc:
        raise RuntimeError("Futures Demo final exchange clock is missing or invalid") from exc
    if exchange_time_ms <= 0:
        raise RuntimeError("Futures Demo final exchange clock must be positive")
    market_data = [
        _validate_h1_market_snapshot(item, mark, candles, exchange_time_ms)
        for item, mark, candles in raw_market_data
    ]
    return {
        "ready": True,
        "account_available": True,
        "available_balance": float(account["availableBalance"]),
        "symbol": requested_symbol,
        "endpoint": client.base_url,
        "exchange_server_time_ms": exchange_time_ms,
        "position_mode": position_mode,
        "multi_assets_mode": multi_assets,
        "approved_contracts_checked": len(contracts),
        "market_data_checked": len(market_data),
        "contracts": contracts,
        "market_data": market_data,
    }


def _validate_h1_market_snapshot(symbol: str, mark: dict, candles, exchange_time_ms: int) -> dict:
    """Fail closed unless mark price and at least one recent, closed H1 candle are valid."""
    if not isinstance(mark, dict):
        raise RuntimeError(f"{symbol}: Futures mark-price response is malformed")
    try:
        mark_price = float(mark["markPrice"])
        mark_time_ms = int(mark["time"])
    except (KeyError, TypeError, ValueError) as exc:
        raise RuntimeError(f"{symbol}: Futures mark price/time is missing or invalid") from exc
    if not math.isfinite(mark_price) or mark_price <= 0:
        raise RuntimeError(f"{symbol}: Futures mark price is not finite and positive")
    if mark_time_ms <= 0 or mark_time_ms > exchange_time_ms + 2_000:
        raise RuntimeError(f"{symbol}: Futures mark-price timestamp is invalid or in the future")
    mark_age_ms = exchange_time_ms - mark_time_ms
    if mark_age_ms > 5 * 60 * 1000:
        raise RuntimeError(f"{symbol}: Futures mark price is stale ({mark_age_ms} ms old)")

    if not isinstance(candles, list) or len(candles) < 2:
        raise RuntimeError(f"{symbol}: Futures H1 history needs at least two candles to identify a closed bar")
    hour_ms = 60 * 60 * 1000
    previous_open_ms = None
    closed = []
    for index, candle in enumerate(candles):
        if not isinstance(candle, (list, tuple)) or len(candle) < 7:
            raise RuntimeError(f"{symbol}: H1 candle {index} has a malformed payload")
        try:
            open_ms = int(candle[0])
            o, h, low, close = (float(candle[i]) for i in range(1, 5))
            close_ms = int(candle[6])
        except (TypeError, ValueError) as exc:
            raise RuntimeError(f"{symbol}: H1 candle {index} contains invalid values") from exc
        prices = (o, h, low, close)
        if not all(math.isfinite(value) and value > 0 for value in prices):
            raise RuntimeError(f"{symbol}: H1 candle {index} contains a non-finite/non-positive OHLC value")
        if close_ms != open_ms + hour_ms - 1:
            raise RuntimeError(f"{symbol}: H1 candle {index} does not span exactly one UTC hour")
        if low > min(o, close) or h < max(o, close) or low > h:
            raise RuntimeError(f"{symbol}: H1 candle {index} has internally inconsistent OHLC values")
        if previous_open_ms is not None and open_ms != previous_open_ms + hour_ms:
            raise RuntimeError(f"{symbol}: H1 candle history is not strictly hourly and contiguous")
        previous_open_ms = open_ms
        if close_ms < exchange_time_ms:
            closed.append({
                "open_time_ms": open_ms,
                "close_time_ms": close_ms,
                "open": o,
                "high": h,
                "low": low,
                "close": close,
            })
    if not closed:
        raise RuntimeError(f"{symbol}: no fully closed H1 candle is available")
    latest = closed[-1]
    candle_age_ms = exchange_time_ms - (latest["close_time_ms"] + 1)
    if candle_age_ms < 0 or candle_age_ms > 61 * 60 * 1000:
        raise RuntimeError(f"{symbol}: latest closed H1 candle is stale ({candle_age_ms} ms old)")
    return {
        "symbol": symbol,
        "mark_price": mark_price,
        "mark_time_ms": mark_time_ms,
        "mark_age_ms": mark_age_ms,
        "latest_closed_h1_open_time_ms": latest["open_time_ms"],
        "latest_closed_h1_close_time_ms": latest["close_time_ms"],
        "latest_closed_h1_close": latest["close"],
        "closed_candle_age_ms": candle_age_ms,
    }

def test_gate_manifest_has_all_required_release_steps():
    assert len(GATE_STEPS) == 30
    assert [step.id for step in GATE_STEPS] == [f"P{i:02d}" for i in range(1, 31)]
    assert {step.mode for step in GATE_STEPS} == {
        "OFFLINE",
        "TESTNET_READ_ONLY",
        "TESTNET_TRADE",
    }


def test_android_release_contract_describes_autonomous_local_runtime():
    step = next(step for step in GATE_STEPS if step.id == "P29")
    assert "local autonomous Futures runtime" in step.description
    assert "no remote backend is required" in step.description
    assert "remote HTTPS cockpit" not in step.description


def test_gate_contains_futures_protection_safety_cases():
    text = " ".join(step.description.lower() for step in GATE_STEPS)
    assert "stop_market" in text
    assert "reduce-only" in text
    assert "new-stop-first" in text
    assert "reconcile_required" in text


def test_futures_client_uses_demo_endpoint_in_testnet():
    testnet = BinanceFuturesClient("k", "s", testnet=True)
    live = BinanceFuturesClient("k", "s", testnet=False)
    assert testnet.base_url == "https://demo-fapi.binance.com"
    assert live.base_url == "https://fapi.binance.com"


def test_live_gate_is_opt_in_only(monkeypatch):
    monkeypatch.delenv("WILLIAMS_TESTNET_E2E", raising=False)
    with __import__("pytest").raises(RuntimeError):
        assert_live_opt_in()


def test_release_gate_never_defaults_to_live(monkeypatch):
    monkeypatch.delenv("WILLIAMS_TESTNET_E2E", raising=False)
    monkeypatch.delenv("TESTNET", raising=False)
    assert live_e2e_enabled() is False


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="Williams Futures Testnet Release Gate")
    parser.add_argument("--read-only", action="store_true")
    parser.add_argument("--print-checklist", action="store_true")
    args = parser.parse_args()

    if args.print_checklist:
        for step in GATE_STEPS:
            print(f"{step.id} [{step.mode}] {step.description}")

    if args.read_only:
        result = run_testnet_read_only(os.getenv("TESTNET_SYMBOL", "BTCUSDT"))
        print("FUTURES DEMO READ-ONLY GATE: PASS")
        print(result)



def test_read_only_gate_checks_all_ten_approved_contracts(monkeypatch):
    class FakeClient:
        base_url = "https://demo-fapi.binance.com"

        def __init__(self, *args, **kwargs):
            self.checked = []
            self.market_checked = []
            # Stable synthetic clock, well inside an H1 candle.
            self.now_ms = 1_800_000_012_345

        def account(self):
            return {
                "availableBalance": "1000",
                "totalWalletBalance": "1000",
                "totalMarginBalance": "1000",
            }

        def get_position_mode(self):
            return {"dualSidePosition": False}

        def get_multi_assets_mode(self):
            return {"multiAssetsMargin": False}

        def sync_time(self):
            return {"serverTime": self.now_ms}

        def mark_price(self, symbol):
            self.market_checked.append(symbol)
            return {"symbol": symbol, "markPrice": "100.0", "time": self.now_ms}

        def klines(self, symbol, interval, limit=200):
            assert interval == "1h" and limit == 3
            hour_ms = 60 * 60 * 1000
            current_open = (self.now_ms // hour_ms) * hour_ms
            previous_open = current_open - hour_ms
            self.market_checked.append(symbol)
            return [
                [previous_open, "99", "102", "98", "100", "10", previous_open + hour_ms - 1],
                [current_open, "100", "101", "99", "100.5", "2", current_open + hour_ms - 1],
            ]

        def exchange_info(self, symbol):
            self.checked.append(symbol)
            return {"symbols": [{
                "symbol": symbol,
                "status": "TRADING",
                "contractType": "PERPETUAL",
                "quoteAsset": "USDT",
                "filters": [
                    {"filterType": "PRICE_FILTER"},
                    {"filterType": "LOT_SIZE"},
                ],
            }]}

    monkeypatch.setattr(__import__(__name__), "BinanceFuturesClient", FakeClient)
    monkeypatch.setenv("WILLIAMS_TESTNET_E2E", "1")
    monkeypatch.setenv("TESTNET", "true")
    monkeypatch.setenv("BINANCE_API_KEY", "test-key")
    monkeypatch.setenv("BINANCE_API_SECRET", "test-secret")
    result = run_testnet_read_only("BTCUSDT")
    assert result["approved_contracts_checked"] == 10
    assert result["market_data_checked"] == 10
    assert {row["symbol"] for row in result["contracts"]} == set(APPROVED_FUTURES_SYMBOLS)
    assert {row["symbol"] for row in result["market_data"]} == set(APPROVED_FUTURES_SYMBOLS)
    assert all(row["closed_candle_age_ms"] >= 0 for row in result["market_data"])


def test_read_only_gate_blocks_unfunded_account_before_symbol_checks(monkeypatch):
    class FakeClient:
        base_url = "https://demo-fapi.binance.com"

        def __init__(self, *args, **kwargs):
            pass

        def sync_time(self):
            return {"serverTime": 1_800_000_012_345}

        def account(self):
            return {
                "availableBalance": "0",
                "totalWalletBalance": "0",
                "totalMarginBalance": "0",
            }

        def get_position_mode(self):
            return {"dualSidePosition": False}

        def get_multi_assets_mode(self):
            return {"multiAssetsMargin": False}

        def exchange_info(self, symbol):
            raise AssertionError("No contract checks should run for an unfunded account")

    monkeypatch.setattr(__import__(__name__), "BinanceFuturesClient", FakeClient)
    monkeypatch.setenv("WILLIAMS_TESTNET_E2E", "1")
    monkeypatch.setenv("TESTNET", "true")
    monkeypatch.setenv("BINANCE_API_KEY", "test-key")
    monkeypatch.setenv("BINANCE_API_SECRET", "test-secret")
    with __import__("pytest").raises(RuntimeError, match="no available balance"):
        run_testnet_read_only("BTCUSDT")


def test_read_only_gate_rejects_symbol_outside_approved_universe(monkeypatch):
    class FakeClient:
        base_url = "https://demo-fapi.binance.com"

        def __init__(self, *args, **kwargs):
            pass

    monkeypatch.setattr(__import__(__name__), "BinanceFuturesClient", FakeClient)
    monkeypatch.setenv("WILLIAMS_TESTNET_E2E", "1")
    monkeypatch.setenv("TESTNET", "true")
    monkeypatch.setenv("BINANCE_API_KEY", "test-key")
    monkeypatch.setenv("BINANCE_API_SECRET", "test-secret")
    with __import__("pytest").raises(RuntimeError, match="outside the approved ten-symbol"):
        run_testnet_read_only("NOTUSDT")


def _synthetic_h1_candles(exchange_time_ms: int, hours_old: int = 0):
    hour_ms = 60 * 60 * 1000
    current_open = (exchange_time_ms // hour_ms) * hour_ms
    last_open = current_open - (1 + hours_old) * hour_ms
    previous_open = last_open - hour_ms
    return [
        [previous_open, "99", "102", "98", "100", "10", previous_open + hour_ms - 1],
        [last_open, "100", "103", "99", "101", "11", last_open + hour_ms - 1],
    ]


def test_read_only_market_gate_rejects_stale_mark_price():
    now_ms = 1_800_000_012_345

    class FakeMarketClient:
        def mark_price(self, symbol):
            return {"symbol": symbol, "markPrice": "100", "time": now_ms - 6 * 60 * 1000}

        def klines(self, symbol, interval, limit=200):
            return _synthetic_h1_candles(now_ms)

    with __import__("pytest").raises(RuntimeError, match="mark price is stale"):
        _validate_h1_market_snapshot(
            "BTCUSDT", FakeMarketClient().mark_price("BTCUSDT"),
            FakeMarketClient().klines("BTCUSDT", "1h", 3), now_ms
        )


def test_read_only_market_gate_rejects_stale_closed_h1_candle():
    now_ms = 1_800_000_012_345

    class FakeMarketClient:
        def mark_price(self, symbol):
            return {"symbol": symbol, "markPrice": "100", "time": now_ms}

        def klines(self, symbol, interval, limit=200):
            return _synthetic_h1_candles(now_ms, hours_old=2)

    with __import__("pytest").raises(RuntimeError, match="latest closed H1 candle is stale"):
        _validate_h1_market_snapshot(
            "BTCUSDT", FakeMarketClient().mark_price("BTCUSDT"),
            FakeMarketClient().klines("BTCUSDT", "1h", 3), now_ms
        )


def test_read_only_market_gate_rejects_inconsistent_ohlc():
    now_ms = 1_800_000_012_345
    hour_ms = 60 * 60 * 1000
    current_open = (now_ms // hour_ms) * hour_ms
    prior_open = current_open - hour_ms
    invalid = [
        [prior_open, "99", "98", "100", "100", "10", prior_open + hour_ms - 1],
        [current_open, "100", "101", "99", "100.5", "2", current_open + hour_ms - 1],
    ]
    with __import__("pytest").raises(RuntimeError, match="inconsistent OHLC"):
        _validate_h1_market_snapshot(
            "BTCUSDT",
            {"symbol": "BTCUSDT", "markPrice": "100", "time": now_ms},
            invalid,
            now_ms,
        )
