"""Keep manual diagnostic CLIs out of automatic, offline pytest collection.

These legacy scripts perform network scans or require historical local fixtures
during import. They remain explicit CLI commands. Pytest tests are collected.
"""
import os

import pytest


@pytest.fixture(autouse=True)
def isolated_runtime_database(monkeypatch, tmp_path):
    """Offline tests must neither create nor alter the default runtime database."""
    from db import Database
    from test_db_guard import assert_test_db_path

    monkeypatch.setenv("WILLIAMS_DB_PATH", str(tmp_path / "runtime.sqlite3"))
    initialize = Database.__init__

    def guarded_initialize(self, path=None):
        selected = path or os.getenv("WILLIAMS_DB_PATH") or "data/trader.sqlite3"
        assert_test_db_path(selected)
        return initialize(self, path)

    monkeypatch.setattr(Database, "__init__", guarded_initialize)


collect_ignore = [
    "safe_smoke_test.py",
    "test_live_portfolio_scan.py",
    "test_live_scanner.py",
    "test_portfolio_integration.py",
    "test_scanner_diagnostics.py",
    "test_scanner_trader_dry_run.py",
    "test_selected_symbol_prepare.py",
    "test_signal_conditions.py",
]
