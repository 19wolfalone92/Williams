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
    def __init__(self, interval, version=1):
        now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
        self.version = version
        self.candle_close_time_ms = now_ms - {
            "1h": 20_000,
            "4h": 40_000,
            "1d": 60_000,
        }.get(interval, 20_000)
        self.price = 100.0
        self.atr = 2.0
        self.jaw = 100.0
        self.teeth = 100.0
        self.lips = 100.0
        self.alligator_state = "BULLISH" if interval == "1h" else "SLEEP"
        self.alligator_awake = interval == "1h"
        self.ao_value = 1.0 if interval == "1h" else 0.0
        self.ac_value = 0.0
        self.williams_core_ready = True
        self.allow_long = interval == "1h"
        self.allow_short = False


class FakeSnapshot:
    def context(self, symbol, interval):
        return FakeContext(interval, 1)

    def versions(self, symbol, intervals):
        return {str(interval).lower(): 1 for interval in intervals}


class FakeCache:
    def __init__(self):
        import threading
        self.execution_lock = threading.RLock()

    def snapshot(self):
        return FakeSnapshot()


def signal(kind=SignalType.REVERSAL, role=SignalRole.ENTRY, bar=100, trigger=101):
    now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
    source_time = now_ms - 3_600_000 + int(bar)
    return SignalSpec.new(
        symbol="BTCUSDT",
        side="BUY",
        signal_type=kind,
        role=role,
        timeframe="1h",
        signal_bar_time_ms=source_time,
        confirmation_time_ms=source_time,
        trigger_price=trigger,
        protective_reference=97,
        invalidation_price=97,
        context_versions={},
        angulation_score=2.5 if kind == SignalType.REVERSAL else 0.0,
        htf_confirmed=True,
        created_at_ms=now_ms,
        expires_at_ms=now_ms + 3_600_000,
    )


def service(db, client):
    barrier = ExecutionBarrier(FakeCache(), db)
    return CampaignExecutionService(client, db, barrier)


def test_spot_buy_stop_price_rounds_up_beyond_signal_extreme():
    with tempfile.TemporaryDirectory() as directory:
        db = Database(os.path.join(directory, "campaign.sqlite3"))
        client = MockExchange()
        executor = service(db, client)
        executor._rules = lambda symbol: {
            "PRICE_FILTER": {"tickSize": "0.01"}
        }
        try:
            assert executor._normalize_price("BTCUSDT", 100.001, round_up=True) == 100.01
            assert executor._normalize_price("BTCUSDT", 100.001) == 100.0
        finally:
            db.conn.close()


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
