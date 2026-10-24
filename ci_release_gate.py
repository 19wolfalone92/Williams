#!/usr/bin/env python3
"""Fast, stdlib-only release contract gate for Williams Futures."""
from __future__ import annotations

import ast
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent
EXPECTED_VERSION = (ROOT / "VERSION").read_text(encoding="utf-8").strip()


def read(path: str) -> str:
    p = ROOT / path
    assert p.is_file(), f"missing required file: {path}"
    return p.read_text(encoding="utf-8", errors="replace")


def must(text: str, needle: str, where: str) -> None:
    assert needle in text, f"{where}: missing {needle!r}"


def must_not(text: str, needle: str, where: str) -> None:
    assert needle not in text, f"{where}: forbidden {needle!r}"


def main() -> None:
    assert re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", EXPECTED_VERSION), "invalid release VERSION"
    gradle_version = re.search(r'versionName\s*=\s*"([^"]+)"', read("app/build.gradle.kts"))
    assert gradle_version and gradle_version.group(1) == EXPECTED_VERSION, "Android release version mismatch"
    server_versions = [ast.literal_eval(node.value) for node in ast.parse(read("server.py")).body
                       if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == "VERSION"
                                                               for t in node.targets)]
    assert server_versions == [EXPECTED_VERSION], "Python API release version mismatch"
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
    must(cfg, 'parse_leverage(source.get("FUTURES_LEVERAGE", "2"))', "trading_config.py")
    must(cfg, 'FUTURES_SYMBOL_LEVERAGES', "trading_config.py")
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
        "trigger <= float(current_teeth)",
        "trigger >= float(current_teeth)",
        "current_trigger_valid = trigger > max(current_teeth, 0.0)",
        "current_trigger_valid = current_teeth > 0 and trigger < current_teeth",
        "trigger<=current_teeth if is_long else trigger>=current_teeth",
        "side=side",
    ):
        must(signals, needle, "williams_signals.py")

    geometry=read("williams_market_geometry.py")
    for needle in ("def angulation_evidence", "def alligator_phases", "def ntd_initial_context",
                   "MOUTH_ANCHORED_EDGE_SLOPES", "PRICE_PATH_NOT_STEEPER"):
        must(geometry,needle,"williams_market_geometry.py")
    # Original TC2/NTD texts compare the executed fractal STOP price against Teeth.
    # Prevent a reintroduction of Python/Android false-blocking on the pivot alone.
    must(geometry, "sign*(trigger-teeth[i])>0", "williams_market_geometry.py")
    must_not(signals, "trigger_base <= float(current_teeth)", "williams_signals.py")
    must_not(signals, "trigger_base >= float(current_teeth)", "williams_signals.py")
    futures = read("futures_williams_runtime.py")
    must(futures, "canonical_indicator_config_from_env()", "futures_williams_runtime.py")
    must(futures, "_signal_profile_entry_block_reason(profile)", "futures_williams_runtime.py")
    must(futures, "def _signal_profile_entry_block_reason(profile: str) -> str", "futures_williams_runtime.py")
    must(futures, 'payload.get("strategy_profile",TC2)!=campaign.tags.get("strategy_profile",TC2)', "futures_williams_runtime.py")
    must(futures, 'market_on_close_reason(', "futures_williams_runtime.py")
    coordinator_editions=read("app/src/main/java/com/williamsbot/NativeFuturesCampaignCoordinator.kt")
    must(coordinator_editions,'Addition campaign/profile identity mismatch',"NativeFuturesCampaignCoordinator.kt")
    must(coordinator_editions,'Market exposure requires an owned NTD close event or TC2 confirmed-flat reversal',"NativeFuturesCampaignCoordinator.kt")
    must(coordinator_editions,'if(isReversal) checkBookReversalDoor(plan,currentIntents,adapter,true)',"TC2 reversal final exchange door")
    must(coordinator_editions,'"TC2_REVERSAL_OLD_ORDERS_NOT_CLEANED_UP"',"TC2 reversal cleanup fence")
    reversal=read("app/src/main/java/com/williamsbot/WilliamsTc2Reversal.kt")
    for proof in ('"EXIT_CONFIRMED"','"FILLED"','"executed_qty"','"position_before_qty"',
                  '"reversal_owner_id"','"TC2_REVERSAL_TRIGGER_TOO_FAR_NO_CHASING"'):
        must(reversal,proof,"TC2 confirmed-flat reversal contract")
    must(coordinator_editions,'exchange quantity cannot be attributed',"NativeFuturesCampaignCoordinator.kt")
    must(futures, "default_symbols = DEFAULT_FUTURES_SYMBOLS", "futures_williams_runtime.py")
    must(futures, "tuple(approved) != DEFAULT_FUTURES_SYMBOLS", "futures_williams_runtime.py")
    must(futures, '("LONG",long_specs,long_htf)', "futures_williams_runtime.py")
    must(futures, '("SHORT",short_specs,short_htf)', "futures_williams_runtime.py")
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
        "/fapi/v3/account",
        "/fapi/v3/positionRisk",
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
    futures_user_stream_contract = read("app/src/main/java/com/williamsbot/BinanceUserDataStream.kt")
    must(futures_user_stream_contract, "FUTURES_USER_STREAM_EVENT_FILTER", "BinanceUserDataStream.kt")
    must(futures_user_stream_contract, "ORDER_TRADE_UPDATE/ACCOUNT_UPDATE/ALGO_UPDATE/ACCOUNT_CONFIG_UPDATE/CONDITIONAL_ORDER_TRIGGER_REJECT/listenKeyExpired", "BinanceUserDataStream.kt")
    must_not(futures_user_stream_contract, "ORDER_TRADE_UPDATE,ACCOUNT_UPDATE,ALGO_UPDATE", "BinanceUserDataStream.kt")
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
    # Public reads are allow-listed and the legacy signed transport is gone.
    # All signed account/order mutations use the dedicated USD-M Futures adapter.
    must(runtime_source, "normalizeFuturesPublicPath(path)", "StandaloneRuntime.kt Futures public route allowlist")
    must_not(runtime_source, "private fun signedRequest(", "StandaloneRuntime.kt legacy signed transport removed")
    must_not(runtime_source, "private fun signedAccount()", "StandaloneRuntime.kt legacy account transport removed")
    must_not(runtime_source, "/api/v3/", "StandaloneRuntime.kt must not contain Spot API routes")
    must_not(runtime_source, 'getBody("/api/v3/', "StandaloneRuntime.kt public reads must not use Spot routes")
    adapter_source = read("app/src/main/java/com/williamsbot/BinanceUsdMFuturesTestnetAdapter.kt")
    must(adapter_source, 'request("GET", "/fapi/v3/account"', "Futures adapter account endpoint")
    must(adapter_source, '"/fapi/v1/algoOrder"', "Futures adapter conditional order endpoint")
    must(adapter_source, '"/fapi/v1/order"', "Futures adapter regular order endpoint")
    route_test = read("app/src/test/java/com/williamsbot/BinanceUsdMFuturesMarketDataTest.kt")
    must(route_test, "legacySpotPublicRoutesAreRejectedInsteadOfTranslated", "Futures route allowlist regression test")
    must(runtime_source, "internal fun conservativeRiskReservation", "StandaloneRuntime.kt")
    must(runtime_source, "futuresMarketData.isCandleClosed(closeTime, exchangeNowMs)", "StandaloneRuntime.kt")
    must(runtime_source, "futuresMarketData.isKlineOpenTimeClosed(it.t, frame, exchangeNowMs)", "StandaloneRuntime.kt")
    must(runtime_source, "futuresMarketData.klineCloseTimeMs(candle.t, frame)", "StandaloneRuntime.kt")
    market_data_source = read("app/src/main/java/com/williamsbot/BinanceUsdMFuturesMarketData.kt")
    must(market_data_source, "internal fun klineCloseTimeMs(openTimeMs: Long, interval: String)", "BinanceUsdMFuturesMarketData.kt")
    must(market_data_source, 'if (interval == "1M")', "BinanceUsdMFuturesMarketData.kt")
    must(market_data_source, "Calendar.MONTH", "BinanceUsdMFuturesMarketData.kt")
    must(market_data_source, "internal fun isLastClosedCandleCurrent", "BinanceUsdMFuturesMarketData.kt")
    must(runtime_source, "futuresMarketData.isLastClosedCandleCurrent(last.t, frame, nowMs)", "StandaloneRuntime.kt interval-aware cache freshness")
    must(runtime_source, "APPROVED_FUTURES_KLINE_INTERVALS", "StandaloneRuntime.kt chart interval contract")
    # Old implementation restarted backward pagination at 'now' on every boot,
    # repeatedly downloading the same pages. Resumable archival data must use
    # the oldest DURABLY saved candle as a strict backward cursor.
    must(runtime_source, "val previousOldest = historyStore.oldest(normalizedSymbol, frame)", "StandaloneRuntime.kt resumable history cursor")
    must(runtime_source, "else futuresMarketData.exchangeNowMs()", "StandaloneRuntime.kt first history fetch")
    must(runtime_source, "endTime = oldestPageTime - 1L", "StandaloneRuntime.kt backward pagination")
    must(runtime_source, "startTime = newest + 1L", "StandaloneRuntime.kt forward gap catch-up")
    must(runtime_source, "private fun schedulePersistentHistoryBackfill", "StandaloneRuntime.kt asynchronous phone archival")
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
    # Super AO (WM2) is independent of the WM3/fractal filter; verify that
    # Android uses the actual third rising AO bar and rejects already-consumed
    # stop triggers instead of allowing a late entry after a scanner gap.
    must(runtime_source, "val signalIndex = candles.lastIndex - (streak - 3)", "StandaloneRuntime.kt WM2")
    must(runtime_source, "fun triggerStillPending(signalIndex: Int, trigger: Double): Boolean", "StandaloneRuntime.kt trigger chronology")
    must(runtime_source, "triggerStillPending(signalIndex, trigger)", "StandaloneRuntime.kt WM2 trigger")
    must(runtime_source, 'selectWilliamsPendingFractal("LONG",waveBars,teethS,tick)', "StandaloneRuntime.kt WM3 trigger")
    must(runtime_source, "triggerStillPending(i, trigger)", "StandaloneRuntime.kt WM1 trigger")
    must_not(runtime_source, "priorFractalValid", "StandaloneRuntime.kt WM2")
    must(runtime_source, "val rawWorkCandles = candles", "StandaloneRuntime.kt H1 decision series")
    must_not(runtime_source, "getOrElse { candles }", "StandaloneRuntime.kt H1 fallback")
    must(runtime_source, '.put("take_profit_pct", 0.0)', "StandaloneRuntime.kt canonical Futures settings")
    setup_source=read("app/src/main/java/com/williamsbot/WilliamsSetupRules.kt")
    # TC2 chapter 11 (PDF pp.147-149): validate the triggered price beyond
    # red Teeth, not the raw pivot before its +1/-1 tick breakout buffer.
    # The older pivot-based release string check incorrectly forbade this fix.
    for needle in ("trigger<=teeth.last()", "trigger>=teeth.last()", "if (value==p) start=j",
                   ".minOf { bars[it].l }-tick", "bars[it].h>=trigger", "bar.h<mouth.min()"):
        must(setup_source,needle,"WilliamsSetupRules.kt confirmed book setup")
    must_not(setup_source, "p<=teeth.last()", "TC2 breakout-vs-formation book contract")
    teeth_source=read("app/src/main/java/com/williamsbot/WilliamsBookH1EntryPolicy.kt")
    must(teeth_source, "if(entry>teeth)", "WM3 actual LONG breakout beyond current Teeth")
    must(teeth_source, "if(entry<teeth)", "WM3 actual SHORT breakout beyond current Teeth")
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

    hierarchy_source = read("app/src/main/java/com/williamsbot/WilliamsWaveHierarchy.kt")
    for needle in ("TIME_AND_PRICE_CONTAINMENT", "COMPETING_SENIOR_IMPULSE_UNRESOLVED",
                   "CORRECTION_HISTORY_INCOMPLETE", "MOMENTUM_FADE_BEFORE_PIVOT"):
        must(hierarchy_source, needle, "WilliamsWaveHierarchy.kt")
    for needle in ("planWilliamsCampaignStop(", "triggeredOpposingSignal(",
                   "CORRECTION_PARENT_INVALIDATED", "williamsCorrectionPriceAllowed("):
        must(runtime_source, needle, "StandaloneRuntime.kt causal wave/management integration")
    must(runtime_source,'exchangeClosed=k.optBoolean("x",false)',"StandaloneRuntime.kt final WS close proof")
    must(runtime_source,"val closed = closedNativeStrategyCandles(live)","StandaloneRuntime.kt closed-only strategy cache")
    must(runtime_source, "val freshSignals = candidate.campaignSignals.filter", "StandaloneRuntime.kt directional hypotheses")
    must(runtime_source, "williamsH1SignalTimeEligible(it.signalBarTimeMs,it.type,it.strategyProfile,contextNow)", "StandaloneRuntime.kt source-specific H1 validity")
    execution_policy = read("app/src/main/java/com/williamsbot/WilliamsExecutionPolicy.kt")
    must(execution_policy, "WILLIAMS_SOURCE_OBSERVATION_ONLY", "Source lifecycle admission policy")
    must(execution_policy, "H1_SNAPSHOT_STALE", "Structural validity still requires fresh H1 data")
    must(execution_policy, 'williamsExecutionProfiles = listOf(williamsTc2Profile)', 'TC2-only new exposure authority')
    must(execution_policy, 'williamsObserverProfiles = listOf(williamsNtdProfile,williamsCourseProfile)',
         'NTD and Course observation only')
    must(execution_policy, 'TC2_ONLY_NEW_EXPOSURE_V3', 'Source ownership policy version must revoke old NTD admission')
    must(coordinator_editions, 'williamsNewExposureBlocker(plan.strategyProfile)==null',
         'Exchange mutation door must reject NTD and Course entry plans')
    policy_py = read("williams_execution_policy.py")
    must(policy_py, 'EXECUTION_PROFILES = (TC2,)', 'Python new-risk policy must match Android')
    must(policy_py, 'OBSERVER_PROFILES = (NTD, COURSE)', 'NTD and Course must only observe in Python')
    ntd_execution = read("app/src/main/java/com/williamsbot/WilliamsNtdExecution.kt")
    for needle in ("NTD_CLOSE_BOUND_CHANGED", "NTD_CLOSE_EXECUTABLE_QUOTE_OUTSIDE_BOUND", "NTD_FIRST_EXTERNAL_FRACTAL_REQUIRED"):
        must(ntd_execution, needle, "NTD final execution evidence")
    must(coordinator_editions, 'ntdCloseEntryDoor(plan,adapter)?.let { error(it) }', 'NTD final quote/bound guard')
    must(futures, 'self._revalidate_ntd_signal(campaign,intent)', 'Python NTD final closed-H1 source guard')
    must(runtime_source, 'sourceEvidence.filter { it.profile==signal.strategyProfile }', 'No cross-source opposition hybrid')

    must(runtime_source, "williamsBookInitialContextRank(", "StandaloneRuntime.kt TC2 H1 book-native initial-entry selection")
    must(runtime_source, "H1_CONTEXT_EVIDENCE_NOT_DURABLE", "StandaloneRuntime.kt source-context provenance")
    must(runtime_source, "private fun estimateManagedEquity(): Double =\n        liveFuturesEquity", "StandaloneRuntime.kt")
    equity_test = read("app/src/test/java/com/williamsbot/FuturesAccountEquityTest.kt")
    for needle in (
        "prefersAuthoritativeTotalMarginBalance",
        "fallsBackToWalletBalancePlusUnrealizedPnlWhenMarginBalanceMissing",
        "rejectsMissingNonFiniteOrNegativeEquity",
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
    must(coordinator_source, 'val manualExit = reason == "MANUAL_EXIT" || reason == "KILL_SWITCH"', "NativeFuturesCampaignCoordinator.kt")
    must(coordinator_source, "manual close remains available, new entries blocked", "NativeFuturesCampaignCoordinator.kt")
    must(coordinator_source, "strategy ownership is unverified; new entries remain blocked", "NativeFuturesCampaignCoordinator.kt")
    must(coordinator_source, "EXCHANGE_POSITION_PLUS_FIVE_CLOSED_H1_BARS", "NativeFuturesCampaignCoordinator.kt")
    must(coordinator_source, "without a recoverable protective H1 stop; manual close remains available, new entries blocked", "NativeFuturesCampaignCoordinator.kt")
    must(coordinator_source, '"strategy_ownership_verified", false', "NativeFuturesCampaignCoordinator.kt")
    must(coordinator_source, '"flat_confirmed", flatConfirmed', "NativeFuturesCampaignCoordinator.kt")
    must(coordinator_source, '"protection_cleanup_pending", flatConfirmed', "NativeFuturesCampaignCoordinator.kt")
    must(runtime_source, "allowReconcileRequired = true", "StandaloneRuntime.kt")
    must(coordinator_source, "existing exit intent is active/ambiguous; reconcile before any new close request", "NativeFuturesCampaignCoordinator.kt")
    must(coordinator_source, "entryTimePolicyProvider().allows(clock())", "NativeFuturesCampaignCoordinator.kt")
    must(coordinator_source, "safePreflightRejection(blocker: String)", "NativeFuturesCampaignCoordinator.kt")
    must(coordinator_source, "marketOrder = plan.kind != EntryKind.LIMIT", "NativeFuturesCampaignCoordinator.kt")
    execution_math = read("app/src/main/java/com/williamsbot/ExecutionMath.kt")
    must(execution_math, "exactFullMarketCloseQuantity(", "ExecutionMath.kt")
    must(execution_math, "refusing a rounded partial close", "ExecutionMath.kt")
    adapter_source = read("app/src/main/java/com/williamsbot/BinanceUsdMFuturesTestnetAdapter.kt")
    must(adapter_source, '"priceProtect" to "false"', "BinanceUsdMFuturesTestnetAdapter.kt")
    must(adapter_source, 'params["closePosition"] = "true"', "BinanceUsdMFuturesTestnetAdapter.kt")
    must(adapter_source, 'fun placeReplacementProtectiveStop(', "BinanceUsdMFuturesTestnetAdapter.kt")
    must(adapter_source, 'params["reduceOnly"] = "true"', "BinanceUsdMFuturesTestnetAdapter.kt")
    must(runtime_source, "cancelPendingEntriesOutsideWilliamsSession()", "StandaloneRuntime.kt")
    must(runtime_source, "OUTSIDE_CONFIGURED_ENTRY_TIME", "StandaloneRuntime.kt")
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
    must(legacy_runtime, '.put("ok", running && !reconcileRequired && !killLatched &&', "StandaloneRuntime.kt")
    must(legacy_runtime, 'coordinator.optString("state") in setOf("READY", "OPEN_PROTECTED", "ENTRY_PENDING")', "StandaloneRuntime.kt")
    must(legacy_runtime, 'userStreamSyncRequired = true', "StandaloneRuntime.kt")
    must(legacy_runtime, 'if (lossPct >= 0.03)', "StandaloneRuntime.kt")
    must(legacy_runtime, 'FUTURES_DAILY_LOSS_LIMIT: 3% reached; new entries blocked', "StandaloneRuntime.kt")

    android = read("app/src/main/java/com/williamsbot/MainActivity.kt")
    for needle in (
        "BuildConfig.VERSION_NAME",
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
        "val maxOpenPositions: Int = APPROVED_FUTURES_CHART_SYMBOLS.size",
        "val maxTotalRiskPct: Double = 0.006",
    ):
        must(android, needle, "MainActivity.kt")

    service = read("app/src/main/java/com/williamsbot/TradingForegroundService.kt")
    for needle in (
        "StandaloneRuntime.start(applicationContext)",
        "StandaloneRuntime.autostart(applicationContext)",
        "if (StandaloneRuntime.stop()) stopSelf()",
        "BackgroundRuntimePolicy.WAKE_TIMEOUT_MS",
        "RuntimeContinuityEvidence.refreshProcessExits",
    ):
        must(service, needle, "TradingForegroundService.kt")
    must_not(service, "remote Futures backend", "TradingForegroundService.kt")
    must_not(service, "legacy native Spot runtime", "TradingForegroundService.kt")
    must_not(service, "StandaloneRuntime.stopTrading()", "TradingForegroundService.kt")

    # Device reboot/update recovery must respect the user's persisted opt-in.
    manifest = read("app/src/main/AndroidManifest.xml")
    for needle in ('android.permission.WAKE_LOCK', 'android:stopWithTask="false"'):
        must(manifest, needle, "AndroidManifest.xml")
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
        "test_android_protection_must_match_owner_id_exact_trigger_direction_and_active_exchange_status",
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
        must(workflow, "python -m pytest -q 2>&1", path)
        must(workflow, "python -m ruff check --select F821,F822,F823,E9", path)
    # All deterministic tests are discovered; diagnostic CLIs cannot hide these suites.
    collection_config = ast.parse(read("conftest.py"))
    ignored = set()
    for node in collection_config.body:
        if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == "collect_ignore" for t in node.targets):
            ignored = set(ast.literal_eval(node.value))
    for required in (
        "test_binance_futures_client.py", "test_futures_endpoint_pinning.py",
        "test_futures_williams_runtime.py", "test_four_echelons.py", "test_wave_engine_integration.py",
        "tests/test_futures_audit_regressions.py", "tests/test_futures_campaign_risk_defaults.py",
        "tests/test_futures_risk_policy_contract.py", "tests/test_risk_engine_daily_loss_cap.py",
    ):
        read(required)
        assert required not in ignored, f"required deterministic suite excluded: {required}"
    workflow = read(".github/workflows/python-ci.yml")
    must(workflow, "futures_williams_runtime.py", ".github/workflows/python-ci.yml")
    must(workflow, "python -m py_compile", ".github/workflows/python-ci.yml")

    print("WILLIAMS FUTURES RELEASE CONTRACT GATE: PASS")


if __name__ == "__main__":
    main()
