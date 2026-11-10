"""Cross-component regression for on-device Cockpit audit event retention.

The SQL tested here is read from the real Kotlin constants, not a manually
duplicated test-only approximation. Tests execute against real sqlite3.
"""
from pathlib import Path
import re
import sqlite3


ROOT = Path(__file__).resolve().parent
KOTLIN = ROOT / "app/src/main/java/com/williamsbot/TradingAuditStore.kt"


def _statement(name: str) -> str:
    source = KOTLIN.read_text(encoding="utf-8")
    match = re.search(r"\b" + re.escape(name) + r'\s*=\s*"([^"]+)"', source)
    assert match, f"SQLite retention SQL must be a literal in {KOTLIN}: {name}"
    return match.group(1)


def test_separate_cockpit_and_incident_retention_never_erases_signed_decisions():
    conn = sqlite3.connect(":memory:")
    conn.execute("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, event_type TEXT)")
    for i in range(1200):
        conn.execute("INSERT INTO events(event_type) VALUES ('COCKPIT_EXECUTION')")
        if i % 9 == 0:
            conn.execute("INSERT INTO events(event_type) VALUES ('COCKPIT_INCIDENT')")
    for name in ("WILLIAMS_STOP_TRAIL_DECISION_V1", "ORDER_FILLED_SIGNED",
                 "WILLIAMS_STOP_TRAIL_DECISION_V1"):
        conn.execute("INSERT INTO events(event_type) VALUES (?)", (name,))

    newest_ui = conn.execute("SELECT MAX(id) FROM events WHERE event_type='COCKPIT_EXECUTION'").fetchone()[0]
    newest_incident = conn.execute("SELECT MAX(id) FROM events WHERE event_type='COCKPIT_INCIDENT'").fetchone()[0]
    assert conn.execute("SELECT count(*) FROM events WHERE event_type='COCKPIT_INCIDENT'").fetchone()[0] > 100

    conn.execute(_statement("COCKPIT_PROJECTION_TRIM_SQL"))
    conn.execute(_statement("COCKPIT_INCIDENT_TRIM_SQL"))
    amounts = dict(conn.execute("SELECT event_type,count(*) FROM events GROUP BY event_type"))
    assert amounts["COCKPIT_EXECUTION"] == 1000
    assert amounts["COCKPIT_INCIDENT"] == 100
    assert amounts["WILLIAMS_STOP_TRAIL_DECISION_V1"] == 2
    assert amounts["ORDER_FILLED_SIGNED"] == 1
    assert conn.execute("SELECT MAX(id) FROM events WHERE event_type='COCKPIT_EXECUTION'").fetchone()[0] == newest_ui
    assert conn.execute("SELECT MAX(id) FROM events WHERE event_type='COCKPIT_INCIDENT'").fetchone()[0] == newest_incident
    conn.execute(_statement("COCKPIT_PROJECTION_TRIM_SQL"))
    conn.execute(_statement("COCKPIT_INCIDENT_TRIM_SQL"))
    assert conn.execute("SELECT count(*) FROM events").fetchone()[0] == 1103


def test_incident_volume_cannot_displace_last_thousand_ui_events():
    conn = sqlite3.connect(":memory:")
    conn.execute("CREATE TABLE events(id INTEGER PRIMARY KEY, event_type TEXT)")
    conn.executemany("INSERT INTO events(event_type) VALUES (?)",
        [("COCKPIT_RECONCILIATION",)] * 1000 + [("COCKPIT_INCIDENT",)] * 500)
    conn.execute(_statement("COCKPIT_PROJECTION_TRIM_SQL"))
    conn.execute(_statement("COCKPIT_INCIDENT_TRIM_SQL"))
    assert conn.execute("SELECT count(*) FROM events WHERE event_type='COCKPIT_RECONCILIATION'").fetchone()[0] == 1000
    assert conn.execute("SELECT count(*) FROM events WHERE event_type='COCKPIT_INCIDENT'").fetchone()[0] == 100
