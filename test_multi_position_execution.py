import pytest
import threading
import time
import uuid

@pytest.fixture(autouse=True)
def _legacy_campaign_mode(monkeypatch):
    monkeypatch.setenv("CAMPAIGN_ENGINE", "false")



from portfolio_trader import MultiPositionTrader
from campaign_execution import CampaignExecutionService, CampaignExecutionError
from db import Database


class FakeClient:
    testnet = True

    def __init__(self):
        self.balances = {"USDT": 10000.0}
        self.orders = {}
        self.order_counter = 100
        self.oco_counter = 500
        self.buy_calls = 0
        self.oco_calls = 0

    def account(self):
        return {
            "balances": [
                {"asset": asset, "free": str(value), "locked": "0"}
                for asset, value in self.balances.items()
            ],
            "status": "TRADING",
            "accountType": "SPOT",
        }

    def ticker_price(self, symbol):
        return {"symbol": symbol, "price": "100.0"}

    def exchange_info(self, symbol):
        return {
            "symbols": [{
                "symbol": symbol,
                "status": "TRADING",
                "baseAsset": symbol.replace("USDT", ""),
                "quoteAsset": "USDT",
                "isSpotTradingAllowed": True,
                "filters": [
                    {"filterType": "PRICE_FILTER", "tickSize": "0.01", "minPrice": "0.01", "maxPrice": "1000000"},
                    {"filterType": "LOT_SIZE", "minQty": "0.001", "maxQty": "1000000", "stepSize": "0.001"},
                    {"filterType": "MIN_NOTIONAL", "minNotional": "10"},
                ],
            }]
        }

    def book_ticker(self, symbol=None):
        return {
            "symbol": symbol or "BTCUSDT",
            "bidPrice": "100.0",
            "askPrice": "100.0",
        }

    def depth(self, symbol, limit=100):
        return {
            "asks": [["100.0", "1000.0"]],
            "bids": [["99.9", "1000.0"]],
        }

    def open_orders(self, symbol=None):
        return []

    def open_order_lists(self):
        return []

    def all_order_lists(self, symbol=None, limit=100):
        return []

    def all_orders(self, symbol, limit=1000):
        return list(self.orders.values())

    def get_order(self, symbol, order_id=None, orig_client_order_id=None):
        if order_id is not None:
            return self.orders[str(order_id)]
        for row in self.orders.values():
            if row.get("clientOrderId") == orig_client_order_id:
                return row
        raise AssertionError("order not found")

    def order_safe(self, symbol, side, type_, *, quote_order_qty=None, quantity=None, new_client_order_id=None, **kwargs):
        assert side == "BUY"
        self.buy_calls += 1
        self.order_counter += 1
        order_id = str(self.order_counter)
        quote = float(quote_order_qty or 0)
        qty = quote / 100.0
        base = symbol.replace("USDT", "")
        self.balances["USDT"] = self.balances.get("USDT", 0.0) - quote
        self.balances[base] = self.balances.get(base, 0.0) + qty
        row = {
            "symbol": symbol,
            "side": "BUY",
            "type": "MARKET",
            "orderId": self.order_counter,
            "clientOrderId": new_client_order_id,
            "status": "FILLED",
            "executedQty": f"{qty:.6f}",
            "cummulativeQuoteQty": f"{quote:.6f}",
            "fills": [{"price": "100.0", "qty": f"{qty:.6f}", "commission": "0", "commissionAsset": "USDT"}],
        }
        self.orders[order_id] = row
        return row

    @staticmethod
    def decimal_floor(value, step):
        from decimal import Decimal, ROUND_DOWN
        return (Decimal(str(value)) / Decimal(str(step))).to_integral_value(rounding=ROUND_DOWN) * Decimal(str(step))

    @staticmethod
    def decimal_format(value):
        from decimal import Decimal
        return format(Decimal(str(value)).normalize(), "f")

    def create_oco_sell_safe(self, symbol, quantity, take_profit_price, stop_price, stop_limit_price, list_client_order_id):
        self.oco_calls += 1
        self.oco_counter += 1
        qty = float(quantity)
        base = symbol.replace("USDT", "")
        self.balances[base] = max(0.0, self.balances.get(base, 0.0) - qty)
        # Keep the asset balance available for the position; an active OCO
        # normally moves it to locked. For this unit test we model it as locked
        # outside the simplified account response.
        self.balances[base] += qty
        return {
            "orderListId": self.oco_counter,
            "listClientOrderId": list_client_order_id,
            "listStatusType": "EXEC_STARTED",
            "listOrderStatus": "EXECUTING",
            "orderReports": [
                {"symbol": symbol, "orderId": self.oco_counter * 10, "orderListId": self.oco_counter,
                 "clientOrderId": "TP", "status": "NEW", "side": "SELL", "type": "TAKE_PROFIT_LIMIT",
                 "price": str(take_profit_price), "origQty": str(quantity), "executedQty": "0"},
                {"symbol": symbol, "orderId": self.oco_counter * 10 + 1, "orderListId": self.oco_counter,
                 "clientOrderId": "SL", "status": "NEW", "side": "SELL", "type": "STOP_LOSS_LIMIT",
                 "stopPrice": str(stop_price), "price": str(stop_limit_price),
                 "origQty": str(quantity), "executedQty": "0"},
            ],
        }


class Selection:
    def __init__(self, symbol):
        self.candidate = type("Candidate", (), {
            "symbol": symbol,
        })()
        self.risk = type("Risk", (), {
            "risk_pct": 0.5,
            "stop_distance_pct": 2.0,
            "take_profit_pct": 4.0,
        })()


def test_market_quantity_uses_market_lot_size_over_lot_size(tmp_path):
    trader = MultiPositionTrader(
        FakeClient(),
        db=Database(str(tmp_path / "market-lot.sqlite3")),
        symbols=[],
    )
    trader._filters = lambda symbol: {
        "LOT_SIZE": {"minQty": "0.001", "stepSize": "0.001"},
        "MARKET_LOT_SIZE": {"minQty": "0.010", "stepSize": "0.010"},
    }

    assert trader._normalize_qty("BTCUSDT", "0.049", market=True) == 0.04
    assert trader._normalize_qty("BTCUSDT", "0.049", market=False) == 0.049


def test_legacy_multi_position_mode_fails_closed_without_signal_contract(tmp_path, monkeypatch):
    monkeypatch.setenv("MAX_OPEN_POSITIONS", "0")
    monkeypatch.setenv("MAX_TOTAL_RISK_PCT", "0.01")
    monkeypatch.setenv("MAX_RISK_PER_TRADE_PCT", "0.005")
    monkeypatch.setenv("MAX_L2_SLIPPAGE_PCT", "0.0015")
    monkeypatch.setenv("DRY_RUN", "false")
    monkeypatch.setenv("MIN_NOTIONAL_BUFFER_PCT", "0")

    client = FakeClient()
    db = Database(str(tmp_path / "trades.sqlite3"))
    trader = MultiPositionTrader(client, db=db, symbols=["BTCUSDT", "ETHUSDT"])
    # Exercise the production allocation path, including fee and slippage
    # buffers, so the test protects the real aggregate-risk contract.

    result = trader.execute([Selection("BTCUSDT"), Selection("ETHUSDT")])

    assert len(result) == 2
    assert all(row["action"] == "ENTRY_BLOCKED" for row in result)
    assert all("ExecutionBarrier" in row["reason"] for row in result)
    assert db.open_trades() == []
    assert client.buy_calls == 0
    assert client.oco_calls == 0


def test_legacy_quantity_and_price_normalization_fail_closed_on_missing_filters(tmp_path):
    client = FakeClient()
    client.exchange_info = lambda symbol: {
        "symbols": [{
            "symbol": symbol,
            "baseAsset": symbol.replace("USDT", ""),
            "filters": [],
        }]
    }
    trader = object.__new__(MultiPositionTrader)
    trader.client = client

    with pytest.raises(RuntimeError, match="quantity filter missing"):
        trader._normalize_qty("BTCUSDT", 1.0)
    with pytest.raises(RuntimeError, match="PRICE_FILTER missing"):
        trader._normalize_price("BTCUSDT", 100.0)

    db = Database(str(tmp_path / "campaign-filter-test.sqlite3"))
    try:
        service = CampaignExecutionService(client, db)
        with pytest.raises(CampaignExecutionError, match="filter missing"):
            service._normalize_qty("BTCUSDT", 1.0)
        with pytest.raises(CampaignExecutionError, match="PRICE_FILTER missing"):
            service._normalize_price("BTCUSDT", 100.0)
    finally:
        db.conn.close()


def test_reconcile_required_campaign_remains_in_portfolio_risk_reservation(tmp_path):
    db = Database(str(tmp_path / "unresolved-risk.sqlite3"))
    try:
        db.save_campaign({
            "campaign_id": "campaign-open",
            "symbol": "BTCUSDT",
            "side": "LONG",
            "execution_timeframe": "5m",
            "state": "TREND_ACTIVE",
            "open_risk_quote": 12.0,
            "pending_risk_quote": 3.0,
        })
        db.save_campaign({
            "campaign_id": "campaign-unknown",
            "symbol": "ETHUSDT",
            "side": "LONG",
            "execution_timeframe": "5m",
            "state": "RECONCILE_REQUIRED",
            "open_risk_quote": 20.0,
            "pending_risk_quote": 5.0,
        })
        db.save_campaign({
            "campaign_id": "campaign-closed",
            "symbol": "SOLUSDT",
            "side": "LONG",
            "execution_timeframe": "5m",
            "state": "CLOSED",
            "open_risk_quote": 100.0,
            "pending_risk_quote": 100.0,
        })
        assert db.campaign_risk_reserved_quote() == 40.0
    finally:
        db.conn.close()


def test_immediate_transaction_serializes_portfolio_risk_reservation_across_connections(tmp_path):
    path = str(tmp_path / "atomic-risk.sqlite3")
    db1 = Database(path)
    db2 = Database(path)
    first_inside = threading.Event()
    release_first = threading.Event()
    second_done = threading.Event()
    observed = []

    def row(campaign_id, risk):
        return {
            "campaign_id": campaign_id,
            "symbol": "BTCUSDT" if campaign_id == "first" else "ETHUSDT",
            "side": "LONG",
            "execution_timeframe": "5m",
            "state": "ENTRY_PENDING",
            "pending_risk_quote": risk,
            "open_risk_quote": 0.0,
        }

    def first_writer():
        with db1.transaction(immediate=True):
            db1.save_campaign(row("first", 60.0))
            first_inside.set()
            assert release_first.wait(5)

    def second_writer():
        assert first_inside.wait(5)
        with db2.transaction(immediate=True):
            observed.append(db2.campaign_risk_reserved_quote())
            db2.save_campaign(row("second", 30.0))
            second_done.set()

    t1 = threading.Thread(target=first_writer)
    t2 = threading.Thread(target=second_writer)
    t1.start()
    t2.start()
    assert first_inside.wait(5)
    time.sleep(0.1)
    assert not second_done.is_set()
    release_first.set()
    t1.join(5)
    t2.join(5)
    try:
        assert not t1.is_alive()
        assert not t2.is_alive()
        assert observed == [60.0]
        assert db1.campaign_risk_reserved_quote() == 90.0
    finally:
        db1.conn.close()
        db2.conn.close()
