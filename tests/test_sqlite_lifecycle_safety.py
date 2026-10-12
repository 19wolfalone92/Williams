"""SQLite lifecycle and production-path guard regressions; temporary DBs only."""
import sqlite3
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from pathlib import Path
from unittest.mock import Mock

import pytest

import test_db_guard
from db import Database
from execution_barrier import ExecutionBarrier, OrderIntent
from market_context import ContextCache
from test_db_guard import assert_test_db_path


def test_database_context_manager_closes_connection_and_persists(tmp_path):
    path = tmp_path / "lifecycle.sqlite3"
    with Database(str(path)) as db:
        db.state_set("lifecycle_probe", "persisted")
    with pytest.raises(sqlite3.ProgrammingError):
        db.conn.execute("SELECT 1")

    with Database(str(path)) as reopened:
        assert reopened.state_get("lifecycle_probe") == "persisted"


def test_transaction_rolls_back_all_writes_on_exception(tmp_path):
    path = tmp_path / "rollback.sqlite3"
    with Database(str(path)) as db:
        with pytest.raises(RuntimeError, match="injected"):
            with db.transaction():
                db.state_set("atomic_probe", "must_rollback")
                db.log_event("INFO", "test", "must rollback", {})
                raise RuntimeError("injected")
        assert db.state_get("atomic_probe") is None
        assert db.conn.execute(
            "SELECT COUNT(*) FROM events WHERE event='test'"
        ).fetchone()[0] == 0


def test_guard_rejects_hard_link_alias_of_production_db(tmp_path, monkeypatch):
    production = tmp_path / "trader.sqlite3"
    production.write_bytes(b"temporary test fixture, not a real DB")
    alias = tmp_path / "alias.sqlite3"
    try:
        alias.hardlink_to(production)
    except OSError:
        pytest.skip("hard links are unavailable on this filesystem")

    monkeypatch.setattr(test_db_guard, "PRODUCTION_DB", production.resolve())
    monkeypatch.delenv("WILLIAMS_ALLOW_PROD_DB_TEST", raising=False)
    with pytest.raises(RuntimeError, match="production DB"):
        assert_test_db_path(alias)


def test_backup_and_restore_roundtrip_uses_only_temporary_databases(tmp_path):
    source_path = tmp_path / "source.sqlite3"
    backup_path = tmp_path / "backup.sqlite3"
    restored_path = tmp_path / "restored.sqlite3"

    with Database(str(source_path)) as source:
        source.state_set("backup_probe", "original")
        with closing(sqlite3.connect(backup_path)) as backup:
            source.conn.backup(backup)

    with closing(sqlite3.connect(backup_path)) as backup, closing(sqlite3.connect(restored_path)) as restored:
        backup.backup(restored)

    with Database(str(restored_path)) as restored:
        assert restored.state_get("backup_probe") == "original"


def test_vps_backup_uses_sqlite_online_backup_instead_of_copying_live_main_file():
    script = (Path(__file__).resolve().parents[1] / "deploy" / "backup_vps.sh").read_text()
    assert "src.backup(dst)" in script
    assert "mode=ro" in script
    assert "tar czf - -C /tmp" in script
    assert "tar czf - -C /app/data trader.sqlite3" not in script
    assert "set -euo pipefail" in script
    assert 'TMP_ARCHIVE="backups/.williams-data-$STAMP.tgz.tmp"' in script
    assert 'tar -tzf "$TMP_ARCHIVE" >/dev/null' in script
    assert 'mv -f "$TMP_ARCHIVE" "$FINAL_ARCHIVE"' in script
    assert script.index('tar -tzf "$TMP_ARCHIVE" >/dev/null') < script.index(
        'mv -f "$TMP_ARCHIVE" "$FINAL_ARCHIVE"'
    )


def test_execution_intent_claim_survives_database_reopen(tmp_path):
    path = tmp_path / "restart.sqlite3"
    intent = OrderIntent.new(
        symbol="BTCUSDT", side="BUY", order_type="LIMIT",
        required_context_versions={}, purpose="CAMPAIGN_ENTRY",
    )
    with Database(str(path)) as first_db:
        first_submit = Mock(return_value={"status": "NEW", "executedQty": "0", "orderId": 3001})
        first = ExecutionBarrier(ContextCache(), first_db).execute(intent, first_submit)
        assert first.accepted is True
        first_submit.assert_called_once()

    with Database(str(path)) as reopened_db:
        second_submit = Mock(return_value={"status": "NEW", "executedQty": "0", "orderId": 3002})
        second = ExecutionBarrier(ContextCache(), reopened_db).execute(intent, second_submit)
        assert second.accepted is False
        assert "duplicate execution intent already claimed" in second.reason
        second_submit.assert_not_called()


def test_parallel_barriers_cannot_submit_the_same_intent_twice(tmp_path):
    path = tmp_path / "parallel.sqlite3"
    with Database(str(path)) as setup_db:
        pass
    db1, db2 = Database(str(path)), Database(str(path))
    intent = OrderIntent.new(
        symbol="BTCUSDT", side="BUY", order_type="LIMIT",
        required_context_versions={}, purpose="CAMPAIGN_ENTRY",
    )
    submit1 = Mock(return_value={"status": "NEW", "executedQty": "0", "orderId": 4001})
    submit2 = Mock(return_value={"status": "NEW", "executedQty": "0", "orderId": 4002})
    barrier1, barrier2 = ExecutionBarrier(ContextCache(), db1), ExecutionBarrier(ContextCache(), db2)
    try:
        with ThreadPoolExecutor(max_workers=2) as pool:
            futures = [
                pool.submit(barrier1.execute, intent, submit1),
                pool.submit(barrier2.execute, intent, submit2),
            ]
            results = [future.result(timeout=10) for future in futures]
        assert sum(result.accepted for result in results) == 1
        assert submit1.call_count + submit2.call_count == 1
    finally:
        db1.close()
        db2.close()


def test_sqlite_busy_timeout_fails_boundedly_without_mutating_database(tmp_path):
    path = tmp_path / "busy.sqlite3"
    blocker = sqlite3.connect(path, timeout=1)
    blocker.execute("PRAGMA journal_mode=DELETE")
    blocker.execute("CREATE TABLE probe (value TEXT NOT NULL)")
    blocker.commit()
    blocker.execute("BEGIN EXCLUSIVE")
    blocker.execute("INSERT INTO probe(value) VALUES('uncommitted')")

    contender = sqlite3.connect(path, timeout=0.05)
    contender.execute("PRAGMA busy_timeout=50")
    try:
        with pytest.raises(sqlite3.OperationalError, match="locked"):
            contender.execute("INSERT INTO probe(value) VALUES('blocked')")
        # A timed-out contender must not report or leave a partial write.
        assert blocker.execute("SELECT COUNT(*) FROM probe").fetchone()[0] == 1
    finally:
        contender.close()
        blocker.rollback()
        blocker.close()

    with closing(sqlite3.connect(path)) as verify:
        assert verify.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        assert verify.execute("SELECT COUNT(*) FROM probe").fetchone()[0] == 0


def test_concurrent_writes_from_independent_database_connections(tmp_path):
    path = tmp_path / "concurrent-writes.sqlite3"
    with Database(str(path)):
        pass
    db1, db2 = Database(str(path)), Database(str(path))

    def write_many(db, prefix):
        for index in range(40):
            db.state_set(f"{prefix}:{index}", str(index))

    try:
        with ThreadPoolExecutor(max_workers=2) as pool:
            first = pool.submit(write_many, db1, "writer-a")
            second = pool.submit(write_many, db2, "writer-b")
            first.result(timeout=15)
            second.result(timeout=15)
    finally:
        db1.close()
        db2.close()

    with Database(str(path)) as verify:
        assert verify.conn.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        assert verify.conn.execute(
            "SELECT COUNT(*) FROM bot_state WHERE key LIKE 'writer-a:%'"
        ).fetchone()[0] == 40
        assert verify.conn.execute(
            "SELECT COUNT(*) FROM bot_state WHERE key LIKE 'writer-b:%'"
        ).fetchone()[0] == 40


def test_failed_write_rolls_back_and_database_remains_usable(tmp_path):
    path = tmp_path / "failed-write.sqlite3"
    with Database(str(path)) as db:
        with pytest.raises(sqlite3.IntegrityError):
            with db.transaction():
                db.state_set("before_failure", "temporary")
                db.conn.execute(
                    """INSERT INTO execution_intents(
                        intent_id, symbol, side, order_type, purpose,
                        required_context_versions_json, status
                    ) VALUES('invalid', NULL, 'BUY', 'LIMIT', 'ENTRY', '{}', 'PENDING')"""
                )
                db.state_set("after_failure", "must_not_exist")

        assert db.state_get("before_failure") is None
        assert db.state_get("after_failure") is None
        assert db.conn.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        db.state_set("after_rollback", "usable")


def test_corrupt_temporary_database_is_detected_without_repair_attempt(tmp_path):
    path = tmp_path / "corrupt.sqlite3"
    path.write_bytes(b"not a SQLite database; test fixture only")

    with closing(sqlite3.connect(path)) as conn:
        with pytest.raises(sqlite3.DatabaseError, match="not a database"):
            conn.execute("SELECT name FROM sqlite_master").fetchall()

    # Detection is intentional; this test never attempts an in-place repair.


def test_failed_backup_open_does_not_damage_source_or_create_successful_backup(tmp_path):
    source_path = tmp_path / "source.sqlite3"
    impossible_backup = tmp_path / "missing-directory" / "backup.sqlite3"
    with Database(str(source_path)) as source:
        source.state_set("durable_probe", "still_here")
        with pytest.raises(sqlite3.OperationalError):
            sqlite3.connect(impossible_backup)

        assert source.state_get("durable_probe") == "still_here"
        assert source.conn.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        assert not impossible_backup.exists()


def test_ambiguous_submission_survives_restart_and_blocks_blind_retry(tmp_path):
    path = tmp_path / "ambiguous-restart.sqlite3"
    intent = OrderIntent.new(
        symbol="BTCUSDT",
        side="BUY",
        order_type="LIMIT",
        required_context_versions={},
        purpose="CAMPAIGN_ENTRY",
        client_order_id="audit-restart-ambiguous-001",
    )

    with Database(str(path)) as first_db:
        submit = Mock(side_effect=TimeoutError("simulated response lost after submit"))
        with pytest.raises(TimeoutError, match="response lost"):
            ExecutionBarrier(ContextCache(), first_db).execute(intent, submit)
        submit.assert_called_once()
        assert first_db.has_unresolved_execution_intents() is True

    with Database(str(path)) as reopened_db:
        retry = Mock(return_value={"status": "NEW", "executedQty": "0", "orderId": 9002})
        result = ExecutionBarrier(ContextCache(), reopened_db).execute(intent, retry)
        assert result.accepted is False
        assert "unresolved PENDING/AMBIGUOUS execution intent" in result.reason
        retry.assert_not_called()
        assert reopened_db.has_unresolved_execution_intents() is True


def test_online_backup_includes_committed_pages_still_in_wal(tmp_path):
    source_path = tmp_path / "wal-source.sqlite3"
    backup_path = tmp_path / "wal-backup.sqlite3"
    source = sqlite3.connect(source_path)
    try:
        assert source.execute("PRAGMA journal_mode=WAL").fetchone()[0].lower() == "wal"
        source.execute("PRAGMA wal_autocheckpoint=0")
        source.execute("CREATE TABLE wal_probe(value TEXT NOT NULL)")
        source.execute("INSERT INTO wal_probe(value) VALUES('committed-in-wal')")
        source.commit()
        assert Path(str(source_path) + "-wal").exists()

        with closing(sqlite3.connect(backup_path)) as backup:
            source.backup(backup)
        with closing(sqlite3.connect(backup_path)) as backup:
            assert backup.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
            assert backup.execute("SELECT value FROM wal_probe").fetchone()[0] == "committed-in-wal"
    finally:
        source.close()


def test_interrupted_online_backup_is_not_published_as_final_copy(tmp_path):
    source_path = tmp_path / "source.sqlite3"
    partial_path = tmp_path / "backup.partial.sqlite3"
    final_path = tmp_path / "backup.sqlite3"
    with closing(sqlite3.connect(source_path)) as source:
        source.execute("CREATE TABLE payload(value TEXT)")
        source.executemany("INSERT INTO payload(value) VALUES(?)", [(str(i),) for i in range(500)])
        source.commit()
        with closing(sqlite3.connect(partial_path)) as partial:
            def interrupt(status, remaining, total):
                raise RuntimeError("simulated interruption during backup")
            with pytest.raises(RuntimeError, match="simulated interruption"):
                source.backup(partial, pages=1, progress=interrupt)
    assert partial_path.exists()
    assert not final_path.exists()
    # The incomplete temporary file may exist, but it is never published at final_path.


def test_trade_column_migration_is_idempotent_after_reopen(tmp_path):
    path = tmp_path / "migration.sqlite3"
    with Database(str(path)) as first:
        columns = {row["name"] for row in first.conn.execute("PRAGMA table_info(trades)")}
        required = {
            "entry_client_order_id", "exit_order_list_client_id", "stop_price",
            "take_profit_price", "risk_pct", "updated_at",
        }
        assert required <= columns
        assert first.state_get("schema_version") == str(Database.SCHEMA_VERSION)

    with Database(str(path)) as reopened:
        columns = {row["name"] for row in reopened.conn.execute("PRAGMA table_info(trades)")}
        assert required <= columns
        assert reopened.state_get("schema_version") == str(Database.SCHEMA_VERSION)
        assert reopened.conn.execute("PRAGMA integrity_check").fetchone()[0] == "ok"


def test_interrupted_schema_migration_rolls_back_and_retry_completes(tmp_path, monkeypatch):
    path = tmp_path / "migration-interrupted.sqlite3"
    original = Database._migrate_trade_columns

    def fail_after_first_alter(db):
        db.conn.execute(
            "ALTER TABLE trades ADD COLUMN entry_client_order_id TEXT"
        )
        raise sqlite3.OperationalError("injected migration interruption")

    monkeypatch.setattr(Database, "_migrate_trade_columns", fail_after_first_alter)
    with pytest.raises(sqlite3.OperationalError, match="injected migration interruption"):
        Database(str(path))

    # Initialization must close the failed connection, and the ALTER must roll back.
    monkeypatch.setattr(Database, "_migrate_trade_columns", original)
    with closing(sqlite3.connect(path)) as check:
        columns = {row[1] for row in check.execute("PRAGMA table_info(trades)")}
        assert "entry_client_order_id" not in columns
        version = check.execute(
            "SELECT value FROM bot_state WHERE key='schema_version'"
        ).fetchone()
        assert version is None

    with Database(str(path)) as recovered:
        columns = {row["name"] for row in recovered.conn.execute("PRAGMA table_info(trades)")}
        assert {
            "entry_client_order_id", "exit_order_list_client_id", "stop_price",
            "take_profit_price", "risk_pct", "updated_at",
        } <= columns
        assert recovered.state_get("schema_version") == str(Database.SCHEMA_VERSION)
        assert recovered.conn.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
