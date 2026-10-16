"""Exercise the real runtime/client/barrier/SQLite chain against a local exchange model."""
import time

import pytest

from campaign_model import SignalRole, SignalSpec, SignalType
from futures_williams_runtime import FuturesCandidate, FuturesWilliamsRuntime
from binance_client import BinanceAPIError


class DemoExchangeModel:
    def __init__(self):
        self.price = 100.0
        self.amount = 0.0
        self.entry_price = 0.0
        self.algos = {}
        self.orders = {}
        self.calls = []
        self.cancel_fill = False
        self.protection_failure = False

    def request(self, method, path, params=None, signed=False):
        p = params or {}
        self.calls.append((method, path, dict(p)))
        if path.endswith("exchangeInfo"):
            return {"symbols": [{"symbol": "BTCUSDT", "filters": [
                {"filterType": "PRICE_FILTER", "tickSize": "0.1"},
                {"filterType": "LOT_SIZE", "stepSize": "0.001", "minQty": "0.001", "maxQty": "1000"},
                {"filterType": "MARKET_LOT_SIZE", "stepSize": "0.001", "minQty": "0.001", "maxQty": "1000"},
            ]}]}
        if path.endswith("marginType") or path.endswith("leverage"):
            return {"code": 200}
        if path.endswith("ticker/price"):
            return {"symbol": "BTCUSDT", "price": str(self.price)}
        if path.endswith("/account"):
            return {"canTrade": True, "assets": [], "totalWalletBalance": "10000", "totalUnrealizedProfit": "0", "availableBalance": "10000"}
        if path.endswith("positionRisk"):
            return [{"symbol": "BTCUSDT", "positionAmt": str(self.amount), "entryPrice": str(self.entry_price),
                     "liquidationPrice": "50" if self.amount > 0 else "200", "positionSide": "BOTH"}]
        if path.endswith("openOrders") or path.endswith("allOrders"):
            return list(self.orders.values()) if path.endswith("allOrders") else []
        if path.endswith("openAlgoOrders") or path.endswith("allAlgoOrders"):
            return [dict(row) for row in self.algos.values() if path.endswith("allAlgoOrders") or row["algoStatus"] == "NEW"]
        if path.endswith("algoOrder"):
            if method == "POST":
                if p.get("closePosition") and self.protection_failure:
                    raise BinanceAPIError("simulated protection rejection", payload={"code": -2015})
                aid = len(self.algos) + 1
                row = {**p, "algoId": aid, "orderType": p["type"], "algoStatus": "NEW", "actualOrderId": ""}
                self.algos[aid] = row
                return dict(row)
            row = (self.algos[int(p["algoId"])] if "algoId" in p else
                   next(row for row in self.algos.values() if row["clientAlgoId"] == p["clientAlgoId"]))
            if method == "DELETE":
                if self.cancel_fill and not row.get("closePosition"):
                    self.fill_entry()
                else:
                    row["algoStatus"] = "CANCELED"
            return dict(row)
        if path.endswith("/order"):
            if method == "POST":
                oid = len(self.orders) + 100
                quantity = abs(self.amount)
                assert p["reduceOnly"] == "true"
                assert p["side"] == ("SELL" if self.amount > 0 else "BUY")
                assert float(p["quantity"]) == quantity
                self.amount = 0.0
                self.orders[oid] = {**p, "orderId": oid, "clientOrderId": p["newClientOrderId"], "status": "FILLED",
                                    "executedQty": str(quantity), "origQty": str(quantity), "avgPrice": str(self.price)}
                return dict(self.orders[oid])
            row = self.orders[int(p["orderId"])]
            if method == "DELETE" and row["status"] == "PARTIALLY_FILLED":
                row["status"] = "FILLED"
                row["executedQty"] = row["origQty"]
                self.amount = float(row["origQty"]) * (1 if row["side"] == "BUY" else -1)
            return dict(row)
        if path.endswith("userTrades"):
            row = self.orders[int(p["orderId"])]
            return [{"qty": row["executedQty"], "price": row["avgPrice"], "commission": "0", "commissionAsset": "USDT"}]
        raise AssertionError((method, path, p))

    def fill_entry(self, fraction=1.0):
        row = next(row for row in self.algos.values() if not row.get("closePosition"))
        oid = 42
        self.entry_price = float(row["triggerPrice"])
        quantity = float(row["quantity"])
        self.amount = quantity * fraction * (1 if row["side"] == "BUY" else -1)
        row.update(algoStatus="FINISHED", actualOrderId=str(oid))
        self.orders[oid] = {"orderId": oid, "symbol": "BTCUSDT", "side": row["side"],
                            "status": "FILLED" if fraction == 1 else "PARTIALLY_FILLED",
                            "origQty": str(quantity), "executedQty": str(quantity * fraction), "avgPrice": str(self.entry_price)}


@pytest.mark.parametrize("side", ["BUY", "SELL"])
@pytest.mark.parametrize("mode", ["fill", "expire_race", "partial_cancel", "protection_failure"])
def test_pending_entry_fill_protection_trail_and_exact_close(monkeypatch, tmp_path, side, mode):
    monkeypatch.setenv("WILLIAMS_DB_PATH", str(tmp_path / "lifecycle.sqlite3"))
    monkeypatch.setenv("DRY_RUN", "false")
    monkeypatch.setenv("TESTNET", "true")
    monkeypatch.setenv("BINANCE_MARKET", "futures_usdt")
    bot = FuturesWilliamsRuntime("test-key", "test-secret")
    exchange = DemoExchangeModel()
    monkeypatch.setattr(bot.client, "_request", exchange.request)
    now = int(time.time() * 1000)
    signal = SignalSpec.new(symbol="BTCUSDT", side=side, signal_type=SignalType.REVERSAL, role=SignalRole.ENTRY,
                            timeframe="1h", signal_bar_time_ms=now - 1000,
                            trigger_price=101 if side == "BUY" else 99,
                            protective_reference=95 if side == "BUY" else 105,
                            expires_at_ms=now + 3_600_000)
    candidate = FuturesCandidate("BTCUSDT", "LONG" if side == "BUY" else "SHORT", signal, (signal,), 0.01, 0.001, True, 90)
    try:
        armed = bot._arm_entry(candidate)
        assert armed["order_id"].startswith("algo:")
        assert len(exchange.algos) == 1 and exchange.amount == 0
        if mode == "expire_race":
            campaign = bot.engine.load_campaign(armed["campaign_id"])
            campaign.tags["pending_signal"]["signal"]["expires_at_ms"] = now - 1
            bot.db.save_campaign(campaign)
            exchange.cancel_fill = True
        else:
            exchange.fill_entry(fraction=0.5 if mode == "partial_cancel" else 1.0)
        exchange.protection_failure = mode == "protection_failure"
        result = bot._reconcile_pending()
        if mode == "protection_failure":
            assert result[0]["flatten"]["state"] == "CLOSED", result
            assert exchange.amount == 0 and bot.state("BTCUSDT") == "RECONCILE_REQUIRED"
            assert not bot._pending_entries() and not bot._pending_exits()
            return
        assert result[0]["state"] == "OPEN", result
        campaign = bot.engine.load_campaign(armed["campaign_id"])
        assert campaign.position_qty == abs(exchange.amount)
        stop = exchange.algos[2]
        assert stop["closePosition"] == "true" and stop["workingType"] == "MARK_PRICE"
        assert "quantity" not in stop and "reduceOnly" not in stop
        assert bot.recover()["ok"]
        exchange.price = 103 if side == "BUY" else 97
        assert bot._replace_protection(campaign, 96 if side == "BUY" else 104)
        assert exchange.algos[2]["algoStatus"] == "CANCELED"
        assert exchange.algos[3]["algoStatus"] == "NEW"
        result = bot._exit_market(campaign, "TEST_MANUAL_CLOSE")
        assert result["state"] == "CLOSED"
        assert exchange.amount == 0 and not bot._pending_entries() and not bot._pending_exits()
        assert all(row["algoStatus"] != "NEW" for row in exchange.algos.values())
        assert bot.db.campaign_risk_reserved_quote() == 0
    finally:
        bot.client.session.close()
        bot.db.conn.close()
