"""Regression coverage for the retired legacy entry path.

The former script submitted a synthetic BUY at module import time and then
tested OCO recovery. That path has no Williams SignalSpec/expiry contract and
is now intentionally disabled; importing a test module must not mutate state
or simulate an order submission.
"""
import pytest

from trader import Trader


class NoOrderClient:
    testnet = True

    def __init__(self):
        self.order_calls = 0

    def order(self, *args, **kwargs):
        self.order_calls += 1
        raise AssertionError("legacy market_buy must not reach exchange adapter")


def test_legacy_market_buy_fails_closed_without_williams_signal_contract():
    trader = object.__new__(Trader)
    trader.dry_run = False
    trader.client = NoOrderClient()

    with pytest.raises(RuntimeError, match="legacy Trader.market_buy"):
        trader.market_buy(100.0)

    assert trader.client.order_calls == 0
