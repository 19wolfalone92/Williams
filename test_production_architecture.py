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


class _DurableBarrierDB:
    def save_execution_intent(self, intent, status, reason=""):
        return None

    def save_execution_event(self, intent_id, event, payload=None):
        return None

    def state_get(self, key, default=None):
        return default

    def claim_signal_once(self, signal_id, canonical_payload):
        return "CLAIMED"


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
    barrier = ExecutionBarrier(cache, db=_DurableBarrierDB())
    snap = cache.snapshot()
    cache.publish(context())
    now_ms = int(time.time() * 1000)
    intent = OrderIntent.new(
        "BTCUSDT", "BUY", "MARKET",
        required_context_versions={"1h": snap.context("BTCUSDT", "1h").version},
        purpose="ENTRY",
        permission_interval="1h",
        signal_timeframe="1h",
        signal_bar_time_ms=now_ms - 7_200_000,
        confirmation_time_ms=now_ms - 3_600_000,
        expires_at_ms=now_ms + 60_000,
        signal_id="test-signal-stale-context",
        campaign_id="test-campaign-stale-context",
    )
    calls = []
    result = barrier.execute(intent, lambda: calls.append("sent"), pre_submit_checks=lambda _snapshot: None)
    assert not result.accepted
    assert not calls
    assert "stale context" in result.reason


def test_execution_barrier_serializes_publish_and_submit():
    cache = ContextCache()
    cache.publish(context())
    class DurableTestDB:
        def __init__(self):
            self.claims = {}
            self.intents = []
            self.events = []
        def save_execution_intent(self, intent, status, reason=""):
            self.intents.append((intent.intent_id, status, reason))
        def save_execution_event(self, intent_id, event, payload=None):
            self.events.append((intent_id, event))
        def claim_signal_once(self, signal_id, canonical_payload):
            if signal_id not in self.claims:
                self.claims[signal_id] = dict(canonical_payload)
                return "CLAIMED"
            return "DUPLICATE" if self.claims[signal_id] == dict(canonical_payload) else "CONFLICT"
        def state_get(self, key, default=None):
            return default

    barrier = ExecutionBarrier(cache, db=DurableTestDB())
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
    now_ms = int(time.time() * 1000)
    intent = OrderIntent.new(
        "BTCUSDT",
        "BUY",
        "MARKET",
        {"1h": version},
        purpose="ENTRY",
        permission_interval="1h",
        signal_timeframe="1h",
        signal_bar_time_ms=now_ms - 7_200_000,
        confirmation_time_ms=now_ms - 3_600_000,
        expires_at_ms=now_ms + 60_000,
        signal_id="test-signal-serialization",
        campaign_id="test-campaign-serialization",
    )
    result = barrier.execute(intent, submit, pre_submit_checks=lambda _snapshot: None)
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


def test_execution_barrier_required_intent_persistence_failure_never_calls_submit():
    class FailingDB:
        def save_execution_intent(self, intent, status, reason=""):
            raise OSError("simulated sqlite write/commit failure")
        def save_execution_event(self, intent_id, event, payload=None):
            raise AssertionError("event write must not be reached after intent persistence failure")
        def state_get(self, key, default=None):
            return default

    cache = ContextCache()
    cache.publish(context())
    barrier = ExecutionBarrier(cache, FailingDB())
    now_ms = int(time.time() * 1000)
    intent = OrderIntent.new(
        "BTCUSDT", "BUY", "MARKET", {"1h": 1},
        purpose="CAMPAIGN_ENTRY",
        permission_interval="1h",
        signal_timeframe="1h",
        signal_bar_time_ms=(now_ms // 3_600_000 - 2) * 3_600_000,
        confirmation_time_ms=(now_ms // 3_600_000 - 1) * 3_600_000,
        expires_at_ms=now_ms + 60_000,
        signal_id="persist-failure-signal",
        campaign_id="persist-failure-campaign",
    )
    calls = []
    try:
        barrier.execute(intent, lambda: calls.append("network"), pre_submit_checks=lambda _s: None)
    except RuntimeError as exc:
        assert "persistence failed" in str(exc)
    else:
        raise AssertionError("required persistence failure must abort execution")
    assert calls == []


def test_execution_barrier_required_admission_event_failure_never_calls_submit():
    class EventFailDB:
        def __init__(self):
            self.intent_statuses = []
        def save_execution_intent(self, intent, status, reason=""):
            self.intent_statuses.append(status)
        def save_execution_event(self, intent_id, event, payload=None):
            raise OSError("simulated sqlite event commit failure")
        def state_get(self, key, default=None):
            return default

    cache = ContextCache()
    cache.publish(context())
    db = EventFailDB()
    barrier = ExecutionBarrier(cache, db)
    now_ms = int(time.time() * 1000)
    intent = OrderIntent.new(
        "BTCUSDT", "BUY", "MARKET", {"1h": 1},
        purpose="CAMPAIGN_ENTRY",
        permission_interval="1h",
        signal_timeframe="1h",
        signal_bar_time_ms=(now_ms // 3_600_000 - 2) * 3_600_000,
        confirmation_time_ms=(now_ms // 3_600_000 - 1) * 3_600_000,
        expires_at_ms=now_ms + 60_000,
        signal_id="event-failure-signal",
        campaign_id="event-failure-campaign",
    )
    calls = []
    try:
        barrier.execute(intent, lambda: calls.append("network"), pre_submit_checks=lambda _s: None)
    except RuntimeError as exc:
        assert "execution-event persistence failed" in str(exc)
    else:
        raise AssertionError("required admission event failure must abort execution")
    assert db.intent_statuses == ["PENDING"]
    assert calls == []


def test_execution_barrier_durably_blocks_replayed_signal_before_second_mutation():
    class DurableDB:
        def __init__(self):
            self.claims = {}
            self.intents = []
            self.events = []
        def save_execution_intent(self, intent, status, reason=""):
            self.intents.append((intent.intent_id, status))
        def save_execution_event(self, intent_id, event, payload=None):
            self.events.append((intent_id, event))
        def try_claim_state(self, key, value):
            if key in self.claims:
                return False
            self.claims[key] = value
            return True
        def state_get(self, key, default=None):
            return default

    cache = ContextCache()
    cache.publish(context())
    db = DurableDB()
    first_barrier = ExecutionBarrier(cache, db)
    now_ms = int(time.time() * 1000)
    common = dict(
        purpose="CAMPAIGN_ENTRY",
        permission_interval="1h",
        signal_timeframe="1h",
        signal_bar_time_ms=(now_ms // 3_600_000 - 2) * 3_600_000,
        confirmation_time_ms=(now_ms // 3_600_000 - 1) * 3_600_000,
        expires_at_ms=now_ms + 60_000,
        signal_id="stable-replay-signal-v1",
        campaign_id="stable-replay-campaign",
    )
    first = OrderIntent.new("BTCUSDT", "BUY", "MARKET", {"1h": 1}, **common)
    calls = []
    result = first_barrier.execute(
        first, lambda: (calls.append("first"), {"status": "NEW"})[1],
        pre_submit_checks=lambda _s: None,
    )
    assert result.accepted
    assert calls == ["first"]

    # Simulate a new barrier/runtime instance after restart while retaining the
    # durable store. A new random intent id must not authorize the same signal.
    second_barrier = ExecutionBarrier(cache, db)
    second = OrderIntent.new("BTCUSDT", "BUY", "MARKET", {"1h": 1}, **common)
    try:
        second_barrier.execute(
            second, lambda: (calls.append("second"), {"status": "NEW"})[1],
            pre_submit_checks=lambda _s: None,
        )
    except RuntimeError as exc:
        assert "duplicate signal blocked" in str(exc)
    else:
        raise AssertionError("replayed signal must be rejected by durable claim")
    assert calls == ["first"]
    assert list(k for k in db.claims if k == "consumed_signal:stable-replay-signal-v1") == [
        "consumed_signal:stable-replay-signal-v1"
    ]
