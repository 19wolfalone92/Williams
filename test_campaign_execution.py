import os
import tempfile

import pytest
from datetime import datetime, timezone

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


def signal(kind=SignalType.REVERSAL, role=SignalRole.ENTRY, bar=100, trigger=101, symbol="BTCUSDT"):
    return SignalSpec.new(
        symbol=symbol,
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
        assert recovered[0]["state"] == "RECONCILE_REQUIRED"
        order = client.get_order("BTCUSDT", orig_client_order_id=result["client_order_id"])
        assert order["status"] in {"PARTIALLY_FILLED", "CANCELED"}
        campaign = svc.engine.load_campaign(result["campaign_id"])
        assert campaign.position_qty > 0
        protected = [
            o for o in client.open_orders("BTCUSDT")
            if str(o.get("side", "")).upper() == "SELL"
            and str(o.get("clientOrderId", "")).startswith(svc.STOP_PREFIX)
        ]
        assert len(protected) == 1
        filled_qty = float(order["executedQty"])
        protected_qty = float(protected[0]["origQty"])
        assert campaign.position_qty == pytest.approx(filled_qty)
        assert protected_qty <= campaign.position_qty
        gap = campaign.position_qty - protected_qty
        if gap > 1e-12:
            assert campaign.state == CampaignState.RECONCILE_REQUIRED
            assert float(campaign.tags["unprotected_exposure_qty"]) == pytest.approx(gap)
            assert campaign.tags["protection_state"] == "PARTIAL"
        else:
            assert campaign.tags["protection_state"] == "FULL"


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


def test_base_asset_commission_reduces_net_inventory_and_fill_history_is_idempotent():
    with tempfile.TemporaryDirectory() as d:
        db = Database(os.path.join(d, "campaign.sqlite3"))
        client = MockExchange()
        client.commission_rate = 0.001
        client.commission_asset = "BTC"
        svc = service(db, client)
        result = svc.arm_initial_entry(
            signal(trigger=101.0), equity_quote=10_000, candidate_risk_pct=0.004
        )
        client.set_price("BTCUSDT", 101.5)
        out = svc.reconcile_pending_entries()
        order = client.get_order("BTCUSDT", orig_client_order_id=result["client_order_id"])
        fills = client.my_trades("BTCUSDT", order_id=order["orderId"])
        gross = sum(float(f["qty"]) for f in fills)
        base_fee = sum(float(f["commission"]) for f in fills if f["commissionAsset"] == "BTC")
        quote_fee = sum(
            float(f["commission"]) * float(f["price"])
            for f in fills if f["commissionAsset"] == "BTC"
        )
        campaign = svc.engine.load_campaign(result["campaign_id"])
        assert campaign.position_qty == pytest.approx(gross - base_fee)
        trade = db.open_trade("BTCUSDT")
        assert trade is not None
        assert float(trade["fees"]) == pytest.approx(quote_fee)

        count_before = len(db.campaign_fills_for_order("BTCUSDT", order["orderId"]))
        accounting_before = svc._account_order_fills("BTCUSDT", campaign.campaign_id, order)
        accounting_after = svc._account_order_fills("BTCUSDT", campaign.campaign_id, order)
        count_after = len(db.campaign_fills_for_order("BTCUSDT", order["orderId"]))
        assert count_before == count_after == len(fills)
        assert accounting_after == accounting_before
        assert out[0]["protective_quantity"] <= campaign.position_qty
        if out[0]["unprotected_exposure_qty"] > 1e-12:
            assert out[0]["state"] == CampaignState.RECONCILE_REQUIRED.value


def test_quote_asset_commission_is_recorded_once_in_quote_units():
    with tempfile.TemporaryDirectory() as d:
        db = Database(os.path.join(d, "campaign.sqlite3"))
        client = MockExchange()
        client.commission_rate = 0.001
        client.commission_asset = "USDT"
        svc = service(db, client)
        result = svc.arm_initial_entry(
            signal(trigger=101.0), equity_quote=10_000, candidate_risk_pct=0.004
        )
        client.set_price("BTCUSDT", 101.5)
        svc.reconcile_pending_entries()
        order = client.get_order("BTCUSDT", orig_client_order_id=result["client_order_id"])
        fills = client.my_trades("BTCUSDT", order_id=order["orderId"])
        expected_fee = sum(float(f["commission"]) for f in fills)
        trade = db.open_trade("BTCUSDT")
        assert trade is not None
        assert float(trade["fees"]) == pytest.approx(expected_fee)
        assert len(db.campaign_fills_for_order("BTCUSDT", order["orderId"])) == len(fills)


def test_third_asset_commission_uses_fill_minute_conversion():
    with tempfile.TemporaryDirectory() as d:
        db = Database(os.path.join(d, "campaign.sqlite3"))
        client = MockExchange()
        client.prices["BNBUSDT"] = 300.0
        client.balances["BNB"] = 1.0
        client.commission_rate = 0.001
        client.commission_asset = "BNB"
        svc = service(db, client)
        result = svc.arm_initial_entry(
            signal(trigger=101.0), equity_quote=10_000, candidate_risk_pct=0.004
        )
        client.set_price("BTCUSDT", 101.5)
        recovered = svc.reconcile_pending_entries()
        order = client.get_order("BTCUSDT", orig_client_order_id=result["client_order_id"])
        fills = client.my_trades("BTCUSDT", order_id=order["orderId"])
        expected_fee_quote = sum(
            float(f["commission"]) * 300.0 for f in fills
        )
        trade = db.open_trade("BTCUSDT")
        assert trade is not None, recovered
        assert float(trade["fees"]) == pytest.approx(expected_fee_quote)


def test_unknown_fee_history_keeps_risk_reserved_and_attempts_protection():
    class MissingTradeHistoryExchange(MockExchange):
        def my_trades(self, symbol, order_id=None, limit=1000):
            raise OSError("trade history temporarily unavailable")

    with tempfile.TemporaryDirectory() as d:
        db = Database(os.path.join(d, "campaign.sqlite3"))
        client = MissingTradeHistoryExchange()
        svc = service(db, client)
        result = svc.arm_initial_entry(
            signal(trigger=101.0), equity_quote=10_000, candidate_risk_pct=0.004
        )
        client.set_price("BTCUSDT", 101.5)
        recovered = svc.reconcile_pending_entries()
        assert recovered[0]["state"] == CampaignState.RECONCILE_REQUIRED.value
        campaign = svc.engine.load_campaign(result["campaign_id"])
        assert campaign is not None
        assert campaign.state == CampaignState.RECONCILE_REQUIRED
        assert campaign.tags["fee_accounting_state"] == "UNKNOWN"
        assert db.campaign_risk_reserved_quote() > 0
        stops = [
            order for order in client.open_orders("BTCUSDT")
            if order["side"] == "SELL"
            and str(order.get("clientOrderId", "")).startswith(svc.STOP_PREFIX)
        ]
        assert len(stops) == 1
        assert float(stops[0]["origQty"]) <= float(
            next(b["free"] for b in client.account()["balances"] if b["asset"] == "BTC")
        )


def test_exit_fee_is_included_once_in_closed_trade_pnl():
    with tempfile.TemporaryDirectory() as d:
        db = Database(os.path.join(d, "campaign.sqlite3"))
        client = MockExchange()
        client.commission_rate = 0.001
        client.commission_asset = "USDT"
        svc = service(db, client)
        result = svc.arm_initial_entry(
            signal(trigger=101.0), equity_quote=10_000, candidate_risk_pct=0.004
        )
        client.set_price("BTCUSDT", 101.5)
        svc.reconcile_pending_entries()
        trade_before = db.open_trade("BTCUSDT")
        assert trade_before is not None
        entry_fee = float(trade_before["fees"] or 0.0)
        entry_price = float(trade_before["entry_price"])
        entry_qty = float(svc.engine.load_campaign(result["campaign_id"]).tags["total_entry_net_qty"])

        client.commission_rate = 0.002
        client.set_price("BTCUSDT", 110.0)
        exit_result = svc.exit_market(
            svc.engine.load_campaign(result["campaign_id"]), reason="TEST_EXIT"
        )
        assert exit_result["state"] == "CLOSED"
        row = db.conn.execute(
            "SELECT pnl,fees,quantity FROM trades WHERE symbol='BTCUSDT' ORDER BY id DESC LIMIT 1"
        ).fetchone()
        assert row is not None
        exit_order_id = db.conn.execute(
            "SELECT order_id FROM campaign_orders WHERE campaign_id=? AND purpose='EXIT' ORDER BY id DESC LIMIT 1",
            (result["campaign_id"],),
        ).fetchone()["order_id"]
        exit_fills = client.my_trades("BTCUSDT", order_id=exit_order_id)
        exit_fee = sum(float(f["commission"]) for f in exit_fills)
        exit_quote = sum(float(f["quoteQty"]) for f in exit_fills)
        total_fees = entry_fee + exit_fee
        assert float(row["fees"]) == pytest.approx(total_fees)
        assert float(row["quantity"]) == pytest.approx(entry_qty)
        assert float(row["pnl"]) == pytest.approx(
            exit_quote - entry_price * entry_qty - total_fees
        )
        assert len(db.campaign_fills_for_order("BTCUSDT", exit_order_id)) == len(exit_fills)


def test_partial_exit_keeps_campaign_reserved_and_restores_residual_protection():
    with tempfile.TemporaryDirectory() as d:
        db = Database(os.path.join(d, "campaign.sqlite3"))
        client = MockExchange()
        svc = service(db, client)
        result = svc.arm_initial_entry(
            signal(trigger=101.0), equity_quote=10_000, candidate_risk_pct=0.004
        )
        client.set_price("BTCUSDT", 101.5)
        svc.reconcile_pending_entries()
        campaign = svc.engine.load_campaign(result["campaign_id"])
        assert campaign is not None
        initial_qty = campaign.position_qty

        client.partial_fill_ratio = 0.5
        with pytest.raises(CampaignExecutionError, match="partially filled"):
            svc.exit_market(campaign, reason="TEST_PARTIAL_EXIT")
        refreshed = svc.engine.load_campaign(result["campaign_id"])
        assert refreshed is not None
        assert refreshed.state == CampaignState.RECONCILE_REQUIRED
        assert 0 < refreshed.position_qty < initial_qty
        assert db.campaign_risk_reserved_quote() > 0
        stops = [
            order for order in client.open_orders("BTCUSDT")
            if order["side"] == "SELL"
            and str(order.get("clientOrderId", "")).startswith(svc.STOP_PREFIX)
        ]
        assert len(stops) == 1
        assert float(stops[0]["origQty"]) <= refreshed.position_qty
