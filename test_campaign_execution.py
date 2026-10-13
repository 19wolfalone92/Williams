import os
import hashlib
import tempfile
from dataclasses import replace
from datetime import datetime, timezone

import pytest

from campaign_engine import CampaignEngine
from campaign_execution import CampaignExecutionError, CampaignExecutionService
from campaign_model import CampaignState, SignalRole, SignalSpec, SignalType
from execution_barrier import ExecutionBarrier
from mock_exchange import MockExchange
from db import Database


class FakeContext:
    def __init__(self, version=1):
        self.version = version
        self.allow_long = True
        self.allow_short = False


class FakeSnapshot:
    def context(self, symbol, interval):
        return FakeContext(1)


class FakeCache:
    def __init__(self):
        import threading
        self.execution_lock = threading.RLock()

    def snapshot(self):
        return FakeSnapshot()


def signal(kind=SignalType.REVERSAL, role=SignalRole.ENTRY, bar=100, trigger=101):
    return SignalSpec.new(
        symbol="BTCUSDT",
        side="BUY",
        signal_type=kind,
        role=role,
        timeframe="5m",
        signal_bar_time_ms=bar,
        trigger_price=trigger,
        protective_reference=97,
        context_versions={},
        htf_confirmed=True,
        expires_at_ms=int(datetime.now(timezone.utc).timestamp() * 1000) + 3_600_000,
    )


def service(db, client):
    # Offline MockExchange identity must be explicitly approved in this test.
    os.environ["WILLIAMS_EXPECTED_API_KEY_SHA256"] = hashlib.sha256(
        str(client.api_key).encode("utf-8")
    ).hexdigest()
    barrier = ExecutionBarrier(FakeCache(), db)
    return CampaignExecutionService(client, db, barrier)


def test_expired_signal_is_rejected_before_order_submission():
    with tempfile.TemporaryDirectory() as d:
        db = Database(os.path.join(d, "campaign.sqlite3"))
        client = MockExchange()
        svc = service(db, client)
        expired = replace(
            signal(),
            expires_at_ms=int(datetime.now(timezone.utc).timestamp() * 1000) - 1,
        )

        with pytest.raises(CampaignExecutionError, match="expired"):
            svc.arm_initial_entry(
                expired,
                equity_quote=10_000,
                candidate_risk_pct=0.004,
            )

        assert client.open_orders("BTCUSDT") == []


def test_signal_without_expiry_is_rejected_before_order_submission():
    with tempfile.TemporaryDirectory() as d:
        db = Database(os.path.join(d, "campaign.sqlite3"))
        client = MockExchange()
        svc = service(db, client)

        with pytest.raises(CampaignExecutionError, match="explicit expiry"):
            svc.arm_initial_entry(
                replace(signal(), expires_at_ms=0),
                equity_quote=10_000,
                candidate_risk_pct=0.004,
            )

        assert client.open_orders("BTCUSDT") == []


def test_conditional_buy_trigger_rounds_up_to_tick():
    svc = CampaignExecutionService.__new__(CampaignExecutionService)
    svc._rules = lambda _symbol: {"PRICE_FILTER": {"tickSize": "0.1"}}

    assert svc._normalize_trigger_price("BTCUSDT", 101.01) == 101.1
    assert svc._normalize_trigger_price("BTCUSDT", 101.1) == 101.1
    assert svc._normalize_trigger_price("BTCUSDT", 100.999) == 101.0
    assert svc._normalize_trigger_price("BTCUSDT", 101.01) >= 101.01


def test_conditional_entry_is_armed_not_market():
    with tempfile.TemporaryDirectory() as d:
        db = Database(os.path.join(d, "campaign.sqlite3"))
        client = MockExchange()
        svc = service(db, client)

        result = svc.arm_initial_entry(
            signal(),
            equity_quote=10_000,
            candidate_risk_pct=0.004,
        )
        assert result["action"] if "action" in result else True
        orders = client.open_orders("BTCUSDT")
        conditional = [
            o for o in orders
            if str(o.get("side")).upper() == "BUY"
            and str(o.get("type")).upper() == "STOP_LOSS"
        ]
        assert len(conditional) == 1
        assert float(conditional[0]["executedQty"]) == 0.0


def test_trigger_then_reconcile_creates_hard_stop():
    with tempfile.TemporaryDirectory() as d:
        db = Database(os.path.join(d, "campaign.sqlite3"))
        client = MockExchange()
        svc = service(db, client)

        spec = signal(trigger=101.0)
        result = svc.arm_initial_entry(
            spec,
            equity_quote=10_000,
            candidate_risk_pct=0.004,
        )

        assert result["campaign_id"]
        client.set_price("BTCUSDT", 101.50)

        recovered = svc.reconcile_pending_entries()
        assert recovered[0]["state"] == "OPEN"
        campaign = svc.engine.load_campaign(result["campaign_id"])
        assert campaign is not None
        assert campaign.position_qty > 0
        assert campaign.current_stop_price > 0
        stops = [
            o for o in client.open_orders("BTCUSDT")
            if str(o.get("side")).upper() == "SELL"
            and str(o.get("clientOrderId", "")).startswith(svc.STOP_PREFIX)
        ]
        assert len(stops) == 1


def test_partial_conditional_entry_is_cancelled_and_residual_protected():
    with tempfile.TemporaryDirectory() as d:
        db = Database(os.path.join(d, "campaign.sqlite3"))
        client = MockExchange()
        client.partial_fill_ratio = 0.4
        svc = service(db, client)

        result = svc.arm_initial_entry(
            signal(trigger=101.0),
            equity_quote=10_000,
            candidate_risk_pct=0.004,
        )
        client.set_price("BTCUSDT", 101.5)
        recovered = svc.reconcile_pending_entries()
        assert recovered[0]["state"] == "OPEN", recovered
        order = client.get_order("BTCUSDT", orig_client_order_id=result["client_order_id"])
        assert order["status"] == "CANCELED"
        campaign = svc.engine.load_campaign(result["campaign_id"])
        assert campaign.position_qty > 0
        stop = client.get_order("BTCUSDT", order_id=int(campaign.tags["protective_order_id"]))
        assert stop["status"] == "NEW"
        assert float(stop["origQty"]) >= campaign.position_qty
        fill_count = db.conn.execute("SELECT COUNT(*) FROM execution_fills").fetchone()[0]
        trade_count = len(client.my_trades("BTCUSDT", order_id=order["orderId"]))
        assert fill_count == trade_count == 1
        assert svc.recovery_coordinator.readiness()["ready"] is True


def test_restart_adopts_pending_campaign_by_client_id():
    with tempfile.TemporaryDirectory() as d:
        db = Database(os.path.join(d, "campaign.sqlite3"))
        client = MockExchange()
        svc = service(db, client)
        result = svc.arm_initial_entry(
            signal(trigger=101.0),
            equity_quote=10_000,
            candidate_risk_pct=0.004,
        )
        campaign_id = result["campaign_id"]

        db2 = Database(os.path.join(d, "campaign.sqlite3"))
        svc2 = service(db2, client)
        client.set_price("BTCUSDT", 101.5)
        out = svc2.reconcile_pending_entries()
        assert out[0]["campaign_id"] == campaign_id
        assert out[0]["state"] == "OPEN"


def test_stop_replacement_never_lowers_stop():
    with tempfile.TemporaryDirectory() as d:
        db = Database(os.path.join(d, "campaign.sqlite3"))
        client = MockExchange()
        svc = service(db, client)
        result = svc.arm_initial_entry(
            signal(trigger=101.0),
            equity_quote=10_000,
            candidate_risk_pct=0.004,
        )
        client.set_price("BTCUSDT", 101.5)
        svc.reconcile_pending_entries()
        campaign = svc.engine.load_campaign(result["campaign_id"])
        assert campaign is not None
        current = campaign.current_stop_price
        assert current > 0
        assert not svc.engine.propose_stop(campaign, current - 1.0, "BAD")
        assert campaign.current_stop_price == current


def test_exchange_fill_journal_is_idempotent_after_full_campaign_restart():
    with tempfile.TemporaryDirectory() as d:
        path = os.path.join(d, "campaign-restart.sqlite3")
        db = Database(path)
        client = MockExchange()
        svc = service(db, client)
        result = svc.arm_initial_entry(
            signal(trigger=101.0),
            equity_quote=10_000,
            candidate_risk_pct=0.004,
        )
        client.set_price("BTCUSDT", 101.5)
        first = svc.reconcile_pending_entries()
        assert first[0]["state"] == "OPEN", first
        campaign = svc.engine.load_campaign(result["campaign_id"])
        assert campaign is not None
        assert campaign.position_qty > 0
        before_position = campaign.position_qty
        before_fill_count = db.conn.execute(
            "SELECT COUNT(*) FROM execution_fills"
        ).fetchone()[0]
        before_campaign_fills = db.conn.execute(
            "SELECT COUNT(*) FROM campaign_fills WHERE campaign_id=?",
            (campaign.campaign_id,),
        ).fetchone()[0]
        before_trade = db.conn.execute(
            "SELECT quantity,fees,pnl FROM trades WHERE id=?",
            (int(campaign.tags["trade_id"]),),
        ).fetchone()

        # Process restart: new DB instance, same durable SQLite and exchange state.
        db2 = Database(path)
        svc2 = service(db2, client)
        second = svc2.reconcile_pending_entries()
        assert not second or all(row.get("state") == "OPEN" for row in second), second
        after = svc2.engine.load_campaign(campaign.campaign_id)
        after_trade = db2.conn.execute(
            "SELECT quantity,fees,pnl FROM trades WHERE id=?",
            (int(after.tags["trade_id"]),),
        ).fetchone()
        assert after.position_qty == before_position
        assert db2.conn.execute("SELECT COUNT(*) FROM execution_fills").fetchone()[0] == before_fill_count
        assert db2.conn.execute(
            "SELECT COUNT(*) FROM campaign_fills WHERE campaign_id=?",
            (campaign.campaign_id,),
        ).fetchone()[0] == before_campaign_fills
        assert dict(after_trade) == dict(before_trade)
        assert svc2.recovery_coordinator.readiness()["ready"] is True
        stops = [
            o for o in client.open_orders("BTCUSDT")
            if str(o.get("side")).upper() == "SELL"
            and str(o.get("clientOrderId", "")).startswith(svc2.STOP_PREFIX)
        ]
        assert len(stops) == 1


def test_campaign_exit_fill_commits_trade_pnl_and_position_once():
    with tempfile.TemporaryDirectory() as d:
        path = os.path.join(d, "campaign-exit.sqlite3")
        db = Database(path)
        client = MockExchange()
        svc = service(db, client)
        result = svc.arm_initial_entry(
            signal(trigger=101.0),
            equity_quote=10_000,
            candidate_risk_pct=0.004,
        )
        client.set_price("BTCUSDT", 101.5)
        opened = svc.reconcile_pending_entries()
        assert opened[0]["state"] == "OPEN", opened
        campaign = svc.engine.load_campaign(result["campaign_id"])
        assert campaign is not None
        entry_qty = float(campaign.position_qty)
        entry_price = float(campaign.average_entry_price)

        closed = svc.exit_market(campaign, reason="TASK0093_E2E_EXIT")
        assert closed["state"] == "CLOSED", closed
        final_campaign = svc.engine.load_campaign(result["campaign_id"])
        assert final_campaign is not None
        assert final_campaign.state == CampaignState.CLOSED
        assert final_campaign.position_qty == 0
        assert final_campaign.realized_pnl_quote == (101.5 - entry_price) * entry_qty or abs(
            final_campaign.realized_pnl_quote - (101.5 - entry_price) * entry_qty
        ) < 1e-6

        trade = db.conn.execute(
            "SELECT quantity,entry_price,exit_price,pnl,fees,exit_time FROM trades WHERE id=?",
            (int(final_campaign.tags["trade_id"]),),
        ).fetchone()
        assert trade is not None and trade["exit_time"] is not None
        assert float(trade["quantity"]) > 0
        assert float(trade["exit_price"]) > 0
        fill_rows = db.conn.execute(
            "SELECT trade_id,raw_json FROM execution_fills ORDER BY trade_id"
        ).fetchall()
        assert len(fill_rows) == 2
        assert any('"side": "BUY"' in row["raw_json"] for row in fill_rows)
        assert any('"side": "SELL"' in row["raw_json"] for row in fill_rows)
        pnl_after_first_exit = float(trade["pnl"])
        svc._ensure_recovered()
        reread = db.conn.execute(
            "SELECT pnl,fees,exit_time FROM trades WHERE id=?",
            (int(final_campaign.tags["trade_id"]),),
        ).fetchone()
        assert float(reread["pnl"]) == pnl_after_first_exit
        assert db.conn.execute("SELECT COUNT(*) FROM execution_fills").fetchone()[0] == 2
        assert svc.recovery_coordinator.readiness()["ready"] is True
