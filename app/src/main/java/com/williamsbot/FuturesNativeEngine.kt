package com.williamsbot

import android.content.Context
import android.content.SharedPreferences
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Dedicated native Android USDⓈ-M Futures engine.
 *
 * It is intentionally isolated from NativeEngine's Spot positions and keys.
 * Only Binance Futures Demo is supported here. All order mutations must pass
 * through durable SQLite intent persistence; a timeout is never a rejection.
 * The first native release supports bounded initial LONG/SHORT campaigns,
 * exchange-side protection, structural trailing, hard exits and recovery.
 * Pyramiding and mainnet are deliberately not enabled by this class.
 */
// Synchronous SharedPreferences commits are deliberate for fail-closed execution
// state transitions: the caller must know whether persistence succeeded before
// any exchange mutation is allowed. Replacing these with apply() would weaken
// the write-before-submit durability contract.
// Android Lint's ApplySharedPref advice is inappropriate for these critical writes.
// KTX edit(commit=true) hides the Boolean returned by SharedPreferences.commit(),
// so standard Editor calls are retained to fail closed when a durable write fails.
@Suppress("ApplySharedPref", "UseKtx")
internal class FuturesNativeEngine(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val prefs: SharedPreferences,
    private val http: OkHttpClient,
    private val auditStore: TradingAuditStore,
    private val rateGuard: BinanceRateGuard
) {
    private data class Bar(
        val openTime: Long,
        val closeTime: Long,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Double
    )

    private data class Signal(
        val symbol: String,
        val direction: String,
        val type: String,
        val signalBarTime: Long,
        val trigger: Double,
        val stop: Double,
        val atr: Double,
        val reason: String
    )

    private data class Frame(
        val bars: List<Bar>,
        val atr: Double,
        val close: Double,
        val jaw: Double,
        val teeth: Double,
        val lips: Double,
        val ao: Double,
        val previousAo: Double,
        val ac: Double,
        val previousAc: Double,
        val bullish: Boolean,
        val bearish: Boolean,
        val awake: Boolean,
        val spreadPct: Double,
        val latestUpFractal: Pair<Int, Double>?,
        val latestDownFractal: Pair<Int, Double>?,
        val lastSignal: Signal?
    )

    private val lock = Any()
    private val cycleLock = Any()
    private val tickSizeCache = java.util.concurrent.ConcurrentHashMap<String, Double>()
    private val stopLoop = AtomicBoolean(false)

    @Volatile private var running = false
    @Volatile private var paused = true
    @Volatile private var killLatched = prefs.getBoolean("futures_kill_latched", false)
    @Volatile private var reconcileRequired = false
    @Volatile private var lastError: String? = null
    @Volatile private var lastScanAtMs = 0L
    @Volatile private var scanCount = 0
    @Volatile private var lastScanSummary = JSONObject()
    private var worker: Thread? = null
    private var client: BinanceUsdmFuturesClient? = null

    private val maxLeverage = 1
    private val maxPositions: Int
        get() = prefs.getInt("futures_max_positions", 3).coerceIn(1, 5)
    private val perCampaignRiskFraction = 0.0025
    private val portfolioRiskFraction = 0.01
    private val maxDailyLossFraction = 0.03
    private val feeBufferPerSideFraction = 0.001
    private val slippageBufferFraction = 0.0015
    private val maxSpreadFraction = 0.0015
    private val minRiskReward = 1.5
    private val pollMillis = 20_000L

    private fun futuresKey(): String = prefs.getString("futures_api_key", "")?.trim().orEmpty()
    private fun futuresSecret(): String = prefs.getString("futures_api_secret", "")?.trim().orEmpty()

    private fun api(): BinanceUsdmFuturesClient {
        synchronized(lock) {
            val key = futuresKey()
            val secret = futuresSecret()
            require(key.isNotBlank() && secret.isNotBlank()) {
                "Configure separate Binance USDⓈ-M Futures Demo credentials first"
            }
            val existing = client
            if (existing != null) return existing
            return BinanceUsdmFuturesClient(
                apiKey = key,
                apiSecret = secret,
                http = http,
                beforeRequest = { rateGuard.beforeRequest() },
                recordRestCall = { method, path, code, request, response ->
                    auditStore.recordRestCall(method, path, code, request, response)
                }
            ).also { client = it }
        }
    }

    fun configureCredentials(body: JSONObject): JSONObject {
        val key = body.optString("api_key").trim()
        val secret = body.optString("api_secret").trim()
        require(key.isNotBlank() && secret.isNotBlank()) {
            "Futures API key and secret are required"
        }
        require(!body.optBoolean("allow_live", false)) {
            "Mainnet Futures is disabled in the native Android runtime; only Demo is supported"
        }
        val symbols = normalizeSymbols(body.optJSONArray("symbols"))
        val interval = normalizeInterval(body.optString("interval", "5m"))
        val trial = BinanceUsdmFuturesClient(
            apiKey = key,
            apiSecret = secret,
            http = http,
            beforeRequest = { rateGuard.beforeRequest() },
            recordRestCall = { method, path, code, request, response ->
                auditStore.recordRestCall(method, path, code, request, response)
            }
        )
        trial.syncTime()
        val account = trial.account()
        require(account.has("availableBalance")) {
            "Futures account response does not contain availableBalance"
        }
        trial.requireOneWayMode()

        synchronized(lock) {
            if (running && (key != futuresKey() || secret != futuresSecret())) {
                throw IllegalStateException("Stop the Futures engine before changing Futures credentials")
            }
            val saved = prefs.edit()
                .putString("futures_api_key", key)
                .putString("futures_api_secret", secret)
                .putString("futures_symbols", symbols.joinToString(","))
                .putString("futures_interval", interval)
                .commit()
            check(saved) { "Futures credentials did not commit to encrypted preferences" }
            check(futuresKey() == key && futuresSecret() == secret) {
                "Futures credentials read-back verification failed"
            }
            client = trial
        }
        return status().put("configured", true).put("credential_validation", "PASS")
    }

    fun clearCredentials(): JSONObject {
        synchronized(cycleLock) {
            require(!running) { "Stop the Futures engine before removing Futures credentials" }
            val exchange = if (futuresKey().isNotBlank()) runCatching { api() }.getOrNull() else null
            val campaigns = auditStore.activeFuturesCampaigns()
            require(campaigns.isEmpty()) {
                "Close and reconcile all Futures campaigns before removing credentials"
            }
            if (exchange != null) {
                require(exchange.positionRisk().let { rows ->
                    (0 until rows.length()).all { i ->
                        abs(rows.optJSONObject(i)?.optString("positionAmt")?.toDoubleOrNull() ?: 0.0) < 1e-12
                    }
                }) { "Cannot remove Futures credentials while any Futures position is open" }
                require(exchange.openOrders().length() == 0 && exchange.openAlgoOrders().length() == 0) {
                    "Cannot remove Futures credentials while exchange orders remain open"
                }
            }
            val credentialsRemoved = prefs.edit()
                .remove("futures_api_key")
                .remove("futures_api_secret")
                .remove("futures_symbols")
                .remove("futures_interval")
                .commit()
            check(credentialsRemoved) {
                "Futures credentials removal was not durably committed"
            }
            client = null
            lastError = null
            reconcileRequired = false
            return status().put("configured", false).put("cleared", true)
        }
    }

    fun start(): JSONObject {
        synchronized(cycleLock) {
            require(futuresKey().isNotBlank() && futuresSecret().isNotBlank()) {
                "Configure separate Futures Demo credentials before START"
            }
            if (worker?.isAlive == true && !running) {
                throw IllegalStateException("Previous Futures worker is still shutting down; refusing to start a second loop")
            }
            if (killLatched) {
                throw IllegalStateException("KILL_SWITCH_LATCHED; use explicit recovery after exchange reconciliation")
            }
            if (running) {
                paused = false
                return status().put("started", false).put("reason", "already_running")
            }

            val exchange = api()
            exchange.syncTime()
            exchange.requireOneWayMode()
            val account = exchange.account()
            require(equity(account) > 0.0) { "Futures account equity is invalid" }
            reconcileRequired = false
            val recovered = reconcileAll(exchange)
            val unresolved = recovered.optInt("unresolved", 0)
            if (unresolved > 0) reconcileRequired = true

            // Never auto-adopt a Futures position that has no matching persisted
            // Futures campaign. Spot positions do not affect this account check.
            if (hasUnmanagedFuturesPositions(exchange)) {
                reconcileRequired = true
                lastError = "Unmanaged or malformed Futures exposure detected; new entries remain blocked"
            }
            if (runCatching { hasUnmanagedFuturesOrders(exchange) }.getOrDefault(true)) {
                reconcileRequired = true
                lastError = "Unmanaged or unverified account-wide Futures orders detected; new entries remain blocked"
            }

            if (!reconcileRequired) for (symbol in symbols()) {
                if (hasActiveCampaign(symbol)) continue
                if (hasLivePosition(exchange, symbol)) continue
                if (exchange.openOrders(symbol).length() > 0 || exchange.openAlgoOrders(symbol).length() > 0) {
                    reconcileRequired = true
                    lastError = "$symbol has unmanaged open Futures orders"
                    continue
                }
                runCatching { prepareSymbolWithDurableIntent(exchange, symbol) }
                    .onFailure {
                        lastError = "$symbol Futures preflight: ${it.message ?: it.javaClass.simpleName}"
                        reconcileRequired = true
                    }
            }

            stopLoop.set(false)
            paused = false
            running = true
            worker = Thread({
                while (running && !stopLoop.get()) {
                    try {
                        cycle()
                        lastError = null
                    } catch (x: Exception) {
                        lastError = "${x.javaClass.simpleName}: ${x.message ?: "Futures cycle failed"}"
                        auditEvent("futures_cycle_error", JSONObject().put("error", lastError))
                    }
                    try {
                        Thread.sleep(pollMillis)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
                running = false
            }, "williams-usdm-futures").apply {
                isDaemon = true
                start()
            }
            return status().put("started", true)
        }
    }

    fun pause(): JSONObject {
        // Flip the admission flag immediately, then serialize cancellation
        // against the active scan. An already-armed conditional entry can
        // still open exposure even after the scanner has paused.
        paused = true
        val cancellations = synchronized(cycleLock) {
            val campaigns = auditStore.activeFuturesCampaigns()
            val hasPendingEntries = campaigns.any {
                it.optString("state").uppercase(Locale.US) in setOf("ENTRY_PENDING", "RECONCILE_REQUIRED") &&
                    it.optString("entry_client_algo_id").isNotBlank()
            }
            val hasUnresolvedCampaign = campaigns.any {
                it.optString("state").uppercase(Locale.US) == "RECONCILE_REQUIRED"
            }
            val hasUnresolvedIntent = auditStore.pendingFuturesIntents().any { isUnresolvedIntent(it) }
            if (hasPendingEntries) {
                runCatching { cancelPendingEntries(api(), "PAUSE") }.getOrElse { error ->
                    reconcileRequired = true
                    lastError = "Pause could not verify pending-entry cancellation: ${error.message}"
                    JSONArray().put(
                        JSONObject()
                            .put("state", "RECONCILE_REQUIRED")
                            .put("reason", lastError)
                    )
                }
            } else if (hasUnresolvedCampaign || hasUnresolvedIntent) {
                reconcileRequired = true
                JSONArray().put(
                    JSONObject()
                        .put("state", "RECONCILE_REQUIRED")
                        .put("reason", "Unresolved Futures campaign/intent requires the management monitor to remain active")
                )
            } else {
                JSONArray()
            }
        }
        val unresolvedCancellation = (0 until cancellations.length()).any {
            cancellations.optJSONObject(it)?.optString("state") == "RECONCILE_REQUIRED"
        }
        val activeCampaignsRemain = auditStore.activeFuturesCampaigns().isNotEmpty()
        val unresolvedIntentRemain = auditStore.pendingFuturesIntents().any { isUnresolvedIntent(it) }
        val unresolved = unresolvedCancellation || activeCampaignsRemain || unresolvedIntentRemain
        auditEvent(
            "futures_paused",
            JSONObject()
                .put("reason", "new entries disabled; pending entries cancelled or reconciled")
                .put("pending_entry_cancellations", cancellations)
                .put("unresolved", unresolved)
        )
        return status()
            .put("state", "PAUSED")
            .put("pending_entry_cancellations", cancellations)
            .put("management_only_monitor_required", unresolved)
    }

    fun resume(): JSONObject {
        require(!killLatched) { "KILL_SWITCH_LATCHED; recover before resume" }
        paused = false
        return status().put("state", if (running) "RUNNING" else "STOPPED")
    }

    fun stop(): JSONObject {
        // Do not stop the monitor if a pending entry could still trigger. The
        // worker stays paused and continues reconciliation/protection instead.
        val pauseResult = pause()
        if (pauseResult.optBoolean("management_only_monitor_required", false)) {
            lastError = "Stop blocked: pending-entry cancellation is unresolved; Futures monitor remains active"
            throw IllegalStateException(lastError!!)
        }
        running = false
        stopLoop.set(true)
        val active = worker
        if (active != null && active !== Thread.currentThread()) {
            active.interrupt()
            val stopped = runCatching {
                active.join(5000L)
                !active.isAlive
            }.getOrDefault(false)
            if (!stopped) {
                lastError = "Futures worker did not stop cleanly; restart is blocked until it exits"
                throw IllegalStateException(lastError!!)
            }
        }
        return status().put("state", "STOPPED").put("note", "Exchange-side protective orders remain active")
    }

    fun kill(): JSONObject {
        // Latch synchronously before waiting for the cycle lock. An in-flight
        // scan sees the latch and cannot submit further entry orders.
        synchronized(lock) {
            killLatched = true
            paused = true
            check(prefs.edit().putBoolean("futures_kill_latched", true).commit()) {
                "Kill-switch latch could not be persisted; refusing to claim the kill switch is active"
            }
        }
        synchronized(cycleLock) {
            val exchange = api()
            val results = cancelPendingEntries(exchange, "KILL_SWITCH")
            val campaigns = auditStore.activeFuturesCampaigns()
            for (campaign in campaigns) {
                val symbol = campaign.optString("symbol").uppercase(Locale.US)
                try {
                    val pos = position(exchange, symbol)
                    val amount = pos.optString("positionAmt").toDoubleOrNull()
                        ?: throw FuturesApiException("$symbol positionAmt is missing or malformed during KILL")
                    if (!amount.isFinite()) throw FuturesApiException("$symbol positionAmt is non-finite during KILL")
                    if (abs(amount) > 1e-12) {
                        results.put(exitPosition(exchange, campaign, "KILL_SWITCH"))
                    } else if (campaign.optString("state") == "ENTRY_PENDING") {
                        cancelPendingEntry(exchange, campaign, "KILL_SWITCH")
                        results.put(JSONObject().put("symbol", symbol).put("action", "ENTRY_CANCEL_REQUESTED"))
                    }
                } catch (x: Exception) {
                    reconcileRequired = true
                    results.put(JSONObject()
                        .put("symbol", symbol)
                        .put("state", "RECONCILE_REQUIRED")
                        .put("error", x.message ?: x.javaClass.simpleName))
                }
            }
            auditEvent("futures_kill_switch", JSONObject().put("results", results))
            return status().put("state", "KILL_SWITCH_LATCHED").put("exit_results", results)
        }
    }

    fun recoverAndResetKill(): JSONObject {
        synchronized(cycleLock) {
            val exchange = api()
            exchange.syncTime()
            exchange.requireOneWayMode()
            val reconciliation = reconcileAll(exchange)
            val anyPosition = exchange.positionRisk().let { rows ->
                (0 until rows.length()).any { i ->
                    val row = rows.optJSONObject(i)
                        ?: throw FuturesApiException("Malformed positionRisk row during kill recovery")
                    val symbol = row.optString("symbol").uppercase(Locale.US)
                    val rawAmount = row.optString("positionAmt")
                    val amount = rawAmount.toDoubleOrNull()
                    if (symbol.isBlank() || amount == null || !amount.isFinite()) {
                        throw FuturesApiException("Invalid positionRisk symbol/positionAmt during kill recovery")
                    }
                    abs(amount) > 1e-12
                }
            }
            val unresolvedIntents = unresolvedIntentCount(exchange)
            val unmanagedOrders = runCatching { hasUnmanagedFuturesOrders(exchange) }.getOrDefault(true)
            if (reconciliation.optInt("unresolved", 0) > 0 || unresolvedIntents > 0 || anyPosition || unmanagedOrders) {
                reconcileRequired = true
                throw IllegalStateException(
                    "Recovery reset denied: positions, open orders, or intents remain unresolved; flat and verified state is required"
                )
            }
            check(prefs.edit().putBoolean("futures_kill_latched", false).commit()) {
                "Kill-switch reset could not be persisted; latch remains active"
            }
            killLatched = false
            reconcileRequired = false
            lastError = null
            return status().put("recovered", true).put("state", "FLAT")
        }
    }

    fun status(): JSONObject {
        val campaigns = runCatching { auditStore.activeFuturesCampaigns() }.getOrDefault(emptyList())
        val active = JSONArray()
        campaigns.forEach { campaign ->
            active.put(JSONObject()
                .put("campaign_id", campaign.optString("campaign_id"))
                .put("symbol", campaign.optString("symbol"))
                .put("direction", campaign.optString("direction"))
                .put("state", campaign.optString("state"))
                .put("entry_trigger", campaign.optDouble("entry_trigger", 0.0))
                .put("stop_price", campaign.optDouble("stop_price", 0.0))
                .put("quantity", campaign.optDouble("quantity", 0.0))
                .put("risk_quote", campaign.optDouble("risk_quote", 0.0))
                .put("protection_active", campaign.optBoolean("protection_active", false)))
        }
        return JSONObject()
            .put("runtime", "BINANCE_USDM_FUTURES_NATIVE")
            .put("product", "BINANCE_USDM_FUTURES")
            .put("testnet", true)
            .put("configured", futuresKey().isNotBlank() && futuresSecret().isNotBlank())
            .put("running", running)
            .put("paused", paused)
            .put("kill_latched", killLatched)
            .put("reconciliation_required", reconcileRequired)
            .put("allow_long", true)
            .put("allow_short", true)
            .put("symbols", JSONArray(symbols()))
            .put("interval", interval())
            .put("max_leverage", maxLeverage)
            .put("max_positions", maxPositions)
            .put("scan_count", scanCount)
            .put("last_scan_at_ms", lastScanAtMs)
            .put("last_scan", JSONObject(lastScanSummary.toString()))
            .put("last_error", lastError ?: "")
            .put("open_campaigns", active)
            .put("unresolved_intents", runCatching { auditStore.pendingFuturesIntents().count { isUnresolvedIntent(it) } }.getOrDefault(-1))
            .put("execution_contract", "durable-intent-before-mutation; no blind mutation retries; reduce-only exits")
    }

    private fun symbols(): List<String> {
        val raw = prefs.getString("futures_symbols", DEFAULT_SYMBOLS.joinToString(",")).orEmpty()
        val parsed = raw.split(",").map { it.trim().uppercase(Locale.US) }.filter { it.isNotBlank() }.distinct()
        return parsed.filter { it.matches(Regex("^[A-Z0-9]{2,20}USDT$")) }.take(10).ifEmpty { DEFAULT_SYMBOLS }
    }

    private fun interval(): String = normalizeInterval(prefs.getString("futures_interval", "5m") ?: "5m")

    private fun normalizeSymbols(array: JSONArray?): List<String> {
        val raw = if (array != null && array.length() > 0) {
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
        } else DEFAULT_SYMBOLS
        val result = raw.map { it.trim().uppercase(Locale.US) }.distinct()
        require(result.isNotEmpty() && result.size <= 10) { "Select between 1 and 10 Futures symbols" }
        result.forEach { symbol ->
            require(symbol.matches(Regex("^[A-Z0-9]{2,20}USDT$"))) {
                "Only USDT-margined symbols are supported: $symbol"
            }
        }
        return result
    }

    private fun normalizeInterval(value: String): String {
        val known = setOf("1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "8h", "12h", "1d")
        val normalized = value.trim().lowercase(Locale.US)
        require(normalized in known) { "Unsupported Futures execution timeframe: $value" }
        return normalized
    }

    private fun parentInterval(value: String): String = when (normalizeInterval(value)) {
        "1m", "3m", "5m" -> "15m"
        "15m", "30m" -> "1h"
        "1h", "2h" -> "4h"
        "4h", "6h", "8h", "12h" -> "1d"
        else -> "15m"
    }

    private fun clientOrderId(prefix: String): String = prefix + UUID.randomUUID().toString().replace("-", "").take(24)

    private fun newIntentId(): String = UUID.randomUUID().toString()

    private fun executeMutation(
        exchange: BinanceUsdmFuturesClient,
        symbol: String,
        operation: String,
        direction: String,
        side: String,
        clientId: String,
        params: JSONObject,
        call: () -> JSONObject
    ): JSONObject {
        val id = newIntentId()
        auditStore.saveFuturesIntentBeforeMutation(
            intentId = id,
            clientId = clientId,
            symbol = symbol,
            operation = operation,
            direction = direction,
            side = side,
            paramsJson = params.toString()
        )
        auditStore.updateFuturesIntent(id, "SUBMITTING")
        try {
            val response = call()
            try {
                auditStore.updateFuturesIntent(id, "SUBMITTED", response.toString())
            } catch (writeFailure: Exception) {
                reconcileRequired = true
                lastError = "Exchange mutation confirmed but result persistence failed; reconciliation required"
                throw FuturesApiException(lastError!!, outcomeUnknown = true, payload = response.toString(), cause = writeFailure)
            }
            return response
        } catch (x: Exception) {
            val unknown = x is FuturesApiException && x.outcomeUnknown
            runCatching {
                auditStore.updateFuturesIntent(
                    id,
                    if (unknown) "UNKNOWN" else "REJECTED",
                    error = x.message ?: x.javaClass.simpleName
                )
            }.onFailure {
                reconcileRequired = true
            }
            if (unknown) {
                reconcileRequired = true
                lastError = x.message ?: "Ambiguous Futures mutation"
            }
            throw x
        }
    }

    private fun prepareSymbolWithDurableIntent(exchange: BinanceUsdmFuturesClient, symbol: String) {
        if (hasActiveCampaign(symbol)) return
        if (hasLivePosition(exchange, symbol)) {
            throw FuturesApiException("$symbol has an exchange position; refusing margin/leverage changes")
        }
        if (exchange.openOrders(symbol).length() > 0 || exchange.openAlgoOrders(symbol).length() > 0) {
            throw FuturesApiException("$symbol has open orders; refusing margin/leverage changes")
        }
        val pseudoClientId = clientOrderId("W2FC_")
        executeMutation(
            exchange,
            symbol,
            "PREPARE_SYMBOL",
            "NONE",
            "NONE",
            pseudoClientId,
            JSONObject().put("marginType", "ISOLATED").put("leverage", 1),
        ) {
            exchange.prepareFlatSymbol(symbol)
            JSONObject().put("symbol", symbol).put("marginType", "ISOLATED").put("leverage", 1)
        }
        val configuration = exchange.symbolConfiguration(symbol)
        require(
            configuration.optString("marginType").equals("ISOLATED", true) &&
                configuration.optString("leverage").toIntOrNull() == 1
        ) {
            "$symbol did not confirm isolated 1x settings after preparation"
        }
    }

    private fun cycle() {
        synchronized(cycleLock) {
            val exchange = api()
            exchange.syncTime()
            val account = exchange.account()
            val equity = equity(account)
            if (equity <= 0.0) throw FuturesApiException("Futures equity is invalid")
            val recovered = reconcileAll(exchange)
            val unowned = hasUnmanagedFuturesPositions(exchange)
            val unownedOrders = runCatching { hasUnmanagedFuturesOrders(exchange) }.getOrDefault(true)
            val permissionError = runCatching { exchange.requireTradePermissionAndSingleAssetMode() }.exceptionOrNull()
            val unknownIntents = unresolvedIntentCount(exchange)
            reconcileRequired = recovered.optInt("unresolved", 0) > 0 || unowned || unownedOrders || unknownIntents > 0
            if (unowned) lastError = "Unmanaged or malformed Futures position blocks new entries"
            if (unownedOrders) lastError = "Unmanaged or unverified account-wide Futures orders block new entries"
            manageExistingCampaigns(exchange)
            if (permissionError != null) {
                reconcileRequired = true
                lastError = "Futures account permission/margin preflight blocked new entries: ${permissionError.message}"
            }
            val baselinePersisted = updateDailyBaseline(equity)
            val dailyLoss = if (baselinePersisted) dailyLossFraction(equity) else 1.0
            var lockoutCancellations = JSONArray()
            if (!killLatched && !paused && (reconcileRequired || dailyLoss >= maxDailyLossFraction)) {
                val reason = if (reconcileRequired) "RECONCILE_REQUIRED" else "DAILY_RISK_LOCKOUT"
                lockoutCancellations = cancelPendingEntries(exchange, reason)
            }
            if (killLatched) {
                val exits = enforceKillOnCampaigns(exchange)
                lastScanSummary = JSONObject()
                    .put("state", "KILL_SWITCH_LATCHED")
                    .put("new_entries", 0)
                    .put("reconciliation", recovered)
                    .put("exit_actions", exits)
                return
            }
            if (paused) {
                lastScanSummary = JSONObject().put("state", "PAUSED").put("new_entries", 0)
                return
            }
            if (reconcileRequired) {
                lastScanSummary = JSONObject()
                    .put("state", "RECONCILE_REQUIRED")
                    .put("unresolved_intents", unknownIntents)
                    .put("reconciliation", recovered)
                    .put("pending_entry_cancellations", lockoutCancellations)
                    .put("new_entries", 0)
                return
            }
            if (dailyLoss >= maxDailyLossFraction) {
                lastScanSummary = JSONObject()
                    .put("state", "DAILY_RISK_LOCKOUT")
                    .put("daily_loss_fraction", dailyLoss)
                    .put("limit_fraction", maxDailyLossFraction)
                    .put("pending_entry_cancellations", lockoutCancellations)
                    .put("new_entries", 0)
                return
            }
            val active = auditStore.activeFuturesCampaigns()
                .filter { it.optString("state") !in setOf("CLOSED", "FLAT") }
            if (active.size >= maxPositions) {
                lastScanSummary = JSONObject().put("state", "POSITION_CAPACITY").put("new_entries", 0)
                return
            }
            val available = account.optString("availableBalance").toDoubleOrNull() ?: 0.0
            var reservedRiskQuote = active.sumOf { it.optDouble("risk_quote", 0.0).coerceAtLeast(0.0) }
            var newEntries = 0
            val decisions = JSONArray()
            for (symbol in symbols()) {
                if (killLatched || paused || !running || stopLoop.get()) break
                if (activeCampaign(symbol)) continue
                try {
                    if (exchange.openOrders(symbol).length() > 0 || exchange.openAlgoOrders(symbol).length() > 0) {
                        reconcileRequired = true
                        decisions.put(JSONObject().put("symbol", symbol).put("action", "BLOCKED").put("reason", "unmanaged open order"))
                        continue
                    }
                    val signal = findSignal(exchange, symbol)
                    if (signal == null) {
                        decisions.put(JSONObject().put("symbol", symbol).put("action", "WAIT").put("reason", "no valid directional Williams trigger"))
                        continue
                    }
                    val remainingPortfolioRisk = max(0.0, equity * portfolioRiskFraction - reservedRiskQuote)
                    val riskBudget = min(equity * perCampaignRiskFraction, remainingPortfolioRisk)
                    if (riskBudget <= 0.0) {
                        decisions.put(JSONObject().put("symbol", symbol).put("action", "BLOCKED").put("reason", "portfolio risk budget exhausted"))
                        continue
                    }
                    if (killLatched || paused || !running || stopLoop.get()) break
                    val quantity = sizePosition(exchange, symbol, signal, equity, available, riskBudget)
                    if (killLatched || paused || !running || stopLoop.get()) break
                    val result = armEntry(exchange, signal, quantity, riskBudget, equity)
                    decisions.put(result)
                    if (result.optString("action") == "ENTRY_ARMED") {
                        newEntries++
                        // Reserve budget immediately inside the same scan cycle;
                        // otherwise every symbol could spend the same remaining 1%.
                        reservedRiskQuote += riskBudget
                    }
                    if (active.size + newEntries >= maxPositions) break
                } catch (x: Exception) {
                    decisions.put(JSONObject().put("symbol", symbol).put("action", "WAIT").put("reason", x.message ?: x.javaClass.simpleName))
                    if (x is FuturesApiException && x.outcomeUnknown) {
                        reconcileRequired = true
                        lastError = x.message ?: "Ambiguous exchange mutation"
                    }
                }
                // An ambiguous exchange result freezes the rest of this scan;
                // never open another symbol until order/position reconciliation.
                if (reconcileRequired) break
            }
            scanCount++
            lastScanAtMs = System.currentTimeMillis()
            lastScanSummary = JSONObject()
                .put("state", if (reconcileRequired) "RECONCILE_REQUIRED" else "RUNNING")
                .put("cycle", scanCount)
                .put("actions", decisions)
                .put("active_campaigns", active.size)
                .put("new_entries", newEntries)
                .put("daily_loss_fraction", dailyLoss)
        }
    }

    private fun equity(account: JSONObject): Double {
        fun field(name: String): Double? {
            if (!account.has(name) || account.isNull(name)) return null
            val raw = account.optString(name).trim()
            if (raw.isEmpty()) return null
            // A present but malformed authoritative equity field must not be
            // silently replaced by a less authoritative balance field.
            return raw.toDoubleOrNull() ?: Double.NaN
        }
        val value = field("totalMarginBalance")
            ?: field("totalWalletBalance")
            ?: field("availableBalance")
            ?: 0.0
        return value.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
    }

    private fun updateDailyBaseline(equity: Double): Boolean {
        if (!equity.isFinite() || equity <= 0.0) return false
        val day = Instant.ofEpochMilli(System.currentTimeMillis())
            .atZone(ZoneOffset.UTC).toLocalDate().toString()
        val savedDay = prefs.getString("futures_daily_day", "")
        if (savedDay != day) {
            // A failed synchronous commit must block entries this cycle.
            return prefs.edit()
                .putString("futures_daily_day", day)
                .putString("futures_daily_equity", equity.toString())
                .commit()
        }
        // If today's baseline is missing/corrupt, do not replace it with the
        // current equity: dailyLossFraction() returns a lockout instead.
        return true
    }

    private fun dailyLossFraction(equity: Double): Double {
        if (!equity.isFinite() || equity <= 0.0) return 1.0
        val raw = prefs.getString("futures_daily_equity", "").orEmpty()
        val baseline = raw.toDoubleOrNull() ?: return 1.0
        if (!baseline.isFinite() || baseline <= 0.0) return 1.0
        return max(0.0, (baseline - equity) / baseline)
    }

    private fun findSignal(exchange: BinanceUsdmFuturesClient, symbol: String): Signal? {
        val tf = interval()
        val primary = analyseFrame(exchange, symbol, tf) ?: return null
        val higherTf = parentInterval(tf)
        val higher = analyseFrame(exchange, symbol, higherTf) ?: return null
        val candidates = listOfNotNull(primary.lastSignal)
            .filter { signal ->
                // This native release intentionally does not pyramid yet.
                // WM2 Super AO and WM3 fractal are campaign ADD_ONs, not valid
                // substitutes for the initial WM1 reversal entry. Until the
                // add-on admission/fill/reconciliation path is implemented,
                // never promote those signals into a new campaign.
                if (signal.type != "REVERSAL") {
                    false
                } else if (signal.direction == "LONG") {
                    higher.bullish && higher.ao > 0.0 && higher.ac > 0.0
                } else {
                    higher.bearish && higher.ao < 0.0 && higher.ac < 0.0
                }
            }
        if (candidates.isEmpty()) return null
        val chosen = candidates.minByOrNull { it.signalBarTime } ?: return null
        val mark = exchange.markPrice(symbol)
        if (chosen.direction == "LONG" && !(chosen.stop < mark && mark < chosen.trigger)) return null
        if (chosen.direction == "SHORT" && !(chosen.trigger < mark && mark < chosen.stop)) return null
        val (bid, ask) = exchange.bookTicker(symbol)
        val mid = (bid + ask) / 2.0
        if (mid <= 0.0 || (ask - bid) / mid > maxSpreadFraction) return null
        return chosen
    }

    private fun analyseFrame(exchange: BinanceUsdmFuturesClient, symbol: String, timeframe: String): Frame? {
        val payload = exchange.klines(symbol, timeframe, 180)
        val now = System.currentTimeMillis()
        val bars = mutableListOf<Bar>()
        for (i in 0 until payload.length()) {
            val row = payload.optJSONArray(i) ?: continue
            if (row.length() < 7) continue
            val openTime = row.optLong(0, 0L)
            val closeTime = row.optLong(6, 0L)
            if (openTime <= 0L || closeTime >= now) continue // ignore a forming candle
            val o = row.optString(1).toDoubleOrNull() ?: continue
            val h = row.optString(2).toDoubleOrNull() ?: continue
            val l = row.optString(3).toDoubleOrNull() ?: continue
            val close = row.optString(4).toDoubleOrNull() ?: continue
            val volume = row.optString(5).toDoubleOrNull() ?: 0.0
            if (!listOf(o, h, l, close, volume).all(Double::isFinite) || h < l || l <= 0.0 || close <= 0.0) continue
            bars += Bar(openTime, closeTime, o, h, l, close, volume)
        }
        if (bars.size < 80) return null
        val tickSize = tickSizeCache.computeIfAbsent(symbol.uppercase(Locale.US)) {
            exchange.symbolFilters(symbol)["PRICE_FILTER"]
                ?.optString("tickSize")?.toDoubleOrNull()
                ?.takeIf { value -> value.isFinite() && value > 0.0 }
                ?: throw FuturesApiException("$symbol Futures PRICE_FILTER tickSize is unavailable")
        }
        return buildFrame(symbol, bars, tickSize)
    }

    private fun buildFrame(symbol: String, bars: List<Bar>, tickSize: Double): Frame {
        val median = bars.map { (it.high + it.low) / 2.0 }
        val jawRaw = smma(median, 13)
        val teethRaw = smma(median, 8)
        val lipsRaw = smma(median, 5)
        val jaw = shifted(jawRaw, 8)
        val teeth = shifted(teethRaw, 5)
        val lips = shifted(lipsRaw, 3)
        val ao = median.indices.map { i ->
            val fast = smaAt(median, i, 5)
            val slow = smaAt(median, i, 34)
            if (fast.isFinite() && slow.isFinite()) fast - slow else Double.NaN
        }
        val ac = ao.indices.map { i ->
            val average = smaAt(ao, i, 5)
            if (ao[i].isFinite() && average.isFinite()) ao[i] - average else Double.NaN
        }
        val i = bars.lastIndex
        val validLines = listOf(jaw[i], teeth[i], lips[i]).all { it.isFinite() && it > 0.0 }
        val close = bars[i].close
        val spreadPct = if (validLines && close > 0.0) {
            (listOf(jaw[i], teeth[i], lips[i]).maxOrNull()!! - listOf(jaw[i], teeth[i], lips[i]).minOrNull()!!) / close
        } else 0.0
        val bullish = validLines && lips[i] > teeth[i] && teeth[i] > jaw[i] && close > lips[i]
        val bearish = validLines && lips[i] < teeth[i] && teeth[i] < jaw[i] && close < lips[i]
        val atrValues = atrSeries(bars)
        val atr = atrValues.lastOrNull() ?: 0.0
        val aoNow = ao[i].takeIf(Double::isFinite) ?: 0.0
        val aoPrev = ao.getOrNull(i - 1)?.takeIf(Double::isFinite) ?: 0.0
        val acNow = ac[i].takeIf(Double::isFinite) ?: 0.0
        val acPrev = ac.getOrNull(i - 1)?.takeIf(Double::isFinite) ?: 0.0
        val awake = spreadPct >= MIN_ALLIGATOR_SPREAD_PCT

        val upFractal = latestFractal(bars, up = true)
        val downFractal = latestFractal(bars, up = false)
        var signal: Signal? = null
        if (atr > 0.0 && validLines && awake) {
            val configLong = bullish && aoNow > 0.0 && acNow > 0.0
            val configShort = bearish && aoNow < 0.0 && acNow < 0.0

            // WM1: reversal bar is context; the stop beyond its extreme is the
            // actionable price trigger. Use the current mouth angle/momentum as
            // a conservative native execution gate.
            if (signal == null && configLong) {
                signal = reversalSignal(
                    symbol, bars, "LONG", jaw, teeth, lips, ao, ac, atr, tickSize
                )
            }
            if (signal == null && configShort) {
                signal = reversalSignal(
                    symbol, bars, "SHORT", jaw, teeth, lips, ao, ac, atr, tickSize
                )
            }

            // WM3: a confirmed fractal outside the Teeth/Balancing Line is a
            // pending stop-entry, never a MARKET chase after the trigger passed.
            if (signal == null && configLong && upFractal != null) {
                val (center, level) = upFractal
                // Use the real exchange tick; pre-submit filters validate rounding again.
                val entryTrigger = level + tickSize
                val stop = bars[center].low - tickSize
                if (level > teeth[i] && bars[i].close < entryTrigger && stop > 0.0) {
                    signal = Signal(symbol, "LONG", "FRACTAL", bars[center].openTime, entryTrigger, stop, atr, "WM3 buy fractal outside Teeth; stop-entry trigger above fractal")
                }
            }
            if (signal == null && configShort && downFractal != null) {
                val (center, level) = downFractal
                val entryTrigger = level - tickSize
                val stop = bars[center].high + tickSize
                if (level < teeth[i] && bars[i].close > entryTrigger && stop > entryTrigger) {
                    signal = Signal(symbol, "SHORT", "FRACTAL", bars[center].openTime, entryTrigger, stop, atr, "WM3 sell fractal outside Teeth; stop-entry trigger below fractal")
                }
            }
            // WM2: three AO histogram bars in the same direction, following an
            // outside fractal, create a separate stop-triggered continuation.
            if (signal == null && configLong && superAo(ao, bars, "LONG", teeth[i], upFractal)) {
                val row = bars[i]
                val trigger = row.high + tickSize
                val stop = row.low - tickSize
                if (row.close < trigger && stop > 0.0) {
                    signal = Signal(symbol, "LONG", "SUPER_AO", row.openTime, trigger, stop, atr, "WM2 Super AO: three rising AO bars after outside up fractal")
                }
            }
            if (signal == null && configShort && superAo(ao, bars, "SHORT", teeth[i], downFractal)) {
                val row = bars[i]
                val trigger = row.low - tickSize
                val stop = row.high + tickSize
                if (row.close > trigger && stop > trigger) {
                    signal = Signal(symbol, "SHORT", "SUPER_AO", row.openTime, trigger, stop, atr, "WM2 Super AO: three falling AO bars after outside down fractal")
                }
            }
        }
        return Frame(
            bars = bars,
            atr = atr,
            close = close,
            jaw = jaw[i],
            teeth = teeth[i],
            lips = lips[i],
            ao = aoNow,
            previousAo = aoPrev,
            ac = acNow,
            previousAc = acPrev,
            bullish = bullish,
            bearish = bearish,
            awake = awake,
            spreadPct = spreadPct,
            latestUpFractal = upFractal,
            latestDownFractal = downFractal,
            lastSignal = signal
        )
    }

    private fun reversalSignal(
        symbol: String,
        bars: List<Bar>,
        direction: String,
        jaw: List<Double>,
        teeth: List<Double>,
        lips: List<Double>,
        ao: List<Double>,
        ac: List<Double>,
        atr: Double,
        tickSize: Double
    ): Signal? {
        // The reversal candle, Alligator location and momentum are evaluated on
        // the same closed bar. The trigger remains a later price confirmation.
        val start = max(2, bars.lastIndex - 2)
        for (i in bars.lastIndex downTo start) {
            val bar = bars[i]
            val previous = bars.subList(i - 2, i)
            val range = bar.high - bar.low
            if (range <= 0.0) continue

            val jawAt = jaw.getOrElse(i) { Double.NaN }
            val teethAt = teeth.getOrElse(i) { Double.NaN }
            val lipsAt = lips.getOrElse(i) { Double.NaN }
            val aoAt = ao.getOrElse(i) { Double.NaN }
            val aoBefore = ao.getOrElse(i - 1) { Double.NaN }
            val acAt = ac.getOrElse(i) { Double.NaN }
            val acBefore = ac.getOrElse(i - 1) { Double.NaN }
            if (!listOf(jawAt, teethAt, lipsAt, aoAt, aoBefore, acAt, acBefore).all(Double::isFinite)) continue

            val closeLocation = (bar.close - bar.low) / range
            if (direction == "LONG") {
                val freshLow = bar.low < previous.minOf { it.low }
                val belowMouth = bar.low < min(jawAt, min(teethAt, lipsAt))
                val momentumImproving = aoAt >= aoBefore && acAt >= acBefore
                val trigger = bar.high + tickSize
                val stop = bar.low - tickSize
                if (freshLow && belowMouth && closeLocation >= 0.50 &&
                    momentumImproving && bars.last().close < trigger && stop > 0.0
                ) {
                    return Signal(
                        symbol, "LONG", "REVERSAL", bar.openTime, trigger, stop, atr,
                        "WM1 bullish reversal outside the Alligator; BUY STOP confirms the signal bar"
                    )
                }
            } else {
                val freshHigh = bar.high > previous.maxOf { it.high }
                val aboveMouth = bar.high > max(jawAt, max(teethAt, lipsAt))
                val momentumImproving = aoAt <= aoBefore && acAt <= acBefore
                val trigger = bar.low - tickSize
                val stop = bar.high + tickSize
                if (freshHigh && aboveMouth && closeLocation <= 0.50 &&
                    momentumImproving && bars.last().close > trigger && stop > trigger
                ) {
                    return Signal(
                        symbol, "SHORT", "REVERSAL", bar.openTime, trigger, stop, atr,
                        "WM1 bearish reversal outside the Alligator; SELL STOP confirms the signal bar"
                    )
                }
            }
        }
        return null
    }

    private fun superAo(
        ao: List<Double>,
        bars: List<Bar>,
        direction: String,
        teeth: Double,
        fractal: Pair<Int, Double>?
    ): Boolean {
        if (ao.size < 4 || fractal == null) return false
        val end = ao.lastIndex
        val recent = (end - 2..end).map { ao.getOrElse(it) { Double.NaN } }
        if (!recent.all(Double::isFinite)) return false
        val threeColors = if (direction == "LONG") {
            recent[0] < recent[1] && recent[1] < recent[2]
        } else {
            recent[0] > recent[1] && recent[1] > recent[2]
        }
        if (!threeColors) return false
        val level = fractal.second
        return if (direction == "LONG") level > teeth && bars.last().close > teeth
        else level < teeth && bars.last().close < teeth
    }

    private fun latestFractal(bars: List<Bar>, up: Boolean): Pair<Int, Double>? {
        if (bars.size < 5) return null
        val earliest = max(2, bars.lastIndex - 40)
        for (center in (bars.lastIndex - 2) downTo earliest) {
            val row = bars[center]
            val left = bars.subList(center - 2, center)
            val right = bars.subList(center + 1, center + 3)
            val valid = if (up) {
                row.high > left.maxOf { it.high } && row.high > right.maxOf { it.high }
            } else {
                row.low < left.minOf { it.low } && row.low < right.minOf { it.low }
            }
            if (valid) return center to if (up) row.high else row.low
        }
        return null
    }

    private fun smma(values: List<Double>, period: Int): List<Double> {
        val out = MutableList(values.size) { Double.NaN }
        if (values.size < period) return out
        val initial = values.take(period)
        if (!initial.all(Double::isFinite)) return out
        var previous = initial.average()
        out[period - 1] = previous
        for (i in period until values.size) {
            if (!values[i].isFinite() || !previous.isFinite()) continue
            previous = (previous * (period - 1) + values[i]) / period
            out[i] = previous
        }
        return out
    }

    private fun shifted(values: List<Double>, shift: Int): List<Double> =
        List(values.size) { i -> values.getOrElse(i - shift) { Double.NaN } }

    private fun smaAt(values: List<Double>, i: Int, period: Int): Double {
        if (i + 1 < period) return Double.NaN
        val window = values.subList(i - period + 1, i + 1)
        return if (window.all(Double::isFinite)) window.average() else Double.NaN
    }

    private fun atrSeries(bars: List<Bar>, period: Int = 14): List<Double> {
        val trueRanges = bars.mapIndexed { i, bar ->
            if (i == 0) bar.high - bar.low
            else max(
                bar.high - bar.low,
                max(abs(bar.high - bars[i - 1].close), abs(bar.low - bars[i - 1].close))
            )
        }
        return trueRanges.indices.map { i ->
            if (i + 1 < period) Double.NaN
            else trueRanges.subList(i - period + 1, i + 1).average()
        }
    }

    private fun sizePosition(
        exchange: BinanceUsdmFuturesClient,
        symbol: String,
        signal: Signal,
        equity: Double,
        available: Double,
        riskBudget: Double
    ): String {
        val mark = exchange.markPrice(symbol)
        if (signal.direction == "LONG" && !(signal.stop < mark && mark < signal.trigger)) {
            throw FuturesApiException("$symbol LONG trigger is stale or stop is breached")
        }
        if (signal.direction == "SHORT" && !(signal.trigger < mark && mark < signal.stop)) {
            throw FuturesApiException("$symbol SHORT trigger is stale or stop is breached")
        }
        val stopDistance = abs(signal.trigger - signal.stop)
        if (stopDistance <= 0.0) throw FuturesApiException("$symbol structural stop distance is invalid")
        val targetDistance = signal.atr * 4.0
        val rr = targetDistance / stopDistance
        if (!rr.isFinite() || rr < minRiskReward) {
            throw FuturesApiException("$symbol structural setup has R:R ${"%.2f".format(Locale.US, rr)} below ${minRiskReward}")
        }
        val costReserve = signal.trigger * (2.0 * feeBufferPerSideFraction + slippageBufferFraction)
        val rawQtyByRisk = riskBudget / (stopDistance + costReserve)
        val maxNotional = min(equity * 0.20, max(0.0, available * 0.90))
        val rawQty = min(rawQtyByRisk, maxNotional / signal.trigger)
        val quantity = exchange.normalizeQuantity(symbol, rawQty, market = false)
        val normalized = quantity.toDouble()
        val notional = normalized * signal.trigger
        val filters = exchange.symbolFilters(symbol)
        val notionalFilter = filters["NOTIONAL"] ?: filters["MIN_NOTIONAL"]
        val minimum = notionalFilter?.let { filter ->
            (filter.optString("minNotional").toDoubleOrNull()
                ?: filter.optString("notional").toDoubleOrNull()
                ?: filter.optString("minNotionalValue").toDoubleOrNull())
        } ?: 0.0
        if (notional < minimum) throw FuturesApiException("$symbol rounded quantity is below minimum notional")
        val actualRisk = normalized * (stopDistance + costReserve)
        if (actualRisk > riskBudget * 1.000001) throw FuturesApiException("$symbol rounded quantity exceeds risk budget")
        return quantity
    }

    private fun armEntry(
        exchange: BinanceUsdmFuturesClient,
        signal: Signal,
        quantity: String,
        riskQuote: Double,
        equity: Double
    ): JSONObject {
        val symbol = signal.symbol
        val side = exchange.directionToEntrySide(signal.direction)
        val rules = exchange.symbolFilters(symbol)
        val trigger = exchange.normalizePrice(symbol, signal.trigger, signal.direction, "ENTRY")
        val stop = exchange.normalizePrice(symbol, signal.stop, signal.direction, "STOP")
        val triggerValue = trigger.toDouble()
        val stopValue = stop.toDouble()
        if (signal.direction == "LONG" && !(stopValue < triggerValue)) {
            throw FuturesApiException("$symbol normalized LONG stop must remain below trigger")
        }
        if (signal.direction == "SHORT" && !(stopValue > triggerValue)) {
            throw FuturesApiException("$symbol normalized SHORT stop must remain above trigger")
        }
        val mark = exchange.markPrice(symbol)
        if (signal.direction == "LONG" && !(stopValue < mark && mark < triggerValue)) {
            throw FuturesApiException("$symbol LONG entry became stale at final validation")
        }
        if (signal.direction == "SHORT" && !(triggerValue < mark && mark < stopValue)) {
            throw FuturesApiException("$symbol SHORT entry became stale at final validation")
        }
        val clientAlgoId = clientOrderId("W2FE_")
        val campaignId = UUID.randomUUID().toString()
        val campaign = JSONObject()
            .put("campaign_id", campaignId)
            .put("symbol", symbol)
            .put("direction", signal.direction)
            .put("state", "ENTRY_PENDING")
            .put("signal_type", signal.type)
            .put("signal_time_ms", signal.signalBarTime)
            .put("timeframe", interval())
            .put("entry_client_algo_id", clientAlgoId)
            .put("entry_algo_id", "")
            .put("protection_client_algo_id", "")
            .put("protection_algo_id", "")
            .put("entry_trigger", triggerValue)
            .put("stop_price", stopValue)
            .put("quantity", quantity.toDouble())
            .put("risk_quote", riskQuote)
            .put("equity_at_entry", equity)
            .put("notional_quote", quantity.toDouble() * triggerValue)
            .put("atr_at_entry", signal.atr)
            .put("entry_price", 0.0)
            .put("position_amt", 0.0)
            .put("entry_fill_reconciliation_pending", true)
            .put("protection_active", false)
            .put("reason", signal.reason)
            .put("created_at_ms", System.currentTimeMillis())
        // Commit the campaign skeleton before placing the exchange entry, so
        // every restart has an owner for any newly-created order.
        auditStore.saveFuturesCampaign(symbol, campaign)

        val params = JSONObject()
            .put("symbol", symbol)
            .put("side", side)
            .put("type", "STOP_MARKET")
            .put("quantity", quantity)
            .put("triggerPrice", trigger)
            .put("clientAlgoId", clientAlgoId)
            .put("structuralStop", stop)
            .put("riskQuote", riskQuote)
            .put("signalType", signal.type)
        var mutationAccepted = false
        try {
            val response = executeMutation(
                exchange,
                symbol,
                "ENTRY",
                signal.direction,
                side,
                clientAlgoId,
                params
            ) {
                exchange.submitConditional(
                    symbol = symbol,
                    side = side,
                    type = "STOP_MARKET",
                    quantity = quantity,
                    triggerPrice = trigger,
                    clientAlgoId = clientAlgoId,
                    closePosition = false,
                    reduceOnly = false,
                    workingType = "MARK_PRICE"
                )
            }
            // From this point onward, any failure is a post-submit uncertainty;
            // never mark the campaign CLOSED just because verification failed.
            mutationAccepted = true
            val responseStatus = response.optString("algoStatus").uppercase(Locale.US)
            val responseAlgoId = response.optString("algoId")
            val verified = exchange.getAlgoOrder(symbol, clientAlgoId = clientAlgoId)
            val verifiedStatus = verified.optString("algoStatus").uppercase(Locale.US)
            val verifiedTrigger = verified.optString("triggerPrice").toDoubleOrNull()
            val verifiedQty = verified.optString("quantity").toDoubleOrNull()
            if (
                responseStatus !in setOf("NEW", "WORKING", "PENDING", "PENDING_NEW") ||
                responseAlgoId.isBlank() ||
                verifiedStatus !in setOf("NEW", "WORKING", "PENDING", "PENDING_NEW") ||
                verified.optString("symbol").uppercase(Locale.US) != symbol ||
                verified.optString("clientAlgoId") != clientAlgoId ||
                verified.optString("algoId") != responseAlgoId ||
                verified.optString("side").uppercase(Locale.US) != side ||
                verified.optString("orderType", verified.optString("type")).uppercase(Locale.US) != "STOP_MARKET" ||
                verified.optBoolean("closePosition", false) ||
                verified.optBoolean("reduceOnly", false) ||
                verifiedTrigger == null || !verifiedTrigger.isFinite() ||
                abs(verifiedTrigger - triggerValue) > 1e-8 ||
                verifiedQty == null || !verifiedQty.isFinite() ||
                abs(verifiedQty - quantity.toDouble()) > max(1e-8, quantity.toDouble() * 1e-6)
            ) {
                throw FuturesApiException(
                    "$symbol entry conditional order failed authoritative identity/side/type/trigger/quantity verification",
                    outcomeUnknown = true
                )
            }
            campaign.put("entry_algo_id", responseAlgoId)
            campaign.put("state", "ENTRY_PENDING")
            campaign.put("entry_status", verifiedStatus)
            auditStore.saveFuturesCampaign(symbol, campaign)
            return JSONObject()
                .put("symbol", symbol)
                .put("direction", signal.direction)
                .put("signal_type", signal.type)
                .put("action", "ENTRY_ARMED")
                .put("client_algo_id", clientAlgoId)
                .put("algo_id", response.optString("algoId"))
                .put("trigger_price", triggerValue)
                .put("stop_price", stopValue)
                .put("quantity", quantity)
                .put("risk_quote", riskQuote)
        } catch (x: Exception) {
            val unknown = x is FuturesApiException && x.outcomeUnknown
            campaign.put(
                "state",
                when {
                    mutationAccepted || unknown -> "ENTRY_PENDING"
                    else -> "CLOSED"
                }
            )
            if (mutationAccepted || unknown) reconcileRequired = true
            campaign.put("reason", x.message ?: x.javaClass.simpleName)
            auditStore.saveFuturesCampaign(symbol, campaign)
            throw x
        }
    }


    private fun position(exchange: BinanceUsdmFuturesClient, symbol: String): JSONObject {
        val rows = exchange.positionRisk(symbol)
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            if (row.optString("symbol").uppercase(Locale.US) == symbol.uppercase(Locale.US)) return row
        }
        throw FuturesApiException("$symbol positionRisk response omitted symbol")
    }

    private fun hasLivePosition(exchange: BinanceUsdmFuturesClient, symbol: String): Boolean {
        val raw = position(exchange, symbol).optString("positionAmt")
        val amount = raw.toDoubleOrNull()
            ?: throw FuturesApiException("$symbol positionAmt is missing or malformed")
        if (!amount.isFinite()) throw FuturesApiException("$symbol positionAmt is non-finite")
        return abs(amount) > 1e-12
    }

    private fun isolated(row: JSONObject): Boolean =
        row.optBoolean("isolated", false) ||
            row.optString("isolated").equals("true", true) ||
            row.optString("marginType").equals("isolated", true)

    private fun hasActiveCampaign(symbol: String): Boolean {
        val row = auditStore.futuresCampaign(symbol) ?: return false
        return row.optString("state").uppercase(Locale.US) !in setOf("CLOSED", "FLAT")
    }

    private fun activeCampaign(symbol: String): Boolean = hasActiveCampaign(symbol)

    private fun hasUnmanagedFuturesPositions(exchange: BinanceUsdmFuturesClient): Boolean {
        val managed = auditStore.activeFuturesCampaigns()
            .map { it.optString("symbol").uppercase(Locale.US) }.toSet()
        val rows = runCatching { exchange.positionRisk() }.getOrElse { return true }
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: return true
            val symbol = row.optString("symbol").uppercase(Locale.US)
            val amount = row.optString("positionAmt").toDoubleOrNull() ?: return true
            if (symbol.isBlank() || !amount.isFinite()) return true
            if (abs(amount) > 1e-12 && symbol !in managed) return true
        }
        return false
    }

    /**
     * Account-wide order ownership check. A clean position snapshot does not
     * prove safety: an orphan conditional entry can create exposure later.
     * Unknown/malformed order rows or endpoint errors fail closed.
     */
    private fun hasUnmanagedFuturesOrders(exchange: BinanceUsdmFuturesClient): Boolean {
        val campaigns = auditStore.activeFuturesCampaigns()
        val knownAlgoBySymbol = mutableMapOf<String, MutableSet<String>>()
        val knownOrderBySymbol = mutableMapOf<String, MutableSet<String>>()
        for (campaign in campaigns) {
            val symbol = campaign.optString("symbol").uppercase(Locale.US)
            if (symbol.isBlank()) return true
            val algos = knownAlgoBySymbol.getOrPut(symbol) { mutableSetOf() }
            val orders = knownOrderBySymbol.getOrPut(symbol) { mutableSetOf() }
            listOf("entry_client_algo_id", "protection_client_algo_id").forEach { key ->
                campaign.optString(key).takeIf { it.isNotBlank() }?.let(algos::add)
            }
            listOf("entry_algo_id", "protection_algo_id").forEach { key ->
                campaign.optString(key).takeIf { it.isNotBlank() }?.let(algos::add)
            }
        }
        for (intent in auditStore.pendingFuturesIntents()) {
            val symbol = intent.optString("symbol").uppercase(Locale.US)
            val clientId = intent.optString("client_id")
            if (symbol.isBlank() || clientId.isBlank()) return true
            when (intent.optString("operation").uppercase(Locale.US)) {
                "ENTRY", "PROTECTION", "PROTECTION_REPLACE" ->
                    knownAlgoBySymbol.getOrPut(symbol) { mutableSetOf() }.add(clientId)
                "EXIT" ->
                    knownOrderBySymbol.getOrPut(symbol) { mutableSetOf() }.add(clientId)
            }
        }
        val algos = runCatching { exchange.openAlgoOrders() }.getOrElse { return true }
        for (i in 0 until algos.length()) {
            val order = algos.optJSONObject(i) ?: return true
            val symbol = order.optString("symbol").uppercase(Locale.US)
            val clientId = order.optString("clientAlgoId")
            val algoId = order.optString("algoId")
            if (symbol.isBlank() || (clientId.isBlank() && algoId.isBlank())) return true
            val known = knownAlgoBySymbol[symbol].orEmpty()
            if (clientId !in known && algoId !in known) return true
        }
        val standard = runCatching { exchange.openOrders() }.getOrElse { return true }
        for (i in 0 until standard.length()) {
            val order = standard.optJSONObject(i) ?: return true
            val symbol = order.optString("symbol").uppercase(Locale.US)
            val clientId = order.optString("clientOrderId")
            if (symbol.isBlank() || clientId.isBlank()) return true
            if (clientId !in knownOrderBySymbol[symbol].orEmpty()) return true
        }
        return false
    }

    private fun reconcileAll(exchange: BinanceUsdmFuturesClient): JSONObject {
        val rows = auditStore.activeFuturesCampaigns()
        val results = JSONArray()
        var unresolved = 0
        for (campaign in rows) {
            val symbol = campaign.optString("symbol").uppercase(Locale.US)
            try {
                val result = reconcileCampaign(exchange, campaign)
                results.put(result)
                if (result.optString("state") == "RECONCILE_REQUIRED") unresolved++
            } catch (x: Exception) {
                unresolved++
                results.put(JSONObject()
                    .put("symbol", symbol)
                    .put("state", "RECONCILE_REQUIRED")
                    .put("error", x.message ?: x.javaClass.simpleName))
                setCampaignState(campaign, "RECONCILE_REQUIRED", x.message ?: "reconciliation failed")
            }
        }
        val unknown = unresolvedIntentCount(exchange)
        if (unknown > 0) unresolved++
        return JSONObject().put("campaigns", results).put("unresolved", unresolved)
    }

    private fun reconcileCampaign(exchange: BinanceUsdmFuturesClient, campaignInput: JSONObject): JSONObject {
        val campaign = JSONObject(campaignInput.toString())
        val symbol = campaign.optString("symbol").uppercase(Locale.US)
        val direction = campaign.optString("direction").uppercase(Locale.US)
        if (direction !in setOf("LONG", "SHORT")) {
            return setCampaignState(campaign, "RECONCILE_REQUIRED", "Campaign direction is invalid")
        }
        val position = position(exchange, symbol)
        val amount = position.optString("positionAmt").toDoubleOrNull()
            ?: return setCampaignState(campaign, "RECONCILE_REQUIRED", "Exchange positionAmt is missing or malformed")
        if (!amount.isFinite()) {
            return setCampaignState(campaign, "RECONCILE_REQUIRED", "Exchange positionAmt is non-finite")
        }
        val expectedPositive = direction == "LONG"
        if (abs(amount) > 1e-12 && ((amount > 0.0) != expectedPositive)) {
            return setCampaignState(campaign, "RECONCILE_REQUIRED", "Exchange position direction conflicts with campaign direction")
        }
        if (campaign.optString("state") == "ENTRY_PENDING" && abs(amount) <= 1e-12) {
            val algo = runCatching {
                exchange.getAlgoOrder(symbol, clientAlgoId = campaign.optString("entry_client_algo_id"))
            }.getOrNull()
            if (algo != null) {
                val status = algo.optString("algoStatus").uppercase(Locale.US)
                if (status in setOf("NEW", "WORKING", "PENDING", "PENDING_NEW")) {
                    val expectedSide = exchange.directionToEntrySide(direction)
                    val expectedTrigger = campaign.optDouble("entry_trigger", 0.0)
                    val expectedQty = campaign.optDouble("quantity", 0.0)
                    val actualTrigger = algo.optString("triggerPrice").toDoubleOrNull()
                    val actualQty = algo.optString("quantity").toDoubleOrNull()
                    val verified =
                        algo.optString("symbol").uppercase(Locale.US) == symbol &&
                            algo.optString("clientAlgoId") == campaign.optString("entry_client_algo_id") &&
                            algo.optString("side").uppercase(Locale.US) == expectedSide &&
                            algo.optString("orderType", algo.optString("type")).uppercase(Locale.US) == "STOP_MARKET" &&
                            !algo.optBoolean("closePosition", false) &&
                            !algo.optBoolean("reduceOnly", false) &&
                            actualTrigger != null && actualTrigger.isFinite() &&
                            expectedTrigger > 0.0 && abs(actualTrigger - expectedTrigger) <= 1e-8 &&
                            actualQty != null && actualQty.isFinite() &&
                            expectedQty > 0.0 && abs(actualQty - expectedQty) <= max(1e-8, expectedQty * 1e-6)
                    if (!verified) {
                        return setCampaignState(
                            campaign,
                            "RECONCILE_REQUIRED",
                            "Pending entry order does not match durable side/type/trigger/quantity intent"
                        )
                    }
                    campaign.put("entry_status", status)
                    auditStore.saveFuturesCampaign(symbol, campaign)
                    updateIntentByClientId(campaign.optString("entry_client_algo_id"), "SUBMITTED", algo.toString())
                    return JSONObject().put("symbol", symbol).put("state", "ENTRY_PENDING").put("algo_status", status)
                }
                if (status in setOf("CANCELED", "CANCELLED", "EXPIRED", "REJECTED")) {
                    val childId = algo.optString("actualOrderId")
                    if (childId.isNotBlank() && childId != "0") {
                        val child = runCatching { exchange.getOrder(symbol, orderId = childId) }.getOrNull()
                            ?: return setCampaignState(
                                campaign,
                                "RECONCILE_REQUIRED",
                                "Terminal entry algo has a child order whose status cannot be verified"
                            )
                        val childStatus = child.optString("status").uppercase(Locale.US)
                        val childQty = child.optString("executedQty").toDoubleOrNull()
                        if (
                            child.optString("symbol").uppercase(Locale.US) != symbol ||
                            child.optString("orderId") != childId ||
                            child.optString("side").uppercase(Locale.US) != exchange.directionToEntrySide(direction) ||
                            childQty == null || !childQty.isFinite() || childQty < 0.0 ||
                            childStatus !in setOf("FILLED", "CANCELED", "CANCELLED", "EXPIRED", "REJECTED")
                        ) {
                            return setCampaignState(
                                campaign,
                                "RECONCILE_REQUIRED",
                                "Terminal entry child order identity/status/quantity is invalid"
                            )
                        }
                        if (childQty > 0.0) {
                            return setCampaignState(
                                campaign,
                                "RECONCILE_REQUIRED",
                                "Entry child has executed quantity despite a flat position; fill history must be reconciled"
                            )
                        }
                    }
                    campaign.put("state", "CLOSED")
                    campaign.put("protection_active", false)
                    campaign.put("reason", "Entry algorithm reached terminal no-fill state $status")
                    auditStore.saveFuturesCampaign(symbol, campaign)
                    updateIntentByClientId(campaign.optString("entry_client_algo_id"), status, algo.toString())
                    return JSONObject().put("symbol", symbol).put("state", "CLOSED").put("algo_status", status)
                }
            }
            return setCampaignState(
                campaign,
                "RECONCILE_REQUIRED",
                "Pending entry has no exchange position and its terminal/fill state cannot be verified"
            )
        }

        if (abs(amount) <= 1e-12 && campaign.optString("state") in setOf("OPEN", "OPEN_UNPROTECTED", "OPEN_PROTECTED", "EXIT_PENDING", "RECONCILE_REQUIRED")) {
            // Recover the crash window after a reduce-only market exit reached
            // Binance but before CLOSED was committed to SQLite.
            val exitIntent = auditStore.pendingFuturesIntents()
                .filter { row ->
                    row.optString("symbol").equals(symbol, true) &&
                        row.optString("operation").equals("EXIT", true) &&
                        row.optString("status").uppercase(Locale.US) in
                            setOf("PENDING", "SUBMITTING", "SUBMITTED", "UNKNOWN", "RECONCILE_REQUIRED")
                }
                .maxByOrNull { it.optLong("created_at", 0L) }
            if (exitIntent != null) {
                val exitOrder = runCatching {
                    exchange.getOrder(symbol, clientOrderId = exitIntent.optString("client_id"))
                }.getOrNull()
                if (exitOrder?.optString("status").equals("FILLED", true)) {
                    val reason = runCatching {
                        JSONObject(exitIntent.optString("params_json", "{}")).optString("reason")
                    }.getOrDefault("RECOVERED_CONFIRMED_MARKET_EXIT").ifBlank {
                        "RECOVERED_CONFIRMED_MARKET_EXIT"
                    }
                    val cleanupProblem = verifyFlatProtectionCleanup(exchange, campaign)
                    if (cleanupProblem != null) {
                        return setCampaignState(campaign, "RECONCILE_REQUIRED", cleanupProblem)
                    }
                    recordMarketExit(exchange, campaign, exitOrder!!, reason)
                    campaign.put("state", "CLOSED")
                    campaign.put("position_amt", 0.0)
                    campaign.put("protection_active", false)
                    campaign.put("exit_reason", reason)
                    campaign.put("closed_at_ms", System.currentTimeMillis())
                    auditStore.saveFuturesCampaign(symbol, campaign)
                    auditStore.updateFuturesIntent(exitIntent.optString("intent_id"), "CONFIRMED", exitOrder.toString())
                    return JSONObject().put("symbol", symbol).put("state", "CLOSED")
                        .put("reason", "recovered confirmed reduce-only exit")
                }
            }

            val protectionClientId = campaign.optString("protection_client_algo_id")
            if (protectionClientId.isNotBlank()) {
                val protection = runCatching {
                    exchange.getAlgoOrder(symbol, clientAlgoId = protectionClientId)
                }.getOrNull()
                val actualOrderId = protection?.optString("actualOrderId").orEmpty()
                if (protection != null && actualOrderId.isNotBlank() && actualOrderId != "0") {
                    val actualOrder = runCatching {
                        exchange.getOrder(symbol, orderId = actualOrderId)
                    }.getOrNull()
                    if (actualOrder?.optString("status").equals("FILLED", true)) {
                        recordExchangeExit(exchange, campaign, protection!!, actualOrder!!, "EXCHANGE_PROTECTIVE_STOP_FILLED")
                        return JSONObject().put("symbol", symbol).put("state", "CLOSED").put("reason", "exchange protective stop filled")
                    }
                }
            }
            return setCampaignState(
                campaign,
                "RECONCILE_REQUIRED",
                "A locally open campaign is flat at Binance, but its closing order/fill cannot be verified"
            )
        }

        if (abs(amount) <= 1e-12) {
            return JSONObject().put("symbol", symbol).put("state", "FLAT")
        }

        campaign.put("position_amt", amount)
        campaign.put("entry_price", position.optString("entryPrice").toDoubleOrNull() ?: campaign.optDouble("entry_price", 0.0))
        if (campaign.optString("state") in setOf("ENTRY_PENDING", "OPEN", "OPEN_UNPROTECTED")) {
            val needsEntryFillVerification = campaign.optBoolean(
                "entry_fill_reconciliation_pending",
                campaign.optString("state") == "ENTRY_PENDING"
            )
            campaign.put("state", "OPEN_UNPROTECTED")
            campaign.put("entry_filled_at_ms", System.currentTimeMillis())
            auditStore.saveFuturesCampaign(symbol, campaign)
            try {
                placeProtection(exchange, campaign)
            } catch (x: Exception) {
                val latestCampaign = auditStore.futuresCampaign(symbol) ?: campaign
                val exit = runCatching { exitPosition(exchange, latestCampaign, "EMERGENCY_NO_PROTECTION") }.getOrNull()
                if (exit == null || exit.optString("action") != "CLOSED") {
                    return setCampaignState(
                        latestCampaign,
                        "RECONCILE_REQUIRED",
                        "Position exists but protective stop failed and emergency exit was not confirmed: ${x.message}"
                    )
                }
                return JSONObject().put("symbol", symbol).put("state", "CLOSED").put("reason", "emergency exit after failed protection")
            }
            val protectedCampaign = auditStore.futuresCampaign(symbol) ?: campaign
            if (needsEntryFillVerification) {
                val fillProblem = verifyInitialEntryFill(exchange, protectedCampaign, position, amount)
                if (fillProblem != null) {
                    if (fillProblem.startsWith("Actual initial fill risk exceeds")) {
                        val emergency = runCatching {
                            exitPosition(exchange, protectedCampaign, "ENTRY_RISK_OVERRUN")
                        }.getOrNull()
                        if (emergency?.optString("action") == "CLOSED") return emergency
                    }
                    return setCampaignState(protectedCampaign, "RECONCILE_REQUIRED", fillProblem)
                }
            }
            return JSONObject()
                .put("symbol", symbol)
                .put("state", "OPEN_PROTECTED")
                .put("direction", direction)
                .put("position_amt", amount)
        }

        if (campaign.optString("state") in setOf("OPEN_PROTECTED", "RECONCILE_REQUIRED", "EXIT_PENDING")) {
            return verifyOrRestoreLiveProtection(exchange, campaign)
        }
        // A position without a recognized campaign state is never left open on
        // the assumption that an old in-memory object is still authoritative.
        val emergency = runCatching { exitPosition(exchange, campaign, "UNKNOWN_CAMPAIGN_STATE") }.getOrNull()
        if (emergency?.optString("action") == "CLOSED") {
            return JSONObject().put("symbol", symbol).put("state", "CLOSED").put("reason", "exited unknown campaign state")
        }
        return setCampaignState(campaign, "RECONCILE_REQUIRED", "Unknown campaign state: ${campaign.optString("state")}")
    }

    /**
     * A non-zero position is not enough to prove that the campaign's pending
     * conditional entry created it. Verify the exact algo, child MARKET order,
     * position quantity/average price, and authoritative userTrades before
     * releasing the campaign from entry reconciliation.
     */
    private fun verifyInitialEntryFill(
        exchange: BinanceUsdmFuturesClient,
        campaign: JSONObject,
        position: JSONObject,
        signedAmount: Double
    ): String? {
        val symbol = campaign.optString("symbol").uppercase(Locale.US)
        val direction = campaign.optString("direction").uppercase(Locale.US)
        val clientId = campaign.optString("entry_client_algo_id")
        if (clientId.isBlank()) return "Initial entry has no durable clientAlgoId"
        return try {
            val algo = exchange.getAlgoOrder(symbol, clientAlgoId = clientId)
            val algoStatus = algo.optString("algoStatus").uppercase(Locale.US)
            val childId = algo.optString("actualOrderId")
            val expectedSide = exchange.directionToEntrySide(direction)
            val expectedTrigger = campaign.optDouble("entry_trigger", 0.0)
            val expectedQty = campaign.optDouble("quantity", 0.0)
            val actualTrigger = algo.optString("triggerPrice").toDoubleOrNull()
            val actualQty = algo.optString("quantity").toDoubleOrNull()
            if (
                algo.optString("symbol").uppercase(Locale.US) != symbol ||
                algo.optString("clientAlgoId") != clientId ||
                algo.optString("side").uppercase(Locale.US) != expectedSide ||
                algo.optString("orderType", algo.optString("type")).uppercase(Locale.US) != "STOP_MARKET" ||
                algo.optBoolean("closePosition", false) ||
                actualTrigger == null || !actualTrigger.isFinite() ||
                expectedTrigger <= 0.0 || abs(actualTrigger - expectedTrigger) > 1e-8 ||
                actualQty == null || !actualQty.isFinite() ||
                expectedQty <= 0.0 || abs(actualQty - expectedQty) > max(1e-8, expectedQty * 1e-6) ||
                algoStatus !in setOf("TRIGGERED", "FINISHED", "CANCELED", "CANCELLED", "EXPIRED")
            ) {
                return "Initial entry algo identity/status/trigger/quantity does not match durable intent"
            }
            if (childId.isBlank() || childId == "0") {
                return "Live position exists but initial entry algo has no authoritative child order ID"
            }
            val child = exchange.getOrder(symbol, orderId = childId)
            val childStatus = child.optString("status").uppercase(Locale.US)
            val executed = child.optString("executedQty").toDoubleOrNull()
                ?: return "Initial entry child executedQty is missing or invalid"
            if (
                child.optString("symbol").uppercase(Locale.US) != symbol ||
                child.optString("orderId") != childId ||
                child.optString("side").uppercase(Locale.US) != expectedSide ||
                child.optString("type").uppercase(Locale.US) != "MARKET" ||
                childStatus !in setOf("FILLED", "CANCELED", "CANCELLED", "EXPIRED", "REJECTED") ||
                !executed.isFinite() || executed <= 0.0 ||
                abs(abs(signedAmount) - executed) > max(1e-8, executed * 1e-6)
            ) {
                return "Initial entry child order does not reconcile to the live position"
            }
            var average = child.optString("avgPrice").toDoubleOrNull() ?: 0.0
            if (average <= 0.0) {
                val quote = child.optString("cumQuote", child.optString("cumQuoteQty")).toDoubleOrNull() ?: 0.0
                if (quote > 0.0) average = quote / executed
            }
            val positionEntry = position.optString("entryPrice").toDoubleOrNull()
                ?: return "Exchange position entryPrice is missing"
            if (!average.isFinite() || average <= 0.0 || !positionEntry.isFinite() ||
                positionEntry <= 0.0 || abs(average - positionEntry) > max(1e-8, average * 1e-5)
            ) {
                return "Initial entry average fill does not match exchange position entryPrice"
            }
            val trades = exchange.getUserTrades(symbol, childId)
            if (trades.length() == 0) return "Initial entry fills are confirmed but userTrades are not yet available"
            var tradeQty = 0.0
            var tradeQuote = 0.0
            var entryFeeQuote = 0.0
            var feeUnknown = false
            for (i in 0 until trades.length()) {
                val trade = trades.optJSONObject(i)
                    ?: return "Initial entry userTrades contains a malformed row"
                if (trade.optString("symbol").uppercase(Locale.US) != symbol ||
                    (trade.has("orderId") && trade.optString("orderId") != childId)
                ) return "Initial entry userTrades identity mismatch"
                val qty = trade.optString("qty").toDoubleOrNull()
                    ?: return "Initial entry trade quantity is invalid"
                val price = trade.optString("price").toDoubleOrNull()
                    ?: return "Initial entry trade price is invalid"
                val fee = trade.optString("commission").toDoubleOrNull()
                    ?: return "Initial entry commission is invalid"
                if (!listOf(qty, price, fee).all(Double::isFinite) || qty <= 0.0 || price <= 0.0 || fee < 0.0) {
                    return "Initial entry userTrades contains invalid numeric values"
                }
                val asset = trade.optString("commissionAsset").uppercase(Locale.US)
                if (fee > 0.0 && asset.isBlank()) return "Initial entry commission asset is missing"
                tradeQty += qty
                tradeQuote += qty * price
                when (asset) {
                    "USDT", "USDC" -> entryFeeQuote += fee
                    "" -> Unit
                    else -> feeUnknown = true
                }
            }
            if (!tradeQty.isFinite() || abs(tradeQty - executed) > max(1e-8, executed * 1e-6) ||
                !tradeQuote.isFinite() || abs(tradeQuote / tradeQty - average) > max(1e-8, average * 1e-5)
            ) return "Initial entry userTrades quantity/average does not match child order"
            val stop = campaign.optDouble("stop_price", 0.0)
            if (stop <= 0.0 || (direction == "LONG" && stop >= average) || (direction == "SHORT" && stop <= average)) {
                return "Initial protective stop geometry is invalid relative to the actual fill"
            }
            val actualRisk = executed * (abs(average - stop) + average * (2.0 * feeBufferPerSideFraction + slippageBufferFraction))
            val reservedRisk = campaign.optDouble("risk_quote", 0.0)
            if (!actualRisk.isFinite() || reservedRisk <= 0.0 || actualRisk > reservedRisk + max(1e-8, reservedRisk * 1e-6)) {
                return "Actual initial fill risk exceeds the durable risk reservation"
            }
            campaign.put("entry_actual_order_id", childId)
            campaign.put("entry_fill_qty", executed)
            campaign.put("entry_fill_price", average)
            campaign.put("entry_fee_quote", entryFeeQuote)
            campaign.put("entry_fee_unknown", feeUnknown)
            campaign.put("entry_fill_verified", true)
            campaign.put("entry_fill_reconciliation_pending", false)
            auditStore.saveFuturesCampaign(symbol, campaign)
            null
        } catch (x: Exception) {
            "Initial entry fill reconciliation failed: ${x.message ?: x.javaClass.simpleName}"
        }
    }

    /**
     * Recover an exchange-side stop submitted before a crash/ambiguous response.
     * When replacing a stop, never forget the old identity until cancellation is
     * authoritatively terminal; both IDs stay durable while replacement is unresolved.
     */
    private fun reconcilePendingProtectionState(
        exchange: BinanceUsdmFuturesClient,
        campaign: JSONObject
    ): JSONObject? {
        val symbol = campaign.optString("symbol").uppercase(Locale.US)
        val direction = campaign.optString("direction").uppercase(Locale.US)
        val pendingId = campaign.optString("pending_protection_client_algo_id")
        if (pendingId.isNotBlank()) {
            val pending = runCatching {
                exchange.getAlgoOrder(symbol, clientAlgoId = pendingId)
            }.getOrElse {
                return setCampaignState(
                    campaign,
                    "RECONCILE_REQUIRED",
                    "Pending protective stop lookup is unresolved: ${it.message ?: it.javaClass.simpleName}"
                )
            }
            val status = pending.optString("algoStatus").uppercase(Locale.US)
            if (status in setOf("NEW", "WORKING", "PENDING", "PENDING_NEW")) {
                val trigger = pending.optString("triggerPrice").toDoubleOrNull()
                val expectedTrigger = campaign.optString("pending_protection_trigger_price").toDoubleOrNull()
                val algoId = pending.optString("algoId")
                if (
                    pending.optString("symbol").uppercase(Locale.US) != symbol ||
                    pending.optString("clientAlgoId") != pendingId ||
                    algoId.isBlank() ||
                    pending.optString("side").uppercase(Locale.US) != exchange.directionToProtectiveSide(direction) ||
                    pending.optString("orderType", pending.optString("type")).uppercase(Locale.US) != "STOP_MARKET" ||
                    !pending.optBoolean("closePosition", false) ||
                    trigger == null || !trigger.isFinite() ||
                    expectedTrigger == null || !expectedTrigger.isFinite() ||
                    abs(trigger - expectedTrigger) > 1e-8
                ) {
                    return setCampaignState(
                        campaign,
                        "RECONCILE_REQUIRED",
                        "Pending protective stop identity/side/type/trigger does not match durable intent"
                    )
                }
                val oldId = campaign.optString("protection_client_algo_id")
                if (oldId.isNotBlank() && oldId != pendingId) {
                    campaign.put("previous_protection_client_algo_id", oldId)
                    campaign.put("previous_protection_algo_id", campaign.optString("protection_algo_id"))
                    campaign.put("protection_replace_reconcile_required", true)
                }
                campaign.put("protection_client_algo_id", pendingId)
                campaign.put("protection_algo_id", algoId)
                campaign.put("protection_status", status)
                campaign.put("protection_active", true)
                campaign.put("stop_price", trigger)
                campaign.put("state", "OPEN_PROTECTED")
                campaign.remove("pending_protection_client_algo_id")
                campaign.remove("pending_protection_trigger_price")
                campaign.remove("pending_protection_reason")
                auditStore.saveFuturesCampaign(symbol, campaign)
            } else if (status in setOf("CANCELED", "CANCELLED", "EXPIRED", "REJECTED")) {
                updateIntentByClientId(pendingId, status, pending.toString())
                campaign.remove("pending_protection_client_algo_id")
                campaign.remove("pending_protection_trigger_price")
                campaign.remove("pending_protection_reason")
                auditStore.saveFuturesCampaign(symbol, campaign)
            } else {
                return setCampaignState(
                    campaign,
                    "RECONCILE_REQUIRED",
                    "Pending protective stop has ambiguous exchange status: ${status.ifBlank { "UNKNOWN" }}"
                )
            }
        }

        val previousId = campaign.optString("previous_protection_client_algo_id")
        if (previousId.isBlank()) {
            if (campaign.optBoolean("protection_replace_reconcile_required", false)) {
                return setCampaignState(
                    campaign,
                    "RECONCILE_REQUIRED",
                    "Protection replacement flag is set but previous stop identity is missing"
                )
            }
            return null
        }
        var previous = runCatching {
            exchange.getAlgoOrder(symbol, clientAlgoId = previousId)
        }.getOrElse {
            return setCampaignState(
                campaign,
                "RECONCILE_REQUIRED",
                "Previous protective stop lookup is unresolved: ${it.message ?: it.javaClass.simpleName}"
            )
        }
        if (previous.optString("symbol").uppercase(Locale.US) != symbol ||
            previous.optString("clientAlgoId") != previousId
        ) {
            return setCampaignState(campaign, "RECONCILE_REQUIRED", "Previous protective stop identity mismatch")
        }
        var previousStatus = previous.optString("algoStatus").uppercase(Locale.US)
        if (previousStatus in setOf("NEW", "WORKING", "PENDING", "PENDING_NEW")) {
            runCatching { cancelOwnedProtection(exchange, campaign, previousId) }.onFailure {
                return setCampaignState(
                    campaign,
                    "RECONCILE_REQUIRED",
                    "Previous protective stop cancellation is unresolved: ${it.message ?: it.javaClass.simpleName}"
                )
            }
            previous = runCatching {
                exchange.getAlgoOrder(symbol, clientAlgoId = previousId)
            }.getOrElse {
                return setCampaignState(campaign, "RECONCILE_REQUIRED", "Previous stop cancellation cannot be verified")
            }
            previousStatus = previous.optString("algoStatus").uppercase(Locale.US)
        }
        if (previousStatus in setOf("TRIGGERED", "FINISHED")) {
            val childId = previous.optString("actualOrderId")
            if (childId.isBlank() || childId == "0") {
                return setCampaignState(campaign, "RECONCILE_REQUIRED", "Previous stop triggered but child order ID is missing")
            }
            val child = runCatching { exchange.getOrder(symbol, orderId = childId) }.getOrElse {
                return setCampaignState(campaign, "RECONCILE_REQUIRED", "Previous stop child order cannot be verified")
            }
            val executed = child.optString("executedQty").toDoubleOrNull()
            val childStatus = child.optString("status").uppercase(Locale.US)
            if (executed == null || !executed.isFinite() || executed < 0.0 ||
                childStatus !in setOf("FILLED", "CANCELED", "CANCELLED", "EXPIRED", "REJECTED")
            ) {
                return setCampaignState(campaign, "RECONCILE_REQUIRED", "Previous stop child status/quantity is ambiguous")
            }
            if (executed > 0.0) {
                return setCampaignState(
                    campaign,
                    "RECONCILE_REQUIRED",
                    "Previous stop child executed during replacement; exit fill reconciliation is required"
                )
            }
            previousStatus = childStatus
        }
        if (previousStatus !in setOf("CANCELED", "CANCELLED", "EXPIRED", "REJECTED")) {
            return setCampaignState(
                campaign,
                "RECONCILE_REQUIRED",
                "Previous protective stop is not confirmed terminal: ${previousStatus.ifBlank { "UNKNOWN" }}"
            )
        }
        updateIntentByClientId(previousId, previousStatus, previous.toString())
        campaign.remove("previous_protection_client_algo_id")
        campaign.remove("previous_protection_algo_id")
        campaign.remove("protection_replace_reconcile_required")
        auditStore.saveFuturesCampaign(symbol, campaign)
        return null
    }

    private fun verifyOrRestoreLiveProtection(
        exchange: BinanceUsdmFuturesClient,
        campaignInput: JSONObject
    ): JSONObject {
        val campaign = JSONObject(campaignInput.toString())
        val symbol = campaign.optString("symbol").uppercase(Locale.US)
        val direction = campaign.optString("direction").uppercase(Locale.US)
        val livePosition = runCatching { position(exchange, symbol) }.getOrElse {
            return setCampaignState(campaign, "RECONCILE_REQUIRED", "Cannot verify live position while checking protection: ${it.message}")
        }
        val liveAmount = livePosition.optString("positionAmt").toDoubleOrNull()
            ?: return setCampaignState(campaign, "RECONCILE_REQUIRED", "positionAmt is missing while checking protection")
        if (!liveAmount.isFinite() || abs(liveAmount) <= 1e-12) {
            return setCampaignState(campaign, "RECONCILE_REQUIRED", "Protection verification requires a finite non-zero live position")
        }
        val rawPersistedQty = campaign.optString("position_amt").toDoubleOrNull()
            ?: return setCampaignState(campaign, "RECONCILE_REQUIRED", "Persisted campaign position_amt is missing or invalid")
        val persistedQty = abs(rawPersistedQty)
        if (!persistedQty.isFinite() || persistedQty <= 0.0) {
            return setCampaignState(campaign, "RECONCILE_REQUIRED", "Persisted campaign position_amt is non-finite or non-positive")
        }
        if (abs(abs(liveAmount) - persistedQty) > max(1e-8, persistedQty * 1e-6)) {
            return setCampaignState(
                campaign,
                "RECONCILE_REQUIRED",
                "Live position quantity differs from persisted campaign quantity; exit/fill reconciliation is required"
            )
        }
        val pendingProtectionRecovery = reconcilePendingProtectionState(exchange, campaign)
        if (pendingProtectionRecovery != null) return pendingProtectionRecovery
        val unresolvedExit = auditStore.pendingFuturesIntents().firstOrNull { intent ->
            intent.optString("symbol").equals(symbol, true) &&
                intent.optString("operation").equals("EXIT", true) &&
                intent.optString("status").uppercase(Locale.US) in
                    setOf("PENDING", "SUBMITTING", "SUBMITTED", "UNKNOWN", "RECONCILE_REQUIRED")
        }
        if (unresolvedExit != null) {
            return setCampaignState(
                campaign,
                "RECONCILE_REQUIRED",
                "A prior reduce-only exit intent remains unresolved while Futures exposure is still open"
            )
        }
        var clientId = campaign.optString("protection_client_algo_id")

        // Prefer exchange-authoritative open protection over guessing from
        // historical intents. This closes the crash window after the exchange
        // accepted a new stop but before its clientAlgoId reached the campaign row.
        if (clientId.isBlank()) {
            val expectedSide = exchange.directionToProtectiveSide(direction)
            val openAlgo = exchange.openAlgoOrders(symbol)
            val ownedStops = (0 until openAlgo.length()).mapNotNull { index ->
                openAlgo.optJSONObject(index)
            }.filter { order ->
                val candidateId = order.optString("clientAlgoId")
                val type = order.optString("orderType", order.optString("type")).uppercase(Locale.US)
                candidateId.startsWith("W2FP_") &&
                    order.optString("side").uppercase(Locale.US) == expectedSide &&
                    type == "STOP_MARKET" &&
                    order.optBoolean("closePosition", false)
            }
            if (ownedStops.size == 1) {
                val found = ownedStops.single()
                clientId = found.optString("clientAlgoId")
                campaign.put("protection_client_algo_id", clientId)
                campaign.put("protection_algo_id", found.optString("algoId"))
                campaign.put("protection_active", true)
                campaign.put("state", "OPEN_PROTECTED")
                auditStore.saveFuturesCampaign(symbol, campaign)
                updateIntentByClientId(clientId, "SUBMITTED", found.toString())
                return JSONObject().put("symbol", symbol).put("state", "OPEN_PROTECTED")
                    .put("reason", "recovered exchange-confirmed protective order")
            }
            if (ownedStops.size > 1) {
                return setCampaignState(
                    campaign,
                    "RECONCILE_REQUIRED",
                    "Multiple bot-owned protective stops are open; refusing to add another stop until duplicates are reconciled"
                )
            }

            val pendingProtection = auditStore.pendingFuturesIntents().firstOrNull { row ->
                row.optString("symbol").equals(symbol, true) &&
                    row.optString("operation").uppercase(Locale.US) in setOf("PROTECTION", "PROTECTION_REPLACE") &&
                    row.optString("status").uppercase(Locale.US) in setOf("PENDING", "SUBMITTING", "UNKNOWN", "SUBMITTED", "RECONCILE_REQUIRED")
            }
            if (pendingProtection != null) clientId = pendingProtection.optString("client_id")
        }

        if (clientId.isNotBlank()) {
            val protection = runCatching {
                exchange.getAlgoOrder(symbol, clientAlgoId = clientId)
            }.getOrNull()
            if (protection != null) {
                val status = protection.optString("algoStatus").uppercase(Locale.US)
                if (status in setOf("NEW", "WORKING", "PENDING", "PENDING_NEW")) {
                    val expectedSide = exchange.directionToProtectiveSide(direction)
                    val expectedStop = campaign.optDouble("stop_price", 0.0)
                    val trigger = protection.optString("triggerPrice").toDoubleOrNull()
                    val validProtection =
                        protection.optString("symbol").uppercase(Locale.US) == symbol &&
                            protection.optString("clientAlgoId") == clientId &&
                            protection.optString("side").uppercase(Locale.US) == expectedSide &&
                            protection.optString("orderType", protection.optString("type")).uppercase(Locale.US) == "STOP_MARKET" &&
                            protection.optBoolean("closePosition", false) &&
                            trigger != null && trigger.isFinite() &&
                            expectedStop > 0.0 && abs(trigger - expectedStop) <= 1e-8
                    if (!validProtection) {
                        val emergency = runCatching {
                            exitPosition(exchange, campaign, "PROTECTION_IDENTITY_MISMATCH")
                        }.getOrNull()
                        if (emergency?.optString("action") == "CLOSED") return emergency
                        return setCampaignState(
                            campaign,
                            "RECONCILE_REQUIRED",
                            "Live protective stop identity/side/type/trigger does not match campaign"
                        )
                    }
                    campaign.put("protection_client_algo_id", clientId)
                    campaign.put("protection_algo_id", protection.optString("algoId"))
                    campaign.put("protection_active", true)
                    campaign.put("state", "OPEN_PROTECTED")
                    auditStore.saveFuturesCampaign(symbol, campaign)
                    updateIntentByClientId(clientId, "SUBMITTED", protection.toString())
                    return JSONObject().put("symbol", symbol).put("state", "OPEN_PROTECTED").put("protection_status", status)
                }

                if (status in setOf("CANCELED", "EXPIRED", "REJECTED")) {
                    val open = exchange.openAlgoOrders(symbol)
                    val stillOpen = (0 until open.length()).any { i ->
                        open.optJSONObject(i)?.optString("clientAlgoId") == clientId
                    }
                    if (!stillOpen) {
                        campaign.put("state", "OPEN_UNPROTECTED")
                        campaign.put("protection_active", false)
                        auditStore.saveFuturesCampaign(symbol, campaign)
                        return try {
                            val replacement = placeProtection(exchange, campaign)
                            JSONObject().put("symbol", symbol).put("state", "OPEN_PROTECTED")
                                .put("reason", "replaced terminal protective order")
                                .put("protection", replacement)
                        } catch (x: Exception) {
                            val exit = runCatching { exitPosition(exchange, campaign, "PROTECTION_LOST") }.getOrNull()
                            if (exit?.optString("action") == "CLOSED") {
                                JSONObject().put("symbol", symbol).put("state", "CLOSED").put("reason", "exit after lost protection")
                            } else {
                                setCampaignState(campaign, "RECONCILE_REQUIRED", "Protection was terminal and replacement failed: ${x.message}")
                            }
                        }
                    }
                }

                // A triggered/finished stop with exposure still present may
                // have partially reduced the position. Finish with reduce-only.
                if (status in setOf("TRIGGERED", "FINISHED")) {
                    val exit = runCatching { exitPosition(exchange, campaign, "PROTECTIVE_STOP_TRIGGERED_RESIDUAL") }.getOrNull()
                    if (exit?.optString("action") == "CLOSED") return exit
                    return setCampaignState(campaign, "RECONCILE_REQUIRED", "Protective stop triggered but exchange exposure remains")
                }
            }
        }

        // If the stop state cannot be verified, reduce exposure rather than
        // leaving a live position dependent on an in-memory watchdog.
        val emergency = runCatching { exitPosition(exchange, campaign, "PROTECTION_STATE_UNVERIFIED") }.getOrNull()
        if (emergency?.optString("action") == "CLOSED") return emergency
        return setCampaignState(
            campaign,
            "RECONCILE_REQUIRED",
            "Live position has no authoritatively confirmed protective stop; emergency reduce-only exit also needs reconciliation"
        )
    }

    private fun enforceKillOnCampaigns(exchange: BinanceUsdmFuturesClient): JSONArray {
        val results = cancelPendingEntries(exchange, "KILL_SWITCH_RETRY")
        for (row in auditStore.activeFuturesCampaigns()) {
            val symbol = row.optString("symbol").uppercase(Locale.US)
            val campaign = JSONObject(row.toString())
            try {
                val live = position(exchange, symbol).optString("positionAmt").toDoubleOrNull()
                    ?: throw FuturesApiException("$symbol positionAmt is invalid during kill enforcement")
                if (!live.isFinite()) throw FuturesApiException("$symbol positionAmt is non-finite during kill enforcement")
                if (abs(live) > 1e-12) {
                    results.put(exitPosition(exchange, campaign, "KILL_SWITCH_RETRY"))
                }
            } catch (x: Exception) {
                reconcileRequired = true
                results.put(JSONObject().put("symbol", symbol).put("state", "RECONCILE_REQUIRED")
                    .put("error", x.message ?: x.javaClass.simpleName))
            }
        }
        return results
    }

    private fun placeProtection(exchange: BinanceUsdmFuturesClient, campaignInput: JSONObject): JSONObject {
        val campaign = JSONObject(campaignInput.toString())
        val symbol = campaign.optString("symbol").uppercase(Locale.US)
        val direction = campaign.optString("direction").uppercase(Locale.US)
        val pos = position(exchange, symbol)
        val amount = pos.optString("positionAmt").toDoubleOrNull()
            ?: throw FuturesApiException("$symbol positionAmt is missing or malformed while placing protection")
        if (!amount.isFinite()) throw FuturesApiException("$symbol positionAmt is non-finite while placing protection")
        if (abs(amount) <= 1e-12) throw FuturesApiException("$symbol has no live Futures position to protect")
        if ((amount > 0.0) != (direction == "LONG")) {
            throw FuturesApiException("$symbol live position direction changed before protection")
        }
        val stop = exchange.normalizePrice(symbol, campaign.optDouble("stop_price"), direction, "STOP")
        val trigger = stop.toDouble()
        val mark = exchange.markPrice(symbol)
        if (direction == "LONG" && trigger >= mark) throw FuturesApiException("$symbol LONG protective stop is at/above mark; market exit required")
        if (direction == "SHORT" && trigger <= mark) throw FuturesApiException("$symbol SHORT protective stop is at/below mark; market exit required")

        val clientId = clientOrderId("W2FP_")
        val side = exchange.directionToProtectiveSide(direction)
        val params = JSONObject()
            .put("symbol", symbol)
            .put("side", side)
            .put("type", "STOP_MARKET")
            .put("triggerPrice", stop)
            .put("closePosition", true)
            .put("clientAlgoId", clientId)

        // Persist the stable identity before submitting. A crash or ambiguous
        // lookup must not orphan a stop that the campaign cannot later find.
        campaign.put("pending_protection_client_algo_id", clientId)
        campaign.put("pending_protection_trigger_price", trigger)
        campaign.put("pending_protection_reason", "PROTECTION_REPAIR")
        auditStore.saveFuturesCampaign(symbol, campaign)

        val response = executeMutation(
            exchange, symbol, "PROTECTION", direction, side, clientId, params
        ) {
            exchange.submitConditional(
                symbol = symbol,
                side = side,
                type = "STOP_MARKET",
                quantity = null,
                triggerPrice = stop,
                clientAlgoId = clientId,
                closePosition = true,
                reduceOnly = false
            )
        }
        val algoId = response.optString("algoId")
        val responseStatus = response.optString("algoStatus").uppercase(Locale.US)
        val verified = runCatching { exchange.getAlgoOrder(symbol, clientAlgoId = clientId) }
            .getOrElse {
                throw FuturesApiException("$symbol protection lookup failed after submit: ${it.message}", outcomeUnknown = true, cause = it)
            }
        val verifiedStatus = verified.optString("algoStatus").uppercase(Locale.US)
        val verifiedTrigger = verified.optString("triggerPrice").toDoubleOrNull()
        val expectedSide = exchange.directionToProtectiveSide(direction)
        if (
            algoId.isBlank() ||
            responseStatus !in setOf("NEW", "WORKING", "PENDING", "PENDING_NEW") ||
            verifiedStatus !in setOf("NEW", "WORKING", "PENDING", "PENDING_NEW") ||
            verified.optString("symbol").uppercase(Locale.US) != symbol ||
            verified.optString("clientAlgoId") != clientId ||
            verified.optString("algoId") != algoId ||
            verified.optString("side").uppercase(Locale.US) != expectedSide ||
            verified.optString("orderType", verified.optString("type")).uppercase(Locale.US) != "STOP_MARKET" ||
            !verified.optBoolean("closePosition", false) ||
            verifiedTrigger == null || !verifiedTrigger.isFinite() ||
            abs(verifiedTrigger - trigger) > 1e-8
        ) {
            reconcileRequired = true
            throw FuturesApiException(
                "$symbol protective stop failed authoritative identity/side/type/trigger verification",
                outcomeUnknown = true
            )
        }
        campaign.put("protection_client_algo_id", clientId)
        campaign.put("protection_algo_id", algoId)
        campaign.put("protection_active", true)
        campaign.put("state", "OPEN_PROTECTED")
        campaign.put("position_amt", amount)
        campaign.put("entry_price", pos.optString("entryPrice").toDoubleOrNull() ?: campaign.optDouble("entry_price", 0.0))
        campaign.put("protection_status", verifiedStatus)
        campaign.remove("pending_protection_client_algo_id")
        campaign.remove("pending_protection_trigger_price")
        campaign.remove("pending_protection_reason")
        auditStore.saveFuturesCampaign(symbol, campaign)
        return verified
    }

    private fun manageStructuralExit(exchange: BinanceUsdmFuturesClient, campaign: JSONObject, frame: Frame) {
        val symbol = campaign.optString("symbol").uppercase(Locale.US)
        val direction = campaign.optString("direction").uppercase(Locale.US)
        if (campaign.optString("state") != "OPEN_PROTECTED") return
        if (frame.bars.size < 3 || frame.atr <= 0.0) return
        val lastTwo = frame.bars.takeLast(2)
        val opposite = if (direction == "LONG") {
            lastTwo.all { bar ->
                val index = frame.bars.indexOf(bar)
                val values = indicatorTuple(frame.bars, index)
                val teethAtBar = values[1]
                val aoAtBar = values[3]
                val acAtBar = values[5]
                bar.close < teethAtBar && aoAtBar.isFinite() && acAtBar.isFinite() &&
                    aoAtBar < 0.0 && acAtBar < 0.0
            }
        } else {
            lastTwo.all { bar ->
                val index = frame.bars.indexOf(bar)
                val values = indicatorTuple(frame.bars, index)
                val teethAtBar = values[1]
                val aoAtBar = values[3]
                val acAtBar = values[5]
                bar.close > teethAtBar && aoAtBar.isFinite() && acAtBar.isFinite() &&
                    aoAtBar > 0.0 && acAtBar > 0.0
            }
        }
        if (opposite) {
            exitPosition(exchange, campaign, "WILLIAMS_TWO_BAR_STRUCTURAL_REVERSAL")
            return
        }

        val position = position(exchange, symbol)
        val mark = exchange.markPrice(symbol)
        val entry = position.optString("entryPrice").toDoubleOrNull() ?: campaign.optDouble("entry_price", 0.0)
        if (entry <= 0.0) return
        val favorable = if (direction == "LONG") mark - entry else entry - mark
        if (favorable / frame.atr < 0.5) return
        val oldStop = campaign.optDouble("stop_price", 0.0)
        val candidate = if (direction == "LONG") {
            val fractal = frame.latestDownFractal?.second ?: 0.0
            max(frame.teeth, fractal) - frame.atr * 0.25
        } else {
            val fractal = frame.latestUpFractal?.second ?: 0.0
            min(frame.teeth, if (fractal > 0.0) fractal else frame.teeth) + frame.atr * 0.25
        }
        val tighter = if (direction == "LONG") candidate > oldStop else oldStop <= 0.0 || candidate < oldStop
        val safe = if (direction == "LONG") candidate > 0.0 && candidate < mark - frame.atr * 0.1
            else candidate > mark + frame.atr * 0.1
        if (!tighter || !safe) return

        val normalized = exchange.normalizePrice(symbol, candidate, direction, "STOP").toDouble()
        val oldClientId = campaign.optString("protection_client_algo_id")
        // Install a new confirmed stop first. Only then cancel the prior stop,
        // never leaving a live position without server-side protection.
        val newClientId = clientOrderId("W2FP_")
        val side = exchange.directionToProtectiveSide(direction)
        val newParams = JSONObject().put("symbol", symbol).put("side", side)
            .put("type", "STOP_MARKET").put("triggerPrice", normalized).put("closePosition", true)
            .put("clientAlgoId", newClientId).put("reason", "STRUCTURAL_TRAIL")
        val newProtection = executeMutation(exchange, symbol, "PROTECTION_REPLACE", direction, side, newClientId, newParams) {
            exchange.submitConditional(symbol, side, "STOP_MARKET", null, normalized.toString(), newClientId, closePosition = true)
        }
        val algoId = newProtection.optString("algoId")
        if (algoId.isBlank()) {
            setCampaignState(campaign, "RECONCILE_REQUIRED", "Replacement stop response omitted algoId")
            return
        }
        campaign.put("protection_client_algo_id", newClientId)
        campaign.put("protection_algo_id", algoId)
        campaign.put("stop_price", normalized)
        campaign.put("protection_active", true)
        auditStore.saveFuturesCampaign(symbol, campaign)
        if (oldClientId.isNotBlank()) {
            runCatching { cancelOwnedProtection(exchange, campaign, oldClientId) }
                .onFailure {
                    // Both stops remain exchange-side; this is safer than a
                    // naked interval, but duplicate protection needs reconciliation.
                    setCampaignState(campaign, "RECONCILE_REQUIRED", "New stop is active but old stop cancellation is uncertain: ${it.message}")
                }
        }
    }

    private fun indicatorTuple(bars: List<Bar>, index: Int): List<Double> {
        val median = bars.map { (it.high + it.low) / 2.0 }
        val ao = median.indices.map { i ->
            val f = smaAt(median, i, 5)
            val s = smaAt(median, i, 34)
            if (f.isFinite() && s.isFinite()) f - s else Double.NaN
        }
        val ac = ao.indices.map { i ->
            val avg = smaAt(ao, i, 5)
            if (ao[i].isFinite() && avg.isFinite()) ao[i] - avg else Double.NaN
        }
        val jaw = shifted(smma(median, 13), 8)
        val teeth = shifted(smma(median, 8), 5)
        val lips = shifted(smma(median, 5), 3)
        return listOf(
            jaw.getOrElse(index) { Double.NaN },
            teeth.getOrElse(index) { Double.NaN },
            lips.getOrElse(index) { Double.NaN },
            ao.getOrElse(index) { Double.NaN },
            ao.getOrElse(index - 1) { Double.NaN },
            ac.getOrElse(index) { Double.NaN },
            ac.getOrElse(index - 1) { Double.NaN }
        )    }

    /**
     * A flat position is not sufficient proof that a protective Algo order is
     * harmless. Confirm cancellation, and reject a stop-child fill that raced
     * with the market exit until both executions can be accounted together.
     */
    private fun verifyFlatProtectionCleanup(
        exchange: BinanceUsdmFuturesClient,
        campaign: JSONObject
    ): String? {
        val symbol = campaign.optString("symbol").uppercase(Locale.US)
        val clientId = campaign.optString("protection_client_algo_id")
        if (clientId.isBlank()) return null
        return try {
            var protection = exchange.getAlgoOrder(symbol, clientAlgoId = clientId)
            if (protection.optString("symbol").uppercase(Locale.US) != symbol ||
                protection.optString("clientAlgoId") != clientId
            ) return "Flat-position protective lookup identity mismatch"
            var status = protection.optString("algoStatus").uppercase(Locale.US)
            if (status in setOf("NEW", "WORKING", "PENDING", "PENDING_NEW")) {
                cancelOwnedProtection(exchange, campaign, clientId)
                protection = exchange.getAlgoOrder(symbol, clientAlgoId = clientId)
                status = protection.optString("algoStatus").uppercase(Locale.US)
            }
            if (status in setOf("CANCELED", "CANCELLED", "EXPIRED", "REJECTED")) {
                updateIntentByClientId(clientId, status, protection.toString())
                null
            } else if (status in setOf("TRIGGERED", "FINISHED")) {
                val childId = protection.optString("actualOrderId")
                if (childId.isBlank() || childId == "0") {
                    "Protective Algo triggered during exit but child order ID is missing"
                } else {
                    val child = exchange.getOrder(symbol, orderId = childId)
                    val executed = child.optString("executedQty").toDoubleOrNull()
                    val childStatus = child.optString("status").uppercase(Locale.US)
                    if (
                        child.optString("symbol").uppercase(Locale.US) != symbol ||
                        child.optString("orderId") != childId ||
                        executed == null || !executed.isFinite() || executed < 0.0 ||
                        childStatus !in setOf("FILLED", "CANCELED", "CANCELLED", "EXPIRED", "REJECTED")
                    ) {
                        "Protective child order cannot be authoritatively reconciled after exit"
                    } else if (executed > 0.0) {
                        "Protective child filled during market exit; both executions require aggregate reconciliation"
                    } else {
                        null
                    }
                }
            } else {
                "Protective Algo has ambiguous status after the position became flat: ${status.ifBlank { "UNKNOWN" }}"
            }
        } catch (x: Exception) {
            "Protective order cleanup after flat position failed: ${x.message ?: x.javaClass.simpleName}"
        }
    }

    private fun recordMarketExit(exchange: BinanceUsdmFuturesClient, campaign: JSONObject, response: JSONObject, reason: String) {
        val symbol = campaign.optString("symbol").uppercase(Locale.US)
        val orderId = response.optString("orderId")
        val clientOrderId = response.optString("clientOrderId")
        val side = exchange.directionToExitSide(campaign.optString("direction"))
        if (orderId.isBlank() || clientOrderId.isBlank() ||
            response.optString("status").uppercase(Locale.US) != "FILLED" ||
            response.optString("symbol").uppercase(Locale.US) != symbol ||
            response.optString("side").uppercase(Locale.US) != side
        ) {
            throw FuturesApiException("$symbol market exit identity/status is not authoritatively FILLED")
        }
        if (campaign.optString("last_exit_order_id") == orderId &&
            campaign.optBoolean("last_exit_accounted", false)
        ) return
        val executed = response.optString("executedQty").toDoubleOrNull()
            ?: throw FuturesApiException("$symbol market exit executedQty is missing or invalid")
        if (!executed.isFinite() || executed <= 0.0) {
            throw FuturesApiException("$symbol market exit executedQty is non-finite or non-positive")
        }
        val persistedAmount = campaign.optString("position_amt").toDoubleOrNull()
            ?: throw FuturesApiException("$symbol persisted position_amt is missing or invalid")
        if (!persistedAmount.isFinite() || abs(persistedAmount) <= 0.0) {
            throw FuturesApiException("$symbol persisted position_amt is non-finite or non-positive")
        }
        val expectedQty = abs(persistedAmount)
        if (abs(executed - expectedQty) > max(1e-8, expectedQty * 1e-6)) {
            throw FuturesApiException(
                "$symbol market exit executedQty does not equal the persisted position quantity; protective fills may have raced"
            )
        }
        val trades = exchange.getUserTrades(symbol, orderId)
        if (trades.length() == 0) {
            throw FuturesApiException("$symbol market exit has no authoritative userTrades yet")
        }
        var realized = 0.0
        var feesQuote = 0.0
        var feeUnknown = false
        var tradeQty = 0.0
        for (i in 0 until trades.length()) {
            val row = trades.optJSONObject(i)
                ?: throw FuturesApiException("$symbol market exit userTrades contains a malformed row")
            if (row.optString("symbol").uppercase(Locale.US) != symbol ||
                (row.has("orderId") && row.optString("orderId") != orderId)
            ) {
                throw FuturesApiException("$symbol market exit userTrades identity mismatch")
            }
            val qty = row.optString("qty").toDoubleOrNull()
                ?: throw FuturesApiException("$symbol market exit trade quantity is invalid")
            val price = row.optString("price").toDoubleOrNull()
                ?: throw FuturesApiException("$symbol market exit trade price is invalid")
            val pnl = row.optString("realizedPnl").toDoubleOrNull()
                ?: throw FuturesApiException("$symbol market exit realizedPnl is missing or invalid")
            val fee = row.optString("commission").toDoubleOrNull()
                ?: throw FuturesApiException("$symbol market exit commission is missing or invalid")
            if (!listOf(qty, price, pnl, fee).all(Double::isFinite) ||
                qty <= 0.0 || price <= 0.0 || fee < 0.0
            ) {
                throw FuturesApiException("$symbol market exit userTrades contains invalid numeric values")
            }
            val feeAsset = row.optString("commissionAsset").uppercase(Locale.US)
            if (fee > 0.0 && feeAsset.isBlank()) {
                throw FuturesApiException("$symbol market exit commission asset is missing")
            }
            tradeQty += qty
            realized += pnl
            when (feeAsset) {
                "USDT", "USDC" -> feesQuote += fee
                "" -> Unit
                else -> feeUnknown = true
            }
        }
        if (!tradeQty.isFinite() || abs(tradeQty - executed) > max(1e-8, executed * 1e-6)) {
            throw FuturesApiException("$symbol market exit executedQty disagrees with userTrades")
        }
        if (!realized.isFinite() || !feesQuote.isFinite()) {
            throw FuturesApiException("$symbol market exit accounting totals are non-finite")
        }
        val entryFee = campaign.optDouble("entry_fee_quote", 0.0)
        if (!entryFee.isFinite() || entryFee < 0.0) {
            throw FuturesApiException("$symbol persisted entry fee is invalid")
        }
        campaign.put("realized_pnl_quote", campaign.optDouble("realized_pnl_quote", 0.0) + realized - feesQuote - entryFee)
        campaign.put("last_exit_order_id", orderId)
        campaign.put("last_exit_fee_quote", feesQuote)
        campaign.put("last_exit_fee_unknown", feeUnknown || campaign.optBoolean("entry_fee_unknown", false))
        campaign.put("last_exit_accounted", true)
        campaign.put("entry_fee_accounted", true)
        campaign.put("exit_reason", reason)
    }

    private fun recordExchangeExit(
        exchange: BinanceUsdmFuturesClient,
        campaign: JSONObject,
        protection: JSONObject,
        actualOrder: JSONObject,
        reason: String
    ) {
        val symbol = campaign.optString("symbol").uppercase(Locale.US)
        val orderId = actualOrder.optString("orderId")
        val expectedSide = exchange.directionToProtectiveSide(campaign.optString("direction"))
        if (
            orderId.isBlank() ||
            actualOrder.optString("symbol").uppercase(Locale.US) != symbol ||
            actualOrder.optString("side").uppercase(Locale.US) != expectedSide ||
            (protection.optString("actualOrderId").isNotBlank() &&
                protection.optString("actualOrderId") != orderId)
        ) throw FuturesApiException("$symbol protective child identity/side mismatch")
        if (campaign.optString("exchange_exit_order_id") == orderId &&
            campaign.optBoolean("exchange_exit_accounted", false)
        ) return
        val executed = actualOrder.optString("executedQty").toDoubleOrNull()
            ?: throw FuturesApiException("$symbol protective exit executedQty is invalid")
        if (actualOrder.optString("status").uppercase(Locale.US) != "FILLED" ||
            !executed.isFinite() || executed <= 0.0
        ) throw FuturesApiException("$symbol protective child is not an authoritative fill")
        val persistedAmount = campaign.optString("position_amt").toDoubleOrNull()
            ?: throw FuturesApiException("$symbol persisted position_amt is missing or invalid")
        if (!persistedAmount.isFinite() || abs(persistedAmount) <= 0.0) {
            throw FuturesApiException("$symbol persisted position_amt is non-finite or non-positive")
        }
        val expectedQty = abs(persistedAmount)
        if (abs(executed - expectedQty) > max(1e-8, expectedQty * 1e-6)) {
            throw FuturesApiException("$symbol protective exit quantity differs from persisted position; another exit may have raced")
        }
        val trades = exchange.getUserTrades(symbol, orderId)
        if (trades.length() == 0) throw FuturesApiException("$symbol protective exit has no authoritative userTrades")
        var realized = 0.0
        var feeQuote = 0.0
        var unknown = false
        var tradeQty = 0.0
        for (i in 0 until trades.length()) {
            val row = trades.optJSONObject(i)
                ?: throw FuturesApiException("$symbol protective userTrades contains a malformed row")
            if (row.optString("symbol").uppercase(Locale.US) != symbol ||
                (row.has("orderId") && row.optString("orderId") != orderId)
            ) throw FuturesApiException("$symbol protective userTrades identity mismatch")
            val qty = row.optString("qty").toDoubleOrNull()
                ?: throw FuturesApiException("$symbol protective trade quantity is invalid")
            val price = row.optString("price").toDoubleOrNull()
                ?: throw FuturesApiException("$symbol protective trade price is invalid")
            val pnl = row.optString("realizedPnl").toDoubleOrNull()
                ?: throw FuturesApiException("$symbol protective realizedPnl is invalid")
            val fee = row.optString("commission").toDoubleOrNull()
                ?: throw FuturesApiException("$symbol protective commission is invalid")
            if (!listOf(qty, price, pnl, fee).all(Double::isFinite) ||
                qty <= 0.0 || price <= 0.0 || fee < 0.0
            ) throw FuturesApiException("$symbol protective userTrades has invalid numeric values")
            val asset = row.optString("commissionAsset").uppercase(Locale.US)
            if (fee > 0.0 && asset.isBlank()) throw FuturesApiException("$symbol protective commission asset is missing")
            tradeQty += qty
            realized += pnl
            when (asset) {
                "USDT", "USDC" -> feeQuote += fee
                "" -> Unit
                else -> unknown = true
            }
        }
        if (!tradeQty.isFinite() || abs(tradeQty - executed) > max(1e-8, executed * 1e-6)) {
            throw FuturesApiException("$symbol protective executedQty disagrees with userTrades")
        }
        val entryFee = campaign.optDouble("entry_fee_quote", 0.0)
        if (!entryFee.isFinite() || entryFee < 0.0) throw FuturesApiException("$symbol persisted entry fee is invalid")
        campaign.put("realized_pnl_quote", campaign.optDouble("realized_pnl_quote", 0.0) + realized - feeQuote - entryFee)
        campaign.put("entry_fee_accounted", true)
        campaign.put("exit_reason", reason)
        campaign.put("protection_active", false)
        campaign.put("position_amt", 0.0)
        campaign.put("closed_at_ms", System.currentTimeMillis())
        campaign.put("exchange_exit_algo_id", protection.optString("algoId"))
        campaign.put("exchange_exit_order_id", orderId)
        campaign.put("exchange_exit_accounted", true)
        campaign.put("exit_fee_unknown", unknown || campaign.optBoolean("entry_fee_unknown", false))
        campaign.put("state", "CLOSED")
        auditStore.saveFuturesCampaign(symbol, campaign)
        updateIntentByClientId(campaign.optString("protection_client_algo_id"), "CONFIRMED", actualOrder.toString())
    }

    private fun setCampaignState(campaignInput: JSONObject, state: String, reason: String): JSONObject {
        val campaign = JSONObject(campaignInput.toString())
        val symbol = campaign.optString("symbol").uppercase(Locale.US)
        campaign.put("state", state)
        campaign.put("reason", reason)
        campaign.put("updated_at_ms", System.currentTimeMillis())
        if (state == "RECONCILE_REQUIRED") reconcileRequired = true
        auditStore.saveFuturesCampaign(symbol, campaign)
        return JSONObject().put("symbol", symbol).put("state", state).put("reason", reason)
    }

    private fun updateIntentByClientId(clientId: String, status: String, response: String) {
        if (clientId.isBlank()) return
        val row = auditStore.pendingFuturesIntents().firstOrNull { it.optString("client_id") == clientId }
            ?: return
        auditStore.updateFuturesIntent(row.optString("intent_id"), status, response)
    }

    private fun isUnresolvedIntent(row: JSONObject): Boolean =
        row.optString("status").uppercase(Locale.US) in
            setOf("PENDING", "SUBMITTING", "UNKNOWN", "RECONCILE_REQUIRED")

    private fun unresolvedIntentCount(exchange: BinanceUsdmFuturesClient): Int {
        val rows = auditStore.pendingFuturesIntents()
        var unresolved = 0
        for (row in rows) {
            val status = row.optString("status").uppercase(Locale.US)
            if (status !in setOf("PENDING", "SUBMITTING", "UNKNOWN", "RECONCILE_REQUIRED")) continue
            val operation = row.optString("operation").uppercase(Locale.US)
            val params = runCatching { JSONObject(row.optString("params_json", "{}")) }.getOrDefault(JSONObject())
            val symbol = row.optString("symbol")
            try {
                when (operation) {
                    "ENTRY", "PROTECTION", "PROTECTION_REPLACE" -> {
                        val found = exchange.getAlgoOrder(symbol, clientAlgoId = row.optString("client_id"))
                        val statusRemote = found.optString("algoStatus").uppercase(Locale.US)
                        if (statusRemote.isNotBlank()) {
                            auditStore.updateFuturesIntent(row.optString("intent_id"), "SUBMITTED", found.toString())
                        } else unresolved++
                    }
                    "EXIT" -> {
                        val found = exchange.getOrder(symbol, clientOrderId = row.optString("client_id"))
                        val statusRemote = found.optString("status").uppercase(Locale.US)
                        if (statusRemote.isNotBlank()) {
                            auditStore.updateFuturesIntent(row.optString("intent_id"), "SUBMITTED", found.toString())
                        } else unresolved++
                    }
                    "CANCEL_PROTECTION", "CANCEL_ENTRY" -> {
                        val target = params.optString("targetClientAlgoId")
                        if (target.isBlank()) {
                            unresolved++
                        } else {
                            val found = exchange.getAlgoOrder(symbol, clientAlgoId = target)
                            val remote = found.optString("algoStatus").uppercase(Locale.US)
                            if (remote in setOf("CANCELED", "EXPIRED", "FINISHED")) {
                                auditStore.updateFuturesIntent(row.optString("intent_id"), "CONFIRMED", found.toString())
                            } else unresolved++
                        }
                    }
                    "PREPARE_SYMBOL" -> {
                        val pos = position(exchange, symbol)
                        if (isolated(pos) && pos.optString("leverage").toIntOrNull() == 1) {
                            auditStore.updateFuturesIntent(row.optString("intent_id"), "CONFIRMED", pos.toString())
                        } else unresolved++
                    }
                    else -> unresolved++
                }
            } catch (_: Exception) {
                unresolved++
            }
        }
        return unresolved
    }

    private fun activeRiskQuote(): Double =
        auditStore.activeFuturesCampaigns()
            .filter { it.optString("state") !in setOf("CLOSED", "FLAT") }
            .sumOf { it.optDouble("risk_quote", 0.0) }

    private fun currentDailyTradeGuardPlaceholder() = Unit

    private fun auditEvent(type: String, payload: JSONObject) {
        runCatching {
            auditStore.recordRestCall("EVENT", type, 200, null, payload.toString())
        }
    }

    companion object {
        private const val MIN_ALLIGATOR_SPREAD_PCT = 0.001
        private val DEFAULT_SYMBOLS = listOf("BTCUSDT", "ETHUSDT", "BNBUSDT")
    }
}