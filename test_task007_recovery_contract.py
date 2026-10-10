"""Fail-closed recovery persistence contract tests for TASK 007."""
import os
import sqlite3
import tempfile

import pytest

from db import Database


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
