import pytest

from binance_usdm_futures_client import FuturesAPIError
from campaign_model import SignalRole, SignalSpec, SignalType
from db import Database
from execution_barrier import ExecutionBarrier
from futures_runtime import FuturesRuntime
from futures_campaign_execution import (
    FuturesCampaignExecutionError,
    FuturesCampaignExecutionService,
)
from market_context import ContextCache, TFMarketContext


class FakeFuturesClient:
    is_usdm_futures = True

    def __init__(self, mark_price):
        self.mark = float(mark_price)
        self.stop_entries = []
        self.market_exits = []
        self.protective_stops = []
        self.protection_response_missing_ids = False
        self.algo_status = "NEW"
        self.cancel_confirms = True
        self._position = {
            "symbol": "BTCUSDT",
            "positionAmt": "0",
            "entryPrice": "0",
            "isolated": True,
            "leverage": "1",
            "marginType": "isolated",
        }

    def ensure_one_way_mode(self):
        return {"dualSidePosition": False}

    def position_risk(self, symbol=None):
        rows = [dict(self._position)]
        return [x for x in rows if symbol is None or x["symbol"] == symbol.upper()]

    def open_orders(self, symbol=None):
        return []

    def open_algo_orders(self, symbol=None):
        return []

    def mark_price(self, symbol):
        return {"symbol": symbol, "markPrice": str(self.mark)}

    def book_ticker(self, symbol=None):
        return {"symbol": symbol, "bidPrice": str(self.mark - 0.01), "askPrice": str(self.mark + 0.01)}

    def exchange_info(self, symbol=None):
        return {
            "symbols": [{
                "symbol": symbol or "BTCUSDT",
                "status": "TRADING",
                "contractType": "PERPETUAL",
                "quoteAsset": "USDT",
                "marginAsset": "USDT",
                "filters": [
                    {"filterType": "PRICE_FILTER", "minPrice": "0", "maxPrice": "10000000", "tickSize": "0.01"},
                    {"filterType": "LOT_SIZE", "minQty": "0.001", "maxQty": "100000", "stepSize": "0.001"},
                    {"filterType": "MARKET_LOT_SIZE", "minQty": "0.001", "maxQty": "100000", "stepSize": "0.001"},
                    {"filterType": "NOTIONAL", "minNotional": "5"},
                ],
            }]
        }

    def symbol_filters(self, symbol):
        return {x["filterType"]: x for x in self.exchange_info(symbol)["symbols"][0]["filters"]}

    def normalize_price(self, symbol, price, *, direction, purpose):
        return f"{float(price):.2f}"

    def normalize_quantity(self, symbol, quantity, *, market=True):
        return f"{int(float(quantity) * 1000) / 1000:.3f}"

    def stop_entry(self, symbol, direction, quantity, trigger_price, client_algo_id):
        self.stop_entries.append({
            "symbol": symbol,
            "direction": direction,
            "quantity": quantity,
            "trigger_price": trigger_price,
            "client_algo_id": client_algo_id,
        })
        return {
            "symbol": symbol,
            "algoId": 123,
            "clientAlgoId": client_algo_id,
            "algoStatus": "NEW",
            "side": "BUY" if direction == "LONG" else "SELL",
        }

    def protective_stop(self, symbol, direction, trigger_price, client_algo_id):
        self.protective_stops.append((symbol, direction, trigger_price, client_algo_id))
        response = {
            "symbol": symbol,
            "algoId": 456,
            "clientAlgoId": client_algo_id,
            "algoStatus": "NEW",
            "side": "SELL" if direction == "LONG" else "BUY",
        }
        if self.protection_response_missing_ids:
            response.pop("algoId")
            response.pop("clientAlgoId")
        return response

    def market_exit(self, symbol, direction, quantity, client_order_id):
        self.market_exits.append((symbol, direction, quantity, client_order_id))
        self._position["positionAmt"] = "0"
        return {
            "symbol": symbol,
            "orderId": 789,
            "clientOrderId": client_order_id,
            "status": "FILLED",
            "side": "SELL" if direction == "LONG" else "BUY",
            "executedQty": quantity,
        }

    def get_algo_order(self, symbol, *, algo_id=None, client_algo_id=None):
        return {
            "symbol": symbol,
            "algoId": algo_id or 456,
            "clientAlgoId": client_algo_id,
            "algoStatus": self.algo_status,
        }

    def cancel_algo_order_safe(self, symbol, *, algo_id=None, client_algo_id=None):
        if self.cancel_confirms:
            self.algo_status = "CANCELED"
        return {
            "symbol": symbol,
            "algoId": algo_id or 456,
            "clientAlgoId": client_algo_id,
            "algoStatus": self.algo_status,
        }


def make_context(cache, *, allow_long, allow_short):
    cache.publish(TFMarketContext(
        symbol="BTCUSDT",
        interval="5m",
        version=0,
        candle_open_time_ms=1,
        candle_close_time_ms=2,
        price=101.0,
        atr=2.0,
        jaw=100.0,
        teeth=100.5,
        lips=100.8,
        alligator_state="BULLISH" if allow_long else "BEARISH" if allow_short else "SLEEP",
        allow_long=allow_long,
        allow_short=allow_short,
        decision="LONG" if allow_long else "SHORT" if allow_short else "NO_TRADE",
        data_bars=220,
    ))


def make_signal(direction):
    if direction == "LONG":
        side, trigger, stop = "BUY", 105.0, 100.0
    else:
        side, trigger, stop = "SELL", 95.0, 100.0
    return SignalSpec.new(
        symbol="BTCUSDT",
        side=side,
        direction=direction,
        signal_type=SignalType.REVERSAL,
        role=SignalRole.ENTRY,
        timeframe="5m",
        signal_bar_time_ms=1000,
        trigger_price=trigger,
        protective_reference=stop,
        invalidation_price=stop,
        htf_confirmed=True,
        reason=f"test {direction}",
    )


@pytest.mark.parametrize(
    ("direction", "mark", "allow_long", "allow_short", "expected_side"),
    [
        ("LONG", 102.0, True, False, "BUY"),
        ("SHORT", 97.0, False, True, "SELL"),
    ],
)
def test_futures_campaign_arms_correct_directional_conditional_entry(
    tmp_path, direction, mark, allow_long, allow_short, expected_side
):
    db = Database(str(tmp_path / "futures.sqlite3"))
    try:
        cache = ContextCache()
        make_context(cache, allow_long=allow_long, allow_short=allow_short)
        client = FakeFuturesClient(mark)
        barrier = ExecutionBarrier(cache, db)
        service = FuturesCampaignExecutionService(
            client,
            db,
            execution_barrier=barrier,
            max_open_positions=3,
            portfolio_risk_limit_pct=0.01,
            campaign_risk_limit_pct=0.005,
        )

        result = service.arm_initial_entry(
            make_signal(direction),
            equity_quote=10000.0,
            atr=2.0,
            candidate_risk_fraction=0.005,
        )

        assert result["action"] == "ENTRY_ARMED"
        assert result["direction"] == direction
        assert len(client.stop_entries) == 1
        assert client.stop_entries[0]["direction"] == direction
        assert result["status"] == "NEW"
        saved = db.conn.execute(
            "SELECT side, purpose, status FROM execution_intents ORDER BY rowid DESC LIMIT 1"
        ).fetchone()
        assert saved["side"] == expected_side
        assert saved["purpose"] == "CAMPAIGN_ENTRY"
        assert saved["status"] == "SUBMITTED"
        campaign = service.engine.load_campaign(result["campaign_id"])
        assert campaign.tags["direction"] == direction
        assert campaign.initial_stop_price == pytest.approx(100.0)
    finally:
        db.conn.close()


def test_short_entry_is_blocked_by_non_bearish_operational_context(tmp_path):
    db = Database(str(tmp_path / "futures.sqlite3"))
    try:
        cache = ContextCache()
        make_context(cache, allow_long=True, allow_short=False)
        client = FakeFuturesClient(mark_price=97.0)
        service = FuturesCampaignExecutionService(
            client,
            db,
            execution_barrier=ExecutionBarrier(cache, db),
        )
        with pytest.raises(FuturesCampaignExecutionError, match="does not allow SHORT"):
            service.arm_initial_entry(
                make_signal("SHORT"),
                equity_quote=10000.0,
                atr=2.0,
                candidate_risk_fraction=0.005,
            )
        assert client.stop_entries == []
    finally:
        db.conn.close()


def test_signal_side_direction_conflict_is_rejected_before_order(tmp_path):
    from futures_campaign_execution import signal_direction

    signal = make_signal("SHORT")
    conflicting = SignalSpec(
        **{**signal.__dict__, "side": "BUY"}
    )
    with pytest.raises(FuturesCampaignExecutionError, match="side/direction conflict"):
        signal_direction(conflicting)

def test_futures_entry_is_blocked_when_available_margin_is_insufficient(tmp_path):
    db = Database(str(tmp_path / "futures-margin.sqlite3"))
    try:
        cache = ContextCache()
        make_context(cache, allow_long=True, allow_short=False)
        client = FakeFuturesClient(mark_price=102.0)
        service = FuturesCampaignExecutionService(
            client,
            db,
            execution_barrier=ExecutionBarrier(cache, db),
            max_open_positions=3,
            portfolio_risk_limit_pct=0.01,
            campaign_risk_limit_pct=0.005,
        )
        with pytest.raises(FuturesCampaignExecutionError, match="insufficient available Futures balance"):
            service.arm_initial_entry(
                make_signal("LONG"),
                equity_quote=10000.0,
                atr=2.0,
                candidate_risk_fraction=0.005,
                available_quote=1.0,
            )
        assert client.stop_entries == []
        pending = db.conn.execute(
            "SELECT COUNT(*) AS n FROM execution_intents WHERE purpose = 'CAMPAIGN_ENTRY'"
        ).fetchone()["n"]
        assert pending == 0
    finally:
        db.conn.close()

def test_kill_latch_keeps_existing_position_management_enabled():
    import threading
    from types import MethodType, SimpleNamespace
    from futures_runtime import FuturesRuntime

    runtime = object.__new__(FuturesRuntime)
    runtime._cycle_lock = threading.RLock()
    runtime._kill_latched = True
    runtime._paused = True
    runtime.client = SimpleNamespace(sync_time=lambda: {})
    runtime._last_account = {"equity_quote": 1000.0, "available_quote": 500.0}
    runtime._last_scan_summary = {}
    runtime._account = MethodType(
        lambda self: self._last_account,
        runtime,
    )
    runtime._recover = MethodType(lambda self: [], runtime)
    managed = []
    runtime._manage_existing_positions = MethodType(
        lambda self: managed.append("managed") or [{"symbol": "BTCUSDT", "action": "HOLD_PROTECTION"}],
        runtime,
    )
    runtime._daily_loss_allows_entry = MethodType(
        lambda self, equity: (True, "within limit"),
        runtime,
    )

    result = runtime.scan_once()

    assert managed == ["managed"]
    assert result["state"] == "KILL_SWITCH_LATCHED"
    assert result["new_entries"] == 0
    assert result["management"][0]["action"] == "HOLD_PROTECTION"


def _armed_entry_for_cancel(tmp_path, *, cancel_confirms=True):
    db = Database(str(tmp_path / "futures-cancel.sqlite3"))
    cache = ContextCache()
    make_context(cache, allow_long=True, allow_short=False)
    client = FakeFuturesClient(mark_price=102.0)
    client.cancel_confirms = cancel_confirms
    service = FuturesCampaignExecutionService(
        client,
        db,
        execution_barrier=ExecutionBarrier(cache, db),
        max_open_positions=3,
        portfolio_risk_limit_pct=0.01,
        campaign_risk_limit_pct=0.005,
    )
    result = service.arm_initial_entry(
        make_signal("LONG"),
        equity_quote=10000.0,
        atr=2.0,
        candidate_risk_fraction=0.005,
    )
    return db, client, service, service.engine.load_campaign(result["campaign_id"])


def test_pending_entry_cancellation_requires_authoritative_terminal_state(tmp_path):
    db, client, service, campaign = _armed_entry_for_cancel(tmp_path, cancel_confirms=True)
    try:
        result = service.cancel_pending_entry(campaign, reason="PAUSE")
        assert result["action"] == "ENTRY_CANCELLED"
        assert result["state"] == "CLOSED"
        assert result["algo_status"] == "CANCELED"
        assert client._position["positionAmt"] == "0"
        saved = service.engine.load_campaign(campaign.campaign_id)
        assert saved.state.value == "CLOSED"
        assert saved.pending_risk_quote == 0.0
    finally:
        db.conn.close()


def test_ambiguous_pending_entry_cancellation_fails_closed(tmp_path):
    db, client, service, campaign = _armed_entry_for_cancel(tmp_path, cancel_confirms=False)
    try:
        result = service.cancel_pending_entry(campaign, reason="KILL_SWITCH")
        assert result["action"] == "CANCEL_UNVERIFIED"
        assert result["state"] == "RECONCILE_REQUIRED"
        saved = service.engine.load_campaign(campaign.campaign_id)
        assert saved.state.value == "RECONCILE_REQUIRED"
        assert client.stop_entries, "the original conditional entry must be treated as potentially live"
    finally:
        db.conn.close()


def test_protection_response_without_order_identity_requires_reconciliation(tmp_path):
    db, client, service, campaign = _armed_entry_for_cancel(tmp_path)
    try:
        # Simulate an authoritative open position after entry and an exchange
        # response that may represent a successful stop submission but omits
        # both order identifiers.
        client._position["positionAmt"] = "0.5"
        client._position["entryPrice"] = "102.0"
        from campaign_model import CampaignState
        campaign.state = CampaignState.OPEN_INITIAL
        campaign.position_qty = 0.5
        campaign.average_entry_price = 102.0
        campaign.open_risk_quote = 10.0
        campaign.pending_risk_quote = 0.0
        campaign.capital_reserved_quote = 0.0
        service.db.save_campaign(campaign)
        client.protection_response_missing_ids = True

        with pytest.raises(FuturesCampaignExecutionError, match="reconciliation required"):
            service.place_protection(campaign, stop_price=100.0)

        saved = service.engine.load_campaign(campaign.campaign_id)
        assert saved.state.value == "RECONCILE_REQUIRED"
        assert saved.reconciliation_state == "REQUIRED"
        assert saved.tags["protection_response_unidentified"]["client_algo_id"]
        assert db.state_get(f"campaign_state:{campaign.campaign_id}") == "RECONCILE_REQUIRED"
        assert db.state_get("position_state:BTCUSDT") == "RECONCILE_REQUIRED"
    finally:
        db.conn.close()



def test_daily_loss_guard_blocks_non_finite_equity_without_resetting_baseline(tmp_path):
    from datetime import datetime, timezone

    db = Database(str(tmp_path / "daily-loss-nan.sqlite3"))
    try:
        runtime = object.__new__(FuturesRuntime)
        runtime.db = db
        runtime.max_daily_loss_pct = 0.03
        today = datetime.now(timezone.utc).date().isoformat()
        db.state_set("futures_day_start_date", today)
        db.state_set("futures_day_start_equity_quote", "1000")
        for bad_equity in (float("nan"), float("inf"), 0.0, -1.0):
            allowed, reason = runtime._daily_loss_allows_entry(bad_equity)
            assert not allowed
            assert "blocked" in reason
        assert db.state_get("futures_day_start_equity_quote") == "1000"
    finally:
        db.conn.close()


def test_daily_loss_guard_fails_closed_on_corrupt_same_day_baseline(tmp_path):
    from datetime import datetime, timezone

    db = Database(str(tmp_path / "daily-loss-corrupt.sqlite3"))
    try:
        runtime = object.__new__(FuturesRuntime)
        runtime.db = db
        runtime.max_daily_loss_pct = 0.03
        db.state_set("futures_day_start_date", datetime.now(timezone.utc).date().isoformat())
        db.state_set("futures_day_start_equity_quote", "NaN")
        allowed, reason = runtime._daily_loss_allows_entry(1000.0)
        assert not allowed
        assert "baseline" in reason
        assert db.state_get("futures_day_start_equity_quote") == "NaN"
    finally:
        db.conn.close()


def test_daily_loss_guard_blocks_new_entries_at_daily_limit(tmp_path):
    from datetime import datetime, timezone

    db = Database(str(tmp_path / "daily-loss-limit.sqlite3"))
    try:
        runtime = object.__new__(FuturesRuntime)
        runtime.db = db
        runtime.max_daily_loss_pct = 0.03
        db.state_set("futures_day_start_date", datetime.now(timezone.utc).date().isoformat())
        db.state_set("futures_day_start_equity_quote", "1000")
        allowed, reason = runtime._daily_loss_allows_entry(970.0)
        assert not allowed
        assert "daily loss limit reached" in reason
    finally:
        db.conn.close()


def test_execution_barrier_stale_intent_only_blocks_new_exposure():
    """Aged entry intents fail closed, but exits/protection remain admissible."""
    import time
    from market_context import MarketStateSnapshot

    barrier = ExecutionBarrier(ContextCache(), db=None, require_durable_intent=False)
    empty_snapshot = MarketStateSnapshot(
        generation=0,
        created_at_ms=int(time.time() * 1000),
        by_symbol={},
    )
    stale_at = int(time.time() * 1000) - 60_000

    stale_entry = __import__("execution_barrier").OrderIntent(
        intent_id="stale-entry",
        symbol="BTCUSDT",
        side="BUY",
        order_type="STOP_MARKET",
        required_context_versions={},
        purpose="CAMPAIGN_ENTRY",
        created_at_ms=stale_at,
        max_age_ms=1_000,
    )
    assert "stale intent" in barrier._validate(stale_entry, empty_snapshot)

    for purpose, order_type, side in (
        ("CAMPAIGN_PROTECTION", "STOP_MARKET", "SELL"),
        ("CAMPAIGN_EXIT", "MARKET", "SELL"),
        ("CAMPAIGN_EXIT_CANCEL_PROTECTION", "CANCEL", "SELL"),
    ):
        safety_intent = __import__("execution_barrier").OrderIntent(
            intent_id=f"stale-{purpose.lower()}",
            symbol="BTCUSDT",
            side=side,
            order_type=order_type,
            # No matching context exists in empty_snapshot. Safety actions
            # must not depend on strategy-context freshness.
            required_context_versions={"1m": 999},
            purpose=purpose,
            created_at_ms=stale_at,
            max_age_ms=1_000,
        )
        assert barrier._validate(safety_intent, empty_snapshot) == ""
