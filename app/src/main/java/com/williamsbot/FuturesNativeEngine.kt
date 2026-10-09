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
    @Volatile private var killLatched = false
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
            prefs.edit()
                .remove("futures_api_key")
                .remove("futures_api_secret")
                .remove("futures_symbols")
                .remove("futures_interval")
                .commit()
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
                lastError = "Unmanaged Futures exposure detected; new entries remain blocked"
            }

            for (symbol in symbols()) {
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
        paused = true
        auditEvent("futures_paused", JSONObject().put("reason", "new entries disabled; open exposure remains managed"))
        return status().put("state", "PAUSED")
    }

    fun resume(): JSONObject {
        require(!killLatched) { "KILL_SWITCH_LATCHED; recover before resume" }
        paused = false
        return status().put("state", if (running) "RUNNING" else "STOPPED")
    }

    fun stop(): JSONObject {
        synchronized(lock) {
            paused = true
            running = false
            stopLoop.set(true)
        }
        val active = worker
        if (active != null && active !== Thread.currentThread()) {
            runCatching { active.join(2500L) }
        }
        return status().put("state", "STOPPED").put("note", "Exchange-side protective orders remain active")
    }

    fun kill(): JSONObject {
        synchronized(cycleLock) {
            killLatched = true
            paused = true
            val exchange = api()
            val results = JSONArray()
            val campaigns = auditStore.activeFuturesCampaigns()
            for (campaign in campaigns) {
                val symbol = campaign.optString("symbol").uppercase(Locale.US)
                try {
                    val pos = position(exchange, symbol)
                    val amount = pos.optDouble("positionAmt", 0.0)
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
                    abs(rows.optJSONObject(i)?.optString("positionAmt")?.toDoubleOrNull() ?: 0.0) > 1e-12
                }
            }
            val unresolvedIntents = unresolvedIntentCount(exchange)
            if (reconciliation.optInt("unresolved", 0) > 0 || unresolvedIntents > 0 || anyPosition) {
                reconcileRequired = true
                throw IllegalStateException(
                    "Recovery reset denied: positions/intents remain unresolved; flat and verified state is required"
                )
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
        val row = position(exchange, symbol)
        require(isolated(row) && row.optString("leverage").toIntOrNull() == 1) {
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
            val unknownIntents = unresolvedIntentCount(exchange)
            reconcileRequired = recovered.optInt("unresolved", 0) > 0 || unowned || unknownIntents > 0
            if (unowned) lastError = "Unmanaged Futures position blocks new entries"
            manageExistingCampaigns(exchange)
            updateDailyBaseline(equity)
            val dailyLoss = dailyLossFraction(equity)
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
                    .put("new_entries", 0)
                return
            }
            if (dailyLoss >= maxDailyLossFraction) {
                lastScanSummary = JSONObject()
                    .put("state", "DAILY_RISK_LOCKOUT")
                    .put("daily_loss_fraction", dailyLoss)
                    .put("limit_fraction", maxDailyLossFraction)
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
            var newEntries = 0
            val decisions = JSONArray()
            for (symbol in symbols()) {
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
                    val riskUsed = active.sumOf { it.optDouble("risk_quote", 0.0) }
                    val riskBudget = min(equity * perCampaignRiskFraction, max(0.0, equity * portfolioRiskFraction - riskUsed))
                    if (riskBudget <= 0.0) {
                        decisions.put(JSONObject().put("symbol", symbol).put("action", "BLOCKED").put("reason", "portfolio risk budget exhausted"))
                        continue
                    }
                    val quantity = sizePosition(exchange, symbol, signal, equity, available, riskBudget)
                    val result = armEntry(exchange, signal, quantity, riskBudget, equity)
                    decisions.put(result)
                    if (result.optString("action") == "ENTRY_ARMED") newEntries++
                    if (active.size + newEntries >= maxPositions) break
                } catch (x: Exception) {
                    decisions.put(JSONObject().put("symbol", symbol).put("action", "WAIT").put("reason", x.message ?: x.javaClass.simpleName))
                    if (x is FuturesApiException && x.outcomeUnknown) reconcileRequired = true
                }
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
        val value = account.optString("totalMarginBalance").toDoubleOrNull()
            ?: account.optString("totalWalletBalance").toDoubleOrNull()
            ?: account.optString("availableBalance").toDoubleOrNull()
            ?: 0.0
        return value.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
    }

    private fun updateDailyBaseline(equity: Double) {
        val day = Instant.ofEpochMilli(System.currentTimeMillis())
            .atZone(ZoneOffset.UTC).toLocalDate().toString()
        val savedDay = prefs.getString("futures_daily_day", "")
        if (savedDay != day) {
            prefs.edit()
                .putString("futures_daily_day", day)
                .putString("futures_daily_equity", equity.toString())
                .commit()
        } else if (prefs.getString("futures_daily_equity", "").orEmpty().toDoubleOrNull() == null) {
            prefs.edit().putString("futures_daily_equity", equity.toString()).commit()
        }
    }

    private fun dailyLossFraction(equity: Double): Double {
        val baseline = prefs.getString("futures_daily_equity", "").orEmpty().toDoubleOrNull() ?: equity
        if (baseline <= 0.0) return 1.0
        return max(0.0, (baseline - equity) / baseline)
    }

    private fun findSignal(exchange: BinanceUsdmFuturesClient, symbol: String): Signal? {
        val tf = interval()
        val primary = analyseFrame(exchange, symbol, tf) ?: return null
        val higherTf = parentInterval(tf)
        val higher = analyseFrame(exchange, symbol, higherTf) ?: return null
        val candidates = listOfNotNull(primary.lastSignal)
            .filter { signal ->
                if (signal.direction == "LONG") higher.bullish && higher.ao > 0.0 && higher.ac > 0.0
                else higher.bearish && higher.ao < 0.0 && higher.ac < 0.0
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
                signal = reversalSignal(symbol, bars, i, "LONG", jaw[i], teeth[i], lips[i], aoNow, aoPrev, acNow, acPrev, atr)
            }
            if (signal == null && configShort) {
                signal = reversalSignal(symbol, bars, i, "SHORT", jaw[i], teeth[i], lips[i], aoNow, aoPrev, acNow, acPrev, atr)
            }

            // WM3: a confirmed fractal outside the Teeth/Balancing Line is a
            // pending stop-entry, never a MARKET chase after the trigger passed.
            if (signal == null && configLong && upFractal != null) {
                val (center, level) = upFractal
                // Actual exchange tick/price filters are enforced again before submission.
                val tick = max(0.0, tickSizeFromPrice(level))
                val entryTrigger = level + tick
                val stop = bars[center].low - tick
                if (level > teeth[i] && bars[i].close < entryTrigger && stop > 0.0) {
                    signal = Signal(symbol, "LONG", "FRACTAL", bars[center].openTime, entryTrigger, stop, atr, "WM3 buy fractal outside Teeth; stop-entry trigger above fractal")
                }
            }
            if (signal == null && configShort && downFractal != null) {
                val (center, level) = downFractal
                val entryTrigger = level - tickSize
                val stop = bars[center].high + tick
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
        currentIndex: Int,
        direction: String,
        jaw: Double,
        teeth: Double,
        lips: Double,
        ao: Double,
        previousAo: Double,
        ac: Double,
        previousAc: Double,
        atr: Double
    ): Signal? {
        // Restrict a first-Wise-Man proposal to a fresh signal bar. The trigger
        // itself must not already have been crossed by the current close.
        val start = max(2, bars.lastIndex - 2)
        for (i in bars.lastIndex downTo start) {
            val bar = bars[i]
            val previous = bars.subList(i - 2, i)
            val range = bar.high - bar.low
            if (range <= 0.0) continue
            val closeLocation = (bar.close - bar.low) / range
            if (direction == "LONG") {
                val freshLow = bar.low < previous.minOf { it.low }
                val belowMouth = bar.low < min(jaw, min(teeth, lips))
                val momentumImproving = ao >= previousAo && ac >= previousAc
                if (freshLow && belowMouth && closeLocation >= 0.50 && momentumImproving) {
                    val tick = tickSizeFromPrice(bar.high)
                    val trigger = bar.high + tick
                    val stop = bar.low - tick
                    if (bars.last().close < trigger && stop > 0.0) {
                        return Signal(symbol, "LONG", "REVERSAL", bar.openTime, trigger, stop, atr, "WM1 bullish reversal outside the Alligator; BUY STOP confirms the signal bar")
                    }
                }
            } else {
                val freshHigh = bar.high > previous.maxOf { it.high }
                val aboveMouth = bar.high > max(jaw, max(teeth, lips))
                val momentumImproving = ao <= previousAo && ac <= previousAc
                if (freshHigh && aboveMouth && closeLocation <= 0.50 && momentumImproving) {
                    val tick = tickSizeFromPrice(bar.low)
                    val trigger = bar.low - tick
                    val stop = bar.high + tick
                    if (bars.last().close > trigger && stop > trigger) {
                        return Signal(symbol, "SHORT", "REVERSAL", bar.openTime, trigger, stop, atr, "WM1 bearish reversal outside the Alligator; SELL STOP confirms the signal bar")
                    }
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

    companion object {
        private const val MIN_ALLIGATOR_SPREAD_PCT = 0.001
        private val DEFAULT_SYMBOLS = listOf("BTCUSDT", "ETHUSDT", "BNBUSDT")
    }
}
