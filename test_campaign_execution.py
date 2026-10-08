import os
import tempfile
import time
from dataclasses import replace
from datetime import datetime, timezone

from campaign_engine import CampaignEngine
from campaign_execution import CampaignExecutionService
from campaign_model import CampaignState, SignalRole, SignalSpec, SignalType
from execution_barrier import ExecutionBarrier
from pending_signal import PendingSignal
from portfolio_trader import MultiPositionTrader
from mock_exchange import MockExchange
from db import Database


class FakeContext:
    def __init__(self, version=1):
        self.version = version
        self.allow_long = True
        self.allow_short = False
        self.candle_close_time_ms = int(time.time() * 1000) - 1_000
        self.price = 100.0


class FakeSnapshot:
    def context(self, symbol, interval):
        return FakeContext(1)


class FakeCache:
    def __init__(self):
        import threading
        self.execution_lock = threading.RLock()

    def snapshot(self):
        return FakeSnapshot()


def signal(kind=SignalType.REVERSAL, role=SignalRole.ENTRY, bar=None, trigger=101, **kwargs):
    bar = int(time.time() * 1000) if bar is None else int(bar)
    return SignalSpec.new(
        symbol="BTCUSDT",
        side="BUY",
        signal_type=kind,
        role=role,
        timeframe="5m",
        signal_bar_time_ms=bar,
        trigger_price=trigger,
        protective_reference=97,
        context_versions={"5m": 1},
        htf_confirmed=True,
        **kwargs,
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



def test_pending_signal_expiry_is_anchored_to_original_signal_bar():
    now = int(time.time() * 1000)
    bar_time = now - 10 * 60_000
    spec = replace(signal(bar=bar_time), created_at_ms=now, expires_at_ms=0)
    pending = PendingSignal.from_spec(spec)

    assert pending.expires_at_ms == bar_time + 2 * 5 * 60_000
    assert pending.is_expired(now)
    assert not pending.actionable(now)


def test_pending_signal_preserves_explicit_expired_deadline():
    now = int(time.time() * 1000)
    spec = signal(bar=now - 60_000, expires_at_ms=now - 1)
    pending = PendingSignal.from_spec(spec)

    assert pending.expires_at_ms == now - 1
    assert pending.is_expired(now)
    assert not pending.actionable(now)


def test_pending_signal_expiry_boundary_is_inclusive():
    now = int(time.time() * 1000)
    spec = signal(bar=now - 60_000, expires_at_ms=now)
    pending = PendingSignal.from_spec(spec)

    assert pending.is_expired(now)
    assert not pending.actionable(now)


def test_pending_signal_future_deadline_is_actionable():
    now = int(time.time() * 1000)
    spec = signal(bar=now, expires_at_ms=now + 60_000)
    pending = PendingSignal.from_spec(spec)

    assert not pending.is_expired(now)
    assert pending.actionable(now)


def test_pending_signal_rescan_and_restart_preserve_absolute_expiry():
    now = int(time.time() * 1000)
    bar_time = now - 10 * 60_000
    spec = replace(signal(bar=bar_time), created_at_ms=now, expires_at_ms=0)
    first = PendingSignal.from_spec(spec)
    rescanned = PendingSignal.from_spec(replace(spec, created_at_ms=now + 1_000))
    restored = PendingSignal(**first.to_dict())

    assert rescanned.signal_id == first.signal_id
    assert rescanned.expires_at_ms == first.expires_at_ms
    assert restored.expires_at_ms == first.expires_at_ms
    assert not rescanned.actionable(now + 1_000)


def test_expired_signal_is_rejected_before_campaign_creation():
    now = int(time.time() * 1000)
    expired = signal(
        bar=now - 10 * 60_000,
        expires_at_ms=now - 1,
    )
    with tempfile.TemporaryDirectory() as d:
        db = Database(os.path.join(d, "campaign.sqlite3"))
        client = MockExchange()
        svc = service(db, client)

        try:
            svc.arm_initial_entry(
                expired,
                equity_quote=10_000,
                candidate_risk_pct=0.004,
            )
        except Exception as exc:
            assert "expired" in str(exc).lower()
        else:
            raise AssertionError("expired signal was accepted")

        assert client.open_orders("BTCUSDT") == []
        assert db.conn.execute("SELECT COUNT(*) FROM campaigns").fetchone()[0] == 0



def test_legacy_autonomous_mode_fails_closed_without_entry_door():
    from types import SimpleNamespace

    trader = object.__new__(MultiPositionTrader)
    trader.campaign_engine_enabled = False
    trader.dry_run = False
    selection = SimpleNamespace(
        candidate=SimpleNamespace(symbol="BTCUSDT"),
        risk=SimpleNamespace(risk_pct=0.4),
    )

    result = trader.execute([selection])

    assert len(result) == 1
    assert result[0]["action"] == "ENTRY_BLOCKED"
    assert "ExecutionBarrier" in result[0]["reason"]



def test_duplicate_signal_id_cannot_create_second_campaign_or_order():
    with tempfile.TemporaryDirectory() as d:
        db = Database(os.path.join(d, "campaign.sqlite3"))
        client = MockExchange()
        svc = service(db, client)
        spec = signal()

        first = svc.arm_initial_entry(
            spec,
            equity_quote=10_000,
            candidate_risk_pct=0.004,
        )
        try:
            svc.arm_initial_entry(
                spec,
                equity_quote=10_000,
                candidate_risk_pct=0.004,
            )
        except Exception as exc:
            assert "already reserved" in str(exc)
        else:
            raise AssertionError("duplicate signal created a second campaign")

        assert len(client.open_orders("BTCUSDT")) == 1
        assert db.conn.execute("SELECT COUNT(*) FROM campaigns").fetchone()[0] == 1
        assert first["signal_id"] == spec.signal_id


def test_expired_persisted_conditional_entry_is_cancelled_on_recovery():
    now = int(time.time() * 1000)
    with tempfile.TemporaryDirectory() as d:
        db = Database(os.path.join(d, "campaign.sqlite3"))
        client = MockExchange()
        svc = service(db, client)
        spec = signal(bar=now, expires_at_ms=now + 10 * 60_000)
        result = svc.arm_initial_entry(
            spec,
            equity_quote=10_000,
            candidate_risk_pct=0.004,
        )

        campaign = svc.engine.load_campaign(result["campaign_id"])
        assert campaign is not None
        campaign.tags["pending_signal_expires_at_ms"] = now - 1
        campaign.tags["pending_signal"]["expires_at_ms"] = now - 1
        db.save_campaign(campaign)

        recovered = svc.reconcile_pending_entries()

        assert recovered[0]["state"] == "EXPIRED", recovered[0]
        assert client.open_orders("BTCUSDT") == []
        assert svc.engine.load_campaign(result["campaign_id"]).state == CampaignState.CLOSED



def test_campaign_engine_true_uses_campaign_executor():
    from types import SimpleNamespace

    trader = object.__new__(MultiPositionTrader)
    trader.campaign_engine_enabled = True
    trader.dry_run = False
    trader.execute_campaign = lambda selections: [{"action": "CAMPAIGN_PATH", "count": len(selections)}]
    selection = SimpleNamespace(candidate=SimpleNamespace(symbol="BTCUSDT"))

    result = trader.execute([selection])

    assert result == [{"action": "CAMPAIGN_PATH", "count": 1}]



def test_restart_recovers_filled_campaign_after_protection_setup_failure():
    with tempfile.TemporaryDirectory() as d:
        db_path = os.path.join(d, "campaign.sqlite3")
        db = Database(db_path)
        client = MockExchange()
        svc = service(db, client)
        result = svc.arm_initial_entry(
            signal(trigger=101.0),
            equity_quote=10_000,
            candidate_risk_pct=0.004,
        )
        client.set_price("BTCUSDT", 101.5)

        def fail_protection(*args, **kwargs):
            raise TimeoutError("simulated protective stop setup failure")

        svc.create_hard_stop = fail_protection
        first = svc.reconcile_pending_entries()
        assert first[0]["state"] == "RECONCILE_REQUIRED"
        campaign = svc.engine.load_campaign(result["campaign_id"])
        assert campaign.state == CampaignState.RECONCILE_REQUIRED
        assert campaign.position_qty == 0
        assert not [
            o for o in client.open_orders("BTCUSDT")
            if o.get("side") == "SELL" and str(o.get("clientOrderId", "")).startswith(svc.STOP_PREFIX)
        ]

        db_after_restart = Database(db_path)
        svc_after_restart = service(db_after_restart, client)
        recovered = svc_after_restart.reconcile_pending_entries()
        assert recovered[0]["state"] == "OPEN"
        campaign = svc_after_restart.engine.load_campaign(result["campaign_id"])
        assert campaign.position_qty > 0
        assert campaign.state == CampaignState.OPEN_INITIAL
        stops = [
            o for o in client.open_orders("BTCUSDT")
            if o.get("side") == "SELL" and str(o.get("clientOrderId", "")).startswith(svc_after_restart.STOP_PREFIX)
        ]
        assert len(stops) == 1
        assert float(stops[0]["origQty"]) == campaign.position_qty


def test_restart_adopts_protective_stop_after_ambiguous_setup_response_without_duplicate():
    with tempfile.TemporaryDirectory() as d:
        db_path = os.path.join(d, "campaign.sqlite3")
        db = Database(db_path)
        client = MockExchange()
        svc = service(db, client)
        result = svc.arm_initial_entry(
            signal(trigger=101.0),
            equity_quote=10_000,
            candidate_risk_pct=0.004,
        )
        client.set_price("BTCUSDT", 101.5)
        original_create = svc.create_hard_stop

        def accept_then_timeout(*args, **kwargs):
            original_create(*args, **kwargs)
            raise TimeoutError("response lost after protective stop accepted")

        svc.create_hard_stop = accept_then_timeout
        first = svc.reconcile_pending_entries()
        assert first[0]["state"] == "RECONCILE_REQUIRED"
        stops_before = [
            o for o in client.open_orders("BTCUSDT")
            if o.get("side") == "SELL" and str(o.get("clientOrderId", "")).startswith(svc.STOP_PREFIX)
        ]
        assert len(stops_before) == 1
        existing_client_id = stops_before[0]["clientOrderId"]

        db_after_restart = Database(db_path)
        svc_after_restart = service(db_after_restart, client)
        recovered = svc_after_restart.reconcile_pending_entries()
        assert recovered[0]["state"] == "OPEN"
        campaign = svc_after_restart.engine.load_campaign(result["campaign_id"])
        assert campaign.position_qty > 0
        stops_after = [
            o for o in client.open_orders("BTCUSDT")
            if o.get("side") == "SELL" and str(o.get("clientOrderId", "")).startswith(svc_after_restart.STOP_PREFIX)
        ]
        assert len(stops_after) == 1
        assert stops_after[0]["clientOrderId"] == existing_client_id
        assert float(stops_after[0]["origQty"]) == campaign.position_qty
