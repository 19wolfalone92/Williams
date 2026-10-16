from binance_futures_client import BinanceFuturesClient


def make_client():
    return BinanceFuturesClient("test-key", "test-secret", testnet=True)


def test_account_uses_current_usdm_futures_v3_endpoint(monkeypatch):
    client = make_client()
    calls = []

    def fake_request(method, path, params=None, signed=False):
        calls.append((method, path, signed))
        return {
            "canTrade": True,
            "assets": [{"asset": "USDT", "walletBalance": "1000", "availableBalance": "900"}],
            "totalWalletBalance": "1000",
            "totalUnrealizedProfit": "0",
            "availableBalance": "900",
            "positions": [],
        }

    monkeypatch.setattr(client, "_request", fake_request)
    account = client.account()

    assert calls == [("GET", "/fapi/v3/account", True)]
    assert account["accountType"] == "FUTURES"
    assert account["balances"][0]["asset"] == "USDT"


def test_position_risk_uses_current_usdm_futures_v3_endpoint(monkeypatch):
    client = make_client()
    calls = []

    def fake_request(method, path, params=None, signed=False):
        calls.append((method, path, params, signed))
        return [{"symbol": "BTCUSDT", "positionAmt": "0"}]

    monkeypatch.setattr(client, "_request", fake_request)
    rows = client.position_risk("BTCUSDT")

    assert rows[0]["symbol"] == "BTCUSDT"
    assert calls == [("GET", "/fapi/v3/positionRisk", {"symbol": "BTCUSDT"}, True)]
