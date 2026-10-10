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


def test_local_http_config_error_response_redacts_synthetic_secret(monkeypatch):
    from fastapi.testclient import TestClient
    import server
    from safe_logging import register_sensitive_value

    marker = "SYNTHETIC_HTTP_SECRET_MARKER_006_123456"
    token = "SYNTHETIC_LOCAL_BEARER_TOKEN_006_123456789"
    register_sensitive_value(marker)
    monkeypatch.setattr(server, "API_TOKEN", token)

    def fail_configure(*_args, **_kwargs):
        raise RuntimeError("exchange setup failed: api_secret=" + marker)

    monkeypatch.setattr(server.state, "configure", fail_configure)
    with TestClient(server.app) as client:
        response = client.post(
            "/api/v1/config/binance",
            headers={"Authorization": "Bearer " + token},
            json={"api_key": "SYNTHETIC_KEY", "api_secret": marker, "testnet": True},
        )

    assert response.status_code == 409
    assert marker not in response.text
    assert token not in response.text
    assert "[REDACTED]" in response.text
