import json
import threading
import time

from execution_barrier import ExecutionBarrier, OrderIntent
from market_context import ContextCache, TFMarketContext, WaveHypothesis
from wise_men import WiseMenPhase, WiseMenStateMachine


class MemoryIntentDB:
    def __init__(self):
        self.intents = {}

    def save_execution_intent(self, intent, status, reason=""):
        self.intents[intent.intent_id] = (status, reason)


def context(symbol="BTCUSDT", interval="1h", allow_long=True, allow_short=False):
    now_ms = int(time.time() * 1000)
    alligator_state = "BULLISH" if allow_long else "BEARISH" if allow_short else "SLEEP"
    active = bool(allow_long or allow_short)
    return TFMarketContext(
        symbol=symbol,
        interval=interval,
        version=0,
        candle_open_time_ms=now_ms - 30_000,
        candle_close_time_ms=now_ms - {
            "1h": 20_000, "4h": 40_000, "1d": 60_000, "1M": 60_000,
        }.get(interval, 20_000),
        price=100.0,
        atr=2.0,
        jaw=98.0,
        teeth=99.0,
        lips=99.5,
        alligator_state=alligator_state,
        alligator_awake=active,
        ao_value=1.0 if allow_long else -1.0 if allow_short else 0.0,
        ac_value=0.0,
        williams_core_ready=True,
        allow_long=allow_long,
        allow_short=allow_short,
        decision="LONG" if allow_long else "SHORT" if allow_short else "NO_TRADE",
        hypotheses=(
            WaveHypothesis("w3", "W3", "LONG", 0.85, 0.10, invalidation_level=94.0),
        ),
        data_bars=220,
    )


def publish_tc2_contexts(
    cache,
    *,
    h1_allow_long=True,
    h1_allow_short=False,
    d1_allow_long=False,
    d1_allow_short=False,
):
    cache.publish(context("BTCUSDT", "1h", h1_allow_long, h1_allow_short))
    # H4 is required as valid context but intentionally does not have to agree
    # directionally with the canonical H1 signal.
    cache.publish(context("BTCUSDT", "4h", False, False))
    cache.publish(context("BTCUSDT", "1d", d1_allow_long, d1_allow_short))


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
    barrier = ExecutionBarrier(cache, MemoryIntentDB())
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
        purpose="EXIT",
        permission_interval="1h",
        client_order_id="stable-serial-test",
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



def test_execution_barrier_fails_closed_when_pending_intent_cannot_be_persisted():
    class FailingDB:
        def save_execution_intent(self, intent, status, reason=""):
            raise OSError("simulated disk failure")

    cache = ContextCache()
    cache.publish(context())
    barrier = ExecutionBarrier(cache, FailingDB())
    version = cache.snapshot().context("BTCUSDT", "1h").version
    intent = OrderIntent.new(
        "BTCUSDT",
        "BUY",
        "MARKET",
        {"1h": version},
        purpose="EXIT",
        permission_interval="1h",
        client_order_id="stable-persistence-failure",
    )
    calls = []

    result = barrier.execute(
        intent,
        lambda: calls.append("sent") or {"status": "FILLED", "executedQty": "1"},
    )

    assert not result.accepted
    assert "persistence_failed" in result.reason
    assert calls == []


def test_execution_barrier_requires_durable_store_by_default():
    cache = ContextCache()
    cache.publish(context())
    barrier = ExecutionBarrier(cache)
    version = cache.snapshot().context("BTCUSDT", "1h").version
    intent = OrderIntent.new(
        "BTCUSDT",
        "BUY",
        "MARKET",
        {"1h": version},
        purpose="EXIT",
        permission_interval="1h",
        client_order_id="stable-no-store",
    )
    calls = []

    result = barrier.execute(
        intent,
        lambda: calls.append("sent") or {"status": "FILLED", "executedQty": "1"},
    )

    assert not result.accepted
    assert "persistence_failed" in result.reason
    assert calls == []


def test_execution_barrier_flags_failure_to_persist_submission_after_one_submit():
    class FailsOnSubmittedDB:
        def __init__(self):
            self.statuses = []

        def save_execution_intent(self, intent, status, reason=""):
            self.statuses.append(status)
            if status == "SUBMITTED":
                raise OSError("simulated failure after exchange response")

    cache = ContextCache()
    cache.publish(context())
    db = FailsOnSubmittedDB()
    barrier = ExecutionBarrier(cache, db)
    version = cache.snapshot().context("BTCUSDT", "1h").version
    intent = OrderIntent.new(
        "BTCUSDT",
        "BUY",
        "MARKET",
        {"1h": version},
        purpose="EXIT",
        permission_interval="1h",
        client_order_id="WILLIAMS_TEST_PERSISTENCE_001",
    )
    calls = []

    try:
        barrier.execute(
            intent,
            lambda: calls.append("sent") or {"status": "FILLED", "executedQty": "1"},
        )
    except RuntimeError as exc:
        assert "reconcile by stable client_order_id" in str(exc)
    else:
        raise AssertionError("post-submit persistence failure must be surfaced")

    assert calls == ["sent"]
    assert db.statuses == ["PENDING", "SUBMITTED"]



def test_campaign_entry_checks_directional_permission_for_long_and_short():
    cache = ContextCache()
    publish_tc2_contexts(
        cache,
        h1_allow_long=False,
        h1_allow_short=False,
    )
    snapshot = cache.snapshot()
    barrier = ExecutionBarrier(cache, MemoryIntentDB())

    versions = snapshot.versions("BTCUSDT", ["1h", "4h", "1d"])
    for side, expected in (("BUY", "LONG"), ("SELL", "SHORT")):
        intent = OrderIntent.new(
            "BTCUSDT",
            side,
            "STOP_MARKET",
            versions,
            purpose="CAMPAIGN_ENTRY",
            permission_interval="1h",
            client_order_id=f"stable-entry-{side.lower()}",
        )
        result = barrier.execute(intent, lambda: {"status": "NEW"})
        assert not result.accepted
        assert f"does not allow {expected}" in result.reason


def test_exit_remains_available_when_reconciliation_is_required():
    class ReconcileDB(MemoryIntentDB):
        def state_get(self, key, default=None):
            if key == "position_state" or key.startswith("campaign_state:"):
                return "RECONCILE_REQUIRED"
            return default

    cache = ContextCache()
    cache.publish(context(allow_long=False, allow_short=False))
    db = ReconcileDB()
    barrier = ExecutionBarrier(cache, db)
    intent = OrderIntent.new(
        "BTCUSDT",
        "SELL",
        "MARKET",
        {},
        purpose="CAMPAIGN_EXIT",
        campaign_id="campaign-1",
        client_order_id="EXIT-RECONCILE-TEST",
    )

    result = barrier.execute(
        intent,
        lambda: {"status": "FILLED", "executedQty": "1"},
    )

    assert result.accepted
    assert db.intents[intent.intent_id][0] == "SUBMITTED"


def test_symbol_specific_reconciliation_lock_blocks_new_campaign_exposure():
    class SymbolLockDB(MemoryIntentDB):
        def state_get(self, key, default=None):
            if key == "position_state:BTCUSDT":
                return "RECONCILE_REQUIRED"
            return default

    cache = ContextCache()
    publish_tc2_contexts(
        cache,
        h1_allow_long=True,
        h1_allow_short=False,
    )
    db = SymbolLockDB()
    barrier = ExecutionBarrier(cache, db)
    snapshot = cache.snapshot()
    intent = OrderIntent.new(
        "BTCUSDT",
        "BUY",
        "STOP_MARKET",
        snapshot.versions("BTCUSDT", ["1h", "4h", "1d"]),
        purpose="CAMPAIGN_ENTRY",
        permission_interval="1h",
        client_order_id="SYMBOL-LOCK-TEST-001",
    )
    submissions = []

    result = barrier.execute(intent, lambda: submissions.append("submitted") or {"status": "NEW"})

    assert not result.accepted
    assert result.reason == "RECONCILE_REQUIRED"
    assert submissions == []



def test_mutating_order_without_stable_client_id_is_blocked():
    cache = ContextCache()
    cache.publish(context(allow_long=True, allow_short=True))
    db = MemoryIntentDB()
    barrier = ExecutionBarrier(cache, db)
    snapshot = cache.snapshot()
    intent = OrderIntent.new(
        "BTCUSDT", "BUY", "STOP_MARKET",
        {"1h": snapshot.context("BTCUSDT", "1h").version},
        purpose="EXIT", permission_interval="1h",
    )
    submissions = []

    result = barrier.execute(intent, lambda: submissions.append("submitted") or {"status": "NEW"})

    assert not result.accepted
    assert result.reason == "missing stable client_order_id"
    assert submissions == []
