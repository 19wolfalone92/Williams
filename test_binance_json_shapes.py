import pytest
from unittest.mock import Mock

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



def test_order_rate_limits_are_learned_from_exchange_info(monkeypatch):
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
                ],
                "symbols": [],
            }

        @property
        def text(self):
            return "{}"

    monkeypatch.setattr(
        client.session,
        "request",
        lambda *args, **kwargs: FakeResponse(),
    )

    result = client.exchange_info()

    assert result["rateLimits"][1]["limit"] == 50
    assert client.request_weight_limit_1m == 6000
    assert client.order_limit_10s == 50
    assert client.order_limit_1m == 1200
    assert client.last_used_weight_1m == 123
    assert client.last_order_count_10s == 49
    assert client.last_order_count_1m == 401


def test_ticker_prices_requires_array(monkeypatch):
    client = make_client()
    monkeypatch.setattr(
        client,
        "_request",
        lambda *args, **kwargs: {"BTCUSDT": "1"},
    )
    with pytest.raises(BinanceAPIError, match="expected array"):
        client.ticker_prices()


def test_oco_quantizes_both_sell_stop_legs(monkeypatch):
    client = make_client()
    monkeypatch.setattr(
        client,
        "_symbol_tick_size",
        lambda symbol: __import__("decimal").Decimal("0.1"),
    )
    captured = {}

    def fake_request(method, path, params=None, signed=False):
        captured["params"] = params
        return {"orderListId": 1, "orderReports": []}

    monkeypatch.setattr(client, "_request", fake_request)
    result = client.create_oco_sell(
        "BTCUSDT",
        "1",
        "105.27",
        "95.03",
        "94.96",
        "WILLV4_OCO_TEST",
    )
    assert result["orderListId"] == 1
    assert captured["params"]["aboveStopPrice"] == "105.2"
    assert captured["params"]["abovePrice"] == "105.1"
    assert captured["params"]["belowStopPrice"] == "95.0"
    assert captured["params"]["belowPrice"] == "94.9"


def test_cancel_replace_http_409_is_unknown_and_not_retried(monkeypatch):
    client = make_client()
    calls = []

    class FakeResponse:
        status_code = 409
        headers = {}
        content = b'{"code":-2022,"msg":"Order cancel-replace partially failed."}'

        @property
        def text(self):
            return self.content.decode()

        def json(self):
            return {"code": -2022, "msg": "Order cancel-replace partially failed."}

    def fake_request(*args, **kwargs):
        calls.append(1)
        return FakeResponse()

    monkeypatch.setattr(client.session, "request", fake_request)

    with pytest.raises(BinanceAPIError) as exc:
        client._request(
            "POST",
            "/api/v3/order/cancelReplace",
            {"symbol": "BTCUSDT", "cancelReplaceMode": "STOP_ON_FAILURE"},
            signed=True,
        )

    assert exc.value.unknown_execution is True
    assert exc.value.status_code == 409
    assert len(calls) == 1


def test_order_mutation_rate_limit_is_unknown_and_not_retried(monkeypatch):
    client = make_client()
    calls = []

    class FakeResponse:
        status_code = 429
        headers = {"Retry-After": "1"}

        @property
        def content(self):
            return b'{"code":-1003,"msg":"Too many requests"}'

        @property
        def text(self):
            return '{"code":-1003,"msg":"Too many requests"}'

        def json(self):
            return {"code": -1003, "msg": "Too many requests"}

    def fake_request(*args, **kwargs):
        calls.append(1)
        return FakeResponse()

    monkeypatch.setattr(client.session, "request", fake_request)

    with pytest.raises(BinanceAPIError) as exc:
        client._request(
            "POST",
            "/api/v3/order",
            {"symbol": "BTCUSDT"},
            signed=True,
        )

    assert exc.value.unknown_execution is True
    assert exc.value.status_code == 429
    assert len(calls) == 1


@pytest.mark.parametrize("status", ["NEW", "PARTIALLY_FILLED", "FILLED", "PENDING_CANCEL", "PENDING_NEW"])
def test_order_safe_reconciles_ambiguous_post_by_client_order_id(monkeypatch, status):
    client = make_client()
    client_order_id = "WILLIAMS_AUDIT_RECONCILE_001"
    ambiguous = BinanceAPIError("simulated response lost", unknown_execution=True)
    submit = Mock(side_effect=ambiguous)
    existing = {
        "symbol": "BTCUSDT",
        "clientOrderId": client_order_id,
        "orderId": 98765,
        "status": status,
        "executedQty": "0.25" if status == "PARTIALLY_FILLED" else "1.0" if status == "FILLED" else "0",
    }
    lookup = __import__("unittest.mock", fromlist=["Mock"]).Mock(return_value=existing)
    monkeypatch.setattr(client, "order", submit)
    monkeypatch.setattr(client, "get_order", lookup)

    result = client.order_safe(
        "BTCUSDT", "BUY", "LIMIT",
        quantity="1",
        new_client_order_id=client_order_id,
    )

    assert result is existing
    submit.assert_called_once()
    lookup.assert_called_once_with("BTCUSDT", orig_client_order_id=client_order_id)


def test_order_safe_does_not_retry_when_reconciliation_lookup_fails(monkeypatch):
    client = make_client()
    client_order_id = "WILLIAMS_AUDIT_RECONCILE_002"
    submit = __import__("unittest.mock", fromlist=["Mock"]).Mock(
        side_effect=BinanceAPIError("simulated timeout", unknown_execution=True)
    )
    lookup = __import__("unittest.mock", fromlist=["Mock"]).Mock(
        side_effect=BinanceAPIError("simulated order lookup unavailable")
    )
    monkeypatch.setattr(client, "order", submit)
    monkeypatch.setattr(client, "get_order", lookup)

    with pytest.raises(BinanceAPIError, match="Reconciliation is required before retry") as exc:
        client.order_safe(
            "BTCUSDT", "BUY", "LIMIT",
            quantity="1",
            new_client_order_id=client_order_id,
        )

    assert exc.value.unknown_execution is True
    submit.assert_called_once()
    lookup.assert_called_once_with("BTCUSDT", orig_client_order_id=client_order_id)


def test_order_safe_reports_known_terminal_cancel_without_retry(monkeypatch):
    client = make_client()
    client_order_id = "WILLIAMS_AUDIT_RECONCILE_003"
    submit = __import__("unittest.mock", fromlist=["Mock"]).Mock(
        side_effect=BinanceAPIError("simulated timeout", unknown_execution=True)
    )
    existing = {
        "symbol": "BTCUSDT",
        "clientOrderId": client_order_id,
        "orderId": 98767,
        "status": "CANCELED",
        "executedQty": "0.25",
    }
    lookup = __import__("unittest.mock", fromlist=["Mock"]).Mock(return_value=existing)
    monkeypatch.setattr(client, "order", submit)
    monkeypatch.setattr(client, "get_order", lookup)

    with pytest.raises(BinanceAPIError, match="terminal after ambiguous submission") as exc:
        client.order_safe(
            "BTCUSDT", "BUY", "LIMIT",
            quantity="1",
            new_client_order_id=client_order_id,
        )

    assert exc.value.payload is existing
    assert exc.value.unknown_execution is False
    submit.assert_called_once()
    lookup.assert_called_once_with("BTCUSDT", orig_client_order_id=client_order_id)


def test_minus_2014_is_reported_without_retry_or_assumed_root_cause(monkeypatch):
    client = make_client()
    calls = []

    class FakeResponse:
        status_code = 400
        headers = {}
        content = b'{"code":-2014,"msg":"API-key format invalid."}'

        @property
        def text(self):
            return self.content.decode()

        def json(self):
            return {"code": -2014, "msg": "API-key format invalid."}

    def fake_request(*args, **kwargs):
        calls.append((args, kwargs))
        return FakeResponse()

    monkeypatch.setattr(client.session, "request", fake_request)

    with pytest.raises(BinanceAPIError) as exc:
        client._request("GET", "/api/v3/account", {}, signed=True)

    assert exc.value.status_code == 400
    assert exc.value.payload["code"] == -2014
    assert exc.value.unknown_execution is False
    assert len(calls) == 1
