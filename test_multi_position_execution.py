import uuid

from portfolio_trader import MultiPositionTrader
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


def test_multi_position_execution_creates_independent_trades(tmp_path, monkeypatch):
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
    trades = db.open_trades()
    assert len(trades) == 2
    assert {row["symbol"] for row in trades} == {"BTCUSDT", "ETHUSDT"}
    assert client.buy_calls == 2
    assert client.oco_calls == 2
    assert trader.reserved_risk_quote() / 10000.0 <= 0.0100001
    assert all(
        float(row["entry_price"]) * float(row["quantity"]) *
        trader._effective_risk_fraction(
            (float(row["entry_price"]) - float(row["stop_price"])) /
            float(row["entry_price"])
        ) / 10000.0 <= 0.005 + 1e-9
        for row in trades
    )
