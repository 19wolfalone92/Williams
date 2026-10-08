#!/usr/bin/env python3
"""Fast, stdlib-only release contract gate for Williams Futures."""
from __future__ import annotations

import ast
from pathlib import Path

ROOT = Path(__file__).resolve().parent
EXPECTED_VERSION = "4.24.0"


def read(path: str) -> str:
    p = ROOT / path
    assert p.is_file(), f"missing required file: {path}"
    return p.read_text(encoding="utf-8", errors="replace")


def must(text: str, needle: str, where: str) -> None:
    assert needle in text, f"{where}: missing {needle!r}"


def must_not(text: str, needle: str, where: str) -> None:
    assert needle not in text, f"{where}: forbidden {needle!r}"


def main() -> None:
    core_files = (
        "trading_config.py",
        "campaign_model.py",
        "campaign_engine.py",
        "futures_williams_runtime.py",
        "binance_futures_client.py",
        "server.py",
        "db.py",
        "execution_barrier.py",
        "williams_signals.py",
        "test_testnet_release_gate.py",
    )
    for path in core_files:
        ast.parse(read(path), filename=path)

    env = read(".env.example")
    for needle in (
        "TESTNET=true",
        "ALLOW_LIVE=false",
        "DRY_RUN=true",
        "BINANCE_MARKET=futures_usdt",
        "FUTURES_LEVERAGE=2",
        "FUTURES_MARGIN_TYPE=ISOLATED",
        "FUTURES_FORCE_ONE_WAY=true",
        "ALLOW_LONG=true",
        "ALLOW_SHORT=true",
        "TAKE_PROFIT_PCT=0.0",
        "CAMPAIGN_TRAIL_BARS=5",
        "MAX_OPEN_POSITIONS=5",
        "MAX_TOTAL_RISK_PCT=0.01",
        "MAX_RISK_PER_TRADE_PCT=0.005",
    ):
        must(env, needle, ".env.example")

    cfg = read("trading_config.py")
    for needle in (
        'market: str = "futures_usdt"',
        "futures_leverage: int = 2",
        'futures_margin_type: str = "ISOLATED"',
        "futures_force_one_way: bool = True",
        "allow_long: bool = True",
        "allow_short: bool = True",
        'market = str(source.get("BINANCE_MARKET", "futures_usdt"))',
        'risk_key = "MAX_RISK_PER_TRADE_PCT"',
        "min(0.005",
        "min(0.01",
    ):
        must(cfg, needle, "trading_config.py")

    model = read("campaign_model.py")
    for needle in (
        "class PendingSignal",
        "class CampaignState",
        "class SignalState",
        "RECONCILE_REQUIRED =",
        "TRIGGERED =",
        "FILLED =",
    ):
        must(model, needle, "campaign_model.py")

    engine = read("campaign_engine.py")
    for needle in (
        "def choose_initial_signal",
        's.side in {"BUY", "SELL"}',
        "def arm_entry",
        "def arm_add_on",
    ):
        must(engine, needle, "campaign_engine.py")

    signals = read("williams_signals.py")
    for needle in (
        "def extract_long_signal_specs",
        "def extract_short_signal_specs",
        "def _latest_super_ao",
        "def _latest_reversal",
        "side=side",
    ):
        must(signals, needle, "williams_signals.py")

    futures = read("futures_williams_runtime.py")
    for needle in (
        "class FuturesWilliamsScanner",
        "class FuturesWilliamsRuntime",
        "PendingSignal",
        "SignalState.VALIDATED",
        'order_type="STOP_MARKET"',
        "reduce_only=True",
        "def _replace_protection",
        "def _reconcile_pending",
        "def _finalize_confirmed_exchange_exit",
        "def recover",
        "def process",
        "FUTURES_LIQUIDATION_BUFFER_PCT",
    ):
        must(futures, needle, "futures_williams_runtime.py")
    must_not(futures, "client.cancel_replace(", "futures_williams_runtime.py")

    client = read("binance_futures_client.py")
    for needle in (
        "/fapi/v1/order",
        "/fapi/v1/account",
        "positionAmt",
        "reduceOnly",
        "STOP_MARKET",
    ):
        must(client, needle, "binance_futures_client.py")

    server = read("server.py")
    for needle in (
        "FuturesWilliamsRuntime",
        "BinanceFuturesClient",
        "BINANCE_MARKET",
        "USD-M Futures",
    ):
        must(server, needle, "server.py")

    android = read("app/src/main/java/com/williamsbot/MainActivity.kt")
    for needle in (
        f"Williams {EXPECTED_VERSION}",
        "class BackendApi",
        "https://",
        'putString("backend_url"',
        'putString("mobile_token"',
    ):
        must(android, needle, "MainActivity.kt")
    must_not(android, "StandaloneRuntime.start(this)", "MainActivity.kt")
    must_not(android, 'putString("api_key"', "MainActivity.kt")
    must_not(android, 'putString("api_secret"', "MainActivity.kt")
    must_not(android, "http://127.0.0.1:18080", "MainActivity.kt")
    must_not(android, "http://localhost:18080", "MainActivity.kt")

    service = read("app/src/main/java/com/williamsbot/TradingForegroundService.kt")
    for needle in ("legacy native Spot runtime", "remote Futures backend"):
        must(service, needle, "TradingForegroundService.kt")
    must_not(service, "StandaloneRuntime.start(this)", "TradingForegroundService.kt")

    remote = read("test_android_remote_only.py")
    for needle in (
        "test_android_has_remote_cockpit_runtime",
        "test_android_remote_backend_requires_https",
        "test_android_does_not_start_native_spot_runtime",
        "test_active_backend_uses_futures_and_legacy_spot_is_not_authoritative",
    ):
        must(remote, needle, "Android remote contract")

    testnet = read("test_testnet_release_gate.py")
    for needle in (
        "BinanceFuturesClient",
        "demo-fapi.binance.com",
        "USD-M Futures Demo",
        "reduce-only",
    ):
        must(testnet, needle, "Futures Testnet release gate")
    must_not(testnet, "BinanceSpotClient", "Futures Testnet release gate")
    must_not(testnet, "Spot Testnet", "Futures Testnet release gate")

    for path in (
        ".github/workflows/python-ci.yml",
        ".github/workflows/campaign-ci.yml",
        ".github/workflows/android-apk.yml",
    ):
        workflow = read(path)
        must(workflow, "ci_release_gate.py", path)
    workflow = read(".github/workflows/python-ci.yml")
    must(workflow, "futures_williams_runtime.py", ".github/workflows/python-ci.yml")
    must(workflow, "python -m py_compile", ".github/workflows/python-ci.yml")

    print("WILLIAMS FUTURES RELEASE CONTRACT GATE: PASS")


if __name__ == "__main__":
    main()
