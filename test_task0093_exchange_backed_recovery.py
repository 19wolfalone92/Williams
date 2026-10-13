"""TASK 009.3 offline fault injection for exchange-backed recovery.

All exchange responses are synthetic. This module never creates a real order or
uses API credentials. Database effects are asserted after restarts/concurrency.
"""
from concurrent.futures import ThreadPoolExecutor
import json
import sqlite3
import threading

import pytest

from db import Database
from execution_barrier import OrderIntent
from execution_recovery import ExecutionRecoveryCoordinator


SCOPE = "spot"
ACCOUNT = "task0093-test-account"


class OfflineExchange:
    def __init__(self):
        self.orders = {}
        self.trades = []
        self.available = True
        self.history_override = None
        self.submit_calls = 0
        self.lock = threading.RLock()

    def add_order(self, client_id, *, order_id="101", status="FILLED",
                  qty="0.01", quote="1000", executed="0.01", symbol="BTCUSDT", side="BUY"):
        row = {
            "symbol": symbol,
            "orderId": str(order_id),
            "clientOrderId": client_id,
            "side": side,
            "type": "STOP_LOSS",
            "status": status,
            "origQty": str(qty),
            "executedQty": str(executed),
            "cummulativeQuoteQty": str(quote),
            "transactTime": 1770000000000,
        }
        self.orders[client_id] = row
        return row

    def add_trade(self, order_id="101", *, trade_id="9001", qty="0.01",
                  price="100000", commission="0.001", commission_asset="USDT",
                  symbol="BTCUSDT", is_buyer=True):
        row = {
            "symbol": symbol,
            "id": trade_id,
            "orderId": str(order_id),
            "qty": str(qty),
            "price": str(price),
            "commission": str(commission),
            "commissionAsset": commission_asset,
            "isBuyer": is_buyer,
            "time": 1770000000000,
        }
        self.trades.append(row)
        return row

    def get_order(self, symbol, order_id=None, orig_client_order_id=None):
        if not self.available:
            raise ConnectionError("injected exchange unavailable")
        with self.lock:
            rows = list(self.orders.values())
            for row in rows:
                if str(row["symbol"]).upper() != str(symbol).upper():
                    continue
                if order_id is not None and str(row["orderId"]) == str(order_id):
                    return dict(row)
                if orig_client_order_id is not None and row["clientOrderId"] == orig_client_order_id:
                    return dict(row)
        raise LookupError("order not found in current Binance order lookup")

    def my_trades(self, symbol, order_id=None, limit=1000):
        if not self.available:
            raise ConnectionError("injected trade history unavailable")
        if self.history_override is not None:
            return self.history_override
        return [
            dict(row) for row in self.trades
            if str(row["symbol"]).upper() == str(symbol).upper()
            and (order_id is None or str(row["orderId"]) == str(order_id))
        ][:limit]

    def exchange_info(self, symbol=None):
        rows = [{
            "symbol": "BTCUSDT", "baseAsset": "BTC", "quoteAsset": "USDT",
            "filters": [],
        }]
        if symbol:
            rows = [row for row in rows if row["symbol"] == str(symbol).upper()]
        return {"symbols": rows}

    def order_safe(self, *args, **kwargs):
        self.submit_calls += 1
        raise AssertionError("recovery must never re-submit an order")


def make_db(path):
    db = Database(str(path))
    db.conn.execute(
        """CREATE TABLE IF NOT EXISTS recovery_effects(
            trade_id TEXT PRIMARY KEY,
            quantity TEXT NOT NULL,
            fee TEXT NOT NULL,
            pnl TEXT NOT NULL
        )"""
    )
    db.conn.commit()
    return db


def make_intent(db, client_id, *, status="UNKNOWN", scope=SCOPE, account=ACCOUNT):
    item = OrderIntent.new(
        "BTCUSDT", "BUY", "STOP_LOSS", {},
        purpose="CAMPAIGN_ENTRY",
        execution_scope=scope,
        client_order_id=client_id,
        campaign_id="campaign-" + client_id,
        signal_id="BTCUSDT:1h:ENTRY:1",
        quantity="0.01",
    )
    assert db.claim_execution_intent(item, scope=scope, account_scope=account)["claimed"]
    db.save_execution_intent(item, "SUBMITTING")
    if status == "UNKNOWN":
        db.mark_execution_unknown(item, "fault-injection: possibly sent", scope=scope, account_scope=account)
    else:
        db.save_execution_intent(item, status)
    return item


def effect(db, intent, order, fill):
    if db.state_get("fail_effect", "0") == "1":
        raise RuntimeError("injected failure after unique fill registration")
    db.conn.execute(
        "INSERT INTO recovery_effects(trade_id,quantity,fee,pnl) VALUES(?,?,?,?)",
        (str(fill["trade_id"]), str(fill["quantity"]), str(fill["commission"]), "0"),
    )


def coordinator(client, db, account=ACCOUNT, scope=SCOPE):
    return ExecutionRecoveryCoordinator(
        client, db,
        fill_handler=lambda intent, order, fill: effect(db, intent, order, fill),
        account_scope=account, execution_scope=scope
    )


def set_filled(client, client_id, *, order_id="101", status="FILLED",
               qty="0.01", quote="1000", executed="0.01", trade_id="9001",
               price="100000", commission="0.001", commission_asset="USDT"):
    client.add_order(
        client_id, order_id=order_id, status=status, qty=qty,
        quote=quote, executed=executed,
    )
    if float(executed) > 0:
        client.add_trade(
            order_id, trade_id=trade_id, qty=executed, price=price,
            commission=commission, commission_asset=commission_asset,
        )


def effects(db):
    rows = db.conn.execute(
        "SELECT trade_id,quantity,fee,pnl FROM recovery_effects ORDER BY trade_id"
    ).fetchall()
    return [dict(row) for row in rows]


def test_01_restart_with_one_unresolved_intent_reconciles_from_exchange(tmp_path):
    path = tmp_path / "single.sqlite3"
    db = make_db(path)
    item = make_intent(db, "TASK0093_SINGLE")
    client = OfflineExchange()
    set_filled(client, item.client_order_id)
    db.conn.close()

    restarted = make_db(path)
    result = coordinator(client, restarted).recover()
    assert result[0]["state"] == "FILLED"
    assert restarted.execution_readiness(SCOPE, ACCOUNT)["ready"] is True
    assert len(effects(restarted)) == 1
    assert restarted.conn.execute(
        "SELECT status,order_id FROM execution_intents WHERE intent_id=?",
        (item.intent_id,),
    ).fetchone()["order_id"] == "101"


def test_02_restart_with_multiple_unresolved_intents_does_not_unlock_early(tmp_path):
    db = make_db(tmp_path / "multiple.sqlite3")
    first = make_intent(db, "TASK0093_MULTI_A")
    second = OrderIntent.new(
        "BTCUSDT", "BUY", "STOP_LOSS", {}, purpose="CAMPAIGN_ENTRY",
        client_order_id="TASK0093_MULTI_B", campaign_id="campaign-B",
    )
    # Simulate an older database containing multiple independently persisted intents.
    db.conn.execute(
        """INSERT INTO execution_intents(
            intent_id,symbol,side,order_type,purpose,required_context_versions_json,
            status,reason,client_order_id,exchange,account_scope,execution_scope,campaign_id
        ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        (second.intent_id, "BTCUSDT", "BUY", "STOP_LOSS", "CAMPAIGN_ENTRY", "{}",
         "UNKNOWN", "legacy concurrent intent", second.client_order_id, "BINANCE",
         ACCOUNT, SCOPE, "campaign-B"),
    )
    db.conn.commit()
    client = OfflineExchange()
    set_filled(client, first.client_order_id, order_id="101", trade_id="9101")
    set_filled(client, second.client_order_id, order_id="102", trade_id="9102")
    results = coordinator(client, db).recover()
    assert {row["intent_id"] for row in results} == {first.intent_id, second.intent_id}
    assert all(row["state"] == "FILLED" for row in results)
    assert db.execution_readiness(SCOPE, ACCOUNT)["ready"] is True
    assert len(effects(db)) == 2


def test_03_binance_unavailable_during_recovery_keeps_lock(tmp_path):
    db = make_db(tmp_path / "unavailable.sqlite3")
    item = make_intent(db, "TASK0093_OFFLINE")
    client = OfflineExchange()
    client.available = False
    result = coordinator(client, db).recover()
    assert result[0]["state"] == "RECONCILE_REQUIRED"
    assert db.execution_readiness(SCOPE, ACCOUNT)["ready"] is False
    assert db.list_unresolved_execution_intents(SCOPE, ACCOUNT)[0]["status"] == "RECONCILE_REQUIRED"


def test_04_exchange_order_found_by_client_id_when_local_order_result_missing(tmp_path):
    db = make_db(tmp_path / "lost-result.sqlite3")
    item = make_intent(db, "TASK0093_LOST_RESULT")
    client = OfflineExchange()
    set_filled(client, item.client_order_id, order_id="1234")
    row = db.conn.execute(
        "SELECT order_id FROM execution_intents WHERE intent_id=?", (item.intent_id,)
    ).fetchone()
    assert row["order_id"] is None
    result = coordinator(client, db).recover()
    assert result[0]["state"] == "FILLED"
    assert db.conn.execute(
        "SELECT order_id FROM execution_intents WHERE intent_id=?", (item.intent_id,)
    ).fetchone()["order_id"] == "1234"


def test_05_order_missing_or_trade_history_incomplete_never_means_not_sent(tmp_path):
    db = make_db(tmp_path / "incomplete-history.sqlite3")
    item = make_intent(db, "TASK0093_HISTORY_GAP")
    client = OfflineExchange()
    client.add_order(item.client_order_id, status="FILLED", qty="0.02", executed="0.02", quote="2000")
    client.add_trade("101", trade_id="9201", qty="0.01", price="100000")
    result = coordinator(client, db).recover()
    assert result[0]["state"] == "RECONCILE_REQUIRED"
    assert db.execution_readiness(SCOPE, ACCOUNT)["ready"] is False
    assert effects(db) == []


def test_06_full_fill_before_crash_is_applied_once_after_restart(tmp_path):
    path = tmp_path / "filled-before-crash.sqlite3"
    db = make_db(path)
    item = make_intent(db, "TASK0093_FILLED_CRASH")
    client = OfflineExchange()
    set_filled(client, item.client_order_id)
    db.conn.close()
    restarted = make_db(path)
    coordinator(client, restarted).recover()
    assert len(effects(restarted)) == 1
    assert restarted.conn.execute("SELECT COUNT(*) FROM execution_fills").fetchone()[0] == 1


def test_07_late_fill_after_cancel_is_discovered_and_deduplicated_on_restart(tmp_path):
    db = make_db(tmp_path / "late-fill.sqlite3")
    item = make_intent(db, "TASK0093_LATE", status="CANCELED")
    client = OfflineExchange()
    # Intent was previously canceled without fills; Binance later reports an execution.
    set_filled(client, item.client_order_id, status="CANCELED", qty="0.02",
               executed="0.01", quote="1000", trade_id="9301")
    result = coordinator(client, db).recover()
    assert result[0]["fills_seen"] == 1
    assert len(effects(db)) == 1
    # Repeated monitor passes must not reapply a known trade.
    coordinator(client, db).recover()
    assert len(effects(db)) == 1


def test_08_duplicate_exchange_fill_delivery_does_not_repeat_effects(tmp_path):
    db = make_db(tmp_path / "duplicate-fill.sqlite3")
    item = make_intent(db, "TASK0093_DUP")
    client = OfflineExchange()
    set_filled(client, item.client_order_id)
    client.trades.append(dict(client.trades[0]))
    result = coordinator(client, db).recover()
    assert result[0]["state"] == "RECONCILE_REQUIRED"
    assert len(effects(db)) == 0


def test_09_two_concurrent_handlers_for_same_fill_apply_once(tmp_path):
    path = tmp_path / "concurrent-fill.sqlite3"
    db1 = make_db(path)
    db2 = make_db(path)
    fill = {
        "exchange": "BINANCE", "account_scope": ACCOUNT, "symbol": "BTCUSDT",
        "trade_id": "9401", "order_id": "101", "intent_id": "intent",
        "quantity": "0.01", "price": "100000", "commission": "0.001",
    }
    def apply(db):
        return db.apply_execution_fill(fill, lambda conn, data: conn.conn.execute(
            "INSERT INTO recovery_effects(trade_id,quantity,fee,pnl) VALUES(?,?,?,?)",
            (data["trade_id"], data["quantity"], data["commission"], "0"),
        ))
    with ThreadPoolExecutor(max_workers=2) as pool:
        result = list(pool.map(apply, [db1, db2]))
    assert sorted(result) == [False, True]
    assert len(effects(db1)) == 1
    assert db1.conn.execute("SELECT COUNT(*) FROM execution_fills").fetchone()[0] == 1


def test_10_failure_after_fill_registration_rolls_back_registry_and_effect(tmp_path):
    db = make_db(tmp_path / "effect-fail.sqlite3")
    db.state_set("fail_effect", "1")
    fill = {"exchange": "BINANCE", "account_scope": ACCOUNT, "symbol": "BTCUSDT",
            "trade_id": "9501", "quantity": "0.01", "price": "100000"}
    with pytest.raises(RuntimeError):
        db.apply_execution_fill(fill, lambda connection, data: effect(db, {}, {}, data))
    assert db.conn.execute("SELECT COUNT(*) FROM execution_fills").fetchone()[0] == 0
    assert effects(db) == []


def test_11_financial_effects_survive_restart_without_double_fee_or_pnl(tmp_path):
    path = tmp_path / "restart-effect.sqlite3"
    db = make_db(path)
    fill = {"exchange": "BINANCE", "account_scope": ACCOUNT, "symbol": "BTCUSDT",
            "trade_id": "9601", "quantity": "0.01", "price": "100000",
            "commission": "1.5"}
    assert db.apply_execution_fill(fill, lambda conn, data: conn.conn.execute(
        "INSERT INTO recovery_effects(trade_id,quantity,fee,pnl) VALUES(?,?,?,?)",
        (data["trade_id"], data["quantity"], data["commission"], "0"),
    ))
    db.conn.close()
    restarted = make_db(path)
    assert restarted.apply_execution_fill(fill, lambda conn, data: pytest.fail("duplicate callback ran")) is False
    rows = effects(restarted)
    assert len(rows) == 1 and rows[0]["fee"] == "1.5" and rows[0]["pnl"] == "0"


def test_12_cancel_fill_race_mismatch_is_ambiguous_and_locks_scope(tmp_path):
    db = make_db(tmp_path / "cancel-fill-race.sqlite3")
    item = make_intent(db, "TASK0093_CANCEL_RACE")
    client = OfflineExchange()
    client.add_order(item.client_order_id, status="CANCELED", qty="0.02", executed="0.02", quote="2000")
    client.add_trade("101", trade_id="9701", qty="0.01", price="100000")
    result = coordinator(client, db).recover()
    assert result[0]["state"] == "RECONCILE_REQUIRED"
    assert db.execution_readiness(SCOPE, ACCOUNT)["ready"] is False


def test_13_lost_submit_response_reconciles_by_client_id_without_resubmit(tmp_path):
    db = make_db(tmp_path / "lost-response.sqlite3")
    item = make_intent(db, "TASK0093_LOST_RESPONSE")
    client = OfflineExchange()
    set_filled(client, item.client_order_id)
    result = coordinator(client, db).recover()
    assert result[0]["state"] == "FILLED"
    assert client.submit_calls == 0


def test_14_failure_persisting_verified_reconciliation_keeps_scope_locked(tmp_path):
    db = make_db(tmp_path / "result-write-fail.sqlite3")
    item = make_intent(db, "TASK0093_RESULT_WRITE_FAIL")
    client = OfflineExchange()
    set_filled(client, item.client_order_id)
    db.conn.execute("""
        CREATE TRIGGER fail_verified_evidence
        BEFORE INSERT ON execution_events
        WHEN NEW.event='RECONCILIATION_VERIFIED'
        BEGIN SELECT RAISE(ABORT, 'injected reconciliation evidence failure'); END
    """)
    db.conn.commit()
    result = coordinator(client, db).recover()
    assert result[0]["state"] == "RECONCILE_REQUIRED"
    assert db.execution_readiness(SCOPE, ACCOUNT)["ready"] is False
    assert db.conn.execute(
        "SELECT status FROM execution_intents WHERE intent_id=?", (item.intent_id,)
    ).fetchone()["status"] == "RECONCILE_REQUIRED"


def test_15_failure_persisting_recovery_log_never_unlocks(tmp_path):
    db = make_db(tmp_path / "recovery-log-fail.sqlite3")
    item = make_intent(db, "TASK0093_RECOVERY_LOG_FAIL")
    client = OfflineExchange()
    client.available = False
    db.conn.execute("""
        CREATE TRIGGER fail_recovery_logs
        BEFORE INSERT ON execution_events
        WHEN NEW.event='RECOVERY_BLOCKED'
        BEGIN SELECT RAISE(ABORT, 'injected recovery log failure'); END
    """)
    db.conn.commit()
    result = coordinator(client, db).recover()
    assert result[0]["state"] == "RECONCILE_REQUIRED"
    assert db.execution_readiness(SCOPE, ACCOUNT)["ready"] is False


def test_16_arbitrary_caller_cannot_clear_unknown_with_forged_evidence(tmp_path):
    db = make_db(tmp_path / "forged-evidence.sqlite3")
    item = make_intent(db, "TASK0093_FORGED")
    with pytest.raises(PermissionError):
        db.resolve_execution_intent(item.intent_id, {"status": "FILLED", "evidence_id": "fake"})
    assert db.execution_readiness(SCOPE, ACCOUNT)["ready"] is False


def test_17_repeated_recovery_is_idempotent_for_canceled_partial_order(tmp_path):
    db = make_db(tmp_path / "repeat-recovery.sqlite3")
    item = make_intent(db, "TASK0093_REPEAT", status="CANCELED")
    client = OfflineExchange()
    set_filled(client, item.client_order_id, status="CANCELED", executed="0.01",
               qty="0.02", quote="1000", trade_id="9801")
    first = coordinator(client, db).recover()
    second = coordinator(client, db).recover()
    assert first[0]["fills_seen"] == 1 and second[0]["fills_seen"] == 1
    assert len(effects(db)) == 1


def test_18_recovery_never_sends_a_second_order(tmp_path):
    db = make_db(tmp_path / "no-resubmit.sqlite3")
    item = make_intent(db, "TASK0093_NO_RESUBMIT")
    client = OfflineExchange()
    set_filled(client, item.client_order_id)
    coordinator(client, db).recover()
    coordinator(client, db).recover()
    assert client.submit_calls == 0


def test_19_commission_and_pnl_effect_rows_are_not_duplicated(tmp_path):
    db = make_db(tmp_path / "fee-pnl.sqlite3")
    item = make_intent(db, "TASK0093_FEE_PNL", status="CANCELED")
    client = OfflineExchange()
    set_filled(client, item.client_order_id, status="CANCELED", trade_id="9901",
               commission="2.25", commission_asset="USDT")
    recovery = coordinator(client, db)
    recovery.recover()
    recovery.recover()
    rows = effects(db)
    assert len(rows) == 1
    assert sum(float(x["fee"]) for x in rows) == pytest.approx(2.25)
    assert sum(float(x["pnl"]) for x in rows) == pytest.approx(0.0)


def test_20_new_execution_claim_is_blocked_until_exchange_reconciliation(tmp_path):
    db = make_db(tmp_path / "gate-before-recovery.sqlite3")
    unresolved = make_intent(db, "TASK0093_GATE")
    candidate = OrderIntent.new(
        "BTCUSDT", "BUY", "STOP_LOSS", {}, purpose="CAMPAIGN_ENTRY",
        client_order_id="TASK0093_MUST_NOT_SEND",
    )
    claim = db.claim_execution_intent(candidate, scope=SCOPE, account_scope=ACCOUNT)
    assert claim["claimed"] is False
    assert claim["reason"] in {"UNRESOLVED_INTENT", "EXECUTION_LOCKED"}
    assert db.execution_readiness(SCOPE, ACCOUNT)["ready"] is False
