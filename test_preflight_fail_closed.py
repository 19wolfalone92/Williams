"""Behavioral fail-closed tests for Task 008 preflight.

These tests use isolated fakes only. They do not contact Binance and do not
claim live Testnet readiness.
"""
from preflight_gate import PreflightCheckService


class FakeClient:
    testnet = True
    base_url = "https://testnet.binance.vision"
    api_key = "test-key-placeholder"
    api_secret = "not-a-real-secret"

    def __init__(self, *, failure=None):
        self.failure = failure
        self.time_offset_ms = 0

    def _raise(self, stage):
        if self.failure == stage:
            raise RuntimeError("https://testnet.invalid/path?signature=DO_NOT_LEAK&secret=DO_NOT_LEAK")

    def sync_time(self):
        self._raise("time")
        return {"serverTime": 1_800_000_000_000}

    def ping(self):
        self._raise("rest")
        return {}

    def account(self):
        self._raise("auth")
        return {"status": "TRADING", "accountType": "SPOT", "balances": []}

    def exchange_info(self, symbol):
        self._raise("symbols")
        return {"symbols": [{
            "symbol": symbol, "status": "TRADING",
            "filters": [
                {"filterType": "PRICE_FILTER"},
                {"filterType": "LOT_SIZE"},
                {"filterType": "MIN_NOTIONAL"},
            ],
        }]}

    def open_orders(self):
        self._raise("orders")
        return []

    def open_order_lists(self):
        return []


def run(failure=None):
    return PreflightCheckService(
        FakeClient(failure=failure), symbols=["BTCUSDT"], max_offset_ms=1000
    ).verify_all()


def test_readiness_never_passes_without_all_runtime_proofs():
    report = run()
    assert report["ready"] is False
    assert report["checks"]["api_reachable"] == "PASS"
    for name in (
        "api_restrictions", "api_trading_status", "candles", "market_websocket", "user_data_stream", "scanner_heartbeat",
        "market_context", "diagnostics_freshness", "storage_integrity",
        "reconciliation", "execution_state_known", "android_freshness",
    ):
        assert report["checks"][name] == "UNKNOWN"


def test_rest_failure_blocks_and_is_structured():
    report = run("rest")
    assert report["ready"] is False
    assert report["checks"]["api_reachable"] == "FAIL"
    assert "RuntimeError" in str(report["details"]["api_reachable"])
    assert "DO_NOT_LEAK" not in str(report)


def test_time_sync_exception_blocks_and_is_sanitized():
    report = run("time")
    assert report["ready"] is False
    assert report["checks"]["server_time"] == "FAIL"
    assert "DO_NOT_LEAK" not in str(report)


def test_authentication_exception_blocks_and_is_sanitized():
    report = run("auth")
    assert report["ready"] is False
    assert report["checks"]["authentication"] == "FAIL"
    assert report["checks"]["account"] == "FAIL"
    assert report["checks"]["balances"] == "FAIL"
    assert "DO_NOT_LEAK" not in str(report)


def test_order_read_exception_blocks_and_is_sanitized():
    report = run("orders")
    assert report["ready"] is False
    assert report["checks"]["open_orders"] == "FAIL"
    assert "DO_NOT_LEAK" not in str(report)


def test_live_or_unknown_environment_fails_before_network():
    client = FakeClient()
    client.testnet = False
    report = PreflightCheckService(client, symbols=["BTCUSDT"]).verify_all()
    assert report["ready"] is False
    assert report["checks"]["environment"] == "FAIL"


def test_large_clock_offset_blocks():
    client = FakeClient()
    client.time_offset_ms = 1001
    report = PreflightCheckService(client, symbols=["BTCUSDT"], max_offset_ms=1000).verify_all()
    assert report["ready"] is False
    assert report["checks"]["server_time"] == "FAIL"


def test_missing_credentials_block():
    client = FakeClient()
    client.api_secret = ""
    report = PreflightCheckService(client, symbols=["BTCUSDT"]).verify_all()
    assert report["ready"] is False
    assert report["checks"]["credentials"] == "FAIL"


def test_unknown_runtime_state_cannot_be_overridden_by_open_order_pass():
    report = run()
    assert report["checks"]["open_orders"] == "PASS"
    assert report["checks"]["reconciliation"] == "UNKNOWN"
    assert report["ready"] is False
    assert report["details"]["readiness"]["blocking_checks"]
