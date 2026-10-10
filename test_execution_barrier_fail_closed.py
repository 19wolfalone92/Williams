"""Behavioral tests for the serialized execution admission barrier.

All exchange operations are fake/in-memory. No network order is sent.
"""
import time

import pytest

from binance_client import BinanceSpotClient
from execution_barrier import ExecutionBarrier, OrderIntent
from market_context import ContextCache, TFMarketContext, WaveHypothesis
from preflight_gate import PreflightCheckService


class FakeDB:
    def __init__(self, *, fail_intent_status=None, fail_event=None, fail_claim=False):
        self.intents = {}
        self.events = []
        self.states = {}
        self.claims = set()
        self.fail_intent_status = fail_intent_status
        self.fail_event = fail_event
        self.fail_claim = fail_claim

    def save_execution_intent(self, intent, status, reason=""):
        if self.fail_intent_status == status:
            raise OSError("synthetic SQLite write failure")
        self.intents[intent.intent_id] = (status, reason, intent.client_order_id)
        return True

    def save_execution_event(self, intent_id, event, payload=None):
        if self.fail_event == event:
            raise OSError("synthetic SQLite event failure")
        self.events.append((intent_id, event, payload))
        return True

    def try_claim_state(self, key, value):
        if self.fail_claim or key in self.claims:
            return False
        self.claims.add(key)
        return True

    def state_get(self, key, default=None):
        return self.states.get(key, default)

    def state_set(self, key, value):
        self.states[key] = value

    def log_event(self, *args, **kwargs):
        return True


def _context():
    return TFMarketContext(
        symbol="BTCUSDT", interval="1h", version=0,
        candle_open_time_ms=1, candle_close_time_ms=2,
        price=100.0, atr=2.0, jaw=98.0, teeth=99.0, lips=99.5,
        allow_long=True, allow_short=False, decision="LONG",
        hypotheses=(WaveHypothesis("w3", "W3", "LONG", 0.85, 0.1, invalidation_level=94.0),),
        data_bars=220,
    )


def _ready_preflight():
    return {
        "ready": True,
        "environment": "TESTNET",
        "product": "SPOT",
        "checked_at_ms": int(time.time() * 1000),
        "checks": {name: "PASS" for name in PreflightCheckService.REQUIRED_CHECKS},
        "requires_reconciliation": False,
    }


def _intent(client_id="CID_STABLE_001"):
    return OrderIntent.new(
        "BTCUSDT", "BUY", "MARKET",
        {"1h": 1},
        permission_interval="1h",
        client_order_id=client_id,
    )


def _barrier(db, provider=_ready_preflight):
    cache = ContextCache()
    cache.publish(_context())
    return ExecutionBarrier(cache, db, preflight_provider=provider)


def _pre_submit(_snapshot):
    return None


def test_pending_intent_write_failure_never_calls_submit():
    db = FakeDB(fail_intent_status="PENDING")
    barrier = _barrier(db)
    calls = []
    result = barrier.execute(_intent(), lambda: calls.append("submit"), pre_submit_checks=_pre_submit)
    assert not result.accepted
    assert result.reason == "execution_intent_persistence_failed"
    assert calls == []


def test_admission_event_write_failure_never_calls_submit():
    db = FakeDB(fail_event="ADMISSION_STARTED")
    barrier = _barrier(db)
    calls = []
    result = barrier.execute(_intent(), lambda: calls.append("submit"), pre_submit_checks=_pre_submit)
    assert not result.accepted
    assert result.reason == "execution_event_persistence_failed"
    assert calls == []
    assert db.intents[result.intent_id][0] == "BLOCKED"


def test_missing_or_stale_preflight_never_calls_submit():
    db = FakeDB()
    calls = []
    no_provider = _barrier(db, provider=None)
    result = no_provider.execute(_intent("CID_NO_PREFLIGHT"), lambda: calls.append("submit"), pre_submit_checks=_pre_submit)
    assert not result.accepted
    assert result.reason == "mandatory_preflight_provider_missing"
    assert calls == []

    stale = dict(_ready_preflight(), checked_at_ms=int(time.time() * 1000) - 10_000)
    stale_barrier = _barrier(FakeDB(), provider=lambda: stale)
    result = stale_barrier.execute(_intent("CID_STALE_PREFLIGHT"), lambda: calls.append("submit"), pre_submit_checks=_pre_submit)
    assert not result.accepted
    assert result.reason == "preflight_result_stale_or_future"
    assert calls == []


def test_duplicate_client_order_id_is_submitted_at_most_once():
    db = FakeDB()
    barrier = _barrier(db)
    calls = []

    first = barrier.execute(
        _intent("CID_DUPLICATE"),
        lambda: (calls.append("first") or {"status": "FILLED", "executedQty": "1"}),
        pre_submit_checks=_pre_submit,
    )
    second = barrier.execute(
        _intent("CID_DUPLICATE"),
        lambda: (calls.append("second") or {"status": "FILLED", "executedQty": "1"}),
        pre_submit_checks=_pre_submit,
    )

    assert first.accepted
    assert not second.accepted
    assert second.reason == "duplicate_or_previously_claimed_client_order_id"
    assert calls == ["first"]
    assert db.intents[first.intent_id][2] == "CID_DUPLICATE"


def test_post_acceptance_event_write_failure_marks_reconciliation_and_never_retries():
    db = FakeDB(fail_event="BINANCE_SUBMITTED")
    barrier = _barrier(db)
    calls = []

    with pytest.raises(RuntimeError, match="reconciliation required"):
        barrier.execute(
            _intent("CID_POST_ACCEPT_FAILURE"),
            lambda: (calls.append("submitted") or {"status": "FILLED", "executedQty": "1"}),
            pre_submit_checks=_pre_submit,
        )

    assert calls == ["submitted"]
    assert db.states["position_state"] == "RECONCILE_REQUIRED"
    assert db.intents[next(iter(db.intents))][0] == "SUBMITTED"


def test_direct_binance_order_mutation_is_denied_outside_barrier():
    client = BinanceSpotClient("test-key", "test-secret", testnet=True)

    class NoNetworkSession:
        called = False
        def request(self, *args, **kwargs):
            self.called = True
            raise AssertionError("network must not be reached")

    session = NoNetworkSession()
    client.session = session
    with pytest.raises(RuntimeError, match="ExecutionBarrier permit required"):
        client.order("BTCUSDT", "BUY", "MARKET", quantity="0.001", new_client_order_id="CID_DIRECT")
    assert not session.called
