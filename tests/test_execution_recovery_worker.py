"""Mock-only restart recovery tests. All persistence uses temporary SQLite DBs."""
import threading
import time
from concurrent.futures import ThreadPoolExecutor
import pytest
from db import Database
from execution_barrier import OrderIntent
from execution_recovery import ExecutionRecoveryWorker

def _intent(db, *, status="AMBIGUOUS", client_id="williams-test-intent"):
    intent = OrderIntent.new("BTCUSDT", "BUY", "MARKET", {},
        purpose="CAMPAIGN_ENTRY", client_order_id=client_id, quantity="0.01")
    db.save_execution_intent(intent, status, "simulated restart")
    return intent

class FakeExchange:
    def __init__(self, response=None, error=None, delay=0):
        self.response, self.error, self.delay = response, error, delay
        self.trade_error = None
        self.trade_rows = None
        self.trade_calls = 0
        self.last_lookup = None
        self.calls, self.lock = 0, threading.Lock()
    def get_order(self, symbol, *, order_id=None, orig_client_order_id=None):
        self.last_lookup = {"order_id": order_id, "orig_client_order_id": orig_client_order_id}
        with self.lock: self.calls += 1
        if self.delay: time.sleep(self.delay)
        if self.error: raise self.error
        return dict(self.response)
    def my_trades(self, symbol, order_id=None, limit=1000):
        self.trade_calls += 1
        if self.trade_error: raise self.trade_error
        if self.trade_rows is not None: return list(self.trade_rows)
        qty = float(self.response.get("executedQty", 0) or 0)
        if qty <= 0: return []
        price = float(self.response.get("price", 0) or 0)
        if price <= 0:
            price = 100.0
        return [{"orderId": order_id, "qty": str(qty), "price": str(price), "quoteQty": str(qty * price)}]

def _order(status="NEW", **updates):
    value = {"symbol":"BTCUSDT","side":"BUY","orderId":123,
        "clientOrderId":"williams-test-intent","status":status,
        "origQty":"0.01000000","executedQty":"0.00000000","price":"0.0"}
    value.update(updates)
    return value

def test_worker_recovers_ambiguous_intent_after_database_reopen(tmp_path):
    path=tmp_path/"recover.sqlite3"
    with Database(str(path)) as db:
        intent=_intent(db); intent_id=intent.intent_id
    with Database(str(path)) as reopened:
        exchange=FakeExchange(_order("FILLED", executedQty="0.01000000"))
        result=ExecutionRecoveryWorker(reopened, exchange).run_once(now=time.time()+10)
        row=reopened.conn.execute("SELECT * FROM execution_intents WHERE intent_id=?",(intent_id,)).fetchone()
        assert result["reconciled"]==1 and row["status"]=="FILLED"
        assert row["order_id"]=="123" and row["exchange_status"]=="FILLED"
        assert row["executed_qty"]==pytest.approx(0.01)
        assert not reopened.has_unresolved_execution_intents() and exchange.calls==1

@pytest.mark.parametrize(("status","executed","expected_local","terminal"),[
    ("NEW","0","AMBIGUOUS",False),
    ("PENDING_CANCEL","0","AMBIGUOUS",False),
    ("PARTIALLY_FILLED","0.004","AMBIGUOUS",False),
    ("FILLED","0.01","FILLED",True),
    ("CANCELED","0.004","CANCELED",True),
    ("EXPIRED","0","EXPIRED",True),
    ("REJECTED","0","REJECTED",True)])
def test_worker_persists_exact_exchange_lifecycle(tmp_path,status,executed,expected_local,terminal):
    with Database(str(tmp_path/"lifecycle.sqlite3")) as db:
        intent=_intent(db); exchange=FakeExchange(_order(status,executedQty=executed))
        now=1000
        result=ExecutionRecoveryWorker(db,exchange,base_retry_seconds=7).run_once(now=now)
        row=db.conn.execute("SELECT * FROM execution_intents WHERE intent_id=?",(intent.intent_id,)).fetchone()
        assert (result["reconciled"] if terminal else result["active"])==1 and row["status"]==expected_local
        assert row["exchange_status"]==status and row["executed_qty"]==pytest.approx(float(executed))
        assert (row["resolved_at"] is not None) is terminal
        if terminal:
            assert not db.has_unresolved_execution_intents()
            assert row["next_reconcile_at"] == 0
        else:
            assert db.has_unresolved_execution_intents()
            assert row["next_reconcile_at"] == now + 7
            assert row["reason"] == "exchange order is active; follow-up reconciliation required"

def test_active_order_recovery_keeps_execution_barrier_closed_until_terminal(tmp_path):
    with Database(str(tmp_path/"active-barrier.sqlite3")) as db:
        intent=_intent(db)
        exchange=FakeExchange(_order("PARTIALLY_FILLED",executedQty="0.004"))
        worker=ExecutionRecoveryWorker(db,exchange,base_retry_seconds=5)
        first=worker.run_once(now=1000)
        assert first["active"]==1 and db.has_unresolved_execution_intents()
        assert worker.run_once(now=1001)["claimed"]==0
        exchange.response=_order("FILLED",executedQty="0.01")
        second=worker.run_once(now=1006)
        row=db.conn.execute("SELECT * FROM execution_intents WHERE intent_id=?",(intent.intent_id,)).fetchone()
        assert second["reconciled"]==1 and row["status"]=="FILLED"
        assert not db.has_unresolved_execution_intents()
        assert exchange.calls==2

def test_not_found_or_rate_limit_remains_ambiguous_with_backoff(tmp_path):
    with Database(str(tmp_path/"retry.sqlite3")) as db:
        intent=_intent(db); exchange=FakeExchange(error=RuntimeError("rate limit / order not found"))
        result=ExecutionRecoveryWorker(db,exchange,base_retry_seconds=5).run_once(now=1000)
        row=db.conn.execute("SELECT * FROM execution_intents WHERE intent_id=?",(intent.intent_id,)).fetchone()
        assert result["deferred"]==1 and row["status"]=="AMBIGUOUS"
        assert row["next_reconcile_at"]==1005 and row["lease_owner"] is None
        assert db.has_unresolved_execution_intents() and exchange.calls==1

def test_mismatched_order_identity_is_never_marked_reconciled(tmp_path):
    with Database(str(tmp_path/"mismatch.sqlite3")) as db:
        intent=_intent(db); exchange=FakeExchange(_order("FILLED",clientOrderId="other-intent"))
        result=ExecutionRecoveryWorker(db,exchange).run_once(now=1000)
        row=db.conn.execute("SELECT status,last_reconcile_error FROM execution_intents WHERE intent_id=?",(intent.intent_id,)).fetchone()
        assert result["deferred"]==1 and row["status"]=="AMBIGUOUS"
        assert "clientOrderId" in row["last_reconcile_error"] and db.has_unresolved_execution_intents()

def test_missing_identifiers_stay_unresolved_without_exchange_call(tmp_path):
    with Database(str(tmp_path/"no-id.sqlite3")) as db:
        intent=_intent(db,client_id=""); exchange=FakeExchange(_order())
        result=ExecutionRecoveryWorker(db,exchange).run_once(now=1000)
        row=db.conn.execute("SELECT status,last_reconcile_error FROM execution_intents WHERE intent_id=?",(intent.intent_id,)).fetchone()
        assert result["deferred"]==1 and exchange.calls==0 and row["status"]=="AMBIGUOUS"
        assert "no persisted orderId or clientOrderId" in row["last_reconcile_error"]

def test_competing_workers_only_claim_an_intent_once(tmp_path):
    path=tmp_path/"lease.sqlite3"
    with Database(str(path)) as db: _intent(db)
    first_db,second_db=Database(str(path)),Database(str(path))
    exchange=FakeExchange(_order("FILLED",executedQty="0.01"),delay=0.05)
    first=ExecutionRecoveryWorker(first_db,exchange,owner="worker-a")
    second=ExecutionRecoveryWorker(second_db,exchange,owner="worker-b")
    try:
        with ThreadPoolExecutor(max_workers=2) as pool:
            results=list(pool.map(lambda w:w.run_once(now=time.time()+10),[first,second]))
        assert sum(r["claimed"] for r in results)==1
        assert sum(r["reconciled"] for r in results)==1 and exchange.calls==1
        assert not first_db.has_unresolved_execution_intents()
    finally:
        first_db.close(); second_db.close()

def test_unexpected_api_payload_does_not_create_false_success(tmp_path):
    with Database(str(tmp_path/"bad-response.sqlite3")) as db:
        intent=_intent(db); exchange=FakeExchange({"symbol":"BTCUSDT","side":"BUY","status":"MAGIC"})
        result=ExecutionRecoveryWorker(db,exchange).run_once(now=1000)
        row=db.conn.execute("SELECT status,last_reconcile_error FROM execution_intents WHERE intent_id=?",(intent.intent_id,)).fetchone()
        assert result["deferred"]==1 and row["status"]=="AMBIGUOUS"
        assert "unknown or missing status" in row["last_reconcile_error"]

def test_completed_intent_is_idempotent_and_not_queried_again(tmp_path):
    with Database(str(tmp_path/"idempotent.sqlite3")) as db:
        intent=_intent(db); exchange=FakeExchange(_order("FILLED",executedQty="0.01"))
        worker=ExecutionRecoveryWorker(db,exchange)
        assert worker.run_once(now=time.time()+10)["reconciled"]==1
        assert worker.run_once(now=time.time()+20)["claimed"]==0 and exchange.calls==1

def test_failure_to_persist_reconciliation_does_not_claim_success(tmp_path,monkeypatch):
    with Database(str(tmp_path/"write-failure.sqlite3")) as db:
        intent=_intent(db); exchange=FakeExchange(_order("FILLED",executedQty="0.01"))
        original=db.complete_execution_intent_recovery
        def fail_once(*args,**kwargs):
            db.complete_execution_intent_recovery=original
            raise OSError("injected reconciliation write failure")
        monkeypatch.setattr(db,"complete_execution_intent_recovery",fail_once)
        result=ExecutionRecoveryWorker(db,exchange).run_once(now=1000)
        row=db.conn.execute("SELECT status,last_reconcile_error FROM execution_intents WHERE intent_id=?",(intent.intent_id,)).fetchone()
        assert result["deferred"]==1 and row["status"]=="AMBIGUOUS"
        assert "OSError" in row["last_reconcile_error"]


class FakeOcoExchange:
    def __init__(self, listing, legs):
        self.listing, self.legs = listing, legs
        self.list_calls = self.order_calls = 0

    def order_list(self, symbol, *, list_client_order_id=None):
        self.list_calls += 1
        return dict(self.listing)

    def get_order(self, symbol, *, order_id=None, orig_client_order_id=None):
        self.order_calls += 1
        return dict(self.legs[str(order_id)])
    def my_trades(self, symbol, order_id=None, limit=1000):
        leg = self.legs[str(order_id)]
        qty = float(leg.get("executedQty", 0) or 0)
        if qty <= 0: return []
        price = float(leg.get("price", 0) or 100)
        return [{"orderId": order_id, "qty": str(qty), "price": str(price), "quoteQty": str(qty * price)}]


def test_worker_reconciles_oco_list_and_both_legs(tmp_path):
    with Database(str(tmp_path / "oco.sqlite3")) as db:
        intent = OrderIntent.new("BTCUSDT", "SELL", "OCO", {},
            purpose="EXIT", client_order_id="williams-oco-list")
        db.save_execution_intent(intent, "AMBIGUOUS", "lost OCO response")
        listing = {"symbol":"BTCUSDT","orderListId":77,"listClientOrderId":"williams-oco-list",
            "listOrderStatus":"EXECUTING","orders":[
                {"orderId":201,"clientOrderId":"tp-leg"},{"orderId":202,"clientOrderId":"sl-leg"}]}
        legs = {
            "201":{"symbol":"BTCUSDT","side":"SELL","orderId":201,"clientOrderId":"tp-leg","status":"NEW","origQty":"0.01","executedQty":"0","price":"100"},
            "202":{"symbol":"BTCUSDT","side":"SELL","orderId":202,"clientOrderId":"sl-leg","status":"NEW","origQty":"0.01","executedQty":"0","price":"90"},
        }
        exchange = FakeOcoExchange(listing, legs)
        result = ExecutionRecoveryWorker(db, exchange).run_once(now=time.time()+10)
        row = db.conn.execute("SELECT * FROM execution_intents WHERE intent_id=?", (intent.intent_id,)).fetchone()
        assert result["active"] == 1 and row["status"] == "AMBIGUOUS"
        assert row["order_list_id"] == "77" and row["orig_qty"] == pytest.approx(0.01)
        assert exchange.list_calls == 1 and exchange.order_calls == 2
        assert db.has_unresolved_execution_intents()
        assert row["exchange_status"] == "EXECUTING"
        assert row["next_reconcile_at"] > time.time()


def test_worker_does_not_resolve_oco_when_one_leg_is_missing(tmp_path):
    with Database(str(tmp_path / "oco-missing-leg.sqlite3")) as db:
        intent = OrderIntent.new("BTCUSDT", "SELL", "OCO", {},
            purpose="EXIT", client_order_id="williams-oco-list")
        db.save_execution_intent(intent, "AMBIGUOUS", "lost OCO response")
        listing = {"orderListId":77,"listClientOrderId":"williams-oco-list",
            "listOrderStatus":"EXECUTING","orders":[{"orderId":201,"clientOrderId":"tp-leg"}]}
        exchange = FakeOcoExchange(listing, {"201":{"symbol":"BTCUSDT","side":"SELL","orderId":201,
            "clientOrderId":"tp-leg","status":"NEW","origQty":"0.01","executedQty":"0","price":"100"}})
        result = ExecutionRecoveryWorker(db, exchange).run_once(now=1000)
        row = db.conn.execute("SELECT status,last_reconcile_error FROM execution_intents WHERE intent_id=?",(intent.intent_id,)).fetchone()
        assert result["deferred"] == 1 and row["status"] == "AMBIGUOUS"
        assert "both authoritative order legs" in row["last_reconcile_error"]


def test_trade_history_failure_keeps_filled_order_ambiguous(tmp_path):
    with Database(str(tmp_path / "trade-history-failure.sqlite3")) as db:
        intent = _intent(db)
        exchange = FakeExchange(_order("FILLED", executedQty="0.01"))
        exchange.trade_error = RuntimeError("simulated signed-read failure")
        result = ExecutionRecoveryWorker(db, exchange).run_once(now=1000)
        row = db.conn.execute("SELECT status,last_reconcile_error FROM execution_intents WHERE intent_id=?",(intent.intent_id,)).fetchone()
        assert result["deferred"] == 1 and row["status"] == "AMBIGUOUS"
        assert "RuntimeError" in row["last_reconcile_error"]
        assert exchange.trade_calls == 1 and db.has_unresolved_execution_intents()


def test_trade_history_quantity_mismatch_keeps_intent_ambiguous(tmp_path):
    with Database(str(tmp_path / "trade-history-mismatch.sqlite3")) as db:
        intent = _intent(db)
        exchange = FakeExchange(_order("FILLED", executedQty="0.01"))
        exchange.trade_rows = [{"orderId":123,"qty":"0.005","price":"100","quoteQty":"0.5"}]
        result = ExecutionRecoveryWorker(db, exchange).run_once(now=1000)
        row = db.conn.execute("SELECT status,last_reconcile_error FROM execution_intents WHERE intent_id=?",(intent.intent_id,)).fetchone()
        assert result["deferred"] == 1 and row["status"] == "AMBIGUOUS"
        assert "does not reconcile" in row["last_reconcile_error"]


def test_cancel_intent_stays_unresolved_while_exchange_order_is_active(tmp_path):
    with Database(str(tmp_path / "cancel-active.sqlite3")) as db:
        intent = OrderIntent.new("BTCUSDT", "BUY", "CANCEL", {},
            purpose="EXIT", client_order_id="williams-test-intent")
        db.save_execution_intent(intent, "AMBIGUOUS", "cancel response lost")
        exchange = FakeExchange(_order("NEW"))
        result = ExecutionRecoveryWorker(db, exchange).run_once(now=1000)
        row = db.conn.execute("SELECT status,last_reconcile_error FROM execution_intents WHERE intent_id=?",(intent.intent_id,)).fetchone()
        assert result["deferred"] == 1 and row["status"] == "AMBIGUOUS"
        assert "cancel outcome remains unresolved" in row["last_reconcile_error"]


def test_oco_all_done_conflicting_with_active_legs_remains_ambiguous(tmp_path):
    with Database(str(tmp_path / "oco-conflict.sqlite3")) as db:
        intent = OrderIntent.new("BTCUSDT", "SELL", "OCO", {},
            purpose="EXIT", client_order_id="williams-oco-list")
        db.save_execution_intent(intent, "AMBIGUOUS", "lost OCO response")
        listing = {"orderListId":77,"listClientOrderId":"williams-oco-list",
            "listOrderStatus":"ALL_DONE","orders":[{"orderId":201,"clientOrderId":"tp-leg"},{"orderId":202,"clientOrderId":"sl-leg"}]}
        legs = {
            "201":{"symbol":"BTCUSDT","side":"SELL","orderId":201,"clientOrderId":"tp-leg","status":"NEW","origQty":"0.01","executedQty":"0","price":"100"},
            "202":{"symbol":"BTCUSDT","side":"SELL","orderId":202,"clientOrderId":"sl-leg","status":"NEW","origQty":"0.01","executedQty":"0","price":"90"},
        }
        exchange = FakeOcoExchange(listing, legs)
        result = ExecutionRecoveryWorker(db, exchange).run_once(now=1000)
        row = db.conn.execute("SELECT status,last_reconcile_error FROM execution_intents WHERE intent_id=?",(intent.intent_id,)).fetchone()
        assert result["deferred"] == 1 and row["status"] == "AMBIGUOUS"
        assert "ALL_DONE but a leg is not terminal" in row["last_reconcile_error"]


def test_non_cancel_intent_gets_a_stable_recovery_client_id():
    intent = OrderIntent.new("BTCUSDT", "BUY", "MARKET", {}, purpose="ENTRY")
    assert intent.client_order_id.startswith("WILLV4_")
    assert len(intent.client_order_id) <= 36


def test_worker_prefers_persisted_order_id_when_available(tmp_path):
    with Database(str(tmp_path / "order-id-lookup.sqlite3")) as db:
        intent = _intent(db)
        db.save_execution_intent(intent, "AMBIGUOUS", "lost response",
            exchange_order={"orderId": 123, "status": "NEW"})
        exchange = FakeExchange(_order("FILLED", executedQty="0.01"))
        result = ExecutionRecoveryWorker(db, exchange).run_once(now=1000)
        assert result["reconciled"] == 1
        assert str(exchange.last_lookup["order_id"]) == "123"
        assert exchange.last_lookup["orig_client_order_id"] is None


def test_cancel_intent_does_not_fabricate_target_order_id():
    intent = OrderIntent.new("BTCUSDT", "BUY", "CANCEL", {}, purpose="EXIT")
    assert intent.client_order_id == ""
