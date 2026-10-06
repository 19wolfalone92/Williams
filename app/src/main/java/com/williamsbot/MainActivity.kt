package com.williamsbot

import android.Manifest
import android.content.Context
import android.os.Bundle
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.FilterChip
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

// Williams 4.20.1 production cockpit
// CI compile-log capture enabled
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val power = getSystemService(PowerManager::class.java)
            if (!power.isIgnoringBatteryOptimizations(packageName)) {
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:$packageName"))
                    )
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                13001
            )
        }
        // Keep the local runtime alive when the phone is used as the autonomous
        // Testnet engine. The VPS backend remains the preferred 24/7 authority.
        val serviceIntent = android.content.Intent(this, WilliamsForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            startService(serviceIntent)
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
    val riskPerTrade: Double = 0.005,
    val maxDailyLoss: Double = 0.03,
    val tradesToday: Int = 0,
    val consecutiveLosses: Int = 0,
    val dailyPnlUsdt: Double = 0.0,
    val tradingMode: String = "ACTIVE",
    val openPositions: Int = 0,
    val maxOpenPositions: Int = 2,
    val reservedRiskPct: Double = 0.0,
    val maxTotalRiskPct: Double = 0.01,
    val reconcileRequired: Boolean = false,
    val positions: List<PositionView> = emptyList(),
    val scannerScanning: Boolean = false,
    val scannerState: String = "NOT_RUN",
    val scannerError: String? = null,
    val scannerSymbols: Int = 0,
    val scanDurationMs: Long = 0L,
    val marketWsConnected: Boolean = false,
    val userWsConnected: Boolean = false,
    val userStreamSyncRequired: Boolean = true,
    val historyReady: Boolean = false,
    val fsmState: String = "STOPPED",
    val executionContractVersion: Int = 1,
    val executionState: String = "STOPPED",
    val executionEnabled: Boolean = false,
    val executionContractReconcileRequired: Boolean = true,
    val executionKillLatched: Boolean = false,
    val p0GatePassed: Boolean = false,
    val p0GateReason: String = "NOT_READY",
    val maxOpenPositionsLocked: Boolean = false,
    val unresolvedSymbols: List<String> = emptyList(),
    val pendingEntrySymbols: List<String> = emptyList()
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
    val wavePath: String,
    val wavePrimaryCount: String,
    val waveAlternativeCount: String,
    val waveAbcPhase: String,
    val quantScore: Double = 0.0,
    val regime: String = "UNKNOWN",
    val regimeScore: Double = 0.0,
    val obi: Double = 0.0,
    val tradeFlowImbalance: Double = 0.0,
    val wave3Probability: Double = 0.0,
    val wave5Probability: Double = 0.0,
    val shadowDirection: String = "HOLD",
    val shadowConfidence: Double = 0.0,
    val shadowReason: String = "",
    val xaiTopFactor: String = ""
)

data class Trade(
    val id: String,
    val symbol: String,
    val side: String,
    val entry: Double?,
    val exit: Double?,
    val pnl: Double?,
    val pnlPct: Double?,
    val rMultiple: Double?,
    val reason: String,
    val outcome: String,
    val classification: String,
    val wavePosition: Int,
    val wavePhase: String,
    val score: Double,
    val mfeR: Double,
    val maeR: Double
)

data class MarketPair(
    val symbol: String,
    val price: Double?
)

data class PositionView(
    val symbol: String,
    val qty: Double,
    val entry: Double,
    val stop: Double?,
    val take: Double?,
    val riskPct: Double
)

private class StandaloneApi(context: Context) {
    private val prefs = context.getSharedPreferences("williams_backend", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    val backendUrl: String
        get() = prefs.getString("backend_url", "http://127.0.0.1:18080")
            ?.trimEnd('/')
            .takeUnless { it.isNullOrBlank() }
            ?: "http://127.0.0.1:18080"

    val mobileToken: String
        get() = prefs.getString("mobile_token", "")?.trim() ?: ""

    fun saveConnection(url: String, token: String) {
        val normalized = url.trim().trimEnd('/')
        require(normalized.isNotBlank()) { "Backend URL не задан" }
        val localhostHttp =
            normalized.startsWith("http://127.0.0.1") ||
                normalized.startsWith("http://localhost")
        if (!normalized.startsWith("https://") && !localhostHttp) {
            error("Backend URL должен использовать HTTPS; HTTP разрешён только для localhost")
        }
        if (!localhostHttp && token.trim().length < 32) {
            error("Для удалённого Backend нужен Mobile API Token (минимум 32 символа).")
        }
        prefs.edit()
            .putString("backend_url", normalized)
            .putString("mobile_token", token.trim())
            .apply()
    }

    fun get(path: String): String = request("GET", path, null)
    fun post(path: String, body: String? = null): String = request("POST", path, body)
    fun delete(path: String): String = request("DELETE", path, null)

    private fun request(
        method: String,
        path: String,
        body: String?
    ): String {
        val builder = Request.Builder()
            .url(backendUrl + path)
        if (mobileToken.isNotBlank()) {
            builder.header("Authorization", "Bearer " + mobileToken)
        }

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
    val api = remember { StandaloneApi(context) }
    val scope = rememberCoroutineScope()

    var tab by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf(Status()) }
    var marketPairs by remember { mutableStateOf(emptyList<MarketPair>()) }
    var selectedPositionSymbol by remember { mutableStateOf<String?>(null) }
    var candles by remember { mutableStateOf(emptyList<Candle>()) }
    var selectedChartInterval by remember { mutableStateOf("1h") }
    var candidates by remember { mutableStateOf(emptyList<Candidate>()) }
    var trades by remember { mutableStateOf(emptyList<Trade>()) }
    var logs by remember { mutableStateOf(emptyList<String>()) }
    var apiKey by remember { mutableStateOf("") }
    var apiSecret by remember { mutableStateOf("") }
    var backendUrl by remember { mutableStateOf(api.backendUrl) }
    var mobileToken by remember { mutableStateOf(api.mobileToken) }
    var message by remember { mutableStateOf("Подключение к Williams Backend…") }
    var refreshing by remember { mutableStateOf(false) }
    var backupPassword by remember { mutableStateOf("") }
    var backupMessage by remember { mutableStateOf("") }

    suspend fun loadAll(scan: Boolean) {
        withContext(Dispatchers.IO) {
            try {
                // Autonomous mode uses the on-device runtime at 127.0.0.1:18080.
                // A remote URL/token is only needed when the user explicitly configures one.
                withContext(Dispatchers.Main) { refreshing = scan }

                val statusJson = JSONObject(api.get("/api/v1/status"))
                val klineJson = JSONObject(api.get("/api/v1/market/klines?interval=" + selectedChartInterval))
                val marketSymbols = listOf("BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT")
                val marketPairsLoaded = marketSymbols.map { symbol ->
                    val j = runCatching { JSONObject(api.get("/api/v1/market/klines?symbol=" + symbol)) }.getOrNull()
                    val arr = j?.optJSONArray("candles")
                    val last = arr?.let { if (it.length() > 0) it.optJSONObject(it.length() - 1) else null }
                    MarketPair(symbol, last?.optDouble("close")?.takeUnless { it.isNaN() || it <= 0.0 })
                }
                var scannerJson =
                    JSONObject(api.get("/api/v1/scanner?refresh=" + scan))

                if (scan && scannerJson.optBoolean("scanning", false)) {
                    for (i in 0 until 20) {
                        delay(1000L)
                        scannerJson =
                            JSONObject(api.get("/api/v1/scanner?refresh=false"))
                        if (!scannerJson.optBoolean("scanning", false)) {
                            break
                        }
                    }
                }

                val tradeArray = JSONArray(api.get("/api/v1/trades"))
                val logArray = JSONArray(api.get("/api/v1/logs"))

                withContext(Dispatchers.Main) {
                    status = parseStatus(statusJson)
                    marketPairs = marketPairsLoaded
                    if (selectedPositionSymbol == null && status.positions.isNotEmpty()) selectedPositionSymbol = status.positions.first().symbol
                    candles = parseCandles(klineJson.optJSONArray("candles") ?: JSONArray())
                    candidates = parseCandidates(
                        scannerJson.optJSONArray("candidates") ?: JSONArray()
                    )
                    trades = parseTrades(tradeArray)
                    logs = parseLogs(logArray)

                    message =
                        scannerJson.optString("last_error").takeIf { it.isNotBlank() }
                            ?: if (scan) {
                                if (scannerJson.optBoolean("scanning", false)) {
                                    "Сканирование продолжается в фоне"
                                } else {
                                    "Сканирование завершено"
                                }
                            } else {
                                "Данные обновлены"
                            }

                    refreshing = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    refreshing = false
                    message = e.message ?: "Ошибка соединения"
                }
            }
        }
    }

    fun refresh(scan: Boolean) {
        scope.launch {
            loadAll(scan)
        }
    }

    LaunchedEffect(selectedPositionSymbol) {
        val symbol = selectedPositionSymbol ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching {
                val json = JSONObject(api.get("/api/v1/market/klines?symbol=" + symbol))
                val parsed = parseCandles(json.optJSONArray("candles") ?: JSONArray())
                withContext(Dispatchers.Main) { candles = parsed }
            }
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

    fun saveBackendConnection() {
        try {
            api.saveConnection(backendUrl, mobileToken)
            message = "Backend URL и Mobile API token сохранены"
        } catch (e: Exception) {
            message = e.message ?: "Не удалось сохранить Backend"
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

    val createBackupLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult

            scope.launch(Dispatchers.IO) {
                try {
                    BackupManager.export(
                        context = context,
                        output = requireNotNull(context.contentResolver.openOutputStream(uri)),
                        password = backupPassword
                    )
                    withContext(Dispatchers.Main) {
                        backupMessage = "Резервная копия создана. Файл зашифрован."
                        backupPassword = ""
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        backupMessage =
                            e.message ?: "Не удалось создать backup"
                    }
                }
            }
        }

    val openBackupLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult

            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(uri).use { input ->
                        BackupManager.import(
                            context = context,
                            input = requireNotNull(input),
                            password = backupPassword
                        )
                    }

                    api.post(
                        "/api/v1/control/recover"
                    )

                    withContext(Dispatchers.Main) {
                        backupMessage =
                            "Backup восстановлен. Binance state сверено. START не включён."
                        backupPassword = ""
                    }

                    loadAll(false)
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        backupMessage =
                            e.message ?: "Не удалось восстановить backup"
                    }
                }
            }
        }

    fun exportBackup() {
        try {
            BackupManager.validatePassword(backupPassword)
            createBackupLauncher.launch("Williams_backup.wlb")
        } catch (e: Exception) {
            backupMessage = e.message ?: "Проверьте пароль backup"
        }
    }

    fun importBackup() {
        try {
            BackupManager.validatePassword(backupPassword)
            openBackupLauncher.launch(arrayOf("application/octet-stream", "application/json", "*/*"))
        } catch (e: Exception) {
            backupMessage = e.message ?: "Проверьте пароль backup"
        }
    }

    LaunchedEffect(Unit) {
        loadAll(true)
        while (isActive) {
            delay(15_000L)
            loadAll(false)
        }
    }

    val titles = listOf("Overview", "Wave Map", "Positions", "Incidents", "Config")
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
                                "WILLIAMS COCKPIT",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "AUTONOMOUS • Android runs Williams locally",
                                style = MaterialTheme.typography.labelSmall,
                                color = AppColors.textMuted
                            )
                        }
                    }
                },
                actions = {
                    StatusDot("P0", if (status.p0GatePassed) AppColors.green else AppColors.red)
                    StatusDot("WSS", if (status.marketWsConnected && !status.userStreamSyncRequired) AppColors.green else AppColors.amber)
                    StatusDot("BINANCE", if (status.binanceConfigured) AppColors.green else AppColors.amber)
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
        val reconciliationBlocked =
            status.reconcileRequired ||
                status.executionContractReconcileRequired ||
                status.state == "RECONCILE_REQUIRED"

        // Reconciliation is a trading safety barrier, not a UI/navigation barrier.
        // The user must be able to inspect Wave Map, Positions, Incidents and Config
        // while execution remains blocked until backend recovery proves the state clean.
        when (tab) {
            0 -> DashboardScreen(
                padding = padding,
                status = status,
                marketPairs = marketPairs,
                candidates = candidates,
                message = message,
                refreshing = refreshing,
                onStart = {
                    command("/api/v1/control/start")
                },
                onPause = {
                    command("/api/v1/control/pause")
                },
                onResume = {
                    command("/api/v1/control/resume")
                },
                onStop = {
                    command("/api/v1/control/stop")
                },
                onKill = {
                    command("/api/v1/control/kill")
                },
                onScan = { refresh(true) },
                onSettings = { tab = 4 },
                 onRecoverDashboard = { command("/api/v1/control/recover") }
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
                candles = candles,
                selectedSymbol = selectedPositionSymbol,
                interval = selectedChartInterval,
                onIntervalChange = { selectedChartInterval = it; refresh(false) },
                onSelect = { selectedPositionSymbol = it },
                onSell = { symbol ->
                    scope.launch(Dispatchers.IO) {
                        try {
                            api.post(
                                "/api/v1/control/sell?symbol=" +
                                    symbol
                            )
                            withContext(Dispatchers.Main) {
                                message =
                                    "SELL отправлен: " + symbol
                            }
                            loadAll(false)
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                message =
                                    e.message ?: "SELL error"
                            }
                        }
                    }
                }
            )

            3 -> HistoryScreen(
                padding = padding,
                trades = trades,
                logs = logs
            )

            else -> SettingsScreen(
                padding = padding,
                status = status,
                backendUrl = backendUrl,
                mobileToken = mobileToken,
                onBackendUrl = { backendUrl = it },
                onMobileToken = { mobileToken = it },
                onSaveBackend = ::saveBackendConnection,
                apiKey = apiKey,
                apiSecret = apiSecret,
                onApiKey = { apiKey = it },
                onApiSecret = { apiSecret = it },
                onSave = ::saveCredentials,
                onClear = ::clearCredentials,
                backupPassword = backupPassword,
                onBackupPassword = { backupPassword = it },
                onExportBackup = ::exportBackup,
                onImportBackup = ::importBackup,
                backupMessage = backupMessage,
                message = message
            )
        }
    }
}

@Composable
private fun SystemHealthBar(status: Status) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppColors.surface)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusDot("P0", if (status.p0GatePassed) AppColors.green else AppColors.red)
            StatusDot("RUNTIME", AppColors.green)
            StatusDot("BINANCE", if (status.binanceConfigured) AppColors.green else AppColors.amber)
            StatusDot("WSS", if (status.marketWsConnected && !status.userStreamSyncRequired) AppColors.green else AppColors.amber)
            StatusDot("EXEC", if (status.executionEnabled && !status.reconcileRequired) AppColors.green else AppColors.red)
            Spacer(Modifier.weight(1f))
            Text("RISK BUDGET • ${status.maxOpenPositions} POS", color = AppColors.textMuted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ReconcileBarrierScreen(
    padding: PaddingValues,
    status: Status,
    onRecover: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().padding(padding).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("RECONCILE_REQUIRED", style = MaterialTheme.typography.headlineMedium, color = AppColors.red, fontWeight = FontWeight.Bold)
        Text("Торговый цикл заблокирован. Android не принимает торговое решение и не снимает этот барьер локально.", color = AppColors.textMuted)
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AppColors.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoRow("Execution State", status.state)
                InfoRow("P0 Gate", if (status.p0GatePassed) "PASS" else status.p0GateReason)
                InfoRow("WSS", if (status.marketWsConnected) "CONNECTED" else "OFFLINE")
                InfoRow("Binance", if (status.binanceConfigured) "CONFIGURED" else "NOT CONFIGURED")
                InfoRow("Positions", status.openPositions.toString() + " / " + status.maxOpenPositions)
                status.unresolvedSymbols.takeIf { it.isNotEmpty() }?.let {
                    InfoRow("Unresolved", it.joinToString(", "))
                }
                status.pendingEntrySymbols.takeIf { it.isNotEmpty() }?.let {
                    InfoRow("Pending entry", it.joinToString(", "))
                }
                status.error?.takeIf { it.isNotBlank() }?.let { Text(it, color = AppColors.red) }
            }
        }
        Button(onClick = onRecover, modifier = Modifier.fillMaxWidth()) {
            Text("RECONCILE / RECOVER")
        }
    }
}

@Composable
private fun DashboardScreen(
    padding: PaddingValues,
    status: Status,
    marketPairs: List<MarketPair>,
    candidates: List<Candidate>,
    message: String,
    refreshing: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onKill: () -> Unit,
    onScan: () -> Unit,
    onSettings: () -> Unit,
    onRecoverDashboard: () -> Unit
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
            SystemHealthBar(status)
        }

        if (status.reconcileRequired ||
            status.executionContractReconcileRequired ||
            status.state == "RECONCILE_REQUIRED") {
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = AppColors.surface)
                ) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("TRADING BLOCKED • RECOVER REQUIRED", color = AppColors.red, fontWeight = FontWeight.Bold)
                        Text(status.error ?: "Exchange state is not reconciled.", color = AppColors.textMuted)
                        OutlinedButton(
                            onClick = onRecoverDashboard,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("RECONCILE / RECOVER")
                        }
                    }
                }
            }
        }

        item {
            ControlCard(
                status = status,
                onStart = onStart,
                onPause = onPause,
                onResume = onResume,
                onStop = onStop,
                onKill = onKill
            )
        }

        item {
            HeroCard(status = status, best = best)
        }

        item {
            MarketPairsCard(marketPairs)
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
            ConnectionHealthCard(status)
        }

        item {
            BestCandidateCard(best)
        }

        item {
            RiskCard(status)
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
                    when (status.scannerState) {
                        "RUNNING" -> "Сканирование рынка…"
                        "READY" -> status.scannerSymbols.toString() +
                            " ликвидных USDT-пар • " + formatMs(status.scanDurationMs)
                        "ERROR" -> "Ошибка сканера: " +
                            (status.scannerError ?: "неизвестная ошибка")
                        else -> "Сканирование ещё не запускалось"
                    },
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
                    Icon(Icons.Filled.Refresh, contentDescription = "Запустить полное сканирование")
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

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniMetric("REGIME", best.regime)
                    MiniMetric("OBI", fmt(best.obi, 2))
                    MiniMetric("SHADOW", best.shadowDirection + " " + fmt(best.shadowConfidence, 2))
                }

                if (best.xaiTopFactor.isNotBlank()) {
                    Text(
                        "XAI: " + best.xaiTopFactor,
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.violet
                    )
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MiniMetric(
                    "Риск",
                    fmt(status.reservedRiskPct * 100, 2) +
                        "/" +
                        fmt(status.maxTotalRiskPct * 100, 0) +
                        "%"
                )
                MiniMetric(
                    "Позиций",
                    status.openPositions.toString() +
                        "/" + status.maxOpenPositions
                )
                MiniMetric("Сегодня", status.tradesToday.toString())
                MiniMetric("Loss", status.consecutiveLosses.toString())
            }
            Text(
                status.tradingMode + " • PnL сегодня " + fmt(status.dailyPnlUsdt, 2) + " USDT",
                style = MaterialTheme.typography.labelSmall,
                color = if (status.tradingMode == "PAUSED") AppColors.red else AppColors.textMuted
            )
        }
    }
}

@Composable
private fun ControlCard(
    status: Status,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onKill: () -> Unit
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
                    enabled = status.binanceConfigured &&
                        !status.running &&
                        !status.reconcileRequired &&
                        !status.executionContractReconcileRequired,
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
                if (status.running)
                    "AUTONOMOUS ENGINE RUNNING • BUY requires market/user WSS + P0 + risk/filter checks."
                 else if (status.reconcileRequired)
                    "TRADING BLOCKED • press RECONCILE / RECOVER."
                 else if (!status.binanceConfigured)
                    "Configure Binance Testnet API Key + Secret first."
                 else
                    "READY TO START • Williams will scan the market and make trading decisions autonomously.",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.amber
            )

            ActionButton(
                modifier = Modifier.fillMaxWidth(),
                text = "KILL SWITCH — STOP + CLOSE",
                icon = Icons.Filled.Warning,
                enabled = status.running || status.openPositions > 0,
                accent = AppColors.red,
                onClick = onKill
            )
        }
    }
}

@Composable
private fun ConnectionHealthCard(status: Status) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppColors.surface)
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Связь Binance", style = MaterialTheme.typography.titleMedium)

            HealthRow("Market WebSocket", status.marketWsConnected)
            HealthRow(
                "User Data Stream",
                status.userWsConnected && !status.userStreamSyncRequired
            )
            HealthRow("История готова", status.historyReady)
            Text(
                "FSM: " + status.fsmState,
                style = MaterialTheme.typography.bodySmall,
                color = if (status.fsmState.contains("RECONCILE") ||
                    status.fsmState.contains("KILL")
                ) AppColors.red else AppColors.textMuted
            )
        }
    }
}

@Composable
private fun HealthRow(label: String, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            if (ok) "OK" else "WAIT",
            color = if (ok) AppColors.green else AppColors.amber,
            fontWeight = FontWeight.SemiBold
        )
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

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MiniMetric("REGIME", candidate.regime)
                MiniMetric("OBI", fmt(candidate.obi, 2))
                MiniMetric("SHADOW", candidate.shadowDirection + " " + fmt(candidate.shadowConfidence, 2))
            }

            if (candidate.xaiTopFactor.isNotBlank()) {
                Text(
                    "XAI: " + candidate.xaiTopFactor,
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.violet
                )
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
private fun MarketPairsCard(pairs: List<MarketPair>) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AppColors.surface)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text("Рынок • USDT", style = MaterialTheme.typography.labelLarge, color = AppColors.textMuted)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                pairs.forEach { pair ->
                    Column(Modifier.padding(top = 6.dp)) {
                        Text(pair.symbol.removeSuffix("USDT") + "/USDT", style = MaterialTheme.typography.labelMedium)
                        Text(pair.price?.let { fmt(it) } ?: "—", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun PositionScreen(
    padding: PaddingValues,
    status: Status,
    candles: List<Candle>,
    selectedSymbol: String?,
    interval: String,
    onIntervalChange: (String) -> Unit,
    onSelect: (String) -> Unit,
    onSell: (String) -> Unit
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
            Text(
                "Портфель",
                style = MaterialTheme.typography.headlineSmall
            )
        }

        if (status.positions.isEmpty()) {
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = AppColors.surface
                    )
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "FLAT",
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Text(
                            "Открытых позиций нет.",
                            color = AppColors.textMuted
                        )
                    }
                }
            }
        } else {
            items(status.positions) { p ->
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = AppColors.surface
                    )
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement =
                                Arrangement.SpaceBetween,
                            verticalAlignment =
                                Alignment.CenterVertically
                        ) {
                            Text(
                                "LONG " + p.symbol,
                                modifier = Modifier.clickable { onSelect(p.symbol) },
                                style =
                                    MaterialTheme.typography.headlineSmall
                            )

                            Row(
                                horizontalArrangement =
                                    Arrangement.spacedBy(6.dp),
                                verticalAlignment =
                                    Alignment.CenterVertically
                            ) {
                                ModeChip(
                                    "RISK " +
                                        fmt(
                                            p.riskPct * 100,
                                            2
                                        ) +
                                        "%",
                                    AppColors.amber
                                )

                                OutlinedButton(
                                    onClick = {
                                        onSell(p.symbol)
                                    }
                                ) {
                                    Text("SELL")
                                }
                            }
                        }
                        InfoRow(
                            "Quantity",
                            fmt(p.qty, 6)
                        )
                        InfoRow(
                            "Entry",
                            fmt(p.entry)
                        )
                        InfoRow(
                            "Current",
                            fmt(status.price)
                        )
                        InfoRow(
                            "SL",
                            fmt(p.stop)
                        )
                        InfoRow(
                            "TP",
                            fmt(p.take)
                        )
                    }
                }
            }
        }

        if (selectedSymbol != null) {
            item { Text("График позиции • " + selectedSymbol, style = MaterialTheme.typography.titleMedium) }
            item {
                if (candles.isEmpty()) EmptyState(icon = Icons.Filled.ShowChart, title = "Нет графика", subtitle = "Выбери позицию и обнови данные.")
                else TradingChart(
                    candles,
                    status,
                    status.positions.firstOrNull { it.symbol == selectedSymbol },
                    interval,
                    onIntervalChange = onIntervalChange
                )
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
            Text("История и разбор сделок", style = MaterialTheme.typography.headlineSmall)
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
                    val wins = trades.count { it.outcome == "WIN" }
                    val losses = trades.count { it.outcome == "LOSS" }
                    val pnl = trades.mapNotNull { it.pnl }.sum()
                    InfoRow("Всего", trades.size.toString())
                    InfoRow("Закрытых", closed.toString())
                    InfoRow("Win rate", if (closed > 0) "%.1f%%".format(Locale.US, wins * 100.0 / closed) else "—")
                    InfoRow("Побед / убытков", wins.toString() + " / " + losses)
                    InfoRow("PnL", fmt(pnl, 2) + " USDT")
                }
            }
        }

        if (trades.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Filled.History,
                    title = "Сделок пока нет",
                    subtitle = "После первой Testnet сделки здесь сохранятся причина входа, волна, риск, MFE/MAE и разбор результата."
                )
            }
        }

        items(trades) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = AppColors.surface)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(it.symbol + " • " + it.outcome, style = MaterialTheme.typography.titleMedium)
                        Text(
                            fmt(it.pnl, 2) + " USDT",
                            color = if ((it.pnl ?: 0.0) >= 0.0) AppColors.green else AppColors.red,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text("Entry " + fmt(it.entry) + " → Exit " + fmt(it.exit), color = AppColors.textMuted)
                    Text(
                        "Wave W" + it.wavePosition + " • " + it.wavePhase +
                            " • Score " + fmt(it.score, 1) +
                            " • R " + fmt(it.rMultiple, 2)
                    )
                    Text(
                        "MFE " + fmt(it.mfeR, 2) + "R • MAE " + fmt(it.maeR, 2) + "R",
                        color = AppColors.textMuted
                    )
                    if (it.reason.isNotBlank()) {
                        Text("Почему открыли: " + it.reason, style = MaterialTheme.typography.bodySmall)
                    }
                    if (it.classification.isNotBlank()) {
                        Text(
                            "Разбор: " + it.classification,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (it.outcome == "LOSS") AppColors.amber else AppColors.green
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
    backendUrl: String,
    mobileToken: String,
    onBackendUrl: (String) -> Unit,
    onMobileToken: (String) -> Unit,
    onSaveBackend: () -> Unit,
    apiKey: String,
    apiSecret: String,
    onApiKey: (String) -> Unit,
    onApiSecret: (String) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    backupPassword: String,
    onBackupPassword: (String) -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    backupMessage: String,
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
                            Icons.Filled.Wifi,
                            contentDescription = null,
                            tint = AppColors.green
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Williams Runtime", style = MaterialTheme.typography.titleMedium)
                    }

                    Text(
                        "Автономный режим: торговый движок и связь с Binance работают прямо на телефоне. Удалённый Backend — опционально.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.textMuted
                    )

                    OutlinedTextField(
                        value = backendUrl,
                        onValueChange = onBackendUrl,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Backend URL") },
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = mobileToken,
                        onValueChange = onMobileToken,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Mobile API token") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )

                    Button(
                        onClick = onSaveBackend,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Сохранить подключение")
                    }

                    InfoRow("Execution authority", "Backend")
                    InfoRow("Universe", "BTC / ETH / BNB / SOL / XRP")
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
                        Text("Binance Testnet • secret never returned", style = MaterialTheme.typography.titleMedium)
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
                            "Статус: Binance подключён. Secret не возвращается в приложение."
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
                    InfoRow("Trade limit", "динамический")
                    InfoRow("Loss guard", "3 подряд → пауза")
                    InfoRow("Scanner cadence", "90 sec")
                    InfoRow("Wave top N", "8")
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
                            Icons.Filled.Lock,
                            contentDescription = null,
                            tint = AppColors.violet
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Восстановление телефона", style = MaterialTheme.typography.titleMedium)
                    }

                    Text(
                        "Зашифрованный backup переносит Binance Testnet credentials на другой телефон. Открытые позиции и ордера в файл не записываются: после восстановления Williams сверяется с Binance.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.textMuted
                    )

                    OutlinedTextField(
                        value = backupPassword,
                        onValueChange = onBackupPassword,
                        label = { Text("Пароль backup (10+ символов)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onExportBackup,
                            enabled = backupPassword.length >= 10,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("СОЗДАТЬ")
                        }

                        OutlinedButton(
                            onClick = onImportBackup,
                            enabled = backupPassword.length >= 10,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("ВОССТАНОВИТЬ")
                        }
                    }

                    if (backupMessage.isNotBlank()) {
                        Text(
                            backupMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (
                                backupMessage.contains("создан", true) ||
                                backupMessage.contains("восстановлен", true)
                            ) AppColors.green else AppColors.amber
                        )
                    }
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
private fun TradingChart(
    candles: List<Candle>,
    status: Status,
    position: PositionView? = null,
    interval: String = "1h",
    onIntervalChange: (String) -> Unit = {}
) {
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
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("ТФ " + interval, style = MaterialTheme.typography.labelLarge)
                listOf("1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "8h", "12h", "1d", "3d", "1w", "1M").forEach { tf ->
                    FilterChip(
                        selected = interval == tf,
                        onClick = { onIntervalChange(tf) },
                        label = { Text(tf) }
                    )
                }
            }
        }
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

            level(position?.entry ?: status.entry, AppColors.primary, "ENTRY")
            level(position?.take ?: status.tp, AppColors.green, "TP")
            level(position?.stop ?: status.sl, AppColors.red, "SL")
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
    val legacy = json.optJSONObject("position")
    val array = json.optJSONArray("positions")
    val parsed =
        mutableListOf<PositionView>()

    if (array != null) {
        for (i in 0 until array.length()) {
            val item =
                array.optJSONObject(i) ?: continue
            val symbol =
                item.optString("symbol", "").trim()
            if (symbol.isBlank()) continue

            parsed += PositionView(
                symbol = symbol,
                qty = item.optDouble("qty", 0.0),
                entry = item.optDouble("entry", 0.0),
                stop = item.optDouble("stop")
                    .takeUnless {
                        it.isNaN() || it == 0.0
                    },
                take = item.optDouble("take")
                    .takeUnless {
                        it.isNaN() || it == 0.0
                    },
                riskPct =
                    item.optDouble("risk_pct", 0.0)
            )
        }
    }

    val first =
        parsed.firstOrNull()

    val legacyQty =
        legacy?.optDouble("quantity")
            ?.takeUnless {
                it.isNaN() || it == 0.0
            }

    val legacyEntry =
        legacy?.optDouble("entry_price")
            ?.takeUnless {
                it.isNaN() || it == 0.0
            }

    val executionContract = json.optJSONObject("execution_state_contract")
    return Status(
        symbol =
            json.optString(
                "symbol",
                first?.symbol ?: "BTCUSDT"
            ),
        interval =
            json.optString("interval", "1h"),
        testnet =
            json.optBoolean("testnet", true),
        running =
            json.optBoolean("running", false),
        paused =
            json.optBoolean("paused", false),
        recovered =
            json.optBoolean("recovered", true),
        state =
            json.optString("state", "FLAT"),
        price =
            json.optDouble("price")
                .takeUnless { it.isNaN() },
        balance =
            json.optDouble("quote_balance")
                .takeUnless { it.isNaN() },
        qty =
            first?.qty ?: legacyQty,
        entry =
            first?.entry ?: legacyEntry,
        tp =
            json.optDouble("take_profit_price")
                .takeUnless {
                    it.isNaN() || it == 0.0
                },
        sl =
            json.optDouble("stop_loss_price")
                .takeUnless {
                    it.isNaN() || it == 0.0
                },
        pnl =
            json.optDouble("pnl")
                .takeUnless { it.isNaN() },
        pnlPct =
            json.optDouble("pnl_pct")
                .takeUnless { it.isNaN() },
        error =
            json.optString("last_error")
                .takeIf { it.isNotBlank() },
        // Native StandaloneRuntime exposes the same state as auth_configured;
        // keep compatibility with backend status payloads that use binance_configured.
        binanceConfigured =
            json.optBoolean(
                "binance_configured",
                json.optBoolean("auth_configured", false)
            ),
        riskPerTrade =
            json.optDouble(
                "risk_per_trade_pct",
                0.005
            ),
        maxDailyLoss =
            json.optDouble(
                "max_daily_loss_pct",
                0.03
            ),
        tradesToday =
            json.optInt("trades_today", 0),
        consecutiveLosses = json.optInt("consecutive_losses", 0),
        dailyPnlUsdt = json.optDouble("daily_pnl_usdt", 0.0),
        tradingMode = json.optString("trading_mode", "ACTIVE"),
        openPositions =
            json.optInt(
                "open_positions",
                parsed.size
            ),
        maxOpenPositions =
            json.optInt(
                "max_open_positions",
                 2
            ),
        reservedRiskPct =
            json.optDouble(
                "reserved_risk_pct",
                0.0
            ),
        maxTotalRiskPct =
            json.optDouble(
                "max_total_risk_pct",
                0.01
            ),
        reconcileRequired =
            json.optBoolean(
                "reconcile_required",
                false
            ),
        positions =
            parsed.toList(),
        scannerScanning =
            json.optBoolean(
                "scanner_scanning",
                false
            ),
        scannerState =
            json.optString(
                "scanner_state",
                "NOT_RUN"
            ),
        scannerError =
            json.optString("scanner_error")
                .takeIf { it.isNotBlank() },
        scannerSymbols =
            json.optInt(
                "scanner_symbols",
                0
            ),
        scanDurationMs =
            json.optLong(
                "scanner_duration_ms",
                0L
            ),
        marketWsConnected =
            json.optBoolean("market_ws_connected", false),
        userWsConnected =
            json.optBoolean("user_ws_connected", false),
        userStreamSyncRequired =
            json.optBoolean("user_stream_sync_required", true),
        historyReady =
            json.optBoolean("history_ready", false),
        fsmState =
            json.optString("fsm_state", "STOPPED"),
        executionContractVersion =
            executionContract?.optInt("version", 1) ?: 1,
        executionState =
            executionContract?.optString("state", "STOPPED") ?: "STOPPED",
        executionEnabled =
            executionContract?.optBoolean("execution_enabled", false) ?: false,
        executionContractReconcileRequired =
            executionContract?.optBoolean("reconciliation_required", true) ?: true,
        executionKillLatched =
            executionContract?.optBoolean("kill_switch_latched", false) ?: false,
        p0GatePassed = json.optBoolean("p0_gate_passed", executionContract?.optBoolean("execution_enabled", false) ?: false),
        p0GateReason = json.optString("p0_gate_reason", if (executionContract?.optBoolean("reconciliation_required", true) == true) "RECONCILE_REQUIRED" else "NOT_READY"),
        maxOpenPositionsLocked = json.optBoolean("max_open_positions_locked", false)
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
            wavePath = x.optString("wave_path", ""),
            wavePrimaryCount = x.optString("wave_primary_count", ""),
            waveAlternativeCount = x.optString("wave_alternative_count", ""),
            waveAbcPhase = x.optString("wave_abc_phase", ""),
            quantScore = x.optDouble("quant_score", x.optDouble("score", 0.0)),
            regime = x.optString("regime", "UNKNOWN"),
            regimeScore = x.optDouble("regime_score", 0.0),
            obi = x.optDouble("obi", 0.0),
            tradeFlowImbalance = x.optDouble("trade_flow_imbalance", 0.0),
            wave3Probability = x.optDouble("wave3_probability", 0.0),
            wave5Probability = x.optDouble("wave5_probability", 0.0),
            shadowDirection = x.optJSONObject("shadow_intent")?.optString("direction", "HOLD") ?: "HOLD",
            shadowConfidence = x.optJSONObject("shadow_intent")?.optDouble("confidence", 0.0) ?: 0.0,
            shadowReason = x.optJSONObject("shadow_intent")?.optString("reason", "") ?: "",
            xaiTopFactor = topXaiFactor(x.optJSONObject("xai_factors"))
        )
    }.sortedByDescending { it.score }

private fun parseTrades(array: JSONArray): List<Trade> =
    List(array.length()) { i ->
        val x = array.getJSONObject(i)
        val snap = x.optJSONObject("entry_snapshot")
        Trade(
            id = x.optString("id"),
            symbol = x.optString("symbol"),
            side = x.optString("side"),
            entry = x.optDouble("entry_price").takeUnless { it.isNaN() },
            exit = x.optDouble("exit_price").takeUnless { it.isNaN() },
            pnl = x.optDouble("pnl").takeUnless { it.isNaN() },
            pnlPct = x.optDouble("pnl_pct").takeUnless { it.isNaN() },
            rMultiple = x.optDouble("r_multiple").takeUnless { it.isNaN() },
            reason = x.optString("entry_reason"),
            outcome = x.optString("outcome", x.optString("status")),
            classification = x.optString("post_trade_classification"),
            wavePosition = snap?.optInt("wave_position", 0) ?: 0,
            wavePhase = snap?.optString("wave_phase", "") ?: "",
            score = snap?.optDouble("score", 0.0) ?: 0.0,
            mfeR = x.optDouble("mfe_r", 0.0),
            maeR = x.optDouble("mae_r", 0.0)
        )
    }

private fun parseLogs(array: JSONArray): List<String> =
    List(array.length()) { i ->
        val x = array.getJSONObject(i)
        x.optString("created_at") + "  " +
            x.optString("level") + "  " +
            x.optString("message")
    }

private fun topXaiFactor(factors: JSONObject?): String {
    if (factors == null) return ""
    var bestName = ""
    var bestValue = 0.0
    val keys = factors.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val value = factors.optDouble(key, 0.0)
        if (kotlin.math.abs(value) > kotlin.math.abs(bestValue)) {
            bestName = key
            bestValue = value
        }
    }
    return if (bestName.isBlank()) "" else bestName + "=" + fmt(bestValue, 2)
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
        path.endsWith("/panic") -> "PANIC STOP: новые входы остановлены"
        path.endsWith("/kill") -> "EMERGENCY EXIT: торговля остановлена, позиция закрыта"
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
