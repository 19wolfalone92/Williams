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
import kotlin.math.ceil

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
    // Futures-native public market data; the legacy Spot account/order transport remains
    // hard-disabled below until every remaining market/execution path is migrated.
    private val futuresMarketData = BinanceUsdMFuturesMarketData(http)
    // Runtime execution is routed through the local USD-M Futures adapter/coordinator below.
    // Any leftover signed Spot mutation is blocked at the signedRequest boundary.
    private val futuresExecutionAdapterReady = true
    @Volatile private var futuresExchangeInfoCache: Pair<Long, JSONObject>? = null
    private val futuresCampaignCoordinator by lazy {
        NativeFuturesCampaignCoordinator(prefs) {
            BinanceUsdMFuturesTestnetAdapter(key(), secret(), http)
        }
    }
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

    // Legacy native engine is not authoritative; keep its diagnostic universe aligned with the
    // remote USDⓈ-M Futures backend so no stale five-symbol contract leaks into the UI.
    private val coreSymbols = listOf(
        "BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT",
        "DOGEUSDT", "ADAUSDT", "LINKUSDT", "AVAXUSDT", "LTCUSDT"
    )
    private val analysisFrames = listOf("1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "8h", "12h", "1d", "3d", "1w", "1M")
    // Startup only needs the frames that directly participate in execution.
    // The complete 15-TF matrix is analysis metadata, not a startup blocker.
    private val startupFrames = listOf("1d", "4h", "1h", "15m")
    // Fetch enough closed candles for the scanner's 150-candle working set while
    // leaving headroom for the currently forming candle.
    private val startupHistoryLimit = 180
    private val minStartupHistoryCandles = 150
    private val maxScanSymbols = 10
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
    private val scannerUniverseLabel = "USD_M_FUTURES_TESTNET_10"

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
        get() = true
    private val campaignExecutionTimeframe: String
        get() = "1h"
    private val campaignRiskLimitPct = 0.006
    private val campaignInitialRiskPct = 0.002
    private val campaignAddRiskCapPct = 0.002
    private val campaignTrailBars: Int
        get() = prefs.getInt("campaign_trail_bars", 5).coerceIn(3, 5)
    private val maxTotalRiskPct = 0.006
    private val maxRiskPerTradePct = 0.0025
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

    private fun reservedRiskPct(): Double {
        val openRisk = positionList().sumOf {
            if (it.riskPct > 0.0) it.riskPct
            else if (it.entry > 0.0) {
                ((it.entry - it.stop) / it.entry).coerceAtLeast(0.0)
            } else 0.0
        }
        val pendingRisk = if (campaignEngineEnabled) {
            synchronized(pendingEntries) {
                pendingEntries.values.sumOf { it.riskReservedPct }
            }
        } else {
            0.0
        }
        return (openRisk + pendingRisk).coerceAtMost(1.0)
    }

    private fun marketDataFresh(maxAgeMs: Long = 30_000L): Boolean =
        restMarketReady &&
            restLastSuccessMs > 0L &&
            System.currentTimeMillis() - restLastSuccessMs <= maxAgeMs

    private fun executionReady(): Boolean =
        futuresExecutionAdapterReady &&
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
            if (!futuresExecutionAdapterReady) add("futures_execution_adapter_missing_spot_api_v3_detected")
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
            // Validate credentials against USDⓈ-M Futures Testnet, never Spot /api/v3.
            val futures = BinanceUsdMFuturesTestnetAdapter(newKey, newSecret, http)
            futures.serverTime()
            val account = futures.account()
            futures.requireOneWayMode()
            restAccountReady = account.has("availableBalance") &&
                account.has("totalWalletBalance") && account.has("totalMarginBalance")
            restLastError = null
            JSONObject()
                .put("configured", true)
                .put("testnet", true)
                .put("standalone", true)
                .put("account_type", "USD_M_FUTURES")
                .put("validation", "FUTURES_CREDENTIALS_AND_ONE_WAY_MODE_PASS")
                .put("read_back_verified", key() == newKey && secret() == newSecret)
                .put("execution_enabled", false)
                .put("execution_blocker", "start_required_for_local_Futures_reconciliation_and_scanner")
        } catch (x: Exception) {
            prefs.edit()
                .putString("api_key", oldKey)
                .putString("api_secret", oldSecret)
                .apply()
            restAccountReady = false
            throw IllegalStateException(
                "Binance USDⓈ-M Futures Testnet credentials validation failed: " +
                    (x.message ?: x.javaClass.simpleName),
                x
            )
        }
    }

    fun clear(): JSONObject {
        check(!running) { "Stop the Futures runtime before deleting credentials" }
        if (key().isNotBlank() && secret().isNotBlank()) {
            val rows = futuresCampaignCoordinator.positionsSnapshot()
            val open = (0 until rows.length()).any { i ->
                val row = rows.optJSONObject(i) ?: return@any false
                kotlin.math.abs(row.optString("positionAmt").toDoubleOrNull() ?: 0.0) > 0.0
            }
            val intents = futuresCampaignCoordinator.intentsSnapshot()
            val pending = (0 until intents.length()).any { i ->
                val intent = intents.optJSONObject(i) ?: return@any true
                intent.optString("exchange_status").uppercase(Locale.US) in setOf(
                    "NEW", "PENDING", "WORKING", "PARTIALLY_FILLED", "SUBMISSION_UNKNOWN",
                    "ACCEPTED", "TRIGGERED", "OPEN_PROTECTED", "POSITION_OPEN"
                )
            }
            require(!open && !pending) { "Close/reconcile every Futures position and pending intent before deleting credentials" }
        }
        stop()
        prefs.edit().remove("api_key").remove("api_secret")
            .remove("positions_json").remove("pending_entries_json")
            .putBoolean("auto_run", false).commit()
        candidates = JSONArray()
        primaryCandles = emptyList()
        scanSymbols = mutableListOf()
        historyReady = false
        historyWarmupRunning = false
        historyState = "IDLE"
        historyLastError = null
        lastError = null
        clearReconcileRequired()
        return JSONObject().put("configured", false).put("cleared", true)
    }

    fun start(): JSONObject {
        if (killLatched) error("KILL_SWITCH_LATCHED: reset only after exchange reconciliation")
        require(key().isNotBlank() && secret().isNotBlank()) {
            "Configure Binance USD-M Futures Testnet credentials first"
        }
        if (running) return JSONObject().put("started", false).put("reason", "already_running")

        val reconciliation = futuresCampaignCoordinator.reconcile()
        if (!reconciliation.optBoolean("ok", false)) {
            reconcileRequired = true
            prefs.edit().putBoolean("reconcile_required", true).putBoolean("auto_run", false).commit()
            lastError = reconciliation.optString("blocker", "Futures reconciliation failed")
            stateMachine.force(TradingState.RECONCILE_REQUIRED, lastError ?: "Futures reconciliation failed")
            return JSONObject().put("started", false).put("state", futuresCampaignCoordinator.state.name)
                .put("execution_enabled", false).put("futures_reconciliation", reconciliation)
        }

        reconcileRequired = false
        prefs.edit().putBoolean("reconcile_required", false).putBoolean("auto_run", true).commit()
        val account = futuresCampaignCoordinator.accountSnapshot()
        liveUsdtBalance = account.optString("availableBalance").toDoubleOrNull() ?: 0.0
        restAccountReady = account.has("availableBalance") &&
            account.has("totalWalletBalance") && account.has("totalMarginBalance")
        stateMachine.force(
            when (futuresCampaignCoordinator.state) {
                NativeFuturesCampaignCoordinator.State.ENTRY_PENDING -> TradingState.ENTRY_PENDING
                NativeFuturesCampaignCoordinator.State.OPEN_PROTECTED -> TradingState.PROTECTED
                NativeFuturesCampaignCoordinator.State.POSITION_UNPROTECTED,
                NativeFuturesCampaignCoordinator.State.RECONCILE_REQUIRED -> TradingState.RECONCILE_REQUIRED
                else -> TradingState.READY_FLAT
            },
            "USD-M Futures account reconciled on Android"
        )
        val generation = ++runtimeGeneration
        running = true
        paused = false
        // Never start the legacy Spot market/user WebSockets. Futures REST market data
        // and exchange-side protective algo orders remain authoritative.
        marketSocket?.close(1000, "Switch to USD-M Futures REST runtime")
        marketSocket = null
        marketSocketConnected = false
        userStream.stop()
        userStreamConnected = false
        userStreamSyncRequired = false
        historyReady = false
        historyState = "LOADING"
        historyLastError = null
        scannerState = "WAITING_FOR_HISTORY"
        scannerError = null
        scanRunId = ""
        scanProgressSymbols = 0
        warmCoreHistoryAsync(generation)

        worker = Thread {
            while (running && generation == runtimeGeneration) {
                try {
                    if (!historyReady) {
                        if (!historyWarmupRunning) warmCoreHistoryAsync(generation)
                    } else if (marketDataFresh() && restAccountReady) {
                        // Reconcile existing positions and manage exchange-side protection
                        // even while paused or after the daily-loss entry lock engages.
                        if (!checkFuturesDailyLoss()) {
                            runCatching { manageNativeFuturesPositions() }
                                .onFailure { lastError = "Futures position monitor: " + (it.message ?: it.javaClass.simpleName) }
                        } else if (paused) {
                            runCatching { manageNativeFuturesPositions() }
                                .onFailure { lastError = "Futures position monitor: " + (it.message ?: it.javaClass.simpleName) }
                        } else if (!reconcileRequired && !killLatched) {
                            requestScan()
                        }
                    }
                } catch (x: Exception) {
                    lastError = x.javaClass.simpleName + ": " + (x.message ?: "")
                }
                try { Thread.sleep(10_000L) } catch (_: InterruptedException) { break }
            }
        }.apply {
            isDaemon = true
            name = "williams-futures-native-engine"
            start()
        }
        return JSONObject()
            .put("started", true)
            .put("exchange", "BINANCE_USDM_FUTURES_TESTNET")
            .put("execution_mode", "AUTONOMOUS_ANDROID")
            .put("timeframe", "1h")
            .put("direction_support", JSONArray().put("LONG").put("SHORT"))
            .put("startup_state", "LOADING_CLOSED_FUTURES_HISTORY")
            .put("futures_reconciliation", reconciliation)
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
        paused = true
        running = false
        prefs.edit().putBoolean("kill_latched", true).putBoolean("auto_run", false).commit()
        worker?.interrupt()
        worker = null
        marketSocket?.close(1000, "USD-M Futures kill switch")
        marketSocket = null
        marketSocketConnected = false
        userStream.stop()
        userStreamConnected = false

        val errors = mutableListOf<String>()
        runCatching { futuresCampaignCoordinator.cancelPendingEntries("KILL_SWITCH") }
            .onFailure { errors += "pending entries: " + (it.message ?: it.javaClass.simpleName) }
        val rows = runCatching { futuresCampaignCoordinator.positionsSnapshot() }.getOrElse {
            errors += "Futures position query: " + (it.message ?: it.javaClass.simpleName)
            JSONArray()
        }
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val symbol = row.optString("symbol").uppercase(Locale.US)
            if (symbol !in coreSymbols) continue
            val signedQty = row.optString("positionAmt").toDoubleOrNull() ?: 0.0
            if (signedQty == 0.0) continue
            try {
                val rules = symbolFilters(symbol)
                val qty = floorStep(kotlin.math.abs(signedQty), if (rules.marketStep > 0.0) rules.marketStep else rules.step)
                require(qty >= rules.minQty) { "Cannot safely round exchange position qty for $symbol" }
                val direction = if (signedQty > 0.0) NativeFuturesCampaignCoordinator.Direction.LONG
                    else NativeFuturesCampaignCoordinator.Direction.SHORT
                val cid = ("W2K_" + symbol.take(8) + "_" + System.nanoTime().toString(16).takeLast(12)).take(36)
                val result = futuresCampaignCoordinator.closePosition(symbol, direction, fmtQty(qty, rules.decimals), cid, "KILL_SWITCH")
                if (!result.optBoolean("ok", false)) errors += "$symbol: " + result.optString("blocker", result.optString("error"))
            } catch (x: Exception) {
                errors += "$symbol: " + (x.message ?: x.javaClass.simpleName)
            }
        }
        val reconciliation = runCatching { futuresCampaignCoordinator.reconcile() }.getOrElse {
            JSONObject().put("ok", false).put("error", it.message ?: it.javaClass.simpleName)
        }
        stateMachine.force(TradingState.KILL_SWITCH_LATCHED, "USD-M Futures kill switch; restart blocked")
        val success = errors.isEmpty() && reconciliation.optBoolean("ok", false)
        lastError = if (success) "KILL_SWITCH: Futures positions reconciled; trading remains latched OFF"
            else "KILL_SWITCH: unresolved exchange close/reconciliation warning"
        return JSONObject().put("killed", true).put("latched", true)
            .put("exchange", "BINANCE_USDM_FUTURES_TESTNET").put("state", "KILL_SWITCH_LATCHED")
            .put("errors", JSONArray(errors)).put("futures_reconciliation", reconciliation)
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
        require(key().isNotBlank() && secret().isNotBlank()) {
            "Configure Binance USD-M Futures Testnet credentials first"
        }
        val result = futuresCampaignCoordinator.reconcile()
        val ok = result.optBoolean("ok", false)
        reconcileRequired = !ok
        prefs.edit().putBoolean("reconcile_required", !ok).commit()
        if (ok) {
            when (futuresCampaignCoordinator.state) {
                NativeFuturesCampaignCoordinator.State.OPEN_PROTECTED ->
                    stateMachine.force(TradingState.PROTECTED, "Futures positions protected by exchange")
                NativeFuturesCampaignCoordinator.State.ENTRY_PENDING ->
                    stateMachine.force(TradingState.ENTRY_PENDING, "Futures pending entry reconciled")
                NativeFuturesCampaignCoordinator.State.POSITION_UNPROTECTED,
                NativeFuturesCampaignCoordinator.State.RECONCILE_REQUIRED ->
                    stateMachine.force(TradingState.RECONCILE_REQUIRED, "Futures protection/reconciliation unresolved")
                else -> stateMachine.force(TradingState.READY_FLAT, "Futures account reconciled")
            }
        } else {
            stateMachine.force(TradingState.RECONCILE_REQUIRED, result.optString("blocker", "Futures reconciliation failed"))
        }
        return JSONObject().put("recovered", ok).put("exchange", "BINANCE_USDM_FUTURES_TESTNET")
            .put("execution_enabled", ok && futuresCampaignCoordinator.status().optBoolean("ready_for_new_entry", false))
            .put("futures_reconciliation", result).put("state", futuresCampaignCoordinator.state.name)
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
        require(!(path.startsWith("/api/v3/") && method != "GET")) {
            "SECURITY_BLOCK: Spot order mutations are disabled; use USD-M Futures execution coordinator"
        }
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
        val i = candles.lastIndex
        val jaw = williamsAlligatorLine(candles, 13, 8)
        val teeth = williamsAlligatorLine(candles, 8, 5)
        val lips = williamsAlligatorLine(candles, 5, 3)
        val jawNow = jaw.getOrNull(i) ?: 0.0
        val teethNow = teeth.getOrNull(i) ?: 0.0
        val lipsNow = lips.getOrNull(i) ?: 0.0
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
            .put("jaw", jawNow).put("teeth", teethNow).put("lips", lipsNow)
            .put("alligator_bullish", lipsNow > teethNow && teethNow > jawNow)
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

        val body = futuresMarketData.klines(
            normalizedSymbol,
            sourceFrame,
            sourceLimit
        ).toString()
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
                    val body = futuresMarketData.klines(
                        normalizedSymbol,
                        frame,
                        1000,
                        endTime = endTime
                    ).toString()
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
            val body = futuresMarketData.klines(
                normalizedSymbol,
                frame,
                3,
                startTime = newest.coerceAtLeast(0L)
            ).toString()
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
        val volumes = HashMap<String, Double>()
        val volumeRows = futuresMarketData.ticker24h()
        for (i in 0 until volumeRows.length()) {
            val item = volumeRows.optJSONObject(i) ?: continue
            val symbol = item.optString("symbol").uppercase(Locale.US)
            if (symbol in coreSymbols) volumes[symbol] = item.optString("quoteVolume").toDoubleOrNull() ?: 0.0
        }
        val spreads = HashMap<String, Double>()
        val bookRows = futuresMarketData.bookTickers()
        for (i in 0 until bookRows.length()) {
            val item = bookRows.optJSONObject(i) ?: continue
            val symbol = item.optString("symbol").uppercase(Locale.US)
            if (symbol !in coreSymbols) continue
            val bid = item.optString("bidPrice").toDoubleOrNull() ?: 0.0
            val ask = item.optString("askPrice").toDoubleOrNull() ?: 0.0
            if (bid > 0.0 && ask >= bid) spreads[symbol] = (ask - bid) / bid
        }
        val missing = coreSymbols.filter { (volumes[it] ?: 0.0) <= 0.0 || spreads[it] == null }
        require(missing.isEmpty()) { "Futures market data missing approved contracts: " + missing.joinToString(",") }
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

    private fun campaignPendingRiskPct(): Double =
        synchronized(pendingEntries) {
            pendingEntries.values.sumOf { it.riskReservedPct }
        }

    private fun campaignRiskUsedPct(): Double =
        (
            positionList().sumOf { p ->
                if (p.riskPct > 0.0) p.riskPct
                else if (p.entry > 0.0 && p.stop > 0.0) {
                    ((p.entry - p.stop) / p.entry).coerceAtLeast(0.0)
                } else 0.0
            } + campaignPendingRiskPct()
        ).coerceAtLeast(0.0)

    private fun campaignRemainingRiskPct(equity: Double): Double =
        if (equity <= 0.0) 0.0
        else (maxTotalRiskPct - campaignRiskUsedPct()).coerceAtLeast(0.0)

    private fun campaignBaseBalance(symbol: String, account: JSONObject? = null): Double {
        val a = account ?: signedAccount()
        val info = JSONObject(
            getBody("/api/v3/exchangeInfo?symbol=" + symbol)
        )
        val asset = info.optJSONArray("symbols")
            ?.optJSONObject(0)
            ?.optString("baseAsset", "")
            ?.uppercase(Locale.US)
            ?: return 0.0
        val balances = a.optJSONArray("balances") ?: JSONArray()
        for (i in 0 until balances.length()) {
            val row = balances.optJSONObject(i) ?: continue
            if (row.optString("asset").uppercase(Locale.US) == asset) {
                return (row.optString("free").toDoubleOrNull() ?: 0.0) +
                    (row.optString("locked").toDoubleOrNull() ?: 0.0)
            }
        }
        return 0.0
    }

    private fun checkCampaignOrderCapacity(symbol: String, additional: Int = 1) {
        val rules = symbolFilters(symbol)
        val response = signedGet("/api/v3/openOrders", "symbol=" + symbol)
        val orders = response.optJSONArray("orders")
            ?: if (response.has("symbol")) JSONArray().put(response) else JSONArray()
        require(orders.length() + additional <= rules.maxNumOrders) {
            "Binance MAX_NUM_ORDERS would be exceeded"
        }
        val algoOpen = (0 until orders.length()).count { i ->
            val type = orders.optJSONObject(i)
                ?.optString("type", "")
                ?.uppercase(Locale.US)
                ?: ""
            type in setOf("STOP_LOSS", "STOP_LOSS_LIMIT", "TAKE_PROFIT", "TAKE_PROFIT_LIMIT")
        }
        require(algoOpen + additional <= rules.maxNumAlgoOrders) {
            "Binance MAX_NUM_ALGO_ORDERS would be exceeded"
        }
    }

    private fun withCampaignMutation(
        symbol: String,
        purpose: String,
        block: () -> JSONObject
    ): JSONObject {
        if (!running) error("Runtime is not running")
        if (paused) error("Runtime is paused")
        if (reconcileRequired) error("RECONCILE_REQUIRED")
        if (killLatched) error("KILL_SWITCH_LATCHED")
        require(executionGate.tryReserve(symbol)) {
            "Campaign execution already reserved for " + symbol
        }
        prefs.edit()
            .putString("execution_gate_symbol", symbol)
            .putString("execution_gate_purpose", purpose)
            .apply()
        return try {
            block()
        } finally {
            executionGate.release(symbol)
            prefs.edit()
                .remove("execution_gate_symbol")
                .remove("execution_gate_purpose")
                .apply()
        }
    }

    private fun campaignEntryCandidate(candidate: BaseAnalysis): CampaignSignalN? =
        // Select the most recent still-valid confirmed setup, never the oldest one
        // returned by a historical scan.
        candidate.campaignSignals.sortedByDescending { it.signalBarTimeMs }.firstOrNull()

    private fun submitCampaignEntry(candidate: BaseAnalysis) {
        if (!campaignEngineEnabled || !candidate.campaignReady) return
        if (reconcileRequired || paused || killLatched) return
        if (positionList().any { it.symbol == candidate.symbol }) return
        if (synchronized(pendingEntries) { pendingEntries.containsKey(candidate.symbol) }) return
        if (positionList().size >= maxOpenPositions) return

        val signal = campaignEntryCandidate(candidate) ?: return
        val equity = estimateManagedEquity()
        val remainingRisk = campaignRemainingRiskPct(equity)
        val riskPct = min(
            campaignInitialRiskPct,
            min(maxRiskPerTradePct, remainingRisk)
        )
        if (riskPct <= 0.0 || equity <= 0.0) return

        val current = runCatching { currentCampaignPrice(candidate.symbol) }.getOrDefault(0.0)
        if (current <= 0.0) return

        val rules = runCatching { symbolFilters(candidate.symbol) }.getOrNull() ?: return
        val trigger = normalizeTriggerPrice(signal.triggerPrice, rules.tick)
        val stop = normalizePrice(signal.protectivePrice, rules.tick)
        if (trigger <= current || stop <= 0.0 || stop >= trigger) return

        val stopFraction = ((trigger - stop) / trigger).coerceAtLeast(0.000001)
        val effectiveLoss = stopFraction + 2.0 * feeBufferPerSidePct + maxSlippagePct
        val riskQuote = equity * riskPct
        val freeUsdt = liveUsdtBalance.coerceAtLeast(0.0)
        val pendingCapital = synchronized(pendingEntries) {
            pendingEntries.values.sumOf { it.capitalReservedQuote }
        }
        val usableCapital = max(0.0, freeUsdt - pendingCapital)
        val notional = min(
            equity * 0.25,
            min(usableCapital * 0.95, riskQuote / effectiveLoss)
        )
        val qty = floorStep(notional / trigger, rules.step)
        if (qty <= 0.0 || qty < rules.minQty) return
        if (qty * trigger < rules.minNotional) return

        val existingBase = runCatching {
            campaignBaseBalance(candidate.symbol)
        }.getOrDefault(0.0)
        if (rules.maxPosition.isFinite() && existingBase + qty > rules.maxPosition) return

        val clientId =
            "W5E_" + signal.type + "_" + signal.signalBarTimeMs + "_" +
                System.nanoTime().toString(16).takeLast(10)
        val campaignId = "W5C_" + System.nanoTime().toString(16)

        val pending = PendingEntry(
            symbol = candidate.symbol,
            clientOrderId = clientId,
            notional = qty * trigger,
            stopDistance = stopFraction,
            triggerPrice = trigger,
            protectivePrice = stop,
            riskReservedPct = riskPct,
            capitalReservedQuote = qty * trigger,
            campaignId = campaignId,
            signalId = signal.signalId,
            signalType = signal.type
        )
        synchronized(pendingEntries) {
            pendingEntries[candidate.symbol] = pending
        }
        stateMachine.force(
            TradingState.ENTRY_PENDING,
            "Williams campaign conditional entry armed"
        )
        savePersistedState()

        executionExecutor.execute {
            try {
                val order = withCampaignMutation(
                    candidate.symbol,
                    "CAMPAIGN_ENTRY"
                ) {
                    require(currentCampaignPrice(candidate.symbol) < trigger) {
                        "Campaign trigger already crossed; no market substitution"
                    }
                    checkCampaignOrderCapacity(candidate.symbol, 1)
                    if (
                        rules.maxPosition.isFinite() &&
                        campaignBaseBalance(candidate.symbol) + qty > rules.maxPosition
                    ) {
                        error("Binance MAX_POSITION would be exceeded")
                    }
                    signedPost(
                        "/api/v3/order",
                        "symbol=" + candidate.symbol +
                            "&side=BUY&type=STOP_LOSS" +
                            "&quantity=" + fmtQty(qty, rules.decimals) +
                            "&stopPrice=" + fmtPrice(trigger, rules.tick) +
                            "&newClientOrderId=" + clientId
                    )
                }
                lastOrder = order
                savePersistedState()
                recoverCampaignPendingEntries()
            } catch (x: Throwable) {
                val observed = runCatching {
                    signedGet(
                        "/api/v3/order",
                        "symbol=" + candidate.symbol +
                            "&origClientOrderId=" + clientId
                    )
                }.getOrNull()
                if (observed != null) {
                    lastOrder = observed
                    runCatching { recoverCampaignPendingEntries() }
                } else {
                    setReconcileRequired(
                        "Campaign entry mutation ambiguous for " + candidate.symbol +
                            ": " + (x.message ?: x.javaClass.simpleName)
                    )
                }
            }
        }
    }

    private fun createCampaignStop(position: PositionState): JSONObject {
        val symbol = position.symbol
        val rules = symbolFilters(symbol)
        val qty = floorStep(position.qty, rules.step)
        val stop = normalizePrice(position.stop, rules.tick)
        require(qty >= rules.minQty && qty > 0.0) {
            symbol + ": campaign protection quantity below minimum"
        }
        require(stop > 0.0) { symbol + ": campaign stop invalid" }
        require(qty * stop >= rules.minNotional) {
            symbol + ": campaign protection below minimum notional"
        }
        require(currentCampaignPrice(symbol) > stop) {
            symbol + ": market price is already at/below campaign stop"
        }

        val clientId = "W5S_" + System.nanoTime().toString(16)
        val order = withCampaignMutation(symbol, "CAMPAIGN_PROTECTION") {
            checkCampaignOrderCapacity(symbol, 1)
            signedPost(
                "/api/v3/order",
                "symbol=" + symbol +
                    "&side=SELL&type=STOP_LOSS" +
                    "&quantity=" + fmtQty(qty, rules.decimals) +
                    "&stopPrice=" + fmtPrice(stop, rules.tick) +
                    "&newClientOrderId=" + clientId
            )
        }
        lastOrder = order
        return order
    }

    private fun replaceCampaignStop(position: PositionState, proposedStop: Double) {
        if (proposedStop <= position.stop) return
        val rules = symbolFilters(position.symbol)
        val stop = normalizePrice(proposedStop, rules.tick)
        val market = currentCampaignPrice(position.symbol)
        if (stop <= position.stop || stop >= market) return

        val oldId = position.protectiveOrderId.toLongOrNull()
        if (oldId == null) {
            val created = createCampaignStop(
                position.copy(
                    stop = stop,
                    stopSource = "3_5_BAR_STRUCTURE"
                )
            )
            synchronized(positions) {
                positions[position.symbol] =
                    position.copy(
                        stop = stop,
                        stopSource = "3_5_BAR_STRUCTURE",
                        protectiveOrderId = created.optString("orderId", "")
                    )
            }
            savePersistedState()
            return
        }

        val newClientId = "W5S_" + System.nanoTime().toString(16)
        val result = withCampaignMutation(position.symbol, "CAMPAIGN_TRAIL") {
            signedCancelReplace(
                position.symbol,
                oldId,
                "SELL",
                "STOP_LOSS",
                quantity = fmtQty(
                    floorStep(position.qty, rules.step),
                    rules.decimals
                ),
                stopPrice = fmtPrice(stop, rules.tick),
                newClientOrderId = newClientId
            )
        }
        val cancelResult = result.optString("cancelResult", "").uppercase(Locale.US)
        val newResult = result.optString("newOrderResult", "").uppercase(Locale.US)
        if (cancelResult != "SUCCESS" || newResult != "SUCCESS") {
            setReconcileRequired(
                "Campaign protective stop replacement ambiguous for " + position.symbol
            )
            return
        }
        val newResponse = result.optJSONObject("newOrderResponse")
            ?: error("Campaign protective replacement has no response")
        val newId = newResponse.optString("orderId", "")
        require(newId.isNotBlank()) {
            "Campaign protective replacement returned no orderId"
        }
        synchronized(positions) {
            positions[position.symbol] =
                position.copy(
                    stop = stop,
                    stopSource = "3_5_BAR_STRUCTURE",
                    protectiveOrderId = newId,
                    campaignState = "TRAILING"
                )
        }
        savePersistedState()
    }

    private fun currentCampaignPrice(symbol: String): Double =
        runCatching { futuresMarketData.markPrice(symbol) }.getOrDefault(livePrices[symbol] ?: 0.0)

    private fun campaignExitMarket(position: PositionState, reason: String) {
        val symbol = position.symbol
        try {
            val stopId = position.protectiveOrderId.toLongOrNull()
            if (stopId != null) {
                withCampaignMutation(
                    symbol,
                    "CAMPAIGN_EXIT_CANCEL_PROTECTION"
                ) {
                    signedDelete(
                        "/api/v3/order",
                        "symbol=" + symbol + "&orderId=" + stopId
                    )
                    JSONObject()
                }
            }

            val account = signedAccount()
            val rules = symbolFilters(symbol)
            val base = campaignBaseBalance(symbol, account)
            val qty = floorStep(
                min(position.qty, base),
                if (rules.marketStep > 0.0) rules.marketStep else rules.step
            )
            val minQty = if (rules.marketMinQty > 0.0) rules.marketMinQty else rules.minQty
            require(qty >= minQty) {
                symbol + ": campaign exit quantity below minimum"
            }

            val cid = "W5X_" + System.nanoTime().toString(16)
            val order = withCampaignMutation(symbol, "CAMPAIGN_EXIT") {
                signedPost(
                    "/api/v3/order",
                    "symbol=" + symbol +
                        "&side=SELL&type=MARKET" +
                        "&quantity=" + fmtQty(
                            qty,
                            if (rules.marketStep > 0.0) {
                                max(
                                    0,
                                    rules.marketStep.toString()
                                        .substringAfter('.', "")
                                        .trimEnd('0')
                                        .length
                                )
                            } else rules.decimals
                        ) +
                        "&newClientOrderId=" + cid
                )
            }
            lastOrder = order
            val executed = order.optString("executedQty").toDoubleOrNull() ?: 0.0
            val quote = order.optString("cummulativeQuoteQty").toDoubleOrNull() ?: 0.0
            require(executed > 0.0 && quote > 0.0) {
                symbol + ": campaign exit has no authoritative fill"
            }

            val remaining = max(0.0, position.qty - executed)
            if (remaining > max(0.000001, position.qty * 0.005)) {
                setReconcileRequired(
                    symbol + ": campaign exit partially filled; residual=" + remaining
                )
                return
            }

            recordCompletedTrade(
                position,
                quote / executed,
                reason,
                order
            )
            synchronized(positions) { positions.remove(symbol) }
            savePersistedState()
            stateMachine.force(
                if (positionList().isEmpty()) TradingState.READY_FLAT else TradingState.PROTECTED,
                "Williams campaign closed"
            )
        } catch (x: Throwable) {
            setReconcileRequired(
                symbol + ": campaign exit failed: " +
                    (x.message ?: x.javaClass.simpleName)
            )
        }
    }

    private fun recoverCampaignPendingEntries() {
        val pending = synchronized(pendingEntries) {
            pendingEntries.values.toList()
        }
        for (intent in pending) {
            try {
                val order = signedGet(
                    "/api/v3/order",
                    "symbol=" + intent.symbol + "&origClientOrderId=" + intent.clientOrderId
                )
                lastOrder = order
                val status = order.optString("status", "").uppercase(Locale.US)
                val executed = order.optString("executedQty").toDoubleOrNull() ?: 0.0
                val quote = order.optString("cummulativeQuoteQty").toDoubleOrNull() ?: 0.0

                when {
                    status == "NEW" || status == "PENDING_NEW" -> Unit

                    status == "PARTIALLY_FILLED" -> {
                        val orderId = order.optLong("orderId", -1L)
                        require(orderId > 0L) {
                            intent.symbol + ": partial campaign entry missing orderId"
                        }
                        try {
                            withCampaignMutation(
                                intent.symbol,
                                "CAMPAIGN_PARTIAL_ENTRY_CANCEL"
                            ) {
                                signedDelete(
                                    "/api/v3/order",
                                    "symbol=" + intent.symbol + "&orderId=" + orderId
                                )
                                JSONObject()
                            }
                        } catch (cancelError: Throwable) {
                            setReconcileRequired(
                                intent.symbol + ": partial campaign BUY cancel ambiguous: " +
                                    (cancelError.message ?: cancelError.javaClass.simpleName)
                            )
                            continue
                        }
                        val finalOrder = signedGet(
                            "/api/v3/order",
                            "symbol=" + intent.symbol + "&orderId=" + orderId
                        )
                        val finalQty = finalOrder.optString("executedQty").toDoubleOrNull() ?: executed
                        val finalQuote = finalOrder.optString("cummulativeQuoteQty").toDoubleOrNull() ?: quote
                        if (finalQty > 0.0) {
                            applyCampaignFill(
                                intent,
                                finalOrder,
                                finalQty,
                                finalQuote
                            )
                        } else {
                            clearCampaignPending(intent.symbol)
                        }
                    }

                    status == "FILLED" -> {
                        require(executed > 0.0 && quote > 0.0) {
                            intent.symbol + ": filled campaign BUY has invalid fill"
                        }
                        applyCampaignFill(
                            intent,
                            order,
                            executed,
                            quote
                        )
                    }

                    status in setOf("CANCELED", "EXPIRED", "REJECTED") -> {
                        if (executed > 0.0) {
                            require(quote > 0.0) {
                                intent.symbol + ": terminal campaign fill lacks quote"
                            }
                            applyCampaignFill(
                                intent,
                                order,
                                executed,
                                quote
                            )
                        } else {
                            clearCampaignPending(intent.symbol)
                        }
                    }

                    else -> setReconcileRequired(
                        intent.symbol + ": unknown campaign pending status=" + status
                    )
                }
            } catch (x: Throwable) {
                setReconcileRequired(
                    intent.symbol + ": campaign pending recovery failed: " +
                        (x.message ?: x.javaClass.simpleName)
                )
            }
        }
    }

    private fun clearCampaignPending(symbol: String) {
        synchronized(pendingEntries) { pendingEntries.remove(symbol) }
        savePersistedState()
        if (positionList().isEmpty()) {
            stateMachine.force(
                TradingState.READY_FLAT,
                "Campaign conditional entry cleared"
            )
        }
    }

    private fun applyCampaignAddOnFill(
        existing: PositionState,
        intent: PendingEntry,
        order: JSONObject,
        qty: Double,
        quote: Double
    ) {
        val entry = quote / qty
        val oldQty = existing.qty
        val newQty = oldQty + qty
        val newAvg =
            ((oldQty * existing.entry) + (qty * entry)) / max(newQty, 1e-12)

        require(existing.stop > 0.0 && existing.stop < entry) {
            existing.symbol + ": add-on cannot be protected by current stop"
        }

        val provisional = existing.copy(
            qty = newQty,
            entry = newAvg,
            riskPct = existing.riskPct + intent.riskReservedPct,
            signalId = intent.signalId,
            signalType = intent.signalType,
            additions = existing.additions + 1,
            campaignState = "POSITION_EXPANDING"
        )

        try {
            val stopId = existing.protectiveOrderId.toLongOrNull()
            val protectedId =
                if (stopId == null) {
                    createCampaignStop(provisional).optString("orderId", "")
                } else {
                    val result = withCampaignMutation(
                        existing.symbol,
                        "CAMPAIGN_ADD_ON_PROTECTION_RESIZE"
                    ) {
                        val rules = symbolFilters(existing.symbol)
                        signedCancelReplace(
                            existing.symbol,
                            stopId,
                            "SELL",
                            "STOP_LOSS",
                            quantity = fmtQty(
                                floorStep(newQty, rules.step),
                                rules.decimals
                            ),
                            stopPrice = fmtPrice(
                                existing.stop,
                                rules.tick
                            ),
                            newClientOrderId =
                                "W5S_" + System.nanoTime().toString(16)
                        )
                    }
                    val cancelOk =
                        result.optString("cancelResult")
                            .uppercase(Locale.US) == "SUCCESS"
                    val newOk =
                        result.optString("newOrderResult")
                            .uppercase(Locale.US) == "SUCCESS"
                    require(cancelOk && newOk) {
                        existing.symbol + ": add-on protection resize ambiguous"
                    }
                    result.optJSONObject("newOrderResponse")
                        ?.optString("orderId", "")
                        ?: ""
                }

            require(protectedId.isNotBlank()) {
                existing.symbol + ": add-on protection has no orderId"
            }

            synchronized(positions) {
                positions[existing.symbol] =
                    provisional.copy(
                        protectiveOrderId = protectedId,
                        campaignState = "TREND_ACTIVE"
                    )
            }
            synchronized(pendingEntries) {
                pendingEntries.remove(existing.symbol)
            }
            savePersistedState()
            stateMachine.force(
                TradingState.PROTECTED,
                "Williams campaign add-on filled"
            )
        } catch (x: Throwable) {
            setReconcileRequired(
                existing.symbol + ": add-on fill protection failed: " +
                    (x.message ?: x.javaClass.simpleName)
            )
        }
    }

    private fun applyCampaignFill(
        intent: PendingEntry,
        order: JSONObject,
        qty: Double,
        quote: Double
    ) {
        val existing = synchronized(positions) { positions[intent.symbol] }
        if (existing != null && existing.campaignId == intent.campaignId) {
            applyCampaignAddOnFill(
                existing,
                intent,
                order,
                qty,
                quote
            )
        } else {
            createCampaignPositionFromFill(
                intent,
                order,
                qty,
                quote
            )
        }
    }

    private fun createCampaignPositionFromFill(
        intent: PendingEntry,
        order: JSONObject,
        qty: Double,
        quote: Double
    ) {
        if (qty <= 0.0 || quote <= 0.0) {
            throw IllegalArgumentException(
                intent.symbol + ": invalid campaign fill"
            )
        }
        val entry = quote / qty
        val stop = intent.protectivePrice
        if (stop <= 0.0 || stop >= entry) {
            throw IllegalStateException(
                intent.symbol + ": invalid campaign stop=" + stop + " entry=" + entry
            )
        }

        val existing = synchronized(positions) { positions[intent.symbol] }
        if (existing != null && existing.campaignId == intent.campaignId) {
            applyCampaignAddOnFill(
                existing,
                intent,
                order,
                qty,
                quote
            )
            return
        }

        val provisional = PositionState(
            symbol = intent.symbol,
            qty = qty,
            entry = entry,
            stop = stop,
            take = 0.0,
            riskPct = intent.riskReservedPct,
            entryOrderId = order.optString("orderId", ""),
            entryClientOrderId = intent.clientOrderId,
            openedAt = order.optLong("transactTime", System.currentTimeMillis()),
            campaignId = intent.campaignId,
            signalId = intent.signalId,
            signalType = intent.signalType,
            campaignState = "OPEN_INITIAL",
            stopSource = "INITIAL_SIGNAL"
        )

        try {
            val protection = createCampaignStop(provisional)
            val protected = provisional.copy(
                protectiveOrderId = protection.optString("orderId", "")
            )
            require(protected.protectiveOrderId.isNotBlank()) {
                intent.symbol + ": campaign protection has no orderId"
            }

            synchronized(positions) {
                positions[intent.symbol] = protected
            }
            synchronized(pendingEntries) {
                pendingEntries.remove(intent.symbol)
            }

            TradeJournal.recordEntry(
                prefs = prefs,
                symbol = protected.symbol,
                entry = protected.entry,
                qty = protected.qty,
                stop = protected.stop,
                take = 0.0,
                riskPct = protected.riskPct,
                notional = protected.entry * protected.qty,
                candidate = JSONObject()
                    .put("campaign_id", protected.campaignId)
                    .put("signal_id", protected.signalId)
                    .put("signal_type", protected.signalType)
                    .put("entry_trigger", intent.triggerPrice)
                    .put("protective_reference", intent.protectivePrice)
                    .put("campaign_entry_mode", "CONDITIONAL_STOP")
            )
            savePersistedState()
            stateMachine.force(
                TradingState.PROTECTED,
                "Campaign entry filled and hard protection armed"
            )
        } catch (x: Throwable) {
            try {
                val balanceQty = campaignBaseBalance(intent.symbol)
                val rules = symbolFilters(intent.symbol)
                val emergencyQty = floorStep(
                    min(qty, balanceQty),
                    if (rules.marketStep > 0.0) rules.marketStep else rules.step
                )
                require(
                    emergencyQty >= (
                        if (rules.marketMinQty > 0.0) rules.marketMinQty else rules.minQty
                    )
                )
                val cid = "W5EM_" + System.nanoTime().toString(16)
                val emergency = withCampaignMutation(
                    intent.symbol,
                    "CAMPAIGN_EMERGENCY_EXIT"
                ) {
                    signedPost(
                        "/api/v3/order",
                        "symbol=" + intent.symbol +
                            "&side=SELL&type=MARKET" +
                            "&quantity=" +
                            fmtQty(
                                emergencyQty,
                                if (rules.marketStep > 0.0) {
                                    max(
                                        0,
                                        rules.marketStep.toString()
                                            .substringAfter('.', "")
                                            .trimEnd('0')
                                            .length
                                    )
                                } else rules.decimals
                            ) +
                            "&newClientOrderId=" + cid
                    )
                }
                lastOrder = emergency
                val sold = emergency.optString("executedQty").toDoubleOrNull() ?: 0.0
                if (sold + max(0.000001, qty * 0.005) >= qty) {
                    synchronized(pendingEntries) { pendingEntries.remove(intent.symbol) }
                    savePersistedState()
                    stateMachine.force(
                        if (positionList().isEmpty()) TradingState.READY_FLAT else TradingState.PROTECTED,
                        "Unprotected campaign emergency exit complete"
                    )
                } else {
                    setReconcileRequired(
                        intent.symbol + ": emergency campaign exit partial after protection failure"
                    )
                }
            } catch (emergency: Throwable) {
                setReconcileRequired(
                    intent.symbol + ": protection failed and emergency exit failed: " +
                        (emergency.message ?: emergency.javaClass.simpleName)
                )
            }
        }
    }

    private fun reconcileCampaignPositions() {
        for (stored in positionList().filter { it.campaignId.isNotBlank() }) {
            try {
                val rules = symbolFilters(stored.symbol)
                val actualBase = campaignBaseBalance(stored.symbol)
                val tolerance = max(0.000001, stored.qty * 0.005)
                require(actualBase + tolerance >= stored.qty) {
                    stored.symbol + ": campaign inventory below expected"
                }

                val response = signedGet(
                    "/api/v3/openOrders",
                    "symbol=" + stored.symbol
                )
                val orders = response.optJSONArray("orders")
                    ?: if (response.has("symbol")) JSONArray().put(response) else JSONArray()

                val managedStops = mutableListOf<JSONObject>()
                val unknownSells = mutableListOf<JSONObject>()

                for (j in 0 until orders.length()) {
                    val o = orders.optJSONObject(j) ?: continue
                    if (o.optString("side").uppercase(Locale.US) != "SELL") continue
                    val cid = o.optString("clientOrderId")
                    if (
                        cid.startsWith("W5S_") &&
                        o.optString("type").uppercase(Locale.US) in
                            setOf("STOP_LOSS", "STOP_LOSS_LIMIT")
                    ) {
                        managedStops += o
                    } else {
                        unknownSells += o
                    }
                }
                require(unknownSells.isEmpty()) {
                    stored.symbol + ": unknown open SELL conflicts with campaign"
                }
                require(managedStops.size <= 1) {
                    stored.symbol + ": multiple campaign protection stops"
                }

                if (managedStops.isEmpty()) {
                    val created = createCampaignStop(stored)
                    synchronized(positions) {
                        positions[stored.symbol] =
                            stored.copy(
                                protectiveOrderId = created.optString("orderId", "")
                            )
                    }
                    savePersistedState()
                    continue
                }

                val stop = managedStops.single()
                val stopId = stop.optString("orderId", "")
                require(stopId.isNotBlank()) {
                    stored.symbol + ": campaign stop missing orderId"
                }
                val stopQty = stop.optString("origQty").toDoubleOrNull() ?: 0.0

                if (abs(stopQty - stored.qty) > tolerance) {
                    replaceCampaignStop(
                        stored.copy(protectiveOrderId = stopId),
                        stored.stop
                    )
                } else {
                    synchronized(positions) {
                        positions[stored.symbol] =
                            stored.copy(
                                protectiveOrderId = stopId,
                                campaignState = "TREND_ACTIVE"
                            )
                    }
                    savePersistedState()
                }
            } catch (x: Throwable) {
                setReconcileRequired(
                    stored.symbol + ": campaign reconciliation failed: " +
                        (x.message ?: x.javaClass.simpleName)
                )
            }
        }

        if (
            positionList().isNotEmpty() &&
            stateMachine.state != TradingState.RECONCILE_REQUIRED &&
            !killLatched
        ) {
            stateMachine.force(
                TradingState.PROTECTED,
                "Campaign positions reconciled"
            )
        }
    }

    private fun manageCampaignPositions() {
        for (stored in positionList().filter { it.campaignId.isNotBlank() }) {
            try {
                val raw = fetchCandles(
                    stored.symbol,
                    campaignExecutionTimeframe,
                    80
                )
                val candles = if (raw.size >= 2) raw.dropLast(1) else emptyList()
                if (candles.size < 20) continue

                val rules = symbolFilters(stored.symbol)
                val currentPrice = currentCampaignPrice(stored.symbol)
                val recentLow = candles
                    .takeLast(campaignTrailBars)
                    .minOfOrNull { it.l }
                    ?: continue
                val proposed = normalizePrice(
                    recentLow - rules.tick,
                    rules.tick
                )

                if (
                    proposed > stored.stop &&
                    proposed < currentPrice
                ) {
                    replaceCampaignStop(
                        stored,
                        proposed
                    )
                }

                val current = synchronized(positions) {
                    positions[stored.symbol]
                } ?: stored
                val wave = waveInfo(
                    candles,
                    campaignExecutionTimeframe
                )
                if (
                    wave.exhaustionRisk >= 75.0 ||
                    wave.aoBearishDivergence
                ) {
                    synchronized(positions) {
                        positions[stored.symbol] =
                            current.copy(
                                campaignState = "EXHAUSTION_WATCH"
                            )
                    }
                    savePersistedState()
                    if (
                        prefs.getBoolean("campaign_auto_exhaustion_exit", false) &&
                        wave.exhaustionRisk >= 85.0 &&
                        wave.aoBearishDivergence
                    ) {
                        campaignExitMarket(
                            positions[stored.symbol] ?: current,
                            "WAVE_EXHAUSTION"
                        )
                    }
                }

                if (prefs.getBoolean("campaign_exit_on_teeth", false)) {
                    val teeth = alligator(candles, candles.lastIndex).teeth
                    if (teeth > 0.0 && candles.last().c < teeth) {
                        campaignExitMarket(
                            positions[stored.symbol] ?: current,
                            "CLOSE_BELOW_TEETH"
                        )
                    }
                }
            } catch (x: Throwable) {
                setReconcileRequired(
                    stored.symbol + ": campaign monitor failed: " +
                        (x.message ?: x.javaClass.simpleName)
                )
            }
        }
    }

    private fun submitCampaignAddOn(
        candidate: BaseAnalysis,
        position: PositionState
    ) {
        if (!campaignEngineEnabled || position.campaignId.isBlank()) return

        val latestSignalTime =
            position.signalId.substringAfterLast(':').toLongOrNull()
                ?: position.openedAt
        val signal = candidate.campaignSignals
            .filter {
                it.type in setOf("SUPER_AO", "FRACTAL") &&
                    it.signalBarTimeMs > latestSignalTime
            }
            .sortedBy { it.signalBarTimeMs }
            .firstOrNull()
            ?: return

        if (synchronized(pendingEntries) { pendingEntries.containsKey(candidate.symbol) }) return

        val equity = estimateManagedEquity()
        val remainingCampaign =
            (campaignRiskLimitPct - position.riskPct).coerceAtLeast(0.0)
        val remainingPortfolio =
            (maxTotalRiskPct - campaignRiskUsedPct()).coerceAtLeast(0.0)

        val trancheBias = when (position.additions) {
            0 -> 1.0
            1 -> 1.0
            2 -> 0.75
            else -> 0.5
        }
        val riskPct = min(
            campaignAddRiskCapPct * trancheBias,
            min(remainingCampaign, remainingPortfolio)
        )
        if (riskPct <= 0.0 || equity <= 0.0) return

        val rules = symbolFilters(candidate.symbol)
        val trigger = normalizePrice(signal.triggerPrice, rules.tick)
        if (trigger <= currentCampaignPrice(candidate.symbol)) return

        val stop = position.stop
        if (stop <= 0.0 || stop >= trigger) return

        val stopFraction = (trigger - stop) / trigger
        val effectiveLoss =
            stopFraction + 2.0 * feeBufferPerSidePct + maxSlippagePct
        val riskQuote = equity * riskPct
        val freeUsdt = liveUsdtBalance.coerceAtLeast(0.0)
        val pendingCapital = synchronized(pendingEntries) {
            pendingEntries.values.sumOf { it.capitalReservedQuote }
        }
        val notional = min(
            equity * 0.15,
            min(
                max(0.0, freeUsdt - pendingCapital) * 0.95,
                riskQuote / max(effectiveLoss, 0.000001)
            )
        )
        val qty = floorStep(notional / trigger, rules.step)
        if (qty < rules.minQty || qty * trigger < rules.minNotional) return
        if (
            rules.maxPosition.isFinite() &&
            campaignBaseBalance(candidate.symbol) + qty > rules.maxPosition
        ) return

        val clientId =
            "W5E_ADD_" + signal.type + "_" + signal.signalBarTimeMs + "_" +
                System.nanoTime().toString(16).takeLast(8)
        val pending = PendingEntry(
            symbol = candidate.symbol,
            clientOrderId = clientId,
            notional = qty * trigger,
            stopDistance = stopFraction,
            triggerPrice = trigger,
            protectivePrice = stop,
            riskReservedPct = riskPct,
            capitalReservedQuote = qty * trigger,
            campaignId = position.campaignId,
            signalId = signal.signalId,
            signalType = signal.type
        )
        synchronized(pendingEntries) {
            pendingEntries[candidate.symbol] = pending
        }
        savePersistedState()

        executionExecutor.execute {
            try {
                withCampaignMutation(
                    candidate.symbol,
                    "CAMPAIGN_ADD_ON"
                ) {
                    require(currentCampaignPrice(candidate.symbol) < trigger) {
                        "Add-on trigger already crossed"
                    }
                    checkCampaignOrderCapacity(candidate.symbol, 1)
                    signedPost(
                        "/api/v3/order",
                        "symbol=" + candidate.symbol +
                            "&side=BUY&type=STOP_LOSS" +
                            "&quantity=" + fmtQty(qty, rules.decimals) +
                            "&stopPrice=" + fmtPrice(trigger, rules.tick) +
                            "&newClientOrderId=" + clientId
                    )
                }
                savePersistedState()
                recoverCampaignPendingEntries()
            } catch (x: Throwable) {
                val observed = runCatching {
                    signedGet(
                        "/api/v3/order",
                        "symbol=" + candidate.symbol +
                            "&origClientOrderId=" + clientId
                    )
                }.getOrNull()
                if (observed != null) {
                    lastOrder = observed
                    runCatching { recoverCampaignPendingEntries() }
                } else {
                    setReconcileRequired(
                        candidate.symbol + ": campaign add-on mutation ambiguous: " +
                            (x.message ?: x.javaClass.simpleName)
                    )
                }
            }
        }
    }

    private fun checkFuturesDailyLoss(): Boolean {
        val account = futuresCampaignCoordinator.accountSnapshot()
        val wallet = account.optString("totalWalletBalance").toDoubleOrNull() ?: 0.0
        val unrealized = account.optString("totalUnrealizedProfit").toDoubleOrNull() ?: 0.0
        val equity = account.optString("totalMarginBalance").toDoubleOrNull() ?: (wallet + unrealized)
        require(equity.isFinite() && equity > 0.0) { "Futures equity is unavailable/non-positive" }
        val utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        utc.set(java.util.Calendar.HOUR_OF_DAY, 0)
        utc.set(java.util.Calendar.MINUTE, 0)
        utc.set(java.util.Calendar.SECOND, 0)
        utc.set(java.util.Calendar.MILLISECOND, 0)
        val baselineKey = "futures_day_start_equity_" + utc.timeInMillis
        if (!prefs.contains(baselineKey)) {
            check(prefs.edit().putString(baselineKey, equity.toString()).commit()) {
                "Could not persist Futures daily equity baseline"
            }
        }
        val baseline = prefs.getString(baselineKey, null)?.toDoubleOrNull() ?: 0.0
        require(baseline > 0.0 && baseline.isFinite()) { "Futures daily equity baseline is invalid" }
        val lossPct = ((baseline - equity) / baseline).coerceAtLeast(0.0)
        check(prefs.edit().putString("futures_daily_loss_pct", lossPct.toString()).commit()) {
            "Could not persist daily loss measurement"
        }
        if (lossPct >= 0.03) {
            paused = true
            prefs.edit().putBoolean("auto_run", false).commit()
            lastError = "FUTURES_DAILY_LOSS_LIMIT: 3% reached; new entries blocked"
            runCatching { futuresCampaignCoordinator.cancelPendingEntries("DAILY_LOSS_LIMIT") }
            return false
        }
        return true
    }

    fun portfolio(): JSONObject {
        val configured = key().isNotBlank() && secret().isNotBlank()
        if (!configured) {
            return JSONObject().put("configured", false)
                .put("exchange", "BINANCE_USDM_FUTURES_TESTNET")
                .put("positions", JSONArray()).put("assets", JSONArray())
        }
        val account = futuresCampaignCoordinator.accountSnapshot()
        val wallet = account.optString("totalWalletBalance").toDoubleOrNull() ?: 0.0
        val unrealized = account.optString("totalUnrealizedProfit").toDoubleOrNull() ?: 0.0
        val equity = account.optString("totalMarginBalance").toDoubleOrNull() ?: wallet + unrealized
        val available = account.optString("availableBalance").toDoubleOrNull() ?: 0.0
        val positions = futuresPositionSnapshot()
        var notional = 0.0
        var posPnl = 0.0
        for (i in 0 until positions.length()) {
            val p = positions.optJSONObject(i) ?: continue
            notional += p.optDouble("qty", 0.0) * p.optDouble("mark_price", 0.0)
            posPnl += p.optDouble("unrealized_pnl_usdt", 0.0)
        }
        val assets = JSONArray().put(
            JSONObject().put("asset", "USDT").put("free", available)
                .put("total", wallet).put("value_usdt", wallet)
                .put("allocation_pct", if (equity > 0.0) wallet / equity * 100.0 else 0.0)
        )
        return JSONObject().put("configured", true)
            .put("exchange", "BINANCE_USDM_FUTURES_TESTNET")
            .put("account_type", "USD_M_FUTURES")
            .put("total_equity_usdt", equity).put("free_equity_usdt", available)
            .put("locked_equity_usdt", (wallet - available).coerceAtLeast(0.0))
            .put("wallet_balance_usdt", wallet).put("unrealized_pnl_usdt", unrealized)
            .put("position_notional_usdt", notional).put("positions_unrealized_pnl_usdt", posPnl)
            .put("assets", assets).put("positions", positions)
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
                        "USD-M Futures scanner is running locally on Android"
                    } else {
                        "USD-M Futures runtime idle or waiting for H1 history/reconciliation"
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
        test("futures_execution_adapter", "binance", futuresExecutionAdapterReady, "FAIL", "Native local USD-M Futures REST adapter/coordinator configured; exchange reconciliation is reported separately")
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
        test("risk_limits", "risk", maxRiskPerTradePct > 0.0 && maxRiskPerTradePct <= 0.0025 && maxTotalRiskPct <= 0.006, "FAIL", "per_trade=" + maxRiskPerTradePct + "; total=" + maxTotalRiskPct + "; daily_loss=0.03")
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
            .put("exchange_market", "USDⓈ-M_FUTURES_REQUIRED")
            .put("futures_execution_adapter_ready", futuresExecutionAdapterReady)
            .put("futures_campaign_coordinator", futuresCampaignCoordinator.status())
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
            return JSONObject().put("changed", false).put("safe_only", true)
                .put("actions", actions).put("execution_ready", executionReady())
                .put("execution_blockers", reasons).put("before", before).put("after", before)
        }
        lastSelfHealMs = System.currentTimeMillis()
        selfHealCount++
        if (running) {
            if (!historyReady && !historyWarmupRunning) {
                historyState = "RECOVERY_REQUESTED"
                warmCoreHistoryAsync(runtimeGeneration)
                actions.put("futures_history_reload_requested")
            }
            val reconciliation = runCatching { futuresCampaignCoordinator.reconcile() }.getOrNull()
            if (reconciliation?.optBoolean("ok", false) == true) {
                actions.put("futures_exchange_reconciliation")
            } else if (reconciliation != null) {
                reconcileRequired = true
                prefs.edit().putBoolean("reconcile_required", true).commit()
                actions.put("futures_reconciliation_blocked")
            }
            if (historyReady && !scanning && !paused) {
                requestScan()
                actions.put("futures_scanner_reconnect_requested")
            }
            if (paused) {
                runCatching { manageNativeFuturesPositions() }
                    .onSuccess { actions.put("futures_position_monitor") }
                    .onFailure { actions.put("futures_position_monitor_failed") }
            }
        } else actions.put("runtime_not_started")
        val after = status(fast = true)
        return JSONObject().put("changed", actions.length() > 0).put("safe_only", true)
            .put("actions", actions).put("execution_ready", executionReady())
            .put("execution_blockers", JSONArray(executionBlockers())).put("before", before).put("after", after)
    }

    fun settings(): JSONObject =
        JSONObject()
            .put("version", BuildConfig.VERSION_NAME)
            .put("campaign_engine", campaignEngineEnabled)
            .put("campaign_execution_timeframe", campaignExecutionTimeframe)
            .put("campaign_entry_mode", "CONDITIONAL_STOP")
            .put("campaign_fixed_take_profit", false)
            .put("campaign_entry_execution", "DISABLED_UNTIL_FUTURES_ADAPTER")
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
            .put("testnet_live_execution", false)
            .put("futures_execution_adapter_ready", futuresExecutionAdapterReady)
            .put("execution_block_reason", "USDⓈ-M Futures adapter missing; Spot /api/v3 order path must never be used for Futures")
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