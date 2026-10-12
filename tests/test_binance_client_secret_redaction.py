"""Regression tests for secret-safe Binance transport exceptions.

Only synthetic credentials and mocked HTTP responses are used.
"""
import requests
import pytest

from binance_client import BinanceAPIError, BinanceSpotClient


SYNTHETIC_SECRETS = (
    "TEST_API_KEY_000000000000",
    "TEST_API_SECRET_111111111111",
    "signature=TEST_SIGNATURE_222222222222",
    "Authorization: Bearer TEST_AUTH_333333333333",
    "token=TEST_QUERY_444444444444",
    "TEST_HTTP_BODY_SECRET_555555555555",
)


class RaisingSession:
    def __init__(self, exc):
        self.exc = exc
        self.headers = {}
        self.calls = 0

    def request(self, *args, **kwargs):
        self.calls += 1
        raise self.exc


class FakeResponse:
    def __init__(self, status_code, body):
        self.status_code = status_code
        self.headers = {}
        self.text = body
        self.content = body.encode()
        self._body = body

    def json(self):
        return {"code": -1000, "msg": self._body}


class ReturningSession:
    def __init__(self, response):
        self.response = response
        self.headers = {}
        self.calls = 0

    def request(self, *args, **kwargs):
        self.calls += 1
        return self.response


@pytest.mark.parametrize("secret", SYNTHETIC_SECRETS)
def test_transport_exception_details_are_redacted_and_mutation_is_unknown(secret):
    client = BinanceSpotClient("TEST_API_KEY_000000000000", "TEST_API_SECRET_111111111111")
    client.session = RaisingSession(requests.Timeout(f"transport failed {secret}"))

    with pytest.raises(BinanceAPIError) as caught:
        client._request("POST", "/api/v3/order", {"symbol": "BTCUSDT"}, signed=True)

    error = caught.value
    assert error.unknown_execution is True
    assert secret not in str(error)
    assert client.session.calls == 1, "ambiguous mutation must never be blindly retried"
    assert error.payload is None


@pytest.mark.parametrize("status_code", [409, 429, 500])
def test_http_response_body_is_not_exposed_in_error_or_payload(status_code):
    body = (
        "apiKey=TEST_API_KEY_000000000000 "
        "secret=TEST_API_SECRET_111111111111 "
        "signature=TEST_SIGNATURE_222222222222 "
        "Authorization: Bearer TEST_AUTH_333333333333 "
        "token=TEST_QUERY_444444444444 "
        "TEST_HTTP_BODY_SECRET_555555555555"
    )
    client = BinanceSpotClient("TEST_API_KEY_000000000000", "TEST_API_SECRET_111111111111")
    client.session = ReturningSession(FakeResponse(status_code, body))

    with pytest.raises(BinanceAPIError) as caught:
        client._request("POST", "/api/v3/order", {"symbol": "BTCUSDT"}, signed=True)

    error = caught.value
    assert body not in str(error)
    assert all(secret not in str(error) for secret in SYNTHETIC_SECRETS)
    assert error.payload is None or error.payload == {"code": -1000}
    assert error.unknown_execution is True
    assert client.session.calls == 1, "ambiguous mutation must never be blindly retried"
