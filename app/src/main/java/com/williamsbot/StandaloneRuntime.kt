package com.williamsbot

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.OkHttpClient
import okhttp3.Request
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
    val currentLegPct: Double
)

private data class PositionState(
    val symbol: String,
    var qty: Double,
    var entry: Double,
    var stop: Double,
    var take: Double,
    var riskPct: Double,
    var ocoListClientId: String = ""
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
        .retryOnConnectionFailure(true)
        .build()

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

            method == "GET" && path == "/api/v1/market/klines" ->
                x.klines(params["symbol"]).toString()

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

    private val maxScanSymbols = 60
    private val waveTopN = 8
    private val scanExecutor = Executors.newFixedThreadPool(6)

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
    private const val BINANCE_RECV_WINDOW_MS = 60000L
    @Volatile private var lastServerTimeSyncMs = 0L
    @Volatile private var lastOrder: JSONObject? = null
    @Volatile private var reconcileRequired = false
    private val positions = mutableMapOf<String, PositionState>()
    private val pendingEntries = mutableMapOf<String, PendingEntry>()

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
                        )
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
            .put("version", "4.14.0")
            .put("standalone", true)
            .put("websocket", false)
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
    }

    fun stop(): JSONObject {
        running = false
        paused = false
        prefs.edit()
            .putBoolean("auto_run", false)
            .apply()
        worker?.interrupt()
        worker = null

        return JSONObject().put("stopped", true)
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
                60 * 1000L
        ) {
            runCatching { syncServerTime() }
        }
        return System.currentTimeMillis() +
            serverTimeOffsetMs
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
        serverTimeOffsetMs =
            remote - ((syncStartedAtMs + after) / 2L)
        lastServerTimeSyncMs = after
    }

    private fun getBody(path: String): String {
        val request = Request.Builder()
            .url(baseUrl + path)
            .get()
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: "{}"
            if (!response.isSuccessful) {
                error("Binance HTTP " + response.code + ": " + body)
            }
            return body
        }
    }

    private fun signedAccount(): JSONObject {
        val timestamp = signedTimestamp().toString()
        val params =
            "timestamp=" + timestamp + "&recvWindow=" + BINANCE_RECV_WINDOW_MS
        val signature = hmac(params, secret())

        val request = Request.Builder()
            .url(
                baseUrl +
                    "/api/v3/account?" +
                    params +
                    "&signature=" +
                    signature
            )
            .header("X-MBX-APIKEY", key())
            .get()
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: "{}"
            if (!response.isSuccessful) {
                error("Binance " + response.code + ": " + body)
            }
            return JSONObject(body)
        }
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

    private fun fetchCandles(
        symbol: String,
        frame: String,
        limit: Int = 150
    ): List<CandleN> {
        val encodedSymbol = symbol.uppercase()
        val body = getBody(
            "/api/v3/klines?symbol=" +
                encodedSymbol +
                "&interval=" +
                frame +
                "&limit=" +
                limit
        )

        val array = JSONArray(body)

        val all = List(array.length()) { i ->
            val row = array.getJSONArray(i)
            CandleN(
                t = row.getLong(0),
                o = row.getString(1).toDouble(),
                h = row.getString(2).toDouble(),
                l = row.getString(3).toDouble(),
                c = row.getString(4).toDouble(),
                v = row.getString(5).toDouble()
            )
        }

        val intervalMs = frameSeconds(frame) * 1000L
        return if (
            all.isNotEmpty() &&
            intervalMs > 0L &&
            all.last().t + intervalMs > System.currentTimeMillis()
        ) {
            all.dropLast(1)
        } else {
            all
        }
    }

    private fun loadUniverse(): Triple<List<String>, Map<String, Double>, Map<String, Double>> {
        val info = JSONObject(getBody("/api/v3/exchangeInfo"))
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

        val symbols = tradingUsdt
            .sortedByDescending { volumes[it] ?: 0.0 }
            .take(maxScanSymbols)
            .toMutableList()

        if (!symbols.contains(primarySymbol)) {
            symbols.add(0, primarySymbol)
        }

        return Triple(symbols.distinct(), volumes, spreads)
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

        final.sortByDescending { it.score }

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

                    val protection =
                        createProtection(
                            symbol = intent.symbol,
                            qty = qty,
                            entry = quote / qty,
                            stopDistance = intent.stopDistance
                        )

                    synchronized(positions) {
                        positions[intent.symbol] =
                            PositionState(
                                symbol = intent.symbol,
                                qty = protection.qty,
                                entry = quote / qty,
                                stop = protection.stop,
                                take = protection.take,
                                riskPct = protection.riskPct,
                                ocoListClientId =
                                    protection.ocoClientId
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

    private fun reconcilePositionsWithExchange() {
        if (positions.isEmpty()) {
            savePersistedState()
            return
        }

        val account = signedAccount()
        val balances =
            account.getJSONArray("balances")
        val openLists =
            runCatching {
                signedGet(
                    "/api/v3/openOrderList",
                    ""
                )
            }.getOrNull()

        val updated =
            mutableListOf<PositionState>()

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

            if (total <= stored.qty * 0.02) {
                val exitPrice =
                    runCatching {
                        JSONObject(
                            getBody("/api/v3/ticker/price?symbol=" + stored.symbol)
                        ).optString("price").toDoubleOrNull() ?: stored.entry
                    }.getOrDefault(stored.entry)
                TradeJournal.close(
                    prefs = prefs,
                    symbol = stored.symbol,
                    exitPrice = exitPrice,
                    reason = "OCO_OR_EXCHANGE_EXIT"
                )
                continue
            }

            val delta =
                abs(total - stored.qty) /
                    stored.qty

            if (delta > 0.05) {
                throw IllegalStateException(
                    stored.symbol +
                        " balance mismatch: expected " +
                        stored.qty +
                        ", exchange " +
                        total
                )
            }

            var current =
                stored.copy(
                    qty = min(
                        stored.qty,
                        total
                    )
                )

            val protected =
                stored.ocoListClientId.isNotBlank() &&
                    openListsContains(
                        openLists,
                        stored.symbol,
                        stored.ocoListClientId
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
                            protection.ocoClientId
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
    ): JSONObject {
        val query =
            if (params.isBlank()) {
                "timestamp=" +
                    signedTimestamp() +
                    "&recvWindow=" + BINANCE_RECV_WINDOW_MS
            } else {
                params +
                    "&timestamp=" +
                    signedTimestamp() +
                    "&recvWindow=" + BINANCE_RECV_WINDOW_MS
            }

        val signature = hmac(query, secret())
        val separator =
            if (path.contains("?")) "&" else "?"

        val request =
            Request.Builder()
                .url(
                    baseUrl +
                        path +
                        separator +
                        query +
                        "&signature=" +
                        signature
                )
                .header(
                    "X-MBX-APIKEY",
                    key()
                )
                .get()
                .build()

        http.newCall(request)
            .execute()
            .use { response ->
                val body =
                    response.body?.string()
                        ?: "{}"
                if (!response.isSuccessful) {
                    error(
                        "Binance " +
                            response.code +
                            ": " +
                            body
                    )
                }
                return JSONObject(body)
            }
    }

    private fun signedPost(path: String, params: String): JSONObject {
        val query =
            params + "&timestamp=" + signedTimestamp() + "&recvWindow=" + BINANCE_RECV_WINDOW_MS
        val signature = hmac(query, secret())
        val request = Request.Builder()
            .url(baseUrl + path)
            .header("X-MBX-APIKEY", key())
            .post(
                (query + "&signature=" + signature).toRequestBody("application/x-www-form-urlencoded".toMediaType())
            )
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: "{}"
            if (!response.isSuccessful) error("Binance " + response.code + ": " + body)
            return JSONObject(body)
        }
    }

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
        val ocoClientId: String
    )

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

        val stopDistance =
            (candidate.atrPct * 2.0)
                .coerceIn(0.01, 0.08)

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
                            protection.ocoClientId
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
                    (1.0 + stopDistance * 1.5),
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

        return Protection(
            qty = normalizedQty,
            stop = stop,
            take = take,
            riskPct =
                actualStopDistance +
                    feeBufferPerSidePct * 2.0,
            ocoClientId =
                oco.optString(
                    "listClientOrderId"
                ).ifBlank {
                    ocoClientId
                }
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
            // Cancel this symbol's protective orders first. If cancellation
            // fails, do not send a market SELL into an unknown OCO state.
            signedDelete(
                "/api/v3/openOrders",
                "symbol=" + symbol
            )

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
    ): JSONObject {
        val query =
            params +
                "&timestamp=" +
                signedTimestamp() +
                "&recvWindow=" + BINANCE_RECV_WINDOW_MS

        val signature = hmac(query, secret())
        val separator =
            if (path.contains("?")) "&" else "?"

        val request =
            Request.Builder()
                .url(
                    baseUrl +
                        path +
                        separator +
                        query +
                        "&signature=" +
                        signature
                )
                .header(
                    "X-MBX-APIKEY",
                    key()
                )
                .delete()
                .build()

        http.newCall(request)
            .execute()
            .use { response ->
                val body =
                    response.body?.string()
                        ?: "{}"
                if (!response.isSuccessful) {
                    error(
                        "Binance " +
                            response.code +
                            ": " +
                            body
                    )
                }
                return JSONObject(body)
            }
    }

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
        val breakoutDistance =
            if (fractalHigh != null && fractalHigh > 0.0) {
                (closes[i] - fractalHigh) / fractalHigh
            } else {
                0.0
            }

        val breakout =
            fractalHigh != null &&
                closes[i] > fractalHigh &&
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

    private fun enrichWithMtf(baseCandidate: BaseAnalysis): BaseAnalysis {
        val symbol = baseCandidate.symbol

        val htf = runCatching {
            fetchCandles(symbol, "4h", 160)
        }.getOrNull()

        val daily = runCatching {
            fetchCandles(symbol, "1d", 160)
        }.getOrNull()

        val lower = runCatching {
            fetchCandles(symbol, "15m", 160)
        }.getOrNull()

        val frames = mutableListOf<WaveInfo>()
        frames.add(baseCandidate.wave)

        if (!htf.isNullOrEmpty()) {
            frames.add(waveInfo(htf, "4h"))
        }

        if (!daily.isNullOrEmpty()) {
            frames.add(waveInfo(daily, "1d"))
        }

        if (!lower.isNullOrEmpty()) {
            frames.add(waveInfo(lower, "15m"))
        }

        val setup = baseCandidate.wave

        var bonus = 0.0
        var exhaustion = setup.exhaustionRisk
        var htfConfirmed = false

        htf?.let {
            val info = waveInfo(it, "4h")
            htfConfirmed =
                info.alligatorBullish &&
                    info.aoPositive &&
                    info.direction == "UP"

            if (htfConfirmed) bonus += 8.0
        }

        val dayInfo = daily?.let { waveInfo(it, "1d") }
        if (dayInfo?.direction == "UP") {
            bonus += 4.0
        }

        if (setup.position == 3) {
            bonus += 6.0
            exhaustion = min(exhaustion, 30.0)
        }

        val parentW5 = frames.filter {
            it.position == 5 &&
                it.direction == "UP"
        }

        val childW3 = frames.filter {
            it.position == 3 &&
                it.direction == "UP"
        }

        val nestedW3ParentW5 =
            parentW5.any { parent ->
                childW3.any { child ->
                    frameSeconds(child.path.substringBefore(":")) <
                        frameSeconds(parent.path.substringBefore(":"))
                }
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

        var score =
            (baseCandidate.score + bonus)
                .coerceIn(0.0, 100.0)

        val finalSignal =
            baseCandidate.signal &&
                htfConfirmed &&
                (
                    baseCandidate.wave.position != 5 ||
                        nestedW3ParentW5
                )

        if (!finalSignal && baseCandidate.signal) {
            score = min(score, 84.0)
        }

        val path = frames
            .sortedByDescending {
                frameSeconds(it.path.substringBefore(":"))
            }
            .joinToString(" > ") {
                it.path.substringBefore(":") + ":" +
                    if (it.position > 0) {
                        "W" + it.position
                    } else {
                        "?"
                    }
            }

        val reason =
            when {
                nestedW3ParentW5 ->
                    "MTF: parent W5 contains active W3 below; W5 is not a veto"
                setup.position == 5 ->
                    "MTF: W5/exhaustion context lowers confidence"
                finalSignal ->
                    "MTF confirmed: 4h aligned + 1h Williams breakout"
                else ->
                    baseCandidate.reason
            }

        return baseCandidate.copy(
            score = score,
            signal = finalSignal,
            htfCandidate = htfConfirmed,
            wave = setup.copy(
                confidence =
                    min(
                        100.0,
                        setup.confidence +
                            if (htfConfirmed) 10.0 else 0.0
                    ),
                exhaustionRisk = exhaustion,
                path = path
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

    private fun latestConfirmedUpFractal(
        candles: List<CandleN>,
        currentIndex: Int
    ): Int? {
        val lastConfirmedCenter =
            currentIndex - 2

        if (lastConfirmedCenter < 2) return null

        val start =
            max(2, lastConfirmedCenter - 6)

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
            min(70, candles.size)
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

        var position = 0
        var phase = "UNKNOWN"
        var confidence = 40.0
        var exhaustion = 20.0

        if (pivots.size >= 2) {
            val p = pivots.takeLast(6)
            val sequence =
                p.joinToString("") {
                    if (it.kind == "DOWN") "D" else "U"
                }

            when {
                direction == "UP" &&
                    sequence.endsWith("D") &&
                    p.size >= 5 -> {
                    position = 5
                    phase = "IMPULSE"
                    confidence = 64.0
                    exhaustion = 78.0
                }

                direction == "UP" &&
                    sequence.endsWith("D") &&
                    p.size >= 3 -> {
                    position = 3
                    phase = "IMPULSE"
                    confidence = 72.0
                    exhaustion = 24.0
                }

                direction == "DOWN" &&
                    sequence.endsWith("U") &&
                    p.size >= 5 -> {
                    position = 5
                    phase = "IMPULSE"
                    confidence = 64.0
                    exhaustion = 78.0
                }

                direction == "DOWN" &&
                    sequence.endsWith("U") &&
                    p.size >= 3 -> {
                    position = 3
                    phase = "IMPULSE"
                    confidence = 72.0
                    exhaustion = 24.0
                }

                p.last().kind == "UP" -> {
                    position = 4
                    phase = "CORRECTION"
                    confidence = 58.0
                    exhaustion = 35.0
                }

                p.last().kind == "DOWN" -> {
                    position = 2
                    phase = "CORRECTION"
                    confidence = 58.0
                    exhaustion = 28.0
                }
            }

            val progression =
                if (p.size >= 4) {
                    val a = p[p.size - 4]
                    val b = p[p.size - 3]
                    val c = p[p.size - 2]
                    val d = p[p.size - 1]

                    if (direction == "UP") {
                        b.price > a.price && d.price > c.price
                    } else {
                        b.price < a.price && d.price < c.price
                    }
                } else {
                    false
                }

            if (progression) {
                confidence =
                    min(92.0, confidence + 12.0)
                if (position == 5) {
                    exhaustion =
                        min(95.0, exhaustion + 8.0)
                }
            }
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

    private fun frameSeconds(frame: String): Long =
        when (frame) {
            "15m" -> 900L
            "1h" -> 3600L
            "4h" -> 14400L
            "1d" -> 86400L
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
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val closedAt = row.optLong("closed_at", 0L)
            if (closedAt < start || row.optString("status") != "CLOSED") continue
            when (row.optString("outcome")) {
                "LOSS" -> {
                    consecutiveLosses++
                    if (lastLossAt == 0L) lastLossAt = closedAt
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
            .put("version", "4.14.0")
            .put("cached", true)
            .put("scanning", scanning)
            .put("last_error", lastError ?: JSONObject.NULL)
            .put("last_scan_at", lastScanAt)
            .put("last_scan_duration_ms", lastScanDurationMs)
            .put("symbols_scanned", lastSymbolsScanned)
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
            .put("version", "4.14.0")
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
            .put("scanner_scanning", scanning)
            .put("scanner_symbols", lastSymbolsScanned)
            .put("scanner_last_scan_at", lastScanAt)
            .put("scanner_duration_ms", lastScanDurationMs)
    }

    fun klines(requestedSymbol: String? = null): JSONObject {
        val symbol = requestedSymbol?.trim()?.uppercase(Locale.US)?.takeIf { it.isNotBlank() } ?: primarySymbol
        val sourceCandles = if (symbol == primarySymbol) {
            if (primaryCandles.isEmpty()) runCatching { primaryCandles = fetchCandles(primarySymbol, interval, 150) }
            primaryCandles
        } else {
            runCatching { fetchCandles(symbol, interval, 150) }.getOrElse { emptyList() }
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
                            ao(primaryCandles, i)
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
                                        ao(primaryCandles, i) > 0.0
                                }
                        )
                        .put(
                            "fractal_up",
                            isUpFractal(primaryCandles, i)
                        )
                        .put(
                            "fractal_down",
                            isDownFractal(primaryCandles, i)
                        )
                )
            }
        }

        return JSONObject()
            .put("symbol", primarySymbol)
            .put("interval", interval)
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
            .put("version", "4.14.0")
            .put("symbol", primarySymbol)
            .put("interval", interval)
            .put("position_fraction", 0.95)
            .put("stop_loss_pct", 0.02)
            .put("take_profit_pct", 0.04)
            .put("poll_seconds", 15)
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
            .put("wave_top_n", waveTopN)
            .put("trade_journal", true)
            .put("trade_journal_max_rows", 500)
}
