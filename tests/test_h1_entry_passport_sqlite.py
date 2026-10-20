"""Run the production H1 passport schema on real SQLite, including upgrades."""
from pathlib import Path
import json
import re
import sqlite3
import pytest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "app/src/main/java/com/williamsbot/WilliamsEntryPassport.kt").read_text()
SCHEMA = [s for s in re.findall(r'"""(.*?)"""\.trimIndent\(\)', SOURCE, re.S)
          if s.lstrip().startswith("CREATE")]
LATEST = re.search(r'latestH1EntryPassportsSql="""(.*?)"""', SOURCE, re.S).group(1)


@pytest.fixture
def db(tmp_path):
    connection = sqlite3.connect(tmp_path / "audit.sqlite3")
    connection.execute("CREATE TABLE orders(id TEXT PRIMARY KEY,payload TEXT)")
    connection.execute("INSERT INTO orders VALUES('old-order','retained')")
    # A prior experimental v4 table must remain intact during additive upgrade.
    connection.execute("CREATE TABLE entry_passport_events(id INTEGER,raw_json TEXT)")
    connection.execute("INSERT INTO entry_passport_events VALUES(1,'legacy')")
    for sql in SCHEMA:
        connection.execute(sql)
    connection.commit()
    yield connection
    connection.close()


def append(db, symbol="BTCUSDT", client="entry", attempt="attempt", phase="PREPARED", at=1000):
    event = dict(symbol=symbol, client_id=client, attempt_id=attempt, phase=phase)
    return db.execute("INSERT INTO h1_entry_passport_events"
                      "(symbol,client_id,attempt_id,direction,phase,observed_at_ms,raw_json)"
                      " VALUES(?,?,?,?,?,?,?)",
                      (symbol, client, attempt, "LONG", phase, at, json.dumps(event))).lastrowid


def test_schema_upgrade_and_reopening_preserve_existing_orders_and_legacy_passports(db):
    for sql in SCHEMA:
        db.execute(sql)
    assert db.execute("SELECT * FROM orders").fetchall() == [("old-order", "retained")]
    assert db.execute("SELECT * FROM entry_passport_events").fetchall() == [(1, "legacy")]


def test_prepared_survives_a_lost_result_and_resume_does_not_rewrite_its_plan(db):
    with db:
        prepared = append(db)
    with pytest.raises(RuntimeError):
        with db:
            append(db, phase="COORDINATOR_RETURNED")
            raise RuntimeError("power loss before committing result")
    rows = db.execute(LATEST).fetchall()
    assert len(rows) == 1 and rows[0][0] == prepared
    assert json.loads(rows[0][2])["phase"] == "PREPARED"


def test_events_cannot_be_updated_or_deleted_and_order_records_still_can(db):
    with db:
        append(db)
    for sql in ("UPDATE h1_entry_passport_events SET phase='FILLED'",
                "DELETE FROM h1_entry_passport_events"):
        with pytest.raises(sqlite3.IntegrityError, match="H1_PASSPORT_APPEND_ONLY"):
            db.execute(sql)
    db.execute("UPDATE orders SET payload='new' WHERE id='old-order'")
    assert db.execute("SELECT COUNT(*) FROM h1_entry_passport_events").fetchone() == (1,)


def test_latest_query_is_per_symbol_and_uses_append_order_even_after_clock_rollback(db):
    append(db, attempt="first", at=2000)
    btc = append(db, attempt="second", at=1000)
    eth = append(db, symbol="ETHUSDT", attempt="eth", at=500)
    rows = {symbol: (row_id, json.loads(raw)) for row_id, symbol, raw in db.execute(LATEST)}
    assert rows["BTCUSDT"][0] == btc and rows["BTCUSDT"][1]["attempt_id"] == "second"
    assert rows["ETHUSDT"][0] == eth


def test_runtime_wires_passport_audit_without_reintroducing_m15_admission():
    runtime = (ROOT / "app/src/main/java/com/williamsbot/StandaloneRuntime.kt").read_text()
    assert "coordinator.setEntryPassportAuditSink" in runtime
    assert "coordinator.requireM15FractalForAutomatedEntries = false" in runtime
    assert "coordinator.m15EntryVerifier = null" in runtime
    assert "auditStore.latestEntryPassportEvents()" in runtime
    assert 'trace.put("entry_passport",williamsEntryPassport(symbol,execution,intents,' in runtime
