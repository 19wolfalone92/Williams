@file:Suppress("UseKtx")
package com.williamsbot

import android.annotation.SuppressLint
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
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.ArrayDeque
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
    @SuppressLint("StaticFieldLeak")
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

    /** Configure credentials in the exact Keystore-backed store consumed by NativeEngine. */
    fun configureCredentials(context: Context, apiKey: String, apiSecret: String): JSONObject {
        start(context)
        return server!!.configureCredentials(apiKey, apiSecret)
    }

    fun clearCredentials(context: Context): JSONObject {
        start(context)
        return server!!.clearCredentials()
    }

    fun status(context: Context, fast: Boolean = false): JSONObject {
        start(context)
        return server!!.status(fast = fast)
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
    var openedAt: Long = 0L,
    var campaignId: String = "",
    var signalId: String = "",
    var signalType: String = "",
    var campaignState: String = "OPEN_INITIAL",
    var stopSource: String = "INITIAL_SIGNAL",
    var additions: Int = 0,
    var protectiveOrderId: String = ""
)

private data class PendingEntry(
    val symbol: String,
    val clientOrderId: String,
    val notional: Double,
    val stopDistance: Double,
    val triggerPrice: Double = 0.0,
    val protectivePrice: Double = 0.0,
    val riskReservedPct: Double = 0.0,
    val capitalReservedQuote: Double = 0.0,
    val campaignId: String = "",
    val signalId: String = "",
    val signalType: String = "REVERSAL"
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

private data class CampaignSignalN(
    val signalId: String,
    val type: String,
    val role: String,
    val signalBarTimeMs: Long,
    val triggerPrice: Double,
    val protectivePrice: Double,
    val teethAtDetection: Double,
    val invalidationPrice: Double,
    val reason: String
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
    val reason: String,
    val campaignSignals: List<CampaignSignalN> = emptyList(),
    val campaignReady: Boolean = false
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
    }

    fun autostart() {
        if (
            prefs.getBoolean("auto_run", false)
        ) {
            e().start()
        }
    }

    fun configureCredentials(apiKey: String, apiSecret: String): JSONObject =
        e().configure(apiKey, apiSecret)

    fun clearCredentials(): JSONObject = e().clear()

    fun status(fast: Boolean = false): JSONObject = e().status(fast = fast)

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
                x.status(fast = params["fast"].equals("true", true)).toString()

            method == "GET" && path == "/api/v1/market/indicators" ->
                x.indicators(params["symbol"], params["interval"]).toString()

            method == "GET" && path == "/api/v1/market/klines" ->
                x.klines(
                    requestedSymbol = params["symbol"],
                    requestedInterval = params["interval"]
                ).toString()

            method == "GET" && path == "/api/v1/market/tickers" ->
                x.marketTickers().toString()

            method == "GET" && path == "/api/v1/scanner" ->
                x.scanner(
                    refresh = params["refresh"].equals("true", true)
                ).toString()

            method == "GET" && path == "/api/v1/portfolio" ->
                x.portfolio().toString()

            method == "GET" && path == "/api/v1/trades" ->
                x.trades().toString()

            method == "GET" && path == "/api/v1/trade-stats" ->
                x.tradeStats().toString()

            method == "GET" && path == "/api/v1/logs" ->
                x.logs().toString()

            method == "GET" && path == "/api/v1/settings" ->
                x.settings().toString()

            method == "GET" && path == "/api/v1/diagnostics" ->
                x.diagnostics().toString()

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
                path == "/api/v1/control/self-heal" ->
                x.selfHeal().toString()

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
    // Startup only needs the frames that directly participate in execution.
    // The complete 15-TF matrix is analysis metadata, not a startup blocker.
    private val startupFrames = listOf("15m", "1h", "4h")
    // Fetch enough closed candles for the scanner's 150-candle working set while
    // leaving headroom for the currently forming candle.
    private val startupHistoryLimit = 180
    private val minStartupHistoryCandles = 150
    private val maxScanSymbols = 5
    private val waveTopN = 5
    private val scanExecutor = Executors.newFixedThreadPool(12)
    private val historyBackfillExecutor = Executors.newFixedThreadPool(6) { runnable ->
        Thread(runnable, "williams-history-backfill").apply { isDaemon = true }
    }
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
    private val orderBookCache = OrderBookCache()
    @Volatile private var marketSocketConnected = false
    @Volatile private var marketSocketLastEventMs = 0L
    @Volatile private var historyWarmupRunning = false
    @Volatile private var historyReady = false
    private val activeHistoryTasks = java.util.concurrent.ConcurrentHashMap.newKeySet<java.util.concurrent.Future<Boolean>>()
    @Volatile private var historyState = "IDLE"
    @Volatile private var historyLastError: String? = null

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
    // Default supports the portfolio model: up to five independent positions;
    // aggregate risk remains capped separately at 1%.
    private val maxOpenPositions: Int
        get() = prefs.getInt("max_open_positions", 5).coerceIn(1, 10)
    private val campaignEngineEnabled: Boolean
        get() = prefs.getBoolean("campaign_engine_enabled", true)
    private val campaignExecutionTimeframe: String
        get() = prefs.getString("campaign_execution_timeframe", "5m") ?: "5m"
    private val campaignRiskLimitPct = 0.005
    private val campaignInitialRiskPct = 0.002
    private val campaignAddRiskCapPct = 0.002
    private val campaignTrailBars: Int
        get() = prefs.getInt("campaign_trail_bars", 5).coerceIn(3, 5)
    private val maxTotalRiskPct = 0.01
    private val maxRiskPerTradePct = 0.005
    private val maxSpreadPct = 0.0015
    private val maxSlippagePct = 0.0015
    private val equityCircuitBreaker = EquityCircuitBreaker(maxDrawdownPct = 0.05)
    @Volatile private var lastEquityCheckMs = 0L
    @Volatile private var circuitBreakerTripInProgress = false
    private val feeBufferPerSidePct = 0.001
    @Volatile private var serverTimeOffsetMs = 0L
    private val BINANCE_RECV_WINDOW_MS = 60000L
    @Volatile private var lastServerTimeSyncMs = 0L
    @Volatile private var lastOrder: JSONObject? = null
    @Volatile private var reconcileRequired = false
    @Volatile private var killLatched = false
    @Volatile private var userStreamConnected = false
    @Volatile private var userStreamSyncRequired = true
    @Volatile private var lastUserEventMs = 0L
    @Volatile private var liveUsdtBalance = 0.0
    @Volatile private var restMarketReady = false
    @Volatile private var restAccountReady = false
    @Volatile private var restLastSuccessMs = 0L
    @Volatile private var restLastLatencyMs = 0L
    @Volatile private var restLastError: String? = null
    @Volatile private var restLastTickerPrice = 0.0
    @Volatile private var restProbeRunning = false
    private var restProbeThread: Thread? = null
    @Volatile private var lastRestReconcileMs = 0L
    @Volatile private var runtimeGeneration = 0L
    @Volatile private var lastSelfHealMs = 0L
    @Volatile private var selfHealCount = 0

    @Volatile private var scannerState = "NOT_RUN"
    @Volatile private var scannerError: String? = null
    @Volatile private var scanRunId = ""
    @Volatile private var scanStartedAt = 0L
    @Volatile private var scanLastProgressAt = 0L
    @Volatile private var scanProgressSymbols = 0

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
        startRestDataProbe()
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
                    it.ocoListId.isNotBlank() ||
                        it.ocoListClientId.isNotBlank() ||
                        it.protectiveOrderId.isNotBlank()
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

    private fun reservedRiskPct(): Double =
        positionList().sumOf {
            if (it.riskPct > 0.0) it.riskPct
            else if (it.entry > 0.0) {
                ((it.entry - it.stop) / it.entry)
                    .coerceAtLeast(0.0)
            } else 0.0
        }

    private fun marketDataFresh(maxAgeMs: Long = 30_000L): Boolean =
        restMarketReady &&
            restLastSuccessMs > 0L &&
            System.currentTimeMillis() - restLastSuccessMs <= maxAgeMs

    private fun executionReady(): Boolean =
        running &&
            !paused &&
            !reconcileRequired &&
            !killLatched &&
            marketDataFresh() &&
            restAccountReady &&
            historyReady &&
            stateMachine.executionAllowed() &&
            !equityCircuitBreaker.isTripped()

    private fun executionBlockers(): List<String> =
        buildList {
            if (!running) add("runtime_not_running")
            if (paused) add("paused")
            if (reconcileRequired) add("reconcile_required")
            if (killLatched) add("kill_switch_latched")
            if (!marketDataFresh()) add("rest_market_stale_or_not_ready")
            if (!restAccountReady) add("rest_account_not_ready")
            if (!historyReady) add("history_not_ready")
            if (!stateMachine.executionAllowed()) add("fsm_not_execution_allowed")
            if (equityCircuitBreaker.isTripped()) add("equity_circuit_breaker")
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
            TradingState.READY_FLAT -> "READY_FLAT"
            TradingState.STOPPED -> "STOPPED"
            TradingState.INITIALIZING -> "INITIALIZING"
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
                    .put("campaign_id", it.campaignId)
                    .put("signal_id", it.signalId)
                    .put("signal_type", it.signalType)
                    .put("campaign_state", it.campaignState)
                    .put("stop_source", it.stopSource)
                    .put("additions", it.additions)
                    .put("protective_order_id", it.protectiveOrderId)
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
                        .put("trigger_price", it.triggerPrice)
                        .put("protective_price", it.protectivePrice)
                        .put("risk_reserved_pct", it.riskReservedPct)
                        .put("capital_reserved_quote", it.capitalReservedQuote)
                        .put("campaign_id", it.campaignId)
                        .put("signal_id", it.signalId)
                        .put("signal_type", it.signalType)
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
                        openedAt = item.optLong("opened_at", 0L),
                        campaignId = item.optString("campaign_id", ""),
                        signalId = item.optString("signal_id", ""),
                        signalType = item.optString("signal_type", ""),
                        campaignState = item.optString("campaign_state", "OPEN_INITIAL"),
                        stopSource = item.optString("stop_source", "INITIAL_SIGNAL"),
                        additions = item.optInt("additions", 0),
                        protectiveOrderId = item.optString("protective_order_id", "")
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
                        ),
                        triggerPrice = item.optDouble("trigger_price", 0.0),
                        protectivePrice = item.optDouble("protective_price", 0.0),
                        riskReservedPct = item.optDouble("risk_reserved_pct", 0.0),
                        capitalReservedQuote = item.optDouble("capital_reserved_quote", 0.0),
                        campaignId = item.optString("campaign_id", ""),
                        signalId = item.optString("signal_id", ""),
                        signalType = item.optString("signal_type", "REVERSAL")
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
                executionReady()
            )
            .put("state", stateName())
            .put("open_positions", positionList().size)
            .put("max_open_positions", maxOpenPositions)
            .put("reserved_risk_pct", reservedRiskPct())
            .put("circuit_breaker_tripped", equityCircuitBreaker.isTripped())
            .put("managed_equity", estimateManagedEquity())
            .put("max_total_risk_pct", maxTotalRiskPct)
            .put("max_risk_per_trade_pct", maxRiskPerTradePct)
            .put("reconcile_required", reconcileRequired)
            .put("execution_state_contract", JSONObject()
                .put("version", 1)
                .put("state", stateMachine.state.name)
                .put("execution_enabled", executionReady())
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

    fun configure(j: JSONObject): JSONObject =
        configure(j.optString("api_key"), j.optString("api_secret"))

    fun configure(apiKey: String, apiSecret: String): JSONObject {
        val newKey = apiKey.trim()
        val newSecret = apiSecret.trim()

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

        val oldKey = key()
        val oldSecret = secret()
        prefs.edit()
            .putString("api_key", newKey)
            .putString("api_secret", newSecret)
            .apply()

        return try {
            val account = signedAccount()
            require(account.optString("accountType", "SPOT").equals("SPOT", true)) {
                "Configured Binance account is not Spot"
            }
            restAccountReady = account.has("balances")
            restLastError = null
            JSONObject()
                .put("configured", true)
                .put("testnet", true)
                .put("standalone", true)
                .put("validation", "PASS")
                .put("read_back_verified", key() == newKey && secret() == newSecret)
        } catch (x: Exception) {
            prefs.edit()
                .putString("api_key", oldKey)
                .putString("api_secret", oldSecret)
                .apply()
            restAccountReady = false
            throw IllegalStateException(
                "Binance credentials validation failed: " +
                    (x.message ?: x.javaClass.simpleName),
                x
            )
        }
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
            .putBoolean("auto_run", false)
            .apply()

        candidates = JSONArray()
        primaryCandles = emptyList()
        scanSymbols = mutableListOf()
        historyReady = false
        historyWarmupRunning = false
        historyState = "IDLE"
        historyLastError = null
        lastError = null
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

        val generation = ++runtimeGeneration
        prefs.edit()
            .putBoolean("auto_run", true)
            .apply()

        stateMachine.force(TradingState.INITIALIZING, "bot start")
        userStreamSyncRequired = true
        historyReady = false
        historyState = "LOADING"
        historyLastError = null
        scannerState = "WAITING_FOR_HISTORY"
        scannerError = null
        scanRunId = ""
        scanProgressSymbols = 0

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
        warmCoreHistoryAsync(generation)
        startMarketDataStream()
        userStream.start()

        worker = Thread {
            while (running && generation == runtimeGeneration) {
                if (!paused) {
                    try {
                        if (!historyReady) {
                            if (!historyWarmupRunning) {
                                warmCoreHistoryAsync(generation)
                            }
                        } else if (
                            marketDataFresh() &&
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
                            if (
                                !userStreamConnected &&
                                positionList().isNotEmpty() &&
                                System.currentTimeMillis() - lastRestReconcileMs >= 60_000L
                            ) {
                                runCatching {
                                    lastRestReconcileMs = System.currentTimeMillis()
                                    reconcilePositionsWithExchange()
                                }.onFailure {
                                    lastError = "REST recovery: " +
                                        (it.message ?: it.javaClass.simpleName)
                                }
                            }
                            requestScan()
                        }
                    } catch (x: Exception) {
                        lastError =
                            x.javaClass.simpleName + ": " +
                                (x.message ?: "")
                    }
                }

                try {
                    Thread.sleep(10_000L)
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
            .put("startup_state", "LOADING_HISTORY")
            .put("execution_ready", executionReady())
            .put("execution_blockers", JSONArray(executionBlockers()))
            .put("interval_seconds", 10)
    }

    fun stop(): JSONObject {
        ++runtimeGeneration
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
        scanning = false
        scannerState = "STOPPED"
        scannerError = null
        historyReady = false
        historyState = "STOPPED"
        historyLastError = null
        activeHistoryTasks.forEach { it.cancel(true) }
        activeHistoryTasks.clear()

        return JSONObject().put("stopped", true).put("state", "STOPPED")
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
                .put("execution_enabled", executionReady())
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
        // GET market-data calls are idempotent. Testnet can transiently return
        // 5xx/502 responses, so retry only safe reads; never retry mutations.
        var lastCode = 0
        var lastBody = "{}"
        val delays = longArrayOf(500L, 1000L, 2000L)
        repeat(3) { attempt ->
            try {
                rateGuard.beforeRequest()
                val request = Request.Builder()
                    .url(baseUrl + path)
                    .get()
                    .build()
                http.newCall(request).execute().use { response ->
                    rateGuard.observe(response.headers, response.code)
                    lastCode = response.code
                    lastBody = response.body?.string() ?: "{}"
                    if (response.isSuccessful) return lastBody
                    if (response.code in setOf(500, 502, 503, 504) && attempt < 2) {
                        Thread.sleep(delays[attempt])
                        return@use
                    }
                }
            } catch (x: java.io.IOException) {
                lastBody = x.message ?: x.javaClass.simpleName
                if (attempt < 2) {
                    Thread.sleep(delays[attempt])
                    return@repeat
                }
            } catch (x: InterruptedException) {
                Thread.currentThread().interrupt()
                throw x
            }
        }
        error("Binance HTTP " + lastCode + ": " + lastBody)
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
        // Keep realtime WSS small and reliable. Deep MTF frames remain REST-backed.
        "wss://stream.testnet.binance.vision/stream?streams=" +
            coreSymbols.flatMap { symbol ->
                startupFrames.map { frame ->
                    symbol.lowercase(Locale.US) + "@kline_" + frame
                } + listOf(
                    symbol.lowercase(Locale.US) + "@bookTicker",
                    symbol.lowercase(Locale.US) + "@depth@100ms",
                    symbol.lowercase(Locale.US) + "@aggTrade"
                )
            }.joinToString("/")

    @Synchronized
    private fun startMarketDataStream() {
        if (marketSocket != null) return
        val request = Request.Builder().url(wsStreamUrl()).build()
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
            while (queue.isNotEmpty()) {
                val first = queue.peekFirst() ?: break
                if (first.timeMs >= cutoff) break
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
            while (queue.isNotEmpty()) {
                val first = queue.peekFirst() ?: break
                if (first.timeMs >= now - 5000L) break
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
            Thread {
                runCatching { syncOrderBookSnapshot(symbol) }
                    .onFailure { lastError = "L2 resync $symbol: " + (it.message ?: it.javaClass.simpleName) }
            }.apply {
                isDaemon = true
                name = "williams-l2-resync-$symbol"
                start()
            }
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

    private fun warmCoreHistoryAsync(generation: Long = runtimeGeneration) {
        if (historyWarmupRunning || !running || generation != runtimeGeneration) return
        historyWarmupRunning = true
        historyReady = false
        historyState = "LOADING"
        historyLastError = null

        Thread {
            val tasks = mutableListOf<Pair<String, java.util.concurrent.Future<Boolean>>>()
            try {
                for (symbol in coreSymbols) {
                    for (frame in startupFrames) {
                        if (!running || generation != runtimeGeneration) return@Thread
                        val label = symbol + ":" + frame
                        val future = historyBackfillExecutor.submit(Callable {
                            if (!running || generation != runtimeGeneration) return@Callable false
                            var recent = emptyList<CandleN>()
                            var failure: Throwable? = null
                            for (attempt in 0 until 3) {
                                val result = runCatching {
                                    fetchCandles(symbol, frame, startupHistoryLimit)
                                }
                                if (result.isSuccess) {
                                    recent = result.getOrDefault(emptyList())
                                    if (recent.size >= minStartupHistoryCandles) break
                                    failure = IllegalStateException("received " + recent.size + " closed candles")
                                } else {
                                    failure = result.exceptionOrNull()
                                }
                                if (attempt < 2) {
                                    try { Thread.sleep(500L * (attempt + 1)) }
                                    catch (_: InterruptedException) {
                                        Thread.currentThread().interrupt()
                                        return@Callable false
                                    }
                                }
                            }

                            if (!running || generation != runtimeGeneration) return@Callable false
                            val persistedCount = historyStore.count(symbol, frame).toInt()
                            val ok = recent.size >= minStartupHistoryCandles &&
                                persistedCount >= minStartupHistoryCandles
                            if (ok) {
                                val cacheKey = symbol + ":" + frame
                                liveCandleCache[cacheKey] =
                                    recent.takeLast(startupHistoryLimit).toMutableList()
                                candleCache[symbol + ":" + frame + ":" + startupHistoryLimit] =
                                    System.currentTimeMillis() to recent
                                indicatorSnapshots[cacheKey] =
                                    buildIndicatorSnapshot(recent, symbol, frame)
                            } else {
                                historyLastError = label + " " +
                                    (failure?.message
                                        ?: "persisted=" + persistedCount + ", received=" + recent.size)
                            }
                            ok
                        })
                        activeHistoryTasks.add(future)
                        tasks += label to future
                    }
                }

                var allReady = true
                val deadline = System.currentTimeMillis() + 90_000L
                tasks.forEach { (label, future) ->
                    val ok = runCatching {
                        val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1_000L)
                        future.get(remaining, TimeUnit.MILLISECONDS)
                    }.getOrElse {
                        future.cancel(true)
                        historyLastError = label + " task timeout/error: " +
                            (it.message ?: it.javaClass.simpleName)
                        false
                    }
                    activeHistoryTasks.remove(future)
                    if (!ok) allReady = false
                }
                if (System.currentTimeMillis() >= deadline && !allReady) {
                    tasks.forEach { (_, future) -> if (!future.isDone) future.cancel(true) }
                }

                if (generation != runtimeGeneration || !running) return@Thread

                historyReady = allReady
                historyState = if (historyReady) "READY" else "WAITING_RETRY"
                if (historyReady) {
                    // Deep history is bounded and sequential on mobile. It is
                    // a cache warmer, never a prerequisite for first scan.
                    historyBackfillExecutor.execute {
                        for (symbol in coreSymbols) {
                            if (!running || generation != runtimeGeneration) break
                            runCatching { fetchFullHistory(symbol, "1h", 6) }
                                .onFailure {
                                    lastError = "background history " + symbol + ": " +
                                        (it.message ?: it.javaClass.simpleName)
                                }
                        }
                    }
                }
            } catch (x: Exception) {
                if (generation == runtimeGeneration) {
                    historyReady = false
                    historyState = "WAITING_RETRY"
                    historyLastError = x.message ?: x.javaClass.simpleName
                    lastError = "history warmup: " + historyLastError
                }
            } finally {
                tasks.forEach { (_, future) -> activeHistoryTasks.remove(future) }
                if (generation == runtimeGeneration) {
                    historyWarmupRunning = false
                }
            }
        }.apply {
            isDaemon = true
            name = "williams-recent-history"
            start()
        }
    }

    private fun fetchCandles(
        symbol: String,
        frame: String,
        limit: Int = 150
    ): List<CandleN> {
        val normalizedSymbol = symbol.uppercase(Locale.US)
        val liveKey = normalizedSymbol + ":" + frame
        liveCandleCache[liveKey]?.let { live ->
            synchronized(live) {
                if (live.size >= limit) return live.takeLast(limit)
            }
        }

        val cacheKey = normalizedSymbol + ":" + frame + ":" + limit
        val persistent = historyStore.loadRecent(
            normalizedSymbol,
            frame,
            limit
        )
        if (persistent.size >= limit) {
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
            liveCandleCache[liveKey] = result.toMutableList()
            return result
        }

        val cached = candleCache[cacheKey]
        val now = System.currentTimeMillis()
        if (cached != null &&
            cached.second.size >= limit &&
            now - cached.first < scanCacheTtlMs
        ) {
            return cached.second.takeLast(limit)
        }

        // Binance REST has a limited set of native intervals. Build the
        // execution timeframes synthetically only when necessary.
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

        val sourceLimit = if (frame == sourceFrame) {
            limit
        } else {
            (
                limit *
                    (
                        frameSeconds(frame).coerceAtLeast(60L) /
                            frameSeconds(sourceFrame).coerceAtLeast(60L)
                    ).toInt() + 20
                ).coerceAtMost(1000)
        }

        val body = getBody(
            "/api/v3/klines?symbol=" + normalizedSymbol +
                "&interval=" + sourceFrame +
                "&limit=" + sourceLimit
        )
        val array = JSONArray(body)
        val source = ArrayList<CandleN>(array.length())
        val sourcePersistent = ArrayList<MarketHistoryStore.Candle>(array.length())
        val fetchedAt = System.currentTimeMillis()

        for (i in 0 until array.length()) {
            val row = array.getJSONArray(i)
            val openTime = row.getLong(0)
            val closeTime = row.getLong(6)
            // Persist only closed candles. The forming candle belongs to the
            // websocket/live cache and must never make history look complete.
            if (closeTime >= fetchedAt) continue

            val open = row.getString(1).toDouble()
            val high = row.getString(2).toDouble()
            val low = row.getString(3).toDouble()
            val close = row.getString(4).toDouble()
            val volume = row.getString(5).toDouble()

            source += CandleN(
                openTime,
                open,
                high,
                low,
                close,
                volume
            )

            if (frame == sourceFrame) {
                sourcePersistent += MarketHistoryStore.Candle(
                    openTime = openTime,
                    closeTime = closeTime,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    volume = volume
                )
            }
        }

        // This is the missing persistence step diagnosed by the Log branch:
        // REST-fetched candles must enter the same SQLite store used by
        // history/status/diagnostics, not remain only in RAM.
        if (sourcePersistent.isNotEmpty()) {
            historyStore.upsertBatch(
                normalizedSymbol,
                frame,
                sourcePersistent,
                complete = false
            )
        }

        val result = if (frame == sourceFrame) {
            source.takeLast(limit)
        } else {
            aggregateCandles(
                source,
                frameSeconds(frame) * 1000L,
                limit
            )
        }

        if (frame != sourceFrame && result.isNotEmpty()) {
            val bucketMs = frameSeconds(frame) * 1000L
            historyStore.upsertBatch(
                normalizedSymbol,
                frame,
                result.map {
                    MarketHistoryStore.Candle(
                        openTime = it.t,
                        closeTime = it.t + bucketMs - 1L,
                        open = it.o,
                        high = it.h,
                        low = it.l,
                        close = it.c,
                        volume = it.v
                    )
                },
                complete = false
            )
        }

        if (result.isNotEmpty()) {
            liveCandleCache[liveKey] = result.takeLast(limit).toMutableList()
            candleCache[cacheKey] =
                now to result.takeLast(limit)
        }
        return result.takeLast(limit)
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

    private fun fetchFullHistory(
        symbol: String,
        frame: String = "1h",
        maxPages: Int = 6
    ): List<CandleN> {
        val normalizedSymbol = symbol.uppercase()

        if (!historyStore.isComplete(normalizedSymbol, frame)) {
            var endTime = System.currentTimeMillis()
            var page = 0
            var reachedHistoryBeginning = false
            val pageLimit = maxPages.coerceIn(1, 100)
            try {
                while (page++ < pageLimit) {
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

                if (reachedHistoryBeginning) {
                    historyStore.markComplete(
                        normalizedSymbol,
                        frame
                    )
                }
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
        val encodedSymbols = URLEncoder.encode(
            JSONArray(coreSymbols).toString(),
            StandardCharsets.UTF_8.name()
        )

        val volumeRows = JSONArray(
            getBody("/api/v3/ticker/24hr?symbols=" + encodedSymbols)
        )
        val volumes = HashMap<String, Double>()
        for (i in 0 until volumeRows.length()) {
            val item = volumeRows.optJSONObject(i) ?: continue
            val symbol = item.optString("symbol").uppercase(Locale.US)
            if (symbol in coreSymbols) {
                volumes[symbol] =
                    item.optString("quoteVolume").toDoubleOrNull() ?: 0.0
            }
        }

        val bookRows = JSONArray(
            getBody("/api/v3/ticker/bookTicker?symbols=" + encodedSymbols)
        )
        val spreads = HashMap<String, Double>()
        for (i in 0 until bookRows.length()) {
            val item = bookRows.optJSONObject(i) ?: continue
            val symbol = item.optString("symbol").uppercase(Locale.US)
            if (symbol !in coreSymbols) continue
            val bid = item.optString("bidPrice").toDoubleOrNull() ?: 0.0
            val ask = item.optString("askPrice").toDoubleOrNull() ?: 0.0
            if (bid > 0.0 && ask >= bid) {
                spreads[symbol] = (ask - bid) / bid
            }
        }

        return Triple(coreSymbols.toList(), volumes, spreads)
    }

    private fun markScanProgress() {
        synchronized(this) {
            scanProgressSymbols += 1
            scanLastProgressAt = System.currentTimeMillis()
        }
    }

    private fun requestScan() {
        // Scanner requires REST market data + history only. WebSockets are
        // realtime acceleration and diagnostics, not a hard scanner dependency.
        if (
            !running ||
            paused ||
            reconcileRequired ||
            killLatched ||
            !historyReady ||
            !restMarketReady
        ) {
            scannerState = when {
                !running -> "STOPPED"
                paused -> "PAUSED"
                reconcileRequired -> "BLOCKED_RECONCILE"
                killLatched -> "BLOCKED_KILL"
                !historyReady -> "WAITING_FOR_HISTORY"
                !restMarketReady -> "WAITING_FOR_REST"
                else -> "WAITING"
            }
            return
        }

        synchronized(this) {
            if (scanning) return
            scanning = true
            scannerError = null
            scanRunId = "scan-" + System.currentTimeMillis()
            scanStartedAt = System.currentTimeMillis()
            scanLastProgressAt = scanStartedAt
            scanProgressSymbols = 0
            scannerState = "RUNNING"
        }

        Thread {
            val startedAt = System.currentTimeMillis()
            try {
                performScan()
                scannerState = "READY"
                scannerError = null
                lastError = null
            } catch (x: Exception) {
                scannerState = "FAILED"
                scannerError = x.javaClass.simpleName + ": " + (x.message ?: "")
                lastError = scannerError
            } finally {
                lastScanDurationMs = System.currentTimeMillis() - startedAt
                lastScanAt = System.currentTimeMillis()
                scanning = false
                scanLastProgressAt = System.currentTimeMillis()
            }
        }.also {
            it.isDaemon = true
            it.name = "williams-scanner"
            it.start()
        }
    }

    private fun performScan() {
        // Exchange reconciliation is performed at START/reconnect. Repeating
        // it for every scanner pass serializes the scanner behind signed REST
        // calls and was a major source of apparent hangs.
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
                        val result = analyseBase(
                            symbol = symbol,
                            candles = workCandles,
                            spread = spreads[symbol] ?: 0.0,
                            volume = volumes[symbol] ?: 0.0
                        )
                        markScanProgress()
                        result
                    } catch (x: Exception) {
                        markScanProgress()
                        scannerError = symbol + ": " +
                            (x.message ?: x.javaClass.simpleName)
                        null
                    }
                }
            )
        }

        val preliminary = mutableListOf<BaseAnalysis>()
        futures.forEach { future ->
            runCatching {
                future.get(30L, TimeUnit.SECONDS)
            }.getOrElse {
                future.cancel(true)
                scannerError = scannerError ?: "scanner task timeout: " + (it.message ?: it.javaClass.simpleName)
                null
            }.let {
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
                        submitOrderIntent(candidate)
                        if (reconcileRequired) return@forEach
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
                    stateMachine.canAdmitEntry(
                        openPositions = positionList().size,
                        maxOpenPositions = maxOpenPositions,
                        hasPendingEntry = synchronized(pendingEntries) {
                            pendingEntries.isNotEmpty()
                        },
                        reconciliationRequired = reconcileRequired,
                        killLatched = killLatched
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

            var ocoCancelled = false
            try {
                if (stored.ocoListId.isNotBlank()) {
                    signedDelete(
                        "/api/v3/orderList",
                        "symbol=" + stored.symbol +
                            "&orderListId=" + stored.ocoListId
                    )
                    ocoCancelled = true
                } else if (stored.ocoListClientId.isNotBlank()) {
                    signedDelete(
                        "/api/v3/orderList",
                        "symbol=" + stored.symbol +
                            "&listClientOrderId=" +
                            stored.ocoListClientId
                    )
                    ocoCancelled = true
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
                if (ocoCancelled) {
                    // OCO cancellation + replacement is not atomic. If the
                    // replacement failed after cancellation, never leave the
                    // position naked: attempt one direct market exit first.
                    val emergency = runCatching {
                        val account = signedAccount()
                        val balances = account.getJSONArray("balances")
                        val asset = stored.symbol.removeSuffix("USDT")
                        var free = 0.0
                        for (i in 0 until balances.length()) {
                            val row = balances.getJSONObject(i)
                            if (row.optString("asset") == asset) {
                                free = row.optString("free").toDoubleOrNull() ?: 0.0
                                break
                            }
                        }
                        val rules = symbolFilters(stored.symbol)
                        val qty = floorStep(min(stored.qty, free), rules.step)
                        require(qty >= rules.minQty) {
                            "Emergency SELL quantity below Binance minimum"
                        }
                        val sell = signedPost(
                            "/api/v3/order",
                            "symbol=" + stored.symbol +
                                "&side=SELL&type=MARKET&quantity=" +
                                fmtQty(qty, rules.decimals)
                        )
                        val exitPrice = sell.optString("cummulativeQuoteQty")
                            .toDoubleOrNull()
                            ?.let { q -> if (qty > 0.0) q / qty else 0.0 }
                            ?.takeIf { it > 0.0 }
                            ?: stored.entry
                        TradeJournal.close(
                            prefs = prefs,
                            symbol = stored.symbol,
                            exitPrice = exitPrice,
                            reason = "EMERGENCY_UNPROTECTED_EXIT",
                            order = sell
                        )
                        recordCompletedTrade(
                            stored = stored,
                            exitPrice = exitPrice,
                            reason = "EMERGENCY_UNPROTECTED_EXIT",
                            raw = sell
                        )
                        synchronized(positions) {
                            positions.remove(stored.symbol)
                        }
                        savePersistedState()
                        stateMachine.force(
                            if (positionList().isEmpty()) {
                                TradingState.READY_FLAT
                            } else {
                                TradingState.PROTECTED
                            },
                            "Emergency exit after failed OCO replacement"
                        )
                        true
                    }.getOrElse {
                        setReconcileRequired(
                            "OCO replacement failed and emergency SELL failed for " +
                                stored.symbol + ": " +
                                (it.message ?: it.javaClass.simpleName)
                        )
                        false
                    }
                    if (emergency) return
                }

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
                    feeBufferPerSidePct * 2.0 +
                    maxSlippagePct,
            ocoClientId = clientId,
            ocoListId = listId
        )
    }

    @Synchronized
    private fun auditManagedOpenOrders() {
        val openOrders = JSONArray(
            signedRawGet("/api/v3/openOrders", "")
        )
        val openLists =
            signedGet("/api/v3/openOrderList", "")
                .let {
                    it.optJSONArray("orderList")
                        ?: it.optJSONArray("ordersLists")
                        ?: it.optJSONArray("orderLists")
                        ?: JSONArray()
                }

        val expectedSymbols =
            pendingEntries.keys.toSet() +
                positionList().map { it.symbol }

        for (i in 0 until openOrders.length()) {
            val order = openOrders.optJSONObject(i) ?: continue
            val clientId = order.optString("clientOrderId")
            if (!clientId.startsWith("W4B_") &&
                !clientId.startsWith("W4S_")
            ) continue

            val symbol =
                order.optString("symbol").uppercase(Locale.US)
            if (symbol !in expectedSymbols) {
                throw IllegalStateException(
                    "Unmanaged Williams open order after restart: " +
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
            if (positionList().none { it.symbol == symbol }) {
                throw IllegalStateException(
                    "Unmanaged Williams OCO after restart: " +
                        symbol + " listClientOrderId=" + listClientId
                )
            }
        }
    }

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
            }.getOrElse {
                throw IllegalStateException(
                    "Cannot verify managed open order lists: " +
                        (it.message ?: it.javaClass.simpleName),
                    it
                )
            }

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
            val response = signedGet(
                "/api/v3/openOrders",
                "symbol=" + symbol
            )
            response.optJSONArray("orders")
                ?: if (response.has("symbol")) {
                    JSONArray().put(response)
                } else JSONArray()
        }.getOrElse {
            throw IllegalStateException(
                "Cannot verify open orders for " + symbol + ": " +
                    (it.message ?: it.javaClass.simpleName),
                it
            )
        }

        val openLists = runCatching {
            signedGet("/api/v3/openOrderList", "")
                .let {
                    it.optJSONArray("orderList")
                        ?: it.optJSONArray("ordersLists")
                        ?: it.optJSONArray("orderLists")
                        ?: JSONArray()
                }
        }.getOrElse {
            throw IllegalStateException(
                "Cannot verify open order lists: " +
                    (it.message ?: it.javaClass.simpleName),
                it
            )
        }

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
        if (positionList().size >= maxOpenPositions) {
            error("Maximum open positions reached")
        }
        if (reconcileRequired) {
            error("RECONCILE_REQUIRED")
        }
        if (killLatched) {
            error("KILL_SWITCH_LATCHED")
        }
        require(marketDataFresh()) {
            "REST market data is not fresh enough for execution"
        }
        // WebSockets are accelerators. REST + exchange-side OCO remain the
        // authoritative safety path when a stream is degraded. The worker
        // performs periodic REST reconciliation while user WS is unavailable.
        require(
            if (gated) {
                stateMachine.state == TradingState.ENTRY_PENDING
            } else {
                stateMachine.state == TradingState.READY_FLAT
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
                feeBufferPerSidePct * 2.0 +
                maxSlippagePct

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

        val takeLimit =
            fmtPrice(
                take - rules.tick,
                rules.tick
            ).toDouble()

        val stopLimit =
            fmtPrice(
                stop * 0.999,
                rules.tick
            ).toDouble()

        if (
            stop >= entry ||
            take <= entry ||
            takeLimit <= entry ||
            takeLimit <= stop
        ) {
            error("Invalid TP/SL relationship")
        }

        validateLimitPrice(symbol, "SELL", takeLimit, rules)
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
                        takeLimit,
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
                    feeBufferPerSidePct * 2.0 +
                    maxSlippagePct,
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

    private fun campaignSignalId(symbol: String, frame: String, type: String, barTimeMs: Long): String =
        symbol.uppercase(Locale.US) + ":" +
            frame.lowercase(Locale.US) + ":" +
            type.uppercase(Locale.US) + ":" +
            barTimeMs

    private fun shiftedSeries(values: List<Double>, shift: Int): List<Double> =
        List(values.size) { i ->
            val source = i - shift
            if (source >= 0) values[source] else Double.NaN
        }

    private fun longCampaignSignals(
        symbol: String,
        candles: List<CandleN>,
        frame: String,
        tick: Double
    ): List<CampaignSignalN> {
        if (candles.size < 45 || tick <= 0.0) return emptyList()

        val medians = candles.map { (it.h + it.l) / 2.0 }
        val jaw = smma(medians, 13)
        val teeth = smma(medians, 8)
        val lips = smma(medians, 5)
        val jawS = shiftedSeries(jaw, 8)
        val teethS = shiftedSeries(teeth, 5)
        val lipsS = shiftedSeries(lips, 3)

        fun bullishAlligator(i: Int): Boolean =
            i in candles.indices &&
                jawS[i].isFinite() &&
                teethS[i].isFinite() &&
                lipsS[i].isFinite() &&
                lipsS[i] > teethS[i] &&
                teethS[i] > jawS[i] &&
                candles[i].c > lipsS[i]

        fun upFractal(i: Int): Boolean {
            if (i < 2 || i + 2 >= candles.size) return false
            return candles[i].h > candles[i - 1].h &&
                candles[i].h > candles[i - 2].h &&
                candles[i].h > candles[i + 1].h &&
                candles[i].h > candles[i + 2].h
        }

        fun downFractal(i: Int): Boolean {
            if (i < 2 || i + 2 >= candles.size) return false
            return candles[i].l < candles[i - 1].l &&
                candles[i].l < candles[i - 2].l &&
                candles[i].l < candles[i + 1].l &&
                candles[i].l < candles[i + 2].l
        }

        fun latestUpFractal(centerLimit: Int): Int? {
            for (i in centerLimit downTo 2) {
                if (upFractal(i)) return i
            }
            return null
        }

        fun latestDownFractal(centerLimit: Int): Int? {
            for (i in centerLimit downTo 2) {
                if (downFractal(i)) return i
            }
            return null
        }

        fun angulationScore(i: Int): Double {
            val start = max(0, i - 4)
            if (i - start < 2 || !jawS[i].isFinite()) return 0.0
            val now = max(0.0, jawS[i] - candles[i].l)
            val then = if (jawS[start].isFinite()) {
                max(0.0, jawS[start] - candles[start].l)
            } else 0.0
            return max(0.0, now - then) / max(candles[i].c, 1e-9) * 100.0
        }

        fun lastValidFractalLevel(at: Int): Pair<Int, Double>? {
            val center = latestUpFractal(max(2, at - 2)) ?: return null
            return center to candles[center].h
        }

        val current = candles.lastIndex
        val out = mutableListOf<CampaignSignalN>()

        // WM1: bullish reversal bar. It is the context bar; execution waits
        // for a break above its high. Countertrend WM1 is intentionally allowed.
        val reversalStart = max(2, candles.lastIndex - 20)
        for (i in candles.lastIndex downTo reversalStart) {
            val priorLow = min(candles[i - 1].l, candles[i - 2].l)
            val range = max(candles[i].h - candles[i].l, 1e-12)
            val closeLocation = (candles[i].c - candles[i].l) / range
            val belowMouth =
                jawS[i].isFinite() &&
                    teethS[i].isFinite() &&
                    lipsS[i].isFinite() &&
                    candles[i].l < min(jawS[i], min(teethS[i], lipsS[i]))
            if (
                candles[i].l < priorLow &&
                closeLocation >= 0.50 &&
                belowMouth &&
                angulationScore(i) > 0.0
            ) {
                val trigger = candles[i].h + tick
                if (candles[current].c < trigger) {
                    out += CampaignSignalN(
                        signalId = campaignSignalId(symbol, frame, "REVERSAL", candles[i].t),
                        type = "REVERSAL",
                        role = "ENTRY",
                        signalBarTimeMs = candles[i].t,
                        triggerPrice = trigger,
                        protectivePrice = candles[i].l - tick,
                        teethAtDetection = teethS[i].takeIf { it.isFinite() } ?: 0.0,
                        invalidationPrice = candles[i].l - tick,
                        reason = "WM1 bullish reversal; waiting above signal-bar high"
                    )
                }
                break
            }
        }

        // WM2: three consecutive rising AO histogram bars, with the previously
        // valid buy-fractal/Balance-Line context still present.
        var streak = 0
        for (i in candles.lastIndex downTo 35) {
            val a = ao(candles, i)
            val p = ao(candles, i - 1)
            if (a > p) streak++ else break
            if (streak == 3) {
                val priorFractalValid = lastValidFractalLevel(i - 1)?.let { (center, level) ->
                    teethS[center + 2].isFinite() && level > teethS[center + 2]
                } ?: false
                if (priorFractalValid) {
                    val trigger = candles[i].h + tick
                    if (candles[current].c < trigger) {
                        out += CampaignSignalN(
                            signalId = campaignSignalId(symbol, frame, "SUPER_AO", candles[i].t),
                            type = "SUPER_AO",
                            role = "ENTRY",
                            signalBarTimeMs = candles[i].t,
                            triggerPrice = trigger,
                            protectivePrice = candles[i].l - tick,
                            teethAtDetection = teethS[i].takeIf { it.isFinite() } ?: 0.0,
                            invalidationPrice = candles[i].l - tick,
                            reason = "WM2 Super AO: third rising AO bar; conditional trigger above price bar"
                        )
                    }
                }
                break
            }
        }

        // WM3: most recent confirmed buy fractal. The signal is persistent,
        // but the trigger is armable only while it remains above current Teeth.
        val fractalCenter = latestUpFractal(candles.lastIndex - 2)
        if (fractalCenter != null) {
            val trigger = candles[fractalCenter].h + tick
            val currentTeeth = teethS[current].takeIf { it.isFinite() } ?: 0.0
            if (candles[current].c < trigger && trigger > currentTeeth) {
                out += CampaignSignalN(
                    signalId = campaignSignalId(symbol, frame, "FRACTAL", candles[fractalCenter].t),
                    type = "FRACTAL",
                    role = "ENTRY",
                    signalBarTimeMs = candles[fractalCenter].t,
                    triggerPrice = trigger,
                    protectivePrice = candles[fractalCenter].l - tick,
                    teethAtDetection = currentTeeth,
                    invalidationPrice = candles[fractalCenter].l - tick,
                    reason = "WM3 confirmed buy fractal; trigger must remain above Teeth"
                )
            }
        }

        // Keep at most one signal per family, ordered by formation time. The
        // campaign engine chooses the earliest still-valid first-entry signal.
        return out.distinctBy { it.signalId }.sortedBy { it.signalBarTimeMs }
    }

    private fun analyseBase(
        symbol: String,
        candles: List<CandleN>,
        spread: Double,
        volume: Double
    ): BaseAnalysis {
        val workCandles =
            if (campaignEngineEnabled &&
                campaignExecutionTimeframe != interval
            ) {
                runCatching {
                    fetchCandles(symbol, campaignExecutionTimeframe, 150)
                }.getOrElse { candles }
            } else {
                candles
            }
        val i = workCandles.lastIndex
        if (i < 40) {
            return BaseAnalysis(
                symbol = symbol,
                candles = workCandles,
                score = 0.0,
                signal = false,
                htfCandidate = false,
                wave = neutralWave(candles),
                atrPct = 0.0,
                riskPct = 0.0,
                riskReward = 0.0,
                spreadPct = spread,
                breakoutDistancePct = 0.0,
                obi = null,
                tradeFlowImbalance = null,
                reason = "Недостаточно свечей: " + candles.size + "/40"
            )
        }

        val closes = workCandles.map { it.c }
        val atrPct = atrPct(workCandles)
        val atrAbs =
            atrAbs(workCandles)

        val campaignRules = runCatching { symbolFilters(symbol) }.getOrNull()
        val campaignTick = campaignRules?.tick ?: 0.0
        val campaignSignals = if (campaignEngineEnabled && campaignTick > 0.0) {
            longCampaignSignals(
                symbol = symbol,
                candles = workCandles,
                frame = campaignExecutionTimeframe,
                tick = campaignTick
            )
        } else {
            emptyList()
        }

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
            fractalHigh?.let { fractal ->
                externalFractal &&
                    closes[i] > fractal &&
                    breakoutDistance <= 0.05
            } ?: false

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

        val preliminaryWave = waveInfo(workCandles, campaignExecutionTimeframe)
        var score =
            trendScore +
                aoScore +
                breakoutScore +
                atrScore +
                volumeScore

        var strictSignal =
            if (campaignEngineEnabled) {
                campaignSignals.isNotEmpty() &&
                    atrPct in 0.0..0.08 &&
                    spread <= maxSpreadPct
            } else {
                bullish &&
                    aoPositive &&
                    breakout &&
                    atrPct in 0.0..0.08
            }

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
                campaignEngineEnabled && campaignSignals.isNotEmpty() ->
                    "Williams campaign: " +
                        campaignSignals.joinToString("+") { it.type } +
                        "; conditional entry"
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
            obi = obi,
            tradeFlowImbalance = flowImbalance,
            reason = reason,
            campaignSignals = campaignSignals,
            campaignReady = campaignEngineEnabled && campaignSignals.isNotEmpty()
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
        // MTF enrichment reads the already-warmed execution cache. The
        // scanner must never trigger full-history reconstruction itself.
        val mtfFrames = listOf("5m", "15m", "30m", "1h", "4h", "1d")
        for (frame in mtfFrames) {
            runCatching { fetchCandles(symbol, frame, 150) }
                .getOrNull()?.takeIf { it.size >= 40 }
                ?.let { frames.add(waveInfo(it, frame)) }
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
            if (junior.aoBearishDivergence) bonus -= 12.0
            if (junior.aoBullishDivergence) bonus += 4.0
        }

        var score = (baseCandidate.score + bonus).coerceIn(0.0, 100.0)

        // Entry is no longer tied to the 1h candle's breakout. We enter on the
        // lower-TF Wave 3 after higher-TF context confirms the direction.
        val finalSignal =
            if (campaignEngineEnabled) {
                baseCandidate.campaignSignals.isNotEmpty() &&
                    baseCandidate.atrPct <= 0.08 &&
                    baseCandidate.spreadPct <= 0.0015
            } else {
                entrySignal &&
                    baseCandidate.atrPct <= 0.08 &&
                    baseCandidate.spreadPct <= 0.0015 &&
                    baseCandidate.riskReward >= 1.5
            }

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
            reason = reason,
            campaignReady = campaignEngineEnabled && baseCandidate.campaignSignals.isNotEmpty()
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
            .put("campaign_engine", campaignEngineEnabled)
            .put("campaign_ready", candidate.campaignReady)
            .put(
                "campaign_signals",
                JSONArray().apply {
                    candidate.campaignSignals.forEach { s ->
                        put(
                            JSONObject()
                                .put("signal_id", s.signalId)
                                .put("type", s.type)
                                .put("role", s.role)
                                .put("signal_bar_time_ms", s.signalBarTimeMs)
                                .put("trigger_price", s.triggerPrice)
                                .put("protective_price", s.protectivePrice)
                                .put("teeth_at_detection", s.teethAtDetection)
                                .put("invalidation_price", s.invalidationPrice)
                                .put("reason", s.reason)
                        )
                    }
                }
            )
            .put(
                "entry_signal_type",
                candidate.campaignSignals.firstOrNull()?.type ?: JSONObject.NULL
            )
            .put(
                "entry_trigger_price",
                candidate.campaignSignals.firstOrNull()?.triggerPrice ?: JSONObject.NULL
            )
            .put(
                "entry_protective_price",
                candidate.campaignSignals.firstOrNull()?.protectivePrice ?: JSONObject.NULL
            )
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

    private fun startRestDataProbe() {
        if (restProbeRunning) return
        restProbeRunning = true
        restProbeThread = Thread {
            var lastAccountProbe = 0L
            while (restProbeRunning) {
                val started = System.currentTimeMillis()
                try {
                    val encodedSymbols = URLEncoder.encode(
                        JSONArray(coreSymbols).toString(),
                        StandardCharsets.UTF_8.name()
                    )
                    val rows = JSONArray(
                        getBody("/api/v3/ticker/price?symbols=" + encodedSymbols)
                    )
                    var found = 0
                    for (i in 0 until rows.length()) {
                        val row = rows.optJSONObject(i) ?: continue
                        val symbol = row.optString("symbol").uppercase(Locale.US)
                        val price = row.optString("price").toDoubleOrNull() ?: 0.0
                        if (symbol in coreSymbols && price > 0.0) {
                            livePrices[symbol] = price
                            if (symbol == primarySymbol) restLastTickerPrice = price
                            found++
                        }
                    }
                    restMarketReady = found == coreSymbols.size
                    restLastSuccessMs = System.currentTimeMillis()
                    restLastLatencyMs = System.currentTimeMillis() - started
                    if (!restMarketReady) {
                        restLastError = "ticker returned " + found + "/" + coreSymbols.size + " symbols"
                    }

                    if (key().isNotBlank() && secret().isNotBlank() &&
                        System.currentTimeMillis() - lastAccountProbe >= 15_000L
                    ) {
                        lastAccountProbe = System.currentTimeMillis()
                        val balances = signedAccount().optJSONArray("balances") ?: JSONArray()
                        var usdt = 0.0
                        var foundUsdt = false
                        for (i in 0 until balances.length()) {
                            val row = balances.optJSONObject(i) ?: continue
                            if (row.optString("asset") == "USDT") {
                                usdt = row.optString("free").toDoubleOrNull() ?: 0.0
                                foundUsdt = true
                            }
                        }
                        liveUsdtBalance = usdt
                        restAccountReady = foundUsdt
                    } else if (key().isBlank() || secret().isBlank()) {
                        restAccountReady = false
                    }
                } catch (x: Exception) {
                    restLastError = x.javaClass.simpleName + ": " + (x.message ?: "")
                    restLastLatencyMs = System.currentTimeMillis() - started
                    restMarketReady = false
                    if (key().isNotBlank() && secret().isNotBlank()) restAccountReady = false
                }

                try { Thread.sleep(5_000L) }
                catch (_: InterruptedException) { break }
            }
        }.apply {
            isDaemon = true
            name = "williams-rest-probe"
            start()
        }
    }

    private fun scannerSnapshot(): JSONObject =
        JSONObject()
            .put("version", BuildConfig.VERSION_NAME)
            .put("cached", true)
            .put("scanning", scanning)
            .put("scanner_state", scannerState)
            .put("scanner_error", scannerError ?: JSONObject.NULL)
            .put("run_id", scanRunId)
            .put("progress_symbols", scanProgressSymbols)
            .put("progress_total", scanSymbols.size)
            .put("last_progress_at", scanLastProgressAt)
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

    fun status(fast: Boolean = false): JSONObject {
        if (!fast && primaryCandles.isEmpty()) {
            runCatching {
                primaryCandles =
                    fetchCandles(primarySymbol, interval, 150)
            }.onFailure {
                lastError =
                    it.message ?: it.javaClass.simpleName
            }
        }

        var balance: Double? = liveUsdtBalance.takeIf { it > 0.0 }
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

        if (!fast && key().isNotBlank() && secret().isNotBlank() && !restAccountReady) {
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
            .put("recovered", true)
            .put("state", stateName())
            .put("execution_enabled", executionReady())
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
            .put("history_state", historyState)
            .put("history_last_error", historyLastError ?: JSONObject.NULL)
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
            .put("scanner_run_id", scanRunId)
            .put("scanner_symbols", lastSymbolsScanned)
            .put("scanner_progress_symbols", scanProgressSymbols)
            .put("scanner_last_scan_at", lastScanAt)
            .put("scanner_started_at", scanStartedAt)
            .put("scanner_last_progress_at", scanLastProgressAt)
            .put("scanner_duration_ms", lastScanDurationMs)
            .put("rest_market_ready", restMarketReady)
            .put("rest_account_ready", restAccountReady)
            .put("rest_last_success_ms", restLastSuccessMs)
            .put("rest_last_latency_ms", restLastLatencyMs)
            .put("rest_last_error", restLastError ?: JSONObject.NULL)
            .put("rest_ticker_price", if (restLastTickerPrice > 0.0) restLastTickerPrice else JSONObject.NULL)
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

    fun marketTickers(): JSONObject {
        val encodedSymbols = URLEncoder.encode(
            JSONArray(coreSymbols).toString(),
            StandardCharsets.UTF_8.name()
        )
        val rows = JSONArray(
            getBody("/api/v3/ticker/price?symbols=" + encodedSymbols)
        )
        val priceMap = HashMap<String, Double>()
        for (i in 0 until rows.length()) {
            val item = rows.optJSONObject(i) ?: continue
            val symbol = item.optString("symbol").uppercase(Locale.US)
            if (symbol !in coreSymbols) continue
            val price = item.optString("price").toDoubleOrNull() ?: continue
            if (price > 0.0) priceMap[symbol] = price
        }

        val pairs = JSONArray()
        for (symbol in coreSymbols) {
            pairs.put(
                JSONObject()
                    .put("symbol", symbol)
                    .put(
                        "price",
                        livePrices[symbol] ?: priceMap[symbol] ?: JSONObject.NULL
                    )
            )
        }
        return JSONObject()
            .put("symbols", JSONArray(coreSymbols))
            .put("pairs", pairs)
    }

    fun portfolio(): JSONObject {
        val configured = key().isNotBlank() && secret().isNotBlank()
        val statusJson = status()
        val positions = JSONArray()
        val sourcePositions = statusJson.optJSONArray("positions") ?: JSONArray()
        var totalPositionValue = 0.0
        for (i in 0 until sourcePositions.length()) {
            val p = sourcePositions.optJSONObject(i) ?: continue
            val symbol = p.optString("symbol", "")
            val qty = p.optDouble("qty", 0.0)
            val entry = p.optDouble("entry", p.optDouble("avg_entry_price", 0.0))
            val current = statusJson.optJSONObject("live_prices")
                ?.optDouble(symbol, 0.0)
                ?.takeIf { it > 0.0 }
                ?: entry
            val value = qty * current
            totalPositionValue += value
            positions.put(
                JSONObject()
                    .put("symbol", symbol)
                    .put("qty", qty)
                    .put("avg_entry_price", entry)
                    .put("stop_loss", p.optDouble("stop", 0.0))
                    .put("take_profit", p.optDouble("take", 0.0))
                    .put("risk_pct", p.optDouble("risk_pct", 0.0))
                    .put("current_price", current)
                    .put("position_value_usdt", value)
                    .put("allocation_pct", 0.0)
                    .put("unrealized_pnl_usdt", (current - entry) * qty)
                    .put("unrealized_pnl_pct", if (entry > 0.0) (current - entry) / entry else 0.0)
                    .put("oco_list_id", p.optString("oco_list_id", ""))
                    .put("oco_list_client_id", p.optString("oco_list_client_id", ""))
            )
        }

        val assets = JSONArray()
        var totalEquity = statusJson.optDouble("quote_balance", 0.0)
        if (configured) {
            runCatching {
                val balances = signedAccount().getJSONArray("balances")
                for (i in 0 until balances.length()) {
                    val b = balances.getJSONObject(i)
                    val free = b.optString("free").toDoubleOrNull() ?: 0.0
                    val locked = b.optString("locked").toDoubleOrNull() ?: 0.0
                    val total = free + locked
                    if (total <= 0.0) continue
                    val asset = b.optString("asset")
                    if (asset == "USDT") {
                        totalEquity = total
                    }
                    assets.put(
                        JSONObject()
                            .put("asset", asset)
                            .put("free", free)
                            .put("locked", locked)
                            .put("total", total)
                            .put("price_usdt", if (asset == "USDT") 1.0 else JSONObject.NULL)
                            .put("value_usdt", if (asset == "USDT") total else 0.0)
                            .put("allocation_pct", 0.0)
                    )
                }
            }
        }

        val freeEquity = statusJson.optDouble("quote_balance", totalEquity)
        return JSONObject()
            .put("configured", configured)
            .put("total_equity_usdt", totalEquity + totalPositionValue)
            .put("free_equity_usdt", freeEquity)
            .put("locked_equity_usdt", 0.0)
            .put("realized_pnl_usdt", 0.0)
            .put("unrealized_pnl_usdt", statusJson.optDouble("pnl", 0.0))
            .put("assets", assets)
            .put("positions", positions)
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

    private fun diagnosticSelfTests(): JSONArray {
        val tests = JSONArray()
        fun test(name: String, domain: String, ok: Boolean, failureSeverity: String, detail: String) {
            tests.put(
                JSONObject()
                    .put("name", name)
                    .put("domain", domain)
                    .put("ok", ok)
                    .put("severity", if (ok) "PASS" else failureSeverity)
                    .put("detail", detail)
            )
        }
        test("version_consistency", "runtime", BuildConfig.VERSION_NAME.isNotBlank(), "FAIL", "app_version=" + BuildConfig.VERSION_NAME)
        test("testnet_enabled", "binance", true, "FAIL", "Standalone runtime uses Binance Spot Testnet")
        test("local_api_loopback", "runtime", 18080 == 18080, "FAIL", "127.0.0.1:18080")
        test("execution_gate", "execution", executionReady(), "FAIL", "execution_ready=" + executionReady() + "; blockers=" + executionBlockers().joinToString(","))
        test("runtime_lifecycle", "runtime", running == (worker?.isAlive == true), "FAIL", "running=" + running + "; worker_alive=" + (worker?.isAlive == true))
        test("history_lifecycle", "database", historyReady || historyState in setOf("IDLE","LOADING","WAITING_RETRY","STOPPED"), "FAIL", "state=" + historyState + "; active_tasks=" + activeHistoryTasks.size)
        test("credentials_state", "binance", key().isNotBlank() && secret().isNotBlank(), "WARN", if (key().isNotBlank() && secret().isNotBlank()) "Binance credentials configured" else "Binance credentials not configured")
        test("rest_market_data", "binance", restMarketReady, "FAIL", if (restMarketReady) "Ticker REST OK; price=" + restLastTickerPrice + "; latency_ms=" + restLastLatencyMs else "REST market failed: " + (restLastError ?: "pending"))
        test("rest_account", "binance", restAccountReady, "WARN", if (restAccountReady) "Signed account OK; USDT=" + liveUsdtBalance else "Account REST failed: " + (restLastError ?: "pending"))
        test("market_websocket", "websocket", marketSocketConnected, "WARN", if (marketSocketConnected) "Market stream connected" else "Market stream disconnected; REST fallback active")
        test("user_websocket", "websocket", userStreamConnected && !userStreamSyncRequired, "WARN", if (userStreamConnected && !userStreamSyncRequired) "User stream connected and synchronized" else "User stream degraded; REST reconciliation active")
        test("history_ready", "database", historyReady, "WARN", if (historyReady) "Market history is ready" else "history_state=" + historyState + "; error=" + (historyLastError ?: "pending"))
        test("scanner_state", "scanner", scannerState == "READY" || (scannerState == "RUNNING" && scanning), "WARN", "state=" + scannerState + "; run_id=" + scanRunId + "; progress=" + scanProgressSymbols + "/" + scanSymbols.size + "; last_progress_ms=" + scanLastProgressAt)
        test("state_machine", "runtime", stateMachine.state.name.isNotBlank(), "FAIL", "fsm_state=" + stateMachine.state.name)
        test("risk_limits", "risk", maxRiskPerTradePct > 0.0 && maxRiskPerTradePct <= 0.005 && maxTotalRiskPct <= 0.01, "FAIL", "per_trade=" + maxRiskPerTradePct + "; total=" + maxTotalRiskPct)
        test("self_heal_contract", "runtime", true, "FAIL", "safe_only=true; endpoint=/api/v1/control/self-heal")
        return tests
    }

    fun diagnostics(): JSONObject {
        // Diagnostics stay local and responsive even when market/history/scanner
        // network work is slow or blocked.
        val tests = diagnosticSelfTests()
        return JSONObject()
            .put("runtime", "standalone")
            .put("device_local_api", "http://127.0.0.1:18080")
            .put("binance_testnet", true)
            .put("status", status(fast = true))
            .put("settings", settings())
            .put("system_info", JSONObject()
                .put("runtime", "standalone")
                .put("app_version", BuildConfig.VERSION_NAME)
                .put("android_sdk", android.os.Build.VERSION.SDK_INT)
                .put("device_model", android.os.Build.MODEL)
                .put("manufacturer", android.os.Build.MANUFACTURER))
            .put("configuration_sanitized", settings())
            .put("tests", tests)
            .put("self_tests", tests)
            .put("diagnostic_contract_version", 6)
            .put("execution_gate", JSONObject()
                .put("ready", executionReady())
                .put("reasons", JSONArray(executionBlockers()))
                .put("degraded_channels", JSONArray().apply {
                    if (!marketSocketConnected) put("market_ws")
                    if (!userStreamConnected || userStreamSyncRequired) put("user_ws")
                }))
            .put("watchdog", JSONObject()
                .put("scanner_stalled", scanning && scanLastProgressAt > 0L &&
                    System.currentTimeMillis() - scanLastProgressAt > 60_000L)
                .put("scanner_progress_age_ms",
                    if (scanLastProgressAt == 0L) 0L else System.currentTimeMillis() - scanLastProgressAt)
                .put("history_state", historyState)
                .put("history_last_error", historyLastError ?: JSONObject.NULL)
                .put("runtime_generation", runtimeGeneration)
                .put("self_heal_count", selfHealCount)
                .put("last_self_heal_at", lastSelfHealMs)
                .put("history_active_tasks", activeHistoryTasks.size))
    }

    fun selfHeal(): JSONObject {
        val before = status(fast = true)
        val actions = JSONArray()
        val reasons = JSONArray(executionBlockers())

        if (killLatched || reconcileRequired) {
            actions.put("blocked_by_safety_barrier")
            return JSONObject()
                .put("changed", false)
                .put("safe_only", true)
                .put("actions", actions)
                .put("execution_ready", executionReady())
                .put("execution_blockers", reasons)
                .put("before", before)
                .put("after", before)
        }

        lastSelfHealMs = System.currentTimeMillis()
        selfHealCount++

        if (running) {
            if (!historyReady && !historyWarmupRunning) {
                historyState = "RECOVERY_REQUESTED"
                warmCoreHistoryAsync(runtimeGeneration)
                actions.put("history_reload_requested")
            }
            if (marketSocket == null || !marketSocketConnected) {
                startMarketDataStream()
                actions.put("market_ws_reconnect_requested")
            }
            if (!userStreamConnected) {
                userStream.start()
                actions.put("user_ws_reconnect_requested")
            }
            if (historyReady && !scanning) {
                requestScan()
                actions.put("scanner_reconnect_requested")
            }
            if (
                !userStreamConnected &&
                positionList().isNotEmpty() &&
                System.currentTimeMillis() - lastRestReconcileMs >= 60_000L
            ) {
                lastRestReconcileMs = System.currentTimeMillis()
                runCatching { reconcilePositionsWithExchange() }
                    .onSuccess { actions.put("rest_reconciliation") }
                    .onFailure { actions.put("rest_reconciliation_failed") }
            }
        } else {
            actions.put("runtime_not_started")
        }

        val after = status(fast = true)
        return JSONObject()
            .put("changed", actions.length() > 0)
            .put("safe_only", true)
            .put("actions", actions)
            .put("execution_ready", executionReady())
            .put("execution_blockers", JSONArray(executionBlockers()))
            .put("before", before)
            .put("after", after)
    }

    fun settings(): JSONObject =
        JSONObject()
            .put("version", BuildConfig.VERSION_NAME)
            .put("campaign_engine", campaignEngineEnabled)
            .put("campaign_execution_timeframe", campaignExecutionTimeframe)
            .put("campaign_entry_mode", "CONDITIONAL_STOP")
            .put("campaign_fixed_take_profit", false)
            .put("campaign_risk_limit_pct", campaignRiskLimitPct)
            .put("campaign_initial_risk_pct", campaignInitialRiskPct)
            .put("campaign_add_on_risk_cap_pct", campaignAddRiskCapPct)
            .put("campaign_trail_bars", campaignTrailBars)
            .put("symbol", primarySymbol)
            .put("interval", interval)
            .put("position_fraction", 0.95)
            .put("stop_loss_pct", 0.02)
            .put("take_profit_pct", 0.04)
            .put("poll_seconds", 10)
            .put("scan_mode", "adaptive_parallel_cached")
            .put("wave_timeframes", analysisFrames.joinToString(","))
            .put("realtime_multi_timeframe_stream", false)
            .put("market_stream_connected", marketSocketConnected)
            .put("core_symbols", coreSymbols.joinToString(","))
            .put("full_history_wave_analysis", false)
            .put("history_retention_candles", 6000)
            .put("full_history_base_timeframe", "1h")
            .put("startup_history_frames", startupFrames.joinToString(","))
            .put("testnet_live_execution", true)
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
            .put("execution_enabled", executionReady())
            .put("max_scan_symbols", maxScanSymbols)
            .put("scanner_universe", scannerUniverseLabel)
            .put("liquidity_preselect", maxScanSymbols)
            .put("deep_wave_targets", waveTopN)
            .put("scanner_strategy", "liquidity -> base -> deep MTF/Waves -> risk -> score")
            .put("wave_top_n", waveTopN)
            .put("trade_journal", true)
            .put("trade_journal_max_rows", 500)
}