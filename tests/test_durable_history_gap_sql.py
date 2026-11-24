"""Run the actual Kotlin-declared SQL against SQLite, without Android mocks.
Android helper lifecycle and Kotlin transaction code require separate device tests.
"""
from pathlib import Path
import re
import sqlite3
import random

STORE = Path(__file__).resolve().parents[1] / "app/src/main/java/com/williamsbot/MarketHistoryStore.kt"
HOUR = 3_600_000


def open_store(path):
    db = sqlite3.connect(path)
    db.execute("""CREATE TABLE IF NOT EXISTS candles(
        symbol TEXT NOT NULL, interval TEXT NOT NULL, open_time INTEGER NOT NULL,
        close_time INTEGER NOT NULL, open REAL NOT NULL, high REAL NOT NULL,
        low REAL NOT NULL, close REAL NOT NULL, volume REAL NOT NULL,
        PRIMARY KEY(symbol, interval, open_time))""")
    block = STORE.read_text().split("internal val durableHistoryGapSchema = listOf(", 1)[1].split(
        "internal const val closedIndicatorModelVersion", 1)[0]
    sql = re.findall(r'"""(.*?)"""', block, re.S)
    assert len(sql) == 4
    for statement in sql:
        db.execute(statement)
    db.commit()
    return db


def put(db, hours, symbol="BTCUSDT", close=10.0):
    for h in hours:
        t = h * HOUR
        db.execute("INSERT OR REPLACE INTO candles VALUES(?,?,?,?,?,?,?,?,?)",
                   (symbol, "1h", t, t+HOUR-1, 10, 12, 9, close, 5))


def gaps(db, symbol="BTCUSDT"):
    return db.execute("SELECT start_ms,right_open FROM history_gap WHERE symbol=? AND interval='1h' ORDER BY right_open",
                      (symbol,)).fetchall()


def test_fresh_newest_does_not_hide_missing_bars(tmp_path):
    path = tmp_path / "market.db"
    db = open_store(path)
    with db:
        put(db, [10, 11, 15])
    assert gaps(db) == [(12*HOUR, 15*HOUR)]
    db.close()
    db = open_store(path)
    assert gaps(db) == [(12*HOUR, 15*HOUR)]
    with db:
        put(db, [13])
    assert gaps(db) == [(12*HOUR, 13*HOUR), (14*HOUR, 15*HOUR)]
    with db:
        put(db, [14, 12, 13])
    assert gaps(db) == []
    assert db.execute("SELECT COUNT(*) FROM candles").fetchone()[0] == 6
    db.close()


def test_prepend_correction_and_pair_isolation(tmp_path):
    db = open_store(tmp_path / "market.db")
    with db:
        put(db, [15, 10, 11])
        put(db, [10, 12], "ETHUSDT")
        put(db, [11], close=11)
    assert gaps(db) == [(12*HOUR, 15*HOUR)]
    assert gaps(db, "ETHUSDT") == [(11*HOUR, 12*HOUR)]
    with db:
        put(db, [12, 13, 14])
    assert gaps(db) == []
    assert gaps(db, "ETHUSDT") == [(11*HOUR, 12*HOUR)]
    db.close()


def test_gap_and_candles_rollback_together(tmp_path):
    db = open_store(tmp_path / "market.db")
    with db:
        put(db, [10, 15])
    db.execute("BEGIN")
    put(db, [11, 12, 13, 14])
    assert gaps(db) == []
    db.rollback()
    assert gaps(db) == [(11*HOUR, 15*HOUR)]
    assert db.execute("SELECT COUNT(*) FROM candles").fetchone()[0] == 2
    db.close()


def test_large_missing_range_is_one_row(tmp_path):
    db = open_store(tmp_path / "market.db")
    with db:
        put(db, [10, 10010])
    assert gaps(db) == [(11*HOUR, 10010*HOUR)]
    with db:
        put(db, range(11, 511))
    assert gaps(db) == [(511*HOUR, 10010*HOUR)]
    db.close()


def test_randomized_against_independent_sorted_reference(tmp_path):
    db = open_store(tmp_path / "market.db")
    rng = random.Random(932)
    seen = set()
    for _ in range(2000):
        h = rng.randrange(10, 2000)
        with db:
            put(db, [h])
        seen.add(h)
        ordered = sorted(seen)
        expected = [((a + 1) * HOUR, b * HOUR)
                    for a, b in zip(ordered, ordered[1:]) if b > a + 1]
        assert gaps(db) == expected
    db.close()
