from market_context import ContextCache, TFMarketContext


def make_context(interval):
    return TFMarketContext(
        symbol="BTCUSDT",
        interval=interval,
        version=0,
        candle_open_time_ms=1,
        candle_close_time_ms=2,
        price=100.0,
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
