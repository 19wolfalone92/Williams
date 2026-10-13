"""TASK 009.2 SQLite fault-injection tests. No exchange/network calls are made."""
from concurrent.futures import ThreadPoolExecutor
import sqlite3

import pytest

from db import Database
from execution_barrier import OrderIntent


def intent(client_order_id="WILL_TASK0092_001"):
    return OrderIntent.new(
        "BTCUSDT", "BUY", "MARKET", {"1h": 1},
        purpose="ENTRY", client_order_id=client_order_id,
    )


def fill(trade_id="7001"):
    return {
        "exchange": "BINANCE",
        "account_scope": "spot-test-account",
        "symbol": "BTCUSDT",
        "trade_id": trade_id,
        "order_id": "9001",
        "intent_id": "intent-1",
        "quantity": "0.01",
        "price": "60000.00",
        "commission": "0.00001",
        "commission_asset": "BTC",
    }


def set_submitting(db, item):
    assert db.claim_execution_intent(item)["claimed"]
    db.save_execution_intent(item, "SUBMITTING")


def test_two_database_instances_cannot_claim_same_client_order_id(tmp_path):
    path = str(tmp_path / "shared.sqlite3")
    db1, db2 = Database(path), Database(path)
    item = intent()
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [
            pool.submit(db1.claim_execution_intent, item),
            pool.submit(db2.claim_execution_intent, item),
        ]
        results = [f.result(timeout=5) for f in futures]
    assert sum(bool(x["claimed"]) for x in results) == 1
    assert not db1.execution_readiness()["ready"]


def test_resolved_old_intent_still_owns_client_order_id(tmp_path):
    db = Database(str(tmp_path / "identity.sqlite3"))
    first = intent("WILL_STABLE_01")
    assert db.claim_execution_intent(first)["claimed"]
    db.save_execution_intent(first, "SUBMITTED")
    second = intent("WILL_STABLE_01")
    result = db.claim_execution_intent(second)
    assert result["claimed"] is False
    assert result["reason"] == "IDENTITY_CONFLICT"


def test_crash_after_intent_persistence_blocks_restart_before_submit(tmp_path):
    path = str(tmp_path / "crash-before-submit.sqlite3")
    db = Database(path)
    item = intent()
    assert db.claim_execution_intent(item)["claimed"]
    db.conn.close()
    restarted = Database(path)
    gate = restarted.execution_readiness()
    assert gate["ready"] is False
    assert gate["reason"] == "UNRESOLVED_INTENT"
    assert restarted.list_unresolved_execution_intents()[0]["status"] == "PENDING"


def test_unknown_lock_survives_restart_and_requires_evidence_to_clear(tmp_path):
    path = str(tmp_path / "unknown.sqlite3")
    db = Database(path)
    item = intent()
    set_submitting(db, item)
    db.mark_execution_unknown(item, "timeout after possible submit")
    db.conn.close()

    restarted = Database(path)
    gate = restarted.execution_readiness()
    assert gate["ready"] is False
    assert gate["reason"] == "EXECUTION_LOCKED"
    with pytest.raises(PermissionError):
        restarted.resolve_execution_intent(
            item.intent_id, {"status": "OPEN", "evidence_id": "untrusted-caller"}
        )
    assert restarted.execution_readiness()["ready"] is False


def test_unknown_lock_write_failure_leaves_unresolved_intent_blocking(tmp_path):
    db = Database(str(tmp_path / "lock-write-failure.sqlite3"))
    item = intent()
    set_submitting(db, item)
    db.conn.execute("""
        CREATE TRIGGER fail_unknown_lock
        BEFORE INSERT ON bot_state
        WHEN NEW.key='execution_lock:spot:spot-default'
        BEGIN SELECT RAISE(ABORT, 'injected lock write failure'); END
    """)
    db.conn.commit()
    with pytest.raises(sqlite3.DatabaseError):
        db.mark_execution_unknown(item, "injected failure")
    assert db.execution_readiness()["ready"] is False
    row = db.conn.execute(
        "SELECT status FROM execution_intents WHERE intent_id=?", (item.intent_id,)
    ).fetchone()
    assert row["status"] == "SUBMITTING"


def test_exchange_result_failure_rolls_back_result_and_keeps_intent_unresolved(tmp_path):
    db = Database(str(tmp_path / "result-write-failure.sqlite3"))
    item = intent()
    set_submitting(db, item)
    db.conn.execute("""
        CREATE TRIGGER fail_exchange_result
        BEFORE INSERT ON execution_events
        WHEN NEW.event='BINANCE_RESULT'
        BEGIN SELECT RAISE(ABORT, 'injected result write failure'); END
    """)
    db.conn.commit()
    with pytest.raises(sqlite3.DatabaseError):
        db.save_execution_result(item, {"status": "NEW", "orderId": 9001})
    row = db.conn.execute(
        "SELECT status FROM execution_intents WHERE intent_id=?", (item.intent_id,)
    ).fetchone()
    assert row["status"] == "SUBMITTING"
    assert db.conn.execute(
        "SELECT COUNT(*) FROM execution_events WHERE event='BINANCE_RESULT'"
    ).fetchone()[0] == 0
    assert db.execution_readiness()["ready"] is False


def test_duplicate_fill_does_not_repeat_database_effect(tmp_path):
    db = Database(str(tmp_path / "fill-dedupe.sqlite3"))
    def apply(database, data):
        n = int(database.state_get("fill_effect_count", "0")) + 1
        database.state_set("fill_effect_count", n)
    assert db.apply_execution_fill(fill(), apply) is True
    assert db.apply_execution_fill(fill(), apply) is False
    assert db.state_get("fill_effect_count") == "1"


def test_financial_effect_failure_rolls_back_fill_claim(tmp_path):
    db = Database(str(tmp_path / "fill-rollback.sqlite3"))
    def fail_after_write(database, data):
        database.state_set("fill_effect_count", 1)
        raise RuntimeError("injected financial effect failure")
    with pytest.raises(RuntimeError):
        db.apply_execution_fill(fill(), fail_after_write)
    assert db.conn.execute("SELECT COUNT(*) FROM execution_fills").fetchone()[0] == 0
    assert db.state_get("fill_effect_count") is None
    assert db.apply_execution_fill(
        fill(), lambda database, data: database.state_set("fill_effect_count", 1)
    ) is True
    assert db.state_get("fill_effect_count") == "1"


def test_concurrent_fill_handlers_across_database_instances_apply_once(tmp_path):
    path = str(tmp_path / "fill-concurrent.sqlite3")
    db1, db2 = Database(path), Database(path)
    def apply(database, data):
        current = int(database.state_get("fill_effect_count", "0")) + 1
        database.state_set("fill_effect_count", current)
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = [
            pool.submit(db1.apply_execution_fill, fill("8123"), apply),
            pool.submit(db2.apply_execution_fill, fill("8123"), apply),
        ]
        applied = [f.result(timeout=5) for f in results]
    assert sorted(applied) == [False, True]
    assert db1.state_get("fill_effect_count") == "1"


def test_late_partial_fill_is_distinct_and_applied_once_per_exchange_trade_id(tmp_path):
    db = Database(str(tmp_path / "partial-late.sqlite3"))
    def apply(database, data):
        current = float(database.state_get("filled_qty", "0"))
        database.state_set("filled_qty", current + float(data["quantity"]))
    assert db.apply_execution_fill(fill("9101"), apply) is True
    later = fill("9102")
    later["quantity"] = "0.005"
    assert db.apply_execution_fill(later, apply) is True
    assert db.apply_execution_fill(later, apply) is False
    assert float(db.state_get("filled_qty")) == pytest.approx(0.015)
