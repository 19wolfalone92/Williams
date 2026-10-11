"""Task 005 fail-closed regression tests; all execution tests use MockExchange only."""
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
import multiprocessing
import hashlib
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
from portfolio_trader import MultiPositionTrader
from binance_client import BinanceAPIError, BinanceSpotClient

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


def test_transaction_begin_failure_never_reaches_exchange(monkeypatch):
    db = Database(":memory:")
    client = MockExchange()
    svc = service(db, client)

    @contextmanager
    def fail_begin():
        raise OSError("SQLite BEGIN failed")
        yield db

    monkeypatch.setattr(db, "transaction", fail_begin)
    with pytest.raises(OSError, match="BEGIN failed"):
        svc.arm_initial_entry(
            signal(), equity_quote=10_000.0, candidate_risk_pct=0.0024
        )
    assert client.orders == []


def test_reservation_insert_failure_rolls_back_and_never_reaches_exchange(monkeypatch):
    db = Database(":memory:")
    client = MockExchange()
    svc = service(db, client)

    def fail_claim(*args, **kwargs):
        raise OSError("reservation insert failed")

    monkeypatch.setattr(db, "try_claim_state", fail_claim)
    with pytest.raises(OSError, match="reservation insert failed"):
        svc.arm_initial_entry(
            signal(), equity_quote=10_000.0, candidate_risk_pct=0.0024
        )
    assert client.orders == []
    assert db.active_campaign_count() == 0


def test_transaction_commit_failure_never_reaches_exchange(monkeypatch):
    db = Database(":memory:")
    client = MockExchange()
    svc = service(db, client)
    original_transaction = db.transaction

    @contextmanager
    def fail_outer_commit():
        if db._transaction_active:
            with original_transaction():
                yield db
            return
        with original_transaction():
            yield db
            raise OSError("SQLite commit failed")

    monkeypatch.setattr(db, "transaction", fail_outer_commit)
    with pytest.raises(OSError, match="commit failed"):
        svc.arm_initial_entry(
            signal(), equity_quote=10_000.0, candidate_risk_pct=0.0024
        )
    assert client.orders == []
    assert db.active_campaign_count() == 0


def test_reservation_readback_failure_keeps_campaign_blocked_without_order(monkeypatch):
    db = Database(":memory:")
    client = MockExchange()
    svc = service(db, client)
    original_state_get = db.state_get
    calls = {"reservation": 0}

    def fail_after_commit(key, default=None):
        if key == "risk_entry_reservation":
            calls["reservation"] += 1
            if calls["reservation"] >= 2:
                raise OSError("reservation readback failed")
        return original_state_get(key, default)

    monkeypatch.setattr(db, "state_get", fail_after_commit)
    with pytest.raises(CampaignExecutionError, match="reservation not verified"):
        svc.arm_initial_entry(
            signal(), equity_quote=10_000.0, candidate_risk_pct=0.0024
        )
    monkeypatch.setattr(db, "state_get", original_state_get)
    assert client.orders == []
    assert db.active_campaign_count() == 1
    campaign = db.open_campaigns()[0]
    assert campaign["state"] == CampaignState.RECONCILE_REQUIRED.value
    assert original_state_get("risk_entry_reservation") is not None
    assert db.campaign_risk_reserved_quote() > 0


def test_ambiguous_exchange_submit_is_not_blindly_retried():
    class AcceptedThenTimeoutExchange(MockExchange):
        def __init__(self):
            super().__init__()
            self.submit_calls = 0

        def order_safe(self, *args, **kwargs):
            self.submit_calls += 1
            super().order_safe(*args, **kwargs)
            raise TimeoutError("response lost after exchange accepted request")

    db = Database(":memory:")
    client = AcceptedThenTimeoutExchange()
    svc = service(db, client)
    with pytest.raises(Exception):
        svc.arm_initial_entry(
            signal(), equity_quote=10_000.0, candidate_risk_pct=0.0024
        )
    assert client.submit_calls == 1
    assert len(client.orders) == 1
    assert db.active_campaign_count() == 1
    campaign = db.open_campaigns()[0]
    assert campaign["state"] == CampaignState.RECONCILE_REQUIRED.value
    assert db.campaign_risk_reserved_quote() > 0


class _FeeLookupFailureClient:
    def my_trades(self, symbol, order_id=None, limit=1000):
        raise OSError("simulated trade-history outage")


class _FeeLookupDb:
    def __init__(self):
        self.events = []

    def log_event(self, *args, **kwargs):
        self.events.append((args, kwargs))


def test_portfolio_execution_unknown_fee_history_fails_closed():
    trader = object.__new__(MultiPositionTrader)
    trader.client = _FeeLookupFailureClient()
    trader.db = _FeeLookupDb()
    with pytest.raises(RuntimeError, match="reconciliation required"):
        trader._authoritative_execution(
            "BTCUSDT",
            {"orderId": 7, "executedQty": "0.01", "cummulativeQuoteQty": "500"},
        )
    assert trader.db.events
    assert trader.db.events[0][0][1] == "fill_commission_lookup_failed"


def test_portfolio_execution_missing_commission_asset_is_unknown():
    trader = object.__new__(MultiPositionTrader)
    trader.client = object()
    trader.db = _FeeLookupDb()
    with pytest.raises(RuntimeError, match="fee status UNKNOWN"):
        trader._authoritative_execution(
            "BTCUSDT",
            {
                "orderId": 7,
                "executedQty": "0.01",
                "cummulativeQuoteQty": "500",
                "fills": [{"price": "50000", "qty": "0.01"}],
            },
        )



def _emergency_intent_fixture(**overrides):
    intent = {
        "operation_id": "a" * 64,
        "identity_version": 1,
        "environment": "BINANCE_SPOT_TESTNET",
        "account_scope": "b" * 24,
        "symbol": "BTCUSDT",
        "trade_id": "42",
        "operation_type": "EMERGENCY_MARKET_SELL",
        "reason": "STOP_LOSS_REACHED_BEFORE_OCO",
        "requested_qty": "0.001000",
        "parameters_hash": "c" * 64,
        "client_order_id": "WILLV4_EMERGENCY_aaaaaaaaaaaaaaaaaa",
    }
    intent.update(overrides)
    return intent


def _process_claim_emergency_sell(db_path, start_event, result_queue):
    from db import Database
    db = Database(db_path)
    try:
        if not start_event.wait(timeout=10):
            result_queue.put("start-timeout")
            return
        row = db.claim_emergency_sell_intent(_emergency_intent_fixture())
        try:
            db.transition_emergency_sell_intent(row["operation_id"], "SUBMITTING")
            result_queue.put("submit-permit")
        except RuntimeError:
            result_queue.put("blocked")
    except Exception as exc:
        result_queue.put(f"error:{type(exc).__name__}:{exc}")
    finally:
        db.conn.close()


def test_emergency_sell_intent_is_immutable_and_durable():
    db = Database(":memory:")
    try:
        intent = _emergency_intent_fixture()
        first = db.claim_emergency_sell_intent(intent)
        assert first["status"] == "PREPARED"
        second = db.claim_emergency_sell_intent(intent)
        assert second["operation_id"] == first["operation_id"]
        assert second["client_order_id"] == intent["client_order_id"]
        with pytest.raises(RuntimeError, match="identity conflict"):
            db.claim_emergency_sell_intent(
                _emergency_intent_fixture(reason="different reason", parameters_hash="d" * 64)
            )
        db.transition_emergency_sell_intent(intent["operation_id"], "SUBMITTING")
        with pytest.raises(RuntimeError, match="illegal emergency SELL intent transition"):
            db.transition_emergency_sell_intent(intent["operation_id"], "SUBMITTING")
        row = db.conn.execute(
            "SELECT status,requested_qty,client_order_id FROM emergency_sell_intents WHERE operation_id=?",
            (intent["operation_id"],),
        ).fetchone()
        assert row["status"] == "SUBMITTING"
        assert row["requested_qty"] == "0.001000"
        assert row["client_order_id"] == intent["client_order_id"]
    finally:
        db.conn.close()


def test_independent_processes_cannot_both_claim_emergency_sell_submit_permission(tmp_path):
    ctx = multiprocessing.get_context("spawn")
    db_path = str(tmp_path / "emergency-intent.sqlite3")
    db = Database(db_path)
    db.conn.close()
    start_event = ctx.Event()
    result_queue = ctx.Queue()
    workers = [
        ctx.Process(target=_process_claim_emergency_sell, args=(db_path, start_event, result_queue))
        for _ in range(8)
    ]
    for worker in workers:
        worker.start()
    start_event.set()
    outcomes = [result_queue.get(timeout=30) for _ in workers]
    for worker in workers:
        worker.join(timeout=30)
        assert not worker.is_alive()
        assert worker.exitcode == 0
    assert outcomes.count("submit-permit") == 1, outcomes
    assert outcomes.count("blocked") == 7, outcomes
    verify = Database(db_path)
    try:
        rows = verify.conn.execute("SELECT * FROM emergency_sell_intents").fetchall()
        assert len(rows) == 1
        assert rows[0]["status"] == "SUBMITTING"
    finally:
        verify.conn.close()



def _prepare_emergency_sink_intent(db, **overrides):
    intent = _emergency_intent_fixture(**overrides)
    db.claim_emergency_sell_intent(intent)
    db.transition_emergency_sell_intent(intent["operation_id"], "SUBMITTING")
    return intent


def test_emergency_sell_rest_sink_denies_direct_call_without_durable_authorizer():
    client = BinanceSpotClient("fake-api-key", "fake-api-secret", testnet=True)
    calls = []
    client.session.request = lambda *args, **kwargs: calls.append((args, kwargs))
    with pytest.raises(BinanceAPIError, match="authorizer unavailable"):
        client.order(
            "BTCUSDT", "SELL", "MARKET", quantity="0.001000",
            new_client_order_id="WILLV4_EMERGENCY_aaaaaaaaaaaaaaaaaa",
        )
    assert calls == []


def test_emergency_sell_rest_sink_consumes_durable_permit_exactly_once():
    from types import SimpleNamespace

    db = Database(":memory:")
    try:
        intent = _prepare_emergency_sink_intent(
            db, account_scope=hashlib.sha256(b"fake-api-key").hexdigest()[:24]
        )
        client = BinanceSpotClient("fake-api-key", "fake-api-secret", testnet=True)
        client.emergency_sell_authorizer = db.authorize_emergency_sell_mutation
        calls = []

        class Response:
            status_code = 200
            headers = {}
            content = b'{"symbol":"BTCUSDT","orderId":7,"status":"FILLED"}'
            text = content.decode()

            def json(self):
                return {"symbol": "BTCUSDT", "orderId": 7, "status": "FILLED"}

        def fake_request(*args, **kwargs):
            calls.append((args, kwargs))
            return Response()

        client.session.request = fake_request
        result = client.order(
            "BTCUSDT", "SELL", "MARKET", quantity="0.001000",
            new_client_order_id=intent["client_order_id"],
        )
        assert result["status"] == "FILLED"
        assert len(calls) == 1
        row = db.conn.execute(
            "SELECT mutation_started_at,status FROM emergency_sell_intents WHERE operation_id=?",
            (intent["operation_id"],),
        ).fetchone()
        assert row["mutation_started_at"]
        assert row["status"] == "SUBMITTING"

        with pytest.raises(BinanceAPIError, match="permit not proven"):
            client.order(
                "BTCUSDT", "SELL", "MARKET", quantity="0.001000",
                new_client_order_id=intent["client_order_id"],
            )
        assert len(calls) == 1
    finally:
        db.conn.close()


def test_emergency_sell_rest_sink_rejects_quantity_and_account_mismatch():
    db = Database(":memory:")
    try:
        intent = _prepare_emergency_sink_intent(db)
        with pytest.raises(RuntimeError, match="quantity mismatch"):
            db.authorize_emergency_sell_mutation(
                symbol="BTCUSDT", quantity="0.001001",
                client_order_id=intent["client_order_id"],
                environment="BINANCE_SPOT_TESTNET",
                account_scope="b" * 24,
            )
        with pytest.raises(RuntimeError, match="account_scope mismatch"):
            db.authorize_emergency_sell_mutation(
                symbol="BTCUSDT", quantity="0.001000",
                client_order_id=intent["client_order_id"],
                environment="BINANCE_SPOT_TESTNET",
                account_scope="x" * 24,
            )
        row = db.conn.execute(
            "SELECT mutation_started_at FROM emergency_sell_intents WHERE operation_id=?",
            (intent["operation_id"],),
        ).fetchone()
        assert row["mutation_started_at"] is None
    finally:
        db.conn.close()



def _process_consume_emergency_permit(db_path, start_event, result_queue):
    from db import Database
    db = Database(db_path)
    try:
        if not start_event.wait(timeout=10):
            result_queue.put("start-timeout")
            return
        try:
            operation_id = db.authorize_emergency_sell_mutation(
                symbol="BTCUSDT",
                quantity="0.001000",
                client_order_id="WILLV4_EMERGENCY_aaaaaaaaaaaaaaaaaa",
                environment="BINANCE_SPOT_TESTNET",
                account_scope="b" * 24,
            )
            result_queue.put("authorized:" + operation_id)
        except Exception as exc:
            result_queue.put("blocked:" + type(exc).__name__)
    finally:
        db.conn.close()


def test_independent_processes_can_consume_emergency_sink_permit_only_once(tmp_path):
    ctx = multiprocessing.get_context("spawn")
    db_path = str(tmp_path / "emergency-sink-permit.sqlite3")
    db = Database(db_path)
    intent = _prepare_emergency_sink_intent(db)
    db.conn.close()
    start_event = ctx.Event()
    result_queue = ctx.Queue()
    workers = [
        ctx.Process(
            target=_process_consume_emergency_permit,
            args=(db_path, start_event, result_queue),
        )
        for _ in range(8)
    ]
    for worker in workers:
        worker.start()
    start_event.set()
    outcomes = [result_queue.get(timeout=30) for _ in workers]
    for worker in workers:
        worker.join(timeout=30)
        assert not worker.is_alive()
        assert worker.exitcode == 0
    assert sum(x.startswith("authorized:") for x in outcomes) == 1, outcomes
    assert sum(x.startswith("blocked:") for x in outcomes) == 7, outcomes
    verify = Database(db_path)
    try:
        row = verify.conn.execute(
            "SELECT mutation_started_at,status FROM emergency_sell_intents WHERE operation_id=?",
            (intent["operation_id"],),
        ).fetchone()
        assert row["mutation_started_at"]
        assert row["status"] == "SUBMITTING"
    finally:
        verify.conn.close()


def test_database_nested_transaction_uses_savepoint_and_outer_rollback_is_safe():
    db = Database(":memory:")
    db.conn.execute("CREATE TABLE tx_probe (value INTEGER PRIMARY KEY)")
    try:
        with db.transaction():
            db.conn.execute("INSERT INTO tx_probe(value) VALUES(1)")
            with pytest.raises(ValueError, match="inner failure"):
                with db.transaction():
                    db.conn.execute("INSERT INTO tx_probe(value) VALUES(2)")
                    raise ValueError("inner failure")
            db.conn.execute("INSERT INTO tx_probe(value) VALUES(3)")

        assert [r["value"] for r in db.conn.execute(
            "SELECT value FROM tx_probe ORDER BY value"
        ).fetchall()] == [1, 3]
        assert not db.conn.in_transaction

        with pytest.raises(RuntimeError, match="outer failure"):
            with db.transaction():
                db.conn.execute("INSERT INTO tx_probe(value) VALUES(4)")
                raise RuntimeError("outer failure")

        assert [r["value"] for r in db.conn.execute(
            "SELECT value FROM tx_probe ORDER BY value"
        ).fetchall()] == [1, 3]
        assert not db.conn.in_transaction

        with db.transaction():
            db.conn.execute("INSERT INTO tx_probe(value) VALUES(5)")
        assert [r["value"] for r in db.conn.execute(
            "SELECT value FROM tx_probe ORDER BY value"
        ).fetchall()] == [1, 3, 5]
        assert not db.conn.in_transaction
    finally:
        db.conn.close()


def test_shared_connection_blocks_other_thread_sql_until_transaction_finishes():
    import threading
    import time

    db = Database(":memory:")
    db.conn.execute("CREATE TABLE tx_probe (value INTEGER PRIMARY KEY)")
    transaction_entered = threading.Event()
    outside_write_done = threading.Event()
    errors = []

    def outside_write():
        try:
            assert transaction_entered.wait(timeout=5)
            db.conn.execute("INSERT INTO tx_probe(value) VALUES(2)")
            outside_write_done.set()
        except Exception as exc:
            errors.append(exc)
            outside_write_done.set()

    worker = threading.Thread(target=outside_write)
    worker.start()
    try:
        with db.transaction():
            db.conn.execute("INSERT INTO tx_probe(value) VALUES(1)")
            transaction_entered.set()
            time.sleep(0.05)
            assert not outside_write_done.is_set(), (
                "another thread executed SQL inside the transaction owner's scope"
            )
        assert outside_write_done.wait(timeout=5)
        worker.join(timeout=5)
        assert not worker.is_alive()
        assert not errors
        assert [r["value"] for r in db.conn.execute(
            "SELECT value FROM tx_probe ORDER BY value"
        ).fetchall()] == [1, 2]
        assert not db.conn.in_transaction
    finally:
        if worker.is_alive():
            worker.join(timeout=5)
        db.conn.close()


def test_database_rejects_unowned_open_transaction_without_rolling_it_back():
    db = Database(":memory:")
    db.conn.execute("CREATE TABLE tx_probe (value INTEGER PRIMARY KEY)")
    try:
        # Explicit low-level BEGIN is intentionally outside Database.transaction().
        db.conn.execute("BEGIN IMMEDIATE")
        db.conn.execute("INSERT INTO tx_probe(value) VALUES(7)")
        with pytest.raises(RuntimeError, match="unowned active transaction"):
            with db.transaction():
                pass
        # The transaction API must not roll back a transaction it does not own.
        assert db.conn.in_transaction
        assert db.conn.execute("SELECT value FROM tx_probe").fetchone()["value"] == 7
        db.conn.rollback()
        assert not db.conn.in_transaction
        with db.transaction():
            db.conn.execute("INSERT INTO tx_probe(value) VALUES(8)")
        assert db.conn.execute("SELECT value FROM tx_probe").fetchone()["value"] == 8
    finally:
        if db.conn.in_transaction:
            db.conn.rollback()
        db.conn.close()
