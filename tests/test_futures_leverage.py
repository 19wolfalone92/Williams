from dataclasses import replace
from types import SimpleNamespace

import pytest

from binance_futures_client import BinanceFuturesClient
from futures_leverage import leverage_bracket, parse_leverage, validate_isolated_leverage
from futures_williams_runtime import FuturesWilliamsRuntime
from trading_config import DEFAULT_FUTURES_SYMBOLS, TradingConfig


def brackets(symbol="BTCUSDT"):
    return {"symbol": symbol, "brackets": [
        {"notionalFloor": 0, "notionalCap": 1000, "initialLeverage": 125, "maintMarginRatio": 0.005},
        {"notionalFloor": 1000, "notionalCap": 10000, "initialLeverage": 10, "maintMarginRatio": 0.01},
    ]}


@pytest.mark.parametrize("value", [0, -1, 126, True, False, None, 1.5, "2.0", "NaN", "", "3x"])
def test_invalid_leverage_is_rejected_instead_of_silently_clamped(value):
    with pytest.raises(ValueError):
        parse_leverage(value)
    with pytest.raises(ValueError):
        TradingConfig.from_env({"FUTURES_LEVERAGE": value})


@pytest.mark.parametrize("leverage", [1, 2, 3, 5, 10, 20, 50, 100, 125])
def test_global_leverage_is_not_capped_at_two(leverage):
    config = TradingConfig.from_env({"FUTURES_LEVERAGE": str(leverage)})
    assert all(config.leverage_for(symbol) == leverage for symbol in DEFAULT_FUTURES_SYMBOLS)
    assert config.allow_long and config.allow_short and len(config.symbols) == 10


def test_per_symbol_settings_survive_configuration_and_do_not_change_risk_caps():
    overrides = ",".join(f"{symbol}:{i + 1}" for i, symbol in enumerate(DEFAULT_FUTURES_SYMBOLS))
    config = TradingConfig.from_env({"FUTURES_LEVERAGE": "5", "FUTURES_SYMBOL_LEVERAGES": overrides})
    assert [config.leverage_for(s) for s in DEFAULT_FUTURES_SYMBOLS] == list(range(1, 11))
    assert config.risk_per_trade_pct == 0.0025
    assert config.max_total_risk_pct == 0.006
    assert config.max_daily_loss_pct == 0.03
    inherited = TradingConfig.from_env({"FUTURES_LEVERAGE": "5", "FUTURES_SYMBOL_LEVERAGES": "btcUSDT:3"})
    assert inherited.leverage_for("BTCUSDT") == 3
    assert inherited.leverage_for("ETHUSDT") == 5


@pytest.mark.parametrize("raw", ["BTCUSDT:0", "BTCUSDT:2.5", "BTCUSDT:5,BTCUSDT:7", "OTHERUSDT:5", "BTCUSDT=5", "BTCUSDT:5,"])
def test_malformed_symbol_overrides_fail_closed(raw):
    with pytest.raises(ValueError):
        TradingConfig.from_env({"FUTURES_SYMBOL_LEVERAGES": raw})


def test_tiers_use_array_or_object_and_floor_inclusive_cap_exclusive():
    assert leverage_bracket(brackets(), "BTCUSDT", 999) == (125, 0.005)
    assert leverage_bracket([brackets()], "BTCUSDT", 1000) == (10, 0.01)
    with pytest.raises(ValueError, match="NOTIONAL_CAP"):
        leverage_bracket(brackets(), "BTCUSDT", 10000)
    with pytest.raises(ValueError, match="LEVERAGE_LIMIT"):
        validate_isolated_leverage(brackets(), "BTCUSDT", 20, 1000, 100, 99)


@pytest.mark.parametrize("payload", [{}, [], {"symbol": "ETHUSDT", "brackets": []}, [brackets(), brackets()],
    {"symbol": "BTCUSDT", "brackets": [{"notionalFloor": 0, "notionalCap": 10000, "initialLeverage": 20, "maintMarginRatio": "NaN"}]}])
def test_missing_or_malformed_exchange_limits_never_authorize_entry(payload):
    with pytest.raises(ValueError):
        validate_isolated_leverage(payload, "BTCUSDT", 2, 500, 100, 99)


@pytest.mark.parametrize("stop", [99.0, 101.0])
def test_high_leverage_cannot_put_liquidation_before_long_or_short_stop(stop):
    validate_isolated_leverage(brackets(), "BTCUSDT", 10, 500, 100, stop)
    with pytest.raises(ValueError, match="STOP_BUFFER"):
        validate_isolated_leverage(brackets(), "BTCUSDT", 125, 500, 100, stop)


def test_short_stop_notional_must_fit_the_next_exchange_tier():
    with pytest.raises(ValueError, match="LEVERAGE_LIMIT"):
        validate_isolated_leverage(brackets(), "BTCUSDT", 20, 999, 100, 101)


def test_client_uses_signed_futures_leverage_routes_and_never_truncates_fractional_input(monkeypatch):
    client = BinanceFuturesClient("key", "secret", testnet=True)
    calls = []
    monkeypatch.setattr(client, "_request", lambda *a, **kw: calls.append((a, kw)) or {})
    try:
        client.set_leverage("BTCUSDT", 10)
        client.leverage_brackets("BTCUSDT")
        with pytest.raises(ValueError):
            client.set_leverage("BTCUSDT", 2.5)
        assert calls == [(("POST", "/fapi/v1/leverage", {"symbol": "BTCUSDT", "leverage": 10}), {"signed": True}),
                         (("GET", "/fapi/v1/leverageBracket", {"symbol": "BTCUSDT"}), {"signed": True})]
    finally:
        client.session.close()


def test_risk_sizing_uses_symbol_leverage_and_buffered_free_margin(monkeypatch):
    bot = object.__new__(FuturesWilliamsRuntime)
    bot.config = TradingConfig(futures_leverage=2)
    bot._equity = lambda: 10000
    bot._available_margin = lambda: 10000
    bot._normalize_qty = lambda s, q: q
    bot._min_notional = lambda s: 0
    monkeypatch.setenv("FUTURES_MAX_MARGIN_FRACTION", "0.25")
    low = bot._size_from_risk("BTCUSDT", 100, 99, 0.002)
    bot.config = replace(bot.config, futures_symbol_leverages=(("BTCUSDT", 10),))
    high = bot._size_from_risk("BTCUSDT", 100, 99, 0.002)
    assert high == low  # Higher leverage alone cannot increase the loss budget.
    bot._available_margin = lambda: 1
    qty, _, notional = bot._size_from_risk("BTCUSDT", 100, 99, 0.002)
    assert notional / 10 <= 0.95
    assert qty * 100 == notional


def test_active_position_never_gets_releveraged_by_configuration_change():
    bot = object.__new__(FuturesWilliamsRuntime)
    bot.config = TradingConfig(futures_leverage=10)
    bot._position = lambda s: {"positionAmt": "-1", "entryPrice": "100", "marginType": "isolated", "leverage": "2"}
    bot.client = SimpleNamespace(symbol_configuration=lambda s: {"symbol": s, "marginType": "ISOLATED", "leverage": 2})
    with pytest.raises(RuntimeError, match="ACTIVE_POSITION_MISMATCH"):
        bot._ensure_symbol_config("BTCUSDT")


def test_pending_entry_never_gets_releveraged_after_restart():
    bot = object.__new__(FuturesWilliamsRuntime)
    bot.config = TradingConfig(futures_leverage=10)
    bot._position = lambda s: {"positionAmt": "0", "entryPrice": "0"}
    bot.db = SimpleNamespace(state_get=lambda key: "existing-client-order-id")
    bot.client = SimpleNamespace(symbol_configuration=lambda s: {"symbol": s, "marginType": "ISOLATED", "leverage": 2})
    with pytest.raises(RuntimeError, match="ACTIVE_POSITION_MISMATCH"):
        bot._ensure_symbol_config("BTCUSDT")


@pytest.mark.parametrize("amount,side,stop", [(1, "BUY", 99), (-1, "SELL", 101)])
def test_add_on_uses_fresh_symbol_config_without_v2_position_fields_and_combined_tier(amount, side, stop):
    bot = object.__new__(FuturesWilliamsRuntime)
    bot.config = TradingConfig(futures_leverage=5)
    bot._leverage_confirmations = {}
    bot._position = lambda s: {"positionAmt": str(amount), "entryPrice": "100"}
    bot.client = SimpleNamespace(
        symbol_configuration=lambda s: {"symbol": s, "marginType": "ISOLATED", "leverage": 5, "maxNotionalValue": "10000"},
        leverage_brackets=lambda s: brackets(s),
    )
    bot._ensure_symbol_config("BTCUSDT")  # No margin/leverage POST methods exist on this client.
    bot._validate_entry_leverage("BTCUSDT", 1, 100, stop, side)
    bot.config = replace(bot.config, futures_leverage=20)
    bot._position = lambda s: {"positionAmt": str(amount * 9), "entryPrice": "100"}
    with pytest.raises(ValueError, match="LEVERAGE_LIMIT"):
        bot._validate_entry_leverage("BTCUSDT", 1, 100, stop, side)


def test_add_on_rejects_opposite_or_malformed_existing_position():
    bot = object.__new__(FuturesWilliamsRuntime)
    bot._position = lambda s: {"positionAmt": "-1", "entryPrice": "100"}
    with pytest.raises(RuntimeError, match="DIRECTION_MISMATCH"):
        bot._validate_entry_leverage("BTCUSDT", 1, 100, 99, "BUY")
    bot._position = lambda s: {"positionAmt": "1", "entryPrice": "0"}
    with pytest.raises(RuntimeError, match="Invalid position"):
        bot._validate_entry_leverage("BTCUSDT", 1, 100, 99, "BUY")
