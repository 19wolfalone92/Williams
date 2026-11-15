"""Exercise the actual additive audit schema with legacy data and rollback."""
from pathlib import Path
import re
import sqlite3

ROOT = Path(__file__).resolve().parent


def recovery_schema():
    source = (ROOT / 'app/src/main/java/com/williamsbot/WilliamsStopTrailRecovery.kt').read_text()
    table = re.search(r'"""(CREATE TABLE IF NOT EXISTS stop_trail_recovery_receipts\(.*?)"""', source, re.S)
    index = re.search(r'"(CREATE INDEX IF NOT EXISTS ix_stop_trail_events[^"\n]*)"', source)
    assert table and index
    return table.group(1), index.group(1)


def legacy_database():
    db = sqlite3.connect(':memory:')
    db.execute('CREATE TABLE events(id INTEGER PRIMARY KEY, event_type TEXT NOT NULL, raw_json TEXT NOT NULL)')
    db.execute('INSERT INTO events VALUES(1, ?, ?)', ('WILLIAMS_STOP_TRAIL_DECISION_V1', 'original failure'))
    db.commit()
    for sql in recovery_schema():
        db.execute(sql)
    db.commit()
    return db


def test_upgrade_is_additive_and_repeatable_without_losing_original_failure():
    db = legacy_database()
    for sql in recovery_schema():
        db.execute(sql)
    assert db.execute('SELECT raw_json FROM events WHERE id=1').fetchone() == ('original failure',)
    assert db.execute('PRAGMA quick_check').fetchone() == ('ok',)


def test_recovery_receipt_is_unique_across_restarts_and_failed_batch_rolls_back():
    db = legacy_database()
    with db:
        db.execute('INSERT INTO events VALUES(2, ?, ?)', ('WILLIAMS_STOP_TRAIL_DECISION_V1', 'completed'))
        db.execute('INSERT INTO stop_trail_recovery_receipts VALUES(?, ?, ?, ?)', ('entry', 'stop', 1, 2))
    try:
        with db:
            db.execute('INSERT INTO events VALUES(3, ?, ?)', ('WILLIAMS_STOP_TRAIL_DECISION_V1', 'duplicate'))
            db.execute('INSERT INTO stop_trail_recovery_receipts VALUES(?, ?, ?, ?)', ('entry', 'stop', 1, 3))
    except sqlite3.IntegrityError:
        pass
    else:
        raise AssertionError('An identical recovered replacement must be unique')
    assert db.execute('SELECT id FROM events ORDER BY id').fetchall() == [(1,), (2,)]
    assert db.execute('SELECT count(*) FROM stop_trail_recovery_receipts').fetchone() == (1,)
