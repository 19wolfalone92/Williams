import os
import sqlite3
import shutil
import tempfile
from pathlib import Path

import trader


PROJECT = Path(__file__).resolve().parent
PROD_DB = PROJECT / "data" / "trader.sqlite3"
CLEAN_DB = PROJECT / "data" / "test_tmp" / "trader.sqlite3.clean_20261004_082723"


def snapshot(path):
    con = sqlite3.connect(path)

    integrity = con.execute(
        "PRAGMA integrity_check"
    ).fetchone()[0]

    state = dict(con.execute(
        "SELECT key,value FROM bot_state ORDER BY key"
    ).fetchall())

    counts = {}

    for table in ["candles", "orders", "trades", "events"]:
        counts[table] = con.execute(
            f"SELECT COUNT(*) FROM {table}"
        ).fetchone()[0]

    con.close()

    return integrity, state, counts


class SafeClient:

    def __init__(self, api_key="", api_secret="", testnet=True, **kwargs):
        self.testnet = True
        self.api_key = api_key or "SAFE_TEST_KEY"
        self.api_secret = api_secret or "SAFE_TEST_SECRET"

    def exchange_info(self, symbol):
        return {
            "symbols": [{
                "symbol": symbol,
                "status": "TRADING",
                "baseAsset": symbol[:-4] if symbol.endswith("USDT") else "BTC",
                "quoteAsset": "USDT",
                "filters": [
                    {
                        "filterType": "LOT_SIZE",
                        "minQty": "0.000001",
                        "stepSize": "0.000001",
                    },
                    {
                        "filterType": "PRICE_FILTER",
                        "tickSize": "0.01",
                    },
                    {
                        "filterType": "MIN_NOTIONAL",
                        "minNotional": "5",
                    },
                ],
            }]
        }

    def sync_time(self):
        pass

    def account(self):
        return {
            "balances": [
                {"asset": "USDT", "free": "1000", "locked": "0"},
                {"asset": "BTC", "free": "0", "locked": "0"},
                {"asset": "ETH", "free": "0", "locked": "0"},
            ]
        }

    def all_orders(self, symbol, limit=1000):
        return []

    def open_order_lists(self, symbol):
        return []

    def book_ticker(self, symbol):
        return {
            "bidPrice": "100000",
            "askPrice": "100000.10",
        }

    def decimal_floor(self, value, step):
        from decimal import Decimal, ROUND_DOWN

        v = Decimal(str(value))
        s = Decimal(str(step))

        return str(
            (v / s).to_integral_value(rounding=ROUND_DOWN) * s
        )

    def decimal_format(self, value):
        return format(float(value), ".8f")

    def order(self, *args, **kwargs):
        raise AssertionError(
            "SAFETY FAILURE: exchange BUY/order reached"
        )

    def create_oco_sell(self, *args, **kwargs):
        raise AssertionError(
            "SAFETY FAILURE: OCO reached"
        )

    def cancel(self, *args, **kwargs):
        raise AssertionError(
            "SAFETY FAILURE: cancel reached"
        )

    def cancel_order(self, *args, **kwargs):
        raise AssertionError(
            "SAFETY FAILURE: cancel_order reached"
        )

    def cancel_oco(self, *args, **kwargs):
        raise AssertionError(
            "SAFETY FAILURE: cancel_oco reached"
        )

    def create_order(self, *args, **kwargs):
        raise AssertionError(
            "SAFETY FAILURE: create_order reached"
        )

    def __getattr__(self, name):
        raise AttributeError(
            f"SafeClient deliberately does not implement: {name}"
        )


print("========================================")
print("=== SAFE ISOLATED SMOKE TEST v4 ===")
print("========================================")

assert PROD_DB.exists()
assert CLEAN_DB.exists()

prod_before = PROD_DB.read_bytes()

integrity, state, counts = snapshot(PROD_DB)

assert integrity == "ok"

assert state == {
    "active_symbol": "BTCUSDT",
    "position_state": "FLAT",
    "schema_version": "2",
}

assert counts == {
    "candles": 0,
    "orders": 0,
    "trades": 0,
    "events": 0,
}

print("[PASS] production DB starts clean")

with tempfile.TemporaryDirectory(
    prefix="williams_smoke_",
    dir=PROJECT / "data" / "test_tmp"
) as tmp:

    test_db = Path(tmp) / "trader.sqlite3"

    shutil.copy2(CLEAN_DB, test_db)

    os.environ["WILLIAMS_DB_PATH"] = str(test_db)
    os.environ["DB_PATH"] = str(test_db)
    os.environ["TESTNET"] = "true"
    os.environ["DRY_RUN"] = "true"
    os.environ["AUTO_SCAN_ENABLED"] = "true"
    os.environ["ALLOW_LIVE"] = "false"

    OriginalClient = trader.BinanceSpotClient
    trader.BinanceSpotClient = SafeClient

    try:

        print()
        print("=== CREATE TEST TRADER ===")

        t = trader.Trader(
            api_key="SAFE_TEST_KEY",
            api_secret="SAFE_TEST_SECRET",
            testnet=True,
        )

        assert t.symbol == "BTCUSDT"
        assert t.active_symbol == "BTCUSDT"
        assert t.dry_run is True
        assert t.auto_scan_enabled is True
        assert t.max_open_positions == 1
        assert t.state() == "FLAT"

        print("[PASS] Trader created")

        print()
        print("=== SYMBOL SWITCH ===")

        t.switch_symbol("ETHUSDT")

        assert t.symbol == "ETHUSDT"
        assert t.active_symbol == "ETHUSDT"
        assert t.state() == "FLAT"

        print("[PASS] symbol switch")
        print("[PASS] state remains FLAT")

        print()
        print("=== DRY-RUN BUY ===")

        try:
            t.market_buy(10)

        except Exception as e:
            print("[EXPECTED]", e)

            assert "DRY_RUN=true" in str(e)

            print(
                "[PASS] DRY_RUN blocked BUY "
                "before exchange call"
            )

        else:
            raise AssertionError(
                "SAFETY FAILURE: market_buy did not block"
            )

        print()
        print("=== TEST DATABASE CHECK ===")

        integrity, state, counts = snapshot(test_db)

        print("integrity =", integrity)
        print("state =", state)
        print("counts =", counts)

        assert integrity == "ok"
        assert state["position_state"] == "FLAT"
        assert "entry_client_order_id" not in state

        assert counts["orders"] == 0
        assert counts["trades"] == 0
        assert counts["candles"] == 0

        # One event is expected from switch_symbol().
        assert counts["events"] == 1

        print("[PASS] no order")
        print("[PASS] no trade")
        print("[PASS] no candle")
        print("[PASS] no ENTRY_PENDING")
        print("[PASS] no entry_client_order_id")
        print("[PASS] expected symbol-switch event only")

    finally:
        trader.BinanceSpotClient = OriginalClient

print()
print("=== PRODUCTION DB IMMUTABILITY CHECK ===")

prod_after = PROD_DB.read_bytes()

assert prod_before == prod_after, (
    "SAFETY FAILURE: production DB changed during isolated test"
)

integrity, state, counts = snapshot(PROD_DB)

assert integrity == "ok"

assert state == {
    "active_symbol": "BTCUSDT",
    "position_state": "FLAT",
    "schema_version": "2",
}

assert counts == {
    "candles": 0,
    "orders": 0,
    "trades": 0,
    "events": 0,
}

print("[PASS] production DB byte-identical before/after")
print("[PASS] production DB remains completely clean")

print()
print("========================================")
print("=== SAFE ISOLATED SMOKE TEST v4: PASS ===")
print("========================================")
