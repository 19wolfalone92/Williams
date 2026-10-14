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
        "test_williams_causality_contract.py",
        "test_testnet_release_gate.py",
        "risk_engine.py",
        "test_risk_engine.py",
    )
    for path in core_files:
        ast.parse(read(path), filename=path)

    # P09 must execute real authenticated market-data checks, not be represented
    # only by an offline checklist label.
    testnet_gate = read("test_testnet_release_gate.py")
    for needle in (
        "def _validate_h1_market_snapshot",
        'client.mark_price(item)',
        'client.klines(item, "1h", limit=3)',
        "market_data_checked",
        "test_read_only_market_gate_rejects_stale_mark_price",
        "test_read_only_market_gate_rejects_stale_closed_h1_candle",
        "test_read_only_market_gate_rejects_inconsistent_ohlc",
    ):
        must(testnet_gate, needle, "test_testnet_release_gate.py")

    env = read(".env.example")
    for needle in (
        "TESTNET=true",
        "ALLOW_LIVE=false",
        "DRY_RUN=true",
        "MAX_DAILY_LOSS_PCT=0.03",
        "RISK_PER_TRADE_PCT=0.0025",
        "MAX_CONSECUTIVE_LOSSES=2",
        "BINANCE_MARKET=futures_usdt",
        "FUTURES_LEVERAGE=2",
        "FUTURES_MARGIN_TYPE=ISOLATED",
        "FUTURES_FORCE_ONE_WAY=true",
        "FUTURES_APPROVED_SYMBOLS=BTCUSDT,ETHUSDT,BNBUSDT,SOLUSDT,XRPUSDT,DOGEUSDT,ADAUSDT,LINKUSDT,AVAXUSDT,LTCUSDT",
        "WILLIAMS_SYMBOLS=BTCUSDT,ETHUSDT,BNBUSDT,SOLUSDT,XRPUSDT,DOGEUSDT,ADAUSDT,LINKUSDT,AVAXUSDT,LTCUSDT",
        "EXECUTION_TIMEFRAME=1h",
        "STRUCTURAL_TIMEFRAMES=1d,4h,1h,15m",
        "ALLOW_LONG=true",
        "ALLOW_SHORT=true",
        "TAKE_PROFIT_PCT=0.0",
        "CAMPAIGN_TRAIL_BARS=5",
        "MAX_OPEN_POSITIONS=1",
        "MAX_TOTAL_RISK_PCT=0.006",
        "MAX_RISK_PER_TRADE_PCT=0.0025",
    ):
        must(env, needle, ".env.example")

    cfg = read("trading_config.py")
    must(cfg, "WILLIAMS_SYMBOLS must match the approved ten-symbol Futures universe", "trading_config.py")
    must(cfg, "AUTO_SCAN_SYMBOLS cannot override the fixed Futures universe", "trading_config.py")
    must(cfg, "EXECUTION_TIMEFRAME must be H1", "trading_config.py")
    must(cfg, "STRUCTURAL_TIMEFRAMES must be D1,H4,H1,M15", "trading_config.py")
    must(cfg, "min(2, _int(source, \"FUTURES_LEVERAGE\", 2))", "trading_config.py")
    must(cfg, 'FUTURES_MARGIN_TYPE must be ISOLATED', "trading_config.py")
    must(cfg, 'FUTURES_FORCE_ONE_WAY must remain enabled', "trading_config.py")
    must(cfg, 'max_daily_loss_pct=max(0.0, min(0.03,', "trading_config.py")
    must(cfg, "futures_leverage=futures_leverage", "trading_config.py")
    must(cfg, "futures_margin_type=futures_margin_type", "trading_config.py")
    must(cfg, "futures_force_one_way=futures_force_one_way", "trading_config.py")
    for needle in (
        'market: str = "futures_usdt"',
        "futures_leverage: int = 2",
        'futures_margin_type: str = "ISOLATED"',
        "futures_force_one_way: bool = True",
        "allow_long: bool = True",
        "allow_short: bool = True",
        'market = str(source.get("BINANCE_MARKET", "futures_usdt"))',
        'risk_key = "MAX_RISK_PER_TRADE_PCT"',
        "min(0.0025",
        "min(0.03",
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

    risk = read("risk_engine.py")
    must(
        risk,
        "structural invalidation stop is required; ATR fallback is forbidden",
        "risk_engine.py",
    )
    must(risk, "stop_price = structural_stop", "risk_engine.py")
    must(risk, "max_daily_loss_pct: float = 0.03", "risk_engine.py")
    must(risk, "min(0.03, raw_daily_loss)", "risk_engine.py")
    must_not(risk, "entry - fallback_stop_distance", "risk_engine.py")
    must_not(risk, "entry + fallback_stop_distance", "risk_engine.py")

    causality_test = read("test_williams_causality_contract.py")
    for needle in (
        "def test_executable_indicator_values_are_prefix_causal",
        "def test_fractal_confirmation_is_not_backdated_to_the_pivot",
        "def test_canonical_futures_contract_keeps_both_directions_and_exact_universe",
        "confirmed_up_level",
        "confirmed_down_level",
    ):
        must(causality_test, needle, "test_williams_causality_contract.py")

    strategy = read("strategy.py")
    for needle in (
        "def canonical_indicator_config_from_env(env=os.environ)",
        "CANONICAL_WILLIAMS_INDICATOR_PARAMS",
        "Non-canonical Williams indicator parameters are blocked",
        "def _profitunity_window(volume_up, mfi_up, valid=None)",
        '"fractal_up_confirmed"',
        '"confirmed_up_center_index"',
        '"confirmed_up_group_start_index"',
        '"mfi_proxy_valid"',
        '"profitunity_macd_line"',
        '"profitunity_macd_signal"',
        '"profitunity_macd_histogram"',
        '"gator_upper"',
        '"gator_lower"',
        '"gator_state"',
        '"balance_line"',
        '"balance_line_side"',
        '"UNKNOWN"',
    ):
        must(strategy, needle, "strategy.py")

    signals = read("williams_signals.py")
    for needle in (
        "def extract_long_signal_specs",
        "def extract_short_signal_specs",
        "def _latest_super_ao",
        "def _latest_reversal",
        "confirmed_up_center_index",
        "confirmed_up_group_start_index",
        "fractal_up_confirmed",
        "side=side",
    ):
        must(signals, needle, "williams_signals.py")

    futures = read("futures_williams_runtime.py")
    must(futures, "canonical_indicator_config_from_env()", "futures_williams_runtime.py")
    must(futures, "_signal_profile_entry_block_reason(profile)", "futures_williams_runtime.py")
    must(futures, "def _signal_profile_entry_block_reason(profile: str) -> str", "futures_williams_runtime.py")
    must(futures, "the standalone Balance Line trade engine is missing/unverified", "futures_williams_runtime.py")
    must(futures, "default_symbols = DEFAULT_FUTURES_SYMBOLS", "futures_williams_runtime.py")
    must(futures, "tuple(approved) != DEFAULT_FUTURES_SYMBOLS", "futures_williams_runtime.py")
    must(futures, '("LONG", long_specs, long_htf)', "futures_williams_runtime.py")
    must(futures, '("SHORT", short_specs, short_htf)', "futures_williams_runtime.py")
    must(futures, '_env_bool("ALLOW_LONG", True)', "futures_williams_runtime.py")
    must(futures, '_env_bool("ALLOW_SHORT", True)', "futures_williams_runtime.py")
    must(futures, 'result.sort(key=lambda c: (-c.score, -c.signal.signal_bar_time_ms, c.symbol, c.side))', "futures_williams_runtime.py")
    must(futures, "self.config.futures_leverage", "futures_williams_runtime.py")
    must(futures, "self.config.futures_margin_type", "futures_williams_runtime.py")
    must(futures, "portfolio_risk_limit_pct=self.config.max_total_risk_pct", "futures_williams_runtime.py")
    must(futures, "campaign_risk_limit_pct=self.config.campaign_risk_limit_pct", "futures_williams_runtime.py")
    must(futures, "self.max_open_positions = self.config.max_open_positions", "futures_williams_runtime.py")
    must(futures, 'self.htf_interval = os.getenv("HTF_INTERVAL", "4h").strip().lower()', "futures_williams_runtime.py")
    must(futures, 'HTF_INTERVAL must be H4 for the canonical Williams campaign', "futures_williams_runtime.py")
    must_not(futures, 'int(os.getenv("MAX_OPEN_POSITIONS"', "futures_williams_runtime.py")
    must_not(futures, 'os.getenv("FUTURES_LEVERAGE"', "futures_williams_runtime.py")
    must_not(futures, 'os.getenv("FUTURES_MARGIN_TYPE"', "futures_williams_runtime.py")
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
        "/fapi/v2/account",
        "positionAmt",
        "reduceOnly",
        "STOP_MARKET",
    ):
        must(client, needle, "binance_futures_client.py")

    server = read("server.py")
    must(server, '"symbols_configured": int(scanner_cache["symbols_configured"])', "server.py")
    must(server, "'scanner_universe': list(t.config.symbols)", "server.py")
    must(server, '"long_candidates": int(scanner_cache["long_candidates"])', "server.py")
    must(server, '"short_candidates": int(scanner_cache["short_candidates"])', "server.py")
    must(server, "'futures_leverage': t.config.futures_leverage", "server.py")
    must(server, "'futures_margin_type': t.config.futures_margin_type", "server.py")
    must(server, "'allow_long': t.config.allow_long", "server.py")
    must(server, "'allow_short': t.config.allow_short", "server.py")
    for needle in (
        "FuturesWilliamsRuntime",
        "BinanceFuturesClient",
        "BINANCE_MARKET",
        "USDⓈ-M Futures",
    ):
        must(server, needle, "server.py")

    legacy_runtime = read("app/src/main/java/com/williamsbot/StandaloneRuntime.kt")
    must(legacy_runtime, '"DOGEUSDT", "ADAUSDT", "LINKUSDT", "AVAXUSDT", "LTCUSDT"', "StandaloneRuntime.kt")
    must(legacy_runtime, "private val maxScanSymbols = 10", "StandaloneRuntime.kt")
    must(legacy_runtime, "in-process Futures Runtime API; no remote execution dependency", "StandaloneRuntime.kt")
    must(legacy_runtime, "private val executionStore = FuturesExecutionStore(context)", "StandaloneRuntime.kt")
    must(legacy_runtime, "wss://demo-fstream.binance.com/market/stream?streams=", "StandaloneRuntime.kt")
    must(legacy_runtime, "wss://demo-fstream.binance.com/public/stream?streams=", "StandaloneRuntime.kt")
    must(legacy_runtime, "wss://demo-fstream.binance.com/private/ws", "StandaloneRuntime.kt")
    must(read("app/src/main/java/com/williamsbot/BinanceUserDataStream.kt"), '&events=" + EVENT_TYPES', "BinanceUserDataStream.kt")
    must(read("app/src/main/java/com/williamsbot/BinanceUserDataStream.kt"), "ORDER_TRADE_UPDATE,ACCOUNT_UPDATE,ALGO_UPDATE,ACCOUNT_CONFIG_UPDATE,CONDITIONAL_ORDER_TRIGGER_REJECT,listenKeyExpired", "BinanceUserDataStream.kt")
    must(legacy_runtime, "marketWebSocketFresh()", "StandaloneRuntime.kt")
    must(legacy_runtime, "publicWebSocketFresh()", "StandaloneRuntime.kt")
    must(legacy_runtime, "userStreamConnected &&", "StandaloneRuntime.kt")
    must(legacy_runtime, "!userStreamSyncRequired", "StandaloneRuntime.kt")
    futures_user_stream = read("app/src/main/java/com/williamsbot/BinanceUserDataStream.kt")
    must(futures_user_stream, '"/fapi/v1/listenKey"', "BinanceUserDataStream.kt")
    must_not(futures_user_stream, "userDataStream.subscribe.signature", "BinanceUserDataStream.kt")
    must(futures_user_stream, 'webSocket.close(1011, "event_handler_failed")', "BinanceUserDataStream.kt")
    must(futures_user_stream, "closed.countDown()", "BinanceUserDataStream.kt")
    must(futures_user_stream, "private val restartRequested = AtomicBoolean(false)", "BinanceUserDataStream.kt")
    must(futures_user_stream, "private fun workerExited(finishedWorker: Thread)", "BinanceUserDataStream.kt")
    must(futures_user_stream, "if (stopping.get()) restartRequested.set(true)", "BinanceUserDataStream.kt")

    # OkHttp Kotlin accessors are compiler errors under the current Kotlin toolchain.
    for path in (
        "app/src/main/java/com/williamsbot/BinanceUsdMFuturesMarketData.kt",
        "app/src/main/java/com/williamsbot/BinanceUsdMFuturesTestnetAdapter.kt",
        "app/src/test/java/com/williamsbot/BinanceUsdMFuturesMarketDataTest.kt",
    ):
        source = read(path)
        for obsolete in ("HttpUrl.parse(", "response.body()", "response.code()", "MediaType.parse(", "ResponseBody.create(", "chain.request().url()"):
            must_not(source, obsolete, path)

    runtime_source = read("app/src/main/java/com/williamsbot/StandaloneRuntime.kt")
    signed_account = runtime_source.split("private fun signedAccount(): JSONObject =", 1)[1].split(
        "private fun signedRequest(", 1
    )[0]
    must(signed_account, 'path = "/fapi/v3/account"', "StandaloneRuntime.kt signed account")
    must_not(signed_account, 'path = "/api/v3/account"', "StandaloneRuntime.kt signed account")
    must(runtime_source, "internal fun conservativeRiskReservation", "StandaloneRuntime.kt")
    must(runtime_source, "futuresMarketData.isCandleClosed(closeTime, exchangeNowMs)", "StandaloneRuntime.kt")
    must(runtime_source, "isCandleOpenTimeClosed(it.t, durationMs, exchangeNowMs)", "StandaloneRuntime.kt")
    must(runtime_source, "var endTime = futuresMarketData.exchangeNowMs()", "StandaloneRuntime.kt")
    must_not(
        runtime_source,
        "((it.entry - it.stop) / it.entry).coerceAtLeast(0.0)",
        "StandaloneRuntime.kt",
    )
    must_not(
        runtime_source,
        "((p.entry - p.stop) / p.entry).coerceAtLeast(0.0)",
        "StandaloneRuntime.kt",
    )
    must_not(
        runtime_source,
        "if (closeTime >= System.currentTimeMillis())",
        "StandaloneRuntime.kt",
    )
    market_test = read("app/src/test/java/com/williamsbot/BinanceUsdMFuturesMarketDataTest.kt")
    must(market_test, "signalFreshnessUsesExchangeNowNotDecisionCandleOpen", "BinanceUsdMFuturesMarketDataTest.kt")
    must(market_test, "closedCandleBoundaryUsesExchangeTimeAndNeverAdmitsTheFormingCandle", "BinanceUsdMFuturesMarketDataTest.kt")
    must(market_test, "liveCandleOpenTimeFilterNeverReturnsFormingOrFutureCandles", "BinanceUsdMFuturesMarketDataTest.kt")
    risk_test = read("app/src/test/java/com/williamsbot/ReservedRiskBudgetTest.kt")
    for needle in (
        "validStoredRiskReservationIsPreservedWithinPortfolioCap",
        "missingOrInvalidRiskReservationConsumesTheRemainingCampaignBudget",
        "invalidPortfolioCapFailsClosed",
    ):
        must(risk_test, needle, "ReservedRiskBudgetTest.kt")
    must(runtime_source, "FUTURES_DAILY_BASELINE_UNKNOWN", "StandaloneRuntime.kt")
    must(runtime_source, "canInitializeDailyRiskBaseline", "StandaloneRuntime.kt")
    must(runtime_source, "observeFuturesEquityFromPersistedBaseline", "StandaloneRuntime.kt")
    must(runtime_source, "AUTOMATIC FUTURES EQUITY CIRCUIT BREAKER", "StandaloneRuntime.kt")
    must(runtime_source, 'persistSafetyFlag("kill_latched", true)', "StandaloneRuntime.kt")
    must(runtime_source, "5% Futures equity circuit breaker tripped", "StandaloneRuntime.kt")
    must(runtime_source, "internal fun futuresAccountEquity", "StandaloneRuntime.kt")
    must(runtime_source, "internal fun isSignalFresh", "StandaloneRuntime.kt")
    must(runtime_source, "internal fun smmaSeededValues", "StandaloneRuntime.kt")
    must(runtime_source, "internal fun awesomeOscillatorAt", "StandaloneRuntime.kt")
    must(runtime_source, "williamsAlligatorLine(candles, 13, 8)", "StandaloneRuntime.kt")
    must(runtime_source, "williamsAlligatorLine(candles, 8, 5)", "StandaloneRuntime.kt")
    must(runtime_source, "williamsAlligatorLine(candles, 5, 3)", "StandaloneRuntime.kt")
    must_not(runtime_source, "williamsAlligatorCurrentLine", "StandaloneRuntime.kt")
    must(runtime_source, "val slowValues = medianPrices.subList(index - 33, index + 1)", "StandaloneRuntime.kt")
    must_not(runtime_source, "medians.subList(index - 34, index + 1).average()", "StandaloneRuntime.kt")
    indicator_test = read("app/src/test/java/com/williamsbot/WilliamsIndicatorParityTest.kt")
    for needle in (
        "smmaUsesTheFirstPeriodSmaAsItsSeed",
        "displayedLineAtCurrentCandleUsesOnlyPastSmmaValue",
        "awesomeOscillatorUsesExactlyThirtyFourAndFiveMedianPrices",
        "invalidPeriodIsRejectedAndNonFiniteWindowsFailClosed",
    ):
        must(indicator_test, needle, "WilliamsIndicatorParityTest.kt")

    must(runtime_source, "isSignalFresh(signal.signalBarTimeMs, contextNow, 5L * 60L * 60L * 1000L)", "StandaloneRuntime.kt")
    must(runtime_source, "private fun estimateManagedEquity(): Double =\n        liveFuturesEquity", "StandaloneRuntime.kt")
    equity_test = read("app/src/test/java/com/williamsbot/FuturesAccountEquityTest.kt")
    for needle in (
        "prefersAuthoritativeTotalMarginBalance",
        "fallsBackToWalletBalancePlusUnrealizedPnlWhenMarginBalanceMissing",
        "rejectsMissingNonFiniteOrNonPositiveEquity",
    ):
        must(equity_test, needle, "FuturesAccountEquityTest.kt")
    must_not(runtime_source, "private fun checkAutomaticCircuitBreaker()", "StandaloneRuntime.kt")
    coordinator_source = read("app/src/main/java/com/williamsbot/NativeFuturesCampaignCoordinator.kt")
    must(coordinator_source, "fun incomeHistory(startTimeMs: Long, endTimeMs: Long): JSONArray", "NativeFuturesCampaignCoordinator.kt")
    must(coordinator_source, "isOwnedProtectionClientId(cid, currentProtectionId, previousProtectionId)", "NativeFuturesCampaignCoordinator.kt")
    must(coordinator_source, "internal fun isManagedOpenPositionIntent", "NativeFuturesCampaignCoordinator.kt")
    must(coordinator_source, "fun isManagedOpenPosition(symbol: String, direction: Direction)", "NativeFuturesCampaignCoordinator.kt")
    must(coordinator_source, 'intent.put("campaign_status", "CLOSED_OR_STOPPED")', "NativeFuturesCampaignCoordinator.kt")
    must(coordinator_source, "campaignLifecycleStatus(intent)", "NativeFuturesCampaignCoordinator.kt")
    must(coordinator_source, "status in setOf(\"OPEN_PROTECTED\", \"POSITION_OPEN\", \"EXIT_PENDING\")", "NativeFuturesCampaignCoordinator.kt")
    must(coordinator_source, '.put("order_status", exchangeStatus)', "NativeFuturesCampaignCoordinator.kt")
    must(coordinator_source, "isOwnedClientOrderForSymbol", "NativeFuturesCampaignCoordinator.kt")
    must(coordinator_source, "if (finalOrderStatus != \"FILLED\")", "NativeFuturesCampaignCoordinator.kt")
    must(coordinator_source, "has no confirmed active Williams campaign; refusing to close an unmanaged position", "NativeFuturesCampaignCoordinator.kt")
    must(coordinator_source, "existing exit intent is active/ambiguous; reconcile before any new close request", "NativeFuturesCampaignCoordinator.kt")
    must(coordinator_source, "Live " , "NativeFuturesCampaignCoordinator.kt")
    must(coordinator_source, "Unmanaged close-position protective stop remains open", "NativeFuturesCampaignCoordinator.kt")
    protection_test = read("app/src/test/java/com/williamsbot/ProtectionOwnershipTest.kt")
    must(protection_test, "onlyCurrentOrExplicitlyTrackedPredecessorProtectionIsBotOwned", "ProtectionOwnershipTest.kt")
    must(protection_test, "emergencyCloseRequiresCurrentProtectedCampaignAndMatchingDirection", "ProtectionOwnershipTest.kt")
    baseline_test = read("app/src/test/java/com/williamsbot/DailyRiskBaselineTest.kt")
    for needle in (
        "baselineCanBeInitializedForFlatIdleAccountWithNoTradingIncome",
        "baselineCannotResetAfterOpenPositionPendingExecutionOrTradingPnl",
    ):
        must(baseline_test, needle, "DailyRiskBaselineTest.kt")
    must(legacy_runtime, '.put("ok", running && !reconcileRequired && !killLatched)', "StandaloneRuntime.kt")
    must(legacy_runtime, 'userStreamSyncRequired = true', "StandaloneRuntime.kt")
    must(legacy_runtime, 'daily_loss=0.03', "StandaloneRuntime.kt")

    android = read("app/src/main/java/com/williamsbot/MainActivity.kt")
    for needle in (
        f"Williams {EXPECTED_VERSION}",
        "class LocalRuntimeApi",
        "StandaloneRuntime.request",
        "TradingForegroundService.ACTION_START",
    ):
        must(android, needle, "MainActivity.kt")
    must_not(android, "class BackendApi", "MainActivity.kt")
    must_not(android, "https://", "MainActivity.kt")
    must_not(android, 'putString("api_key"', "MainActivity.kt")
    must_not(android, 'putString("api_secret"', "MainActivity.kt")
    must_not(android, "Backend URL", "MainActivity.kt")
    must(android, "isIgnoringBatteryOptimizations", "MainActivity.kt")
    must(android, "ACTION_APPLICATION_DETAILS_SETTINGS", "MainActivity.kt")
    for needle in (
        "val riskPerTrade: Double = 0.0025",
        "val maxDailyLoss: Double = 0.03",
        "val maxOpenPositions: Int = 1",
        "val maxTotalRiskPct: Double = 0.006",
    ):
        must(android, needle, "MainActivity.kt")

    service = read("app/src/main/java/com/williamsbot/TradingForegroundService.kt")
    for needle in (
        "StandaloneRuntime.start(applicationContext)",
        "StandaloneRuntime.autostart(applicationContext)",
        "StandaloneRuntime.stopTrading()",
    ):
        must(service, needle, "TradingForegroundService.kt")
    must_not(service, "remote Futures backend", "TradingForegroundService.kt")
    must_not(service, "legacy native Spot runtime", "TradingForegroundService.kt")

    # Device reboot/update recovery must respect the user's persisted opt-in.
    manifest = read("app/src/main/AndroidManifest.xml")
    for needle in (
        "android.permission.RECEIVE_BOOT_COMPLETED",
        'android:name=".TradingBootReceiver"',
        "android.intent.action.BOOT_COMPLETED",
        "android.intent.action.MY_PACKAGE_REPLACED",
        'android:exported="false"',
    ):
        must(manifest, needle, "AndroidManifest.xml boot recovery")
    boot_receiver = read("app/src/main/java/com/williamsbot/TradingBootReceiver.kt")
    for needle in (
        "Intent.ACTION_BOOT_COMPLETED",
        "Intent.ACTION_MY_PACKAGE_REPLACED",
        'getBoolean("auto_run", false)',
        "ContextCompat.startForegroundService",
        "TradingForegroundService.ACTION_START",
        "Fail closed: do not resume trading if the persisted opt-in cannot be read",
    ):
        must(boot_receiver, needle, "TradingBootReceiver.kt")

    android_contract = read("test_android_remote_only.py")
    for needle in (
        "test_android_starts_native_futures_runtime_from_foreground_service",
        "test_android_boot_recovery_is_opt_in_and_keeps_reconciliation_gate",
        "test_android_exposes_battery_policy_diagnostic",
        "test_android_ui_routes_directly_to_local_runtime_without_remote_backend",
        "test_android_market_socket_uses_futures_demo_endpoint_and_gates_entries",
        "test_android_campaign_and_intents_are_sqlite_backed",
        "test_android_reconcile_and_kill_latches_are_sqlite_durable",
        "test_android_corrupt_persisted_campaign_state_fails_closed",
        "test_android_reversal_htf_gate_matches_python_campaign_contract",
        "test_android_daily_loss_is_rechecked_at_order_admission",
        "test_android_signed_execution_boundary_rejects_legacy_spot_routes",
        "test_android_user_stream_is_not_the_spot_signed_subscription_protocol",
        "test_android_private_futures_stream_uses_supported_event_query_delimiters",
        "test_android_campaign_lifecycle_is_separate_from_exchange_order_status",
        "test_android_exit_requires_exchange_position_protection_and_filled_order_confirmation",
        "test_android_protection_must_match_owner_id_and_exact_trigger",
        "test_android_trailing_stop_retains_confirmed_protection_after_ambiguous_replacement",
        "test_android_trailing_stops_are_mark_relative_not_entry_relative",
        "test_android_diagnostics_do_not_fake_self_heal_readiness",
    ):
        must(android_contract, needle, "Android autonomous runtime contract")

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

    # The authenticated preflight is read-only and runs on campaign-branch pushes,
    # as well as retaining the explicit manual dispatch option.
    testnet_workflow = read(".github/workflows/testnet-readonly.yml")
    must(testnet_workflow, "push:", ".github/workflows/testnet-readonly.yml")
    must(testnet_workflow, "branches: [futures-williams-campaign]", ".github/workflows/testnet-readonly.yml")
    must(testnet_workflow, "workflow_dispatch:", ".github/workflows/testnet-readonly.yml")
    must(testnet_workflow, "WILLIAMS_TESTNET_API_KEY", ".github/workflows/testnet-readonly.yml")
    must(testnet_workflow, "WILLIAMS_TESTNET_API_SECRET", ".github/workflows/testnet-readonly.yml")
    must(testnet_workflow, "WILLIAMS_TESTNET_E2E: \"1\"", ".github/workflows/testnet-readonly.yml")
    must(testnet_workflow, "inputs.symbol || 'BTCUSDT'", ".github/workflows/testnet-readonly.yml")
    must(testnet_workflow, "python test_testnet_release_gate.py --read-only", ".github/workflows/testnet-readonly.yml")
    must_not(testnet_workflow, "python test_testnet_release_gate.py --trade", ".github/workflows/testnet-readonly.yml")

    for path in (".github/workflows/python-ci.yml", ".github/workflows/campaign-ci.yml"):
        workflow = read(path)
        for test_path in (
            "tests/test_futures_campaign_risk_defaults.py",
            "tests/test_futures_risk_policy_contract.py",
            "tests/test_risk_engine_daily_loss_cap.py",
        ):
            must(workflow, test_path, path)
    workflow = read(".github/workflows/python-ci.yml")
    must(workflow, "futures_williams_runtime.py", ".github/workflows/python-ci.yml")
    must(workflow, "python -m py_compile", ".github/workflows/python-ci.yml")

    print("WILLIAMS FUTURES RELEASE CONTRACT GATE: PASS")


if __name__ == "__main__":
    main()
