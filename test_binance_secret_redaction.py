"""Synthetic secret-marker regression tests for Binance client error surfaces."""
import json
import traceback

import pytest
import requests

from binance_client import BinanceAPIError, BinanceSpotClient
from execution_permit import _BARRIER_ADMISSION_TOKEN, _mutation_permit


SECRET_MARKER = "SYNTHETIC_SECRET_MARKER_7d9a"


class FakeResponse:
    def __init__(self, status_code):
        self.status_code = status_code
        self.headers = {}
        self.text = f"body={SECRET_MARKER}"

    def json(self):
        return {"code": -1000, "msg": f"body={SECRET_MARKER}", "apiSecret": SECRET_MARKER}


class FakeSession:
    def __init__(self, response=None, exception=None):
        self.response = response
        self.exception = exception
        self.headers = {}
        self.called = False

    def request(self, *args, **kwargs):
        self.called = True
        if self.exception:
            raise self.exception
        return self.response


@pytest.mark.parametrize("status", [400, 409, 418, 429, 500])
def test_http_error_payload_and_traceback_do_not_expose_marker(status):
    client = BinanceSpotClient("fake-key", "fake-secret", testnet=True)
    session = FakeSession(response=FakeResponse(status))
    client.session = session
    method = "POST" if status in (409, 418, 429, 500) else "GET"
    path = "/api/v3/order/cancelReplace" if status == 409 else "/api/v3/order" if method == "POST" else "/api/v3/account"

    try:
        if method == "POST":
            with _mutation_permit(client, admission_token=_BARRIER_ADMISSION_TOKEN):
                client._request(method, path, {}, signed=False)
        else:
            client._request(method, path, {}, signed=False)
    except BinanceAPIError as exc:
        rendered = "\n".join(traceback.format_exception(exc))
        surfaces = str(exc) + repr(exc.payload) + json.dumps(exc.payload, default=str) + rendered
        assert SECRET_MARKER not in surfaces
        assert "response body redacted" in surfaces or "rate limit response" in surfaces
    else:
        pytest.fail(f"HTTP {status} should have raised BinanceAPIError")
    assert session.called


def test_transport_exception_marker_is_not_rendered_in_traceback():
    client = BinanceSpotClient("fake-key", "fake-secret", testnet=True)
    session = FakeSession(exception=requests.Timeout(f"request URL contains {SECRET_MARKER}"))
    client.session = session

    with pytest.raises(BinanceAPIError) as caught:
        client._request("GET", "/api/v3/account", {}, signed=False)

    rendered = "\n".join(traceback.format_exception(caught.value))
    surfaces = str(caught.value) + repr(caught.value.payload) + rendered
    assert SECRET_MARKER not in surfaces
    assert "Timeout" in str(caught.value)


def test_spot_testnet_permit_cannot_authorize_mainnet_client():
    testnet_client = BinanceSpotClient("fake-key", "fake-secret", testnet=True)
    mainnet_client = BinanceSpotClient("fake-key", "fake-secret", testnet=False)

    class NoNetworkSession:
        called = False
        def request(self, *args, **kwargs):
            self.called = True
            raise AssertionError("network must not be reached")

    testnet_session = NoNetworkSession()
    mainnet_session = NoNetworkSession()
    testnet_client.session = testnet_session
    mainnet_client.session = mainnet_session

    with _mutation_permit(testnet_client, admission_token=_BARRIER_ADMISSION_TOKEN):
        with pytest.raises(RuntimeError, match="matching ExecutionBarrier permit required"):
            mainnet_client.order(
                "BTCUSDT", "BUY", "MARKET",
                quantity="0.001", new_client_order_id="CID_WRONG_CLIENT",
            )

    assert not testnet_session.called
    assert not mainnet_session.called
