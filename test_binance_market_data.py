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
