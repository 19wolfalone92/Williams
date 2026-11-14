"""New risk is limited to ten symbols; older liabilities retain management."""
from types import SimpleNamespace
from threading import RLock

import pytest

from binance_futures_client import BinanceFuturesClient
from execution_barrier import OrderIntent
from futures_williams_runtime import FuturesWilliamsRuntime
from williams_symbol_policy import NEW_RISK_SYMBOLS, MANAGEMENT_SYMBOLS, new_risk_symbol_blocker

LEGACY = ("SUIUSDT", "NEARUSDT", "DOTUSDT", "TRXUSDT", "BCHUSDT")


def test_explicit_new_risk_set_does_not_reduce_management_universe():
    assert len(NEW_RISK_SYMBOLS) == 10
    assert len(MANAGEMENT_SYMBOLS) == 15
    assert set(MANAGEMENT_SYMBOLS) - set(NEW_RISK_SYMBOLS) == set(LEGACY)
    assert all(new_risk_symbol_blocker(s) is None for s in NEW_RISK_SYMBOLS)
    assert all(new_risk_symbol_blocker(s) for s in [*LEGACY, "btcusdt", "BTCUSDT ", "", None])


@pytest.mark.parametrize("symbol", LEGACY)
@pytest.mark.parametrize("side", ["BUY", "SELL"])
@pytest.mark.parametrize("kind", ["MARKET", "LIMIT", "STOP_MARKET", "TAKE_PROFIT_MARKET"])
def test_direct_client_cannot_create_any_new_legacy_symbol_order(monkeypatch, symbol, side, kind):
    client = BinanceFuturesClient("test", "test", testnet=True)
    def forbidden(*args, **kwargs):
        pytest.fail("Blocked new risk reached the transport")
    monkeypatch.setattr(client, "_request", forbidden)
    with pytest.raises(ValueError, match="FUTURES_SYMBOL_MANAGEMENT_ONLY"):
        client.order(symbol, side, kind, quantity="1", price="100", stop_price="101")


@pytest.mark.parametrize("symbol", LEGACY)
@pytest.mark.parametrize("purpose", ["CAMPAIGN_ENTRY", "CAMPAIGN_ADD_ON"])
def test_runtime_final_gate_does_not_allow_a_missing_campaign_to_bypass_symbol_policy(symbol, purpose):
    bot = object.__new__(FuturesWilliamsRuntime)
    intent = OrderIntent.new(symbol, "BUY", "STOP_MARKET", {}, purpose=purpose)
    with pytest.raises(RuntimeError, match="FUTURES_SYMBOL_MANAGEMENT_ONLY"):
        bot._futures_pre_submit_checks(intent)


@pytest.mark.parametrize("symbol", LEGACY)
def test_runtime_rejects_entry_and_add_before_state_or_network_work(symbol):
    bot = object.__new__(FuturesWilliamsRuntime)
    bot.execution_barrier = SimpleNamespace(context_cache=SimpleNamespace(execution_lock=RLock()))
    bot._record_execution_decision = lambda *args: None
    signal = SimpleNamespace(symbol=symbol)
    # No DB/client exists: early admission must reject without allocating an intent.
    with pytest.raises(RuntimeError, match="FUTURES_SYMBOL_MANAGEMENT_ONLY"):
        bot._arm_entry(SimpleNamespace(signal=signal))
    with pytest.raises(RuntimeError, match="FUTURES_SYMBOL_MANAGEMENT_ONLY"):
        bot._arm_add_on(SimpleNamespace(), signal)


@pytest.mark.parametrize("symbol", LEGACY)
def test_final_http_boundary_rejects_new_legacy_exposure_even_without_order_helper(symbol):
    client = BinanceFuturesClient("test", "test", testnet=True)
    with pytest.raises(ValueError, match="FUTURES_SYMBOL_MANAGEMENT_ONLY"):
        client._request("POST", "/fapi/v1/algoOrder", {"symbol": symbol}, signed=True)


@pytest.mark.parametrize("symbol", LEGACY)
@pytest.mark.parametrize("side", ["BUY", "SELL"])
def test_direct_client_keeps_owned_stop_and_reduce_only_exit_access(monkeypatch, symbol, side):
    client = BinanceFuturesClient("test", "test", testnet=True)
    calls = []
    def request(method, path, params=None, signed=False):
        calls.append((method, path, params))
        return {"algoId": "1", "algoStatus": "NEW", "symbol": symbol} if path.endswith("algoOrder") else {"orderId": 2, "status": "FILLED"}
    monkeypatch.setattr(client, "_request", request)
    client.order(symbol, side, "STOP_MARKET", stop_price="95", close_position=True)
    client.order(symbol, side, "MARKET", quantity="1", reduce_only=True)
    assert len(calls) == 2
    assert calls[0][2]["closePosition"] == "true"
    assert calls[1][2]["reduceOnly"] == "true"
