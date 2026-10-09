import pytest

from binance_usdm_futures_client import BinanceUsdmFuturesClient
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
