import os
from contextlib import contextmanager
import tempfile
from copy import deepcopy

from test_db_guard import assert_test_db_safe
fd, db_path = tempfile.mkstemp(
    prefix="williams_atomic_db_",
    suffix=".sqlite3"
)
os.close(fd)

os.environ["WILLIAMS_DB_PATH"] = db_path
assert_test_db_safe()
os.environ["WILLIAMS_TEST_DB"] = db_path

from trader import Trader


class FakeDB:
    def __init__(self):
        self.state = {"active_symbol": "BTCUSDT"}
        self.events = []

        self.fail_log_event = False
        self.fail_restore = False

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
        if self.fail_restore and str(value) == "BTCUSDT":
            raise RuntimeError("SIMULATED RESTORE FAILURE")

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
        data = {
            "BTCUSDT": {
                "status": "TRADING",
                "baseAsset": "BTC",
                "quoteAsset": "USDT",
                "filters": [],
            },
            "ETHUSDT": {
                "status": "TRADING",
                "baseAsset": "ETH",
                "quoteAsset": "USDT",
                "filters": [],
            },
        }

        return {"symbols": [data[symbol]]}


def make_trader():
    t = object.__new__(Trader)

    t.symbol = "BTCUSDT"
    t.active_symbol = "BTCUSDT"
    t.filters = {"OLD": "FILTER"}
    t.base_asset = "BTC"
    t.quote_asset = "USDT"
    t.recovered = True

    t.client = FakeClient()
    t.db = FakeDB()

    return t


print("=== DB PERSISTENCE FAILURE TEST ===")

t = make_trader()

before_runtime = {
    "symbol": t.symbol,
    "active_symbol": t.active_symbol,
    "filters": deepcopy(t.filters),
    "base_asset": t.base_asset,
    "quote_asset": t.quote_asset,
    "recovered": t.recovered,
}

# First DB write succeeds, second operation fails.
t.db.fail_log_event = True

try:
    t.switch_symbol("ETHUSDT")
except RuntimeError as e:
    print("[EXPECTED] persistence failure:", e)
else:
    raise AssertionError("switch_symbol unexpectedly succeeded")

after_runtime = {
    "symbol": t.symbol,
    "active_symbol": t.active_symbol,
    "filters": deepcopy(t.filters),
    "base_asset": t.base_asset,
    "quote_asset": t.quote_asset,
    "recovered": t.recovered,
}

assert after_runtime == before_runtime
print("[PASS] runtime rollback after persistence failure")

assert t.db.state_get("active_symbol") == "BTCUSDT"
print("[PASS] DB active_symbol restored")


# ------------------------------------------------------------
# Harder case:
# restoration itself fails.
# ------------------------------------------------------------
t = make_trader()

t.db.fail_log_event = True
t.db.fail_restore = True

try:
    t.switch_symbol("ETHUSDT")
except RuntimeError as e:
    print("[EXPECTED] restore failure:", e)
else:
    raise AssertionError("switch_symbol unexpectedly succeeded")

print("[INFO] runtime after failed DB restore:")
print("       symbol       =", t.symbol)
print("       active_symbol =", t.active_symbol)
print("       DB symbol     =", t.db.state_get("active_symbol"))

assert t.symbol == "BTCUSDT"
assert t.active_symbol == "BTCUSDT"

print("[PASS] runtime remains internally consistent even when DB restore fails")

print()
print("DB PERSISTENCE FAILURE TEST: PASS")
print("Production DB was not used.")
