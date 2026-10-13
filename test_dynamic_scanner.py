from market_scanner import MarketScanner
from trading_config import DEFAULT_SYMBOLS


class FakeClient:
    def exchange_info(self):
        return {"symbols": []}


def test_all_setting_resolves_to_fixed_five_pair_allowlist(monkeypatch):
    monkeypatch.setenv("SCAN_ALL_USDT", "true")
    monkeypatch.setenv("SCAN_MAX_SYMBOLS", "1")
    monkeypatch.delenv("SCAN_SYMBOLS", raising=False)
    monkeypatch.delenv("AUTO_SCAN_SYMBOLS", raising=False)

    scanner = MarketScanner(FakeClient(), symbols=None)
    assert scanner._resolve_symbols() == list(DEFAULT_SYMBOLS)


def test_explicit_empty_test_universe_remains_empty(monkeypatch):
    monkeypatch.setenv("SCAN_ALL_USDT", "true")
    scanner = MarketScanner(FakeClient(), symbols=[])
    assert scanner._resolve_symbols() == []


def test_custom_symbols_are_filtered_to_the_five_pair_allowlist(monkeypatch):
    monkeypatch.setenv("SCAN_ALL_USDT", "true")
    scanner = MarketScanner(FakeClient(), symbols=["BTCUSDT", "ADAUSDT", "XRPUSDT"])
    assert scanner._resolve_symbols() == ["BTCUSDT", "XRPUSDT"]


def test_universe_compatibility_method_cannot_expand_to_exchange_wide_pairs():
    scanner = object.__new__(MarketScanner)
    scanner.symbols = list(DEFAULT_SYMBOLS)
    assert scanner._discover_usdt_symbols() == list(DEFAULT_SYMBOLS)
