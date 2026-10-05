package com.williamsbot

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.Calendar
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.floor

object StandaloneRuntime {
    private var server: StandaloneServer? = null

    fun start(context: Context) {
        if (server == null) {
            server =
                StandaloneServer(
                    context.applicationContext
                )
            server!!.start()
        }
    }

    fun autostart(context: Context) {
        start(context)
        server?.autostart()
    }

    fun startTrading() {
        server?.startTrading()
    }

    fun stopTrading() {
        server?.stopTrading()
    }

    fun stop() {
        server?.stop()
        server = null
    }
}

private data class CandleN(
    val t: Long,
    val o: Double,
    val h: Double,
    val l: Double,
    val c: Double,
    val v: Double
)

private data class PivotN(
    val kind: String,
    val price: Double,
    val index: Int
)

private data class WaveInfo(
    val position: Int,
    val phase: String,
    val confidence: Double,
    val exhaustionRisk: Double,
    val direction: String,
    val path: String,
    val alligatorBullish: Boolean,
    val aoPositive: Boolean,
    val aoBearishDivergence: Boolean = false,
    val aoBullishDivergence: Boolean = false,
    val currentLegPct: Double,
    val entryFrame: String = "",
    val entryWave: Int = 0,
    val parentFrame: String = "",
    val parentWave: Int = 0,
    val entryConfidence: Double = 0.0,
    val nestedW3ParentW5: Boolean = false,
    val countertrendCorrectionImpulse: Boolean = false
)

private data class PositionState(
    val symbol: String,
    var qty: Double,
    var entry: Double,
    var stop: Double,
    var take: Double,
    var riskPct: Double,
    var ocoListClientId: String = "",
    var ocoListId: String = "",
    var entryOrderId: String = "",
    var entryClientOrderId: String = "",
    var openedAt: Long = 0L
)

private data class PendingEntry(
    val symbol: String,
    val clientOrderId: String,
    val notional: Double,
    val stopDistance: Double
)

private data class SymbolRules(
    val step: Double,
    val tick: Double,
    val decimals: Int,
    val minQty: Double,
    val minNotional: Double,
    val quoteOrderQtyMarketAllowed: Boolean,
    val ocoAllowed: Boolean
)

private data class BaseAnalysis(
    val symbol: String,
    val candles: List<CandleN>,
    val score: Double,
    val signal: Boolean,
    val htfCandidate: Boolean,
    val wave: WaveInfo,
    val atrPct: Double,
    val riskPct: Double,
    val riskReward: Double,
    val spreadPct: Double,
    val breakoutDistancePct: Double,
    val reason: String
)

private class StandaloneServer(private val context: Context) {
    private val port = 18080
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "williams_native_secure",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val historyStore = MarketHistoryStore(context)
    private val rateGuard = BinanceRateGuard()

    private var socket: ServerSocket? = null
    private var engine: NativeEngine? = null

    fun start() {
        if (socket != null) return

        socket = ServerSocket(
            port,
            32,
            InetAddress.getByName("127.0.0.1")
        )

        Thread {
            while (true) {
                try {
                    val c = socket?.accept() ?: break
                    Thread { handle(c) }.start()
                } catch (_: Exception) {
                    break
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        engine?.stop()
    }

    fun autostart() {
        if (
            prefs.getBoolean("auto_run", false)
        ) {
            e().start()
        }
    }

    fun startTrading() {
        e().start()
    }

    fun stopTrading() {
        e().stop()
    }

    private fun e(): NativeEngine {
        if (engine == null) {
            engine = NativeEngine(context, prefs, client)
        }
        return engine!!
    }

    private fun handle(s: Socket) {
        s.use {
            try {
                val reader = BufferedReader(
                    InputStreamReader(
                        s.getInputStream(),
                        StandardCharsets.UTF_8
                    )
                )

                val first = reader.readLine() ?: return
                val parts = first.split(" ")
                if (parts.size < 2) return

                val method = parts[0]
                val target = parts[1]

                var length = 0
                while (true) {
                    val line = reader.readLine() ?: return
                    if (line.isEmpty()) break

                    val header = line.split(":", limit = 2)
                    if (
                        header.size == 2 &&
                        header[0].equals("Content-Length", ignoreCase = true)
                    ) {
                        length = header[1].trim().toIntOrNull() ?: 0
                    }
                }

                val chars = CharArray(length)
                if (length > 0) {
                    reader.read(chars)
                }

                val body = route(method, target, String(chars))
                val bytes = body.toByteArray(StandardCharsets.UTF_8)

                val output = s.getOutputStream()
                output.write(
                    (
                        "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/json; charset=utf-8\r\n" +
                            "Cache-Control: no-store\r\n" +
                            "Content-Length: " + bytes.size + "\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(StandardCharsets.UTF_8)
                )
                output.write(bytes)
                output.flush()
            } catch (x: Exception) {
                val bytes = JSONObject()
                    .put("error", x.message ?: x.javaClass.simpleName)
                    .toString()
                    .toByteArray(StandardCharsets.UTF_8)

                try {
                    val out = s.getOutputStream()
                    out.write(
                        (
                            "HTTP/1.1 500 Internal Server Error\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Content-Length: " + bytes.size + "\r\n" +
                                "Connection: close\r\n\r\n"
                            ).toByteArray(StandardCharsets.UTF_8)
                    )
                    out.write(bytes)
                    out.flush()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun route(
        method: String,
        target: String,
        body: String
    ): String {
        val queryIndex = target.indexOf('?')
        val path = if (queryIndex < 0) {
            target
        } else {
            target.substring(0, queryIndex)
        }

        val params = if (queryIndex < 0) {
            emptyMap()
        } else {
            target
                .substring(queryIndex + 1)
                .split("&")
                .filter { it.contains("=") }
                .associate {
                    val item = it.split("=", limit = 2)
                    URLDecoder.decode(item[0], "UTF-8") to
                        URLDecoder.decode(item[1], "UTF-8")
                }
        }

        val x = e()

        return when {
            method == "GET" && path == "/api/v1/health" ->
                x.health().toString()

            method == "GET" && path == "/api/v1/status" ->
                x.status().toString()

            method == "GET" && path == "/api/v1/market/indicators" ->
                x.indicators(params["symbol"], params["interval"]).toString()

            method == "GET" && path == "/api/v1/market/klines" ->
                x.klines(
                    requestedSymbol = params["symbol"],
                    requestedInterval = params["interval"]
                ).toString()

            method == "GET" && path == "/api/v1/scanner" ->
                x.scanner(
                    refresh = params["refresh"].equals("true", true)
                ).toString()

            method == "GET" && path == "/api/v1/trades" ->
                x.trades().toString()

            method == "GET" && path == "/api/v1/trade-stats" ->
                x.tradeStats().toString()

            method == "GET" && path == "/api/v1/logs" ->
                x.logs().toString()

            method == "GET" && path == "/api/v1/settings" ->
                x.settings().toString()

            method == "POST" && path == "/api/v1/config/binance" ->
                x.configure(JSONObject(body)).toString()

            method == "DELETE" && path == "/api/v1/config/binance" ->
                x.clear().toString()

            method == "POST" && path == "/api/v1/control/start" ->
                x.start().toString()

            method == "POST" && path == "/api/v1/control/stop" ->
                x.stop().toString()

            method == "POST" && path == "/api/v1/control/pause" ->
                x.pause().toString()

            method == "POST" && path == "/api/v1/control/resume" ->
                x.resume().toString()

            method == "POST" &&
                path == "/api/v1/control/recover" ->
                x.recover().toString()

            method == "POST" &&
                path == "/api/v1/control/kill" ->
                x.kill().toString()

            method == "POST" &&
                path == "/api/v1/control/kill/reset" ->
                x.resetKillSwitch().toString()

            method == "GET" &&
                path == "/api/v1/history/status" ->
                x.historyStatus().toString()

            method == "POST" &&
                path == "/api/v1/control/sell" ->
                x.sell(
                    params["symbol"]
                        ?: error("symbol is required")
                ).toString()

            else ->
                JSONObject().put("error", "Not found").toString()
        }
    }
}

private class NativeEngine(
    private val context: Context,
    private val prefs: android.content.SharedPreferences,
    private val http: OkHttpClient
) {
    private val baseUrl = "https://testnet.binance.vision"
    private val primarySymbol = "BTCUSDT"
    private val interval = "1h"

    // Deep-analysis universe: five core USDT pairs only.
    private val coreSymbols = listOf("BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT")
    private val analysisFrames = listOf("1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "8h", "12h", "1d", "3d", "1w", "1M")
    private val maxScanSymbols = 5
    private val waveTopN = 10
    private val scanExecutor = Executors.newFixedThreadPool(12)
    private val candleCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<CandleN>>>()
    private val liveCandleCache = java.util.concurrent.ConcurrentHashMap<String, MutableList<CandleN>>()
    private val indicatorSnapshots = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
    private var marketSocket: WebSocket? = null
    @Volatile private var marketSocketConnected = false
    @Volatile private var marketSocketLastEventMs = 0L
    @Volatile private var historyWarmupRunning = false

    private val scanCacheTtlMs = 12_000L
    private val deepWatchTopN = 10
    private val scannerUniverseLabel = "CORE_5_BTC_ETH_BNB_SOL_XRP"

    @Volatile
    private var running = false

    @Volatile
    private var paused = false

    @Volatile
    private var scanning = false

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var lastScanAt = 0L

    private var worker: Thread? = null
    private var scanSymbols = mutableListOf<String>()
    private var candidates = JSONArray()
    private var primaryCandles = emptyList<CandleN>()
    private var lastScanDurationMs = 0L
    private var lastSymbolsScanned = 0
    private val maxOpenPositions = 3
    private val maxTotalRiskPct = 0.01
    private val maxRiskPerTradePct = 0.005
    private val maxSpreadPct = 0.0015
    private val maxSlippagePct = 0.005
    private val feeBufferPerSidePct = 0.001
    @Volatile private var serverTimeOffsetMs = 0L
    private val BINANCE_RECV_WINDOW_MS = 60000L
    @Volatile private var lastServerTimeSyncMs = 0L
    @Volatile private var lastOrder: JSONObject? = null
    @Volatile private var reconcileRequired = false
    @Volatile private var killLatched = false
    @Volatile private var userStreamConnected = false
    @Volatile private var lastUserEventMs = 0L
    private val positions = mutableMapOf<String, PositionState>()
    private val pendingEntries = mutableMapOf<String, PendingEntry>()

    private val userStream = BinanceUserDataStream(
        http = client,
        endpoint = "wss://ws-api.testnet.binance.vision/ws-api/v3",
        apiKeyProvider = { key() },
        apiSecretProvider = { secret() },
        timestampProvider = { signedTimestamp() },
        onConnection = { connected, error ->
            userStreamConnected = connected
            if (connected) {
                if (lastError?.startsWith("user ws:") == true) {
                    lastError = null
                }
            } else if (!error.equals("stopped")) {
                lastError = "user ws: " + (error ?: "disconnected")
            }
        },
        onEvent = { event ->
            handleUserEvent(event)
        }
    )

    init {
        loadPersistedState()
    }

    private fun key(): String =
        prefs.getString("api_key", "") ?: ""

    private fun secret(): String =
        prefs.getString("api_secret", "") ?: ""

    private fun positionList(): List<PositionState> =
        synchronized(positions) { positions.values.toList() }

    private fun reservedRiskPct(): Double =
        positionList().sumOf {
            if (it.riskPct > 0.0) it.riskPct
            else if (it.entry > 0.0) {
                ((it.entry - it.stop) / it.entry)
                    .coerceAtLeast(0.0)
            } else 0.0
        }

    private fun stateName(): String =
        when {
            killLatched -> "KILL_SWITCH_LATCHED"
            reconcileRequired -> "RECONCILE_REQUIRED"
            pendingEntries.isNotEmpty() -> "ENTRY_PENDING"
            positions.isEmpty() -> "FLAT"
            else -> "OPEN"
        }

    private fun savePersistedState() {
        val pos = JSONArray()
        positionList().forEach {
            pos.put(
                JSONObject()
                    .put("symbol", it.symbol)
                    .put("qty", it.qty)
                    .put("entry", it.entry)
                    .put("stop", it.stop)
                    .put("take", it.take)
                    .put("risk_pct", it.riskPct)
                    .put("oco_list_client_id", it.ocoListClientId)
                    .put("oco_list_id", it.ocoListId)
                    .put("entry_order_id", it.entryOrderId)
                    .put("entry_client_order_id", it.entryClientOrderId)
                    .put("opened_at", it.openedAt)
            )
        }

        val pending = JSONArray()
        synchronized(pendingEntries) {
            pendingEntries.values.forEach {
                pending.put(
                    JSONObject()
                        .put("symbol", it.symbol)
                        .put("client_order_id", it.clientOrderId)
                        .put("notional", it.notional)
                        .put("stop_distance", it.stopDistance)
                )
            }
        }

        prefs.edit()
            .putString("positions_json", pos.toString())
            .putString("pending_entries_json", pending.toString())
            .apply()
    }

    private fun loadPersistedState() {
        synchronized(positions) {
            positions.clear()
            runCatching {
                val array = JSONArray(
                    prefs.getString("positions_json", "[]")
                )
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val symbol = item.optString("symbol").uppercase()
                    if (symbol.isBlank()) continue
                    positions[symbol] = PositionState(
                        symbol = symbol,
                        qty = item.optDouble("qty", 0.0),
                        entry = item.optDouble("entry", 0.0),
                        stop = item.optDouble("stop", 0.0),
                        take = item.optDouble("take", 0.0),
                        riskPct = item.optDouble("risk_pct", 0.0),
                        ocoListClientId = item.optString(
                            "oco_list_client_id"
                        ),
                        ocoListId = item.optString("oco_list_id"),
                        entryOrderId = item.optString("entry_order_id"),
                        entryClientOrderId = item.optString(
                            "entry_client_order_id"
                        ),
                        openedAt = item.optLong("opened_at", 0L)
                    )
                }
            }
        }

        synchronized(pendingEntries) {
            pendingEntries.clear()
            runCatching {
                val array = JSONArray(
                    prefs.getString("pending_entries_json", "[]")
                )
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val symbol = item.optString("symbol").uppercase()
                    if (symbol.isBlank()) continue
                    pendingEntries[symbol] = PendingEntry(
                        symbol = symbol,
                        clientOrderId = item.optString(
                            "client_order_id"
                        ),
                        notional = item.optDouble(
                            "notional",
                            0.0
                        ),
                        stopDistance = item.optDouble(
                            "stop_distance",
                            0.02
                        )
                    )
                }
            }
        }

        reconcileRequired =
            prefs.getBoolean("reconcile_required", false)
        killLatched =
            prefs.getBoolean("kill_latched", false)
    }

    private fun setReconcileRequired(reason: String) {
        reconcileRequired = true
        prefs.edit()
            .putBoolean("reconcile_required", true)
            .apply()
        lastError = "RECONCILE_REQUIRED: " + reason
        stop()
    }

    private fun clearReconcileRequired() {
        reconcileRequired = false
        prefs.edit()
            .putBoolean("reconcile_required", false)
            .apply()
    }

    fun health(): JSONObject =
        JSONObject()
            .put("ok", true)
            .put("service", "williams-native")
            .put("version", "4.17.0")
            .put("standalone", true)
            .put("websocket", marketSocketConnected)
            .put("market_stream_last_event_ms", marketSocketLastEventMs)
            .put("user_stream_connected", userStreamConnected)
            .put("user_stream_last_event_ms", lastUserEventMs)
            .put("history_warmup_running", historyWarmupRunning)
            .put("history", historyStore.status(coreSymbols, analysisFrames))
            .put("rate_limits", rateGuard.snapshot())
            .put("core_symbols", JSONArray(coreSymbols))
            .put("analysis_timeframes", JSONArray(analysisFrames))
            .put(
                "execution_enabled",
                !reconcileRequired
            )
            .put("state", stateName())
            .put("open_positions", positionList().size)
            .put("max_open_positions", maxOpenPositions)
            .put("reserved_risk_pct", reservedRiskPct())
            .put("max_total_risk_pct", maxTotalRiskPct)
            .put("max_risk_per_trade_pct", maxRiskPerTradePct)
            .put("reconcile_required", reconcileRequired)
            .put(
                "positions",
                JSONArray().apply {
                    positionList().forEach {
                        put(
                            JSONObject()
                                .put("symbol", it.symbol)
                                .put("qty", it.qty)
                                .put("entry", it.entry)
                                .put("stop", it.stop)
                                .put("take", it.take)
                                .put("risk_pct", it.riskPct)
                                .put("oco_list_id", it.ocoListId)
                                .put("oco_list_client_id", it.ocoListClientId)
                                .put("entry_order_id", it.entryOrderId)
                                .put("entry_client_order_id", it.entryClientOrderId)
                                .put("opened_at", it.openedAt)
                        )
                    }
                }
            )
            .put("last_order", lastOrder ?: JSONObject.NULL)
            .put(
                "auth_configured",
                key().isNotBlank() && secret().isNotBlank()
            )

    fun configure(j: JSONObject): JSONObject {
        val newKey = j.optString("api_key").trim()
        val newSecret = j.optString("api_secret").trim()

        if (running) {
            error(
                "Stop the bot before changing Binance credentials."
            )
        }

        if (
            positions.isNotEmpty() &&
            (newKey != key() || newSecret != secret())
        ) {
            error(
                "Stop/reconcile the bot before changing Binance credentials."
            )
        }

        require(
            newKey.isNotBlank() &&
                newSecret.isNotBlank()
        ) {
            "API Key and API Secret are required"
        }

        prefs.edit()
            .putString("api_key", newKey)
            .putString("api_secret", newSecret)
            .apply()

        return JSONObject()
            .put("configured", true)
            .put("testnet", true)
            .put("standalone", true)
    }

    fun clear(): JSONObject {
        if (
            positions.isNotEmpty() ||
            pendingEntries.isNotEmpty()
        ) {
            error(
                "Close/reconcile all positions before deleting Binance credentials."
            )
        }

        stop()
        prefs.edit()
            .remove("api_key")
            .remove("api_secret")
            .remove("positions_json")
            .remove("pending_entries_json")
            .apply()

        candidates = JSONArray()
        primaryCandles = emptyList()
        scanSymbols = mutableListOf()
        clearReconcileRequired()

        return JSONObject()
            .put("configured", false)
            .put("cleared", true)
    }

    fun start(): JSONObject {
        if (killLatched) {
            error("KILL_SWITCH_LATCHED: recover and reset kill switch before START")
        }
        require(
            key().isNotBlank() && secret().isNotBlank()
        ) {
            "Configure Binance Testnet credentials first"
        }
        if (running) {
            return JSONObject()
                .put("started", false)
                .put("reason", "already_running")
        }

        require(!reconcileRequired) {
            "RECONCILE_REQUIRED must be resolved before START"
        }

        prefs.edit()
            .putBoolean("auto_run", true)
            .apply()

        running = true
        paused = false
        startMarketDataStream()
        userStream.start()
        warmCoreHistoryAsync()

        worker = Thread {
            while (running) {
                if (!paused) {
                    try {
                        requestScan()
                    } catch (x: Exception) {
                        lastError =
                            x.javaClass.simpleName + ": " +
                                (x.message ?: "")
                    }
                }

                try {
                    Thread.sleep(15_000L)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.also {
            it.isDaemon = true
            it.start()
        }

        return JSONObject()
            .put("started", true)
            .put("interval_seconds", 15)
    }

    fun stop(): JSONObject {
        running = false
        paused = false
        marketSocket?.close(1000, "Williams stopped")
        marketSocket = null
        marketSocketConnected = false
        userStream.stop()
        userStreamConnected = false
        prefs.edit()
            .putBoolean("auto_run", false)
            .apply()
        worker?.interrupt()
        worker = null

        return JSONObject().put("stopped", true)
    }

    @Synchronized
    fun kill(): JSONObject {
        killLatched = true
        prefs.edit()
            .putBoolean("kill_latched", true)
            .putBoolean("auto_run", false)
            .apply()

        running = false
        paused = true
        worker?.interrupt()
        worker = null
        marketSocket?.close(1000, "Williams kill switch")
        marketSocket = null
        marketSocketConnected = false
        userStream.stop()
        userStreamConnected = false

        val pending = synchronized(pendingEntries) {
            pendingEntries.values.toList()
        }
        for (intent in pending) {
            runCatching {
                signedDelete(
                    "/api/v3/order",
                    "symbol=" + intent.symbol +
                        "&origClientOrderId=" + intent.clientOrderId
                )
            }
        }

        runCatching { recoverPendingEntries() }

        val symbols = positionList().map { it.symbol }
        val errors = mutableListOf<String>()
        for (symbol in symbols) {
            val result = runCatching { sell(symbol) }
            if (result.isFailure) {
                errors += symbol + ": " +
                    (result.exceptionOrNull()?.message ?: "kill sell failed")
            }
        }

        val recoveryOk = runCatching {
            reconcilePositionsWithExchange()
        }.isSuccess

        val failedSymbols = errors.map { it.substringBefore(":") }.toSet()
        val forcedState =
            if (errors.isEmpty() && recoveryOk) {
                stateName()
            } else {
                reconcileRequired = true
                prefs.edit()
                    .putBoolean("reconcile_required", true)
                    .apply()
                "RECONCILE_REQUIRED"
            }

        lastError =
            if (errors.isEmpty() && recoveryOk) {
                "KILL_SWITCH executed; trading remains latched OFF"
            } else {
                "KILL_SWITCH completed with reconciliation warnings"
            }

        return JSONObject()
            .put("killed", true)
            .put("latched", true)
            .put("state", forcedState)
            .put(
                "closed_symbols",
                JSONArray(symbols.filter { it !in failedSymbols })
            )
            .put("errors", JSONArray(errors))
    }

    fun resetKillSwitch(): JSONObject {
        require(!running) {
            "Stop the bot before resetting the kill switch."
        }
        recover()
        require(!reconcileRequired) {
            "Reconciliation is still required."
        }
        killLatched = false
        prefs.edit()
            .putBoolean("kill_latched", false)
            .apply()
        lastError = null
        return JSONObject()
            .put("reset", true)
            .put("state", stateName())
    }

    fun pause(): JSONObject {
        paused = true
        return JSONObject().put("paused", true)
    }

    fun resume(): JSONObject {
        paused = false
        return JSONObject().put("resumed", true)
    }

    @Synchronized
    fun recover(): JSONObject {
        require(
            key().isNotBlank() &&
                secret().isNotBlank()
        ) {
            "Configure Binance Testnet credentials first"
        }

        return try {
            clearReconcileRequired()
            recoverPendingEntries()
            reconcilePositionsWithExchange()

            JSONObject()
                .put("recovered", !reconcileRequired)
                .put("state", stateName())
                .put(
                    "execution_enabled",
                    !reconcileRequired
                )
                .put(
                    "open_positions",
                    positionList().size
                )
                .put(
                    "reserved_risk_pct",
                    reservedRiskPct()
                )
        } catch (x: Exception) {
            setReconcileRequired(
                x.message ?: "recovery failed"
            )
            JSONObject()
                .put("recovered", false)
                .put("state", "RECONCILE_REQUIRED")
                .put("execution_enabled", !reconcileRequired)
                .put(
                    "error",
                    x.message ?: x.javaClass.simpleName
                )
        }
    }

    private fun signedTimestamp(): Long {
        val now = System.currentTimeMillis()
        if (
            now - lastServerTimeSyncMs >
                15 * 1000L
        ) {
            runCatching { syncServerTime() }
        }
        // Binance rejects timestamps even slightly ahead of its clock.
        // Keep a small safety margin on the safe side of server time.
        return System.currentTimeMillis() +
            serverTimeOffsetMs -
            750L
    }

    @Synchronized
    private fun syncServerTime() {
        val syncStartedAtMs = System.currentTimeMillis()
        val remote =
            JSONObject(
                getBody("/api/v3/time")
            ).optLong("serverTime", 0L)

        if (remote <= 0L) {
            error("Binance server time unavailable")
        }

        val after = System.currentTimeMillis()
        val midpoint = (syncStartedAtMs + after) / 2L
        // Estimate network latency using the request midpoint and bias
        // slightly behind Binance to avoid -1021 "timestamp ahead".
        serverTimeOffsetMs = remote - midpoint
        lastServerTimeSyncMs = after
    }

    private fun getBody(path: String): String {
        rateGuard.beforeRequest()
        val request = Request.Builder()
            .url(baseUrl + path)
            .get()
            .build()

        http.newCall(request).execute().use { response ->
            rateGuard.observe(response.headers, response.code)
            val body = response.body?.string() ?: "{}"
            if (!response.isSuccessful) {
                error("Binance HTTP " + response.code + ": " + body)
            }
            return body
        }
    }

    private fun signedAccount(): JSONObject =
        signedRequest(
            method = "GET",
            path = "/api/v3/account",
            params = ""
        )

    private fun signedRequest(
        method: String,
        path: String,
        params: String
    ): JSONObject {
        var lastBody = "{}"
        repeat(2) { attempt ->
            val query = if (params.isBlank()) {
                "timestamp=" + signedTimestamp() + "&recvWindow=" + BINANCE_RECV_WINDOW_MS
            } else {
                params + "&timestamp=" + signedTimestamp() + "&recvWindow=" + BINANCE_RECV_WINDOW_MS
            }
            val signature = hmac(query, secret())
            val request = when (method) {
                "POST" -> Request.Builder()
                    .url(baseUrl + path)
                    .header("X-MBX-APIKEY", key())
                    .post((query + "&signature=" + signature).toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                    .build()
                "DELETE" -> Request.Builder()
                    .url(baseUrl + path + "?" + query + "&signature=" + signature)
                    .header("X-MBX-APIKEY", key())
                    .delete()
                    .build()
                else -> Request.Builder()
                    .url(baseUrl + path + "?" + query + "&signature=" + signature)
                    .header("X-MBX-APIKEY", key())
                    .get()
                    .build()
            }
            rateGuard.beforeRequest()
            http.newCall(request).execute().use { response ->
                rateGuard.observe(response.headers, response.code)
                lastBody = response.body?.string() ?: "{}"
                if (response.isSuccessful) return JSONObject(lastBody)
                if (attempt == 0 && lastBody.contains("-1021")) {
                    runCatching { syncServerTime() }
                    return@use
                }
                error("Binance " + response.code + ": " + lastBody)
            }
        }
        error("Binance timestamp retry failed: " + lastBody)
    }

    private fun signedRawGet(
        path: String,
        params: String
    ): String {
        var lastBody = "{}"
        repeat(2) { attempt ->
            val query = if (params.isBlank()) {
                "timestamp=" + signedTimestamp() +
                    "&recvWindow=" + BINANCE_RECV_WINDOW_MS
            } else {
                params +
                    "&timestamp=" + signedTimestamp() +
                    "&recvWindow=" + BINANCE_RECV_WINDOW_MS
            }
            val signature = hmac(query, secret())
            val request = Request.Builder()
                .url(
                    baseUrl +
                        path +
                        if (query.isBlank()) {
                            ""
                        } else {
                            "?" + query + "&signature=" + signature
                        }
                )
                .header("X-MBX-APIKEY", key())
                .get()
                .build()

            rateGuard.beforeRequest()
            http.newCall(request).execute().use { response ->
                rateGuard.observe(response.headers, response.code)
                lastBody = response.body?.string() ?: "{}"
                if (response.isSuccessful) return lastBody
                if (attempt == 0 && lastBody.contains("-1021")) {
                    runCatching { syncServerTime() }
                    return@use
                }
                error("Binance " + response.code + ": " + lastBody)
            }
        }
        error("Binance signed GET failed: " + lastBody)
    }

    private fun hmac(value: String, secretValue: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(
            SecretKeySpec(
                secretValue.toByteArray(StandardCharsets.UTF_8),
                "HmacSHA256"
            )
        )

        return mac.doFinal(
            value.toByteArray(StandardCharsets.UTF_8)
        ).joinToString("") {
            "%02x".format(it)
        }
    }

    private fun wsStreamUrl(): String =
        "wss://stream.testnet.binance.vision/stream?streams=" +
            coreSymbols.flatMap { symbol ->
                analysisFrames.map { frame ->
                    symbol.lowercase(Locale.US) + "@kline_" + frame
                }
            }.joinToString("/")

    private fun startMarketDataStream() {
        if (marketSocket != null) return
        val request = Request.Builder().url(wsStreamUrl()).build()
        marketSocket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                marketSocketConnected = true
                lastError = null
                Thread {
                    try {
                        Thread.sleep(23L * 60L * 60L * 1000L)
                        if (running && marketSocket === webSocket) {
                            webSocket.close(1000, "planned_24h_reconnect")
                        }
                    } catch (_: InterruptedException) {
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                marketSocketLastEventMs = System.currentTimeMillis()
                runCatching { consumeKlineStream(JSONObject(text)) }
                    .onFailure { lastError = "WS kline: " + (it.message ?: it.javaClass.simpleName) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                marketSocketConnected = false
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                marketSocketConnected = false
                marketSocket = null
                if (running) Thread { Thread.sleep(1500); if (running) startMarketDataStream() }.start()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                marketSocketConnected = false
                marketSocket = null
                lastError = "WS market: " + (t.message ?: t.javaClass.simpleName)
                if (running) Thread { Thread.sleep(2500); if (running) startMarketDataStream() }.start()
            }
        })
    }

    private fun consumeKlineStream(envelope: JSONObject) {
        val data = envelope.optJSONObject("data") ?: return
        if (data.optString("e") != "kline") return
        val k = data.optJSONObject("k") ?: return
        val symbol = k.optString("s").uppercase(Locale.US)
        val frame = k.optString("i")
        if (symbol !in coreSymbols || frame !in analysisFrames) return

        val candle = CandleN(
            k.optLong("t"),
            k.optString("o").toDoubleOrNull() ?: return,
            k.optString("h").toDoubleOrNull() ?: return,
            k.optString("l").toDoubleOrNull() ?: return,
            k.optString("c").toDoubleOrNull() ?: return,
            k.optString("v").toDoubleOrNull() ?: return
        )
        val key = symbol + ":" + frame
        val list = liveCandleCache.computeIfAbsent(key) { mutableListOf() }
        synchronized(list) {
            if (list.isNotEmpty() && list.last().t == candle.t) {
                list[list.lastIndex] = candle
            } else {
                list.add(candle)
                if (list.size > 600) list.removeAt(0)
            }
            val snapshot = buildIndicatorSnapshot(list.toList(), symbol, frame)
            indicatorSnapshots[key] = snapshot
            candleCache[key] = System.currentTimeMillis() to list.toList()

            if (k.optBoolean("x", false)) {
                historyStore.upsertBatch(
                    symbol,
                    frame,
                    listOf(
                        MarketHistoryStore.Candle(
                            openTime = candle.t,
                            closeTime = k.optLong("T"),
                            open = candle.o,
                            high = candle.h,
                            low = candle.l,
                            close = candle.c,
                            volume = candle.v
                        )
                    ),
                )
            }
        }
    }

    private fun buildIndicatorSnapshot(candles: List<CandleN>, symbol: String, frame: String): JSONObject {
        if (candles.size < 40) return JSONObject().put("symbol", symbol).put("interval", frame).put("ready", false)
        val prices = candles.map { it.c }
        val jaw = smma(prices, 13)
        val teeth = smma(prices, 8)
        val lips = smma(prices, 5)
        val i = candles.lastIndex
        val aoNow = ao(candles, i)
        val aoPrev = ao(candles, i - 1)
        val acNow = aoNow - (0 until 5).map { ao(candles, i - it) }.average()
        val acPrev = aoPrev - (0 until 5).map { ao(candles, i - 1 - it) }.average()
        val greenZone = aoNow > aoPrev && acNow > acPrev
        val redZone = aoNow < aoPrev && acNow < acPrev
        val range = max(1e-12, candles[i].h - candles[i].l)
        val mfiProxy = range / max(candles[i].v, 1e-12)
        val previousRange = max(1e-12, candles[i - 1].h - candles[i - 1].l)
        val previousMfiProxy = previousRange / max(candles[i - 1].v, 1e-12)
        val mfiUp = mfiProxy > previousMfiProxy
        val upFractal = latestConfirmedUpFractal(candles, i)
        val downFractal = if (i >= 4 && isDownFractal(candles, i - 2)) i - 2 else null
        return JSONObject()
            .put("symbol", symbol).put("interval", frame).put("ready", true)
            .put("time", candles[i].t).put("price", candles[i].c)
            .put("jaw", jaw[i]).put("teeth", teeth[i]).put("lips", lips[i])
            .put("alligator_bullish", lips[i] > teeth[i] && teeth[i] > jaw[i])
            .put("ao", aoNow).put("ao_previous", aoPrev)
            .put("ao_cross_up", aoPrev <= 0.0 && aoNow > 0.0)
            .put("ac", acNow).put("ac_positive", acNow > 0.0)
            .put("zone", when { greenZone -> "GREEN"; redZone -> "RED"; else -> "GRAY" })
            .put("green_zone", greenZone)
            .put("red_zone", redZone)
            .put("mfi_proxy", mfiProxy)
            .put("mfi_up", mfiUp)
            .put("fractal_up_index", upFractal ?: JSONObject.NULL)
            .put("fractal_down_index", downFractal ?: JSONObject.NULL)
            .put("ao_bullish_divergence", aoBullishDivergence(candles))
            .put("ao_bearish_divergence", aoBearishDivergence(candles))
            .put("wave", waveInfo(candles, frame).path)
    }

    private fun warmCoreHistoryAsync() {
        if (historyWarmupRunning) return
        historyWarmupRunning = true
        Thread {
            try {
                for (symbol in coreSymbols) {
                    for (frame in analysisFrames) {
                        if (!running) return@Thread
                        // Keep the full historical source available to the wave engine.
                        // The live RAM cache remains bounded to avoid Android OOM.
                        runCatching {
                            fetchFullHistory(symbol, frame)
                            val recent = historyStore.loadRecent(symbol, frame, 600)
                            val recentCandleN = recent.map {
                                CandleN(
                                    it.openTime,
                                    it.open,
                                    it.high,
                                    it.low,
                                    it.close,
                                    it.volume
                                )
                            }
                            val key = symbol + ":" + frame
                            liveCandleCache[key] = recentCandleN.toMutableList()
                            candleCache[key] = System.currentTimeMillis() to recentCandleN
                            if (recent.size >= 40) {
                                indicatorSnapshots[key] = buildIndicatorSnapshot(recent, symbol, frame)
                            }
                        }
                    }
                }
            } finally {
                historyWarmupRunning = false
            }
        }.apply { isDaemon = true }.start()
    }

    private fun fetchCandles(
        symbol: String,
        frame: String,
        limit: Int = 150
    ): List<CandleN> {
        val liveKey = symbol.uppercase() + ":" + frame
        liveCandleCache[liveKey]?.let { live ->
            synchronized(live) {
                if (live.size >= min(40, limit)) return live.takeLast(limit)
            }
        }
        val cacheKey = symbol.uppercase() + ":" + frame + ":" + limit
        val persistent = historyStore.loadRecent(
            symbol.uppercase(),
            frame,
            limit
        )
        if (persistent.size >= min(40, limit)) {
            val result = persistent.map {
                CandleN(
                    it.openTime,
                    it.open,
                    it.high,
                    it.low,
                    it.close,
                    it.volume
                )
            }
            candleCache[cacheKey] = System.currentTimeMillis() to result
            return result
        }

        val cached = candleCache[cacheKey]
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.first < scanCacheTtlMs) return cached.second

        // Binance REST has a limited set of native intervals. Build the
        // execution timeframes (including seconds) synthetically from the
        // smallest reliable market data available instead of sending invalid
        // intervals such as 5s/2h/25h to Binance.
        val nativeFrames = setOf("1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "8h", "12h", "1d")
        val sourceFrame = when {
            frame in nativeFrames -> frame
            frame.endsWith("s") -> "1m"
            frame.endsWith("h") -> "1h"
            else -> "1m"
        }

        val sourceLimit = if (frame == sourceFrame) limit
        else (limit * (frameSeconds(frame).coerceAtLeast(60L) /
            frameSeconds(sourceFrame).coerceAtLeast(60L)).toInt() + 20).coerceAtMost(1000)

        val body = getBody(
            "/api/v3/klines?symbol=" + symbol.uppercase() +
                "&interval=" + sourceFrame + "&limit=" + sourceLimit
        )
        val array = JSONArray(body)
        val source = List(array.length()) { i ->
            val row = array.getJSONArray(i)
            CandleN(
                row.getLong(0),
                row.getString(1).toDouble(),
                row.getString(2).toDouble(),
                row.getString(3).toDouble(),
                row.getString(4).toDouble(),
                row.getString(5).toDouble()
            )
        }

        val result = if (frame == sourceFrame) {
            source
        } else {
            aggregateCandles(source, frameSeconds(frame) * 1000L, limit)
        }

        candleCache[cacheKey] = now to result
        return result
    }

    private fun aggregateCandles(
        source: List<CandleN>,
        bucketMs: Long,
        limit: Int
    ): List<CandleN> {
        if (source.isEmpty() || bucketMs <= 0L) return emptyList()

        val out = ArrayList<CandleN>()
        var bucketStart = -1L
        var open = 0.0
        var high = 0.0
        var low = 0.0
        var close = 0.0
        var volume = 0.0

        fun flush() {
            if (bucketStart >= 0L) {
                out.add(CandleN(bucketStart, open, high, low, close, volume))
            }
        }

        for (c in source) {
            val b = (c.t / bucketMs) * bucketMs
            if (b != bucketStart) {
                flush()
                bucketStart = b
                open = c.o
                high = c.h
                low = c.l
                close = c.c
                volume = c.v
            } else {
                high = max(high, c.h)
                low = min(low, c.l)
                close = c.c
                volume += c.v
            }
        }
        flush()

        val now = System.currentTimeMillis()
        return out
            .filter { it.t + bucketMs <= now }
            .takeLast(limit)
    }

    private fun fetchFullHistory(symbol: String, frame: String = "1h"): List<CandleN> {
        val normalizedSymbol = symbol.uppercase()

        if (!historyStore.isComplete(normalizedSymbol, frame)) {
            var endTime = System.currentTimeMillis()
            var page = 0
            var reachedHistoryBeginning = false
            try {
                while (page++ < 10000) {
                    val body = getBody(
                        "/api/v3/klines?symbol=" + normalizedSymbol +
                            "&interval=" + frame +
                            "&limit=1000&endTime=" + endTime
                    )
                    val array = JSONArray(body)
                    if (array.length() == 0) {
                        reachedHistoryBeginning = true
                        break
                    }

                    val batch = ArrayList<MarketHistoryStore.Candle>(
                        array.length()
                    )
                    var oldest = Long.MAX_VALUE

                    for (i in 0 until array.length()) {
                        val row = array.getJSONArray(i)
                        val openTime = row.getLong(0)
                        val closeTime = row.getLong(6)
                        oldest = min(oldest, openTime)

                        if (closeTime >= System.currentTimeMillis()) {
                            continue
                        }

                        batch += MarketHistoryStore.Candle(
                            openTime = openTime,
                            closeTime = closeTime,
                            open = row.getString(1).toDouble(),
                            high = row.getString(2).toDouble(),
                            low = row.getString(3).toDouble(),
                            close = row.getString(4).toDouble(),
                            volume = row.getString(5).toDouble()
                        )
                    }

                    if (batch.isNotEmpty()) {
                        historyStore.upsertBatch(
                            normalizedSymbol,
                            frame,
                            batch
                        )
                    }

                    if (
                        oldest == Long.MAX_VALUE ||
                        oldest <= 0L ||
                        array.length() < 1000
                    ) {
                        reachedHistoryBeginning = true
                        break
                    }

                    endTime = oldest - 1L
                }

                if (!reachedHistoryBeginning) {
                    throw IllegalStateException(
                        "Historical download page limit reached before beginning: " +
                            normalizedSymbol + ":" + frame
                    )
                }

                historyStore.markComplete(
                    normalizedSymbol,
                    frame
                )
            } catch (x: Exception) {
                historyStore.setError(
                    normalizedSymbol,
                    frame,
                    x.message ?: x.javaClass.simpleName
                )
                throw x
            }
        } else {
            // After the initial full download, only refresh the recent tail.
            val newest = historyStore.newest(
                normalizedSymbol,
                frame
            )
            val body = getBody(
                "/api/v3/klines?symbol=" + normalizedSymbol +
                    "&interval=" + frame +
                    "&limit=3&startTime=" +
                    newest.coerceAtLeast(0L)
            )
            val array = JSONArray(body)
            val batch = ArrayList<MarketHistoryStore.Candle>(array.length())

            for (i in 0 until array.length()) {
                val row = array.getJSONArray(i)
                val closeTime = row.getLong(6)
                if (closeTime >= System.currentTimeMillis()) continue
                batch += MarketHistoryStore.Candle(
                    openTime = row.getLong(0),
                    closeTime = closeTime,
                    open = row.getString(1).toDouble(),
                    high = row.getString(2).toDouble(),
                    low = row.getString(3).toDouble(),
                    close = row.getString(4).toDouble(),
                    volume = row.getString(5).toDouble()
                )
            }

            historyStore.upsertBatch(
                normalizedSymbol,
                frame,
                batch,
                complete = true
            )
        }

        // Only a bounded working set enters RAM.
        return historyStore.loadRecent(
            normalizedSymbol,
            frame,
            5000
        ).map {
            CandleN(
                it.openTime,
                it.open,
                it.high,
                it.low,
                it.close,
                it.volume
            )
        }
    }

    private fun loadUniverse(): Triple<List<String>, Map<String, Double>, Map<String, Double>> {
        val info = JSONObject(getBody("/api/v3/exchangeInfo"))
        rateGuard.updateFromExchangeInfo(info)
        val infoRows = info.getJSONArray("symbols")

        val tradingUsdt = HashSet<String>()
        for (i in 0 until infoRows.length()) {
            val item = infoRows.getJSONObject(i)
            if (
                item.optString("status") == "TRADING" &&
                item.optString("quoteAsset") == "USDT"
            ) {
                val symbol = item.optString("symbol")
                if (
                    symbol.isNotBlank() &&
                    !symbol.contains("UPUSDT") &&
                    !symbol.contains("DOWNUSDT") &&
                    !symbol.contains("BULLUSDT") &&
                    !symbol.contains("BEARUSDT")
                ) {
                    tradingUsdt.add(symbol)
                }
            }
        }

        val volumeRows = JSONArray(getBody("/api/v3/ticker/24hr"))
        val volumes = HashMap<String, Double>()

        for (i in 0 until volumeRows.length()) {
            val item = volumeRows.getJSONObject(i)
            val symbol = item.optString("symbol")
            if (tradingUsdt.contains(symbol)) {
                volumes[symbol] =
                    item.optString("quoteVolume").toDoubleOrNull() ?: 0.0
            }
        }

        val bookRows = JSONArray(getBody("/api/v3/ticker/bookTicker"))
        val spreads = HashMap<String, Double>()

        for (i in 0 until bookRows.length()) {
            val item = bookRows.getJSONObject(i)
            val symbol = item.optString("symbol")
            if (tradingUsdt.contains(symbol)) {
                val bid =
                    item.optString("bidPrice").toDoubleOrNull() ?: 0.0
                val ask =
                    item.optString("askPrice").toDoubleOrNull() ?: 0.0
                if (bid > 0.0 && ask >= bid) {
                    spreads[symbol] = (ask - bid) / bid
                }
            }
        }

        val symbols = coreSymbols.filter { tradingUsdt.contains(it) }.toMutableList()
        require(symbols.size == coreSymbols.size) {
            "Core Binance universe incomplete: expected BTCUSDT, ETHUSDT, BNBUSDT, SOLUSDT, XRPUSDT"
        }
        return Triple(symbols, volumes, spreads)
    }

    private fun requestScan() {
        synchronized(this) {
            if (scanning) return
            scanning = true
        }

        Thread {
            val startedAt = System.currentTimeMillis()
            try {
                performScan()
                lastError = null
            } catch (x: Exception) {
                lastError =
                    x.javaClass.simpleName + ": " +
                        (x.message ?: "")
            } finally {
                lastScanDurationMs =
                    System.currentTimeMillis() - startedAt
                lastScanAt = System.currentTimeMillis()
                scanning = false
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun performScan() {
        if (
            key().isNotBlank() &&
            secret().isNotBlank()
        ) {
            runCatching { recover() }
                .onFailure {
                    setReconcileRequired(
                        it.message ?: "recovery failed"
                    )
                }
        }

        if (reconcileRequired) return

        // Update MFE/MAE from live marks before ranking new entries.
        for (open in positionList()) {
            runCatching {
                val mark = JSONObject(
                    getBody("/api/v3/ticker/price?symbol=" + open.symbol)
                ).optString("price").toDoubleOrNull() ?: 0.0
                TradeJournal.updateExcursion(prefs, open.symbol, mark)
            }
        }

        runCatching { manageWilliamsStops() }

        val universe = loadUniverse()
        scanSymbols = universe.first.toMutableList()

        val volumes = universe.second
        val spreads = universe.third

        val futures = scanSymbols.map { symbol ->
            scanExecutor.submit(
                Callable {
                    try {
                        val candles = fetchCandles(symbol, interval, 150)
                        analyseBase(
                            symbol = symbol,
                            candles = candles,
                            spread = spreads[symbol] ?: 0.0,
                            volume = volumes[symbol] ?: 0.0
                        )
                    } catch (_: Exception) {
                        null
                    }
                }
            )
        }

        val preliminary = mutableListOf<BaseAnalysis>()
        futures.forEach { future ->
            runCatching {
                future.get()
            }.getOrNull()?.let {
                if (it is BaseAnalysis) {
                    preliminary.add(it)
                }
            }
        }

        if (preliminary.isEmpty()) {
            error("Scanner received no market data")
        }

        val rankedBase =
            preliminary.sortedByDescending { it.score }

        val waveTargets =
            rankedBase
                .take(waveTopN)
                .filter { it.candles.size >= 140 }

        val final = waveTargets.map { baseCandidate ->
            enrichWithMtf(baseCandidate)
        }.toMutableList()

        rankedBase
            .drop(waveTargets.size)
            .take(20)
            .forEach {
                final.add(it)
            }

        final.sortWith(compareByDescending<BaseAnalysis> { it.signal }
            .thenByDescending { it.score }
            .thenByDescending { it.wave.confidence }
            .thenBy { it.wave.exhaustionRisk }
            .thenBy { it.spreadPct })

        val output = JSONArray()
        final.take(20).forEach { candidate ->
            output.put(toJson(candidate))
        }

        candidates = output
        lastSymbolsScanned = scanSymbols.size
        runCatching { PositionsWidgetProvider.refresh(context) }

        runCatching {
            primaryCandles = fetchCandles(primarySymbol, interval, 150)
        }

        if (
            key().isNotBlank() &&
            secret().isNotBlank()
        ) {
            runCatching { signedAccount() }

            val guard = dailyTradeGuard()
            if (guard.optBoolean("allow", true) == false && guard.optString("mode") == "PAUSED") {
                paused = true
                lastError = "3 losses подряд — автоматическая пауза. Нажми RESUME после проверки."
            }
            if (
                running &&
                !paused &&
                !reconcileRequired &&
                guard.optBoolean("allow", true) &&
                positionList().size < maxOpenPositions
            ) {
                val slots =
                    maxOpenPositions -
                        positionList().size

                final
                    .asSequence()
                    .filter {
                        it.signal &&
                            it.score >= 70.0 &&
                            !positions.containsKey(it.symbol)
                    }
                    .sortedByDescending { it.score }
                    .take(slots)
                    .forEach { candidate ->
                        runCatching {
                            executeBuyWithProtection(candidate)
                        }.onFailure {
                            lastError =
                                "ORDER " +
                                    candidate.symbol +
                                    ": " +
                                    (
                                        it.message
                                            ?: it.javaClass.simpleName
                                    )
                        }

                        if (reconcileRequired) return@forEach
                    }
            }
        }
    }

    private fun manageWilliamsStops() {
        for (stored in positionList()) {
            val rawCandles =
                runCatching {
                    fetchCandles(stored.symbol, "1h", 140)
                }.getOrNull() ?: continue

            // Binance klines include the currently forming candle. Williams'
            // close-based decisions must be made on a completed bar.
            val candles =
                if (rawCandles.size >= 2) {
                    rawCandles.dropLast(1)
                } else {
                    emptyList()
                }

            if (candles.size < 40) continue

            val prices = candles.map { it.c }
            val teeth =
                smma(prices, 8).last()
            val lastClosed =
                candles.last()

            // Stop-close-only style: a confirmed close through the Teeth is a
            // trend-exit event. Do not manufacture an impossible stop above
            // current price; close the managed position instead.
            if (lastClosed.c < teeth) {
                runCatching {
                    sell(stored.symbol)
                }.onFailure { x ->
                    setReconcileRequired(
                        "Williams Teeth close exit failed for " +
                            stored.symbol + ": " +
                            (x.message ?: x.javaClass.simpleName)
                    )
                }
                continue
            }

            var consecutiveGreen = 0
            var trailingLow = Double.POSITIVE_INFINITY
            for (
                i in candles.lastIndex downTo
                    max(5, candles.lastIndex - 9)
            ) {
                val aoNow = ao(candles, i)
                val aoPrev = ao(candles, i - 1)
                val acWindowStart = max(0, i - 4)
                val acNow =
                    aoNow -
                        (acWindowStart..i)
                            .map { ao(candles, it) }
                            .average()
                val acPrevWindowStart = max(0, i - 5)
                val acPrev =
                    aoPrev -
                        (acPrevWindowStart until i)
                            .map { ao(candles, it) }
                            .average()

                val green =
                    aoNow > aoPrev &&
                        acNow > acPrev

                if (!green) break

                consecutiveGreen++
                trailingLow = min(trailingLow, candles[i].l)
                if (consecutiveGreen >= 5) break
            }

            val tick =
                runCatching {
                    symbolFilters(stored.symbol).tick
                }.getOrDefault(0.0)

            if (tick <= 0.0) continue

            var desiredStop = stored.stop

            if (consecutiveGreen >= 5 &&
                trailingLow.isFinite()
            ) {
                desiredStop =
                    max(
                        desiredStop,
                        trailingLow - tick
                    )
            }

            // A stop can be tightened toward the current Teeth only while it
            // remains below the market and improves protection.
            if (teeth > stored.stop &&
                teeth < lastClosed.c
            ) {
                desiredStop =
                    max(
                        desiredStop,
                        teeth - tick
                    )
            }

            if (desiredStop <= stored.stop + tick ||
                desiredStop >= lastClosed.c
            ) {
                continue
            }

            try {
                if (stored.ocoListId.isNotBlank()) {
                    signedDelete(
                        "/api/v3/orderList",
                        "symbol=" + stored.symbol +
                            "&orderListId=" + stored.ocoListId
                    )
                } else if (stored.ocoListClientId.isNotBlank()) {
                    signedDelete(
                        "/api/v3/orderList",
                        "symbol=" + stored.symbol +
                            "&listClientOrderId=" +
                            stored.ocoListClientId
                    )
                } else {
                    setReconcileRequired(
                        "Williams trailing update: managed OCO identifier missing for " +
                            stored.symbol
                    )
                    return
                }

                val distance =
                    ((stored.entry - desiredStop) /
                        stored.entry)
                        .coerceIn(0.001, 0.08)

                val protection =
                    createProtection(
                        symbol = stored.symbol,
                        qty = stored.qty,
                        entry = stored.entry,
                        stopDistance = distance
                    )

                synchronized(positions) {
                    positions[stored.symbol] =
                        stored.copy(
                            stop = protection.stop,
                            take = protection.take,
                            riskPct = protection.riskPct,
                            ocoListClientId =
                                protection.ocoClientId,
                            ocoListId =
                                protection.ocoListId
                        )
                }
                savePersistedState()
            } catch (x: Exception) {
                setReconcileRequired(
                    "Williams trailing stop update failed for " +
                        stored.symbol +
                        ": " +
                        (x.message ?: x.javaClass.simpleName)
                )
                return
            }
        }
    }

    private fun handleUserEvent(event: JSONObject) {
        lastUserEventMs = System.currentTimeMillis()
        when (event.optString("e")) {
            "executionReport" -> {
                lastOrder = JSONObject()
                    .put("symbol", event.optString("s"))
                    .put("side", event.optString("S"))
                    .put("type", event.optString("o"))
                    .put("orderId", event.optString("i"))
                    .put("orderListId", event.optString("g"))
                    .put("clientOrderId", event.optString("c"))
                    .put("executionType", event.optString("x"))
                    .put("status", event.optString("X"))
                    .put("price", event.optString("p"))
                    .put("stopPrice", event.optString("P"))
                    .put("origQty", event.optString("q"))
                    .put("lastQty", event.optString("l"))
                    .put("executedQty", event.optString("z"))
                    .put("quoteQty", event.optString("Z"))
                    .put("eventTime", event.optLong("E"))
                    .put("transactionTime", event.optLong("T"))

                val terminal =
                    event.optString("X").uppercase(Locale.US) in
                        setOf("FILLED", "CANCELED", "REJECTED", "EXPIRED")
                val trade =
                    event.optString("x").uppercase(Locale.US) == "TRADE"

                if (terminal || trade) {
                    Thread {
                        try {
                            Thread.sleep(150L)
                            synchronized(this) {
                                recoverPendingEntries()
                                reconcilePositionsWithExchange()
                            }
                        } catch (x: Exception) {
                            if (!killLatched) {
                                lastError =
                                    "user event reconciliation: " +
                                        (x.message ?: x.javaClass.simpleName)
                            }
                        }
                    }.apply {
                        isDaemon = true
                        start()
                    }
                }
            }

            "listStatus" -> {
                lastOrder = event
                Thread {
                    try {
                        Thread.sleep(100L)
                        synchronized(this) {
                            reconcilePositionsWithExchange()
                        }
                    } catch (_: Exception) {
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
            }

            "eventStreamTerminated" -> {
                userStreamConnected = false
                lastError =
                    "user ws: Binance stream terminated; reconnecting"
            }
        }
    }

    private fun reconcilePosition() {
        reconcilePositionsWithExchange()
    }

    private fun recoverPendingEntries() {
        val pending =
            synchronized(pendingEntries) {
                pendingEntries.values.toList()
            }

        for (intent in pending) {
            val order =
                signedGet(
                    "/api/v3/order",
                    "symbol=" + intent.symbol +
                        "&origClientOrderId=" +
                        intent.clientOrderId
                )

            when (order.optString("status")) {
                "FILLED" -> {
                    val qty =
                        order.optString("executedQty")
                            .toDoubleOrNull() ?: 0.0
                    val quote =
                        order.optString("cummulativeQuoteQty")
                            .toDoubleOrNull() ?: 0.0

                    if (qty <= 0.0 || quote <= 0.0) {
                        throw IllegalStateException(
                            "Pending BUY has no fill: " +
                                intent.symbol
                        )
                    }

                    val lists =
                        signedGet(
                            "/api/v3/openOrderList",
                            ""
                        )
                    val entryPrice = quote / qty

                    val existingBotOco =
                        (lists.optJSONArray("orderList")
                            ?: lists.optJSONArray("ordersLists")
                            ?: lists.optJSONArray("orderLists"))
                            ?.let { rows ->
                                (0 until rows.length())
                                    .mapNotNull { rows.optJSONObject(it) }
                                    .firstOrNull {
                                        it.optString("symbol") ==
                                            intent.symbol &&
                                            it.optString("listClientOrderId")
                                                .startsWith("W4O_")
                                    }
                            }

                    val protection =
                        if (existingBotOco != null) {
                            adoptExistingProtection(
                                symbol = intent.symbol,
                                qty = qty,
                                entry = entryPrice,
                                stopDistance = intent.stopDistance,
                                oco = existingBotOco
                            )
                        } else {
                            if (
                                hasAnyOpenListForSymbol(
                                    lists,
                                    intent.symbol
                                )
                            ) {
                                throw IllegalStateException(
                                    "Unrecognized open order list for " +
                                        intent.symbol
                                )
                            }
                            createProtection(
                                symbol = intent.symbol,
                                qty = qty,
                                entry = entryPrice,
                                stopDistance = intent.stopDistance
                            )
                        }

                    synchronized(positions) {
                        positions[intent.symbol] =
                            PositionState(
                                symbol = intent.symbol,
                                qty = protection.qty,
                                entry = entryPrice,
                                stop = protection.stop,
                                take = protection.take,
                                riskPct = protection.riskPct,
                                ocoListClientId =
                                    protection.ocoClientId,
                                ocoListId = protection.ocoListId,
                                entryOrderId = order.optString(
                                    "orderId"
                                ),
                                entryClientOrderId =
                                    intent.clientOrderId,
                                openedAt =
                                    order.optLong(
                                        "transactTime",
                                        System.currentTimeMillis()
                                    )
                            )
                    }

                    synchronized(pendingEntries) {
                        pendingEntries.remove(
                            intent.symbol
                        )
                    }
                    savePersistedState()
                }

                "CANCELED", "EXPIRED", "REJECTED" -> {
                    synchronized(pendingEntries) {
                        pendingEntries.remove(
                            intent.symbol
                        )
                    }
                    savePersistedState()
                }
            }
        }
    }

    private fun adoptExistingProtection(
        symbol: String,
        qty: Double,
        entry: Double,
        stopDistance: Double,
        oco: JSONObject
    ): Protection {
        val rules = symbolFilters(symbol)
        val normalizedQty = floorStep(qty, rules.step)
        if (normalizedQty < rules.minQty) {
            error("Existing OCO quantity is below Binance minimum")
        }

        val stop = fmtPrice(
            entry * (1.0 - stopDistance),
            rules.tick
        ).toDouble()
        val take = fmtPrice(
            entry * (1.0 + stopDistance * 4.0),
            rules.tick
        ).toDouble()
        if (stop >= entry || take <= entry) {
            error("Invalid adopted OCO prices")
        }

        val listId = oco.optString("orderListId")
            .ifBlank { error("Existing OCO has no orderListId") }
        val clientId = oco.optString("listClientOrderId")
            .ifBlank { error("Existing OCO has no listClientOrderId") }

        return Protection(
            qty = normalizedQty,
            stop = stop,
            take = take,
            riskPct =
                ((entry - stop) / entry).coerceIn(0.0, 1.0) +
                    feeBufferPerSidePct * 2.0,
            ocoClientId = clientId,
            ocoListId = listId
        )
    }

    @Synchronized
    private fun reconcilePositionsWithExchange() {
        if (positions.isEmpty()) {
            savePersistedState()
            return
        }

        val account = signedAccount()
        val balances = account.getJSONArray("balances")
        val openLists =
            runCatching {
                signedGet(
                    "/api/v3/openOrderList",
                    ""
                )
            }.getOrNull()

        val updated = mutableListOf<PositionState>()
        val minRecoveryQty = 0.000001

        for (stored in positionList()) {
            if (
                stored.qty <= 0.0 ||
                stored.entry <= 0.0 ||
                stored.stop <= 0.0
            ) {
                throw IllegalStateException(
                    "Invalid persisted position " +
                        stored.symbol
                )
            }

            val allOrders = JSONArray(
                signedRawGet(
                    "/api/v3/allOrders",
                    "symbol=" + stored.symbol +
                        "&limit=1000"
                )
            )

            val exitFills = mutableListOf<JSONObject>()
            var soldQty = 0.0
            var soldQuote = 0.0

            for (i in 0 until allOrders.length()) {
                val order = allOrders.optJSONObject(i) ?: continue
                if (
                    order.optString("symbol").uppercase() !=
                        stored.symbol.uppercase()
                ) continue
                if (order.optString("side").uppercase() != "SELL") continue
                if (order.optString("status").uppercase() != "FILLED") continue

                val listId =
                    order.optString("orderListId")
                if (
                    stored.ocoListId.isBlank() ||
                    listId != stored.ocoListId
                ) continue

                val entryTime = stored.openedAt
                val orderTime = order.optLong(
                    "transactTime",
                    order.optLong("time", 0L)
                )
                if (entryTime > 0L && orderTime > 0L &&
                    orderTime < entryTime) {
                    continue
                }

                val qty =
                    order.optString("executedQty")
                        .toDoubleOrNull() ?: 0.0
                val quote =
                    order.optString("cummulativeQuoteQty")
                        .toDoubleOrNull() ?: 0.0
                if (qty <= 0.0) continue

                soldQty += qty
                soldQuote += quote
                exitFills.add(order)
            }

            val asset =
                stored.symbol.removeSuffix("USDT")
            var total = 0.0

            for (i in 0 until balances.length()) {
                val b = balances.getJSONObject(i)
                if (b.optString("asset") == asset) {
                    val free =
                        b.optString("free")
                            .toDoubleOrNull() ?: 0.0
                    val locked =
                        b.optString("locked")
                            .toDoubleOrNull() ?: 0.0
                    total = free + locked
                    break
                }
            }

            val expectedRemaining =
                max(0.0, stored.qty - soldQty)
            val tolerance =
                max(
                    minRecoveryQty,
                    max(
                        expectedRemaining,
                        stored.qty
                    ) * 0.005
                )

            if (soldQty > 0.0 && expectedRemaining <= minRecoveryQty) {
                if (total > tolerance) {
                    throw IllegalStateException(
                        stored.symbol +
                            " reports full managed SELL but exchange still "
                            + "holds " + total + " units"
                    )
                }

                val lastExit =
                    exitFills.maxByOrNull {
                        it.optLong(
                            "transactTime",
                            it.optLong("time", 0L)
                        )
                    } ?: throw IllegalStateException(
                        stored.symbol +
                            ": managed SELL evidence missing"
                    )

                val exitPrice =
                    if (soldQuote > 0.0 && soldQty > 0.0) {
                        soldQuote / soldQty
                    } else {
                        lastExit.optString("price")
                            .toDoubleOrNull()
                            ?: stored.entry
                    }
                val pnl =
                    (exitPrice - stored.entry) * stored.qty
                val pnlPct =
                    if (stored.entry > 0.0) {
                        exitPrice / stored.entry - 1.0
                    } else {
                        0.0
                    }
                val exitType =
                    lastExit.optString("type").uppercase()
                val reason =
                    when {
                        exitType.contains("TAKE_PROFIT") ->
                            "TAKE_PROFIT"
                        exitType.contains("STOP_LOSS") ->
                            "STOP_LOSS"
                        else ->
                            "BOT_OCO_EXIT"
                    }

                TradeJournal.close(
                    prefs = prefs,
                    symbol = stored.symbol,
                    exitPrice = exitPrice,
                    reason = reason,
                    order = lastExit
                )
                continue
            }

            if (
                total <= tolerance &&
                soldQty <= 0.0
            ) {
                throw IllegalStateException(
                    stored.symbol +
                        ": exchange position disappeared without "
                        + "managed SELL evidence"
                )
            }

            if (expectedRemaining <= minRecoveryQty) {
                throw IllegalStateException(
                    stored.symbol +
                        ": position quantity is inconsistent with "
                        + "exchange history"
                )
            }

            if (
                abs(total - expectedRemaining) > tolerance
            ) {
                throw IllegalStateException(
                    stored.symbol +
                        " managed balance mismatch: expected " +
                        expectedRemaining +
                        ", exchange " +
                        total
                )
            }

            var current =
                stored.copy(
                    qty = expectedRemaining
                        .coerceAtMost(total)
                )

            val protected =
                (
                    stored.ocoListClientId.isNotBlank() &&
                        openListsContains(
                            openLists,
                            stored.symbol,
                            stored.ocoListClientId
                        )
                ) ||
                    (
                        stored.ocoListId.isNotBlank() &&
                            openListsContainsListId(
                                openLists,
                                stored.symbol,
                                stored.ocoListId
                            )
                    )

            if (!protected) {
                if (
                    hasAnyOpenListForSymbol(
                        openLists,
                        stored.symbol
                    )
                ) {
                    throw IllegalStateException(
                        "Unrecognized open order list for " +
                            stored.symbol
                    )
                }

                val distance =
                    ((stored.entry - stored.stop) /
                        stored.entry)
                        .coerceIn(0.01, 0.08)

                val protection =
                    createProtection(
                        symbol = stored.symbol,
                        qty = current.qty,
                        entry = stored.entry,
                        stopDistance = distance
                    )

                current =
                    current.copy(
                        qty = protection.qty,
                        stop = protection.stop,
                        take = protection.take,
                        riskPct = protection.riskPct,
                        ocoListClientId =
                            protection.ocoClientId,
                        ocoListId = protection.ocoListId
                    )
            }

            updated.add(current)
        }

        synchronized(positions) {
            positions.clear()
            updated.forEach {
                positions[it.symbol] = it
            }
        }
        savePersistedState()
    }

    private fun hasAnyOpenListForSymbol(
        response: JSONObject?,
        symbol: String
    ): Boolean {
        if (response == null) return false
        val rows =
            response.optJSONArray("orderList")
                ?: response.optJSONArray("ordersLists")
                ?: response.optJSONArray("orderLists")
                ?: return false

        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            if (row.optString("symbol") == symbol) {
                return true
            }
        }
        return false
    }

    private fun openListsContainsListId(
        response: JSONObject?,
        symbol: String,
        listId: String
    ): Boolean {
        if (response == null || listId.isBlank()) return false
        val rows =
            response.optJSONArray("orderList")
                ?: response.optJSONArray("ordersLists")
                ?: response.optJSONArray("orderLists")
                ?: return false

        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            if (
                row.optString("symbol") == symbol &&
                row.optString("orderListId") == listId
            ) {
                return true
            }
        }
        return false
    }

    private fun openListsContains(
        response: JSONObject?,
        symbol: String,
        clientId: String
    ): Boolean {
        if (response == null) return false
        val rows =
            response.optJSONArray("orderList")
                ?: response.optJSONArray("ordersLists")
                ?: response.optJSONArray("orderLists")
                ?: return false

        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            if (
                row.optString("symbol") == symbol &&
                row.optString("listClientOrderId") ==
                    clientId
            ) {
                return true
            }
        }
        return false
    }

    private fun signedGet(
        path: String,
        params: String
    ): JSONObject = signedRequest("GET", path, params)

    private fun signedPost(
        path: String,
        params: String
    ): JSONObject = signedRequest("POST", path, params)

    private fun symbolFilters(symbol: String): SymbolRules {
        val info =
            JSONObject(
                getBody(
                    "/api/v3/exchangeInfo?symbol=" +
                        symbol
                )
            )
        val row =
            info.getJSONArray("symbols")
                .getJSONObject(0)
        val filters =
            row.getJSONArray("filters")

        var step = 0.000001
        var minQty = 0.0
        var tick = 0.000001
        var minNotional = 0.0

        for (i in 0 until filters.length()) {
            val f = filters.getJSONObject(i)
            when (f.optString("filterType")) {
                "LOT_SIZE", "MARKET_LOT_SIZE" -> {
                    step =
                        f.optString("stepSize")
                            .toDoubleOrNull() ?: step
                    minQty =
                        max(
                            minQty,
                            f.optString("minQty")
                                .toDoubleOrNull()
                                ?: 0.0
                        )
                }
                "PRICE_FILTER" -> {
                    tick =
                        f.optString("tickSize")
                            .toDoubleOrNull() ?: tick
                }
                "MIN_NOTIONAL", "NOTIONAL" -> {
                    minNotional =
                        max(
                            minNotional,
                            f.optString("minNotional")
                                .toDoubleOrNull() ?: 0.0
                        )
                }
            }
        }

        val decimals =
            max(
                0,
                step.toString()
                    .substringAfter('.', "")
                    .trimEnd('0')
                    .length
            )

        val quoteAllowed =
            row.optBoolean(
                "quoteOrderQtyMarketAllowed",
                true
            )

        val types =
            row.optJSONArray("orderTypes")

        val ocoAllowed =
            row.optBoolean(
                "ocoAllowed",
                types?.let {
                    var take = false
                    var stop = false

                    for (i in 0 until it.length()) {
                        when (it.optString(i)) {
                            "TAKE_PROFIT_LIMIT" ->
                                take = true
                            "STOP_LOSS_LIMIT" ->
                                stop = true
                        }
                    }

                    take && stop
                } ?: false
            )

        return SymbolRules(
            step = step,
            tick = tick,
            decimals = decimals,
            minQty = minQty,
            minNotional = minNotional,
            quoteOrderQtyMarketAllowed =
                quoteAllowed,
            ocoAllowed = ocoAllowed
        )
    }

    private fun floorStep(value: Double, step: Double): Double =
        if (step <= 0.0) value else floor(value / step) * step

    private fun fmtQty(value: Double, decimals: Int): String =
        "%." + decimals.coerceAtMost(8) + "f"
            .format(java.util.Locale.US, value)

    private fun fmtPrice(value: Double, tick: Double): String {
        val rounded = if (tick > 0.0) floor(value / tick) * tick else value
        val decimals = max(0, tick.toString().substringAfter('.', "").trimEnd('0').length)
        return "%." + decimals.coerceAtMost(8) + "f"
            .format(java.util.Locale.US, rounded)
    }

    private data class Protection(
        val qty: Double,
        val stop: Double,
        val take: Double,
        val riskPct: Double,
        val ocoClientId: String,
        val ocoListId: String
    )

    private fun initialWilliamsStopDistance(candidate: BaseAnalysis): Double {
        val candles = candidate.candles
        if (candles.size < 20) return (candidate.atrPct * 2.0).coerceIn(0.01, 0.08)
        val prices = candles.map { it.c }
        val teeth = smma(prices, 8).lastOrNull() ?: prices.last()
        var swingLow = Double.POSITIVE_INFINITY
        for (i in max(2, candles.lastIndex - 30)..candles.lastIndex - 2) {
            if (isDownFractal(candles, i)) swingLow = min(swingLow, candles[i].l)
        }
        val reference = prices.last()
        val atr = reference * candidate.atrPct
        val structuralStop = min(
            if (swingLow.isFinite()) swingLow else reference - atr * 2.0,
            teeth
        ) - atr * 0.15
        return ((reference - structuralStop) / reference).coerceIn(0.005, 0.08)
    }

    private fun executeBuyWithProtection(
        candidate: BaseAnalysis
    ) {
        if (positions.containsKey(candidate.symbol)) {
            return
        }
        if (positionList().size >= maxOpenPositions) {
            error("Maximum open positions reached")
        }
        if (reconcileRequired) {
            error("RECONCILE_REQUIRED")
        }
        if (killLatched) {
            error("KILL_SWITCH_LATCHED")
        }
        if (!marketSocketConnected) {
            error("MARKET_WS_NOT_READY")
        }
        if (!userStreamConnected) {
            error("USER_DATA_STREAM_NOT_READY")
        }
        if (
            reservedRiskPct() >=
                maxTotalRiskPct - 0.000001
        ) {
            error("Portfolio risk budget exhausted")
        }
        if (!candidate.signal) {
            error("signal not confirmed")
        }
        if (candidate.score < 70.0) {
            error("score below entry threshold")
        }
        if (candidate.spreadPct > maxSpreadPct) {
            error("spread too wide")
        }
        if (
            candidate.atrPct <= 0.0 ||
            candidate.atrPct > 0.08
        ) {
            error("ATR outside safety range")
        }

        val rules =
            symbolFilters(candidate.symbol)

        if (!rules.ocoAllowed) {
            error(
                "OCO is not supported for " +
                    candidate.symbol
            )
        }

        val account = signedAccount()
        var usdtFree = 0.0
        val balances =
            account.getJSONArray("balances")

        for (i in 0 until balances.length()) {
            val b = balances.getJSONObject(i)
            if (b.optString("asset") == "USDT") {
                usdtFree =
                    b.optString("free")
                        .toDoubleOrNull() ?: 0.0
                break
            }
        }

        if (usdtFree < 25.0) {
            error("USDT balance too low")
        }

        val remainingRiskPct =
            (maxTotalRiskPct -
                reservedRiskPct())
                .coerceAtLeast(0.0)

        val allocationRiskPct =
            min(
                maxRiskPerTradePct,
                remainingRiskPct
            )

        if (allocationRiskPct < 0.001) {
            error(
                "Remaining portfolio risk budget is too small"
            )
        }

        // Williams-style initial protection: structure/Teeth first, ATR as guard.
        val stopDistance = initialWilliamsStopDistance(candidate)

        val effectiveRiskDistance =
            stopDistance +
                feeBufferPerSidePct * 2.0

        val riskQuote =
            usdtFree * allocationRiskPct

        val notional =
            min(
                usdtFree * 0.25,
                riskQuote / effectiveRiskDistance
            )

        if (
            rules.minNotional > 0.0 &&
            notional < rules.minNotional
        ) {
            error(
                "Notional below Binance minimum: " +
                    rules.minNotional
            )
        }

        if (notional < 10.0) {
            error("order notional too small")
        }

        val clientOrderId =
            "W4B_" +
                java.util.UUID.randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(28)

        synchronized(pendingEntries) {
            pendingEntries[candidate.symbol] =
                PendingEntry(
                    symbol = candidate.symbol,
                    clientOrderId = clientOrderId,
                    notional = notional,
                    stopDistance = stopDistance
                )
        }
        savePersistedState()

        val buyParams =
            if (rules.quoteOrderQtyMarketAllowed) {
                "symbol=" + candidate.symbol +
                    "&side=BUY&type=MARKET" +
                    "&quoteOrderQty=" +
                    "%.2f".format(
                        java.util.Locale.US,
                        notional
                    ) +
                    "&newClientOrderId=" +
                    clientOrderId
            } else {
                val ticker =
                    JSONObject(
                        getBody(
                            "/api/v3/ticker/price?symbol=" +
                                candidate.symbol
                        )
                    )
                val price =
                    ticker.optString("price")
                        .toDoubleOrNull()
                        ?: error(
                            "No market price for " +
                                candidate.symbol
                        )
                val qty =
                    floorStep(
                        notional / price,
                        rules.step
                    )

                if (
                    qty < rules.minQty ||
                    qty * price < rules.minNotional
                ) {
                    error(
                        "Calculated quantity is below Binance filters"
                    )
                }

                "symbol=" + candidate.symbol +
                    "&side=BUY&type=MARKET" +
                    "&quantity=" +
                    fmtQty(
                        qty,
                        rules.decimals
                    ) +
                    "&newClientOrderId=" +
                    clientOrderId
            }

        try {
            val buy =
                signedPost(
                    "/api/v3/order",
                    buyParams
                )

            val qty =
                buy.optString("executedQty")
                    .toDoubleOrNull() ?: 0.0
            val quote =
                buy.optString(
                    "cummulativeQuoteQty"
                ).toDoubleOrNull() ?: 0.0

            if (qty <= 0.0 || quote <= 0.0) {
                error("BUY returned no fill")
            }

            val entry = quote / qty

            val ask =
                runCatching {
                    JSONObject(
                        getBody(
                            "/api/v3/ticker/bookTicker?symbol=" +
                                candidate.symbol
                        )
                    )
                }.getOrNull()
                    ?.optString("askPrice")
                    ?.toDoubleOrNull()
                    ?: 0.0

            if (
                ask > 0.0 &&
                entry >
                    ask * (1.0 + maxSlippagePct)
            ) {
                error(
                    "Slippage exceeds " +
                        (maxSlippagePct * 100.0) +
                        "% for " +
                        candidate.symbol
                )
            }

            val protection =
                createProtection(
                    symbol = candidate.symbol,
                    qty = qty,
                    entry = entry,
                    stopDistance = stopDistance
                )

            val journalSnapshot =
                JSONObject()
                    .put("symbol", candidate.symbol)
                    .put("score", candidate.score)
                    .put("signal", candidate.signal)
                    .put("breakout_distance_pct", candidate.breakoutDistancePct)
                    .put("risk_pct", candidate.riskPct)
                    .put("risk_reward", candidate.riskReward)
                    .put("atr_pct", candidate.atrPct)
                    .put("spread_pct", candidate.spreadPct)
                    .put("htf_confirmed", candidate.htfCandidate)
                    .put("reason", candidate.reason)
                    .put("wave_position", candidate.wave.position)
                    .put("wave_phase", candidate.wave.phase)
                    .put("wave_confidence", candidate.wave.confidence)
                    .put("wave_exhaustion_risk", candidate.wave.exhaustionRisk)
                    .put("wave_path", candidate.wave.path)
                    .put("entry_timeframe", candidate.wave.entryFrame)
                    .put("entry_wave", candidate.wave.entryWave)
                    .put("parent_timeframe", candidate.wave.parentFrame)
                    .put("parent_wave", candidate.wave.parentWave)
                    .put("entry_wave_confidence", candidate.wave.entryConfidence)
                    .put("nested_w3_parent_w5", candidate.wave.nestedW3ParentW5)
                    .put("wave_alligator_bullish", candidate.wave.alligatorBullish)
                    .put("wave_ao_positive", candidate.wave.aoPositive)
            TradeJournal.recordEntry(
                prefs = prefs,
                symbol = candidate.symbol,
                entry = entry,
                qty = protection.qty,
                stop = protection.stop,
                take = protection.take,
                riskPct = protection.riskPct,
                notional = notional,
                candidate = journalSnapshot
            )

            synchronized(positions) {
                positions[candidate.symbol] =
                    PositionState(
                        symbol = candidate.symbol,
                        qty = protection.qty,
                        entry = entry,
                        stop = protection.stop,
                        take = protection.take,
                        riskPct = protection.riskPct,
                        ocoListClientId =
                            protection.ocoClientId,
                        ocoListId = protection.ocoListId,
                        entryOrderId = buy.optString(
                            "orderId"
                        ),
                        entryClientOrderId = clientOrderId,
                        openedAt = buy.optLong(
                            "transactTime",
                            System.currentTimeMillis()
                        )
                    )
            }

            synchronized(pendingEntries) {
                pendingEntries.remove(
                    candidate.symbol
                )
            }
            savePersistedState()

            lastOrder =
                JSONObject()
                    .put("buy", buy)
                    .put(
                        "risk_pct",
                        protection.riskPct
                    )
                    .put(
                        "notional_usdt",
                        notional
                    )
                    .put(
                        "stop_price",
                        protection.stop
                    )
                    .put(
                        "take_profit",
                        protection.take
                    )
                    .put(
                        "oco_list_client_id",
                        protection.ocoClientId
                    )
        } catch (x: Exception) {
            val pending =
                synchronized(pendingEntries) {
                    pendingEntries[candidate.symbol]
                }

            runCatching {
                val order =
                    signedGet(
                        "/api/v3/order",
                        "symbol=" + candidate.symbol +
                            "&origClientOrderId=" +
                            (pending?.clientOrderId
                                ?: clientOrderId)
                    )

                if (
                    order.optString("status") == "FILLED"
                ) {
                    val filled =
                        order.optString("executedQty")
                            .toDoubleOrNull() ?: 0.0

                    if (filled > 0.0) {
                        signedPost(
                            "/api/v3/order",
                            "symbol=" + candidate.symbol +
                                "&side=SELL&type=MARKET" +
                                "&quantity=" +
                                fmtQty(
                                    filled,
                                    rules.decimals
                                )
                        )
                        synchronized(pendingEntries) {
                            pendingEntries.remove(
                                candidate.symbol
                            )
                        }
                        savePersistedState()
                    }
                }
            }

            throw IllegalStateException(
                "Protected BUY failed: " +
                    (x.message ?: x.javaClass.simpleName)
            )
        }
    }

    private fun createProtection(
        symbol: String,
        qty: Double,
        entry: Double,
        stopDistance: Double
    ): Protection {
        val rules = symbolFilters(symbol)

        if (!rules.ocoAllowed) {
            error(
                "OCO is not supported for " +
                    symbol
            )
        }

        val normalizedQty =
            floorStep(qty, rules.step)

        if (normalizedQty < rules.minQty) {
            error(
                "Filled quantity is below Binance minimum"
            )
        }

        val stop =
            fmtPrice(
                entry * (1.0 - stopDistance),
                rules.tick
            ).toDouble()

        val take =
            fmtPrice(
                entry *
                    (1.0 + stopDistance * 4.0),
                rules.tick
            ).toDouble()

        val stopLimit =
            fmtPrice(
                stop * 0.999,
                rules.tick
            ).toDouble()

        if (stop >= entry || take <= entry) {
            error("Invalid TP/SL relationship")
        }

        val ocoClientId =
            "W4O_" +
                java.util.UUID.randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(28)

        val oco =
            signedPost(
                "/api/v3/orderList/oco",
                "symbol=" + symbol +
                    "&side=SELL" +
                    "&quantity=" +
                    fmtQty(
                        normalizedQty,
                        rules.decimals
                    ) +
                    "&aboveType=TAKE_PROFIT_LIMIT" +
                    "&abovePrice=" +
                    fmtPrice(
                        take,
                        rules.tick
                    ) +
                    "&aboveStopPrice=" +
                    fmtPrice(
                        take,
                        rules.tick
                    ) +
                    "&aboveTimeInForce=GTC" +
                    "&belowType=STOP_LOSS_LIMIT" +
                    "&belowStopPrice=" +
                    fmtPrice(
                        stop,
                        rules.tick
                    ) +
                    "&belowPrice=" +
                    fmtPrice(
                        stopLimit,
                        rules.tick
                    ) +
                    "&belowTimeInForce=GTC" +
                    "&newOrderRespType=FULL" +
                    "&listClientOrderId=" +
                    ocoClientId
            )

        val actualStopDistance =
            ((entry - stop) / entry)
                .coerceIn(0.0, 1.0)

        val actualOcoClientId =
            oco.optString("listClientOrderId")
                .ifBlank { ocoClientId }
        val actualOcoListId =
            oco.optString("orderListId").ifBlank {
                error("Binance OCO response has no orderListId")
            }

        return Protection(
            qty = normalizedQty,
            stop = stop,
            take = take,
            riskPct =
                actualStopDistance +
                    feeBufferPerSidePct * 2.0,
            ocoClientId = actualOcoClientId,
            ocoListId = actualOcoListId
        )
    }

    fun sell(symbolInput: String): JSONObject {
        val symbol = symbolInput.uppercase().trim()
        require(symbol.isNotBlank()) {
            "symbol is required"
        }

        val stored =
            synchronized(positions) {
                positions[symbol]
            }

        if (stored == null) {
            // The OCO may have already closed the position.
            recover()
            return JSONObject()
                .put("sold", false)
                .put("symbol", symbol)
                .put("state", stateName())
                .put(
                    "reason",
                    "position_not_found; recovery_checked"
                )
        }

        val rules = symbolFilters(symbol)

        return try {
            // Cancel only the bot-owned OCO. Never cancel unrelated
            // orders that happen to belong to the same symbol.
            if (stored.ocoListId.isNotBlank()) {
                signedDelete(
                    "/api/v3/orderList",
                    "symbol=" + symbol +
                        "&orderListId=" + stored.ocoListId
                )
            } else if (stored.ocoListClientId.isNotBlank()) {
                signedDelete(
                    "/api/v3/orderList",
                    "symbol=" + symbol +
                        "&listClientOrderId=" +
                        stored.ocoListClientId
                )
            } else {
                setReconcileRequired(
                    "Manual SELL refused: missing managed OCO identifier for " +
                        symbol
                )
                return JSONObject()
                    .put("sold", false)
                    .put("symbol", symbol)
                    .put("state", "RECONCILE_REQUIRED")
                    .put("reason", "managed OCO identifier missing")
            }

            val account = signedAccount()
            val balances =
                account.getJSONArray("balances")
            val asset =
                symbol.removeSuffix("USDT")

            var free = 0.0
            for (i in 0 until balances.length()) {
                val b = balances.getJSONObject(i)
                if (b.optString("asset") == asset) {
                    free =
                        b.optString("free")
                            .toDoubleOrNull() ?: 0.0
                    break
                }
            }

            val qty =
                floorStep(
                    min(stored.qty, free),
                    rules.step
                )

            if (qty <= 0.0 || qty < rules.minQty) {
                reconcilePositionsWithExchange()
                return JSONObject()
                    .put("sold", false)
                    .put("symbol", symbol)
                    .put(
                        "reason",
                        "position already closed or quantity unavailable"
                    )
                    .put("state", stateName())
            }

            val sell =
                signedPost(
                    "/api/v3/order",
                    "symbol=" + symbol +
                        "&side=SELL&type=MARKET" +
                        "&quantity=" +
                        fmtQty(
                            qty,
                            rules.decimals
                        )
                )

            val exitPrice =
                sell.optString("cummulativeQuoteQty")
                    .toDoubleOrNull()
                    ?.let { quote -> if (qty > 0.0) quote / qty else 0.0 }
                    ?.takeIf { it > 0.0 }
                    ?: runCatching {
                        JSONObject(
                            getBody("/api/v3/ticker/price?symbol=" + symbol)
                        ).optString("price").toDoubleOrNull() ?: stored.entry
                    }.getOrDefault(stored.entry)
            TradeJournal.close(
                prefs = prefs,
                symbol = symbol,
                exitPrice = exitPrice,
                reason = "MANUAL_SELL",
                order = sell
            )
            synchronized(positions) {
                positions.remove(symbol)
            }
            savePersistedState()

            JSONObject()
                .put("sold", true)
                .put("symbol", symbol)
                .put("quantity", qty)
                .put("order", sell)
                .put("state", stateName())
        } catch (x: Exception) {
            setReconcileRequired(
                "Manual SELL failed for " +
                    symbol +
                    ": " +
                    (x.message ?: x.javaClass.simpleName)
            )

            JSONObject()
                .put("sold", false)
                .put("symbol", symbol)
                .put("state", "RECONCILE_REQUIRED")
                .put(
                    "error",
                    x.message ?: x.javaClass.simpleName
                )
        }
    }

    private fun signedDelete(
        path: String,
        params: String
    ): JSONObject = signedRequest("DELETE", path, params)

    private fun analyseBase(
        symbol: String,
        candles: List<CandleN>,
        spread: Double,
        volume: Double
    ): BaseAnalysis {
        val i = candles.lastIndex
        if (i < 40) {
            return BaseAnalysis(
                symbol = symbol,
                candles = candles,
                score = 0.0,
                signal = false,
                htfCandidate = false,
                wave = neutralWave(candles),
                atrPct = 0.0,
                riskPct = 0.0,
                riskReward = 0.0,
                spreadPct = spread,
                breakoutDistancePct = 0.0,
                reason = "Недостаточно свечей"
            )
        }

        val closes = candles.map { it.c }
        val atrPct = atrPct(candles)
        val atrAbs =
            atrAbs(candles)

        val alligator = alligator(closes)
        val bullish =
            alligator.lips > alligator.teeth &&
                alligator.teeth > alligator.jaw &&
                closes[i] > alligator.lips

        val aoValue = ao(candles, i)
        val aoPositive = aoValue > 0.0

        val fractalIndex = latestConfirmedUpFractal(candles, i)
        val fractalHigh =
            fractalIndex?.let { candles[it].h }
        val teethSeries = smma(closes, 8)
        val fractalTeeth =
            fractalIndex?.let { teethSeries.getOrNull(it) }
        val externalFractal =
            fractalHigh != null &&
                fractalTeeth != null &&
                fractalHigh > fractalTeeth
        val breakoutDistance =
            if (fractalHigh != null && fractalHigh > 0.0) {
                (closes[i] - fractalHigh) / fractalHigh
            } else {
                0.0
            }

        val breakout =
            externalFractal &&
                closes[i] > fractalHigh!! &&
                breakoutDistance <= 0.05

        val trendScore = if (bullish) 35.0 else 0.0
        val aoScore =
            when {
                aoPositive && ao(candles, i - 1) <= aoValue -> 20.0
                aoPositive -> 12.0
                else -> 0.0
            }

        val breakoutScore =
            if (breakout) 25.0 else if (fractalHigh != null) 8.0 else 0.0

        val atrScore =
            when {
                atrPct <= 0.0 -> 0.0
                atrPct <= 0.08 -> (1.0 - atrPct / 0.08) * 10.0
                else -> 0.0
            }

        val volumeScore =
            when {
                volume >= 100_000_000.0 -> 5.0
                volume >= 10_000_000.0 -> 3.0
                else -> 1.0
            }

        val preliminaryWave = waveInfo(candles, "1h")
        var score =
            trendScore +
                aoScore +
                breakoutScore +
                atrScore +
                volumeScore

        val strictSignal =
            bullish &&
                aoPositive &&
                breakout &&
                atrPct in 0.0..0.08

        if (preliminaryWave.position == 3) {
            score += 5.0
        } else if (preliminaryWave.position == 5) {
            score -= 8.0
        }
        if (preliminaryWave.aoBearishDivergence) {
            score -= 12.0
        }

        score = score.coerceIn(0.0, 100.0)

        val riskPct =
            min(0.08, max(0.0, atrPct * 2.0))

        val rrValue =
            if (atrAbs > 0.0) 2.0 else 0.0

        val reason =
            when {
                strictSignal ->
                    "Alligator + AO + подтверждённый Fractal breakout"
                preliminaryWave.position == 5 ->
                    "Wave 5: повышенный риск истощения"
                bullish && aoPositive ->
                    "Бычья пасть + положительный AO; ждём breakout"
                else ->
                    "Наблюдение: структура ещё не готова"
            }

        return BaseAnalysis(
            symbol = symbol,
            candles = candles,
            score = score,
            signal = strictSignal,
            htfCandidate = bullish && aoPositive,
            wave = preliminaryWave,
            atrPct = atrPct,
            riskPct = riskPct,
            riskReward = rrValue,
            spreadPct = spread,
            breakoutDistancePct = breakoutDistance,
            reason = reason
        )
    }

    private fun isCountertrendCorrectionImpulse(
        child: WaveInfo,
        frames: List<WaveInfo>
    ): Boolean {
        if (child.position !in 1..5 || child.direction == "NEUTRAL") return false
        val childSeconds = frameSeconds(child.path.substringBefore(":"))
        val parent = frames
            .filter { it.path != child.path && frameSeconds(it.path.substringBefore(":")) > childSeconds && it.position in listOf(2, 4) }
            .sortedBy { frameSeconds(it.path.substringBefore(":")) }
            .firstOrNull()
        // Parent W2/W4 is a correction against its parent's impulse direction.
        // Its A/C legs may be five-wave impulses, but they must not be treated
        // as a continuation entry in the opposite direction.
        return parent != null && parent.direction != child.direction
    }

    private fun enrichWithMtf(baseCandidate: BaseAnalysis): BaseAnalysis {
        val symbol = baseCandidate.symbol
        val frames = mutableListOf<WaveInfo>()
        frames.add(baseCandidate.wave)

        // The higher timeframe supplies the market context; a lower timeframe
        // supplies the actual entry trigger. A child Wave 3 is therefore
        // allowed inside a parent Wave 3 OR a parent Wave 5.
        val mtfFrames = analysisFrames
        for (frame in mtfFrames) {
            runCatching { fetchCandles(symbol, frame, 300) }
                .getOrNull()?.takeIf { it.size >= 40 }
                ?.let { frames.add(waveInfo(it, frame)) }
        }

        // Full-history reconstruction is retained for the base degree;
        // realtime streams provide the current candle for every degree.
        val history = runCatching { fetchFullHistory(symbol, "1h") }.getOrNull()
        if (!history.isNullOrEmpty()) {
            frames.add(waveInfo(history, "1hHISTORY"))
        }

        val setup = baseCandidate.wave
        var bonus = 0.0
        var exhaustion = setup.exhaustionRisk

        val htf4h = frames.firstOrNull { it.path.startsWith("4h:") }
        val htfConfirmed =
            htf4h != null &&
                htf4h.alligatorBullish &&
                htf4h.aoPositive &&
                htf4h.direction == "UP"

        if (htfConfirmed) bonus += 8.0
        if (frames.any { it.path.startsWith("1d:") && it.direction == "UP" }) bonus += 4.0
        if (setup.position == 3) {
            bonus += 6.0
            exhaustion = min(exhaustion, 30.0)
        }

        val parentW5 = frames.filter {
            it.position == 5 && it.direction == "UP"
        }
        val childW3 = frames.filter {
            it.position == 3 &&
                it.direction == "UP" &&
                it.alligatorBullish &&
                it.aoPositive
        }

        val nestedW3ParentW5 = parentW5.any { parent ->
            childW3.any { child ->
                frameSeconds(child.path.substringBefore(":")) <
                    frameSeconds(parent.path.substringBefore(":"))
            }
        }

        val countertrendCorrectionWaves = frames.filter { child ->
            isCountertrendCorrectionImpulse(child, frames)
        }
        val countertrendCorrectionImpulse = countertrendCorrectionWaves.isNotEmpty()

        if (countertrendCorrectionImpulse) {
            bonus -= 10.0
            exhaustion = max(exhaustion, 55.0)
        }

        if (nestedW3ParentW5) {
            bonus += 10.0
            exhaustion =
                min(
                    exhaustion,
                    max(10.0, setup.exhaustionRisk - 10.0)
                )
        } else if (setup.position == 5) {
            bonus -= 12.0
            exhaustion = max(exhaustion, 65.0)
        }

        // Find the lowest available timeframe with a fresh Wave 3 that is
        // aligned with the higher-timeframe direction. This is the execution
        // timeframe; the 1h/4h wave is context, not necessarily the entry.
        val entryCandidates = frames
            .filter {
                it.position == 3 &&
                    it.direction == "UP" &&
                    it.alligatorBullish &&
                    it.aoPositive &&
                    !isCountertrendCorrectionImpulse(it, frames)
            }
            .sortedBy { frameSeconds(it.path.substringBefore(":")) }

        val entry = entryCandidates.firstOrNull()

        // Find the actual nearest larger wave regardless of direction. A W3
        // inside a parent W2/W4 is part of the correction tree (A/C or B),
        // not a continuation entry, so direction alone must not select a
        // distant bullish parent and accidentally bless it.
        val parent = if (entry != null) {
            frames
                .filter {
                    val seconds = frameSeconds(it.path.substringBefore(":"))
                    seconds > frameSeconds(entry.path.substringBefore(":")) &&
                        it.position in 1..5
                }
                .sortedBy { frameSeconds(it.path.substringBefore(":")) }
                .firstOrNull()
        } else {
            null
        }

        // Three-level hierarchy: senior = main trend, middle = order-control
        // wave, junior = precise entry wave. Senior/middle do not need their
        // own entry signal; only the junior setup triggers the order.
        val junior = entry
        val middle = parent
        val senior = middle?.let { m ->
            frames
                .filter {
                    val s = frameSeconds(it.path.substringBefore(":"))
                    s > frameSeconds(m.path.substringBefore(":")) &&
                        it.position in 1..5
                }
                .sortedBy { frameSeconds(it.path.substringBefore(":")) }
                .firstOrNull()
        }

        val entryInsideCorrection = parent?.position in listOf(2, 4)
        val entrySignal =
            junior != null &&
                !entryInsideCorrection &&
                middle?.position !in listOf(2, 4) &&
                junior.confidence >= 55.0 &&
                junior.alligatorBullish &&
                junior.aoPositive &&
                junior.direction == "UP"

        if (entrySignal) {
            // A lower-TF Wave 3 is the preferred entry, especially when the
            // parent is Wave 3. A child W3 inside a parent W5 is allowed but
            // receives a smaller quality bonus.
            bonus += if (parent?.position == 3) 14.0 else 7.0
            // AO bearish divergence warns that the junior impulse is losing
            // momentum. It lowers priority rather than blindly vetoing a
            // strong nested W3.
            if (junior?.aoBearishDivergence == true) bonus -= 12.0
            if (junior?.aoBullishDivergence == true) bonus += 4.0
        }

        var score = (baseCandidate.score + bonus).coerceIn(0.0, 100.0)

        // Entry is no longer tied to the 1h candle's breakout. We enter on the
        // lower-TF Wave 3 after higher-TF context confirms the direction.
        val finalSignal =
            entrySignal &&
                baseCandidate.atrPct <= 0.08 &&
                baseCandidate.spreadPct <= 0.0015 &&
                baseCandidate.riskReward >= 1.5

        if (!finalSignal && baseCandidate.signal) {
            score = min(score, 84.0)
        }

        val path = frames
            .sortedByDescending { frameSeconds(it.path.substringBefore(":")) }
            .joinToString(" > ") {
                it.path.substringBefore(":") +
                    ":" +
                    if (it.position > 0) "W" + it.position else "?"
            }

        val entryFrame =
            junior?.path?.substringBefore(":") ?: ""
        val parentFrame =
            middle?.path?.substringBefore(":") ?: ""

        val reason = when {
            entrySignal && middle?.position == 3 ->
                "MTF ENTRY: $entryFrame Wave 3 inside parent $parentFrame Wave 3"
            countertrendCorrectionImpulse && !entrySignal ->
                "MTF: countertrend 5-wave structure belongs to W2/W4 correction; no LONG entry"
            entrySignal && nestedW3ParentW5 ->
                "MTF ENTRY: $entryFrame Wave 3 inside parent Wave 5; allowed with reduced priority"
            setup.position == 5 && !nestedW3ParentW5 ->
                "MTF: Wave 5/exhaustion context blocks entry"
            finalSignal ->
                "MTF ENTRY: lower-TF Wave 3 + higher-TF context confirmed"
            else ->
                baseCandidate.reason
        }

        return baseCandidate.copy(
            score = score,
            signal = finalSignal,
            htfCandidate = htfConfirmed,
            wave = setup.copy(
                confidence = min(
                    100.0,
                    setup.confidence +
                        if (htfConfirmed) 10.0 else 0.0
                ),
                exhaustionRisk = exhaustion,
                path = path,
                entryFrame = entryFrame,
                entryWave = junior?.position ?: 0,
                parentFrame = parentFrame,
                parentWave = middle?.position ?: 0,
                entryConfidence = junior?.confidence ?: 0.0,
                nestedW3ParentW5 = nestedW3ParentW5,
                countertrendCorrectionImpulse = countertrendCorrectionImpulse
            ),
            reason = reason
        )
    }

    private fun toJson(candidate: BaseAnalysis): JSONObject {
        val wave = candidate.wave
        val signalStrength =
            if (candidate.signal) "CONFIRMED" else "WATCHING"

        val setupState =
            if (candidate.signal) "SIGNAL" else "WATCHING"

        val nestedW3 =
            wave.path.contains("W3")

        val nestedParentW5 =
            wave.path.contains("W5") &&
                wave.path.contains("W3")

        val displayWaveScore =
            when {
                nestedParentW5 -> 92.0
                wave.position == 3 -> 86.0
                wave.position == 5 -> 42.0
                else -> 65.0
            }

        val riskReward =
            candidate.riskReward

        return JSONObject()
            .put("symbol", candidate.symbol)
            .put("score", candidate.score)
            .put("signal", candidate.signal)
            .put("setup_score", candidate.score)
            .put("signal_strength", signalStrength)
            .put("breakout_distance_pct", candidate.breakoutDistancePct * 100.0)
            .put("risk_pct", candidate.riskPct * 100.0)
            .put("risk_reward", riskReward)
            .put("ao_bearish_divergence", wave.aoBearishDivergence)
            .put("ao_bullish_divergence", wave.aoBullishDivergence)
            .put("atr_pct", candidate.atrPct)
            .put("spread_pct", candidate.spreadPct)
            .put("htf_confirmed", candidate.htfCandidate)
            .put("setup_state", setupState)
            .put("reason", candidate.reason)
            .put("wise_man_count", wiseManCount(candidate))
            .put("signal_family", "ALLIGATOR_AO_FRACTAL")
            .put("wave_score", displayWaveScore)
            .put("wave_position", wave.position)
            .put("wave_phase", wave.phase)
            .put("wave_confidence", wave.confidence)
            .put("wave_exhaustion_risk", wave.exhaustionRisk)
            .put("nested_w3", nestedW3)
            .put("nested_w3_parent_w5", nestedParentW5)
            .put("entry_timeframe", wave.entryFrame)
            .put("entry_wave", wave.entryWave)
            .put("parent_timeframe", wave.parentFrame)
            .put("parent_wave", wave.parentWave)
            .put("entry_wave_confidence", wave.entryConfidence)
            .put(
                "wave_path",
                if (wave.path.isBlank()) {
                    "1d ? > 4h ? > 1h ? > 15m ?"
                } else {
                    wave.path
                }
            )
    }

    private fun wiseManCount(candidate: BaseAnalysis): Int {
        var count = 0
        if (candidate.wave.alligatorBullish) count++
        if (candidate.wave.aoPositive) count++
        if (candidate.breakoutDistancePct > 0.0) count++
        return count.coerceIn(0, 3)
    }

    private fun alligator(
        closes: List<Double>
    ): TripleValues {
        val jaw = smma(closes, 13).lastOrNull() ?: 0.0
        val teeth = smma(closes, 8).lastOrNull() ?: 0.0
        val lips = smma(closes, 5).lastOrNull() ?: 0.0
        return TripleValues(jaw, teeth, lips)
    }

    private data class TripleValues(
        val jaw: Double,
        val teeth: Double,
        val lips: Double
    )

    private fun smma(
        values: List<Double>,
        length: Int
    ): List<Double> {
        if (values.isEmpty()) return emptyList()

        val output = MutableList(values.size) { values[0] }
        for (i in 1 until values.size) {
            output[i] =
                (
                    output[i - 1] * (length - 1) +
                        values[i]
                    ) / length
        }
        return output
    }

    private fun ao(
        candles: List<CandleN>,
        index: Int
    ): Double {
        if (index < 34) return 0.0

        val medians =
            candles.map { (it.h + it.l) / 2.0 }

        val fast =
            medians.subList(index - 4, index + 1).average()

        val slow =
            medians.subList(index - 34, index + 1).average()

        return fast - slow
    }

    private fun aoBearishDivergence(candles: List<CandleN>): Boolean {
        if (candles.size < 45) return false
        val end = candles.lastIndex
        val start = max(2, end - 35)
        val peaks = (start + 1 until end).filter {
            candles[it].h > candles[it - 1].h && candles[it].h >= candles[it + 1].h
        }
        if (peaks.size < 2) return false
        val recent = peaks.last()
        val previous = peaks[peaks.size - 2]
        return candles[recent].h > candles[previous].h && ao(candles, recent) < ao(candles, previous)
    }

    private fun aoBullishDivergence(candles: List<CandleN>): Boolean {
        if (candles.size < 45) return false
        val end = candles.lastIndex
        val start = max(2, end - 35)
        val troughs = (start + 1 until end).filter {
            candles[it].l < candles[it - 1].l && candles[it].l <= candles[it + 1].l
        }
        if (troughs.size < 2) return false
        val recent = troughs.last()
        val previous = troughs[troughs.size - 2]
        return candles[recent].l < candles[previous].l && ao(candles, recent) > ao(candles, previous)
    }
    private fun latestConfirmedUpFractal(
        candles: List<CandleN>,
        currentIndex: Int
    ): Int? {
        val lastConfirmedCenter =
            currentIndex - 2

        if (lastConfirmedCenter < 2) return null

        val start =
            max(2, lastConfirmedCenter - 40)

        for (i in lastConfirmedCenter downTo start) {
            if (isUpFractal(candles, i)) {
                return i
            }
        }

        return null
    }

    private fun isUpFractal(
        candles: List<CandleN>,
        i: Int
    ): Boolean {
        if (i < 2 || i + 2 >= candles.size) {
            return false
        }

        return candles[i].h > candles[i - 1].h &&
            candles[i].h > candles[i - 2].h &&
            candles[i].h > candles[i + 1].h &&
            candles[i].h > candles[i + 2].h
    }

    private fun isDownFractal(
        candles: List<CandleN>,
        i: Int
    ): Boolean {
        if (i < 2 || i + 2 >= candles.size) {
            return false
        }

        return candles[i].l < candles[i - 1].l &&
            candles[i].l < candles[i - 2].l &&
            candles[i].l < candles[i + 1].l &&
            candles[i].l < candles[i + 2].l
    }

    private fun fractalPivots(
        candles: List<CandleN>
    ): List<PivotN> {
        if (candles.size < 10) return emptyList()

        val pivots = mutableListOf<PivotN>()
        for (i in 2 until candles.size - 2) {
            val up = isUpFractal(candles, i)
            val down = isDownFractal(candles, i)

            if (up && !down) {
                pivots.add(PivotN("UP", candles[i].h, i + 2))
            } else if (down && !up) {
                pivots.add(PivotN("DOWN", candles[i].l, i + 2))
            }
        }

        val alternating = mutableListOf<PivotN>()
        for (pivot in pivots) {
            if (alternating.isEmpty()) {
                alternating.add(pivot)
                continue
            }

            val last = alternating.last()
            if (pivot.kind != last.kind) {
                alternating.add(pivot)
            } else {
                val moreExtreme =
                    if (pivot.kind == "UP") {
                        pivot.price > last.price
                    } else {
                        pivot.price < last.price
                    }

                if (moreExtreme) {
                    alternating[alternating.lastIndex] = pivot
                }
            }
        }

        return alternating.takeLast(8)
    }

    private fun waveInfo(
        candles: List<CandleN>,
        frame: String
    ): WaveInfo {
        if (candles.size < 40) {
            return neutralWave(candles, frame)
        }

        val pivots = fractalPivots(candles)
        val current = candles.last().c
        val previous = candles[candles.lastIndex - 1].c

        val series = candles.takeLast(
            min(140, candles.size)
        )

        val low =
            series.minOfOrNull { it.l } ?: current
        val high =
            series.maxOfOrNull { it.h } ?: current
        val range = max(1e-9, high - low)

        val normalized =
            (current - low) / range

        val trendClose =
            candles[
                max(0, candles.lastIndex - 8)
            ].c

        val direction =
            when {
                bullishTrendHint(candles) -> "UP"
                bearishTrendHint(candles) -> "DOWN"
                current > trendClose -> "UP"
                current < trendClose -> "DOWN"
                else -> "NEUTRAL"
            }

        val alligatorValues =
            alligator(candles.map { it.c })

        val bullish =
            alligatorValues.lips >
                alligatorValues.teeth &&
                alligatorValues.teeth >
                alligatorValues.jaw &&
                current >
                alligatorValues.lips

        val aoPositive =
            ao(candles, candles.lastIndex) > 0.0
        val aoBearDiv = aoBearishDivergence(candles)
        val aoBullDiv = aoBullishDivergence(candles)

        var position = 0
        var phase = "UNKNOWN"
        var confidence = 40.0
        var exhaustion = 20.0

        // Structural W1-W5 estimate: fractals are only the swing anchors.
        // Price progression and correction depth validate the count; AO/Alligator
        // confirm momentum but do not manufacture a wave label by themselves.
        if (pivots.size >= 2) {
            val p = pivots.takeLast(6)
            val expected = if (direction == "UP") listOf("DOWN","UP","DOWN","UP","DOWN","UP")
            else listOf("UP","DOWN","UP","DOWN","UP","DOWN")

            fun fitFor(n: Int): Double {
                if (p.size < n) return 0.0
                val s = p.takeLast(n)
                val exp = expected.take(n)
                if (s.map { it.kind } != exp) return 0.0
                var score = 50.0
                fun impulse(a: Double, b: Double): Double = abs(b - a)

                if (n >= 2) {
                    val w1 = if (direction == "UP") s[1].price - s[0].price else s[0].price - s[1].price
                    score += if (w1 > 0.0) 8.0 else -18.0
                }
                if (n >= 3) {
                    val validW2 = if (direction == "UP") s[2].price > s[0].price else s[2].price < s[0].price
                    score += if (validW2) 10.0 else -28.0
                    val w1 = impulse(s[0].price, s[1].price)
                    val retr = if (w1 > 0.0) impulse(s[1].price, s[2].price) / w1 else 9.0
                    score += when { retr in 0.236..0.786 -> 7.0; retr > 1.0 -> -10.0; else -> 0.0 }
                }
                if (n >= 4) {
                    val extendsW1 = if (direction == "UP") s[3].price > s[1].price else s[3].price < s[1].price
                    score += if (extendsW1) 12.0 else -25.0
                    val w1 = impulse(s[0].price, s[1].price)
                    val w3 = impulse(s[2].price, s[3].price)
                    if (w1 > 0.0 && w3 >= w1 * 0.90) score += 8.0
                    else if (w1 > 0.0 && w3 < w1 * 0.65) score -= 8.0
                }
                if (n >= 5) {
                    val validW4 = if (direction == "UP") s[4].price > s[1].price else s[4].price < s[1].price
                    val beforeW3 = if (direction == "UP") s[4].price < s[3].price else s[4].price > s[3].price
                    score += when { validW4 && beforeW3 -> 10.0; validW4 -> 2.0; else -> -18.0 }
                }
                if (n >= 6) {
                    val extendsW3 = if (direction == "UP") s[5].price > s[3].price else s[5].price < s[3].price
                    score += if (extendsW3) 10.0 else -22.0
                    val w1 = impulse(s[0].price, s[1].price)
                    val w3 = impulse(s[2].price, s[3].price)
                    val w5 = impulse(s[4].price, s[5].price)
                    if (minOf(w1, w3, w5) > 0.0) score += if (w3 >= minOf(w1, w5) * 0.85) 5.0 else -12.0
                }
                if (n == 4) {
                    val aoAtW3 = ao(candles, s[3].index.coerceIn(0, candles.lastIndex))
                    if ((direction == "UP" && aoAtW3 > 0.0) || (direction == "DOWN" && aoAtW3 < 0.0)) score += 6.0
                }
                return score.coerceIn(0.0, 100.0)
            }

            // Active impulse leg starts at the latest confirmed corrective pivot.
            // D/U pivot counts of 3 and 5 correspond to W3/W5 setups respectively.
            val active = when {
                direction == "UP" && p.last().kind == "DOWN" -> true
                direction == "DOWN" && p.last().kind == "UP" -> true
                else -> false
            }
            if (active) {
                val candidates = listOf(5 to fitFor(5), 3 to fitFor(3), 1 to fitFor(2))
                val best = candidates.filter { it.second > 0.0 }.maxByOrNull {
                    // Prefer a well-formed W3 over a weak W5. A W5 needs a clear
                    // structure; otherwise the engine deliberately falls back.
                    it.second + if (it.first == 3) 2.0 else 0.0
                }
                if (best != null) {
                    position = best.first
                    phase = "IMPULSE"
                    confidence = best.second
                    exhaustion = if (position == 5) 58.0 else if (position == 3) 22.0 else 18.0
                }
            } else {
                position = if (p.last().kind == "UP") 4 else 2
                phase = "CORRECTION"
                confidence = if (position == 4) fitFor(5).coerceAtLeast(48.0) else fitFor(3).coerceAtLeast(48.0)
                exhaustion = if (position == 4) 35.0 else 28.0
            }

            if (aoBearDiv && position == 5) exhaustion = min(95.0, exhaustion + 18.0)
            if (aoBullDiv && position == 3) confidence = min(98.0, confidence + 8.0)
        } else if (bullish) {
            position = 1
            phase = "IMPULSE"
            confidence = 48.0
            exhaustion = 18.0
        }
        if (bullish && aoPositive) {
            confidence =
                min(98.0, confidence + 6.0)
        }

        val currentLegPct =
            if (range > 0.0) {
                abs(current - low) / range
            } else {
                0.0
            }

        val lastPivot =
            pivots.lastOrNull()

        val path =
            if (position > 0) {
                frame + ":W" + position
            } else {
                frame + ":?"
            }

        return WaveInfo(
            position = position,
            phase = phase,
            confidence = confidence,
            exhaustionRisk = exhaustion,
            direction = direction,
            path = path,
            alligatorBullish = bullish,
            aoPositive = aoPositive,
            aoBearishDivergence = aoBearDiv,
            aoBullishDivergence = aoBullDiv,
            currentLegPct = currentLegPct
        )
    }

    private fun bullishTrendHint(candles: List<CandleN>): Boolean {
        if (candles.size < 10) return false
        val current = candles.last().c
        val past = candles[candles.lastIndex - 8].c
        return current > past
    }

    private fun bearishTrendHint(candles: List<CandleN>): Boolean {
        if (candles.size < 10) return false
        val current = candles.last().c
        val past = candles[candles.lastIndex - 8].c
        return current < past
    }

    private fun neutralWave(
        candles: List<CandleN>,
        frame: String = "1h"
    ): WaveInfo =
        WaveInfo(
            position = 0,
            phase = "UNKNOWN",
            confidence = 0.0,
            exhaustionRisk = 0.0,
            direction = "NEUTRAL",
            path = frame + ":?",
            alligatorBullish = false,
            aoPositive = false,
            aoBearishDivergence = false,
            aoBullishDivergence = false,
            currentLegPct = 0.0
        )

    private fun atrAbs(
        candles: List<CandleN>,
        length: Int = 14
    ): Double {
        if (candles.size < length + 1) return 0.0

        val trueRanges = mutableListOf<Double>()
        for (i in 1 until candles.size) {
            val current = candles[i]
            val previous = candles[i - 1]
            trueRanges.add(
                max(
                    current.h - current.l,
                    max(
                        abs(current.h - previous.c),
                        abs(current.l - previous.c)
                    )
                )
            )
        }

        return trueRanges
            .takeLast(length)
            .average()
    }

    private fun atrPct(
        candles: List<CandleN>
    ): Double {
        val price =
            candles.lastOrNull()?.c ?: 0.0

        if (price <= 0.0) return 0.0

        return atrAbs(candles) / price
    }

    private fun frameSeconds(frame: String): Long = when (frame) {
        "1m" -> 60L
        "3m" -> 180L
        "5m" -> 300L
        "15m" -> 900L
        "30m" -> 1800L
        "1h" -> 3600L
        "2h" -> 7200L
        "4h" -> 14400L
        "6h" -> 21600L
        "8h" -> 28800L
        "12h" -> 43200L
        "1d" -> 86400L
        "3d" -> 259200L
        "1w" -> 604800L
        "1M" -> 2592000L
        else -> 0L
    }


    private fun dailyTradeGuard(): JSONObject {
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val rows = TradeJournal.trades(prefs)
        var count = 0
        var pnl = 0.0
        var consecutiveLosses = 0
        var lastLossAt = 0L
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val closedAt = row.optLong("closed_at", 0L)
            if (closedAt < start || row.optString("status") != "CLOSED") continue
            count++
            pnl += row.optDouble("pnl", 0.0)
        }
        val closedToday = mutableListOf<JSONObject>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val closedAt = row.optLong("closed_at", 0L)
            if (closedAt >= start && row.optString("status") == "CLOSED") {
                closedToday.add(row)
            }
        }
        closedToday.sortByDescending { it.optLong("closed_at", 0L) }
        for (row in closedToday) {
            when (row.optString("outcome").uppercase(Locale.US)) {
                "LOSS" -> {
                    consecutiveLosses++
                    if (lastLossAt == 0L) lastLossAt = row.optLong("closed_at", 0L)
                }
                "WIN", "BREAKEVEN" -> break
            }
        }
        val cooldown = consecutiveLosses >= 2 && lastLossAt > 0L &&
            System.currentTimeMillis() - lastLossAt < 30L * 60L * 1000L
        val hardPause = consecutiveLosses >= 3
        return JSONObject()
            .put("trades_today", count)
            .put("daily_pnl_usdt", pnl)
            .put("consecutive_losses", consecutiveLosses)
            .put("allow", !cooldown && !hardPause)
            .put("mode", when {
                hardPause -> "PAUSED"
                cooldown -> "COOLDOWN"
                else -> "ACTIVE"
            })
    }

    private fun scannerSnapshot(): JSONObject =
        JSONObject()
            .put("version", "4.17.0")
            .put("cached", true)
            .put("scanning", scanning)
            .put("last_error", lastError ?: JSONObject.NULL)
            .put("last_scan_at", lastScanAt)
            .put("last_scan_duration_ms", lastScanDurationMs)
            .put("symbols_scanned", lastSymbolsScanned)
            .put("scan_universe", scannerUniverseLabel)
            .put("deep_wave_targets", waveTopN)
            .put("candidates", candidates)
            .put(
                "best_candidate",
                if (candidates.length() > 0) {
                    candidates.getJSONObject(0)
                } else {
                    JSONObject().put("symbol", primarySymbol)
                }
            )

    fun scanner(refresh: Boolean): JSONObject {
        if (refresh) {
            requestScan()
        }

        return scannerSnapshot()
    }

    fun status(): JSONObject {
        if (primaryCandles.isEmpty()) {
            runCatching {
                primaryCandles =
                    fetchCandles(primarySymbol, interval, 150)
            }.onFailure {
                lastError =
                    it.message ?: it.javaClass.simpleName
            }
        }

        var balance: Double? = null

        if (key().isNotBlank() && secret().isNotBlank()) {
            runCatching {
                val balances =
                    signedAccount().getJSONArray("balances")
                for (i in 0 until balances.length()) {
                    val item = balances.getJSONObject(i)
                    if (item.optString("asset") == "USDT") {
                        balance =
                            item.optString("free")
                                .toDoubleOrNull()
                        break
                    }
                }
            }.onFailure {
                lastError =
                    it.message ?: it.javaClass.simpleName
            }
        }

        val dailyGuard = dailyTradeGuard()

        return JSONObject()
            .put("version", "4.17.0")
            .put("symbol", primarySymbol)
            .put("interval", interval)
            .put("testnet", true)
            .put("running", running)
            .put("paused", paused)
            .put("recovered", true)
            .put("state", stateName())
            .put("execution_enabled", !reconcileRequired)
            .put(
                "position_symbol",
                positionList().firstOrNull()?.symbol ?: JSONObject.NULL
            )
            .put(
                "position_qty",
                positionList().firstOrNull()?.qty ?: JSONObject.NULL
            )
            .put(
                "position_entry",
                positionList().firstOrNull()?.entry ?: JSONObject.NULL
            )
            .put(
                "last_error",
                lastError ?: JSONObject.NULL
            )
            .put(
                "binance_configured",
                key().isNotBlank() && secret().isNotBlank()
            )
            .put(
                "price",
                primaryCandles.lastOrNull()?.c
                    ?: JSONObject.NULL
            )
            .put(
                "quote_balance",
                balance ?: JSONObject.NULL
            )
            .put(
                "position",
                positionList().firstOrNull()?.let {
                    JSONObject()
                        .put("symbol", it.symbol)
                        .put("qty", it.qty)
                        .put("entry", it.entry)
                        .put("stop", it.stop)
                        .put("take", it.take)
                } ?: JSONObject.NULL
            )
            .put(
                "positions",
                JSONArray().apply {
                    positionList().forEach {
                        put(
                            JSONObject()
                                .put("symbol", it.symbol)
                                .put("qty", it.qty)
                                .put("entry", it.entry)
                                .put("stop", it.stop)
                                .put("take", it.take)
                                .put("risk_pct", it.riskPct)
                        )
                    }
                }
            )
            .put("open_positions", positionList().size)
            .put("max_open_positions", maxOpenPositions)
            .put("reserved_risk_pct", reservedRiskPct())
            .put("max_total_risk_pct", maxTotalRiskPct)
            .put("max_risk_per_trade_pct", maxRiskPerTradePct)
            .put("reconcile_required", reconcileRequired)
            .put("pnl", JSONObject.NULL)
            .put("pnl_pct", JSONObject.NULL)
            .put(
                "take_profit_price",
                positionList().firstOrNull()?.take ?: JSONObject.NULL
            )
            .put(
                "stop_loss_price",
                positionList().firstOrNull()?.stop ?: JSONObject.NULL
            )
            .put("stop_loss_pct", 0.02)
            .put("take_profit_pct", 0.04)
            .put("risk_per_trade_pct", maxRiskPerTradePct)
            .put("max_daily_loss_pct", 0.03)
            .put("trades_today", dailyGuard.optInt("trades_today", 0))
            .put("daily_pnl_usdt", dailyGuard.optDouble("daily_pnl_usdt", 0.0))
            .put("consecutive_losses", dailyGuard.optInt("consecutive_losses", 0))
            .put("trading_mode", dailyGuard.optString("mode", "ACTIVE"))
            .put("server_time", System.currentTimeMillis())
            .put("market_ws_connected", marketSocketConnected)
            .put("user_ws_connected", userStreamConnected)
            .put("user_stream_last_event_ms", lastUserEventMs)
            .put("kill_switch_latched", killLatched)
            .put("rate_limits", rateGuard.snapshot())
            .put("history", historyStore.status(coreSymbols, analysisFrames))
            .put("scanner_scanning", scanning)
            .put("scanner_symbols", lastSymbolsScanned)
            .put("scanner_last_scan_at", lastScanAt)
            .put("scanner_duration_ms", lastScanDurationMs)
    }

    fun historyStatus(): JSONObject =
        historyStore.status(coreSymbols, analysisFrames)

    fun indicators(requestedSymbol: String? = null, requestedInterval: String? = null): JSONObject {
        val symbol = requestedSymbol?.uppercase(Locale.US) ?: primarySymbol
        val frame = requestedInterval ?: interval
        return indicatorSnapshots[symbol + ":" + frame]
            ?: buildIndicatorSnapshot(
                fetchCandles(symbol, frame, 150),
                symbol,
                frame
            )
    }

    fun klines(
        requestedSymbol: String? = null,
        requestedInterval: String? = null
    ): JSONObject {
        val symbol = requestedSymbol?.trim()?.uppercase(Locale.US)?.takeIf { it.isNotBlank() } ?: primarySymbol
        val selectedInterval = requestedInterval?.trim()
            ?.takeIf { it in listOf("1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "8h", "12h", "1d", "3d", "1w", "1M") }
            ?: interval
        val sourceCandles = if (symbol == primarySymbol && selectedInterval == interval) {
            if (primaryCandles.isEmpty()) runCatching { primaryCandles = fetchCandles(primarySymbol, selectedInterval, 150) }
            primaryCandles
        } else {
            runCatching { fetchCandles(symbol, selectedInterval, 150) }.getOrElse { emptyList() }
        }

        val output = JSONArray()
        val prices =
            sourceCandles.map { it.c }

        if (prices.isNotEmpty()) {
            val jaw = smma(prices, 13)
            val teeth = smma(prices, 8)
            val lips = smma(prices, 5)

            val start =
                max(0, sourceCandles.size - 120)

            for (i in start until sourceCandles.size) {
                val c = sourceCandles[i]
                output.put(
                    JSONObject()
                        .put("time", c.t)
                        .put("open", c.o)
                        .put("high", c.h)
                        .put("low", c.l)
                        .put("close", c.c)
                        .put(
                            "jaw",
                            jaw.getOrNull(i)
                                ?: JSONObject.NULL
                        )
                        .put(
                            "teeth",
                            teeth.getOrNull(i)
                                ?: JSONObject.NULL
                        )
                        .put(
                            "lips",
                            lips.getOrNull(i)
                                ?: JSONObject.NULL
                        )
                        .put(
                            "ao",
                            ao(sourceCandles, i)
                        )
                        .put(
                            "long_signal",
                            i >= 40 &&
                                alligator(
                                    prices.subList(
                                        0,
                                        i + 1
                                    )
                                ).let { values ->
                                    values.lips > values.teeth &&
                                        values.teeth > values.jaw &&
                                        prices[i] > values.lips &&
                                        ao(sourceCandles, i) > 0.0
                                }
                        )
                        .put(
                            "fractal_up",
                            isUpFractal(sourceCandles, i)
                        )
                        .put(
                            "fractal_down",
                            isDownFractal(sourceCandles, i)
                        )
                )
            }
        }

        return JSONObject()
            .put("symbol", symbol)
            .put("interval", selectedInterval)
            .put("candles", output)
    }

    fun trades(): JSONArray = TradeJournal.trades(prefs)

    fun tradeStats(): JSONObject = TradeJournal.stats(prefs)

    fun logs(): JSONArray =
        JSONArray().put(
            JSONObject()
                .put("created_at", System.currentTimeMillis())
                .put("level", "INFO")
                .put(
                    "message",
                    if (reconcileRequired) {
                        "Recovery required; trading is blocked"
                    } else if (scanning) {
                        "Standalone scanner is running; TESTNET; execution enabled"
                    } else {
                        "Standalone native engine active; TESTNET"
                    }
                )
        )

    fun settings(): JSONObject =
        JSONObject()
            .put("version", "4.17.0")
            .put("symbol", primarySymbol)
            .put("interval", interval)
            .put("position_fraction", 0.95)
            .put("stop_loss_pct", 0.02)
            .put("take_profit_pct", 0.04)
            .put("poll_seconds", 15)
            .put("scan_mode", "adaptive_parallel_cached")
            .put("wave_timeframes", analysisFrames.joinToString(","))
            .put("realtime_multi_timeframe_stream", marketSocketConnected)
            .put("core_symbols", coreSymbols.joinToString(","))
            .put("full_history_wave_analysis", true)
            .put("full_history_base_timeframe", "1h")
            .put("risk_per_trade_pct", maxRiskPerTradePct)
            .put("max_daily_loss_pct", 0.03)
            .put("max_trades_per_day", 0)
            .put("max_consecutive_losses", 3)
            .put("cooldown_minutes", 30)
            .put("min_risk_reward", 1.5)
            .put("atr_period", 14)
            .put("max_atr_pct", 0.08)
            .put("max_spread_pct", 0.0015)
            .put("require_htf_confirmation", true)
            .put("htf_interval", "4h")
            .put("strategy_name", "Williams Profitunity Conservative")
            .put("standalone", true)
            .put("execution_enabled", !reconcileRequired)
            .put("max_scan_symbols", maxScanSymbols)
            .put("scanner_universe", scannerUniverseLabel)
            .put("liquidity_preselect", maxScanSymbols)
            .put("deep_wave_targets", waveTopN)
            .put("scanner_strategy", "liquidity -> base -> deep MTF/Waves -> risk -> score")
            .put("wave_top_n", waveTopN)
            .put("trade_journal", true)
            .put("trade_journal_max_rows", 500)
}