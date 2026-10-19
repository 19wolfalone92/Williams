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
        self.leverage = 2
        self.bad_leverage_confirmation = False
        self.bad_brackets = False
        self.liquidation_price = "MODEL"
        self.close_fraction = 1.0
        self.close_status = "FILLED"
        self.market_add_timeout_after_fill = False
        self.market_close_timeout_after_fill=False

    def request(self, method, path, params=None, signed=False):
        p = params or {}
        self.calls.append((method, path, dict(p)))
        if path.endswith("exchangeInfo"):
            return {"symbols": [{"symbol": "BTCUSDT", "filters": [
                {"filterType": "PRICE_FILTER", "tickSize": "0.1"},
                {"filterType": "LOT_SIZE", "stepSize": "0.001", "minQty": "0.001", "maxQty": "1000"},
                {"filterType": "MARKET_LOT_SIZE", "stepSize": "0.001", "minQty": "0.001", "maxQty": "1000"},
            ]}]}
        if path.endswith("leverageBracket"):
            return {} if self.bad_brackets else {"symbol": "BTCUSDT", "brackets": [
                {"notionalFloor": 0, "notionalCap": 1000000, "initialLeverage": 125, "maintMarginRatio": 0.005}
            ]}
        if path.endswith("symbolConfig"):
            return [{"symbol": "BTCUSDT", "marginType": "ISOLATED", "leverage": self.leverage,
                     "maxNotionalValue": "1000000"}]
        if path.endswith("leverage"):
            self.leverage = p["leverage"]
            return {"code": 200} if self.bad_leverage_confirmation else {
                "symbol": p["symbol"], "leverage": self.leverage, "maxNotionalValue": "1000000"
            }
        if path.endswith("marginType"):
            return {"code": 200}
        if path.endswith("ticker/price"):
            return {"symbol": "BTCUSDT", "price": str(self.price)}
        if path.endswith("/account"):
            return {"canTrade": True, "assets": [], "totalWalletBalance": "10000", "totalUnrealizedProfit": "0", "availableBalance": "10000"}
        if path.endswith("positionRisk"):
            return [{"symbol": "BTCUSDT", "positionAmt": str(self.amount), "entryPrice": str(self.entry_price),
                     "liquidationPrice": self.liquidation_price if self.liquidation_price != "MODEL" else ("50" if self.amount > 0 else "200"), "positionSide": "BOTH",
                     "leverage": str(self.leverage), "marginType": "isolated"}]
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
                if p.get("reduceOnly")!="true":
                    assert p["type"]=="MARKET"
                    assert self.amount==0 or p["side"]==("BUY" if self.amount>0 else "SELL")
                    quantity=float(p["quantity"]);old=abs(self.amount)
                    self.entry_price=(self.entry_price*old+self.price*quantity)/(old+quantity)
                    self.amount+=(quantity if self.amount>0 else -quantity)
                    self.orders[oid]={**p,"orderId":oid,"clientOrderId":p["newClientOrderId"],"status":"FILLED",
                        "executedQty":str(quantity),"origQty":str(quantity),"avgPrice":str(self.price)}
                    if self.market_add_timeout_after_fill:
                        raise BinanceAPIError("simulated response lost AFTER the MARKET fill")
                    return dict(self.orders[oid])
                quantity = float(p["quantity"])
                assert quantity<=abs(self.amount)
                assert p["reduceOnly"] == "true"
                assert p["side"] == ("SELL" if self.amount > 0 else "BUY")
                self.amount -= quantity*self.close_fraction*(1 if self.amount>0 else -1)
                self.orders[oid] = {**p, "orderId": oid, "clientOrderId": p["newClientOrderId"], "status": "FILLED",
                                    "executedQty": str(quantity * self.close_fraction), "origQty": str(quantity), "avgPrice": str(self.price)}
                self.orders[oid]["status"] = self.close_status
                if self.market_close_timeout_after_fill:raise BinanceAPIError("simulated reply lost AFTER reduction fill")
                return dict(self.orders[oid])
            row = self.orders[int(p["orderId"])] if "orderId" in p else next(
                row for row in self.orders.values() if row.get("clientOrderId") == p["origClientOrderId"])
            if method == "DELETE" and row["status"] == "PARTIALLY_FILLED":
                row["status"] = "FILLED"
                row["executedQty"] = row["origQty"]
                self.amount = float(row["origQty"]) * (1 if row["side"] == "BUY" else -1)
            return dict(row)
        if path.endswith("userTrades"):
            row = self.orders[int(p["orderId"])]
            qty=float(row["executedQty"]);price=float(row["avgPrice"])
            pnl=qty*(price-self.entry_price)*(1 if row["side"]=="SELL" else -1) if row.get("reduceOnly")=="true" else 0
            return [{"id":int(p["orderId"])*100+1,"qty":row["executedQty"],"price":row["avgPrice"],"commission":"0",
                "commissionAsset":"USDT","realizedPnl":str(pnl)}]
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


@pytest.fixture
def armed_runtime(monkeypatch, tmp_path, request):
    option=getattr(request,"param","BUY")
    side=option["side"] if isinstance(option,dict) else option
    profile=option.get("profile","TC2_THREE_WISE_MEN") if isinstance(option,dict) else "TC2_THREE_WISE_MEN"
    monkeypatch.setenv("WILLIAMS_DB_PATH", str(tmp_path / "deep-lifecycle.sqlite3"))
    monkeypatch.setenv("DRY_RUN", "false")
    monkeypatch.setenv("FUTURES_LEVERAGE", "2")
    monkeypatch.setenv("FUTURES_SYMBOL_LEVERAGES", "")
    monkeypatch.setenv("WILLIAMS_SIGNAL_PROFILE",profile)
    bot = FuturesWilliamsRuntime("test-key", "test-secret", testnet=True)
    exchange = DemoExchangeModel()
    monkeypatch.setattr(bot.client, "_request", exchange.request)
    now = int(time.time() * 1000)
    signal = SignalSpec.new(symbol="BTCUSDT", side=side, signal_type=SignalType.FRACTAL if profile=="NTD_FIVE_DIMENSIONS" else SignalType.REVERSAL, role=SignalRole.ENTRY,
        timeframe="1h", signal_bar_time_ms=now - (7_200_000 if profile=="NTD_FIVE_DIMENSIONS" else 1000), trigger_price=101 if side == "BUY" else 99,
        protective_reference=95 if side == "BUY" else 105, expires_at_ms=now + 3600000,strategy_profile=profile,
        setup_context={"ntd_initial":{"eligible":True,"sleep_at_ms":now-10*3600000,"reason":"FIXTURE_OBSERVED_SLEEP"}} if profile=="NTD_FIVE_DIMENSIONS" else {})
    candidate = FuturesCandidate("BTCUSDT", "LONG" if side == "BUY" else "SHORT", signal, (signal,), 0.01, 0.001, True, 90)
    armed = bot._arm_entry(candidate)
    trace=bot.db.conn.execute("SELECT raw_json FROM events WHERE event='decision_trace_execution' ORDER BY id DESC LIMIT 1").fetchone()
    assert trace and '"stage": "_arm_entry"' in trace[0]
    assert "test-secret" not in trace[0]
    bot.preflight_report = {"ready": True}
    bot.recovered = True
    try:
        yield bot, exchange, armed
    finally:
        bot.client.session.close()
        bot.db.conn.close()


@pytest.mark.parametrize("armed_runtime", ["BUY", "SELL"], indirect=True)
@pytest.mark.parametrize("price", ["0", "NaN", None, "malformed"])
def test_unknown_liquidation_protects_the_fill_without_automatic_flatten(armed_runtime, price):
    bot, exchange, armed = armed_runtime
    exchange.fill_entry()
    exchange.liquidation_price = price
    bot._reconcile_pending()
    assert not any(method == "POST" and path.endswith("/order") for method, path, _ in exchange.calls)
    assert any(row.get("closePosition") == "true" and row["algoStatus"] == "NEW" for row in exchange.algos.values())
    assert not bot._pending_entries()
    assert bot.state("BTCUSDT") == "RECONCILE_REQUIRED"
    exchange.liquidation_price = "MODEL"
    assert bot.recover()["ok"]
    assert bot.state("BTCUSDT") == "OPEN"


@pytest.mark.parametrize("armed_runtime", ["BUY", "SELL"], indirect=True)
def test_unknown_liquidation_during_recovery_retains_existing_protection(armed_runtime):
    bot, exchange, armed = armed_runtime
    exchange.fill_entry()
    bot._reconcile_pending()
    stop = exchange.algos[2]
    exchange.liquidation_price = "0"
    assert not bot.recover()["ok"]
    assert exchange.amount != 0 and stop["algoStatus"] == "NEW"
    assert not any(method == "POST" and path.endswith("/order") for method, path, _ in exchange.calls)


def test_scanner_failure_does_not_skip_position_ownership_reconciliation(armed_runtime):
    bot, exchange, armed = armed_runtime
    exchange.fill_entry()
    bot._reconcile_pending()
    bot.preflight_report = {"ready": True}
    bot.recovered = True
    exchange.amount *= 2
    bot.scanner.scan = lambda: []
    bot.scanner.last_scan_status = {"state": "FAILED", "reason": "model market outage"}
    assert bot.process() == []
    assert bot.state("BTCUSDT") == "RECONCILE_REQUIRED"


@pytest.mark.parametrize("racing_fill",[False,True])
def test_project_h1_expiry_cancels_a_durably_owned_old_pending_signal_and_protects_a_racing_fill(armed_runtime,monkeypatch,racing_fill):
    bot,exchange,_=armed_runtime
    campaign=bot._active_campaign("BTCUSDT")
    observed=time.time()
    campaign.tags["pending_signal"]["signal"]["expires_at_ms"]=int((observed+80*3600)*1000)
    bot.db.save_campaign(campaign)
    exchange.cancel_fill=racing_fill
    monkeypatch.setattr(time,"time",lambda:observed+6*3600)
    bot._reconcile_pending()
    assert not bot._pending_entries()
    assert any(method=="DELETE" and path.endswith("algoOrder") for method,path,_ in exchange.calls)
    if racing_fill:
        assert exchange.amount>0
        assert any(o.get("closePosition")=="true" and o["algoStatus"]=="NEW" for o in exchange.algos.values())
    else:
        assert exchange.amount==0
        assert bot.db.campaign_risk_reserved_quote()==0


def test_five_bar_protection_still_moves_when_scanner_is_unavailable(armed_runtime,monkeypatch):
    import pandas as pd
    bot,exchange,_=armed_runtime
    exchange.fill_entry()
    bot._reconcile_pending()
    observed=time.time()+2*3600
    monkeypatch.setattr(time,"time",lambda:observed)
    end=pd.Timestamp(observed,unit="s",tz="UTC").floor("h")-pd.Timedelta(hours=1)
    prices=[97+i*.1 for i in range(80)]
    frame=pd.DataFrame({"open":prices,"high":[p+1 for p in prices],"low":[p-1 for p in prices],
                        "close":prices,"volume":[1000]*80},index=pd.date_range(end=end,periods=80,freq="h"))
    monkeypatch.setattr("futures_williams_runtime.fetch_klines",lambda *a,**k:frame)
    exchange.price=110
    bot.scanner.scan=lambda:[]
    bot.scanner.last_scan_status={"state":"FAILED","reason":"model outage"}
    assert bot.process()==[]
    assert bot._active_campaign("BTCUSDT").current_stop_price>95
    assert exchange.algos[2]["algoStatus"]=="CANCELED"
    assert any(o.get("closePosition")=="true" and o["algoStatus"]=="NEW" for o in exchange.algos.values())
    assert not any(method=="POST" and path.endswith("/order") for method,path,_ in exchange.calls)


def test_ntd_close_event_cannot_accidentally_be_sent_as_a_tc2_stop_order(armed_runtime):
    bot,exchange,_=armed_runtime
    campaign=bot._active_campaign("BTCUSDT")
    signal=SignalSpec.new(symbol="BTCUSDT",side="BUY",signal_type=SignalType.ZONE_ADD_ON,
        role=SignalRole.ADD_ON,timeframe="1h",signal_bar_time_ms=int(time.time()*1000),
        trigger_price=100,protective_reference=95,execution_semantics="MARKET_ON_CLOSE")
    before=len(exchange.calls)
    with pytest.raises(RuntimeError,match="execution semantics"):
        bot._arm_add_on(campaign,signal)
    assert len(exchange.calls)==before


def test_expired_in_match_entry_retires_pending_reservation(armed_runtime):
    bot, exchange, armed = armed_runtime
    exchange.algos[1]["algoStatus"] = "EXPIRED_IN_MATCH"
    bot._reconcile_pending()
    assert not bot._pending_entries()
    assert bot.state("BTCUSDT") == "FLAT"
    assert bot.db.campaign_risk_reserved_quote() == 0


def test_emergency_cancel_resolves_an_entry_fill_racing_cancellation(armed_runtime):
    bot, exchange, armed = armed_runtime
    bot.db.state_set("kill_switch_latched", "true")
    exchange.cancel_fill = True
    result = bot.cancel_pending_entries()
    assert result["ok"], result
    assert not bot._pending_entries()
    assert exchange.amount != 0
    assert bot.manual_sell("BTCUSDT")["sold"]
    assert exchange.amount == 0


def test_a_terminal_partial_exit_accounts_for_the_fill_and_preserves_the_owned_remainder(armed_runtime):
    bot, exchange, armed = armed_runtime
    exchange.fill_entry()
    bot._reconcile_pending()
    campaign = bot.engine.load_campaign(armed["campaign_id"])
    original = campaign.position_qty
    exchange.close_fraction = 0.4
    exchange.close_status = "EXPIRED_IN_MATCH"
    with pytest.raises(RuntimeError, match="residual|terminal failure"):
        bot._exit_market(campaign, "MODEL_PARTIAL_EXIT")
    recovered = bot._reconcile_pending_exits()
    assert not any("error" in result for result in recovered), recovered
    remaining = bot.engine.load_campaign(campaign.campaign_id)
    assert remaining.position_qty == pytest.approx(original * 0.6)
    assert not bot._pending_exits()
    assert bot.recover()["ok"]
    partial = [dict(r) for r in bot.db.conn.execute("SELECT * FROM trades WHERE exit_time IS NOT NULL")]
    assert len(partial) == 1 and partial[0]["quantity"] == pytest.approx(original * 0.4)
    assert bot.db.open_trade("BTCUSDT")["quantity"] == pytest.approx(original * 0.6)


def test_a_campaign_exit_cancels_a_still_armed_add_on_before_closing(armed_runtime):
    bot, exchange, armed = armed_runtime
    exchange.fill_entry()
    bot._reconcile_pending()
    campaign = bot.engine.load_campaign(armed["campaign_id"])
    now = int(time.time() * 1000)
    signal = SignalSpec.new(symbol="BTCUSDT", side="BUY", signal_type=SignalType.SUPER_AO,
        role=SignalRole.ADD_ON, timeframe="1h", signal_bar_time_ms=now, trigger_price=103,
        protective_reference=95, expires_at_ms=now + 3600000)
    bot._arm_add_on(campaign, signal)
    campaign = bot.engine.load_campaign(campaign.campaign_id)
    assert bot._pending_entries()
    assert bot._exit_market(campaign, "MANUAL_EXIT")["state"] == "CLOSED"
    assert not bot._pending_entries()
    assert all(row["algoStatus"] != "NEW" for row in exchange.algos.values())



@pytest.mark.parametrize("side", ["BUY", "SELL"])
@pytest.mark.parametrize("leverage", [1, 2, 5, 10])
@pytest.mark.parametrize("mode", ["fill", "expire_race", "partial_cancel", "protection_failure"])
def test_pending_entry_fill_protection_trail_and_exact_close(monkeypatch, tmp_path, side, leverage, mode):
    monkeypatch.setenv("WILLIAMS_DB_PATH", str(tmp_path / "lifecycle.sqlite3"))
    monkeypatch.setenv("DRY_RUN", "false")
    monkeypatch.setenv("TESTNET", "true")
    monkeypatch.setenv("BINANCE_MARKET", "futures_usdt")
    monkeypatch.setenv("FUTURES_LEVERAGE", str(leverage))
    monkeypatch.setenv("FUTURES_SYMBOL_LEVERAGES", "")
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
        assert exchange.leverage == leverage
        campaign = bot.engine.load_campaign(armed["campaign_id"])
        assert campaign.tags["futures_leverage"] == leverage
        assert campaign.capital_reserved_quote == pytest.approx(armed["quantity"] * armed["trigger_price"] / leverage)
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


@pytest.mark.parametrize("failure", ["confirmation", "brackets", "excessive_leverage"])
@pytest.mark.parametrize("side", ["BUY", "SELL"])
def test_leverage_failure_never_sends_an_entry(monkeypatch, tmp_path, failure, side):
    monkeypatch.setenv("WILLIAMS_DB_PATH", str(tmp_path / "reject.sqlite3"))
    monkeypatch.setenv("DRY_RUN", "false")
    monkeypatch.setenv("FUTURES_LEVERAGE", "125" if failure == "excessive_leverage" else "5")
    monkeypatch.setenv("FUTURES_SYMBOL_LEVERAGES", "")
    bot = FuturesWilliamsRuntime("test-key", "test-secret", testnet=True)
    exchange = DemoExchangeModel()
    exchange.bad_leverage_confirmation = failure == "confirmation"
    exchange.bad_brackets = failure == "brackets"
    monkeypatch.setattr(bot.client, "_request", exchange.request)
    now = int(time.time() * 1000)
    signal = SignalSpec.new(symbol="BTCUSDT", side=side, signal_type=SignalType.REVERSAL, role=SignalRole.ENTRY,
        timeframe="1h", signal_bar_time_ms=now - 1000, trigger_price=101 if side == "BUY" else 99,
        protective_reference=95 if side == "BUY" else 105, expires_at_ms=now + 3600000)
    candidate = FuturesCandidate("BTCUSDT", "LONG" if side == "BUY" else "SHORT", signal, (signal,), 0.01, 0.001, True, 90)
    try:
        with pytest.raises((ValueError, RuntimeError)):
            bot._arm_entry(candidate)
        assert not exchange.algos and not exchange.orders and exchange.amount == 0
        assert not bot._pending_entries()
        assert bot.db.campaign_risk_reserved_quote() == 0
    finally:
        bot.client.session.close()
        bot.db.conn.close()


@pytest.mark.parametrize("armed_runtime",[{"side":"BUY","profile":"NTD_FIVE_DIMENSIONS"},{"side":"SELL","profile":"NTD_FIVE_DIMENSIONS"}],indirect=True)
@pytest.mark.parametrize("lost_response",[False,True])
def test_ntd_close_event_uses_one_market_order_and_recovers_its_aggregate_fill(armed_runtime,monkeypatch,lost_response):
    bot,exchange,_=armed_runtime
    exchange.fill_entry();bot._reconcile_pending()
    campaign=bot.engine.load_campaign(bot.db.open_campaigns()[0]["campaign_id"])
    before=abs(exchange.amount)
    # Advance the exchange/model clock to the next real hourly boundary after
    # the original position exists; this is not a signal predating its entry.
    closed_at=(int(time.time()*1000)//3_600_000+1)*3_600_000
    monkeypatch.setattr(time,"time",lambda:(closed_at+1000)/1000)
    monkeypatch.setattr(bot,"_daily_entry_guard",lambda:(True,"MODEL_TRADING_SESSION"))
    exchange.price=101.1 if campaign.side=="BUY" else 98.9
    exchange.market_add_timeout_after_fill=lost_response
    import pandas as pd
    from williams_profiles import extract_profile_signals
    sign=1 if campaign.side=="BUY" else -1
    prices=[exchange.price-sign*.2]*43+[exchange.price-sign*.1,exchange.price]
    ind=pd.DataFrame({"open_time_ms":[closed_at-(45-i)*3_600_000 for i in range(45)],
        "open":prices,"high":[p+.5 for p in prices],"low":[p-.5 for p in prices],"close":prices,"volume":[1000]*45,
        "ao":[0]*43+[sign,2*sign],"ac":[0]*43+[sign,2*sign],
        "jaw_current":[90 if sign==1 else 110]*45,"teeth_current":[91 if sign==1 else 109]*45,
        "lips_current":[92 if sign==1 else 108]*45,
        "long_fractal_outside":[sign==1]*45,"short_fractal_outside":[sign==-1]*45})
    signal=next(s for s in extract_profile_signals("BTCUSDT",ind,profile="NTD_FIVE_DIMENSIONS",
        side="LONG" if sign==1 else "SHORT",timeframe="1h",tick_size=.1) if s.signal_type==SignalType.ZONE_ADD_ON)
    assert signal.expires_at_ms==closed_at+90_000
    try:bot._arm_add_on(campaign,signal)
    except (RuntimeError,BinanceAPIError):
        assert lost_response
    bot._reconcile_pending()
    updated=bot.engine.load_campaign(campaign.campaign_id)
    assert abs(exchange.amount)>before
    assert updated.position_qty==pytest.approx(abs(exchange.amount)) and updated.additions==1
    assert not bot._pending_entries()
    market=[p for method,path,p in exchange.calls if method=="POST" and path.endswith("/order") and p.get("reduceOnly")!="true"]
    assert len(market)==1 and market[0]["type"]=="MARKET" and "stopPrice" not in market[0]
    assert any(a.get("closePosition")=="true" and a["algoStatus"]=="NEW" for a in exchange.algos.values())
    with pytest.raises(RuntimeError,match="already admitted"):
        bot._arm_add_on(updated,signal)
    assert len([p for method,path,p in exchange.calls if method=="POST" and path.endswith("/order") and p.get("reduceOnly")!="true"])==1


@pytest.mark.parametrize("armed_runtime",["BUY","SELL"],indirect=True)
@pytest.mark.parametrize("lost",[False,True])
def test_course_intentional_trim_is_attributed_once_and_keeps_its_exchange_stop(armed_runtime,lost):
    bot,exchange,armed=armed_runtime
    exchange.fill_entry();bot._reconcile_pending()
    campaign=bot.engine.load_campaign(armed["campaign_id"])
    # Arrange an already-owned Course position; this test concerns reduction
    # ownership/recovery, whose entry formation has independent source tests.
    campaign.tags["strategy_profile"]="COURSE_PROFITUNITY"
    campaign.tags["pending_course_stage"]="W4"
    bot.db.save_campaign(campaign)
    original=campaign.position_qty
    quantity=bot._normalize_qty(campaign.symbol,original*.5)
    stop=next(row for row in exchange.algos.values() if row.get("closePosition")=="true")
    stopid=stop["clientAlgoId"]
    exchange.market_close_timeout_after_fill=lost
    if lost:
        with pytest.raises(RuntimeError):bot._exit_market(campaign,"COURSE_STAGE_TRIM",partial_quantity=quantity)
        bot._reconcile_pending_exits()
    else:
        result=bot._exit_market(campaign,"COURSE_STAGE_TRIM",partial_quantity=quantity)
        assert result["partial_reduction"]
    assert bot.recover()["ok"]
    updated=bot.engine.load_campaign(campaign.campaign_id)
    assert updated.position_qty==pytest.approx(original-quantity)
    assert updated.tags["course_plan"]["stage"]=="W4"
    assert bot.db.open_trade(campaign.symbol)["quantity"]==pytest.approx(original-quantity)
    assert not bot._pending_exits()
    assert next(row for row in exchange.algos.values() if row["algoStatus"]=="NEW")["clientAlgoId"]==stopid
    assert len([p for method,path,p in exchange.calls if method=="POST" and path.endswith("/order")])==1


@pytest.mark.parametrize("armed_runtime",["BUY","SELL"],indirect=True)
def test_course_management_executes_an_observed_w4_trim_without_waiting_for_entry_session(armed_runtime,monkeypatch):
    import pandas as pd
    from tests.test_williams_wave_hierarchy import correction_report
    bot,exchange,armed=armed_runtime
    exchange.fill_entry();bot._reconcile_pending()
    campaign=bot.engine.load_campaign(armed["campaign_id"])
    original=campaign.position_qty
    campaign.tags["strategy_profile"]="COURSE_PROFITUNITY"
    campaign.tags["course_plan"]={"stage":"W3_EXTENSION","origin_at":10,"unit_quantity":original/10,"unit_is_actual":True}
    bot.db.save_campaign(campaign)
    now=int(time.time()*1000)
    prices=[100+i*.1 for i in range(80)]
    frame=pd.DataFrame({"open_time_ms":[now-(81-i)*3_600_000 for i in range(80)],"open":prices,
        "high":[p+1 for p in prices],"low":[p-1 for p in prices],"close":prices,"volume":[1000]*80})
    monkeypatch.setattr("futures_williams_runtime.fetch_klines",lambda *a,**k:frame)
    monkeypatch.setattr("futures_williams_runtime.course_active_context",lambda *a,**k:{"phase":"W4"})
    bot.scanner.wave_hierarchy=lambda *a,**k:correction_report()
    bot.db.state_set("entry_admission_paused","true")
    result=bot._trail(campaign)
    assert result["partial_reduction"]
    updated=bot.engine.load_campaign(campaign.campaign_id)
    assert updated.tags["course_plan"]["stage"]=="W4"
    assert 0<updated.position_qty<original
    assert bot.recover()["ok"]


@pytest.mark.parametrize("armed_runtime",[{"side":"BUY","profile":"NTD_FIVE_DIMENSIONS"},{"side":"SELL","profile":"NTD_FIVE_DIMENSIONS"}],indirect=True)
@pytest.mark.parametrize("market",[False,True])
def test_ntd_profitable_authenticated_cycle_allows_one_new_dimension_entry(armed_runtime,monkeypatch,market):
    bot,exchange,armed=armed_runtime
    exchange.fill_entry();bot._reconcile_pending()
    previous=bot.engine.load_campaign(armed["campaign_id"])
    exchange.price=104 if previous.side=="BUY" else 96
    assert bot._exit_market(previous,"PROFIT_CYCLE")["state"]=="CLOSED"
    previous=bot.engine.load_campaign(previous.campaign_id)
    assert previous.tags.get("verified_trade_result",{}).get("net_pnl",0)>0
    closed_at=(int(time.time()*1000)//3_600_000+3)*3_600_000
    monkeypatch.setattr(time,"time",lambda:(closed_at+1000)/1000)
    monkeypatch.setattr(bot,"_daily_entry_guard",lambda:(True,"MODEL_TRADING_SESSION"))
    signal=SignalSpec.new(symbol="BTCUSDT",side=previous.side,signal_type=SignalType.ZONE_ADD_ON if market else SignalType.AO_SAUCER,
        role=SignalRole.ADD_ON,timeframe="1h",signal_bar_time_ms=closed_at-3_600_000,
        trigger_price=exchange.price if market else exchange.price+(1 if previous.side=="BUY" else -1),
        protective_reference=95 if previous.side=="BUY" else 105,execution_semantics="MARKET_ON_CLOSE" if market else "STOP_ENTRY",
        strategy_profile="NTD_FIVE_DIMENSIONS",expires_at_ms=closed_at+3_600_000)
    assert bot._ntd_reentry_source(signal).campaign_id==previous.campaign_id
    candidate=FuturesCandidate("BTCUSDT","LONG" if previous.side=="BUY" else "SHORT",signal,(signal,),.01,.001,True,90)
    admitted=bot._arm_entry(candidate)
    assert bot.db.state_get("ntd_reentry_consumed:"+previous.campaign_id)==admitted["campaign_id"]
    assert bot._ntd_reentry_source(signal) is None
    if market:
        bot._reconcile_pending()
        current=bot.engine.load_campaign(admitted["campaign_id"])
        assert current.position_qty>0 and current.tags["strategy_profile"]=="NTD_FIVE_DIMENSIONS"


@pytest.mark.parametrize("armed_runtime",["BUY","SELL"],indirect=True)
def test_actual_gap_fill_cannot_reuse_the_planned_risk_reservation(armed_runtime):
    bot,exchange,armed=armed_runtime
    exchange.fill_entry()
    actual=106 if exchange.amount>0 else 94
    exchange.entry_price=actual
    exchange.orders[42]["avgPrice"]=str(actual)
    result=bot._reconcile_pending()
    assert result
    campaign=bot.engine.load_campaign(armed["campaign_id"])
    assert campaign.tags["actual_risk_audit"]["status"]=="BLOCKED"
    assert campaign.tags["actual_risk_audit"]["reason"]=="ACTUAL_ENTRY_RISK_EXCEEDS_0_25_PERCENT"
    assert bot.state(campaign.symbol)=="RECONCILE_REQUIRED"
    assert any(row.get("closePosition")=="true" and row["algoStatus"]=="NEW" for row in exchange.algos.values())
    assert not bot.recover()["ok"]
    assert bot.manual_sell(campaign.symbol)["sold"]
