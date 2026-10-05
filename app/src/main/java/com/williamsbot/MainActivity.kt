package com.williamsbot

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
        StandaloneRuntime.start(this)
        TradeNotificationHelper.ensureChannel(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7001)
        }
        setContent {
            WilliamsTheme {
                WilliamsApp(this)
            }
        }
    }
}

private object AppColors {
    val background = Color(0xFF070B12)
    val surface = Color(0xFF0F1622)
    val surface2 = Color(0xFF151F2E)
    val primary = Color(0xFF5DE2E7)
    val violet = Color(0xFF9D8CFF)
    val green = Color(0xFF62E6A7)
    val amber = Color(0xFFFFC857)
    val red = Color(0xFFFF687A)
    val blue = Color(0xFF67A8FF)
    val textMuted = Color(0xFF98A8BD)
}

@Composable
private fun WilliamsTheme(content: @Composable () -> Unit) {
    val colors = androidx.compose.material3.darkColorScheme(
        primary = AppColors.primary,
        secondary = AppColors.violet,
        tertiary = AppColors.green,
        error = AppColors.red,
        background = AppColors.background,
        surface = AppColors.surface,
        surfaceVariant = AppColors.surface2,
        onPrimary = Color(0xFF001113),
        onBackground = Color(0xFFF2F6FC),
        onSurface = Color(0xFFF2F6FC),
        onSurfaceVariant = AppColors.textMuted
    )

    MaterialTheme(
        colorScheme = colors,
        typography = androidx.compose.material3.Typography().run {
            copy(
                headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Bold),
                headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold),
                titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold),
                titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
        },
        content = content
    )
}

data class Status(
    val symbol: String = "BTCUSDT",
    val interval: String = "1h",
    val testnet: Boolean = true,
    val running: Boolean = false,
    val paused: Boolean = false,
    val recovered: Boolean = true,
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
    val binanceConfigured: Boolean = false,
    val riskPerTrade: Double = 0.01,
    val maxDailyLoss: Double = 0.03,
    val tradesToday: Int = 0,
    val maxTrades: Int = 5,
    val consecutiveLosses: Int = 0,
    val maxConsecutiveLosses: Int = 3,
    val scannerScanning: Boolean = false,
    val scannerSymbols: Int = 0,
    val scanDurationMs: Long = 0L
)

data class Candle(
    val time: Long,
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
    val reason: String,
    val wiseManCount: Int,
    val signalFamily: String,
    val waveScore: Double,
    val wavePosition: Int,
    val wavePhase: String,
    val waveConfidence: Double,
    val waveExhaustionRisk: Double,
    val nestedW3: Boolean,
    val nestedW3ParentW5: Boolean,
    val wavePath: String
)

data class Trade(
    val id: String,
    val side: String,
    val entry: Double?,
    val exit: Double?,
    val pnl: Double?,
    val reason: String
)

private class StandaloneApi {
    private val base = "http://localhost:18080"
    private val token = "standalone"
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    fun get(path: String): String = request("GET", path, null)
    fun post(path: String, body: String? = null): String = request("POST", path, body)
    fun delete(path: String): String = request("DELETE", path, null)

    private fun request(
        method: String,
        path: String,
        body: String?
    ): String {
        val builder = Request.Builder()
            .url(base + path)
            .header("Authorization", "Bearer " + token)

        val requestBody =
            body?.toRequestBody("application/json".toMediaType())

        val request = builder.method(
            method,
            if (method == "POST") {
                requestBody ?: "".toRequestBody(null)
            } else {
                null
            }
        ).build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string() ?: "{}"
            if (!response.isSuccessful) {
                error("HTTP " + response.code + ": " + text)
            }
            return text
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WilliamsApp(context: Context) {
    val api = remember { StandaloneApi() }
    val scope = rememberCoroutineScope()

    var tab by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf(Status()) }
    var candles by remember { mutableStateOf(emptyList<Candle>()) }
    var candidates by remember { mutableStateOf(emptyList<Candidate>()) }
    var trades by remember { mutableStateOf(emptyList<Trade>()) }
    var logs by remember { mutableStateOf(emptyList<String>()) }
    var apiKey by remember { mutableStateOf("") }
    var apiSecret by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("Standalone engine запускается…") }
    var refreshing by remember { mutableStateOf(false) }

    suspend fun loadAll(scan: Boolean) {
        withContext(Dispatchers.Main) { refreshing = scan }
        try {
            val (statusJson, klineJson, scannerJson, tradeArray, logArray) =
                coroutineScope {
                    val status = async(Dispatchers.IO) { JSONObject(api.get("/api/v1/status")) }
                    val klines = async(Dispatchers.IO) { JSONObject(api.get("/api/v1/market/klines")) }
                    val scanner = async(Dispatchers.IO) {
                        JSONObject(api.get("/api/v1/scanner?refresh=" + scan))
                    }
                    val trade = async(Dispatchers.IO) { JSONArray(api.get("/api/v1/trades")) }
                    val logsJson = async(Dispatchers.IO) { JSONArray(api.get("/api/v1/logs")) }
                    awaitAll(status, klines, scanner, trade, logsJson).mapIndexed { i, value ->
                        when (i) {
                            0 -> value as JSONObject
                            1 -> value as JSONObject
                            2 -> value as JSONObject
                            3 -> value as JSONArray
                            else -> value as JSONArray
                        }
                    }
                }

            withContext(Dispatchers.Main) {
                status = parseStatus(statusJson)
                candles = parseCandles(klineJson.optJSONArray("candles") ?: JSONArray())
                candidates = parseCandidates(scannerJson.optJSONArray("candidates") ?: JSONArray())
                trades = parseTrades(tradeArray)
                logs = parseLogs(logArray)
                refreshing = false
                message =
                    scannerJson.optString("last_error").takeIf { it.isNotBlank() }
                        ?: if (scan && scannerJson.optBoolean("scanning", false))
                            "Сканирование идёт в фоне"
                        else if (scan) "Сканирование завершено"
                        else "Данные обновлены"
            }
            TradeNotificationHelper.processTradeList(this@MainActivity, tradeArray)
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                refreshing = false
                message = e.message ?: "Ошибка соединения"
            }
        }
    }

    fun refresh(scan: Boolean) {
        scope.launch {
            loadAll(scan)
        }
    }

    fun command(path: String) {
        scope.launch(Dispatchers.IO) {
            try {
                api.post(path)
                withContext(Dispatchers.Main) {
                    message = commandLabel(path)
                }
                loadAll(false)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    message = e.message ?: "Ошибка команды"
                }
            }
        }
    }

    fun saveCredentials() {
        scope.launch(Dispatchers.IO) {
            try {
                require(apiKey.isNotBlank()) { "Введите API Key" }
                require(apiSecret.isNotBlank()) { "Введите API Secret" }

                val body = JSONObject()
                    .put("api_key", apiKey.trim())
                    .put("api_secret", apiSecret.trim())
                    .put("testnet", true)
                    .toString()

                api.post("/api/v1/config/binance", body)

                withContext(Dispatchers.Main) {
                    apiKey = ""
                    apiSecret = ""
                    message = "Binance Testnet подключён и ключи сохранены зашифрованно"
                }
                loadAll(false)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    message = e.message ?: "Не удалось сохранить ключи"
                }
            }
        }
    }

    fun clearCredentials() {
        scope.launch(Dispatchers.IO) {
            try {
                api.delete("/api/v1/config/binance")
                withContext(Dispatchers.Main) {
                    message = "Ключи удалены с устройства"
                    apiKey = ""
                    apiSecret = ""
                }
                loadAll(false)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    message = e.message ?: "Не удалось удалить ключи"
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        loadAll(false)
        while (isActive) {
            delay(5_000L)
            loadAll(false)
        }
    }

    val titles = listOf("Обзор", "Сканер", "Позиция", "История", "Настройки")
    val icons = listOf(
        Icons.Filled.Dashboard,
        Icons.Filled.Radar,
        Icons.Filled.ShowChart,
        Icons.Filled.History,
        Icons.Filled.Settings
    )

    Scaffold(
        containerColor = AppColors.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AppColors.background
                ),
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = AppColors.primary.copy(alpha = 0.16f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    "W",
                                    color = AppColors.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                "WILLIAMS TRADER",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "Native • без VPS • без Termux",
                                style = MaterialTheme.typography.labelSmall,
                                color = AppColors.textMuted
                            )
                        }
                    }
                },
                actions = {
                    ModeChip("TESTNET", AppColors.green)
                    IconButton(onClick = { refresh(true) }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "Сканировать",
                            tint = if (refreshing) AppColors.amber
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(
                modifier = Modifier.navigationBarsPadding(),
                containerColor = AppColors.surface
            ) {
                titles.forEachIndexed { index, title ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = {
                            Icon(
                                icons[index],
                                contentDescription = title
                            )
                        },
                        label = { Text(title) }
                    )
                }
            }
        }
    ) { padding ->
        when (tab) {
            0 -> DashboardScreen(
                padding = padding,
                status = status,
                candidates = candidates,
                message = message,
                refreshing = refreshing,
                onStart = { command("/api/v1/control/start") },
                onPause = { command("/api/v1/control/pause") },
                onResume = { command("/api/v1/control/resume") },
                onStop = { command("/api/v1/control/stop") },
                onScan = { refresh(true) },
                onSettings = { tab = 4 }
            )

            1 -> ScannerScreen(
                padding = padding,
                candidates = candidates,
                status = status,
                refreshing = refreshing,
                message = message,
                onRefresh = { refresh(true) }
            )

            2 -> PositionScreen(
                padding = padding,
                status = status,
                candles = candles
            )

            3 -> HistoryScreen(
                padding = padding,
                trades = trades,
                logs = logs
            )

            else -> SettingsScreen(
                padding = padding,
                status = status,
                apiKey = apiKey,
                apiSecret = apiSecret,
                onApiKey = { apiKey = it },
                onApiSecret = { apiSecret = it },
                onSave = ::saveCredentials,
                onClear = ::clearCredentials,
                message = message
            )
        }
    }
}

@Composable
private fun DashboardScreen(
    padding: PaddingValues,
    status: Status,
    candidates: List<Candidate>,
    message: String,
    refreshing: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onScan: () -> Unit,
    onSettings: () -> Unit
) {
    val best = candidates.firstOrNull()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
    ) {
        item {
            HeroCard(status = status, best = best)
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Цена",
                    value = fmt(status.price, 2),
                    icon = Icons.Filled.ShowChart,
                    accent = AppColors.primary
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Баланс",
                    value = fmt(status.balance, 2),
                    suffix = " USDT",
                    icon = Icons.Filled.AccountBalanceWallet,
                    accent = AppColors.green
                )
            }
        }

        item {
            ScanSummaryCard(
                status = status,
                refreshing = refreshing,
                onScan = onScan
            )
        }

        item {
            BestCandidateCard(best)
        }

        item {
            RiskCard(status)
        }

        item {
            ControlCard(
                status = status,
                onStart = onStart,
                onPause = onPause,
                onResume = onResume,
                onStop = onStop
            )
        }

        item {
            StatusMessage(message = message, onSettings = onSettings)
        }
    }
}

@Composable
private fun HeroCard(status: Status, best: Candidate?) {
    val runningColor =
        if (status.running && !status.paused) AppColors.green
        else if (status.paused) AppColors.amber
        else AppColors.textMuted

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = AppColors.surface
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            AppColors.primary.copy(alpha = 0.14f),
                            AppColors.violet.copy(alpha = 0.10f),
                            Color.Transparent
                        )
                    )
                )
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Торговый центр", style = MaterialTheme.typography.labelLarge)
                    StatusDot(
                        label = statusLabel(status),
                        color = runningColor
                    )
                }

                Text(
                    best?.symbol ?: status.symbol,
                    style = MaterialTheme.typography.headlineMedium
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    ModeChip("TESTNET", AppColors.green)
                    Spacer(Modifier.width(8.dp))
                    ModeChip(
                        if (status.binanceConfigured) "BINANCE OK" else "НЕТ КЛЮЧЕЙ",
                        if (status.binanceConfigured) AppColors.green else AppColors.amber
                    )
                }

                Spacer(Modifier.height(4.dp))

                Text(
                    when {
                        best == null -> "Сканер собирает рыночную картину."
                        best.nestedW3ParentW5 ->
                            "Найдена внутренняя W3 внутри старшего W5 — W5 не блокирует сетап."
                        best.wavePosition == 5 ->
                            "Сетап попал в W5: приложение снижает приоритет из-за истощения."
                        best.wavePosition == 3 ->
                            "Рабочая структура близка к W3 — приоритет повышен."
                        else ->
                            "Williams Profitunity Conservative: Alligator + AO + Fractals + MTF."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.textMuted
                )
            }
        }
    }
}

@Composable
private fun ScanSummaryCard(
    status: Status,
    refreshing: Boolean,
    onScan: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppColors.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = AppColors.violet.copy(alpha = 0.14f),
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Radar,
                        contentDescription = null,
                        tint = AppColors.violet
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text("Market Scanner", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (status.scannerScanning)
                        "Сканирование " + status.scannerSymbols + " пар…"
                    else
                        status.scannerSymbols.toString() +
                            " ликвидных USDT-пар • " +
                            formatMs(status.scanDurationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.textMuted
                )
            }

            if (refreshing || status.scannerScanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.5.dp
                )
            } else {
                IconButton(onClick = onScan) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Обновить")
                }
            }
        }
    }
}

@Composable
private fun BestCandidateCard(best: Candidate?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppColors.surface)
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Лучший кандидат", style = MaterialTheme.typography.titleMedium)
                best?.let { ScorePill(it.score) }
            }

            if (best == null) {
                Text(
                    "Нажми обновление сканера. Рынок будет оценён по ликвидности, Williams setup и MTF-wave.",
                    color = AppColors.textMuted
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            best.symbol,
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            candidateState(best),
                            color = candidateColor(best),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    WavePill(best)
                }

                Text(
                    best.reason,
                    style = MaterialTheme.typography.bodyMedium
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniMetric("RR", fmt(best.riskReward, 2))
                    MiniMetric("ATR", fmt(best.atrPct * 100.0, 2) + "%")
                    MiniMetric("HTF", if (best.htfConfirmed) "OK" else "WAIT")
                    MiniMetric("W", waveText(best))
                }
            }
        }
    }
}

@Composable
private fun RiskCard(status: Status) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppColors.surface)
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Timeline,
                    contentDescription = null,
                    tint = AppColors.blue
                )
                Spacer(Modifier.width(8.dp))
                Text("Защита", style = MaterialTheme.typography.titleMedium)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniMetric("Риск", fmt(status.riskPerTrade * 100, 1) + "%")
                MiniMetric(
                    "Сегодня",
                    status.tradesToday.toString() + "/" + status.maxTrades
                )
                MiniMetric(
                    "Loss streak",
                    status.consecutiveLosses.toString() +
                        "/" + status.maxConsecutiveLosses
                )
                MiniMetric(
                    "Day limit",
                    fmt(status.maxDailyLoss * 100, 1) + "%"
                )
            }
        }
    }
}

@Composable
private fun ControlCard(
    status: Status,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppColors.surface)
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Двигатель", style = MaterialTheme.typography.titleMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(
                    modifier = Modifier.weight(1f),
                    text = "START",
                    icon = Icons.Filled.PlayArrow,
                    enabled = status.binanceConfigured && !status.running,
                    accent = AppColors.green,
                    onClick = onStart
                )
                ActionButton(
                    modifier = Modifier.weight(1f),
                    text = if (status.paused) "RESUME" else "PAUSE",
                    icon = if (status.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    enabled = status.running,
                    accent = AppColors.amber,
                    onClick = if (status.paused) onResume else onPause
                )
                ActionButton(
                    modifier = Modifier.weight(1f),
                    text = "STOP",
                    icon = Icons.Filled.Stop,
                    enabled = status.running,
                    accent = AppColors.red,
                    onClick = onStop
                )
            }

            Text(
                "TESTNET execution включён: при подтверждённом сигнале бот может открыть BUY и сразу поставить защитный OCO SELL (TP/SL).",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.amber
            )
        }
    }
}

@Composable
private fun StatusMessage(message: String, onSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = AppColors.surface2.copy(alpha = 0.65f)
        )
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (message.contains("Ошибка", true)) Icons.Filled.Warning
                else Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = if (message.contains("Ошибка", true))
                    AppColors.red else AppColors.green
            )
            Spacer(Modifier.width(10.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onSettings) {
                Text("Ключи")
            }
        }
    }
}

@Composable
private fun ScannerScreen(
    padding: PaddingValues,
    candidates: List<Candidate>,
    status: Status,
    refreshing: Boolean,
    message: String,
    onRefresh: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Market Scanner", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        status.scannerSymbols.toString() +
                            " пар • top " + min(20, candidates.size).toString(),
                        color = AppColors.textMuted
                    )
                }
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !refreshing
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("СКАН")
                }
            }
        }

        item {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.textMuted
            )
        }

        if (candidates.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Filled.Radar,
                    title = "Сканер пуст",
                    subtitle = "Первое сканирование сортирует пары по ликвидности, Williams setup и волновому контексту."
                )
            }
        }

        items(candidates) {
            CandidateCardModern(it)
        }
    }
}

@Composable
private fun CandidateCardModern(candidate: Candidate) {
    val accent = candidateColor(candidate)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppColors.surface)
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(candidate.symbol, style = MaterialTheme.typography.titleLarge)
                    Text(
                        candidateState(candidate),
                        color = accent,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                ScorePill(candidate.score)
            }

            Text(
                candidate.reason,
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.textMuted
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MiniMetric("WAVE", waveText(candidate))
                MiniMetric("W-SCORE", fmt(candidate.waveScore, 0))
                MiniMetric("EXHAUST", fmt(candidate.waveExhaustionRisk, 0) + "%")
                MiniMetric("HTF", if (candidate.htfConfirmed) "OK" else "WAIT")
            }

            Divider(color = AppColors.surface2)

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MiniMetric("RR", fmt(candidate.riskReward, 2))
                MiniMetric("ATR", fmt(candidate.atrPct * 100, 2) + "%")
                MiniMetric("SPREAD", fmt(candidate.spreadPct * 100, 3) + "%")
                MiniMetric("WM", candidate.wiseManCount.toString() + "/3")
            }

            if (candidate.nestedW3ParentW5) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = AppColors.green.copy(alpha = 0.12f)
                ) {
                    Row(Modifier.padding(10.dp)) {
                        Icon(
                            Icons.Filled.Visibility,
                            contentDescription = null,
                            tint = AppColors.green,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Nested W3 внутри parent W5 — W5 не veto.",
                            color = AppColors.green,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            Text(
                "MTF  " + candidate.wavePath,
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.textMuted
            )
        }
    }
}

@Composable
private fun PositionScreen(
    padding: PaddingValues,
    status: Status,
    candles: List<Candle>
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
    ) {
        item {
            Text("Позиция", style = MaterialTheme.typography.headlineSmall)
        }

        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = AppColors.surface)
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (status.qty == null) {
                        Text("FLAT", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "Позиции нет. Execution пока отключён.",
                            color = AppColors.textMuted
                        )
                    } else {
                        Text(
                            "LONG " + status.symbol,
                            style = MaterialTheme.typography.headlineMedium
                        )
                        InfoRow("Quantity", fmt(status.qty, 6))
                        InfoRow("Entry", fmt(status.entry))
                        InfoRow("Current", fmt(status.price))
                        InfoRow("SL", fmt(status.sl))
                        InfoRow("TP", fmt(status.tp))
                        InfoRow("PnL", fmt(status.pnl, 2) + " USDT")
                        InfoRow("PnL %", pct(status.pnlPct))
                    }
                }
            }
        }

        item {
            Text(
                "BTCUSDT • 1h • Williams context",
                style = MaterialTheme.typography.titleMedium
            )
        }

        item {
            if (candles.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.ShowChart,
                    title = "Нет графика",
                    subtitle = "Подключи Testnet и обнови данные."
                )
            } else {
                TradingChart(candles, status)
            }
        }
    }
}

@Composable
private fun HistoryScreen(
    padding: PaddingValues,
    trades: List<Trade>,
    logs: List<String>
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
    ) {
        item {
            Text("История", style = MaterialTheme.typography.headlineSmall)
        }

        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = AppColors.surface)
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val closed = trades.count { it.exit != null }
                    val pnl = trades.mapNotNull { it.pnl }.sum()
                    InfoRow("Всего", trades.size.toString())
                    InfoRow("Закрытых", closed.toString())
                    InfoRow("PnL", fmt(pnl, 2) + " USDT")
                }
            }
        }

        if (trades.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Filled.History,
                    title = "Сделок пока нет",
                    subtitle = "Это ожидаемо: execution в standalone 4.13 пока отключён."
                )
            }
        }

        items(trades) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = AppColors.surface)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "#" + it.id + "  " + it.side,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Entry " + fmt(it.entry) +
                            " • Exit " + fmt(it.exit),
                        color = AppColors.textMuted
                    )
                    Text("PnL " + fmt(it.pnl) + " USDT")
                    if (it.reason.isNotBlank()) {
                        Text(
                            it.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.textMuted
                        )
                    }
                }
            }
        }

        item {
            Text("Системные события", style = MaterialTheme.typography.titleMedium)
        }

        items(logs.takeLast(20).reversed()) {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.textMuted,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun SettingsScreen(
    padding: PaddingValues,
    status: Status,
    apiKey: String,
    apiSecret: String,
    onApiKey: (String) -> Unit,
    onApiSecret: (String) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    message: String
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
    ) {
        item {
            Text("Настройки", style = MaterialTheme.typography.headlineSmall)
        }

        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = AppColors.surface)
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            tint = AppColors.green
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Standalone", style = MaterialTheme.typography.titleMedium)
                    }

                    Text(
                        "Движок работает прямо внутри APK. VPS, Termux, Mobile API token и QR pairing здесь не нужны.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.textMuted
                    )

                    InfoRow("Режим", "BINANCE TESTNET")
                    InfoRow("Backend", "Встроенный")
                    InfoRow("Execution", "DISABLED")
                    InfoRow("Max scan", "60 пар")
                    InfoRow("MTF", "1D / 4H / 1H / 15M")
                }
            }
        }

        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = AppColors.surface)
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.AccountBalanceWallet,
                            contentDescription = null,
                            tint = AppColors.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Binance Testnet", style = MaterialTheme.typography.titleMedium)
                    }

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
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = true,
                            onCheckedChange = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Testnet закреплён",
                            color = AppColors.green
                        )
                    }

                    Button(
                        onClick = onSave,
                        enabled = apiKey.isNotBlank() && apiSecret.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("СОХРАНИТЬ TESTNET КЛЮЧИ")
                    }

                    OutlinedButton(
                        onClick = onClear,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("УДАЛИТЬ КЛЮЧИ")
                    }

                    Text(
                        if (status.binanceConfigured)
                            "Статус: Binance подключён."
                        else
                            "Статус: API ключи ещё не заданы.",
                        color = if (status.binanceConfigured)
                            AppColors.green else AppColors.amber
                    )
                }
            }
        }

        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = AppColors.surface)
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Правила защиты", style = MaterialTheme.typography.titleMedium)
                    InfoRow("Risk / trade", fmt(status.riskPerTrade * 100, 1) + "%")
                    InfoRow("Daily loss", fmt(status.maxDailyLoss * 100, 1) + "%")
                    InfoRow("Max trades", status.maxTrades.toString())
                    InfoRow("Max loss streak", status.maxConsecutiveLosses.toString())
                    InfoRow("Scanner cadence", "90 sec")
                    InfoRow("Wave top N", "8")
                }
            }
        }

        item {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.textMuted
            )
        }
    }
}

@Composable
private fun MetricCard(
    modifier: Modifier,
    title: String,
    value: String,
    suffix: String = "",
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = AppColors.surface)
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(title, color = AppColors.textMuted)
            }
            Text(
                value + suffix,
                style = MaterialTheme.typography.titleLarge
            )
        }
    }
}

@Composable
private fun MiniMetric(label: String, value: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = AppColors.surface2
    ) {
        Column(
            Modifier
                .padding(horizontal = 9.dp, vertical = 7.dp)
                .width(64.dp)
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.textMuted
            )
            Text(
                value,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun ActionButton(
    modifier: Modifier,
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(text)
    }
}

@Composable
private fun ModeChip(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = color.copy(alpha = 0.13f)
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun StatusDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = CircleShape,
            color = color.copy(alpha = 0.18f),
            modifier = Modifier.size(26.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Surface(
                    shape = CircleShape,
                    color = color,
                    modifier = Modifier.size(8.dp)
                ) {}
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(label, color = color, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ScorePill(score: Double) {
    ModeChip(
        text = "SCORE " + fmt(score, 0),
        color = when {
            score >= 80 -> AppColors.green
            score >= 60 -> AppColors.primary
            else -> AppColors.amber
        }
    )
}

@Composable
private fun WavePill(candidate: Candidate) {
    val color = when {
        candidate.nestedW3ParentW5 -> AppColors.green
        candidate.wavePosition == 3 -> AppColors.primary
        candidate.wavePosition == 5 -> AppColors.amber
        else -> AppColors.violet
    }

    ModeChip(
        text = waveText(candidate),
        color = color
    )
}

@Composable
private fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppColors.surface)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = AppColors.primary,
                modifier = Modifier.size(38.dp)
            )
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                color = AppColors.textMuted,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun TradingChart(candles: List<Candle>, status: Status) {
    var scale by remember(candles.size) { mutableStateOf(1f) }
    var pan by remember(candles.size) { mutableStateOf(0f) }

    val visibleCount =
        (candles.size / scale).toInt().coerceIn(24, candles.size)

    val maxStart = max(0, candles.size - visibleCount)
    val shift =
        (pan / 18f).toInt().coerceIn(-maxStart, 0)
    val start =
        (candles.size - visibleCount + shift).coerceIn(0, maxStart)

    val visible =
        candles.subList(start, start + visibleCount)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppColors.surface)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(340.dp)
                .padding(8.dp)
                .pointerInput(candles) {
                    detectTransformGestures { _, drag, zoom, _ ->
                        scale =
                            (scale * zoom).coerceIn(1f, 5f)
                        pan += drag.x
                    }
                }
        ) {
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
                index.toFloat() / max(1, visible.size - 1) * size.width

            for (grid in 1..4) {
                val yy = size.height * grid / 5f
                drawLine(
                    AppColors.surface2,
                    Offset(0f, yy),
                    Offset(size.width, yy),
                    1f
                )
            }

            visible.forEachIndexed { index, candle ->
                val center = x(index)
                drawLine(
                    AppColors.textMuted.copy(alpha = 0.55f),
                    Offset(center, y(candle.high)),
                    Offset(center, y(candle.low)),
                    1f
                )

                val top = y(max(candle.open, candle.close))
                val bottom = y(min(candle.open, candle.close))

                drawRect(
                    color = if (candle.close >= candle.open)
                        AppColors.green else AppColors.red,
                    topLeft = Offset(center - 3f, top),
                    size = Size(
                        6f,
                        max(2f, bottom - top)
                    )
                )
            }

            fun indicatorLine(
                selector: (Candle) -> Double?,
                color: Color
            ) {
                val path = Path()
                var started = false
                visible.forEachIndexed { index, candle ->
                    selector(candle)?.let { value ->
                        if (!started) {
                            path.moveTo(x(index), y(value))
                            started = true
                        } else {
                            path.lineTo(x(index), y(value))
                        }
                    }
                }
                if (started) {
                    drawPath(
                        path = path,
                        color = color,
                        style = Stroke(
                            width = 2.2f,
                            cap = StrokeCap.Round
                        )
                    )
                }
            }

            indicatorLine({ it.jaw }, AppColors.violet)
            indicatorLine({ it.teeth }, AppColors.amber)
            indicatorLine({ it.lips }, AppColors.primary)

            fun level(price: Double?, color: Color, label: String) {
                if (price == null) return
                val yy = y(price)
                drawLine(
                    color,
                    Offset(0f, yy),
                    Offset(size.width, yy),
                    2f
                )

                drawContext.canvas.nativeCanvas.drawText(
                    label + " " + fmt(price),
                    12f,
                    max(20f, yy - 6f),
                    android.graphics.Paint().apply {
                        this.color = color.toArgb()
                        textSize = 26f
                        isAntiAlias = true
                    }
                )
            }

            level(status.entry, AppColors.primary, "ENTRY")
            level(status.tp, AppColors.green, "TP")
            level(status.sl, AppColors.red, "SL")
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = AppColors.textMuted)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

private fun parseStatus(json: JSONObject): Status {
    val position = json.optJSONObject("position")
    return Status(
        symbol = json.optString("symbol", "BTCUSDT"),
        interval = json.optString("interval", "1h"),
        testnet = json.optBoolean("testnet", true),
        running = json.optBoolean("running", false),
        paused = json.optBoolean("paused", false),
        recovered = json.optBoolean("recovered", true),
        state = json.optString("state", "FLAT"),
        price = json.optDouble("price").takeUnless { it.isNaN() },
        balance = json.optDouble("quote_balance").takeUnless { it.isNaN() },
        qty = position?.optDouble("quantity")?.takeUnless { it.isNaN() },
        entry = position?.optDouble("entry_price")?.takeUnless { it.isNaN() },
        tp = json.optDouble("take_profit_price").takeUnless { it.isNaN() || it == 0.0 },
        sl = json.optDouble("stop_loss_price").takeUnless { it.isNaN() || it == 0.0 },
        pnl = json.optDouble("pnl").takeUnless { it.isNaN() },
        pnlPct = json.optDouble("pnl_pct").takeUnless { it.isNaN() },
        error = json.optString("last_error").takeIf { it.isNotBlank() },
        binanceConfigured = json.optBoolean("binance_configured"),
        riskPerTrade = json.optDouble("risk_per_trade_pct", 0.01),
        maxDailyLoss = json.optDouble("max_daily_loss_pct", 0.03),
        tradesToday = json.optInt("trades_today", 0),
        maxTrades = json.optInt("max_trades_per_day", 5),
        consecutiveLosses = json.optInt("consecutive_losses", 0),
        maxConsecutiveLosses = json.optInt("max_consecutive_losses", 3),
        scannerScanning = json.optBoolean("scanner_scanning", false),
        scannerSymbols = json.optInt("scanner_symbols", 0),
        scanDurationMs = json.optLong("scanner_duration_ms", 0L)
    )
}

private fun parseCandles(array: JSONArray): List<Candle> =
    List(array.length()) { i ->
        val x = array.getJSONObject(i)
        Candle(
            time = x.optLong("time"),
            open = x.optDouble("open"),
            close = x.optDouble("close"),
            high = x.optDouble("high"),
            low = x.optDouble("low"),
            jaw = x.optDouble("jaw").takeUnless { it.isNaN() },
            teeth = x.optDouble("teeth").takeUnless { it.isNaN() },
            lips = x.optDouble("lips").takeUnless { it.isNaN() },
            longSignal = x.optBoolean("long_signal"),
            fractalUp = x.optBoolean("fractal_up"),
            fractalDown = x.optBoolean("fractal_down")
        )
    }

private fun parseCandidates(array: JSONArray): List<Candidate> =
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
            reason = x.optString("reason", ""),
            wiseManCount = x.optInt("wise_man_count", 0),
            signalFamily = x.optString("signal_family", "NONE"),
            waveScore = x.optDouble("wave_score", 50.0),
            wavePosition = x.optInt("wave_position", 0),
            wavePhase = x.optString("wave_phase", "UNKNOWN"),
            waveConfidence = x.optDouble("wave_confidence", 0.0),
            waveExhaustionRisk = x.optDouble("wave_exhaustion_risk", 0.0),
            nestedW3 = x.optBoolean("nested_w3"),
            nestedW3ParentW5 = x.optBoolean("nested_w3_parent_w5"),
            wavePath = x.optString("wave_path", "")
        )
    }.sortedByDescending { it.score }

private fun parseTrades(array: JSONArray): List<Trade> =
    List(array.length()) { i ->
        val x = array.getJSONObject(i)
        Trade(
            id = x.optString("id"),
            side = x.optString("side"),
            entry = x.optDouble("entry_price").takeUnless { it.isNaN() },
            exit = x.optDouble("exit_price").takeUnless { it.isNaN() },
            pnl = x.optDouble("pnl").takeUnless { it.isNaN() },
            reason = x.optString("reason")
        )
    }

private fun parseLogs(array: JSONArray): List<String> =
    List(array.length()) { i ->
        val x = array.getJSONObject(i)
        x.optString("created_at") + "  " +
            x.optString("level") + "  " +
            x.optString("message")
    }

private fun candidateColor(candidate: Candidate): Color =
    when {
        candidate.signal -> AppColors.green
        candidate.nestedW3ParentW5 -> AppColors.green
        candidate.wavePosition == 3 -> AppColors.primary
        candidate.wavePosition == 5 -> AppColors.amber
        else -> AppColors.textMuted
    }

private fun candidateState(candidate: Candidate): String =
    when {
        candidate.signal -> "CONFIRMED LONG"
        candidate.nestedW3ParentW5 -> "NESTED W3"
        candidate.wavePosition == 5 -> "W5 • EXHAUSTION WATCH"
        candidate.wavePosition == 3 -> "W3 • PRIORITY"
        else -> "WATCHING"
    }

private fun waveText(candidate: Candidate): String =
    if (candidate.wavePosition > 0) "W" + candidate.wavePosition else "W?"

private fun statusLabel(status: Status): String =
    when {
        status.running && status.paused -> "PAUSED"
        status.running -> "RUNNING"
        else -> "STOPPED"
    }

private fun formatMs(value: Long): String =
    if (value <= 0L) "ожидание" else (value / 1000L).toString() + "s"

private fun commandLabel(path: String): String =
    when {
        path.endsWith("/start") -> "Двигатель запущен"
        path.endsWith("/pause") -> "Пауза включена"
        path.endsWith("/resume") -> "Сканирование возобновлено"
        path.endsWith("/stop") -> "Двигатель остановлен"
        else -> "Команда выполнена"
    }

private fun fmt(value: Double?, digits: Int = 2): String =
    if (value == null || value.isNaN()) {
        "—"
    } else {
        String.format(Locale.US, "%." + digits + "f", value)
    }

private fun pct(value: Double?): String =
    if (value == null || value.isNaN()) {
        "—"
    } else {
        String.format(Locale.US, "%+.2f%%", value * 100.0)
    }
