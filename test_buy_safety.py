import os

import pytest

from trader import Trader


class FakeClient:
    testnet = False

    def order(self, *args, **kwargs):
        raise AssertionError("order() must never be called by the safety test")


class FakeDB:
    def open_trade(self):
        return None

    def state_set(self, *args, **kwargs):
        raise AssertionError("DB mutation must not happen before safety guards")


class FakeDryRunClient:
    testnet = True

    def order(self, *args, **kwargs):
        raise AssertionError("order() must never be called in DRY_RUN")


def make_trader(client):
    t = object.__new__(Trader)
    t.dry_run = False
    t.client = client
    t.max_open_positions = 1
    t.db = FakeDB()
    t.symbol = "BTCUSDT"
    return t


def test_direct_market_buy_blocks_live_without_allow_live(monkeypatch):
    monkeypatch.setenv("ALLOW_LIVE", "false")
    t = make_trader(FakeClient())

    with pytest.raises(RuntimeError, match="ALLOW_LIVE=true"):
        t.market_buy(100.0)


def test_dry_run_blocks_before_db_mutation(monkeypatch):
    monkeypatch.setenv("ALLOW_LIVE", "false")
    t = make_trader(FakeDryRunClient())
    t.dry_run = True

    with pytest.raises(RuntimeError, match="DRY_RUN=true"):
        t.market_buy(100.0)


def test_market_buy_guard_order_is_independent_of_setup(monkeypatch):
    monkeypatch.setenv("ALLOW_LIVE", "false")
    t = make_trader(FakeClient())
    t.dry_run = False

    # The test intentionally never calls Trader.setup(). The execution guard
    # must still prevent a direct live BUY path.
    with pytest.raises(RuntimeError):
        t.market_buy(100.0)
