"""Regression: Android rawQuery(String[]) binds every argument as SQL TEXT.

Arithmetic expressions (open_time + 3600000, COALESCE(...)) have no
SQLite column affinity, so a comparison with rawQuery bound TEXT must CAST
to numeric. Without it every 1h replay clock page looks empty even though
the long-term archive contains six years of candles, and every commit
revision guard reports a spurious source change.
"""
from pathlib import Path
import re
import sqlite3

SOURCE = Path("app/src/main/java/com/williamsbot/WilliamsDeviceWaveReplay.kt")


def kotlin_sql(name: str) -> str:
    source = SOURCE.read_text(encoding="utf-8")
    match = re.search(r"internal val " + re.escape(name) + r'=\s*"""(.*?)"""', source, re.S)
    assert match, f"Missing production SQL: {name}"
    return match.group(1)


def test_h1_archive_clock_uses_real_sqlite_string_binding():
    db = sqlite3.connect(":memory:")
    db.execute("""CREATE TABLE candles(
       symbol TEXT, interval TEXT, open_time INTEGER, close_time INTEGER,
       open REAL, high REAL, low REAL, close REAL, volume REAL)""")
    first = 1_792_526_400_000
    db.executemany(
        "INSERT INTO candles VALUES(?,?,?,?,?,?,?,?,?)",
        [("BTCUSDT", "1h", first + i * 3600000,
          first + (i + 1) * 3600000 - 1, 1., 2., .5, 1.5, 10.) for i in range(4)]
    )
    # Exact binding contract: SQLite rawQuery passes strings, not integer objects.
    rows = db.execute(kotlin_sql("deviceWaveReplayClockSql"),
                      ("BTCUSDT", "0", str(first + 4 * 3600000),
                       str(first + 4 * 3600000), "32")).fetchall()
    assert [r[0] for r in rows] == [first + i * 3600000 for i in range(1, 5)]


def test_wave_commit_revision_guard_uses_numeric_cast_for_coalesce():
    db = sqlite3.connect(":memory:")
    db.executescript("""CREATE TABLE market_history_revision (
        symbol TEXT PRIMARY KEY, revision INTEGER);
    CREATE TABLE wave_replay_state (
        symbol TEXT PRIMARY KEY, model TEXT, last_closed_ms INTEGER,
        processed INTEGER, chain_sha256 TEXT, dirty_from_ms INTEGER);""")
    db.execute("INSERT INTO market_history_revision VALUES ('BTCUSDT',42)")
    db.execute("INSERT INTO wave_replay_state VALUES(?,?,?,?,?,?)",
               ("BTCUSDT", "WILLIAMS_H1_ARCHIVE_W3_GLOBAL_LAG_V2", 0, 0, "", 0))
    query = kotlin_sql("deviceWaveReplayCommitGuardSql")
    args = ("BTCUSDT", "0", "WILLIAMS_H1_ARCHIVE_W3_GLOBAL_LAG_V2", "", "0", "42")
    assert db.execute(query, args).fetchone() == (1,), "clean source must admit replay commit"
    db.execute("UPDATE market_history_revision SET revision=revision+1 WHERE symbol='BTCUSDT'")
    assert db.execute(query, args).fetchone() is None, "actual changed source must block commit"
    db.execute("UPDATE market_history_revision SET revision=42 WHERE symbol='BTCUSDT'")
    db.execute("UPDATE wave_replay_state SET dirty_from_ms=1 WHERE symbol='BTCUSDT'")
    assert db.execute(query, args).fetchone() is None, "dirty replay must stay blocked"


def test_sqlite_did_reproduce_old_all_empty_false_positive():
    db = sqlite3.connect(":memory:")
    assert db.execute("SELECT (1792526400000+3600000)>?", ("0",)).fetchone() == (0,)
    assert db.execute("SELECT COALESCE(42,0)=?", ("42",)).fetchone() == (0,)
    assert db.execute("SELECT (1792526400000+3600000)>CAST(? AS INTEGER)", ("0",)).fetchone() == (1,)
    assert db.execute("SELECT COALESCE(42,0)=CAST(? AS INTEGER)", ("42",)).fetchone() == (1,)
