from market_scanner import MarketScanner


class FakeClient:
    def exchange_info(self):
        return {
            "symbols": [
                {
                    "symbol": "BTCUSDT",
                    "status": "TRADING",
                    "baseAsset": "BTC",
                    "quoteAsset": "USDT",
                    "isSpotTradingAllowed": True,
                    "permissions": ["SPOT"],
                },
                {
                    "symbol": "ETHUSDT",
                    "status": "TRADING",
                    "baseAsset": "ETH",
                    "quoteAsset": "USDT",
                    "isSpotTradingAllowed": True,
                    "permissions": ["SPOT"],
                },
                {
                    "symbol": "XRPBTC",
                    "status": "TRADING",
                    "baseAsset": "XRP",
                    "quoteAsset": "BTC",
                    "isSpotTradingAllowed": True,
                    "permissions": ["SPOT"],
                },
                {
                    "symbol": "BTCUPUSDT",
                    "status": "TRADING",
                    "baseAsset": "BTCUP",
                    "quoteAsset": "USDT",
                    "isSpotTradingAllowed": True,
                    "permissions": ["SPOT"],
                },
                {
                    "symbol": "OLDUSDT",
                    "status": "BREAK",
                    "baseAsset": "OLD",
                    "quoteAsset": "USDT",
                    "isSpotTradingAllowed": True,
                    "permissions": ["SPOT"],
                },
            ]
        }


def test_full_universe_discovers_spot_usdt_only(monkeypatch):
    monkeypatch.setenv("SCAN_ALL_USDT", "true")
    monkeypatch.setenv("SCAN_MAX_SYMBOLS", "0")
    monkeypatch.setenv("EXCLUDE_LEVERAGED_TOKENS", "true")
    monkeypatch.delenv("SCAN_SYMBOLS", raising=False)
    monkeypatch.delenv("AUTO_SCAN_SYMBOLS", raising=False)

    scanner = MarketScanner(FakeClient(), symbols=None)
    assert scanner._resolve_symbols() == ["BTCUSDT", "ETHUSDT"]


def test_explicit_empty_test_universe_remains_empty(monkeypatch):
    monkeypatch.setenv("SCAN_ALL_USDT", "true")
    scanner = MarketScanner(FakeClient(), symbols=[])
    assert scanner._resolve_symbols() == []


def test_symbol_limit_is_applied_after_validation(monkeypatch):
    monkeypatch.setenv("SCAN_ALL_USDT", "true")
    monkeypatch.setenv("SCAN_MAX_SYMBOLS", "1")
    scanner = MarketScanner(FakeClient(), symbols=None)
    assert scanner._resolve_symbols() == ["BTCUSDT"]


def test_autonomous_scan_does_not_truncate_discovered_universe_to_five():
    scanner = object.__new__(MarketScanner)
    scanner.explicit_symbols = False
    scanner.scan_all_usdt = True
    scanner._discover_usdt_symbols = lambda: [
        "BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT", "XRPUSDT", "ADAUSDT"
    ]
    resolved = scanner._resolve_symbols()
    assert len(resolved) == 6
