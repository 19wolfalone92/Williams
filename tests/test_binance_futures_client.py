import hashlib
import hmac
from urllib.parse import parse_qs, urlencode

import pytest

from binance_futures_client import BinanceFuturesAPIError, BinanceUsdMFuturesClient


class Response:
    def __init__(self, payload, status_code=200):
        self._payload = payload
        self.status_code = status_code
        self.text = str(payload)
        self.headers = {}

    def json(self):
        return self._payload


class Session:
    def __init__(self, responses):
        self.responses = list(responses)
        self.headers = {}
        self.calls = []

    def request(self, method, url, params=None, timeout=None):
        self.calls.append((method, url, dict(params or {}), timeout))
        return self.responses.pop(0)


def client(responses):
    session = Session(responses)
    return BinanceUsdMFuturesClient("key", "secret", demo=True, session=session), session


def test_demo_uses_futures_demo_base_and_futures_routes():
    c, session = client([Response({"serverTime": 123}), Response([["kline"]])])
    assert c.base_url == "https://demo-fapi.binance.com"
    assert c.server_time() == {"serverTime": 123}
    assert c.klines("BTCUSDT", "1h", limit=1) == [["kline"]]
    assert session.calls[0][1].endswith("/fapi/v1/time")
    assert session.calls[1][1].endswith("/fapi/v1/klines")


def test_signed_query_has_hmac_sha256_signature():
    c, session = client([Response({"totalWalletBalance": "100"})])
    c.account()
    _, url, params, _ = session.calls[0]
    assert url.endswith("/fapi/v2/account")
    signature = params.pop("signature")
    query = urlencode(params, doseq=True)
    expected = hmac.new(b"secret", query.encode(), hashlib.sha256).hexdigest()
    assert signature == expected
    assert params["recvWindow"] == 5000
    assert "timestamp" in params


def test_order_requires_stable_client_id_and_uses_futures_fields():
    c, session = client([Response({"orderId": 4, "status": "NEW"})])
    with pytest.raises(ValueError, match="stable"):
        c.order("BTCUSDT", "BUY", "MARKET", quantity="0.01")
    result = c.order(
        "BTCUSDT", "SELL", "MARKET", quantity="0.01",
        new_client_order_id="wb21-exit-001", position_side="BOTH", reduce_only=True,
    )
    assert result["status"] == "NEW"
    method, url, params, _ = session.calls[0]
    assert (method, url) == ("POST", "https://demo-fapi.binance.com/fapi/v1/order")
    assert params["newClientOrderId"] == "wb21-exit-001"
    assert params["reduceOnly"] == "true"
    assert params["positionSide"] == "BOTH"


def test_hedge_mode_rejects_reduce_only_parameter():
    c, _ = client([])
    with pytest.raises(ValueError, match="Hedge Mode"):
        c.order(
            "BTCUSDT", "SELL", "MARKET", quantity="0.01",
            new_client_order_id="wb21-exit-002", position_side="LONG", reduce_only=True,
        )


def test_ambiguous_post_is_reconciled_by_client_id_without_resubmission():
    c, session = client([
        Response({"msg": "gateway timeout"}, status_code=504),
        Response({"symbol": "BTCUSDT", "clientOrderId": "wb21-entry-001", "status": "FILLED"}),
    ])
    result = c.order_safe(
        "BTCUSDT", "BUY", "MARKET", quantity="0.01",
        new_client_order_id="wb21-entry-001",
    )
    assert result["status"] == "FILLED"
    assert len(session.calls) == 2
    assert session.calls[0][0] == "POST"
    assert session.calls[1][0] == "GET"
    assert session.calls[1][1].endswith("/fapi/v1/order")
    assert session.calls[1][2]["origClientOrderId"] == "wb21-entry-001"


def test_ambiguous_post_without_reconciliation_stays_unknown():
    c, session = client([
        Response({"msg": "gateway timeout"}, status_code=504),
        Response({"code": -1001, "msg": "exchange unavailable"}, status_code=503),
    ])
    with pytest.raises(BinanceFuturesAPIError) as caught:
        c.order_safe(
            "BTCUSDT", "BUY", "MARKET", quantity="0.01",
            new_client_order_id="wb21-entry-002",
        )
    assert caught.value.unknown_execution is True
    assert "reconciliation required" in str(caught.value)
    assert len(session.calls) == 2


def test_bad_leverage_is_rejected_before_exchange_call():
    c, session = client([])
    with pytest.raises(ValueError, match="leverage"):
        c.change_leverage("BTCUSDT", 0)
    assert session.calls == []


def test_non_2xx_market_read_is_not_mistaken_for_valid_payload():
    c, _ = client([Response({"code": -1121, "msg": "Invalid symbol"}, status_code=400)])
    with pytest.raises(BinanceFuturesAPIError):
        c.ticker_price("NOT_A_SYMBOL")


def test_spot_portfolio_trader_rejects_futures_client_before_initialization():
    from portfolio_trader import MultiPositionTrader

    class FuturesClientMarker:
        product = "USD_M_FUTURES"

    with pytest.raises(ValueError, match="Spot-only"):
        MultiPositionTrader(FuturesClientMarker())


def test_ambiguous_submission_reconciled_as_canceled_is_not_reported_as_success():
    c, session = client([
        Response({"msg": "gateway timeout"}, status_code=504),
        Response({"symbol": "BTCUSDT", "clientOrderId": "wb21-entry-003", "status": "CANCELED"}),
    ])
    with pytest.raises(BinanceFuturesAPIError, match="confirmed terminal"):
        c.order_safe(
            "BTCUSDT", "BUY", "MARKET", quantity="0.01",
            new_client_order_id="wb21-entry-003",
        )
    assert len(session.calls) == 2


def test_success_response_with_missing_order_status_is_blocked_as_unknown():
    c, _ = client([Response({"symbol": "BTCUSDT", "orderId": 99})])
    with pytest.raises(BinanceFuturesAPIError, match="unknown status"):
        c.order_safe(
            "BTCUSDT", "BUY", "MARKET", quantity="0.01",
            new_client_order_id="wb21-entry-004",
        )


@pytest.mark.parametrize("kwargs, message", [
    ({"quantity": "0.01", "stop_price": "60000", "close_position": True}, "quantity"),
    ({"stop_price": "60000", "close_position": True, "reduce_only": False}, "reduceOnly"),
    ({"stop_price": "60000", "close_position": False}, "must be true"),
])
def test_invalid_close_position_combinations_are_rejected(kwargs, message):
    c, session = client([])
    with pytest.raises(ValueError, match=message):
        c.order(
            "BTCUSDT", "SELL", "STOP_MARKET",
            new_client_order_id="wb21-stop-invalid",
            **kwargs,
        )
    assert session.calls == []
