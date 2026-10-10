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
        self.fail_intent_status = set(fail_intent_status) if isinstance(fail_intent_status, (set, list, tuple)) else ({fail_intent_status} if fail_intent_status else set())
        self.fail_event = fail_event
        self.fail_claim = fail_claim

    def save_execution_intent(self, intent, status, reason=""):
        if status in self.fail_intent_status:
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


class FakeSpotClient:
    testnet = True
    base_url = "https://testnet.binance.vision"


def _barrier(db, provider=_ready_preflight, *, client=None, service_client=None):
    cache = ContextCache()
    cache.publish(_context())
    client = client or FakeSpotClient()
    service = None
    if provider is not None:
        service = PreflightCheckService(service_client if service_client is not None else client, symbols=["BTCUSDT"])
        # Controlled test seam: production callers cannot supply a callback to
        # ExecutionBarrier; tests can deterministically model Preflight outcomes.
        service.verify_all = provider
    return ExecutionBarrier(cache, db, preflight_service=service, exchange_client=client)


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
    assert db.intents[result.intent_id][0] == "BLOCKED"


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
    assert result.reason == "trusted_preflight_service_missing_or_invalid"
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
    with pytest.raises(RuntimeError, match="matching ExecutionBarrier permit required"):
        client.order("BTCUSDT", "BUY", "MARKET", quantity="0.001", new_client_order_id="CID_DIRECT")
    assert not session.called


def test_blocked_state_write_failure_still_never_calls_submit():
    db = FakeDB(fail_intent_status={"BLOCKED"})
    stale = dict(_ready_preflight(), checked_at_ms=int(time.time() * 1000) - 10_000)
    barrier = _barrier(db, provider=lambda: stale)
    calls = []
    result = barrier.execute(_intent("CID_BLOCKED_WRITE_FAIL"), lambda: calls.append("submit"), pre_submit_checks=_pre_submit)
    assert not result.accepted
    assert result.reason == "preflight_result_stale_or_future"
    assert calls == []


def test_submit_timeout_is_ambiguous_and_requires_reconciliation_without_retry():
    db = FakeDB()
    barrier = _barrier(db)
    calls = []

    def uncertain_submit():
        calls.append("submit")
        raise TimeoutError("synthetic response lost after possible exchange acceptance")

    with pytest.raises(TimeoutError):
        barrier.execute(_intent("CID_TIMEOUT_UNKNOWN"), uncertain_submit, pre_submit_checks=_pre_submit)

    assert calls == ["submit"]
    assert db.states["position_state"] == "RECONCILE_REQUIRED"
    row = next(iter(db.intents.values()))
    assert row[0] == "AMBIGUOUS"
    assert row[2] == "CID_TIMEOUT_UNKNOWN"


def test_submitted_state_write_failure_marks_reconciliation_and_does_not_retry():
    db = FakeDB(fail_intent_status={"SUBMITTED"})
    barrier = _barrier(db)
    calls = []

    with pytest.raises(RuntimeError, match="durable result persistence failed"):
        barrier.execute(
            _intent("CID_SUBMITTED_WRITE_FAIL"),
            lambda: (calls.append("submit") or {"status": "FILLED", "executedQty": "1"}),
            pre_submit_checks=_pre_submit,
        )

    assert calls == ["submit"]
    assert db.states["position_state"] == "RECONCILE_REQUIRED"
    assert db.intents[next(iter(db.intents))][0] == "PENDING"


def test_concurrent_duplicate_buy_intents_submit_once():
    import threading

    db = FakeDB()
    barrier = _barrier(db)
    calls = []
    results = []

    def run():
        results.append(barrier.execute(
            _intent("CID_CONCURRENT_DUPLICATE"),
            lambda: (calls.append("submit") or {"status": "FILLED", "executedQty": "1"}),
            pre_submit_checks=_pre_submit,
        ))

    threads = [threading.Thread(target=run) for _ in range(2)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(timeout=2)

    assert all(not thread.is_alive() for thread in threads)
    assert calls == ["submit"]
    assert sum(1 for result in results if result.accepted) == 1
    assert sum(1 for result in results if not result.accepted) == 1


def test_pending_and_blocked_persistence_both_fail_never_calls_submit():
    db = FakeDB(fail_intent_status={"PENDING", "BLOCKED"})
    barrier = _barrier(db)
    calls = []
    result = barrier.execute(_intent("CID_DOUBLE_PERSISTENCE_FAILURE"), lambda: calls.append("submit"), pre_submit_checks=_pre_submit)
    assert not result.accepted
    assert result.reason == "execution_intent_persistence_failed"
    assert calls == []
    assert result.intent_id not in db.intents


def test_unknown_reconciliation_and_preflight_exception_block_mutation():
    db = FakeDB()
    calls = []
    unknown = _ready_preflight()
    unknown["checks"]["reconciliation"] = "UNKNOWN"
    barrier = _barrier(db, provider=lambda: unknown)
    result = barrier.execute(_intent("CID_RECON_UNKNOWN"), lambda: calls.append("submit"), pre_submit_checks=_pre_submit)
    assert not result.accepted
    assert result.reason == "preflight_required_check_not_pass"
    assert calls == []

    def raises():
        raise TimeoutError("synthetic preflight timeout")
    barrier = _barrier(FakeDB(), provider=raises)
    result = barrier.execute(_intent("CID_PREFLIGHT_EXCEPTION"), lambda: calls.append("submit"), pre_submit_checks=_pre_submit)
    assert not result.accepted
    assert result.reason == "preflight_exception:TimeoutError"
    assert calls == []


def test_missing_stable_client_order_id_blocks_before_submit():
    db = FakeDB()
    barrier = _barrier(db)
    calls = []
    intent = OrderIntent.new("BTCUSDT", "BUY", "MARKET", {"1h": 1}, permission_interval="1h")
    result = barrier.execute(intent, lambda: calls.append("submit"), pre_submit_checks=_pre_submit)
    assert not result.accepted
    assert result.reason == "stable_client_order_id_missing"
    assert calls == []


def test_mainnet_exchange_client_is_rejected_even_with_green_preflight():
    class MainnetClient:
        testnet = False
        base_url = "https://api.binance.com"

    cache = ContextCache()
    cache.publish(_context())
    barrier = ExecutionBarrier(
        cache, FakeDB(),
        preflight_provider=_ready_preflight,
        exchange_client=MainnetClient(),
    )
    calls = []
    result = barrier.execute(_intent("CID_MAINNET_BLOCK"), lambda: calls.append("submit"), pre_submit_checks=_pre_submit)
    assert not result.accepted
    assert result.reason == "execution_client_not_pinned_to_spot_testnet"
    assert calls == []


def test_arbitrary_ready_callback_is_not_trusted_and_never_submits():
    db = FakeDB()
    cache = ContextCache()
    cache.publish(_context())
    calls = []
    # Legacy arbitrary callback is intentionally ignored, even if it returns a
    # complete fabricated ready report.
    barrier = ExecutionBarrier(
        cache, db, preflight_provider=_ready_preflight,
        exchange_client=FakeSpotClient(),
    )
    result = barrier.execute(
        _intent("CID_UNTRUSTED_CALLBACK"),
        lambda: calls.append("submit"),
        pre_submit_checks=_pre_submit,
    )
    assert not result.accepted
    assert result.reason == "trusted_preflight_service_missing_or_invalid"
    assert calls == []


def test_preflight_service_bound_to_different_client_never_submits():
    db = FakeDB()
    execution_client = FakeSpotClient()
    other_client = FakeSpotClient()
    barrier = _barrier(
        db, client=execution_client, service_client=other_client,
    )
    calls = []
    result = barrier.execute(
        _intent("CID_PREFLIGHT_CLIENT_MISMATCH"),
        lambda: calls.append("submit"),
        pre_submit_checks=_pre_submit,
    )
    assert not result.accepted
    assert result.reason == "preflight_execution_client_mismatch"
    assert calls == []


@pytest.mark.parametrize("violation", [
    "endpoint", "environment", "missing_checks", "future", "unknown", "reconcile",
])
def test_preflight_integrity_violations_never_submit(violation):
    report = _ready_preflight()
    if violation == "endpoint":
        client = FakeSpotClient()
        client.base_url = "https://api.binance.com"
        barrier = _barrier(FakeDB(), client=client)
    else:
        client = None
        if violation == "environment":
            report["environment"] = "LIVE"
        elif violation == "missing_checks":
            report["checks"].pop(PreflightCheckService.REQUIRED_CHECKS[0])
        elif violation == "future":
            report["checked_at_ms"] = int(time.time() * 1000) + 60_000
        elif violation == "unknown":
            report["checks"][PreflightCheckService.REQUIRED_CHECKS[0]] = "UNKNOWN"
        elif violation == "reconcile":
            report["requires_reconciliation"] = True
        barrier = _barrier(FakeDB(), provider=lambda: report)
    calls = []
    result = barrier.execute(
        _intent("CID_PREFLIGHT_" + violation.upper()),
        lambda: calls.append("submit") or {"status": "FILLED", "executedQty": "1"},
        pre_submit_checks=_pre_submit,
    )
    assert not result.accepted
    assert calls == []
