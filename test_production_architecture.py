import json
import threading
import time

from execution_barrier import ExecutionBarrier, OrderIntent
from market_context import ContextCache, TFMarketContext, WaveHypothesis
from wise_men import WiseMenPhase, WiseMenStateMachine


def context(symbol="BTCUSDT", interval="1h", allow_long=True, allow_short=False):
    return TFMarketContext(
        symbol=symbol,
        interval=interval,
        version=0,
        candle_open_time_ms=1,
        candle_close_time_ms=2,
        price=100.0,
        atr=2.0,
        jaw=98.0,
        teeth=99.0,
        lips=99.5,
        allow_long=allow_long,
        allow_short=allow_short,
        decision="LONG" if allow_long else "NO_TRADE",
        hypotheses=(
            WaveHypothesis("w3", "W3", "LONG", 0.85, 0.10, invalidation_level=94.0),
        ),
        data_bars=220,
    )


def test_copy_on_write_versions_and_immutable_snapshot():
    cache = ContextCache()
    first = cache.publish(context())
    assert first.context("BTCUSDT", "1h").version == 1
    second = cache.publish(context())
    assert second.context("BTCUSDT", "1h").version == 2
    assert first.context("BTCUSDT", "1h").version == 1


def test_execution_barrier_rejects_stale_context():
    cache = ContextCache()
    cache.publish(context())
    barrier = ExecutionBarrier(cache)
    snap = cache.snapshot()
    cache.publish(context())
    intent = OrderIntent.new(
        "BTCUSDT", "BUY", "MARKET",
        required_context_versions={"1h": snap.context("BTCUSDT", "1h").version},
    )
    calls = []
    result = barrier.execute(intent, lambda: calls.append("sent"))
    assert not result.accepted
    assert not calls
    assert "stale context" in result.reason


def test_execution_barrier_serializes_publish_and_submit():
    cache = ContextCache()
    cache.publish(context())
    barrier = ExecutionBarrier(cache)
    version = cache.snapshot().context("BTCUSDT", "1h").version
    started = threading.Event()
    published = threading.Event()

    def submit():
        started.set()
        time.sleep(0.05)
        assert cache.snapshot().context("BTCUSDT", "1h").version == version
        return {"status": "FILLED", "orderId": 123456, "clientOrderId": "TEST"}

    def publish():
        started.wait(1)
        cache.publish(context())
        published.set()

    t = threading.Thread(target=publish)
    t.start()
    intent = OrderIntent.new(
        "BTCUSDT",
        "BUY",
        "MARKET",
        {"1h": version},
        permission_interval="1h",
    )
    result = barrier.execute(intent, submit)
    t.join()
    assert result.accepted
    assert published.is_set()
    assert cache.snapshot().context("BTCUSDT", "1h").version == version + 1


def test_wise_men_state_machine_is_durable():
    class DB:
        def __init__(self): self.data = {}
        def state_get(self, key, default=None): return self.data.get(key, default)
        def state_set(self, key, value): self.data[key] = value

    db = DB()
    wm = WiseMenStateMachine(db, "BTCUSDT", "LONG")
    wm.observe("c1", wm1_triggered=True, level=100)
    assert wm.state.phase == WiseMenPhase.WM1_TRIGGERED
    wm.observe("c2", wm2_triggered=True, level=102)
    assert wm.state.phase == WiseMenPhase.WM2_TRIGGERED
    wm.observe("c3", wm3_triggered=True, level=105)
    assert wm.state.phase == WiseMenPhase.TREND_ACTIVE

    restored = WiseMenStateMachine(db, "BTCUSDT", "LONG")
    assert restored.state.phase == WiseMenPhase.TREND_ACTIVE
    payload = json.loads(db.data[restored.key])
    assert payload["additions"] == 0
