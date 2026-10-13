from __future__ import annotations

import pytest

from binance_client import BinanceSpotClient
from portfolio_trader import MultiPositionTrader
from trader import Trader


class ProductionClientStub(BinanceSpotClient):
    """A production-client type stub that never performs network I/O."""

    def __init__(self):
        self.testnet = True
        self.api_key = "stub-key"
        self.api_secret = "stub-secret"
        self.order_calls = 0
        self.oco_calls = 0

    def order(self, *args, **kwargs):
        self.order_calls += 1
        raise AssertionError("production legacy sink must block before order()")

    def create_oco_sell(self, *args, **kwargs):
        self.oco_calls += 1
        raise AssertionError("production legacy sink must block before create_oco_sell()")


def test_legacy_trader_buy_blocks_production_client_before_network():
    client = ProductionClientStub()
    trader = Trader.__new__(Trader)
    trader.client = client
    trader.dry_run = False

    with pytest.raises(RuntimeError, match="Legacy Trader.market_buy is disabled"):
        trader.market_buy(100.0)
    assert client.order_calls == 0


def test_legacy_trader_oco_blocks_production_client_before_network():
    client = ProductionClientStub()
    trader = Trader.__new__(Trader)
    trader.client = client

    with pytest.raises(RuntimeError, match="Legacy Trader.place_oco is disabled"):
        trader.place_oco(1.0, 100.0)
    assert client.oco_calls == 0


def test_legacy_portfolio_oco_and_emergency_sell_are_contained():
    client = ProductionClientStub()
    trader = MultiPositionTrader.__new__(MultiPositionTrader)
    trader.client = client

    with pytest.raises(RuntimeError, match="Legacy direct OCO creation is disabled"):
        trader._create_oco("BTCUSDT", 1.0, 100.0, 1)
    with pytest.raises(RuntimeError, match="Legacy direct emergency SELL is disabled"):
        trader._emergency_market_sell("BTCUSDT", 1.0, 1, "test")
    assert client.oco_calls == 0
    assert client.order_calls == 0


def test_portfolio_legacy_entry_fallback_is_contained():
    client = ProductionClientStub()
    trader = MultiPositionTrader.__new__(MultiPositionTrader)
    trader.client = client
    trader.campaign_engine_enabled = False

    with pytest.raises(RuntimeError, match="production legacy direct order path is disabled"):
        trader.execute([object()])
    assert client.order_calls == 0
