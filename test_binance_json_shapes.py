import pytest

from binance_client import BinanceAPIError, BinanceSpotClient


def make_client():
    return BinanceSpotClient("key", "secret", testnet=True)


@pytest.mark.parametrize("payload", [[], {}, None, "[]"])
def test_open_orders_rejects_non_array_shape(monkeypatch, payload):
    client = make_client()
    monkeypatch.setattr(
        client,
        "_request",
        lambda *args, **kwargs: payload,
    )
    if isinstance(payload, list):
        assert client.open_orders("BTCUSDT") == payload
    else:
        with pytest.raises(BinanceAPIError, match="expected array"):
            client.open_orders("BTCUSDT")


def test_open_order_list_root_array_is_accepted(monkeypatch):
    client = make_client()
    monkeypatch.setattr(
        client,
        "_request",
        lambda *args, **kwargs: [],
    )
    assert client.open_order_lists("BTCUSDT") == []


def test_order_list_requires_object(monkeypatch):
    client = make_client()
    monkeypatch.setattr(
        client,
        "_request",
        lambda *args, **kwargs: [],
    )
    with pytest.raises(BinanceAPIError, match="expected object"):
        client.order_list("BTCUSDT", list_client_order_id="W4O_TEST")


def test_all_orders_requires_array(monkeypatch):
    client = make_client()
    monkeypatch.setattr(
        client,
        "_request",
        lambda *args, **kwargs: {"orders": []},
    )
    with pytest.raises(BinanceAPIError, match="expected array"):
        client.all_orders("BTCUSDT")


def test_all_order_lists_requires_array(monkeypatch):
    client = make_client()
    monkeypatch.setattr(
        client,
        "_request",
        lambda *args, **kwargs: {"orderList": []},
    )
    with pytest.raises(BinanceAPIError, match="expected array"):
        client.all_order_lists("BTCUSDT")


def test_array_shape_error_is_not_marked_unknown_execution():
    client = make_client()
    with pytest.raises(BinanceAPIError) as exc:
        client._require_json_array({}, "/openOrders")
    assert exc.value.unknown_execution is False

def test_signed_post_retries_once_only_for_timestamp_rejection(monkeypatch):
    client = make_client()
    calls = []

    class FakeResponse:
        def __init__(self, status_code, payload):
            self.status_code = status_code
            self._payload = payload
            self.headers = {}

        def json(self):
            return self._payload

        @property
        def text(self):
            return str(self._payload)

    responses = [
        FakeResponse(400, {"code": -1021, "msg": "Timestamp for this request is outside recvWindow."}),
        FakeResponse(200, {"ok": True}),
    ]

    def fake_request(*args, **kwargs):
        calls.append((args, kwargs))
        return responses.pop(0)

    monkeypatch.setattr(client.session, "request", fake_request)
    monkeypatch.setattr(client, "sync_time", lambda: setattr(client, "time_offset_ms", 123))

    result = client._request(
        "POST",
        "/api/v3/order",
        {"symbol": "BTCUSDT"},
        signed=True,
    )

    assert result == {"ok": True}
    assert len(calls) == 2


def test_signed_post_transport_error_is_not_retried(monkeypatch):
    client = make_client()
    calls = []

    def fake_request(*args, **kwargs):
        calls.append((args, kwargs))
        import requests
        raise requests.Timeout("simulated timeout")

    monkeypatch.setattr(client.session, "request", fake_request)

    with pytest.raises(BinanceAPIError) as exc:
        client._request(
            "POST",
            "/api/v3/order",
            {"symbol": "BTCUSDT"},
            signed=True,
        )

    assert exc.value.unknown_execution is True
    assert len(calls) == 1


def test_order_rate_limits_include_10s_window(monkeypatch):
    client = make_client()

    class FakeResponse:
        status_code = 200
        headers = {
            "X-MBX-USED-WEIGHT-1M": "123",
            "X-MBX-ORDER-COUNT-10S": "49",
            "X-MBX-ORDER-COUNT-1M": "401",
        }

        def json(self):
            return {
                "rateLimits": [
                    {
                        "rateLimitType": "REQUEST_WEIGHT",
                        "interval": "MINUTE",
                        "intervalNum": 1,
                        "limit": 6000,
                    },
                    {
                        "rateLimitType": "ORDERS",
                        "interval": "SECOND",
                        "intervalNum": 10,
                        "limit": 50,
                    },
                    {
                        "rateLimitType": "ORDERS",
                        "interval": "MINUTE",
                        "intervalNum": 1,
                        "limit": 1200,
                    },
                ]
            }

        @property
        def text(self):
            return "{}"

    monkeypatch.setattr(client.session, "request", lambda *args, **kwargs: FakeResponse())

    client._request("GET", "/api/v3/exchangeInfo", signed=False)

    assert client.last_order_count_10s == 49
    assert client.last_order_count_1m == 401

    client._request = lambda *args, **kwargs: FakeResponse().json()
    info = client._request("GET", "/api/v3/exchangeInfo")
    client.order_limit_10s = 50
    client.order_limit_1m = 1200
    assert info["rateLimits"][1]["limit"] == 50
