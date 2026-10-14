from pathlib import Path


ROOT = Path(__file__).resolve().parent
ANDROID = ROOT / "app" / "src" / "main"


def _read(path):
    return path.read_text(errors="replace") if path.exists() else ""


def test_android_starts_native_futures_runtime_from_foreground_service():
    main = _read(ANDROID / "java/com/williamsbot/MainActivity.kt")
    service = _read(ANDROID / "java/com/williamsbot/TradingForegroundService.kt")
    manifest = _read(ANDROID / "AndroidManifest.xml")

    assert "TradingForegroundService.ACTION_START" in main
    assert "StandaloneRuntime.start(applicationContext)" in service
    assert "StandaloneRuntime.autostart(applicationContext)" in service
    assert "StandaloneRuntime.stopTrading()" in service
    assert 'android:name=".TradingForegroundService"' in manifest
    assert "Futures Testnet runtime on this device" in service


def test_android_ui_routes_directly_to_local_runtime_without_remote_backend():
    main = _read(ANDROID / "java/com/williamsbot/MainActivity.kt")
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")

    assert "class LocalRuntimeApi" in main
    assert "StandaloneRuntime.request(appContext" in main
    assert "class BackendApi" not in main
    assert "backend_url" not in main
    assert "mobile_token" not in main
    assert "fun request(context: Context" in runtime
    assert "fun dispatch(method: String, target: String, body: String)" in runtime
    assert "ServerSocket" not in runtime
    assert "InetAddress.getByName(\"127.0.0.1\")" not in runtime
    assert "private fun handle(s: Socket)" not in runtime
    # Trading API keys are never put in UI/backend connection preferences.
    assert 'putString("api_key"' not in main
    assert 'putString("api_secret"' not in main


def test_android_market_socket_uses_futures_demo_endpoint_and_gates_entries():
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")

    assert "wss://demo-fstream.binance.com/market/stream?streams=" in runtime
    assert "wss://demo-fstream.binance.com/public/stream?streams=" in runtime
    assert "wss://demo-fstream.binance.com/private/ws?events=ORDER_TRADE_UPDATE/ACCOUNT_UPDATE/ALGO_UPDATE/ACCOUNT_CONFIG_UPDATE/CONDITIONAL_ORDER_TRIGGER_REJECT/listenKeyExpired" in runtime
    assert "startMarketDataStream()" in runtime
    assert "startPublicDataStream()" in runtime
    assert "publicWebSocketFresh()" in runtime
    assert "marketSocketLastEventMs = System.currentTimeMillis()" in runtime
    assert "private fun marketWebSocketFresh" in runtime
    assert "marketWebSocketFresh() &&" in runtime
    assert 'add("market_websocket_disconnected_or_stale")' in runtime
    assert "if (!executionReady() || !candidate.campaignReady) return" in runtime
    assert "https://demo-fapi.binance.com" in runtime


def test_android_corrupt_persisted_campaign_state_fails_closed():
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")

    assert "var persistedStateCorrupt = false" in runtime
    assert "Persisted Futures positions are unreadable" in runtime
    assert "Persisted Futures pending entries are unreadable" in runtime
    assert "if (persistedStateCorrupt)" in runtime
    assert 'persistSafetyFlag("reconcile_required", true)' in runtime
    assert 'val interruptedGateSymbol' in runtime
    assert 'prefs.getString("execution_gate_symbol", "")' in runtime
    assert 'prefs.edit().putBoolean("auto_run", false).commit()' in runtime
    assert "Execution gate interrupted for " in runtime
    assert "TradingState.RECONCILE_REQUIRED" in runtime


def test_android_reconcile_and_kill_latches_are_sqlite_durable():
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")
    store = _read(ANDROID / "java/com/williamsbot/FuturesExecutionStore.kt")

    assert 'executionStore.readState("reconcile_required")' in runtime
    assert 'executionStore.readState("kill_latched")' in runtime
    assert 'persistSafetyFlag("reconcile_required", true)' in runtime
    assert 'persistSafetyFlag("kill_latched", true)' in runtime
    assert "SQLite safety-latch write failed" in runtime
    assert 'check(persistSafetyFlag("kill_latched", false))' in runtime
    assert 'check(persistSafetyFlag("reconcile_required", !ok))' in runtime
    assert '"reconcile_required"' in runtime and '"kill_latched"' in runtime
    assert "CREATE TABLE IF NOT EXISTS runtime_state" in store


def test_android_campaign_and_intents_are_sqlite_backed():
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")
    coordinator = _read(ANDROID / "java/com/williamsbot/NativeFuturesCampaignCoordinator.kt")
    store = _read(ANDROID / "java/com/williamsbot/FuturesExecutionStore.kt")

    assert "private val executionStore = FuturesExecutionStore(context)" in runtime
    assert "NativeFuturesCampaignCoordinator(executionStore)" in runtime
    assert "executionStore.writeRuntimeState" in runtime
    assert 'executionStore.writeState("futures_daily_loss_pct", lossPct.toString())' in runtime
    assert 'executionStore.writeState("futures_daily_pnl_usdt", dailyPnlQuote.toString())' in runtime
    assert 'executionStore.readState("futures_daily_pnl_usdt")' in runtime
    assert "executionStore.readState" in runtime
    assert "private val executionStore: FuturesExecutionStore" in coordinator
    assert "executionStore.writeState(intentsKey, array.toString())" in coordinator
    assert "SQLiteOpenHelper" in store
    assert "CREATE TABLE IF NOT EXISTS runtime_state" in store
    assert "db.transaction {" in store
    assert "SQLiteOpenHelper" in store
    assert "migrateLegacyState" in store
    assert "legacy.all[key]?.toString()" in store
    assert "legacy.getString(key, null)" not in store
    assert "prefs.getString(intentsKey" not in coordinator
    assert "prefs.edit().putString(intentsKey" not in coordinator


def test_android_daily_loss_is_rechecked_at_order_admission():
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")

    assert "val dailyLossAllowsEntry = runCatching { checkFuturesDailyLoss() }" in runtime
    assert "if (!dailyLossAllowsEntry) return" in runtime
    assert "FUTURES_DAILY_LOSS_CHECK_FAILED" in runtime
    assert 'if (lossPct >= 0.01)' in runtime


def test_android_reversal_htf_gate_matches_python_campaign_contract():
    android = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")
    python = _read(ROOT / "futures_williams_runtime.py")

    assert 'signal.type.contains("REVERSAL", ignoreCase = true)' in android
    assert "(reversalSignal || h4Bull) && !d1Bear" in android
    assert "(reversalSignal || h4Bear) && !d1Bull" in android
    assert "def _requires_directional_htf_confirmation" in python
    assert "return signal_type != SignalType.REVERSAL" in python


def test_android_signed_execution_boundary_rejects_legacy_spot_routes():
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")

    assert 'require(path.startsWith("/fapi/"))' in runtime
    assert "Any leftover signed Spot mutation is blocked at the signedRequest boundary." in runtime
    assert '"/api/v3/order"' in runtime  # legacy call sites may remain only as fail-closed dead paths
    adapter = _read(ANDROID / "java/com/williamsbot/BinanceUsdMFuturesTestnetAdapter.kt")
    assert '"/fapi/v1/order"' in adapter
    assert "NativeFuturesCampaignCoordinator(executionStore)" in runtime


def test_android_futures_adapter_has_unambiguous_long_short_sides():
    adapter = _read(ANDROID / "java/com/williamsbot/BinanceUsdMFuturesTestnetAdapter.kt")
    tests = _read(ROOT / "app/src/test/java/com/williamsbot/BinanceUsdMFuturesTestnetAdapterContractTest.kt")

    assert 'private val base = "https://demo-fapi.binance.com"' in adapter
    assert '"/fapi/v1/order"' in adapter
    assert '"/fapi/v1/algoOrder"' in adapter
    assert 'fun entrySide(direction: String)' in adapter
    assert 'fun closeSide(direction: String)' in adapter
    assert 'assertEquals("BUY", BinanceUsdMFuturesTestnetAdapter.entrySide("LONG"))' in tests
    assert 'assertEquals("SELL", BinanceUsdMFuturesTestnetAdapter.entrySide("SHORT"))' in tests
    assert 'assertEquals("SELL", BinanceUsdMFuturesTestnetAdapter.closeSide("LONG"))' in tests
    assert 'assertEquals("BUY", BinanceUsdMFuturesTestnetAdapter.closeSide("SHORT"))' in tests


def test_server_python_backend_remains_futures_and_mainnet_is_disabled_by_default():
    config = _read(ROOT / ".env.example")
    server = _read(ROOT / "server.py")
    runtime = _read(ROOT / "futures_williams_runtime.py")

    assert "BINANCE_MARKET=futures_usdt" in config
    assert "TESTNET=true" in config
    assert "ALLOW_LIVE=false" in config
    assert "DRY_RUN=true" in config
    assert "FuturesWilliamsRuntime" in server
    assert "BinanceFuturesClient" in server
    assert "extract_long_signal_specs" in runtime
    assert "extract_short_signal_specs" in runtime


def test_environment_secrets_are_gitignored():
    ignore = _read(ROOT / ".gitignore")
    assert ".env" in ignore
    assert "keystore.properties" in ignore
    assert "data/binance_credentials.enc" in ignore
    assert "data/credential_master.key" in ignore


def test_android_defaults_match_the_approved_single_campaign_risk_profile():
    main = _read(ANDROID / "java/com/williamsbot/MainActivity.kt")
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")

    assert "val riskPerTrade: Double = 0.0025" in main
    assert "val maxDailyLoss: Double = 0.01" in main
    assert "val maxOpenPositions: Int = 1" in main
    assert "val maxTotalRiskPct: Double = 0.006" in main
    assert '"max_daily_loss_pct",\n                0.01' in main
    assert '"max_total_risk_pct",\n                0.006' in main
    assert '.put("max_daily_loss_pct", 0.01)' in runtime
    assert "daily_loss=0.03" not in runtime


def test_android_diagnostics_do_not_fake_self_heal_readiness():
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")

    assert 'test("self_heal_contract", "runtime", true' not in runtime
    assert 'running && !reconcileRequired && !killLatched' in runtime
    assert '"running=" + running' in runtime
    assert '"kill_switch_latched", killLatched' in runtime
    assert '.put("testnet_live_execution", executionReady())' in runtime
    assert '.put("testnet_live_execution", false)' not in runtime


def test_android_williams_indicator_decisions_use_current_time_alligator_and_canonical_ao():
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")
    assert "williamsAlligatorCurrentLine(candles, 13)" in runtime
    assert "williamsAlligatorCurrentLine(candles, 8)" in runtime
    assert "williamsAlligatorCurrentLine(candles, 5)" in runtime
    assert "val slowValues = medianPrices.subList(index - 33, index + 1)" in runtime
    assert "medianPrices.subList(index - 34, index + 1).average()" not in runtime
    assert "private fun alligator(" in runtime


def test_android_kill_switch_never_closes_an_unmanaged_or_unreconciled_position():
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")
    coordinator = _read(ANDROID / "java/com/williamsbot/NativeFuturesCampaignCoordinator.kt")
    assert "val ownershipReconciliation = runCatching { futuresCampaignCoordinator.reconcile() }" in runtime
    assert "futuresCampaignCoordinator.isManagedOpenPosition(symbol, direction)" in runtime
    assert "position left untouched" in runtime
    assert "an existing exit intent is active/ambiguous; reconcile before any new close request" in coordinator
    assert "manual reconciliation required" in coordinator


def test_android_private_futures_stream_uses_supported_event_query_delimiters():
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")
    user_stream = _read(ANDROID / "java/com/williamsbot/BinanceUserDataStream.kt")
    assert 'endpoint = "wss://demo-fstream.binance.com/private/ws"' in runtime
    assert '"&events=" + EVENT_TYPES' in user_stream
    assert '"ORDER_TRADE_UPDATE,ACCOUNT_UPDATE,ALGO_UPDATE,ACCOUNT_CONFIG_UPDATE,CONDITIONAL_ORDER_TRIGGER_REJECT,listenKeyExpired"' in user_stream
    assert "ORDER_TRADE_UPDATE/ACCOUNT_UPDATE" not in runtime + user_stream

def test_android_user_stream_is_not_the_spot_signed_subscription_protocol():
    user_stream = _read(ANDROID / "java/com/williamsbot/BinanceUserDataStream.kt")
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")
    assert '"/fapi/v1/listenKey"' in user_stream
    assert "userDataStream.subscribe.signature" not in user_stream
    assert "userStream.start()" in runtime
    assert '"ORDER_TRADE_UPDATE", "ACCOUNT_UPDATE", "ALGO_UPDATE"' in runtime
    assert "userStreamConnected &&" in runtime
    assert "!userStreamSyncRequired" in runtime
    assert 'webSocket.close(1011, "event_handler_failed")' in user_stream
    assert "closed.countDown()" in user_stream
    assert "private val restartRequested = AtomicBoolean(false)" in user_stream
    assert "private fun workerExited(finishedWorker: Thread)" in user_stream
    assert "if (stopping.get()) restartRequested.set(true)" in user_stream
