import os
import sqlite3
import tempfile

from db import Database


from test_db_guard import assert_test_db_path
fd, path = tempfile.mkstemp(
    prefix="williams_atomic_",
    suffix=".sqlite3"
)
os.close(fd)

print("=== REAL SQLITE TRANSACTION TEST ===")
print("TEST DB:", path)

assert_test_db_path(path)
db = Database(path)

# ------------------------------------------------------------
# Initial state
# ------------------------------------------------------------
db.state_set("active_symbol", "BTCUSDT")

before_events = db.conn.execute(
    "SELECT COUNT(*) AS n FROM events"
).fetchone()["n"]

print("Initial active_symbol:", db.state_get("active_symbol"))
print("Initial events:", before_events)

# ------------------------------------------------------------
# SUCCESSFUL TRANSACTION
# ------------------------------------------------------------
with db.transaction():
    db.state_set("active_symbol", "ETHUSDT")
    db.log_event(
        "INFO",
        "symbol_switched",
        "Test successful transaction",
        {"from": "BTCUSDT", "to": "ETHUSDT"},
    )

after_success_symbol = db.state_get("active_symbol")
after_success_events = db.conn.execute(
    "SELECT COUNT(*) AS n FROM events"
).fetchone()["n"]

assert after_success_symbol == "ETHUSDT"
assert after_success_events == before_events + 1

print("[PASS] successful transaction committed atomically")

# ------------------------------------------------------------
# FAILED TRANSACTION
# ------------------------------------------------------------
failed = False

try:
    with db.transaction():
        db.state_set("active_symbol", "XRPUSDT")

        # This write must NOT survive the rollback.
        db.log_event(
            "INFO",
            "symbol_switched",
            "This event must be rolled back",
            {"from": "ETHUSDT", "to": "XRPUSDT"},
        )

        raise RuntimeError("SIMULATED FAILURE AFTER DB WRITES")

except RuntimeError as e:
    failed = True
    print("[EXPECTED]", e)

assert failed

rolled_back_symbol = db.state_get("active_symbol")
rolled_back_events = db.conn.execute(
    "SELECT COUNT(*) AS n FROM events"
).fetchone()["n"]

assert rolled_back_symbol == "ETHUSDT"
assert rolled_back_events == after_success_events

print("[PASS] state_set rolled back")
print("[PASS] log_event rolled back")
print("[PASS] DB remained internally consistent")

# ------------------------------------------------------------
# SIMULATE log_event FAILURE
# ------------------------------------------------------------
original_log_event = db.log_event

def failing_log_event(*args, **kwargs):
    raise RuntimeError("SIMULATED log_event FAILURE")

db.log_event = failing_log_event

try:
    with db.transaction():
        db.state_set("active_symbol", "SOLUSDT")
        db.log_event(
            "INFO",
            "symbol_switched",
            "must fail",
            {"from": "ETHUSDT", "to": "SOLUSDT"},
        )

except RuntimeError as e:
    print("[EXPECTED]", e)

finally:
    db.log_event = original_log_event

final_symbol = db.state_get("active_symbol")
final_events = db.conn.execute(
    "SELECT COUNT(*) AS n FROM events"
).fetchone()["n"]

assert final_symbol == "ETHUSDT"
assert final_events == after_success_events

print("[PASS] state change rolled back when log_event failed")
print("[PASS] no partial DB commit remained")

# ------------------------------------------------------------
# FINAL
# ------------------------------------------------------------
db.conn.close()

try:
    os.remove(path)
except OSError:
    pass

print()
print("REAL SQLITE TRANSACTION TEST: PASS")
