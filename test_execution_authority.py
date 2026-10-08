import time

import pytest

from binance_client import BinanceAPIError, BinanceSpotClient
from execution_barrier import ExecutionBarrier, OrderIntent
from market_context import ContextCache, TFMarketContext


class IntentDB:
    def save_execution_intent(self, *args, **kwargs):
        return None

    def save_execution_event(self, *args, **kwargs):
        return None

    def log_event(self, *args, **kwargs):
        return None


def make_context():
    return TFMarketContext(
        symbol="BTCUSDT",
        interval="5m",
        version=0,
        candle_open_time_ms=1,
        candle_close_time_ms=int(time.time() * 1000) - 1_000,
        price=100.0,
        allow_long=True,
        allow_short=False,
    )


def test_binance_client_rejects_direct_buy_calls_outside_barrier():
    client = BinanceSpotClient("test-key", "test-secret", testnet=True)
    with pytest.raises(BinanceAPIError, match="ExecutionBarrier"):
        client.order_safe(
            "BTCUSDT", "BUY", "STOP_LOSS",
            quantity="1", stop_price="101",
            new_client_order_id="WTEST_DIRECT_BUY",
        )
    with pytest.raises(BinanceAPIError, match="ExecutionBarrier"):
        client.order(
            "BTCUSDT", "BUY", "MARKET",
            quantity="1", new_client_order_id="WTEST_RAW_BUY",
        )


def test_barrier_scoped_buy_is_allowed_and_uses_same_client_order_id():
    cache = ContextCache()
    cache.publish(make_context())
    db = IntentDB()
    barrier = ExecutionBarrier(cache, db)
    client = BinanceSpotClient("test-key", "test-secret", testnet=True)
    requests = []
    client._request = lambda method, path, params=None, **kwargs: (
        requests.append((method, path, dict(params or {})))
        or {"status": "NEW", "executedQty": "0", "orderId": 17}
    )
    version = cache.snapshot().context("BTCUSDT", "5m").version
    intent = OrderIntent.new(
        "BTCUSDT",
        "BUY",
        "STOP_LOSS",
        {"5m": version},
        quantity="1",
        client_order_id="WTEST_BARRIER_BUY",
        purpose="CAMPAIGN_ENTRY",
        campaign_id="campaign-1",
        signal_id="signal-1",
        signal_expires_at_ms=int(time.time() * 1000) + 60_000,
        permission_interval="5m",
        invalidation_level=97.0,
        trigger_price=101.0,
    )

    result = barrier.execute(
        intent,
        lambda: client.order_safe(
            "BTCUSDT", "BUY", "STOP_LOSS",
            quantity="1", stop_price="101",
            new_client_order_id=intent.client_order_id,
        ),
    )
    assert result.accepted
    assert len(requests) == 1
    assert requests[0][1] == "/api/v3/order"
    assert requests[0][2]["newClientOrderId"] == intent.client_order_id
