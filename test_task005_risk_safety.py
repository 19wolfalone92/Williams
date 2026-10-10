"""Task 005 fail-closed regression tests; all execution tests use MockExchange only."""
from concurrent.futures import ThreadPoolExecutor
import multiprocessing
from datetime import datetime as RealDatetime, timezone
from dataclasses import replace
from decimal import Decimal

import pytest

from campaign_execution import CampaignExecutionError
from campaign_engine import CampaignEngine
from campaign_model import CampaignState
from db import Database
from equity_breaker import EquityCircuitBreaker
import equity_breaker as equity_breaker_module
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
    with pytest.raises(CampaignExecutionError, match="structural stop"):
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



def test_closed_trade_with_null_pnl_is_unknown_not_zero():
    db = Database(":memory:")
    db.conn.execute(
        "INSERT INTO trades (entry_time, exit_time, symbol, pnl) "
        "VALUES (datetime('now'), datetime('now'), 'BTCUSDT', NULL)"
    )
    db.conn.commit()
    allowed, reason = EquityCircuitBreaker().check(db, 10_000.0)
    assert not allowed
    assert "realized PnL unavailable" in reason


def _process_arm_entry(db_path, symbol, start_event, result_queue):
    """Spawn-safe worker: each process owns an independent SQLite connection."""
    from db import Database
    from mock_exchange import MockExchange
    from campaign_execution import CampaignExecutionError
    from test_campaign_execution import service, signal

    if not start_event.wait(timeout=10):
        result_queue.put((symbol, "start-timeout"))
        return
    db = Database(db_path)
    client = MockExchange()
    svc = service(db, client)
    try:
        svc.arm_initial_entry(
            signal(symbol=symbol),
            equity_quote=10_000.0,
            candidate_risk_pct=0.0024,
        )
        result_queue.put((symbol, "accepted"))
    except CampaignExecutionError:
        result_queue.put((symbol, "blocked"))
    except Exception as exc:
        result_queue.put((symbol, f"error:{type(exc).__name__}:{exc}"))
    finally:
        db.conn.close()


def test_separate_processes_cannot_reserve_same_portfolio_budget(tmp_path):
    """Independent processes must serialize through SQLite, not a Python lock."""
    ctx = multiprocessing.get_context("spawn")
    db_path = str(tmp_path / "risk-reservation.sqlite3")
    db = Database(db_path)
    db.conn.close()  # schema exists before both independent processes start

    start_event = ctx.Event()
    result_queue = ctx.Queue()
    workers = [
        ctx.Process(target=_process_arm_entry, args=(db_path, symbol, start_event, result_queue))
        for symbol in ("BTCUSDT", "ETHUSDT")
    ]
    for worker in workers:
        worker.start()
    start_event.set()
    results = [result_queue.get(timeout=20) for _ in workers]
    for worker in workers:
        worker.join(timeout=20)
        assert not worker.is_alive()
        assert worker.exitcode == 0

    outcomes = [state for _, state in results]
    assert outcomes.count("accepted") == 1, results
    assert outcomes.count("blocked") == 1, results
    verify = Database(db_path)
    try:
        assert verify.active_campaign_count() == 1
        assert verify.state_get("risk_entry_reservation") is not None or verify.campaign_risk_reserved_quote() > 0
        assert float(verify.state_get("daily_start_equity")) == pytest.approx(10_000.0)
        assert verify.state_get("daily_risk_day")
    finally:
        verify.conn.close()


def _sample_campaign_fill(**overrides):
    fill = {
        "campaign_id": "campaign-a",
        "order_id": "9001",
        "trade_id": "12345",
        "symbol": "BTCUSDT",
        "side": "BUY",
        "quantity": 0.01,
        "price": 50000.0,
        "quote_quantity": 500.0,
        "fee_quote": 0.5,
        "commission_base": 0.0,
        "commission_asset": "USDT",
        "raw_json": {"commission": "0.5", "commissionAsset": "USDT"},
    }
    fill.update(overrides)
    return fill


def test_campaign_fill_replay_is_idempotent_and_conflicts_are_rejected():
    db = Database(":memory:")
    try:
        fill = _sample_campaign_fill()
        assert db.save_campaign_fill(fill) is True
        assert db.save_campaign_fill(fill) is False
        assert len(db.campaign_fills_for_order("BTCUSDT", "9001")) == 1
        with pytest.raises(RuntimeError, match="conflicting replay"):
            db.save_campaign_fill(_sample_campaign_fill(fee_quote=0.0))
        assert len(db.campaign_fills_for_order("BTCUSDT", "9001")) == 1
        assert db.campaign_fills_for_order("BTCUSDT", "9001")[0]["fee_quote"] == 0.5
    finally:
        db.conn.close()


def test_campaign_fill_requires_authoritative_trade_id_and_valid_fee_fields():
    db = Database(":memory:")
    try:
        with pytest.raises(ValueError, match="authoritative exchange trade_id"):
            db.save_campaign_fill(_sample_campaign_fill(trade_id=None))
        with pytest.raises(ValueError, match="invalid quantity"):
            db.save_campaign_fill(_sample_campaign_fill(fee_quote=float("nan")))
    finally:
        db.conn.close()


def test_utc_boundary_uses_recent_pre_midnight_equity_snapshot(monkeypatch):
    db = Database(":memory:")
    fixed_now = RealDatetime(2026, 10, 10, 0, 0, 10, tzinfo=timezone.utc)
    fixed_ms = int(fixed_now.timestamp() * 1000)

    class FrozenDateTime(RealDatetime):
        @classmethod
        def now(cls, tz=None):
            return cls.fromtimestamp(fixed_ms / 1000.0, tz=tz)

    monkeypatch.setattr(equity_breaker_module, "datetime", FrozenDateTime)
    observed = int(RealDatetime(2026, 10, 9, 23, 59, 0, tzinfo=timezone.utc).timestamp() * 1000)
    db.state_set("daily_risk_day", "2026-10-09")
    db.state_set("daily_start_equity", "10000")
    db.state_set("last_observed_equity", "10000")
    db.state_set("last_observed_equity_at_ms", str(observed))

    allowed, reason = EquityCircuitBreaker().check(db, 9_950.0)
    assert allowed, reason
    assert db.state_get("daily_risk_day") == "2026-10-10"
    assert float(db.state_get("daily_start_equity")) == pytest.approx(10_000.0)


def test_utc_boundary_after_long_outage_blocks_without_trusted_snapshot(monkeypatch):
    db = Database(":memory:")
    fixed_now = RealDatetime(2026, 10, 10, 0, 10, 0, tzinfo=timezone.utc)
    fixed_ms = int(fixed_now.timestamp() * 1000)

    class FrozenDateTime(RealDatetime):
        @classmethod
        def now(cls, tz=None):
            return cls.fromtimestamp(fixed_ms / 1000.0, tz=tz)

    monkeypatch.setattr(equity_breaker_module, "datetime", FrozenDateTime)
    observed = int(RealDatetime(2026, 10, 9, 23, 0, 0, tzinfo=timezone.utc).timestamp() * 1000)
    db.state_set("daily_risk_day", "2026-10-09")
    db.state_set("daily_start_equity", "10000")
    db.state_set("last_observed_equity", "10000")
    db.state_set("last_observed_equity_at_ms", str(observed))

    allowed, reason = EquityCircuitBreaker().check(db, 10_000.0)
    assert not allowed
    assert "new entries blocked" in reason
    assert db.state_get("daily_risk_day") == "2026-10-09"


def test_missing_daily_baseline_with_existing_campaign_blocks_entry():
    db = Database(":memory:")
    campaign = CampaignEngine(db).create_campaign(signal(), initial_risk_pct=0.0024)
    db.save_campaign(campaign)
    client = MockExchange()
    svc = service(db, client)
    with pytest.raises(CampaignExecutionError, match="daily risk gate blocked"):
        svc.arm_initial_entry(
            signal(symbol="ETHUSDT"), equity_quote=10_000.0, candidate_risk_pct=0.0024
        )
    assert client.orders == []
    assert db.state_get("daily_start_equity") is None


def test_protective_quantity_uses_common_lot_and_market_lot_grid():
    class DifferentLotGridsExchange(MockExchange):
        def exchange_info(self, symbol=None):
            info = super().exchange_info(symbol)
            for row in info["symbols"]:
                row["filters"] = [
                    f for f in row["filters"]
                    if f.get("filterType") not in {"LOT_SIZE", "MARKET_LOT_SIZE"}
                ] + [
                    {"filterType": "LOT_SIZE", "stepSize": "0.002", "minQty": "0.002", "maxQty": "10"},
                    {"filterType": "MARKET_LOT_SIZE", "stepSize": "0.003", "minQty": "0.003", "maxQty": "9"},
                ]
            return info

    client = DifferentLotGridsExchange()
    svc = service(Database(":memory:"), client)
    qty = svc._normalize_qty("BTCUSDT", 0.011)
    assert qty == pytest.approx(0.006)
    assert Decimal(str(qty)) % Decimal("0.002") == 0
    assert Decimal(str(qty)) % Decimal("0.003") == 0
    with pytest.raises(CampaignExecutionError, match="common LOT_SIZE/MARKET_LOT_SIZE"):
        svc._normalize_qty("BTCUSDT", 0.005)
