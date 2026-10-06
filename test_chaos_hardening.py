import requests
import pytest

from binance_client import BinanceAPIError, BinanceSpotClient


class FakeResponse:
    def __init__(self, status=200, payload=None, headers=None, text=""):
        self.status_code = status
        self._payload = payload if payload is not None else {}
        self.headers = headers or {}
        self.text = text

    def json(self):
        return self._payload


class FakeSession:
    def __init__(self, actions):
        self.actions = list(actions)
        self.calls = []

    def request(self, method, url, params=None, timeout=None):
        self.calls.append((method, url, params, timeout))
        action = self.actions.pop(0)
        if isinstance(action, BaseException):
            raise action
        return action


def test_post_timeout_is_unknown_execution():
    client = BinanceSpotClient("k", "s")
    client.session = FakeSession([requests.Timeout("lost")])

    with pytest.raises(BinanceAPIError) as exc:
        client.order("BTCUSDT", "BUY", "MARKET", quote_order_qty="25")

    assert exc.value.unknown_execution is True


def test_get_timeout_retries_safely():
    session = FakeSession([
        requests.Timeout("lost"),
        FakeResponse(200, {"price": "100"}),
    ])
    client = BinanceSpotClient("k", "s")
    client.session = session

    assert client.ticker_price("BTCUSDT")["price"] == "100"
    assert len(session.calls) == 2


def test_get_429_retries_after_rate_limit():
    session = FakeSession([
        FakeResponse(429, {"code": -1003}, {"Retry-After": "0.01"}),
        FakeResponse(200, {"price": "101"}),
    ])
    client = BinanceSpotClient("k", "s")
    client.session = session

    assert client.ticker_price("BTCUSDT")["price"] == "101"
    assert len(session.calls) == 2


def test_post_5xx_is_unknown_execution():
    client = BinanceSpotClient("k", "s")
    client.session = FakeSession([
        FakeResponse(500, {"code": -1000, "msg": "server error"})
    ])

    with pytest.raises(BinanceAPIError) as exc:
        client.order("BTCUSDT", "BUY", "MARKET", quote_order_qty="25")

    assert exc.value.unknown_execution is True


def test_cancel_replace_uses_stop_on_failure():
    client = BinanceSpotClient("k", "s")
    captured = {}

    def fake_request(method, path, params=None, signed=False):
        captured.update(method=method, path=path, params=params, signed=signed)
        return {"cancelResult": "SUCCESS", "newOrderResult": "SUCCESS"}

    client._request = fake_request
    result = client.cancel_replace(
        "BTCUSDT",
        123,
        "SELL",
        "LIMIT",
        quantity="0.001",
        price="100000",
        time_in_force="GTC",
        new_client_order_id="W4R_TEST",
    )

    assert result["cancelResult"] == "SUCCESS"
    assert captured["method"] == "POST"
    assert captured["path"] == "/api/v3/order/cancelReplace"
    assert captured["signed"] is True
    assert captured["params"]["cancelReplaceMode"] == "STOP_ON_FAILURE"
    assert captured["params"]["cancelOrderId"] == 123
