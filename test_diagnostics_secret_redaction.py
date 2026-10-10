"""End-to-end synthetic-secret checks for diagnostics and SQLite output."""
import json

from binance_client import BinanceSpotClient
from db import Database
from diagnostics import DiagnosticManager


def test_diagnostic_message_traceback_and_json_redact_registered_secret(tmp_path):
    marker = "SYNTHETIC_SECRET_MARKER_DIAGNOSTICS_0123456789"
    # Constructing the client registers the configured credential for redaction.
    BinanceSpotClient("SYNTHETIC_API_KEY_0123456789", marker, testnet=True)
    manager = DiagnosticManager()
    try:
        raise RuntimeError(f"Authorization: Bearer {marker}; api_secret={marker}")
    except RuntimeError as exc:
        incident = manager.classify("test", exc)

    assert marker not in incident.message
    assert marker not in incident.traceback_text
    payload = manager.report_json(
        incident,
        extra={"api_secret": marker, "error": f"request failed: {marker}"},
    )
    assert marker not in payload
    assert "[REDACTED]" in payload


def test_sqlite_events_and_order_raw_json_redact_registered_secret(tmp_path):
    marker = "SYNTHETIC_SECRET_MARKER_SQLITE_0123456789"
    BinanceSpotClient("SYNTHETIC_API_KEY_SQLITE_012345", marker, testnet=True)
    db = Database(str(tmp_path / "redaction.sqlite3"))

    db.log_event(
        "ERROR",
        "synthetic_error",
        f"timeout: {marker}",
        {"api_secret": marker, "detail": f"exception contains {marker}"},
    )
    db.save_order({
        "symbol": "BTCUSDT",
        "side": "BUY",
        "type": "MARKET",
        "orderId": "redaction-test-1",
        "clientOrderId": "WILL_REDACTION_TEST_1",
        "status": "REJECTED",
        "msg": marker,
    })

    event = db.conn.execute(
        "SELECT message, raw_json FROM events WHERE event='synthetic_error'"
    ).fetchone()
    order = db.conn.execute(
        "SELECT raw_json FROM orders WHERE order_id='redaction-test-1'"
    ).fetchone()
    serialized = json.dumps(dict(event)) + order["raw_json"]
    assert marker not in serialized
    assert "[REDACTED]" in serialized
