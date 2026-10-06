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
