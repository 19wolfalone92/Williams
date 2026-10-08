#!/usr/bin/env python3
"""Fast, stdlib-only release gate for the Williams repository."""
# Autonomous Android runtime contract is validated alongside the Python release gate.
from __future__ import annotations
import ast
from pathlib import Path

ROOT = Path(__file__).resolve().parent
EXPECTED_VERSION = '4.24.0'

def read(path: str) -> str:
    p = ROOT / path
    assert p.is_file(), f"missing required file: {path}"
    return p.read_text(encoding="utf-8", errors="replace")

def must(text: str, needle: str, where: str) -> None:
    assert needle in text, f"{where}: missing {needle!r}"

def must_not(text: str, needle: str, where: str) -> None:
    assert needle not in text, f"{where}: forbidden {needle!r}"

def main() -> None:
    for path in (
        "trading_config.py", "market_scanner.py", "portfolio_controller.py",
        "portfolio_trader.py", "trader.py", "server.py", "wave_engine.py",
        "execution_barrier.py", "order_state_machine.py", "pending_signal.py",
        "decision_trace.py", "recovery_matrix.py", "stop_engine.py",
        "binance_client.py", "preflight_gate.py", "test_testnet_release_gate.py",
    ):
        ast.parse(read(path), filename=path)

    env = read(".env.example")
    for needle in (
        "TESTNET=true", "ALLOW_LIVE=false", "MAX_OPEN_POSITIONS=5",
        "AUTO_SCAN_SYMBOLS=", "SCAN_ALL_USDT=true", "SCAN_MAX_SYMBOLS=0",
        "LIQUIDITY_PRESELECT=0", "WAVE_FULL_TF_ALL=true",
    ):
        must(env, needle, ".env.example")

    cfg = read("trading_config.py")
    must(cfg, "max_open_positions: int = 5", "trading_config.py")
    must(cfg, 'MAX_OPEN_POSITIONS", 5', "trading_config.py")
    must(cfg, '{"ALL", "AUTO", "*"}', "trading_config.py")
    must(cfg, 'risk_key = "MAX_RISK_PER_TRADE_PCT"', "trading_config.py")
    must(cfg, 'min(0.005', "trading_config.py")
    must(cfg, 'min(0.01', "trading_config.py")

    scanner = read("market_scanner.py")
    for needle in (
        'os.getenv("SCAN_ALL_USDT", "true")',
        'os.getenv("SCAN_MAX_SYMBOLS", "0")',
        'os.getenv("LIQUIDITY_PRESELECT", "0")',
        'os.getenv("SCAN_WORKERS", "4")',
    ):
        must(scanner, needle, "market_scanner.py")

    trader = read("trader.py")
    must(trader, "os.getenv('AUTO_SCAN_SYMBOLS', '').strip()", "trader.py")
    must(trader, "SCAN_THROTTLED", "trader.py")
    portfolio_trader = read("portfolio_trader.py")
    must(portfolio_trader, 'os.getenv("CAMPAIGN_ENGINE", "true")', "portfolio_trader.py")
    must(trader, "_auto_scan_lock", "trader.py")

    server = read("server.py")
    must(server, f"VERSION = '{EXPECTED_VERSION}'", "server.py")

    android = read("app/src/main/java/com/williamsbot/MainActivity.kt")
    must(android, f"Williams {EXPECTED_VERSION}", "MainActivity.kt")
    for needle in (
        "Top 50 liquid USDT", "1D / 4H / 1H / 15M",
        '.putString("api_key"', '.putString("api_secret"',
    ):
        must_not(android, needle, "MainActivity.kt")
    must(android, "TradingForegroundService", "MainActivity.kt")
    must(android, "http://127.0.0.1:18080", "MainActivity.kt")
    gradle = read("app/build.gradle.kts")
    must(gradle, f'versionName = "{EXPECTED_VERSION}"', "app/build.gradle.kts")
    must(android, "val maxOpenPositions: Int = 5", "MainActivity.kt")
    runtime = read("app/src/main/java/com/williamsbot/StandaloneRuntime.kt")
    must(runtime, 'prefs.getInt("max_open_positions", 5)', "StandaloneRuntime.kt")
    must(runtime, "private val maxSlippagePct = 0.0015", "StandaloneRuntime.kt")
    must(runtime, "control/self-heal", "StandaloneRuntime.kt")
    must(runtime, "activeHistoryTasks", "StandaloneRuntime.kt")
    must(runtime, "campaignEngineEnabled", "StandaloneRuntime.kt")
    must(runtime, 'type=STOP_LOSS', "StandaloneRuntime.kt")

    remote = read("test_android_remote_only.py")
    for needle in (
        "test_android_has_autonomous_runtime",
        "test_android_local_runtime_does_not_require_remote_https",
        "test_native_runtime_uses_binance_spot_testnet",
        "test_android_has_portfolio_local_api",
    ):
        must(remote, needle, "Android autonomous contract")

    testnet = read("test_testnet_release_gate.py")
    must(testnet, "if not live_e2e_enabled():", "testnet release gate")
    must(testnet, "Binance Spot Testnet", "testnet release gate")

    android_ci = read(".github/workflows/android-apk.yml")
    must(android_ci, "python3 ci_release_gate.py", "android workflow")
    must(android_ci, "group: android-${{ github.workflow }}-${{ github.ref }}-${{ github.sha }}", "android workflow")
    must(android_ci, "gradle --no-daemon :app:lintDebug", "android workflow")
    must_not(android_ci, "./gradlew --no-daemon :app:", "android workflow")

    execution_barrier = read("execution_barrier.py")
    must(execution_barrier, "class ExecutionBarrier", "execution_barrier.py")
    must(execution_barrier, "OrderStateMachine", "execution_barrier.py")
    must(execution_barrier, "DecisionTrace", "execution_barrier.py")
    must(execution_barrier, "RECONCILE_REQUIRED", "execution_barrier.py")
    pending_signal = read("pending_signal.py")
    must(pending_signal, "class PendingSignal", "pending_signal.py")
    recovery = read("recovery_matrix.py")
    must(recovery, "EXPIRED_IN_MATCH", "recovery_matrix.py")
    python_ci = read(".github/workflows/python-ci.yml")
    must(python_ci, "test_execution_components.py", "python workflow")
    must(python_ci, "python3 ci_release_gate.py", "python workflow")
    must(python_ci, "group: backend-ci-${{ github.ref }}-${{ github.sha }}", "python workflow")

    testnet_ci = read(".github/workflows/testnet-readonly.yml")
    must(testnet_ci, "python3 ci_release_gate.py", "testnet workflow")
    must(testnet_ci, 'ALLOW_LIVE: "false"', "testnet workflow")
    must(testnet_ci, 'TESTNET: "true"', "testnet workflow")
    must(testnet_ci, "test_testnet_release_gate.py --read-only", "testnet workflow")

    print("WILLIAMS RELEASE CONTRACT GATE: PASS")

if __name__ == "__main__":
    main()
