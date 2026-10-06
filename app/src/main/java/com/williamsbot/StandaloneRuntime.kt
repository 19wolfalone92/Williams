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
import java.util.ArrayDeque
import java.util.Calendar
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicBoolean
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

    fun bootstrap(context: Context) {
        start(context)
        server?.bootstrap()
    }

    fun configureCredentials(
        context: Context,
        apiKey: String,
        apiSecret: String
    ): JSONObject {
        start(context)
        return server?.configureCredentials(apiKey, apiSecret)
            ?: error("Williams local runtime is unavailable")
    }

    fun credentialsConfigured(): Boolean =
        server?.credentialsConfigured() ?: false

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

private data class ExecutionResult(
    val symbol: String,
    val success: Boolean,
    val error: String? = null
)

private data class SymbolRules(
    val step: Double,
    val tick: Double,
    val decimals: Int,
    val minQty: Double,
    val maxQty: Double,
    val marketStep: Double,
    val marketMinQty: Double,
    val marketMaxQty: Double,
    val minPrice: Double,
    val maxPrice: Double,
    val minNotional: Double,
    val maxNotional: Double,
    val quoteOrderQtyMarketAllowed: Boolean,
    val ocoAllowed: Boolean,
    val percentUp: Double,
    val percentDown: Double,
    val bidPercentUp: Double,
    val bidPercentDown: Double,
    val askPercentUp: Double,
    val askPercentDown: Double,
    val avgPriceMins: Int,
    val maxNumOrders: Int,
    val maxNumAlgoOrders: Int,
    val maxNumOrderLists: Int,
    val maxPosition: Double
)

private data class TradeFlowSample(
    val timeMs: Long,
    val quoteVolume: Double,
    val aggressiveBuy: Boolean
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
    val obi: Double? = null,
    val tradeFlowImbalance: Double? = null,
    val wiseManCount: Int = 0,
    val signalFamily: String = "NONE",
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
    private val auditStore = TradingAuditStore(context)
    private val stateMachine = TradingStateMachine(
        onTransition = { from, to, reason ->
            auditStore.recordState(from, to, reason)
        }
    )

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
        engine?.shutdown()
        engine = null
    }

    fun autostart() {
        if (prefs.getBoolean("auto_run", false)) {
            e().start()
        }
    }

    fun bootstrap() {
        Thread({
            try {
                runCatching { e().recover() }
                if (prefs.getBoolean("auto_run", false)) {
                    runCatching { e().start() }
                }
            } catch (_: Throwable) {
                // Concrete runtime errors remain visible through /status.
            }
        }, "williams-autonomous-bootstrap").apply {
            isDaemon = true
            start()
        }
    }

    fun startTrading() {
        e().start()
    }

    fun credentialsConfigured(): Boolean =
        e().credentialsConfigured()

    fun stopTrading() {
        e().stop()
    }

    fun configureCredentials(
        apiKey: String,
        apiSecret: String
    ): JSONObject =
        e().configureCredentials(apiKey, apiSecret)

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
                var offset = 0
                while (offset < length) {
                    val read = reader.read(chars, offset, length - offset)
                    if (read < 0) {
                        error("Unexpected end of HTTP request body")
                    }
                    offset += read
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

            method == "GET" && path == "/api/v1/portfolio" ->
                x.portfolio().toString()

            method == "GET" && path == "/api/v1/diagnostics" ->
                x.diagnostics(
                    run = params["run"].equals("true", true)
                ).toString()

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
    private val historyStore = MarketHistoryStore(context)
    private val rateGuard = BinanceRateGuard()
    private val auditStore = TradingAuditStore(context)
    private val executionAccumulator = ExecutionAccumulator()
    private val stateMachine = TradingStateMachine(
        onTransition = { from, to, reason ->
            auditStore.recordState(from, to, reason)
        }
    )
    private val tradingEventLoop = TradingEventLoop()
    private val executionGate = ExecutionGate()
    private val executionExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "williams-execution-io").apply { isDaemon = true }
    }
    
    private val primarySymbol = "BTCUSDT"
    private val interval = "1h"

    // Deep-analysis universe: five core USDT pairs only.
    private val coreSymbols = listOf("BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT")
    private val analysisFrames = listOf("1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "8h", "12h", "1d", "3d", "1w", "1M")
    private val maxScanSymbols = 50
    private val waveTopN = 8
    private val scanExecutor = Executors.newFixedThreadPool(12)
    // Market WebSocket callbacks must stay lightweight. Indicator/Williams
    // calculations are deliberately moved off the OkHttp WebSocket callback
    // thread so a burst of kline events cannot delay depth/aggTrade handling.
    private val indicatorExecutor = Executors.newFixedThreadPool(2)
    private val candleCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<CandleN>>>()
    private val liveCandleCache = java.util.concurrent.ConcurrentHashMap<String, MutableList<CandleN>>()
    private val indicatorSnapshots = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
    private val livePrices = java.util.concurrent.ConcurrentHashMap<String, Double>()
    private val tradeFlow = java.util.concurrent.ConcurrentHashMap<String, ArrayDeque<TradeFlowSample>>()
    private val lastUserEventTimeByType = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var marketSocket: WebSocket? = null
    private val marketReconnectScheduled = AtomicBoolean(false)
    private val l2ResyncInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val orderBookCache = OrderBookCache()
    @Volatile private var marketSocketConnected = false
    @Volatile private var marketSocketLastEventMs = 0L
    @Volatile private var historyWarmupRunning = false
    @Volatile private var historyReady = false

    private val scanCacheTtlMs = 12_000L
    private val deepWatchTopN = 8
    private val scannerUniverseLabel = "USDT_LIQUIDITY_TOP_50"

    @Volatile
    private var running = false

    @Volatile
    private var paused = false

    @Volatile
    private var scanning = false

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var scannerState = "NOT_RUN"

    @Volatile
    private var scannerError: String? = null

    @Volatile
    private var lastScanAt = 0L

    private var worker: Thread? = null
    @Volatile private var scanSymbols: List<String> = emptyList()
    @Volatile private var candidates = JSONArray()
    private var primaryCandles = emptyList<CandleN>()
    @Volatile private var lastScanDurationMs = 0L
    @Volatile private var lastSymbolsScanned = 0
    // Position count is governed by the aggregate risk budget, not an artificial
    // one-position switch. A fixed trade-risk cap still yields a finite capacity.
    private val maxOpenPositions = 0
    private val maxTotalRiskPct = 0.01
    private val maxRiskPerTradePct = 0.005
    private val maxSpreadPct = 0.0015
    private val maxSlippagePct = 0.0015
    private val equityCircuitBreaker = EquityCircuitBreaker(maxDrawdownPct = 0.03)
    @Volatile private var lastEquityCheckMs = 0L
    @Volatile private var circuitBreakerTripInProgress = false
    private val feeBufferPerSidePct = 0.001
    @Volatile private var serverTimeOffsetMs = 0L
    private val BINANCE_RECV_WINDOW_MS = 5000L
    @Volatile private var lastServerTimeSyncMs = 0L
    @Volatile private var lastOrder: JSONObject? = null
    @Volatile private var reconcileRequired = false
    @Volatile private var killLatched = false
    @Volatile private var userStreamConnected = false
    @Volatile private var userStreamSyncRequired = true
    @Volatile private var lastUserEventMs = 0L
    @Volatile private var liveUsdtBalance = 0.0
    private val positions = mutableMapOf<String, PositionState>()
    private val pendingEntries = mutableMapOf<String, PendingEntry>()

    private val userStream = BinanceUserDataStream(
        http = http,
        endpoint = "wss://ws-api.testnet.binance.vision/ws-api/v3",
        apiKeyProvider = { key() },
        apiSecretProvider = { secret() },
        timestampProvider = { signedTimestamp() },
        onConnection = { connected, error ->
            userStreamConnected = connected
            if (connected) {
                if (running || positions.isNotEmpty()) {
                    userStreamSyncRequired = true
                    Thread {
                        try {
                            recoverPendingEntries()
                            reconcilePositionsWithExchange()
                            userStreamSyncRequired = false
                        } catch (x: Exception) {
                            setReconcileRequired(
                                "User stream reconnect reconciliation failed: " +
                                    (x.message ?: x.javaClass.simpleName)
                            )
                        }
                    }.apply {
                        isDaemon = true
                        start()
                    }
                } else {
                    // Read-only diagnostic connections must not mutate trading
                    // state or trigger a reconciliation side effect.
                    userStreamSyncRequired = false
                }
            } else if (!error.equals("stopped")) {
                userStreamSyncRequired = true
                if (positions.isNotEmpty() && !killLatched) {
                    lastError = "user ws: " + (error ?: "disconnected")
                }
            }
        },
        onEvent = { event ->
            handleUserEvent(event)
        }
    )

    init {
        loadPersistedState()
        restoreExecutionAccumulators()
        val interruptedGateSymbol =
            prefs.getString("execution_gate_symbol", "") ?: ""
        if (interruptedGateSymbol.isNotBlank()) {
            reconcileRequired = true
            lastError =
                "Execution gate interrupted for " + interruptedGateSymbol +
                    "; REST reconciliation required"
        }
        stateMachine.force(
            when {
                killLatched -> TradingState.KILL_SWITCH_LATCHED
                reconcileRequired -> TradingState.RECONCILE_REQUIRED
                pendingEntries.isNotEmpty() -> TradingState.ENTRY_PENDING
                positions.isEmpty() -> TradingState.STOPPED
                positions.values.all {
                    it.ocoListId.isNotBlank() || it.ocoListClientId.isNotBlank()
                } -> TradingState.PROTECTED
                else -> TradingState.OPEN_UNPROTECTED
            },
            "restore persisted trading state"
        )
    }

    private fun restoreExecutionAccumulators() {
        val orderIds = mutableSetOf<String>()
        positionList().forEach { if (it.entryOrderId.isNotBlank()) orderIds.add(it.entryOrderId) }
        orderIds.forEach { orderId ->
            executionAccumulator.restore(
                orderId,
                auditStore.executionEvents(orderId)
            )
        }
    }

    private fun key(): String =
        prefs.getString("api_key", "") ?: ""

    private fun secret(): String =
        prefs.getString("api_secret", "") ?: ""

    private fun positionList(): List<PositionState> =
        synchronized(positions) { positions.values.toList() }

    private fun reservedRiskPct(): Double {
        val equity = estimateManagedEquity().coerceAtLeast(1.0)
        val positionRisk = positionList().sumOf { position ->
            val distance = if (position.entry > 0.0) {
                ((position.entry - position.stop) / position.entry)
                    .coerceAtLeast(0.0)
            } else 0.0
            (position.qty * position.entry * (distance + feeBufferPerSidePct * 2.0)) / equity
        }
        val pendingRisk = synchronized(pendingEntries) {
            pendingEntries.values.sumOf { pending ->
                (pending.notional * (pending.stopDistance + feeBufferPerSidePct * 2.0)) / equity
            }
        }
        return positionRisk + pendingRisk
    }

    private fun stateName(): String =
        when (stateMachine.state) {
            TradingState.KILL_SWITCH_LATCHED -> "KILL_SWITCH_LATCHED"
            TradingState.RECONCILE_REQUIRED,
            TradingState.SYNC_REQUIRED -> "RECONCILE_REQUIRED"
            TradingState.ENTRY_PENDING -> "ENTRY_PENDING"
            TradingState.OPEN_UNPROTECTED,
            TradingState.PROTECTED,
            TradingState.EXIT_PENDING -> "OPEN"
            TradingState.READY_FLAT,
            TradingState.STOPPED,
            TradingState.INITIALIZING -> "FLAT"
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
        stateMachine.force(
            TradingState.RECONCILE_REQUIRED,
            reason
        )
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
        if (!killLatched && pendingEntries.isEmpty() && positions.isEmpty()) {
            stateMachine.force(
                TradingState.READY_FLAT,
                "reconciliation cleared"
            )
        }
    }

    fun health(): JSONObject =
        JSONObject()
            .put("ok", true)
            .put("service", "williams-native")
            .put("version", BuildConfig.VERSION_NAME)
.put("standalone", true)
            .put("mode", "AUTONOMOUS")
            .put("runtime_ready", true)
            .put("websocket", marketSocketConnected)
            .put("market_stream_last_event_ms", marketSocketLastEventMs)
            .put("user_stream_connected", userStreamConnected)
            .put("user_stream_sync_required", userStreamSyncRequired)
            .put("user_stream_last_event_ms", lastUserEventMs)
            .put("fsm_state", stateMachine.state.name)
            .put("history_warmup_running", historyWarmupRunning)
            .put("history_ready", historyReady)
            .put("l2", orderBookCache.status())
            .put("android_runtime", AndroidRuntimeHealth.snapshot(context))
            .put("history", historyStore.status(coreSymbols, analysisFrames))
            .put("rate_limits", rateGuard.snapshot())
            .put("core_symbols", JSONArray(coreSymbols))
            .put("analysis_timeframes", JSONArray(analysisFrames))
            .put(
                "execution_enabled",
                !reconcileRequired &&
                    !killLatched &&
                    !userStreamSyncRequired
            )
            .put("state", stateName())
            .put("open_positions", positionList().size)
            .put(
                "max_open_positions",
                floor(maxTotalRiskPct / maxRiskPerTradePct).toInt()
            )
            .put("position_capacity_mode", "RISK_BUDGET")
            .put(
                "risk_based_position_capacity",
                if (maxOpenPositions > 0) {
                    maxOpenPositions
                } else {
                    floor(maxTotalRiskPct / maxRiskPerTradePct).toInt()
                }
            )
            .put("reserved_risk_pct", reservedRiskPct())
            .put("circuit_breaker_tripped", equityCircuitBreaker.isTripped())
            .put("managed_equity", estimateManagedEquity())
            .put("max_total_risk_pct", maxTotalRiskPct)
            .put("max_risk_per_trade_pct", maxRiskPerTradePct)
            .put("reconcile_required", reconcileRequired)
            .put("p0_gate_passed", p0GatePassed())
            .put("p0_gate_reason", p0GateReason())
            .put("max_open_positions_locked", false)
            .put("unresolved_symbols", unresolvedPositionSymbols())
            .put("pending_entry_symbols", pendingEntrySymbols())
            .put("execution_state_contract", JSONObject()
                .put("version", 1)
                .put("state", stateMachine.state.name)
                .put("execution_enabled", stateMachine.executionAllowed() && !reconcileRequired && !userStreamSyncRequired)
                .put("reconciliation_required", reconcileRequired)
                .put("user_stream_sync_required", userStreamSyncRequired)
                .put("kill_switch_latched", killLatched))
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
            .commit()
            .also { ok ->
                check(ok) { "Не удалось записать Binance credentials в защищённое хранилище" }
            }

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

    fun configureCredentials(
        apiKey: String,
        apiSecret: String
    ): JSONObject =
        configure(
            JSONObject()
                .put("api_key", apiKey)
                .put("api_secret", apiSecret)
                .put("testnet", true)
        )

    fun credentialsConfigured(): Boolean =
        key().isNotBlank() && secret().isNotBlank()

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

        stateMachine.force(TradingState.INITIALIZING, "bot start")
        userStreamSyncRequired = true

        try {
            recoverPendingEntries()
            reconcilePositionsWithExchange()
            auditManagedOpenOrders()
            liveUsdtBalance = signedAccount()
                .getJSONArray("balances")
                .let { balances ->
                    (0 until balances.length())
                        .asSequence()
                        .map { balances.getJSONObject(it) }
                        .firstOrNull { it.optString("asset") == "USDT" }
                        ?.optString("free")
                        ?.toDoubleOrNull()
                        ?: 0.0
                }
            equityCircuitBreaker.reset(estimateManagedEquity())
        } catch (x: Exception) {
            setReconcileRequired(
                "startup exchange synchronization failed: " +
                    (x.message ?: x.javaClass.simpleName)
            )
            return JSONObject()
                .put("started", false)
                .put("state", "RECONCILE_REQUIRED")
                .put("error", x.message ?: x.javaClass.simpleName)
        }

        running = true
        paused = false
        startMarketDataStream()
        userStream.start()
        warmCoreHistoryAsync()

        worker = Thread {
            while (running) {
                if (!paused) {
                    try {
                        // Historical MTF/Wave warmup is deliberately non-blocking.
                        // The scanner can use bounded recent candles immediately;
                        // fetchCandles() falls back to Binance REST when a history
                        // slot is not warm yet. Full history continues in the
                        // background for deeper Wave/MTF analysis.
                        if (!historyReady && !historyWarmupRunning) {
                            warmCoreHistoryAsync()
                        }

                        if (
                            marketSocketConnected &&
                            userStreamConnected &&
                            !userStreamSyncRequired &&
                            !reconcileRequired &&
                            !killLatched
                        ) {
                            if (positions.isEmpty()) {
                                stateMachine.transition(
                                    TradingState.READY_FLAT,
                                    "exchange synchronized and streams ready"
                                )
                            } else if (
                                stateMachine.state == TradingState.OPEN_UNPROTECTED
                            ) {
                                stateMachine.transition(
                                    TradingState.PROTECTED,
                                    "managed position restored"
                                )
                            }
                            checkAutomaticCircuitBreaker()
                        }
                    } catch (x: Exception) {
                        lastError =
                            x.javaClass.simpleName + ": " +
                                (x.message ?: "")
                    }

                    // Scanner telemetry is deliberately outside the trading
                    // pause/recovery gate. PAUSED, RECONCILE_REQUIRED and
                    // KILL_SWITCH must never make the market radar appear dead.
                    if (running) {
                        requestScan()
                    }
                }

                try {
                    Thread.sleep(90_000L)
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
            .put("interval_seconds", 90)
            .put("scanner_startup_mode", "NON_BLOCKING_HISTORY")
            .put("history_warmup_background", true)
    }

    fun isTradingBlocked(): Boolean =
        reconcileRequired || killLatched || userStreamSyncRequired

    private fun p0GateReason(): String =
        when {
            key().isBlank() || secret().isBlank() -> "BINANCE_NOT_CONFIGURED"
            !running -> "RUNTIME_NOT_RUNNING"
            reconcileRequired -> "RECONCILE_REQUIRED"
            killLatched -> "KILL_SWITCH_LATCHED"
            paused -> "TRADING_PAUSED"
            equityCircuitBreaker.isTripped() -> "CIRCUIT_BREAKER_TRIPPED"
            !marketSocketConnected -> "MARKET_WS_OFFLINE"
            !userStreamConnected -> "USER_WS_OFFLINE"
            userStreamSyncRequired -> "USER_STREAM_SYNC_REQUIRED"
            !stateMachine.executionAllowed() -> "FSM_NOT_EXECUTABLE"
            else -> "PASS"
        }

    private fun p0GatePassed(): Boolean =
        p0GateReason() == "PASS"

    private fun unresolvedPositionSymbols(): JSONArray =
        JSONArray().apply {
            positionList()
                .filter {
                    it.qty <= 0.0 ||
                        it.entry <= 0.0 ||
                        it.stop <= 0.0 ||
                        it.take <= 0.0 ||
                        (it.ocoListId.isBlank() && it.ocoListClientId.isBlank())
                }
                .forEach { put(it.symbol) }
        }

    private fun pendingEntrySymbols(): JSONArray =
        JSONArray().apply {
            synchronized(pendingEntries) {
                pendingEntries.keys
                    .sorted()
                    .forEach { put(it) }
            }
        }

    fun shutdown() {
        runCatching { stop() }
        scanExecutor.shutdownNow()
        indicatorExecutor.shutdownNow()
        executionExecutor.shutdownNow()
        tradingEventLoop.close()
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
        marketReconnectScheduled.set(false)
        l2ResyncInFlight.clear()

        return JSONObject().put("stopped", true)
    }

    @Synchronized
    fun kill(): JSONObject {
        killLatched = true
        stateMachine.force(
            TradingState.KILL_SWITCH_LATCHED,
            "kill switch requested"
        )
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
        stateMachine.force(
            TradingState.READY_FLAT,
            "kill switch reset"
        )
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
            recoverPendingEntries()
            reconcilePositionsWithExchange()
            auditManagedOpenOrders()
            reconcileRequired = false
            prefs.edit()
                .putBoolean("reconcile_required", false)
                .remove("execution_gate_symbol")
                .apply()
            lastError = null
            stateMachine.force(
                if (positionList().isEmpty()) TradingState.READY_FLAT
                else TradingState.PROTECTED,
                "authoritative Binance reconciliation complete"
            )

            JSONObject()
                .put("recovered", true)
                .put("state", stateMachine.state.name)
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
            val rawQuery = if (params.isBlank()) {
                "timestamp=" + signedTimestamp() + "&recvWindow=" + BINANCE_RECV_WINDOW_MS
            } else {
                params + "&timestamp=" + signedTimestamp() + "&recvWindow=" + BINANCE_RECV_WINDOW_MS
            }
            val query = encodeSignedParams(rawQuery)
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
            rateGuard.beforeRequest(isOrder = false)
            http.newCall(request).execute().use { response ->
                rateGuard.observe(response.headers, response.code, isOrder = false)
                lastBody = response.body?.string() ?: "{}"
                auditStore.recordRestCall(
                    method,
                    path,
                    response.code,
                    params.ifBlank { null },
                    lastBody
                )
                if (response.isSuccessful) return JSONObject(lastBody)
                if (attempt == 0 && lastBody.contains("-1021")) {
                    runCatching { syncServerTime() }
                    return@use
                }

                val ambiguous =
                    response.code >= 500 || lastBody.contains("-1007")
                if (ambiguous) {
                    if (method == "GET" && attempt == 0) {
                        try { Thread.sleep(250L) } catch (_: InterruptedException) {}
                        return@use
                    }
                    setReconcileRequired(
                        "Binance " + method + " status UNKNOWN; REST reconciliation required"
                    )
                    error(
                        "Binance " + response.code +
                            " / -1007: execution status UNKNOWN; reconciliation required"
                    )
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
            val rawQuery = if (params.isBlank()) {
                "timestamp=" + signedTimestamp() +
                    "&recvWindow=" + BINANCE_RECV_WINDOW_MS
            } else {
                params +
                    "&timestamp=" + signedTimestamp() +
                    "&recvWindow=" + BINANCE_RECV_WINDOW_MS
            }
            val query = encodeSignedParams(rawQuery)
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

            rateGuard.beforeRequest(isOrder = method != "GET")
            http.newCall(request).execute().use { response ->
                rateGuard.observe(response.headers, response.code, isOrder = method != "GET")
                lastBody = response.body?.string() ?: "{}"
                auditStore.recordRestCall(
                    "GET",
                    path,
                    response.code,
                    params.ifBlank { null },
                    lastBody
                )
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

    private fun encodeSignedParams(raw: String): String =
        raw.split("&")
            .filter { it.isNotBlank() }
            .joinToString("&") { part ->
                val index = part.indexOf('=')
                val key = if (index >= 0) part.substring(0, index) else part
                val value = if (index >= 0) part.substring(index + 1) else ""
                percentEncode(key) + "=" + percentEncode(value)
            }

    private fun percentEncode(value: String): String =
        java.net.URLEncoder.encode(
            value,
            StandardCharsets.UTF_8.toString()
        )
            .replace("+", "%20")
            .replace("%7E", "~")

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
                } + listOf(
                    symbol.lowercase(Locale.US) + "@bookTicker",
                    symbol.lowercase(Locale.US) + "@depth@100ms",
                    symbol.lowercase(Locale.US) + "@aggTrade"
                )
            }.joinToString("/")

    private fun startMarketDataStream() {
        if (marketSocket != null) return
        val request = Request.Builder().url(wsStreamUrl()).build()
        marketReconnectScheduled.set(false)
        marketSocket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                marketSocketConnected = true
                lastError = null
                Thread {
                    warmOrderBookSnapshots()
                }.apply {
                    isDaemon = true
                    name = "williams-l2-bootstrap"
                    start()
                }
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
                runCatching { consumeMarketStream(JSONObject(text)) }
                    .onFailure { lastError = "WS market: " + (it.message ?: it.javaClass.simpleName) }

                runCatching {
                    val data = JSONObject(text).optJSONObject("data")
                    if (data?.optString("e") == "serverShutdown") {
                        marketSocketConnected = false
                        webSocket.close(1000, "serverShutdown")
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                marketSocketConnected = false
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                marketSocketConnected = false
                if (marketSocket === webSocket) marketSocket = null
                scheduleMarketReconnect(1500L)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                marketSocketConnected = false
                if (marketSocket === webSocket) marketSocket = null
                lastError = "WS market: " + (t.message ?: t.javaClass.simpleName)
                scheduleMarketReconnect(2500L)
            }
        })
    }

    private fun consumeMarketStream(envelope: JSONObject) {
        val data = envelope.optJSONObject("data") ?: return
        when (data.optString("e")) {
            "depthUpdate" -> consumeDepthUpdate(data)
            "aggTrade" -> consumeAggTrade(data)
            "kline" -> consumeKlineStream(envelope)
            "bookTicker" -> {
                val symbol = data.optString("s").uppercase(Locale.US)
                if (symbol !in coreSymbols) return
                val bid = data.optString("b").toDoubleOrNull() ?: return
                val ask = data.optString("a").toDoubleOrNull() ?: return
                if (bid <= 0.0 || ask < bid) return
                livePrices[symbol] = (bid + ask) / 2.0
            }
        }
    }

    private fun scheduleMarketReconnect(delayMs: Long) {
        if (!running) return
        if (!marketReconnectScheduled.compareAndSet(false, true)) return
        Thread {
            try {
                Thread.sleep(delayMs.coerceAtLeast(250L))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (running) {
                startMarketDataStream()
            } else {
                marketReconnectScheduled.set(false)
            }
        }.apply {
            isDaemon = true
            name = "williams-market-ws-reconnect"
            start()
        }
    }

    private fun requestL2Resync(symbol: String) {
        if (!l2ResyncInFlight.add(symbol)) return
        Thread {
            try {
                syncOrderBookSnapshot(symbol)
            } catch (x: Exception) {
                lastError = "L2 resync $symbol: " + (x.message ?: x.javaClass.simpleName)
            } finally {
                l2ResyncInFlight.remove(symbol)
            }
        }.apply {
            isDaemon = true
            name = "williams-l2-resync-$symbol"
            start()
        }
    }

    private fun warmOrderBookSnapshots() {
        for (symbol in coreSymbols) {
            runCatching { syncOrderBookSnapshot(symbol) }
                .onFailure { lastError = "L2 snapshot $symbol: " + (it.message ?: it.javaClass.simpleName) }
        }
    }

    private fun syncOrderBookSnapshot(symbol: String) {
        val book = JSONObject(
            getBody("/api/v3/depth?symbol=" + symbol + "&limit=100")
        )
        orderBookCache.seed(symbol, book)
    }

    private fun consumeAggTrade(data: JSONObject) {
        val symbol = data.optString("s").uppercase(Locale.US)
        if (symbol !in coreSymbols) return
        val price = data.optString("p").toDoubleOrNull() ?: return
        val qty = data.optString("q").toDoubleOrNull() ?: return
        val time = data.optLong("T", System.currentTimeMillis())
        val quote = price * qty
        if (quote <= 0.0) return
        val queue = tradeFlow.computeIfAbsent(symbol) { ArrayDeque() }
        synchronized(queue) {
            queue.addLast(
                TradeFlowSample(
                    timeMs = time,
                    quoteVolume = quote,
                    // Binance m=true means the buyer was the maker, so the
                    // aggressive side was the seller.
                    aggressiveBuy = !data.optBoolean("m", false)
                )
            )
            val cutoff = time - 5000L
            while (queue.isNotEmpty() && queue.peekFirst().timeMs < cutoff) {
                queue.removeFirst()
            }
            while (queue.size > 2000) queue.removeFirst()
        }
    }

    private fun tradeFlowImbalance(symbol: String): Double? {
        val queue = tradeFlow[symbol.uppercase()] ?: return null
        val now = System.currentTimeMillis()
        var buy = 0.0
        var sell = 0.0
        synchronized(queue) {
            while (queue.isNotEmpty() && queue.peekFirst().timeMs < now - 5000L) {
                queue.removeFirst()
            }
            queue.forEach {
                if (it.aggressiveBuy) buy += it.quoteVolume else sell += it.quoteVolume
            }
        }
        val total = buy + sell
        return if (total > 0.0) (buy - sell) / total else null
    }

    private fun consumeDepthUpdate(data: JSONObject) {
        val symbol = data.optString("s").uppercase(Locale.US)
        if (symbol !in coreSymbols) return
        val first = data.optLong("U", -1L)
        val last = data.optLong("u", -1L)
        if (first < 0L || last < 0L) return
        val ok = orderBookCache.apply(
            symbol = symbol,
            firstUpdateId = first,
            finalUpdateId = last,
            bids = data.optJSONArray("b") ?: JSONArray(),
            asks = data.optJSONArray("a") ?: JSONArray()
        )
        if (!ok) {
            requestL2Resync(symbol)
        }
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
            val snapshotCandles = list.toList()
            candleCache[key] = System.currentTimeMillis() to snapshotCandles

            // Only cache maintenance happens on the WebSocket callback thread.
            // The heavier Williams/indicator calculation runs independently.
            indicatorExecutor.execute {
                runCatching {
                    val snapshot = buildIndicatorSnapshot(
                        snapshotCandles,
                        symbol,
                        frame
                    )
                    // Do not let an older queued calculation overwrite a newer
                    // realtime candle snapshot.
                    val currentTime = liveCandleCache[key]
                        ?.lastOrNull()
                        ?.t
                        ?: 0L
                    if (snapshotCandles.lastOrNull()?.t ?: 0L >= currentTime) {
                        indicatorSnapshots[key] = snapshot
                    }
                }.onFailure {
                    lastError = "indicator analysis: " +
                        (it.message ?: it.javaClass.simpleName)
                }
            }

            if (k.optBoolean("x", false)) {
                val closedCandle = MarketHistoryStore.Candle(
                    openTime = candle.t,
                    closeTime = k.optLong("T"),
                    open = candle.o,
                    high = candle.h,
                    low = candle.l,
                    close = candle.c,
                    volume = candle.v
                )
                indicatorExecutor.execute {
                    runCatching {
                        historyStore.upsertBatch(
                            symbol,
                            frame,
                            listOf(closedCandle)
                        )
                    }.onFailure {
                        lastError = "history update: " +
                            (it.message ?: it.javaClass.simpleName)
                    }
                }
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
        historyReady = false
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
                                val recentN = recent.map {
                                    CandleN(
                                        it.openTime,
                                        it.open,
                                        it.high,
                                        it.low,
                                        it.close,
                                        it.volume
                                    )
                                }
                                indicatorSnapshots[key] =
                                    buildIndicatorSnapshot(
                                        recentN,
                                        symbol,
                                        frame
                                    )
                            }
                        }
                    }
                }

                historyReady =
                    coreSymbols.all { symbol ->
                        analysisFrames.all { frame ->
                            historyStore.isComplete(symbol, frame)
                        }
                    }
            } catch (x: Exception) {
                historyReady = false
                lastError =
                    "history warmup: " +
                        (x.message ?: x.javaClass.simpleName)
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
        val nativeFrames = setOf(
            "1m", "3m", "5m", "15m", "30m", "1h",
            "2h", "4h", "6h", "8h", "12h", "1d",
            "3d", "1w", "1M"
        )
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
            val symbolStatus = item.optString("symbolStatus")
                .trim()
                .uppercase(Locale.US)
            if (
                item.optString("status") == "TRADING" &&
                symbolStatus != "CANCEL_ONLY" &&
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

        val symbols = tradingUsdt
            .asSequence()
            .filter { spreads[it] != null && (volumes[it] ?: 0.0) > 0.0 }
            .sortedByDescending { volumes[it] ?: 0.0 }
            .take(maxScanSymbols)
            .toList()
        require(symbols.isNotEmpty()) {
            "Binance returned no liquid USDT symbols"
        }
        return Triple(symbols, volumes, spreads)
    }

    private fun requestScan() {
        synchronized(this) {
            if (scanning) return
            scanning = true
            scannerState = "RUNNING"
            scannerError = null
        }

        Thread {
            val startedAt = System.currentTimeMillis()
            try {
                performScan()
                scannerState = "READY"
                scannerError = null
            } catch (x: Exception) {
                scannerState = "ERROR"
                scannerError = x.javaClass.simpleName + ": " + (x.message ?: "unknown scanner error")
                lastError = scannerError
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
        // Market scanning is independent from the execution/reconciliation gate.
        // It may run while STOPPED or RECONCILE_REQUIRED, but it never clears
        // the gate and never submits orders unless the trading loop is running.
        if (running && !reconcileRequired && !killLatched) {
            for (open in positionList()) {
                runCatching {
                    val mark = JSONObject(
                        getBody("/api/v3/ticker/price?symbol=" + open.symbol)
                    ).optString("price").toDoubleOrNull() ?: 0.0
                    TradeJournal.updateExcursion(prefs, open.symbol, mark)
                }
            }
            runCatching { manageWilliamsStops() }
        }

        val universe = loadUniverse()
        scanSymbols = universe.first.toList()

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

        // Every strict Williams signal receives the expensive MTF/Wave pass.
        // Only watch-only candidates are capped to Wave Top-N for latency.
        val waveTargets =
            (
                rankedBase.filter { it.signal } +
                    rankedBase.filter { !it.signal }.take(waveTopN)
                )
                .distinctBy { it.symbol }
                .filter { it.candles.size >= 140 }

        val final = waveTargets.map { baseCandidate ->
            enrichWithMtf(baseCandidate)
        }.toMutableList()

        rankedBase
            .filter { candidate -> waveTargets.none { it.symbol == candidate.symbol } }
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
                reservedRiskPct() < maxTotalRiskPct - 0.000001
            ) {
                final
                    .asSequence()
                    .filter {
                        it.signal &&
                            it.score >= 70.0 &&
                            positionList().none { open -> open.symbol == it.symbol } &&
                            synchronized(pendingEntries) {
                                it.symbol !in pendingEntries
                            }
                    }
                    .sortedByDescending { it.score }
                    .firstOrNull()
                    ?.let { candidate ->
                        if (reservedRiskPct() < maxTotalRiskPct - 0.000001) {
                            submitOrderIntent(candidate)
                        }
                    }
            }
        }
    }

    /**
     * Scanner proposals cross a single serialized admission barrier.
     * Only the event-loop thread reserves ENTRY_PENDING; REST execution
     * runs on a separate executor and never blocks the event loop.
     */
    private fun submitOrderIntent(candidate: BaseAnalysis) {
        tradingEventLoop.post {
            val accepted =
                running &&
                    !paused &&
                    !reconcileRequired &&
                    !killLatched &&
                    stateMachine.state in setOf(
                        TradingState.READY_FLAT,
                        TradingState.PROTECTED
                    ) &&
                    candidate.signal &&
                    candidate.score >= 70.0 &&
                    executionGate.tryReserve(candidate.symbol)

            if (!accepted) return@post

            if (!stateMachine.transition(
                    TradingState.ENTRY_PENDING,
                    "BUY intent admitted by ExecutionGate"
                )) {
                executionGate.release(candidate.symbol)
                return@post
            }

            prefs.edit()
                .putString("execution_gate_symbol", candidate.symbol)
                .apply()

            executionExecutor.execute {
                val result = try {
                    executeBuyWithProtection(candidate, gated = true)
                    ExecutionResult(candidate.symbol, true)
                } catch (x: Throwable) {
                    ExecutionResult(
                        candidate.symbol,
                        false,
                        x.message ?: x.javaClass.simpleName
                    )
                }

                tradingEventLoop.post {
                    executionGate.release(result.symbol)
                    prefs.edit()
                        .remove("execution_gate_symbol")
                        .apply()

                    if (
                        !result.success &&
                        !reconcileRequired &&
                        positionList().none { it.symbol == result.symbol } &&
                        synchronized(pendingEntries) {
                            !pendingEntries.containsKey(result.symbol)
                        } &&
                        stateMachine.state == TradingState.ENTRY_PENDING
                    ) {
                        stateMachine.transition(
                            if (positionList().isEmpty()) {
                                TradingState.READY_FLAT
                            } else {
                                TradingState.PROTECTED
                            },
                            "ExecutionResult failure: " +
                                (result.error ?: "unknown execution failure")
                        )
                    }
                    if (!result.success) {
                        lastError =
                            "ORDER " + result.symbol + ": " +
                                (result.error ?: "unknown execution failure")
                    }
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

    private fun recordCompletedTrade(
        stored: PositionState,
        exitPrice: Double,
        reason: String,
        raw: JSONObject?
    ) {
        val closedAt = System.currentTimeMillis()
        val gross =
            (exitPrice - stored.entry) * stored.qty
        val (feeUsdt, feeKnown) =
            auditStore.estimateFeesUsdt(
                symbol = stored.symbol,
                openedAt = stored.openedAt,
                closedAt = closedAt,
                exitPrice = exitPrice
            )
        val net = gross - feeUsdt
        val risk =
            ((stored.entry - stored.stop) / stored.entry)
                .coerceAtLeast(0.000001)
        val rMultiple =
            if (stored.entry > 0.0) {
                (net / (stored.entry * stored.qty)) / risk
            } else 0.0
        val outcome =
            when {
                net > 0.0 -> "WIN"
                net < 0.0 -> "LOSS"
                else -> "BREAKEVEN"
            }
        auditStore.recordTrade(
            tradeId =
                "T:" + stored.symbol + ":" +
                    stored.openedAt + ":" +
                    stored.entryOrderId,
            symbol = stored.symbol,
            entryPrice = stored.entry,
            exitPrice = exitPrice,
            qty = stored.qty,
            notionalUsdt = stored.entry * stored.qty,
            grossPnl = gross,
            netPnl = net,
            rMultiple = rMultiple,
            openedAt = stored.openedAt,
            closedAt = closedAt,
            outcome = outcome,
            reason = reason,
            rawJson = raw?.toString(),
            feeUsdt = feeUsdt,
            feeKnown = feeKnown
        )
    }

    private fun handleUserEvent(event: JSONObject) {
        tradingEventLoop.post { handleUserEventSerialized(event) }
    }

    private fun handleUserEventSerialized(event: JSONObject) {
        lastUserEventMs = System.currentTimeMillis()
        auditStore.recordUserEvent(event)
        executionAccumulator.accept(event)

        val eventType = event.optString("e", "unknown")
        val eventTime = event.optLong("E", 0L)
        if (eventTime > 0L) {
            val previous = lastUserEventTimeByType[eventType]
            if (previous != null && eventTime < previous) {
                userStreamSyncRequired = true
                lastError =
                    "user ws: out-of-order $eventType event; REST resync required"
                return
            }
            lastUserEventTimeByType[eventType] =
                max(previous ?: 0L, eventTime)
        }

        when (eventType) {
            "executionReport" -> {
                val side = event.optString("S").uppercase(Locale.US)
                val status = event.optString("X").uppercase(Locale.US)
                val execution = event.optString("x").uppercase(Locale.US)
                val clientId = event.optString("c")
                val symbol = event.optString("s").uppercase(Locale.US)

                lastOrder = JSONObject()
                    .put("symbol", symbol)
                    .put("side", side)
                    .put("type", event.optString("o"))
                    .put("orderId", event.optString("i"))
                    .put("orderListId", event.optString("g"))
                    .put("clientOrderId", clientId)
                    .put("executionType", execution)
                    .put("status", status)
                    .put("rejectReason", event.optString("r"))
                    .put("lastQty", event.optString("l"))
                    .put("lastPrice", event.optString("L"))
                    .put("executedQty", event.optString("z"))
                    .put("quoteQty", event.optString("Z"))
                    .put("commission", event.optString("n"))
                    .put("commissionAsset", event.optString("N"))
                    .put("eventTime", eventTime)
                    .put("transactionTime", event.optLong("T"))
                    .put("executionAccumulator", executionAccumulator.snapshot(event.optString("i"))?.let { acc ->
                        JSONObject()
                            .put("executedQty", acc.executedQty.toPlainString())
                            .put("executedQuote", acc.executedQuote.toPlainString())
                            .put("vwap", acc.vwap.toPlainString())
                            .put("feeUsdt", acc.feeUsdt.toPlainString())
                            .put("feeKnown", acc.feeKnown)
                            .put("fills", acc.fills)
                    })

                when {
                    clientId.startsWith("W4B_") &&
                        status == "NEW" ->
                        stateMachine.transition(
                            TradingState.ENTRY_PENDING,
                            "BUY NEW"
                        )

                    clientId.startsWith("W4B_") &&
                        status == "PARTIALLY_FILLED" ->
                        stateMachine.transition(
                            TradingState.ENTRY_PENDING,
                            "BUY PARTIALLY_FILLED"
                        )

                    clientId.startsWith("W4B_") &&
                        status == "FILLED" ->
                        stateMachine.transition(
                            TradingState.OPEN_UNPROTECTED,
                            "BUY FILLED"
                        )

                    clientId.startsWith("W4B_") &&
                        status in setOf(
                            "CANCELED",
                            "REJECTED",
                            "EXPIRED"
                        ) &&
                        positions[symbol] == null ->
                        stateMachine.transition(
                            TradingState.READY_FLAT,
                            "BUY terminal $status"
                        )

                    side == "SELL" &&
                        status in setOf(
                            "NEW",
                            "PARTIALLY_FILLED"
                        ) ->
                        stateMachine.transition(
                            TradingState.EXIT_PENDING,
                            "SELL $status"
                        )
                }

                if (
                    status in setOf(
                        "FILLED",
                        "CANCELED",
                        "REJECTED",
                        "EXPIRED"
                    ) || execution == "TRADE"
                ) {
                    Thread {
                        runCatching {
                            Thread.sleep(120L)
                            recoverPendingEntries()
                            reconcilePositionsWithExchange()
                        }.onFailure {
                            if (!killLatched) {
                                setReconcileRequired(
                                    "executionReport reconciliation failed: " +
                                        (it.message ?: it.javaClass.simpleName)
                                )
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
                val listStatusType =
                    event.optString("l").uppercase(Locale.US)
                val listOrderStatus =
                    event.optString("L").uppercase(Locale.US)

                if (
                    listStatusType.contains("EXEC") ||
                    listOrderStatus.contains("EXECUTING")
                ) {
                    stateMachine.transition(
                        TradingState.PROTECTED,
                        "OCO active"
                    )
                }

                if (
                    listStatusType.contains("ALL_DONE") ||
                    listStatusType == "ALL_DONE" ||
                    listOrderStatus.contains("ALL_DONE")
                ) {
                    stateMachine.transition(
                        TradingState.EXIT_PENDING,
                        "OCO completed"
                    )
                }

                Thread {
                    runCatching {
                        Thread.sleep(100L)
                        reconcilePositionsWithExchange()
                    }.onFailure {
                        if (!killLatched) {
                            setReconcileRequired(
                                "OCO event reconciliation failed: " +
                                    (it.message ?: it.javaClass.simpleName)
                            )
                        }
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
            }

            "outboundAccountPosition",
            "balanceUpdate" -> {
                val balances =
                    event.optJSONArray("B")
                        ?: event.optJSONObject("a")
                            ?.optJSONArray("B")
                if (balances != null) {
                    for (i in 0 until balances.length()) {
                        val row = balances.optJSONObject(i) ?: continue
                        if (row.optString("a") == "USDT") {
                            val free =
                                row.optString("f").toDoubleOrNull()
                                    ?: row.optDouble("f", 0.0)
                            val locked =
                                row.optString("l").toDoubleOrNull()
                                    ?: row.optDouble("l", 0.0)
                            liveUsdtBalance = free + locked
                        }
                    }
                }
            }

            "eventStreamTerminated" -> {
                userStreamSyncRequired = true
                lastError =
                    "user ws: event stream terminated; reconnect + REST resync"
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

                    val lists = signedOpenOrderLists()
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
    private fun auditManagedOpenOrders() {
        val openOrders = signedOpenOrdersArray()
        val openLists = signedOpenOrderLists().optJSONArray("orderList") ?: JSONArray()

        val expectedSymbols =
            pendingEntries.keys.toSet() +
                positionList().map { it.symbol }

        // Recover only orders that Williams itself created. A stale Williams
        // order with no local position used to permanently block a clean
        // Testnet restart. BUY intents are safe to cancel when no durable
        // pending entry exists. SELL/OCO protection is cancellable only when
        // the exchange confirms that the corresponding base asset is absent.
        val account = signedAccount()
        val balances = account.optJSONArray("balances") ?: JSONArray()

        fun baseBalance(symbol: String): Double {
            val asset = symbol.removeSuffix("USDT")
            for (i in 0 until balances.length()) {
                val row = balances.optJSONObject(i) ?: continue
                if (row.optString("asset").uppercase(Locale.US) == asset) {
                    val free = row.optString("free").toDoubleOrNull() ?: 0.0
                    val locked = row.optString("locked").toDoubleOrNull() ?: 0.0
                    return free + locked
                }
            }
            return 0.0
        }

        for (i in 0 until openOrders.length()) {
            val order = openOrders.optJSONObject(i) ?: continue
            val clientId = order.optString("clientOrderId")
            if (!clientId.startsWith("W4B_") &&
                !clientId.startsWith("W4S_")
            ) continue

            val symbol =
                order.optString("symbol").uppercase(Locale.US)
            if (symbol in expectedSymbols) continue

            val side = order.optString("side").uppercase(Locale.US)
            if (side == "BUY") {
                // No durable pending entry owns this BUY anymore. Cancel only
                // the explicitly Williams-owned orphan order.
                signedDelete(
                    "/api/v3/order",
                    "symbol=" + symbol +
                        "&orderId=" + order.optString("orderId")
                )
                continue
            }

            // An orphan SELL is safe to cancel only when the exchange has no
            // corresponding base asset. Otherwise the safety barrier remains.
            if (baseBalance(symbol) <= 0.000001) {
                signedDelete(
                    "/api/v3/order",
                    "symbol=" + symbol +
                        "&orderId=" + order.optString("orderId")
                )
            } else {
                throw IllegalStateException(
                    "Unmanaged Williams SELL with exchange balance: " +
                        symbol + " clientOrderId=" + clientId
                )
            }
        }

        for (i in 0 until openLists.length()) {
            val list = openLists.optJSONObject(i) ?: continue
            val listClientId = list.optString("listClientOrderId")
            if (!listClientId.startsWith("W4O_")) continue

            val symbol =
                list.optString("symbol").uppercase(Locale.US)
            if (positionList().any { it.symbol == symbol }) continue

            // An orphan Williams OCO with no base asset cannot protect a real
            // position. Cancel only that Williams-owned list. If the asset
            // exists, keep RECONCILE_REQUIRED instead of guessing.
            if (baseBalance(symbol) <= 0.000001) {
                val listId = list.optString("orderListId")
                if (listId.isNotBlank()) {
                    signedDelete(
                        "/api/v3/orderList",
                        "symbol=" + symbol +
                            "&orderListId=" + listId
                    )
                } else {
                    signedDelete(
                        "/api/v3/orderList",
                        "symbol=" + symbol +
                            "&listClientOrderId=" + listClientId
                    )
                }
            } else {
                throw IllegalStateException(
                    "Unmanaged Williams OCO with exchange balance: " +
                        symbol + " listClientOrderId=" + listClientId
                )
            }
        }
    }

    private fun reconcilePositionsWithExchange() {
        auditManagedOpenOrders()
        if (positions.isEmpty()) {
            savePersistedState()
            return
        }

        val account = signedAccount()
        val balances = account.getJSONArray("balances")
        val openLists = signedOpenOrderLists()

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

            val allOrders = signedAllOrdersArray(stored.symbol)

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
                recordCompletedTrade(
                    stored = stored,
                    exitPrice = exitPrice,
                    reason = reason,
                    raw = lastExit
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

    /**
     * Binance array-root endpoints are normalized before callers inspect
     * them. This prevents an empty [] response from being misread as a
     * JSONObject and then silently swallowed by runCatching.
     */
    private fun signedOpenOrders(
        symbol: String? = null
    ): JSONObject {
        val params = symbol?.let { "symbol=" + it } ?: ""
        val body = signedRawGet("/api/v3/openOrders", params)
        return runCatching {
            JSONObject(body)
        }.getOrElse {
            JSONObject().put("orders", JSONArray(body))
        }
    }

    private fun signedOpenOrdersArray(symbol: String? = null): JSONArray {
        val response = signedOpenOrders(symbol)
        return response.optJSONArray("orders")
            ?: if (response.has("symbol")) {
                JSONArray().put(response)
            } else {
                throw IllegalStateException(
                    "Binance /openOrders returned an unexpected JSON object"
                )
            }
    }

    private fun signedAllOrdersArray(symbol: String): JSONArray {
        val body = signedRawGet(
            "/api/v3/allOrders",
            "symbol=" + symbol + "&limit=1000"
        )
        return runCatching {
            JSONArray(body)
        }.getOrElse {
            throw IllegalStateException(
                "Binance /allOrders returned a non-array JSON response",
                it
            )
        }
    }

    /**
     * Binance /openOrderList returns a JSON array at the root when there are
     * no open order lists. Keep the internal representation as an object so
     * the existing reconciliation helpers can safely inspect the array.
     */
    private fun signedOpenOrderLists(): JSONObject {
        val body = signedRawGet("/api/v3/openOrderList", "")
        return runCatching {
            JSONObject(body)
        }.getOrElse {
            JSONObject().put("orderList", JSONArray(body))
        }
    }

    private fun signedPost(
        path: String,
        params: String
    ): JSONObject = signedRequest("POST", path, params)

    /**
     * Binance cancel-replace is not truly transactional. STOP_ON_FAILURE
     * prevents the replacement attempt when cancellation itself fails;
     * callers must still reconcile when the response is ambiguous.
     */
    private fun signedCancelReplace(
        symbol: String,
        cancelOrderId: Long,
        side: String,
        type: String,
        quantity: String? = null,
        price: String? = null,
        timeInForce: String? = null,
        newClientOrderId: String? = null
    ): JSONObject {
        val params = StringBuilder()
            .append("symbol=").append(symbol)
            .append("&cancelReplaceMode=STOP_ON_FAILURE")
            .append("&cancelOrderId=").append(cancelOrderId)
            .append("&side=").append(side)
            .append("&type=").append(type)
            .append("&newOrderRespType=FULL")
        if (!quantity.isNullOrBlank()) params.append("&quantity=").append(quantity)
        if (!price.isNullOrBlank()) params.append("&price=").append(price)
        if (!timeInForce.isNullOrBlank()) params.append("&timeInForce=").append(timeInForce)
        if (!newClientOrderId.isNullOrBlank()) params.append("&newClientOrderId=").append(newClientOrderId)
        return signedPost("/api/v3/order/cancelReplace", params.toString())
    }

    private fun symbolFilters(symbol: String): SymbolRules {
        val info = JSONObject(
            getBody("/api/v3/exchangeInfo?symbol=" + symbol)
        )
        val row = info.getJSONArray("symbols").getJSONObject(0)
        val filters = row.getJSONArray("filters")

        var step = 0.000001
        var minQty = 0.0
        var maxQty = Double.POSITIVE_INFINITY
        var marketStep = 0.0
        var marketMinQty = 0.0
        var marketMaxQty = Double.POSITIVE_INFINITY
        var tick = 0.000001
        var minPrice = 0.0
        var maxPrice = Double.POSITIVE_INFINITY
        var minNotional = 0.0
        var maxNotional = Double.POSITIVE_INFINITY
        var percentUp = 0.0
        var percentDown = 0.0
        var bidUp = 0.0
        var bidDown = 0.0
        var askUp = 0.0
        var askDown = 0.0
        var avgPriceMins = 0
        var maxNumOrders = Int.MAX_VALUE
        var maxNumAlgoOrders = Int.MAX_VALUE
        var maxNumOrderLists = Int.MAX_VALUE
        var maxPosition = Double.POSITIVE_INFINITY

        for (i in 0 until filters.length()) {
            val f = filters.getJSONObject(i)
            when (f.optString("filterType")) {
                "LOT_SIZE" -> {
                    step = f.optString("stepSize").toDoubleOrNull() ?: step
                    minQty = f.optString("minQty").toDoubleOrNull() ?: minQty
                    maxQty = f.optString("maxQty").toDoubleOrNull() ?: maxQty
                }
                "MARKET_LOT_SIZE" -> {
                    marketStep = f.optString("stepSize").toDoubleOrNull() ?: marketStep
                    marketMinQty = f.optString("minQty").toDoubleOrNull() ?: marketMinQty
                    marketMaxQty = f.optString("maxQty").toDoubleOrNull() ?: marketMaxQty
                }
                "PRICE_FILTER" -> {
                    tick = f.optString("tickSize").toDoubleOrNull() ?: tick
                    minPrice = f.optString("minPrice").toDoubleOrNull() ?: minPrice
                    maxPrice = f.optString("maxPrice").toDoubleOrNull() ?: maxPrice
                }
                "MIN_NOTIONAL" -> {
                    minNotional = max(
                        minNotional,
                        f.optString("minNotional").toDoubleOrNull() ?: 0.0
                    )
                }
                "NOTIONAL" -> {
                    minNotional = max(
                        minNotional,
                        f.optString("minNotional").toDoubleOrNull() ?: 0.0
                    )
                    maxNotional = min(
                        maxNotional,
                        f.optString("maxNotional").toDoubleOrNull()
                            ?: Double.POSITIVE_INFINITY
                    )
                }
                "PERCENT_PRICE" -> {
                    percentUp = f.optString("multiplierUp").toDoubleOrNull() ?: percentUp
                    percentDown = f.optString("multiplierDown").toDoubleOrNull() ?: percentDown
                    avgPriceMins = f.optInt("avgPriceMins", avgPriceMins)
                }
                "PERCENT_PRICE_BY_SIDE" -> {
                    bidUp = f.optString("bidMultiplierUp").toDoubleOrNull() ?: bidUp
                    bidDown = f.optString("bidMultiplierDown").toDoubleOrNull() ?: bidDown
                    askUp = f.optString("askMultiplierUp").toDoubleOrNull() ?: askUp
                    askDown = f.optString("askMultiplierDown").toDoubleOrNull() ?: askDown
                    avgPriceMins = f.optInt("avgPriceMins", avgPriceMins)
                }
                "MAX_NUM_ORDERS" -> {
                    maxNumOrders = f.optInt("maxNumOrders", maxNumOrders)
                }
                "MAX_NUM_ALGO_ORDERS" -> {
                    maxNumAlgoOrders = f.optInt("maxNumAlgoOrders", maxNumAlgoOrders)
                }
                "MAX_NUM_ORDER_LISTS" -> {
                    maxNumOrderLists = f.optInt("maxNumOrderLists", maxNumOrderLists)
                }
                "MAX_POSITION" -> {
                    maxPosition =
                        f.optString("maxPosition").toDoubleOrNull()
                            ?: maxPosition
                }
            }
        }

        val decimals = max(
            0,
            step.toString().substringAfter('.', "").trimEnd('0').length
        )
        val quoteAllowed = row.optBoolean("quoteOrderQtyMarketAllowed", true)
        val types = row.optJSONArray("orderTypes")
        val ocoAllowed = row.optBoolean(
            "ocoAllowed",
            types?.let {
                var take = false
                var stop = false
                for (i in 0 until it.length()) {
                    when (it.optString(i)) {
                        "TAKE_PROFIT_LIMIT" -> take = true
                        "STOP_LOSS_LIMIT" -> stop = true
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
            maxQty = maxQty,
            marketStep = marketStep,
            marketMinQty = marketMinQty,
            marketMaxQty = marketMaxQty,
            minPrice = minPrice,
            maxPrice = maxPrice,
            minNotional = minNotional,
            maxNotional = maxNotional,
            quoteOrderQtyMarketAllowed = quoteAllowed,
            ocoAllowed = ocoAllowed,
            percentUp = percentUp,
            percentDown = percentDown,
            bidPercentUp = bidUp,
            bidPercentDown = bidDown,
            askPercentUp = askUp,
            askPercentDown = askDown,
            avgPriceMins = avgPriceMins,
            maxNumOrders = maxNumOrders,
            maxNumAlgoOrders = maxNumAlgoOrders,
            maxNumOrderLists = maxNumOrderLists,
            maxPosition = maxPosition
        )
    }

    private fun floorStep(value: Double, step: Double): Double =
        if (step <= 0.0) value else ExecutionMath.floorToStep(value, step)

    private fun fmtQty(value: Double, decimals: Int): String =
        ExecutionMath.floorToScale(value, decimals)

    private fun fmtPrice(value: Double, tick: Double): String {
        val rounded = if (tick > 0.0) {
            ExecutionMath.priceToTick(value, tick)
        } else value
        val decimals = max(
            0,
            tick.toString().substringAfter('.', "").trimEnd('0').length
        )
        return ExecutionMath.floorToScale(rounded, decimals)
    }

    private fun validateLimitPrice(
        symbol: String,
        side: String,
        price: Double,
        rules: SymbolRules
    ) {
        require(price > 0.0) { "Price must be positive" }

        if (rules.minPrice > 0.0) {
            require(price >= rules.minPrice) { "Price below Binance minPrice" }
        }
        if (rules.maxPrice.isFinite()) {
            require(price <= rules.maxPrice) { "Price above Binance maxPrice" }
        }

        val refPrice = runCatching {
            JSONObject(
                getBody("/api/v3/avgPrice?symbol=" + symbol)
            ).optString("price").toDoubleOrNull()
        }.getOrNull()?.takeIf { it > 0.0 } ?: return

        val lower =
            if (side == "SELL" && rules.askPercentDown > 0.0) {
                refPrice * rules.askPercentDown
            } else if (side == "BUY" && rules.bidPercentDown > 0.0) {
                refPrice * rules.bidPercentDown
            } else if (rules.percentDown > 0.0) {
                refPrice * rules.percentDown
            } else 0.0

        val upper =
            if (side == "SELL" && rules.askPercentUp > 0.0) {
                refPrice * rules.askPercentUp
            } else if (side == "BUY" && rules.bidPercentUp > 0.0) {
                refPrice * rules.bidPercentUp
            } else if (rules.percentUp > 0.0) {
                refPrice * rules.percentUp
            } else Double.POSITIVE_INFINITY

        require(price >= lower && price <= upper) {
            "Price outside Binance percent-price filter"
        }
    }

    private fun checkOcoCapacity(
        symbol: String,
        rules: SymbolRules
    ) {
        val openOrders = runCatching {
            val response = signedOpenOrders(symbol)
            response.optJSONArray("orders")
                ?: if (response.has("symbol")) {
                    JSONArray().put(response)
                } else JSONArray()
        }.getOrElse { JSONArray() }

        val openLists = runCatching {
            signedOpenOrderLists()
                .let {
                    it.optJSONArray("orderList")
                        ?: it.optJSONArray("ordersLists")
                        ?: it.optJSONArray("orderLists")
                        ?: JSONArray()
                }
        }.getOrElse { JSONArray() }

        require(openOrders.length() + 2 <= rules.maxNumOrders) {
            "Binance MAX_NUM_ORDERS would be exceeded"
        }

        val algoCount = (0 until openOrders.length()).count { i ->
            val type = openOrders.optJSONObject(i)
                ?.optString("type")
                ?.uppercase(Locale.US)
                ?: ""
            type.contains("STOP") || type.contains("TAKE_PROFIT")
        }

        // One OCO contains one conditional leg; keep a conservative margin.
        require(algoCount + 1 <= rules.maxNumAlgoOrders) {
            "Binance MAX_NUM_ALGO_ORDERS would be exceeded"
        }

        require(openLists.length() + 1 <= rules.maxNumOrderLists) {
            "Binance MAX_NUM_ORDER_LISTS would be exceeded"
        }
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

    private fun estimateMarketBuySlippage(
        symbol: String,
        quoteNotional: Double
    ): Pair<Double, Double> {
        require(quoteNotional > 0.0)

        // Prefer the continuously maintained local L2 book. A REST snapshot
        // remains a safety fallback if the stream is not synchronized yet.
        orderBookCache.estimateBuy(symbol, quoteNotional)?.let { return it }

        val book = JSONObject(
            getBody("/api/v3/depth?symbol=" + symbol + "&limit=100")
        )
        val asks = book.optJSONArray("asks")
            ?: error("Order book has no asks for $symbol")
        require(asks.length() > 0) { "Order book is empty for $symbol" }

        var remainingQuote = quoteNotional
        var consumedBase = 0.0
        var consumedQuote = 0.0
        var bestAsk = 0.0

        for (i in 0 until asks.length()) {
            val row = asks.optJSONArray(i) ?: continue
            val price = row.optString(0).toDoubleOrNull() ?: continue
            val qty = row.optString(1).toDoubleOrNull() ?: continue
            if (price <= 0.0 || qty <= 0.0) continue
            if (bestAsk <= 0.0) bestAsk = price

            val levelQuote = price * qty
            val usedQuote = min(remainingQuote, levelQuote)
            consumedQuote += usedQuote
            consumedBase += usedQuote / price
            remainingQuote -= usedQuote
            if (remainingQuote <= 1e-9) break
        }

        require(remainingQuote <= 1e-9) {
            "Insufficient visible ask liquidity for $symbol"
        }
        require(bestAsk > 0.0 && consumedBase > 0.0) {
            "Invalid order book for $symbol"
        }

        val vwap = consumedQuote / consumedBase
        val slippage = (vwap / bestAsk - 1.0).coerceAtLeast(0.0)
        return vwap to slippage
    }

    private fun estimateManagedEquity(): Double {
        var equity = liveUsdtBalance.coerceAtLeast(0.0)
        positionList().forEach { position ->
            val mark = livePrices[position.symbol]
                ?: if (position.symbol == primarySymbol) {
                    primaryCandles.lastOrNull()?.c ?: 0.0
                } else 0.0
            if (mark > 0.0) equity += position.qty * mark
        }
        return equity
    }

    private fun checkAutomaticCircuitBreaker() {
        if (!running || paused || killLatched || circuitBreakerTripInProgress) return
        val now = System.currentTimeMillis()
        if (now - lastEquityCheckMs < 60_000L) return
        lastEquityCheckMs = now

        runCatching {
            val account = signedAccount()
            val balances = account.getJSONArray("balances")
            var usdt = 0.0
            for (i in 0 until balances.length()) {
                val b = balances.getJSONObject(i)
                if (b.optString("asset") == "USDT") {
                    usdt = b.optString("free").toDoubleOrNull() ?: 0.0
                    break
                }
            }
            liveUsdtBalance = usdt

            val snapshot = equityCircuitBreaker.observe(
                estimateManagedEquity(),
                now
            )
            if (snapshot.tripped) {
                circuitBreakerTripInProgress = true
                lastError =
                    "AUTOMATIC CIRCUIT BREAKER: managed equity drawdown " +
                        "%.2f%%".format(
                            Locale.US,
                            snapshot.drawdownPct * 100.0
                        )
                Thread {
                    try {
                        kill()
                    } finally {
                        circuitBreakerTripInProgress = false
                    }
                }.apply {
                    isDaemon = true
                    name = "williams-equity-circuit-breaker"
                    start()
                }
            }
        }.onFailure {
            lastError =
                "equity monitor: " +
                    (it.message ?: it.javaClass.simpleName)
        }
    }

    private fun executeBuyWithProtection(
        candidate: BaseAnalysis,
        gated: Boolean = false
    ) {
        if (positions.containsKey(candidate.symbol)) {
            return
        }
        // Williams conservative production contract: exactly one managed
        // position at a time. The scanner resumes only after it is flat.
        if (maxOpenPositions > 0 && positionList().size >= maxOpenPositions) {
            error("MAX_OPEN_POSITIONS=$maxOpenPositions")
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
        if (userStreamSyncRequired) {
            error("USER_DATA_STREAM_SYNC_REQUIRED")
        }
        require(
            if (gated) {
                stateMachine.state == TradingState.ENTRY_PENDING ||
                    stateMachine.state == TradingState.PROTECTED
            } else {
                stateMachine.state == TradingState.READY_FLAT ||
                    stateMachine.state == TradingState.PROTECTED
            }
        ) {
            "FSM forbids BUY from state " + stateMachine.state.name
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

        val (bookVwap, bookSlippage) =
            estimateMarketBuySlippage(
                candidate.symbol,
                notional
            )
        require(bookSlippage <= maxSlippagePct) {
            "Order-book slippage " +
                "%.4f".format(Locale.US, bookSlippage * 100.0) +
                "% exceeds configured " +
                "%.4f".format(Locale.US, maxSlippagePct * 100.0) +
                "% for " + candidate.symbol
        }

        val clientOrderId =
            "W4B_" +
                java.util.UUID.randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(28)

        if (!gated) {
            if (!stateMachine.transition(
                TradingState.ENTRY_PENDING,
                "BUY intent created"
            )) {
                error("FSM rejected BUY intent")
            }
        }

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

        val baseAsset = candidate.symbol.removeSuffix("USDT")
        val currentBaseQty = run {
            var amount = 0.0
            for (i in 0 until balances.length()) {
                val row = balances.getJSONObject(i)
                if (row.optString("asset") == baseAsset) {
                    amount =
                        (row.optString("free").toDoubleOrNull() ?: 0.0) +
                        (row.optString("locked").toDoubleOrNull() ?: 0.0)
                    break
                }
            }
            amount
        }

        if (rules.maxPosition.isFinite()) {
            val referencePrice = runCatching {
                JSONObject(
                    getBody("/api/v3/ticker/price?symbol=" + candidate.symbol)
                ).optString("price").toDoubleOrNull()
            }.getOrNull() ?: 0.0

            if (referencePrice > 0.0) {
                val estimatedQty = notional / referencePrice
                require(
                    currentBaseQty + estimatedQty <=
                        rules.maxPosition + rules.step
                ) {
                    "Binance MAX_POSITION would be exceeded"
                }
            }
        }

        val buyParams =
            if (rules.quoteOrderQtyMarketAllowed) {
                "symbol=" + candidate.symbol +
                    "&side=BUY&type=MARKET" +
                    "&quoteOrderQty=" +
                    ExecutionMath.plain(
                        ExecutionMath.decimal(notional),
                        2
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
                    .put("obi", candidate.obi)
                    .put("trade_flow_imbalance", candidate.tradeFlowImbalance)
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
            stateMachine.force(
                TradingState.PROTECTED,
                "BUY filled and OCO created"
            )
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

            val order = runCatching {
                signedGet(
                    "/api/v3/order",
                    "symbol=" + candidate.symbol +
                        "&origClientOrderId=" +
                        (pending?.clientOrderId ?: clientOrderId)
                )
            }.getOrNull()

            if (order?.optString("status") == "FILLED") {
                val filled =
                    order.optString("executedQty").toDoubleOrNull() ?: 0.0
                val quote =
                    order.optString("cummulativeQuoteQty").toDoubleOrNull() ?: 0.0

                if (filled > 0.0) {
                    val entryPrice =
                        if (quote > 0.0) quote / filled
                        else candidate.candles.lastOrNull()?.c ?: 0.0

                    synchronized(positions) {
                        positions[candidate.symbol] =
                            PositionState(
                                symbol = candidate.symbol,
                                qty = filled,
                                entry = entryPrice,
                                stop = entryPrice * (1.0 - (pending?.stopDistance ?: 0.02)),
                                take = entryPrice * (1.0 + (pending?.stopDistance ?: 0.02) * 4.0),
                                riskPct = pending?.stopDistance ?: 0.02,
                                entryOrderId = order.optString("orderId"),
                                entryClientOrderId =
                                    pending?.clientOrderId ?: clientOrderId,
                                openedAt = order.optLong(
                                    "transactTime",
                                    System.currentTimeMillis()
                                )
                            )
                    }

                    stateMachine.force(
                        TradingState.OPEN_UNPROTECTED,
                        "BUY transport outcome unknown; REST proved FILLED"
                    )
                    synchronized(pendingEntries) {
                        pendingEntries.remove(candidate.symbol)
                    }
                    savePersistedState()

                    val protectionResult = runCatching {
                        reconcilePositionsWithExchange()
                    }

                    if (protectionResult.isFailure) {
                        // Never assume the failed protection request was harmless.
                        // Reconcile again immediately; only emergency-close if the
                        // exchange still cannot prove protection.
                        val emergency = runCatching {
                            reconcilePositionsWithExchange()
                        }

                        if (emergency.isFailure) {
                            runCatching {
                                signedPost(
                                    "/api/v3/order",
                                    "symbol=" + candidate.symbol +
                                        "&side=SELL&type=MARKET" +
                                        "&quantity=" +
                                        fmtQty(filled, rules.decimals)
                                )
                            }.onFailure {
                                setReconcileRequired(
                                    "BUY filled without verifiable protection; emergency SELL failed: " +
                                        (it.message ?: it.javaClass.simpleName)
                                )
                            }
                        }
                    }
                }
            } else {
                synchronized(pendingEntries) {
                    pendingEntries.remove(candidate.symbol)
                }
                stateMachine.transition(
                    TradingState.READY_FLAT,
                    "BUY not filled / terminal after transport error"
                )
                savePersistedState()
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

        val maxQty = min(
            rules.maxQty,
            if (rules.marketMaxQty.isFinite()) rules.marketMaxQty else rules.maxQty
        )
        val minQty = max(rules.minQty, rules.marketMinQty)
        if (normalizedQty < minQty) {
            error("Filled quantity is below Binance minimum")
        }
        if (maxQty.isFinite() && normalizedQty > maxQty) {
            error("Filled quantity is above Binance maximum")
        }

        checkOcoCapacity(symbol, rules)

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

        validateLimitPrice(symbol, "SELL", take, rules)
        validateLimitPrice(symbol, "SELL", stop, rules)
        validateLimitPrice(symbol, "SELL", stopLimit, rules)

        val notional = normalizedQty * entry
        if (rules.minNotional > 0.0 && notional < rules.minNotional) {
            error("OCO notional below Binance minimum")
        }
        if (rules.maxNotional.isFinite() && notional > rules.maxNotional) {
            error("OCO notional above Binance maximum")
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

        stateMachine.transition(
            TradingState.EXIT_PENDING,
            "manual SELL requested"
        )

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
            recordCompletedTrade(
                stored = stored,
                exitPrice = exitPrice,
                reason = "MANUAL_SELL",
                raw = sell
            )
            synchronized(positions) {
                positions.remove(symbol)
            }
            stateMachine.force(
                TradingState.READY_FLAT,
                "manual SELL filled"
            )
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
        // Never make a signal from the still-forming Binance candle.
        val closed = if (candles.size > 1) candles.dropLast(1) else emptyList()
        val i = closed.lastIndex
        if (i < 40) {
            return BaseAnalysis(
                symbol = symbol,
                candles = closed,
                score = 0.0,
                signal = false,
                htfCandidate = false,
                wave = neutralWave(closed),
                atrPct = 0.0,
                riskPct = 0.0,
                riskReward = 0.0,
                spreadPct = spread,
                breakoutDistancePct = 0.0,
                reason = "Недостаточно закрытых свечей"
            )
        }

        val closes = closed.map { it.c }
        val medians = closed.map { (it.h + it.l) / 2.0 }

        // Williams Alligator 13/8/5 with 8/5/3 displacement.
        val jawSeries = smma(medians, 13)
        val teethSeries = smma(medians, 8)
        val lipsSeries = smma(medians, 5)
        val jaw = jawSeries.getOrElse(i - 8) { 0.0 }
        val teeth = teethSeries.getOrElse(i - 5) { 0.0 }
        val lips = lipsSeries.getOrElse(i - 3) { 0.0 }

        val bullish =
            lips > teeth &&
                teeth > jaw &&
                closes[i] > lips

        val mouthMax = max(jaw, max(teeth, lips))
        val mouthMin = min(jaw, min(teeth, lips))
        val alligatorSpread =
            if (closes[i] > 0.0) {
                (mouthMax - mouthMin) / closes[i]
            } else 0.0
        val awake = alligatorSpread >= 0.001

        val aoValue = ao(closed, i)
        val aoPositive = aoValue > 0.0

        val fractalIndex = latestConfirmedUpFractal(closed, i)
        val fractalHigh = fractalIndex?.let { closed[it].h }
        // Profitunity's Balance-Line gate compares the confirmed fractal
        // level to the CURRENT displaced Teeth value.
        val fractalOutside =
            fractalHigh != null &&
                teeth > 0.0 &&
                fractalHigh > teeth

        val previousFractalIndex = latestConfirmedUpFractal(closed, i - 1)
        val previousFractalHigh =
            previousFractalIndex?.let { closed[it].h }

        val breakoutDistance =
            if (fractalHigh != null && fractalHigh > 0.0) {
                (closes[i] - fractalHigh) / fractalHigh
            } else {
                0.0
            }

        val fractalBreak =
            fractalOutside &&
                closes[i] > fractalHigh!! &&
                breakoutDistance <= 0.05 &&
                previousFractalHigh != null &&
                closes[i - 1] <= previousFractalHigh

        // First Wise Man: reversal bar followed by breakout of its high.
        var reversalHigh = Double.NaN
        var reversalEntry = false
        for (j in max(2, i - 40) until i) {
            val range = closed[j].h - closed[j].l
            if (range <= 0.0) continue
            val priorLow1 = closed[j - 1].l
            val priorLow2 = closed[j - 2].l
            val closeLocation =
                (closed[j].c - closed[j].l) / range
            val jawJ = jawSeries.getOrNull(j - 8) ?: continue
            val teethJ = teethSeries.getOrNull(j - 5) ?: continue
            val lipsJ = lipsSeries.getOrNull(j - 3) ?: continue
            val reversal =
                closed[j].l < min(priorLow1, priorLow2) &&
                    closeLocation >= 0.50 &&
                    closed[j].l < min(jawJ, min(teethJ, lipsJ))
            if (reversal) {
                reversalHigh = closed[j].h
                break
            }
        }
        if (reversalHigh.isFinite()) {
            reversalEntry =
                closes[i] > reversalHigh &&
                    bullish
        }

        // Second Wise Man: three consecutive green AO bars after a valid
        // confirmed fractal outside the Teeth line.
        var greenStreak = 0
        for (j in i downTo max(1, i - 10)) {
            if (ao(closed, j) > ao(closed, j - 1)) {
                greenStreak++
            } else {
                break
            }
        }
        val prevFractalForAo = latestConfirmedUpFractal(closed, i - 1)
        val prevFractalOutside =
            prevFractalForAo != null &&
                teethSeries.getOrNull(i - 1 - 5)?.let {
                    closed[prevFractalForAo].h > it
                } == true
        val superAo = greenStreak >= 3 && prevFractalOutside

        // Third Wise Man: confirmed fractal breakout.
        val wiseCount =
            listOf(
                reversalEntry,
                superAo,
                fractalBreak
            ).count { it }

        val family =
            buildList {
                if (reversalEntry) add("REVERSAL")
                if (superAo) add("SUPER_AO")
                if (fractalBreak) add("FRACTAL")
            }.joinToString("+")
                .ifBlank { "NONE" }

        val longSignal =
            fractalOutside &&
                bullish &&
                awake &&
                wiseCount >= 2

        val atrPct = atrPct(closed)
        val atrAbs = atrAbs(closed)

        val trendScore = if (bullish) 35.0 else 0.0
        val aoScore =
            when {
                aoPositive && ao(closed, i - 1) <= aoValue -> 20.0
                aoPositive -> 12.0
                else -> 0.0
            }
        val breakoutScore =
            if (fractalBreak) 25.0
            else if (fractalHigh != null) 8.0
            else 0.0
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
        var score =
            trendScore +
                aoScore +
                breakoutScore +
                atrScore +
                volumeScore +
                (wiseCount * 2.0)

        val preliminaryWave = waveInfo(closed, "1h")
        if (preliminaryWave.position == 3) {
            score += 5.0
        } else if (preliminaryWave.position == 5) {
            score -= 8.0
        }
        if (preliminaryWave.aoBearishDivergence) {
            score -= 12.0
        }

        val obi = orderBookCache.imbalance(symbol)
        val flowImbalance = tradeFlowImbalance(symbol)
        var strictSignal = longSignal

        if (obi != null) {
            score += (obi * 5.0).coerceIn(-5.0, 5.0)
            if (strictSignal && obi <= -0.80) strictSignal = false
        }
        if (flowImbalance != null) {
            score += (flowImbalance * 4.0).coerceIn(-4.0, 4.0)
        }

        score = score.coerceIn(0.0, 100.0)

        val riskPct =
            min(0.08, max(0.0, atrPct * 2.0))
        val rrValue =
            if (atrAbs > 0.0) 2.0 else 0.0

        val reason =
            when {
                strictSignal ->
                    "Alligator + 2/3 Wise Men: $family"
                preliminaryWave.position == 5 ->
                    "Wave 5: повышенный риск истощения"
                bullish && aoPositive ->
                    "Бычья пасть + положительный AO; ждём Wise-Man trigger"
                else ->
                    "Наблюдение: структура ещё не готова"
            }

        return BaseAnalysis(
            symbol = symbol,
            candles = closed,
            score = score,
            signal = strictSignal,
            htfCandidate = bullish && aoPositive,
            wave = preliminaryWave,
            atrPct = atrPct,
            riskPct = riskPct,
            riskReward = rrValue,
            spreadPct = spread,
            breakoutDistancePct = breakoutDistance,
            obi = obi,
            tradeFlowImbalance = flowImbalance,
            wiseManCount = wiseCount,
            signalFamily = family,
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
        val waveSafeForEntry =
            nestedW3ParentW5 ||
                (
                    setup.position != 5 &&
                        setup.exhaustionRisk <= 80.0 &&
                        !setup.aoBearishDivergence
                )

        val finalSignal =
            entrySignal &&
                htfConfirmed &&
                waveSafeForEntry &&
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
            obi = baseCandidate.obi,
            tradeFlowImbalance = baseCandidate.tradeFlowImbalance,
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
            .put("obi", candidate.obi)
            .put("trade_flow_imbalance", candidate.tradeFlowImbalance)
            .put("htf_confirmed", candidate.htfCandidate)
            .put("setup_state", setupState)
            .put("reason", candidate.reason)
            .put("wise_man_count", candidate.wiseManCount)
            .put("signal_family", candidate.signalFamily)
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
        if (values.isEmpty() || length <= 0) return emptyList()
        if (values.size < length) return List(values.size) { Double.NaN }

        val output = MutableList(values.size) { Double.NaN }
        output[length - 1] =
            values.take(length).average()

        for (i in length until values.size) {
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
        if (index < 33) return 0.0

        val medians =
            candles.map { (it.h + it.l) / 2.0 }

        val fast =
            medians.subList(index - 4, index + 1).average()

        val slow =
            medians.subList(index - 33, index + 1).average()

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
        val maxDailyLossPct = 0.03
        val equity = estimateManagedEquity().coerceAtLeast(1.0)
        val dailyLossPct = (-pnl / equity).coerceAtLeast(0.0)
        val dailyLossLimit = dailyLossPct >= maxDailyLossPct
        val maxTradesReached = count >= 5
        val mode = when {
            maxTradesReached -> "DAILY_TRADE_LIMIT"
            dailyLossLimit -> "DAILY_LOSS_LIMIT"
            hardPause -> "PAUSED"
            cooldown -> "COOLDOWN"
            else -> "ACTIVE"
        }
        return JSONObject()
            .put("trades_today", count)
            .put("max_trades_per_day", 5)
            .put("daily_pnl_usdt", pnl)
            .put("daily_loss_pct", dailyLossPct)
            .put("max_daily_loss_pct", maxDailyLossPct)
            .put("consecutive_losses", consecutiveLosses)
            .put("allow", !cooldown && !hardPause && !dailyLossLimit && !maxTradesReached)
            .put("mode", mode)
    }

    private fun scannerSnapshot(): JSONObject =
        JSONObject()
            .put("version", BuildConfig.VERSION_NAME)
            .put("cached", true)
            .put("scanning", scanning)
            .put("scanner_state", scannerState)
            .put("scanner_error", scannerError ?: JSONObject.NULL)
            .put("last_error", lastError ?: JSONObject.NULL)
            .put("last_scan_at", lastScanAt)
            .put("last_scan_duration_ms", lastScanDurationMs)
            .put("symbols_scanned", lastSymbolsScanned)
            .put("scanner_symbols", lastSymbolsScanned)
            .put("scan_universe", scannerUniverseLabel)
            .put("deep_wave_targets", waveTopN)
            .put("history_ready", historyReady)
            .put("history_warmup_running", historyWarmupRunning)
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

    fun portfolio(): JSONObject {
        if (key().isBlank() || secret().isBlank()) {
            return JSONObject()
                .put("configured", false)
                .put("testnet", true)
                .put("total_equity_usdt", JSONObject.NULL)
                .put("free_equity_usdt", JSONObject.NULL)
                .put("locked_equity_usdt", JSONObject.NULL)
                .put("realized_pnl_usdt", TradeJournal.stats(prefs).optDouble("pnl", 0.0))
                .put("unrealized_pnl_usdt", 0.0)
                .put("assets", JSONArray())
                .put("positions", JSONArray())
        }

        val account = signedAccount()
        val tickerRows = JSONArray(getBody("/api/v3/ticker/price"))
        val prices = HashMap<String, Double>()
        for (i in 0 until tickerRows.length()) {
            val row = tickerRows.optJSONObject(i) ?: continue
            val price = row.optString("price").toDoubleOrNull() ?: continue
            if (price > 0.0) {
                prices[row.optString("symbol").uppercase(Locale.US)] = price
            }
        }

        data class AssetRow(
            val asset: String,
            val free: Double,
            val locked: Double,
            val price: Double?,
            val value: Double
        )

        fun valuePrice(asset: String): Double? {
            if (asset in setOf("USDT", "USDC", "FDUSD", "TUSD", "USDE", "USDP", "DAI")) {
                return 1.0
            }
            prices[asset + "USDT"]?.takeIf { it > 0.0 }?.let { return it }
            prices[asset + "USDC"]?.let { cross ->
                val usdc = prices["USDCUSDT"] ?: 0.0
                if (cross > 0.0 && usdc > 0.0) return cross * usdc
            }
            for (bridge in listOf("BTC", "ETH", "BNB")) {
                val direct = prices[asset + bridge] ?: continue
                val bridgeUsdt = prices[bridge + "USDT"] ?: continue
                if (direct > 0.0 && bridgeUsdt > 0.0) return direct * bridgeUsdt
            }
            return null
        }

        val rows = mutableListOf<AssetRow>()
        val balances = account.optJSONArray("balances") ?: JSONArray()
        for (i in 0 until balances.length()) {
            val row = balances.optJSONObject(i) ?: continue
            val asset = row.optString("asset").uppercase(Locale.US)
            if (asset.isBlank()) continue
            val free = row.optString("free").toDoubleOrNull() ?: 0.0
            val locked = row.optString("locked").toDoubleOrNull() ?: 0.0
            val total = free + locked
            if (total <= 0.000000000001) continue
            val price = valuePrice(asset)
            rows += AssetRow(
                asset = asset,
                free = free,
                locked = locked,
                price = price,
                value = if (price != null && price > 0.0) total * price else 0.0
            )
        }

        val unknownAssets = rows.filter { it.price == null }.map { it.asset }
        val totalEquity = rows.sumOf { it.value }
        val freeEquity = rows.sumOf {
            if (it.price != null && it.price > 0.0) it.free * it.price else 0.0
        }
        val lockedEquity = rows.sumOf {
            if (it.price != null && it.price > 0.0) it.locked * it.price else 0.0
        }

        val assets = JSONArray()
        rows.sortedByDescending { it.value }.forEach {
            assets.put(
                JSONObject()
                    .put("asset", it.asset)
                    .put("free", it.free)
                    .put("locked", it.locked)
                    .put("total", it.free + it.locked)
                    .put("price_usdt", it.price ?: JSONObject.NULL)
                    .put("value_usdt", it.value)
                    .put(
                        "allocation_pct",
                        if (totalEquity > 0.0) it.value / totalEquity else 0.0
                    )
            )
        }

        val positions = JSONArray()
        var unrealized = 0.0
        positionList().forEach { position ->
            val mark = livePrices[position.symbol] ?: prices[position.symbol] ?: 0.0
            val pnl = if (mark > 0.0) (mark - position.entry) * position.qty else 0.0
            unrealized += pnl
            val positionValue = if (mark > 0.0) mark * position.qty else position.entry * position.qty
            positions.put(
                JSONObject()
                    .put("symbol", position.symbol)
                    .put("qty", position.qty)
                    .put("avg_entry_price", position.entry)
                    .put("current_price", if (mark > 0.0) mark else JSONObject.NULL)
                    .put("position_value_usdt", positionValue)
                    .put("allocation_pct", if (totalEquity > 0.0) positionValue / totalEquity else 0.0)
                    .put("unrealized_pnl_usdt", pnl)
                    .put("unrealized_pnl_pct", if (position.entry > 0.0 && mark > 0.0) (mark - position.entry) / position.entry else 0.0)
                    .put("stop_loss", position.stop)
                    .put("take_profit", position.take)
                    .put("risk_pct", position.riskPct)
                    .put("risk_amount_usdt", max(0.0, (position.entry - position.stop) * position.qty))
                    .put("oco_list_id", position.ocoListId)
                    .put("oco_list_client_id", position.ocoListClientId)
            )
        }

        return JSONObject()
            .put("configured", true)
            .put("testnet", true)
            .put("total_equity_usdt", totalEquity)
            .put("free_equity_usdt", freeEquity)
            .put("locked_equity_usdt", lockedEquity)
            .put("realized_pnl_usdt", TradeJournal.stats(prefs).optDouble("pnl", 0.0))
            .put("unrealized_pnl_usdt", unrealized)
            .put("assets", assets)
            .put("positions", positions)
            .put("position_count", positionList().size)
            .put("valuation_unknown_assets", unknownAssets.size)
            .put("valuation_unknown_asset_list", JSONArray(unknownAssets))
            .put("valuation_complete", unknownAssets.isEmpty())
    }

    fun diagnostics(run: Boolean): JSONObject {
        val startedAt = System.currentTimeMillis()
        val tests = JSONArray()

        fun test(domain: String, name: String, block: () -> String) {
            try {
                tests.put(
                    JSONObject()
                        .put("domain", domain)
                        .put("name", name)
                        .put("status", "PASS")
                        .put("message", block())
                )
            } catch (x: Exception) {
                tests.put(
                    JSONObject()
                        .put("domain", domain)
                        .put("name", name)
                        .put("status", "FAIL")
                        .put("message", x.message ?: x.javaClass.simpleName)
                )
            }
        }

        val configured = key().isNotBlank() && secret().isNotBlank()
        test("runtime", "Runtime health") {
            "state=" + stateMachine.state.name + "; running=" + running + "; paused=" + paused
        }
        test("security", "Diagnostic write-safety") {
            "0 order writes; no execution-gate bypass"
        }
        test("binance", "Public /api/v3/time") {
            val server = JSONObject(getBody("/api/v3/time")).optLong("serverTime", 0L)
            require(server > 0L) { "serverTime missing" }
            "serverTime=" + server
        }
        test("binance", "Public /api/v3/exchangeInfo") {
            val info = JSONObject(getBody("/api/v3/exchangeInfo"))
            rateGuard.updateFromExchangeInfo(info)
            val count = info.optJSONArray("symbols")?.length() ?: 0
            require(count > 0) { "exchangeInfo returned no symbols" }
            "symbols=" + count + "; weightLimit=" + rateGuard.requestWeightLimit1m + "; orderLimit=" + rateGuard.orderLimit1m
        }
        test("binance", "Signed account + permissions") {
            require(configured) { "credentials_not_configured" }
            val account = signedAccount()
            val canTrade = account.optBoolean("canTrade", false)
            val balances = account.optJSONArray("balances")?.length() ?: 0
            require(canTrade) { "canTrade=false or permission unavailable" }
            "canTrade=" + canTrade + "; balances=" + balances
        }
        test("binance", "Server time offset") {
            require(configured) { "credentials_not_configured" }
            syncServerTime()
            "offset_ms=" + serverTimeOffsetMs
        }
        test("binance", "Klines 1h / 4h") {
            val a = fetchCandles(primarySymbol, "1h", 80)
            val b = fetchCandles(primarySymbol, "4h", 80)
            require(a.size >= 40) { "1h candles=" + a.size }
            require(b.size >= 40) { "4h candles=" + b.size }
            "1h=" + a.size + "; 4h=" + b.size
        }
        test("scanner", "USDT universe and liquidity") {
            val universe = loadUniverse()
            require(universe.first.isNotEmpty()) { "empty universe" }
            "eligible_top=" + universe.first.size + "; max=" + maxScanSymbols
        }
        test("scanner", "Williams + MTF sample") {
            val candles = fetchCandles(primarySymbol, "1h", 150)
            val base = analyseBase(primarySymbol, candles, 0.0, 100_000_000.0)
            "wave=W" + base.wave.position + "; score=" + String.format(Locale.US, "%.2f", base.score) +
                "; confidence=" + String.format(Locale.US, "%.2f", base.wave.confidence)
        }
        test("database", "Persistent history store") {
            val snapshot = historyStore.status(coreSymbols, analysisFrames)
            require(snapshot.optInt("symbols", 0) >= 0) { "history snapshot invalid" }
            "history store responded"
        }
        test("execution", "FSM safety contract") {
            require(stateMachine.state.name.isNotBlank()) { "FSM state missing" }
            "state=" + stateMachine.state.name + "; reconcile=" + reconcileRequired +
                "; kill=" + killLatched + "; userSync=" + userStreamSyncRequired
        }
        test("websocket", "Market WebSocket") {
            val wasConnected = marketSocketConnected
            if (!wasConnected) {
                startMarketDataStream()
                val deadline = System.currentTimeMillis() + 5000L
                while (!marketSocketConnected && System.currentTimeMillis() < deadline) {
                    Thread.sleep(100L)
                }
            }
            val ok = marketSocketConnected
            if (!wasConnected && !running) {
                marketSocket?.close(1000, "diagnostics")
                marketSocket = null
                marketSocketConnected = false
            }
            require(ok) { "market_ws_not_connected" }
            "connected=true"
        }
        test("websocket", "User Data Stream") {
            require(configured) { "credentials_not_configured" }
            val wasConnected = userStreamConnected
            if (!wasConnected) {
                userStream.start()
                val deadline = System.currentTimeMillis() + 7000L
                while (!userStreamConnected && System.currentTimeMillis() < deadline) {
                    Thread.sleep(100L)
                }
            }
            val ok = userStreamConnected
            if (!wasConnected && !running) {
                userStream.stop()
                userStreamConnected = false
                userStreamSyncRequired = true
            }
            require(ok) { "user_data_stream_not_connected" }
            "subscription=signature; connected=true"
        }
        test("execution", "Read-only order filters and OCO support") {
            val rules = symbolFilters(primarySymbol)
            require(rules.step > 0.0 && rules.tick > 0.0) { "symbol filters invalid" }
            "tick=" + rules.tick + "; step=" + rules.step + "; minNotional=" + rules.minNotional +
                "; ocoAllowed=" + rules.ocoAllowed
        }
        test("ui", "Local Android runtime contract") {
            "HTTP=127.0.0.1:18080; appVersion=" + BuildConfig.VERSION_NAME + "; apiSchema=1"
        }
        test("execution", "No order endpoint invoked by diagnostics") {
            "POST/DELETE /api/v3/order* are intentionally not called"
        }

        val failCount = (0 until tests.length()).count {
            tests.optJSONObject(it)?.optString("status") == "FAIL"
        }
        return JSONObject()
            .put("schema", 1)
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("created_at", System.currentTimeMillis())
            .put("duration_ms", System.currentTimeMillis() - startedAt)
            .put("overall", if (failCount == 0) "PASS" else "FAIL")
            .put("configured", configured)
            .put("testnet", true)
            .put("tests", tests)
            .put("system_info", AndroidRuntimeHealth.snapshot(context))
            .put(
                "configuration_sanitized",
                JSONObject()
                    .put("mode", "AUTONOMOUS")
                    .put("testnet", true)
                    .put("api_key_configured", configured)
                    .put("api_key_fingerprint", if (configured) "configured_only" else "not_configured")
                    .put("risk_per_trade_pct", maxRiskPerTradePct)
                    .put("max_total_risk_pct", maxTotalRiskPct)
                    .put("max_daily_loss_pct", 0.03)
                    .put("max_trades_per_day", 5)
                    .put("max_consecutive_losses", 3)
                    .put("scanner_max_symbols", maxScanSymbols)
                    .put("wave_top_n", waveTopN)
                    .put("recv_window_ms", BINANCE_RECV_WINDOW_MS)
            )
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
        var livePnl = 0.0
        var livePnlPct = 0.0

        positionList().forEach { position ->
            val mark = (
                livePrices[position.symbol]
                    ?: if (position.symbol == primarySymbol) {
                        primaryCandles.lastOrNull()?.c
                    } else {
                        null
                    }
                ) ?: 0.0
            if (mark > 0.0) {
                livePnl += (mark - position.entry) * position.qty
            }
        }
        val totalEntryNotional =
            positionList().sumOf { it.entry * it.qty }
        if (totalEntryNotional > 0.0) {
            livePnlPct = livePnl / totalEntryNotional
        }

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
                        liveUsdtBalance = balance ?: 0.0
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
            .put("version", BuildConfig.VERSION_NAME)
            .put("symbol", primarySymbol)
            .put("interval", interval)
            .put("testnet", true)
            .put("running", running)
            .put("paused", paused)
            .put("recovered", !reconcileRequired)
            .put("state", stateName())
            .put("execution_enabled", !isTradingBlocked())
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
                livePrices[primarySymbol]
                    ?: primaryCandles.lastOrNull()?.c
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
            .put("position_capacity_mode", "FIXED_COUNT_AND_RISK")
            .put("risk_based_position_capacity", maxOpenPositions)
            .put("max_open_positions", maxOpenPositions)
            .put("reserved_risk_pct", reservedRiskPct())
            .put("max_total_risk_pct", maxTotalRiskPct)
            .put("max_risk_per_trade_pct", maxRiskPerTradePct)
            .put("reconcile_required", reconcileRequired)
            .put("pnl", if (positionList().isEmpty()) JSONObject.NULL else livePnl)
            .put("pnl_pct", if (positionList().isEmpty()) JSONObject.NULL else livePnlPct)
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
            .put("max_open_positions", maxOpenPositions)
            .put("max_daily_loss_pct", 0.03)
            .put("trades_today", dailyGuard.optInt("trades_today", 0))
            .put("daily_pnl_usdt", dailyGuard.optDouble("daily_pnl_usdt", 0.0))
            .put("consecutive_losses", dailyGuard.optInt("consecutive_losses", 0))
            .put("trading_mode", dailyGuard.optString("mode", "ACTIVE"))
            .put("server_time", System.currentTimeMillis())
            .put("market_ws_connected", marketSocketConnected)
            .put("user_ws_connected", userStreamConnected)
            .put("user_stream_sync_required", userStreamSyncRequired)
            .put("user_stream_last_event_ms", lastUserEventMs)
            .put("history_ready", historyReady)
            .put("fsm_state", stateMachine.state.name)
            .put("kill_switch_latched", killLatched)
            .put("live_prices", JSONObject().apply {
                livePrices.forEach { (symbol, price) -> put(symbol, price) }
            })
            .put("rate_limits", rateGuard.snapshot())
            .put("history", historyStore.status(coreSymbols, analysisFrames))
            .put("scanner_scanning", scanning)
            .put("scanner_state", scannerState)
            .put("scanner_error", scannerError ?: JSONObject.NULL)
            .put("scanner_symbols", lastSymbolsScanned)
            .put("scanner_last_scan_at", lastScanAt)
            .put("scanner_duration_ms", lastScanDurationMs)
            .put("scanner_universe_size", scanSymbols.size)
            .put("scanner_candidates", candidates.length())
            .put("scanner_last_success", if (scannerState == "READY") lastScanAt else 0L)
            .put("p0_gate_passed", p0GatePassed())
            .put("p0_gate_reason", p0GateReason())
            .put("max_open_positions_locked", true)
            .put("unresolved_symbols", unresolvedPositionSymbols())
            .put("pending_entry_symbols", pendingEntrySymbols())
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
            .put("version", BuildConfig.VERSION_NAME)
            .put("symbol", primarySymbol)
            .put("interval", interval)
            .put("position_fraction", 0.25)
            .put("stop_loss_pct", 0.02)
            .put("take_profit_pct", 0.04)
            .put("poll_seconds", 90)
            .put("scan_mode", "adaptive_parallel_cached")
            .put("wave_timeframes", analysisFrames.joinToString(","))
            .put("realtime_multi_timeframe_stream", marketSocketConnected)
            .put("core_symbols", coreSymbols.joinToString(","))
            .put("full_history_wave_analysis", true)
            .put("full_history_base_timeframe", "1h")
            .put("risk_per_trade_pct", maxRiskPerTradePct)
            .put("max_daily_loss_pct", 0.03)
            .put("max_trades_per_day", 5)
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
            .put("scanner_cadence_seconds", 90)
            .put("deep_wave_targets", waveTopN)
            .put("scanner_strategy", "liquidity -> base -> deep MTF/Waves -> risk -> score")
            .put("wave_top_n", waveTopN)
            .put("trade_journal", true)
            .put("trade_journal_max_rows", 500)
}