package com.williamsbot

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WilliamsApp(this) }
    }
}

data class Status(
    val symbol: String = "-",
    val interval: String = "-",
    val testnet: Boolean = true,
    val running: Boolean = false,
    val paused: Boolean = false,
    val recovered: Boolean = false,
    val state: String = "FLAT",
    val price: Double? = null,
    val balance: Double? = null,
    val qty: Double? = null,
    val entry: Double? = null,
    val tp: Double? = null,
    val sl: Double? = null,
    val pnl: Double? = null,
    val pnlPct: Double? = null,
    val error: String? = null,
    val serverTime: String = "",
    val wsConnected: Boolean = false,
    val binanceConfigured: Boolean = false,
    val riskPerTrade: Double = 0.01,
    val maxDailyLoss: Double = 0.03,
    val tradesToday: Int = 0,
    val maxTrades: Int = 5,
    val consecutiveLosses: Int = 0,
    val maxConsecutiveLosses: Int = 3
)

data class Candle(
    val time: String,
    val open: Double,
    val close: Double,
    val high: Double,
    val low: Double,
    val jaw: Double?,
    val teeth: Double?,
    val lips: Double?,
    val longSignal: Boolean,
    val fractalUp: Boolean,
    val fractalDown: Boolean
)

data class Candidate(
    val symbol: String,
    val score: Double,
    val signal: Boolean,
    val setupScore: Double,
    val signalStrength: String,
    val breakoutDistancePct: Double,
    val riskPct: Double,
    val riskReward: Double,
    val atrPct: Double,
    val spreadPct: Double,
    val htfConfirmed: Boolean,
    val setupState: String,
    val reason: String
)

data class Trade(
    val id: String,
    val side: String,
    val entry: Double?,
    val exit: Double?,
    val pnl: Double?,
    val reason: String
)

class SecureStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "williams_secure",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun get(key: String, default: String = "") = prefs.getString(key, default) ?: default
    fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    fun getBool(key: String, default: Boolean) = prefs.getBoolean(key, default)
    fun putBool(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
}

class Api(private val base: String, private val token: String) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun get(path: String) = request("GET", path, null)
    fun post(path: String, body: String? = null) = request("POST", path, body)
    fun delete(path: String) = request("DELETE", path, null)

    private fun request(method: String, path: String, body: String?): String {
        val builder = Request.Builder()
            .url(base.trimEnd('/') + path)
            .header("Authorization", "Bearer $token")

        val requestBody = body?.toRequestBody("application/json".toMediaType())
        val request = builder
            .method(method, if (method == "POST") requestBody ?: "".toRequestBody(null) else null)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code}: ${response.body?.string()}")
            }
            return response.body?.string() ?: "{}"
        }
    }
}

class ReconnectingSocket(
    private val client: OkHttpClient,
    private val request: Request,
    private val onMessage: (JSONObject) -> Unit,
    private val onState: (Boolean, String?) -> Unit
) : WebSocketListener() {
    @Volatile private var stopped = true
    private var socket: WebSocket? = null
    private var attempt = 0
    private val scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()

    fun start() {
        stopped = false
        attempt = 0
        connect()
    }

    fun stop() {
        stopped = true
        socket?.close(1000, "screen closed")
        scheduler.shutdownNow()
    }

    private fun connect() {
        if (!stopped) socket = client.newWebSocket(request, this)
    }

    private fun schedule(reason: String) {
        if (stopped) return
        val delay = (1000L shl attempt.coerceAtMost(5)).coerceAtMost(30_000L)
        attempt++
        onState(false, reason)
        scheduler.schedule({ connect() }, delay, TimeUnit.MILLISECONDS)
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        attempt = 0
        socket = webSocket
        onState(true, null)
        webSocket.send("ping")
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        runCatching { onMessage(JSONObject(text)) }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        schedule("WebSocket закрывается ($code)")
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        schedule("WebSocket закрыт ($code)")
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        schedule(t.message ?: "WebSocket error")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WilliamsApp(context: Context) {
    val store = remember { SecureStore(context) }
    var host by remember { mutableStateOf(store.get("host", "http://localhost:8000")) }
    var token by remember { mutableStateOf(store.get("token", "")) }
    var apiKey by remember { mutableStateOf("") }
    var apiSecret by remember { mutableStateOf("") }
    var testnet by remember { mutableStateOf(store.getBool("testnet", true)) }

    var status by remember { mutableStateOf(Status(testnet = testnet)) }
    var candles by remember { mutableStateOf(emptyList<Candle>()) }
    var candidates by remember { mutableStateOf(emptyList<Candidate>()) }
    var trades by remember { mutableStateOf(emptyList<Trade>()) }
    var logs by remember { mutableStateOf(emptyList<String>()) }
    var message by remember { mutableStateOf("Подключение…") }
    var tab by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }

    val scanner = remember { GmsBarcodeScanning.getClient(context) }
    val scope = rememberCoroutineScope()
    val httpClient = remember {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    fun api() = Api(host, token)

    fun applySnapshot(json: JSONObject) {
        val position = json.optJSONObject("position")
        status = status.copy(
            symbol = json.optString("symbol", status.symbol),
            interval = json.optString("interval", status.interval),
            testnet = json.optBoolean("testnet", status.testnet),
            running = json.optBoolean("running", status.running),
            paused = json.optBoolean("paused", status.paused),
            recovered = json.optBoolean("recovered", status.recovered),
            state = json.optString("state", status.state),
            price = json.optDouble("price").takeUnless { it.isNaN() },
            balance = json.optDouble("quote_balance").takeUnless { it.isNaN() },
            qty = position?.optDouble("quantity")?.takeUnless { it.isNaN() },
            entry = position?.optDouble("entry_price")?.takeUnless { it.isNaN() },
            tp = json.optDouble("take_profit_price").takeUnless { it.isNaN() || it == 0.0 },
            sl = json.optDouble("stop_loss_price").takeUnless { it.isNaN() || it == 0.0 },
            pnl = json.optDouble("pnl").takeUnless { it.isNaN() },
            pnlPct = json.optDouble("pnl_pct").takeUnless { it.isNaN() },
            error = json.optString("last_error").takeIf { it.isNotBlank() },
            serverTime = json.optString("server_time", status.serverTime),
            binanceConfigured = json.optBoolean("binance_configured", status.binanceConfigured),
            riskPerTrade = json.optDouble("risk_per_trade_pct", status.riskPerTrade),
            maxDailyLoss = json.optDouble("max_daily_loss_pct", status.maxDailyLoss),
            tradesToday = json.optInt("trades_today", status.tradesToday),
            maxTrades = json.optInt("max_trades_per_day", status.maxTrades),
            consecutiveLosses = json.optInt("consecutive_losses", status.consecutiveLosses),
            maxConsecutiveLosses = json.optInt("max_consecutive_losses", status.maxConsecutiveLosses)
        )

        json.optJSONArray("candles")?.let { array ->
            candles = List(array.length()) { i ->
                val x = array.getJSONObject(i)
                Candle(
                    x.optString("time"),
                    x.optDouble("open"),
                    x.optDouble("close"),
                    x.optDouble("high"),
                    x.optDouble("low"),
                    x.optDouble("jaw").takeUnless { it.isNaN() },
                    x.optDouble("teeth").takeUnless { it.isNaN() },
                    x.optDouble("lips").takeUnless { it.isNaN() },
                    x.optBoolean("long_signal"),
                    x.optBoolean("fractal_up"),
                    x.optBoolean("fractal_down")
                )
            }
        }
    }

    fun parseCandidates(array: JSONArray): List<Candidate> =
        List(array.length()) { i ->
            val x = array.getJSONObject(i)
            Candidate(
                symbol = x.optString("symbol", "-"),
                score = x.optDouble("score", 0.0),
                signal = x.optBoolean("signal"),
                setupScore = x.optDouble("setup_score", 0.0),
                signalStrength = x.optString("signal_strength", ""),
                breakoutDistancePct = x.optDouble("breakout_distance_pct", 0.0),
                riskPct = x.optDouble("risk_pct", 0.0),
                riskReward = x.optDouble("risk_reward", 0.0),
                atrPct = x.optDouble("atr_pct", 0.0),
                spreadPct = x.optDouble("spread_pct", 0.0),
                htfConfirmed = x.optBoolean("htf_confirmed"),
                setupState = x.optString("setup_state", "WATCHING"),
                reason = x.optString("reason", "")
            )
        }.sortedByDescending { it.score }

    fun refresh(scannerRefresh: Boolean = false) {
        if (host.isBlank() || token.isBlank()) return
        scope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { refreshing = true }
                val a = api()
                val statusJson = JSONObject(a.get("/api/v1/status"))
                val klinesJson = JSONObject(a.get("/api/v1/market/klines"))
                val snapshot = JSONObject(statusJson.toString()).apply {
                    put("candles", klinesJson.getJSONArray("candles"))
                }
                val scannerJson = JSONArray(
                    a.get("/api/v1/scanner?refresh=$scannerRefresh")
                )
                val tradesJson = JSONArray(a.get("/api/v1/trades"))
                val logsJson = JSONArray(a.get("/api/v1/logs"))

                val newTrades = List(tradesJson.length()) { i ->
                    val x = tradesJson.getJSONObject(i)
                    Trade(
                        x.optString("id"),
                        x.optString("side"),
                        x.optDouble("entry_price").takeUnless { it.isNaN() },
                        x.optDouble("exit_price").takeUnless { it.isNaN() },
                        x.optDouble("pnl").takeUnless { it.isNaN() },
                        x.optString("reason")
                    )
                }

                val newLogs = List(logsJson.length()) { i ->
                    val x = logsJson.getJSONObject(i)
                    "${x.optString("created_at")} ${x.optString("level")} ${x.optString("message")}"
                }

                withContext(Dispatchers.Main) {
                    applySnapshot(snapshot)
                    candidates = parseCandidates(scannerJson)
                    trades = newTrades
                    logs = newLogs
                    refreshing = false
                    message = "Данные обновлены"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    refreshing = false
                    message = e.message ?: "Ошибка соединения"
                }
            }
        }
    }

    fun command(path: String) {
        scope.launch(Dispatchers.IO) {
            try {
                api().post(path)
                refresh()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    message = e.message ?: "Ошибка команды"
                }
            }
        }
    }

    fun applyPairing(raw: String) {
        try {
            val uri = Uri.parse(raw.trim())
            if (uri.scheme != "williams" || uri.host != "connect") {
                throw IllegalArgumentException("Неверный QR-код Williams")
            }

            val newHost = uri.getQueryParameter("url")
                ?.trim()
                ?.trimEnd('/')
                ?: throw IllegalArgumentException("В QR нет адреса сервера")

            val newToken = uri.getQueryParameter("token")
                ?.trim()
                ?: throw IllegalArgumentException("В QR нет токена")

            require(
                newHost.startsWith("https://") ||
                    newHost.startsWith("http://localhost:")
            ) {
                "Удалённое подключение требует HTTPS; HTTP разрешён только для локального телефона"
            }
            require(newToken.length >= 32) { "Неверный Mobile API Token" }

            host = newHost
            token = newToken
            store.put("host", newHost)
            store.put("token", newToken)
            message = "Сервер подключён через QR"
            tab = 0
        } catch (e: Exception) {
            message = e.message ?: "Не удалось прочитать QR"
        }
    }

    fun scanPairing() {
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                barcode.rawValue?.let(::applyPairing)
            }
            .addOnFailureListener { e ->
                message = e.message ?: "QR-сканер недоступен"
            }
    }

    fun configure() {
        scope.launch(Dispatchers.IO) {
            try {
                val body = JSONObject()
                    .put("api_key", apiKey)
                    .put("api_secret", apiSecret)
                    .put("testnet", testnet)
                    .toString()

                api().post("/api/v1/config/binance", body)
                store.put("host", host)
                store.put("token", token)
                store.putBool("testnet", testnet)
                apiKey = ""
                apiSecret = ""

                withContext(Dispatchers.Main) {
                    message = "Binance ключи приняты сервером (Testnet=$testnet)"
                }
                refresh()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    message = e.message ?: "Ошибка конфигурации"
                }
            }
        }
    }

    DisposableEffect(host, token) {
        if (token.isBlank()) {
            onDispose { }
        } else {
            refresh()

            val wsUrl = host.trimEnd('/')
                .replaceFirst(Regex("^http://"), "ws://")
                .replaceFirst(Regex("^https://"), "wss://") +
                "/api/v1/ws"

            val listener = ReconnectingSocket(
                httpClient,
                Request.Builder()
                    .url(wsUrl)
                    .header("Authorization", "Bearer $token")
                    .build(),
                { root ->
                    scope.launch(Dispatchers.Main) {
                        when (root.optString("type")) {
                            "snapshot", "ticker", "account" -> {
                                root.optJSONObject("data")?.let { applySnapshot(it) }
                                if (root.optString("type") == "snapshot") {
                                    message = "WebSocket: realtime"
                                }
                            }
                            "candle" -> {
                                root.optJSONObject("data")?.let { x ->
                                    val candle = Candle(
                                        x.optString("time"),
                                        x.optDouble("open"),
                                        x.optDouble("close"),
                                        x.optDouble("high"),
                                        x.optDouble("low"),
                                        x.optDouble("jaw").takeUnless { it.isNaN() },
                                        x.optDouble("teeth").takeUnless { it.isNaN() },
                                        x.optDouble("lips").takeUnless { it.isNaN() },
                                        x.optBoolean("long_signal"),
                                        x.optBoolean("fractal_up"),
                                        x.optBoolean("fractal_down")
                                    )
                                    candles = (candles.filterNot { it.time == candle.time } + candle)
                                        .takeLast(120)
                                }
                            }
                        }
                    }
                },
                { connected, error ->
                    scope.launch(Dispatchers.Main) {
                        status = status.copy(
                            wsConnected = connected,
                            error = error
                        )
                        if (connected) message = "WebSocket: realtime"
                        else if (error != null) message = error
                    }
                }
            )

            listener.start()
            onDispose { listener.stop() }
        }
    }

    LaunchedEffect(Unit) {
        if (token.isNotBlank() && host.isNotBlank()) refresh()
    }

    LaunchedEffect(tab) {
        if (tab == 1 || tab == 2 || tab == 3) refresh()
    }

    val colors = darkColorScheme(
        primary = Color(0xFF4DD0E1),
        secondary = Color(0xFF7E8CFF),
        tertiary = Color(0xFF62E6A7),
        error = Color(0xFFFF667A),
        background = Color(0xFF080B12),
        surface = Color(0xFF111621),
        surfaceVariant = Color(0xFF1A2230)
    )

    MaterialTheme(colorScheme = colors) {
        if (host.isBlank() || token.isBlank()) {
            SetupScreen(
                testnet = testnet,
                onTestnetChange = { testnet = it },
                onScan = ::scanPairing,
                host = host,
                onHost = { host = it },
                token = token,
                onToken = { token = it },
                apiKey = apiKey,
                onApiKey = { apiKey = it },
                apiSecret = apiSecret,
                onApiSecret = { apiSecret = it },
                onConfigure = ::configure,
                message = message
            )
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text("Williams Trader")
                                Text(
                                    "${status.symbol} · ${status.interval}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        },
                        actions = {
                            ModeBadge(status)
                        }
                    )
                },
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        val labels = listOf("Главная", "Сканер", "Позиция", "История", "Настройки")
                        val icons = listOf(
                            Icons.Default.Home,
                            Icons.Default.Search,
                            Icons.Default.ShowChart,
                            Icons.Default.History,
                            Icons.Default.Settings
                        )
                        labels.forEachIndexed { index, label ->
                            NavigationBarItem(
                                selected = tab == index,
                                onClick = { tab = index },
                                icon = { Icon(icons[index], label) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            ) { padding ->
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    when (tab) {
                        0 -> dashboard(status, candidates, ::command, message, refreshing, ::refresh)
                        1 -> scannerScreen(candidates, refreshing, { refresh(true) })
                        2 -> positionScreen(status, candles)
                        3 -> historyScreen(trades, logs)
                        4 -> settingsScreen(
                            status = status,
                            host = host,
                            onHost = { host = it },
                            token = token,
                            onToken = { token = it },
                            apiKey = apiKey,
                            onApiKey = { apiKey = it },
                            apiSecret = apiSecret,
                            onApiSecret = { apiSecret = it },
                            testnet = testnet,
                            onTestnet = { testnet = it },
                            onScan = ::scanPairing,
                            onConfigure = ::configure,
                            onRefresh = { refresh(true) },
                            onDelete = {
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        api().post("/api/v1/control/stop")
                                        api().delete("/api/v1/config/binance")
                                        withContext(Dispatchers.Main) {
                                            apiKey = ""
                                            apiSecret = ""
                                            message = "Binance credentials удалены"
                                        }
                                        refresh()
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            message = e.message ?: "Ошибка удаления"
                                        }
                                    }
                                }
                            },
                            message = message
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SetupScreen(
    testnet: Boolean,
    onTestnetChange: (Boolean) -> Unit,
    onScan: () -> Unit,
    host: String,
    onHost: (String) -> Unit,
    token: String,
    onToken: (String) -> Unit,
    apiKey: String,
    onApiKey: (String) -> Unit,
    apiSecret: String,
    onApiSecret: (String) -> Unit,
    onConfigure: () -> Unit,
    message: String
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 24.dp)
    ) {
        item {
            Text("WILLIAMS", style = MaterialTheme.typography.headlineLarge)
            Text("TRADER", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "Автоматическая торговая система на телефоне. Termux запускает backend, SQLite, AutoScan и watchdog.",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Подключение", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Локальный режим: http://localhost:8000. Удалённый сервер — только HTTPS/WSS.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(onClick = onScan, Modifier.fillMaxWidth()) {
                        Text("СКАНИРОВАТЬ QR")
                    }
                }
            }
        }

        item {
            OutlinedTextField(
                value = host,
                onValueChange = onHost,
                label = { Text("Backend URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            OutlinedTextField(
                value = token,
                onValueChange = onToken,
                label = { Text("Mobile API token") },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Binance", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = onApiKey,
                        label = { Text("API Key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = apiSecret,
                        onValueChange = onApiSecret,
                        label = { Text("API Secret") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(testnet, onTestnetChange)
                        Text("Использовать Binance Testnet")
                    }
                    Text(
                        "Ключи передаются backend и не хранятся в открытом виде в приложении.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        item {
            Button(
                onClick = onConfigure,
                enabled = host.isNotBlank() && token.length >= 32 &&
                    apiKey.isNotBlank() && apiSecret.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("ПОДКЛЮЧИТЬ И СОХРАНИТЬ")
            }
        }

        item {
            Text(message, style = MaterialTheme.typography.bodySmall)
        }

        item {
            Text(
                "Безопасность: Testnet + DRY_RUN. Реальная торговля не включается автоматически.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
    }
}

fun androidx.compose.foundation.lazy.LazyListScope.dashboard(
    status: Status,
    candidates: List<Candidate>,
    command: (String) -> Unit,
    message: String,
    refreshing: Boolean,
    refresh: () -> Unit
) {
    item {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusChip(if (!status.running) "STOPPED" else if (status.paused) "PAUSED" else "RUNNING")
            StatusChip(if (status.wsConnected) "WS LIVE" else "WS OFF")
            StatusChip(if (status.testnet) "TESTNET" else "LIVE")
            if (refreshing) Text("↻")
        }
    }

    item {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Баланс", style = MaterialTheme.typography.labelMedium)
                Text(
                    "${fmt(status.balance)} USDT",
                    style = MaterialTheme.typography.headlineLarge
                )
                Text(
                    "${status.symbol} · ${status.interval} · ${fmt(status.price)}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }

    item {
        val best = candidates.firstOrNull()
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Лучший кандидат", style = MaterialTheme.typography.titleMedium)
                if (best == null) {
                    Text("Сканер ещё не получил данные")
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(best.symbol, style = MaterialTheme.typography.titleLarge)
                        ScoreBadge(best.score)
                    }
                    Text(stateLabel(best), style = MaterialTheme.typography.bodyMedium)
                    Text(best.reason, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    item {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Позиция", style = MaterialTheme.typography.titleMedium)
                if (status.qty != null) {
                    InfoRow("Сторона", "LONG")
                    InfoRow("Entry", fmt(status.entry))
                    InfoRow("TP", fmt(status.tp))
                    InfoRow("SL", fmt(status.sl))
                    InfoRow("PnL", "${fmt(status.pnl)} USDT")
                    InfoRow("Доходность", pct(status.pnlPct))
                } else {
                    Text("Открытой позиции нет")
                }
            }
        }
    }

    item {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Контроль риска", style = MaterialTheme.typography.titleMedium)
                InfoRow("Риск сделки", "${fmt(status.riskPerTrade * 100)}%")
                InfoRow("Сделки сегодня", "${status.tradesToday}/${status.maxTrades}")
                InfoRow("Убытки подряд", "${status.consecutiveLosses}/${status.maxConsecutiveLosses}")
                InfoRow("Дневной лимит", "${fmt(status.maxDailyLoss * 100)}%")
            }
        }
    }

    item {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { command("/api/v1/control/start") },
                enabled = status.binanceConfigured && !status.running,
                modifier = Modifier.weight(1f)
            ) { Text("START") }
            OutlinedButton(
                onClick = { command("/api/v1/control/pause") },
                enabled = status.running,
                modifier = Modifier.weight(1f)
            ) { Text("PAUSE") }
        }
    }

    item {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { command("/api/v1/control/resume") },
                modifier = Modifier.weight(1f)
            ) { Text("RESUME") }
            OutlinedButton(
                onClick = { command("/api/v1/control/stop") },
                modifier = Modifier.weight(1f)
            ) { Text("STOP") }
            OutlinedButton(
                onClick = { command("/api/v1/control/recover") },
                modifier = Modifier.weight(1f)
            ) { Text("RECOVER") }
        }
    }

    item {
        Text(
            "Williams: ${status.state}",
            style = MaterialTheme.typography.bodySmall,
            color = if (status.state == "RECONCILE_REQUIRED")
                MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    item {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    status.error?.let { error ->
        item {
            Text(
                "Ошибка: $error",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

fun androidx.compose.foundation.lazy.LazyListScope.scannerScreen(
    candidates: List<Candidate>,
    refreshing: Boolean,
    refresh: () -> Unit
) {
    item {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Market Scanner", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${candidates.size} кандидатов · сортировка по Score",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            OutlinedButton(onClick = refresh, enabled = !refreshing) {
                Text(if (refreshing) "..." else "ОБНОВИТЬ")
            }
        }
    }

    if (candidates.isEmpty()) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "Нет данных сканера. Нажми «ОБНОВИТЬ».",
                    Modifier.padding(16.dp)
                )
            }
        }
    }

    items(candidates) { candidate ->
        CandidateCard(candidate)
    }
}

@Composable
fun CandidateCard(candidate: Candidate) {
    val accent = when {
        candidate.signal -> MaterialTheme.colorScheme.tertiary
        candidate.setupState == "SETUP_READY" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(candidate.symbol, style = MaterialTheme.typography.titleLarge)
                    Text(
                        stateLabel(candidate),
                        style = MaterialTheme.typography.labelMedium,
                        color = accent
                    )
                }
                ScoreBadge(candidate.score)
            }

            Text(
                candidate.reason.ifBlank { "Backend не сообщил дополнительное пояснение." },
                style = MaterialTheme.typography.bodyMedium
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Metric("Setup", fmt(candidate.setupScore))
                Metric("HTF", if (candidate.htfConfirmed) "OK" else "WAIT")
                Metric("RR", fmt(candidate.riskReward))
                Metric("ATR", "${fmt(candidate.atrPct * 100)}%")
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Metric("Breakout", "${fmt(candidate.breakoutDistancePct)}%")
                Metric("Spread", "${fmt(candidate.spreadPct)}%")
                Metric("Risk", "${fmt(candidate.riskPct)}%")
            }

            if (candidate.signal) {
                Text(
                    "STRICT LONG SIGNAL: 7/7",
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.labelLarge
                )
            } else if (candidate.setupState == "SETUP_READY") {
                Text(
                    "Кандидат готов к наблюдению — вход не подтверждён.",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

fun androidx.compose.foundation.lazy.LazyListScope.positionScreen(
    status: Status,
    candles: List<Candle>
) {
    item {
        Text("Позиция", style = MaterialTheme.typography.headlineSmall)
    }

    item {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (status.qty == null) {
                    Text("FLAT", style = MaterialTheme.typography.headlineMedium)
                    Text("Открытой позиции нет.")
                } else {
                    Text("LONG ${status.symbol}", style = MaterialTheme.typography.headlineMedium)
                    InfoRow("Количество", fmt(status.qty, 6))
                    InfoRow("Entry", fmt(status.entry))
                    InfoRow("Current", fmt(status.price))
                    InfoRow("Stop Loss", fmt(status.sl))
                    InfoRow("Take Profit", fmt(status.tp))
                    InfoRow("R:R", rr(status.entry, status.sl, status.tp))
                    InfoRow("PnL", "${fmt(status.pnl)} USDT")
                    InfoRow("PnL %", pct(status.pnlPct))
                }
            }
        }
    }

    item {
        Text(
            "Market context · ${candles.size} свечей",
            style = MaterialTheme.typography.titleMedium
        )
    }

    item {
        if (candles.isEmpty()) {
            Text("Нет рыночных данных.")
        } else {
            TradingChart(candles, status)
        }
    }
}

fun androidx.compose.foundation.lazy.LazyListScope.historyScreen(
    trades: List<Trade>,
    logs: List<String>
) {
    item {
        Text("История", style = MaterialTheme.typography.headlineSmall)
    }

    item {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val closed = trades.count { it.exit != null }
                val pnl = trades.mapNotNull { it.pnl }.sum()
                InfoRow("Записей", trades.size.toString())
                InfoRow("Закрытых", closed.toString())
                InfoRow("Суммарный PnL", "${fmt(pnl)} USDT")
            }
        }
    }

    items(trades) { TradeCard(it) }

    if (trades.isEmpty()) {
        item { Text("Сделок пока нет.") }
    }

    item {
        Text("Последние события", style = MaterialTheme.typography.titleMedium)
    }

    items(logs.takeLast(30).reversed()) { log ->
        Text(
            log,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 2.dp)
        )
    }
}

fun androidx.compose.foundation.lazy.LazyListScope.settingsScreen(
    status: Status,
    host: String,
    onHost: (String) -> Unit,
    token: String,
    onToken: (String) -> Unit,
    apiKey: String,
    onApiKey: (String) -> Unit,
    apiSecret: String,
    onApiSecret: (String) -> Unit,
    testnet: Boolean,
    onTestnet: (Boolean) -> Unit,
    onScan: () -> Unit,
    onConfigure: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    message: String
) {
    item {
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Подключение", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Локальный backend: http://localhost:8000",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(onClick = onScan, Modifier.fillMaxWidth()) {
                    Text("СКАНИРОВАТЬ QR")
                }
                OutlinedTextField(
                    value = host,
                    onValueChange = onHost,
                    label = { Text("Backend URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = onToken,
                    label = { Text("Mobile API token") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    item {
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Binance", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = onApiKey,
                    label = { Text("API Key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = apiSecret,
                    onValueChange = onApiSecret,
                    label = { Text("API Secret") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(testnet, onTestnet)
                    Text("Binance Testnet")
                }
                Button(
                    onClick = onConfigure,
                    enabled = apiKey.isNotBlank() && apiSecret.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("СОХРАНИТЬ КЛЮЧИ")
                }
                OutlinedButton(onClick = onDelete, Modifier.fillMaxWidth()) {
                    Text("ОТКЛЮЧИТЬ BINANCE И УДАЛИТЬ КЛЮЧИ")
                }
            }
        }
    }

    item {
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Режим", style = MaterialTheme.typography.titleMedium)
                InfoRow("Backend", if (status.wsConnected) "Online" else "Offline")
                InfoRow("Binance", if (status.binanceConfigured) "Подключён" else "Не настроен")
                InfoRow("Environment", if (testnet) "TESTNET" else "LIVE")
                InfoRow("Williams", status.state)
                Text(
                    "Android отображает решения backend и не рассчитывает торговые сигналы самостоятельно.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    item {
        OutlinedButton(onClick = onRefresh, Modifier.fillMaxWidth()) {
            Text("ОБНОВИТЬ ДАННЫЕ")
        }
    }

    item {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
@Composable
fun ModeBadge(status: Status) {
    val text = when {
        status.testnet -> "TESTNET"
        else -> "LIVE"
    }
    val color = if (status.testnet) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.error
    }
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(end = 12.dp)
    )
}

@Composable
fun ScoreBadge(score: Double) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            "SCORE ${fmt(score)}",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
fun Metric(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun InfoRow(key: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(key, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

@Composable
fun StatusChip(text: String) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
fun TradeCard(trade: Trade) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("#${trade.id} ${trade.side}", style = MaterialTheme.typography.titleMedium)
            Text("Entry ${fmt(trade.entry)} · Exit ${fmt(trade.exit)}")
            Text("PnL ${fmt(trade.pnl)} USDT")
            if (trade.reason.isNotBlank()) {
                Text(trade.reason, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun TradingChart(candles: List<Candle>, status: Status) {
    if (candles.isEmpty()) {
        Text("Нет рыночных данных")
        return
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableFloatStateOf(0f) }
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val errorColor = MaterialTheme.colorScheme.error
    val outlineColor = MaterialTheme.colorScheme.outline
    val surfaceVariantColor = MaterialTheme.colorScheme.surfaceVariant

    androidx.compose.foundation.Canvas(
        Modifier
            .fillMaxWidth()
            .height(330.dp)
            .background(
                surfaceVariantColor,
                RoundedCornerShape(16.dp)
            )
            .pointerInput(candles) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offset = (offset + pan.x).coerceIn(-700f, 700f)
                }
            }
    ) {
        val count = (candles.size / scale)
            .toInt()
            .coerceAtLeast(20)
            .coerceAtMost(candles.size)

        val start = (candles.size - count - (offset / 10f).toInt())
            .coerceIn(0, candles.size - count)

        val visible = candles.subList(start, start + count)
        val levels = listOfNotNull(status.tp, status.entry, status.sl)
        val minPrice = min(
            visible.minOf { it.low },
            levels.minOrNull() ?: Double.MAX_VALUE
        )
        val maxPrice = max(
            visible.maxOf { it.high },
            levels.maxOrNull() ?: Double.MIN_VALUE
        )
        val range = max(1e-9, maxPrice - minPrice)

        fun y(price: Double): Float =
            (size.height - (price - minPrice) / range * size.height).toFloat()

        fun x(index: Int): Float =
            if (visible.size == 1) size.width / 2
            else index.toFloat() / (visible.size - 1) * size.width

        visible.forEachIndexed { index, candle ->
            val center = x(index)
            val high = y(candle.high)
            val low = y(candle.low)
            drawLine(
                outlineColor,
                Offset(center, high),
                Offset(center, low),
                1f
            )

            val top = y(max(candle.open, candle.close))
            val bottom = y(min(candle.open, candle.close))
            drawRect(
                if (candle.close >= candle.open)
                    primaryColor
                else errorColor,
                Offset(center - 3f, top),
                androidx.compose.ui.geometry.Size(
                    6f,
                    max(2f, bottom - top)
                )
            )
        }

        fun line(selector: (Candle) -> Double?, color: Color) {
            val path = Path()
            var started = false
            visible.forEachIndexed { index, candle ->
                selector(candle)?.let { price ->
                    if (!started) {
                        path.moveTo(x(index), y(price))
                        started = true
                    } else {
                        path.lineTo(x(index), y(price))
                    }
                }
            }
            if (started) {
                drawPath(
                    path,
                    color,
                    style = Stroke(2f, cap = StrokeCap.Round)
                )
            }
        }

        line({ it.jaw }, tertiaryColor)
        line({ it.teeth }, secondaryColor)
        line({ it.lips }, primaryColor)

        fun level(price: Double?, color: Color, label: String) {
            if (price == null) return
            val yy = y(price)
            drawLine(color, Offset(0f, yy), Offset(size.width, yy), 2f)
            drawContext.canvas.nativeCanvas.drawText(
                "$label ${fmt(price)}",
                12f,
                yy - 6f,
                android.graphics.Paint().apply {
                    this.color = color.toArgb()
                    textSize = 28f
                }
            )
        }

        level(status.entry, primaryColor, "ENTRY")
        level(status.tp, tertiaryColor, "TP")
        level(status.sl, errorColor, "SL")
    }
}

fun stateLabel(candidate: Candidate): String = when {
    candidate.signal -> "STRONG SIGNAL"
    candidate.setupState == "SETUP_READY" -> "SETUP READY"
    else -> "WATCHING"
}

fun rr(entry: Double?, sl: Double?, tp: Double?): String {
    if (entry == null || sl == null || tp == null || entry == sl) return "—"
    val risk = entry - sl
    val reward = tp - entry
    if (risk <= 0.0) return "—"
    return String.format(Locale.US, "%.2f", reward / risk)
}

fun fmt(value: Double?, digits: Int = 2): String =
    if (value == null || value.isNaN()) "—"
    else String.format(Locale.US, "%.${digits}f", value)

fun pct(value: Double?): String =
    if (value == null || value.isNaN()) "—"
    else String.format(Locale.US, "%+.2f%%", value * 100.0)
