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
    assert "StandaloneRuntime.request(context: Context" in runtime
    assert "fun dispatch(method: String, target: String, body: String)" in runtime
    # Trading API keys are never put in UI/backend connection preferences.
    assert 'putString("api_key"' not in main
    assert 'putString("api_secret"' not in main


def test_android_market_socket_uses_futures_demo_endpoint_and_gates_entries():
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")

    assert "wss://demo-fstream.binance.com/market/stream?streams=" in runtime
    assert "wss://demo-fstream.binance.com/public/stream?streams=" in runtime
    assert "wss://demo-fstream.binance.com/private/ws?events=ORDER_TRADE_UPDATE/ACCOUNT_UPDATE/ALGO_UPDATE" in runtime
    assert "startMarketDataStream()" in runtime
    assert "startPublicDataStream()" in runtime
    assert "publicWebSocketFresh()" in runtime
    assert "marketSocketLastEventMs = System.currentTimeMillis()" in runtime
    assert "private fun marketWebSocketFresh" in runtime
    assert "marketWebSocketFresh() &&" in runtime
    assert 'add("market_websocket_disconnected_or_stale")' in runtime
    assert "if (!executionReady() || !candidate.campaignReady) return" in runtime
    assert "https://demo-fapi.binance.com" in runtime


def test_android_campaign_and_intents_are_sqlite_backed():
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")
    coordinator = _read(ANDROID / "java/com/williamsbot/NativeFuturesCampaignCoordinator.kt")
    store = _read(ANDROID / "java/com/williamsbot/FuturesExecutionStore.kt")

    assert "private val executionStore = FuturesExecutionStore(context)" in runtime
    assert "NativeFuturesCampaignCoordinator(executionStore)" in runtime
    assert "executionStore.writeRuntimeState" in runtime
    assert "executionStore.readState" in runtime
    assert "private val executionStore: FuturesExecutionStore" in coordinator
    assert "executionStore.writeState(intentsKey, array.toString())" in coordinator
    assert "SQLiteOpenHelper" in store
    assert "CREATE TABLE IF NOT EXISTS runtime_state" in store
    assert "db.beginTransaction()" in store
    assert "migrateLegacyState" in store
    assert "prefs.getString(intentsKey" not in coordinator
    assert "prefs.edit().putString(intentsKey" not in coordinator


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
    assert '"max_daily_loss_pct", 0.01' in main
    assert '"max_total_risk_pct",\n                0.006' in main
    assert '.put("max_daily_loss_pct", 0.01)' in runtime
    assert "daily_loss=0.03" not in runtime


def test_futures_user_stream_is_not_the_spot_signed_subscription_protocol():
    user_stream = _read(ANDROID / "java/com/williamsbot/BinanceUserDataStream.kt")
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")
    assert '"/fapi/v1/listenKey"' in user_stream
    assert "userDataStream.subscribe.signature" not in user_stream
    assert "userStream.start()" in runtime
    assert '"ORDER_TRADE_UPDATE", "ACCOUNT_UPDATE", "ALGO_UPDATE"' in runtime
    assert "userStreamConnected &&" in runtime
    assert "!userStreamSyncRequired" in runtime
