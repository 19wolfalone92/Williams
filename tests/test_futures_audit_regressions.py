"""Deterministic regressions for exchange-boundary and campaign recovery defects."""
import json
from types import SimpleNamespace

import pytest
import requests

from binance_client import BinanceAPIError
from binance_futures_client import BinanceFuturesClient
from campaign_model import CampaignState, PendingOrderRecord, TradingCampaign
from db import Database
from execution_barrier import ExecutionBarrier, OrderIntent
from futures_williams_runtime import FuturesWilliamsRuntime
from market_context import ContextCache


@pytest.fixture
def client():
    return BinanceFuturesClient("test-key", "test-secret", testnet=True)


@pytest.fixture
def runtime(tmp_path):
    bot = object.__new__(FuturesWilliamsRuntime)
    bot.db = Database(str(tmp_path / "audit.sqlite3"))
    bot.client = SimpleNamespace(testnet=True)
    bot.config = SimpleNamespace(allow_live=False)
    bot.dry_run = False
    bot.execution_barrier = ExecutionBarrier(ContextCache(), bot.db)
    yield bot
    bot.db.conn.close()


def response(status, body):
    result = requests.Response()
    result.status_code = status
    result._content = (body if isinstance(body, str) else json.dumps(body)).encode()
    return result


def test_exchange_info_filters_locally_instead_of_taking_another_contract(client, monkeypatch):
    calls = []
    rows = [{"symbol": "BTCUSDT", "filters": [{"filterType": "PRICE_FILTER", "tickSize": "0.1"}]},
            {"symbol": "XRPUSDT", "filters": [{"filterType": "PRICE_FILTER", "tickSize": "0.0001"}]}]
    def request(*args, **kwargs):
        calls.append((args, kwargs))
        return {"symbols": rows}
    monkeypatch.setattr(client, "_request", request)
    assert client.exchange_info("XRPUSDT")["symbols"] == [rows[1]]
    assert calls == [(("GET", "/fapi/v1/exchangeInfo"), {})]
    with pytest.raises(BinanceAPIError):
        client.exchange_info("UNKNOWNUSDT")


def test_runtime_filters_reject_first_row_for_wrong_symbol(runtime):
    runtime.client.exchange_info = lambda symbol: {"symbols": [{"symbol": "BTCUSDT", "filters": []}]}
    with pytest.raises(RuntimeError, match="not found"):
        runtime._filters("XRPUSDT")


@pytest.mark.parametrize("side", ["BUY", "SELL"])
def test_conditional_entries_use_algo_api_and_disjoint_id_namespace(client, monkeypatch, side):
    calls = []
    def request(method, path, params=None, signed=False):
        calls.append((method, path, params, signed))
        return {"algoId": 7, "clientAlgoId": "entry", "algoStatus": "NEW", "side": side,
                "symbol": "BTCUSDT", "orderType": "STOP_MARKET", "quantity": "0.01", "triggerPrice": "101"}
    monkeypatch.setattr(client, "_request", request)
    result = client.order_safe("BTCUSDT", side, "STOP_MARKET", quantity="0.01", stop_price="101", new_client_order_id="entry")
    assert result["orderId"] == "algo:7"
    assert result["status"] == "NEW"
    method, path, params, signed = calls[0]
    assert (method, path, signed) == ("POST", "/fapi/v1/algoOrder", True)
    assert params["clientAlgoId"] == "entry" and params["triggerPrice"] == "101"
    assert "newClientOrderId" not in params and "stopPrice" not in params


def test_triggered_algo_requires_actual_order_fill_evidence(client, monkeypatch):
    calls = []
    def request(method, path, params=None, signed=False):
        calls.append((path, params))
        if path.endswith("algoOrder"):
            return {"algoId": 7, "clientAlgoId": "entry", "algoStatus": "FINISHED", "actualOrderId": "7",
                    "symbol": "BTCUSDT", "side": "BUY", "orderType": "STOP_MARKET"}
        return {"orderId": 7, "status": "PARTIALLY_FILLED", "symbol": "BTCUSDT", "side": "BUY",
                "executedQty": "0.003", "avgPrice": "101"}
    monkeypatch.setattr(client, "_request", request)
    result = client.get_order("BTCUSDT", order_id="algo:7")
    assert result["orderId"] == "algo:7" and result["actualOrderId"] == "7"
    assert result["status"] == "PARTIALLY_FILLED" and result["executedQty"] == "0.003"
    assert calls == [("/fapi/v1/algoOrder", {"algoId": 7}), ("/fapi/v1/order", {"symbol": "BTCUSDT", "orderId": 7})]


def test_algo_finished_without_actual_order_is_never_reported_filled(client, monkeypatch):
    monkeypatch.setattr(client, "_request", lambda *a, **kw: {
        "algoId": 1, "symbol": "BTCUSDT", "algoStatus": "FINISHED", "actualOrderId": "", "actualQty": "1"})
    result = client.get_order("BTCUSDT", order_id="algo:1")
    assert result["status"] == "TRIGGERED" and result["executedQty"] == "0"


def test_algo_cannot_adopt_child_order_of_other_symbol(client, monkeypatch):
    def request(method, path, params=None, signed=False):
        if path.endswith("algoOrder"):
            return {"algoId": 1, "actualOrderId": "2", "symbol": "BTCUSDT", "side": "BUY"}
        return {"orderId": 2, "symbol": "ETHUSDT", "side": "BUY", "status": "FILLED"}
    monkeypatch.setattr(client, "_request", request)
    with pytest.raises(BinanceAPIError, match="identity mismatch"):
        client.get_order("BTCUSDT", order_id="algo:1")


def test_lost_conditional_post_is_reconciled_without_second_post(client, monkeypatch):
    calls = []
    def request(method, path, params=None, signed=False):
        calls.append((method, path))
        if method == "POST":
            raise BinanceAPIError("lost reply", unknown_execution=True)
        assert params == {"clientAlgoId": "entry"}
        return {"algoId": 7, "symbol": "BTCUSDT", "clientAlgoId": "entry", "algoStatus": "NEW"}
    monkeypatch.setattr(client, "_request", request)
    result = client.order_safe("BTCUSDT", "BUY", "STOP_MARKET", quantity="0.01", stop_price="101", new_client_order_id="entry")
    assert result["orderId"] == "algo:7"
    assert calls == [("POST", "/fapi/v1/algoOrder"), ("GET", "/fapi/v1/algoOrder")]


@pytest.mark.parametrize("payload", [{}, {"positions": []}, [None], [{"symbol": "BTCUSDT", "positionAmt": "NaN"}], [{"symbol": "BTCUSDT"}]])
def test_malformed_positions_never_mean_flat(client, monkeypatch, payload):
    monkeypatch.setattr(client, "_request", lambda *a, **kw: payload)
    with pytest.raises(BinanceAPIError):
        client.position("BTCUSDT")


def test_hedge_position_is_not_adopted_as_one_way(client, monkeypatch):
    monkeypatch.setattr(client, "_request", lambda *a, **kw: [{"symbol": "BTCUSDT", "positionAmt": "1", "positionSide": "LONG"}])
    with pytest.raises(BinanceAPIError, match="One-way"):
        client.position("BTCUSDT")


def test_html_success_for_order_is_ambiguous_and_redirects_are_disabled(client):
    calls = []
    def send(*a, **kw):
        calls.append(kw)
        return response(200, "<html>gateway error</html>")
    client.session = SimpleNamespace(request=send)
    with pytest.raises(BinanceAPIError) as caught:
        client._request("POST", "/fapi/v1/order", {}, signed=True)
    assert caught.value.unknown_execution
    assert len(calls) == 1 and calls[0]["allow_redirects"] is False


def test_runtime_unwraps_execution_result_to_exchange_payload(runtime):
    intent = OrderIntent.new("BTCUSDT", "BUY", "STOP_MARKET", {}, purpose="CAMPAIGN_ENTRY")
    assert runtime._submit(intent, lambda: {"orderId": "algo:7"}) == {"orderId": "algo:7"}


def test_blocked_intent_is_durably_blocked_and_runtime_never_sends(runtime):
    intent = OrderIntent.new("BTCUSDT", "BUY", "MARKET", {"1h": 99}, purpose="ENTRY")
    with pytest.raises(RuntimeError, match="execution blocked"):
        runtime._submit(intent, lambda: pytest.fail("must not send"))
    row = runtime.db.conn.execute("SELECT status FROM execution_intents WHERE intent_id=?", (intent.intent_id,)).fetchone()
    assert row["status"] == "BLOCKED"


def test_submitted_but_unpersisted_result_latches_reconciliation(runtime, monkeypatch):
    real_save = runtime.db.save_execution_intent
    def save(intent, status, reason=""):
        return False if status == "SUBMITTED" else real_save(intent, status, reason)
    monkeypatch.setattr(runtime.db, "save_execution_intent", save)
    states = []
    runtime._set_state = lambda *args: states.append(args)
    intent = OrderIntent.new("BTCUSDT", "BUY", "STOP_MARKET", {}, purpose="CAMPAIGN_ENTRY")
    sent = []
    with pytest.raises(RuntimeError, match="persistence_failed"):
        runtime._submit(intent, lambda: sent.append(1) or {"orderId": 1})
    assert sent == [1] and states == [("BTCUSDT", "RECONCILE_REQUIRED")]


@pytest.mark.parametrize("qty", [0.0015, 0.101])
def test_full_close_never_rounds_or_caps_exchange_position(runtime, qty):
    runtime._filters = lambda symbol: {
        "LOT_SIZE": {"stepSize": "0.001", "minQty": "0.001", "maxQty": "100"},
        "MARKET_LOT_SIZE": {"stepSize": "0.001", "minQty": "0.001", "maxQty": "0.1"},
    }
    with pytest.raises(RuntimeError, match="partial close"):
        runtime._full_market_close_qty("BTCUSDT", qty)
    assert runtime._full_market_close_qty("BTCUSDT", 0.01) == 0.01


@pytest.fixture
def campaign(runtime):
    item = TradingCampaign("campaign", "BTCUSDT", "BUY", "1h", state=CampaignState.TREND_ACTIVE,
                           position_qty=1, current_stop_price=95, average_entry_price=100,
                           tags={"protective_order_client_id": "WILLF_STOP_owned"})
    runtime.db.save_campaign(item)
    runtime.db.save_campaign_order(PendingOrderRecord(client_order_id="WILLF_STOP_owned", campaign_id=item.campaign_id,
                                                     purpose="PROTECTION", order_id="algo:7"))
    return item


def protective_order():
    return {"symbol": "BTCUSDT", "side": "SELL", "type": "STOP_MARKET", "status": "NEW", "stopPrice": "95",
            "clientOrderId": "WILLF_STOP_owned", "closePosition": True, "positionSide": "BOTH"}


@pytest.mark.parametrize("change", [{"side": "BUY"}, {"symbol": "ETHUSDT"}, {"status": "CANCELED"},
                                     {"status": ""}, {"stopPrice": "94"}, {"clientOrderId": "WILLF_STOP_foreign"},
                                     {"closePosition": False, "reduceOnly": True, "origQty": "0.99"},
                                     {"positionSide": "LONG"}, {"priceProtect": True}])
def test_recovery_rejects_wrong_or_insufficient_protection(runtime, campaign, change):
    assert runtime._protection_matches(campaign, protective_order())
    assert not runtime._protection_matches(campaign, {**protective_order(), **change})


def test_position_ownership_does_not_allow_one_percent_foreign_quantity(runtime, campaign):
    assert runtime._position_matches(campaign, {"positionAmt": "1"})
    assert not runtime._position_matches(campaign, {"positionAmt": "1.005"})
    assert not runtime._position_matches(campaign, {"positionAmt": "-1"})


@pytest.mark.parametrize("value", ["0", "NaN", "Infinity", ""])
def test_liquidation_guard_fails_closed_on_unknown_liquidation(runtime, campaign, value):
    assert not runtime._liquidation_guard(campaign, {"liquidationPrice": value, "entryPrice": "100"})


def test_unresolved_campaign_retains_risk_and_capital_until_flat(runtime, campaign):
    campaign.state = CampaignState.RECONCILE_REQUIRED
    campaign.open_risk_quote = 10
    campaign.pending_risk_quote = 5
    campaign.capital_reserved_quote = 100
    runtime.db.save_campaign(campaign)
    assert runtime.db.campaign_risk_reserved_quote() == 15
    assert runtime.db.campaign_capital_reserved_quote() == 100
    campaign.state = CampaignState.CLOSED
    runtime.db.save_campaign(campaign)
    assert runtime.db.campaign_risk_reserved_quote() == 0
    assert runtime.db.campaign_capital_reserved_quote() == 0


def test_backend_startup_and_shutdown_have_live_stop_events(monkeypatch):
    import server
    monkeypatch.setattr(server, "state", SimpleNamespace(api_key="", api_secret="", stop=lambda: None))
    monkeypatch.setattr(server.mtf_service, "stop", lambda: None)
    monkeypatch.setattr(server.hub, "stop", lambda: None)
    monkeypatch.setenv("AUTO_START", "false")
    try:
        server.startup()
        threads = [server.scanner_watchdog_thread, server.supervisor_thread, server.heartbeat_thread]
        assert all(thread.is_alive() for thread in threads)
    finally:
        server.shutdown()
    for thread in threads:
        thread.join(timeout=2)
        assert not thread.is_alive()


@pytest.mark.parametrize("path", ["/fapi/v1/../api/v3/order", "/fapi/v1/%2e%2e/order", "/fapi/v1/order\\x", "/fapi/v1//order"])
def test_path_normalization_cannot_escape_futures_boundary(client, path):
    client.session = SimpleNamespace(request=lambda *a, **kw: pytest.fail("no network"))
    with pytest.raises(ValueError, match="canonical"):
        client._request("POST", path, signed=True)


def test_retry_after_longer_than_thirty_seconds_is_honored_in_full(client, monkeypatch):
    clock = [100.0]
    sleeps = []
    def sleep(seconds):
        sleeps.append(seconds)
        clock[0] += seconds
    monkeypatch.setattr("binance_futures_client.time.time", lambda: clock[0])
    monkeypatch.setattr("binance_futures_client.time.sleep", sleep)
    client.rate_limit_pause_until = 165.0
    sent = []
    client.session = SimpleNamespace(request=lambda *a, **kw: sent.append(clock[0]) or response(200, {}))
    client.ping()
    assert sleeps == [30.0, 30.0, 5.0]
    assert sent == [165.0]
