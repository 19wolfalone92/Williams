import json
import os
import threading
import time

from db import Database
from portfolio_trader import MultiPositionTrader
from telegram_bot import Telegram
from ws_hub import WebSocketHub


def _filters():
    return {
        "symbols": [
            {
                "symbol": "BTCUSDT",
                "status": "TRADING",
                "baseAsset": "BTC",
                "quoteAsset": "USDT",
                "filters": [
                    {
                        "filterType": "PRICE_FILTER",
                        "minPrice": "0.01",
                        "maxPrice": "1000000",
                        "tickSize": "0.01",
                    },
                    {
                        "filterType": "LOT_SIZE",
                        "minQty": "0.0001",
                        "maxQty": "1000",
                        "stepSize": "0.0001",
                    },
                    {
                        "filterType": "MIN_NOTIONAL",
                        "minNotional": "10",
                    },
                ],
            }
        ]
    }


class FakeBinance:
    def __init__(self, *, entry_status="CANCELED", entry_qty="0.4", exit_qty="0.0"):
        self.entry_status = entry_status
        self.entry_qty = entry_qty
        self.exit_qty = exit_qty
        self.created_oco_qty = None
        self.oco_calls = 0
        self.open_orders_calls = 0

    def all_orders(self, symbol, limit=1000):
        rows = [
            {
                "symbol": "BTCUSDT",
                "side": "BUY",
                "type": "MARKET",
                "orderId": "1",
                "clientOrderId": "WILLV4_ENTRY_TEST",
                "status": self.entry_status,
                "executedQty": self.entry_qty,
                "cummulativeQuoteQty": str(float(self.entry_qty) * 100),
                "time": 1000,
            }
        ]
        if float(self.exit_qty) > 0:
            rows.append(
                {
                    "symbol": "BTCUSDT",
                    "side": "SELL",
                    "type": "STOP_LOSS_LIMIT",
                    "orderId": "2",
                    "orderListId": "10",
                    "clientOrderId": "WILLV4_OCO_TEST",
                    "status": "CANCELED",
                    "executedQty": self.exit_qty,
                    "cummulativeQuoteQty": str(float(self.exit_qty) * 98),
                    "time": 2000,
                }
            )
        return rows

    def get_order(self, symbol, orig_client_order_id=None, **kwargs):
        return {
            "symbol": "BTCUSDT",
            "side": "BUY",
            "type": "MARKET",
            "orderId": "1",
            "clientOrderId": orig_client_order_id or "WILLV4_ENTRY_TEST",
            "status": self.entry_status,
            "executedQty": self.entry_qty,
            "cummulativeQuoteQty": str(float(self.entry_qty) * 100),
            "transactTime": 1000,
        }

    def exchange_info(self, symbol):
        return _filters()

    def account(self):
        remaining = max(0.0, float(self.entry_qty) - float(self.exit_qty))
        return {
            "accountType": "SPOT",
            "balances": [
                {"asset": "USDT", "free": "1000", "locked": "0"},
                {"asset": "BTC", "free": str(remaining), "locked": "0"},
            ],
        }

    def open_order_lists(self, symbol=None):
        return []

    def all_order_lists(self, symbol=None, limit=100):
        if float(self.exit_qty) > 0:
            return [
                {
                    "symbol": "BTCUSDT",
                    "orderListId": "10",
                    "listClientOrderId": "WILLV4_OCO_TEST",
                    "listStatusType": "ALL_DONE",
                }
            ]
        return []

    def open_orders(self, symbol=None):
        self.open_orders_calls += 1
        return [
            {
                "symbol": "BTCUSDT",
                "side": "SELL",
                "type": "STOP_LOSS_LIMIT",
                "orderId": "99",
                "orderListId": "10",
                "clientOrderId": "WILLV4_OCO_TEST",
                "status": "NEW",
                "origQty": "0.4",
                "executedQty": "0",
                "price": "97.5",
                "stopPrice": "98",
            }
        ]

    def book_ticker(self, symbol):
        return {"bidPrice": "100", "askPrice": "100.01"}

    def ticker_price(self, symbol):
        return {"price": "100"}

    @staticmethod
    def decimal_floor(value, step):
        from decimal import Decimal, ROUND_DOWN

        return (
            Decimal(str(value)) / Decimal(str(step))
        ).to_integral_value(rounding=ROUND_DOWN) * Decimal(str(step))

    @staticmethod
    def decimal_format(value):
        return format(value, "f") if hasattr(value, "__format__") else str(value)

    def create_oco_sell_safe(
        self,
        symbol,
        quantity,
        take_profit_price,
        stop_price,
        stop_limit_price,
        list_client_order_id,
    ):
        self.oco_calls += 1
        self.created_oco_qty = float(quantity)
        return {
            "orderListId": "20",
            "listClientOrderId": list_client_order_id,
            "orderReports": [
                {
                    "symbol": symbol,
                    "side": "SELL",
                    "type": "TAKE_PROFIT_LIMIT",
                    "orderId": "21",
                    "orderListId": "20",
                    "clientOrderId": list_client_order_id + "_TP",
                    "status": "NEW",
                    "origQty": str(quantity),
                    "executedQty": "0",
                    "price": take_profit_price,
                    "stopPrice": take_profit_price,
                },
                {
                    "symbol": symbol,
                    "side": "SELL",
                    "type": "STOP_LOSS_LIMIT",
                    "orderId": "22",
                    "orderListId": "20",
                    "clientOrderId": list_client_order_id + "_SL",
                    "status": "NEW",
                    "origQty": str(quantity),
                    "executedQty": "0",
                    "price": stop_limit_price,
                    "stopPrice": stop_price,
                },
            ],
        }


def test_terminal_partial_entry_is_materialized_and_protected(tmp_path):
    db = Database(str(tmp_path / "partial-entry.sqlite3"))

    class NoOpenOrdersFakeBinance(FakeBinance):
        def open_orders(self, symbol=None):
            self.open_orders_calls += 1
            return []

    client = NoOpenOrdersFakeBinance(entry_status="CANCELED", entry_qty="0.4")
    trader = MultiPositionTrader(client, db=db, symbols=[])

    db.state_set("entry_client_order_id:BTCUSDT", "WILLV4_ENTRY_TEST")
    db.state_set("position_state:BTCUSDT", "ENTRY_PENDING")

    result = trader.recover()

    assert result["ok"] is True
    trade = db.open_trade("BTCUSDT")
    assert trade is not None
    assert abs(float(trade["quantity"]) - 0.4) < 1e-9
    assert trader.state("BTCUSDT") == "OPEN"
    assert client.created_oco_qty == 0.4


def test_terminal_partial_oco_is_counted_once_and_reprotected(tmp_path):
    db = Database(str(tmp_path / "partial-oco.sqlite3"))

    class TerminalOcoFakeBinance(FakeBinance):
        def open_orders(self, symbol=None):
            self.open_orders_calls += 1
            return []

    client = TerminalOcoFakeBinance(
        entry_status="FILLED",
        entry_qty="1.0",
        exit_qty="0.3",
    )
    trader = MultiPositionTrader(client, db=db, symbols=[])

    trade_id = db.save_trade(
        entry_time="1970-01-01T00:00:01+00:00",
        symbol="BTCUSDT",
        side="LONG",
        entry_price=100.0,
        quantity=1.0,
        entry_order_id="1",
        entry_client_order_id="WILLV4_ENTRY_TEST",
        exit_order_list_id="10",
        exit_order_list_client_id="WILLV4_OCO_TEST",
        stop_price=98.0,
        take_profit_price=104.0,
        risk_pct=0.5,
        fees=0.0,
    )
    db.state_set("position_state:BTCUSDT", "OPEN")

    first = trader._reconcile_trade(trade_id)
    assert first["state"] == "OPEN"
    assert first["oco_restored"] is True
    assert abs(float(db.open_trade("BTCUSDT")["quantity"]) - 0.7) < 1e-9
    assert abs(client.created_oco_qty - 0.7) < 1e-9

    # Same historical CANCELED/PARTIAL order is seen again. The quantity must
    # remain 0.7 rather than being reduced to 0.4 a second time.
    second = trader._reconcile_trade(trade_id)
    assert second["state"] == "OPEN"
    assert abs(float(db.open_trade("BTCUSDT")["quantity"]) - 0.7) < 1e-9
    assert client.oco_calls == 2


def test_pending_entry_is_a_durable_duplicate_entry_barrier(tmp_path):
    db = Database(str(tmp_path / "dedupe.sqlite3"))
    client = FakeBinance()
    trader = MultiPositionTrader(client, db=db, symbols=[])

    db.state_set("entry_client_order_id:BTCUSDT", "WILLV4_ENTRY_TEST")
    db.state_set("position_state:BTCUSDT", "ENTRY_PENDING")

    assert trader._can_enter("BTCUSDT") is False


def test_user_stream_subscription_reconnect_performs_rest_catchup(tmp_path):
    old = os.environ.get("WILLIAMS_DB_PATH")
    os.environ["WILLIAMS_DB_PATH"] = str(tmp_path / "ws.sqlite3")
    try:
        hub = WebSocketHub()
        fake = FakeBinance()
        hub.client = fake
        hub.symbol = "BTCUSDT"
        hub.base_asset = "BTC"
        hub.quote_asset = "USDT"

        hub._on_user_message(
            None,
            json.dumps(
                {
                    "status": 200,
                    "result": {"subscriptionId": 7},
                }
            ),
        )

        assert hub.user_connected is True
        assert hub.user_sync_required is False
        assert fake.open_orders_calls >= 1

        rows = hub.db.recent_orders("BTCUSDT", 10)
        assert len(rows) == 1
        assert rows[0]["order_id"] == "99"
    finally:
        if old is None:
            os.environ.pop("WILLIAMS_DB_PATH", None)
        else:
            os.environ["WILLIAMS_DB_PATH"] = old


def test_telegram_send_does_not_wait_for_network(monkeypatch):
    started = threading.Event()
    release = threading.Event()

    def fake_post(*args, **kwargs):
        started.set()
        release.wait(1)
        class Response:
            def raise_for_status(self):
                return None
        return Response()

    monkeypatch.setattr("telegram_bot.requests.post", fake_post)
    tg = Telegram("token", "chat")

    t0 = time.monotonic()
    assert tg.send("integration-test") is True
    elapsed = time.monotonic() - t0

    assert elapsed < 0.1
    assert started.wait(0.5)
    release.set()
    tg.close()


def test_active_oco_two_legs_represent_one_quantity(tmp_path):
    db = Database(str(tmp_path / "active-oco.sqlite3"))

    class ActiveOcoBinance(FakeBinance):
        def open_orders(self, symbol=None):
            return [
                {
                    "symbol": "BTCUSDT",
                    "side": "SELL",
                    "type": "TAKE_PROFIT_LIMIT",
                    "orderId": "21",
                    "orderListId": "10",
                    "clientOrderId": "WILLV4_OCO_TEST_TP",
                    "status": "NEW",
                    "origQty": "1.0",
                    "executedQty": "0",
                    "price": "104",
                    "stopPrice": "104",
                },
                {
                    "symbol": "BTCUSDT",
                    "side": "SELL",
                    "type": "STOP_LOSS_LIMIT",
                    "orderId": "22",
                    "orderListId": "10",
                    "clientOrderId": "WILLV4_OCO_TEST_SL",
                    "status": "NEW",
                    "origQty": "1.0",
                    "executedQty": "0",
                    "price": "97.5",
                    "stopPrice": "98",
                },
            ]

        def all_order_lists(self, symbol=None, limit=100):
            return [{
                "symbol": "BTCUSDT",
                "orderListId": "10",
                "listClientOrderId": "WILLV4_OCO_TEST",
                "listStatusType": "EXEC_STARTED",
            }]

    client = ActiveOcoBinance(entry_status="FILLED", entry_qty="1.0")
    trader = MultiPositionTrader(client, db=db, symbols=[])
    trade_id = db.save_trade(
        entry_time="1970-01-01T00:00:01+00:00",
        symbol="BTCUSDT",
        side="LONG",
        entry_price=100.0,
        quantity=1.0,
        entry_order_id="1",
        entry_client_order_id="WILLV4_ENTRY_TEST",
        exit_order_list_id="10",
        exit_order_list_client_id="WILLV4_OCO_TEST",
        stop_price=98.0,
        take_profit_price=104.0,
        risk_pct=0.5,
        fees=0.0,
    )
    db.state_set("position_state:BTCUSDT", "OPEN")

    result = trader._reconcile_trade(trade_id)

    assert result["state"] == "OPEN"
    assert result["protected"] is True
    assert result["remaining_quantity"] == 1.0



def test_active_oco_partial_fill_keeps_second_leg_as_shared_protection(tmp_path):
    """One OCO leg may be PARTIALLY_FILLED while its sibling remains NEW.

    The two child SELLs are alternative exits for one inventory quantity. A
    0.30 fill against a 1.00 position therefore leaves exactly 0.70 managed,
    not 1.70 and not 1.00. The active OCO must remain recognized as one bundle.
    """
    db = Database(str(tmp_path / "active-partial-oco.sqlite3"))

    class PartialActiveOcoBinance(FakeBinance):
        def open_orders(self, symbol=None):
            return [
                {
                    "symbol": "BTCUSDT",
                    "side": "SELL",
                    "type": "TAKE_PROFIT_LIMIT",
                    "orderId": "21",
                    "orderListId": "10",
                    "clientOrderId": "WILLV4_OCO_TEST_TP",
                    "status": "PARTIALLY_FILLED",
                    "origQty": "1.0",
                    "executedQty": "0.3",
                    "cummulativeQuoteQty": "30",
                    "price": "104",
                    "stopPrice": "104",
                },
                {
                    "symbol": "BTCUSDT",
                    "side": "SELL",
                    "type": "STOP_LOSS_LIMIT",
                    "orderId": "22",
                    "orderListId": "10",
                    "clientOrderId": "WILLV4_OCO_TEST_SL",
                    "status": "NEW",
                    "origQty": "1.0",
                    "executedQty": "0",
                    "cummulativeQuoteQty": "0",
                    "price": "97.5",
                    "stopPrice": "98",
                },
            ]

        def all_order_lists(self, symbol=None, limit=100):
            return [{
                "symbol": "BTCUSDT",
                "orderListId": "10",
                "listClientOrderId": "WILLV4_OCO_TEST",
                "listStatusType": "EXEC_STARTED",
                "listOrderStatus": "EXECUTING",
            }]

        def all_orders(self, symbol, limit=1000):
            rows = [
                {
                    "symbol": "BTCUSDT",
                    "side": "BUY",
                    "type": "MARKET",
                    "orderId": "1",
                    "clientOrderId": "WILLV4_ENTRY_TEST",
                    "status": "FILLED",
                    "executedQty": "1.0",
                    "cummulativeQuoteQty": "100",
                    "time": 1000,
                },
                {
                    "symbol": "BTCUSDT",
                    "side": "SELL",
                    "type": "TAKE_PROFIT_LIMIT",
                    "orderId": "21",
                    "orderListId": "10",
                    "clientOrderId": "WILLV4_OCO_TEST_TP",
                    "status": "PARTIALLY_FILLED",
                    "origQty": "1.0",
                    "executedQty": "0.3",
                    "cummulativeQuoteQty": "30",
                    "time": 2000,
                },
            ]
            return rows

        def account(self):
            return {
                "accountType": "SPOT",
                "balances": [
                    {"asset": "USDT", "free": "900", "locked": "0"},
                    {"asset": "BTC", "free": "0.7", "locked": "0"},
                ],
            }

    client = PartialActiveOcoBinance(
        entry_status="FILLED",
        entry_qty="1.0",
    )
    trader = MultiPositionTrader(client, db=db, symbols=[])

    trade_id = db.save_trade(
        entry_time="1970-01-01T00:00:01+00:00",
        symbol="BTCUSDT",
        side="LONG",
        entry_price=100.0,
        quantity=1.0,
        entry_order_id="1",
        entry_client_order_id="WILLV4_ENTRY_TEST",
        exit_order_list_id="10",
        exit_order_list_client_id="WILLV4_OCO_TEST",
        stop_price=98.0,
        take_profit_price=104.0,
        risk_pct=0.5,
        fees=0.0,
    )
    db.state_set("position_state:BTCUSDT", "OPEN")

    result = trader._reconcile_trade(trade_id)

    assert result["state"] == "OPEN"
    assert result["protected"] is True
    assert result["partial_exit"] is True
    assert abs(result["remaining_quantity"] - 0.7) < 1e-9
    assert abs(float(db.open_trade("BTCUSDT")["quantity"]) - 0.7) < 1e-9
    assert client.oco_calls == 0




def test_market_klines_uses_requested_symbol_and_interval(monkeypatch):
    import pandas as pd
    import server

    class TraderStub:
        symbol = "BTCUSDT"
        interval = "1h"

        class Client:
            pass

        client = Client()

    captured = {}

    def fake_fetch(client, symbol, interval, limit=120):
        captured.update(symbol=symbol, interval=interval, limit=limit)
        idx = pd.date_range("2026-10-01", periods=40, freq="h", tz="UTC")
        return pd.DataFrame(
            {
                "open": [100.0] * 40,
                "high": [101.0] * 40,
                "low": [99.0] * 40,
                "close": [100.0] * 40,
                "volume": [10.0] * 40,
            },
            index=idx,
        )

    monkeypatch.setattr(server.state, "ensure_trader", lambda: TraderStub())
    monkeypatch.setattr(server, "fetch_klines", fake_fetch)

    payload = server.market_klines(limit=30, symbol="ETHUSDT", interval="4h")

    assert captured == {"symbol": "ETHUSDT", "interval": "4h", "limit": 30}
    assert payload["symbol"] == "ETHUSDT"
    assert payload["interval"] == "4h"
    assert len(payload["candles"]) == 39



def test_execution_accumulator_removes_base_asset_commission_from_managed_qty():
    from execution_accumulator import accumulate_order

    summary = accumulate_order(
        {
            "symbol": "BTCUSDT",
            "side": "BUY",
            "status": "FILLED",
            "executedQty": "1.0",
            "cummulativeQuoteQty": "100",
            "fills": [
                {
                    "price": "100",
                    "qty": "1.0",
                    "commission": "0.001",
                    "commissionAsset": "BTC",
                }
            ],
        },
        base_asset="BTC",
    )

    assert abs(float(summary.executed_qty) - 1.0) < 1e-12
    assert abs(float(summary.net_base_qty) - 0.999) < 1e-12
    assert abs(float(summary.fee_quote_equivalent) - 0.1) < 1e-12


def test_execution_accumulator_keeps_quote_commission_as_quote_fee():
    from execution_accumulator import accumulate_order

    summary = accumulate_order(
        {
            "symbol": "BTCUSDT",
            "side": "BUY",
            "status": "FILLED",
            "fills": [
                {
                    "price": "100",
                    "qty": "1.0",
                    "commission": "0.1",
                    "commissionAsset": "USDT",
                }
            ],
        },
        base_asset="BTC",
    )

    assert abs(float(summary.fee_quote) - 0.1) < 1e-12
    assert abs(float(summary.fee_quote_equivalent) - 0.1) < 1e-12
    assert abs(float(summary.net_base_qty) - 1.0) < 1e-12
