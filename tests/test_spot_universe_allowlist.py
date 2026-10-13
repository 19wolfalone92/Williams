import unittest
from unittest.mock import patch

from market_scanner import MarketScanner
from trading_config import DEFAULT_SYMBOLS, TradingConfig


class SpotUniverseAllowlistTests(unittest.TestCase):
    def test_empty_and_all_scan_overrides_resolve_to_fixed_five(self):
        with patch.dict("os.environ", {}, clear=True):
            self.assertEqual(TradingConfig.from_env().symbols, DEFAULT_SYMBOLS)
            scanner = object.__new__(MarketScanner)
            self.assertEqual(scanner._load_symbols(), list(DEFAULT_SYMBOLS))
        with patch.dict("os.environ", {"AUTO_SCAN_SYMBOLS": "ALL", "SCAN_SYMBOLS": "ALL"}):
            self.assertEqual(TradingConfig.from_env().symbols, DEFAULT_SYMBOLS)
            scanner = object.__new__(MarketScanner)
            self.assertEqual(scanner._load_symbols(), list(DEFAULT_SYMBOLS))

    def test_custom_scan_symbols_are_filtered_to_allowlist(self):
        with patch.dict("os.environ", {"SCAN_SYMBOLS": "BTCUSDT,NOTREALUSDT,XRPUSDT"}):
            scanner = object.__new__(MarketScanner)
            self.assertEqual(scanner._load_symbols(), ["BTCUSDT", "XRPUSDT"])

    def test_invalid_explicit_symbol_does_not_expand_to_exchange_universe(self):
        scanner = object.__new__(MarketScanner)
        scanner.explicit_symbols = True
        scanner.symbols = []
        self.assertEqual(scanner._resolve_symbols(), [])


if __name__ == "__main__":
    unittest.main()
