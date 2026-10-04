"""
Williams test database safety guard.

Purpose:
    Prevent tests from accidentally writing to the production SQLite DB.

Usage:
    from test_db_guard import assert_test_db_safe
    assert_test_db_safe()

The guard intentionally allows production DB access only when the caller
explicitly opts into production verification via WILLIAMS_ALLOW_PROD_DB_TEST=1.
"""

import os
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parent
PRODUCTION_DB = (PROJECT_ROOT / "data" / "trader.sqlite3").resolve()


def resolved_db_path():
    configured = (
        os.getenv("WILLIAMS_DB_PATH")
        or os.getenv("DB_PATH")
        or str(PRODUCTION_DB)
    )
    return Path(configured).resolve()


def assert_test_db_safe():
    """
    Fail hard if a test is about to use production DB.

    Tests must provide WILLIAMS_DB_PATH or DB_PATH pointing somewhere other
    than data/trader.sqlite3.
    """

    actual = resolved_db_path()

    if actual == PRODUCTION_DB:
        if os.getenv("WILLIAMS_ALLOW_PROD_DB_TEST") == "1":
            return

        raise RuntimeError(
            "TEST DATABASE SAFETY VIOLATION: "
            f"test attempted to use production DB: {actual}\n"
            "Set WILLIAMS_DB_PATH to an isolated test database."
        )

    return


def assert_test_db_path(path):
    """
    Validate an explicitly supplied SQLite path.
    """
    actual = Path(path).resolve()

    if actual == PRODUCTION_DB:
        if os.getenv("WILLIAMS_ALLOW_PROD_DB_TEST") == "1":
            return

        raise RuntimeError(
            "TEST DATABASE SAFETY VIOLATION: "
            f"attempted production DB: {actual}"
        )
