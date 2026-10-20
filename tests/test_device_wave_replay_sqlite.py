"""Exercise actual production trigger SQL against real SQLite, including REPLACE."""
from pathlib import Path
import re
import sqlite3
import pytest

SOURCE = Path(__file__).parents[1] / 'app/src/main/java/com/williamsbot/WilliamsDeviceWaveReplay.kt'
SCHEMA = [s for s in re.findall(r'"""(.*?)"""\.trimIndent\(\)', SOURCE.read_text(), re.S)
          if s.lstrip().startswith('CREATE')]
CLOCK = re.search(r'deviceWaveReplayClockSql="""(.*?)"""', SOURCE.read_text(), re.S).group(1)
GUARD = re.search(r'deviceWaveReplayCommitGuardSql="""(.*?)"""', SOURCE.read_text(), re.S).group(1)


@pytest.fixture
def db(tmp_path):
    con = sqlite3.connect(tmp_path/'history.sqlite3')
    con.execute('CREATE TABLE candles(symbol TEXT, interval TEXT, open_time INTEGER, close_time INTEGER, '
                'open REAL, high REAL, low REAL, close REAL, volume REAL, PRIMARY KEY(symbol,interval,open_time))')
    con.execute("INSERT INTO candles VALUES('ETHUSDT','1h',0,3599999,100,101,99,100,10)")
    con.commit()
    for sql in SCHEMA:
        con.execute(sql)
    con.execute("INSERT INTO wave_replay_state(symbol,model,last_closed_ms,processed) VALUES('ETHUSDT','model',7200000,2)")
    con.commit()
    yield con
    con.close()


def ingest(db, opened=0, close=100, closed=None, symbol='ETHUSDT'):
    closed = opened+3599999 if closed is None else closed
    db.execute('INSERT OR REPLACE INTO candles VALUES(?,?,?,?,?,?,?,?,?)',
               (symbol,'1h',opened,closed,100,max(101,close),99,close,10))


def revision(db):
    row = db.execute("SELECT revision FROM market_history_revision WHERE symbol='ETHUSDT'").fetchone()
    return row[0] if row else 0


def dirty(db):
    return db.execute("SELECT dirty_from_ms FROM wave_replay_state WHERE symbol='ETHUSDT'").fetchone()[0]


def test_upgrade_preserves_candles_and_identical_restart_tails_do_not_invalidate(db):
    for _ in range(3):
        ingest(db)
    assert db.execute('SELECT COUNT(*) FROM candles').fetchone()[0] == 1
    assert revision(db) == 0 and dirty(db) == 0


def test_replace_of_old_candle_invalidates_and_revision_never_resets_to_one(db):
    for price in (100.5,100.7,100.8):
        ingest(db,close=price)
    assert revision(db) == 3
    assert dirty(db) == 3600000
    assert db.execute("SELECT last_closed_ms FROM wave_replay_state").fetchone()[0] == 7200000


def test_future_append_does_not_rewind_already_replayed_history(db):
    ingest(db,opened=7200000)
    assert revision(db) == 1 and dirty(db) == 0


def test_late_backfill_and_delete_of_closed_history_invalidate_replay(db):
    ingest(db,opened=3600000)
    assert dirty(db) == 7200000
    db.execute('DELETE FROM candles WHERE open_time=0')
    assert dirty(db) == 3600000


def test_replace_cannot_hide_old_history_by_changing_close_time_to_future(db):
    ingest(db,closed=10000000)
    assert dirty(db) == 3600000


def test_another_symbol_does_not_invalidate_this_pairs_checkpoint(db):
    ingest(db,symbol='BTCUSDT')
    assert revision(db) == 0 and dirty(db) == 0


def test_failed_batch_rolls_back_source_revision_and_invalidation_together(db):
    with pytest.raises(RuntimeError):
        with db:
            ingest(db,close=100.9)
            raise RuntimeError('power loss before commit')
    assert dirty(db) == 0 and revision(db) == 0
    assert db.execute('SELECT close FROM candles WHERE open_time=0').fetchone()[0] == 100


def test_unchanged_update_is_inert_but_real_update_invalidates(db):
    db.execute('UPDATE candles SET close=close WHERE open_time=0')
    assert revision(db) == 0
    db.execute('UPDATE candles SET close=100.5 WHERE open_time=0')
    assert revision(db) == 1 and dirty(db) == 3600000


def test_journal_and_cursor_are_atomic_under_interrupted_commit(db):
    with pytest.raises(RuntimeError):
        with db:
            db.execute("INSERT INTO wave_replay_journal VALUES('ETHUSDT',10800000,'state','chain',?)",(b'evidence',))
            db.execute("UPDATE wave_replay_state SET last_closed_ms=10800000,processed=3 WHERE symbol='ETHUSDT'")
            raise RuntimeError('kill during transaction')
    assert db.execute('SELECT COUNT(*) FROM wave_replay_journal').fetchone()[0] == 0
    assert db.execute('SELECT last_closed_ms,processed FROM wave_replay_state').fetchone() == (7200000,2)


def test_actual_clock_query_visits_only_genuine_closed_h1_rows_and_skips_gaps(db):
    ingest(db,opened=3600000,closed=9999999)
    ingest(db,opened=7200000)
    assert db.execute(CLOCK,('ETHUSDT',0,10800000,10800000,32)).fetchall() == [(3600000,),(10800000,)]
    assert db.execute(CLOCK,('ETHUSDT',3600000,10800000,10800000,1)).fetchall() == [(10800000,)]
    assert db.execute(CLOCK,('ETHUSDT',0,3599999,3599999,32)).fetchall() == []


def test_actual_commit_guard_rejects_a_source_write_even_when_it_is_only_future_data(db):
    args=('ETHUSDT',7200000,'model','',2,0)
    assert db.execute(GUARD,args).fetchone() == (1,)
    ingest(db,opened=7200000)
    assert dirty(db) == 0
    assert db.execute(GUARD,args).fetchone() is None
    assert db.execute(GUARD,(*args[:-1],1)).fetchone() == (1,)
    ingest(db,close=100.8)
    assert db.execute(GUARD,(*args[:-1],2)).fetchone() is None
