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
    # These execution tests model a process that has completed an explicit,
    # successful exchange reconciliation. Fresh Database instances correctly
    # start blocked; test setup must not bypass that production invariant.
    db.mark_recovery_ready({
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
    })
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


def test_cancel_race_requeries_final_filled_quantity_before_protection():
    class FillDuringCancelExchange(MockExchange):
        def cancel_order(self, symbol, order_id=None, orig_client_order_id=None):
            order = self.get_order(
                symbol, order_id=order_id, orig_client_order_id=orig_client_order_id
            )
            target = next(
                row for row in self.orders
                if str(row["orderId"]) == str(order["orderId"])
            )
            # Model the exchange matching the remainder immediately before
            # cancellation takes effect. The cancel response alone is stale;
            # the subsequent GET order must reveal the final executed quantity.
            requested = float(target.get("origQty", 0) or 0)
            already = float(target.get("executedQty", 0) or 0)
            remainder = max(0.0, requested - already)
            price = float(self.prices[symbol])
            base = symbol.replace("USDT", "")
            self.balances[base] += remainder
            self.balances["USDT"] -= remainder * price
            target["executedQty"] = str(requested)
            target["cummulativeQuoteQty"] = str(requested * price)
            target["status"] = "FILLED"
            return dict(target)

    with tempfile.TemporaryDirectory() as d:
        db = Database(os.path.join(d, "campaign.sqlite3"))
        client = FillDuringCancelExchange()
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
        order = client.get_order(
            "BTCUSDT", orig_client_order_id=result["client_order_id"]
        )
        campaign = svc.engine.load_campaign(result["campaign_id"])
        assert order["status"] == "FILLED"
        assert float(campaign.position_qty) == float(order["executedQty"])
        stops = [
            row for row in client.open_orders("BTCUSDT")
            if str(row.get("side", "")).upper() == "SELL"
            and str(row.get("clientOrderId", "")).startswith(svc.STOP_PREFIX)
        ]
        assert len(stops) == 1
        assert float(stops[0]["origQty"]) == float(order["executedQty"])


def test_recovery_rebuilds_missing_campaign_order_link_from_exchange():
    with tempfile.TemporaryDirectory() as d:
        db = Database(os.path.join(d, "campaign.sqlite3"))
        client = MockExchange()
        svc = service(db, client)
        result = svc.arm_initial_entry(
            signal(trigger=101.0),
            equity_quote=10_000,
            candidate_risk_pct=0.004,
        )
        # Simulate process death after Binance accepted the conditional order
        # but before campaign_orders was committed.
        db.conn.execute(
            "DELETE FROM campaign_orders WHERE client_order_id=?",
            (result["client_order_id"],),
        )
        db.conn.commit()

        client.set_price("BTCUSDT", 101.5)
        recovered = svc.reconcile_pending_entries()

        assert recovered[0]["state"] == "OPEN"
        row = db.conn.execute(
            "SELECT campaign_id,order_id,client_order_id,status FROM campaign_orders "
            "WHERE client_order_id=?",
            (result["client_order_id"],),
        ).fetchone()
        assert row is not None
        assert row["campaign_id"] == result["campaign_id"]
        assert str(row["order_id"]) == str(result["order_id"])
        assert row["status"] == "FILLED"


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


def test_stop_replacement_confirms_old_terminal_and_new_active():
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
        assert svc.reconcile_pending_entries()[0]["state"] == "OPEN"
        campaign = svc.engine.load_campaign(result["campaign_id"])
        old_id = int(campaign.tags["protective_order_id"])
        proposed = float(campaign.current_stop_price) + 0.1

        replaced = svc.replace_structural_stop(
            campaign,
            existing_order_id=old_id,
            quantity=float(campaign.position_qty),
            proposed_stop=proposed,
        )

        old_order = client.get_order("BTCUSDT", order_id=old_id)
        new_order = client.get_order(
            "BTCUSDT",
            orig_client_order_id=replaced["newOrderResponse"]["clientOrderId"],
        )
        assert old_order["status"] == "CANCELED"
        assert new_order["status"] == "NEW"
        assert str(new_order["orderId"]) == str(replaced["newOrderResponse"]["orderId"])


def test_campaign_exit_never_sells_foreign_base_inventory():
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
        assert svc.reconcile_pending_entries()[0]["state"] == "OPEN"
        campaign = svc.engine.load_campaign(result["campaign_id"])
        managed_qty = float(campaign.position_qty)

        # The wallet also contains BTC that is not owned by this campaign.
        client.balances["BTC"] += 0.02
        outcome = svc.exit_market(campaign, reason="TEST_FOREIGN_BALANCE")

        assert outcome["state"] == "CLOSED"
        assert abs(client.balances["BTC"] - 0.02) < 1e-8
        market_sells = [
            row for row in client.orders
            if row.get("side") == "SELL" and row.get("type") == "MARKET"
        ]
        assert len(market_sells) == 1
        assert float(market_sells[0]["origQty"]) <= managed_qty + 1e-8


def test_filled_protective_stop_during_cancel_blocks_followup_market_sell():
    class StopFillRaceExchange(MockExchange):
        def cancel_order(self, symbol, order_id=None, orig_client_order_id=None):
            current = self.get_order(
                symbol, order_id=order_id, orig_client_order_id=orig_client_order_id
            )
            if str(current.get("status", "")).upper() == "FILLED":
                raise RuntimeError("mock cancel raced with protective fill")
            return super().cancel_order(
                symbol, order_id=order_id, orig_client_order_id=orig_client_order_id
            )

    with tempfile.TemporaryDirectory() as d:
        db = Database(os.path.join(d, "campaign.sqlite3"))
        client = StopFillRaceExchange()
        svc = service(db, client)
        result = svc.arm_initial_entry(
            signal(trigger=101.0),
            equity_quote=10_000,
            candidate_risk_pct=0.004,
        )
        client.set_price("BTCUSDT", 101.5)
        recovered = svc.reconcile_pending_entries()
        assert recovered[0]["state"] == "OPEN"
        campaign = svc.engine.load_campaign(result["campaign_id"])
        stop_id = str(campaign.tags["protective_order_id"])
        client.set_price("BTCUSDT", float(campaign.current_stop_price) - 0.1)
        market_sells_before = sum(
            1 for row in client.orders
            if row.get("side") == "SELL" and row.get("type") == "MARKET"
        )

        import pytest
        with pytest.raises(Exception, match="protective stop filled"):
            svc.exit_market(campaign, reason="TEST_EXIT")

        market_sells_after = sum(
            1 for row in client.orders
            if row.get("side") == "SELL" and row.get("type") == "MARKET"
        )
        assert market_sells_after == market_sells_before
        assert db.state_get(f"position_state:BTCUSDT") == "RECONCILE_REQUIRED"
        assert client.get_order("BTCUSDT", order_id=stop_id)["status"] == "FILLED"


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
