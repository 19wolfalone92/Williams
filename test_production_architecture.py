import json
import threading
import time

from execution_barrier import ExecutionBarrier, OrderIntent
from market_context import ContextCache, TFMarketContext, WaveHypothesis
from wise_men import WiseMenPhase, WiseMenStateMachine


class IntentDB:
    def __init__(self):
        self.intents = {}
        self.state = {}

    def save_execution_intent(self, intent, status, reason=""):
        self.intents[intent.intent_id] = (status, reason, intent.client_order_id)

    def save_execution_event(self, *args, **kwargs):
        return None

    def log_event(self, *args, **kwargs):
        return None

    def state_get(self, key, default=None):
        return self.state.get(key, default)


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
    barrier = ExecutionBarrier(cache, IntentDB())
    snap = cache.snapshot()
    cache.publish(context())
    intent = OrderIntent.new(
        "BTCUSDT", "BUY", "MARKET",
        required_context_versions={"1h": snap.context("BTCUSDT", "1h").version},
        client_order_id="WTEST_123456789",
        quantity="1",
        client_order_id="WTEST_123456789",
        quantity="1",
        signal_id="test-signal",
        signal_expires_at_ms=int(time.time() * 1000) + 60_000,
        permission_interval="1h",
    )
    calls = []
    result = barrier.execute(intent, lambda: calls.append("sent"))
    assert not result.accepted
    assert not calls
    assert "stale context" in result.reason


def test_execution_barrier_serializes_publish_and_submit():
    cache = ContextCache()
    cache.publish(context())
    barrier = ExecutionBarrier(cache, IntentDB())
    version = cache.snapshot().context("BTCUSDT", "1h").version
    started = threading.Event()
    published = threading.Event()

    def submit():
        started.set()
        time.sleep(0.05)
        assert cache.snapshot().context("BTCUSDT", "1h").version == version
        return {"status": "FILLED"}

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
        client_order_id="WTEST_123456789",
        quantity="1",
        signal_id="test-signal",
        signal_expires_at_ms=int(time.time() * 1000) + 60_000,
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



def test_execution_barrier_rejects_signal_expired_after_intent_creation():
    cache = ContextCache()
    cache.publish(context())
    barrier = ExecutionBarrier(cache, IntentDB())
    version = cache.snapshot().context("BTCUSDT", "1h").version
    intent = OrderIntent.new(
        "BTCUSDT", "BUY", "STOP_LOSS",
        {"1h": version},
        client_order_id="WTEST_123456789",
        quantity="1",
        signal_id="test-signal",
        signal_expires_at_ms=int(time.time() * 1000) - 1,
        permission_interval="1h",
    )
    calls = []
    result = barrier.execute(intent, lambda: calls.append("submitted") or {"status": "NEW"})

    assert not result.accepted
    assert "signal expired" in result.reason
    assert calls == []


def test_execution_barrier_rejects_empty_context_dependencies_for_campaign_entry():
    cache = ContextCache()
    cache.publish(context())
    barrier = ExecutionBarrier(cache, IntentDB())
    intent = OrderIntent.new(
        "BTCUSDT", "BUY", "STOP_LOSS",
        {},
        purpose="CAMPAIGN_ENTRY",
        campaign_id="campaign-1",
        client_order_id="WTEST_123456789",
        quantity="1",
        client_order_id="WTEST_123456789",
        quantity="1",
        signal_id="signal-1",
        signal_expires_at_ms=int(time.time() * 1000) + 60_000,
        permission_interval="1h",
    )
    calls = []
    result = barrier.execute(intent, lambda: calls.append("submitted") or {"status": "NEW"})

    assert not result.accepted
    assert result.reason == "missing required_context_versions"
    assert calls == []


def test_execution_barrier_checks_direction_permission_for_campaign_entry():
    cache = ContextCache()
    cache.publish(context(allow_long=False, allow_short=False))
    barrier = ExecutionBarrier(cache, IntentDB())
    version = cache.snapshot().context("BTCUSDT", "1h").version
    intent = OrderIntent.new(
        "BTCUSDT", "BUY", "STOP_LOSS",
        {"1h": version},
        purpose="CAMPAIGN_ENTRY",
        campaign_id="campaign-1",
        client_order_id="WTEST_123456789",
        quantity="1",
        signal_id="signal-1",
        signal_expires_at_ms=int(time.time() * 1000) + 60_000,
        permission_interval="1h",
    )
    calls = []
    result = barrier.execute(intent, lambda: calls.append("submitted") or {"status": "NEW"})

    assert not result.accepted
    assert "does not allow LONG" in result.reason
    assert calls == []



def test_execution_barrier_rechecks_expiry_after_slow_pre_submit_validation():
    cache = ContextCache()
    cache.publish(context())
    barrier = ExecutionBarrier(cache, IntentDB())
    version = cache.snapshot().context("BTCUSDT", "1h").version
    intent = OrderIntent.new(
        "BTCUSDT", "BUY", "STOP_LOSS",
        {"1h": version},
        client_order_id="WTEST_123456789",
        quantity="1",
        signal_id="test-signal",
        signal_expires_at_ms=int(time.time() * 1000) + 250,
        permission_interval="1h",
    )
    calls = []

    def slow_pre_submit(_snapshot):
        time.sleep(0.35)

    result = barrier.execute(
        intent,
        lambda: calls.append("submitted") or {"status": "NEW"},
        pre_submit_checks=slow_pre_submit,
    )

    assert not result.accepted
    assert "signal expired" in result.reason
    assert calls == []


def test_execution_barrier_rejects_buy_with_unrecognized_purpose():
    cache = ContextCache()
    cache.publish(context())
    barrier = ExecutionBarrier(cache, IntentDB())
    intent = OrderIntent.new(
        "BTCUSDT", "BUY", "MARKET",
        {"1h": 1},
        purpose="LEGACY_BYPASS",
        client_order_id="WTEST_123456789",
        quantity="1",
        signal_id="signal-legacy",
        signal_expires_at_ms=int(time.time() * 1000) + 60_000,
        permission_interval="1h",
    )
    calls = []
    result = barrier.execute(intent, lambda: calls.append("submitted") or {"status": "FILLED"})

    assert not result.accepted
    assert "unsupported entry purpose" in result.reason
    assert calls == []


def test_execution_barrier_keeps_protective_sell_available_during_reconciliation():
    class ReconcileDB(IntentDB):
        def __init__(self):
            super().__init__()
        def state_get(self, key, default=None):
            return "RECONCILE_REQUIRED" if key == "position_state" else default

    cache = ContextCache()
    barrier = ExecutionBarrier(cache, ReconcileDB())
    intent = OrderIntent.new(
        "BTCUSDT", "SELL", "STOP_LOSS",
        {},
        purpose="CAMPAIGN_PROTECTION",
        campaign_id="campaign-open",
    )
    calls = []
    result = barrier.execute(
        intent,
        lambda: calls.append("protect") or {"status": "NEW"},
    )

    assert result.accepted
    assert calls == ["protect"]


def test_execution_barrier_blocks_new_entry_during_reconciliation():
    class ReconcileDB(IntentDB):
        def __init__(self):
            super().__init__()
        def state_get(self, key, default=None):
            return "RECONCILE_REQUIRED" if key == "position_state" else default

    cache = ContextCache()
    cache.publish(context())
    barrier = ExecutionBarrier(cache, ReconcileDB())
    version = cache.snapshot().context("BTCUSDT", "1h").version
    intent = OrderIntent.new(
        "BTCUSDT", "BUY", "STOP_LOSS",
        {"1h": version},
        client_order_id="WTEST_123456789",
        quantity="1",
        signal_id="signal-1",
        signal_expires_at_ms=int(time.time() * 1000) + 60_000,
        permission_interval="1h",
    )
    calls = []
    result = barrier.execute(
        intent,
        lambda: calls.append("entry") or {"status": "NEW"},
    )

    assert not result.accepted
    assert result.reason == "RECONCILE_REQUIRED"
    assert calls == []



def test_execution_barrier_requires_permission_interval_version_dependency():
    cache = ContextCache()
    cache.publish(context(interval="1h"))
    cache.publish(context(interval="4h"))
    barrier = ExecutionBarrier(cache, IntentDB())
    version = cache.snapshot().context("BTCUSDT", "4h").version
    intent = OrderIntent.new(
        "BTCUSDT", "BUY", "STOP_LOSS",
        {"4h": version},
        client_order_id="WTEST_123456789",
        quantity="1",
        signal_id="signal-1",
        signal_expires_at_ms=int(time.time() * 1000) + 60_000,
        permission_interval="1h",
    )
    calls = []
    result = barrier.execute(
        intent,
        lambda: calls.append("entry") or {"status": "NEW"},
    )

    assert not result.accepted
    assert result.reason == "permission_interval missing from required_context_versions"
    assert calls == []