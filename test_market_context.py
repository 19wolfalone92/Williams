import time

from market_context import ContextCache, TFMarketContext


def make_context(interval):
    now_ms = int(time.time() * 1000)
    return TFMarketContext(
        symbol="BTCUSDT",
        interval=interval,
        version=0,
        candle_open_time_ms=now_ms - 60_000,
        candle_close_time_ms=now_ms - 30_000,
        price=100.0,
        allow_long=True,
        alligator_state="BULLISH",
        alligator_awake=True,
        ao_value=1.0,
        ac_value=0.0,
        williams_core_ready=True,
    )


def test_monthly_and_minute_contexts_do_not_collide():
    cache = ContextCache()
    cache.publish(make_context("1m"))
    cache.publish(make_context("1M"))
    snapshot = cache.snapshot()
    assert snapshot.context("BTCUSDT", "1m").interval == "1m"
    assert snapshot.context("BTCUSDT", "1M").interval == "1M"
    assert snapshot.context("BTCUSDT", "1m").version == 1
    assert snapshot.context("BTCUSDT", "1M").version == 1


def test_context_versions_preserve_monthly_interval_identity():
    cache = ContextCache()
    cache.publish(make_context("1m"))
    cache.publish(make_context("1M"))
    assert cache.snapshot().versions("BTCUSDT", ["1m", "1M"]) == {"1m": 1, "1M": 1}


def test_execution_barrier_keeps_monthly_permission_separate_from_minute():
    from execution_barrier import ExecutionBarrier, OrderIntent

    cache = ContextCache()
    cache.publish(make_context("1m"))
    monthly = make_context("1M")
    monthly = TFMarketContext(
        **{**monthly.__dict__, "allow_long": True}
    )
    cache.publish(monthly)
    snapshot = cache.snapshot()
    intent = OrderIntent.new(
        "BTCUSDT",
        "BUY",
        "STOP_MARKET",
        {"1M": 1},
        client_order_id="test-monthly-entry",
        purpose="ENTRY",
        permission_interval="1M",
    )
    barrier = ExecutionBarrier(cache, require_durable_intent=False)
    assert barrier._validate(intent, snapshot) == ""
