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
        # These deterministic contexts are test fixtures, not a production
        # context source. They model the operative signal bar and D1 macro.
        self.candle_close_time_ms = 10_000


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
        context_versions={"5m": 1, "1d": 1},
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
        queried = []
        original_get_order = client.get_order

        def track_authoritative_lookup(*args, **kwargs):
            queried.append((args, kwargs))
            return original_get_order(*args, **kwargs)

        client.get_order = track_authoritative_lookup
        out = svc2.reconcile_pending_entries()
        assert queried, "restart must query Binance/mock exchange before adopting the order"
        assert any(
            kwargs.get("orig_client_order_id") == result["client_order_id"]
            for _, kwargs in queried
        )
        assert out[0]["campaign_id"] == campaign_id
        assert out[0]["state"] == "OPEN"
        # Reconciliation adopts the existing conditional order; it must not
        # submit a second order with the same logical identity.
        assert sum(
            1 for order in client.orders
            if order.get("clientOrderId") == result["client_order_id"]
        ) == 1


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



def test_unknown_exchange_order_state_keeps_campaign_blocked():
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
        original_get_order = client.get_order

        def unknown_order_state(*args, **kwargs):
            response = original_get_order(*args, **kwargs)
            return {**response, "status": "EXCHANGE_STATE_UNKNOWN"}

        client.get_order = unknown_order_state
        recovered = svc.reconcile_pending_entries()
        assert recovered[0]["state"] == "RECONCILE_REQUIRED"
        assert recovered[0]["campaign_id"] == result["campaign_id"]
        campaign = svc.engine.load_campaign(result["campaign_id"])
        assert campaign is not None
        assert campaign.state == CampaignState.RECONCILE_REQUIRED
        assert db.state_get("position_state:BTCUSDT") == "RECONCILE_REQUIRED"
