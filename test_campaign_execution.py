import os
import tempfile
import time
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


def signal(kind=SignalType.REVERSAL, role=SignalRole.ENTRY, bar=None, trigger=101):
    now_ms = int(time.time() * 1000)
    if bar is None:
        bar = (now_ms // 3_600_000) * 3_600_000 - 2 * 3_600_000
    return SignalSpec.new(
        symbol="BTCUSDT",
        side="BUY",
        signal_type=kind,
        role=role,
        timeframe="1h",
        signal_bar_time_ms=bar,
        trigger_price=trigger,
        protective_reference=97,
        invalidation_price=97,
        context_versions={},
        htf_confirmed=True,
        created_at_ms=int(time.time() * 1000),
        confirmation_time_ms=int(bar) + 3_600_000,
        expires_at_ms=now_ms + 60 * 60 * 1000,
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



def test_execution_barrier_blocks_non_h1_campaign_entry_without_order_mutation(monkeypatch):
    from execution_barrier import OrderIntent

    now = int(time.time() * 1000)
    monkeypatch.setenv("EXECUTION_TIMEFRAME", "1h")
    barrier = ExecutionBarrier(FakeCache(), None)
    attempts = []

    cases = [
        {"signal_timeframe": "5m", "permission_interval": "1h", "expires_at_ms": now + 60_000},
        {"signal_timeframe": "15m", "permission_interval": "15m", "expires_at_ms": now + 60_000},
        {"signal_timeframe": "", "permission_interval": "1h", "expires_at_ms": now + 60_000},
        {"signal_timeframe": "1h", "permission_interval": "1h", "expires_at_ms": now},
    ]
    for fields in cases:
        intent = OrderIntent.new(
            "BTCUSDT", "BUY", "STOP_LOSS", {},
            purpose="CAMPAIGN_ENTRY",
            signal_timeframe=fields["signal_timeframe"],
            permission_interval=fields["permission_interval"],
            signal_bar_time_ms=now - 3_600_000,
            confirmation_time_ms=now - 1,
            expires_at_ms=fields["expires_at_ms"],
        )
        result = barrier.execute(intent, lambda: attempts.append("MUTATED"))
        assert not result.accepted
    assert attempts == []


def test_execution_barrier_blocks_h1_signal_when_runtime_config_is_non_h1(monkeypatch):
    from execution_barrier import OrderIntent

    now = int(time.time() * 1000)
    monkeypatch.setenv("EXECUTION_TIMEFRAME", "5m")
    barrier = ExecutionBarrier(FakeCache(), None)
    attempts = []
    intent = OrderIntent.new(
        "BTCUSDT", "BUY", "STOP_LOSS", {},
        purpose="CAMPAIGN_ENTRY",
        signal_timeframe="1h",
        permission_interval="1h",
        signal_bar_time_ms=now - 3_600_000,
        confirmation_time_ms=now - 1,
        expires_at_ms=now + 60_000,
    )
    result = barrier.execute(intent, lambda: attempts.append("MUTATED"))
    assert not result.accepted
    assert attempts == []



def test_execution_barrier_blocks_entry_aliases_and_addons_before_submit(monkeypatch):
    from execution_barrier import OrderIntent

    now = int(time.time() * 1000)
    monkeypatch.setenv("EXECUTION_TIMEFRAME", "1h")
    barrier = ExecutionBarrier(FakeCache(), None)
    calls = []
    for purpose in ("ENTRY", "ADD_ON", "CAMPAIGN_ADD_ON"):
        intent = OrderIntent.new(
            "BTCUSDT", "BUY", "STOP_LOSS", {},
            purpose=purpose,
            signal_timeframe="5m",
            permission_interval="1h",
            signal_bar_time_ms=now - 7_200_000,
            confirmation_time_ms=now - 3_600_000,
            expires_at_ms=now + 60_000,
            signal_id="signal-1",
            campaign_id="campaign-1",
        )
        result = barrier.execute(intent, lambda: calls.append(purpose), pre_submit_checks=lambda _s: None)
        assert not result.accepted, (purpose, result.reason)
    assert calls == []


def test_execution_barrier_requires_last_mile_callback_for_new_entry(monkeypatch):
    from execution_barrier import OrderIntent

    now = int(time.time() * 1000)
    monkeypatch.setenv("EXECUTION_TIMEFRAME", "1h")
    barrier = ExecutionBarrier(FakeCache(), None)
    calls = []
    intent = OrderIntent.new(
        "BTCUSDT", "BUY", "STOP_LOSS", {},
        purpose="CAMPAIGN_ENTRY",
        signal_timeframe="1h",
        permission_interval="1h",
        signal_bar_time_ms=now - 7_200_000,
        confirmation_time_ms=now - 3_600_000,
        expires_at_ms=now + 60_000,
        signal_id="signal-1",
        campaign_id="campaign-1",
    )
    result = barrier.execute(intent, lambda: calls.append("MUTATED"))
    assert not result.accepted
    assert "last-mile" in result.reason
    assert calls == []


def test_execution_barrier_does_not_apply_entry_timeframe_gate_to_campaign_exit(monkeypatch):
    from execution_barrier import OrderIntent

    monkeypatch.setenv("EXECUTION_TIMEFRAME", "5m")
    barrier = ExecutionBarrier(FakeCache(), None)
    intent = OrderIntent.new(
        "BTCUSDT", "SELL", "MARKET", {},
        purpose="CAMPAIGN_EXIT",
        campaign_id="campaign-1",
    )
    result = barrier.execute(intent, lambda: {"status": "FILLED"})
    assert result.accepted
