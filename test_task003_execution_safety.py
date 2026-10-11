import sqlite3
import threading

import pytest

from db import Database
from execution_barrier import (
    ExecutionAmbiguousError,
    ExecutionBarrier,
    OrderIntent,
)
from market_context import ContextCache, TFMarketContext
from order_identity import stable_client_order_id
from order_state_machine import OrderState, OrderStateMachine


def _cache(*, allow_long=True, allow_short=False):
    cache = ContextCache()
    cache.publish(
        TFMarketContext(
            symbol="BTCUSDT",
            interval="1h",
            version=1,
            candle_open_time_ms=1_000,
            candle_close_time_ms=2_000,
            price=100.0,
            allow_long=allow_long,
            allow_short=allow_short,
            long_probability=0.7 if allow_long else 0.0,
            short_probability=0.1 if allow_short else 0.0,
            no_trade_probability=0.2,
        )
    )
    return cache


def _entry_intent(*, signal_id="BTCUSDT:1h:ENTRY:2000", cid=None, risk=10.0):
    cid = cid or stable_client_order_id("WILLV5_ENTRY_", "BTCUSDT", signal_id)
    return OrderIntent.new(
        "BTCUSDT",
        "BUY",
        "MARKET",
        {"1h": 1},
        purpose="ENTRY",
        permission_interval="1h",
        signal_id=signal_id,
        client_order_id=cid,
        quote_order_quantity="100",
        risk_quote=risk,
        capital_reserved_quote=100,
    )


def _filled():
    return {
        "symbol": "BTCUSDT",
        "orderId": 101,
        "clientOrderId": "WILLV5_ENTRY_test",
        "status": "FILLED",
        "executedQty": "1.0",
        "cummulativeQuoteQty": "100.0",
    }


def test_intent_save_error_never_calls_exchange(tmp_path, monkeypatch):
    db = Database(str(tmp_path / "state.sqlite3"))
    barrier = ExecutionBarrier(_cache(), db)
    calls = []

    def fail_save(*args, **kwargs):
        raise sqlite3.OperationalError("injected INSERT failure")

    monkeypatch.setattr(db, "save_execution_intent", fail_save)
    result = barrier.execute(
        _entry_intent(),
        lambda: calls.append("sent") or _filled(),
        pre_submit_checks=lambda _snapshot: None,
    )
    assert not result.accepted
    assert "persistence_failed" in result.reason
    assert calls == []


def test_commit_error_never_calls_exchange(tmp_path, monkeypatch):
    db = Database(str(tmp_path / "state.sqlite3"))
    barrier = ExecutionBarrier(_cache(), db)
    calls = []
    original_commit = db._commit_durable
    commits = {"count": 0}

    def fail_third_commit():
        commits["count"] += 1
        # Entry admission lock and stable identity claims commit first.
        # Fail the first write-ahead execution-intent COMMIT.
        if commits["count"] == 3:
            raise sqlite3.OperationalError("injected COMMIT failure")
        return original_commit()

    monkeypatch.setattr(db, "_commit_durable", fail_third_commit)
    result = barrier.execute(
        _entry_intent(),
        lambda: calls.append("sent") or _filled(),
        pre_submit_checks=lambda _snapshot: None,
    )
    assert not result.accepted
    assert calls == []
    assert not db.has_unresolved_execution_intents()


def test_missing_mandatory_persistence_method_fails_closed(tmp_path):
    real_db = Database(str(tmp_path / "state.sqlite3"))

    class MissingIntentWriter:
        def __getattr__(self, name):
            if name == "save_execution_intent":
                raise AttributeError(name)
            return getattr(real_db, name)

    barrier = ExecutionBarrier(_cache(), MissingIntentWriter())
    calls = []
    result = barrier.execute(
        _entry_intent(),
        lambda: calls.append("sent") or _filled(),
        pre_submit_checks=lambda _snapshot: None,
    )
    assert not result.accepted
    assert calls == []


def test_two_parallel_identical_entries_submit_at_most_once(tmp_path):
    db = Database(str(tmp_path / "state.sqlite3"))
    cache = _cache()
    barrier = ExecutionBarrier(cache, db)
    intent = _entry_intent()
    calls = []
    outcomes = []
    lock = threading.Lock()

    def submit():
        with lock:
            calls.append(intent.client_order_id)
        return {**_filled(), "clientOrderId": intent.client_order_id}

    def worker():
        outcomes.append(
            barrier.execute(
                intent,
                submit,
                pre_submit_checks=lambda _snapshot: None,
            )
        )

    threads = [threading.Thread(target=worker) for _ in range(2)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(timeout=5)

    assert all(not thread.is_alive() for thread in threads)
    assert len(calls) == 1
    assert sum(1 for result in outcomes if result.accepted) == 1


def test_timeout_after_possible_send_locks_new_entries(tmp_path):
    db = Database(str(tmp_path / "state.sqlite3"))
    barrier = ExecutionBarrier(_cache(), db)
    actual_side_effect = {"happened": False}

    def timeout_after_send():
        actual_side_effect["happened"] = True
        raise TimeoutError("simulated response lost after Binance accepted order")

    with pytest.raises(ExecutionAmbiguousError):
        barrier.execute(
            _entry_intent(),
            timeout_after_send,
            pre_submit_checks=lambda _snapshot: None,
        )

    assert actual_side_effect["happened"]
    assert db.has_unresolved_execution_intents()
    assert db.state_get(ExecutionBarrier.MUTATION_LOCK_KEY, "")

    # A repeated user command with the same logical identity is not a retry
    # authorization while the original exchange outcome remains ambiguous.
    repeat_calls = []
    repeated = barrier.execute(
        _entry_intent(),
        lambda: repeat_calls.append("blind-retry") or _filled(),
        pre_submit_checks=lambda _snapshot: None,
    )
    assert not repeated.accepted
    assert repeat_calls == []
    assert db.load_execution_intent(_entry_intent().intent_id)["status"] == "AMBIGUOUS"

    next_calls = []
    blocked = barrier.execute(
        _entry_intent(signal_id="BTCUSDT:1h:ENTRY:2001"),
        lambda: next_calls.append("sent") or _filled(),
        pre_submit_checks=lambda _snapshot: None,
    )
    assert not blocked.accepted
    assert next_calls == []


def test_partial_fill_quantity_is_not_flattened_by_state_machine():
    fsm = OrderStateMachine()
    fsm.transition(OrderState.ADMISSION)
    fsm.transition(OrderState.SUBMITTING)
    state = fsm.observe_exchange_status("PARTIALLY_FILLED", 0.4)
    assert state == OrderState.PARTIALLY_FILLED


def test_cancel_fill_race_requires_reconciliation_and_never_retries(tmp_path):
    db = Database(str(tmp_path / "state.sqlite3"))
    barrier = ExecutionBarrier(_cache(), db)
    calls = []
    cid = stable_client_order_id("WILLV5_STOP_CANCEL_", "BTCUSDT", "77")
    intent = OrderIntent.new(
        "BTCUSDT",
        "SELL",
        "CANCEL",
        {},
        purpose="CAMPAIGN_PROTECTION_CANCEL",
        client_order_id=cid,
        campaign_id="campaign-1",
        signal_id="signal-1",
        operation_kind="CANCEL",
        related_order_id="77",
    )

    def cancel_with_fill_race():
        calls.append("cancel")
        return {
            "symbol": "BTCUSDT",
            "orderId": 77,
            "clientOrderId": "WILLV5_STOP_target",
            "status": "CANCELED",
            "executedQty": "0.25",
        }

    with pytest.raises(ExecutionAmbiguousError):
        barrier.execute(intent, cancel_with_fill_race, pre_submit_checks=lambda _s: None)

    assert calls == ["cancel"]
    assert db.has_unresolved_execution_intents()
    assert db.state_get(ExecutionBarrier.MUTATION_LOCK_KEY, "")


def test_oco_cancel_fill_race_is_ambiguous_even_when_order_ids_array_has_no_status(tmp_path):
    db = Database(str(tmp_path / "state.sqlite3"))
    barrier = ExecutionBarrier(_cache(), db)
    cid = stable_client_order_id("WILLV5_OCO_CANCEL_", "BTCUSDT", "list-7")
    intent = OrderIntent.new(
        "BTCUSDT",
        "SELL",
        "CANCEL",
        {},
        purpose="CANCEL",
        client_order_id=cid,
        campaign_id="campaign-1",
        signal_id="oco-cancel:list-7",
        operation_kind="CANCEL_OCO",
        related_order_list_id="7",
    )
    calls = []

    def cancel_with_fill_race():
        calls.append("cancel")
        return {
            "symbol": "BTCUSDT",
            "orderListId": 7,
            "listStatusType": "ALL_DONE",
            "orders": [
                {"orderId": 71, "clientOrderId": "WILLV5_TP"},
                {"orderId": 72, "clientOrderId": "WILLV5_SL"},
            ],
            "orderReports": [
                {"orderId": 71, "status": "CANCELED", "executedQty": "0"},
                {"orderId": 72, "status": "FILLED", "executedQty": "0.2"},
            ],
        }

    with pytest.raises(ExecutionAmbiguousError):
        barrier.execute(
            intent,
            cancel_with_fill_race,
            pre_submit_checks=lambda _snapshot: None,
        )
    assert calls == ["cancel"]
    row = db.load_execution_intent(intent.intent_id)
    assert row["status"] == "AMBIGUOUS"
    assert db.state_get(ExecutionBarrier.MUTATION_LOCK_KEY, "")


def test_cancel_replace_partial_old_fill_is_ambiguous(tmp_path):
    db = Database(str(tmp_path / "state.sqlite3"))
    barrier = ExecutionBarrier(_cache(), db)
    cid = stable_client_order_id("WILLV5_TRAIL_REPLACE_", "campaign-1", "old-77", "stop-98")
    intent = OrderIntent.new(
        "BTCUSDT",
        "SELL",
        "STOP_LOSS",
        {},
        purpose="CAMPAIGN_TRAIL",
        client_order_id=cid,
        campaign_id="campaign-1",
        signal_id="signal-1",
        operation_kind="CANCEL_REPLACE",
        related_order_id="77",
        quantity="1",
    )
    calls = []
    response = {
        "cancelResult": "SUCCESS",
        "cancelResponse": {
            "orderId": 77,
            "status": "CANCELED",
            "executedQty": "0.2",
        },
        "newOrderResult": "SUCCESS",
        "newOrderResponse": {
            "orderId": 78,
            "status": "NEW",
            "executedQty": "0",
        },
    }
    with pytest.raises(ExecutionAmbiguousError):
        barrier.execute(
            intent,
            lambda: calls.append("cancelReplace") or response,
            pre_submit_checks=lambda _snapshot: None,
        )
    assert calls == ["cancelReplace"]
    row = db.load_execution_intent(intent.intent_id)
    assert row["status"] == "AMBIGUOUS"
    assert row["exchange_executed_qty"] is None
    assert db.state_get(ExecutionBarrier.MUTATION_LOCK_KEY, "")


def test_stale_context_version_blocks_new_entry(tmp_path):
    db = Database(str(tmp_path / "state.sqlite3"))
    cache = _cache()
    cache.publish(
        TFMarketContext(
            symbol="BTCUSDT",
            interval="1h",
            version=2,
            candle_open_time_ms=2_001,
            candle_close_time_ms=3_000,
            price=101.0,
            allow_long=True,
            long_probability=0.7,
            short_probability=0.1,
            no_trade_probability=0.2,
        )
    )
    barrier = ExecutionBarrier(cache, db)
    calls = []
    result = barrier.execute(
        _entry_intent(),
        lambda: calls.append("sent") or _filled(),
        pre_submit_checks=lambda _snapshot: None,
    )
    assert not result.accepted
    assert "stale context" in result.reason
    assert calls == []


@pytest.mark.parametrize(
    "state_key",
    ["position_state", "position_state:BTCUSDT", "campaign_state:campaign-1"],
)
def test_reconcile_required_state_blocks_new_entry(tmp_path, state_key):
    db = Database(str(tmp_path / "state.sqlite3"))
    db.state_set(state_key, "RECONCILE_REQUIRED")
    barrier = ExecutionBarrier(_cache(), db)
    calls = []
    result = barrier.execute(
        _entry_intent(),
        lambda: calls.append("sent") or _filled(),
        pre_submit_checks=lambda _snapshot: None,
    )
    assert not result.accepted
    assert calls == []


def test_optional_telemetry_failure_does_not_block_safe_exit(tmp_path, monkeypatch):
    db = Database(str(tmp_path / "state.sqlite3"))
    cache = _cache()
    barrier = ExecutionBarrier(cache, db)
    monkeypatch.setattr(
        db,
        "log_event",
        lambda *args, **kwargs: (_ for _ in ()).throw(RuntimeError("telemetry offline")),
    )
    cid = stable_client_order_id("WILLV5_EXIT_", "campaign-1", "signal-1", "qty-1")
    intent = OrderIntent.new(
        "BTCUSDT",
        "SELL",
        "MARKET",
        {},
        purpose="CAMPAIGN_EXIT",
        client_order_id=cid,
        campaign_id="campaign-1",
        signal_id="signal-1",
        quantity="1",
    )
    calls = []
    response = {
        "symbol": "BTCUSDT",
        "orderId": 102,
        "clientOrderId": cid,
        "status": "FILLED",
        "executedQty": "1",
        "cummulativeQuoteQty": "99",
    }
    result = barrier.execute(
        intent,
        lambda: calls.append("sent") or response,
        pre_submit_checks=lambda _snapshot: None,
    )
    assert result.accepted
    assert len(calls) == 1
    # A mandatory execution result is not optional telemetry. The actual
    # Binance response and fill quantities must survive a process restart.
    row = db.find_execution_intent_by_client_order_id(cid)
    assert row is not None
    assert row["exchange_status"] == "FILLED"
    assert row["exchange_order_id"] == "102"
    assert row["exchange_executed_qty"] == "1"
    assert row["exchange_cumulative_quote_qty"] == "99"
    assert '"executedQty":"1"' in row["exchange_response_json"]


def test_mandatory_exchange_result_persistence_failure_stays_ambiguous(tmp_path, monkeypatch):
    db = Database(str(tmp_path / "state.sqlite3"))
    barrier = ExecutionBarrier(_cache(), db)
    intent = _entry_intent()
    calls = []

    def fail_result_persistence(*_args, **_kwargs):
        raise RuntimeError("injected mandatory result-store failure")

    monkeypatch.setattr(db, "save_execution_result", fail_result_persistence)
    with pytest.raises(ExecutionAmbiguousError):
        barrier.execute(
            intent,
            lambda: calls.append("exchange_may_have_accepted") or {
                **_filled(),
                "clientOrderId": intent.client_order_id,
            },
            pre_submit_checks=lambda _snapshot: None,
        )

    # The exchange call may have succeeded. The code must not claim a durable
    # success or try again; the unresolved intent and global latch force GET/
    # account reconciliation with this exact stable clientOrderId.
    assert calls == ["exchange_may_have_accepted"]
    assert db.state_get(ExecutionBarrier.MUTATION_LOCK_KEY, "")
    assert db.has_unresolved_execution_intents()
    row = db.load_execution_intent(intent.intent_id)
    assert row is not None
    assert row["status"] in {"AMBIGUOUS", "SUBMITTING", "RECONCILE_REQUIRED"}
    assert not barrier.execute(
        _entry_intent(signal_id="BTCUSDT:1h:next"),
        lambda: calls.append("blind_retry") or _filled(),
        pre_submit_checks=lambda _snapshot: None,
    ).accepted
    assert calls == ["exchange_may_have_accepted"]


def test_corrupt_db_or_missing_store_never_sends_order():
    barrier = ExecutionBarrier(_cache(), None, require_durable_intent=True)
    calls = []
    result = barrier.execute(
        _entry_intent(),
        lambda: calls.append("sent") or _filled(),
        pre_submit_checks=lambda _snapshot: None,
    )
    assert not result.accepted
    assert calls == []


def test_native_android_signed_order_mutations_are_fenced_before_network():
    from pathlib import Path

    runtime = (
        Path(__file__).parent
        / "app"
        / "src"
        / "main"
        / "java"
        / "com"
        / "williamsbot"
        / "StandaloneRuntime.kt"
    ).read_text(encoding="utf-8")
    signed_start = runtime.index("private fun signedRequest(")
    signed_end = runtime.index("private fun signedRawGet(", signed_start)
    signed_request = runtime[signed_start:signed_end]

    # The only native network send primitive in this method must occur after
    # durable intent creation and the SUBMITTING commit/read-back.
    assert signed_request.index("validateAndPersistMutationIntent(mutation)") < signed_request.index(
        "http.newCall(request).execute()"
    )
    assert signed_request.index("auditStore.recordExecutionMutationResult(") < signed_request.index(
        "return JSONObject(lastBody)"
    )
    assert "auditStore.markExecutionMutationAmbiguous(" in signed_request
    assert "latchMutationReconciliation(" in signed_request

    # Duplicate helper declarations can silently change which version is
    # reviewed versus which version Kotlin compiles; require one canonical fence.
    import re
    for declaration in (
        "ExecutionMutationSpec",
        "parseSignedParams",
        "stableNativeClientOrderId",
        "executionMutationSpec",
        "latchMutationReconciliation",
        "validateAndPersistMutationIntent",
        "mutationResultStatus",
        "reconcileMutationFromAuthoritativeRead",
        "isPotentialOrderMutation",
    ):
        count = len(re.findall(
            rf"\bprivate\s+(?:data\s+class|fun)\s+{declaration}\b",
            runtime,
        ))
        assert count == 1, f"expected one canonical {declaration}, got {count}"

    # All supported REST mutation endpoints pass through this exact boundary.
    for endpoint in (
        "/api/v3/order",
        "/api/v3/orderList/oco",
        "/api/v3/order/cancelReplace",
        "/api/v3/orderList",
    ):
        assert endpoint in runtime
    assert "stable clientOrderId/listClientOrderId missing" in runtime
    assert "unresolvedExecutionMutationCount()" in runtime
    assert "unresolvedExecutionMutationCount(spec.symbol)" in runtime

    # Source inventory at this SHA found exactly three OkHttp senders:
    # 1) getBody(): unsigned GET to the configured local/backend baseUrl;
    # 2) signedRequest(): signed Binance REST boundary, with mutation intent
    #    persistence and mutation-path validation before execute();
    # 3) signedRawGet(): signed Binance GET-only read helper.
    # The GET helpers are not write-capable and do not accept mutation verbs.
    network_senders = [
        line for line in runtime.splitlines()
        if "http.newCall(" in line and ".execute()" in line
    ]
    assert len(network_senders) == 3, (
        "native raw HTTP senders changed; inventory every path before acceptance"
    )
    def function_body(name):
        start = runtime.index(f"private fun {name}(")
        end = runtime.find("\n    private fun ", start + 1)
        return runtime[start:] if end < 0 else runtime[start:end]
    backend_get = function_body("getBody")
    raw_get = function_body("signedRawGet")
    assert "baseUrl + path" in backend_get and ".get()" in backend_get
    assert "signedTimestamp()" in raw_get and ".get()" in raw_get
    assert ".post(" not in raw_get and ".delete(" not in raw_get and ".put(" not in raw_get
    assert "http.newCall(request).execute()" in raw_get
    assert "http.newCall(request).execute()" in signed_request
    assert "validateAndPersistMutationIntent(mutation)" in signed_request
    assert "auditStore.recordExecutionMutationResult(" in signed_request


def test_native_optional_rest_telemetry_is_not_execution_authority():
    from pathlib import Path

    runtime = (
        Path(__file__).parent
        / "app"
        / "src"
        / "main"
        / "java"
        / "com"
        / "williamsbot"
        / "StandaloneRuntime.kt"
    ).read_text(encoding="utf-8")
    signed_start = runtime.index("private fun signedRequest(")
    signed_end = runtime.index("private fun signedRawGet(", signed_start)
    signed_request = runtime[signed_start:signed_end]
    assert "runCatching {\n                        auditStore.recordRestCall(" in signed_request
    assert "auditStore.recordExecutionMutationResult(" in signed_request
    # A REST telemetry failure is swallowed; the durable mutation result write
    # is outside that runCatching and forces RCR if its commit/read-back fails.
    result_write = signed_request.index("auditStore.recordExecutionMutationResult(")
    telemetry_write = signed_request.index("auditStore.recordRestCall(")
    assert result_write < telemetry_write



def test_release_version_contracts_are_consistent():
    from pathlib import Path
    import re

    root = Path(__file__).parent
    gate = (root / "ci_release_gate.py").read_text(encoding="utf-8")
    server = (root / "server.py").read_text(encoding="utf-8")
    gradle = (root / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    activity = (
        root / "app" / "src" / "main" / "java" / "com" / "williamsbot"
        / "MainActivity.kt"
    ).read_text(encoding="utf-8")
    wrapper = (root / "gradle" / "wrapper" / "gradle-wrapper.properties").read_text(encoding="utf-8")
    android_ci = (root / ".github" / "workflows" / "android-apk.yml").read_text(encoding="utf-8")
    campaign_ci = (root / ".github" / "workflows" / "campaign-ci.yml").read_text(encoding="utf-8")
    wrapper_match = re.search(r"gradle-([0-9.]+)-bin\.zip", wrapper)
    android_gradle_match = re.search(r"GRADLE_VERSION:\s*['\"]([^'\"]+)['\"]", android_ci)
    campaign_gradle_match = re.search(r"gradle-version:\s*['\"]([^'\"]+)['\"]", campaign_ci)
    assert wrapper_match and android_gradle_match and campaign_gradle_match
    gate_match = re.search(r'EXPECTED_VERSION\s*=\s*[\'"]([^\'"]+)[\'"]', gate)
    server_match = re.search(r'^VERSION\s*=\s*[\'"]([^\'"]+)[\'"]', server, re.MULTILINE)
    gradle_match = re.search(r'versionName\s*=\s*"([^"]+)"', gradle)
    assert gate_match and server_match and gradle_match
    canonical = server_match.group(1)
    assert canonical == "4.25.0", "unexpected canonical release version; review release contract"
    assert gate_match.group(1) == canonical
    assert gradle_match.group(1) == canonical
    assert f"Williams {canonical}" in activity
    wrapper_version = wrapper_match.group(1)
    assert android_gradle_match.group(1) == wrapper_version
    assert campaign_gradle_match.group(1) == wrapper_version
    assert wrapper_version == "8.14.6", "unexpected wrapper version; review Android toolchain contract"



def test_process_death_after_submitting_intent_never_blindly_resends(tmp_path):
    db = Database(str(tmp_path / "state.sqlite3"))
    intent = _entry_intent()
    # Simulate process death after SUBMITTING is committed but before the
    # network callback runs. A fresh barrier must treat this as unresolved.
    assert db.save_execution_intent(
        intent, "SUBMITTING", "simulated process death before socket send"
    )
    restarted_barrier = ExecutionBarrier(_cache(), db)
    calls = []
    result = restarted_barrier.execute(
        intent,
        lambda: calls.append("sent") or _filled(),
        pre_submit_checks=lambda _snapshot: None,
    )
    assert not result.accepted
    assert calls == []
    assert db.has_unresolved_execution_intents()
    assert db.load_execution_intent(intent.intent_id)["status"] == "SUBMITTING"

def test_legacy_trader_preserves_entry_identity_while_execution_is_unresolved():
    from pathlib import Path
    from trader import Trader

    trader_source = (
        Path(__file__).parent / "trader.py"
    ).read_text(encoding="utf-8")
    helper = trader_source[
        trader_source.index("    def _execution_recovery_blocked("):
        trader_source.index("    def normalize_qty(", trader_source.index("    def _execution_recovery_blocked("))
    ]
    assert "self.execution_barrier.mutation_locked" in helper
    assert "self.db.has_unresolved_execution_intents()" in helper
    assert "except Exception:" in helper
    assert "return True" in helper

    # Exercise the guard with minimal collaborators: an unresolved execution
    # record or a failed state read must never authorize replacing its CID.
    class FakeDB:
        def __init__(self, unresolved):
            self.unresolved = unresolved

        def has_unresolved_execution_intents(self):
            if isinstance(self.unresolved, Exception):
                raise self.unresolved
            return self.unresolved

    class FakeBarrier:
        def __init__(self, locked):
            self.mutation_locked = locked

    instance = Trader.__new__(Trader)
    instance.db = FakeDB(True)
    instance.execution_barrier = FakeBarrier(False)
    assert instance._execution_recovery_blocked()
    instance.db = FakeDB(False)
    instance.execution_barrier = FakeBarrier(True)
    assert instance._execution_recovery_blocked()
    instance.db = FakeDB(RuntimeError("SQLite read failed"))
    instance.execution_barrier = FakeBarrier(False)
    assert instance._execution_recovery_blocked()
    instance.db = FakeDB(False)
    instance.execution_barrier = FakeBarrier(False)
    assert not instance._execution_recovery_blocked()

    # Guard must execute before writing a new entry_client_order_id, and the
    # barrier-rejected branch must re-check unresolved state before deleting it.
    buy_start = trader_source.index("    def market_buy(")
    buy_end = trader_source.find("\\n    def ", buy_start + 1)
    buy_body = trader_source[buy_start:buy_end if buy_end >= 0 else len(trader_source)]
    guard_pos = buy_body.index("if self._execution_recovery_blocked():")
    reservation_pos = buy_body.index("self.db.state_set('entry_client_order_id', cid)")
    assert guard_pos < reservation_pos
    rejected_pos = buy_body.index("if not result.accepted:")
    unresolved_recheck = buy_body.index("if self._execution_recovery_blocked():", rejected_pos)
    delete_pos = buy_body.index("self.db.state_delete('entry_client_order_id')", rejected_pos)
    assert unresolved_recheck < delete_pos


# TASK 003.3: independent OS-process proof. Threads are deliberately not used
# here: each worker opens its own SQLite connection to the same persistent file.
def _task0033_process_submit_worker(db_path, intent, start_event, result_queue, submit_counter):
    import time
    from db import Database
    from execution_barrier import ExecutionBarrier

    db = Database(db_path)
    try:
        cache = _cache()
        barrier = ExecutionBarrier(cache, db)
        if not start_event.wait(timeout=10):
            result_queue.put(("ERROR", "start barrier timed out"))
            return

        def submit():
            with submit_counter.get_lock():
                submit_counter.value += 1
            return {
                "symbol": intent.symbol,
                "orderId": 9001,
                "clientOrderId": intent.client_order_id,
                "status": "FILLED",
                "executedQty": "1.0",
                "cummulativeQuoteQty": "100.0",
            }

        result = barrier.execute(
            intent,
            submit,
            pre_submit_checks=lambda _snapshot: None,
        )
        result_queue.put(("ACCEPTED" if result.accepted else "BLOCKED", result.reason))
    finally:
        db.conn.close()


def test_independent_processes_same_logical_intent_submit_at_most_once(tmp_path):
    import multiprocessing

    db_path = str(tmp_path / "cross-process.sqlite3")
    bootstrap = Database(db_path)
    # Initialize schema before racing workers, so the race exercises intent
    # reservation/submit semantics rather than first-boot DDL contention.
    bootstrap.conn.close()

    ctx = multiprocessing.get_context("spawn")
    start_event = ctx.Event()
    result_queue = ctx.Queue()
    submit_counter = ctx.Value("i", 0)
    intent = _entry_intent(signal_id="BTCUSDT:1h:ENTRY:cross-process-0033")

    workers = [
        ctx.Process(
            target=_task0033_process_submit_worker,
            args=(db_path, intent, start_event, result_queue, submit_counter),
        )
        for _ in range(2)
    ]
    for worker in workers:
        worker.start()
    start_event.set()
    outcomes = [result_queue.get(timeout=20) for _ in workers]
    for worker in workers:
        worker.join(timeout=20)

    assert all(not worker.is_alive() for worker in workers)
    assert [worker.exitcode for worker in workers] == [0, 0]
    assert submit_counter.value == 1
    assert sum(status == "ACCEPTED" for status, _ in outcomes) == 1
    assert sum(status == "BLOCKED" for status, _ in outcomes) == 1

    # Reopen the same durable file in the parent and confirm one identity claim
    # and one terminal intent are visible across process boundaries.
    verify = Database(db_path)
    try:
        assert verify.load_execution_intent(intent.intent_id) is not None
        assert not verify.has_unresolved_execution_intents()
    finally:
        verify.conn.close()


def _task0033_crash_after_submitting_worker(db_path, intent, ready_event):
    from db import Database

    db = Database(db_path)
    try:
        assert db.save_execution_intent(
            intent, "SUBMITTING", "TASK0033 injected process death before known response"
        )
        ready_event.set()
        # The parent terminates this OS process after the durable state is visible.
        import time
        time.sleep(30)
    finally:
        db.conn.close()


def test_os_process_death_after_submitting_blocks_second_process_retry(tmp_path):
    import multiprocessing
    from db import Database
    from execution_barrier import ExecutionBarrier

    db_path = str(tmp_path / "process-death.sqlite3")
    bootstrap = Database(db_path)
    bootstrap.conn.close()
    ctx = multiprocessing.get_context("spawn")
    ready = ctx.Event()
    intent = _entry_intent(signal_id="BTCUSDT:1h:ENTRY:process-death-0033")
    first = ctx.Process(
        target=_task0033_crash_after_submitting_worker,
        args=(db_path, intent, ready),
    )
    first.start()
    try:
        assert ready.wait(timeout=15)
        first.terminate()
        first.join(timeout=10)
        assert first.exitcode is not None

        db = Database(db_path)
        try:
            barrier = ExecutionBarrier(_cache(), db)
            calls = []
            result = barrier.execute(
                intent,
                lambda: calls.append("blind-retry") or _filled(),
                pre_submit_checks=lambda _snapshot: None,
            )
            assert not result.accepted
            assert calls == []
            assert db.has_unresolved_execution_intents()
            assert db.load_execution_intent(intent.intent_id)["status"] == "SUBMITTING"
        finally:
            db.conn.close()
    finally:
        if first.is_alive():
            first.terminate()
            first.join(timeout=5)


def _financial_fill_worker(db_path, queue, fill):
    db = Database(str(db_path))
    db.conn.execute(
        "CREATE TABLE IF NOT EXISTS test_financial_projection("
        "effect_id TEXT PRIMARY KEY, quantity_decimal TEXT NOT NULL)"
    )
    db.conn.commit()

    def project(database, normalized):
        database.conn.execute(
            "INSERT INTO test_financial_projection(effect_id,quantity_decimal) VALUES(?,?)",
            (
                __import__("hashlib").sha256(
                    "\\x1f".join((
                        normalized["exchange"], normalized["environment"],
                        normalized["account_scope"], normalized["symbol"],
                        normalized["exchange_fill_id"],
                    )).encode("utf-8")
                ).hexdigest(),
                normalized["quantity"],
            ),
        )

    try:
        result = db.apply_financial_fill_effect(fill, project)
        queue.put(("ok", result))
    except Exception as exc:
        queue.put(("error", repr(exc)))
    finally:
        db.conn.close()


def _financial_fill(fill_id="9001", **overrides):
    fill = {
        "exchange": "BINANCE",
        "environment": "SPOT_TESTNET",
        "account_scope": "test-account-A",
        "symbol": "BTCUSDT",
        "exchange_fill_id": fill_id,
        "quantity": "0.01000000",
        "price": "61234.12000000",
        "quote_quantity": "612.3412000000",
        "commission": "0.00001000",
        "commission_asset": "BTC",
    }
    fill.update(overrides)
    return fill


def test_financial_fill_effect_is_idempotent_and_decimal_exact(tmp_path):
    db = Database(str(tmp_path / "financial.sqlite3"))
    db.conn.execute(
        "CREATE TABLE test_financial_projection(effect_id TEXT PRIMARY KEY, quantity_decimal TEXT NOT NULL)"
    )
    db.conn.commit()
    applied = []

    def project(database, normalized):
        applied.append(normalized)
        database.conn.execute(
            "INSERT INTO test_financial_projection(effect_id,quantity_decimal) VALUES(?,?)",
            (normalized["exchange_fill_id"], normalized["quantity"]),
        )

    first = db.apply_financial_fill_effect(_financial_fill(), project)
    duplicate = db.apply_financial_fill_effect(_financial_fill(), project)
    assert first["status"] == "APPLIED" and not first["duplicate"]
    assert duplicate["status"] == "APPLIED" and duplicate["duplicate"]
    assert len(applied) == 1
    assert applied[0]["quantity"] == "0.01000000"
    assert applied[0]["price"] == "61234.12000000"
    assert db.conn.execute("SELECT COUNT(*) FROM test_financial_projection").fetchone()[0] == 1
    row = db.conn.execute("SELECT * FROM financial_effect_journal").fetchone()
    assert row["commission_status"] == "KNOWN"
    assert row["projection_status"] == "APPLIED"


def test_unknown_commission_blocks_projection_until_explicit_resolution(tmp_path):
    db = Database(str(tmp_path / "unknown-fee.sqlite3"))
    db.conn.execute(
        "CREATE TABLE test_financial_projection(effect_id TEXT PRIMARY KEY, quantity_decimal TEXT NOT NULL)"
    )
    db.conn.commit()
    calls = []

    def project(database, normalized):
        calls.append(normalized)
        database.conn.execute(
            "INSERT INTO test_financial_projection(effect_id,quantity_decimal) VALUES(?,?)",
            (normalized["exchange_fill_id"], normalized["quantity"]),
        )

    pending = db.apply_financial_fill_effect(
        _financial_fill("9002", commission=None, commission_asset=None), project
    )
    assert pending["status"] == "PENDING_FEE"
    assert calls == []
    row = db.conn.execute("SELECT * FROM financial_effect_journal").fetchone()
    assert row["commission_status"] == "UNKNOWN"
    assert row["commission_decimal"] is None
    assert row["projection_status"] == "PENDING_FEE"

    resolved = db.resolve_financial_effect_fee(
        row["effect_id"], "0.00001000", "BTC", project
    )
    assert resolved["status"] == "APPLIED"
    assert len(calls) == 1
    assert calls[0]["commission"] == "0.00001000"
    assert db.conn.execute("SELECT COUNT(*) FROM test_financial_projection").fetchone()[0] == 1


def test_projection_failure_rolls_back_journal_and_projection_atomically(tmp_path):
    db = Database(str(tmp_path / "projection-failure.sqlite3"))
    db.conn.execute(
        "CREATE TABLE test_financial_projection(effect_id TEXT PRIMARY KEY, quantity_decimal TEXT NOT NULL)"
    )
    db.conn.commit()

    def fail_projection(database, normalized):
        database.conn.execute(
            "INSERT INTO test_financial_projection(effect_id,quantity_decimal) VALUES(?,?)",
            (normalized["exchange_fill_id"], normalized["quantity"]),
        )
        raise RuntimeError("injected projection failure")

    with pytest.raises(RuntimeError, match="injected projection failure"):
        db.apply_financial_fill_effect(_financial_fill("9003"), fail_projection)
    assert db.conn.execute("SELECT COUNT(*) FROM financial_effect_journal").fetchone()[0] == 0
    assert db.conn.execute("SELECT COUNT(*) FROM test_financial_projection").fetchone()[0] == 0


def test_independent_processes_apply_one_financial_fill_effect(tmp_path):
    import multiprocessing

    db_path = tmp_path / "concurrent-financial.sqlite3"
    db = Database(str(db_path))
    db.conn.execute(
        "CREATE TABLE test_financial_projection(effect_id TEXT PRIMARY KEY, quantity_decimal TEXT NOT NULL)"
    )
    db.conn.commit()
    db.conn.close()

    ctx = multiprocessing.get_context("spawn")
    queue = ctx.Queue()
    fill = _financial_fill("9004")
    workers = [
        ctx.Process(target=_financial_fill_worker, args=(str(db_path), queue, fill))
        for _ in range(2)
    ]
    for worker in workers:
        worker.start()
    for worker in workers:
        worker.join(timeout=15)
        assert not worker.is_alive(), "financial fill worker timed out"
        assert worker.exitcode == 0
    results = [queue.get(timeout=3) for _ in workers]
    assert all(result[0] == "ok" for result in results), results

    db = Database(str(db_path))
    journal = db.conn.execute("SELECT * FROM financial_effect_journal").fetchall()
    projection = db.conn.execute("SELECT * FROM test_financial_projection").fetchall()
    assert len(journal) == 1
    assert journal[0]["projection_status"] == "APPLIED"
    assert len(projection) == 1
    assert projection[0]["quantity_decimal"] == "0.01000000"


def test_fill_identity_conflict_is_fail_closed_and_does_not_reapply(tmp_path):
    db = Database(str(tmp_path / "fill-conflict.sqlite3"))
    db.conn.execute(
        "CREATE TABLE test_financial_projection(effect_id TEXT PRIMARY KEY, quantity_decimal TEXT NOT NULL)"
    )
    db.conn.commit()
    calls = []

    def project(database, normalized):
        calls.append(normalized)
        database.conn.execute(
            "INSERT INTO test_financial_projection(effect_id,quantity_decimal) VALUES(?,?)",
            (normalized["exchange_fill_id"], normalized["quantity"]),
        )

    db.apply_financial_fill_effect(_financial_fill("9010"), project)
    with pytest.raises(RuntimeError, match="immutable fill payload differs"):
        db.apply_financial_fill_effect(
            _financial_fill("9010", quantity="0.02000000"), project
        )
    assert len(calls) == 1
    assert db.conn.execute("SELECT COUNT(*) FROM test_financial_projection").fetchone()[0] == 1
    row = db.conn.execute("SELECT quantity_decimal,projection_status FROM financial_effect_journal").fetchone()
    assert row["quantity_decimal"] == "0.01"
    assert row["projection_status"] == "APPLIED"


def test_decimal_equivalent_fill_payload_is_canonical_duplicate(tmp_path):
    db = Database(str(tmp_path / "decimal-canonical.sqlite3"))
    db.conn.execute(
        "CREATE TABLE test_financial_projection(effect_id TEXT PRIMARY KEY, quantity_decimal TEXT NOT NULL)"
    )
    db.conn.commit()
    calls = []

    def project(database, normalized):
        calls.append(normalized)
        database.conn.execute(
            "INSERT INTO test_financial_projection(effect_id,quantity_decimal) VALUES(?,?)",
            (normalized["exchange_fill_id"], normalized["quantity"]),
        )

    first = db.apply_financial_fill_effect(
        _financial_fill("9011", quantity="0.010000", price="61234.1200",
                        quote_quantity="612.341200", commission="0.00001000"),
        project,
    )
    second = db.apply_financial_fill_effect(
        _financial_fill("9011", quantity="0.01", price="61234.12",
                        quote_quantity="612.3412", commission="0.00001"),
        project,
    )
    assert first["status"] == second["status"] == "APPLIED"
    assert not first["duplicate"] and second["duplicate"]
    assert len(calls) == 1
    row = db.conn.execute("SELECT * FROM financial_effect_journal").fetchone()
    assert row["quantity_decimal"] == "0.01"
    assert row["price_decimal"] == "61234.12"
    assert row["commission_decimal"] == "0.00001"


def test_unknown_fee_first_delivery_and_duplicate_are_distinguished(tmp_path):
    db = Database(str(tmp_path / "unknown-fee-duplicate.sqlite3"))
    calls = []

    def project(_database, normalized):
        calls.append(normalized)

    fill = _financial_fill("9012", commission=None, commission_asset=None)
    first = db.apply_financial_fill_effect(fill, project)
    duplicate = db.apply_financial_fill_effect(fill, project)
    assert first["status"] == duplicate["status"] == "PENDING_FEE"
    assert first["duplicate"] is False
    assert duplicate["duplicate"] is True
    assert calls == []
