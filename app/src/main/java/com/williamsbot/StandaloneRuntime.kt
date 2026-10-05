package com.williamsbot

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
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
            server = StandaloneServer(context.applicationContext)
            server!!.start()
        }
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
                x.klines().toString()

            method == "GET" && path == "/api/v1/snapshot" ->
                x.snapshot().toString()

            method == "GET" && path == "/api/v1/scanner" ->
                x.scanner(
                    refresh = params["refresh"].equals("true", true)
                ).toString()

            method == "GET" && path == "/api/v1/trades" ->
                x.trades().toString()

            method == "GET" && path == "/api/v1/insights" ->
                x.insights().toString()

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

            method == "POST" && path == "/api/v1/control/recover" ->
                x.recover().toString()

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

    private val maxScanSymbols = 120
    private val waveTopN = 12
    private val scanExecutor = Executors.newFixedThreadPool(12)

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
    @Volatile private var positionSymbol: String? = null
    @Volatile private var positionQty = 0.0
    @Volatile private var positionEntry = 0.0
    @Volatile private var lastOrder: JSONObject? = null

    private fun key(): String =
        prefs.getString("api_key", "") ?: ""

    private fun secret(): String =
        prefs.getString("api_secret", "") ?: ""

    private fun tradeHistory(): JSONArray {
        return runCatching {
            JSONArray(prefs.getString("trade_history", "[]") ?: "[]")
        }.getOrDefault(JSONArray())
    }

    private fun saveTradeHistory(history: JSONArray) {
        prefs.edit().putString("trade_history", history.toString()).apply()
    }

    private fun addTrade(record: JSONObject) {
        val old = tradeHistory()
        val out = JSONArray().put(record)
        for (i in 0 until old.length()) {
            val item = old.optJSONObject(i) ?: continue
            out.put(item)
        }
        while (out.length() > 500) out.remove(out.length() - 1)
        saveTradeHistory(out)
    }

    private fun recordEntry(candidate: BaseAnalysis, buy: JSONObject, qty: Double, entry: Double, stop: Double, take: Double, notional: Double) {
        val wave = candidate.wave
        addTrade(
            JSONObject()
                .put("id", buy.optLong("orderId", System.currentTimeMillis()))
                .put("entry_time", System.currentTimeMillis())
                .put("exit_time", JSONObject.NULL)
                .put("symbol", candidate.symbol)
                .put("side", "LONG")
                .put("entry_price", entry)
                .put("quantity", qty)
                .put("score", candidate.score)
                .put("setup_score", candidate.score)
                .put("risk_pct", candidate.riskPct)
                .put("risk_reward", candidate.riskReward)
                .put("atr_pct", candidate.atrPct)
                .put("spread_pct", candidate.spreadPct)
                .put("htf_confirmed", candidate.htfCandidate)
                .put("wave_position", wave.position)
                .put("wave_phase", wave.phase)
                .put("wave_confidence", wave.confidence)
                .put("wave_exhaustion_risk", wave.exhaustionRisk)
                .put("nested_w3_parent_w5", wave.path.contains("W5") && wave.path.contains("W3"))
                .put("wave_path", wave.path)
                .put("reason", candidate.reason)
                .put("planned_stop", stop)
                .put("planned_take_profit", take)
                .put("notional_usdt", notional)
                .put("best_price", entry)
                .put("worst_price", entry)
                .put("mfe_pct", 0.0)
                .put("mae_pct", 0.0)
        )
        TradeNotificationHelper.notify(
            context,
            "Williams: сделка открыта",
            candidate.symbol + " LONG • entry " + "%.8f".format(Locale.US, entry) +
                " • Score " + "%.1f".format(Locale.US, candidate.score) +
                " • W" + wave.position,
            30000 + buy.optInt("orderId", 0)
        )
    }

    private fun observeOpenTrade(price: Double) {
        if (price <= 0.0) return
        val history = tradeHistory()
        if (history.length() == 0) return
        val open = (0 until history.length())
            .mapNotNull { history.optJSONObject(it) }
            .firstOrNull {
                val exit = it.opt("exit_time")
                exit == null || exit == JSONObject.NULL || it.optString("exit_time").isBlank()
            } ?: return

        val entry = open.optDouble("entry_price", 0.0)
        if (entry <= 0.0) return
        val best = max(open.optDouble("best_price", entry), price)
        val worst = min(open.optDouble("worst_price", entry), price)
        open.put("best_price", best)
        open.put("worst_price", worst)
        open.put("mfe_pct", ((best - entry) / entry) * 100.0)
        open.put("mae_pct", ((worst - entry) / entry) * 100.0)
        saveTradeHistory(history)
    }

    private fun recordExit(exitPrice: Double, reason: String, order: JSONObject?) {
        val history = tradeHistory()
        if (history.length() == 0) return
        val index = (0 until history.length()).firstOrNull {
            val x = history.optJSONObject(it)
            x != null && (x.opt("exit_time") == null || x.opt("exit_time") == JSONObject.NULL) &&
                x.optString("symbol") == positionSymbol
        } ?: return
        val trade = history.getJSONObject(index)
        val entry = trade.optDouble("entry_price", 0.0)
        val qty = trade.optDouble("quantity", positionQty)
        val pnl = (exitPrice - entry) * qty
        val pnlPct = if (entry > 0) (exitPrice / entry - 1.0) * 100.0 else 0.0
        val diagnosis = when {
            pnl >= 0.0 && trade.optInt("wave_position", 0) == 3 -> "WAVE_3_SETUP_WORKED"
            pnl >= 0.0 -> "SETUP_WORKED"
            trade.optDouble("wave_exhaustion_risk", 0.0) >= 70.0 ||
                trade.optInt("wave_position", 0) == 5 -> "WAVE_EXHAUSTION"
            !trade.optBoolean("htf_confirmed", false) -> "HTF_CONFLICT"
            pnl < 0.0 -> "FALSE_BREAKOUT_OR_REGIME_SHIFT"
            else -> "UNKNOWN"
        }
        trade.put("exit_time", System.currentTimeMillis())
            .put("exit_price", exitPrice)
            .put("pnl", pnl)
            .put("pnl_pct", pnlPct)
            .put("reason", reason)
            .put("diagnosis", diagnosis)
            .put("order_type", order?.optString("type", ""))
            .put("duration_seconds", (System.currentTimeMillis() - trade.optLong("entry_time", System.currentTimeMillis())) / 1000.0)
        saveTradeHistory(history)

        val result = if (pnl >= 0.0) "прибыль" else "убыток"
        TradeNotificationHelper.notify(
            context,
            "Williams: сделка закрыта",
            trade.optString("symbol") + " • " + result + " " +
                "%.4f".format(Locale.US, pnl) + " USDT • " + diagnosis,
            40000 + trade.optInt("id", 0)
        )
    }

    fun insights(): JSONObject {
        val history = tradeHistory()
        var wins = 0
        var losses = 0
        var totalPnl = 0.0
        val diagnoses = mutableMapOf<String, Int>()
        for (i in 0 until history.length()) {
            val t = history.optJSONObject(i) ?: continue
            if (t.opt("exit_time") == null || t.opt("exit_time") == JSONObject.NULL) continue
            val pnl = t.optDouble("pnl", 0.0)
            totalPnl += pnl
            if (pnl > 0) wins++ else if (pnl < 0) losses++
            val d = t.optString("diagnosis", "UNCLASSIFIED")
            diagnoses[d] = (diagnoses[d] ?: 0) + 1
        }
        val total = wins + losses
        val dJson = JSONObject()
        diagnoses.forEach { (k,v) -> dJson.put(k,v) }
        return JSONObject()
            .put("total", total)
            .put("wins", wins)
            .put("losses", losses)
            .put("win_rate", if (total > 0) wins.toDouble()/total else 0.0)
            .put("pnl", totalPnl)
            .put("diagnoses", dJson)
    }

    fun health(): JSONObject =
        JSONObject()
            .put("ok", true)
            .put("service", "williams-native")
            .put("version", "4.13.0")
            .put("standalone", true)
            .put("websocket", false)
            .put("execution_enabled", true)
            .put("position_symbol", positionSymbol ?: "")
            .put("position_qty", positionQty)
            .put("position_entry", positionEntry)
            .put("last_order", lastOrder ?: JSONObject.NULL)
            .put(
                "auth_configured",
                key().isNotBlank() && secret().isNotBlank()
            )

    fun configure(j: JSONObject): JSONObject {
        val newKey = j.optString("api_key").trim()
        val newSecret = j.optString("api_secret").trim()

        prefs.edit()
            .putString("api_key", newKey)
            .putString("api_secret", newSecret)
            .apply()

        return JSONObject()
            .put(
                "configured",
                key().isNotBlank() && secret().isNotBlank()
            )
            .put("testnet", true)
            .put("standalone", true)
    }

    fun clear(): JSONObject {
        stop()
        prefs.edit()
            .remove("api_key")
            .remove("api_secret")
            .apply()

        candidates = JSONArray()
        primaryCandles = emptyList()
        scanSymbols = mutableListOf()

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
                    Thread.sleep(20_000L)
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
            .put("interval_seconds", 20)
    }

    fun stop(): JSONObject {
        running = false
        paused = false
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

    fun recover(): JSONObject =
        JSONObject()
            .put("recovered", true)
            .put("state", "FLAT")
            .put("execution_enabled", false)

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

    private fun signedGetBody(path: String, params: String): String {
        val query = if (params.isBlank()) {
            "timestamp=" + System.currentTimeMillis() + "&recvWindow=5000"
        } else {
            params + "&timestamp=" + System.currentTimeMillis() + "&recvWindow=5000"
        }
        val signature = hmac(query, secret())
        val request = Request.Builder()
            .url(baseUrl + path + "?" + query + "&signature=" + signature)
            .header("X-MBX-APIKEY", key())
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: "{}"
            if (!response.isSuccessful) error("Binance " + response.code + ": " + body)
            return body
        }
    }

    private fun signedAccount(): JSONObject {
        val timestamp = System.currentTimeMillis().toString()
        val params =
            "timestamp=" + timestamp + "&recvWindow=5000"
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
        if (key().isNotBlank() && secret().isNotBlank()) {
            runCatching { reconcilePosition() }
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

        val waveFutures = waveTargets.map { baseCandidate ->
            scanExecutor.submit(Callable {
                runCatching { enrichWithMtf(baseCandidate) }.getOrNull()
            })
        }
        val final = mutableListOf<BaseAnalysis>()
        waveFutures.forEach { future ->
            runCatching { future.get() }.getOrNull()?.let { final.add(it) }
        }

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

        runCatching {
            primaryCandles = fetchCandles(primarySymbol, interval, 150)
        }

        if (
            key().isNotBlank() &&
            secret().isNotBlank()
        ) {
            runCatching { signedAccount() }

            val best = final
                .filter { it.signal }
                .maxByOrNull { it.score }

            if (
                running &&
                !paused &&
                positionSymbol == null &&
                best != null &&
                best.score >= 70.0
            ) {
                runCatching {
                    executeBuyWithProtection(best)
                }.onFailure {
                    lastError =
                        "ORDER: " + (it.message ?: it.javaClass.simpleName)
                }
            }
        }
    }

    private fun reconcilePosition() {
        val symbol = positionSymbol ?: return
        val asset = symbol.removeSuffix("USDT")
        val account = signedAccount()
        val balances = account.getJSONArray("balances")
        var free = 0.0
        for (i in 0 until balances.length()) {
            val b = balances.getJSONObject(i)
            if (b.optString("asset") == asset) {
                free = b.optString("free").toDoubleOrNull() ?: 0.0
                break
            }
        }

        if (free <= 0.0) {
            val closedSymbol = positionSymbol
            val closedEntry = positionEntry
            val recentOrder = runCatching {
                val body = signedGetBody(
                    "/api/v3/allOrders",
                    "symbol=" + closedSymbol + "&limit=50"
                )
                val arr = JSONArray(body)
                var chosen: JSONObject? = null
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optString("side") == "SELL" && o.optString("status") == "FILLED") {
                        chosen = o
                    }
                }
                chosen
            }.getOrNull()
            val exitPrice = recentOrder?.let {
                val q = it.optString("executedQty").toDoubleOrNull() ?: 0.0
                val z = it.optString("cummulativeQuoteQty").toDoubleOrNull() ?: 0.0
                if (q > 0.0 && z > 0.0) z / q else it.optString("price").toDoubleOrNull() ?: 0.0
            } ?: 0.0
            if (closedSymbol != null && closedEntry > 0.0 && exitPrice > 0.0) {
                val type = recentOrder?.optString("type", "") ?: ""
                val reason = when {
                    type.contains("TAKE_PROFIT", true) -> "TAKE_PROFIT"
                    type.contains("STOP", true) -> "STOP_LOSS"
                    else -> "SELL_FILLED"
                }
                observeOpenTrade(exitPrice)
                recordExit(exitPrice, reason, recentOrder)
            }
            positionSymbol = null
            positionQty = 0.0
            positionEntry = 0.0
            prefs.edit()
                .remove("position_symbol")
                .remove("position_qty")
                .remove("position_entry")
                .remove("position_stop")
                .remove("position_take")
                .apply()
            return
        }

        positionQty = free
        prefs.edit()
            .putString("position_qty", free.toString())
            .apply()
    }

    private fun signedPost(path: String, params: String): JSONObject {
        val query =
            params + "&timestamp=" + System.currentTimeMillis() + "&recvWindow=5000"
        val signature = hmac(query, secret())
        val request = Request.Builder()
            .url(baseUrl + path)
            .header("X-MBX-APIKEY", key())
            .post(
                okhttp3.RequestBody.create(
                    MediaType.parse("application/x-www-form-urlencoded"),
                    query + "&signature=" + signature
                )
            )
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: "{}"
            if (!response.isSuccessful) error("Binance " + response.code + ": " + body)
            return JSONObject(body)
        }
    }

    private fun symbolFilters(symbol: String): Triple<Double, Double, Int> {
        val info = JSONObject(getBody("/api/v3/exchangeInfo?symbol=" + symbol))
        val filters = info.getJSONArray("symbols").getJSONObject(0).getJSONArray("filters")
        var step = 0.000001
        var minQty = 0.0
        var priceTick = 0.000001
        for (i in 0 until filters.length()) {
            val f = filters.getJSONObject(i)
            when (f.optString("filterType")) {
                "LOT_SIZE" -> {
                    step = f.optString("stepSize").toDoubleOrNull() ?: step
                    minQty = f.optString("minQty").toDoubleOrNull() ?: minQty
                }
                "PRICE_FILTER" -> priceTick = f.optString("tickSize").toDoubleOrNull() ?: priceTick
            }
        }
        val decimals = max(0, step.toString().substringAfter('.', "").trimEnd('0').length)
        return Triple(step, priceTick, decimals)
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

    private fun executeBuyWithProtection(candidate: BaseAnalysis) {
        if (candidate.spreadPct > 0.0015) error("spread too wide")
        if (candidate.atrPct <= 0.0 || candidate.atrPct > 0.08) error("ATR outside safety range")
        if (!candidate.signal) error("signal not confirmed")

        val account = signedAccount()
        var usdtFree = 0.0
        val balances = account.getJSONArray("balances")
        for (i in 0 until balances.length()) {
            val b = balances.getJSONObject(i)
            if (b.optString("asset") == "USDT") {
                usdtFree = b.optString("free").toDoubleOrNull() ?: 0.0
                break
            }
        }
        if (usdtFree < 25.0) error("USDT balance too low")

        val riskBudget = usdtFree * 0.01
        val stopDistance = (candidate.atrPct * 2.0).coerceIn(0.01, 0.08)
        val notional = min(usdtFree * 0.10, riskBudget / stopDistance)
        if (notional < 10.0) error("order notional too small")

        val buy = signedPost(
            "/api/v3/order",
            "symbol=" + candidate.symbol +
                "&side=BUY&type=MARKET&quoteOrderQty=" +
                "%.2f".format(java.util.Locale.US, notional)
        )

        val fills = buy.optJSONArray("fills")
        var qtyFilled = 0.0
        var cost = 0.0
        if (fills != null) {
            for (i in 0 until fills.length()) {
                val f = fills.getJSONObject(i)
                val fq = f.optString("qty").toDoubleOrNull() ?: 0.0
                val fp = f.optString("price").toDoubleOrNull() ?: 0.0
                qtyFilled += fq
                cost += fq * fp
            }
        }
        val entry = if (qtyFilled > 0.0) cost / qtyFilled else 0.0
        if (entry <= 0.0) error("BUY returned no fill price")

        val (step, tick, decimals) = symbolFilters(candidate.symbol)
        val qty = floorStep(qtyFilled, step)
        if (qty <= 0.0) error("filled quantity below step")

        val stop = entry * (1.0 - stopDistance)
        val take = entry * (1.0 + stopDistance * 1.5)
        val stopLimit = stop * 0.999

        val oco = try {
            signedPost(
                "/api/v3/order/oco",
                "symbol=" + candidate.symbol +
                    "&side=SELL&quantity=" + fmtQty(qty, decimals) +
                    "&price=" + fmtPrice(take, tick) +
                    "&stopPrice=" + fmtPrice(stop, tick) +
                    "&stopLimitPrice=" + fmtPrice(stopLimit, tick) +
                    "&stopLimitTimeInForce=GTC"
            )
        } catch (ocoError: Exception) {
            // Never leave an unprotected Testnet position after a failed OCO.
            runCatching {
                signedPost(
                    "/api/v3/order",
                    "symbol=" + candidate.symbol +
                        "&side=SELL&type=MARKET&quantity=" +
                        fmtQty(qty, decimals)
                )
            }
            throw IllegalStateException(
                "OCO failed; emergency SELL attempted: " +
                    (ocoError.message ?: "unknown error")
            )
        }

        positionSymbol = candidate.symbol
        positionQty = qty
        positionEntry = entry
        prefs.edit()
            .putString("position_symbol", candidate.symbol)
            .putString("position_qty", qty.toString())
            .putString("position_entry", entry.toString())
            .putString("position_stop", stop.toString())
            .putString("position_take", take.toString())
            .apply()
        lastOrder = JSONObject()
            .put("buy", buy)
            .put("oco", oco)
            .put("risk_usdt", riskBudget)
            .put("notional_usdt", notional)
            .put("stop_price", stop)
            .put("take_profit", take)
        recordEntry(candidate, buy, qty, entry, stop, take, notional)
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

    private fun scannerSnapshot(): JSONObject =
        JSONObject()
            .put("version", "4.13.0")
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

        return JSONObject()
            .put("version", "4.13.0")
            .put("symbol", primarySymbol)
            .put("interval", interval)
            .put("testnet", true)
            .put("running", running)
            .put("paused", paused)
            .put("recovered", true)
            .put("state", if (positionSymbol == null) "FLAT" else "LONG")
            .put("execution_enabled", true)
            .put("position_symbol", positionSymbol ?: JSONObject.NULL)
            .put("position_qty", if (positionSymbol == null) JSONObject.NULL else positionQty)
            .put("position_entry", if (positionSymbol == null) JSONObject.NULL else positionEntry)
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
                if (positionSymbol == null) JSONObject.NULL else
                    JSONObject()
                        .put("symbol", positionSymbol)
                        .put("quantity", positionQty)
                        .put("entry_price", positionEntry)
            )
            .put("pnl", if (positionSymbol != null && primaryCandles.isNotEmpty())
                (primaryCandles.last().c - positionEntry) * positionQty else JSONObject.NULL)
            .put("pnl_pct", if (positionSymbol != null && positionEntry > 0.0 && primaryCandles.isNotEmpty())
                primaryCandles.last().c / positionEntry - 1.0 else JSONObject.NULL)
            .put("take_profit_price", prefs.getString("position_take", null)?.toDoubleOrNull() ?: JSONObject.NULL)
            .put("stop_loss_price", prefs.getString("position_stop", null)?.toDoubleOrNull() ?: JSONObject.NULL)
            .put("stop_loss_pct", 0.02)
            .put("take_profit_pct", 0.04)
            .put("risk_per_trade_pct", 0.01)
            .put("max_daily_loss_pct", 0.03)
            .put("max_trades_per_day", 5)
            .put("consecutive_losses", 0)
            .put("max_consecutive_losses", 3)
            .put("trades_today", 0)
            .put("server_time", System.currentTimeMillis())
            .put("scanner_scanning", scanning)
            .put("scanner_symbols", lastSymbolsScanned)
            .put("scanner_last_scan_at", lastScanAt)
            .put("scanner_duration_ms", lastScanDurationMs)
    }

    fun snapshot(): JSONObject {
        return JSONObject()
            .put("status", status())
            .put("klines", klines())
            .put("scanner", scanner(false))
            .put("trades", trades())
            .put("insights", insights())
            .put("logs", logs())
    }

    fun klines(): JSONObject {
        if (primaryCandles.isEmpty()) {
            runCatching {
                primaryCandles =
                    fetchCandles(primarySymbol, interval, 150)
            }
        }

        val output = JSONArray()
        val prices =
            primaryCandles.map { it.c }

        if (prices.isNotEmpty()) {
            val jaw = smma(prices, 13)
            val teeth = smma(prices, 8)
            val lips = smma(prices, 5)

            val start =
                max(0, primaryCandles.size - 120)

            for (i in start until primaryCandles.size) {
                val c = primaryCandles[i]
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

    fun trades(): JSONArray = tradeHistory()

    fun logs(): JSONArray =
        JSONArray().put(
            JSONObject()
                .put("created_at", System.currentTimeMillis())
                .put("level", "INFO")
                .put(
                    "message",
                    if (scanning) {
                        "Standalone scanner is running; TESTNET; execution enabled"
                    } else {
                        "Standalone native engine active; TESTNET; execution enabled"
                    }
                )
        )

    fun settings(): JSONObject =
        JSONObject()
            .put("version", "4.13.0")
            .put("symbol", primarySymbol)
            .put("interval", interval)
            .put("position_fraction", 0.95)
            .put("stop_loss_pct", 0.02)
            .put("take_profit_pct", 0.04)
            .put("poll_seconds", 15)
            .put("risk_per_trade_pct", 0.01)
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
            .put("execution_enabled", false)
            .put("max_scan_symbols", maxScanSymbols)
            .put("wave_top_n", waveTopN)
}
