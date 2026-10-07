import os
from pathlib import Path

from db import Database
from portfolio_trader import MultiPositionTrader


class FakeClient:
    testnet = True

    def __init__(self):
        self.order_map = {}
        self.orders_by_symbol = {}
        self.open_lists = []
        self.history_lists = []
        self.created_oco = []

    def exchange_info(self, symbol):
        base = symbol.upper().replace("USDT", "")
        return {
            "symbols": [{
                "symbol": symbol.upper(),
                "status": "TRADING",
                "baseAsset": base,
                "quoteAsset": "USDT",
                "filters": [
                    {"filterType": "LOT_SIZE", "minQty": "0.000001", "stepSize": "0.000001"},
                    {"filterType": "PRICE_FILTER", "tickSize": "0.01"},
                    {"filterType": "MIN_NOTIONAL", "minNotional": "5"},
                ],
            }]
        }

    def account(self):
        assets = {
            "BTC": 0.0,
            "ETH": 2.0,
            "SOL": 3.0,
        }
        return {
            "balances": [
                {"asset": "USDT", "free": "9000", "locked": "0"},
                *[
                    {"asset": asset, "free": str(qty), "locked": "0"}
                    for asset, qty in assets.items()
                ],
            ]
        }

    def all_orders(self, symbol, limit=1000):
        return list(self.orders_by_symbol.get(symbol.upper(), []))[-limit:]

    def open_orders(self, symbol=None):
        rows = []
        for item in self.orders_by_symbol.values():
            rows.extend(item)
        return [
            x for x in rows
            if str(x.get("status", "")).upper() in {"NEW", "PARTIALLY_FILLED"}
            and (symbol is None or str(x.get("symbol", "")).upper() == symbol.upper())
        ]

    def open_order_lists(self, symbol=None):
        rows = list(self.open_lists)
        if symbol:
            rows = [
                x for x in rows
                if str(x.get("symbol", "")).upper() == symbol.upper()
            ]
        return rows

    def all_order_lists(self, symbol=None, limit=100):
        rows = list(self.history_lists)
        if symbol:
            rows = [
                x for x in rows
                if str(x.get("symbol", "")).upper() == symbol.upper()
            ]
        return rows[-limit:]

    def get_order(self, symbol, order_id=None, orig_client_order_id=None):
        for order in self.orders_by_symbol.get(symbol.upper(), []):
            if (
                order_id is not None
                and str(order.get("orderId")) == str(order_id)
            ) or (
                orig_client_order_id
                and str(order.get("clientOrderId")) == str(orig_client_order_id)
            ):
                return order
        raise RuntimeError("order not found")

    def order(self, symbol, *args, **kwargs):
        raise AssertionError("unexpected new order in lifecycle recovery test")

    def create_oco_sell(
        self,
        symbol,
        quantity,
        take_profit_price,
        stop_price,
        stop_limit_price,
        list_client_order_id,
    ):
        order_list_id = 900 + len(self.created_oco)
        self.created_oco.append({
            "symbol": symbol,
            "quantity": float(quantity),
            "list_client_order_id": list_client_order_id,
            "order_list_id": order_list_id,
        })
        self.open_lists.append({
            "symbol": symbol,
            "orderListId": str(order_list_id),
            "listClientOrderId": list_client_order_id,
            "listStatusType": "EXEC_STARTED",
            "listOrderStatus": "EXECUTING",
        })
        return {
            "orderListId": order_list_id,
            "listClientOrderId": list_client_order_id,
            "orderReports": [
                {
                    "symbol": symbol,
                    "side": "SELL",
                    "type": "TAKE_PROFIT_LIMIT",
                    "orderId": str(order_list_id * 10 + 1),
                    "orderListId": str(order_list_id),
                    "clientOrderId": list_client_order_id + "_TP",
                    "status": "NEW",
                    "price": take_profit_price,
                    "stopPrice": take_profit_price,
                    "origQty": quantity,
                },
                {
                    "symbol": symbol,
                    "side": "SELL",
                    "type": "STOP_LOSS_LIMIT",
                    "orderId": str(order_list_id * 10 + 2),
                    "orderListId": str(order_list_id),
                    "clientOrderId": list_client_order_id + "_SL",
                    "status": "NEW",
                    "price": stop_limit_price,
                    "stopPrice": stop_price,
                    "origQty": quantity,
                },
            ],
        }

    @staticmethod
    def decimal_floor(value, step):
        from decimal import Decimal, ROUND_DOWN
        return (
            Decimal(str(value)) / Decimal(str(step))
        ).to_integral_value(rounding=ROUND_DOWN) * Decimal(str(step))

    @staticmethod
    def decimal_format(value):
        return format(float(value), ".8f")


def add_trade(db, **kwargs):
    base = dict(
        entry_time="2026-10-05T10:00:00+00:00",
        side="LONG",
        pnl=0,
        pnl_pct=0,
        fees=0,
    )
    base.update(kwargs)
    return db.save_trade(**base)


def test_multiple_positions_are_independent_and_exit_is_mapped_to_one_symbol(tmp_path: Path):
    os.environ["WILLIAMS_DB_PATH"] = str(tmp_path / "multi.sqlite3")
    db = Database(os.environ["WILLIAMS_DB_PATH"])
    client = FakeClient()

    btc_id = add_trade(
        db,
        symbol="BTCUSDT",
        entry_price=100000,
        quantity=0.01,
        entry_order_id="btc-buy-1",
        entry_client_order_id="WILLV4_ENTRY_BTC",
        exit_order_list_id="101",
        exit_order_list_client_id="WILLV4_OCO_BTC",
        stop_price=98000,
        take_profit_price=104000,
        risk_pct=0.002,
    )
    eth_id = add_trade(
        db,
        symbol="ETHUSDT",
        entry_price=4000,
        quantity=2,
        entry_order_id="eth-buy-1",
        entry_client_order_id="WILLV4_ENTRY_ETH",
        exit_order_list_id="202",
        exit_order_list_client_id="WILLV4_OCO_ETH",
        stop_price=3920,
        take_profit_price=4160,
        risk_pct=0.002,
    )

    client.orders_by_symbol["BTCUSDT"] = [
        {
            "symbol": "BTCUSDT",
            "side": "BUY",
            "type": "MARKET",
            "orderId": "btc-buy-1",
            "clientOrderId": "WILLV4_ENTRY_BTC",
            "status": "FILLED",
            "executedQty": "0.01",
            "cummulativeQuoteQty": "1000",
            "time": 100,
        },
        {
            "symbol": "BTCUSDT",
            "side": "SELL",
            "type": "TAKE_PROFIT_LIMIT",
            "orderId": "btc-tp-1",
            "orderListId": "101",
            "clientOrderId": "WILLV4_OCO_BTC_TP",
            "status": "FILLED",
            "executedQty": "0.01",
            "cummulativeQuoteQty": "1040",
            "price": "104000",
            "time": 200,
        },
    ]
    client.orders_by_symbol["ETHUSDT"] = [
        {
            "symbol": "ETHUSDT",
            "side": "BUY",
            "type": "MARKET",
            "orderId": "eth-buy-1",
            "clientOrderId": "WILLV4_ENTRY_ETH",
            "status": "FILLED",
            "executedQty": "2",
            "cummulativeQuoteQty": "8000",
            "time": 100,
        }
    ]
    client.open_lists.append({
        "symbol": "ETHUSDT",
        "orderListId": "202",
        "listClientOrderId": "WILLV4_OCO_ETH",
        "listStatusType": "EXEC_STARTED",
        "listOrderStatus": "EXECUTING",
    })

    multi = MultiPositionTrader(client, db=db)
    result = multi.reconcile_open_positions()

    assert any(x.get("closed") and x.get("symbol") == "BTCUSDT" for x in result)
    assert db.open_trade("BTCUSDT") is None
    eth = db.open_trade("ETHUSDT")
    assert eth is not None
    assert eth["id"] == eth_id
    assert db.open_trades() == [eth]
    assert multi.state("BTCUSDT") == "FLAT"
    assert multi.state("ETHUSDT") == "OPEN"
    assert btc_id != eth_id
    assert multi.reserved_risk_quote() > 0


def test_missing_oco_is_restored_from_persisted_position(tmp_path: Path):
    os.environ["WILLIAMS_DB_PATH"] = str(tmp_path / "restore.sqlite3")
    db = Database(os.environ["WILLIAMS_DB_PATH"])
    client = FakeClient()

    trade_id = add_trade(
        db,
        symbol="SOLUSDT",
        entry_price=200,
        quantity=3,
        entry_order_id="sol-buy-1",
        entry_client_order_id="WILLV4_ENTRY_SOL",
        stop_price=196,
        take_profit_price=206,
        risk_pct=0.002,
    )
    client.orders_by_symbol["SOLUSDT"] = [{
        "symbol": "SOLUSDT",
        "side": "BUY",
        "type": "MARKET",
        "orderId": "sol-buy-1",
        "clientOrderId": "WILLV4_ENTRY_SOL",
        "status": "FILLED",
        "executedQty": "3",
        "cummulativeQuoteQty": "600",
        "time": 100,
    }]

    multi = MultiPositionTrader(client, db=db)
    result = multi.reconcile_open_positions()

    assert result[0]["trade_id"] == trade_id
    assert result[0]["state"] == "OPEN"
    assert result[0]["protected"] is True
    assert result[0]["oco_restored"] is True
    assert len(client.created_oco) == 1

    trade = db.open_trade("SOLUSDT")
    assert trade is not None
    assert trade["exit_order_list_id"] is not None
    assert trade["exit_order_list_client_id"].startswith("WILLV4_OCO_")
    assert trade["stop_price"] < trade["entry_price"] < trade["take_profit_price"]


def test_filled_pending_buy_is_recovered_into_its_own_position(tmp_path: Path):
    os.environ["WILLIAMS_DB_PATH"] = str(tmp_path / "pending.sqlite3")
    db = Database(os.environ["WILLIAMS_DB_PATH"])
    client = FakeClient()

    client.orders_by_symbol["SOLUSDT"] = [{
        "symbol": "SOLUSDT",
        "side": "BUY",
        "type": "MARKET",
        "orderId": "sol-buy-9",
        "clientOrderId": "WILLV4_ENTRY_PENDING_SOL",
        "status": "FILLED",
        "executedQty": "3",
        "cummulativeQuoteQty": "600",
        "time": 100,
    }]
    db.state_set(
        "entry_client_order_id:SOLUSDT",
        "WILLV4_ENTRY_PENDING_SOL",
    )
    db.state_set(
        "position_state:SOLUSDT",
        "ENTRY_PENDING",
    )

    multi = MultiPositionTrader(client, db=db)
    result = multi.recover_pending_entries()

    assert result[0]["symbol"] == "SOLUSDT"
    trade = db.open_trade("SOLUSDT")
    assert trade is not None
    assert trade["entry_order_id"] == "sol-buy-9"
    assert trade["entry_client_order_id"] == "WILLV4_ENTRY_PENDING_SOL"
    assert db.state_get("entry_client_order_id:SOLUSDT") is None
    assert multi.state("SOLUSDT") == "OPEN"
    assert len(client.created_oco) == 1


def test_recover_clears_stale_reconcile_barrier_when_exchange_is_clean(tmp_path: Path):
    os.environ["WILLIAMS_DB_PATH"] = str(tmp_path / "stale_reconcile.sqlite3")
    db = Database(os.environ["WILLIAMS_DB_PATH"])
    client = FakeClient()

    # Simulate the exact failure mode: a stale per-symbol barrier survives
    # after the managed trade has already disappeared from SQLite/exchange.
    db.state_set("position_state", "RECONCILE_REQUIRED")
    db.state_set("position_state:BTCUSDT", "RECONCILE_REQUIRED")

    multi = MultiPositionTrader(
        client,
        db=db,
        symbols=["BTCUSDT"],
    )

    result = multi.recover()

    assert result["ok"] is True
    assert multi.state("BTCUSDT") == "FLAT"
    assert db.state_get("position_state") == "FLAT"
    assert db.state_get("position_state:BTCUSDT") == "FLAT"


def test_recover_keeps_reconcile_barrier_for_unresolved_exchange_position(tmp_path: Path):
    os.environ["WILLIAMS_DB_PATH"] = str(tmp_path / "unresolved_reconcile.sqlite3")
    db = Database(os.environ["WILLIAMS_DB_PATH"])
    client = FakeClient()

    add_trade(
        db,
        symbol="SOLUSDT",
        entry_price=200,
        quantity=3,
        entry_order_id="sol-buy-unresolved",
        entry_client_order_id="WILLV4_ENTRY_SOL_UNRESOLVED",
        stop_price=196,
        take_profit_price=206,
        risk_pct=0.002,
    )
    client.orders_by_symbol["SOLUSDT"] = [{
        "symbol": "SOLUSDT",
        "side": "BUY",
        "type": "MARKET",
        "orderId": "sol-buy-unresolved",
        "clientOrderId": "WILLV4_ENTRY_SOL_UNRESOLVED",
        "status": "FILLED",
        "executedQty": "3",
        "cummulativeQuoteQty": "600",
        "time": 100,
    }]
    client.account = lambda: {
        "balances": [
            {"asset": "USDT", "free": "9000", "locked": "0"},
            {"asset": "SOL", "free": "1.0", "locked": "0"},
        ]
    }
    db.state_set("position_state:SOLUSDT", "RECONCILE_REQUIRED")

    multi = MultiPositionTrader(client, db=db, symbols=["SOLUSDT"])
    result = multi.recover()

    assert result["ok"] is False
    assert multi.state("SOLUSDT") == "RECONCILE_REQUIRED"
    assert db.state_get("position_state") == "RECONCILE_REQUIRED"



def test_partial_manual_sell_keeps_residual_position_protected(tmp_path: Path):
    class ManualPartialClient(FakeClient):
        def __init__(self):
            super().__init__()
            self.cancelled = False
            self.manual_order = None

        def account(self):
            return {
                "balances": [
                    {"asset": "USDT", "free": "9400", "locked": "0"},
                    {"asset": "BTC", "free": "0.4", "locked": "0"},
                ],
            }

        def cancel_oco(self, symbol, order_list_id=None, list_client_order_id=None):
            self.cancelled = True
            return {"orderListId": order_list_id or 10}

        def order_safe(self, symbol, side, type_, *, quantity=None, new_client_order_id=None, **kwargs):
            assert side == "SELL"
            assert type_ == "MARKET"
            self.manual_order = {
                "symbol": symbol,
                "side": "SELL",
                "type": "MARKET",
                "orderId": "manual-1",
                "clientOrderId": new_client_order_id,
                "status": "FILLED",
                "executedQty": "0.6",
                "cummulativeQuoteQty": "60",
            }
            return self.manual_order

        def all_orders(self, symbol, limit=1000):
            return [
                {
                    "symbol": "BTCUSDT",
                    "side": "BUY",
                    "type": "MARKET",
                    "orderId": "buy-1",
                    "clientOrderId": "WILLV4_ENTRY_TEST",
                    "status": "FILLED",
                    "executedQty": "1.0",
                    "cummulativeQuoteQty": "100",
                    "time": 1000,
                },
                self.manual_order or {},
            ]

        def book_ticker(self, symbol=None):
            return {"symbol": symbol or "BTCUSDT", "bidPrice": "100", "askPrice": "100"}

        def ticker_price(self, symbol):
            return {"symbol": symbol, "price": "100"}

    db = Database(str(tmp_path / "manual-partial.sqlite3"))
    client = ManualPartialClient()
    trade_id = add_trade(
        db,
        symbol="BTCUSDT",
        entry_price=100,
        quantity=1.0,
        entry_order_id="buy-1",
        entry_client_order_id="WILLV4_ENTRY_TEST",
        exit_order_list_id="10",
        exit_order_list_client_id="WILLV4_OCO_TEST",
        stop_price=98,
        take_profit_price=104,
        risk_pct=0.5,
    )
    db.state_set("position_state:BTCUSDT", "OPEN")
    trader = MultiPositionTrader(client, db=db, symbols=["BTCUSDT"])
    result = trader.manual_sell("BTCUSDT")

    assert result["sold"] is True
    assert result["partial"] is True
    assert abs(float(result["residual_quantity"]) - 0.4) < 1e-9
    trade = db.open_trade("BTCUSDT")
    assert trade is not None
    assert trade["id"] == trade_id
    assert abs(float(trade["quantity"]) - 0.4) < 1e-9
    assert trader.state("BTCUSDT") == "OPEN"
    assert client.cancelled is True
    assert len(client.created_oco) == 1
    assert abs(client.created_oco[0]["quantity"] - 0.4) < 1e-9
    assert str(client.manual_order["clientOrderId"]).startswith("WILLV4_MANUAL_")


def test_partial_emergency_sell_never_closes_trade_as_flat(tmp_path: Path):
    class EmergencyPartialClient(FakeClient):
        def account(self):
            return {
                "balances": [
                    {"asset": "USDT", "free": "9400", "locked": "0"},
                    {"asset": "BTC", "free": "0.4", "locked": "0"},
                ],
            }

        def order_safe(self, symbol, side, type_, *, quantity=None, new_client_order_id=None, **kwargs):
            assert side == "SELL"
            assert type_ == "MARKET"
            return {
                "symbol": symbol,
                "side": "SELL",
                "type": "MARKET",
                "orderId": "emergency-1",
                "clientOrderId": new_client_order_id,
                "status": "FILLED",
                "executedQty": "0.6",
                "cummulativeQuoteQty": "60",
            }

        def get_order(self, symbol, order_id=None, orig_client_order_id=None):
            return {
                "symbol": symbol,
                "side": "SELL",
                "type": "MARKET",
                "orderId": "emergency-1",
                "clientOrderId": orig_client_order_id or "WILLV4_EMERGENCY_TEST",
                "status": "FILLED",
                "executedQty": "0.6",
                "cummulativeQuoteQty": "60",
            }

    db = Database(str(tmp_path / "emergency-partial.sqlite3"))
    client = EmergencyPartialClient()
    trade_id = add_trade(
        db,
        symbol="BTCUSDT",
        entry_price=100,
        quantity=1.0,
        entry_order_id="buy-1",
        entry_client_order_id="WILLV4_ENTRY_TEST",
        stop_price=98,
        take_profit_price=104,
        risk_pct=0.5,
    )
    db.state_set("position_state:BTCUSDT", "OPEN")
    trader = MultiPositionTrader(client, db=db, symbols=["BTCUSDT"])
    result = trader._emergency_market_sell("BTCUSDT", 1.0, trade_id, "TEST_PARTIAL")

    assert result["emergency_exit"] is True
    assert result["state"] == "RECONCILE_REQUIRED"
    assert abs(float(result["residual_quantity"]) - 0.4) < 1e-9
    trade = db.open_trade("BTCUSDT")
    assert trade is not None
    assert abs(float(trade["quantity"]) - 0.4) < 1e-9
    assert trader.state("BTCUSDT") == "RECONCILE_REQUIRED"
