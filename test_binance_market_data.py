from binance_client import BinanceSpotClient


def test_spot_market_data_methods_build_expected_routes(monkeypatch):
    client = BinanceSpotClient("k", "s", testnet=True)
    calls = []

    def fake(method, path, params=None, signed=False):
        calls.append((method, path, params, signed))
        if path.endswith(("/trades", "/aggTrades")):
            return []
        return {"price": "1"}

    monkeypatch.setattr(client, "_request", fake)
    client.trades("BTCUSDT", 20)
    client.agg_trades("BTCUSDT", 20)
    client.avg_price("BTCUSDT")
    assert calls == [
        ("GET", "/api/v3/trades", {"symbol": "BTCUSDT", "limit": 20}, False),
        ("GET", "/api/v3/aggTrades", {"symbol": "BTCUSDT", "limit": 20}, False),
        ("GET", "/api/v3/avgPrice", {"symbol": "BTCUSDT"}, False),
    ]


def test_legacy_history_endpoint_follows_testnet_switch(monkeypatch):
    import data

    monkeypatch.setenv("TESTNET", "false")
    # Reload is intentionally avoided; verify the source rule through a
    # fresh module import so the environment boundary is explicit.
    import importlib
    reloaded = importlib.reload(data)
    assert reloaded.BASE_URL == "https://api.binance.com"

    monkeypatch.setenv("TESTNET", "true")
    reloaded = importlib.reload(data)
    assert reloaded.BASE_URL == "https://testnet.binance.vision"
