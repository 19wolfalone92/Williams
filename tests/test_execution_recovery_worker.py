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
        self.calls, self.lock = 0, threading.Lock()
    def get_order(self, symbol, *, order_id=None, orig_client_order_id=None):
        with self.lock: self.calls += 1
        if self.delay: time.sleep(self.delay)
        if self.error: raise self.error
        return dict(self.response)

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

@pytest.mark.parametrize(("status","executed","expected"),[
    ("NEW","0","NEW"),("PARTIALLY_FILLED","0.004","PARTIALLY_FILLED"),
    ("FILLED","0.01","FILLED"),("CANCELED","0.004","CANCELED"),("EXPIRED","0","EXPIRED")])
def test_worker_persists_exact_exchange_lifecycle(tmp_path,status,executed,expected):
    with Database(str(tmp_path/"lifecycle.sqlite3")) as db:
        intent=_intent(db); exchange=FakeExchange(_order(status,executedQty=executed))
        result=ExecutionRecoveryWorker(db,exchange).run_once(now=time.time()+10)
        row=db.conn.execute("SELECT * FROM execution_intents WHERE intent_id=?",(intent.intent_id,)).fetchone()
        assert result["reconciled"]==1 and row["status"]==expected
        assert row["exchange_status"]==expected and row["executed_qty"]==pytest.approx(float(executed))
        assert (row["resolved_at"] is not None) is (status in {"FILLED","CANCELED","EXPIRED"})

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
        assert "injected reconciliation write failure" in row["last_reconcile_error"]
