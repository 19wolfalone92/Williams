import os
from contextlib import contextmanager
import tempfile
from copy import deepcopy

# ------------------------------------------------------------
# ISOLATED TEST DB — NEVER production
# ------------------------------------------------------------
from test_db_guard import assert_test_db_safe
fd, db_path = tempfile.mkstemp(
    prefix="williams_atomic_switch_",
    suffix=".sqlite3"
)
os.close(fd)

os.environ["WILLIAMS_DB_PATH"] = db_path
assert_test_db_safe()
os.environ["WILLIAMS_TEST_DB"] = db_path

from trader import Trader


class FakeDB:
    def __init__(self):
        self.state = {
            "active_symbol": "BTCUSDT",
        }
        self.events = []

        self.fail_state_set = False
        self.fail_log_event = False

    @contextmanager
    def transaction(self):
        state_snapshot = deepcopy(self.state)
        events_snapshot = deepcopy(self.events)
        try:
            yield self
        except Exception:
            self.state = state_snapshot
            self.events = events_snapshot
            raise

    def state_get(self, key, default=None):
        return self.state.get(key, default)

    def state_set(self, key, value):
        if self.fail_state_set:
            raise RuntimeError("SIMULATED state_set FAILURE")
        self.state[key] = str(value)

    def state_delete(self, key):
        self.state.pop(key, None)

    def log_event(self, *args, **kwargs):
        if self.fail_log_event:
            raise RuntimeError("SIMULATED log_event FAILURE")
        self.events.append((args, kwargs))

    def open_trade(self, symbol=None):
        return None


class FakeClient:
    def exchange_info(self, symbol):
        symbols = {
            "BTCUSDT": {
                "status": "TRADING",
                "baseAsset": "BTC",
                "quoteAsset": "USDT",
                "filters": [
                    {
                        "filterType": "LOT_SIZE",
                        "minQty": "0.00001",
                        "stepSize": "0.00001",
                    }
                ],
            },
            "ETHUSDT": {
                "status": "TRADING",
                "baseAsset": "ETH",
                "quoteAsset": "USDT",
                "filters": [
                    {
                        "filterType": "LOT_SIZE",
                        "minQty": "0.0001",
                        "stepSize": "0.0001",
                    }
                ],
            },
        }

        if symbol not in symbols:
            return {"symbols": []}

        return {"symbols": [symbols[symbol]]}


def make_trader():
    t = object.__new__(Trader)

    t.symbol = "BTCUSDT"
    t.active_symbol = "BTCUSDT"

    t.filters = {
        "OLD_FILTER": {
            "filterType": "TEST",
            "value": "original",
        }
    }

    t.base_asset = "BTC"
    t.quote_asset = "USDT"
    t.recovered = True

    t.client = FakeClient()
    t.db = FakeDB()

    return t


def state_snapshot(t):
    return {
        "symbol": t.symbol,
        "active_symbol": t.active_symbol,
        "filters": deepcopy(t.filters),
        "base_asset": t.base_asset,
        "quote_asset": t.quote_asset,
        "recovered": t.recovered,
        "db_active_symbol": t.db.state_get("active_symbol"),
    }


print("=== ATOMIC SWITCH ROLLBACK TEST ===")

# ------------------------------------------------------------
# TEST 1 — state_set failure
# ------------------------------------------------------------
t = make_trader()
before = state_snapshot(t)

t.db.fail_state_set = True

try:
    t.switch_symbol("ETHUSDT")
except RuntimeError as e:
    print("[EXPECTED] state_set failure:", e)
else:
    raise AssertionError("switch_symbol unexpectedly succeeded")

after = state_snapshot(t)

assert after == before, (
    "ROLLBACK FAILURE after state_set exception\n"
    f"BEFORE = {before}\n"
    f"AFTER  = {after}"
)

print("[PASS] state_set failure fully rolled back")


# ------------------------------------------------------------
# TEST 2 — log_event failure
# ------------------------------------------------------------
t = make_trader()
before = state_snapshot(t)

t.db.fail_log_event = True

try:
    t.switch_symbol("ETHUSDT")
except RuntimeError as e:
    print("[EXPECTED] log_event failure:", e)
else:
    raise AssertionError("switch_symbol unexpectedly succeeded")

after = state_snapshot(t)

assert after == before, (
    "ROLLBACK FAILURE after log_event exception\n"
    f"BEFORE = {before}\n"
    f"AFTER  = {after}"
)

print("[PASS] log_event failure fully rolled back")


# ------------------------------------------------------------
# TEST 3 — successful switch
# ------------------------------------------------------------
t = make_trader()

result = t.switch_symbol("ETHUSDT")

assert result["symbol"] == "ETHUSDT"
assert t.symbol == "ETHUSDT"
assert t.active_symbol == "ETHUSDT"
assert t.base_asset == "ETH"
assert t.quote_asset == "USDT"
assert t.recovered is False
assert t.db.state_get("active_symbol") == "ETHUSDT"

print("[PASS] successful switch commits completely")


# ------------------------------------------------------------
# RESULT
# ------------------------------------------------------------
print()
print("ATOMIC SWITCH ROLLBACK TEST: PASS")
print("state_set failure: PASS")
print("log_event failure: PASS")
print("successful switch: PASS")
print("Production DB was not used.")
