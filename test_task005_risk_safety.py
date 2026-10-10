"""Task 005 fail-closed regression tests; all execution tests use MockExchange only."""
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace

import pytest

from campaign_execution import CampaignExecutionError
from campaign_engine import CampaignEngine
from campaign_model import CampaignState
from db import Database
from equity_breaker import EquityCircuitBreaker
from mock_exchange import MockExchange
from risk_engine import RiskEngine

from test_campaign_execution import service, signal


def test_invalid_prices_and_stop_geometry_block_risk():
    engine = RiskEngine(balance_quote=10_000.0, risk_per_trade_pct=0.0025)
    assert not engine.analyse("BTCUSDT", float("nan"), 1.0).allowed
    assert not engine.analyse("BTCUSDT", 100.0, 0.0).allowed
    invalid_stop = engine.analyse(
        "BTCUSDT", 100.0, 1.0, invalidation_price=101.0, side="LONG"
    )
    assert not invalid_stop.allowed

    db = Database(":memory:")
    client = MockExchange()
    svc = service(db, client)
    with pytest.raises(CampaignExecutionError, match="invalid campaign structural stop"):
        svc.arm_initial_entry(
            replace(signal(), protective_reference=102.0),
            equity_quote=10_000.0,
            candidate_risk_pct=0.0024,
        )
    assert client.orders == []


def test_missing_exchange_filters_block_without_order():
    class MissingFiltersExchange(MockExchange):
        def exchange_info(self, symbol=None):
            row = super().exchange_info(symbol)["symbols"][0]
            row["filters"] = [
                {"filterType": "MIN_NOTIONAL", "minNotional": "10"}
            ]
            return {"symbols": [row]}

    db = Database(":memory:")
    client = MissingFiltersExchange()
    svc = service(db, client)
    with pytest.raises(CampaignExecutionError, match="filters are missing"):
        svc.arm_initial_entry(
            signal(), equity_quote=10_000.0, candidate_risk_pct=0.0024
        )
    assert client.orders == []


def test_invalid_combined_lot_rounding_is_rejected():
    db = Database(":memory:")
    svc = service(db, MockExchange())
    filters = {
        "LOT_SIZE": {"stepSize": "0.01", "minQty": "0.01", "maxQty": "10"},
        "MARKET_LOT_SIZE": {"stepSize": "1", "minQty": "1", "maxQty": "10"},
        "MIN_NOTIONAL": {"minNotional": "10"},
    }
    with pytest.raises(CampaignExecutionError):
        svc._normalized_entry_qty("BTCUSDT", notional=1.5, trigger=100.0, filters=filters)


def test_insufficient_free_usdt_blocks_entry_before_submission():
    db = Database(":memory:")
    client = MockExchange()
    client.balances["USDT"] = 1.0
    svc = service(db, client)
    with pytest.raises(CampaignExecutionError):
        svc.arm_initial_entry(
            signal(), equity_quote=10_000.0, candidate_risk_pct=0.0024
        )
    assert client.orders == []


def test_two_symbols_cannot_reserve_same_portfolio_capacity_concurrently():
    db = Database(":memory:")
    client = MockExchange()
    svc = service(db, client)

    def arm(symbol):
        try:
            return svc.arm_initial_entry(
                signal(symbol=symbol),
                equity_quote=10_000.0,
                candidate_risk_pct=0.0024,
            )
        except CampaignExecutionError as exc:
            return exc

    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(arm, ["BTCUSDT", "ETHUSDT"]))

    assert sum(isinstance(r, dict) for r in results) == 1
    assert sum(isinstance(r, CampaignExecutionError) for r in results) == 1
    assert db.active_campaign_count() == 1
    assert len(client.open_orders()) == 1


def test_campaign_reserved_risk_survives_reconcile_required_state():
    db = Database(":memory:")
    engine = CampaignEngine(db)
    campaign = engine.create_campaign(signal(), initial_risk_pct=0.0024)
    campaign.state = CampaignState.RECONCILE_REQUIRED
    campaign.pending_risk_quote = 24.0
    campaign.capital_reserved_quote = 250.0
    db.save_campaign(campaign)

    assert db.active_campaign_count() == 1
    assert db.campaign_risk_reserved_quote() >= 24.0

    campaign.state = CampaignState.CLOSED
    campaign.pending_risk_quote = 0.0
    campaign.open_risk_quote = 0.0
    db.save_campaign(campaign)
    assert db.campaign_risk_reserved_quote() == 0.0


def test_daily_loss_latches_across_breaker_restart():
    db = Database(":memory:")
    breaker = EquityCircuitBreaker(max_daily_loss_pct=0.01)
    allowed, _ = breaker.check(db, 10_000.0)
    assert allowed

    allowed, reason = breaker.check(db, 9_899.0)
    assert not allowed
    assert "tripped" in reason

    restarted = EquityCircuitBreaker(max_daily_loss_pct=0.01)
    allowed, reason = restarted.check(db, 9_899.0)
    assert not allowed
    assert "latched" in reason


def test_unknown_realized_pnl_fails_closed():
    class UnreadablePnl:
        def pnl_today_all(self):
            raise OSError("ledger is unreadable")

    allowed, reason = EquityCircuitBreaker().check(UnreadablePnl(), 10_000.0)
    assert not allowed
    assert "realized PnL unavailable" in reason
