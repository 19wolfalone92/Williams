import pytest

from binance_usdm_futures_client import BinanceUsdmFuturesClient
import futures_testnet_release_gate as gate
from futures_testnet_release_gate import (
    FUTURES_GATE_STEPS,
    assert_futures_testnet_opt_in,
    futures_testnet_e2e_enabled,
)


def test_futures_gate_manifest_covers_direction_partial_fill_and_recovery():
    assert [step.id for step in FUTURES_GATE_STEPS] == [f"F{i:02d}" for i in range(1, 21)]
    descriptions = " ".join(step.description.lower() for step in FUTURES_GATE_STEPS)
    assert "long" in descriptions and "short" in descriptions
    assert "partial" in descriptions
    assert "orphaned" in descriptions
    assert "restart" in descriptions
    assert {step.mode for step in FUTURES_GATE_STEPS} == {
        "OFFLINE", "TESTNET_READ_ONLY", "TESTNET_TRADE"
    }


def test_futures_demo_endpoint_is_selected_by_default_client():
    client = BinanceUsdmFuturesClient("key", "secret", testnet=True)
    assert client.base_url == BinanceUsdmFuturesClient.DEMO_BASE_URL


def test_futures_demo_live_checks_are_disabled_by_default(monkeypatch):
    monkeypatch.delenv("WILLIAMS_FUTURES_TESTNET_E2E", raising=False)
    monkeypatch.delenv("BINANCE_API_KEY", raising=False)
    monkeypatch.delenv("BINANCE_API_SECRET", raising=False)
    assert futures_testnet_e2e_enabled() is False
    with pytest.raises(RuntimeError, match="disabled"):
        assert_futures_testnet_opt_in()


def test_futures_demo_gate_requires_explicit_opt_in_and_credentials(monkeypatch):
    monkeypatch.setenv("WILLIAMS_FUTURES_TESTNET_E2E", "1")
    monkeypatch.setenv("TESTNET", "true")
    monkeypatch.delenv("BINANCE_API_KEY", raising=False)
    monkeypatch.delenv("BINANCE_API_SECRET", raising=False)
    with pytest.raises(RuntimeError, match="requires BINANCE_API_KEY"):
        assert_futures_testnet_opt_in()


def test_futures_demo_gate_refuses_testnet_false(monkeypatch):
    monkeypatch.setenv("WILLIAMS_FUTURES_TESTNET_E2E", "1")
    monkeypatch.setenv("TESTNET", "false")
    monkeypatch.setenv("BINANCE_API_KEY", "key")
    monkeypatch.setenv("BINANCE_API_SECRET", "secret")
    with pytest.raises(RuntimeError, match="TESTNET=true"):
        assert_futures_testnet_opt_in()

class FakeReadOnlyFuturesClient:
    DEMO_BASE_URL = "https://demo-fapi.binance.com"

    def __init__(self, api_key, api_secret, *, testnet, allow_live, max_leverage):
        self.base_url = self.DEMO_BASE_URL
        self.can_trade = True
        self.isolated = True
        self.leverage = 1
        self.quote_asset = "USDT"
        self.margin_asset = "USDT"
        self.missing_market_filter = False
        self.bad_filter_step = False
        self.crossed_book = False
        self.position_amt = "0"
        self.standard_orders = []
        self.algo_orders = []

    def sync_time(self):
        return {"serverTime": 1}

    def ensure_one_way_mode(self):
        return {"dualSidePosition": False}

    def account(self):
        return {"canTrade": self.can_trade}

    def position_risk(self, symbol=None):
        rows = [{
            "symbol": "BTCUSDT",
            "positionAmt": self.position_amt,
            "isolated": self.isolated,
            "leverage": str(self.leverage),
        }]
        return [row for row in rows if not symbol or row["symbol"] == symbol.upper()]

    def exchange_info(self, symbol=None):
        return {"symbols": [{
            "symbol": symbol or "BTCUSDT",
            "status": "TRADING",
            "contractType": "PERPETUAL",
            "quoteAsset": self.quote_asset,
            "marginAsset": self.margin_asset,
        }]}

    def symbol_filters(self, symbol):
        filters = {
            "PRICE_FILTER": {
                "minPrice": "0", "maxPrice": "1000000", "tickSize": "0.01",
            },
            "LOT_SIZE": {
                "minQty": "0.001", "maxQty": "100000", "stepSize": "0.001",
            },
        }
        if not self.missing_market_filter:
            filters["MARKET_LOT_SIZE"] = {
                "minQty": "0.001", "maxQty": "100000",
                "stepSize": "0" if self.bad_filter_step else "0.001",
            }
        return filters

    def mark_price(self, symbol):
        return {"symbol": symbol, "markPrice": "100.0"}

    def book_ticker(self, symbol):
        return {
            "symbol": symbol,
            "bidPrice": "100.01" if self.crossed_book else "99.99",
            "askPrice": "100.00" if self.crossed_book else "100.01",
        }

    def open_orders(self):
        return list(self.standard_orders)

    def open_algo_orders(self):
        return list(self.algo_orders)


def _enable_fake_demo_gate(monkeypatch, *, client_type=FakeReadOnlyFuturesClient):
    monkeypatch.setenv("WILLIAMS_FUTURES_TESTNET_E2E", "1")
    monkeypatch.setenv("TESTNET", "true")
    monkeypatch.setenv("BINANCE_API_KEY", "demo-key")
    monkeypatch.setenv("BINANCE_API_SECRET", "demo-secret")
    monkeypatch.setattr(gate, "BinanceUsdmFuturesClient", client_type)


def test_read_only_release_gate_passes_only_when_demo_policy_is_verified(monkeypatch):
    _enable_fake_demo_gate(monkeypatch)

    result = gate.run_futures_testnet_read_only(["BTCUSDT"])

    assert result["status"] == "PASS_READ_ONLY"
    assert result["release_gate_passed"] is True
    assert result["mutations_submitted"] == 0
    assert result["account_can_trade"] is True
    assert result["markets"][0]["quote_asset"] == "USDT"
    assert result["markets"][0]["margin_asset"] == "USDT"
    assert result["markets"][0]["isolated_margin"] is True
    assert result["markets"][0]["leverage"] == 1
    assert result["markets"][0]["validated_filters"]["MARKET_LOT_SIZE"]["step"] == pytest.approx(0.001)
    assert 0 <= result["markets"][0]["spread_pct"] < 0.001


@pytest.mark.parametrize(
    ("setting", "value", "message"),
    [
        ("can_trade", False, "canTrade=true"),
        ("isolated", False, "isolated margin"),
        ("leverage", 2, "must be 1x"),
        ("quote_asset", "BUSD", "quote asset must be USDT"),
        ("margin_asset", "BUSD", "margin asset must be USDT"),
        ("missing_market_filter", True, "LOT_SIZE and MARKET_LOT_SIZE"),
        ("bad_filter_step", True, "MARKET_LOT_SIZE bounds/stepSize are invalid"),
        ("crossed_book", True, "best bid/ask is invalid"),
    ],
)
def test_read_only_release_gate_fails_closed_on_invalid_demo_policy(monkeypatch, setting, value, message):
    class ConfiguredClient(FakeReadOnlyFuturesClient):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, **kwargs)
            setattr(self, setting, value)

    _enable_fake_demo_gate(monkeypatch, client_type=ConfiguredClient)

    with pytest.raises(RuntimeError, match=message):
        gate.run_futures_testnet_read_only(["BTCUSDT"])

@pytest.mark.parametrize(
    ("field", "value", "expected_status"),
    [
        ("position_amt", "0.01", "BLOCKED_ACCOUNT_NOT_CLEAN"),
        ("standard_orders", [{"symbol": "BTCUSDT", "orderId": 1}], "BLOCKED_ACCOUNT_NOT_CLEAN"),
        ("algo_orders", [{"symbol": "BTCUSDT", "algoId": 2}], "BLOCKED_ACCOUNT_NOT_CLEAN"),
    ],
)
def test_read_only_gate_does_not_call_dirty_demo_account_release_ready(
    monkeypatch, field, value, expected_status
):
    class DirtyClient(FakeReadOnlyFuturesClient):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, **kwargs)
            setattr(self, field, value)

    _enable_fake_demo_gate(monkeypatch, client_type=DirtyClient)

    result = gate.run_futures_testnet_read_only(["BTCUSDT"])

    assert result["status"] == expected_status
    assert result["release_gate_passed"] is False
    assert result["mutations_submitted"] == 0

