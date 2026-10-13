"""Cross-process regression tests for durable canonical signal claims."""
import multiprocessing
import os
import sqlite3
import tempfile
import time

from db import Database


def _claim_worker(db_path, signal_id, canonical, start_event, result_queue):
    db = Database(db_path)
    try:
        if not start_event.wait(10):
            result_queue.put(("ERROR", "start barrier timeout"))
            return
        try:
            result = db.claim_signal_once(signal_id, canonical)
            result_queue.put((result, os.getpid()))
        except Exception as exc:  # SQLite errors must be observable, never an allow.
            result_queue.put(("ERROR", type(exc).__name__))
    finally:
        db.conn.close()


def _new_database():
    fd, path = tempfile.mkstemp(prefix="williams_signal_claim_", suffix=".sqlite3")
    os.close(fd)
    db = Database(path)
    db.conn.close()
    return path


def _cleanup(path):
    for suffix in ("", "-wal", "-shm"):
        try:
            os.remove(path + suffix)
        except OSError:
            pass


def test_two_os_processes_only_one_can_claim_same_signal():
    path = _new_database()
    ctx = multiprocessing.get_context("spawn")
    start = ctx.Event()
    results = ctx.Queue()
    canonical = {
        "symbol": "BTCUSDT",
        "side": "BUY",
        "timeframe": "1h",
        "signal_bar_time_ms": 1_800_000,
        "confirmation_time_ms": 5_400_000,
        "campaign_id": "campaign-a",
        "signal_type": "CAMPAIGN_ENTRY",
    }
    workers = [
        ctx.Process(target=_claim_worker, args=(path, "signal-fixed-001", canonical, start, results))
        for _ in range(2)
    ]
    try:
        for worker in workers:
            worker.start()
        start.set()
        observed = [results.get(timeout=20)[0] for _ in workers]
        for worker in workers:
            worker.join(timeout=20)
            assert worker.exitcode == 0
        assert sorted(observed) == ["CLAIMED", "DUPLICATE"]
    finally:
        for worker in workers:
            if worker.is_alive():
                worker.terminate()
                worker.join()
        _cleanup(path)


def test_claim_survives_database_reopen_and_new_attempt_identity():
    path = _new_database()
    canonical = {
        "symbol": "ETHUSDT",
        "side": "BUY",
        "timeframe": "1h",
        "signal_bar_time_ms": 1_800_000,
        "confirmation_time_ms": 5_400_000,
        "campaign_id": "campaign-b",
        "signal_type": "CAMPAIGN_ENTRY",
    }
    try:
        first = Database(path)
        assert first.claim_signal_once("signal-fixed-002", canonical) == "CLAIMED"
        first.conn.close()

        restarted = Database(path)
        # intent/client-order attempt IDs are deliberately not part of the
        # claim API: replaying the same logical signal remains blocked.
        assert restarted.claim_signal_once("signal-fixed-002", canonical) == "DUPLICATE"
        restarted.conn.close()
    finally:
        _cleanup(path)


def test_same_signal_id_with_conflicting_canonical_payload_is_detected():
    path = _new_database()
    original = {
        "symbol": "BTCUSDT",
        "side": "BUY",
        "timeframe": "1h",
        "signal_bar_time_ms": 1_800_000,
        "confirmation_time_ms": 5_400_000,
        "campaign_id": "campaign-c",
        "signal_type": "CAMPAIGN_ENTRY",
    }
    conflict = dict(original, side="SELL")
    try:
        db = Database(path)
        assert db.claim_signal_once("signal-fixed-003", original) == "CLAIMED"
        assert db.claim_signal_once("signal-fixed-003", conflict) == "CONFLICT"
        db.conn.close()
    finally:
        _cleanup(path)


def test_eight_os_processes_only_one_can_claim_same_signal():
    path = _new_database()
    ctx = multiprocessing.get_context("spawn")
    start = ctx.Event()
    results = ctx.Queue()
    canonical = {
        "schema_version": 1,
        "market_type": "SPOT",
        "symbol": "SOLUSDT",
        "side": "BUY",
        "timeframe": "1h",
        "signal_bar_time_ms": 7_200_000,
        "confirmation_time_ms": 10_800_000,
        "signal_type": "SUPER_AO",
        "strategy_version": "UNRESOLVED_TEST_FIXTURE",
    }
    workers = [
        ctx.Process(
            target=_claim_worker,
            args=(path, "signal-fixed-many-processes", canonical, start, results),
        )
        for _ in range(8)
    ]
    try:
        for worker in workers:
            worker.start()
        start.set()
        observed = [results.get(timeout=30)[0] for _ in workers]
        for worker in workers:
            worker.join(timeout=30)
            assert worker.exitcode == 0
        assert observed.count("CLAIMED") == 1
        assert observed.count("DUPLICATE") == 7
        assert "ERROR" not in observed
    finally:
        for worker in workers:
            if worker.is_alive():
                worker.terminate()
                worker.join()
        _cleanup(path)


def test_two_os_processes_with_conflicting_payload_never_both_claim():
    path = _new_database()
    ctx = multiprocessing.get_context("spawn")
    start = ctx.Event()
    results = ctx.Queue()
    first = {
        "symbol": "BTCUSDT",
        "side": "BUY",
        "timeframe": "1h",
        "signal_bar_time_ms": 1_800_000,
        "confirmation_time_ms": 5_400_000,
        "signal_type": "REVERSAL",
        "strategy_version": "contract-a",
    }
    conflicting = dict(first, side="SELL")
    workers = [
        ctx.Process(target=_claim_worker, args=(path, "signal-conflict-process", payload, start, results))
        for payload in (first, conflicting)
    ]
    try:
        for worker in workers:
            worker.start()
        start.set()
        observed = [results.get(timeout=20)[0] for _ in workers]
        for worker in workers:
            worker.join(timeout=20)
            assert worker.exitcode == 0
        assert observed.count("CLAIMED") == 1
        assert observed.count("CONFLICT") == 1
        assert "ERROR" not in observed
    finally:
        for worker in workers:
            if worker.is_alive():
                worker.terminate()
                worker.join()
        _cleanup(path)


def test_sqlite_busy_is_an_error_not_an_execution_grant():
    path = _new_database()
    locker = None
    claimant = None
    try:
        claimant = Database(path)
        claimant.conn.execute("PRAGMA busy_timeout=100")
        locker = sqlite3.connect(path, timeout=0.1, isolation_level=None)
        locker.execute("PRAGMA busy_timeout=100")
        locker.execute("BEGIN IMMEDIATE")
        try:
            claimant.claim_signal_once(
                "signal-while-busy",
                {"symbol": "BTCUSDT", "side": "BUY"},
            )
        except sqlite3.OperationalError as exc:
            assert "locked" in str(exc).lower() or "busy" in str(exc).lower()
        else:
            raise AssertionError("SQLite write-lock contention must not return CLAIMED")
    finally:
        if claimant is not None:
            claimant.conn.close()
        if locker is not None:
            try:
                locker.rollback()
            finally:
                locker.close()
        _cleanup(path)
