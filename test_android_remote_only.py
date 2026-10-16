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


def test_android_boot_recovery_is_opt_in_and_keeps_reconciliation_gate():
    manifest = _read(ANDROID / "AndroidManifest.xml")
    boot_receiver = _read(ANDROID / "java/com/williamsbot/TradingBootReceiver.kt")
    service = _read(ANDROID / "java/com/williamsbot/TradingForegroundService.kt")
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")

    assert "android.permission.RECEIVE_BOOT_COMPLETED" in manifest
    assert 'android:name=".TradingBootReceiver"' in manifest
    assert "android.intent.action.BOOT_COMPLETED" in manifest
    assert "android.intent.action.MY_PACKAGE_REPLACED" in manifest
    assert 'getBoolean("auto_run", false)' in boot_receiver
    assert "ContextCompat.startForegroundService" in boot_receiver
    assert "TradingForegroundService.ACTION_START" in boot_receiver
    assert "StandaloneRuntime.autostart(applicationContext)" in service
    # The worker is started with private user-stream synchronization still required;
    # new entries remain locked until exchange state is reconciled.
    assert "userStreamSyncRequired = true" in runtime
    assert "futuresCampaignCoordinator.reconcile()" in runtime


def test_android_exposes_battery_policy_diagnostic():
    main = _read(ANDROID / "java/com/williamsbot/MainActivity.kt")
    assert "isIgnoringBatteryOptimizations" in main
    assert "Doze exemption" in main
    assert "isLowPowerStandbyEnabled" in main
    assert "isExemptFromLowPowerStandby" in main
    assert "ACTION_APPLICATION_DETAILS_SETTINGS" in main


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
    assert 'endpoint = "wss://demo-fstream.binance.com/private/ws"' in runtime
    user_stream = _read(ANDROID / "java/com/williamsbot/BinanceUserDataStream.kt")
    assert "private const val EVENT_TYPES = FUTURES_USER_STREAM_EVENT_FILTER" in user_stream
    assert "ORDER_TRADE_UPDATE/ACCOUNT_UPDATE/ALGO_UPDATE" in user_stream
    assert "ORDER_TRADE_UPDATE,ACCOUNT_UPDATE,ALGO_UPDATE" not in user_stream
    assert 'endpoint + "?listenKey="' in user_stream
    assert "events=" in user_stream
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
    # Recovery now handles a failed durable latch write explicitly instead of
    # relying on check(), so the runtime remains fail-closed and reports a blocker.
    assert 'if (!persistSafetyFlag("reconcile_required", !ok))' in runtime
    assert "SQLite could not persist recovery reconciliation state" in runtime
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
    assert 'if (lossPct >= 0.03)' in runtime


def test_android_reversal_htf_gate_matches_python_campaign_contract():
    android = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")
    python = _read(ROOT / "futures_williams_runtime.py")

    # Directional context is ranked only after both H1 hypotheses are preserved.
    # A D1 conflict vetoes that side, while H4 alignment ranks allowed candidates.
    assert "internal fun directionalSignalContextRank(" in android
    assert "val freshSignals = candidate.campaignSignals.filter" in android
    assert "directionalSignalContextRank(it.type, d1Bull, d1Bear, h4Bull, h4Bear)" in android
    assert "internal fun campaignSignalDirection(signalType: String): String?" in android
    assert "WILLIAMS_SIGNAL_DIRECTION_UNKNOWN: refusing to default an unknown signal to LONG" in android
    assert "return signal_type != SignalType.REVERSAL" in python


def test_android_manual_direction_override_is_demo_only_and_keeps_execution_safety_gates():
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")
    ui = _read(ANDROID / "java/com/williamsbot/MainActivity.kt")

    assert 'path == "/api/v1/control/testnet-force-mode"' in runtime
    assert 'path == "/api/v1/control/testnet-force-entry"' in runtime
    assert 'require(baseUrl == "https://demo-fapi.binance.com")' in runtime
    assert "require(executionGate.tryReserve(FUTURES_EXECUTION_DOOR_KEY))" in runtime
    assert 'prefs.getBoolean("force_testnet_mode", false) ||' in runtime
    assert "executionGate.release(FUTURES_EXECUTION_DOOR_KEY)" in runtime
    assert "Futures positions/orders/protection must be reconciled" in runtime
    assert "checkFuturesDailyLoss()" in runtime
    assert "TESTNET_FORCE_MODE_ACTIVE: automatic entries suspended" in runtime
    assert "FUTURES_ENTRY_SPREAD_BLOCKED" in runtime
    assert "H1_STRUCTURAL_TRIGGER_ALREADY_CROSSED" in runtime
    assert 'NativeFuturesCampaignCoordinator.Direction.LONG' in runtime
    assert 'NativeFuturesCampaignCoordinator.Direction.SHORT' in runtime
    assert "private fun TestnetForceTradingCard(" in ui
    assert 'mutableStateOf("SHORT")' in ui
    assert 'put("direction", direction)' in ui
    assert 'put("acknowledge", true)' in ui
    assert "protectiveStop = fmtPrice(stop, rules.tick)" in runtime


def test_android_signed_execution_boundary_rejects_legacy_spot_routes():
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")
    market_data = _read(ANDROID / "java/com/williamsbot/BinanceUsdMFuturesMarketData.kt")

    # The legacy signed transport and retired Spot order paths are removed entirely.
    assert "Legacy signed transport has been removed" in runtime
    assert "private fun signedRequest(" not in runtime
    assert "/api/v3/" not in runtime
    assert "normalizeFuturesPublicPath(path)" in runtime
    assert "internal fun normalizeFuturesPublicPath(path: String)" in market_data
    assert "SECURITY_BLOCK: unsupported public route in USD-M Futures runtime" in market_data
    adapter = _read(ANDROID / "java/com/williamsbot/BinanceUsdMFuturesTestnetAdapter.kt")
    assert '"/fapi/v1/order"' in adapter
    assert '"/fapi/v1/algoOrder"' in adapter
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


def test_android_defaults_match_the_approved_multi_symbol_campaign_risk_profile():
    main = _read(ANDROID / "java/com/williamsbot/MainActivity.kt")
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")

    assert "val riskPerTrade: Double = 0.0025" in main
    assert "val maxDailyLoss: Double = 0.03" in main
    assert "val maxOpenPositions: Int = APPROVED_FUTURES_CHART_SYMBOLS.size" in main
    assert "get() = APPROVED_FUTURES_SYMBOLS.size" in runtime
    assert "val maxTotalRiskPct: Double = 0.006" in main
    assert '"max_daily_loss_pct",\n                0.03' in main
    assert '"max_total_risk_pct",\n                0.006' in main
    assert '.put("max_daily_loss_pct", 0.03)' in runtime
    assert "if (lossPct >= 0.03)" in runtime
    assert "FUTURES_DAILY_LOSS_LIMIT: 3% reached; new entries blocked" in runtime


def test_android_diagnostics_do_not_fake_self_heal_readiness():
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")

    assert 'test("self_heal_contract", "runtime", true' not in runtime
    assert 'running && !reconcileRequired && !killLatched' in runtime
    assert '"running=" + running' in runtime
    assert '"kill_switch_latched", killLatched' in runtime
    assert '.put("testnet_live_execution", executionReady())' in runtime
    assert '.put("testnet_live_execution", false)' not in runtime


def test_android_williams_indicator_decisions_use_chart_aligned_alligator_and_canonical_ao():
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")
    assert "williamsAlligatorLine(candles, 13, 8)" in runtime
    assert "williamsAlligatorLine(candles, 8, 5)" in runtime
    assert "williamsAlligatorLine(candles, 5, 3)" in runtime
    assert "williamsAlligatorCurrentLine" not in runtime
    assert "val slowValues = medianPrices.subList(index - 33, index + 1)" in runtime
    assert "medianPrices.subList(index - 34, index + 1).average()" not in runtime
    assert "private fun alligator(" in runtime
    assert "Super AO is independent of WM3/fractals." in runtime
    assert "priorFractalValid" not in runtime
    assert "candles[fractalCenter].h > centerTeeth" in runtime
    assert "trigger > currentTeeth" in runtime
    assert "fun upGroupStart(i: Int): Int" in runtime
    assert "fun downGroupStart(i: Int): Int" in runtime
    assert "candles[i + 1].h == candles[i].h" in runtime
    assert ".minOf { candles[it].l } - tick" in runtime


def test_android_kill_switch_never_closes_an_unmanaged_or_unreconciled_position():
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")
    coordinator = _read(ANDROID / "java/com/williamsbot/NativeFuturesCampaignCoordinator.kt")
    # The unsafe direct call must stay absent: ownership reconciliation must pass through the durable Execution Door.
    assert "val ownershipReconciliation = runCatching { futuresCampaignCoordinator.reconcile() }" not in runtime
    assert 'val ownershipReconciliation = runCatching {' in runtime
    assert 'reconcileFuturesThroughDoor(' in runtime
    assert '"KILL_SWITCH_PRE_CLOSE_RECONCILIATION"' in runtime
    assert "futuresCampaignCoordinator.isManagedOpenPosition(symbol, direction)" in runtime
    assert "position left untouched" in runtime
    assert "an existing exit intent is active/ambiguous; reconcile before any new close request" in coordinator
    assert "manual reconciliation required" in coordinator



def test_android_signed_account_refresh_uses_usdm_futures_route():
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")
    signed_account = runtime.split("private fun signedAccount(): JSONObject =", 1)[1].split(
        "private fun signedRequest(", 1
    )[0]
    assert 'path = "/fapi/v3/account"' in signed_account
    assert 'path = "/api/v3/account"' not in signed_account
    assert 'require(path.startsWith("/fapi/"))' in runtime

def test_android_private_futures_stream_uses_supported_event_query_delimiters():
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")
    user_stream = _read(ANDROID / "java/com/williamsbot/BinanceUserDataStream.kt")
    assert 'endpoint = "wss://demo-fstream.binance.com/private/ws"' in runtime
    assert '"&events=" + EVENT_TYPES' in user_stream
    assert "FUTURES_USER_STREAM_EVENT_FILTER" in user_stream
    assert '"ORDER_TRADE_UPDATE/ACCOUNT_UPDATE/ALGO_UPDATE/ACCOUNT_CONFIG_UPDATE/CONDITIONAL_ORDER_TRIGGER_REJECT/listenKeyExpired"' in user_stream
    assert "ORDER_TRADE_UPDATE/ACCOUNT_UPDATE" in user_stream
    assert "ORDER_TRADE_UPDATE,ACCOUNT_UPDATE,ALGO_UPDATE" not in user_stream

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


def test_android_execution_gate_requires_coordinator_and_stream_reconciliation():
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")
    user_stream = _read(ANDROID / "java/com/williamsbot/BinanceUserDataStream.kt")
    coordinator = _read(ANDROID / "java/com/williamsbot/NativeFuturesCampaignCoordinator.kt")
    assert "private fun campaignCoordinatorReadyForEntry(): Boolean" in runtime
    assert '"READY", "OPEN_PROTECTED", "ENTRY_PENDING"' in runtime
    assert "private fun futuresFsmAllowsEntry(): Boolean" in runtime
    assert "campaignCoordinatorReadyForEntry() &&" in runtime
    assert '"futures_campaign_coordinator_not_ready:"' in runtime
    assert "confirmedPendingEntriesAllowConcurrentAdmission(currentIntents)" in coordinator
    assert '"ready_for_new_entry", readyForNewEntry' in coordinator
    assert "val existingCampaignIntents = futuresCampaignCoordinator.intentsSnapshot()" in runtime
    assert "reservedSymbol in coreSymbols" in runtime
    assert "else if (!userStreamConnected)" in runtime
    assert "userStreamSyncRequired = true" in runtime
    assert "internal fun shouldReconnectFuturesUserStream(event: JSONObject): Boolean" in user_stream
    assert "if (shouldReconnectFuturesUserStream(event))" in user_stream


def test_android_h1_is_the_only_directional_decision_and_m15_is_execution_readiness_only():
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")
    entry = runtime.split("private fun submitNativeFuturesCampaignEntry(", 1)[1].split(
        "private fun performCampaignScan(", 1
    )[0]
    assert "M15_EXECUTION_BLOCKED" in entry
    assert "latest closed M15 candle is stale" in entry
    assert "submitM15Bull" not in entry
    assert "submitM15Bear" not in entry
    assert "m15Confirmed" not in entry
    assert "M15_CONFIRMATION_BLOCKED" not in entry
    # H1 signals still pass through D1 macro veto and H4 context, then the
    # exchange-side conditional entry uses the H1 trigger/structural stop.
    assert "signal.triggerPrice" in entry
    assert "signal.protectivePrice" in entry
    assert "H1_TRIGGER_ALREADY_CROSSED" in entry
    assert "val submitMark = futuresMarketData.markPrice(symbol)" in entry
    assert "futuresCampaignCoordinator.submitPendingEntry(plan)" in entry


def test_android_campaign_lifecycle_is_separate_from_exchange_order_status():
    coordinator = _read(ANDROID / "java/com/williamsbot/NativeFuturesCampaignCoordinator.kt")
    assert "fun campaignLifecycleStatus(intent: JSONObject)" in coordinator
    assert "internal fun shouldFinalizeFilledFlatCampaign(" in coordinator
    assert "flat_after_fill_first_seen_at_ms" in coordinator
    assert 'intent.optString("campaign_status")' in coordinator
    assert 'intent.optString("order_status")' in coordinator
    assert '"FILL_CONFIRMATION_PENDING"' in coordinator
    assert "isEntryCampaignReserved(intent)" in coordinator
    assert "futuresPositionSlotBlocker(" in coordinator
    assert "maxPositions = allowedSymbols.size" in coordinator
    assert "latestEntryIntentIndex(intents, symbol)" in coordinator
    flat_cleanup = coordinator.index("// Once Binance reports the symbol flat")
    terminal_write = coordinator.index('intent.put("campaign_status", "CLOSED_OR_STOPPED")', flat_cleanup)
    durable_save = coordinator.index("saveIntents(persisted)", terminal_write)
    stop_cleanup = coordinator.index("val algos = adapter.openAlgoOrders(symbol)", terminal_write)
    assert terminal_write < durable_save < stop_cleanup
    assert "internal fun shouldFinalizeFilledFlatCampaign(" in coordinator
    assert "filledEntryRequiresAStableFlatWindowAndNoUnresolvedExitBeforeTerminalization" in _read(ROOT / "app/src/test/java/com/williamsbot/ProtectionOwnershipTest.kt")


def test_android_exit_requires_exchange_position_protection_and_filled_order_confirmation():
    coordinator = _read(ANDROID / "java/com/williamsbot/NativeFuturesCampaignCoordinator.kt")
    assert "val adapterPreflight = adapterFactory()" in coordinator
    assert "adapterPreflight.positions()" in coordinator
    assert "adapterPreflight.openAlgoOrders(symbol)" in coordinator
    assert "requestedQty.compareTo(liveAmount.abs()) != 0" in coordinator
    assert "val confirmedOrder = adapter.order(symbol, clientOrderId)" in coordinator
    assert 'if (finalOrderStatus != "FILLED")' in coordinator
    # Explicit operator exits are a risk-reducing escape hatch when the
    # campaign journal or stop metadata was lost. They still require exchange
    # position, direction, exact quantity, order-FILLED and flat confirmation.
    assert 'val manualExit = reason == "MANUAL_EXIT" || reason == "KILL_SWITCH"' in coordinator
    assert 'if (managedEntry == null && !manualExit)' in coordinator
    assert 'if (!manualExit)' in coordinator
    assert 'managedEntry?.put("campaign_status", "CLOSED_OR_STOPPED")' in coordinator
    assert 'exitIntent.put("exchange_status", "EXIT_CONFIRMED")' in coordinator
    assert 'allowReconcileRequired = true' in _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")
    assert 'isOwnedClientOrderForSymbol(symbol, clientId, activeProtectionClientIdsBySymbol)' in coordinator


def test_android_pre_order_candidate_rejection_does_not_fake_unknown_mutation():
    coordinator = _read(ANDROID / "java/com/williamsbot/NativeFuturesCampaignCoordinator.kt")
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")
    submit = coordinator.split("fun submitPendingEntry(", 1)[1].split(
        "fun cancelPendingEntries(", 1
    )[0]
    door = runtime.split("private fun withCampaignMutation(", 1)[1].split(
        "private fun reconcileFuturesThroughDoor(", 1
    )[0]
    assert "private fun safePreflightRejection(blocker: String)" in coordinator
    assert '.put("safe_rejection", true)' in coordinator
    assert '.put("mutation_attempted", false)' in coordinator
    assert "return@synchronized safePreflightRejection(contractBlocker)" in submit
    assert "return@synchronized safePreflightRejection(slotBlocker)" in submit
    assert "return@synchronized safePreflightRejection(riskBlocker)" in submit
    assert "val safePreflightRejection = result.optBoolean(\"safe_rejection\", false)" in door
    assert "!safePreflightRejection && (" in door


def test_android_orphan_futures_position_gets_only_a_deterministic_emergency_stop_and_stays_entry_blocked():
    coordinator = _read(ANDROID / "java/com/williamsbot/NativeFuturesCampaignCoordinator.kt")
    recovery = coordinator.split(
        "if (matchingIntent == null && latestIntentIndex == null)", 1
    )[1].split("if (matchingIntent != null) {", 1)[0]
    assert "structuralStopRecoveryProvider?.invoke" in recovery
    assert "EXCHANGE_POSITION_PLUS_FIVE_CLOSED_H1_BARS" in recovery
    assert '"strategy_ownership_verified", false' in recovery
    assert "manual close remains available" in recovery
    assert "automatic adoption is blocked" not in recovery
    assert "strategy ownership is unverified; new entries remain blocked" in coordinator


def test_android_entry_cancellation_rechecks_order_and_partial_fill_before_retiring_campaign():
    coordinator = _read(ANDROID / "java/com/williamsbot/NativeFuturesCampaignCoordinator.kt")
    cancel = coordinator.split("fun cancelPendingEntries(", 1)[1].split(
        "fun trailProtectiveStop(", 1
    )[0]
    assert "val confirmed = when (intent.optString(\"kind\"))" in cancel
    assert "adapter.order(symbol, clientId)" in cancel
    assert "adapter.queryAlgoOrder(symbol, clientId)" in cancel
    assert "persistConfirmedCancellation(" in cancel
    assert "val refreshedPositions = adapter.positions()" in cancel
    assert "position_observed_after_cancel" in cancel
    assert "cancel_prior_order_status" in cancel
    assert 'campaignStatus = "FILL_CONFIRMATION_PENDING"' not in cancel
    assert 'exchangeStatus == "FINISHED" || reportedExecuted' in cancel
    assert 'prior == "FILL_CONFIRMATION_PENDING"' in coordinator
    assert "possiblePartialFill" in coordinator


def test_android_exit_keeps_account_state_open_protected_when_other_positions_remain():
    coordinator = _read(ANDROID / "java/com/williamsbot/NativeFuturesCampaignCoordinator.kt")
    close = coordinator.split("fun closePosition(", 1)[1].split(
        "fun status(): JSONObject", 1
    )[0]
    assert "val accountPositionsAfterExit = adapter.positions()" in close
    assert "remainingProtectedPositions++" in close
    assert "state = if (remainingProtectedPositions > 0) State.OPEN_PROTECTED else State.READY" in close
    assert "findProtectiveStop(otherAlgos, otherDirection, ownerStopId, ownerStop)" in close


def test_android_utc_entry_session_blocks_new_orders_and_cancels_resting_entries_after_close():
    coordinator = _read(ANDROID / "java/com/williamsbot/NativeFuturesCampaignCoordinator.kt")
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")
    assert "internal fun isWithinWilliamsEntrySessionUtc(epochMs: Long): Boolean" in coordinator
    assert "return hour in 8 until 20" in coordinator
    submit = runtime.split("private fun submitNativeFuturesCampaignEntry(", 1)[1].split(
        "private fun scanDailyRiskAllowsEntry(", 1
    )[0]
    assert "!isWithinWilliamsEntrySessionUtc(sessionClockMs)" in submit
    assert "!isWithinWilliamsEntrySessionUtc(submitNow)" in submit
    assert "private fun cancelPendingEntriesOutsideWilliamsSession()" in runtime
    assert '"OUTSIDE_WILLIAMS_ENTRY_SESSION"' in runtime
    assert '"OUT_OF_SESSION_CANCEL_PENDING_ENTRIES"' in runtime
    # Signed REST management runs asynchronously so a slow reconciliation cannot
    # delay scanning every approved symbol.
    scan = runtime.split("private fun performCampaignScan()", 1)[1].split(
        "private fun cancelPendingEntriesOutsideWilliamsSession()", 1
    )[0]
    manager = runtime.split("private fun scheduleNativePositionManagement()", 1)[1].split(
        "private fun performCampaignScan()", 1
    )[0]
    assert "scheduleNativePositionManagement()" in scan
    assert "manageNativeFuturesPositions()" in manager
    assert "cancelPendingEntriesOutsideWilliamsSession()" in manager


def test_android_protective_stop_is_not_suppressed_by_price_protect_divergence():
    adapter = _read(ANDROID / "java/com/williamsbot/BinanceUsdMFuturesTestnetAdapter.kt")
    protective = adapter.split("fun placeProtectiveStop(", 1)[1].split(
        "private fun request(", 1
    )[0]
    assert '"type" to "STOP_MARKET"' in protective
    assert '"closePosition" to "true"' in protective
    assert '"workingType" to "MARK_PRICE"' in protective
    assert '"priceProtect" to "false"' in protective


def test_android_protection_must_match_owner_id_exact_trigger_direction_and_active_exchange_status():
    coordinator = _read(ANDROID / "java/com/williamsbot/NativeFuturesCampaignCoordinator.kt")
    # The exact exchange proof is centralized so it can be tested independently of
    # the reconciliation loop; it must not accept a missing/defaulted algo status.
    assert "internal fun matchesConfirmedFuturesProtectiveStop(" in coordinator
    assert 'val clientId = order.optString("clientAlgoId")' in coordinator
    assert "clientId == expectedClientId && actual.compareTo(expected) == 0" in coordinator
    assert "isActiveFuturesProtectionStatus(status)" in coordinator
    assert 'status.trim().uppercase(Locale.US) in setOf("NEW", "WORKING")' in coordinator
    assert 'if (direction == "LONG" && side != "SELL") return false' in coordinator
    assert 'if (direction == "SHORT" && side != "BUY") return false' in coordinator
    assert 'protectionStatus == "SUBMISSION_UNKNOWN" || protectionStatus == "PROTECTED"' in coordinator
    assert "unknownProtectiveStopExists" in coordinator



def test_android_trailing_stop_retains_confirmed_protection_after_ambiguous_replacement():
    coordinator = _read(ANDROID / "java/com/williamsbot/NativeFuturesCampaignCoordinator.kt")
    assert "oldStopStillConfirmed" in coordinator
    assert "replacementConfirmed == null" in coordinator
    assert '.put("previous_protection_client_id", clientAlgoId)' in coordinator
    assert 'state = State.RECONCILE_REQUIRED' in coordinator
    assert "untrackedRemainder" in coordinator
    assert '.remove("previous_protection_client_id")' in coordinator
    assert "val predecessor = findProtectiveStop(" in coordinator
    assert '.put("trail_recovery_at_ms", System.currentTimeMillis())' in coordinator
    assert "val afterCleanup = adapter.openAlgoOrders(symbol)" in coordinator
    assert "trackedPredecessorStillOpen" in coordinator
    assert "if (currentStopSafe)" in coordinator


def test_android_trailing_stops_are_mark_relative_not_entry_relative():
    coordinator = _read(ANDROID / "java/com/williamsbot/NativeFuturesCampaignCoordinator.kt")
    assert "internal fun isProtectiveStopOnCorrectSide(" in coordinator
    assert "var liveMark: Double? = null" in coordinator
    assert "val currentStopSafe = matchingIntent != null && liveMark != null" in coordinator
    assert "isProtectiveStopOnCorrectSide(" in coordinator
    assert "val liveMark = if (matchingIntent != null)" not in coordinator
    assert "hasValidStructuralStop(plan.direction.name, plan.entryPrice, plan.protectiveStop)" in coordinator
    unit_test = _read(ROOT / "app/src/test/java/com/williamsbot/ProtectionOwnershipTest.kt")
    assert "profitLockingTrailingStopsMayCrossEntryButMustRemainBeyondLiveMark" in unit_test

def test_android_super_ao_is_independent_of_the_wm3_fractal_gate():
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")
    assert "Super AO is independent of WM3/fractals." in runtime
    assert "priorFractalValid" not in runtime
    assert "lastValidFractalLevel" not in runtime

    long_signals = runtime.split("private fun longCampaignSignals(", 1)[1].split(
        "private fun shortCampaignSignals(", 1
    )[0]
    wm2 = long_signals.split("// WM2:", 1)[1].split("// WM3:", 1)[0]
    assert 'type = "SUPER_AO"' in wm2
    assert "triggerPrice = trigger" in wm2
    assert "protectivePrice = candles[signalIndex].l - tick" in wm2


def test_android_campaign_analysis_never_relabels_fallback_candles_as_h1():
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")
    analysis = runtime.split("private fun analyseBase(", 1)[1].split(
        "\n    private fun ", 1
    )[0]
    assert "val rawWorkCandles = candles" in analysis
    assert "getOrElse { candles }" not in analysis
    assert "performCampaignScan()" in runtime
    assert 'fetchCandles(symbol, "1h", 180)' in runtime


def test_android_websocket_closed_candles_are_persisted_before_history_readiness():
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")
    fetch = runtime.split("private fun fetchCandles(", 1)[1].split(
        "\n    private fun ", 1
    )[0]
    live_cache = fetch.split("liveCandleCache[liveKey]?.let", 1)[1].split(
        "val cacheKey =", 1
    )[0]
    assert "historyStore.upsertBatch(" in live_cache
    assert "closed.takeLast(limit).map" in live_cache
    assert "return closed.takeLast(limit)" in live_cache
    assert live_cache.index("historyStore.upsertBatch(") < live_cache.index(
        "return closed.takeLast(limit)"
    )


def test_android_settings_do_not_advertise_legacy_fixed_tp_or_position_sizing():
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")
    settings = runtime.split("fun settings(): JSONObject", 1)[1]
    assert '.put("campaign_fixed_take_profit", false)' in settings
    assert '.put("position_fraction", 0.0)' in settings
    assert '.put("stop_loss_pct", 0.0)' in settings
    assert '.put("take_profit_pct", 0.0)' in settings
    assert '.put("legacy_spot_settings_active", false)' in settings
    assert '.put("position_sizing_model", "STRUCTURAL_STOP_RISK_BUDGET")' in settings

def test_android_super_ao_uses_the_third_rising_bar_and_not_the_oldest_of_three():
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")
    long_signals = runtime.split("private fun longCampaignSignals(", 1)[1].split(
        "private fun shortCampaignSignals(", 1
    )[0]
    wm2 = long_signals.split("// WM2:", 1)[1].split("// WM3:", 1)[0]
    assert "val signalIndex = candles.lastIndex - (streak - 3)" in wm2
    assert "signalBarTimeMs = candles[signalIndex].t" in wm2
    assert "triggerPrice = trigger" in wm2
    assert "protectivePrice = candles[signalIndex].l - tick" in wm2
    assert "streak == 3" not in wm2


def test_android_williams_stop_entries_reject_any_intervening_trigger_cross():
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")
    long_signals = runtime.split("private fun longCampaignSignals(", 1)[1].split(
        "private fun shortCampaignSignals(", 1
    )[0]
    assert "fun triggerStillPending(signalIndex: Int, trigger: Double): Boolean" in long_signals
    assert "for (later in signalIndex + 1..current)" in long_signals
    assert "if (candles[later].h >= trigger) return false" in long_signals
    assert long_signals.count("triggerStillPending(") == 4  # helper + WM1 + WM2 + WM3
    assert "if (triggerStillPending(i, trigger))" in long_signals
    assert "if (triggerStillPending(signalIndex, trigger))" in long_signals
    assert "triggerStillPending(fractalCenter, trigger)" in long_signals




def test_chart_cockpit_switches_all_approved_futures_symbols_without_changing_scan_universe():
    ui = _read(ANDROID / "java/com/williamsbot/MainActivity.kt")
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")
    symbols = [
        "BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT",
        "DOGEUSDT", "ADAUSDT", "LINKUSDT", "AVAXUSDT", "LTCUSDT",
    ]
    for symbol in symbols:
        assert f'"{symbol}"' in ui
        assert f'"{symbol}"' in runtime
    assert "private fun MarketChartPanel(" in ui
    assert 'Text("РЕШЕНИЕ ДВИЖКА ПО ВЫБРАННОЙ ПАРЕ"' in ui
    assert "Почему сканер так решил:" in ui
    assert "entry_trigger_price" in ui
    assert "entry_protective_price" in ui
    assert "Переключение графика не меняет логику входа." in ui
    assert 'fetchCandles(symbol, "1h", 180)' in runtime
    assert "val completeScan = ranked.size == coreSymbols.size" in runtime
    assert "scannerState = when {" in runtime
    assert '"READY_BLOCKED"' in runtime
    assert '"PARTIAL_BLOCKED"' in runtime


def test_chart_viewport_handles_fewer_than_twenty_four_candles():
    ui = _read(ANDROID / "java/com/williamsbot/MainActivity.kt")
    assert "min(candles.size, max(1, (candles.size / scale).toInt()))" in ui
    assert ".coerceIn(24, candles.size)" not in ui


def test_chart_endpoint_rejects_unknown_symbol_or_interval_instead_of_silent_fallback():
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")
    chart = runtime.split("fun klines(", 1)[1].split("fun marketTickers()", 1)[0]
    assert 'require(symbol in coreSymbols)' in chart
    assert 'require(selectedInterval in APPROVED_FUTURES_KLINE_INTERVALS)' in chart
    assert 'APPROVED_FUTURES_KLINE_INTERVALS' in chart
    assert "fetchCandles(symbol, selectedInterval, 150)" in chart
    assert "futuresMarketData.isLastClosedCandleCurrent(last.t, frame, nowMs)" in runtime
    market_data = _read(ANDROID / "java/com/williamsbot/BinanceUsdMFuturesMarketData.kt")
    assert "internal fun isLastClosedCandleCurrent" in market_data
    assert "Calendar.MONTH" in market_data
    assert "getOrElse { emptyList() }" not in chart
    assert "Unsupported Futures chart interval" in chart


def test_scanner_diagnostic_does_not_pass_ready_at_zero_of_ten_or_stale_progress():
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")

    # Assert the real production diagnostic contract. The old embedded diagnostic
    # self-test was removed when readiness moved into the regular runtime snapshot.
    assert "val scannerUniverseMatches = scanSymbols.size == coreSymbols.size && scanSymbols.toSet() == coreSymbols.toSet()" in runtime
    assert 'val scannerComplete = scannerState in setOf("READY", "READY_BLOCKED") &&' in runtime
    assert "!scanning && scannerUniverseMatches" in runtime
    assert "scanProgressSymbols == coreSymbols.size && lastSymbolsScanned == coreSymbols.size" in runtime
    assert "scannerProgressAgeMs <= 60_000L" in runtime
    assert 'scannerState == "READY" || (scannerState == "RUNNING"' not in runtime


def test_actions_testnet_gate_skips_when_ci_secrets_absent_without_claiming_test_passed():
    workflow = _read(ROOT / ".github/workflows/testnet-readonly.yml")
    assert "id: check-ci-testnet-credentials" in workflow
    assert "configured=false" in workflow
    assert "Authenticated Futures Demo read-only gate skipped" in workflow
    assert "No authenticated Futures Demo account check was performed" in workflow
    assert "if: steps.check-ci-testnet-credentials.outputs.configured == 'true'" in workflow
    assert "exit 1" not in workflow



def test_android_ui_uses_scanner_endpoint_for_real_state_progress_and_universe():
    ui = _read(ANDROID / "java/com/williamsbot/MainActivity.kt")
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")
    assert 'scannerJson.optString("scanner_state", status.scannerState)' in ui
    assert 'scannerJson.optInt("progress_symbols", 0)' in ui
    assert 'scannerJson.optJSONArray("scan_symbols")' in ui
    assert 'scannerJson.optString("scanner_error")' in ui
    assert 'scannerJson.optInt("progress_total", 10)' in ui
    assert '.put("progress_total", coreSymbols.size)' in runtime
    assert '.put("scan_symbols", JSONArray().apply { scanSymbols.forEach { put(it) } })' in runtime
    assert '"Сканирование выполняется • "' in ui


def test_android_ui_does_not_display_json_null_as_a_scanner_error():
    ui = _read(ANDROID / "java/com/williamsbot/MainActivity.kt")
    assert 'takeIf { it.isNotBlank() && it != "null" }' in ui
    assert 'takeUnless { it.isBlank() || it == "null" }' in ui



def test_futures_position_panel_maps_mark_price_and_computes_missing_notional_fields():
    ui = _read(ANDROID / "java/com/williamsbot/MainActivity.kt")
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")
    assert 'item.optDouble("mark_price", Double.NaN)' in ui
    assert 'val positionValue = item.optDouble(' in ui
    assert '(currentPrice ?: entryPrice) * kotlin.math.abs(quantity)' in ui
    assert 'val accountEquity = json.optDouble("futures_equity", Double.NaN)' in ui
    assert 'val allocation = item.optDouble(' in ui
    assert 'val pnlFraction = item.optDouble(' in ui
    assert "fmt(p.currentPrice)" in ui
    assert "fmt(p.currentPrice ?: status.price)" not in ui
    assert '.put("mark_price", mark)' in runtime
    assert '.put("futures_equity", if (restAccountReady) liveFuturesEquity else JSONObject.NULL)' in runtime


def test_position_chart_only_draws_levels_from_the_selected_contract():
    ui = _read(ANDROID / "java/com/williamsbot/MainActivity.kt")
    assert 'val selectedPosition = status.positions.firstOrNull { it.symbol == selectedSymbol }' in ui
    assert "val selectedPosition = status.positions.firstOrNull { it.symbol == selectedSymbol }" in ui
    assert "status.copy(" in ui
    assert "entry = selectedPosition?.entry" in ui
    assert "tp = selectedPosition?.take" in ui
    assert "sl = selectedPosition?.stop" in ui



def test_android_candidate_state_never_labels_short_as_confirmed_long():
    ui = _read(ANDROID / "java/com/williamsbot/MainActivity.kt")
    assert 'candidate.entrySignalType.startsWith("SHORT_", ignoreCase = true) -> "SHORT"' in ui
    assert '"H1 SETUP • SHORT"' in ui
    assert '"H1 SETUP • LONG"' in ui
    assert '"H1 SETUP • DIRECTION UNKNOWN"' in ui
    assert '"CONFIRMED LONG"' not in ui
    assert '"CONFIRMED SHORT"' not in ui
    assert "candidateDirection(candidate) == \"SHORT\" -> \"SHORT\"" in ui



def test_scanner_explains_the_same_newest_signal_that_execution_selects():
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")
    ui = _read(ANDROID / "java/com/williamsbot/MainActivity.kt")
    assert "val primarySignal = campaignEntryCandidate(candidate)" in runtime
    assert '.put("entry_signal_type", primarySignal?.type ?: JSONObject.NULL)' in runtime
    assert '.put("entry_trigger_price", primarySignal?.triggerPrice ?: JSONObject.NULL)' in runtime
    assert '.put("entry_protective_price", primarySignal?.protectivePrice ?: JSONObject.NULL)' in runtime
    assert "candidate.campaignSignals.firstOrNull()?.type" not in runtime
    assert "directionalAlignmentCount(candidate, primarySignal)" in runtime
    assert 'InfoRow("Согласование направления"' in ui


def test_directional_alignment_diagnostics_check_short_values_with_short_geometry():
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")
    fn = runtime.split("private fun directionalAlignmentCount(", 1)[1].split(
        "private fun williamsAlligatorLine(", 1
    )[0]
    assert 'signal.type.startsWith("SHORT_", ignoreCase = true)' in fn
    assert "lines.lips < lines.teeth && lines.teeth < lines.jaw && close < lines.lips" in fn
    assert "aoValue < 0.0" in fn
    assert "signal.triggerPrice < close" in fn
    assert "signal.protectivePrice > signal.triggerPrice" in fn



def test_android_futures_long_and_short_readiness_uses_h1_signal_not_m5_mtf_gate():
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")
    enrich = runtime.split("private fun enrichWithMtf(", 1)[1].split(
        "private fun toJson(", 1
    )[0]
    final_gate = enrich.split("val finalSignal =", 1)[1].split(
        "if (!finalSignal && baseCandidate.signal)", 1
    )[0]
    assert "primaryDecisionSignal != null" in final_gate
    assert "baseCandidate.atrPct in 0.0..0.08" in final_gate
    assert "baseCandidate.spreadPct in 0.0..1.5E-3" in final_gate or "baseCandidate.spreadPct in 0.0..0.0015" in final_gate
    assert "htfConfirmed &&" not in final_gate
    assert "junior != null" not in final_gate
    assert "entryInsideCorrection" not in final_gate
    assert "middle?.position !in listOf(2, 4)" not in final_gate
    assert "M15/M5 wave analysis is displayed as diagnostics only" in enrich
    assert "submitNativeFuturesCampaignEntry performs" in enrich
    submit = runtime.split("private fun submitNativeFuturesCampaignEntry(", 1)[1].split(
        "private fun performCampaignScan(", 1
    )[0]
    # The production path uses an explicit canonical mapper and fails closed for unknown types.
    assert 'when (campaignSignalDirection(signal.type))' in submit
    assert '"LONG" -> NativeFuturesCampaignCoordinator.Direction.LONG' in submit
    assert '"SHORT" -> NativeFuturesCampaignCoordinator.Direction.SHORT' in submit
    assert "WILLIAMS_SIGNAL_DIRECTION_UNKNOWN" in submit
    assert "val freshSignals = candidate.campaignSignals.filter" in submit
    assert "directionalSignalContextRank(it.type, d1Bull, d1Bear, h4Bull, h4Bear)" in submit
    assert "neither LONG nor SHORT is allowed by D1/H4" in submit
    assert "internal fun directionalSignalContextRank(" in runtime


def test_android_mtf_context_and_wave_diagnostics_support_bearish_structure():
    runtime = _read(ANDROID / "java/com/williamsbot/StandaloneRuntime.kt")
    wave = runtime.split("private fun waveInfo(", 1)[1].split(
        "private fun bullishTrendHint(", 1
    )[0]
    enrich = runtime.split("private fun enrichWithMtf(", 1)[1].split(
        "private fun toJson(", 1
    )[0]
    assert "val alligatorBearish" in runtime
    assert "val bearish =" in wave
    assert "alligatorBearish = bearish" in wave
    assert 'primaryDecisionSignal?.type?.startsWith("SHORT_", ignoreCase = true) == true' in enrich
    assert "htf4h.alligatorBearish && !htf4h.aoPositive" in enrich
    assert "htf1d.alligatorBearish && !htf1d.aoPositive" in enrich
    assert "it.position == 3 && it.direction == decisionDirection" in enrich
    assert "junior.alligatorBearish && !junior.aoPositive" in enrich
