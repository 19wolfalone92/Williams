"""Offline static contract guards for TASK 000.7.

These tests intentionally use source text only: no network, credentials, exchange,
or order submission. They should be run from the repository root with pytest.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def test_python_and_android_version_contract_is_aligned():
    gate = read("ci_release_gate.py")
    server = read("server.py")
    gradle = read("app/build.gradle.kts")
    ui = read("app/src/main/java/com/williamsbot/MainActivity.kt")
    expected = re.search(r"EXPECTED_VERSION\s*=\s*'([^']+)'", gate).group(1)
    server_version = re.search(r"VERSION\s*=\s*'([^']+)'", server).group(1)
    android_version = re.search(r'versionName\s*=\s*"([^"]+)"', gradle).group(1)
    assert server_version == expected, f"server.py={server_version}, release gate={expected}"
    assert android_version == expected, f"Gradle={android_version}, release gate={expected}"
    assert f"Williams {expected}" in ui


def test_mutating_python_routes_are_explicitly_inventoryable():
    server = read("server.py")
    expected_routes = (
        "@app.post('/api/v1/control/start'",
        "@app.post('/api/v1/control/stop'",
        "@app.post('/api/v1/control/pause'",
        "@app.post('/api/v1/control/resume'",
        "@app.post('/api/v1/control/panic'",
        "@app.post('/api/v1/control/kill'",
        "@app.post('/api/v1/control/sell'",
        "@app.post('/api/v1/control/recover'",
        "'/api/v1/config/binance'",
    )
    missing = [route for route in expected_routes if route not in server]
    assert not missing, f"Python mutation route inventory incomplete: {missing}"


def test_android_control_dispatch_has_matching_core_paths():
    android = read("app/src/main/java/com/williamsbot/StandaloneRuntime.kt")
    expected_paths = (
        "/api/v1/control/start",
        "/api/v1/control/stop",
        "/api/v1/control/pause",
        "/api/v1/control/resume",
        "/api/v1/control/kill",
        "/api/v1/control/recover",
        "/api/v1/control/sell",
    )
    missing = [path for path in expected_paths if path not in android]
    assert not missing, f"Android control route inventory incomplete: {missing}"


def test_trade_ack_is_not_treated_as_fill_by_contract():
    report = read("docs/audits/task-0007-api-contract.md")
    assert "not evidence of order fill" in report
    assert "never blind mutation retry" in report
    assert "authoritative REST snapshot" in report


def test_ws_snapshot_and_reconciliation_contract_is_documented():
    ws = read("ws_hub.py")
    report = read("docs/audits/task-0007-api-contract.md")
    assert "'user_sync_required':self.user_sync_required" in ws
    assert "monotonic per-account sequence" in report
    assert "fetch authoritative REST snapshot/catch-up" in report
