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
        self.protection_response_status = "NEW"
        self.entry_response_status = "NEW"
        self.algo_status = "NEW"
        self.protection_algo_status = "NEW"
        self.entry_actual_order_id = None
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

    def symbol_configuration(self, symbol):
        return {
            "symbol": symbol.upper(),
            "marginType": "ISOLATED" if self._position.get("isolated") is True else "CROSSED",
            "leverage": int(self._position.get("leverage", 0)),
        }

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
            "algoStatus": self.entry_response_status,
            "side": "BUY" if direction == "LONG" else "SELL",
        }

    def protective_stop(self, symbol, direction, trigger_price, client_algo_id):
        self.protective_stops.append((symbol, direction, trigger_price, client_algo_id))
        response = {
            "symbol": symbol,
            "algoId": 456,
            "clientAlgoId": client_algo_id,
            "algoStatus": self.protection_response_status,
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
        prior = next(
            (row for row in self.protective_stops if row[3] == client_algo_id),
            None,
        )
        entry = next(
            (row for row in self.stop_entries if row["client_algo_id"] == client_algo_id),
            None,
        )
        if entry:
            return {
                "symbol": symbol,
                "algoId": algo_id or 123,
                "clientAlgoId": client_algo_id,
                "algoStatus": self.algo_status,
                "side": "BUY" if entry["direction"] == "LONG" else "SELL",
                "type": "STOP_MARKET",
                "orderType": "STOP_MARKET",
                "closePosition": False,
                "triggerPrice": entry["trigger_price"],
                "quantity": entry["quantity"],
                **({"actualOrderId": self.entry_actual_order_id} if getattr(self, "entry_actual_order_id", None) else {}),
            }
        side = (
            "SELL" if prior[1] == "LONG" else "BUY"
        ) if prior else (
            "SELL" if float(self._position.get("positionAmt", 0) or 0) > 0 else "BUY"
        )
        response = {
            "symbol": symbol,
            "algoId": algo_id or 456,
            "clientAlgoId": client_algo_id,
            "algoStatus": self.protection_algo_status,
            "side": side,
            "type": "STOP_MARKET",
            "orderType": "STOP_MARKET",
            "closePosition": True,
        }
        if prior:
            response["triggerPrice"] = prior[2]
        return response

    def cancel_algo_order_safe(self, symbol, *, algo_id=None, client_algo_id=None):
        is_protection = any(row[3] == client_algo_id for row in self.protective_stops)
        if self.cancel_confirms:
            if is_protection:
                self.protection_algo_status = "CANCELED"
            else:
                self.algo_status = "CANCELED"
        return {
            "symbol": symbol,
            "algoId": algo_id or 456,
            "clientAlgoId": client_algo_id,
            "algoStatus": self.protection_algo_status if is_protection else self.algo_status,
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


def test_signal_spec_restore_preserves_zero_source_candle_index():
    from futures_runtime import signal_spec_from_dict

    raw = make_signal("LONG").to_dict()
    raw["source_candle_index"] = 0

    restored = signal_spec_from_dict(raw)

    assert restored.source_candle_index == 0


def test_signal_spec_restore_defaults_null_source_candle_index_to_unknown():
    from futures_runtime import signal_spec_from_dict

    raw = make_signal("SHORT").to_dict()
    raw["source_candle_index"] = None

    restored = signal_spec_from_dict(raw)

    assert restored.source_candle_index == -1


def test_sqlite_signal_persistence_preserves_zero_source_candle_index(tmp_path):
    db = Database(str(tmp_path / "source-index.sqlite3"))
    try:
        signal = SignalSpec.new(
            symbol="BTCUSDT",
            side="BUY",
            direction="LONG",
            signal_type=SignalType.REVERSAL,
            role=SignalRole.ENTRY,
            timeframe="5m",
            signal_bar_time_ms=1234,
            trigger_price=105.0,
            protective_reference=100.0,
            source_candle_index=0,
        )
        db.save_campaign_signal(signal, "campaign-source-index")

        row = db.conn.execute(
            "SELECT source_candle_index FROM campaign_signals WHERE signal_id=?",
            (signal.signal_id,),
        ).fetchone()
        assert row["source_candle_index"] == 0
    finally:
        db.conn.close()


def test_persistent_kill_latch_survives_restart_and_fails_closed_on_corruption(tmp_path):
    from futures_runtime import _read_persistent_kill_latch

    db = Database(str(tmp_path / "kill-latch.sqlite3"))
    try:
        assert _read_persistent_kill_latch(db) is False

        db.state_set("futures_kill_latched", "true")
        assert _read_persistent_kill_latch(db) is True

        # A new runtime reads the same durable state after process restart.
        reopened = Database(str(tmp_path / "kill-latch.sqlite3"))
        try:
            assert _read_persistent_kill_latch(reopened) is True
            reopened.state_set("futures_kill_latched", "false")
            assert _read_persistent_kill_latch(reopened) is False
            reopened.state_set("futures_kill_latched", "corrupt")
            assert _read_persistent_kill_latch(reopened) is True
        finally:
            reopened.conn.close()
    finally:
        db.conn.close()


def test_account_zero_margin_equity_does_not_fallback_to_wallet_balance():
    from types import SimpleNamespace

    runtime = object.__new__(FuturesRuntime)
    runtime.client = SimpleNamespace(account=lambda: {
        "availableBalance": "500",
        "totalMarginBalance": 0.0,
        "totalWalletBalance": "1000",
    })
    runtime.controller = SimpleNamespace(risk_engine=SimpleNamespace(balance=1.0))
    runtime._last_account = {}

    with pytest.raises(RuntimeError, match="invalid equity/availableBalance"):
        runtime._account()


def test_account_uses_wallet_equity_only_when_margin_equity_is_absent():
    from types import SimpleNamespace

    runtime = object.__new__(FuturesRuntime)
    runtime.client = SimpleNamespace(account=lambda: {
        "availableBalance": "500",
        "totalWalletBalance": "1000",
    })
    runtime.controller = SimpleNamespace(risk_engine=SimpleNamespace(balance=1.0))
    runtime._last_account = {}

    result = runtime._account()

    assert result["totalWalletBalance"] == "1000"
    assert runtime._last_account["equity_quote"] == pytest.approx(1000.0)
    assert runtime._last_account["available_quote"] == pytest.approx(500.0)


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

        initial_entry_count = len(client.stop_entries)
        result = service.arm_initial_entry(
            make_signal(direction),
            equity_quote=10000.0,
            atr=2.0,
            candidate_risk_fraction=0.005,
        )

        assert result["action"] == "ENTRY_ARMED"
        assert result["direction"] == direction
        assert len(client.stop_entries) == initial_entry_count + 1
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
    runtime._cancel_pending_entries = MethodType(lambda self, reason: [], runtime)

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


def test_expired_pending_entry_is_cancelled_during_reconciliation(tmp_path):
    db, client, service, campaign = _armed_entry_for_cancel(tmp_path, cancel_confirms=True)
    try:
        campaign.tags["entry_expires_at_ms"] = 1
        db.save_campaign(campaign)

        result = service.reconcile_symbol("BTCUSDT")

        assert result["action"] == "ENTRY_CANCELLED"
        assert result["state"] == "CLOSED"
        assert client.algo_status == "CANCELED"
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
        client_order_id="stale-entry-client",
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
            client_order_id=f"stale-safety-{purpose.lower()}",
            created_at_ms=stale_at,
            max_age_ms=1_000,
        )
        assert barrier._validate(safety_intent, empty_snapshot) == ""


@pytest.mark.parametrize(
    ("campaign_state", "expected_state"),
    [
        ("ADD_ON_ARMING", "ADD_ON_PENDING"),
        ("ADD_ON_PENDING", "ADD_ON_PENDING"),
        ("POSITION_EXPANDING", "RECONCILE_REQUIRED"),
    ],
)
def test_add_on_reconciliation_uses_exchange_order_and_position_state(campaign_state, expected_state):
    from types import SimpleNamespace
    from campaign_model import CampaignState

    calls = []
    campaign = SimpleNamespace(
        campaign_id="campaign-1",
        symbol="BTCUSDT",
        state=CampaignState(campaign_state),
        position_qty=0.5,
        open_risk_quote=10.0,
        pending_risk_quote=2.0,
        capital_reserved_quote=20.0,
        current_stop_price=95.0,
        initial_stop_price=95.0,
        current_signal_id="signal-add",
        tags={
            "direction": "LONG",
            "protective_client_algo_id": "stop-1",
            "protective_algo_id": "stop-algo-1",
            "protection_active": True,
            "pending_add_on_client_algo_id": "add-on-1",
            "pending_add_on_algo_id": "add-algo-1",
            "pending_add_on_original_qty": 0.5,
            "pending_add_on_trigger_price": 105.0,
            "pending_add_on_stop_price": 95.0,
            "pending_add_on_quantity": 0.1,
            "pending_add_on_risk_quote": 2.0,
            "pending_add_on_original_entry": 100.0,
            "pending_add_on_direction": "LONG",
        },
        transition=lambda state, reason="": setattr(campaign, "state", state),
    )
    position = {
        "symbol": "BTCUSDT",
        "positionAmt": "0.5",
        "entryPrice": "100.0",
    }
    db = SimpleNamespace(
        state_set=lambda key, value: calls.append((key, value)),
        save_campaign=lambda camp: calls.append(("save_campaign", camp.state.value)),
    )

    def get_algo_order(symbol, *, algo_id=None, client_algo_id=None):
        if client_algo_id == "stop-1" or algo_id == "stop-algo-1":
            return {
                "symbol": symbol, "algoId": "stop-algo-1", "clientAlgoId": "stop-1",
                "algoStatus": "NEW", "side": "SELL", "type": "STOP_MARKET",
                "closePosition": True, "triggerPrice": "95.0",
            }
        return {
            "symbol": symbol, "algoId": "add-algo-1", "clientAlgoId": "add-on-1",
            "algoStatus": "NEW", "side": "BUY", "type": "STOP_MARKET",
            "closePosition": False, "triggerPrice": "105.0", "quantity": "0.1",
        }

    client = SimpleNamespace(get_algo_order=get_algo_order)
    engine = SimpleNamespace(
        mark_reconcile_required=lambda camp, reason: (
            setattr(camp, "state", CampaignState.RECONCILE_REQUIRED),
            calls.append(("reconcile_reason", reason)),
        ),
    )
    service = object.__new__(FuturesCampaignExecutionService)
    service.client = client
    service.db = db
    service.engine = engine
    service._position_row = lambda symbol: position
    service._find_active_campaign = lambda symbol: campaign
    service._campaign_direction = lambda camp: "LONG"
    service._cancel_algo_via_barrier = lambda *args, **kwargs: {
        "symbol": "BTCUSDT", "algoStatus": "CANCELED"
    }

    result = service.reconcile_symbol("BTCUSDT")

    assert result["state"] == expected_state
    if expected_state == "RECONCILE_REQUIRED":
        assert any(key == "position_state:BTCUSDT" and value == "RECONCILE_REQUIRED" for key, value in calls)
    else:
        assert result["client_algo_id"] == "add-on-1"
        assert result["protection"] == "CONFIRMED"



def test_reconcile_detects_exchange_local_quantity_drift_after_confirming_protection():
    from types import SimpleNamespace
    from campaign_model import CampaignState

    calls = []
    campaign = SimpleNamespace(
        campaign_id="campaign-2",
        symbol="BTCUSDT",
        state=CampaignState.TREND_ACTIVE,
        position_qty=0.4,
        tags={"protective_client_algo_id": "stop-2"},
        current_stop_price=95.0,
        initial_stop_price=95.0,
    )
    position = {
        "symbol": "BTCUSDT",
        "positionAmt": "0.5",
        "entryPrice": "100.0",
    }
    db = SimpleNamespace(state_set=lambda key, value: calls.append((key, value)))
    client = SimpleNamespace(
        get_algo_order=lambda *args, **kwargs: {
            "algoStatus": "NEW", "clientAlgoId": "stop-2", "side": "SELL",
            "type": "STOP_MARKET", "orderType": "STOP_MARKET",
            "closePosition": True, "triggerPrice": "95.0",
        },
    )
    engine = SimpleNamespace(
        mark_reconcile_required=lambda camp, reason: (
            setattr(camp, "state", CampaignState.RECONCILE_REQUIRED),
            calls.append(("reconcile_reason", reason)),
        ),
    )
    service = object.__new__(FuturesCampaignExecutionService)
    service.client = client
    service.db = db
    service.engine = engine
    service._position_row = lambda symbol: position
    service._find_active_campaign = lambda symbol: campaign
    service._campaign_direction = lambda camp: "LONG"

    result = service.reconcile_symbol("BTCUSDT")

    assert result["state"] == "RECONCILE_REQUIRED"
    assert "quantity mismatch" in result["reason"]
    assert result["protection"] == "CONFIRMED"


def test_execution_barrier_executes_stale_safety_intents_without_market_context():
    import time
    from execution_barrier import OrderIntent

    barrier = ExecutionBarrier(ContextCache(), db=None, require_durable_intent=False)
    stale_at = int(time.time() * 1000) - 60_000
    cases = [
        ("CAMPAIGN_PROTECTION", "STOP_MARKET", "SELL", {"algoStatus": "NEW"}),
        ("CAMPAIGN_EXIT", "MARKET", "SELL", {"status": "FILLED"}),
        ("CAMPAIGN_EXIT_CANCEL_PROTECTION", "CANCEL", "SELL", {"algoStatus": "CANCELED"}),
    ]
    for purpose, order_type, side, response in cases:
        submitted = []
        intent = OrderIntent(
            intent_id=f"integration-{purpose}",
            symbol="BTCUSDT",
            side=side,
            order_type=order_type,
            required_context_versions={"1m": 999},
            purpose=purpose,
            client_order_id=f"integration-safety-{purpose.lower()}",
            created_at_ms=stale_at,
            max_age_ms=1_000,
        )
        result = barrier.execute(intent, lambda: submitted.append(purpose) or response)
        assert result.accepted, (purpose, result.reason)
        assert submitted == [purpose]

    submitted_entry = []
    stale_entry = OrderIntent(
        intent_id="integration-stale-entry",
        symbol="BTCUSDT",
        side="BUY",
        order_type="STOP_MARKET",
        required_context_versions={},
        purpose="CAMPAIGN_ENTRY",
        client_order_id="integration-stale-entry-client",
        created_at_ms=stale_at,
        max_age_ms=1_000,
    )
    result = barrier.execute(
        stale_entry,
        lambda: submitted_entry.append("entry") or {"algoStatus": "NEW"},
    )
    assert not result.accepted
    assert "stale intent" in result.reason
    assert submitted_entry == []


@pytest.mark.parametrize("bad_amount", ["NaN", "Infinity", "-Infinity", "not-a-number"])
def test_reconcile_rejects_invalid_exchange_position_quantity(bad_amount):
    from types import SimpleNamespace

    state_updates = []
    service = object.__new__(FuturesCampaignExecutionService)
    service._position_row = lambda symbol: {"symbol": symbol, "positionAmt": bad_amount}
    service.db = SimpleNamespace(
        state_set=lambda key, value: state_updates.append((key, value)),
    )

    result = service.reconcile_symbol("BTCUSDT")

    assert result["state"] == "RECONCILE_REQUIRED"
    assert "invalid exchange position quantity" in result["reason"]
    assert ("position_state:BTCUSDT", "RECONCILE_REQUIRED") in state_updates


@pytest.mark.parametrize("bad_amount", ["NaN", "Infinity", "-Infinity"])
def test_account_reconciliation_blocks_new_exposure_on_non_finite_position(bad_amount):
    from types import SimpleNamespace

    service = object.__new__(FuturesCampaignExecutionService)
    service.client = SimpleNamespace(
        position_risk=lambda *args, **kwargs: [
            {"symbol": "BTCUSDT", "positionAmt": bad_amount},
        ],
    )
    service.db = SimpleNamespace(open_campaigns=lambda: [])
    service._active_rows = lambda: []

    with pytest.raises(FuturesCampaignExecutionError, match="non-finite positionAmt"):
        service._assert_no_unmanaged_positions("ETHUSDT")


@pytest.mark.parametrize("terminal_status", ["CANCELED", "EXPIRED", "REJECTED"])
def test_protection_terminal_response_never_marks_stop_active(tmp_path, terminal_status):
    db, client, service, campaign = _armed_entry_for_cancel(tmp_path)
    try:
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
        client.protection_response_status = terminal_status

        with pytest.raises(FuturesCampaignExecutionError, match="not confirmed active"):
            service.place_protection(campaign, stop_price=100.0)

        saved = service.engine.load_campaign(campaign.campaign_id)
        assert saved.state == CampaignState.RECONCILE_REQUIRED
        assert saved.tags.get("protection_active") is not True
        assert saved.tags["protection_response_unidentified"]["status"] == terminal_status
        assert db.state_get(f"campaign_state:{campaign.campaign_id}") == "RECONCILE_REQUIRED"
        assert db.state_get("position_state:BTCUSDT") == "RECONCILE_REQUIRED"
    finally:
        db.conn.close()


@pytest.mark.parametrize("bad_amount", ["NaN", "Infinity", "-Infinity", "not-a-number"])
def test_place_protection_rejects_invalid_live_position_quantity(tmp_path, bad_amount):
    db, client, service, campaign = _armed_entry_for_cancel(tmp_path)
    try:
        client._position["positionAmt"] = bad_amount
        client._position["entryPrice"] = "102.0"
        with pytest.raises(FuturesCampaignExecutionError, match="positionAmt"):
            service.place_protection(campaign, stop_price=100.0)
        assert client.protective_stops == []
    finally:
        db.conn.close()


@pytest.mark.parametrize("bad_entry", ["NaN", "Infinity", "not-a-number"])
def test_place_protection_rejects_invalid_live_entry_price(tmp_path, bad_entry):
    db, client, service, campaign = _armed_entry_for_cancel(tmp_path)
    try:
        client._position["positionAmt"] = "0.5"
        client._position["entryPrice"] = bad_entry
        with pytest.raises(FuturesCampaignExecutionError, match="entry price"):
            service.place_protection(campaign, stop_price=100.0)
        assert client.protective_stops == []
    finally:
        db.conn.close()

@pytest.mark.parametrize("terminal_status", ["EXPIRED", "REJECTED"])
def test_terminal_entry_response_is_not_reported_as_armed(tmp_path, terminal_status):
    db = Database(str(tmp_path / "terminal-entry.sqlite3"))
    try:
        cache = ContextCache()
        make_context(cache, allow_long=True, allow_short=False)
        client = FakeFuturesClient(mark_price=102.0)
        client.entry_response_status = terminal_status
        service = FuturesCampaignExecutionService(
            client, db, execution_barrier=ExecutionBarrier(cache, db),
            max_open_positions=3, portfolio_risk_limit_pct=0.01, campaign_risk_limit_pct=0.005,
        )
        with pytest.raises(FuturesCampaignExecutionError, match="entry algo status is not active"):
            service.arm_initial_entry(
                make_signal("LONG"), equity_quote=10000.0, atr=2.0, candidate_risk_fraction=0.005,
            )
        campaigns = db.open_campaigns()
        assert campaigns
        assert campaigns[0]["state"] == "RECONCILE_REQUIRED"
        assert db.state_get("position_state:BTCUSDT") == "RECONCILE_REQUIRED"
    finally:
        db.conn.close()

def test_runtime_recovery_persists_campaign_and_position_reconcile_locks_on_exception():
    from types import SimpleNamespace
    from campaign_model import CampaignState

    events = []
    campaign = SimpleNamespace(state=CampaignState.ENTRY_PENDING)
    engine = SimpleNamespace(
        load_campaign=lambda campaign_id: campaign,
        mark_reconcile_required=lambda loaded, reason: (
            setattr(loaded, "state", CampaignState.RECONCILE_REQUIRED),
            events.append(("campaign", reason)),
        ),
    )
    row = {"symbol": "BTCUSDT", "campaign_id": "c-1", "tags": {"execution_mode": "FUTURES"}}
    execution = SimpleNamespace(
        _active_rows=lambda: [row],
        _row_tags=lambda item: item["tags"],
        reconcile_symbol=lambda symbol: (_ for _ in ()).throw(ValueError("invalid fill data")),
        engine=engine,
    )
    state = {}
    runtime = object.__new__(FuturesRuntime)
    runtime.execution = execution
    runtime.db = SimpleNamespace(state_set=lambda key, value: state.__setitem__(key, value))

    result = runtime._recover()

    assert result[0]["state"] == "RECONCILE_REQUIRED"
    assert campaign.state == CampaignState.RECONCILE_REQUIRED
    assert state["campaign_state:c-1"] == "RECONCILE_REQUIRED"
    assert state["position_state:BTCUSDT"] == "RECONCILE_REQUIRED"
    assert events and "invalid fill data" in events[0][1]

@pytest.mark.parametrize("bad_amount", ["NaN", "Infinity", "not-a-number"])
def test_runtime_skips_strategy_management_when_exchange_quantity_is_invalid(bad_amount):
    from types import SimpleNamespace
    from campaign_model import CampaignState

    state = {}
    events = []
    campaign = SimpleNamespace(campaign_id="c-2", state=CampaignState.TREND_ACTIVE)
    engine = SimpleNamespace(
        load_campaign=lambda campaign_id: campaign,
        mark_reconcile_required=lambda loaded, reason: (
            setattr(loaded, "state", CampaignState.RECONCILE_REQUIRED),
            events.append(reason),
        ),
    )
    row = {"symbol": "BTCUSDT", "campaign_id": "c-2", "tags": {"execution_mode": "FUTURES"}}
    execution = SimpleNamespace(
        _active_rows=lambda: [row],
        _row_tags=lambda item: item["tags"],
        _position_row=lambda symbol: {"symbol": symbol, "positionAmt": bad_amount},
        engine=engine,
    )
    runtime = object.__new__(FuturesRuntime)
    runtime.execution = execution
    runtime.db = SimpleNamespace(state_set=lambda key, value: state.__setitem__(key, value))
    runtime._cancel_pending_entries = lambda reason: []

    results = runtime._manage_existing_positions()

    assert results[0]["action"] == "RECONCILE_REQUIRED"
    assert campaign.state == CampaignState.RECONCILE_REQUIRED
    assert state["campaign_state:c-2"] == "RECONCILE_REQUIRED"
    assert state["position_state:BTCUSDT"] == "RECONCILE_REQUIRED"
    assert events

def test_stop_replacement_requires_confirmed_old_stop_cancellation(tmp_path):
    db, client, service, campaign = _armed_entry_for_cancel(tmp_path, cancel_confirms=False)
    try:
        from campaign_model import CampaignState
        client._position["positionAmt"] = "0.5"
        client._position["entryPrice"] = "102.0"
        campaign.state = CampaignState.OPEN_INITIAL
        campaign.position_qty = 0.5
        campaign.average_entry_price = 102.0
        campaign.open_risk_quote = 10.0
        campaign.pending_risk_quote = 0.0
        campaign.capital_reserved_quote = 0.0
        campaign.initial_stop_price = 100.0
        campaign.current_stop_price = 100.0
        campaign.tags["protective_client_algo_id"] = "old-stop-client"
        campaign.tags["protective_algo_id"] = 111
        campaign.tags["protection_active"] = True
        service.db.save_campaign(campaign)

        with pytest.raises(FuturesCampaignExecutionError, match="cancellation is not confirmed terminal"):
            service.replace_protection(campaign, stop_price=101.0)

        saved = service.engine.load_campaign(campaign.campaign_id)
        assert saved.state == CampaignState.RECONCILE_REQUIRED
        assert saved.tags.get("protection_replace_reconcile_required") is True
        assert saved.tags.get("previous_protective_client_algo_id") == "old-stop-client"
        assert db.state_get(f"campaign_state:{campaign.campaign_id}") == "RECONCILE_REQUIRED"
        assert db.state_get("position_state:BTCUSDT") == "RECONCILE_REQUIRED"
    finally:
        db.conn.close()

@pytest.mark.parametrize("bad_amount", ["NaN", "Infinity", "not-a-number"])
def test_exit_refuses_to_submit_when_live_position_quantity_is_invalid(tmp_path, bad_amount):
    db, client, service, campaign = _armed_entry_for_cancel(tmp_path)
    try:
        from campaign_model import CampaignState
        client._position["positionAmt"] = bad_amount
        campaign.state = CampaignState.TREND_ACTIVE
        campaign.position_qty = 0.5
        service.db.save_campaign(campaign)
        with pytest.raises(FuturesCampaignExecutionError, match="positionAmt"):
            service.exit_position(campaign, reason="TEST_INVALID_POSITION")
        assert client.market_exits == []
        assert db.state_get(f"campaign_state:{campaign.campaign_id}") == "RECONCILE_REQUIRED"
        assert db.state_get("position_state:BTCUSDT") == "RECONCILE_REQUIRED"
    finally:
        db.conn.close()

def test_exit_does_not_close_campaign_when_post_exit_quantity_is_non_finite(tmp_path):
    db, client, service, campaign = _armed_entry_for_cancel(tmp_path)
    try:
        from campaign_model import CampaignState
        client._position["positionAmt"] = "0.5"
        client._position["entryPrice"] = "102.0"
        campaign.state = CampaignState.TREND_ACTIVE
        campaign.tags.pop("entry_fill_reconciliation_pending", None)
        campaign.position_qty = 0.5
        campaign.average_entry_price = 102.0
        campaign.open_risk_quote = 10.0
        campaign.pending_risk_quote = 0.0
        campaign.capital_reserved_quote = 0.0
        service.db.save_campaign(campaign)
        service._position_amount = lambda symbol: float("nan")

        result = service.exit_position(campaign, reason="TEST_UNCONFIRMED_EXIT")

        assert result["action"] == "RECONCILE_REQUIRED"
        assert campaign.state == CampaignState.RECONCILE_REQUIRED
        assert db.state_get(f"campaign_state:{campaign.campaign_id}") == "RECONCILE_REQUIRED"
        assert db.state_get("position_state:BTCUSDT") == "RECONCILE_REQUIRED"
        assert client.market_exits
    finally:
        db.conn.close()

def test_exit_keeps_protection_active_until_reduce_only_exit_and_reconciles_cleanup(tmp_path):
    db, client, service, campaign = _armed_entry_for_cancel(tmp_path, cancel_confirms=False)
    try:
        from campaign_model import CampaignState
        client._position["positionAmt"] = "0.5"
        client._position["entryPrice"] = "102.0"
        client.algo_status = "NEW"
        campaign.state = CampaignState.TREND_ACTIVE
        campaign.tags.pop("entry_fill_reconciliation_pending", None)
        campaign.position_qty = 0.5
        campaign.average_entry_price = 102.0
        campaign.open_risk_quote = 10.0
        campaign.pending_risk_quote = 0.0
        campaign.capital_reserved_quote = 0.0
        campaign.tags["protective_client_algo_id"] = "old-stop-client"
        campaign.tags["protective_algo_id"] = 456
        service.db.save_campaign(campaign)

        result = service.exit_position(campaign, reason="TEST_ORPHANED_STOP")

        # The reduce-only exit is attempted without first cancelling the only
        # exchange-side protection. Failed cleanup leaves reconciliation locked.
        assert result["action"] == "RECONCILE_REQUIRED"
        assert client.market_exits
        assert client.protection_algo_status == "NEW"
        assert campaign.state == CampaignState.RECONCILE_REQUIRED
        assert db.state_get(f"campaign_state:{campaign.campaign_id}") == "RECONCILE_REQUIRED"
        assert db.state_get("position_state:BTCUSDT") == "RECONCILE_REQUIRED"
    finally:
        db.conn.close()


def test_runtime_ownership_check_fails_closed_without_configured_symbols():
    from types import SimpleNamespace

    checked = []
    runtime = object.__new__(FuturesRuntime)
    runtime.symbols = ()
    runtime.execution = SimpleNamespace(
        _assert_no_unmanaged_positions=lambda symbol: checked.append(symbol)
    )

    with pytest.raises(RuntimeError, match="No configured Futures symbols"):
        runtime._assert_configured_symbol_ownership()

    assert checked == []


def test_account_can_trade_false_blocks_entries_but_keeps_management_active():
    import threading
    from types import MethodType, SimpleNamespace

    runtime = object.__new__(FuturesRuntime)
    runtime._cycle_lock = threading.RLock()
    runtime._kill_latched = False
    runtime._paused = False
    runtime.client = SimpleNamespace(sync_time=lambda: {}, account_permissions=lambda: {"canTrade": False, "multiAssetsMargin": False})
    runtime.controller = SimpleNamespace(
        balance_quote=1000.0, risk_engine=SimpleNamespace(balance=1000.0)
    )
    runtime._last_account = {"equity_quote": 1000.0, "available_quote": 500.0}
    runtime._last_scan_summary = {}
    runtime._account = MethodType(lambda self: {"canTrade": False}, runtime)
    runtime._recover = MethodType(lambda self: [], runtime)
    runtime.execution = SimpleNamespace(_assert_no_unmanaged_positions=lambda symbol: None)
    runtime.symbols = ("BTCUSDT",)
    managed = []
    runtime._manage_existing_positions = MethodType(
        lambda self: managed.append("managed") or [
            {"symbol": "BTCUSDT", "action": "PROTECTION_MAINTAINED"}
        ],
        runtime,
    )
    runtime._daily_loss_allows_entry = MethodType(
        lambda self, equity: (True, "within limit"),
        runtime,
    )
    runtime._cancel_pending_entries = MethodType(lambda self, reason: [], runtime)

    result = runtime.scan_once()

    assert managed == ["managed"]
    assert result["state"] == "DAILY_RISK_LOCKOUT"
    assert "canTrade is not explicitly true" in result["reason"]
    assert result["new_entries"] == 0


def test_account_preflight_failure_does_not_skip_existing_position_management():
    import threading
    from types import MethodType, SimpleNamespace

    runtime = object.__new__(FuturesRuntime)
    runtime._cycle_lock = threading.RLock()
    runtime._kill_latched = False
    runtime._paused = False
    runtime.client = SimpleNamespace(sync_time=lambda: {}, account_permissions=lambda: {"canTrade": True, "multiAssetsMargin": False})
    runtime.controller = SimpleNamespace(
        balance_quote=1000.0, risk_engine=SimpleNamespace(balance=1000.0)
    )
    runtime._last_scan_summary = {}
    runtime._recover = MethodType(lambda self: [], runtime)
    runtime.execution = SimpleNamespace(_assert_no_unmanaged_positions=lambda symbol: None)
    runtime.symbols = ("BTCUSDT",)
    managed = []
    runtime._manage_existing_positions = MethodType(
        lambda self: managed.append("managed") or [
            {"symbol": "BTCUSDT", "action": "PROTECTION_MAINTAINED"}
        ],
        runtime,
    )

    def account_failure(self):
        raise RuntimeError("simulated account endpoint outage")

    runtime._account = MethodType(account_failure, runtime)
    runtime._daily_loss_allows_entry = MethodType(
        lambda self, equity: (True, "otherwise within limit"),
        runtime,
    )
    runtime._cancel_pending_entries = MethodType(lambda self, reason: [], runtime)

    result = runtime.scan_once()

    assert managed == ["managed"]
    assert result["state"] == "DAILY_RISK_LOCKOUT"
    assert "account endpoint outage" in result["reason"]
    assert result["new_entries"] == 0
    assert runtime.controller.balance_quote == pytest.approx(1000.0)
    assert runtime.controller.risk_engine.balance == pytest.approx(1000.0)


def test_daily_loss_lockout_still_manages_open_positions_and_blocks_entries():
    import threading
    from types import MethodType, SimpleNamespace
    from futures_runtime import FuturesRuntime

    runtime = object.__new__(FuturesRuntime)
    runtime._cycle_lock = threading.RLock()
    runtime._kill_latched = False
    runtime._paused = False
    runtime.client = SimpleNamespace(sync_time=lambda: {}, account_permissions=lambda: {"canTrade": True, "multiAssetsMargin": False})
    runtime.controller = SimpleNamespace(
        balance_quote=1.0, risk_engine=SimpleNamespace(balance=1.0)
    )
    runtime._last_account = {"equity_quote": 970.0, "available_quote": 400.0}
    runtime._last_scan_summary = {}
    runtime._account = MethodType(lambda self: self._last_account, runtime)
    runtime._recover = MethodType(lambda self: [], runtime)
    runtime.execution = SimpleNamespace(_assert_no_unmanaged_positions=lambda symbol: None)
    runtime.symbols = ("BTCUSDT",)
    managed = []
    runtime._manage_existing_positions = MethodType(
        lambda self: managed.append("managed") or [
            {"symbol": "BTCUSDT", "action": "PROTECTION_MAINTAINED"}
        ],
        runtime,
    )
    runtime._daily_loss_allows_entry = MethodType(
        lambda self, equity: (False, "daily loss limit reached"),
        runtime,
    )
    cancelled = []
    runtime._cancel_pending_entries = MethodType(
        lambda self, reason: cancelled.append(reason) or [
            {"symbol": "ETHUSDT", "state": "CLOSED", "action": "ENTRY_CANCELLED"}
        ],
        runtime,
    )

    result = runtime.scan_once()

    assert managed == ["managed"]
    assert runtime.controller.balance_quote == pytest.approx(970.0)
    assert runtime.controller.risk_engine.balance == pytest.approx(970.0)
    assert cancelled == ["DAILY_RISK_LOCKOUT"]
    assert result["pending_order_cancellations"][0]["action"] == "ENTRY_CANCELLED"
    assert result["state"] == "DAILY_RISK_LOCKOUT"
    assert result["new_entries"] == 0
    assert result["management"][0]["action"] == "PROTECTION_MAINTAINED"


def test_pending_protection_recovers_same_exchange_order_without_duplicate_submit(tmp_path):
    db, client, service, campaign = _armed_entry_for_cancel(tmp_path)
    try:
        from campaign_model import CampaignState
        client._position["positionAmt"] = "0.5"
        client._position["entryPrice"] = "102.0"
        client.algo_status = "NEW"
        client.protective_stops.append(("BTCUSDT", "LONG", "100.00", "pending-stop-client"))
        campaign.state = CampaignState.OPEN_INITIAL
        campaign.position_qty = 0.5
        campaign.average_entry_price = 102.0
        campaign.initial_stop_price = 100.0
        campaign.current_stop_price = 100.0
        campaign.tags["pending_protective_client_algo_id"] = "pending-stop-client"
        campaign.tags["pending_protective_stop_price"] = 100.0
        service.db.save_campaign(campaign)

        result = service.place_protection(campaign, stop_price=100.0)

        assert result["recovered_existing_order"] is True
        assert result["client_algo_id"] == "pending-stop-client"
        assert campaign.tags["protective_client_algo_id"] == "pending-stop-client"
        assert campaign.tags["protection_active"] is True
        assert len(client.protective_stops) == 1
        assert db.state_get(f"position_state:{campaign.symbol}") is None
    finally:
        db.conn.close()


def test_unknown_pending_protection_outcome_never_retries_with_new_id(tmp_path):
    db, client, service, campaign = _armed_entry_for_cancel(tmp_path)
    try:
        from campaign_model import CampaignState
        client._position["positionAmt"] = "0.5"
        client._position["entryPrice"] = "102.0"
        campaign.state = CampaignState.OPEN_INITIAL
        campaign.position_qty = 0.5
        campaign.average_entry_price = 102.0
        campaign.initial_stop_price = 100.0
        campaign.current_stop_price = 100.0
        campaign.tags["pending_protective_client_algo_id"] = "unknown-prior-stop"
        campaign.tags["pending_protective_stop_price"] = 100.0
        service.db.save_campaign(campaign)

        def lookup_fails(*args, **kwargs):
            raise TimeoutError("simulated Binance lookup timeout")
        client.get_algo_order = lookup_fails

        with pytest.raises(FuturesCampaignExecutionError, match="refusing duplicate submission"):
            service.place_protection(campaign, stop_price=100.0)

        assert client.protective_stops == []
        assert campaign.state == CampaignState.RECONCILE_REQUIRED
        assert db.state_get(f"campaign_state:{campaign.campaign_id}") == "RECONCILE_REQUIRED"
        assert db.state_get("position_state:BTCUSDT") == "RECONCILE_REQUIRED"
    finally:
        db.conn.close()


def test_exit_submission_timeout_persists_stable_client_id_and_blocks_duplicate(tmp_path):
    db, client, service, campaign = _armed_entry_for_cancel(tmp_path)
    try:
        from campaign_model import CampaignState
        client._position["positionAmt"] = "0.5"
        client._position["entryPrice"] = "102.0"
        campaign.state = CampaignState.OPEN_INITIAL
        campaign.position_qty = 0.5
        campaign.average_entry_price = 102.0
        campaign.open_risk_quote = 1.0
        campaign.current_stop_price = 100.0
        service.db.save_campaign(campaign)

        submit_calls = []
        def timed_out_exit(*args, **kwargs):
            submit_calls.append((args, kwargs))
            raise TimeoutError("simulated timeout after request may have reached Binance")
        client.market_exit = timed_out_exit

        first = service.exit_position(campaign, reason="TEST_EXIT")
        assert first["action"] == "RECONCILE_REQUIRED"
        stable_id = first["client_order_id"]
        assert stable_id.startswith("W2FX_")
        saved = service.engine.load_campaign(campaign.campaign_id)
        assert saved.tags["pending_exit_client_order_id"] == stable_id
        assert db.state_get(f"campaign_state:{campaign.campaign_id}") == "RECONCILE_REQUIRED"
        assert len(submit_calls) == 1

        lookup_calls = []
        def unknown_lookup(*args, **kwargs):
            lookup_calls.append((args, kwargs))
            raise TimeoutError("simulated order lookup timeout")
        client.get_order = unknown_lookup

        second = service.exit_position(saved, reason="RETRY_SHOULD_NOT_DUPLICATE")
        assert second["action"] == "RECONCILE_REQUIRED"
        assert second["client_order_id"] == stable_id
        assert len(submit_calls) == 1, "must not submit a second MARKET exit with a new ID"
        assert len(lookup_calls) == 1
        assert db.state_get(f"position_state:{campaign.symbol}") == "RECONCILE_REQUIRED"
    finally:
        db.conn.close()


def _make_add_on_signal(direction="LONG", signal_time=2000):
    side = "BUY" if direction == "LONG" else "SELL"
    trigger = 105.0 if direction == "LONG" else 95.0
    stop = 100.0
    return SignalSpec.new(
        symbol="BTCUSDT",
        side=side,
        direction=direction,
        signal_type=SignalType.SUPER_AO,
        role=SignalRole.ADD_ON,
        timeframe="5m",
        signal_bar_time_ms=signal_time,
        trigger_price=trigger,
        protective_reference=stop,
        invalidation_price=stop,
        htf_confirmed=True,
        reason="test add-on",
    )


def _prepare_open_campaign_for_add_on(tmp_path, direction="LONG"):
    db, client, service, campaign = _armed_entry_for_cancel(tmp_path)
    from campaign_model import CampaignState
    if direction == "SHORT":
        make_context(service.barrier.context_cache, allow_long=False, allow_short=True)
    client.mark = 102.0 if direction == "LONG" else 98.0
    client._position["positionAmt"] = "0.5" if direction == "LONG" else "-0.5"
    client._position["entryPrice"] = "102.0" if direction == "LONG" else "98.0"
    campaign.state = CampaignState.OPEN_INITIAL
    campaign.position_qty = 0.5
    campaign.average_entry_price = float(client._position["entryPrice"])
    campaign.initial_stop_price = 100.0
    campaign.current_stop_price = 100.0
    campaign.open_risk_quote = 20.0
    campaign.pending_risk_quote = 0.0
    campaign.capital_reserved_quote = 50.0
    campaign.tags["direction"] = direction
    campaign.tags.pop("entry_fill_reconciliation_pending", None)
    campaign.tags["risk_budget_quote"] = 50.0
    campaign.tags["last_signal_time_ms"] = 1000
    campaign.tags["protective_client_algo_id"] = "protective-test-id"
    campaign.tags["protective_algo_id"] = "456"
    campaign.tags["protection_active"] = True
    client.protective_stops.append((
        "BTCUSDT", direction, "100.00", "protective-test-id"
    ))
    db.state_delete("futures_entry_pending:BTCUSDT")
    db.state_set("position_state:BTCUSDT", CampaignState.OPEN_INITIAL.value)
    db.save_campaign(campaign)
    return db, client, service, campaign


@pytest.mark.parametrize("direction", ["LONG", "SHORT"])
def test_futures_add_on_reserves_risk_and_arms_directionally(tmp_path, direction):
    db, client, service, campaign = _prepare_open_campaign_for_add_on(tmp_path, direction)
    try:
        initial_entry_count = len(client.stop_entries)
        result = service.arm_add_on(
            _make_add_on_signal(direction),
            equity_quote=10000.0,
            candidate_risk_fraction=0.001,
            available_quote=5000.0,
        )
        assert result["action"] == "ADD_ON_ARMED"
        assert result["direction"] == direction
        assert client.stop_entries[-1]["direction"] == direction
        assert result["client_algo_id"].startswith("W2FA_")
        saved = service.engine.load_campaign(campaign.campaign_id)
        assert saved.state.value == "ADD_ON_PENDING"
        assert saved.pending_risk_quote > 0
        assert saved.tags["pending_add_on_client_algo_id"] == result["client_algo_id"]
        assert len(client.stop_entries) == initial_entry_count + 1
    finally:
        db.conn.close()


def test_futures_add_on_partial_fill_reconciles_position_and_risk(tmp_path):
    db, client, service, campaign = _prepare_open_campaign_for_add_on(tmp_path, "LONG")
    try:
        armed = service.arm_add_on(
            _make_add_on_signal("LONG"),
            equity_quote=10000.0,
            candidate_risk_fraction=0.001,
            available_quote=5000.0,
        )
        add_client_id = armed["client_algo_id"]
        client._position["positionAmt"] = "0.6"
        client._position["entryPrice"] = "102.333333"

        def lookup_algo(symbol, *, algo_id=None, client_algo_id=None):
            if client_algo_id == add_client_id or algo_id == armed["algo_id"]:
                return {
                    "symbol": symbol, "algoId": armed["algo_id"],
                    "clientAlgoId": add_client_id, "algoStatus": "TRIGGERED",
                    "actualOrderId": 999, "side": "BUY", "type": "STOP_MARKET",
                    "closePosition": False, "triggerPrice": "105.0",
                    "quantity": str(armed["quantity"]),
                }
            return {
                "symbol": symbol, "algoId": "456",
                "clientAlgoId": "protective-test-id", "algoStatus": "NEW",
                "side": "SELL", "type": "STOP_MARKET",
                "closePosition": True, "triggerPrice": "100.0",
            }
        client.get_algo_order = lookup_algo
        client.get_order = lambda symbol, *, order_id=None, orig_client_order_id=None: {
            "symbol": symbol, "orderId": 999, "clientOrderId": add_client_id,
            "status": "FILLED", "executedQty": "0.1", "avgPrice": "104.0",
            "cumQuote": "10.4", "side": "BUY", "type": "MARKET",
        }
        client.user_trades = lambda symbol, *, order_id=None, limit=1000: [{
            "symbol": symbol, "orderId": 999, "qty": "0.1", "price": "104.0",
            "realizedPnl": "0", "commission": "0.01", "commissionAsset": "USDT",
        }]

        result = service.reconcile_symbol("BTCUSDT")

        assert result["action"] == "ADD_ON_FILLED"
        assert result["filled_quantity"] == pytest.approx(0.1)
        saved = service.engine.load_campaign(campaign.campaign_id)
        assert saved.state.value == "TREND_ACTIVE"
        assert saved.position_qty == pytest.approx(0.6)
        assert saved.additions == 1
        assert saved.pending_risk_quote == 0.0
        assert "pending_add_on_client_algo_id" not in saved.tags
    finally:
        db.conn.close()


def test_filled_market_exit_requires_matching_trade_history_and_records_pnl(tmp_path):
    db, client, service, campaign = _armed_entry_for_cancel(tmp_path)
    try:
        from campaign_model import CampaignState
        client._position["positionAmt"] = "0.5"
        client._position["entryPrice"] = "102.0"
        campaign.state = CampaignState.OPEN_INITIAL
        campaign.tags.pop("entry_fill_reconciliation_pending", None)
        campaign.position_qty = 0.5
        campaign.average_entry_price = 102.0
        campaign.open_risk_quote = 1.0
        campaign.current_stop_price = 100.0
        service.db.save_campaign(campaign)
        client.user_trades = lambda symbol, *, order_id=None, limit=1000: [{
            "symbol": symbol, "orderId": order_id, "qty": "0.5", "price": "101.0",
            "realizedPnl": "-0.5", "commission": "0.1", "commissionAsset": "USDT",
        }]

        result = service.exit_position(campaign, reason="TEST_EXIT")

        assert result["action"] == "CLOSED"
        assert result["status"] == "FILLED"
        assert result["realized_pnl_quote_net_known_fees"] == pytest.approx(-0.6)
        saved = service.engine.load_campaign(campaign.campaign_id)
        assert saved.state.value == "CLOSED"
        assert saved.position_qty == 0.0
        assert saved.realized_pnl_quote == pytest.approx(-0.6)
        assert db.state_get("position_state:BTCUSDT") == "FLAT"
    finally:
        db.conn.close()


def test_protection_lookup_timeout_does_not_create_duplicate_stop(tmp_path):
    db, client, service, campaign = _prepare_open_campaign_for_add_on(tmp_path, "LONG")
    try:
        original_count = len(client.protective_stops)
        def lookup_timeout(*args, **kwargs):
            raise TimeoutError("simulated Binance algo lookup timeout")
        client.get_algo_order = lookup_timeout

        result = service.reconcile_symbol("BTCUSDT")

        assert result["state"] == "RECONCILE_REQUIRED"
        assert len(client.protective_stops) == original_count
        assert db.state_get(f"campaign_state:{campaign.campaign_id}") == "RECONCILE_REQUIRED"
        assert db.state_get("position_state:BTCUSDT") == "RECONCILE_REQUIRED"
    finally:
        db.conn.close()


def test_normal_management_scan_does_not_cancel_pending_entries():
    from types import SimpleNamespace
    runtime = object.__new__(FuturesRuntime)
    calls = []
    runtime.execution = SimpleNamespace(_active_rows=lambda: [], _row_tags=lambda row: {})
    runtime._cancel_pending_entries = lambda **kwargs: calls.append(kwargs) or []

    result = runtime._manage_existing_positions()

    assert result == []
    assert calls == [], "pending entries are cancelled only on explicit pause/kill"


def test_initial_conditional_partial_fill_is_cancelled_and_reconciled(tmp_path):
    db, client, service, campaign = _armed_entry_for_cancel(tmp_path)
    try:
        expected = float(campaign.tags["entry_quantity"])
        executed = expected / 2.0
        client._position["positionAmt"] = str(executed)
        client._position["entryPrice"] = "104.9"
        client.algo_status = "FINISHED"
        client.entry_actual_order_id = 999
        child_status = {"value": "PARTIALLY_FILLED"}
        cancel_calls = []

        def get_order(symbol, *, order_id=None, orig_client_order_id=None):
            return {
                "symbol": symbol, "orderId": 999, "clientOrderId": "child-order-id",
                "side": "BUY", "type": "MARKET", "status": child_status["value"],
                "executedQty": str(executed), "avgPrice": "104.9",
                "cumQuote": str(executed * 104.9),
            }
        def cancel_order_safe(symbol, *, order_id=None, orig_client_order_id=None):
            cancel_calls.append(order_id)
            child_status["value"] = "CANCELED"
            return {"symbol": symbol, "orderId": order_id, "status": "CANCELED"}
        client.get_order = get_order
        client.cancel_order_safe = cancel_order_safe
        client.user_trades = lambda symbol, *, order_id=None, limit=1000: [{
            "symbol": symbol, "orderId": 999, "qty": str(executed), "price": "104.9",
            "realizedPnl": "0", "commission": "0.02", "commissionAsset": "USDT",
        }]

        result = service.reconcile_symbol("BTCUSDT")

        assert result["state"] == "OPEN_INITIAL"
        assert result["partial_entry"] is True
        assert cancel_calls == [999]
        saved = service.engine.load_campaign(campaign.campaign_id)
        assert saved.position_qty == pytest.approx(executed)
        assert saved.state.value == "OPEN_INITIAL"
        assert saved.tags["entry_fee_quote"] == pytest.approx(0.02)
        assert "entry_fill_reconciliation_pending" not in saved.tags
        assert db.state_get("position_state:BTCUSDT") == "OPEN_INITIAL"
    finally:
        db.conn.close()


def test_partial_terminal_exit_is_aggregated_with_residual_exit(tmp_path):
    db, client, service, campaign = _armed_entry_for_cancel(tmp_path)
    try:
        from campaign_model import CampaignState
        client._position["positionAmt"] = "0.5"
        client._position["entryPrice"] = "102.0"
        campaign.state = CampaignState.OPEN_INITIAL
        campaign.tags.pop("entry_fill_reconciliation_pending", None)
        campaign.position_qty = 0.5
        campaign.average_entry_price = 102.0
        campaign.open_risk_quote = 1.0
        campaign.current_stop_price = 100.0
        service.db.save_campaign(campaign)

        exit_orders = {}
        def first_partial_exit(symbol, direction, quantity, client_order_id):
            exit_orders[client_order_id] = {
                "symbol": symbol, "orderId": 789, "clientOrderId": client_order_id,
                "side": "SELL", "type": "MARKET", "status": "CANCELED",
                "executedQty": "0.2", "avgPrice": "101.5", "cumQuote": "20.3",
            }
            client._position["positionAmt"] = "0.3"
            return exit_orders[client_order_id]
        client.market_exit = first_partial_exit
        first = service.exit_position(campaign, reason="TEST_PARTIAL_EXIT")
        assert first["action"] == "RECONCILE_REQUIRED"
        first_client_id = campaign.tags["pending_exit_client_order_id"]

        def second_exit(symbol, direction, quantity, client_order_id):
            exit_orders[client_order_id] = {
                "symbol": symbol, "orderId": 790, "clientOrderId": client_order_id,
                "side": "SELL", "type": "MARKET", "status": "FILLED",
                "executedQty": "0.3", "avgPrice": "101.0", "cumQuote": "30.3",
            }
            client._position["positionAmt"] = "0"
            return exit_orders[client_order_id]

        def get_order(symbol, *, order_id=None, orig_client_order_id=None):
            if orig_client_order_id == first_client_id:
                return exit_orders[first_client_id]
            if str(order_id) == "789":
                return exit_orders[first_client_id]
            if str(order_id) == "790":
                return next(v for v in exit_orders.values() if str(v["orderId"]) == "790")
            raise TimeoutError("unknown order lookup")

        def user_trades(symbol, *, order_id=None, limit=1000):
            if str(order_id) == "789":
                return [{
                    "symbol": symbol, "orderId": 789, "qty": "0.2", "price": "101.5",
                    "realizedPnl": "-0.1", "commission": "0.02", "commissionAsset": "USDT",
                }]
            if str(order_id) == "790":
                return [{
                    "symbol": symbol, "orderId": 790, "qty": "0.3", "price": "101.0",
                    "realizedPnl": "-0.2", "commission": "0.03", "commissionAsset": "USDT",
                }]
            return []
        client.get_order = get_order
        client.user_trades = user_trades
        client.market_exit = second_exit

        saved = service.engine.load_campaign(campaign.campaign_id)
        result = service.exit_position(saved, reason="TEST_RESIDUAL_EXIT")

        assert result["action"] == "CLOSED"
        assert result["realized_pnl_quote_net_known_fees"] == pytest.approx(-0.35)
        closed = service.engine.load_campaign(campaign.campaign_id)
        assert closed.state.value == "CLOSED"
        assert closed.position_qty == 0.0
        assert len(closed.tags["last_market_exit"]["orders"]) == 2
        assert db.state_get("position_state:BTCUSDT") == "FLAT"
    finally:
        db.conn.close()


def test_flat_position_cancels_orphan_stop_and_does_not_guess_closed(tmp_path):
    db, client, service, campaign = _prepare_open_campaign_for_add_on(tmp_path, "LONG")
    try:
        client._position["positionAmt"] = "0"
        client.algo_status = "NEW"

        result = service.reconcile_symbol("BTCUSDT")

        assert result["state"] == "RECONCILE_REQUIRED"
        saved = service.engine.load_campaign(campaign.campaign_id)
        assert saved.state.value == "RECONCILE_REQUIRED"
        assert db.state_get("position_state:BTCUSDT") == "RECONCILE_REQUIRED"
        # The fake exchange reports cancellation terminal on the stable stop ID;
        # without a verified child fill, the campaign must not be marked closed.
    finally:
        db.conn.close()


def test_protective_exit_closes_only_when_trade_qty_matches_live_campaign(tmp_path):
    db, client, service, campaign = _prepare_open_campaign_for_add_on(tmp_path, "LONG")
    try:
        client._position["positionAmt"] = "0"
        client.algo_status = "FINISHED"
        client.get_algo_order = lambda symbol, *, algo_id=None, client_algo_id=None: {
            "symbol": symbol, "algoId": str(algo_id or "456"),
            "clientAlgoId": client_algo_id or "protective-test-id",
            "algoStatus": "FINISHED", "actualOrderId": 789, "side": "SELL",
            "type": "STOP_MARKET", "orderType": "STOP_MARKET",
            "closePosition": True, "triggerPrice": "100.0",
        }
        client.get_order = lambda symbol, *, order_id=None, orig_client_order_id=None: {
            "symbol": symbol, "orderId": 789, "clientOrderId": "child-stop-order",
            "side": "SELL", "type": "MARKET", "status": "FILLED",
            "executedQty": "0.5", "avgPrice": "100.0", "cumQuote": "50.0",
        }
        client.user_trades = lambda symbol, *, order_id=None, limit=1000: [{
            "symbol": symbol, "orderId": 789, "qty": "0.5", "price": "100.0",
            "realizedPnl": "-1.0", "commission": "0.1", "commissionAsset": "USDT",
        }]

        result = service.reconcile_symbol("BTCUSDT")

        assert result["state"] == "CLOSED"
        assert result["realized_pnl_quote_net_known_fees"] == pytest.approx(-1.1)
        saved = service.engine.load_campaign(campaign.campaign_id)
        assert saved.state.value == "CLOSED"
        assert saved.position_qty == 0.0
        assert db.state_get("position_state:BTCUSDT") == "FLAT"
    finally:
        db.conn.close()


def test_flat_position_recovers_filled_pending_market_exit_before_new_submit(tmp_path):
    db, client, service, campaign = _prepare_open_campaign_for_add_on(tmp_path, "LONG")
    try:
        client._position["positionAmt"] = "0"
        stable_id = "W2FX_RECOVER_FILLED_EXIT"
        campaign.tags["pending_exit_client_order_id"] = stable_id
        campaign.tags["pending_exit_reason"] = "RECOVER_AFTER_CRASH"
        campaign.tags["pending_exit_expected_qty"] = 0.5
        campaign.tags["exit_cycle_original_qty"] = 0.5
        db.save_campaign(campaign)

        client.get_order = lambda symbol, *, order_id=None, orig_client_order_id=None: {
            "symbol": symbol, "orderId": 789, "clientOrderId": stable_id,
            "status": "FILLED", "executedQty": "0.5", "avgPrice": "101.0",
            "cumQuote": "50.5", "side": "SELL", "type": "MARKET",
        }
        client.user_trades = lambda symbol, *, order_id=None, limit=1000: [{
            "symbol": symbol, "orderId": 789, "qty": "0.5", "price": "101.0",
            "realizedPnl": "-0.5", "commission": "0.1", "commissionAsset": "USDT",
        }]

        result = service.reconcile_symbol("BTCUSDT")

        assert result["action"] == "CLOSED"
        assert result["realized_pnl_quote_net_known_fees"] == pytest.approx(-0.6)
        saved = service.engine.load_campaign(campaign.campaign_id)
        assert saved.state.value == "CLOSED"
        assert saved.position_qty == 0.0
        assert db.state_get("position_state:BTCUSDT") == "FLAT"
        assert len(client.market_exits) == 0, "must not submit a second market exit"
    finally:
        db.conn.close()


def test_triggered_protective_stop_without_child_id_exits_residual_but_does_not_false_close(tmp_path):
    db, client, service, campaign = _prepare_open_campaign_for_add_on(tmp_path, "LONG")
    try:
        # Binance reports the Algo stop as triggered but does not yet expose its
        # child order ID. The residual exposure must still be reduce-only exited,
        # while PnL finalization stays blocked until the stop fill can be reconciled.
        client.protection_algo_status = "TRIGGERED"
        client.user_trades = lambda symbol, *, order_id=None, limit=1000: [{
            "symbol": symbol, "orderId": order_id, "qty": "0.5", "price": "101.0",
            "realizedPnl": "-0.5", "commission": "0.1", "commissionAsset": "USDT",
        }]

        result = service.reconcile_symbol("BTCUSDT")

        assert result["action"] == "RECONCILE_REQUIRED"
        assert len(client.market_exits) == 1, "residual exposure must still be reduced"
        assert client.protective_stops
        saved = service.engine.load_campaign(campaign.campaign_id)
        assert saved.state.value == "RECONCILE_REQUIRED"
        assert "actualOrderId is missing" in saved.tags.get("reconcile_reason", "")
        assert db.state_get("position_state:BTCUSDT") == "RECONCILE_REQUIRED"
    finally:
        db.conn.close()


@pytest.mark.parametrize("missing_field", ["commissionAsset", "realizedPnl"])
def test_exit_trade_reconciliation_rejects_missing_accounting_fields(missing_field):
    row = {
        "qty": "0.5",
        "price": "101.0",
        "commission": "0.1",
        "commissionAsset": "USDT",
        "realizedPnl": "-0.5",
    }
    row.pop(missing_field)

    with pytest.raises(FuturesCampaignExecutionError, match=f"omitted required fields: {missing_field}"):
        FuturesCampaignExecutionService._validate_user_trade_row(
            row,
            "BTCUSDT",
            "market exit",
            require_realized_pnl=True,
        )


def test_position_payload_missing_quantity_blocks_new_entry_fail_closed(tmp_path):
    db = Database(str(tmp_path / "missing-position-amount.sqlite3"))
    try:
        cache = ContextCache()
        make_context(cache, allow_long=True, allow_short=False)
        client = FakeFuturesClient(102.0)
        client.position_risk = lambda symbol=None: [{"symbol": "BTCUSDT"}]
        service = FuturesCampaignExecutionService(
            client,
            db,
            execution_barrier=ExecutionBarrier(cache, db),
        )

        with pytest.raises(FuturesCampaignExecutionError, match="omitted positionAmt"):
            service.arm_initial_entry(
                make_signal("LONG"),
                equity_quote=10000.0,
                atr=2.0,
                candidate_risk_fraction=0.005,
            )

        assert client.stop_entries == []
    finally:
        db.conn.close()


def test_isolated_one_x_policy_uses_symbol_config_when_position_risk_is_empty(tmp_path):
    db = Database(str(tmp_path / "symbol-config-policy.sqlite3"))
    try:
        client = FakeFuturesClient(102.0)
        client.position_risk = lambda symbol=None: []
        cache = ContextCache()
        service = FuturesCampaignExecutionService(
            client, db, execution_barrier=ExecutionBarrier(cache, db)
        )

        configuration = service._assert_isolated_1x("BTCUSDT")

        assert configuration["marginType"] == "ISOLATED"
        assert configuration["leverage"] == 1
    finally:
        db.conn.close()


def test_empty_v3_position_risk_is_authoritative_flat_snapshot(tmp_path):
    db = Database(str(tmp_path / "empty-position-risk.sqlite3"))
    try:
        client = FakeFuturesClient(102.0)
        client.position_risk = lambda symbol=None: []
        cache = ContextCache()
        service = FuturesCampaignExecutionService(
            client, db, execution_barrier=ExecutionBarrier(cache, db)
        )

        row = service._position_row("BTCUSDT")

        assert row["symbol"] == "BTCUSDT"
        assert row["positionAmt"] == "0"
        assert row["_position_risk_empty"] is True
    finally:
        db.conn.close()


def test_position_risk_wrapper_payload_is_not_treated_as_empty(tmp_path):
    db = Database(str(tmp_path / "wrapped-position-risk.sqlite3"))
    try:
        cache = ContextCache()
        make_context(cache, allow_long=True, allow_short=False)
        client = FakeFuturesClient(102.0)
        client.position_risk = lambda symbol=None: {"positions": []}
        service = FuturesCampaignExecutionService(
            client,
            db,
            execution_barrier=ExecutionBarrier(cache, db),
        )

        with pytest.raises(FuturesCampaignExecutionError, match="malformed payload"):
            service.arm_initial_entry(
                make_signal("LONG"),
                equity_quote=10000.0,
                atr=2.0,
                candidate_risk_fraction=0.005,
            )

        assert client.stop_entries == []
    finally:
        db.conn.close()


def test_malformed_position_payload_blocks_new_entry_fail_closed(tmp_path):
    db = Database(str(tmp_path / "malformed-position-risk.sqlite3"))
    try:
        cache = ContextCache()
        make_context(cache, allow_long=True, allow_short=False)
        client = FakeFuturesClient(102.0)
        client.position_risk = lambda symbol=None: {"unexpected": "payload"}
        service = FuturesCampaignExecutionService(
            client,
            db,
            execution_barrier=ExecutionBarrier(cache, db),
        )

        with pytest.raises(FuturesCampaignExecutionError, match="positionRisk returned a malformed payload"):
            service.arm_initial_entry(
                make_signal("LONG"),
                equity_quote=10000.0,
                atr=2.0,
                candidate_risk_fraction=0.005,
            )

        assert client.stop_entries == []
    finally:
        db.conn.close()


def test_orphan_open_algo_order_blocks_new_entry_when_position_is_flat(tmp_path):
    db = Database(str(tmp_path / "orphan-algo.sqlite3"))
    try:
        cache = ContextCache()
        make_context(cache, allow_long=True, allow_short=False)
        client = FakeFuturesClient(102.0)
        client.open_algo_orders = lambda symbol=None: [{
            "symbol": symbol or "BTCUSDT",
            "algoId": "9001",
            "clientAlgoId": "unowned-orphan-stop",
            "algoStatus": "NEW",
            "side": "SELL",
            "type": "STOP_MARKET",
            "closePosition": True,
        }]
        service = FuturesCampaignExecutionService(
            client,
            db,
            execution_barrier=ExecutionBarrier(cache, db),
            max_open_positions=3,
            portfolio_risk_limit_pct=0.01,
            campaign_risk_limit_pct=0.005,
        )

        with pytest.raises(FuturesCampaignExecutionError, match="orphan/unowned open exchange orders"):
            service.arm_initial_entry(
                make_signal("LONG"),
                equity_quote=10000.0,
                atr=2.0,
                candidate_risk_fraction=0.005,
            )

        assert client.stop_entries == []
        assert db.state_get("position_state:BTCUSDT") == "RECONCILE_REQUIRED"
    finally:
        db.conn.close()


def test_unconfigured_symbol_orphan_order_blocks_new_entry_account_wide(tmp_path):
    db = Database(str(tmp_path / "account-wide-orphan-order.sqlite3"))
    try:
        cache = ContextCache()
        make_context(cache, allow_long=True, allow_short=False)
        client = FakeFuturesClient(102.0)
        client.open_orders = lambda symbol=None: []
        client.open_algo_orders = lambda symbol=None: [{
            "symbol": "XRPUSDT",
            "algoId": "99001",
            "clientAlgoId": "unowned-order-on-unconfigured-symbol",
            "algoStatus": "NEW",
            "side": "SELL",
            "type": "STOP_MARKET",
            "closePosition": True,
        }]
        service = FuturesCampaignExecutionService(
            client,
            db,
            execution_barrier=ExecutionBarrier(cache, db),
            max_open_positions=3,
            portfolio_risk_limit_pct=0.01,
            campaign_risk_limit_pct=0.005,
        )

        with pytest.raises(FuturesCampaignExecutionError, match="orphan/unowned open exchange orders"):
            service.arm_initial_entry(
                make_signal("LONG"),
                equity_quote=10000.0,
                atr=2.0,
                candidate_risk_fraction=0.005,
            )

        assert client.stop_entries == []
        assert db.state_get("position_state:XRPUSDT") == "RECONCILE_REQUIRED"
    finally:
        db.conn.close()


def test_triggered_protective_algo_without_child_order_id_stays_unresolved(tmp_path):
    db, client, service, campaign = _prepare_open_campaign_for_add_on(tmp_path, "LONG")
    try:
        client._position["positionAmt"] = "0"

        def triggered_without_child(symbol, *, algo_id=None, client_algo_id=None):
            return {
                "symbol": symbol,
                "algoId": str(algo_id or campaign.tags.get("protective_algo_id") or "456"),
                "clientAlgoId": client_algo_id or campaign.tags["protective_client_algo_id"],
                "algoStatus": "FINISHED",
                "side": "SELL",
                "type": "STOP_MARKET",
                "orderType": "STOP_MARKET",
                "closePosition": True,
                "triggerPrice": str(campaign.current_stop_price),
                # actualOrderId deliberately absent: exchange child execution
                # history has not yet been authoritatively linked.
            }

        client.get_algo_order = triggered_without_child
        result = service.reconcile_symbol("BTCUSDT")

        assert result["state"] == "RECONCILE_REQUIRED"
        assert "actualOrderId is missing" in result["reason"]
        saved = service.engine.load_campaign(campaign.campaign_id)
        assert saved.state.value == "RECONCILE_REQUIRED"
        assert saved.position_qty > 0
        assert client.market_exits == []
        assert db.state_get("position_state:BTCUSDT") == "RECONCILE_REQUIRED"
    finally:
        db.conn.close()


def test_reconcile_does_not_report_flat_when_unowned_orders_exist_without_campaign(tmp_path):
    db = Database(str(tmp_path / "flat-orphan-reconcile.sqlite3"))
    try:
        client = FakeFuturesClient(102.0)
        client.open_orders = lambda symbol=None: []
        client.open_algo_orders = lambda symbol=None: [{
            "symbol": symbol or "BTCUSDT",
            "algoId": "9002",
            "clientAlgoId": "unowned-triggered-entry",
            "algoStatus": "NEW",
            "side": "BUY",
            "type": "STOP_MARKET",
            "closePosition": False,
        }]
        cache = ContextCache()
        service = FuturesCampaignExecutionService(
            client,
            db,
            execution_barrier=ExecutionBarrier(cache, db),
        )

        result = service.reconcile_symbol("BTCUSDT")

        assert result["state"] == "RECONCILE_REQUIRED"
        assert result["open_algo_orders"] == 1
        assert db.state_get("position_state:BTCUSDT") == "RECONCILE_REQUIRED"
    finally:
        db.conn.close()

def test_partial_protective_stop_and_market_exit_reconcile_as_one_flattening_cycle(tmp_path):
    db, client, service, campaign = _prepare_open_campaign_for_add_on(tmp_path, "LONG")
    try:
        # Original campaign was 0.5, but the closePosition protective child
        # partially filled 0.2 just before the runtime noticed the stop. The
        # remaining 0.3 is then closed by a reduce-only market order.
        campaign.position_qty = 0.5
        campaign.average_entry_price = 102.0
        campaign.tags["protective_client_algo_id"] = "W2FP_PROTECTIVE_PARTIAL"
        campaign.tags["protective_algo_id"] = "456"
        client._position["positionAmt"] = "0.3"
        client._position["entryPrice"] = "102.0"

        client.protective_stops = [
            ("BTCUSDT", "LONG", "100.0", "W2FP_PROTECTIVE_PARTIAL")
        ]
        def protective_lookup(symbol, *, algo_id=None, client_algo_id=None):
            return {
                "symbol": symbol,
                "algoId": str(algo_id or "456"),
                "clientAlgoId": client_algo_id or "W2FP_PROTECTIVE_PARTIAL",
                "algoStatus": "FINISHED",
                "actualOrderId": 901,
                "side": "SELL",
                "type": "STOP_MARKET",
                "orderType": "STOP_MARKET",
                "closePosition": True,
                "triggerPrice": "100.0",
            }

        market_orders = {}
        def residual_market_exit(symbol, direction, quantity, client_order_id):
            client._position["positionAmt"] = "0"
            order = {
                "symbol": symbol, "orderId": 790, "clientOrderId": client_order_id,
                "side": "SELL", "type": "MARKET", "status": "FILLED",
                "executedQty": "0.3", "avgPrice": "99.0", "cumQuote": "29.7",
            }
            market_orders[client_order_id] = order
            return order

        protective_child = {
            "symbol": "BTCUSDT", "orderId": 901, "clientOrderId": "stop-child-901",
            "side": "SELL", "type": "MARKET", "status": "CANCELED",
            "executedQty": "0.2", "avgPrice": "100.0", "cumQuote": "20.0",
        }
        def get_order(symbol, *, order_id=None, orig_client_order_id=None):
            if orig_client_order_id:
                found = market_orders.get(orig_client_order_id)
                if found is not None:
                    return found
            if str(order_id) == "901":
                return protective_child
            if str(order_id) == "790":
                return next(iter(market_orders.values()))
            raise TimeoutError(f"unknown test order: {order_id}/{orig_client_order_id}")

        def user_trades(symbol, *, order_id=None, limit=1000):
            if str(order_id) == "901":
                return [{
                    "symbol": symbol, "orderId": 901, "qty": "0.2", "price": "100.0",
                    "realizedPnl": "-0.2", "commission": "0.02", "commissionAsset": "USDT",
                }]
            if str(order_id) == "790":
                return [{
                    "symbol": symbol, "orderId": 790, "qty": "0.3", "price": "99.0",
                    "realizedPnl": "-0.3", "commission": "0.03", "commissionAsset": "USDT",
                }]
            return []

        client.get_algo_order = protective_lookup
        client.market_exit = residual_market_exit
        client.get_order = get_order
        client.user_trades = user_trades

        first = service.exit_position(campaign, reason="STOP_MARKET_RACE")
        # The market exit and stop-child partial fill are reconciled in the
        # same cycle when both histories are authoritative.
        result = first if first.get("action") == "CLOSED" else service.reconcile_symbol("BTCUSDT")

        assert result["action"] == "CLOSED"
        assert result["realized_pnl_quote_net_known_fees"] == pytest.approx(-0.55)
        closed = service.engine.load_campaign(campaign.campaign_id)
        assert closed.state.value == "CLOSED"
        assert closed.position_qty == 0.0
        orders = closed.tags["last_market_exit"]["orders"]
        assert len(orders) == 2
        assert sum(x["executed_qty"] for x in orders if x["source"] == "MARKET_EXIT") == pytest.approx(0.3)
        assert sum(x["executed_qty"] for x in orders if x["source"] == "PROTECTIVE_STOP") == pytest.approx(0.2)
        assert db.state_get("position_state:BTCUSDT") == "FLAT"
    finally:
        db.conn.close()



def test_reconciliation_lockout_cancels_already_armed_entries():
    import threading
    from types import MethodType, SimpleNamespace

    runtime = object.__new__(FuturesRuntime)
    runtime._cycle_lock = threading.RLock()
    runtime._kill_latched = False
    runtime._paused = False
    runtime.client = SimpleNamespace(
        sync_time=lambda: {},
        account_permissions=lambda: {"canTrade": True, "multiAssetsMargin": False},
    )
    runtime.controller = SimpleNamespace(
        balance_quote=1000.0, risk_engine=SimpleNamespace(balance=1000.0)
    )
    runtime._last_account = {"equity_quote": 1000.0, "available_quote": 500.0}
    runtime._last_scan_summary = {}
    runtime._account = MethodType(lambda self: self._last_account, runtime)
    runtime._recover = MethodType(
        lambda self: [{"symbol": "BTCUSDT", "state": "RECONCILE_REQUIRED"}],
        runtime,
    )
    runtime.execution = SimpleNamespace(_assert_no_unmanaged_positions=lambda symbol: None)
    runtime.symbols = ("BTCUSDT",)
    runtime._manage_existing_positions = MethodType(
        lambda self: [{"symbol": "BTCUSDT", "action": "PROTECTION_MAINTAINED"}],
        runtime,
    )
    runtime._daily_loss_allows_entry = MethodType(lambda self, equity: (True, "within limit"), runtime)
    cancelled = []
    runtime._cancel_pending_entries = MethodType(
        lambda self, reason: cancelled.append(reason) or [
            {"symbol": "ETHUSDT", "action": "ENTRY_CANCELLED", "state": "CLOSED"}
        ],
        runtime,
    )

    result = runtime.scan_once()

    assert cancelled == ["RECONCILE_REQUIRED"]
    assert result["state"] == "RECONCILE_REQUIRED"
    assert result["new_entries"] == 0
    assert result["pending_order_cancellations"][0]["action"] == "ENTRY_CANCELLED"


def test_lockout_cancels_reconcile_required_entry_with_durable_pending_flag():
    from types import SimpleNamespace
    from campaign_model import CampaignState
    from futures_runtime import FuturesRuntime

    row = {
        "campaign_id": "campaign-uncertain-entry",
        "symbol": "BTCUSDT",
        "state": "RECONCILE_REQUIRED",
        "tags": {
            "execution_mode": "FUTURES",
            "entry_fill_reconciliation_pending": True,
            "entry_client_algo_id": "pending-entry-client",
        },
    }
    campaign = SimpleNamespace(state=CampaignState.RECONCILE_REQUIRED)
    calls = []
    runtime = object.__new__(FuturesRuntime)
    runtime.execution = SimpleNamespace(
        _active_rows=lambda: [row],
        _row_tags=lambda item: item["tags"],
        engine=SimpleNamespace(load_campaign=lambda campaign_id: campaign),
        cancel_pending_entry=lambda loaded, reason: calls.append((loaded, reason)) or {
            "symbol": "BTCUSDT", "state": "RECONCILE_REQUIRED", "action": "CANCEL_UNVERIFIED"
        },
        cancel_pending_add_on=lambda *args, **kwargs: pytest.fail("wrong cancellation path"),
    )
    runtime.db = SimpleNamespace(log_event=lambda *args, **kwargs: None)

    results = runtime._cancel_pending_entries(reason="RECONCILE_REQUIRED")

    assert calls == [(campaign, "RECONCILE_REQUIRED")]
    assert results[0]["action"] == "CANCEL_UNVERIFIED"


def test_pause_keeps_management_monitor_required_while_futures_campaign_is_active():
    import threading
    from types import MethodType, SimpleNamespace
    from futures_runtime import FuturesRuntime

    row = {
        "campaign_id": "active-protected-campaign",
        "symbol": "BTCUSDT",
        "state": "OPEN_PROTECTED",
        "tags": {"execution_mode": "FUTURES"},
    }
    runtime = object.__new__(FuturesRuntime)
    runtime._cycle_lock = threading.RLock()
    runtime._lock = threading.RLock()
    runtime._paused = False
    runtime._kill_latched = False
    runtime.execution = SimpleNamespace(
        _active_rows=lambda: [row],
        _row_tags=lambda item: item["tags"],
    )
    runtime.db = SimpleNamespace(log_event=lambda *args, **kwargs: None)
    runtime.status = MethodType(lambda self: {"paused": self._paused}, runtime)

    result = runtime.pause()

    assert result["state"] == "PAUSED"
    assert result["management_only_monitor_required"] is True
    assert "management monitor alive" in result["warning"]


@pytest.mark.parametrize("direction", ["LONG", "SHORT"])
def test_initial_signal_expiring_at_current_millisecond_is_rejected(tmp_path, monkeypatch, direction):
    from dataclasses import replace
    import futures_campaign_execution as execution_module

    now_ms = 1_800_000_000_000
    monkeypatch.setattr(execution_module.time, "time", lambda: now_ms / 1000.0)
    db = Database(str(tmp_path / f"exact-expiry-{direction}.sqlite3"))
    try:
        cache = ContextCache()
        make_context(cache, allow_long=True, allow_short=True)
        client = FakeFuturesClient(mark_price=102.0 if direction == "LONG" else 98.0)
        service = FuturesCampaignExecutionService(
            client, db, execution_barrier=ExecutionBarrier(cache, db)
        )
        signal = replace(make_signal(direction), expires_at_ms=now_ms)
        with pytest.raises(FuturesCampaignExecutionError, match="signal has expired"):
            service.arm_initial_entry(
                signal, equity_quote=10000.0, atr=2.0, candidate_risk_fraction=0.005
            )
        assert client.stop_entries == []
    finally:
        db.conn.close()


@pytest.mark.parametrize("direction", ["LONG", "SHORT"])
def test_add_on_expiring_at_current_millisecond_is_rejected(tmp_path, monkeypatch, direction):
    from dataclasses import replace
    import futures_campaign_execution as execution_module

    now_ms = 1_800_000_000_000
    db, client, service, campaign = _prepare_open_campaign_for_add_on(tmp_path, direction)
    monkeypatch.setattr(execution_module.time, "time", lambda: now_ms / 1000.0)
    try:
        signal = replace(_make_add_on_signal(direction), expires_at_ms=now_ms)
        with pytest.raises(FuturesCampaignExecutionError, match="add-on signal has expired"):
            service.arm_add_on(
                signal, equity_quote=10000.0, candidate_risk_fraction=0.001,
                available_quote=5000.0,
            )
        assert len(client.stop_entries) == 1  # only the original campaign entry
    finally:
        db.conn.close()
