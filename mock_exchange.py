"""Deterministic Binance-like exchange mock for failure/recovery testing."""
from __future__ import annotations

import copy
import time
import uuid


class MockExchange:
    def __init__(self):
        self.testnet = True
        self.api_key = "mock-key"
        self.api_secret = "mock-secret"
        self.prices = {"BTCUSDT": 100.0, "ETHUSDT": 10.0}
        self.balances = {"USDT": 10_000.0, "BTC": 0.0, "ETH": 0.0}
        self.books = {
            "BTCUSDT": {"bids": [["99.99", "10"]], "asks": [["100.01", "10"]], "lastUpdateId": 1},
            "ETHUSDT": {"bids": [["9.99", "100"]], "asks": [["10.01", "100"]], "lastUpdateId": 1},
        }
        self.orders: list[dict] = []
        self.open_oco: dict[str, dict] = {}
        self.timeout_next = False
        self.partial_fill_ratio = 1.0
        self.sequence_gap_next = False

    def ticker_price(self, symbol):
        return {"symbol": symbol, "price": str(self.prices[symbol])}

    def book_ticker(self, symbol):
        book = self.books[symbol]
        return {
            "symbol": symbol,
            "bidPrice": book["bids"][0][0],
            "askPrice": book["asks"][0][0],
            "bidQty": book["bids"][0][1],
            "askQty": book["asks"][0][1],
        }

    def depth(self, symbol, limit=20):
        data = copy.deepcopy(self.books[symbol])
        if self.sequence_gap_next:
            self.sequence_gap_next = False
            data["lastUpdateId"] += 10
        return data

    def depth_update(self, symbol):
        """Return one Binance-like depthUpdate event with controllable U/u gap."""
        book = self.books[symbol]
        base = int(book["lastUpdateId"])
        first = base + 1
        final = first
        if self.sequence_gap_next:
            self.sequence_gap_next = False
            first += 5
            final += 5
        book["lastUpdateId"] = final
        return {
            "e": "depthUpdate",
            "s": symbol,
            "U": first,
            "u": final,
            "b": copy.deepcopy(book["bids"]),
            "a": copy.deepcopy(book["asks"]),
        }

    def ticker_24hr(self, symbol=None):
        rows = []
        for s in self.prices:
            rows.append({"symbol": s, "quoteVolume": "1000000"})
        return rows if symbol is None else next(x for x in rows if x["symbol"] == symbol)

    def exchange_info(self, symbol=None):
        rows = []
        for s, price in self.prices.items():
            base = s.replace("USDT", "")
            rows.append({
                "symbol": s,
                "status": "TRADING",
                "quoteAsset": "USDT",
                "baseAsset": base,
                "isSpotTradingAllowed": True,
                "permissions": ["SPOT"],
                "filters": [
                    {"filterType": "LOT_SIZE", "minQty": "0.001", "stepSize": "0.001", "maxQty": "100000"},
                    {"filterType": "MARKET_LOT_SIZE", "minQty": "0.001", "stepSize": "0.001", "maxQty": "100000"},
                    {"filterType": "PRICE_FILTER", "minPrice": "0.01", "maxPrice": "1000000", "tickSize": "0.01"},
                    {"filterType": "MIN_NOTIONAL", "minNotional": "10"},
                    {"filterType": "MAX_NUM_ORDERS", "maxNumOrders": "25"},
                    {"filterType": "MAX_NUM_ALGO_ORDERS", "maxNumAlgoOrders": "10"},
                    {"filterType": "MAX_NUM_ORDER_LISTS", "maxNumOrderLists": "10"},
                    {"filterType": "MAX_POSITION", "maxPosition": "100000"},
                ],
            })
        return {"symbols": rows} if symbol is None else {"symbols": [x for x in rows if x["symbol"] == symbol]}

    def account(self):
        return {"balances": [
            {"asset": k, "free": str(v), "locked": "0.0"}
            for k, v in self.balances.items()
        ]}

    def _new_order(self, symbol, side, qty, status="FILLED", client_order_id="", order_type="MARKET", stop_price=0.0):
        oid = str(len(self.orders) + 1)
        executed = (
            float(qty) * float(self.partial_fill_ratio)
            if str(status).upper() != "NEW"
            else 0.0
        )
        order = {
            "symbol": symbol,
            "side": side,
            "type": order_type,
            "status": status,
            "orderId": oid,
            "clientOrderId": client_order_id or "MOCK_" + uuid.uuid4().hex[:10],
            "origQty": str(qty),
            "executedQty": str(executed),
            "stopPrice": str(stop_price) if float(stop_price or 0) > 0 else "0",
            "transactTime": int(time.time() * 1000),
            "time": int(time.time() * 1000),
        }
        self.orders.append(order)
        return order

    def market_buy(self, symbol, quote_order_qty=None, quantity=None, client_order_id=None):
        if self.timeout_next:
            self.timeout_next = False
            raise TimeoutError("mock 504 timeout")
        price = self.prices[symbol]
        qty = float(quantity) if quantity is not None else float(quote_order_qty) / price
        qty = max(0.0, qty)
        filled = qty * float(self.partial_fill_ratio)
        self.balances["USDT"] -= filled * price
        self.balances[symbol.replace("USDT", "")] += filled
        return self._new_order(symbol, "BUY", qty, client_order_id=client_order_id or "")

    def market_sell(self, symbol, quantity, client_order_id=None):
        if self.timeout_next:
            self.timeout_next = False
            raise TimeoutError("mock 504 timeout")
        base = symbol.replace("USDT", "")
        filled = min(float(quantity) * float(self.partial_fill_ratio), self.balances.get(base, 0.0))
        self.balances[base] -= filled
        self.balances["USDT"] += filled * self.prices[symbol]
        return self._new_order(symbol, "SELL", quantity, client_order_id=client_order_id or "")

    def order_safe(self, symbol, side, type_, *, quantity=None, quote_order_qty=None,
                   price=None, stop_price=None, time_in_force=None,
                   new_client_order_id=None, **kwargs):
        if self.timeout_next:
            self.timeout_next = False
            raise TimeoutError("mock transport timeout")
        if str(type_).upper() == "STOP_LOSS":
            qty = float(quantity or 0)
            order = self._new_order(
                symbol,
                side,
                qty,
                status="NEW",
                client_order_id=new_client_order_id or "",
                order_type="STOP_LOSS",
                stop_price=float(stop_price or 0),
            )
            return order
        if str(type_).upper() == "MARKET":
            if str(side).upper() == "BUY":
                return self.market_buy(
                    symbol,
                    quote_order_qty=quote_order_qty,
                    quantity=quantity,
                    client_order_id=new_client_order_id,
                )
            return self.market_sell(
                symbol,
                quantity=float(quantity or 0),
                client_order_id=new_client_order_id,
            )
        raise ValueError("MockExchange supports MARKET and STOP_LOSS")

    def get_order(self, symbol, order_id=None, orig_client_order_id=None):
        for order in reversed(self.orders):
            if order["symbol"] != symbol:
                continue
            if order_id is not None and str(order["orderId"]) == str(order_id):
                return copy.deepcopy(order)
            if orig_client_order_id is not None and order["clientOrderId"] == orig_client_order_id:
                return copy.deepcopy(order)
        raise RuntimeError("mock order not found")

    def cancel_order(self, symbol, order_id=None, orig_client_order_id=None):
        order = self.get_order(symbol, order_id=order_id, orig_client_order_id=orig_client_order_id)
        for row in self.orders:
            if row["orderId"] == order["orderId"]:
                row["status"] = "CANCELED"
        return copy.deepcopy(order)

    def set_price(self, symbol, price):
        self.prices[symbol] = float(price)
        # Trigger BUY stops upward and SELL stops downward. A trigger is an
        # exchange event; execution occurs at the new simulated market price.
        for order in list(self.orders):
            if order["symbol"] != symbol or order["status"] != "NEW":
                continue
            stop = float(order.get("stopPrice", 0) or 0)
            if stop <= 0:
                continue
            side = str(order["side"]).upper()
            triggered = (
                side == "BUY" and self.prices[symbol] >= stop
            ) or (
                side == "SELL" and self.prices[symbol] <= stop
            )
            if not triggered:
                continue

            requested = float(order.get("origQty", 0) or 0)
            executed = requested * float(self.partial_fill_ratio)
            if side == "BUY":
                cost = executed * self.prices[symbol]
                self.balances["USDT"] -= cost
                self.balances[symbol.replace("USDT", "")] += executed
            else:
                base = symbol.replace("USDT", "")
                executed = min(executed, self.balances.get(base, 0.0))
                self.balances[base] -= executed
                self.balances["USDT"] += executed * self.prices[symbol]

            order["executedQty"] = str(executed)
            order["cummulativeQuoteQty"] = str(executed * self.prices[symbol])
            order["transactTime"] = int(time.time() * 1000)
            order["time"] = order["transactTime"]
            order["status"] = "FILLED" if executed + 1e-12 >= requested else "PARTIALLY_FILLED"

    def open_order_lists(self):
        return [copy.deepcopy(x) for x in self.open_oco.values()]

    def all_order_lists(self, symbol=None, limit=100):
        rows = self.open_order_lists()
        if symbol:
            rows = [x for x in rows if x["symbol"] == symbol]
        return rows[:limit]

    def cancelReplace(self, *args, **kwargs):
        raise NotImplementedError("Use the BinanceSpotClient-compatible cancel_replace method in tests")

    def cancel_replace(self, symbol, cancel_order_id, side, type_, *, quantity=None, price=None, stop_price=None,
                       time_in_force=None, new_client_order_id=None):
        old = self.get_order(symbol, order_id=cancel_order_id)
        self.cancel_order(symbol, order_id=cancel_order_id)
        return {
            "cancelResult": "SUCCESS",
            "newOrderResult": "SUCCESS",
            "newOrderResponse": self.order_safe(
                symbol,
                side,
                type_,
                quantity=quantity,
                price=price,
                stop_price=stop_price,
                time_in_force=time_in_force,
                new_client_order_id=new_client_order_id,
            ),
        }

    def create_oco_sell(self, symbol, quantity, take_profit, stop_price, stop_limit, client_order_id):
        if self.timeout_next:
            self.timeout_next = False
            raise TimeoutError("mock 504 timeout")
        item = {
            "orderListId": str(len(self.open_oco) + 1),
            "symbol": symbol,
            "status": "EXECUTING",
            "clientOrderId": client_order_id,
            "quantity": str(quantity),
            "takeProfit": str(take_profit),
            "stopPrice": str(stop_price),
            "stopLimitPrice": str(stop_limit),
            "orderReports": [],
        }
        self.open_oco[symbol] = item
        return item

    def all_orders(self, symbol, limit=1000):
        return [o for o in self.orders if o["symbol"] == symbol][-limit:]

    def open_orders(self, symbol=None):
        rows = [
            copy.deepcopy(o)
            for o in self.orders
            if o["status"] in {"NEW", "PARTIALLY_FILLED"}
            and (symbol is None or o["symbol"] == symbol)
        ]
        for item in self.open_oco.values():
            if symbol is None or item["symbol"] == symbol:
                rows.append(copy.deepcopy(item))
        return rows

    def agg_trades(self, symbol, limit=100):
        price = self.prices[symbol]
        return [{"p": str(price), "q": "0.01", "m": False, "T": int(time.time() * 1000)} for _ in range(min(limit, 10))]

    def klines(self, symbol, interval="1h", limit=220):
        now = int(time.time())
        step = {"1m": 60, "5m": 300, "15m": 900, "1h": 3600, "4h": 14400}.get(interval, 3600)
        rows = []
        price = self.prices[symbol]
        for i in range(max(2, limit)):
            t = (now - (limit - i) * step) * 1000
            p = price * (1.0 + 0.0002 * ((i % 7) - 3))
            rows.append([t, str(p), str(p*1.002), str(p*0.998), str(p*1.0005), "10", t + step*1000 - 1])
        return rows

    def sync_time(self):
        return None

    def decimal_format(self, value):
        return str(value)
