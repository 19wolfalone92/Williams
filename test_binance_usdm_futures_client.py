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


@pytest.mark.parametrize("payload", [{}, {"dualSidePosition": None}, {"dualSidePosition": "unknown"}])
def test_one_way_mode_requires_explicit_valid_exchange_confirmation(monkeypatch, payload):
    client = make_client()
    monkeypatch.setattr(client, "_request", lambda *args, **kwargs: payload)

    with pytest.raises(FuturesAPIError, match="one-way mode is not confirmed"):
        client.ensure_one_way_mode()


@pytest.mark.parametrize("payload", [{"dualSidePosition": False}, {"dualSidePosition": "false"}, {"dualSidePosition": "0"}])
def test_one_way_mode_accepts_explicit_false_confirmation(monkeypatch, payload):
    client = make_client()
    monkeypatch.setattr(client, "_request", lambda *args, **kwargs: payload)

    assert client.ensure_one_way_mode() == payload


def test_account_permissions_reads_explicit_can_trade_from_v2(monkeypatch):
    client = make_client()
    calls = []

    def fake_request(method, path, params=None, signed=False):
        calls.append((method, path, signed))
        return {"canTrade": True, "multiAssetsMargin": False}

    monkeypatch.setattr(client, "_request", fake_request)

    assert client.account_permissions() == {"canTrade": True, "multiAssetsMargin": False}
    assert calls == [("GET", "/fapi/v2/account", True)]


@pytest.mark.parametrize("payload", [
    {"multiAssetsMargin": False},
    {"canTrade": None, "multiAssetsMargin": False},
    {"canTrade": "unknown", "multiAssetsMargin": False},
])
def test_account_permissions_rejects_missing_or_ambiguous_can_trade(monkeypatch, payload):
    client = make_client()
    monkeypatch.setattr(client, "_request", lambda *args, **kwargs: payload)

    with pytest.raises(FuturesAPIError, match="canTrade"):
        client.account_permissions()


def test_account_permissions_returns_false_when_exchange_disables_trading(monkeypatch):
    client = make_client()
    monkeypatch.setattr(
        client, "_request", lambda *args, **kwargs: {"canTrade": False, "multiAssetsMargin": False}
    )

    assert client.account_permissions() == {"canTrade": False, "multiAssetsMargin": False}


def test_account_permissions_requires_explicit_single_asset_mode(monkeypatch):
    client = make_client()
    monkeypatch.setattr(
        client,
        "_request",
        lambda *args, **kwargs: {"canTrade": True},
    )

    with pytest.raises(FuturesAPIError, match="multiAssetsMargin"):
        client.account_permissions()


def test_account_permissions_reports_multi_asset_mode_when_enabled(monkeypatch):
    client = make_client()
    monkeypatch.setattr(
        client,
        "_request",
        lambda *args, **kwargs: {"canTrade": True, "multiAssetsMargin": True},
    )

    assert client.account_permissions() == {"canTrade": True, "multiAssetsMargin": True}


def test_symbol_configuration_reads_margin_and_leverage_from_symbol_config(monkeypatch):
    client = make_client()
    monkeypatch.setattr(
        client,
        "_request",
        lambda *args, **kwargs: [{
            "symbol": "BTCUSDT",
            "marginType": "ISOLATED",
            "leverage": 1,
            "maxNotionalValue": "1000000",
        }],
    )

    result = client.symbol_configuration("BTCUSDT")

    assert result["symbol"] == "BTCUSDT"
    assert result["marginType"] == "ISOLATED"
    assert result["leverage"] == 1


def test_symbol_configuration_rejects_missing_symbol_or_leverage(monkeypatch):
    client = make_client()
    monkeypatch.setattr(
        client,
        "_request",
        lambda *args, **kwargs: [{"symbol": "BTCUSDT", "marginType": "ISOLATED"}],
    )

    with pytest.raises(FuturesAPIError, match="leverage"):
        client.symbol_configuration("BTCUSDT")


def test_symbol_configuration_rejects_fractional_leverage(monkeypatch):
    client = make_client()
    monkeypatch.setattr(
        client,
        "_request",
        lambda *args, **kwargs: [{
            "symbol": "BTCUSDT", "marginType": "ISOLATED", "leverage": 1.5,
        }],
    )

    with pytest.raises(FuturesAPIError, match="invalid leverage"):
        client.symbol_configuration("BTCUSDT")


def test_prepare_symbol_verifies_applied_isolated_one_x_policy(monkeypatch):
    client = make_client()
    responses = {
        "/fapi/v1/positionSide/dual": {"dualSidePosition": False},
        "/fapi/v1/marginType": {"symbol": "BTCUSDT", "marginType": "ISOLATED"},
        "/fapi/v1/leverage": {"symbol": "BTCUSDT", "leverage": 1},
        "/fapi/v1/symbolConfig": [{
            "symbol": "BTCUSDT", "marginType": "ISOLATED", "leverage": 1,
        }],
    }
    monkeypatch.setattr(
        client,
        "_request",
        lambda method, path, params=None, signed=False: responses[path],
    )

    result = client.prepare_symbol("BTCUSDT")

    assert result["configuration"]["marginType"] == "ISOLATED"
    assert result["configuration"]["leverage"] == 1


def test_prepare_symbol_fails_if_exchange_does_not_apply_one_x(monkeypatch):
    client = make_client()
    responses = {
        "/fapi/v1/positionSide/dual": {"dualSidePosition": False},
        "/fapi/v1/marginType": {"symbol": "BTCUSDT", "marginType": "ISOLATED"},
        "/fapi/v1/leverage": {"symbol": "BTCUSDT", "leverage": 1},
        "/fapi/v1/symbolConfig": [{
            "symbol": "BTCUSDT", "marginType": "ISOLATED", "leverage": 2,
        }],
    }
    monkeypatch.setattr(
        client,
        "_request",
        lambda method, path, params=None, signed=False: responses[path],
    )

    with pytest.raises(FuturesAPIError, match="did not confirm 1x"):
        client.prepare_symbol("BTCUSDT")


@pytest.mark.parametrize("value", [2, 1.5, "1.5", float("nan"), float("inf"), 0, -1])
def test_client_rejects_non_exact_one_x_leverage(value):
    with pytest.raises(ValueError, match="exactly 1x leverage"):
        make_client(max_leverage=value)


@pytest.mark.parametrize("value", [999, 60001, 5000.5, "5000.5", float("nan"), float("inf")])
def test_client_rejects_invalid_recv_window(value):
    with pytest.raises(ValueError, match="recv_window must be an integer"):
        make_client(recv_window=value)


@pytest.mark.parametrize("value", [0, -1, float("nan"), float("inf"), "invalid"])
def test_client_rejects_non_positive_or_non_finite_timeout(value):
    with pytest.raises(ValueError, match="timeout must be finite and positive"):
        make_client(timeout=value)


def test_client_accepts_valid_safety_settings():
    client = make_client(max_leverage="1", recv_window="5000", timeout=1.5)
    assert client.max_leverage == 1
    assert client.recv_window == 5000
    assert client.timeout == 1.5


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


@pytest.mark.parametrize(
    ("status_code", "payload"),
    [
        (408, {"code": 408, "msg": "request timeout"}),
        (425, {"code": 425, "msg": "too early"}),
        (400, {"code": -1006, "msg": "execution status unknown"}),
        (400, {"code": -1007, "msg": "timeout; execution status unknown"}),
    ],
)
def test_mutation_timeout_or_binance_unknown_execution_code_is_ambiguous(
    status_code, payload
):
    from types import SimpleNamespace

    client = make_client()

    class Response:
        def __init__(self, status, body):
            self.status_code = status
            self.text = str(body)
            self.body = body

        def json(self):
            return self.body

    client.session = SimpleNamespace(
        request=lambda *args, **kwargs: Response(status_code, payload)
    )

    with pytest.raises(FuturesAPIError) as exc_info:
        client._request("POST", "/fapi/v1/order", {}, signed=True)

    assert exc_info.value.unknown_execution is True
    assert exc_info.value.status_code == status_code


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


def test_user_trades_rejects_malformed_rows(monkeypatch):
    client = make_client()
    monkeypatch.setattr(
        client,
        "_request",
        lambda *args, **kwargs: [{"orderId": 1, "qty": "0.1"}, None],
    )

    with pytest.raises(FuturesAPIError, match="userTrades contains a malformed row"):
        client.user_trades("BTCUSDT", order_id=1)


@pytest.mark.parametrize("method_name", ["open_orders", "open_algo_orders"])
def test_open_order_listing_malformed_payload_fails_closed(monkeypatch, method_name):
    client = make_client()
    monkeypatch.setattr(client, "_request", lambda *args, **kwargs: {})

    with pytest.raises(FuturesAPIError, match="malformed payload"):
        getattr(client, method_name)("BTCUSDT")


@pytest.mark.parametrize("method_name", ["open_orders", "open_algo_orders"])
def test_open_order_listing_rejects_malformed_rows(monkeypatch, method_name):
    client = make_client()
    monkeypatch.setattr(client, "_request", lambda *args, **kwargs: [{"orderId": 1}, None])

    with pytest.raises(FuturesAPIError, match="malformed row"):
        getattr(client, method_name)("BTCUSDT")


def test_mainnet_flag_is_not_enabled_by_allow_live_argument_alone(monkeypatch):
    monkeypatch.setenv("ALLOW_LIVE", "false")
    with pytest.raises(ValueError, match="Mainnet futures is disabled"):
        make_client(testnet=False, allow_live=True)
