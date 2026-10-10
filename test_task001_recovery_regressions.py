"""Deterministic Task 001 recovery regressions; fake exchange only, no network orders."""
import time

import pytest

from binance_client import BinanceAPIError, BinanceSpotClient
from db import Database
from campaign_execution import CampaignExecutionService
from execution_barrier import ExecutionBarrier, OrderIntent
from market_context import ContextCache, TFMarketContext


def ready_context():
    cache = ContextCache()
    now = int(time.time() * 1000)
    cache.publish(TFMarketContext(
        symbol="BTCUSDT", interval="1h", version=7,
        candle_open_time_ms=now - 3_600_000,
        candle_close_time_ms=now, price=100.0,
        allow_long=True, allow_short=False, decision="LONG",
    ))
    return cache


def ready_db(tmp_path):
    db = Database(str(tmp_path / "recovery-regression.sqlite3"))
    db.state_set("position_state", "FLAT")
    db.state_set("position_state:BTCUSDT", "FLAT")
    return db


def entry_intent(cache):
    ctx = cache.snapshot().context("BTCUSDT", "1h")
    return OrderIntent.new(
        "BTCUSDT", "BUY", "STOP_LOSS",
        required_context_versions={"1h": ctx.version},
        purpose="ENTRY", permission_interval="1h",
        client_order_id="TASK001_DBFAIL_ONCE",
    )


def test_exchange_accepted_but_outcome_sqlite_write_fails_is_never_reposted(tmp_path, monkeypatch):
    cache = ready_context()
    db = ready_db(tmp_path)
    real_save = db.save_execution_intent
    writes = {"submitted": 0}

    def injected_save(intent, status, reason=""):
        if status == "SUBMITTED":
            writes["submitted"] += 1
            raise OSError("injected SQLite outcome commit failure")
        return real_save(intent, status, reason)

    monkeypatch.setattr(db, "save_execution_intent", injected_save)
    barrier = ExecutionBarrier(cache, db)
    posts = []
    response = {"symbol": "BTCUSDT", "orderId": 1234,
                "clientOrderId": "TASK001_DBFAIL_ONCE",
                "status": "NEW", "executedQty": "0"}

    with pytest.raises(RuntimeError, match="outcome persistence failed"):
        barrier.execute(entry_intent(cache), lambda: posts.append("POST") or response)

    assert posts == ["POST"]
    assert writes["submitted"] == 1
    row = db.conn.execute(
        "SELECT status, reason FROM execution_intents WHERE client_order_id=?",
        ("TASK001_DBFAIL_ONCE",),
    ).fetchone()
    assert row["status"] == "AMBIGUOUS"
    assert "outcome persistence failed" in row["reason"]
    assert db.state_get("position_state") == "RECONCILE_REQUIRED"
    assert db.state_get("position_state:BTCUSDT") == "RECONCILE_REQUIRED"

    # The recovery contract is an authoritative lookup by the stable client ID,
    # not another POST. This fake is deliberately read-only.
    class FakeExchange:
        def __init__(self):
            self.lookups = []
        def get_order(self, symbol, orig_client_order_id=None):
            self.lookups.append((symbol, orig_client_order_id))
            return response

    exchange = FakeExchange()
    service = CampaignExecutionService(exchange, db, barrier)
    recovery = service.startup_recovery_report
    assert recovery["resolved"] == [row_id for row_id, in db.conn.execute(
        "SELECT intent_id FROM execution_intents WHERE client_order_id=?",
        ("TASK001_DBFAIL_ONCE",),
    )]
    assert recovery["unresolved"] == []
    assert exchange.lookups == [("BTCUSDT", "TASK001_DBFAIL_ONCE")]
    reconciled = db.conn.execute(
        "SELECT status FROM execution_intents WHERE client_order_id=?",
        ("TASK001_DBFAIL_ONCE",),
    ).fetchone()
    assert reconciled["status"] == "RECONCILED"
    # Reconciliation is a GET-only path; the original POST count stays exactly one.
    assert posts == ["POST"]


@pytest.mark.parametrize("cancel_result,new_result,cancel_response,new_response,authoritative", [
    ("SUCCESS", "SUCCESS",
     {"orderId": 11, "status": "CANCELED"},
     {"orderId": 12, "status": "NEW"}, True),
    ("SUCCESS", "FAILURE",
     {"orderId": 11, "status": "CANCELED"},
     {"orderId": 12, "status": "REJECTED"}, False),
    ("FAILURE", "SUCCESS",
     {"orderId": 11, "status": "NEW"},
     {"orderId": 12, "status": "NEW"}, False),
    ("", "", None, None, False),  # timeout/empty body
    ("SUCCESS", "SUCCESS", {"orderId": 11}, {"status": "NEW"}, False),  # malformed
])
def test_cancel_replace_requires_both_authoritative_legs(
    cancel_result, new_result, cancel_response, new_response, authoritative
):
    payload = {
        "cancelResult": cancel_result,
        "newOrderResult": new_result,
        "cancelResponse": cancel_response,
        "newOrderResponse": new_response,
    }
    assert ExecutionBarrier._cancel_replace_response_authoritative(payload) is authoritative


def test_oco_response_needs_two_distinct_authoritative_child_orders():
    base = {
        "orderListId": 9,
        "listClientOrderId": "TASK001_OCO",
        "listStatusType": "EXEC_STARTED",
        "listOrderStatus": "EXECUTING",
    }
    # List-level success without child states is not sufficient.
    assert not ExecutionBarrier._oco_response_authoritative(base)
    one_child = dict(base, orderReports=[{"orderId": 1, "status": "NEW"}])
    assert not ExecutionBarrier._oco_response_authoritative(one_child)
    duplicate_children = dict(base, orderReports=[
        {"orderId": 1, "status": "NEW"}, {"orderId": 1, "status": "NEW"}
    ])
    assert not ExecutionBarrier._oco_response_authoritative(duplicate_children)
    complete = dict(base, orderReports=[
        {"orderId": 1, "status": "NEW"}, {"orderId": 2, "status": "PARTIALLY_FILLED"}
    ])
    assert ExecutionBarrier._oco_response_authoritative(complete)


def test_bulk_cancel_is_blocked_without_network_io():
    client = BinanceSpotClient("fake-key", "fake-secret", testnet=True)
    calls = []
    client._request = lambda *args, **kwargs: calls.append((args, kwargs))
    with pytest.raises(BinanceAPIError, match="Bulk cancel is disabled"):
        client.cancel_open_orders("BTCUSDT")
    assert calls == []



@pytest.mark.parametrize("case,response,raises", [
    ("both_success", {
        "cancelResult": "SUCCESS", "newOrderResult": "SUCCESS",
        "cancelResponse": {"orderId": 21, "status": "CANCELED"},
        "newOrderResponse": {"orderId": 22, "status": "NEW", "executedQty": "0"},
    }, False),
    ("cancel_only", {
        "cancelResult": "SUCCESS", "newOrderResult": "FAILURE",
        "cancelResponse": {"orderId": 21, "status": "CANCELED"},
        "newOrderResponse": {"orderId": 22, "status": "REJECTED"},
    }, True),
    ("replace_only", {
        "cancelResult": "FAILURE", "newOrderResult": "SUCCESS",
        "cancelResponse": {"orderId": 21, "status": "NEW"},
        "newOrderResponse": {"orderId": 22, "status": "NEW", "executedQty": "0"},
    }, True),
    ("malformed", {
        "cancelResult": "SUCCESS", "newOrderResult": "SUCCESS",
        "cancelResponse": {"orderId": 21},
        "newOrderResponse": {"status": "NEW"},
    }, True),
])
def test_cancel_replace_barrier_matrix(case, response, raises, tmp_path):
    cache = ready_context()
    db = ready_db(tmp_path)
    barrier = ExecutionBarrier(cache, db)
    intent = OrderIntent.new(
        "BTCUSDT", "SELL", "STOP_LOSS",
        required_context_versions={}, purpose="CAMPAIGN_TRAIL",
        client_order_id="TASK001_CR_" + case,
    )
    if raises:
        with pytest.raises(RuntimeError, match="cancelReplace response"):
            barrier.execute(intent, lambda: response)
        row = db.conn.execute(
            "SELECT status FROM execution_intents WHERE intent_id=?", (intent.intent_id,)
        ).fetchone()
        assert row["status"] == "AMBIGUOUS"
        assert db.state_get("position_state") == "RECONCILE_REQUIRED"
    else:
        result = barrier.execute(intent, lambda: response)
        assert result.accepted is True
        row = db.conn.execute(
            "SELECT status FROM execution_intents WHERE intent_id=?", (intent.intent_id,)
        ).fetchone()
        assert row["status"] == "SUBMITTED"


def test_cancel_replace_timeout_is_ambiguous_and_never_retried(tmp_path):
    cache = ready_context()
    db = ready_db(tmp_path)
    barrier = ExecutionBarrier(cache, db)
    intent = OrderIntent.new(
        "BTCUSDT", "SELL", "STOP_LOSS", required_context_versions={},
        purpose="CAMPAIGN_TRAIL", client_order_id="TASK001_CR_TIMEOUT",
    )
    posts = []
    def timeout_once():
        posts.append("POST")
        raise TimeoutError("injected cancelReplace timeout")
    with pytest.raises(TimeoutError):
        barrier.execute(intent, timeout_once)
    assert posts == ["POST"]
    row = db.conn.execute(
        "SELECT status FROM execution_intents WHERE intent_id=?", (intent.intent_id,)
    ).fetchone()
    assert row["status"] == "AMBIGUOUS"
    assert db.state_get("position_state") == "RECONCILE_REQUIRED"


def test_production_bulk_cancel_paths_are_disabled_in_python_and_android():
    from pathlib import Path

    root = Path(__file__).parent
    client = (root / "binance_client.py").read_text(encoding="utf-8")
    start = client.index("    def cancel_open_orders(")
    end = client.index("\n    def ", start + 8)
    method = client[start:end]
    assert "Bulk cancel is disabled" in method
    assert "self._request(" not in method

    runtime = (root / "app/src/main/java/com/williamsbot/StandaloneRuntime.kt").read_text(encoding="utf-8")
    assert 'method == "DELETE" && path == "/api/v3/openOrders"' not in runtime
    assert 'else -> error("Unapproved Binance mutation route:' in runtime
