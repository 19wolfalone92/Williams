"""Fail-closed recovery persistence contract tests for TASK 007."""
import os
import sqlite3
import tempfile

import pytest

from db import Database
from mock_exchange import MockExchange
from portfolio_trader import MultiPositionTrader
from trader import Trader
from execution_barrier import ExecutionBarrier, ExecutionPersistenceError, OrderIntent
from market_context import ContextCache


def evidence(**overrides):
    data = {
        "exchange_reachable": True,
        "account_checked": True,
        "open_orders_checked": True,
        "order_history_checked": True,
        "fills_checked": True,
        "positions_checked": True,
        "protection_checked": True,
        "campaigns_checked": True,
        "intents_checked": True,
        "unresolved_symbols": [],
        "pending_entries": [],
        "unknown_orders": [],
        "inventory_mismatches": [],
        "unprotected_positions": [],
        "unknown_campaigns": [],
        "canonical_position_state": "FLAT",
    }
    data.update(overrides)
    return data


def test_new_database_starts_reconciliation_required_not_flat():
    with tempfile.TemporaryDirectory() as tmp:
        db = Database(os.path.join(tmp, "state.sqlite3"))
        assert db.state_get("recovery_state") == "REQUIRED"
        assert db.state_get("position_state") == "RECONCILE_REQUIRED"


def test_recovery_cannot_unlock_without_exchange_derived_position_state():
    with tempfile.TemporaryDirectory() as tmp:
        db = Database(os.path.join(tmp, "state.sqlite3"))
        payload = evidence()
        del payload["canonical_position_state"]
        with pytest.raises(RuntimeError, match="not evidence of FLAT"):
            db.mark_recovery_ready(payload)
        assert db.state_get("recovery_state") == "REQUIRED"


def test_recovery_cannot_unlock_when_any_reconciliation_dimension_is_unresolved():
    with tempfile.TemporaryDirectory() as tmp:
        db = Database(os.path.join(tmp, "state.sqlite3"))
        payload = evidence(unknown_orders=["BTCUSDT:client-id-unknown"])
        with pytest.raises(RuntimeError, match="unresolved"):
            db.mark_recovery_ready(payload)
        assert db.state_get("recovery_state") == "REQUIRED"


def test_recovery_unlock_requires_all_checks_and_persists_canonical_state():
    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "state.sqlite3")
        db = Database(path)
        payload = evidence(canonical_position_state="OPEN")
        db.mark_recovery_ready(payload)
        assert db.state_get("recovery_state") == "READY"
        assert db.state_get("position_state") == "OPEN"
        db2 = Database(path)
        # Every new process must re-enter recovery, even if the previous process
        # had a fully reconciled snapshot.
        assert db2.state_get("recovery_state") == "REQUIRED"
        assert db2.state_get("position_state") == "RECONCILE_REQUIRED"


def test_v7_database_migrates_target_order_id_and_migration_is_repeatable(tmp_path):
    path = str(tmp_path / "schema-v7.sqlite3")
    db = Database(path)
    db.conn.close()

    legacy = sqlite3.connect(path)
    legacy.execute("ALTER TABLE execution_intents DROP COLUMN target_order_id")
    legacy.execute(
        "UPDATE bot_state SET value='7' WHERE key='schema_version'"
    )
    legacy.commit()
    legacy.close()

    migrated = Database(path)
    columns = {
        row["name"]
        for row in migrated.conn.execute("PRAGMA table_info(execution_intents)").fetchall()
    }
    assert "target_order_id" in columns
    assert int(migrated.state_get("schema_version")) == Database.SCHEMA_VERSION
    migrated.conn.close()

    reopened = Database(path)
    columns_after_repeat = {
        row["name"]
        for row in reopened.conn.execute("PRAGMA table_info(execution_intents)").fetchall()
    }
    assert "target_order_id" in columns_after_repeat
    assert int(reopened.state_get("schema_version")) == Database.SCHEMA_VERSION
    reopened.conn.close()


def test_future_schema_version_is_not_downgraded():
    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "state.sqlite3")
        db = Database(path)
        db.state_set("schema_version", Database.SCHEMA_VERSION + 100)
        db.conn.close()
        with pytest.raises(RuntimeError, match="newer than supported"):
            Database(path)


def test_repeated_fill_identity_is_idempotent_and_conflicts_fail_closed():
    with tempfile.TemporaryDirectory() as tmp:
        db = Database(os.path.join(tmp, "state.sqlite3"))
        fill = {
            "campaign_id": "campaign-1",
            "order_id": "123",
            "exchange_trade_id": "trade-55",
            "symbol": "BTCUSDT",
            "side": "BUY",
            "quantity": 0.25,
            "price": 100.0,
            "quote_quantity": 25.0,
        }
        assert db.save_campaign_fill(fill) is True
        assert db.save_campaign_fill(fill) is False
        assert db.conn.execute(
            "SELECT COUNT(*) FROM campaign_fills WHERE symbol='BTCUSDT' AND exchange_trade_id='trade-55'"
        ).fetchone()[0] == 1
        conflicting = dict(fill, quantity=0.5)
        with pytest.raises(RuntimeError, match="conflicts with persisted fill"):
            db.save_campaign_fill(conflicting)
        conflicting_fee = dict(fill, fee_quote=0.25)
        with pytest.raises(RuntimeError, match="conflicts with persisted fill"):
            db.save_campaign_fill(conflicting_fee)


def test_campaign_order_identity_cannot_be_rebound_to_another_client_id():
    with tempfile.TemporaryDirectory() as tmp:
        db = Database(os.path.join(tmp, "state.sqlite3"))
        base = {
            "campaign_id": "campaign-1",
            "symbol": "BTCUSDT",
            "side": "BUY",
            "purpose": "ENTRY",
            "order_type": "STOP_LOSS",
            "order_id": "900",
            "client_order_id": "client-a",
            "status": "NEW",
            "quantity": 1.0,
        }
        assert db.save_campaign_order(base) is True
        with pytest.raises(RuntimeError, match="different clientOrderId"):
            db.save_campaign_order(dict(base, client_order_id="client-b"))


def test_fill_without_stable_exchange_identity_is_rejected():
    with tempfile.TemporaryDirectory() as tmp:
        db = Database(os.path.join(tmp, "state.sqlite3"))
        with pytest.raises(ValueError, match="stable Binance trade"):
            db.save_campaign_fill({
                "campaign_id": "campaign-1",
                "order_id": "123",
                "symbol": "BTCUSDT",
                "side": "BUY",
                "quantity": 1.0,
                "price": 100.0,
            })


def test_order_identity_conflict_is_not_silently_overwritten():
    with tempfile.TemporaryDirectory() as tmp:
        db = Database(os.path.join(tmp, "state.sqlite3"))
        db.save_order({
            "symbol": "BTCUSDT", "orderId": 10, "clientOrderId": "cid-a",
            "side": "BUY", "type": "LIMIT", "status": "NEW", "origQty": "1",
        })
        with pytest.raises(RuntimeError, match="clientOrderId collision"):
            db.save_order({
                "symbol": "BTCUSDT", "orderId": 11, "clientOrderId": "cid-a",
                "side": "BUY", "type": "LIMIT", "status": "NEW", "origQty": "1",
            })


def _recovery_gate_fixture(path):
    db = Database(path)
    client = MockExchange()
    runtime = object.__new__(Trader)
    runtime.db = db
    runtime.client = client
    runtime.auto_scan_symbols = ["BTCUSDT"]
    runtime.symbol = "BTCUSDT"
    portfolio = MultiPositionTrader(client, db=db, symbols=["BTCUSDT"])
    report = {
        "ready": True,
        "checks": {"api_reachable": "PASS", "account": "PASS"},
    }
    recovery = {"ok": True, "state": "FLAT"}
    return db, client, runtime, portfolio, report, recovery


def test_startup_gate_unlocks_only_after_complete_read_only_reconciliation(tmp_path):
    db, client, runtime, portfolio, report, recovery = _recovery_gate_fixture(
        str(tmp_path / "gate.sqlite3")
    )
    assert db.state_get("recovery_state") == "REQUIRED"
    runtime._finalize_recovery_gate(report, recovery, portfolio)
    assert db.state_get("recovery_state") == "READY"
    assert db.state_get("position_state") == "FLAT"


def test_startup_gate_remains_blocked_when_exchange_has_unknown_open_order(tmp_path):
    db, client, runtime, portfolio, report, recovery = _recovery_gate_fixture(
        str(tmp_path / "gate_unknown.sqlite3")
    )
    client.orders.append({
        "symbol": "BTCUSDT",
        "orderId": "foreign-1",
        "clientOrderId": "FOREIGN_ORDER",
        "side": "BUY",
        "type": "LIMIT",
        "status": "NEW",
        "origQty": "1",
        "executedQty": "0",
    })
    with pytest.raises(RuntimeError, match="unknown exchange orders"):
        runtime._finalize_recovery_gate(report, recovery, portfolio)
    assert db.state_get("recovery_state") == "REQUIRED"


def test_ambiguous_cancel_recovers_against_persisted_target_order_id(tmp_path):
    db, client, runtime, portfolio, report, recovery = _recovery_gate_fixture(
        str(tmp_path / "cancel_target.sqlite3")
    )
    target = {
        "symbol": "BTCUSDT",
        "orderId": "77",
        "clientOrderId": "WILLV5_STOP_target",
        "side": "SELL",
        "type": "STOP_LOSS",
        "status": "CANCELED",
        "origQty": "0.01",
        "executedQty": "0",
        "cummulativeQuoteQty": "0",
    }
    client.orders.append(target)
    intent = OrderIntent.new(
        "BTCUSDT",
        "SELL",
        "CANCEL",
        required_context_versions={},
        client_order_id="WILLV5_STOP_CANCEL_attempt",
        target_order_id="77",
        purpose="CAMPAIGN_PROTECTION_CANCEL",
        campaign_id="campaign-cancel",
    )
    db.save_execution_intent(intent, "AMBIGUOUS", "cancel response lost")

    runtime._finalize_recovery_gate(report, recovery, portfolio)

    stored = db.conn.execute(
        "SELECT status,target_order_id FROM execution_intents WHERE intent_id=?",
        (intent.intent_id,),
    ).fetchone()
    assert stored["status"] == "RECONCILED"
    assert stored["target_order_id"] == "77"
    assert db.state_get("recovery_state") == "READY"


def test_duplicate_durable_client_order_id_is_rejected_by_sqlite(tmp_path):
    db = Database(str(tmp_path / "unique_client_order.sqlite3"))
    first = OrderIntent.new(
        "BTCUSDT", "BUY", "MARKET", {},
        client_order_id="WILLV5_ENTRY_unique",
        purpose="CAMPAIGN_ENTRY",
        campaign_id="campaign-one",
    )
    second = OrderIntent.new(
        "BTCUSDT", "BUY", "MARKET", {},
        client_order_id="WILLV5_ENTRY_unique",
        purpose="CAMPAIGN_ENTRY",
        campaign_id="campaign-two",
    )
    db.save_execution_intent(first, "PENDING")
    with pytest.raises(sqlite3.IntegrityError):
        db.save_execution_intent(second, "PENDING")


def test_legacy_portfolio_mutation_persists_intent_before_exchange_call(tmp_path):
    db = Database(str(tmp_path / "legacy_with_barrier.sqlite3"))
    db.mark_recovery_ready(evidence())
    client = MockExchange()
    barrier = ExecutionBarrier(ContextCache(), db)
    portfolio = MultiPositionTrader(
        client, db=db, symbols=["BTCUSDT"], execution_barrier=barrier
    )
    client_id = "WILLV4_ENTRY_barrier-required"
    seen = []

    def submit():
        row = db.conn.execute(
            "SELECT status FROM execution_intents WHERE symbol=? AND client_order_id=?",
            ("BTCUSDT", client_id),
        ).fetchone()
        seen.append(row["status"] if row else "MISSING")
        return {
            "symbol": "BTCUSDT",
            "orderId": "1234",
            "clientOrderId": client_id,
            "side": "BUY",
            "type": "MARKET",
            "status": "FILLED",
            "executedQty": "0.01",
            "cummulativeQuoteQty": "600",
        }

    response = portfolio._execute_mutation(
        "BTCUSDT",
        "BUY",
        "MARKET",
        "CAMPAIGN_ENTRY",
        client_id,
        submit,
        trade_id="legacy-entry:BTCUSDT",
    )
    assert response["orderId"] == "1234"
    assert seen == ["PENDING"]
    row = db.conn.execute(
        "SELECT status FROM execution_intents WHERE symbol=? AND client_order_id=?",
        ("BTCUSDT", client_id),
    ).fetchone()
    assert row["status"] == "SUBMITTED"


def test_legacy_portfolio_mutation_without_barrier_never_calls_exchange(tmp_path):
    db = Database(str(tmp_path / "legacy_no_barrier.sqlite3"))
    client = MockExchange()
    portfolio = MultiPositionTrader(client, db=db, symbols=["BTCUSDT"])
    calls = []

    with pytest.raises(ExecutionPersistenceError, match="ExecutionBarrier is unavailable"):
        portfolio._execute_mutation(
            "BTCUSDT",
            "BUY",
            "MARKET",
            "CAMPAIGN_ENTRY",
            "WILLV4_ENTRY_should-not-submit",
            lambda: calls.append("submitted") or {"status": "FILLED"},
            trade_id="legacy-entry:BTCUSDT",
        )
    assert calls == []


def test_ambiguous_oco_cancel_recovers_only_from_terminal_exchange_list(tmp_path):
    db, client, runtime, portfolio, report, recovery = _recovery_gate_fixture(
        str(tmp_path / "cancel_oco.sqlite3")
    )
    client.order_list = lambda symbol, order_list_id=None, list_client_order_id=None: {
        "symbol": symbol,
        "orderListId": str(order_list_id or "list-7"),
        "listOrderStatus": "ALL_DONE",
        "listStatusType": "ALL_DONE",
    }
    intent = OrderIntent.new(
        "BTCUSDT",
        "SELL",
        "CANCEL_OCO",
        required_context_versions={},
        client_order_id="WILLV5_CANCEL_OCO_unique",
        target_order_list_id="list-7",
        purpose="CAMPAIGN_PROTECTION_CANCEL",
        campaign_id="campaign-oco",
    )
    db.save_execution_intent(intent, "AMBIGUOUS", "cancel response lost")

    runtime._finalize_recovery_gate(report, recovery, portfolio)

    stored = db.conn.execute(
        "SELECT status,target_order_list_id FROM execution_intents WHERE intent_id=?",
        (intent.intent_id,),
    ).fetchone()
    assert stored["status"] == "RECONCILED"
    assert stored["target_order_list_id"] == "list-7"
    assert db.state_get("recovery_state") == "READY"


def test_ambiguous_submission_cannot_be_retried_with_same_intent_or_client_id(tmp_path):
    db = Database(str(tmp_path / "ambiguous.sqlite3"))
    db.mark_recovery_ready(evidence())
    cache = ContextCache()
    barrier = ExecutionBarrier(cache, db)
    intent = OrderIntent.new(
        "BTCUSDT",
        "SELL",
        "MARKET",
        required_context_versions={},
        quantity="0.01",
        client_order_id="WILLV5_EXIT_stable-test-id",
        purpose="CAMPAIGN_EXIT",
        campaign_id="campaign-ambiguous",
    )
    calls = []

    def timeout_after_possible_acceptance():
        calls.append("submitted")
        raise TimeoutError("response lost after possible exchange acceptance")

    with pytest.raises(TimeoutError):
        barrier.execute(intent, timeout_after_possible_acceptance)

    with pytest.raises(ExecutionPersistenceError, match="intent ID already exists"):
        barrier.execute(intent, timeout_after_possible_acceptance)

    retry_with_new_intent = OrderIntent.new(
        "BTCUSDT",
        "SELL",
        "MARKET",
        required_context_versions={},
        quantity="0.01",
        client_order_id="WILLV5_EXIT_stable-test-id",
        purpose="CAMPAIGN_EXIT",
        campaign_id="campaign-ambiguous",
    )
    with pytest.raises(ExecutionPersistenceError, match="clientOrderId already belongs"):
        barrier.execute(retry_with_new_intent, timeout_after_possible_acceptance)

    assert calls == ["submitted"]
    row = db.conn.execute(
        "SELECT status FROM execution_intents WHERE intent_id=?",
        (intent.intent_id,),
    ).fetchone()
    assert row["status"] == "AMBIGUOUS"
