import unittest

from market_context import ContextCache, TFMarketContext
from market_scanner import MarketScanner


class ScannerContextVersionTests(unittest.TestCase):
    def setUp(self):
        self.cache = ContextCache()
        self.scanner = object.__new__(MarketScanner)
        self.scanner.context_cache = self.cache
        self.scanner.interval = "1h"

    def publish(self, interval):
        self.cache.publish(TFMarketContext(
            symbol="BTCUSDT",
            interval=interval,
            version=1,
            candle_open_time_ms=1,
            candle_close_time_ms=2,
            price=100.0,
            allow_long=True,
            decision="LONG",
        ))

    def test_complete_d1_h4_h1_context_vector_is_attached(self):
        for frame in ("1d", "4h", "1h"):
            self.publish(frame)
        self.assertEqual(
            self.scanner._signal_context_versions("BTCUSDT"),
            {"1d": 1, "4h": 1, "1h": 1},
        )

    def test_partial_context_vector_is_rejected_as_empty(self):
        self.publish("1d")
        self.publish("1h")
        self.assertEqual(self.scanner._signal_context_versions("BTCUSDT"), {})

    def test_missing_context_cache_fails_closed(self):
        self.scanner.context_cache = None
        self.assertEqual(self.scanner._signal_context_versions("BTCUSDT"), {})


if __name__ == "__main__":
    unittest.main()
