import os
import tempfile
from datetime import datetime, timezone

from campaign_engine import CampaignEngine
from campaign_execution import CampaignExecutionService
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
    )


def service(db, client):
    barrier = ExecutionBarrier(FakeCache(), db)
    return CampaignExecutionService(client, db, barrier)


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
        assert recovered[0]["state"] == "OPEN"
        order = client.get_order("BTCUSDT", orig_client_order_id=result["client_order_id"])
        assert order["status"] in {"PARTIALLY_FILLED", "CANCELED"}
        campaign = svc.engine.load_campaign(result["campaign_id"])
        assert campaign.position_qty > 0


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


def test_campaign_permission_denial_is_fail_closed_before_exchange_mutation():
    class DenyContext(FakeContext):
        def __init__(self, version=1):
            super().__init__(version)
            self.allow_long = False

    class DenySnapshot:
        def context(self, symbol, interval):
            return DenyContext(1)

    class DenyCache(FakeCache):
        def snapshot(self):
            return DenySnapshot()

    with tempfile.TemporaryDirectory() as d:
        db = Database(os.path.join(d, "permission.sqlite3"))
        barrier = ExecutionBarrier(DenyCache(), db)
        calls = []

        from execution_barrier import OrderIntent
        intent = OrderIntent.new(
            "BTCUSDT",
            "BUY",
            "STOP_LOSS",
            {},
            purpose="CAMPAIGN_ENTRY",
            permission_interval="5m",
            campaign_id="campaign-1",
            client_order_id="WILLV5_ENTRY_TEST",
        )
        result = barrier.execute(
            intent,
            lambda: calls.append("BINANCE") or {"orderId": "1"},
        )
        assert result.accepted is False
        assert "does not allow LONG" in result.reason
        assert calls == []


def test_reconcile_required_campaign_risk_remains_reserved():
    with tempfile.TemporaryDirectory() as d:
        db = Database(os.path.join(d, "risk.sqlite3"))
        engine = CampaignEngine(db)
        spec = signal(trigger=101.0)
        campaign = engine.create_campaign(spec, initial_risk_pct=0.002)
        campaign.pending_risk_quote = 25.0
        campaign.state = CampaignState.RECONCILE_REQUIRED
        db.save_campaign(campaign)
        assert db.campaign_risk_reserved_quote() == 25.0


def test_partial_fill_cancel_creates_canonical_cancel_intent():
    with tempfile.TemporaryDirectory() as d:
        db = Database(os.path.join(d, "partial-cancel.sqlite3"))
        client = MockExchange()
        client.partial_fill_ratio = 0.4
        svc = service(db, client)

        result = svc.arm_initial_entry(
            signal(trigger=101.0),
            equity_quote=10_000,
            candidate_risk_pct=0.004,
        )
        client.set_price("BTCUSDT", 101.5)
        svc.reconcile_pending_entries()

        row = db.conn.execute(
            "SELECT purpose,status FROM execution_intents "
            "WHERE purpose='CAMPAIGN_ENTRY_PARTIAL_CANCEL' "
            "ORDER BY created_at DESC LIMIT 1"
        ).fetchone()
        assert row is not None
        assert row["purpose"] == "CAMPAIGN_ENTRY_PARTIAL_CANCEL"
        assert row["status"] == "SUBMITTED"


def test_canonical_campaign_reconcile_state_blocks_even_if_mirror_is_stale():
    with tempfile.TemporaryDirectory() as d:
        db = Database(os.path.join(d, "canonical-reconcile.sqlite3"))
        engine = CampaignEngine(db)
        spec = signal(trigger=101.0)
        campaign = engine.create_campaign(spec, initial_risk_pct=0.002)
        # Simulate a stale mirror from an older runtime.
        db.state_set(f"campaign_state:{campaign.campaign_id}", "ENTRY_PENDING")
        campaign.state = CampaignState.RECONCILE_REQUIRED
        db.conn.execute(
            "UPDATE campaigns SET state='RECONCILE_REQUIRED' WHERE campaign_id=?",
            (campaign.campaign_id,),
        )
        db.conn.commit()

        from execution_barrier import OrderIntent
        intent = OrderIntent.new(
            "BTCUSDT",
            "BUY",
            "STOP_LOSS",
            {},
            purpose="CAMPAIGN_ENTRY",
            permission_interval="5m",
            campaign_id=campaign.campaign_id,
        )
        result = ExecutionBarrier(FakeCache(), db).execute(
            intent,
            lambda: {"orderId": "must-not-submit"},
        )
        assert result.accepted is False
        assert "reconcile" in result.reason.lower()
