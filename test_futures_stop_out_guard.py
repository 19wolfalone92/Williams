from datetime import datetime, timezone

from db import Database
from futures_runtime import FuturesRuntime


def insert_event(db, campaign_id, event, reason, created_at):
    db.conn.execute(
        "INSERT INTO campaign_events(campaign_id, event, reason, created_at) VALUES (?, ?, ?, ?)",
        (campaign_id, event, reason, created_at),
    )
    db.conn.commit()


def test_daily_stop_count_counts_only_finalized_stop_closes_in_utc_window():
    db = Database(":memory:")
    insert_event(db, "old", "CAMPAIGN_CLOSED", "EXCHANGE_PROTECTIVE_STOP_FILLED", "2026-10-09 23:59:59")
    insert_event(db, "stop1", "CAMPAIGN_CLOSED", "EXCHANGE_PROTECTIVE_STOP_FILLED", "2026-10-10 00:01:00")
    insert_event(db, "stop2", "CAMPAIGN_CLOSED", "STRUCTURAL_STOP", "2026-10-10 08:00:00")
    insert_event(db, "target", "CAMPAIGN_CLOSED", "STRUCTURAL_TARGET", "2026-10-10 08:10:00")
    insert_event(db, "pending", "EXIT_PENDING", "STOP requested but not filled", "2026-10-10 08:20:00")

    assert db.count_confirmed_stop_exits_since("2026-10-10 00:00:00") == 2
    db.conn.close()


def test_runtime_daily_stop_guard_blocks_after_configured_count():
    db = Database(":memory:")
    insert_event(db, "stop1", "CAMPAIGN_CLOSED", "EXCHANGE_PROTECTIVE_STOP_FILLED", "2026-10-10 00:01:00")
    insert_event(db, "stop2", "CAMPAIGN_CLOSED", "STRUCTURAL_STOP", "2026-10-10 08:00:00")

    runtime = object.__new__(FuturesRuntime)
    runtime.db = db
    runtime.max_stop_outs_per_utc_day = 2

    allowed, reason = runtime._stop_outs_allow_entry(
        now=datetime(2026, 10, 10, 10, 0, tzinfo=timezone.utc)
    )
    assert not allowed
    assert "limit reached" in reason
    db.conn.close()


def test_runtime_daily_stop_guard_fails_closed_if_count_is_unavailable():
    class BrokenDB:
        def count_confirmed_stop_exits_since(self, _):
            raise RuntimeError("sqlite unavailable")

    runtime = object.__new__(FuturesRuntime)
    runtime.db = BrokenDB()
    runtime.max_stop_outs_per_utc_day = 2

    allowed, reason = runtime._stop_outs_allow_entry(
        now=datetime(2026, 10, 10, 10, 0, tzinfo=timezone.utc)
    )
    assert not allowed
    assert "new entries blocked" in reason
