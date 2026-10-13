import unittest

from execution_barrier import ExecutionBarrier, OrderIntent
from market_context import ContextCache, TFMarketContext


class CampaignEntryContextGateTests(unittest.TestCase):
    def setUp(self):
        self.cache = ContextCache()
        self.barrier = ExecutionBarrier(self.cache)

    def context(self, allow_long=True):
        return TFMarketContext(
            symbol="BTCUSDT",
            interval="1h",
            version=1,
            candle_open_time_ms=1,
            candle_close_time_ms=2,
            price=100.0,
            allow_long=allow_long,
            decision="ALLOW_LONG" if allow_long else "NO_TRADE",
        )

    def test_campaign_entry_without_versioned_context_is_blocked(self):
        intent = OrderIntent.new(
            "BTCUSDT", "BUY", "STOP_LOSS", {},
            purpose="CAMPAIGN_ENTRY", permission_interval="1h",
        )
        reason = self.barrier._validate(intent, self.cache.snapshot())
        self.assertEqual(reason, "missing required_context_versions")

    def test_campaign_entry_requires_operative_long_permission(self):
        self.cache.publish(self.context(allow_long=False))
        intent = OrderIntent.new(
            "BTCUSDT", "BUY", "STOP_LOSS", {"1h": 1},
            purpose="CAMPAIGN_ENTRY", permission_interval="1h",
        )
        reason = self.barrier._validate(intent, self.cache.snapshot())
        self.assertEqual(reason, "context 1h does not allow LONG")

    def test_campaign_entry_passes_context_gate_only_when_version_and_permission_match(self):
        self.cache.publish(self.context(allow_long=True))
        intent = OrderIntent.new(
            "BTCUSDT", "BUY", "STOP_LOSS", {"1h": 1},
            purpose="CAMPAIGN_ENTRY", permission_interval="1h",
        )
        self.assertEqual(self.barrier._validate(intent, self.cache.snapshot()), "")

    def test_campaign_exit_is_not_blocked_by_entry_direction_permission(self):
        intent = OrderIntent.new(
            "BTCUSDT", "SELL", "MARKET", {},
            purpose="CAMPAIGN_EXIT",
        )
        self.assertEqual(self.barrier._validate(intent, self.cache.snapshot()), "")


if __name__ == "__main__":
    unittest.main()
