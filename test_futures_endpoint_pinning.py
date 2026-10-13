"""Regression tests for Futures environment endpoint isolation."""

import pytest

from binance_futures_client import BinanceFuturesClient


def test_testnet_defaults_to_official_demo_futures_host(monkeypatch):
    monkeypatch.delenv("BINANCE_FUTURES_TESTNET_URL", raising=False)
    client = BinanceFuturesClient("test-key", "test-secret", testnet=True)
    assert client.testnet is True
    assert client.base_url == "https://demo-fapi.binance.com"


@pytest.mark.parametrize(
    "endpoint",
    [
        "https://fapi.binance.com",
        "https://example.invalid",
        "http://demo-fapi.binance.com",
        "https://user:password@demo-fapi.binance.com",
        "https://demo-fapi.binance.com/path",
        "https://demo-fapi.binance.com?redirect=1",
    ],
)
def test_testnet_rejects_endpoint_override(monkeypatch, endpoint):
    monkeypatch.setenv("BINANCE_FUTURES_TESTNET_URL", endpoint)
    with pytest.raises(ValueError, match="Testnet client must use"):
        BinanceFuturesClient("test-key", "test-secret", testnet=True)


def test_live_client_is_pinned_to_official_futures_host(monkeypatch):
    monkeypatch.delenv("BINANCE_FUTURES_URL", raising=False)
    client = BinanceFuturesClient("test-key", "test-secret", testnet=False)
    assert client.testnet is False
    assert client.base_url == "https://fapi.binance.com"


def test_live_client_rejects_untrusted_endpoint_override(monkeypatch):
    monkeypatch.setenv("BINANCE_FUTURES_URL", "https://example.invalid")
    with pytest.raises(ValueError, match="Live Futures client must use"):
        BinanceFuturesClient("test-key", "test-secret", testnet=False)
