"""Synthetic-marker tests for Binance HTTP error sanitization; no network calls."""
import requests
import pytest

from binance_client import BinanceAPIError, BinanceSpotClient


SECRET_MARKER = "SYNTHETIC_SECRET_MARKER_DO_NOT_LOG"


class _Response:
    def __init__(self, status_code, payload=None, body=None):
        self.status_code = status_code
        self.headers = {}
        self._payload = payload
        self.text = body if body is not None else repr(payload)
        self.content = self.text.encode()

    def json(self):
        if isinstance(self._payload, Exception):
            raise self._payload
        return self._payload


class _Session:
    def __init__(self, result):
        self.result = result
        self.headers = type("_Headers", (), {"update": lambda self, _value: None})()
        self.calls = 0

    def request(self, *args, **kwargs):
        self.calls += 1
        if isinstance(self.result, BaseException):
            raise self.result
        return self.result


def _client(result):
    client = BinanceSpotClient("SYNTHETIC_API_KEY", "SYNTHETIC_API_SECRET", testnet=True)
    client.session = _Session(result)
    return client


def test_timeout_message_and_traceback_chain_do_not_expose_exception_text():
    client = _client(requests.Timeout(SECRET_MARKER))
    with pytest.raises(BinanceAPIError) as caught:
        client._request("POST", "/api/v3/order", {"symbol": "BTCUSDT"}, signed=False)
    assert caught.value.unknown_execution is True
    assert SECRET_MARKER not in str(caught.value)
    assert caught.value.__cause__ is None
    assert client.session.calls == 1


def test_api_error_body_is_redacted_but_safe_status_and_code_remain():
    client = _client(_Response(400, {"code": -1000, "msg": SECRET_MARKER}))
    with pytest.raises(BinanceAPIError) as caught:
        client._request("POST", "/api/v3/order", {"symbol": "BTCUSDT"}, signed=False)
    assert "-1000" in str(caught.value)
    assert SECRET_MARKER not in str(caught.value)
    assert SECRET_MARKER not in repr(caught.value.payload)
    assert caught.value.payload == {"code": -1000, "msg": "response body redacted"}


def test_malformed_http_body_is_redacted():
    client = _client(_Response(500, ValueError("bad JSON"), body=SECRET_MARKER))
    with pytest.raises(BinanceAPIError) as caught:
        client._request("POST", "/api/v3/order", {"symbol": "BTCUSDT"}, signed=False)
    assert caught.value.unknown_execution is True
    assert SECRET_MARKER not in str(caught.value)
    assert SECRET_MARKER not in repr(caught.value.payload)
    assert caught.value.payload["msg"] == "response body redacted"


def test_cancel_replace_error_payload_is_redacted():
    client = _client(_Response(409, {"code": -2022, "msg": SECRET_MARKER}))
    with pytest.raises(BinanceAPIError) as caught:
        client._request("POST", "/api/v3/order/cancelReplace", {}, signed=False)
    assert caught.value.unknown_execution is True
    assert "-2022" in str(caught.value)
    assert SECRET_MARKER not in str(caught.value)
    assert SECRET_MARKER not in repr(caught.value.payload)



@pytest.mark.parametrize(
    ("status", "executed_qty"),
    [("PARTIALLY_FILLED", "0.4"), ("FILLED", "1.0")],
)
def test_order_safe_reconciles_lost_post_response_by_client_order_id(status, executed_qty):
    client_order_id = f"WILL_TASK006_RECOVER_{status}_011"
    exchange_order = {
        "symbol": "BTCUSDT",
        "orderId": 9001,
        "clientOrderId": client_order_id,
        "status": status,
        "origQty": "1.0",
        "executedQty": executed_qty,
        "cummulativeQuoteQty": str(float(executed_qty) * 100.0),
    }

    class _AcceptThenLoseResponseSession:
        def __init__(self):
            self.calls = []
            self.headers = type("_Headers", (), {"update": lambda self, _value: None})()

        def request(self, method, url, params=None, timeout=None):
            self.calls.append((method, url, dict(params or {})))
            if method == "POST":
                # Fake exchange accepted the order before the client lost the response.
                raise requests.Timeout("synthetic lost response")
            if method == "GET" and url.endswith("/api/v3/order"):
                return _Response(200, exchange_order)
            raise AssertionError(f"Unexpected fake exchange request: {method} {url}")

    client = BinanceSpotClient("SYNTHETIC_API_KEY_012345", "SYNTHETIC_API_SECRET_012345", testnet=True)
    session = _AcceptThenLoseResponseSession()
    client.session = session

    result = client.order_safe(
        "BTCUSDT",
        "BUY",
        "MARKET",
        quantity="1.0",
        new_client_order_id=client_order_id,
    )

    assert result["clientOrderId"] == client_order_id
    assert result["status"] == status
    assert result["executedQty"] == executed_qty
    assert [method for method, _url, _params in session.calls].count("POST") == 1
    assert [method for method, _url, _params in session.calls].count("GET") == 1
    assert SECRET_MARKER not in repr(result)
