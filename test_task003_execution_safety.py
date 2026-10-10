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
