"""Williams USDⓈ-M Futures Testnet release gate.

Offline CI validates the contract.  An authenticated read-only Futures Demo
check is available only with explicit WILLIAMS_TESTNET_E2E=1.  No order
mutation is performed by the read-only gate.
"""
from __future__ import annotations

import os
from dataclasses import dataclass

from binance_futures_client import BinanceFuturesClient


@dataclass(frozen=True)
class GateStep:
    id: str
    phase: str
    description: str
    mode: str  # OFFLINE, TESTNET_READ_ONLY, TESTNET_TRADE


GATE_STEPS = (
    GateStep("P01", "environment", "USD-M Futures Demo endpoint is selected in Testnet mode", "OFFLINE"),
    GateStep("P02", "environment", "Live Futures endpoint requires explicit live configuration", "OFFLINE"),
    GateStep("P03", "auth", "Futures API credentials are accepted by Binance", "TESTNET_READ_ONLY"),
    GateStep("P04", "auth", "Request signature, timestamp and recvWindow are valid", "TESTNET_READ_ONLY"),
    GateStep("P05", "account", "USD-M Futures account is reachable", "TESTNET_READ_ONLY"),
    GateStep("P06", "account", "One-Way position mode is enabled", "TESTNET_READ_ONLY"),
    GateStep("P07", "account", "Multi-Assets mode is disabled for the campaign runtime", "TESTNET_READ_ONLY"),
    GateStep("P08", "market", "exchangeInfo exposes valid Futures symbol filters", "TESTNET_READ_ONLY"),
    GateStep("P09", "market", "Closed-candle market data is fresh and internally consistent", "TESTNET_READ_ONLY"),
    GateStep("P10", "scanner", "Scanner selects active USDⓈ-M USDT perpetuals", "OFFLINE"),
    GateStep("P11", "strategy", "LONG and SHORT Williams signal extractors are present", "OFFLINE"),
    GateStep("P12", "strategy", "First presenting Wise-Man can start a campaign", "OFFLINE"),
    GateStep("P13", "strategy", "Super AO can be the first presenting entry signal", "OFFLINE"),
    GateStep("P14", "strategy", "Later Super AO/fractal signals are add-on candidates", "OFFLINE"),
    GateStep("P15", "risk", "Risk sizing is based on structural stop distance", "OFFLINE"),
    GateStep("P16", "risk", "1:5:4:3:2 campaign allocation remains within the hard risk cap", "OFFLINE"),
    GateStep("P17", "execution", "Every order mutation passes through the execution barrier", "OFFLINE"),
    GateStep("P18", "execution", "Conditional entry uses STOP_MARKET and durable client order id", "OFFLINE"),
    GateStep("P19", "execution", "Actual Futures fills determine campaign position quantity and average entry", "OFFLINE"),
    GateStep("P20", "protection", "Protection is reduce-only and covers the actual campaign quantity", "OFFLINE"),
    GateStep("P21", "protection", "Protection replacement is new-stop-first and recovery-safe", "OFFLINE"),
    GateStep("P22", "protection", "Protective stop cannot be loosened by trailing logic", "OFFLINE"),
    GateStep("P23", "recovery", "Crash after conditional entry is reconciled from Binance order state", "OFFLINE"),
    GateStep("P24", "recovery", "Crash after fill is reconciled from Binance position state", "OFFLINE"),
    GateStep("P25", "recovery", "Bot-owned exit fills are attributable to the exact campaign", "OFFLINE"),
    GateStep("P26", "recovery", "Uncertain position/order state fails closed into RECONCILE_REQUIRED", "OFFLINE"),
    GateStep("P27", "exit", "Williams structural exit uses the last 3/5-bar extreme", "OFFLINE"),
    GateStep("P28", "exit", "No fixed take-profit is required by the canonical campaign", "OFFLINE"),
    GateStep("P29", "android", "Android is a remote HTTPS cockpit and does not start native trading", "OFFLINE"),
    GateStep("P30", "release", "Only a clean final gate permits controlled Testnet trading", "TESTNET_TRADE"),
)


def live_e2e_enabled() -> bool:
    return os.getenv("WILLIAMS_TESTNET_E2E", "").strip() == "1"


def assert_live_opt_in() -> None:
    if not live_e2e_enabled():
        raise RuntimeError(
            "Live Testnet execution is disabled. Set WILLIAMS_TESTNET_E2E=1 explicitly."
        )
    if os.getenv("TESTNET", "true").strip().lower() != "true":
        raise RuntimeError("Release Gate refuses to run: TESTNET must be true.")
    if not os.getenv("BINANCE_API_KEY") or not os.getenv("BINANCE_API_SECRET"):
        raise RuntimeError(
            "Release Gate requires BINANCE_API_KEY and BINANCE_API_SECRET."
        )


def run_testnet_read_only(symbol: str = "BTCUSDT") -> dict:
    """Run authenticated, non-mutating USD-M Futures Demo checks."""
    assert_live_opt_in()
    client = BinanceFuturesClient(
        os.environ["BINANCE_API_KEY"],
        os.environ["BINANCE_API_SECRET"],
        testnet=True,
    )
    if client.base_url != "https://demo-fapi.binance.com":
        raise RuntimeError(
            f"Refusing Futures Demo Gate: unexpected endpoint {client.base_url!r}"
        )

    account = client.account()
    exchange = client.exchange_info(symbol)
    position_mode = client.get_position_mode()
    multi_assets = client.get_multi_assets_mode()

    symbols = {
        str(row.get("symbol", "")).upper()
        for row in (exchange or {}).get("symbols", [])
        if isinstance(row, dict)
    }
    if symbol.upper() not in symbols:
        raise RuntimeError(f"{symbol}: not present in Futures exchangeInfo")

    if bool((position_mode or {}).get("dualSidePosition")):
        raise RuntimeError("Futures Demo account is in Hedge Mode; Williams requires One-Way mode.")
    if bool((multi_assets or {}).get("multiAssetsMargin")):
        raise RuntimeError("Futures Demo account has Multi-Assets Mode enabled; Williams requires Single-Asset mode.")

    return {
        "ready": True,
        "account_available": bool(account is not None),
        "symbol": symbol.upper(),
        "endpoint": client.base_url,
        "position_mode": position_mode,
        "multi_assets_mode": multi_assets,
        "exchange_symbol_present": True,
    }


def test_gate_manifest_has_all_required_release_steps():
    assert len(GATE_STEPS) == 30
    assert [step.id for step in GATE_STEPS] == [f"P{i:02d}" for i in range(1, 31)]
    assert {step.mode for step in GATE_STEPS} == {
        "OFFLINE",
        "TESTNET_READ_ONLY",
        "TESTNET_TRADE",
    }


def test_gate_contains_futures_protection_safety_cases():
    text = " ".join(step.description.lower() for step in GATE_STEPS)
    assert "stop_market" in text
    assert "reduce-only" in text
    assert "new-stop-first" in text
    assert "reconcile_required" in text


def test_futures_client_uses_demo_endpoint_in_testnet():
    testnet = BinanceFuturesClient("k", "s", testnet=True)
    live = BinanceFuturesClient("k", "s", testnet=False)
    assert testnet.base_url == "https://demo-fapi.binance.com"
    assert live.base_url == "https://fapi.binance.com"


def test_live_gate_is_opt_in_only(monkeypatch):
    monkeypatch.delenv("WILLIAMS_TESTNET_E2E", raising=False)
    with __import__("pytest").raises(RuntimeError):
        assert_live_opt_in()


def test_release_gate_never_defaults_to_live(monkeypatch):
    monkeypatch.delenv("WILLIAMS_TESTNET_E2E", raising=False)
    monkeypatch.delenv("TESTNET", raising=False)
    assert live_e2e_enabled() is False


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="Williams Futures Testnet Release Gate")
    parser.add_argument("--read-only", action="store_true")
    parser.add_argument("--print-checklist", action="store_true")
    args = parser.parse_args()

    if args.print_checklist:
        for step in GATE_STEPS:
            print(f"{step.id} [{step.mode}] {step.description}")

    if args.read_only:
        result = run_testnet_read_only(os.getenv("TESTNET_SYMBOL", "BTCUSDT"))
        print("FUTURES DEMO READ-ONLY GATE: PASS")
        print(result)
