"""SQLite lifecycle and production-path guard regressions; temporary DBs only."""
import sqlite3
from contextlib import closing
from pathlib import Path

import pytest

import test_db_guard
from db import Database
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
