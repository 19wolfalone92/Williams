import os

import pytest

from binance_usdm_futures_client import BinanceUsdmFuturesClient, FuturesAPIError


def make_client(**kwargs):
    return BinanceUsdmFuturesClient("test-key", "test-secret", **kwargs)


def test_demo_futures_endpoint_is_default_and_mainnet_requires_double_opt_in(monkeypatch):
    client = make_client()
    assert client.testnet is True
    assert client.base_url == "https://demo-fapi.binance.com"

    monkeypatch.delenv("ALLOW_LIVE", raising=False)
    with pytest.raises(ValueError, match="Mainnet futures is disabled"):
        make_client(testnet=False, allow_live=True)

    monkeypatch.setenv("ALLOW_LIVE", "true")
    live = make_client(testnet=False, allow_live=True)
    assert live.base_url == "https://fapi.binance.com"


def test_live_leverage_above_one_is_rejected():
    with pytest.raises(ValueError, match="capped at 1x"):
        BinanceUsdmFuturesClient("test-key", "test-secret", max_leverage=2)


def test_market_entry_side_is_directional_and_not_reduce_only(monkeypatch):
    client = make_client()
    monkeypatch.setattr(client, "ensure_one_way_mode", lambda: {"dualSidePosition": False})
    calls = []

    def request(method, path, params=None, *, signed=False):
        calls.append((method, path, dict(params or {}), signed))
        return {
            "symbol": params["symbol"],
            "side": params["side"],
            "status": "FILLED",
            "executedQty": params["quantity"],
            "orderId": 10,
        }

    monkeypatch.setattr(client, "_request", request)
    long = client.market_entry("BTCUSDT", "LONG", "0.01", "WILL-LONG-ENTRY-1")
    short = client.market_entry("BTCUSDT", "SHORT", "0.01", "WILL-SHORT-ENTRY-1")

    order_calls = [call for call in calls if call[1] == "/fapi/v1/order"]
    assert long["side"] == "BUY"
    assert short["side"] == "SELL"
    assert all(call[2].get("reduceOnly") is None for call in order_calls)
    assert [call[2]["newClientOrderId"] for call in order_calls] == [
        "WILL-LONG-ENTRY-1",
        "WILL-SHORT-ENTRY-1",
    ]


def test_market_exit_is_opposite_side_and_reduce_only(monkeypatch):
    client = make_client()
    monkeypatch.setattr(client, "ensure_one_way_mode", lambda: {"dualSidePosition": False})
    calls = []

    def request(method, path, params=None, *, signed=False):
        calls.append((method, path, dict(params or {})))
        return {"symbol": params["symbol"], "side": params["side"], "status": "FILLED", "orderId": 11}

    monkeypatch.setattr(client, "_request", request)
    long_exit = client.market_exit("BTCUSDT", "LONG", "0.01", "WILL-LONG-EXIT-1")
    short_exit = client.market_exit("BTCUSDT", "SHORT", "0.01", "WILL-SHORT-EXIT-1")

    assert long_exit["side"] == "SELL"
    assert short_exit["side"] == "BUY"
    assert [c[2]["reduceOnly"] for c in calls] == ["true", "true"]


def test_protective_stop_uses_close_position_and_correct_opposite_side(monkeypatch):
    client = make_client()
    monkeypatch.setattr(client, "ensure_one_way_mode", lambda: {"dualSidePosition": False})
    calls = []

    def request(method, path, params=None, *, signed=False):
        calls.append((method, path, dict(params or {})))
        return {"symbol": params["symbol"], "side": params["side"], "algoStatus": "NEW", "algoId": 12}

    monkeypatch.setattr(client, "_request", request)
    long_stop = client.protective_stop("BTCUSDT", "LONG", "95000", "WILL-LONG-STOP-1")
    short_stop = client.protective_stop("BTCUSDT", "SHORT", "105000", "WILL-SHORT-STOP-1")

    assert long_stop["side"] == "SELL"
    assert short_stop["side"] == "BUY"
    assert all(c[1] == "/fapi/v1/algoOrder" for c in calls)
    assert all(c[2]["algoType"] == "CONDITIONAL" for c in calls)
    assert all(c[2]["closePosition"] == "true" for c in calls)
    assert all("quantity" not in c[2] and "reduceOnly" not in c[2] for c in calls)


def test_ambiguous_order_post_is_reconciled_and_never_blindly_retried(monkeypatch):
    client = make_client()
    calls = []

    def request(method, path, params=None, *, signed=False):
        calls.append((method, path, dict(params or {})))
        if method == "POST":
            raise FuturesAPIError("socket timeout", unknown_execution=True)
        raise FuturesAPIError("order not found yet", unknown_execution=False, status_code=400, payload={"code": -2013})

    monkeypatch.setattr(client, "_request", request)
    with pytest.raises(FuturesAPIError, match="outcome UNKNOWN") as exc_info:
        client.new_order_safe(
            "BTCUSDT",
            "BUY",
            "MARKET",
            quantity="0.01",
            client_order_id="WILL-UNKNOWN-POST-1",
        )

    assert exc_info.value.unknown_execution is True
    assert sum(1 for c in calls if c[0] == "POST") == 1
    assert sum(1 for c in calls if c[0] == "GET") == 1


def test_quantity_is_rounded_down_to_market_lot_step(monkeypatch):
    client = make_client()
    monkeypatch.setattr(
        client,
        "symbol_filters",
        lambda symbol: {
            "LOT_SIZE": {"minQty": "0.001", "maxQty": "1000", "stepSize": "0.001"},
            "MARKET_LOT_SIZE": {"minQty": "0.002", "maxQty": "1000", "stepSize": "0.002"},
        },
    )

    assert client.normalize_quantity("BTCUSDT", "1.2399", market=True) == "1.238"
    with pytest.raises(ValueError, match="below minQty"):
        client.normalize_quantity("BTCUSDT", "0.001", market=True)


def test_conditional_stop_requires_valid_close_position_contract():
    client = make_client()
    with pytest.raises(ValueError, match="cannot be combined with quantity"):
        client.new_algo_order_safe(
            "BTCUSDT",
            "SELL",
            "STOP_MARKET",
            client_algo_id="WILL-INVALID-STOP-1",
            trigger_price="95000",
            quantity="0.01",
            close_position=True,
        )


def test_mainnet_flag_is_not_enabled_by_allow_live_argument_alone(monkeypatch):
    monkeypatch.setenv("ALLOW_LIVE", "false")
    with pytest.raises(ValueError, match="Mainnet futures is disabled"):
        make_client(testnet=False, allow_live=True)
