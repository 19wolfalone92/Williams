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



@pytest.mark.parametrize("order_type,purpose,list_status,child_status", [
    ("OCO", "PROTECTION", "EXECUTING", "NEW"),
    ("CANCEL", "CAMPAIGN_EXIT_CANCEL", "ALL_DONE", "CANCELED"),
])
def test_oco_timeout_restart_queries_list_and_both_children(
    order_type, purpose, list_status, child_status, tmp_path
):
    cache = ready_context()
    db = ready_db(tmp_path)
    cid = "TASK001_OCO_RECOVERY_" + purpose
    intent = OrderIntent.new(
        "BTCUSDT", "SELL", order_type, required_context_versions={},
        purpose=purpose, client_order_id=cid,
    )
    db.save_execution_intent(intent, "AMBIGUOUS", "simulated timeout after request")
    db.state_set("position_state", "RECONCILE_REQUIRED")
    db.state_set("position_state:BTCUSDT", "RECONCILE_REQUIRED")

    class FakeExchange:
        def __init__(self):
            self.list_queries = []
            self.child_queries = []
        def order_list(self, symbol, list_client_order_id=None):
            self.list_queries.append((symbol, list_client_order_id))
            return {
                "symbol": symbol, "orderListId": 77,
                "listClientOrderId": list_client_order_id,
                "listStatusType": list_status,
                "listOrderStatus": list_status,
                "orders": [
                    {"orderId": 701, "clientOrderId": cid + "_A"},
                    {"orderId": 702, "clientOrderId": cid + "_B"},
                ],
            }
        def get_order(self, symbol, order_id=None, orig_client_order_id=None):
            self.child_queries.append((symbol, order_id))
            return {
                "symbol": symbol, "orderId": order_id,
                "clientOrderId": cid + ("_A" if int(order_id) == 701 else "_B"),
                "status": child_status, "executedQty": "0",
            }

    exchange = FakeExchange()
    service = CampaignExecutionService(exchange, db, ExecutionBarrier(cache, db))
    assert service.startup_recovery_report["resolved"] == [intent.intent_id]
    assert service.startup_recovery_report["unresolved"] == []
    assert exchange.list_queries == [("BTCUSDT", cid)]
    assert exchange.child_queries == [("BTCUSDT", 701), ("BTCUSDT", 702)]
    row = db.conn.execute(
        "SELECT status FROM execution_intents WHERE intent_id=?", (intent.intent_id,)
    ).fetchone()
    assert row["status"] == "RECONCILED"
    # Order/list lookup alone cannot clear the portfolio exposure latch.
    assert db.state_get("position_state") == "RECONCILE_REQUIRED"



def test_android_oco_recovery_requires_authoritative_state_for_both_children():
    from pathlib import Path
    source = (Path(__file__).parent /
              "app/src/main/java/com/williamsbot/StandaloneRuntime.kt").read_text(encoding="utf-8")
    assert "Binance order-list lookup did not identify both child orders" in source
    assert "Binance OCO child order state is not authoritative" in source
    assert 'signedGet(\n                                "/api/v3/order",' in source


@pytest.mark.parametrize("bad_child", [
    {"orderId": 701, "status": "UNKNOWN"},
    {"orderId": 701},
    {"orderId": None, "status": "NEW"},
])
def test_oco_restart_unknown_or_malformed_child_stays_unresolved(
    bad_child, tmp_path
):
    """A known list ID is insufficient: both child states must be authoritative."""
    cache = ready_context()
    db = ready_db(tmp_path)
    cid = "TASK001_OCO_UNKNOWN_CHILD"
    intent = OrderIntent.new(
        "BTCUSDT", "SELL", "OCO", required_context_versions={},
        purpose="PROTECTION", client_order_id=cid,
    )
    db.save_execution_intent(intent, "AMBIGUOUS", "simulated response loss")
    db.state_set("position_state", "RECONCILE_REQUIRED")
    db.state_set("position_state:BTCUSDT", "RECONCILE_REQUIRED")

    class FakeExchange:
        def __init__(self):
            self.create_calls = 0
            self.list_queries = 0
            self.child_queries = []
        def order_list(self, symbol, list_client_order_id=None):
            self.list_queries += 1
            return {
                "symbol": symbol, "orderListId": 88,
                "listClientOrderId": list_client_order_id,
                "listStatusType": "EXECUTING", "listOrderStatus": "EXECUTING",
                "orders": [
                    {"orderId": 701, "clientOrderId": cid + "_A"},
                    {"orderId": 702, "clientOrderId": cid + "_B"},
                ],
            }
        def get_order(self, symbol, order_id=None, orig_client_order_id=None):
            self.child_queries.append(order_id)
            if str(order_id) == "701":
                return dict(bad_child, symbol=symbol)
            return {
                "symbol": symbol, "orderId": order_id,
                "clientOrderId": cid + "_B", "status": "NEW",
                "executedQty": "0",
            }
        def create_oco_sell_safe(self, *args, **kwargs):
            self.create_calls += 1
            raise AssertionError("recovery must never create duplicate protection")

    exchange = FakeExchange()
    service = CampaignExecutionService(exchange, db, ExecutionBarrier(cache, db))
    report = service.startup_recovery_report
    assert report["resolved"] == []
    assert report["unresolved"] == [intent.intent_id]
    assert exchange.list_queries == 1
    assert exchange.child_queries == [701, 702]
    assert exchange.create_calls == 0
    row = db.conn.execute(
        "SELECT status FROM execution_intents WHERE intent_id=?", (intent.intent_id,)
    ).fetchone()
    assert row["status"] == "AMBIGUOUS"
    assert db.state_get("position_state") == "RECONCILE_REQUIRED"
    assert db.state_get("position_state:BTCUSDT") == "RECONCILE_REQUIRED"


def test_cancel_replace_restart_refuses_to_infer_old_leg_from_replacement_only(tmp_path):
    """Current schema intentionally blocks restart resolution without old-order identity."""
    cache = ready_context()
    db = ready_db(tmp_path)
    cid = "TASK001_CANCEL_REPLACE_RESTART"
    intent = OrderIntent.new(
        "BTCUSDT", "SELL", "STOP_LOSS", required_context_versions={},
        purpose="CAMPAIGN_TRAIL", client_order_id=cid,
    )
    db.save_execution_intent(intent, "AMBIGUOUS", "cancelReplace response lost")
    db.state_set("position_state", "RECONCILE_REQUIRED")
    db.state_set("position_state:BTCUSDT", "RECONCILE_REQUIRED")

    class FakeExchange:
        def __init__(self):
            self.lookups = []
            self.posts = 0
        def get_order(self, symbol, orig_client_order_id=None, order_id=None):
            self.lookups.append((symbol, orig_client_order_id, order_id))
            return {
                "symbol": symbol, "orderId": 901,
                "clientOrderId": cid, "status": "NEW", "executedQty": "0",
            }
        def cancel_replace(self, *args, **kwargs):
            self.posts += 1
            raise AssertionError("restart recovery must not repeat cancelReplace")

    exchange = FakeExchange()
    service = CampaignExecutionService(exchange, db, ExecutionBarrier(cache, db))
    assert service.startup_recovery_report["resolved"] == []
    assert service.startup_recovery_report["unresolved"] == [intent.intent_id]
    assert exchange.lookups == []
    assert exchange.posts == 0
    row = db.conn.execute(
        "SELECT status FROM execution_intents WHERE intent_id=?", (intent.intent_id,)
    ).fetchone()
    assert row["status"] == "AMBIGUOUS"
    assert db.state_get("position_state") == "RECONCILE_REQUIRED"



def test_cancel_replace_operation_journal_persists_both_legs_and_recovers_independently(tmp_path):
    """Restart queries the old exchange ID and replacement client ID without resubmitting."""
    cache = ready_context()
    db = ready_db(tmp_path)
    old_id = "811"
    old_cid = "TASK001_OLD_STOP"
    new_cid = "TASK001_NEW_STOP"
    intent = OrderIntent.new(
        "BTCUSDT", "SELL", "STOP_LOSS", required_context_versions={},
        purpose="CAMPAIGN_TRAIL", client_order_id=new_cid,
    )
    db.save_execution_intent(intent, "AMBIGUOUS", "simulated response loss")
    db.create_cancel_replace_operation(
        operation_id=intent.intent_id, intent_id=intent.intent_id,
        symbol="BTCUSDT", old_order_id=old_id, old_client_order_id=old_cid,
        replacement_client_order_id=new_cid,
    )
    saved = db.get_cancel_replace_operation(intent.intent_id)
    assert saved["old_exchange_order_id"] == old_id
    assert saved["old_client_order_id"] == old_cid
    assert saved["replacement_client_order_id"] == new_cid
    assert saved["contract_version"] == 1
    assert saved["reconcile_required"] == 1

    class FakeExchange:
        def __init__(self):
            self.lookups = []
            self.mutations = 0
        def get_order(self, symbol, order_id=None, orig_client_order_id=None):
            self.lookups.append((symbol, str(order_id) if order_id is not None else None,
                                 orig_client_order_id))
            if order_id is not None:
                return {"symbol": symbol, "orderId": int(order_id),
                        "clientOrderId": old_cid, "status": "CANCELED",
                        "executedQty": "0.1", "origQty": "1"}
            return {"symbol": symbol, "orderId": 912,
                    "clientOrderId": new_cid, "status": "NEW",
                    "executedQty": "0", "origQty": "0.9"}
        def cancel_replace(self, *args, **kwargs):
            self.mutations += 1
            raise AssertionError("recovery must never repeat cancelReplace")

    exchange = FakeExchange()
    service = CampaignExecutionService(exchange, db, ExecutionBarrier(cache, db))
    assert exchange.lookups == [
        ("BTCUSDT", old_id, None),
        ("BTCUSDT", None, new_cid),
    ]
    assert exchange.mutations == 0
    assert service.startup_recovery_report["resolved"] == []
    assert service.startup_recovery_report["unresolved"] == [
        intent.intent_id + ": order legs observed; full financial and protection reconciliation required"
    ]
    saved = db.get_cancel_replace_operation(intent.intent_id)
    assert saved["cancel_state"] == "CANCELED"
    assert saved["replacement_state"] == "NEW"
    assert saved["reconcile_required"] == 1
    assert saved["replacement_exchange_order_id"] == "912"
    assert db.state_get("position_state") == "RECONCILE_REQUIRED"


def test_exchange_fill_ledger_is_idempotent_and_preserves_exact_decimal_values(tmp_path):
    db = ready_db(tmp_path)
    trade = {
        "id": 901, "orderId": 77, "symbol": "BTCUSDT",
        "qty": "0.123456789123", "price": "61234.56789123",
        "quoteQty": "7559.876543210987", "commission": "0.00001234",
        "commissionAsset": "BNB", "isBuyer": True, "time": 1770000000000,
    }
    first = db.record_exchange_fill("BTCUSDT", trade)
    second = db.record_exchange_fill("BTCUSDT", dict(trade))
    rows = db.exchange_fills_for_order("BTCUSDT", 77)
    assert first["inserted"] is True
    assert second["inserted"] is False
    assert len(rows) == 1
    assert rows[0]["quantity_text"] == "0.123456789123"
    assert rows[0]["price_text"] == "61234.56789123"
    assert rows[0]["quote_quantity_text"] == "7559.876543210987"
    assert rows[0]["commission_amount_text"] == "0.00001234"
    assert rows[0]["commission_asset"] == "BNB"
    assert rows[0]["financial_state"] == "CONFIRMED"


def test_exchange_fill_ledger_keeps_missing_fee_unknown_then_enriches_same_trade(tmp_path):
    db = ready_db(tmp_path)
    base = {
        "id": 902, "orderId": 78, "symbol": "BTCUSDT",
        "qty": "0.25", "price": "40000.00", "quoteQty": "10000.00",
    }
    first = db.record_exchange_fill("BTCUSDT", base)
    assert first["financial_state"] == "UNKNOWN"
    assert db.exchange_fills_for_order("BTCUSDT", 78)[0]["commission_amount_text"] is None
    enriched = dict(base, commission="0.0001", commissionAsset="BTC")
    second = db.record_exchange_fill("BTCUSDT", enriched)
    row = db.exchange_fills_for_order("BTCUSDT", 78)[0]
    assert second["inserted"] is False
    assert row["commission_amount_text"] == "0.0001"
    assert row["commission_asset"] == "BTC"
    assert row["financial_state"] == "CONFIRMED"


def test_exchange_fill_ledger_rejects_trade_id_with_conflicting_financial_data(tmp_path):
    db = ready_db(tmp_path)
    trade = {
        "id": 903, "orderId": 79, "symbol": "BTCUSDT",
        "qty": "0.5", "price": "100.00", "quoteQty": "50.00",
        "commission": "0.01", "commissionAsset": "USDT",
    }
    db.record_exchange_fill("BTCUSDT", trade)
    import pytest
    with pytest.raises(RuntimeError, match="identity collision"):
        db.record_exchange_fill("BTCUSDT", dict(trade, qty="0.6"))


def test_fee_reconciliation_does_not_treat_missing_commission_as_zero(tmp_path):
    db = ready_db(tmp_path)

    class FakeExchange:
        def my_trades(self, symbol, order_id=None):
            return [{
                "id": 904, "orderId": order_id, "symbol": symbol,
                "qty": "0.1", "price": "100.00", "quoteQty": "10.00",
                # Missing commission and commissionAsset are intentionally unknown.
            }]
        def exchange_info(self, symbol):
            return {"symbols": [{"baseAsset": "BTC", "quoteAsset": "USDT"}]}

    service = CampaignExecutionService(FakeExchange(), db, None)
    with pytest.raises(Exception, match="commission amount or asset is unknown"):
        service._executed_fee_quote("BTCUSDT", {"orderId": 80}, 0.1, 100.0)
    rows = db.exchange_fills_for_order("BTCUSDT", 80)
    assert len(rows) == 1
    assert rows[0]["financial_state"] == "UNKNOWN"
    assert rows[0]["commission_amount_text"] is None
    assert rows[0]["commission_asset"] is None


def test_cancel_replace_operation_identity_is_immutable(tmp_path):
    db = ready_db(tmp_path)
    db.create_cancel_replace_operation("op1", "intent1", "BTCUSDT", "11", "OLD", "NEW")
    import pytest
    with pytest.raises(RuntimeError, match="identity collision"):
        db.create_cancel_replace_operation("op1", "intent1", "BTCUSDT", "12", "OLD", "NEW")

# Release-gate note: fake-exchange recovery tests are not physical-device or Spot Testnet evidence.
