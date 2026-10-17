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
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

// Williams 4.25.0 autonomous Futures Testnet runtime.
// The phone owns the autonomous USD-M Futures Testnet runtime.
// CI compile-log capture enabled
// Diagnostic contract v2: runtime self-tests are exposed through /api/v1/diagnostics.
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The phone owns the Futures Testnet runtime. Keep it alive in a foreground
        // service; cockpit commands execute in-process and never send Binance
        // credentials to a remote backend.
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
        setContent {
            WilliamsTheme {
                WilliamsApp(this)
                // A visible Activity may start a service normally, which then promotes
                // itself immediately. Wait for the first draw before cold service class loading.
                LaunchedEffect(Unit) {
                    androidx.compose.runtime.withFrameNanos { }
                    androidx.compose.runtime.withFrameNanos { }
                    while (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) delay(100L)
                    val intent = Intent(this@MainActivity, TradingForegroundService::class.java)
                        .setAction(TradingForegroundService.ACTION_START)
                    try {
                        startService(intent)
                    } catch (_: IllegalStateException) {
                        // The Activity may have moved to the background between the check and call.
                        runCatching { ContextCompat.startForegroundService(this@MainActivity, intent) }
                            .onFailure { RuntimeServiceState.lastError = safeDiagnosticText(it.message.orEmpty()) }
                    }
                }
            }
        }
    }
}

internal object AppAppearance {
    var light by mutableStateOf(false)
    var textScale by mutableFloatStateOf(1f)
}

object AppColors {
    val background get() = if (AppAppearance.light) Color(0xFFF2F5FA) else Color(0xFF070B12)
    val surface get() = if (AppAppearance.light) Color.White else Color(0xFF0F1622)
    val surface2 get() = if (AppAppearance.light) Color(0xFFE5EBF3) else Color(0xFF151F2E)
    val primary get() = if (AppAppearance.light) Color(0xFF006F76) else Color(0xFF5DE2E7)
    val violet get() = if (AppAppearance.light) Color(0xFF603DAA) else Color(0xFF9D8CFF)
    val green get() = if (AppAppearance.light) Color(0xFF006B48) else Color(0xFF62E6A7)
    val amber get() = if (AppAppearance.light) Color(0xFF8C5900) else Color(0xFFFFC857)
    val red get() = if (AppAppearance.light) Color(0xFFB3263C) else Color(0xFFFF687A)
    val blue get() = if (AppAppearance.light) Color(0xFF2255A0) else Color(0xFF67A8FF)
    val textMuted get() = if (AppAppearance.light) Color(0xFF526176) else Color(0xFF98A8BD)
}

// Static release-contract anchor: the chart must expose every approved USD-M contract
// while using the shared backend/execution universe, never a separate scan universe:
// "BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT",
// "DOGEUSDT", "ADAUSDT", "LINKUSDT", "AVAXUSDT", "LTCUSDT".
private val APPROVED_FUTURES_CHART_SYMBOLS = APPROVED_FUTURES_SYMBOLS
private val CHART_VIEW_INTERVALS = APPROVED_FUTURES_KLINE_INTERVALS

@Composable
private fun WilliamsTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val preferences = context.getSharedPreferences("cockpit_appearance", Context.MODE_PRIVATE)
        AppAppearance.light = preferences.getBoolean("light", false)
        AppAppearance.textScale = preferences.getFloat("text_scale", 1f).coerceIn(1f, 1.3f)
    }
    val density = LocalDensity.current
    val base = if (AppAppearance.light) androidx.compose.material3.lightColorScheme() else androidx.compose.material3.darkColorScheme()
    val colors = base.copy(
        primary = AppColors.primary,
        secondary = AppColors.violet,
        tertiary = AppColors.green,
        error = AppColors.red,
        background = AppColors.background,
        surface = AppColors.surface,
        surfaceVariant = AppColors.surface2,
        onPrimary = if (AppAppearance.light) Color.White else Color(0xFF001113),
        onBackground = if (AppAppearance.light) Color(0xFF182333) else Color(0xFFF2F6FC),
        onSurface = if (AppAppearance.light) Color(0xFF182333) else Color(0xFFF2F6FC),
        onSurfaceVariant = AppColors.textMuted
    )

    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * AppAppearance.textScale)) {
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
}

data class Status(
    val symbol: String = "BTCUSDT",
    val interval: String = "1h",
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
    val binanceConfigured: Boolean = false,
    val riskPerTrade: Double = 0.0025,
    val maxDailyLoss: Double = 0.03,
    val tradesToday: Int = 0,
    val consecutiveLosses: Int = 0,
    val dailyPnlUsdt: Double = 0.0,
    val dailyLossPct: Double = 0.0,
    val dailyCapitalFlowUsdt: Double = 0.0,
    val tradingMode: String = "ACTIVE",
    val openPositions: Int = 0,
    val maxOpenPositions: Int = APPROVED_FUTURES_CHART_SYMBOLS.size,
    val reservedRiskPct: Double = 0.0,
    val maxTotalRiskPct: Double = 0.006,
    val reconcileRequired: Boolean = false,
    val positions: List<PositionView> = emptyList(),
    val scannerScanning: Boolean = false,
    val scannerState: String = "NOT_RUN",
    val scannerError: String? = null,
    val scannerSymbols: Int = 0,
    val scannerConfiguredSymbols: Int = 10,
    val scannerProgressSymbols: Int = 0,
    val scannerProgressTotal: Int = 10,
    val scannerUniverse: List<String> = emptyList(),
    val scannerLongCandidates: Int = 0,
    val scannerShortCandidates: Int = 0,
    val allowLong: Boolean = false,
    val allowShort: Boolean = false,
    val futuresLeverage: Int = 0,
    val futuresLeverageSettingsJson: String = "",
    val futuresMarginType: String = "UNKNOWN",
    val futuresForceOneWay: Boolean = false,
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
    val pendingEntrySymbols: List<String> = emptyList(),
    val forceTestnetMode: Boolean = false,
    val snapshotAtMs: Long = 0L,
    val positionsSnapshotOk: Boolean = false,
    val positionsSnapshotAtMs: Long = 0L,
    val accountSnapshotAtMs: Long = 0L,
    val futuresIntents: List<JSONObject> = emptyList(),
    val cockpitReadiness: JSONObject = JSONObject()
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
    val fractalDown: Boolean,
    val ao: Double? = null,
    val ac: Double? = null,
    val mfiProxy: Double? = null,
    val signalTypes: List<String> = emptyList()
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
    val xaiTopFactor: String = "",
    val campaignReady: Boolean = false,
    val entrySignalType: String = "",
    val entryTriggerPrice: Double? = null,
    val entryProtectivePrice: Double? = null
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
    val maeR: Double,
    val direction: String = "",
    val feeUsdt: Double? = null,
    val feeKnown: Boolean = true,
    val initialRiskUsdt: Double? = null,
    val entrySlippagePct: Double? = null,
    val pnlComplete: Boolean = true,
    val fillCount: Int = 0,
    val openedAtMs: Long = 0L,
    val closedAtMs: Long = 0L,
    val stopPrice: Double? = null,
    val exitReason: String = ""
)

data class MarketPair(
    val symbol: String,
    val price: Double?
)

data class PortfolioAsset(
    val asset: String,
    val free: Double,
    val locked: Double,
    val total: Double,
    val priceUsdt: Double?,
    val valueUsdt: Double,
    val allocationPct: Double
)

data class PortfolioSummary(
    val configured: Boolean = false,
    val totalEquityUsdt: Double? = null,
    val freeEquityUsdt: Double? = null,
    val lockedEquityUsdt: Double? = null,
    val realizedPnlUsdt: Double = 0.0,
    val unrealizedPnlUsdt: Double = 0.0,
    val assets: List<PortfolioAsset> = emptyList(),
    val positions: List<PositionView> = emptyList()
)

data class PositionView(
    val symbol: String,
    val qty: Double,
    val entry: Double,
    val stop: Double?,
    val take: Double?,
    val riskPct: Double,
    val currentPrice: Double? = null,
    val positionValueUsdt: Double = 0.0,
    val allocationPct: Double = 0.0,
    val pnlUsdt: Double = 0.0,
    val pnlPct: Double = 0.0,
    val ocoListId: String = "",
    val ocoListClientId: String = "",
    val direction: String = ""
)

private class LocalRuntimeApi(context: Context) {
    private val appContext = context.applicationContext

    fun get(path: String): String =
        StandaloneRuntime.request(appContext, "GET", path, null)

    fun post(path: String, body: String? = null): String =
        StandaloneRuntime.request(appContext, "POST", path, body)

    fun delete(path: String): String =
        StandaloneRuntime.request(appContext, "DELETE", path, null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WilliamsApp(context: Context) {
    val api = remember { LocalRuntimeApi(context) }
    val scope = rememberCoroutineScope()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var status by remember { mutableStateOf(Status()) }
    var marketPairs by remember { mutableStateOf(emptyList<MarketPair>()) }
    var selectedPositionSymbol by remember { mutableStateOf<String?>(null) }
    var candles by remember { mutableStateOf(emptyList<Candle>()) }
    var selectedChartSymbol by rememberSaveable { mutableStateOf("BTCUSDT") }
    var chartCandles by remember { mutableStateOf(emptyList<Candle>()) }
    var chartLoading by remember { mutableStateOf(false) }
    var chartError by remember { mutableStateOf<String?>(null) }
    var chartLivePrice by remember { mutableStateOf<Double?>(null) }
    var chartQuoteSource by remember { mutableStateOf("UNAVAILABLE") }
    var chartQuoteUpdatedAtMs by remember { mutableLongStateOf(0L) }
    var chartIndicatorSummary by remember { mutableStateOf("Загрузка индикаторов…") }
    var selectedChartInterval by remember { mutableStateOf("1h") }
    var candidates by remember { mutableStateOf(emptyList<Candidate>()) }
    var trades by remember { mutableStateOf(emptyList<Trade>()) }
    var logs by remember { mutableStateOf(emptyList<String>()) }
    var apiKey by remember { mutableStateOf("") }
    var apiSecret by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("Williams Futures: локальный автономный Runtime") }
    var refreshing by remember { mutableStateOf(false) }
    var backupPassword by remember { mutableStateOf("") }
    var backupMessage by remember { mutableStateOf("") }
    var diagnosticsMessage by remember { mutableStateOf("") }
    var diagnosticReport by remember { mutableStateOf<JSONObject?>(null) }
    var diagnosticsRunning by remember { mutableStateOf(false) }
    var historicalTrade by remember { mutableStateOf<Trade?>(null) }
    var requestedCloseSymbol by remember { mutableStateOf<String?>(null) }
    var criticalSetting by remember { mutableStateOf("") }
    val loadMutex = remember { Mutex() }

    suspend fun loadAll(scan: Boolean) {
        if (!loadMutex.tryLock()) return
        try {
            refreshing = scan
            val payload = withContext(Dispatchers.IO) {
                if (scan) api.get("/api/v1/scanner?refresh=true")
                JSONObject(api.get("/api/v1/cockpit"))
            }
            val source = payload.getJSONObject("status")
            val scanner = payload.getJSONObject("scanner")
            val loadedStatus = parseStatus(source)
            val loadedCandidates = parseCandidates(scanner.optJSONArray("candidates") ?: JSONArray())
            val loadedTrades = parseTrades(payload.optJSONArray("trades") ?: JSONArray())
            val prices = source.optJSONObject("live_prices") ?: JSONObject()
            val loadedPairs = APPROVED_FUTURES_CHART_SYMBOLS.map { symbol ->
                MarketPair(symbol, prices.optDouble(symbol).takeIf { it.isFinite() && it > 0.0 })
            }
            withContext(Dispatchers.Main) {
                // Publish one envelope together; no screen combines independent status reads.
                status = loadedStatus
                candidates = loadedCandidates
                trades = loadedTrades
                marketPairs = loadedPairs
                logs = CockpitDiagnostics.rows(payload.optJSONArray("events")).map {
                    cockpitTime(it.optLong("E")) + " • " + it.optString("state") + " • " + it.optString("detail")
                }.reversed()
                message = if (scan) "Сканирование запрошено" else "Данные обновлены"
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val reason = safeDiagnosticText(failure.message ?: failure.javaClass.simpleName)
            withContext(Dispatchers.Main) {
                message = "Частичная ошибка обновления: $reason"
                status = status.copy(positionsSnapshotOk = false,
                    cockpitReadiness = JSONObject(status.cockpitReadiness.toString())
                        .put("technical_ready", false).put("new_entries_allowed", false)
                        .put("position_management_ready", false).put("new_entries_reason", "Обновление состояния не подтверждено: $reason"))
            }
            withContext(Dispatchers.IO) {
                runCatching { api.post("/api/v1/ui/event", JSONObject().put("action", "REFRESH_FAILED").put("detail", reason).toString()) }
            }
        } finally {
            loadMutex.unlock()
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { refreshing = false }
        }
    }

    fun refresh(scan: Boolean) {
        scope.launch {
            loadAll(scan)
        }
    }

    LaunchedEffect(selectedPositionSymbol, selectedChartInterval) {
        val symbol = selectedPositionSymbol ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching {
                val json = JSONObject(
                    api.get(
                        "/api/v1/market/klines?symbol=" +
                            symbol +
                            "&interval=" + selectedChartInterval
                    )
                )
                val parsed = parseCandles(json.optJSONArray("candles") ?: JSONArray())
                withContext(Dispatchers.Main) { candles = parsed }
            }
        }
    }

    // Read-only market view; the Runtime scanner independently evaluates all ten contracts.
    LaunchedEffect(tab, selectedChartSymbol, selectedChartInterval, historicalTrade?.id) {
        if (tab != 1) return@LaunchedEffect
        chartCandles = emptyList()
        chartLivePrice = null
        chartQuoteSource = "UNAVAILABLE"
        chartQuoteUpdatedAtMs = 0L
        chartIndicatorSummary = "Загрузка индикаторов…"
        chartError = null
        chartLoading = true
        var lastCandleRefreshMs = 0L
        while (isActive) {
            val requestSymbol = selectedChartSymbol
            val requestInterval = selectedChartInterval
            try {
                val refreshCandles = chartCandles.isEmpty() || System.currentTimeMillis() - lastCandleRefreshMs >= 30_000L
                val raw = withContext(Dispatchers.IO) {
                    if (refreshCandles) api.get("/api/v1/market/klines?symbol=" + requestSymbol + "&interval=" + requestInterval + "&bars=6000" +
                        (historicalTrade?.let { "&until=" + ((it.closedAtMs.takeIf { t -> t > 0L } ?: it.openedAtMs) + 14_400_000L) } ?: ""))
                    else api.get("/api/v1/market/quote?symbol=" + requestSymbol)
                }
                val payload = JSONObject(raw)
                payload.optString("error").takeIf { it.isNotBlank() && it != "null" }?.let {
                    throw IllegalStateException(it)
                }
                require(payload.optString("symbol") == requestSymbol) { "Runtime вернул график другой пары" }
                require(!refreshCandles || payload.optString("interval") == requestInterval) { "Runtime вернул другой таймфрейм; молчаливая подмена запрещена" }
                val loaded = if (refreshCandles) parseCandles(payload.optJSONArray("candles") ?: JSONArray()) else chartCandles
                chartLivePrice = if (historicalTrade == null) payload.optDouble("live_price").takeIf { it.isFinite() && it > 0.0 } else null
                chartQuoteSource = payload.optString("live_price_source", "UNAVAILABLE")
                chartQuoteUpdatedAtMs = payload.optLong("live_price_at_ms", 0L)
                if (refreshCandles) {
                    chartIndicatorSummary = chartIndicatorSummary(payload.optJSONObject("indicator_snapshot"))
                    lastCandleRefreshMs = System.currentTimeMillis()
                }
                if (loaded.isEmpty()) {
                    chartError = "$requestSymbol $requestInterval: закрытые свечи не получены"
                } else {
                    chartCandles = loaded
                    chartError = null
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                chartError = failure.message ?: failure.javaClass.simpleName
            }
            chartLoading = false
            delay(if (historicalTrade == null) 2_000L else 30_000L)
        }
    }

    fun saveFuturesLeverage(payload: JSONObject) {
        scope.launch(Dispatchers.IO) {
            try {
                val response = JSONObject(api.post("/api/v1/config/futures-leverage", payload.toString()))
                check(response.optBoolean("saved", false)) {
                    response.optString("error", "Не удалось сохранить плечо")
                }
                withContext(Dispatchers.Main) { message = response.optString("message") }
                loadAll(false)
            } catch (failure: Exception) {
                withContext(Dispatchers.Main) { message = failure.message ?: "Ошибка настройки плеча" }
            }
        }
    }

    fun command(path: String) {
        scope.launch(Dispatchers.IO) {
            try {
                runCatching { api.post("/api/v1/ui/event", JSONObject().put("action", "CONTROL").put("detail", path).toString()) }
                val rawResponse = api.post(path)
                val response = runCatching { JSONObject(rawResponse) }.getOrNull()
                val label = if (path.endsWith("/control/start")) {
                    if (response?.optBoolean("started", false) == true) {
                        "Runtime запущен. Загружается закрытая H1-история; сканер должен показать RUNNING/READY или точную блокировку."
                    } else if (response?.optString("reason") == "already_running") {
                        "Runtime уже запущен; обновляю фактическое состояние сканера."
                    } else {
                        val directBlocker = response?.optString("blocker")
                            ?.takeIf { !it.isNullOrBlank() && it != "null" }
                        val reconcileBlocker = response?.optJSONObject("futures_reconciliation")
                            ?.optString("blocker")
                            ?.takeIf { !it.isNullOrBlank() && it != "null" }
                        val reason = directBlocker ?: reconcileBlocker
                            ?: response?.optString("state")?.takeIf { !it.isNullOrBlank() && it != "null" }
                            ?: "Binance Futures reconciliation/account validation did not pass"
                        "Runtime НЕ запущен: " + reason
                    }
                } else {
                    response?.optString("reason")?.takeIf { it.isNotBlank() && it != "null" } ?: commandLabel(path)
                }
                withContext(Dispatchers.Main) {
                    message = label
                }
                loadAll(false)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    message = e.message ?: "Ошибка команды"
                }
            }
        }
    }

    fun setTestnetForceMode(enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            try {
                val payload = JSONObject()
                    .put("enabled", enabled)
                    .put("acknowledge", true)
                val response = JSONObject(
                    api.post("/api/v1/control/testnet-force-mode", payload.toString())
                )
                val label = response.optString(
                    "message",
                    if (response.optBoolean("enabled", false)) {
                        "Режим ручного направления TESTNET включён."
                    } else {
                        "Режим отключён; Runtime оставлен на паузе."
                    }
                )
                withContext(Dispatchers.Main) { message = label }
                loadAll(false)
            } catch (failure: Exception) {
                withContext(Dispatchers.Main) {
                    message = failure.message ?: "Не удалось изменить режим TESTNET"
                }
                loadAll(false)
            }
        }
    }

    fun submitForcedTestnetEntry(symbol: String, direction: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val payload = JSONObject()
                    .put("symbol", symbol)
                    .put("direction", direction)
                    .put("acknowledge", true)
                val response = JSONObject(
                    api.post("/api/v1/control/testnet-force-entry", payload.toString())
                )
                val detail = response.optString(
                    "message",
                    response.optString("blocker", "Нет подробностей от Runtime")
                )
                val label = if (response.optBoolean("accepted", false)) {
                    "$detail • ${response.optString("symbol")} ${response.optString("direction")}, " +
                        "trigger ${response.optString("trigger_price")}, " +
                        "SL ${response.optString("protective_stop")}, " +
                        "risk ${(response.optDouble("risk_pct", 0.0) * 100.0)}%"
                } else {
                    "Условный вход не принят: $detail"
                }
                withContext(Dispatchers.Main) { message = label }
                loadAll(false)
            } catch (failure: Exception) {
                withContext(Dispatchers.Main) {
                    message = failure.message ?: "Ошибка принудительного входа TESTNET"
                }
                loadAll(false)
            }
        }
    }

    fun saveCredentials() {
        scope.launch(Dispatchers.IO) {
            try {
                require(apiKey.isNotBlank()) { "Введите API Key" }
                require(apiSecret.isNotBlank()) { "Введите API Secret" }
                val payload = JSONObject()
                    .put("api_key", apiKey.trim())
                    .put("api_secret", apiSecret.trim())
                    .put("testnet", true)
                api.post("/api/v1/config/binance", payload.toString())

                val verified = JSONObject(api.get("/api/v1/status?fast=true"))
                val configured = verified.optBoolean(
                    "binance_configured",
                    verified.optBoolean("auth_configured", false)
                )
                val testnet = verified.optBoolean("testnet", false)
                val executionEnabled = verified.optBoolean("execution_enabled", false)

                withContext(Dispatchers.Main) {
                    apiKey = ""
                    apiSecret = ""
                    status = parseStatus(verified)
                    diagnosticsMessage = ""
                    message = when {
                        configured && testnet && verified.optBoolean("running", false) && executionEnabled ->
                            "Binance Futures Testnet подтверждён; локальный Runtime и Execution Gate готовы"
                        configured && testnet && !verified.optBoolean("running", false) ->
                            "Ключи Futures Testnet проверены. Runtime остановлен: нажми START для сверки биржи, загрузки H1-истории и запуска сканера."
                        configured && testnet ->
                            "Futures Testnet подтверждён; входы пока заблокированы проверками безопасности, данных или сверки"
                        configured ->
                            "Ключи сохранены, но runtime не подтвердил TESTNET"
                        else ->
                            "Ключи сохранены, но Binance ещё не подтвердил конфигурацию"
                    }
                }
                refresh(false)
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
                val verified = JSONObject(api.get("/api/v1/status?fast=true"))
                withContext(Dispatchers.Main) {
                    status = parseStatus(verified)
                    message = "Локальные Binance Futures Testnet ключи удалены."
                    apiKey = ""
                    apiSecret = ""
                }
                refresh(false)
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
                            "Настройки восстановлены. Binance-ключи не импортируются; Futures-аккаунт будет заново сверён напрямую с Binance."
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

    fun selfHeal() {
        scope.launch(Dispatchers.IO) {
            try {
                val result = JSONObject(api.post("/api/v1/control/self-heal"))
                val blockers = result.optJSONArray("execution_blockers")
                val blockerText = if (blockers == null || blockers.length() == 0) {
                    "нет"
                } else {
                    (0 until blockers.length()).joinToString(", ") { blockers.optString(it) }
                }
                withContext(Dispatchers.Main) {
                    diagnosticsMessage =
                        if (result.optBoolean("execution_ready", false)) {
                            "SELF-HEAL: контур восстановлен, Execution READY."
                        } else {
                            "SELF-HEAL: безопасное восстановление выполнено. Блокировки: $blockerText"
                        }
                }
                loadAll(false)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    diagnosticsMessage =
                        e.message ?: "Не удалось выполнить безопасное восстановление"
                }
            }
        }
    }

    fun runDiagnostics() {
        if (diagnosticsRunning) return
        diagnosticsRunning = true
        scope.launch(Dispatchers.IO) {
            try {
                runCatching { api.post("/api/v1/ui/event", JSONObject().put("action", "DIAGNOSTICS").put("detail", "Run requested").toString()) }
                val report = api.get("/api/v1/diagnostics?run=true")
                DiagnosticsArchive.writeLatest(context, report)
                withContext(Dispatchers.Main) {
                    diagnosticReport = JSONObject(report)
                    diagnosticsMessage = "Проверка завершена: " + diagnosticReport?.optJSONObject("diagnostic_summary")?.optString("overall")

                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    diagnosticsMessage =
                        e.message ?: "Не удалось выполнить диагностику"
                }
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { diagnosticsRunning = false }
            }
        }
    }

    val exportDiagnosticsLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip")
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch(Dispatchers.IO) {
                try {
                    val source = requireNotNull(DiagnosticsArchive.latest(context)) {
                        "Диагностика ещё не создана"
                    }
                    context.contentResolver.openOutputStream(uri).use { output ->
                        requireNotNull(output)
                        source.inputStream().use { input -> input.copyTo(output) }
                    }
                    withContext(Dispatchers.Main) {
                        diagnosticsMessage = "Диагностический архив экспортирован."
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        diagnosticsMessage =
                            e.message ?: "Не удалось экспортировать диагностику"
                    }
                }
            }
        }

    fun exportDiagnostics() {
        if (DiagnosticsArchive.latest(context) == null) {
            diagnosticsMessage = "Сначала запустите диагностику."
            return
        }
        exportDiagnosticsLauncher.launch("Williams_Diagnostics.zip")
    }

    LaunchedEffect(Unit) {
        delay(700L)
        loadAll(false)
        while (isActive) {
            delay(5_000L)
            loadAll(false)
        }
    }

    LaunchedEffect(tab) {
        withContext(Dispatchers.IO) {
            runCatching { api.post("/api/v1/ui/event", JSONObject().put("action", "NAVIGATE").put("detail", "screen=$tab").toString()) }
        }
        if (tab == 4 && diagnosticReport == null) runDiagnostics()
    }
    BackHandler(tab in listOf(3, 4, 5)) { tab = 6 }
    val titles = listOf("Главная", "Рынок", "Сделки", "Анализ", "Система")
    val destinations = listOf(0, 1, 2, 7, 6)
    val icons = listOf(Icons.Filled.Dashboard, Icons.Filled.ShowChart,
        Icons.Filled.AccountBalanceWallet, Icons.Filled.Timeline, Icons.Filled.Settings)

    if (criticalSetting.isNotEmpty()) AlertDialog(
        onDismissRequest = { criticalSetting = "" },
        title = { Text(when (criticalSetting) { "save" -> "Изменить подключение Binance?"; "clear" -> "Удалить ключи Binance?"; else -> "Сбросить аварийную блокировку?" }) },
        text = { Text(when (criticalSetting) {
            "save" -> "Будут проверены новые ключи USDⓈ-M Futures Testnet. Изменение подключения разрешено только при безопасном состоянии Runtime."
            "clear" -> "Будут удалены сохранённые ключи API. Удаление запрещено при позициях или неразрешённых заявках."
            else -> "Сброс разрешён только после остановки движка, подтверждения пустого счёта и отсутствия ожидающих заявок."
        }) },
        confirmButton = { TextButton(onClick = {
            when (criticalSetting) { "save" -> saveCredentials(); "clear" -> clearCredentials(); else -> command("/api/v1/control/kill/reset") }
            criticalSetting = ""
        }) { Text("Подтвердить") } },
        dismissButton = { TextButton(onClick = { criticalSetting = "" }) { Text("Отмена") } }
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
                                "WILLIAMS BOT 2.1",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "USDⓈ-M FUTURES • 10 ПАР • LONG / SHORT",
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
                    val targetTab = destinations[index]
                    NavigationBarItem(
                        selected = if (targetTab == 6) tab in listOf(3, 4, 5, 6) else tab == targetTab,
                        onClick = { tab = targetTab },
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
            0 -> CockpitDashboard(padding, status, candidates, message,
                onMarket = { selectedChartSymbol = it; historicalTrade = null; tab = 1 },
                onSystem = { tab = 4 }, controls = {
                    CockpitControls(status,
                        onStart = { command("/api/v1/control/start") },
                        onPause = { command("/api/v1/control/pause") },
                        onResume = { command("/api/v1/control/resume") },
                        onSuspend = { command("/api/v1/control/stop") },
                        onShutdown = { command("/api/v1/control/shutdown") },
                        onKill = { command("/api/v1/control/block") })
                })

            1 -> ScannerScreen(
                padding = padding,
                candidates = candidates,
                status = status,
                refreshing = refreshing,
                message = message,
                chartSymbol = selectedChartSymbol,
                chartInterval = selectedChartInterval,
                chartCandles = chartCandles,
                chartLivePrice = chartLivePrice,
                chartQuoteSource = chartQuoteSource,
                chartQuoteUpdatedAtMs = chartQuoteUpdatedAtMs,
                chartIndicatorSummary = chartIndicatorSummary,
                chartLoading = chartLoading,
                chartError = chartError,
                onChartSymbolChange = { selectedChartSymbol = it; historicalTrade = null },
                onChartIntervalChange = { selectedChartInterval = it },
                onRefresh = { refresh(true) },
                historicalTrade = historicalTrade,
                onLive = { historicalTrade = null }
            )

            2 -> CockpitTrades(padding, status, trades,
                onMarket = { selectedChartSymbol = it; historicalTrade = null; tab = 1 },
                onTradeChart = { historicalTrade = it; selectedChartSymbol = it.symbol; selectedChartInterval = "1h"; tab = 1 },
                active = {
                PositionScreen(
                padding = PaddingValues(0.dp),
                status = status,
                candles = candles,
                selectedSymbol = selectedPositionSymbol,
                interval = selectedChartInterval,
                onIntervalChange = { selectedChartInterval = it },
                onSelect = { selectedPositionSymbol = it },
                onSell = { symbol ->
                    requestedCloseSymbol = symbol
                }
                )
                requestedCloseSymbol?.let { symbol ->
                    AlertDialog(onDismissRequest = { requestedCloseSymbol = null },
                        title = { Text("Закрыть $symbol?") },
                        text = { Text("Будет отправлен запрос закрытия позиции с проверкой принадлежности и подтверждением Binance.") },
                        dismissButton = { TextButton(onClick = { requestedCloseSymbol = null }) { Text("Отмена") } },
                        confirmButton = { TextButton(onClick = {
                            requestedCloseSymbol = null
                    scope.launch(Dispatchers.IO) {
                        try {
                            val result = JSONObject(
                                api.post("/api/v1/control/sell?symbol=" + symbol)
                            )
                            val confirmed = result.optBoolean("sold", false)
                            val reconcileRequired = result.optBoolean("reconcile_required", false)
                            val direction = result.optString("direction_closed", "Futures")
                            val detail = result.optJSONObject("result")
                                ?.optString("blocker")
                                ?.takeIf { it.isNotBlank() && it != "null" }
                                ?: result.optString("reason", "биржа не подтвердила закрытие")
                            withContext(Dispatchers.Main) {
                                message = when {
                                    confirmed && reconcileRequired ->
                                        "$direction позиция $symbol закрыта и подтверждена биржей, но защитный ордер/журнал требует сверки; новые входы заблокированы: $detail"
                                    confirmed ->
                                        "$direction позиция $symbol закрыта и подтверждена Futures Runtime"
                                    else ->
                                        "Закрытие $symbol НЕ подтверждено: $detail. Не отправляй повторную заявку вслепую."
                                }
                            }
                            loadAll(false)
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                message =
                                    e.message ?: "SELL error"
                            }
                        }
                    }
                        }) { Text("Закрыть позицию") } })
                }
                }, history = { HistoryScreen(PaddingValues(0.dp), trades, logs) {
                    historicalTrade = it; selectedChartSymbol = it.symbol; selectedChartInterval = "1h"; tab = 1
                } })

            3 -> HistoryScreen(
                padding = padding,
                trades = trades,
                logs = logs,
                onTradeChart = { historicalTrade = it; selectedChartSymbol = it.symbol; selectedChartInterval = "1h"; tab = 1 }
            )

            4 -> CockpitDiagnosticScreen(padding, status, diagnosticReport, diagnosticsMessage,
                onRun = ::runDiagnostics, onExport = ::exportDiagnostics, onSelfHeal = ::selfHeal, running = diagnosticsRunning)

            7 -> CockpitAnalysis(padding, status, candidates) {
                selectedChartSymbol = it; historicalTrade = null; tab = 1
            }

            6 -> MoreScreen(
                padding = padding,
                onIncidents = { tab = 3 },
                onDiagnostics = { tab = 4 },
                onConfig = { tab = 5 },
                advanced = {
                    CockpitAppearanceCard(context)
                    CockpitCard("Торговля и риск") {
                        CockpitField("Инструменты", "10 USDⓈ-M контрактов")
                        CockpitField("Направления", "LONG и SHORT")
                        CockpitField("Решение / контекст", "H1 / H4, D1")
                        CockpitField("Резерв риска", "до 0,25% на вход / 0,60% суммарно")
                        CockpitField("Дневной лимит убытка", "3%")
                        Text("Параметры ограничены торговым движком. Изменение экрана графика их не меняет.", style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted)
                    }
                    TestnetForceTradingCard(status, ::setTestnetForceMode, ::submitForcedTestnetEntry)
                    FuturesLeverageSettingsCard(status, ::saveFuturesLeverage)
                    if (status.executionKillLatched) OutlinedButton(onClick = { criticalSetting = "reset" }, modifier = Modifier.fillMaxWidth()) { Text("Сбросить аварийную блокировку при пустом счёте") }
                }
            )

            else -> SettingsScreen(
                padding = padding,
                status = status,
                apiKey = apiKey,
                apiSecret = apiSecret,
                onApiKey = { apiKey = it },
                onApiSecret = { apiSecret = it },
                onSave = { criticalSetting = "save" },
                onClear = { criticalSetting = "clear" },
                onSaveLeverage = ::saveFuturesLeverage,
                backupPassword = backupPassword,
                onBackupPassword = { backupPassword = it },
                onExportBackup = ::exportBackup,
                onImportBackup = ::importBackup,
                backupMessage = backupMessage,
                onRunDiagnostics = ::runDiagnostics,
                onExportDiagnostics = ::exportDiagnostics,
                diagnosticsMessage = diagnosticsMessage,
                message = message
            )
        }
    }
}

@Composable
private fun MoreScreen(
    padding: PaddingValues,
    onIncidents: () -> Unit,
    onDiagnostics: () -> Unit,
    onConfig: () -> Unit,
    advanced: @Composable () -> Unit = {}
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp)
    ) {
        item {
            Text("Система", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "История, диагностика и настройки собраны здесь, чтобы главные экраны оставались компактными.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.textMuted
            )
        }
        item {
            MoreDestinationCard(
                title = "История и журнал",
                description = "Закрытые сделки, события Runtime и причины отказов.",
                action = "ОТКРЫТЬ ИСТОРИЮ",
                onClick = onIncidents
            )
        }
        item {
            MoreDestinationCard(
                title = "Диагностика",
                description = "REST, WebSocket, история свечей, сканер, Execution Gate и экспорт ZIP.",
                action = "ОТКРЫТЬ ДИАГНОСТИКУ",
                onClick = onDiagnostics
            )
        }
        item {
            MoreDestinationCard(
                title = "Настройки и резервная копия",
                description = "Binance Futures Demo API, резервное копирование и восстановление.",
                action = "ОТКРЫТЬ НАСТРОЙКИ",
                onClick = onConfig
            )
        }
        item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { advanced() } }
    }
}

@Composable
private fun MoreDestinationCard(
    title: String,
    description: String,
    action: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppColors.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted)
            OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(action) }
        }
    }
}

@Composable
private fun TestnetForceTradingCard(
    status: Status,
    onSetMode: (Boolean) -> Unit,
    onSubmitEntry: (String, String) -> Unit
) {
    val approvedSymbols = listOf(
        "BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT",
        "DOGEUSDT", "ADAUSDT", "LINKUSDT", "AVAXUSDT", "LTCUSDT"
    )
    var symbol by remember { mutableStateOf("SOLUSDT") }
    var direction by remember { mutableStateOf("SHORT") }
    var confirmEntry by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (status.forceTestnetMode) AppColors.surface2 else AppColors.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Принудительная торговля", style = MaterialTheme.typography.titleMedium)
                ModeChip(
                    if (status.forceTestnetMode) "ARMED • DEMO" else "DEMO ONLY",
                    if (status.forceTestnetMode) AppColors.amber else AppColors.primary
                )
            }
            Text(
                "Ручной выбор LONG или SHORT для поиска ошибок на Futures Demo. Автоматические входы по сигналам отключаются, рыночный сканер и управление открытыми позициями продолжаются.",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.textMuted
            )
            Text("Инструмент", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                approvedSymbols.forEach { approved ->
                    FilterChip(
                        selected = symbol == approved,
                        onClick = { symbol = approved },
                        label = { Text(approved.removeSuffix("USDT")) }
                    )
                }
            }
            Text("Направление", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = direction == "LONG",
                    onClick = { direction = "LONG" },
                    label = { Text("LONG • покупка") }
                )
                FilterChip(
                    selected = direction == "SHORT",
                    onClick = { direction = "SHORT" },
                    label = { Text("SHORT • продажа") }
                )
            }
            Text(
                "Вход остаётся условным STOP по закрытым H1-уровням. Перед отправкой Runtime повторно проверяет направление, структурный стоп, доступный баланс, стартовый риск 0,20% на сделку (жёсткий потолок 0,25%) / 0,60% суммарно, дневной лимит 3%, фильтры биржи и состояние защиты. Это не рыночный вход и не обход аварийных блокировок.",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.textMuted
            )
            if (!status.forceTestnetMode) {
                Button(
                    onClick = { onSetMode(true) },
                    enabled = status.running && status.binanceConfigured &&
                        !status.reconcileRequired && !status.executionKillLatched,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("ВКЛЮЧИТЬ ПРИНУДИТЕЛЬНУЮ ТОРГОВЛЮ")
                }
                Text(
                    when {
                        !status.running -> "Сначала запусти Runtime."
                        status.reconcileRequired || status.executionKillLatched ->
                            "Сначала восстанови состояние биржи; режим не обходит RECONCILE/KILL."
                        !status.p0GatePassed ->
                            "Входы заблокированы P0: " + status.p0GateReason +
                                ". Исправь первопричину; ручной режим не обходит защитные блокировки."
                        else ->
                            "Ручной выбор стороны использует H1-структуру, даже если AI-гипотеза указывает противоположное направление."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (!status.p0GatePassed || status.reconcileRequired || status.executionKillLatched)
                        AppColors.red else AppColors.amber
                )
            } else {
                Button(
                    onClick = { confirmEntry = true },
                    enabled = status.running && !status.paused &&
                        !status.reconcileRequired && !status.executionKillLatched &&
                        status.p0GatePassed,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("РАЗМЕСТИТЬ УСЛОВНЫЙ $direction • $symbol")
                }
                OutlinedButton(
                    onClick = { onSetMode(false) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("ВЫКЛЮЧИТЬ РЕЖИМ И ОСТАНОВИТЬ ВХОДЫ") }
            }
        }
    }

    if (confirmEntry) {
        AlertDialog(
            onDismissRequest = { confirmEntry = false },
            title = { Text("Подтвердить $direction $symbol?") },
            text = {
                Text(
                    "Будет рассчитан новый условный H1 STOP по структуре последних закрытых свечей. LONG и SHORT используют разные стороны пробоя и противоположно расположенный защитный стоп. Если уровень уже пройден, данные устарели или любая обязательная проверка не пройдёт, ордер не отправится. Принудительный режим влияет только на выбор направления и работает только на Futures Demo."
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmEntry = false
                    onSubmitEntry(symbol, direction)
                }) { Text("ПОДТВЕРДИТЬ DEMO-ОРДЕР") }
            },
            dismissButton = {
                TextButton(onClick = { confirmEntry = false }) { Text("ОТМЕНА") }
            }
        )
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
            StatusDot(
                "RUNTIME",
                if (status.binanceConfigured || status.running) AppColors.green else AppColors.amber
            )
            StatusDot(
                "BINANCE",
                if (status.binanceConfigured) AppColors.green else AppColors.amber
            )
            StatusDot(
                "WSS",
                if (status.marketWsConnected && status.userWsConnected && !status.userStreamSyncRequired) AppColors.green else AppColors.amber
            )
            StatusDot(
                "EXEC",
                when {
                    status.reconcileRequired || status.executionKillLatched -> AppColors.red
                    status.running && status.executionEnabled -> AppColors.green
                    else -> AppColors.amber
                }
            )
            Spacer(Modifier.weight(1f))
            Text("RISK BUDGET • ${status.maxOpenPositions} POS", color = AppColors.textMuted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun PortfolioScreen(
    padding: PaddingValues,
    portfolio: PortfolioSummary,
    status: Status
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
                "Portfolio",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        }
        item {
            PortfolioCard(portfolio, status)
        }
    }
}

@Composable
private fun PortfolioCard(
    portfolio: PortfolioSummary,
    status: Status
) {
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
                Text("Портфель", style = MaterialTheme.typography.titleMedium)
                ModeChip(
                    if (portfolio.configured) "LIVE VIEW • TESTNET" else "ОЖИДАЕТ КЛЮЧИ",
                    if (portfolio.configured) AppColors.green else AppColors.amber
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MiniMetric(
                    "EQUITY",
                    fmt(portfolio.totalEquityUsdt, 2)
                )
                MiniMetric(
                    "FREE",
                    fmt(portfolio.freeEquityUsdt, 2)
                )
                MiniMetric(
                    "LOCKED",
                    fmt(portfolio.lockedEquityUsdt, 2)
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MiniMetric(
                    "REALIZED",
                    fmt(portfolio.realizedPnlUsdt, 2)
                )
                MiniMetric(
                    "OPEN PnL",
                    fmt(portfolio.unrealizedPnlUsdt, 2)
                )
                MiniMetric(
                    "RISK",
                    fmt(status.reservedRiskPct * 100.0, 2) + "%"
                )
            }

            if (portfolio.assets.isEmpty()) {
                Text(
                    "Нет ненулевых активов или баланс ещё не получен.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.textMuted
                )
            } else {
                portfolio.assets.forEach { asset ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            asset.asset,
                            fontWeight = FontWeight.SemiBold
                        )
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                fmt(asset.total, 6) + " " + asset.asset,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "free " + fmt(asset.free, 6) +
                                    " • locked " + fmt(asset.locked, 6),
                                color = AppColors.textMuted,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                if (asset.priceUsdt != null) {
                                    fmt(asset.valueUsdt, 2) + " USDT"
                                } else {
                                    "цена неизвестна"
                                },
                                color = AppColors.textMuted,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsScreen(
    padding: PaddingValues,
    status: Status,
    onRun: () -> Unit,
    onSelfHeal: () -> Unit,
    onExport: () -> Unit,
    message: String
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp)
    ) {
        item {
            Text("Diagnostics", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        item { DiagnosticRow("REST price", if (status.price != null) "PASS • " + fmt(status.price, 6) else "FAIL • no price") }
        item { DiagnosticRow("USDT balance", if (status.balance != null) "PASS • " + fmt(status.balance, 4) else "WAIT • no account data") }
        item { DiagnosticRow("History", if (status.historyReady) "PASS" else "BLOCK • " + status.scannerState) }
        item { DiagnosticRow("Market WS", if (status.marketWsConnected) "CONNECTED" else "DEGRADED • REST fallback") }
        item { DiagnosticRow("User WS", if (status.userWsConnected && !status.userStreamSyncRequired) "CONNECTED" else "DEGRADED • REST reconciliation") }
        item {
            DiagnosticRow(
                "Scanner",
                status.scannerState + " • " + status.scannerSymbols + "/" +
                    status.scannerConfiguredSymbols + " pairs • LONG " +
                    status.scannerLongCandidates + " / SHORT " +
                    status.scannerShortCandidates + " • " + status.scanDurationMs + " ms"
            )
        }
        item {
            DiagnosticRow(
                "Approved Futures universe",
                status.scannerUniverse.joinToString(", ").ifBlank { "not available" }
            )
        }
        item {
            DiagnosticRow(
                "Directions",
                "LONG ${if (status.allowLong) "ENABLED" else "BLOCKED"} • SHORT " +
                    "${if (status.allowShort) "ENABLED" else "BLOCKED"}"
            )
        }
        item {
            DiagnosticRow(
                "Futures mode",
                "${status.futuresLeverage}x • ${status.futuresMarginType} • ONE-WAY " +
                    if (status.futuresForceOneWay) "REQUIRED" else "INVALID"
            )
        }
        item { DiagnosticRow("Execution", if (status.p0GatePassed) "READY" else "BLOCKED • " + status.p0GateReason) }
        status.error?.let { item { DiagnosticRow("Last error", it) } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRun) { Text("RUN DIAGNOSTICS") }
                OutlinedButton(
                    onClick = onSelfHeal,
                    enabled = !status.executionKillLatched && !status.reconcileRequired
                ) { Text("SELF-HEAL") }
                OutlinedButton(onClick = onExport) { Text("EXPORT SNAPSHOT") }
            }
        }
        if (message.isNotBlank()) item {
            Text(message, color = AppColors.textMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppColors.surface)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(value, color = AppColors.textMuted)
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
    onRecoverDashboard: () -> Unit,
    onSetTestnetForceMode: (Boolean) -> Unit,
    onForcedEntry: (String, String) -> Unit
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
                    title = "USDT free",
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
            TestnetForceTradingCard(
                status = status,
                onSetMode = onSetTestnetForceMode,
                onSubmitEntry = onForcedEntry
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
                    when (status.scannerState) {
                        "RUNNING" -> "Сканирование рынка…"
                        "READY" -> status.scannerSymbols.toString() + "/" +
                            status.scannerConfiguredSymbols + " пар проверено • LONG " +
                            status.scannerLongCandidates + " / SHORT " +
                            status.scannerShortCandidates + " • " + formatMs(status.scanDurationMs)
                        "PARTIAL", "PARTIAL_BLOCKED" -> "Проверено " + status.scannerSymbols + "/" +
                            status.scannerConfiguredSymbols + "; новые входы заблокированы"
                        "WAITING_FOR_HISTORY" -> "Ожидание закрытой истории H1: " +
                            (status.scannerError ?: "нужно загрузить историю для 10 контрактов")
                        "BLOCKED_ACCOUNT" -> "Баланс/счёт Futures заблокировал сканирование: " +
                            (status.scannerError ?: "проверка аккаунта не пройдена")
                        "WAITING_FOR_MARKET_DATA", "WAITING_FOR_REST" ->
                            "Котировки Futures недоступны или устарели: " +
                                (status.scannerError ?: "ожидание REST-данных")
                        "BLOCKED_RECONCILE", "BLOCKED_FUTURES_RECONCILIATION" ->
                            "Нужна сверка позиции/ордеров Futures: " +
                                (status.scannerError ?: "торговля заблокирована")
                        "BLOCKED_KILL" -> "Аварийная блокировка включена: " +
                            (status.scannerError ?: "сначала сверить состояние биржи")
                        "BLOCKED_DAILY_LOSS" -> "Достигнут дневной лимит потерь: новые входы запрещены"
                        "PAUSED" -> "Runtime на паузе"
                        "STOPPED" -> "Runtime остановлен; нажми START для запуска"
                        "BLOCKED" -> "Сканирование заблокировано: " +
                            (status.scannerError ?: "проверка конфигурации не пройдена")
                        "ERROR", "FAILED" -> "Ошибка сканера: " +
                            (status.scannerError ?: "неизвестная ошибка")
                        else -> status.scannerError?.takeIf { it.isNotBlank() }
                            ?: "Состояние сканера: " + status.scannerState
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
                status.tradingMode + " • торговый PnL сегодня " + fmt(status.dailyPnlUsdt, 2) + " USDT",
                style = MaterialTheme.typography.labelSmall,
                color = if (status.tradingMode == "PAUSED") AppColors.red else AppColors.textMuted
            )
            val lossPct = status.dailyLossPct * 100.0
            Text(
                "Дневной лимит: " + fmt(lossPct, 2) + "% / " +
                    fmt(status.maxDailyLoss * 100.0, 0) + "%",
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    lossPct >= status.maxDailyLoss * 100.0 -> AppColors.red
                    lossPct >= status.maxDailyLoss * 75.0 -> AppColors.amber
                    else -> AppColors.textMuted
                }
            )
            Text(
                "Капитальные движения за UTC-день: " +
                    fmt(status.dailyCapitalFlowUsdt, 2) + " USDT (не считаются торговым PnL)",
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.textMuted
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
                    enabled = runtimeStartButtonEnabled(
                        binanceConfigured = status.binanceConfigured,
                        running = status.running,
                        killLatched = status.executionKillLatched
                    ),
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
    chartSymbol: String,
    chartInterval: String,
    chartCandles: List<Candle>,
    chartLivePrice: Double?,
    chartQuoteSource: String,
    chartQuoteUpdatedAtMs: Long,
    chartIndicatorSummary: String,
    chartLoading: Boolean,
    chartError: String?,
    onChartSymbolChange: (String) -> Unit,
    onChartIntervalChange: (String) -> Unit,
    onRefresh: () -> Unit,
    historicalTrade: Trade? = null,
    onLive: () -> Unit = {}
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
                Column(Modifier.weight(1f)) {
                    Text("График рынка и решение бота", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        when (status.scannerState) {
                            "RUNNING" -> "Сканирование выполняется • " +
                                status.scannerProgressSymbols + "/" + status.scannerProgressTotal
                            "ERROR", "FAILED" -> "Ошибка сканера"
                            "BLOCKED", "BLOCKED_ACCOUNT", "BLOCKED_RECONCILE",
                            "BLOCKED_KILL", "BLOCKED_DAILY_LOSS",
                            "BLOCKED_FUTURES_RECONCILIATION" -> "Сканирование заблокировано"
                            "PARTIAL", "PARTIAL_BLOCKED" ->
                                "Неполное сканирование • новые входы заблокированы"
                            "WAITING_FOR_HISTORY" -> "Ожидание истории закрытых H1-свечей"
                            "WAITING_FOR_MARKET_DATA", "WAITING_FOR_REST" ->
                                "Ожидание свежих котировок Futures"
                            "PAUSED" -> "Runtime на паузе"
                            "STOPPED" -> "Runtime остановлен"
                            "READY" ->
                                status.scannerSymbols.toString() + "/" +
                                    status.scannerConfiguredSymbols + " утверждённых пар • LONG " +
                                    status.scannerLongCandidates + " / SHORT " +
                                    status.scannerShortCandidates + " • кандидатов " +
                                    candidates.size
                            else -> "Состояние: " + status.scannerState
                        },
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

        if (historicalTrade != null) item {
            CockpitCard("Исторический график • ${historicalTrade.symbol}", AppColors.violet) {
                Text("Вход: ${cockpitTime(historicalTrade.openedAtMs)} • выход: ${cockpitTime(historicalTrade.closedAtMs)}", style = MaterialTheme.typography.bodySmall)
                Text(historicalTrade.reason.ifBlank { "Причина входа не записана" }, style = MaterialTheme.typography.bodySmall)
                Text("Выход: " + historicalTrade.exitReason.ifBlank { "Причина не записана" }, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onLive) { Text("Вернуться к прямому эфиру") }
            }
        }
        item {
            MarketChartPanel(
                selectedSymbol = chartSymbol,
                interval = chartInterval,
                candles = chartCandles,
                livePrice = chartLivePrice,
                quoteSource = chartQuoteSource,
                quoteUpdatedAtMs = chartQuoteUpdatedAtMs,
                indicatorSummary = chartIndicatorSummary,
                loading = chartLoading,
                error = chartError,
                candidate = candidates.firstOrNull { it.symbol.equals(chartSymbol, ignoreCase = true) },
                status = status,
                onSymbolChange = onChartSymbolChange,
                onIntervalChange = onChartIntervalChange,
                historicalTrade = historicalTrade
            )
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
private fun MarketChartPanel(
    selectedSymbol: String,
    interval: String,
    candles: List<Candle>,
    livePrice: Double?,
    quoteSource: String,
    quoteUpdatedAtMs: Long,
    indicatorSummary: String,
    loading: Boolean,
    error: String?,
    candidate: Candidate?,
    status: Status,
    onSymbolChange: (String) -> Unit,
    onIntervalChange: (String) -> Unit,
    historicalTrade: Trade? = null
) {
    val selectedPosition = if (historicalTrade != null) null else status.positions.firstOrNull {
        it.symbol.equals(selectedSymbol, ignoreCase = true)
    }
    val displayStatus = status.copy(
        symbol = selectedSymbol,
        // Do not mislabel the previous candle's close as a moving live price.
        price = livePrice,
        entry = null,
        tp = null,
        sl = null
    )
    val direction = when {
        candidate == null -> "НЕТ КАНДИДАТА"
        candidateDirection(candidate) == "SHORT" -> "SHORT"
        candidateDirection(candidate) == "LONG" -> "LONG"
        else -> "НЕ ОПРЕДЕЛЕНО"
    }

    var details by remember(selectedSymbol) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AppColors.surface)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(selectedSymbol, style = MaterialTheme.typography.titleLarge)
                    Text("USDⓈ-M Futures Demo • выбранная пара", color = AppColors.textMuted, style = MaterialTheme.typography.labelSmall)
                }
                Text((livePrice ?: candles.lastOrNull()?.close)?.let { fmt(it) } ?: if (loading) "Загрузка…" else "—",
                    color = AppColors.primary, fontWeight = FontWeight.Bold)
            }
            Text("Бот независимо сканирует все 10 контрактов. Здесь показан только выбранный рынок.",
                color = AppColors.textMuted, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                APPROVED_FUTURES_CHART_SYMBOLS.forEach { symbol ->
                    FilterChip(selected = selectedSymbol == symbol, onClick = { onSymbolChange(symbol) },
                        label = { Text(symbol.removeSuffix("USDT")) })
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                CHART_VIEW_INTERVALS.forEach { frame ->
                    FilterChip(selected = interval == frame, onClick = { onIntervalChange(frame) },
                        label = { Text(chartIntervalLabel(frame)) })
                }
            }
            Text("Решение: H1 • контекст: H4 / D1 • смена графика не меняет торговлю", color = AppColors.textMuted, style = MaterialTheme.typography.labelSmall)
            Text((if (historicalTrade != null) "Исторические свечи" else "Котировка: " + (livePrice?.let { fmt(it) } ?: "нет")) + " • источник: " + quoteSource +
                (if (quoteUpdatedAtMs > 0L) " • " + ((System.currentTimeMillis() - quoteUpdatedAtMs).coerceAtLeast(0L) / 1000L) + "с назад" else ""),
                color = if (livePrice != null && CockpitDiagnostics.fresh(quoteUpdatedAtMs, System.currentTimeMillis(), 60_000L)) AppColors.green else AppColors.amber,
                style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { details = !details }) { Text(if (details) "Скрыть данные индикаторов" else "Что видит бот • данные индикаторов") }
            if (details) Text(indicatorSummary, color = AppColors.textMuted, style = MaterialTheme.typography.bodySmall)
            if (candles.isEmpty()) {
                if (loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Получение свечей Futures…", color = AppColors.textMuted)
                    }
                } else {
                    EmptyState(icon = Icons.Filled.ShowChart, title = "Нет данных графика",
                        subtitle = error ?: "Закрытые свечи ещё не получены из локального Futures Runtime.")
                }
            } else {
                TradingChart(candles = candles, status = displayStatus, position = selectedPosition,
                    candidate = if (historicalTrade == null) candidate else null, interval = interval,
                    onIntervalChange = onIntervalChange, historicalTrade = historicalTrade)
            }
            if (!error.isNullOrBlank() && candles.isNotEmpty()) {
                Text("Обновление графика: " + error, color = AppColors.amber, style = MaterialTheme.typography.bodySmall)
            }
            if (historicalTrade == null) {
            Divider(color = AppColors.surface2)
            Text("ЧТО ДУМАЕТ БОТ • H4 → H1 → СИГНАЛ → РИСК", style = MaterialTheme.typography.titleMedium)
            Text("Показан последний результат сканера, а не команда немедленной покупки/продажи. Ордер требует отдельного H1-триггера, проверки риска и биржевой сверки.",
                color = AppColors.textMuted, style = MaterialTheme.typography.bodySmall)
            if (candidate == null) {
                Text("Нет результата сканера для $selectedSymbol. Направление LONG/SHORT не выводится только из графика.",
                    color = AppColors.amber, style = MaterialTheme.typography.bodyMedium)
            } else {
                InfoRow("Направление кандидата", direction)
                InfoRow("Состояние стратегии", when {
                    candidate.campaignReady -> "кандидат передан на проверку допуска"
                    candidate.signal -> "сигнал наблюдается; допуск не подтверждён"
                    else -> "наблюдение / вход не подтверждён"
                })
                InfoRow("Семейство Williams", candidate.entrySignalType.ifBlank { "не указано" })
                InfoRow("HTF-контекст", if (candidate.htfConfirmed) "подтверждён" else "не подтверждён")
                if (details) {
                InfoRow("Согласование направления", candidate.wiseManCount.toString() + "/3 (диагностика, не счётчик Three Wise Men)")
                InfoRow("Волновая фаза", "W" + candidate.wavePosition + " • " + candidate.wavePhase)
                InfoRow("Волновая уверенность / истощение", fmt(candidate.waveConfidence, 2) + " / " + fmt(candidate.waveExhaustionRisk, 1) + "%")
                InfoRow("Вероятности W3 / W5", fmt(candidate.wave3Probability, 2) + " / " + fmt(candidate.wave5Probability, 2))
                InfoRow("Режим рынка", candidate.regime + " • score " + fmt(candidate.regimeScore, 2))
                InfoRow("OBI стакана", fmt(candidate.obi, 3))
                InfoRow("Дисбаланс потока сделок", fmt(candidate.tradeFlowImbalance, 3))
                InfoRow("Теневая оценка", candidate.shadowDirection + " • " + fmt(candidate.shadowConfidence, 2))
                InfoRow("Количественная оценка", fmt(candidate.quantScore, 2))
                InfoRow("Оценка риска", if (candidate.riskPct > 0.0) fmt(candidate.riskPct, 2) + "%" else "—")
                InfoRow("Risk/reward", if (candidate.riskReward > 0.0) fmt(candidate.riskReward, 2) else "—")
                InfoRow("Спред / ATR", fmt(candidate.spreadPct * 100.0, 3) + "% / " + fmt(candidate.atrPct * 100.0, 3) + "%")
                }
                InfoRow("Триггер условного STOP", fmt(candidate.entryTriggerPrice))
                InfoRow("Структурный стоп", fmt(candidate.entryProtectivePrice))
                if (candidate.shadowReason.isNotBlank()) Text("Теневая оценка: " + candidate.shadowReason,
                    style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted)
                if (candidate.xaiTopFactor.isNotBlank()) Text("Главный фактор: " + candidate.xaiTopFactor,
                    style = MaterialTheme.typography.bodySmall, color = AppColors.violet)
                if (candidate.reason.isNotBlank()) Text("Почему сканер так решил: " + candidate.reason,
                    style = MaterialTheme.typography.bodySmall)
                if (candidate.wavePath.isNotBlank()) Text("Контекст волны: " + candidate.wavePath,
                    color = AppColors.textMuted, style = MaterialTheme.typography.bodySmall)
            }
            }
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
                MiniMetric("Align", candidate.wiseManCount.toString() + "/3")
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
                "Позиции",
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
                            if (status.positionsSnapshotOk && CockpitDiagnostics.fresh(status.positionsSnapshotAtMs, System.currentTimeMillis(), 15_000L)) "FLAT" else "NOT VERIFIED",
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Text(
                            if (status.positionsSnapshotOk && CockpitDiagnostics.fresh(status.positionsSnapshotAtMs, System.currentTimeMillis(), 15_000L)) "Открытых позиций нет." else "Нет актуального подтверждения позиций Binance.",
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
                                (p.direction.takeIf { it == "LONG" || it == "SHORT" }
                                    ?: if (p.qty < 0.0) "SHORT" else "LONG") + " " + p.symbol,
                                modifier = Modifier.weight(1f).clickable { onSelect(p.symbol) },
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
                                    Text("ЗАКРЫТЬ")
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
                            fmt(p.currentPrice)
                        )
                        InfoRow(
                            "Position value",
                            fmt(p.positionValueUsdt, 2) + " USDT"
                        )
                        InfoRow(
                            "Allocation",
                            fmt(p.allocationPct * 100.0, 2) + "%"
                        )
                        InfoRow(
                            "PnL",
                            fmt(p.pnlUsdt, 2) + " USDT (" +
                                fmt(p.pnlPct * 100.0, 2) + "%)"
                        )
                        InfoRow(
                            "SL",
                            fmt(p.stop)
                        )
                        InfoRow(
                            "TP",
                            fmt(p.take)
                        )
                        InfoRow(
                            "Защита",
                            if (status.positionsSnapshotOk && status.futuresIntents.any {
                                it.optString("symbol") == p.symbol && it.optString("direction") == p.direction &&
                                    CockpitDiagnostics.protectionProven(it, System.currentTimeMillis())
                            }) "Подтверждена Binance"
                            else "НЕ ПОДТВЕРЖДЕНА — требуется сверка"
                        )
                    }
                }
            }
        }

        if (selectedSymbol != null) {
            item { Text("График позиции • " + selectedSymbol, style = MaterialTheme.typography.titleMedium) }
            item {
                if (candles.isEmpty()) EmptyState(icon = Icons.Filled.ShowChart, title = "Нет графика", subtitle = "Выбери позицию и обнови данные.")
                else {
                    val selectedPosition = status.positions.firstOrNull { it.symbol == selectedSymbol }
                    TradingChart(
                        candles,
                        status.copy(
                            entry = selectedPosition?.entry,
                            tp = selectedPosition?.take,
                            sl = selectedPosition?.stop
                        ),
                        selectedPosition,
                        interval = interval,
                        onIntervalChange = onIntervalChange
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryScreen(
    padding: PaddingValues,
    trades: List<Trade>,
    logs: List<String>,
    onTradeChart: (Trade) -> Unit = {}
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
                    val complete = trades.filter { it.exit != null && it.pnlComplete }
                    val incomplete = closed - complete.size
                    val wins = complete.count { it.outcome == "WIN" }
                    val losses = complete.count { it.outcome == "LOSS" }
                    val pnl = complete.mapNotNull { it.pnl }.sum()
                    InfoRow("Всего", trades.size.toString())
                    InfoRow("Закрытых", closed.toString())
                    InfoRow("Полная статистика", complete.size.toString() + " • неполная " + incomplete)
                    InfoRow("Win rate", if (complete.isNotEmpty()) "%.1f%%".format(Locale.US, wins * 100.0 / complete.size) else "—")
                    InfoRow("Побед / убытков", wins.toString() + " / " + losses)
                    InfoRow("PnL (только полные данные)", fmt(pnl, 2) + " USDT")
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
                        Text(it.symbol + " • " + it.direction + " • " + it.outcome, style = MaterialTheme.typography.titleMedium)
                        Text(
                            fmt(it.pnl, 2) + " USDT",
                            color = if ((it.pnl ?: 0.0) >= 0.0) AppColors.green else AppColors.red,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text("Entry " + fmt(it.entry) + " → Exit " + fmt(it.exit), color = AppColors.textMuted)
                    if (it.initialRiskUsdt != null) {
                        Text("Initial risk " + fmt(it.initialRiskUsdt, 2) + " USDT")
                    }
                    if (it.feeUsdt != null) {
                        Text(
                            "Commission " + fmt(it.feeUsdt, 4) + " USDT" +
                                if (it.feeKnown) "" else " • fee conversion incomplete",
                            color = if (it.feeKnown) AppColors.textMuted else AppColors.amber,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (it.entrySlippagePct != null) {
                        Text(
                            "Entry slippage (adverse +) " +
                                fmt(it.entrySlippagePct * 100.0, 4) + "%",
                            color = AppColors.textMuted,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (!it.pnlComplete) {
                        Text(
                            "Net PnL excluded from aggregate: exchange commission currency could not be converted exactly",
                            color = AppColors.amber,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
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
                    if (it.exitReason.isNotBlank()) Text("Почему вышли: " + it.exitReason, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onTradeChart(it) }) { Text("Открыть график сделки") }
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
    apiKey: String,
    apiSecret: String,
    onApiKey: (String) -> Unit,
    onApiSecret: (String) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    onSaveLeverage: (JSONObject) -> Unit,
    backupPassword: String,
    onBackupPassword: (String) -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    backupMessage: String,
    onRunDiagnostics: () -> Unit,
    onExportDiagnostics: () -> Unit,
    diagnosticsMessage: String,
    message: String
) {
    val context = LocalContext.current
    val batteryPowerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val batteryExemptionGranted =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            batteryPowerManager.isIgnoringBatteryOptimizations(context.packageName)
    val lowPowerStandbyEnabled =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            batteryPowerManager.isLowPowerStandbyEnabled
    val lowPowerStandbyExempt =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            batteryPowerManager.isExemptFromLowPowerStandby()

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

        item { FuturesLeverageSettingsCard(status, onSaveLeverage) }

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
                        "Автономный режим: торговая логика работает на телефоне; API-ключи передаются только локальному движку и Binance Futures Testnet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.textMuted
                    )

                    InfoRow("Execution authority", "Автономный телефон • Binance напрямую")
                    InfoRow("Scanner universe", "USDⓈ-M Futures • 10 USDT contracts")
                    InfoRow("MTF", "D1/H4 context • H1 decisions • M15 monitoring")
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
                    Text("Фоновая работа Android", style = MaterialTheme.typography.titleMedium)
                    InfoRow(
                        "Doze exemption",
                        if (batteryExemptionGranted) "Разрешено" else "Не подтверждено системой"
                    )
                    InfoRow(
                        "Low Power Standby",
                        when {
                            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> "Недоступен"
                            !lowPowerStandbyEnabled -> "Выключен"
                            lowPowerStandbyExempt -> "Для приложения ограничения сняты"
                            else -> "Включён: ограничения сети могут применяться"
                        }
                    )
                    Text(
                        "Для автономной работы откройте системные настройки приложения и проверьте режим батареи/фоновой активности. Поведение также зависит от производителя телефона; этот статус не заменяет физический тест.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.textMuted
                    )
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null)
                                    )
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("ОТКРЫТЬ НАСТРОЙКИ ПРИЛОЖЕНИЯ")
                    }
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
                    InfoRow("Loss guard", "2 подряд → пауза")
                    InfoRow("Scanner cadence", "10 sec")
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
                        "Зашифрованный backup не содержит Binance API key/secret, открытые позиции или ордера. После восстановления локальный Futures Runtime сверяет фактическое состояние напрямую с Binance.",
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
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = AppColors.surface)
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = AppColors.violet
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Самодиагностика",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Text(
                        "После ввода ключей Williams автоматически проверяет Binance REST/WSS, MTF/Wave, сканер, FSM, БД и торговые фильтры. Торговые заявки диагностика не отправляет.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.textMuted
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onRunDiagnostics,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("ПРОВЕРИТЬ")
                        }
                        OutlinedButton(
                            onClick = onExportDiagnostics,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("ЭКСПОРТ")
                        }
                    }
                    if (diagnosticsMessage.isNotBlank()) {
                        Text(
                            diagnosticsMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.green
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
    candidate: Candidate? = null,
    interval: String = "1h",
    onIntervalChange: (String) -> Unit = {},
    historicalTrade: Trade? = null
) {
    if (candles.isEmpty()) { Text("Нет закрытых свечей"); return }
    var showJaw by remember { mutableStateOf(true) }
    var showTeeth by remember { mutableStateOf(true) }
    var showLips by remember { mutableStateOf(true) }
    var showFractals by remember { mutableStateOf(true) }
    var showSignals by remember { mutableStateOf(true) }
    var showLevels by remember { mutableStateOf(true) }
    var showAo by remember { mutableStateOf(true) }
    var showAc by remember { mutableStateOf(true) }
    var showMfi by remember { mutableStateOf(false) }
    var selectedBar by remember { mutableIntStateOf(-1) }
    var scale by remember(candles.size) { mutableFloatStateOf(max(1f, candles.size / 100f)) }
    var pan by remember(candles.size) { mutableFloatStateOf(0f) }

    val visibleCount = min(candles.size, max(1, (candles.size / scale).toInt()))

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
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(Triple("Jaw", showJaw) { showJaw = !showJaw }, Triple("Teeth", showTeeth) { showTeeth = !showTeeth },
                Triple("Lips", showLips) { showLips = !showLips }, Triple("Фракталы", showFractals) { showFractals = !showFractals },
                Triple("Мудрецы", showSignals) { showSignals = !showSignals }, Triple("Уровни", showLevels) { showLevels = !showLevels },
                Triple("AO", showAo) { showAo = !showAo }, Triple("AC", showAc) { showAc = !showAc },
                Triple("MFI", showMfi) { showMfi = !showMfi }).forEach { (label, enabled, change) ->
                FilterChip(enabled, change, label = { Text(label) })
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("Alligator: Jaw 13/8 • Teeth 8/5 • Lips 5/3  |  Fractals подтверждаются после двух правых закрытых баров",
                color = AppColors.textMuted, style = MaterialTheme.typography.labelSmall)
            Text(
                "График • " + chartIntervalLabel(interval) +
                    " • H1 остаётся единственным таймфреймом торгового решения",
                color = AppColors.textMuted,
                style = MaterialTheme.typography.labelSmall
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(340.dp)
                .padding(8.dp)
                .pointerInput(visible.size) {
                    detectTapGestures { tap -> selectedBar = (tap.x / size.width * visible.size).toInt().coerceIn(0, visible.lastIndex) }
                }
                .pointerInput(visible.size) {
                    detectTransformGestures { _, drag, zoom, _ ->
                        scale =
                            (scale * zoom).coerceIn(1f, max(5f, candles.size / 20f))
                        pan += drag.x
                    }
                }
        ) {
            val levels = listOfNotNull(
                status.tp, status.entry, status.sl, status.price,
                candidate?.entryTriggerPrice, candidate?.entryProtectivePrice,
                position?.entry, position?.stop, historicalTrade?.entry, historicalTrade?.exit, historicalTrade?.stopPrice
            ) + visible.flatMap { listOfNotNull(if (showJaw) it.jaw else null, if (showTeeth) it.teeth else null, if (showLips) it.lips else null) }
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
                if (showFractals && candle.fractalUp) {
                    drawCircle(AppColors.red, radius = 4f, center = Offset(center, y(candle.high) - 7f))
                }
                if (showFractals && candle.fractalDown) {
                    drawCircle(AppColors.green, radius = 4f, center = Offset(center, y(candle.low) + 7f))
                }
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

            if (showJaw) indicatorLine({ it.jaw }, AppColors.violet)
            if (showTeeth) indicatorLine({ it.teeth }, AppColors.amber)
            if (showLips) indicatorLine({ it.lips }, AppColors.primary)
            if (showSignals) visible.forEachIndexed { index, c ->
                c.signalTypes.forEach { type ->
                    val short = type.startsWith("SHORT")
                    val family = when { type.contains("REVERSAL") -> "WM1"; type.contains("SUPER_AO") -> "WM2"; type.contains("FRACTAL") -> "WM3"; else -> type }
                    drawContext.canvas.nativeCanvas.drawText((if (short) "▼" else "▲") + family,
                        x(index), y(if (short) c.high else c.low), android.graphics.Paint().apply {
                            color = (if (short) AppColors.red else AppColors.green).toArgb(); textSize = 10.sp.toPx(); isAntiAlias = true
                        })
                }
            }
            fun tradeMarker(at: Long, price: Double?, label: String, color: Color) {
                if (at <= 0L || price == null || visible.isEmpty()) return
                val frameMs = when (interval) { "1h" -> 3_600_000L; "4h" -> 14_400_000L; "1d" -> 86_400_000L; else -> 0L }
                val index = visible.indexOfLast { it.time <= at }
                if (index < 0 || (index == visible.lastIndex && frameMs > 0 && at >= visible.last().time + frameMs)) return
                drawLine(color.copy(alpha = 0.6f), Offset(x(index), 0f), Offset(x(index), size.height), 1f)
                drawCircle(color, 5f, Offset(x(index), y(price)))
                drawContext.canvas.nativeCanvas.drawText(label, x(index), max(20f, y(price) - 8f), android.graphics.Paint().apply {
                    this.color = color.toArgb(); textSize = 11.sp.toPx(); isAntiAlias = true
                })
            }
            historicalTrade?.let { trade ->
                tradeMarker(trade.openedAtMs, trade.entry, "ВХОД ${trade.direction}", AppColors.primary)
                tradeMarker(trade.closedAtMs, trade.exit, "ВЫХОД", AppColors.violet)
            }
            if (selectedBar in visible.indices) drawLine(AppColors.textMuted, Offset(x(selectedBar), 0f), Offset(x(selectedBar), size.height), 1f)

            fun level(price: Double?, color: Color, label: String) {
                if (price == null || !price.isFinite() || !showLevels) return
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
                        textSize = 11.sp.toPx()
                        isAntiAlias = true
                    }
                )
            }

            level(status.price, AppColors.primary, "LIVE")
            level(position?.entry ?: status.entry, AppColors.primary, "ENTRY")
            level(position?.take ?: status.tp, AppColors.green, "TP")
            level(position?.stop ?: status.sl, AppColors.red, "SL")
            level(candidate?.entryTriggerPrice, AppColors.amber, "H1 TRIGGER")
            level(candidate?.entryProtectivePrice, AppColors.red.copy(alpha = 0.9f), "H1 STRUCTURAL STOP")
        }
        Text(cockpitTime(visible.first().time) + "  —  " + cockpitTime(visible.last().time), modifier = Modifier.padding(horizontal = 8.dp), color = AppColors.textMuted, style = MaterialTheme.typography.labelSmall)
        if (selectedBar in visible.indices) {
            val c = visible[selectedBar]
            Text("${cockpitTime(c.time)} • O ${fmt(c.open)} • H ${fmt(c.high)} • L ${fmt(c.low)} • C ${fmt(c.close)}\nAO ${fmt(c.ao, 5)} • AC ${fmt(c.ac, 5)} • ${c.signalTypes.joinToString()}", modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
        }
        if (showAo) {
        Text("Awesome Oscillator (AO) • зелёный: столбец растёт, красный: столбец снижается",
            color = AppColors.textMuted, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp))
        Canvas(modifier = Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 8.dp, vertical = 4.dp)) {
            val finite = visible.mapNotNull { it.ao?.takeIf(Double::isFinite) }
            val maxAbs = finite.maxOfOrNull { kotlin.math.abs(it) } ?: 0.0
            val zeroY = size.height / 2f
            drawLine(AppColors.textMuted.copy(alpha = 0.65f), Offset(0f, zeroY), Offset(size.width, zeroY), 1f)
            if (maxAbs > 0.0 && visible.isNotEmpty()) {
                val step = size.width / visible.size.toFloat()
                visible.forEachIndexed { index, candle ->
                    val value = candle.ao?.takeIf(Double::isFinite) ?: return@forEachIndexed
                    val height = (kotlin.math.abs(value) / maxAbs * (size.height * 0.42f)).toFloat()
                    val left = index * step + step * 0.15f
                    val previous = if (index > 0) visible[index - 1].ao else null
                    val rising = previous?.let { value > it } ?: false
                    drawRect(color = if (rising) AppColors.green else AppColors.red,
                        topLeft = Offset(left, if (value >= 0.0) zeroY - height else zeroY),
                        size = Size(max(1f, step * 0.7f), max(1f, height)))
                }
            }
        }
        }
        if (showAc) {
        Text("Accelerator Oscillator (AC = AO − SMA5(AO)) • зелёный: растёт, красный: снижается; не самостоятельный вход",
            color = AppColors.textMuted, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp))
        Canvas(modifier = Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 8.dp, vertical = 4.dp)) {
            val finite = visible.mapNotNull { it.ac?.takeIf(Double::isFinite) }
            val maxAbs = finite.maxOfOrNull { kotlin.math.abs(it) } ?: 0.0
            val zeroY = size.height / 2f
            drawLine(AppColors.textMuted.copy(alpha = 0.65f), Offset(0f, zeroY), Offset(size.width, zeroY), 1f)
            if (maxAbs > 0.0 && visible.isNotEmpty()) {
                val step = size.width / visible.size.toFloat()
                visible.forEachIndexed { index, candle ->
                    val value = candle.ac?.takeIf(Double::isFinite) ?: return@forEachIndexed
                    val height = (kotlin.math.abs(value) / maxAbs * (size.height * 0.42f)).toFloat()
                    val left = index * step + step * 0.15f
                    val previous = if (index > 0) visible[index - 1].ac else null
                    val rising = previous?.let { value > it } ?: false
                    drawRect(color = if (rising) AppColors.green else AppColors.red,
                        topLeft = Offset(left, if (value >= 0.0) zeroY - height else zeroY),
                        size = Size(max(1f, step * 0.7f), max(1f, height)))
                }
            }
        }
        }
        if (showMfi) {
        Text("Williams MFI proxy = (High − Low) / Volume • это диагностический прокси потока, не классический MFI 0–100",
            color = AppColors.textMuted, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp))
        Canvas(modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp, vertical = 4.dp)) {
            val finite = visible.mapNotNull { it.mfiProxy?.takeIf { v -> v.isFinite() && v >= 0.0 } }
            val maxValue = finite.maxOrNull() ?: 0.0
            if (maxValue > 0.0 && visible.isNotEmpty()) {
                val step = size.width / visible.size.toFloat()
                var previous: Double? = null
                visible.forEachIndexed { index, candle ->
                    val value = candle.mfiProxy?.takeIf { it.isFinite() && it >= 0.0 } ?: return@forEachIndexed
                    val height = (value / maxValue * (size.height * 0.8f)).toFloat()
                    val left = index * step + step * 0.15f
                    val rising = previous?.let { value > it } ?: false
                    drawRect(color = if (rising) AppColors.green else AppColors.amber,
                        topLeft = Offset(left, size.height - height),
                        size = Size(max(1f, step * 0.7f), max(1f, height)))
                    previous = value
                }
            }
        }
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

private fun parsePortfolio(json: JSONObject): PortfolioSummary {
    val assets = mutableListOf<PortfolioAsset>()
    val array = json.optJSONArray("assets") ?: JSONArray()
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        assets += PortfolioAsset(
            asset = item.optString("asset"),
            free = item.optDouble("free", 0.0),
            locked = item.optDouble("locked", 0.0),
            total = item.optDouble("total", 0.0),
            priceUsdt = item.optDouble("price_usdt")
                .takeUnless { it.isNaN() || it == 0.0 },
            valueUsdt = item.optDouble("value_usdt", 0.0),
            allocationPct = item.optDouble("allocation_pct", 0.0)
        )
    }
    val positions = mutableListOf<PositionView>()
    val positionArray = json.optJSONArray("positions") ?: JSONArray()
    for (i in 0 until positionArray.length()) {
        val item = positionArray.optJSONObject(i) ?: continue
        positions += PositionView(
            symbol = item.optString("symbol"),
            qty = item.optDouble("qty", 0.0),
            entry = item.optDouble("avg_entry_price", 0.0),
            stop = item.optDouble("stop_loss")
                .takeUnless { it.isNaN() || it == 0.0 },
            take = item.optDouble("take_profit")
                .takeUnless { it.isNaN() || it == 0.0 },
            riskPct = item.optDouble("risk_pct", 0.0),
            currentPrice = item.optDouble("current_price")
                .takeUnless { it.isNaN() || it == 0.0 },
            positionValueUsdt = item.optDouble("position_value_usdt", 0.0),
            allocationPct = item.optDouble("allocation_pct", 0.0),
            pnlUsdt = item.optDouble("unrealized_pnl_usdt", 0.0),
            pnlPct = item.optDouble("unrealized_pnl_pct", 0.0),
            ocoListId = item.optString("oco_list_id"),
            ocoListClientId = item.optString("oco_list_client_id")
        )
    }

    return PortfolioSummary(
        configured = json.optBoolean("configured", false),
        totalEquityUsdt = json.optDouble("total_equity_usdt")
            .takeUnless { it.isNaN() || json.isNull("total_equity_usdt") },
        freeEquityUsdt = json.optDouble("free_equity_usdt")
            .takeUnless { it.isNaN() || json.isNull("free_equity_usdt") },
        lockedEquityUsdt = json.optDouble("locked_equity_usdt")
            .takeUnless { it.isNaN() || json.isNull("locked_equity_usdt") },
        realizedPnlUsdt = json.optDouble("realized_pnl_usdt", 0.0),
        unrealizedPnlUsdt = json.optDouble("unrealized_pnl_usdt", 0.0),
        assets = assets,
        positions = positions
    )
}

internal fun parseStatus(json: JSONObject): Status {
    val legacy = json.optJSONObject("position")
    val array = json.optJSONArray("positions")
    val parsed =
        mutableListOf<PositionView>()

    if (array != null) {
        for (i in 0 until array.length()) {
            val item =
                array.optJSONObject(i) ?: continue
            val symbol = item.optString("symbol", "").trim()
            if (symbol.isBlank()) continue

            val quantity = item.optDouble("qty", 0.0)
            val entryPrice = item.optDouble("avg_entry_price", item.optDouble("entry", 0.0))
            val currentPrice = item.optDouble(
                "current_price",
                item.optDouble("mark_price", Double.NaN)
            ).takeIf { it.isFinite() && it > 0.0 }
            val unrealizedPnl = item.optDouble("unrealized_pnl_usdt", 0.0)
            val accountEquity = json.optDouble("futures_equity", Double.NaN)
                .takeIf { it.isFinite() && it > 0.0 }
            val positionValue = item.optDouble(
                "position_value_usdt",
                (currentPrice ?: entryPrice) * kotlin.math.abs(quantity)
            ).takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
            val allocation = item.optDouble(
                "allocation_pct",
                if (accountEquity != null) positionValue / accountEquity else 0.0
            ).takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
            val pnlFraction = item.optDouble(
                "unrealized_pnl_pct",
                if (entryPrice > 0.0 && quantity != 0.0) {
                    unrealizedPnl / (entryPrice * kotlin.math.abs(quantity))
                } else {
                    0.0
                }
            ).takeIf { it.isFinite() } ?: 0.0

            parsed += PositionView(
                symbol = symbol,
                qty = quantity,
                direction = item.optString("direction", "").uppercase(Locale.US),
                entry = entryPrice,
                stop = item.optDouble("stop_loss", item.optDouble("stop"))
                    .takeUnless { it.isNaN() || it == 0.0 },
                take = item.optDouble("take_profit", item.optDouble("take"))
                    .takeUnless { it.isNaN() || it == 0.0 },
                riskPct = item.optDouble("risk_pct", 0.0),
                currentPrice = currentPrice,
                positionValueUsdt = positionValue,
                allocationPct = allocation,
                pnlUsdt = unrealizedPnl,
                pnlPct = pnlFraction,
                ocoListId = item.optString("oco_list_id"),
                ocoListClientId = item.optString("oco_list_client_id")
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

    val scannerUniverse = mutableListOf<String>()
    json.optJSONArray("scanner_universe")?.let { symbols ->
        for (i in 0 until symbols.length()) {
            val symbol = symbols.optString(i, "").trim()
            if (symbol.isNotBlank()) scannerUniverse += symbol
        }
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
                .takeUnless { it.isBlank() || it == "null" },
        // Accept the local runtime's canonical credential flag and older status aliases.
        binanceConfigured =
            json.optBoolean(
                "binance_configured",
                json.optBoolean("auth_configured", false)
            ),
        riskPerTrade =
            json.optDouble(
                "risk_per_trade_pct",
                0.0025
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
        dailyLossPct = json.optDouble("daily_loss_pct", 0.0).coerceAtLeast(0.0),
        dailyCapitalFlowUsdt = json.optDouble("daily_capital_flow_usdt", 0.0),
        tradingMode = json.optString("trading_mode", "ACTIVE"),
        openPositions =
            json.optInt(
                "open_positions",
                parsed.size
            ),
        maxOpenPositions =
            json.optInt(
                "max_open_positions",
                APPROVED_FUTURES_CHART_SYMBOLS.size
            ),
        reservedRiskPct =
            json.optDouble(
                "reserved_risk_pct",
                0.0
            ),
        maxTotalRiskPct =
            json.optDouble(
                "max_total_risk_pct",
                0.006
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
                .takeUnless { it.isBlank() || it == "null" },
        scannerSymbols =
            json.optInt(
                "scanner_symbols",
                0
            ),
        scannerConfiguredSymbols =
            json.optInt("scanner_configured_symbols", 10),
        scannerProgressSymbols = json.optInt("scanner_progress_symbols"),
        scannerProgressTotal = json.optInt("scanner_progress_total", 10),
        scannerUniverse = scannerUniverse.toList(),
        scannerLongCandidates =
            json.optInt("scanner_long_candidates", 0),
        scannerShortCandidates =
            json.optInt("scanner_short_candidates", 0),
        allowLong =
            json.optBoolean("allow_long", false),
        allowShort =
            json.optBoolean("allow_short", false),
        futuresLeverage =
            json.optInt("futures_leverage", 0),
        futuresLeverageSettingsJson =
            json.optJSONObject("futures_leverage_settings")?.toString().orEmpty(),
        futuresMarginType =
            json.optString("futures_margin_type", "UNKNOWN"),
        futuresForceOneWay =
            json.optBoolean("futures_force_one_way", false),
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
            executionContract?.optBoolean(
                "reconciliation_required",
                json.optBoolean("reconcile_required", false)
            ) ?: json.optBoolean("reconcile_required", false),
        executionKillLatched =
            executionContract?.optBoolean(
                "kill_switch_latched",
                json.optBoolean("kill_switch_latched", false)
            ) ?: json.optBoolean("kill_switch_latched", false),
        p0GatePassed = json.optBoolean(
            "p0_gate_passed",
            executionContract?.optBoolean("execution_enabled", false) ?: false
        ),
        p0GateReason = json.optString(
            "p0_gate_reason",
            if (executionContract?.optBoolean("reconciliation_required", true) == true) {
                "RECONCILE_REQUIRED"
            } else {
                "NOT_READY"
            }
        ),
        maxOpenPositionsLocked = json.optBoolean(
            "max_open_positions_locked",
            false
        ),
        unresolvedSymbols = json.optJSONArray("unresolved_symbols")?.let { array ->
            List(array.length()) { i -> array.optString(i) }
                .filter { it.isNotBlank() }
        } ?: emptyList(),
        pendingEntrySymbols = json.optJSONArray("pending_entry_symbols")?.let { array ->
            List(array.length()) { i -> array.optString(i) }
                .filter { it.isNotBlank() }
        } ?: emptyList(),
        forceTestnetMode = json.optBoolean("force_testnet_mode", false),
        snapshotAtMs = json.optLong("snapshot_at_ms"),
        positionsSnapshotOk = json.optBoolean("positions_snapshot_ok"),
        positionsSnapshotAtMs = json.optLong("positions_snapshot_at_ms"),
        accountSnapshotAtMs = json.optLong("account_snapshot_at_ms"),
        futuresIntents = CockpitDiagnostics.rows(json.optJSONObject("futures_runtime")?.optJSONArray("pending_intent_details")),
        cockpitReadiness = json.optJSONObject("cockpit_readiness") ?: JSONObject()
    )
}

private fun chartIndicatorSummary(snapshot: JSONObject?): String {
    if (snapshot == null || !snapshot.optBoolean("ready", false)) {
        return "Индикаторы: недостаточно закрытых свечей для корректного расчёта"
    }
    fun value(key: String): Double? = snapshot.optDouble(key).takeIf { it.isFinite() && !snapshot.isNull(key) }
    val jaw = value("jaw")
    val teeth = value("teeth")
    val lips = value("lips")
    val alligator = when {
        lips != null && teeth != null && jaw != null && lips > teeth && teeth > jaw -> "бычий порядок"
        lips != null && teeth != null && jaw != null && lips < teeth && teeth < jaw -> "медвежий порядок"
        else -> "переплетён / не направлен"
    }
    val zone = when (snapshot.optString("zone")) { "GREEN" -> "зелёная"; "RED" -> "красная"; else -> "нейтральная" }
    val divergence = when {
        snapshot.optBoolean("ao_bullish_divergence") && snapshot.optBoolean("ao_bearish_divergence") -> "оба экстремума (проверить вручную)"
        snapshot.optBoolean("ao_bullish_divergence") -> "бычья"
        snapshot.optBoolean("ao_bearish_divergence") -> "медвежья"
        else -> "нет подтверждённой"
    }
    val upFractal = !snapshot.isNull("fractal_up_index")
    val downFractal = !snapshot.isNull("fractal_down_index")
    val mfi = value("mfi_proxy")?.let { fmt(it, 6) } ?: "—"
    return "ЧТО ВИДИТ ДВИЖОК: Alligator $alligator • AO " + (value("ao")?.let { fmt(it, 5) } ?: "—") +
        " • AC " + (value("ac")?.let { fmt(it, 5) } ?: "—") + " • AO zero-cross-up=" + snapshot.optBoolean("ao_cross_up") + " • зона $zone • " +
        "MFI proxy (H−L)/volume=$mfi, рост=" + snapshot.optBoolean("mfi_up") + " • AO divergence=$divergence • " +
        "подтверждённые фракталы: UP=$upFractal / DOWN=$downFractal • wave=" + snapshot.optString("wave").ifBlank { "недостаточно данных" }
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
            fractalDown = x.optBoolean("fractal_down"),
            ao = x.optDouble("ao").takeUnless { it.isNaN() || x.isNull("ao") },
            ac = x.optDouble("ac").takeUnless { it.isNaN() || x.isNull("ac") },
            mfiProxy = x.optDouble("mfi_proxy").takeUnless { it.isNaN() || x.isNull("mfi_proxy") },
            signalTypes = x.optJSONArray("signal_types")?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList()
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
            xaiTopFactor = topXaiFactor(x.optJSONObject("xai_factors")),
            campaignReady = x.optBoolean("campaign_ready", false),
            entrySignalType = x.optString("entry_signal_type", "").takeUnless { it == "null" } ?: "",
            entryTriggerPrice = x.optDouble("entry_trigger_price", Double.NaN).takeIf { it.isFinite() && it > 0.0 },
            entryProtectivePrice = x.optDouble("entry_protective_price", Double.NaN).takeIf { it.isFinite() && it > 0.0 }
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
            maeR = x.optDouble("mae_r", 0.0),
            direction = x.optString("direction").ifBlank {
                if (x.optString("side").equals("SELL", true)) "SHORT" else "LONG"
            },
            feeUsdt = x.optDouble("fee_usdt", Double.NaN).takeUnless { it.isNaN() },
            feeKnown = x.optBoolean("fee_known", true),
            initialRiskUsdt = x.optDouble("initial_risk_usdt", Double.NaN).takeUnless { it.isNaN() },
            entrySlippagePct = x.optDouble("entry_slippage_pct", Double.NaN).takeUnless { it.isNaN() },
            pnlComplete = x.optBoolean("pnl_complete", true),
            fillCount = x.optInt("fill_count", 0),
            openedAtMs = x.optLong("opened_at"),
            closedAtMs = x.optLong("closed_at"),
            stopPrice = x.optDouble("stop_price").takeIf { it.isFinite() && it > 0.0 },
            exitReason = x.optString("exit_reason")
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

private fun candidateDirection(candidate: Candidate): String? =
    when {
        candidate.entrySignalType.startsWith("SHORT_", ignoreCase = true) -> "SHORT"
        candidate.entrySignalType in setOf("REVERSAL", "SUPER_AO", "FRACTAL") -> "LONG"
        else -> null
    }

private fun candidateState(candidate: Candidate): String =
    when {
        candidate.signal -> when (candidateDirection(candidate)) {
            "LONG" -> "H1 SETUP • LONG"
            "SHORT" -> "H1 SETUP • SHORT"
            else -> "H1 SETUP • DIRECTION UNKNOWN"
        }
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
        path.endsWith("/kill") -> "Аварийное закрытие запрошено; результат требует подтверждения Binance"
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

private fun chartIntervalLabel(frame: String): String = when {
    frame == "1M" -> "MN1"
    frame.endsWith("m") -> "M" + frame.dropLast(1)
    frame.endsWith("h") -> "H" + frame.dropLast(1)
    frame.endsWith("d") -> "D" + frame.dropLast(1)
    frame.endsWith("w") -> "W" + frame.dropLast(1)
    else -> frame
}
