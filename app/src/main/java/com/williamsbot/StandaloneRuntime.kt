@file:Suppress("UseKtx")
// Synchronous SharedPreferences commits here are deliberate: these writes mirror
// fail-closed recovery/kill/daily-loss safety latches and must finish before the
// runtime acknowledges a safety transition. Durable authority remains SQLite.
@file:android.annotation.SuppressLint("ApplySharedPref")
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
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.ArrayDeque
import java.util.Calendar
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.floor
import kotlin.math.ceil

/**
 * TC2 chapter 9: a bullish reversal bar is entirely below the Alligator mouth,
 * closes in its upper half, and still has falling (red) AO. Angulation is a
 * separate, explicitly approximate context test; this predicate is not an entry.
 * SHORT uses the exact reflected OHLC/indicator geometry.
 */
internal fun tc2BullishWiseManBarQualified(
    high: Double, low: Double, close: Double, priorLow: Double,
    jaw: Double, teeth: Double, lips: Double, aoNow: Double, aoPrevious: Double
): Boolean {
    if (listOf(high, low, close, priorLow, jaw, teeth, lips, aoNow, aoPrevious)
            .any { !it.isFinite() } || high <= low || close !in low..high) return false
    return low < priorLow &&
        close >= low + (high - low) * 0.5 &&
        high < min(jaw, min(teeth, lips)) &&
        aoNow < aoPrevious
}

/** TC2: a pending opposite setup is NOT an exit until its price actually triggers. */
internal fun tc2OppositeBreakoutTriggered(
    positionIsLong: Boolean,
    observedPrice: Double,
    opposingTrigger: Double
): Boolean {
    if (!observedPrice.isFinite() || !opposingTrigger.isFinite() ||
        observedPrice <= 0.0 || opposingTrigger <= 0.0) return false
    return if (positionIsLong) observedPrice <= opposingTrigger
        else observedPrice >= opposingTrigger
}

/**
 * TC2 ch.11 figs 11.1/11.2: equal extremes can be separated by one inside
 * bar. Two consecutive strictly lower/higher bars separate independent groups.
 * This makes the 6- and 9-bar drawings causal, without globally using >=.
 */
internal fun tc2FractalGroupStart(
    extremes: List<Double>, anchor: Int, isHigh: Boolean
): Int {
    if (anchor !in extremes.indices || !extremes[anchor].isFinite()) return anchor
    val level = extremes[anchor]
    var first = anchor
    var lowerRun = 0
    for (j in anchor - 1 downTo 0) {
        val value = extremes[j]
        if (!value.isFinite() || (if (isHigh) value > level else value < level)) break
        if (value == level) {
            first = j
            lowerRun = 0
        } else {
            lowerRun++
            if (lowerRun >= 2) break
        }
    }
    return first
}

internal fun tc2ConfirmedFractal(
    extremes: List<Double>, anchor: Int, isHigh: Boolean
): Boolean {
    if (anchor < 2 || anchor + 2 >= extremes.size) return false
    val level = extremes[anchor]
    if (!level.isFinite()) return false
    val start = tc2FractalGroupStart(extremes, anchor, isHigh)
    if (start < 2) return false
    fun outside(value: Double) = value.isFinite() &&
        (if (isHigh) value < level else value > level)
    return outside(extremes[start - 1]) && outside(extremes[start - 2]) &&
        outside(extremes[anchor + 1]) && outside(extremes[anchor + 2])
}

object StandaloneRuntime {
    @SuppressLint("StaticFieldLeak")
    private var server: StandaloneServer? = null

    @Synchronized
    fun start(context: Context) {
        if (server == null) {
            server = StandaloneServer(context.applicationContext)
        }
    }

    /** Dispatch cockpit commands directly to the local engine; no remote backend or open loopback port. */
    fun request(context: Context, method: String, path: String, body: String? = null): String {
        start(context)
        return requireNotNull(server).dispatch(method, path, body.orEmpty())
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

/**
 * Risk reservations are fractions of account equity, not price-distance percentages.
 * An absent/invalid reservation is unknown exposure and therefore consumes the
 * entire campaign budget until exchange reconciliation restores the persisted value.
 */
/**
 * Seed SMMA with the first-period simple average, matching the canonical Python
 * indicator implementation. Unwarmed values are null rather than guessed.
 */
internal fun smmaSeededValues(values: List<Double>, period: Int): List<Double?> {
    require(period > 0) { "SMMA period must be positive" }
    val output = MutableList<Double?>(values.size) { null }
    if (values.size < period || values.take(period).any { !it.isFinite() }) return output
    var seed = 0.0
    for (i in 0 until period) seed += values[i]
    output[period - 1] = seed / period
    for (i in period until values.size) {
        val previous = output[i - 1] ?: continue
        if (!values[i].isFinite()) continue
        output[i] = (previous * (period - 1) + values[i]) / period
    }
    return output
}

/** Display-only future chart displacement. Execution paths use current-time SMMA values instead. */
internal fun shiftAlligatorForDisplay(raw: List<Double?>, displacement: Int): List<Double?> {
    require(displacement >= 0) { "Alligator displacement must be non-negative" }
    return List(raw.size) { displayIndex ->
        val sourceIndex = displayIndex - displacement
        if (sourceIndex >= 0) raw.getOrNull(sourceIndex) else null
    }
}

/** Awesome Oscillator: SMA(5, median price) minus SMA(34, median price). */
internal fun awesomeOscillatorAt(medianPrices: List<Double>, index: Int): Double {
    if (index < 33 || index >= medianPrices.size) return Double.NaN
    val fastValues = medianPrices.subList(index - 4, index + 1)
    val slowValues = medianPrices.subList(index - 33, index + 1)
    if (fastValues.any { !it.isFinite() } || slowValues.any { !it.isFinite() }) return Double.NaN
    return fastValues.average() - slowValues.average()
}

/** Accelerator Oscillator: AO minus the five-bar simple average of AO. */
internal fun acceleratorOscillatorAt(aoValues: List<Double>, index: Int): Double {
    if (index < 4 || index >= aoValues.size) return Double.NaN
    val window = aoValues.subList(index - 4, index + 1)
    if (window.any { !it.isFinite() }) return Double.NaN
    return aoValues[index] - window.average()
}

internal data class StartupHistoryPolicy(
    val requestedCount: Int,
    val minimumCount: Int
)

/**
 * Each startup timeframe has its own real data requirement. H1 is the strategy
 * decision series; D1/H4/M15 are context/monitoring series and must not
 * prevent H1 startup by demanding the same history depth.
 */
internal fun startupHistoryPolicy(
    frame: String,
    h1RequestedCount: Int = 180,
    h1MinimumCount: Int = 150
): StartupHistoryPolicy = when (frame) {
    "1h" -> StartupHistoryPolicy(h1RequestedCount, h1MinimumCount)
    "4h" -> StartupHistoryPolicy(120, 45)
    "1d" -> StartupHistoryPolicy(90, 45)
    "15m" -> StartupHistoryPolicy(90, 45)
    else -> StartupHistoryPolicy(h1RequestedCount, h1MinimumCount)
}

internal fun futuresAccountEquity(
    totalMarginBalance: Double?,
    totalWalletBalance: Double?,
    totalUnrealizedProfit: Double?
): Double? {
    val value = totalMarginBalance
        ?: if (totalWalletBalance != null && totalUnrealizedProfit != null) {
            totalWalletBalance + totalUnrealizedProfit
        } else {
            return null
        }
    // Zero is a valid, known balance on a fresh/empty Demo account. Unknown is null;
    // non-finite and negative values remain invalid. The order gate separately requires
    // strictly positive available USDT and equity before any mutation can be attempted.
    return value.takeIf { it.isFinite() && it >= 0.0 }
}

internal data class FuturesAccountSnapshot(
    val availableUsdt: Double,
    val walletUsdt: Double,
    val equityUsdt: Double,
    val unrealizedPnlUsdt: Double?
)

/** Parse both numeric JSON values and numeric strings from the official USD-M account shape. */
internal fun parseFuturesAccountSnapshot(account: JSONObject): FuturesAccountSnapshot? {
    val usdt = (account.optJSONArray("assets") ?: JSONArray()).let { assets ->
        (0 until assets.length()).asSequence()
            .mapNotNull { assets.optJSONObject(it) }
            .firstOrNull { it.optString("asset").equals("USDT", ignoreCase = true) }
    }
    fun number(objectValue: JSONObject?, key: String): Double? {
        val raw = objectValue?.opt(key) ?: return null
        val parsed = when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.trim().toDoubleOrNull()
            else -> null
        }
        return parsed?.takeIf { it.isFinite() }
    }
    fun rootOrUsdt(rootKey: String, assetKey: String): Double? =
        number(account, rootKey) ?: number(usdt, assetKey)

    val available = rootOrUsdt("availableBalance", "availableBalance")
        ?.takeIf { it >= 0.0 } ?: return null
    val wallet = rootOrUsdt("totalWalletBalance", "walletBalance")
        ?.takeIf { it >= 0.0 } ?: return null
    val unrealized = rootOrUsdt("totalUnrealizedProfit", "unrealizedProfit")
    val margin = rootOrUsdt("totalMarginBalance", "marginBalance")
    val equity = futuresAccountEquity(margin, wallet, unrealized) ?: return null
    return FuturesAccountSnapshot(
        availableUsdt = available,
        walletUsdt = wallet,
        equityUsdt = equity,
        unrealizedPnlUsdt = unrealized
    )
}

internal fun futuresAccountFundedForEntry(
    availableUsdt: Double?,
    equity: Double?
): Boolean =
    availableUsdt != null && availableUsdt.isFinite() && availableUsdt > 0.0 &&
        equity != null && equity.isFinite() && equity > 0.0

/** Reject future-dated and genuinely stale signal bars using Binance server time. */
internal fun isSignalFresh(
    signalBarTimeMs: Long,
    exchangeNowMs: Long,
    maximumAgeMs: Long
): Boolean {
    if (signalBarTimeMs <= 0L || exchangeNowMs < signalBarTimeMs || maximumAgeMs < 0L) return false
    return exchangeNowMs - signalBarTimeMs <= maximumAgeMs
}

/**
 * Auto-run means keep the on-device monitor alive. The Kill Switch forbids order
 * mutations through Execution Gate; it does not forbid safe diagnostics/reconciliation.
 */
@Suppress("UNUSED_PARAMETER")
internal fun runtimeRecoveryContract(
    autoRunRequested: Boolean,
    running: Boolean,
    workerAlive: Boolean,
    killLatched: Boolean
): Boolean =
    running == workerAlive &&
        (!autoRunRequested || (running && workerAlive))

/**
 * Cross-check independent readiness claims before diagnostics report an overall state.
 * A mismatch is evidence of an inconsistent snapshot, never permission to enable execution.
 */
internal fun diagnosticConsistencyFindings(
    coordinatorState: String,
    coordinatorReady: Boolean,
    coordinatorBlocker: String,
    runtimeState: String,
    runtimeReconcileRequired: Boolean,
    executionEnabled: Boolean,
    dailyRiskBaselineSource: String,
    reconciliationRunning: Boolean,
    topLevelRuntimeState: String = runtimeState
): List<String> = buildList {
    if (topLevelRuntimeState.isNotBlank() && runtimeState.isNotBlank() &&
        topLevelRuntimeState != runtimeState) {
        add("RUNTIME_STATE_MISMATCH: status.state=$topLevelRuntimeState but execution_state_contract.state=$runtimeState")
    }
    if (coordinatorState == "RECONCILE_REQUIRED" && runtimeState != "RECONCILE_REQUIRED") {
        add("STATE_MISMATCH: coordinator requires reconciliation but runtime state is $runtimeState")
    }
    if (coordinatorState == "RECONCILE_REQUIRED" && !runtimeReconcileRequired) {
        add("RECONCILE_LATCH_MISMATCH: coordinator requires reconciliation but runtime reconcile_required is false")
    }
    if (!coordinatorReady && executionEnabled) {
        add("GATE_CONTRADICTION: execution enabled while campaign coordinator is not ready")
    }
    if (coordinatorState == "RECONCILE_REQUIRED" &&
        coordinatorBlocker == "exchange_reconciliation_in_progress" && !reconciliationRunning) {
        add("RECONCILIATION_STALE_BLOCKER: coordinator reports reconciliation in progress but no reconciliation task is running")
    }
    if (runtimeReconcileRequired && executionEnabled) {
        add("SAFETY_CONTRADICTION: execution enabled while reconcile_required is latched")
    }
    if (dailyRiskBaselineSource.isBlank() ||
        dailyRiskBaselineSource.equals("UNKNOWN", ignoreCase = true) ||
        dailyRiskBaselineSource.equals("null", ignoreCase = true)) {
        add("RISK_BASELINE_UNKNOWN: UTC daily-risk baseline source is not established")
        if (executionEnabled) {
            add("RISK_BASELINE_UNSAFE_EXECUTION: execution is enabled without a known UTC daily-risk baseline")
        }
    }
}

/** Diagnostic severity must not call a stopped monitor healthy just because both flags are false. */
internal fun runtimeLifecycleSeverity(
    autoRunRequested: Boolean,
    running: Boolean,
    workerAlive: Boolean
): String = when {
    running && workerAlive -> "PASS"
    running != workerAlive -> "FAIL"
    autoRunRequested -> "FAIL"
    else -> "WARN"
}

/** History is healthy when ready or actively loading; idle empty history is never a PASS. */
internal fun historyLifecycleSeverity(
    autoRunRequested: Boolean,
    running: Boolean,
    historyReady: Boolean,
    warmupRunning: Boolean,
    activeTasks: Int
): String = when {
    historyReady -> "PASS"
    running && warmupRunning && activeTasks > 0 -> "PASS"
    autoRunRequested && running -> "FAIL"
    else -> "WARN"
}

/** Only the result produced by the currently connected private-stream generation may clear its sync latch. */
internal fun mayCommitUserStreamSync(
    connected: Boolean,
    reconciliationPending: Boolean,
    attemptGeneration: Long,
    currentGeneration: Long
): Boolean = connected && !reconciliationPending && attemptGeneration == currentGeneration

/** A reconciliation worker is unhealthy if its start time is unknown, in the future, or over the deadline. */
internal fun reconciliationTaskLifecycleHealthy(
    running: Boolean,
    startedAtMs: Long,
    nowMs: Long,
    maximumDurationMs: Long = 300_000L
): Boolean {
    if (!running) return true
    if (maximumDurationMs <= 0L || startedAtMs <= 0L || nowMs < startedAtMs) return false
    return nowMs - startedAtMs <= maximumDurationMs
}

/** Private-stream readiness is based on successful exchange reconciliation, not event traffic. */
internal fun userStreamSyncIsFresh(
    connected: Boolean,
    syncRequired: Boolean,
    lastSuccessfulSyncMs: Long,
    nowMs: Long
): Boolean =
    connected && !syncRequired && lastSuccessfulSyncMs > 0L &&
        nowMs >= lastSuccessfulSyncMs

/** Prefer a live WS quote, then a fresh Futures REST ticker, then the last closed candle. */
internal fun dashboardPrice(
    livePrice: Double?,
    restPrice: Double?,
    lastClosedPrice: Double?,
    livePriceFresh: Boolean,
    restTickerFresh: Boolean
): Double? {
    fun valid(value: Double?): Double? =
        value?.takeIf { it.isFinite() && it > 0.0 }
    return (if (livePriceFresh) valid(livePrice) else null)
        ?: (if (restTickerFresh) valid(restPrice) else null)
        ?: valid(lastClosedPrice)
}

internal fun scannerReadinessState(
    running: Boolean,
    paused: Boolean,
    reconcileRequired: Boolean,
    killLatched: Boolean,
    historyReady: Boolean,
    accountReady: Boolean,
    marketDataFresh: Boolean
): String = when {
    !running -> "STOPPED"
    paused -> "PAUSED"
    reconcileRequired -> "BLOCKED_RECONCILE"
    killLatched -> "BLOCKED_KILL"
    !historyReady -> "WAITING_FOR_HISTORY"
    !accountReady -> "BLOCKED_ACCOUNT"
    !marketDataFresh -> "WAITING_FOR_MARKET_DATA"
    else -> "RUNNABLE"
}

/**
 * Read-only discovery has fewer prerequisites than order execution. A pause,
 * kill switch, or unresolved exchange position must not stop H1 diagnostics;
 * the independent Execution Gate still blocks every new order mutation.
 */
internal fun scannerDataReadinessState(
    running: Boolean,
    historyReady: Boolean,
    marketDataFresh: Boolean
): String = when {
    !running -> "STOPPED"
    !historyReady -> "WAITING_FOR_HISTORY"
    !marketDataFresh -> "WAITING_FOR_MARKET_DATA"
    else -> "RUNNABLE"
}

/** Execution blockers remain visible after a successful read-only market scan. */
internal fun scannerExecutionBlocker(
    paused: Boolean,
    reconcileRequired: Boolean,
    killLatched: Boolean,
    accountReady: Boolean,
    marketDataFresh: Boolean,
    coordinatorState: String
): String? = when {
    killLatched -> "BLOCKED_KILL"
    reconcileRequired -> "BLOCKED_FUTURES_RECONCILIATION"
    coordinatorState.uppercase(Locale.US) in setOf(
        "UNINITIALIZED", "RECONCILE_REQUIRED", "POSITION_UNPROTECTED",
        "ENTRY_PENDING", "EXIT_PENDING"
    ) -> "BLOCKED_FUTURES_RECONCILIATION"
    paused -> "PAUSED"
    !accountReady -> "BLOCKED_ACCOUNT"
    !marketDataFresh -> "WAITING_FOR_MARKET_DATA"
    else -> null
}

/** START is the safe entry point for authoritative reconciliation. */
internal fun runtimeStartButtonEnabled(
    binanceConfigured: Boolean,
    running: Boolean,
    killLatched: Boolean
): Boolean {
    // START also brings up read-only market/history feeds and safe reconciliation.
    // A kill latch blocks order mutations, not the operator's ability to diagnose it.
    return binanceConfigured && !running
}

/**
 * Permit a throttled retry when a connected private stream remains unsynchronized.
 * This schedules reconciliation only; it never clears the safety barrier itself.
 */
internal fun shouldRetryUserStreamReconciliation(
    connected: Boolean,
    syncRequired: Boolean,
    reconciliationRunning: Boolean,
    lastAttemptMs: Long,
    nowMs: Long,
    minIntervalMs: Long = 10_000L
): Boolean {
    if (!connected || !syncRequired || reconciliationRunning || minIntervalMs < 0L || nowMs < 0L) return false
    if (lastAttemptMs <= 0L) return true
    return nowMs >= lastAttemptMs && nowMs - lastAttemptMs >= minIntervalMs
}

/** Detect a socket that is open but has stopped delivering events or has a stalled handshake. */
internal fun streamNeedsReconnect(
    socketPresent: Boolean,
    lastEventMs: Long,
    startedAtMs: Long,
    nowMs: Long,
    maxIdleMs: Long = 45_000L
): Boolean {
    if (!socketPresent || maxIdleMs <= 0L) return false
    val referenceMs = if (lastEventMs > 0L) lastEventMs else startedAtMs
    return referenceMs > 0L && nowMs >= referenceMs && nowMs - referenceMs > maxIdleMs
}

/**
 * Keep read-only market discovery alive on an unfunded Demo account or when an
 * account refresh is temporarily unknown. A confirmed risk breach that latches
 * PAUSE/KILL must stop scanning for entries and invoke position management.
 */
internal fun dailyRiskBlocksScanner(
    accountFunded: Boolean,
    dailyRiskAllowed: Boolean,
    paused: Boolean,
    killLatched: Boolean
): Boolean = accountFunded && !dailyRiskAllowed && (paused || killLatched)

internal fun conservativeRiskReservation(storedRiskPct: Double, maxTotalRiskPct: Double): Double {
    if (!maxTotalRiskPct.isFinite() || maxTotalRiskPct <= 0.0) return 1.0
    if (!storedRiskPct.isFinite() || storedRiskPct <= 0.0) return maxTotalRiskPct
    return minOf(storedRiskPct, maxTotalRiskPct)
}

/**
 * A new daily-loss baseline is trustworthy only when there is no exchange exposure,
 * pending execution, or trading PnL from earlier in this UTC day. Otherwise resetting
 * it to current equity could erase an already-incurred daily loss.
 */
internal fun canInitializeDailyRiskBaseline(
    hasOpenPosition: Boolean,
    hasActiveExecution: Boolean,
    hasTradingIncome: Boolean
): Boolean = !hasOpenPosition && !hasActiveExecution && !hasTradingIncome

/** Missing provenance may be repaired only when the already-persisted baseline is valid. */
internal fun canRepairDailyRiskBaselineSource(
    baselineValue: String?,
    source: String?
): Boolean {
    val baseline = baselineValue?.toDoubleOrNull()
    val sourceMissing = source.isNullOrBlank() ||
        source.equals("UNKNOWN", ignoreCase = true) ||
        source.equals("null", ignoreCase = true)
    return sourceMissing && baseline != null && baseline.isFinite() && baseline > 0.0
}

// Rebuild a missing UTC daily equity baseline from the complete USD-M Futures
// income history. Transfers are neutral capital movements; trading income and
// unrealized PnL are removed from current equity to reconstruct the day's starting
// trading equity. A full 1000-row response is treated as possibly truncated.
private val FUTURES_CAPITAL_FLOW_TYPES = setOf(
    "TRANSFER", "INTERNAL_TRANSFER", "DEPOSIT", "WITHDRAW",
    "WELCOME_BONUS", "BONUS", "AUTO_EXCHANGE",
    "COIN_SWAP_DEPOSIT", "COIN_SWAP_WITHDRAW",
    "CROSS_COLLATERAL_TRANSFER", "PORTFOLIO_MARGIN_TRANSFER"
)

internal data class FuturesDailyIncomeSummary(
    val totalIncomeUsdt: Double,
    val tradingIncomeUsdt: Double,
    val capitalFlowUsdt: Double,
    val hasTradingActivity: Boolean
)

internal fun summarizeFuturesDailyIncome(
    incomeRows: JSONArray,
    maximumRows: Int = Int.MAX_VALUE
): FuturesDailyIncomeSummary? {
    if (maximumRows <= 0 || incomeRows.length() >= maximumRows) return null
    var total = 0.0
    var trading = 0.0
    var capitalFlow = 0.0
    var hasTradingActivity = false
    for (index in 0 until incomeRows.length()) {
        val row = incomeRows.optJSONObject(index) ?: return null
        val type = row.optString("incomeType").trim().uppercase(Locale.US)
        val amount = when (val raw = row.opt("income")) {
            is Number -> raw.toDouble()
            is String -> raw.trim().toDoubleOrNull()
            else -> null
        } ?: return null
        if (type.isBlank() || !amount.isFinite()) return null
        total += amount
        if (type in FUTURES_CAPITAL_FLOW_TYPES) {
            capitalFlow += amount
        } else {
            trading += amount
            hasTradingActivity = true
        }
        if (!total.isFinite() || !trading.isFinite() || !capitalFlow.isFinite()) return null
    }
    return FuturesDailyIncomeSummary(total, trading, capitalFlow, hasTradingActivity)
}

internal fun reconstructFuturesDailyRiskBaseline(
    currentEquityUsdt: Double,
    unrealizedPnlUsdt: Double?,
    incomeRows: JSONArray,
    maximumRows: Int = Int.MAX_VALUE
): Double? {
    if (!currentEquityUsdt.isFinite() || currentEquityUsdt <= 0.0 ||
        unrealizedPnlUsdt == null || !unrealizedPnlUsdt.isFinite()
    ) return null
    val summary = summarizeFuturesDailyIncome(incomeRows, maximumRows) ?: return null
    // Equity includes both trading results and capital movements. Remove all Futures
    // income-ledger movements plus current unrealized PnL to recover the day baseline.
    val baseline = currentEquityUsdt - summary.totalIncomeUsdt - unrealizedPnlUsdt
    return baseline.takeIf { it.isFinite() && it > 0.0 }
}

internal data class FuturesDailyBaselineDecision(
    val baselineEquityUsdt: Double,
    val source: String
)

/**
 * Never replace a missing daily baseline with current equity if the exchange reports
 * income rows or an open/unknown exposure. Capital transfers must be removed from
 * reconstructed starting equity even when there is no trading income today.
 */
internal fun selectFuturesDailyBaseline(
    currentEquityUsdt: Double,
    unrealizedPnlUsdt: Double?,
    incomeRows: JSONArray,
    hasOpenOrUnknownPositionOrPendingExecution: Boolean
): FuturesDailyBaselineDecision? {
    if (!currentEquityUsdt.isFinite() || currentEquityUsdt <= 0.0) return null
    val summary = summarizeFuturesDailyIncome(incomeRows) ?: return null
    if (incomeRows.length() > 0) {
        val reconstructed = reconstructFuturesDailyRiskBaseline(
            currentEquityUsdt = currentEquityUsdt,
            unrealizedPnlUsdt = unrealizedPnlUsdt,
            incomeRows = incomeRows
        ) ?: return null
        return FuturesDailyBaselineDecision(
            baselineEquityUsdt = reconstructed,
            source = if (summary.hasTradingActivity) {
                "RECONSTRUCTED_FROM_FUTURES_INCOME"
            } else {
                "RECONSTRUCTED_FROM_CAPITAL_FLOW_LEDGER"
            }
        )
    }
    if (hasOpenOrUnknownPositionOrPendingExecution) return null
    return FuturesDailyBaselineDecision(currentEquityUsdt, "INITIAL_FLAT_SNAPSHOT")
}

internal data class ForcedH1EntryLevels(
    val triggerPrice: Double,
    val protectiveStop: Double
)

/** User-directed test entry levels, derived only from closed H1 structure. */
internal fun forcedH1EntryLevels(
    direction: String,
    highs: List<Double>,
    lows: List<Double>,
    tickSize: Double
): ForcedH1EntryLevels? {
    val side = direction.trim().uppercase(Locale.US)
    if (side != "LONG" && side != "SHORT") return null
    if (tickSize <= 0.0 || !tickSize.isFinite() ||
        highs.size < 5 || lows.size < 5 ||
        highs.takeLast(5).any { !it.isFinite() || it <= 0.0 } ||
        lows.takeLast(5).any { !it.isFinite() || it <= 0.0 }
    ) return null

    val breakoutHigh = highs.takeLast(3).maxOrNull() ?: return null
    val breakoutLow = lows.takeLast(3).minOrNull() ?: return null
    val structureHigh = highs.takeLast(5).maxOrNull() ?: return null
    val structureLow = lows.takeLast(5).minOrNull() ?: return null
    fun roundDown(value: Double): Double = floor(value / tickSize + 1e-12) * tickSize
    fun roundUp(value: Double): Double = ceil(value / tickSize - 1e-12) * tickSize

    val levels = if (side == "LONG") {
        ForcedH1EntryLevels(
            triggerPrice = roundUp(breakoutHigh + tickSize),
            protectiveStop = roundDown(structureLow - tickSize)
        )
    } else {
        ForcedH1EntryLevels(
            triggerPrice = roundDown(breakoutLow - tickSize),
            protectiveStop = roundUp(structureHigh + tickSize)
        )
    }
    if (!levels.triggerPrice.isFinite() || !levels.protectiveStop.isFinite() ||
        levels.triggerPrice <= 0.0 || levels.protectiveStop <= 0.0 ||
        (side == "LONG" && levels.protectiveStop >= levels.triggerPrice) ||
        (side == "SHORT" && levels.protectiveStop <= levels.triggerPrice)
    ) return null
    return levels
}

/** Resolve only explicit strategy signal types; unknown types must never default to LONG. */
internal fun isCampaignAddOnSignalForDirection(signalType: String, positionSignalType: String): Boolean {
    val signal = signalType.trim().uppercase(Locale.US)
    val position = positionSignalType.trim().uppercase(Locale.US)
    val signalIsShort = signal.startsWith("SHORT_")
    val positionIsShort = position.startsWith("SHORT_")
    val family = if (signalIsShort) signal.removePrefix("SHORT_") else signal
    // Unknown position direction must not inherit the LONG default.
    if (position.isBlank() || (!positionIsShort && position !in setOf("REVERSAL", "SUPER_AO", "FRACTAL") && !position.startsWith("LONG_"))) {
        return false
    }
    return signalIsShort == positionIsShort && family in setOf("SUPER_AO", "FRACTAL")
}

/** Resolve only explicit strategy signal types; unknown types must never default to LONG. */
internal fun campaignSignalDirection(signalType: String): String? {
    val type = signalType.trim().uppercase(Locale.US)
    return when {
        type.startsWith("SHORT_") -> "SHORT"
        type.startsWith("LONG_") || type in setOf("REVERSAL", "SUPER_AO", "FRACTAL") -> "LONG"
        else -> null
    }
}

/** 0 means the direction is vetoed, 1 is an allowed reversal, 2 is aligned H4 context. */
internal fun directionalSignalContextRank(
    signalType: String,
    d1Bull: Boolean,
    d1Bear: Boolean,
    h4Bull: Boolean,
    h4Bear: Boolean
): Int {
    val type = signalType.trim().uppercase(Locale.US)
    val direction = campaignSignalDirection(type) ?: return 0
    val isLong = direction == "LONG"
    val isShort = direction == "SHORT"
    if (isLong && d1Bear || isShort && d1Bull) return 0
    val h4Aligned = if (isLong) h4Bull else h4Bear
    val isReversal = type == "REVERSAL" || type.endsWith("_REVERSAL")
    return when {
        h4Aligned -> 2
        isReversal -> 1
        else -> 0
    }
}


/** Rebuild the in-memory circuit breaker from the durable UTC baseline after process restart. */
internal fun observeFuturesEquityFromPersistedBaseline(
    breaker: EquityCircuitBreaker,
    dailyBaseline: Double,
    equity: Double,
    nowMs: Long
): EquityCircuitBreaker.Snapshot {
    breaker.observe(dailyBaseline, nowMs)
    return breaker.observe(equity, nowMs)
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
    val alligatorBearish: Boolean,
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

/**
 * Analysis-only per-timeframe evidence, never order authorization. A wave label
 * cannot be treated as observed truth, and incomplete/open candles are excluded.
 */
/**
 * Returns ONLY exchange-confirmed bar opens, in strictly increasing order.
 * A forming candle and a future-dated candle are never wave-count evidence.
 */
internal fun tc2ClosedWaveBarTimes(
    opens: List<Long>,
    frameDurationMs: Long,
    exchangeNowMs: Long
): List<Long> {
    if (frameDurationMs <= 0L || exchangeNowMs <= 0L) return emptyList()
    return opens.filter { it > 0L && it <= exchangeNowMs &&
        exchangeNowMs - it >= frameDurationMs }
        .distinct().sorted()
}

/**
 * Shape not screenshot: TC1 ch.7 figs 7-14/15 compares identical price
 * heights with different durations. This is a documented *engineering*
 * geometric proxy, not a source-claimed exact Williams area formula.
 * ATR-normalized distance works across contracts of different price scales.
 */
internal data class WilliamsSwingGeometry(
    val bars: Int, val distanceAtr: Double,
    val velocityAtrPerBar: Double, val triangularAreaAtrBars: Double
)
internal fun williamsSwingGeometry(
    from: Double, to: Double, barCount: Int, atr: Double
): WilliamsSwingGeometry? {
    if (!from.isFinite() || !to.isFinite() || !atr.isFinite() ||
        from <= 0.0 || to <= 0.0 || atr <= 0.0 || barCount <= 0) return null
    val distance = kotlin.math.abs(to - from) / atr
    if (!distance.isFinite()) return null
    return WilliamsSwingGeometry(barCount, distance, distance / barCount,
        distance * barCount / 2.0)
}

private data class WaveFrameEvidence(
    val timeframe: String,
    val state: String,
    val asOfCloseMs: Long,
    val closedBars: Int,
    val phase: String = "UNKNOWN",
    val primary: String = "UNKNOWN",
    val alternative: String = "UNKNOWN",
    val direction: String = "NEUTRAL",
    val confidence: Double = 0.0,
    val pivotCount: Int = 0,
    val lastPivotKind: String = "NONE",
    val lastPivotPrice: Double = 0.0,
    val oppositePivotPrice: Double = 0.0,
    val swing: WilliamsSwingGeometry? = null,
    val lastClose: Double = 0.0,
    val ao: Double = 0.0,
    val aoDelta: Double = 0.0,
    val ac: Double = 0.0,
    val alligatorState: String = "UNKNOWN",
    val invalidationPrice: Double = 0.0,
    val reason: String = ""
) {
    fun asJson(): JSONObject = JSONObject()
        .put("timeframe", timeframe)
        .put("state", state)
        .put("as_of_closed_at_ms", asOfCloseMs)
        .put("closed_bars", closedBars)
        .put("phase", phase)
        .put("primary_count", primary)
        .put("alternative_count", alternative)
        .put("direction", direction)
        .put("confidence", confidence)
        .put("confirmed_pivots", pivotCount)
        .put("last_pivot_kind", lastPivotKind)
        .put("last_pivot_price", if (lastPivotPrice > 0.0) lastPivotPrice else JSONObject.NULL)
        .put("opposite_pivot_price", if (oppositePivotPrice > 0.0) oppositePivotPrice else JSONObject.NULL)
        .put("invalidation_price", if (invalidationPrice > 0.0) invalidationPrice else JSONObject.NULL)
        .put("last_close", if (lastClose > 0.0) lastClose else JSONObject.NULL)
        .put("ao", ao).put("ao_delta", aoDelta).put("ac", ac)
        .put("alligator_state", alligatorState)
        .put("swing_bars", swing?.bars ?: JSONObject.NULL)
        .put("swing_distance_atr", swing?.distanceAtr ?: JSONObject.NULL)
        .put("swing_velocity_atr_per_bar", swing?.velocityAtrPerBar ?: JSONObject.NULL)
        .put("price_time_area_proxy_atr_bars", swing?.triangularAreaAtrBars ?: JSONObject.NULL)
        .put("wave_model", "HEURISTIC_UNVERIFIED")
        .put("execution_authority", false)
        .put("reason", reason)
}

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
    val campaignReady: Boolean = false,
    val waveFrames: List<WaveFrameEvidence> = emptyList()
)

private class StandaloneServer(private val context: Context) {
    // in-process Futures Runtime API; no remote execution dependency; no loopback listener.
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "williams_native_secure",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val executionStore = FuturesExecutionStore(context).also {
        it.migrateLegacyState(
            prefs,
            listOf(
                "futures_campaign_intents_v2",
                "positions_json",
                "pending_entries_json",
                "futures_daily_loss_pct",
                "futures_daily_pnl_usdt",
                "reconcile_required",
                "kill_latched"
            ) + prefs.all.keys.filter { it.startsWith("futures_day_start_equity_") }
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val historyStore = MarketHistoryStore(context)
    private val auditStore = TradingAuditStore(context)
    private val stateMachine = TradingStateMachine(
        onTransition = { from, to, reason ->
            auditStore.recordState(from, to, reason)
        }
    )

    private var engine: NativeEngine? = null

    fun stop() {
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
            engine = NativeEngine(context, prefs, client, executionStore)
        }
        return engine!!
    }

    fun dispatch(method: String, target: String, body: String): String =
        route(method, target, body)

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

            method == "POST" && path == "/api/v1/control/testnet-force-mode" ->
                x.testnetForceMode(JSONObject(body)).toString()

            method == "POST" && path == "/api/v1/control/testnet-force-entry" ->
                x.forceTestnetEntry(JSONObject(body)).toString()

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
    private val http: OkHttpClient,
    private val executionStore: FuturesExecutionStore
) {
    private val baseUrl = "https://demo-fapi.binance.com"
    // All REST transport in this Android runtime is anchored to USD-M Futures Testnet.
    // Spot-shaped signed routes are rejected; legacy public reads are translated only
    // to their Futures market-data equivalents by getBody().
    // One governor is shared by public market reads, signed account/order REST,
    // and diagnostics so all Futures requests respect the same IP rate bucket.
    private val rateGuard = BinanceRateGuard()
    private val futuresMarketData = BinanceUsdMFuturesMarketData(http, rateGuard)
    // Runtime execution is routed through the local USD-M Futures adapter/coordinator below.
    // Legacy signed transport was removed; account/order mutations use the Futures adapter/coordinator.
    private val futuresExecutionAdapterReady = true
    @Volatile private var futuresExchangeInfoCache: Pair<Long, JSONObject>? = null
    private val futuresCampaignCoordinator by lazy {
        NativeFuturesCampaignCoordinator(executionStore) {
            BinanceUsdMFuturesTestnetAdapter(key(), secret(), http, rateGuard)
        }.also { coordinator ->
            coordinator.setStructuralStopRecoveryProvider { symbol, direction, mark, quantity, entry ->
                recoverMissingStructuralStop(symbol, direction, mark, quantity, entry)
            }
        }
    }
    private val historyStore = MarketHistoryStore(context)
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

    // This on-device USD-M Futures Runtime owns the approved ten-contract universe.
    // The remote backend is optional and is not in the order-execution path.
    private val coreSymbols = listOf(
        "BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT",
        "DOGEUSDT", "ADAUSDT", "LINKUSDT", "AVAXUSDT", "LTCUSDT"
    )
    private val analysisFrames = listOf("1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "8h", "12h", "1d", "3d", "1w", "1M")
    // Startup only needs the frames that directly participate in execution.
    // The complete 15-TF matrix is analysis metadata, not a startup blocker.
    private val startupFrames = listOf("1d", "4h", "1h", "15m")
    // The H1 decision engine needs the full 150-candle working set. Higher-timeframe
    // context has explicit lower minimums in the entry contract; requiring 150 D1
    // candles during startup can deadlock scanning on a fresh Futures Demo market.
    private val startupHistoryLimit = 180
    private val minStartupHistoryCandles = 150

    private fun startupHistoryLimit(frame: String): Int =
        startupHistoryPolicy(frame, startupHistoryLimit, minStartupHistoryCandles).requestedCount

    private fun minStartupHistoryCandles(frame: String): Int =
        startupHistoryPolicy(frame, startupHistoryLimit, minStartupHistoryCandles).minimumCount
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
    // Keep last trade distinct from bid/ask midpoint: the chart LIVE line should
    // follow the traded price first, then the live Futures bookTicker, then REST.
    private val lastTradePrices = java.util.concurrent.ConcurrentHashMap<String, Double>()
    private val lastTradeUpdatedAtMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val livePrices = java.util.concurrent.ConcurrentHashMap<String, Double>()
    private val livePriceUpdatedAtMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    // Chart fallback is rate-limited per symbol when the live Futures public streams are stale.
    private val chartRestQuoteCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Double, Long>>()
    private val tradeFlow = java.util.concurrent.ConcurrentHashMap<String, ArrayDeque<TradeFlowSample>>()
    private val lastUserEventTimeByType = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var marketSocket: WebSocket? = null
    private var publicSocket: WebSocket? = null
    private val orderBookCache = OrderBookCache()
    @Volatile private var marketSocketConnected = false
    @Volatile private var marketSocketLastEventMs = 0L
    @Volatile private var marketSocketStartedMs = 0L
    @Volatile private var marketReconnectRequested = false
    @Volatile private var publicSocketConnected = false
    @Volatile private var publicSocketLastEventMs = 0L
    @Volatile private var publicSocketStartedMs = 0L
    @Volatile private var publicReconnectRequested = false
    @Volatile private var historyWarmupRunning = false
    @Volatile private var historyReady = false
    private val activeHistoryTasks = java.util.concurrent.ConcurrentHashMap.newKeySet<java.util.concurrent.Future<Boolean>>()
    @Volatile private var historyState = "IDLE"
    @Volatile private var historyLastError: String? = null

    private val scanCacheTtlMs = 12_000L
    private val scanUniverseCacheTtlMs = 15_000L
    @Volatile private var cachedScanUniverse: Triple<List<String>, Map<String, Double>, Map<String, Double>>? = null
    @Volatile private var scanUniverseCacheAtMs = 0L
    @Volatile private var lastScanDailyRiskCheckAtMs = 0L
    @Volatile private var lastScanDailyRiskCheckAllowed = false
    @Volatile private var lastNativeManagementAttemptMs = 0L
    private val deepWatchTopN = 10
    private val scannerUniverseLabel = "USD_M_FUTURES_TESTNET_10"

    @Volatile
    private var running = false

    @Volatile
    private var paused = false

    @Volatile
    private var forceTestnetMode =
        prefs.getBoolean("force_testnet_mode", false) ||
            executionStore.readState("futures_testnet_force_mode") == "true"

    @Volatile
    private var nativeManagementRunning = false

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
    // Concurrent campaigns may span the approved Futures universe. One campaign per symbol,
    // unresolved intents occupying slots, and the 0.6% aggregate risk cap remain mandatory.
    private val maxOpenPositions: Int
        get() = APPROVED_FUTURES_SYMBOLS.size
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
    @Volatile private var circuitBreakerTripInProgress = false
    private val feeBufferPerSidePct = 0.001
    @Volatile private var lastOrder: JSONObject? = null
    @Volatile private var reconcileRequired = false
    @Volatile private var killLatched = false
    @Volatile private var userStreamConnected = false
    @Volatile private var userStreamConnectionGeneration = 0L
    @Volatile private var userStreamSyncRequired = true
    @Volatile private var userStreamLastError: String? = null
    // All account-wide Futures snapshots share one lock, including startup, user-stream,
    // position-management, recovery and self-heal callers. The durable Execution Door
    // serializes mutations, but without this lock concurrent reconciliation requests can
    // race their coordinator snapshots and incorrectly latch RECONCILE_REQUIRED.
    private val futuresReconciliationLock = java.util.concurrent.locks.ReentrantLock()
    @Volatile private var futuresReconciliationRunning = false
    @Volatile private var futuresStreamReconcileRunning = false
    @Volatile private var futuresStreamReconcilePending = false
    @Volatile private var lastUserStreamReconcileAttemptMs = 0L
    @Volatile private var userStreamReconcileStartedAtMs = 0L
    @Volatile private var userStreamReconcileFinishedAtMs = 0L
    @Volatile private var userStreamReconcileFailure: String? = null
    @Volatile private var lastUserEventMs = 0L
    @Volatile private var lastUserStreamSyncAtMs = 0L
    @Volatile private var liveUsdtBalance = 0.0
    @Volatile private var liveUsdtBalanceKnown = false
    @Volatile private var futuresAccountSnapshotUpdatedAtMs = 0L
    @Volatile private var futuresAccountSnapshotRefreshRunning = false
    @Volatile private var futuresAccountSnapshotLastAttemptMs = 0L
    @Volatile private var futuresAccountSnapshotRefreshError: String? = null
    @Volatile private var cachedFuturesPositionsSnapshot: JSONArray? = null
    @Volatile private var cachedFuturesPositionsSnapshotAtMs = 0L
    @Volatile private var futuresPositionSnapshotRefreshRunning = false
    @Volatile private var futuresPositionSnapshotLastAttemptMs = 0L
    @Volatile private var futuresPositionSnapshotRefreshError: String? = null
    // Authoritative Futures account equity (totalMarginBalance, with wallet+UPnL fallback).
    // Never synthesize equity by adding position notional to free balance.
    @Volatile private var liveFuturesEquity = 0.0
    @Volatile private var restMarketReady = false
    @Volatile private var restAccountReady = false
    @Volatile private var restLastSuccessMs = 0L
    @Volatile private var restLastLatencyMs = 0L
    @Volatile private var restLastError: String? = null
    @Volatile private var restAccountLastError: String? = null
    @Volatile private var restLastTickerPrice = 0.0
    @Volatile private var restProbeRunning = false
    private var restProbeThread: Thread? = null
    @Volatile private var lastRestReconcileMs = 0L
    @Volatile private var lastReconciliationAttemptAtMs = 0L
    @Volatile private var lastReconciliationCompletedAtMs = 0L
    @Volatile private var lastReconciliationResult = "NEVER_RUN"
    @Volatile private var lastReconciliationLockWaitMs = 0L
    @Volatile private var lastReconciliationLockWaitFailure: String? = null
    @Volatile private var runtimeGeneration = 0L
    @Volatile private var lastSelfHealMs = 0L
    @Volatile private var selfHealCount = 0
    @Volatile private var workerRecoveryWindowStartedMs = 0L
    @Volatile private var workerRecoveryAttemptsInWindow = 0

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
        endpoint = "wss://demo-fstream.binance.com/private/ws",
        apiKeyProvider = { key() },
        onConnection = { connected, error ->
            synchronized(this) {
                // Change generation under the same monitor used by the reconciliation
                // worker so a prior socket's REST snapshot cannot certify a new session.
                userStreamConnectionGeneration += 1L
                userStreamConnected = connected
                userStreamSyncRequired = true
                userStreamLastError = if (connected) null else error?.takeUnless { it == "stopped" }
                if (connected) {
                    lastUserStreamSyncAtMs = 0L
                    if (lastError?.startsWith("user ws: ") == true) lastError = null
                } else if (!error.equals("stopped")) {
                    if (!killLatched) lastError = "user ws: " + (error ?: "disconnected")
                }
            }
            if (connected) scheduleFuturesStreamReconciliation("user stream connected", force = true)
        },
        onEvent = { event ->
            handleUserEvent(event)
        }
    )

    init {
        loadPersistedState()
        startRestDataProbe()
        restoreExecutionAccumulators()
        // SQLite is authoritative for the execution-door lease. SharedPreferences
        // mirrors it for backward compatibility, but must not hide a stale SQLite lease.
        val interruptedGateSymbol =
            (prefs.getString("execution_gate_symbol", "") ?: "")
                .ifBlank { executionStore.readState("execution_gate_symbol").orEmpty() }
        if (interruptedGateSymbol.isNotBlank()) {
            reconcileRequired = true
            persistSafetyFlag("reconcile_required", true)
            prefs.edit().putBoolean("auto_run", false).commit()
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

    private fun updateCachedFuturesAccount(account: JSONObject): Boolean {
        // A single tested parser is used by startup, diagnostics, risk and entry sizing.
        // Never substitute Spot balances, another asset, or position notional for USDT.
        val parsed = parseFuturesAccountSnapshot(account)
        if (parsed == null) {
            val usdtPresent = (account.optJSONArray("assets") ?: JSONArray()).let { assets ->
                (0 until assets.length()).any { i ->
                    assets.optJSONObject(i)?.optString("asset").equals("USDT", ignoreCase = true)
                }
            }
            liveUsdtBalanceKnown = false
            restAccountReady = false
            liveFuturesEquity = 0.0
            restAccountLastError = "Futures account missing valid USDT available balance/wallet/equity fields; rootKeys=" +
                account.keys().asSequence().toList().sorted().joinToString(",") +
                "; usdtAssetPresent=" + usdtPresent
            return false
        }

        liveUsdtBalance = parsed.availableUsdt
        liveUsdtBalanceKnown = true
        liveFuturesEquity = parsed.equityUsdt
        futuresAccountSnapshotUpdatedAtMs = System.currentTimeMillis()
        futuresAccountSnapshotRefreshError = null
        restAccountReady = true
        restAccountLastError = null
        return true
    }

    private fun reservedRiskPct(): Double {
        val openRisk = positionList().sumOf {
            conservativeRiskReservation(it.riskPct, maxTotalRiskPct)
        }
        val pendingRisk = if (campaignEngineEnabled) {
            synchronized(pendingEntries) {
                pendingEntries.values.sumOf {
                    conservativeRiskReservation(it.riskReservedPct, maxTotalRiskPct)
                }
            }
        } else {
            0.0
        }
        return (openRisk + pendingRisk).coerceAtMost(maxTotalRiskPct)
    }

    private fun marketDataFresh(maxAgeMs: Long = 30_000L): Boolean =
        restMarketReady &&
            restLastSuccessMs > 0L &&
            System.currentTimeMillis() - restLastSuccessMs <= maxAgeMs

    private fun marketWebSocketFresh(maxAgeMs: Long = 30_000L): Boolean =
        marketSocketConnected &&
            marketSocketLastEventMs > 0L &&
            System.currentTimeMillis() - marketSocketLastEventMs <= maxAgeMs

    private fun publicWebSocketFresh(maxAgeMs: Long = 30_000L): Boolean =
        publicSocketConnected &&
            publicSocketLastEventMs > 0L &&
            System.currentTimeMillis() - publicSocketLastEventMs <= maxAgeMs

    private fun accountFundedForEntry(): Boolean =
        restAccountReady && futuresAccountFundedForEntry(liveUsdtBalance, liveFuturesEquity)

    /**
     * Runtime readiness and the campaign coordinator must agree. The coordinator
     * owns the exchange-side campaign lifecycle; a healthy socket/account alone
     * must never make the outer gate report execution-ready while it is reconciling.
     */
    private fun campaignCoordinatorReadyForEntry(): Boolean = runCatching {
        val status = futuresCampaignCoordinator.status()
        status.optBoolean("ready_for_new_entry", false) &&
            status.optString("state") in setOf("READY", "OPEN_PROTECTED", "ENTRY_PENDING")
    }.getOrDefault(false)

    /**
     * ENTRY_PENDING can still admit another symbol only when the coordinator has
     * reconciled the existing pending order(s) and confirms admission is safe.
     * Generic state-machine executionAllowed() stays restrictive for other callers.
     */
    private fun futuresFsmAllowsEntry(): Boolean =
        stateMachine.executionAllowed() ||
            (stateMachine.state == TradingState.ENTRY_PENDING && campaignCoordinatorReadyForEntry())

    private fun futuresLossStreakGuard(): FuturesLossStreakDecision =
        evaluateFuturesLossStreak(
            tradeRows = TradeJournal.trades(prefs),
            nowMs = System.currentTimeMillis()
        )

    private fun executionReady(): Boolean =
        futuresExecutionAdapterReady &&
            campaignCoordinatorReadyForEntry() &&
            running &&
            !paused &&
            !reconcileRequired &&
            !killLatched &&
            marketDataFresh() &&
            marketWebSocketFresh() &&
            publicWebSocketFresh() &&
            userStreamConnected &&
            !userStreamSyncRequired &&
            restAccountReady &&
            accountFundedForEntry() &&
            historyReady &&
            futuresFsmAllowsEntry() &&
            futuresLossStreakGuard().allowNewEntries &&
            !equityCircuitBreaker.isTripped()

    private fun executionBlockers(): List<String> =
        buildList {
            if (!futuresExecutionAdapterReady) add("futures_execution_adapter_disabled")
            if (!campaignCoordinatorReadyForEntry()) {
                val coordinator = runCatching { futuresCampaignCoordinator.status() }.getOrNull()
                add("futures_campaign_coordinator_not_ready:" +
                    (coordinator?.optString("state")?.takeIf { it.isNotBlank() } ?: "unknown") +
                    ":" + (coordinator?.optString("blocker")?.takeIf { it.isNotBlank() && it != "null" } ?: "reconciliation_required"))
            }
            if (!running) add("runtime_not_running")
            if (paused) add("paused")
            if (reconcileRequired) add("reconcile_required")
            if (killLatched) add("kill_switch_latched")
            if (!marketDataFresh()) add("rest_market_stale_or_not_ready")
            if (!marketWebSocketFresh()) add("market_websocket_disconnected_or_stale")
            if (!publicWebSocketFresh()) add("public_depth_or_bookticker_stream_disconnected_or_stale")
            if (!userStreamConnected || userStreamSyncRequired) add("futures_user_stream_disconnected_or_unsynchronized")
            if (!restAccountReady) add("rest_account_not_ready")
            else if (!accountFundedForEntry()) add("futures_account_unfunded_or_zero_usdt")
            if (!historyReady) add("history_not_ready")
            if (!futuresFsmAllowsEntry()) add("fsm_not_execution_allowed")
            val lossStreak = futuresLossStreakGuard()
            if (!lossStreak.available) add(lossStreak.reason ?: "futures_loss_streak_unknown")
            else if (!lossStreak.allowNewEntries) add(lossStreak.reason ?: "futures_consecutive_loss_guard")
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

        check(executionStore.writeRuntimeState(pos.toString(), pending.toString())) {
            "SQLite commit failed while persisting Futures positions and pending entries"
        }
    }

    private fun loadPersistedState() {
        var persistedStateCorrupt = false
        synchronized(positions) {
            positions.clear()
            runCatching {
                val array = JSONArray(executionStore.readState("positions_json") ?: "[]")
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
            }.onFailure { failure ->
                persistedStateCorrupt = true
                lastError = "Persisted Futures positions are unreadable: " +
                    (failure.message ?: failure.javaClass.simpleName)
            }
        }

        synchronized(pendingEntries) {
            pendingEntries.clear()
            runCatching {
                val array = JSONArray(executionStore.readState("pending_entries_json") ?: "[]")
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
            }.onFailure { failure ->
                persistedStateCorrupt = true
                lastError = "Persisted Futures pending entries are unreadable: " +
                    (failure.message ?: failure.javaClass.simpleName)
            }
        }

        reconcileRequired =
            (executionStore.readState("reconcile_required")?.toBooleanStrictOrNull()
                ?: prefs.getBoolean("reconcile_required", false))
        killLatched =
            (executionStore.readState("kill_latched")?.toBooleanStrictOrNull()
                ?: prefs.getBoolean("kill_latched", false))
        if (persistedStateCorrupt) {
            reconcileRequired = true
            persistSafetyFlag("reconcile_required", true)
            stateMachine.force(
                TradingState.RECONCILE_REQUIRED,
                lastError ?: "Persisted Futures state is corrupt"
            )
        }
    }

    /**
     * Journal safety latches in SQLite before mirroring compatibility preferences.
     * A failed SQLite write always leaves the runtime fail-closed in memory.
     */
    private fun persistSafetyFlag(key: String, value: Boolean): Boolean {
        val durable = executionStore.writeState(key, value.toString())
        runCatching { prefs.edit().putBoolean(key, value).commit() }
        if (!durable) {
            lastError = "SQLite safety-latch write failed for $key=$value"
            if (key == "reconcile_required") reconcileRequired = true
            if (key == "kill_latched") killLatched = true
            return false
        }
        return true
    }

    private fun setReconcileRequired(reason: String) {
        // An UNKNOWN exchange outcome blocks order mutations but must not tear down
        // the monitor needed to reconcile that outcome. Stopping here used to kill
        // market/history/user streams and reset auto-run on the exact failure that
        // required fresh exchange evidence.
        reconcileRequired = true
        persistSafetyFlag("reconcile_required", true)
        val blocker = "RECONCILE_REQUIRED: " + reason
        scannerState = "BLOCKED_FUTURES_RECONCILIATION"
        scannerError = blocker
        lastError = blocker
        stateMachine.force(
            if (killLatched) TradingState.KILL_SWITCH_LATCHED else TradingState.RECONCILE_REQUIRED,
            blocker
        )
    }

    private fun clearReconcileRequired() {
        if (!persistSafetyFlag("reconcile_required", false)) return
        reconcileRequired = false
        if (!killLatched && pendingEntries.isEmpty() && positions.isEmpty()) {
            stateMachine.force(
                TradingState.READY_FLAT,
                "reconciliation cleared"
            )
        }
    }

    fun health(): JSONObject =
        JSONObject()
            .put("ok", running && !reconcileRequired && !killLatched)
            .put("execution_ready", executionReady())
            .put("service", "williams-native")
            .put("version", BuildConfig.VERSION_NAME)
            .put("standalone", true)
            .put("websocket", marketSocketConnected)
            .put("market_stream_last_event_ms", marketSocketLastEventMs)
            .put("public_websocket", publicSocketConnected)
            .put("public_stream_last_event_ms", publicSocketLastEventMs)
            .put("user_stream_connected", userStreamConnected)
            .put("user_stream_sync_required", userStreamSyncRequired)
            .put("user_stream_connection_generation", userStreamConnectionGeneration)
            .put("user_stream_last_event_ms", lastUserEventMs)
            .put("user_stream_last_successful_sync_ms", lastUserStreamSyncAtMs)
            .put("user_stream_last_error", userStreamLastError ?: JSONObject.NULL)
            .put("user_stream_reconcile_running", futuresStreamReconcileRunning)
            .put("user_stream_reconcile_started_at_ms", userStreamReconcileStartedAtMs)
            .put("user_stream_reconcile_finished_at_ms", userStreamReconcileFinishedAtMs)
            .put("user_stream_reconcile_duration_ms", if (userStreamReconcileStartedAtMs > 0L) ((if (futuresStreamReconcileRunning) System.currentTimeMillis() else userStreamReconcileFinishedAtMs) - userStreamReconcileStartedAtMs).coerceAtLeast(0L) else JSONObject.NULL)
            .put("user_stream_reconcile_failure", userStreamReconcileFailure ?: JSONObject.NULL)
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
            .put("futures_coordinator_state", stateMachine.state.name)
            .put("futures_coordinator_blocker", lastError ?: JSONObject.NULL)
            .put("futures_reconciliation_in_progress", futuresReconciliationRunning)
            .put("last_reconciliation_attempt_at_ms", lastReconciliationAttemptAtMs)
            .put("last_reconciliation_completed_at_ms", lastReconciliationCompletedAtMs)
            .put("last_reconciliation_result", lastReconciliationResult)
            .put("last_reconciliation_lock_wait_ms", lastReconciliationLockWaitMs)
            .put("last_reconciliation_lock_wait_failure", lastReconciliationLockWaitFailure ?: JSONObject.NULL)
            .put("last_rest_reconcile_ms", lastRestReconcileMs)
            .put("execution_state_contract", JSONObject()
                .put("version", 1)
                .put("state", stateName())
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
            check(updateCachedFuturesAccount(account)) {
                "Futures Testnet account returned invalid or incomplete equity/balance values"
            }
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
                val row = rows.optJSONObject(i)
                    ?: throw IllegalStateException("Malformed Binance Futures position row while validating credential deletion")
                val amount = row.optString("positionAmt").toDoubleOrNull()
                    ?: throw IllegalStateException("Malformed Binance Futures position amount while validating credential deletion")
                kotlin.math.abs(amount) > 0.0
            }
            val intents = futuresCampaignCoordinator.intentsSnapshot()
            val pending = (0 until intents.length()).any { i ->
                val intent = intents.optJSONObject(i) ?: return@any true
                isEntryCampaignReserved(intent)
            }
            require(!open && !pending) { "Close/reconcile every Futures position and pending intent before deleting credentials" }
        }
        stop()
        check(executionStore.writeRuntimeState("[]", "[]") &&
            executionStore.writeState("futures_campaign_intents_v2", "[]")) {
            "SQLite clear failed; persisted Futures state was preserved"
        }
        prefs.edit().remove("api_key").remove("api_secret")
            .remove("positions_json").remove("pending_entries_json")
            .remove("futures_campaign_intents_v2")
            .putBoolean("auto_run", false).commit()
        candidates = JSONArray()
        primaryCandles = emptyList()
        scanSymbols = mutableListOf()
        cachedFuturesPositionsSnapshot = null
        cachedFuturesPositionsSnapshotAtMs = 0L
        futuresPositionSnapshotLastAttemptMs = 0L
        futuresPositionSnapshotRefreshError = null
        liveUsdtBalance = 0.0
        liveUsdtBalanceKnown = false
        liveFuturesEquity = 0.0
        restAccountReady = false
        restAccountLastError = null
        futuresAccountSnapshotUpdatedAtMs = 0L
        futuresAccountSnapshotLastAttemptMs = 0L
        futuresAccountSnapshotRefreshError = null
        historyReady = false
        historyWarmupRunning = false
        historyState = "IDLE"
        historyLastError = null
        lastError = null
        clearReconcileRequired()
        return JSONObject().put("configured", false).put("cleared", true)
    }

    @Synchronized
    fun start(): JSONObject {
        require(key().isNotBlank() && secret().isNotBlank()) {
            "Configure Binance USD-M Futures Testnet credentials first"
        }
        if (running) return JSONObject().put("started", false).put("reason", "already_running")

        // Reconciliation is allowed to run under a persisted kill latch because it
        // only queries exchange state and performs explicitly owned protection cleanup.
        // A failed reconciliation must block entries, not prevent read-only market
        // streams and history from starting; otherwise the operator cannot diagnose or
        // repair the state that caused the latch.
        var startupBlocker: String? = null
        val reconciliationAttempt = runCatching {
            reconcileFuturesThroughDoor(
                "STARTUP_RECONCILIATION",
                allowStopped = true,
                allowKillLatched = true
            )
        }
        val reconciliation = reconciliationAttempt.getOrNull()
        if (reconciliation == null || !reconciliation.optBoolean("ok", false)) {
            reconcileRequired = true
            val persisted = persistSafetyFlag("reconcile_required", true)
            val failure = when {
                reconciliationAttempt.isFailure -> reconciliationAttempt.exceptionOrNull()?.message
                    ?: reconciliationAttempt.exceptionOrNull()?.javaClass?.simpleName
                    ?: "Futures reconciliation threw an unknown exception"
                reconciliation != null -> reconciliation.optString("blocker", "Futures reconciliation failed")
                    .ifBlank { "Futures reconciliation failed" }
                else -> "Futures reconciliation returned no result"
            }
            startupBlocker = if (persisted) failure
                else "$failure; additionally failed to persist reconciliation safety latch"
            lastError = startupBlocker
            scannerState = "BLOCKED_FUTURES_RECONCILIATION"
            scannerError = startupBlocker
            stateMachine.force(
                if (killLatched) TradingState.KILL_SWITCH_LATCHED else TradingState.RECONCILE_REQUIRED,
                startupBlocker ?: failure
            )
        } else if (!persistSafetyFlag("reconcile_required", false)) {
            reconcileRequired = true
            val blocker = "SQLite could not persist the cleared reconciliation latch"
            startupBlocker = blocker
            lastError = blocker
            scannerState = "BLOCKED_FUTURES_RECONCILIATION"
            scannerError = blocker
            stateMachine.force(
                if (killLatched) TradingState.KILL_SWITCH_LATCHED else TradingState.RECONCILE_REQUIRED,
                blocker
            )
        } else {
            reconcileRequired = false
        }

        // Account failure also blocks execution, but must not strand local history
        // warmup or market streams. Keep the runtime in safe-monitoring mode so the
        // cockpit can display the actual REST/account error and recovery can retry.
        try {
            val account = futuresCampaignCoordinator.accountSnapshot()
            if (!updateCachedFuturesAccount(account)) {
                restAccountReady = false
                val reason = restAccountLastError
                    ?: "Futures account balance/equity fields failed validation"
                startupBlocker = startupBlocker ?: reason
                scannerState = "BLOCKED_ACCOUNT"
                scannerError = reason
                lastError = reason
            }
        } catch (x: Exception) {
            restAccountReady = false
            restAccountLastError = x.message ?: x.javaClass.simpleName
            val reason = "Futures account request failed before Runtime start: $restAccountLastError"
            startupBlocker = startupBlocker ?: reason
            scannerState = "BLOCKED_ACCOUNT"
            scannerError = reason
            lastError = reason
        }

        // Explicit START persists the user's request to keep local monitoring alive.
        // The independent kill/reconciliation/account gates remain authoritative and
        // no failed startup check is interpreted as permission to place an entry.
        if (!prefs.edit().putBoolean("auto_run", true).commit()) {
            prefs.edit().putBoolean("auto_run", false).commit()
            val blocker = "Could not persist the explicit Runtime auto-run preference"
            lastError = blocker
            scannerState = "FAILED"
            scannerError = blocker
            stateMachine.force(TradingState.RECONCILE_REQUIRED, blocker)
            throw IllegalStateException(blocker)
        }
        stateMachine.force(
            when {
                killLatched -> TradingState.KILL_SWITCH_LATCHED
                reconcileRequired -> TradingState.RECONCILE_REQUIRED
                else -> when (futuresCampaignCoordinator.state) {
                    NativeFuturesCampaignCoordinator.State.ENTRY_PENDING -> TradingState.ENTRY_PENDING
                    NativeFuturesCampaignCoordinator.State.OPEN_PROTECTED -> TradingState.PROTECTED
                    NativeFuturesCampaignCoordinator.State.POSITION_UNPROTECTED,
                    NativeFuturesCampaignCoordinator.State.RECONCILE_REQUIRED -> TradingState.RECONCILE_REQUIRED
                    else -> TradingState.READY_FLAT
                }
            },
            startupBlocker ?: if (killLatched) {
                "Kill switch latched; runtime started for safe monitoring only"
            } else if (reconcileRequired) {
                "Futures reconciliation unresolved; runtime started for safe monitoring only"
            } else {
                "USD-M Futures account reconciliation checked on Android"
            }
        )
        val generation = ++runtimeGeneration
        running = true
        // Independent reconciliation/account gates block entries. Only an explicit
        // kill latch needs to force the pause flag during safe monitoring.
        paused = killLatched
        // The Futures Demo market stream is a live execution prerequisite. REST remains
        // authoritative for recovery and account/position reconciliation.
        marketSocket?.close(1000, "Restart USD-M Futures market stream")
        marketSocket = null
        marketSocketConnected = false
        marketSocketLastEventMs = 0L
        marketSocketStartedMs = 0L
        marketReconnectRequested = false
        publicSocket?.close(1000, "Restart USD-M Futures public stream")
        publicSocket = null
        publicSocketConnected = false
        publicSocketLastEventMs = 0L
        publicSocketStartedMs = 0L
        publicReconnectRequested = false
        startMarketDataStream()
        startPublicDataStream()
        // Restart the Futures private event stream; its onConnection callback
        // reconciles open orders, positions and protection before entries are unlocked.
        userStream.stop()
        userStreamConnected = false
        userStreamSyncRequired = true
        userStream.start()
        historyReady = false
        historyState = "LOADING"
        historyLastError = null
        scannerState = "WAITING_FOR_HISTORY"
        scannerError = null
        scanRunId = ""
        scanProgressSymbols = 0
        warmCoreHistoryAsync(generation)

        worker = Thread {
            try {
                while (running && generation == runtimeGeneration) {
                    try {
                        repairStaleMarketStreams()
                        // A connected private stream can still be unsynchronized after a
                        // transient REST/Execution-Door failure. Retry reconciliation with
                        // bounded backoff instead of leaving the runtime permanently blocked
                        // until another socket reconnect happens. The gate remains fail-closed.
                        if (userStreamConnected && userStreamSyncRequired && !futuresStreamReconcileRunning) {
                            scheduleFuturesStreamReconciliation("automatic sync retry")
                        }

                        // Keep managing existing exposure while entry prerequisites are
                        // degraded. manageNativeFuturesPositions reconciles with the exchange;
                        // it does not weaken the new-entry Execution Gate.
                        val campaignState = futuresCampaignCoordinator.state
                        val campaignNeedsMonitoring = campaignState in setOf(
                            NativeFuturesCampaignCoordinator.State.ENTRY_PENDING,
                            NativeFuturesCampaignCoordinator.State.OPEN_PROTECTED,
                            NativeFuturesCampaignCoordinator.State.POSITION_UNPROTECTED,
                            NativeFuturesCampaignCoordinator.State.EXIT_PENDING,
                            NativeFuturesCampaignCoordinator.State.RECONCILE_REQUIRED
                        )
                        val prerequisitesDegraded =
                            !historyReady || reconcileRequired || !restAccountReady ||
                                !marketDataFresh() || !marketWebSocketFresh() ||
                                !publicWebSocketFresh() || !userStreamConnected ||
                                userStreamSyncRequired
                        if (campaignNeedsMonitoring && (prerequisitesDegraded || killLatched || paused)) {
                            // Do not run a second account-wide reconciliation on the scanner
                            // worker. Route every monitor request through the single-flight
                            // scheduler; overlapping REST reconciliation used to leave the
                            // coordinator observable in RECONCILE_REQUIRED/in-progress and
                            // starve scans while another reconcile held the Execution Door.
                            scheduleNativePositionManagement()
                        }

                        if (!historyReady) {
                            scannerState = "WAITING_FOR_HISTORY"
                            scannerError = historyLastError
                            if (!historyWarmupRunning) warmCoreHistoryAsync(generation)
                        } else {
                            requestScan()
                        }
                    } catch (x: Exception) {
                        lastError = x.javaClass.simpleName + ": " + (x.message ?: "")
                    }
                    try { Thread.sleep(1_000L) } catch (_: InterruptedException) { break }
                }
            } finally {
                if (running && generation == runtimeGeneration) {
                    running = false
                    scannerState = "FAILED"
                    scannerError = "Runtime worker exited unexpectedly"
                    lastError = "Runtime worker exited unexpectedly; scheduling safe recovery"
                    stateMachine.force(TradingState.STOPPED, lastError ?: "Runtime worker exited unexpectedly")
                    scheduleUnexpectedWorkerRecovery(generation, lastError ?: "Runtime worker exited unexpectedly")
                }
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
            .put(
                "startup_state",
                if (killLatched || reconcileRequired || !restAccountReady) {
                    "SAFE_MONITORING_BLOCKED"
                } else {
                    "LOADING_CLOSED_FUTURES_HISTORY"
                }
            )
            .put("startup_blocker", startupBlocker ?: JSONObject.NULL)
            .put("kill_switch_latched", killLatched)
            .put("futures_reconciliation", reconciliation ?: JSONObject().put("ok", false).put("blocker", startupBlocker ?: "no result"))
            .put("execution_ready", executionReady())
            .put("execution_blockers", JSONArray(executionBlockers()))
            .put("interval_seconds", 10)
    }

    fun stop(): JSONObject {
        // Fence off new local entries before making the cancellation network call.
        ++runtimeGeneration
        running = false
        paused = false
        // Stopping the local worker must not leave an unfilled exchange-side
        // conditional entry armed to open a position after the phone stops.
        val cancellation = if (key().isNotBlank() && secret().isNotBlank()) {
            runCatching {
                withCampaignMutation(
                    symbol = "FUTURES_ACCOUNT",
                    purpose = "RUNTIME_STOP_CANCEL_PENDING",
                    allowPaused = true,
                    allowStopped = true,
                    allowKillLatched = true,
                    allowReconcileRequired = true,
                    waitForDoorMs = 50_000L
                ) {
                    futuresCampaignCoordinator.cancelPendingEntries("RUNTIME_STOP")
                }
            }.getOrElse { JSONObject().put("ok", false).put("error", it.message ?: it.javaClass.simpleName) }
        } else {
            JSONObject().put("ok", false).put("error", "Futures credentials unavailable; exchange cancellation cannot be confirmed")
        }
        if (!cancellation.optBoolean("ok", false) ||
            cancellation.optString("state") == NativeFuturesCampaignCoordinator.State.RECONCILE_REQUIRED.name
        ) {
            reconcileRequired = true
            persistSafetyFlag("reconcile_required", true)
            lastError = if (!cancellation.optBoolean("ok", false)) {
                "Stop completed locally, but pending Futures entry cancellation is unconfirmed: " +
                    cancellation.optString("error", cancellation.optString("blocker", "unknown cancellation state"))
            } else {
                "Pending entries canceled; existing Futures positions require reconciliation before restart"
            }
        }
        marketSocket?.close(1000, "Williams stopped")
        marketSocket = null
        marketSocketConnected = false
        marketSocketLastEventMs = 0L
        marketSocketStartedMs = 0L
        marketReconnectRequested = false
        publicSocket?.close(1000, "Williams stopped")
        publicSocket = null
        publicSocketConnected = false
        publicSocketLastEventMs = 0L
        publicSocketStartedMs = 0L
        publicReconnectRequested = false
        userStream.stop()
        userStreamConnected = false
        userStreamSyncRequired = true
        if (!prefs.edit().putBoolean("auto_run", false).commit()) {
            lastError = "Runtime stopped locally, but auto-run OFF could not be committed to preferences"
            persistSafetyFlag("reconcile_required", true)
            reconcileRequired = true
        }
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
            .put("pending_entry_cancellation", cancellation)
            .put("reconcile_required", reconcileRequired)
            .put("warning", lastError ?: JSONObject.NULL)
    }

    @Synchronized
    fun kill(): JSONObject {
        killLatched = true
        paused = true
        running = false
        val killLatchPersisted = persistSafetyFlag("kill_latched", true)
        if (!killLatchPersisted) {
            reconcileRequired = true
            persistSafetyFlag("reconcile_required", true)
        }
        prefs.edit().putBoolean("auto_run", false).commit()
        worker?.interrupt()
        worker = null
        marketSocket?.close(1000, "USD-M Futures kill switch")
        marketSocket = null
        marketSocketConnected = false
        publicSocket?.close(1000, "USD-M Futures kill switch")
        publicSocket = null
        publicSocketConnected = false
        userStream.stop()
        userStreamConnected = false
        userStreamSyncRequired = true

        val errors = mutableListOf<String>()
        if (!killLatchPersisted) {
            errors += "kill-switch latch could not be durably confirmed in SQLite"
        }

        // Classify exposure before canceling intents. Terminal campaign history must
        // never be revived as ownership evidence for an unrelated/manual live position.
        val ownershipReconciliation = runCatching {
            reconcileFuturesThroughDoor(
                "KILL_SWITCH_PRE_CLOSE_RECONCILIATION",
                allowStopped = true,
                allowKillLatched = true
            )
        }.getOrElse {
                JSONObject().put("ok", false).put("error", it.message ?: it.javaClass.simpleName)
            }
        val ownershipVerified = ownershipReconciliation.optBoolean("ok", false)
        if (!ownershipVerified) {
            errors += "pre-close Futures ownership/protection reconciliation failed; no position is auto-closed: " +
                ownershipReconciliation.optString(
                    "blocker",
                    ownershipReconciliation.optString("error", "unknown exchange state")
                )
            reconcileRequired = true
            persistSafetyFlag("reconcile_required", true)
        }

        val cancellation = runCatching {
            withCampaignMutation(
                symbol = "FUTURES_ACCOUNT",
                purpose = "KILL_SWITCH_CANCEL_PENDING",
                allowPaused = true,
                allowStopped = true,
                allowKillLatched = true,
                allowReconcileRequired = true,
                waitForDoorMs = 50_000L
            ) {
                futuresCampaignCoordinator.cancelPendingEntries("KILL_SWITCH")
            }
        }.getOrElse {
            JSONObject().put("ok", false).put("error", it.message ?: it.javaClass.simpleName)
        }
        if (!cancellation.optBoolean("ok", false)) {
            errors += "pending entry cancellation unresolved: " +
                cancellation.optString("blocker", cancellation.optString("error", "unknown exchange state"))
            reconcileRequired = true
            persistSafetyFlag("reconcile_required", true)
        }

        val rows = runCatching { futuresCampaignCoordinator.positionsSnapshot() }.getOrElse {
            errors += "Futures position query: " + (it.message ?: it.javaClass.simpleName)
            JSONArray()
        }
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i)
                ?: throw IllegalStateException("Malformed Binance Futures position row during kill switch")
            val symbol = row.optString("symbol").uppercase(Locale.US)
            if (symbol !in coreSymbols) continue
            val signedQty = row.optString("positionAmt").toDoubleOrNull()
                ?: throw IllegalStateException("Malformed Binance Futures position amount for $symbol during kill switch")
            if (signedQty == 0.0) continue
            val direction = if (signedQty > 0.0) NativeFuturesCampaignCoordinator.Direction.LONG
                else NativeFuturesCampaignCoordinator.Direction.SHORT
            if (!ownershipVerified ||
                !futuresCampaignCoordinator.isManagedOpenPosition(symbol, direction)
            ) {
                errors += "$symbol: live Futures position has no confirmed active Williams campaign/protective stop; position left untouched"
                reconcileRequired = true
                persistSafetyFlag("reconcile_required", true)
                continue
            }
            try {
                val rules = symbolFilters(symbol)
                val exchangeQtyText = row.optString("positionAmt")
                val closeQty = fullMarketCloseQuantity(exchangeQtyText, rules)
                val cid = ("W2K_" + symbol.take(8) + "_" + System.nanoTime().toString(16).takeLast(12)).take(36)
                val result = withCampaignMutation(
                    symbol = symbol,
                    purpose = "KILL_SWITCH_CLOSE",
                    allowPaused = true,
                    allowStopped = true,
                    allowKillLatched = true,
                    allowReconcileRequired = ownershipVerified,
                    waitForDoorMs = 10_000L
                ) {
                    futuresCampaignCoordinator.closePosition(
                        symbol, direction, closeQty, cid, "KILL_SWITCH"
                    )
                }
                if (!result.optBoolean("ok", false)) errors += "$symbol: " + result.optString("blocker", result.optString("error"))
            } catch (x: Exception) {
                errors += "$symbol: " + (x.message ?: x.javaClass.simpleName)
            }
        }
        val reconciliation = runCatching {
            reconcileFuturesThroughDoor(
                "KILL_SWITCH_FINAL_RECONCILIATION",
                allowStopped = true,
                allowKillLatched = true
            )
        }.getOrElse {
            JSONObject().put("ok", false).put("error", it.message ?: it.javaClass.simpleName)
        }
        stateMachine.force(TradingState.KILL_SWITCH_LATCHED, "USD-M Futures kill switch; restart blocked")
        val success = errors.isEmpty() && reconciliation.optBoolean("ok", false)
        lastError = if (success) "KILL_SWITCH: Futures positions reconciled; trading remains latched OFF"
            else "KILL_SWITCH: unresolved exchange close/reconciliation warning"
        return JSONObject().put("killed", true).put("latched", true)
            .put("exchange", "BINANCE_USDM_FUTURES_TESTNET").put("state", "KILL_SWITCH_LATCHED")
            .put("errors", JSONArray(errors)).put("ownership_reconciliation", ownershipReconciliation)
            .put("pending_entry_cancellation", cancellation)
            .put("futures_reconciliation", reconciliation)
    }

    fun resetKillSwitch(): JSONObject {
        require(!running) {
            "Stop the bot before resetting the kill switch."
        }
        val recovery = recover()
        require(recovery.optBoolean("recovered", false) && !reconcileRequired) {
            "Futures exchange reconciliation is still required."
        }
        val positions = futuresCampaignCoordinator.positionsSnapshot()
        val hasOpenPosition = (0 until positions.length()).any { i ->
            val row = positions.optJSONObject(i)
                ?: throw IllegalStateException("Malformed Binance Futures position row during kill-switch reset")
            val amount = row.optString("positionAmt").toDoubleOrNull()
                ?: throw IllegalStateException("Malformed Binance Futures position amount during kill-switch reset")
            kotlin.math.abs(amount) > 0.0
        }
        require(!hasOpenPosition) {
            "Kill switch reset refused: Binance Futures still reports an open position."
        }
        val intents = futuresCampaignCoordinator.intentsSnapshot()
        val hasPendingOrder = (0 until intents.length()).any { i ->
            val row = intents.optJSONObject(i) ?: return@any true
            isEntryCampaignReserved(row) ||
                row.optString("exchange_status").uppercase(Locale.US) in setOf(
                    "SUBMISSION_UNKNOWN", "CANCEL_SUBMISSION_UNKNOWN", "EXIT_PENDING",
                    "EXIT_TERMINAL_POSITION_OPEN"
                )
        }
        require(!hasPendingOrder && futuresCampaignCoordinator.state == NativeFuturesCampaignCoordinator.State.READY) {
            "Kill switch reset refused: pending or unresolved Futures intent remains."
        }
        check(persistSafetyFlag("kill_latched", false)) {
            "SQLite could not persist kill-switch reset; kill switch remains latched"
        }
        killLatched = false
        paused = false
        stateMachine.force(TradingState.READY_FLAT, "Futures account flat and kill switch reset")
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

    /** Explicit operator exit; still uses the Futures Execution Door and exchange confirmation. */
    @Synchronized
    fun sell(symbol: String): JSONObject = sellFuturesPosition(symbol)

    /**
     * Testnet-only directional override. It does not bypass risk, reconciliation,
     * account, freshness, session, exchange filters, or structural protection gates.
     * Automatic signal entries are suspended while this mode is enabled so the
     * explicitly chosen side cannot race an autonomous opposite-side entry.
     */
    @Synchronized
    fun testnetForceMode(request: JSONObject): JSONObject {
        require(baseUrl == "https://demo-fapi.binance.com") {
            "Forced trading is available only on the pinned USD-M Futures Demo endpoint"
        }
        require(request.optBoolean("acknowledge", false)) {
            "Explicit acknowledgement is required for the Testnet directional override"
        }
        val enabled = request.optBoolean("enabled", false)
        if (!enabled) {
            forceTestnetMode = false
            val preferenceSaved = prefs.edit()
                .putBoolean("force_testnet_mode", false)
                .putBoolean("auto_run", false)
                .commit()
            val databaseSaved = executionStore.writeState("futures_testnet_force_mode", "false")
            paused = true
            lastError = if (preferenceSaved && databaseSaved) {
                "TESTNET_FORCE_MODE_DISABLED: Runtime paused; daily-risk baseline retained"
            } else {
                "TESTNET_FORCE_MODE_DISABLE_PERSISTENCE_FAILED: Runtime paused; restart requires reconciliation"
            }
            if (!preferenceSaved || !databaseSaved) {
                reconcileRequired = true
                persistSafetyFlag("reconcile_required", true)
            }
            return JSONObject()
                .put("enabled", false)
                .put("demo_only", true)
                .put("paused", true)
                .put("daily_loss_limit_pct", 0.03)
                .put("baseline_reset", false)
                .put("message", lastError)
        }

        require(running) { "Start the Futures Demo Runtime before enabling the directional override" }
        require(!killLatched && !reconcileRequired && !equityCircuitBreaker.isTripped()) {
            "KILL_SWITCH_LATCHED, RECONCILE_REQUIRED, or equity circuit breaker prevents forced trading"
        }
        require(historyReady && marketDataFresh() && marketWebSocketFresh() &&
            publicWebSocketFresh() && userStreamConnected && !userStreamSyncRequired
        ) { "Fresh Futures history and synchronized market/public/user streams are required" }
        require(restAccountReady && accountFundedForEntry()) {
            "A valid funded Futures Demo account snapshot is required"
        }
        require(campaignCoordinatorReadyForEntry()) {
            "Futures positions/orders/protection must be reconciled before enabling the override"
        }

        val previousError = lastError
        val dailyRiskAllowed = runCatching { checkFuturesDailyLoss() }.getOrElse {
            lastError = "FUTURES_DAILY_LOSS_CHECK_FAILED: " +
                (it.message ?: it.javaClass.simpleName)
            false
        }
        require(dailyRiskAllowed) {
            lastError ?: "Daily Futures risk limit has not been verified; forced trading remains blocked"
        }
        val baselineOnlyPause = paused &&
            previousError?.startsWith("FUTURES_DAILY_BASELINE_UNKNOWN") == true
        require(!paused || baselineOnlyPause) {
            "Runtime is paused for a reason other than the recoverable missing daily baseline; inspect Diagnostics first"
        }
        if (baselineOnlyPause) {
            require(prefs.edit().putBoolean("auto_run", true).commit()) {
                "Could not persist Runtime resume after daily-baseline reconstruction"
            }
            paused = false
            lastError = null
        }

        require(executionGate.tryReserve(FUTURES_EXECUTION_DOOR_KEY)) {
            "Futures Execution Door is busy; retry after the in-flight exchange operation is reconciled"
        }
        try {
            require(executionReady()) {
                "Testnet override cannot be armed: " + executionBlockers().joinToString(", ")
            }
            forceTestnetMode = true
            val preferenceSaved = prefs.edit()
                .putBoolean("force_testnet_mode", true)
                .putBoolean("auto_run", true)
                .commit()
            val databaseSaved = executionStore.writeState("futures_testnet_force_mode", "true")
            if (!preferenceSaved || !databaseSaved) {
                forceTestnetMode = false
                paused = true
                prefs.edit().putBoolean("auto_run", false).putBoolean("force_testnet_mode", false).commit()
                executionStore.writeState("futures_testnet_force_mode", "false")
                lastError = "TESTNET_FORCE_MODE_PERSISTENCE_FAILED: forced entries remain disabled"
                throw IllegalStateException(lastError)
            }
        } finally {
            executionGate.release(FUTURES_EXECUTION_DOOR_KEY)
        }
        lastError = null
        return JSONObject()
            .put("enabled", true)
            .put("demo_only", true)
            .put("automatic_entries_suspended", true)
            .put("daily_loss_limit_pct", 0.03)
            .put("daily_baseline_source", "UTC_DAY_RECONCILED_FROM_FUTURES_INCOME")
            .put("baseline_reset", false)
            .put("message", "Testnet force mode armed; select LONG or SHORT for an H1 conditional entry. Mandatory risk/protection gates remain active.")
    }

    /**
     * Submit one operator-directed conditional H1 breakout on Futures Demo.
     * The direction may override the current strategy hypothesis, but never the
     * account-wide Execution Door, daily-loss cap, exchange filters, or structural SL.
     */
    @Synchronized
    fun forceTestnetEntry(request: JSONObject): JSONObject {
        require(baseUrl == "https://demo-fapi.binance.com" && forceTestnetMode) {
            "Enable the explicit Futures Demo force mode first"
        }
        require(request.optBoolean("acknowledge", false)) {
            "Explicit acknowledgement is required before submitting the forced directional entry"
        }
        val symbol = request.optString("symbol").trim().uppercase(Locale.US)
        require(symbol in coreSymbols) { "Symbol is outside the approved ten-contract Futures universe" }
        val directionText = request.optString("direction").trim().uppercase(Locale.US)
        val direction = when (directionText) {
            "LONG" -> NativeFuturesCampaignCoordinator.Direction.LONG
            "SHORT" -> NativeFuturesCampaignCoordinator.Direction.SHORT
            else -> throw IllegalArgumentException("Direction must be LONG or SHORT")
        }
        require(executionReady()) {
            "Forced entry blocked by the mandatory Execution Gate: " + executionBlockers().joinToString(", ")
        }
        require(checkFuturesDailyLoss()) {
            lastError ?: "Daily Futures risk limit is unknown or breached"
        }
        require(executionReady()) {
            "Forced entry blocked after daily-risk refresh: " + executionBlockers().joinToString(", ")
        }

        val book = futuresMarketData.topOfBook(symbol)
        val liveSpreadPct = (book.second - book.first) / book.first
        require(
            book.first.isFinite() && book.first > 0.0 &&
                book.second.isFinite() && book.second >= book.first &&
                liveSpreadPct.isFinite() && liveSpreadPct in 0.0..maxSpreadPct
        ) {
            "FUTURES_ENTRY_SPREAD_BLOCKED: $symbol live spread=$liveSpreadPct"
        }
        val nowMs = futuresMarketData.exchangeNowMs()
        require(isWithinWilliamsEntrySessionUtc(nowMs)) {
            "Williams/Testnet entry window is closed; conditional entries are permitted only 08:00–20:00 UTC"
        }
        val h1BarMs = frameSeconds("1h") * 1000L
        val h1 = fetchCandles(symbol, "1h", 180)
            .filter { it.t > 0L && it.t + h1BarMs <= nowMs }
        require(h1.size >= 150) {
            "Forced entry requires at least 150 closed H1 candles; found ${h1.size}"
        }
        val rules = symbolFilters(symbol)
        val levels = forcedH1EntryLevels(
            direction = direction.name,
            highs = h1.takeLast(5).map { it.h },
            lows = h1.takeLast(5).map { it.l },
            tickSize = rules.tick
        ) ?: throw IllegalStateException("Could not derive valid structural H1 trigger/stop for $symbol")
        val trigger = if (direction == NativeFuturesCampaignCoordinator.Direction.LONG) {
            normalizeTriggerPrice(levels.triggerPrice, rules.tick)
        } else {
            normalizePrice(levels.triggerPrice, rules.tick)
        }
        val stop = if (direction == NativeFuturesCampaignCoordinator.Direction.LONG) {
            normalizePrice(levels.protectiveStop, rules.tick)
        } else {
            normalizeTriggerPrice(levels.protectiveStop, rules.tick)
        }
        val contract = futuresCampaignCoordinator.contractPrice(symbol)
        require(
            if (direction == NativeFuturesCampaignCoordinator.Direction.LONG) contract < trigger
            else contract > trigger
        ) {
            "H1_STRUCTURAL_TRIGGER_ALREADY_CROSSED: contract=$contract, trigger=$trigger; no market-order substitution"
        }
        require(
            if (direction == NativeFuturesCampaignCoordinator.Direction.LONG) stop < trigger
            else stop > trigger
        ) { "Structural stop is on the wrong side of the forced H1 trigger" }

        val account = futuresCampaignCoordinator.accountSnapshot()
        val parsed = parseFuturesAccountSnapshot(account)
            ?: throw IllegalStateException("Fresh authenticated USD-M Futures account snapshot is invalid")
        require(updateCachedFuturesAccount(account) && futuresAccountFundedForEntry(parsed.availableUsdt, parsed.equityUsdt)) {
            "Positive available USDT and equity are required for a forced entry"
        }
        val intents = futuresCampaignCoordinator.intentsSnapshot()
        val riskUsed = (0 until intents.length()).mapNotNull { intents.optJSONObject(it) }.sumOf { intent ->
            if (intent.optString("intent_role", "ENTRY") == "ENTRY" && isEntryCampaignReserved(intent)) {
                intent.optDouble("risk_pct", Double.NaN).takeIf { it.isFinite() && it > 0.0 }
                    ?: maxTotalRiskPct
            } else 0.0
        }
        val riskPct = min(campaignInitialRiskPct, min(maxRiskPerTradePct, maxTotalRiskPct - riskUsed))
        require(riskPct > 0.0) { "Aggregate Futures campaign risk budget is exhausted or unknown" }

        val stopFraction = kotlin.math.abs(trigger - stop) / trigger
        val effectiveLoss = stopFraction + 2.0 * feeBufferPerSidePct + maxSlippagePct
        require(stopFraction.isFinite() && stopFraction > 0.0 && effectiveLoss.isFinite()) {
            "Forced-entry structural stop/risk calculation is invalid"
        }
        val notional = min(
            parsed.equityUsdt * 0.25,
            min(parsed.availableUsdt * 2.0 * 0.95, parsed.equityUsdt * riskPct / effectiveLoss)
        )
        val marketLotEnabled = rules.marketStep.isFinite() && rules.marketStep > 0.0
        val quantityStep = ExecutionMath.commonQuantityStep(rules.step, rules.marketStep)
        val minQuantity = if (marketLotEnabled) max(rules.minQty, rules.marketMinQty) else rules.minQty
        val maxQuantity = if (marketLotEnabled) min(rules.maxQty, rules.marketMaxQty) else rules.maxQty
        val qty = floorStep(notional / trigger, quantityStep)
        require(
            qty.isFinite() && qty >= minQuantity && qty <= maxQuantity &&
                ExecutionMath.isMultiple(qty, rules.step) &&
                (!marketLotEnabled || ExecutionMath.isMultiple(qty, rules.marketStep)) &&
                qty * trigger >= rules.minNotional &&
                qty * trigger <= rules.maxNotional
        ) {
            "FUTURES_QUANTITY_FILTER_BLOCKED: forced quantity fails LOT_SIZE/MARKET_LOT_SIZE/notional filters"
        }
        val clientId = ("W2F_" + symbol.take(7) + "_" + direction.name.take(1) + "_" + nowMs.toString(36)).take(36)
        val plan = NativeFuturesCampaignCoordinator.EntryPlan(
            symbol = symbol,
            direction = direction,
            kind = NativeFuturesCampaignCoordinator.EntryKind.STOP,
            quantity = fmtQty(qty, rules.decimals),
            entryPrice = fmtPrice(trigger, rules.tick),
            protectiveStop = fmtPrice(stop, rules.tick),
            clientId = clientId,
            riskPct = riskPct
        )
        val result = withCampaignMutation(
            symbol = symbol,
            purpose = "TESTNET_FORCED_DIRECTION_ENTRY"
        ) {
            require(forceTestnetMode && executionReady()) {
                "Forced-mode state or mandatory Execution Gate changed before submission"
            }
            require(checkFuturesDailyLoss()) {
                lastError ?: "Daily loss limit could not be verified at submission"
            }
            val submitMark = futuresMarketData.markPrice(symbol)
            require(
                if (direction == NativeFuturesCampaignCoordinator.Direction.LONG) submitMark < trigger
                else submitMark > trigger
            ) { "H1_STRUCTURAL_TRIGGER_ALREADY_CROSSED at final preflight; no market-order substitution" }
            futuresCampaignCoordinator.submitPendingEntry(plan)
        }
        lastOrder = result
        if (!result.optBoolean("accepted", false)) {
            lastError = result.optString("blocker", result.optString("error", "Forced conditional entry rejected"))
            if (futuresCampaignCoordinator.state == NativeFuturesCampaignCoordinator.State.RECONCILE_REQUIRED) {
                reconcileRequired = true
                persistSafetyFlag("reconcile_required", true)
                stateMachine.force(TradingState.RECONCILE_REQUIRED, lastError ?: "Forced Futures submission unresolved")
            }
        } else {
            lastRestReconcileMs = 0L
            stateMachine.force(
                TradingState.ENTRY_PENDING,
                "Operator-selected Futures Demo $direction conditional H1 entry submitted; awaiting exchange fill/reconciliation"
            )
        }
        return JSONObject()
            .put("accepted", result.optBoolean("accepted", false))
            .put("safe_rejection", result.optBoolean("safe_rejection", false))
            .put("demo_only", true)
            .put("manual_direction_override", true)
            .put("automatic_entries_suspended", true)
            .put("symbol", symbol)
            .put("direction", direction.name)
            .put("entry_kind", "STOP")
            .put("quantity", plan.quantity)
            .put("trigger_price", plan.entryPrice)
            .put("protective_stop", plan.protectiveStop)
            .put("spread_pct", liveSpreadPct)
            .put("risk_pct", riskPct)
            .put("max_daily_loss_pct", 0.03)
            .put("blocker", result.optString("blocker", JSONObject.NULL.toString()))
            .put("message", if (result.optBoolean("accepted", false)) {
                "Forced Testnet conditional entry accepted; exchange reconciliation and stop confirmation are still required"
            } else {
                result.optString("blocker", "Forced conditional entry rejected before execution")
            })
    }

    @Synchronized
    fun recover(): JSONObject {
        require(key().isNotBlank() && secret().isNotBlank()) {
            "Configure Binance USD-M Futures Testnet credentials first"
        }
        val reconciliationAttempt = runCatching {
            reconcileFuturesThroughDoor(
                "RECOVERY_RECONCILIATION",
                allowStopped = true,
                allowKillLatched = true
            )
        }
        val result = reconciliationAttempt.getOrNull()
        if (result == null) {
            reconcileRequired = true
            val persisted = persistSafetyFlag("reconcile_required", true)
            prefs.edit().putBoolean("auto_run", false).commit()
            val reason = reconciliationAttempt.exceptionOrNull()?.message
                ?: reconciliationAttempt.exceptionOrNull()?.javaClass?.simpleName
                ?: "Futures reconciliation returned no result"
            lastError = if (persisted) "Futures reconciliation failed: $reason"
                else "Futures reconciliation failed: $reason; safety latch persistence failed"
            scannerState = "BLOCKED_FUTURES_RECONCILIATION"
            scannerError = lastError
            stateMachine.force(TradingState.RECONCILE_REQUIRED, lastError ?: reason)
            return JSONObject().put("recovered", false)
                .put("exchange", "BINANCE_USDM_FUTURES_TESTNET")
                .put("execution_enabled", false)
                .put("blocker", lastError ?: reason)
                .put("futures_reconciliation", JSONObject().put("ok", false).put("blocker", lastError ?: reason))
                .put("state", "RECONCILE_REQUIRED")
        }
        val ok = result.optBoolean("ok", false)
        reconcileRequired = !ok
        if (!persistSafetyFlag("reconcile_required", !ok)) {
            // Persistence failure is itself a reconciliation blocker. Never let
            // an in-memory success reopen the gate if the durable latch was not saved.
            reconcileRequired = true
            val blocker = "SQLite could not persist recovery reconciliation state"
            lastError = blocker
            stateMachine.force(TradingState.RECONCILE_REQUIRED, blocker)
            return JSONObject().put("recovered", false)
                .put("exchange", "BINANCE_USDM_FUTURES_TESTNET")
                .put("execution_enabled", false)
                .put("blocker", blocker)
                .put("futures_reconciliation", result)
                .put("state", futuresCampaignCoordinator.state.name)
        }
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

    private fun getBody(path: String): String {
        // Public market data is strictly allow-listed and always goes to USD-M Futures Demo.
        // Spot-shaped aliases are rejected instead of silently translated.
        val futuresPath = normalizeFuturesPublicPath(path)
        val requestUrl = "https://demo-fapi.binance.com" + futuresPath
        // GET market-data calls are idempotent. Testnet can transiently return
        // 5xx/502 responses, so retry only safe reads; never retry mutations.
        var lastCode = 0
        var lastBody = "{}"
        val delays = longArrayOf(500L, 1000L, 2000L)
        repeat(3) { attempt ->
            try {
                rateGuard.beforeRequest()
                val request = Request.Builder()
                    .url(requestUrl)
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
        if (lastCode > 0) {
            error("Binance Futures REST HTTP " + lastCode + ": " + lastBody)
        }
        error("Binance Futures REST transport failed after 3 attempts: " + lastBody)
    }

    private fun wsStreamUrl(): String =
        // Market endpoint carries regular feeds only; depth/bookTicker use /public.
        "wss://demo-fstream.binance.com/market/stream?streams=" +
            coreSymbols.flatMap { symbol ->
                startupFrames.map { frame ->
                    symbol.lowercase(Locale.US) + "@kline_" + frame
                } + listOf(symbol.lowercase(Locale.US) + "@aggTrade")
            }.joinToString("/")

    private fun publicStreamUrl(): String =
        "wss://demo-fstream.binance.com/public/stream?streams=" +
            coreSymbols.flatMap { symbol ->
                listOf(
                    symbol.lowercase(Locale.US) + "@bookTicker",
                    symbol.lowercase(Locale.US) + "@depth@100ms"
                )
            }.joinToString("/")

    @Synchronized
    private fun startMarketDataStream() {
        if (marketSocket != null) return
        marketSocketLastEventMs = 0L
        marketSocketStartedMs = System.currentTimeMillis()
        marketReconnectRequested = false
        val request = Request.Builder().url(wsStreamUrl()).build()
        marketSocket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (marketSocket !== webSocket) {
                    webSocket.close(1000, "superseded market stream")
                    return
                }
                marketSocketConnected = true
                marketSocketStartedMs = System.currentTimeMillis()
                lastError = null
                scheduleMarketReconnect(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (marketSocket !== webSocket) return
                marketSocketLastEventMs = System.currentTimeMillis()
                runCatching { consumeMarketStream(JSONObject(text), publicStream = false) }
                    .onFailure { lastError = "WS market: " + (it.message ?: it.javaClass.simpleName) }
                runCatching {
                    val data = JSONObject(text).optJSONObject("data")
                    if (data?.optString("e") == "serverShutdown" && marketSocket === webSocket) {
                        marketSocketConnected = false
                        webSocket.close(1000, "serverShutdown")
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (marketSocket === webSocket) marketSocketConnected = false
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (marketSocket === webSocket) {
                    marketSocketConnected = false
                    marketSocket = null
                    marketSocketLastEventMs = 0L
                    marketSocketStartedMs = 0L
                    marketReconnectRequested = false
                    if (running) scheduleReconnect("williams-market-reconnect", 1500L) { startMarketDataStream() }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (marketSocket === webSocket) {
                    marketSocketConnected = false
                    marketSocket = null
                    marketSocketLastEventMs = 0L
                    marketSocketStartedMs = 0L
                    marketReconnectRequested = false
                    lastError = "WS market: " + (t.message ?: t.javaClass.simpleName)
                    if (running) scheduleReconnect("williams-market-retry", 2500L) { startMarketDataStream() }
                }
            }
        })
    }

    @Synchronized
    private fun startPublicDataStream() {
        if (publicSocket != null) return
        publicSocketLastEventMs = 0L
        publicSocketStartedMs = System.currentTimeMillis()
        publicReconnectRequested = false
        val request = Request.Builder().url(publicStreamUrl()).build()
        publicSocket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (publicSocket !== webSocket) {
                    webSocket.close(1000, "superseded public stream")
                    return
                }
                publicSocketConnected = true
                publicSocketStartedMs = System.currentTimeMillis()
                Thread {
                    warmOrderBookSnapshots()
                }.apply {
                    isDaemon = true
                    name = "williams-l2-bootstrap"
                    start()
                }
                scheduleMarketReconnect(webSocket, publicStream = true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (publicSocket !== webSocket) return
                publicSocketLastEventMs = System.currentTimeMillis()
                runCatching { consumeMarketStream(JSONObject(text), publicStream = true) }
                    .onFailure { lastError = "WS public: " + (it.message ?: it.javaClass.simpleName) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (publicSocket === webSocket) publicSocketConnected = false
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (publicSocket === webSocket) {
                    publicSocketConnected = false
                    publicSocket = null
                    publicSocketLastEventMs = 0L
                    publicSocketStartedMs = 0L
                    publicReconnectRequested = false
                    if (running) scheduleReconnect("williams-public-reconnect", 1500L) { startPublicDataStream() }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (publicSocket === webSocket) {
                    publicSocketConnected = false
                    publicSocket = null
                    publicSocketLastEventMs = 0L
                    publicSocketStartedMs = 0L
                    publicReconnectRequested = false
                    lastError = "WS public: " + (t.message ?: t.javaClass.simpleName)
                    if (running) scheduleReconnect("williams-public-retry", 2500L) { startPublicDataStream() }
                }
            }
        })
    }

    private fun scheduleUnexpectedWorkerRecovery(expectedGeneration: Long, reason: String) {
        // Kill Switch blocks trading, not recovery of the monitor itself.
        if (!prefs.getBoolean("auto_run", false)) return
        Thread {
            try {
                Thread.sleep(1_500L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@Thread
            }
            if (expectedGeneration != runtimeGeneration || running ||
                !prefs.getBoolean("auto_run", false)
            ) return@Thread

            val nowMs = System.currentTimeMillis()
            val allowed = synchronized(this) {
                if (workerRecoveryWindowStartedMs == 0L ||
                    nowMs - workerRecoveryWindowStartedMs >= 15L * 60L * 1000L ||
                    nowMs < workerRecoveryWindowStartedMs
                ) {
                    workerRecoveryWindowStartedMs = nowMs
                    workerRecoveryAttemptsInWindow = 0
                }
                if (workerRecoveryAttemptsInWindow >= 3) false
                else {
                    workerRecoveryAttemptsInWindow++
                    true
                }
            }
            if (!allowed) {
                prefs.edit().putBoolean("auto_run", false).commit()
                scannerState = "FAILED"
                scannerError = "Automatic runtime recovery stopped after three failures in 15 minutes"
                lastError = scannerError
                return@Thread
            }

            lastSelfHealMs = nowMs
            selfHealCount++
            val attempted = runCatching { start() }
            val response = attempted.getOrNull()
            val blocker = when {
                attempted.isFailure -> attempted.exceptionOrNull()?.message
                    ?: attempted.exceptionOrNull()?.javaClass?.simpleName
                response?.optBoolean("started", false) == true -> null
                response?.optString("reason") == "already_running" -> null
                else -> response?.optString("blocker")
                    ?: response?.optJSONObject("futures_reconciliation")?.optString("blocker")
                    ?: response?.optString("state")
                    ?: "Safe Runtime restart was rejected"
            }
            if (blocker != null) {
                prefs.edit().putBoolean("auto_run", false).commit()
                scannerState = "BLOCKED_RUNTIME_RECOVERY"
                scannerError = blocker
                lastError = "Runtime recovery blocked (" + reason + "): " + blocker
            }
        }.apply {
            isDaemon = true
            name = "williams-futures-runtime-recovery"
            start()
        }
    }

    private fun scheduleReconnect(name: String, delayMs: Long, action: () -> Unit) {
        Thread {
            try {
                Thread.sleep(delayMs)
                if (running) action()
            } catch (_: InterruptedException) {
            }
        }.apply {
            isDaemon = true
            this.name = name
            start()
        }
    }

    private fun scheduleMarketReconnect(webSocket: WebSocket, publicStream: Boolean = false) {
        Thread {
            try {
                Thread.sleep(23L * 60L * 60L * 1000L)
                if (running && (if (publicStream) publicSocket === webSocket else marketSocket === webSocket)) {
                    webSocket.close(1000, "planned_24h_reconnect")
                }
            } catch (_: InterruptedException) {
            }
        }.apply {
            isDaemon = true
            name = if (publicStream) "williams-public-planned-reconnect" else "williams-market-planned-reconnect"
            start()
        }
    }

    @Synchronized
    private fun repairStaleMarketStreams() {
        if (!running) return
        val nowMs = System.currentTimeMillis()

        val observedMarket = marketSocket
        if (streamNeedsReconnect(
                socketPresent = observedMarket != null,
                lastEventMs = marketSocketLastEventMs,
                startedAtMs = marketSocketStartedMs,
                nowMs = nowMs
            ) && !marketReconnectRequested
        ) {
            marketReconnectRequested = true
            observedMarket?.close(1001, "stale USD-M Futures market stream")
            scheduleReconnect("williams-market-stale-reconnect", 1_000L) {
                synchronized(this) {
                    if (marketSocket === observedMarket) {
                        marketSocket = null
                        marketSocketConnected = false
                        marketSocketLastEventMs = 0L
                        marketSocketStartedMs = 0L
                        startMarketDataStream()
                    }
                    marketReconnectRequested = false
                }
            }
        }

        val observedPublic = publicSocket
        if (streamNeedsReconnect(
                socketPresent = observedPublic != null,
                lastEventMs = publicSocketLastEventMs,
                startedAtMs = publicSocketStartedMs,
                nowMs = nowMs
            ) && !publicReconnectRequested
        ) {
            publicReconnectRequested = true
            observedPublic?.close(1001, "stale USD-M Futures public stream")
            scheduleReconnect("williams-public-stale-reconnect", 1_000L) {
                synchronized(this) {
                    if (publicSocket === observedPublic) {
                        publicSocket = null
                        publicSocketConnected = false
                        publicSocketLastEventMs = 0L
                        publicSocketStartedMs = 0L
                        startPublicDataStream()
                    }
                    publicReconnectRequested = false
                }
            }
        }
    }

    private fun consumeMarketStream(envelope: JSONObject, publicStream: Boolean = false) {
        val eventAt = System.currentTimeMillis()
        if (publicStream) publicSocketLastEventMs = eventAt else marketSocketLastEventMs = eventAt
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
                livePriceUpdatedAtMs[symbol] = eventAt
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
            getBody("/fapi/v1/depth?symbol=" + symbol + "&limit=100")
        )
        orderBookCache.seed(symbol, book)
    }

    private fun consumeAggTrade(data: JSONObject) {
        val symbol = data.optString("s").uppercase(Locale.US)
        if (symbol !in coreSymbols) return
        val price = data.optString("p").toDoubleOrNull() ?: return
        if (!price.isFinite() || price <= 0.0) return
        val receivedAtMs = System.currentTimeMillis()
        lastTradePrices[symbol] = price
        lastTradeUpdatedAtMs[symbol] = receivedAtMs
        val qty = data.optString("q").toDoubleOrNull() ?: return
        // Stream recency uses local receive time. Exchange trade timestamps can be
        // offset from the phone clock and must not prematurely expire the 5s flow window.
        val time = receivedAtMs
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
        // Chart may display a forming kline, but wave labels must ONLY use
        // last exchange-confirmed closes. The same evidence is exposed to the
        // scanner and the operator's switchable per-pair chart.
        val waveEvidence = waveFrameEvidence(frame, candles, futuresMarketData.exchangeNowMs())
        return JSONObject()
            .put("symbol", symbol).put("interval", frame).put("ready", true)
            .put("candle_count", candles.size)
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
            .put("wave", if (waveEvidence.primary == "UNKNOWN") "$frame:?"
                else "$frame:" + waveEvidence.primary)
            .put("wave_context", waveEvidence.asJson())
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
                                val requiredCount = minStartupHistoryCandles(frame)
                                val requestedCount = startupHistoryLimit(frame)
                                val result = runCatching {
                                    fetchCandles(symbol, frame, requestedCount)
                                }
                                if (result.isSuccess) {
                                    recent = result.getOrDefault(emptyList())
                                    if (recent.size >= requiredCount) break
                                    failure = IllegalStateException(
                                        "received " + recent.size + " closed candles; need " + requiredCount
                                    )
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
                            val requiredCount = minStartupHistoryCandles(frame)
                            val requestedCount = startupHistoryLimit(frame)
                            val persistedCount = historyStore.count(symbol, frame).toInt()
                            val ok = recent.size >= requiredCount &&
                                persistedCount >= requiredCount
                            if (ok) {
                                val cacheKey = symbol + ":" + frame
                                liveCandleCache[cacheKey] =
                                    recent.takeLast(requestedCount).toMutableList()
                                candleCache[symbol + ":" + frame + ":" + requestedCount] =
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

    private fun candleSeriesCurrent(candles: List<CandleN>, frame: String, nowMs: Long = futuresMarketData.exchangeNowMs()): Boolean {
        val last = candles.lastOrNull() ?: return false
        // CandleN.t is the candle open time. Use native exchange boundaries so a
        // 28/29/31-day month cannot leave the chart/history cache stale for days.
        return futuresMarketData.isLastClosedCandleCurrent(last.t, frame, nowMs)
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
                // WS caches also contain the currently forming kline (x=false).
                // It is valid for display, never for a trading decision. Strategy
                // reads are restricted to bars closed according to Binance server time.
                val exchangeNowMs = futuresMarketData.exchangeNowMs()
                val closed = live.filter {
                    futuresMarketData.isKlineOpenTimeClosed(it.t, frame, exchangeNowMs)
                }
                if (closed.size >= limit && candleSeriesCurrent(closed, frame, exchangeNowMs)) {
                    // A WebSocket warm cache is not proof that the SQLite history
                    // journal is populated. Startup readiness checks both the live
                    // closed-bar count and persisted count; returning here without
                    // persisting made a healthy stream keep the scanner stuck in
                    // WAITING_FOR_HISTORY on a fresh install.
                    val toStored: (CandleN) -> MarketHistoryStore.Candle = { candle ->
                        MarketHistoryStore.Candle(
                            openTime = candle.t,
                            closeTime = futuresMarketData.klineCloseTimeMs(candle.t, frame),
                            open = candle.o,
                            high = candle.h,
                            low = candle.l,
                            close = candle.c,
                            volume = candle.v
                        )
                    }
                    // This initial seed is deliberately used only when SQLite lacks enough
                    // history. It also keeps the repository's closed-candle persistence contract explicit.
                    val initialRows = closed.takeLast(limit).map(toStored)
                    val latestClosedTime = closed.lastOrNull()?.t ?: 0L
                    val persistedNewest = historyStore.newest(normalizedSymbol, frame)
                    val persistedCount = historyStore.count(normalizedSymbol, frame)
                    // Do not rewrite 150-180 candles into SQLite on every scan tick.
                    // Persist the initial warm cache once, then only newly closed bars.
                    if (persistedCount < limit.toLong() || persistedNewest < latestClosedTime) {
                        val rowsToPersist = if (persistedCount < limit.toLong() || persistedNewest <= 0L) {
                            initialRows
                        } else {
                            closed.filter { it.t > persistedNewest }.map(toStored)
                        }
                        if (rowsToPersist.isNotEmpty()) {
                            historyStore.upsertBatch(
                                normalizedSymbol,
                                frame,
                                rowsToPersist,
                                complete = false
                            )
                        }
                    }
                    return closed.takeLast(limit)
                }
            }
        }

        val cacheKey = normalizedSymbol + ":" + frame + ":" + limit
        val persistent = historyStore.loadRecent(normalizedSymbol, frame, limit)
        val persistentCandles = persistent.map {
            CandleN(it.openTime, it.open, it.high, it.low, it.close, it.volume)
        }
        if (persistent.size >= limit && candleSeriesCurrent(persistentCandles, frame)) {
            candleCache[cacheKey] = System.currentTimeMillis() to persistentCandles
            liveCandleCache[liveKey] = persistentCandles.toMutableList()
            return persistentCandles
        }

        val cached = candleCache[cacheKey]
        val now = System.currentTimeMillis()
        if (cached != null &&
            cached.second.size >= limit &&
            now - cached.first < scanCacheTtlMs &&
            candleSeriesCurrent(cached.second, frame, futuresMarketData.exchangeNowMs())
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
        val fetchedAt = futuresMarketData.exchangeNowMs()

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

        val exchangeNowMs = futuresMarketData.exchangeNowMs()
        return out
            .filter { it.t + bucketMs <= exchangeNowMs }
            .takeLast(limit)
    }

    private fun fetchFullHistory(
        symbol: String,
        frame: String = "1h",
        maxPages: Int = 6
    ): List<CandleN> {
        val normalizedSymbol = symbol.uppercase()

        if (!historyStore.isComplete(normalizedSymbol, frame)) {
            var endTime = futuresMarketData.exchangeNowMs()
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
                    val exchangeNowMs = futuresMarketData.exchangeNowMs()

                    val batch = ArrayList<MarketHistoryStore.Candle>(
                        array.length()
                    )
                    var oldest = Long.MAX_VALUE

                    for (i in 0 until array.length()) {
                        val row = array.getJSONArray(i)
                        val openTime = row.getLong(0)
                        val closeTime = row.getLong(6)
                        oldest = min(oldest, openTime)

                        if (!futuresMarketData.isCandleClosed(closeTime, exchangeNowMs)) {
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
            val exchangeNowMs = futuresMarketData.exchangeNowMs()

            for (i in 0 until array.length()) {
                val row = array.getJSONArray(i)
                val closeTime = row.getLong(6)
                if (!futuresMarketData.isCandleClosed(closeTime, exchangeNowMs)) continue
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

    @Synchronized
    private fun loadUniverse(): Triple<List<String>, Map<String, Double>, Map<String, Double>> {
        val nowMs = System.currentTimeMillis()
        val cached = cachedScanUniverse
        val cachedAt = scanUniverseCacheAtMs
        if (cached != null && cachedAt > 0L && nowMs >= cachedAt &&
            nowMs - cachedAt < scanUniverseCacheTtlMs
        ) {
            // Liquidity/24h volume is a coarse scan preselection only. The entry
            // path independently obtains a fresh Futures top-of-book before mutation.
            return Triple(cached.first.toList(), HashMap(cached.second), HashMap(cached.third))
        }

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
        val snapshot = Triple(coreSymbols.toList(), volumes.toMap(), spreads.toMap())
        cachedScanUniverse = snapshot
        scanUniverseCacheAtMs = nowMs
        return Triple(snapshot.first.toList(), HashMap(snapshot.second), HashMap(snapshot.third))
    }

    private fun markScanProgress() {
        synchronized(this) {
            scanProgressSymbols += 1
            scanLastProgressAt = System.currentTimeMillis()
        }
    }

    private fun requestScan() {
        // The scanner uses REST market data/history; WS remains an execution prerequisite.
        // Gate failures are surfaced as actionable states rather than silent early returns.
        // Do not pass hard-coded "safe" values into the scanner gate. The scanner
        // must reflect the real runtime safety latches and the authoritative Futures
        // coordinator; otherwise a manual scan can appear RUNNING during reconciliation.
        val gateState = scannerDataReadinessState(
            running = running,
            historyReady = historyReady,
            marketDataFresh = marketDataFresh()
        )
        if (gateState != "RUNNABLE") {
            scannerState = gateState
            scannerError = when (gateState) {
                "STOPPED" -> "Runtime is stopped; press START after Futures credentials are configured"
                "WAITING_FOR_HISTORY" -> historyLastError ?: "Waiting for closed H1 history for all 10 approved contracts"
                "WAITING_FOR_MARKET_DATA" -> restLastError ?: "Futures REST market data is stale or unavailable"
                else -> "Read-only scanner data gate is not ready: " + gateState
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
                // The campaign scanner may deliberately finish in PARTIAL_BLOCKED,
                // FAILED, or BLOCKED_*; do not overwrite that decision with READY.
                if (scannerState == "RUNNING") scannerState = "READY"
                if (scannerState == "READY") {
                    scannerError = null
                    lastError = null
                }
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
            pendingEntries.values.sumOf {
                conservativeRiskReservation(it.riskReservedPct, maxTotalRiskPct)
            }.coerceAtMost(maxTotalRiskPct)
        }

    private fun campaignRiskUsedPct(): Double =
        (
            positionList().sumOf {
                conservativeRiskReservation(it.riskPct, maxTotalRiskPct)
            } + campaignPendingRiskPct()
        ).coerceAtMost(maxTotalRiskPct)

    private fun campaignRemainingRiskPct(equity: Double): Double =
        if (equity <= 0.0) 0.0
        else (maxTotalRiskPct - campaignRiskUsedPct()).coerceAtLeast(0.0)

    // Retired Spot balance/order-capacity helpers removed; Futures limits come from exchangeInfo.
    
    /**
     * One account-wide Execution Door for every runtime-issued Futures mutation.
     * Safety/recovery actions may explicitly opt into paused/stopped/reconcile states;
     * ordinary entries never can. The lease is committed to SQLite and mirrored to
     * encrypted preferences before network I/O. An unresolved lease fails closed.
     */
    private fun withCampaignMutation(
        symbol: String,
        purpose: String,
        allowPaused: Boolean = false,
        allowStopped: Boolean = false,
        allowKillLatched: Boolean = false,
        allowReconcileRequired: Boolean = false,
        waitForDoorMs: Long = 0L,
        block: () -> JSONObject
    ): JSONObject {
        executionDoorBlocker(
            running = running,
            paused = paused,
            reconcileRequired = reconcileRequired,
            killLatched = killLatched,
            allowPaused = allowPaused,
            allowStopped = allowStopped,
            allowKillLatched = allowKillLatched,
            allowReconcileRequired = allowReconcileRequired
        )?.let { error(it) }

        // Mutations are serialized account-wide through one durable Execution Door.
        // This serialization does not limit the number of distinct-symbol open campaigns.
        val reservationKey = FUTURES_EXECUTION_DOOR_KEY
        val deadlineNanos = System.nanoTime() +
            TimeUnit.MILLISECONDS.toNanos(waitForDoorMs.coerceAtLeast(0L))
        var reserved = executionGate.tryReserve(reservationKey)
        while (!reserved && waitForDoorMs > 0L && System.nanoTime() < deadlineNanos) {
            try {
                Thread.sleep(25L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                error("Interrupted while waiting for the Futures Execution Door")
            }
            reserved = executionGate.tryReserve(reservationKey)
        }
        require(reserved) {
            "Futures Execution Door is already reserved by another mutation"
        }

        val leasePersisted = executionStore.writeExecutionGate(symbol, purpose) &&
            prefs.edit()
                .putString("execution_gate_symbol", symbol)
                .putString("execution_gate_purpose", purpose)
                .commit()
        if (!leasePersisted) {
            executionGate.release(reservationKey)
            reconcileRequired = true
            persistSafetyFlag("reconcile_required", true)
            val blocker = "Futures Execution Door lease could not be committed; exchange mutation blocked"
            lastError = blocker
            stateMachine.force(TradingState.RECONCILE_REQUIRED, blocker)
            error(blocker)
        }

        try {
            val result = block()
            // A coordinator safe_preflight_rejection is emitted only before the
            // intent is journaled and before any exchange order mutation. Do not
            // poison the account-wide reconciliation latch for a rejected candidate
            // (e.g. a filter/slot/risk failure); still fail closed on every ambiguous
            // or post-mutation error.
            val safePreflightRejection = result.optBoolean("safe_rejection", false) &&
                !result.optBoolean("mutation_attempted", true)
            val reportedFailure = !safePreflightRejection && (
                (result.has("ok") && !result.optBoolean("ok", false)) ||
                    (result.has("accepted") && !result.optBoolean("accepted", false))
                )
            if (reportedFailure) {
                reconcileRequired = true
                persistSafetyFlag("reconcile_required", true)
                val blocker = "Futures mutation returned an unresolved/non-success result ($purpose): " +
                    result.optString("blocker", result.optString("error", result.optString("state", "not_ok")))
                lastError = blocker
                scannerError = blocker
                stateMachine.force(
                    if (killLatched) TradingState.KILL_SWITCH_LATCHED
                    else TradingState.RECONCILE_REQUIRED,
                    blocker
                )
            }
            return result
        } catch (failure: Exception) {
            // An exception after a mutation request may mean Binance accepted it but
            // the response was lost. Do not reopen the execution path until exchange
            // state has been reconciled, even if the caller forgets to latch the error.
            reconcileRequired = true
            persistSafetyFlag("reconcile_required", true)
            val blocker = "Futures mutation outcome requires reconciliation ($purpose): " +
                (failure.message ?: failure.javaClass.simpleName)
            lastError = blocker
            scannerError = blocker
            stateMachine.force(
                if (killLatched) TradingState.KILL_SWITCH_LATCHED
                else TradingState.RECONCILE_REQUIRED,
                blocker
            )
            throw failure
        } finally {
            // Clear the durable lease while still holding the in-memory reservation.
            // Releasing first creates a race where a second mutation writes its lease
            // and the first mutation then erases that new lease.
            val sqliteCleared = runCatching {
                executionStore.writeExecutionGate("", "")
            }.getOrDefault(false)
            val preferencesCleared = runCatching {
                prefs.edit()
                    .remove("execution_gate_symbol")
                    .remove("execution_gate_purpose")
                    .commit()
            }.getOrDefault(false)
            if (!sqliteCleared || !preferencesCleared) {
                reconcileRequired = true
                persistSafetyFlag("reconcile_required", true)
                val blocker = "Futures Execution Door lease could not be cleared durably after mutation"
                lastError = blocker
                scannerError = blocker
                stateMachine.force(TradingState.RECONCILE_REQUIRED, blocker)
                // Intentionally keep the in-memory reservation: unresolved durable
                // execution state must not permit another exchange mutation.
                throw IllegalStateException(blocker)
            }
            executionGate.release(reservationKey)
        }
    }

    /**
     * Reconciliation can place, replace, or cancel protective algo orders. It therefore
     * belongs behind the same durable account-wide Execution Door as entries/exits.
     */
    private fun reconcileFuturesThroughDoor(
        purpose: String,
        allowStopped: Boolean = false,
        allowKillLatched: Boolean = false,
        waitForDoorMs: Long = 50_000L
    ): JSONObject {
        // Bound lock acquisition so a stalled account-wide reconciliation cannot strand
        // queued private-stream work indefinitely. Unknown exchange state remains blocked.
        val waitStartedAtMs = System.currentTimeMillis()
        val lockAcquired = try {
            futuresReconciliationLock.tryLock(waitForDoorMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            lastReconciliationLockWaitFailure = "Interrupted while waiting for the account-wide reconciliation lock"
            false
        }
        lastReconciliationLockWaitMs = (System.currentTimeMillis() - waitStartedAtMs).coerceAtLeast(0L)
        if (!lockAcquired) {
            val baseReason = if (Thread.currentThread().isInterrupted) {
                "Futures reconciliation ($purpose) was interrupted while waiting for the account-wide reconciliation lock"
            } else {
                "Futures reconciliation ($purpose) timed out after ${lastReconciliationLockWaitMs}ms waiting for the account-wide reconciliation lock"
            }
            reconcileRequired = true
            val latchPersisted = persistSafetyFlag("reconcile_required", true)
            val reason = if (latchPersisted) baseReason
                else "$baseReason; SQLite failed to persist the reconciliation safety latch"
            lastReconciliationLockWaitFailure = reason
            lastReconciliationAttemptAtMs = waitStartedAtMs
            lastReconciliationCompletedAtMs = System.currentTimeMillis()
            lastReconciliationResult = "BLOCKED_LOCK: $reason"
            lastError = reason
            stateMachine.force(TradingState.RECONCILE_REQUIRED, reason)
            // Do not enter the coordinator monitor here: the lock owner may still be
            // inside reconcile(), so recording a blocker there could recreate the hang.
            return JSONObject()
                .put("ok", false)
                .put("engine", "USD_M_FUTURES_TESTNET")
                .put("state", "RECONCILE_REQUIRED")
                .put("blocker", reason)
                .put("lock_wait_ms", lastReconciliationLockWaitMs)
        }
        futuresReconciliationRunning = true
        lastReconciliationAttemptAtMs = System.currentTimeMillis()
        lastReconciliationResult = "RUNNING"
        lastReconciliationLockWaitFailure = null
        try {
            // The Door may reject before invoking the lambda (e.g. reservation timeout,
            // interrupted wait, or failed durable lease). Record that concrete blocker in
            // the coordinator; otherwise its previous "exchange_reconciliation_in_progress"
            // text survives even though no reconciliation task is running.
            val result = try {
                withCampaignMutation(
                    symbol = "FUTURES_ACCOUNT",
                    purpose = purpose,
                    allowPaused = true,
                    allowStopped = allowStopped,
                    allowKillLatched = allowKillLatched,
                    allowReconcileRequired = true,
                    waitForDoorMs = waitForDoorMs
                ) {
                    futuresCampaignCoordinator.reconcile()
                }
            } catch (failure: Exception) {
                val reason = "Execution Door prevented Futures reconciliation ($purpose): " +
                    (failure.message ?: failure.javaClass.simpleName)
                val blocked = futuresCampaignCoordinator.recordReconciliationBlocker(reason)
                lastError = reason
                blocked
            }
            if (result.optBoolean("ok", false)) {
                lastRestReconcileMs = System.currentTimeMillis()
                lastReconciliationResult = "SUCCESS"
            } else {
                lastReconciliationResult = "BLOCKED: " +
                    result.optString("blocker", result.optString("error", "unknown"))
            }
            return result
        } finally {
            lastReconciliationCompletedAtMs = System.currentTimeMillis()
            if (lastReconciliationResult == "RUNNING") {
                lastReconciliationResult = "INTERRUPTED_OR_ABORTED"
            }
            futuresReconciliationRunning = false
            futuresReconciliationLock.unlock()
        }
    }

    private fun requireSuccessfulFuturesReconciliation(purpose: String) {
        val result = reconcileFuturesThroughDoor(
            purpose,
            allowStopped = true,
            allowKillLatched = true
        )
        if (result.optBoolean("ok", false)) return
        val blocker = result.optString("blocker", result.optString("error", "Futures reconciliation failed"))
        reconcileRequired = true
        persistSafetyFlag("reconcile_required", true)
        lastError = blocker
        scannerError = blocker
        stateMachine.force(TradingState.RECONCILE_REQUIRED, blocker)
        throw IllegalStateException(blocker)
    }

    private fun campaignEntryCandidate(candidate: BaseAnalysis): CampaignSignalN? =
        // Select the most recent still-valid confirmed setup, never the oldest one
        // returned by a historical scan.
        candidate.campaignSignals.sortedByDescending { it.signalBarTimeMs }.firstOrNull()

    // Legacy Spot-era campaign submission/management/recovery implementation removed.
    // Entry, trailing protection, and exits are owned by NativeFuturesCampaignCoordinator.

    private fun checkFuturesDailyLoss(): Boolean {
        val account = try {
            futuresCampaignCoordinator.accountSnapshot()
        } catch (failure: Exception) {
            restAccountReady = false
            restAccountLastError = failure.message ?: failure.javaClass.simpleName
            lastError = "FUTURES_ACCOUNT_REFRESH_FAILED: " + restAccountLastError
            return false
        }
        if (!updateCachedFuturesAccount(account)) {
            lastError = "FUTURES_ACCOUNT_INVALID: " +
                (restAccountLastError ?: "account balance/equity fields failed validation")
            return false
        }
        val equity = liveFuturesEquity
        if (!futuresAccountFundedForEntry(liveUsdtBalance, equity)) {
            // Empty Demo wallets are valid for diagnostics and scanning, but can never
            // establish a meaningful percentage-loss baseline or authorize an order.
            lastError = "FUTURES_ACCOUNT_UNFUNDED: positive available USDT and equity are required for entries"
            return false
        }
        val utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        val exchangeNowMs = futuresMarketData.exchangeNowMs()
        utc.timeInMillis = exchangeNowMs
        utc.set(java.util.Calendar.HOUR_OF_DAY, 0)
        utc.set(java.util.Calendar.MINUTE, 0)
        utc.set(java.util.Calendar.SECOND, 0)
        utc.set(java.util.Calendar.MILLISECOND, 0)
        val utcDayStartMs = utc.timeInMillis
        val baselineKey = "futures_day_start_equity_" + utcDayStartMs
        var incomeRowsForDay: JSONArray? = null

        if (executionStore.readState(baselineKey) == null) {
            // Reconstruct from the full UTC-day income ledger when trades already
            // occurred. Never reset to current equity just because the local baseline
            // disappeared: that would erase realized losses from today's 3% limit.
            val baselineAttempt = runCatching {
                val positions = futuresCampaignCoordinator.positionsSnapshot()
                val hasOpenOrUnknownPosition = (0 until positions.length()).any { index ->
                    val row = positions.optJSONObject(index) ?: return@any true
                    val amount = row.optString("positionAmt").toDoubleOrNull()
                    amount == null || !amount.isFinite() || amount != 0.0
                }
                val intents = futuresCampaignCoordinator.intentsSnapshot()
                val hasActiveExecution = (0 until intents.length()).any { index ->
                    val row = intents.optJSONObject(index) ?: return@any true
                    isEntryCampaignReserved(row)
                }
                val income = futuresCampaignCoordinator.incomeHistory(utcDayStartMs, exchangeNowMs)
                incomeRowsForDay = income
                val incomeSummary = summarizeFuturesDailyIncome(income)
                    ?: throw IllegalStateException("UTC-day Futures income is malformed or reached the 1000-row truncation guard")
                val hasTradingIncome = incomeSummary.hasTradingActivity
                val accountSnapshot = parseFuturesAccountSnapshot(account)
                    ?: throw IllegalStateException("Cannot reconstruct daily baseline without a parsed Futures account snapshot")
                val unrealized = accountSnapshot.unrealizedPnlUsdt ?: run {
                    if (hasOpenOrUnknownPosition) {
                        val values = (0 until positions.length()).map { index ->
                            val row = positions.optJSONObject(index)
                                ?: throw IllegalStateException("Malformed Futures position row at index $index")
                            row.optString("unRealizedProfit").toDoubleOrNull()
                                ?.takeIf { it.isFinite() }
                                ?: throw IllegalStateException("Missing unrealized PnL for position row $index")
                        }
                        values.sum().takeIf { it.isFinite() }
                            ?: throw IllegalStateException("Invalid aggregate Futures unrealized PnL")
                    } else 0.0
                }

                val baselineDecision = selectFuturesDailyBaseline(
                    currentEquityUsdt = equity,
                    unrealizedPnlUsdt = unrealized,
                    incomeRows = income,
                    hasOpenOrUnknownPositionOrPendingExecution =
                        hasOpenOrUnknownPosition || hasActiveExecution
                ) ?: throw IllegalStateException(
                    "Cannot safely reconstruct UTC-day Futures baseline from income, unrealized PnL, and exchange exposure"
                )
                baselineDecision.baselineEquityUsdt to baselineDecision.source
            }
            val baselineResult = baselineAttempt.getOrElse { failure ->
                paused = true
                prefs.edit().putBoolean("auto_run", false).commit()
                lastError = "FUTURES_DAILY_BASELINE_UNKNOWN: unable to reconstruct UTC-day equity safely: " +
                    (failure.message ?: failure.javaClass.simpleName) + "; daily limit remains 3% and entries stay blocked"
                return false
            }
            check(executionStore.writeState(baselineKey, baselineResult.first.toString()) &&
                executionStore.writeState("futures_day_start_equity_source_" + utcDayStartMs, baselineResult.second)
            ) {
                paused = true
                prefs.edit().putBoolean("auto_run", false).commit()
                lastError = "FUTURES_DAILY_BASELINE_PERSIST_FAILED: SQLite could not durably store the reconstructed baseline"
                return false
            }
            if (lastError?.startsWith("FUTURES_DAILY_BASELINE_UNKNOWN") == true) lastError = null
        }

        val baseline = executionStore.readState(baselineKey)?.toDoubleOrNull() ?: 0.0
        require(baseline > 0.0 && baseline.isFinite()) { "Futures daily equity baseline is invalid" }
        // Older builds persisted the numeric baseline and its provenance as two separate
        // SQLite writes. A process death between those writes left a valid baseline but no
        // source key; because initialization only ran when the numeric key was absent, the
        // missing provenance could then persist forever. Repair metadata only when the
        // already-persisted baseline itself validates; never replace/reset the baseline.
        val baselineSourceKey = "futures_day_start_equity_source_" + utcDayStartMs
        val existingBaselineSource = executionStore.readState(baselineSourceKey)
        if (existingBaselineSource.isNullOrBlank() ||
            existingBaselineSource.equals("UNKNOWN", ignoreCase = true) ||
            existingBaselineSource.equals("null", ignoreCase = true)) {
            check(executionStore.writeState(
                baselineSourceKey, "SQLITE_PERSISTED_BASELINE_SOURCE_RECOVERED"
            )) {
                paused = true
                prefs.edit().putBoolean("auto_run", false).commit()
                lastError = "FUTURES_DAILY_BASELINE_SOURCE_REPAIR_FAILED: persisted baseline is valid but provenance metadata could not be repaired"
                return false
            }
        }
        val dailyIncomeRows = incomeRowsForDay ?: runCatching {
            futuresCampaignCoordinator.incomeHistory(utcDayStartMs, exchangeNowMs)
        }.getOrElse { failure ->
            paused = true
            prefs.edit().putBoolean("auto_run", false).commit()
            lastError = "FUTURES_DAILY_INCOME_UNKNOWN: cannot verify the UTC-day PnL ledger: " +
                (failure.message ?: failure.javaClass.simpleName)
            return false
        }
        val dailyIncome = summarizeFuturesDailyIncome(dailyIncomeRows) ?: run {
            paused = true
            prefs.edit().putBoolean("auto_run", false).commit()
            lastError = "FUTURES_DAILY_INCOME_UNKNOWN: income rows are malformed or truncated; entries remain blocked"
            return false
        }
        val accountSnapshot = parseFuturesAccountSnapshot(account)
        val currentUnrealizedPnl = accountSnapshot?.unrealizedPnlUsdt ?: runCatching {
            val positions = futuresCampaignCoordinator.positionsSnapshot()
            val values = (0 until positions.length()).map { index ->
                val row = positions.optJSONObject(index)
                    ?: throw IllegalStateException("Malformed Futures position row $index")
                row.optString("unRealizedProfit").toDoubleOrNull()
                    ?.takeIf { it.isFinite() }
                    ?: throw IllegalStateException("Missing unrealized PnL for Futures position row $index")
            }
            values.sum().takeIf { it.isFinite() }
                ?: throw IllegalStateException("Invalid aggregate Futures unrealized PnL")
        }.getOrElse { failure ->
            paused = true
            prefs.edit().putBoolean("auto_run", false).commit()
            lastError = "FUTURES_DAILY_UNREALIZED_PNL_UNKNOWN: " +
                (failure.message ?: failure.javaClass.simpleName)
            return false
        }
        // Calculate trading drawdown independently of transfers/deposits/withdrawals.
        // Otherwise a deposit can hide a loss and a withdrawal can manufacture one.
        val dailyPnlQuote = dailyIncome.tradingIncomeUsdt + currentUnrealizedPnl
        val riskAdjustedEquity = baseline + dailyPnlQuote
        if (!dailyPnlQuote.isFinite() || !riskAdjustedEquity.isFinite() || riskAdjustedEquity <= 0.0) {
            paused = true
            prefs.edit().putBoolean("auto_run", false).commit()
            lastError = "FUTURES_DAILY_PNL_INVALID: risk-adjusted equity is not finite and positive"
            return false
        }
        val lossPct = ((baseline - riskAdjustedEquity) / baseline).coerceAtLeast(0.0)
        check(
            executionStore.writeState("futures_daily_loss_pct", lossPct.toString()) &&
                executionStore.writeState("futures_daily_pnl_usdt", dailyPnlQuote.toString()) &&
                executionStore.writeState("futures_daily_capital_flow_usdt", dailyIncome.capitalFlowUsdt.toString())
        ) {
            "SQLite commit failed while persisting daily Futures equity/PnL measurement"
        }
        if (observeFuturesEquityFromPersistedBaseline(
                equityCircuitBreaker, baseline, riskAdjustedEquity, exchangeNowMs
            ).tripped
        ) {
            if (!killLatched && !circuitBreakerTripInProgress) {
                circuitBreakerTripInProgress = true
                paused = true
                // Persist the kill latch synchronously before scheduling network work.
                // A process death between threshold detection and the close routine
                // must not silently re-arm trading after the restart.
                val killLatchPersisted = persistSafetyFlag("kill_latched", true)
                killLatched = true
                if (!killLatchPersisted) {
                    reconcileRequired = true
                    persistSafetyFlag("reconcile_required", true)
                }
                stateMachine.force(
                    TradingState.KILL_SWITCH_LATCHED,
                    "5% Futures equity circuit breaker tripped"
                )
                prefs.edit().putBoolean("auto_run", false).commit()
                val drawdownPct = ((baseline - riskAdjustedEquity) / baseline).coerceAtLeast(0.0) * 100.0
                lastError = "AUTOMATIC FUTURES EQUITY CIRCUIT BREAKER: daily drawdown " +
                    "%.2f%% reached; kill switch engaged".format(Locale.US, drawdownPct)
                Thread {
                    try {
                        kill()
                    } finally {
                        circuitBreakerTripInProgress = false
                    }
                }.apply {
                    isDaemon = true
                    name = "williams-futures-equity-circuit-breaker"
                    start()
                }
            }
            return false
        }
        if (lossPct >= 0.03) {
            paused = true
            prefs.edit().putBoolean("auto_run", false).commit()
            lastError = "FUTURES_DAILY_LOSS_LIMIT: 3% reached; new entries blocked"
            runCatching {
                withCampaignMutation(
                    symbol = "FUTURES_ACCOUNT",
                    purpose = "DAILY_LOSS_CANCEL_PENDING",
                    allowPaused = true,
                    allowReconcileRequired = true,
                    waitForDoorMs = 50_000L
                ) {
                    futuresCampaignCoordinator.cancelPendingEntries("DAILY_LOSS_LIMIT")
                }
            }
            return false
        }
        return true
    }

    private fun dailyRiskBaselineSource(): String = runCatching {
        val now = futuresMarketData.exchangeNowMs()
        val utc = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        utc.timeInMillis = now
        utc.set(Calendar.HOUR_OF_DAY, 0)
        utc.set(Calendar.MINUTE, 0)
        utc.set(Calendar.SECOND, 0)
        utc.set(Calendar.MILLISECOND, 0)
        val dayStart = utc.timeInMillis
        val baselineKey = "futures_day_start_equity_" + dayStart
        val sourceKey = "futures_day_start_equity_source_" + dayStart
        val source = executionStore.readState(sourceKey)
        if (!source.isNullOrBlank() &&
            !source.equals("UNKNOWN", ignoreCase = true) &&
            !source.equals("null", ignoreCase = true)
        ) return@runCatching source
        // Recover only provenance metadata for an already persisted, valid baseline.
        // Never manufacture a baseline from current equity here: the risk gate owns that.
        val baselineValue = executionStore.readState(baselineKey)
        if (canRepairDailyRiskBaselineSource(baselineValue, source) &&
            executionStore.writeState(sourceKey, "SQLITE_PERSISTED_BASELINE_SOURCE_RECOVERED")
        ) "SQLITE_PERSISTED_BASELINE_SOURCE_RECOVERED" else "UNKNOWN"
    }.getOrDefault("UNKNOWN")

    /**
     * Signed account requests are network-bound. Fast dashboard polling must not block
     * on them; refresh asynchronously and expose freshness/error diagnostics instead.
     * Entry/risk authorization still uses the coordinator's direct exchange snapshot.
     */
    private fun refreshFuturesAccountSnapshotIfNeeded(maxAgeMs: Long = 15_000L) {
        if (key().isBlank() || secret().isBlank()) return
        val nowMs = System.currentTimeMillis()
        val age = nowMs - futuresAccountSnapshotUpdatedAtMs
        if (futuresAccountSnapshotUpdatedAtMs > 0L && age in 0..maxAgeMs) return
        val start = synchronized(this) {
            val last = futuresAccountSnapshotLastAttemptMs
            val retryAllowed = last <= 0L || nowMs < last || nowMs - last >= maxAgeMs
            if (!futuresAccountSnapshotRefreshRunning && retryAllowed) {
                futuresAccountSnapshotRefreshRunning = true
                futuresAccountSnapshotLastAttemptMs = nowMs
                true
            } else false
        }
        if (!start) return
        val generationAtStart = runtimeGeneration
        val apiKeyAtStart = key()
        Thread {
            try {
                val account = futuresCampaignCoordinator.accountSnapshot()
                // A credentials reset/reconfiguration may race this background request.
                // Never publish a snapshot fetched under a previous credential set.
                if (generationAtStart != runtimeGeneration || apiKeyAtStart != key()) {
                    return@Thread
                }
                check(updateCachedFuturesAccount(account)) {
                    restAccountLastError ?: "Futures account payload failed validation"
                }
                futuresAccountSnapshotRefreshError = null
            } catch (failure: Exception) {
                val reason = failure.message ?: failure.javaClass.simpleName
                futuresAccountSnapshotRefreshError = reason
                restAccountReady = false
                restAccountLastError = reason
                reconcileRequired = true
                persistSafetyFlag("reconcile_required", true)
                lastError = "Futures account snapshot refresh failed; trading blocked: " + reason
                stateMachine.force(TradingState.RECONCILE_REQUIRED, lastError ?: reason)
            } finally {
                synchronized(this) { futuresAccountSnapshotRefreshRunning = false }
            }
        }.apply {
            isDaemon = true
            name = "williams-futures-account-snapshot"
            start()
        }
    }

    /**
     * The dashboard polls fast status together with tickers/scanner. Do not make every
     * UI refresh wait on a signed position REST call: serve a bounded-age snapshot and
     * refresh it on a worker. Trading/reconciliation mutations still query Binance
     * directly through the coordinator and never trust this display cache.
     */
    private fun fastFuturesPositionsSnapshot(): Pair<JSONArray, String?> {
        val nowMs = System.currentTimeMillis()
        val cached = cachedFuturesPositionsSnapshot
        val cachedAt = cachedFuturesPositionsSnapshotAtMs
        if (cached != null && cachedAt > 0L && nowMs >= cachedAt && nowMs - cachedAt <= 15_000L) {
            return JSONArray(cached.toString()) to null
        }

        val shouldRefresh = synchronized(this) {
            val lastAttempt = futuresPositionSnapshotLastAttemptMs
            val retryAllowed = lastAttempt <= 0L || nowMs < lastAttempt || nowMs - lastAttempt >= 15_000L
            if (!futuresPositionSnapshotRefreshRunning && retryAllowed &&
                key().isNotBlank() && secret().isNotBlank()
            ) {
                futuresPositionSnapshotRefreshRunning = true
                futuresPositionSnapshotLastAttemptMs = nowMs
                true
            } else false
        }
        if (shouldRefresh) {
            Thread {
                try {
                    futuresPositionSnapshot()
                    futuresPositionSnapshotRefreshError = null
                } catch (failure: Exception) {
                    val reason = failure.message ?: failure.javaClass.simpleName
                    futuresPositionSnapshotRefreshError = reason
                    reconcileRequired = true
                    persistSafetyFlag("reconcile_required", true)
                    lastError = "Fast-status Futures position refresh failed; trading blocked: " + reason
                    stateMachine.force(TradingState.RECONCILE_REQUIRED, lastError ?: reason)
                } finally {
                    synchronized(this) { futuresPositionSnapshotRefreshRunning = false }
                }
            }.apply {
                isDaemon = true
                name = "williams-futures-position-snapshot"
                start()
            }
        }

        val fallback = cachedFuturesPositionsSnapshot
        val error = futuresPositionSnapshotRefreshError
            ?: if (fallback == null) {
                "Exchange position snapshot is loading; no cached snapshot yet"
            } else {
                "Showing a stale cached Futures position snapshot while refresh runs"
            }
        return (fallback?.let { JSONArray(it.toString()) } ?: JSONArray()) to error
    }

    private fun futuresPositionSnapshot(): JSONArray {
        val out = JSONArray()
        if (key().isBlank() || secret().isBlank()) return out
        val positions = futuresCampaignCoordinator.positionsSnapshot()
        val intents = runCatching { futuresCampaignCoordinator.intentsSnapshot() }.getOrDefault(JSONArray())
        for (i in 0 until positions.length()) {
            val row = positions.optJSONObject(i)
                ?: throw IllegalStateException("Malformed Binance Futures position row in position snapshot")
            val symbol = row.optString("symbol").uppercase(Locale.US)
            val signedQty = row.optString("positionAmt").toDoubleOrNull()
                ?: throw IllegalStateException("Malformed Futures position amount for $symbol")
            if (signedQty == 0.0) continue
            if (symbol !in coreSymbols) {
                throw IllegalStateException(
                    "Unmanaged live Futures position outside the approved ten-symbol universe: $symbol"
                )
            }
            val direction = if (signedQty > 0.0) "LONG" else "SHORT"
            val intentIndex = latestEntryIntentIndex(intents, symbol)
            val intent = intentIndex?.let { intents.optJSONObject(it) }
                ?.takeIf {
                    it.optString("direction").equals(direction, ignoreCase = true) &&
                        campaignLifecycleStatus(it) == "OPEN_PROTECTED"
                }
            val entry = row.optString("entryPrice").toDoubleOrNull() ?: 0.0
            val mark = row.optString("markPrice").toDoubleOrNull()
                ?: runCatching { futuresMarketData.markPrice(symbol) }.getOrDefault(0.0)
            val qty = kotlin.math.abs(signedQty)
            val pnl = row.optString("unRealizedProfit").toDoubleOrNull() ?: 0.0
            out.put(JSONObject()
                .put("symbol", symbol).put("direction", direction).put("side", direction)
                .put("positionAmt", signedQty).put("qty", qty).put("entry", entry).put("entryPrice", entry)
                .put("mark_price", mark).put("stop", intent?.optString("protective_stop")?.toDoubleOrNull() ?: JSONObject.NULL)
                .put("risk_pct", intent?.optDouble("risk_pct", 0.0) ?: 0.0)
                .put("unrealized_pnl_usdt", pnl).put("margin_type", row.optString("marginType"))
                .put("leverage", row.optString("leverage")).put("exchange_confirmed", true))
        }
        val snapshotNow = System.currentTimeMillis()
        cachedFuturesPositionsSnapshot = JSONArray(out.toString())
        cachedFuturesPositionsSnapshotAtMs = snapshotNow
        futuresPositionSnapshotRefreshError = null
        return out
    }

    private fun sellFuturesPosition(symbolInput: String): JSONObject {
        val symbol = symbolInput.trim().uppercase(Locale.US)
        require(symbol in coreSymbols) { "Symbol is outside the approved Futures universe: " + symbol }
        val rows = futuresCampaignCoordinator.positionsSnapshot()
        val row = (0 until rows.length()).map { index ->
            rows.optJSONObject(index)
                ?: throw IllegalStateException("Malformed Binance Futures position row during manual exit")
        }.firstOrNull { candidate ->
            val amount = candidate.optString("positionAmt").toDoubleOrNull()
                ?: throw IllegalStateException("Malformed Binance Futures position amount during manual exit")
            candidate.optString("symbol") == symbol && kotlin.math.abs(amount) > 0.0
        } ?: run {
            val rec = reconcileFuturesThroughDoor(
                "MANUAL_EXIT_POSITION_NOT_FOUND_RECONCILIATION",
                allowStopped = true,
                allowKillLatched = true
            )
            return JSONObject().put("sold", false).put("symbol", symbol)
                .put("reason", "exchange_position_not_found").put("reconciliation", rec)
        }
        val signedQty = row.optString("positionAmt").toDouble()
        val direction = if (signedQty > 0.0) NativeFuturesCampaignCoordinator.Direction.LONG
            else NativeFuturesCampaignCoordinator.Direction.SHORT
        val rules = symbolFilters(symbol)
        val closeQty = fullMarketCloseQuantity(row.optString("positionAmt"), rules)
        val clientId = ("W2X_" + symbol.take(8) + "_" + System.nanoTime().toString(16).takeLast(12)).take(36)
        val result = withCampaignMutation(
            symbol = symbol,
            purpose = "MANUAL_FUTURES_EXIT",
            allowPaused = true,
            allowStopped = true,
            allowKillLatched = true,
            // Closing exposure is an explicitly requested risk-reducing mutation.
            // It must pass the Execution Door even while new entries are blocked
            // by reconciliation; the coordinator still verifies live side and size.
            allowReconcileRequired = true
        ) {
            futuresCampaignCoordinator.closePosition(
                symbol, direction, closeQty, clientId, "MANUAL_EXIT"
            )
        }
        if (!result.optBoolean("ok", false)) lastError = result.optString("blocker", result.optString("error"))
        val flatConfirmed = result.optBoolean("flat_confirmed", false)
        return JSONObject()
            // "sold" describes actual exchange exposure, not whether orphan-stop
            // cleanup finished. Keep reconciliation latched via ok=false while
            // still reporting a confirmed flat position truthfully to the UI.
            .put("sold", result.optBoolean("ok", false) || flatConfirmed)
            .put("flat_confirmed", result.optBoolean("flat_confirmed", result.optBoolean("ok", false)))
            .put("reconcile_required", !result.optBoolean("ok", false))
            .put("symbol", symbol)
            .put("direction_closed", direction.name).put("exchange", "BINANCE_USDM_FUTURES_TESTNET")
            .put("result", result)
    }

    /**
     * Fetch a campaign's account-specific Futures fills without exceeding Binance's
     * seven-day query window. Saturated 1000-row windows are bisected instead of
     * silently treating truncated history as complete.
     */
    private fun loadFuturesTradeRows(
        symbol: String,
        startTimeMs: Long,
        endTimeMs: Long,
        depth: Int = 0
    ): List<JSONObject>? {
        if (startTimeMs < 0L || endTimeMs < startTimeMs || depth > 40) return null
        val maxWindowMs = 7L * 24L * 60L * 60L * 1000L
        if (endTimeMs - startTimeMs > maxWindowMs) {
            val splitEnd = startTimeMs + maxWindowMs
            val left = loadFuturesTradeRows(symbol, startTimeMs, splitEnd, depth + 1) ?: return null
            val right = loadFuturesTradeRows(symbol, splitEnd + 1L, endTimeMs, depth + 1) ?: return null
            return left + right
        }
        val rows = runCatching {
            futuresCampaignCoordinator.userTrades(symbol, startTimeMs, endTimeMs, 1000)
        }.getOrNull() ?: return null
        if (rows.length() < 1000) {
            return (0 until rows.length()).map { i -> rows.optJSONObject(i) ?: return null }
        }
        // A full page may be truncated. Split by inclusive timestamps and verify each
        // child window, rather than publishing potentially partial trade statistics.
        if (startTimeMs >= endTimeMs) return null
        val midpoint = startTimeMs + (endTimeMs - startTimeMs) / 2L
        val left = loadFuturesTradeRows(symbol, startTimeMs, midpoint, depth + 1) ?: return null
        val right = loadFuturesTradeRows(symbol, midpoint + 1L, endTimeMs, depth + 1) ?: return null
        return left + right
    }

    /**
     * Native Futures campaigns are not legacy TradeJournal entries. On every confirmed
     * reconciliation, backfill closed campaigns from Binance userTrades and write an
     * idempotent journal record. Incomplete exchange history/fee conversion stays
     * explicitly marked and never enters aggregate net-PnL statistics as if exact.
     */
    private fun journalClosedNativeFuturesTrades() {
        val nowMs = System.currentTimeMillis()
        val intents = futuresCampaignCoordinator.intentsSnapshot()
        for (i in 0 until intents.length()) {
            val intent = intents.optJSONObject(i) ?: continue
            if (intent.optString("intent_role", "ENTRY") != "ENTRY") continue
            if (campaignLifecycleStatus(intent) != "CLOSED_OR_STOPPED") continue
            val clientId = intent.optString("client_id")
            if (clientId.isBlank()) continue
            val journalStatus = intent.optString("trade_journal_status")
            if (journalStatus == "RECORDED" || journalStatus == "NO_FILLED_TRADES") continue
            val lastAttempt = intent.optLong("trade_journal_updated_at_ms", 0L)
            if (journalStatus == "RETRY" && lastAttempt > 0L &&
                nowMs >= lastAttempt && nowMs - lastAttempt < 60_000L
            ) continue

            val fillObservedAt = maxOf(
                intent.optLong("position_open_observed_at_ms", 0L),
                intent.optLong("fill_observed_at_ms", 0L)
            )
            val hasObservedPosition = intent.optDouble("actual_entry_price", 0.0) > 0.0
            val exchangeEntryStatus = exchangeOrderStatus(intent).uppercase(Locale.US)
            val definitivelyUnfilledTerminal = exchangeEntryStatus in setOf(
                "CANCELED", "CANCELLED", "EXPIRED", "EXPIRED_IN_MATCH", "REJECTED"
            )
            if (fillObservedAt <= 0L && !hasObservedPosition && definitivelyUnfilledTerminal) {
                // Only a clearly terminal, never-filled order is skipped. Older durable
                // campaigns with no fill timestamp are still reconstructed from exchange
                // history rather than being incorrectly discarded.
                futuresCampaignCoordinator.markTradeJournalStatus(
                    clientId, "NO_FILLED_TRADES", "Exchange entry was terminal without an observed fill"
                )
                continue
            }
            val startTime = intent.optLong("created_at_ms", 0L).takeIf { it > 0L }
                ?: fillObservedAt
            if (startTime <= 0L) {
                futuresCampaignCoordinator.markTradeJournalStatus(
                    clientId, "RETRY", "Campaign has no trustworthy order/fill start timestamp"
                )
                continue
            }
            val closedAt = maxOf(
                intent.optLong("closed_observed_at_ms", 0L),
                intent.optLong("confirmed_flat_at_ms", 0L)
            )
            if (closedAt < startTime || closedAt <= 0L) {
                futuresCampaignCoordinator.markTradeJournalStatus(
                    clientId, "RETRY", "Closed campaign is missing a valid timestamp range"
                )
                continue
            }
            val symbol = intent.optString("symbol").uppercase(Locale.US)
            val direction = intent.optString("direction").uppercase(Locale.US)
            if (symbol !in coreSymbols || direction !in setOf("LONG", "SHORT")) {
                futuresCampaignCoordinator.markTradeJournalStatus(
                    clientId, "RETRY", "Persisted campaign symbol/direction is invalid"
                )
                continue
            }
            try {
                val fills = loadFuturesTradeRows(symbol, startTime, closedAt)
                if (fills == null) {
                    futuresCampaignCoordinator.markTradeJournalStatus(
                        clientId, "RETRY", "Binance userTrades history was unavailable or remained saturated"
                    )
                    continue
                }
                val fillArray = JSONArray().apply { fills.forEach { put(it) } }
                val riskBudget = intent.optDouble("risk_estimated_all_in_loss_usdt", Double.NaN)
                val summaryRiskBudget = intent.optDouble("risk_budget_usdt", Double.NaN)
                // Include scale-in quantity conservatively against the original structural
                // stop. A trailing stop must not shrink the campaign's recorded initial risk.
                val initialStop = intent.optString("initial_protective_stop")
                    .toDoubleOrNull()
                    ?: intent.optString("protective_stop").toDoubleOrNull()
                val summaryProbe = summarizeFuturesCampaignTrades(
                    symbol, direction, startTime, closedAt, fillArray,
                    initialRiskUsdt = listOf(riskBudget, summaryRiskBudget, 1.0)
                        .first { it.isFinite() && it > 0.0 }
                )
                if (summaryProbe == null) {
                    futuresCampaignCoordinator.markTradeJournalStatus(
                        clientId, "RETRY",
                        "Exchange fills did not prove matching opening/closing quantities and realized PnL"
                    )
                    continue
                }
                val reconstructedStructuralRisk = initialStop?.takeIf {
                    hasValidStructuralStop(direction, summaryProbe.entryPrice.toString(), it.toString())
                }?.let {
                    kotlin.math.abs(summaryProbe.entryPrice - it) * summaryProbe.quantity +
                        summaryProbe.notionalUsdt * (2.0 * feeBufferPerSidePct + maxSlippagePct)
                }?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
                val initialRisk = maxOf(
                    riskBudget.takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
                    summaryRiskBudget.takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
                    reconstructedStructuralRisk
                )
                if (!initialRisk.isFinite() || initialRisk <= 0.0) {
                    futuresCampaignCoordinator.markTradeJournalStatus(
                        clientId, "RETRY", "Initial campaign risk could not be reconstructed"
                    )
                    continue
                }
                val summary = summarizeFuturesCampaignTrades(
                    symbol, direction, startTime, closedAt, fillArray, initialRisk
                )
                if (summary == null) {
                    futuresCampaignCoordinator.markTradeJournalStatus(
                        clientId, "RETRY", "Exchange trade statistics failed risk-qualified revalidation"
                    )
                    continue
                }
                val trigger = intent.optString("entry_price").toDoubleOrNull()
                    ?.takeIf { it.isFinite() && it > 0.0 } ?: summary.entryPrice
                val entrySlippagePct = if (direction == "LONG") {
                    (summary.entryPrice - trigger) / trigger
                } else {
                    (trigger - summary.entryPrice) / trigger
                }
                val exitIntent = (0 until intents.length()).mapNotNull { j -> intents.optJSONObject(j) }
                    .lastOrNull {
                        it.optString("intent_role") == "EXIT" &&
                            it.optString("symbol").equals(symbol, true) &&
                            it.optString("exchange_status").equals("EXIT_CONFIRMED", true) &&
                            it.optLong("created_at_ms", 0L) in startTime..closedAt
                    }
                val reason = exitIntent?.optString("reason")?.takeIf { it.isNotBlank() }
                    ?: intent.optString("close_reason").takeIf { it.isNotBlank() }
                    ?: "EXCHANGE_RECONCILED_CLOSE"
                TradeJournal.recordExchangeCompleted(
                    prefs = prefs,
                    id = "FUTURES:" + clientId,
                    symbol = symbol,
                    direction = direction,
                    entryPrice = summary.entryPrice,
                    exitPrice = summary.exitPrice,
                    qty = summary.quantity,
                    grossPnlUsdt = summary.grossPnlUsdt,
                    feeUsdt = summary.feeUsdt,
                    feeKnown = summary.feeKnown,
                    initialRiskUsdt = initialRisk,
                    openedAtMs = summary.openedAtMs,
                    closedAtMs = summary.closedAtMs,
                    reason = reason,
                    entrySnapshot = JSONObject(intent.toString()),
                    entrySlippagePct = entrySlippagePct,
                    fillCount = summary.fillCount
                )
                futuresCampaignCoordinator.markTradeJournalStatus(
                    clientId, "RECORDED",
                    "source=Binance userTrades; fills=${summary.fillCount}; fees_known=${summary.feeKnown}"
                )
            } catch (failure: Exception) {
                futuresCampaignCoordinator.markTradeJournalStatus(
                    clientId, "RETRY",
                    failure.message ?: failure.javaClass.simpleName
                )
            }
        }
    }

    private fun manageNativeFuturesPositions(): Boolean {
        val nowMs = System.currentTimeMillis()
        val priorState = futuresCampaignCoordinator.state
        val minimumReconcileIntervalMs = when (priorState) {
            NativeFuturesCampaignCoordinator.State.READY -> 10_000L
            NativeFuturesCampaignCoordinator.State.OPEN_PROTECTED -> 3_000L
            NativeFuturesCampaignCoordinator.State.ENTRY_PENDING -> 2_500L
            NativeFuturesCampaignCoordinator.State.EXIT_PENDING -> 1_500L
            NativeFuturesCampaignCoordinator.State.POSITION_UNPROTECTED,
            NativeFuturesCampaignCoordinator.State.RECONCILE_REQUIRED,
            NativeFuturesCampaignCoordinator.State.UNINITIALIZED -> 0L
        }
        val lastSuccessfulReconcile = lastRestReconcileMs
        val privateStreamSynchronized = userStreamConnected && !userStreamSyncRequired
        if (!reconcileRequired && privateStreamSynchronized &&
            minimumReconcileIntervalMs > 0L && lastSuccessfulReconcile > 0L &&
            nowMs >= lastSuccessfulReconcile &&
            nowMs - lastSuccessfulReconcile < minimumReconcileIntervalMs
        ) {
            // Private WS trade/account events schedule immediate reconciliation. This
            // fallback cadence prevents the 1-second market scanner issuing a full
            // signed account/open-order/algo-order reconciliation on every pass.
            runCatching { journalClosedNativeFuturesTrades() }
            return true
        }
        val lastAttempt = lastNativeManagementAttemptMs
        if (lastAttempt > 0L && nowMs >= lastAttempt && nowMs - lastAttempt < 2_000L) {
            // Bound retries after transient exchange/door failures without pretending
            // the failure was cleared. A reconciliation latch still blocks all entries.
            return !reconcileRequired
        }
        lastNativeManagementAttemptMs = nowMs

        val reconciliation = reconcileFuturesThroughDoor(
            "POSITION_MANAGEMENT_RECONCILIATION",
            allowStopped = true,
            allowKillLatched = true
        )
        if (!reconciliation.optBoolean("ok", false)) {
            scannerError = reconciliation.optString("blocker", "Futures reconciliation failed")
            reconcileRequired = true
            persistSafetyFlag("reconcile_required", true)
            stateMachine.force(TradingState.RECONCILE_REQUIRED, scannerError ?: "Futures reconciliation failed")
            return false
        }
        // Never reopen entries if SQLite cannot confirm that the reconciliation
        // latch was cleared. Persist first, then update the in-memory authority.
        if (!persistSafetyFlag("reconcile_required", false)) {
            reconcileRequired = true
            val blocker = "SQLite could not persist cleared Futures reconciliation state"
            lastError = blocker
            scannerError = blocker
            stateMachine.force(TradingState.RECONCILE_REQUIRED, blocker)
            return false
        }
        reconcileRequired = false
        if (killLatched) {
            stateMachine.force(TradingState.KILL_SWITCH_LATCHED, "Kill switch remains latched; managing exposure without new entries")
        } else {
            when (futuresCampaignCoordinator.state) {
                NativeFuturesCampaignCoordinator.State.OPEN_PROTECTED ->
                    stateMachine.force(TradingState.PROTECTED, "Exchange confirms Futures position protected")
                NativeFuturesCampaignCoordinator.State.ENTRY_PENDING ->
                    stateMachine.force(TradingState.ENTRY_PENDING, "Futures entry intent pending exchange fill")
                NativeFuturesCampaignCoordinator.State.READY ->
                    stateMachine.force(TradingState.READY_FLAT, "Futures account reconciled and flat")
                NativeFuturesCampaignCoordinator.State.POSITION_UNPROTECTED,
                NativeFuturesCampaignCoordinator.State.RECONCILE_REQUIRED -> {
                    reconcileRequired = true
                    persistSafetyFlag("reconcile_required", true)
                    stateMachine.force(TradingState.RECONCILE_REQUIRED, futuresCampaignCoordinator.lastBlocker ?: "Futures protection missing")
                    return false
                }
                NativeFuturesCampaignCoordinator.State.EXIT_PENDING,
                NativeFuturesCampaignCoordinator.State.UNINITIALIZED -> {
                    reconcileRequired = true
                    persistSafetyFlag("reconcile_required", true)
                    stateMachine.force(TradingState.RECONCILE_REQUIRED, "Futures coordinator not in a tradable reconciled state")
                    return false
                }
            }
        }
        runCatching { journalClosedNativeFuturesTrades() }
            .onFailure { lastError = "Futures trade audit backfill failed: " + (it.message ?: it.javaClass.simpleName) }
        val positions = futuresCampaignCoordinator.positionsSnapshot()
        val intents = futuresCampaignCoordinator.intentsSnapshot()
        for (i in 0 until positions.length()) {
            val row = positions.optJSONObject(i)
                ?: throw IllegalStateException("Malformed Binance Futures position row during structural exit evaluation")
            val symbol = row.optString("symbol").uppercase(Locale.US)
            if (symbol !in coreSymbols) continue
            val signedQty = row.optString("positionAmt").toDoubleOrNull()
                ?: throw IllegalStateException("Malformed Binance Futures position amount for $symbol during structural exit evaluation")
            if (signedQty == 0.0) continue
            val direction = if (signedQty > 0.0) NativeFuturesCampaignCoordinator.Direction.LONG
                else NativeFuturesCampaignCoordinator.Direction.SHORT
            val dir = direction.name
            val candles = fetchCandles(symbol, "1h", 180)
            if (candles.size < 45) throw IllegalStateException(symbol + " needs at least 45 closed H1 candles")
            val latest = candles.last()
            val rules = symbolFilters(symbol)
            val contract = futuresCampaignCoordinator.contractPrice(symbol)
            // TC2 early WM1 can enter on the opposite side of Teeth. A universal
            // NTD Teeth-close would immediately liquidate a valid early campaign.
            // Existing exchange protection and the 3-5 bar trail remain active.
            val newerBarCutoff = latest.t - 60L * 60L * 1000L
            val opposing = if (direction == NativeFuturesCampaignCoordinator.Direction.LONG) {
                shortCampaignSignals(symbol, candles, "1h", rules.tick).any {
                    it.signalBarTimeMs >= newerBarCutoff &&
                        tc2OppositeBreakoutTriggered(true, contract, it.triggerPrice)
                }
            } else {
                longCampaignSignals(symbol, candles, "1h", rules.tick).any {
                    it.signalBarTimeMs >= newerBarCutoff &&
                        tc2OppositeBreakoutTriggered(false, contract, it.triggerPrice)
                }
            }
            if (opposing) {
                val closeQty = fullMarketCloseQuantity(row.optString("positionAmt"), rules)
                val clientId = ("W2O_" + symbol.take(8) + "_" + System.nanoTime().toString(16).takeLast(12)).take(36)
                val result = withCampaignMutation(
                    symbol = symbol,
                    purpose = "OPPOSING_WILLIAMS_EXIT",
                    allowPaused = true
                ) {
                    futuresCampaignCoordinator.closePosition(
                        symbol, direction, closeQty, clientId, "OPPOSITE_WILLIAMS_TRIGGERED"
                    )
                }
                if (!result.optBoolean("ok", false)) throw IllegalStateException(result.optString("blocker", result.optString("error")))
                continue
            }
            val intentIndex = latestEntryIntentIndex(intents, symbol)
            val intent = intentIndex?.let { intents.optJSONObject(it) }
                ?.takeIf {
                    it.optString("direction").equals(dir, ignoreCase = true) &&
                        campaignLifecycleStatus(it) == "OPEN_PROTECTED"
                } ?: throw IllegalStateException(symbol + " has no latest exchange-confirmed protected campaign")
            val oldStop = intent.optString("protective_stop").toDoubleOrNull()
                ?: throw IllegalStateException(symbol + " structural stop is missing")
            // Protective exchange STOP_MARKET remains MARK_PRICE-triggered:
            // trailing comparisons must use MARK_PRICE, unlike TC2 entries.
            val mark = futuresMarketData.markPrice(symbol)
            val trail = candles.takeLast(campaignTrailBars)
            val proposed = if (direction == NativeFuturesCampaignCoordinator.Direction.LONG) {
                normalizePrice((trail.minOfOrNull { it.l } ?: oldStop) - rules.tick, rules.tick)
            } else {
                normalizeTriggerPrice((trail.maxOfOrNull { it.h } ?: oldStop) + rules.tick, rules.tick)
            }
            val tighter = if (direction == NativeFuturesCampaignCoordinator.Direction.LONG) proposed > oldStop && proposed < mark
                else proposed < oldStop && proposed > mark
            if (tighter) {
                val clientId = ("W2T_" + symbol.take(8) + "_" + latest.t.toString(16)).take(36)
                val result = withCampaignMutation(
                    symbol = symbol,
                    purpose = "STRUCTURAL_STOP_TRAIL",
                    allowPaused = true
                ) {
                    futuresCampaignCoordinator.trailProtectiveStop(
                        symbol, direction, fmtPrice(proposed, rules.tick), clientId
                    )
                }
                if (!result.optBoolean("ok", false)) throw IllegalStateException(result.optString("blocker", result.optString("error")))
            }
        }
        return true
    }

    private fun submitNativeFuturesCampaignEntry(candidate: BaseAnalysis) {
        // The operator-directed mode suppresses automatic direction selection even
        // when this scan began before the mode was armed.
        if (forceTestnetMode) return
        // Entry-session overlay is UTC 08:00 inclusive to 20:00 exclusive.
        // Position protection/management and explicit exits remain active 24/7.
        if (!executionReady() || !candidate.campaignReady) return
        val sessionClockMs = runCatching { futuresMarketData.exchangeNowMs() }.getOrElse { failure ->
            lastError = "FUTURES_SESSION_CLOCK_UNAVAILABLE: " +
                (failure.message ?: failure.javaClass.simpleName)
            return
        }
        if (!isWithinWilliamsEntrySessionUtc(sessionClockMs)) {
            lastError = "WILLIAMS_ENTRY_SESSION_CLOSED: new entries allowed only from 08:00 to 20:00 UTC; position management remains active"
            return
        }

        // Re-check daily equity immediately before the exchange mutation. If the account
        // or loss baseline becomes unknown at this point, fail closed for this entry.
        val dailyLossAllowsEntry = runCatching { checkFuturesDailyLoss() }.getOrElse { failure ->
            paused = true
            prefs.edit().putBoolean("auto_run", false).commit()
            lastError = "FUTURES_DAILY_LOSS_CHECK_FAILED: " +
                (failure.message ?: failure.javaClass.simpleName)
            false
        }
        if (!dailyLossAllowsEntry) return
        if (!executionReady() || !candidate.campaignReady) return
        if (candidate.spreadPct !in 0.0..maxSpreadPct) return
        val symbol = candidate.symbol
        val topOfBook = runCatching { futuresMarketData.topOfBook(symbol) }.getOrElse { failure ->
            lastError = "FUTURES_ENTRY_TOP_OF_BOOK_FAILED: $symbol: " +
                (failure.message ?: failure.javaClass.simpleName)
            return
        }
        val currentSpreadPct = (topOfBook.second - topOfBook.first) / topOfBook.first
        if (!currentSpreadPct.isFinite() || currentSpreadPct !in 0.0..maxSpreadPct) {
            lastError = "FUTURES_ENTRY_SPREAD_BLOCKED: $symbol live spread=" + currentSpreadPct
            return
        }
        // Keep both directional hypotheses until D1/H4 context is known. Picking
        // the newest signal before applying the directional macro veto caused a
        // valid opposite-side alternative to be discarded prematurely.
        val contextNow = futuresMarketData.exchangeNowMs()
        val freshSignals = candidate.campaignSignals.filter {
            isSignalFresh(it.signalBarTimeMs, contextNow, 5L * 60L * 60L * 1000L)
        }
        if (freshSignals.isEmpty()) {
            lastError = "WILLIAMS_SIGNAL_EXPIRED: no fresh confirmed H1 hypothesis for $symbol"
            return
        }

        // Macro D1 is an airbag; H4 supplies context; H1 is the only decision TF.
        // M15 is execution/fill monitoring only; M5 remains diagnostics/replay only.
        val d1BarMs = frameSeconds("1d") * 1000L
        val h4BarMs = frameSeconds("4h") * 1000L
        val d1 = fetchCandles(symbol, "1d", 90)
            .filter { it.t > 0L && it.t + d1BarMs <= contextNow }
        val h4 = fetchCandles(symbol, "4h", 120)
            .filter { it.t > 0L && it.t + h4BarMs <= contextNow }
        if (d1.size < 45 || h4.size < 45) return
        val d1Line = alligator(d1, d1.lastIndex)
        val h4Line = alligator(h4, h4.lastIndex)
        val d1Bull = d1Line.lips > d1Line.teeth && d1Line.teeth > d1Line.jaw &&
            d1.last().c > d1Line.lips && ao(d1, d1.lastIndex) > 0.0
        val d1Bear = d1Line.lips < d1Line.teeth && d1Line.teeth < d1Line.jaw &&
            d1.last().c < d1Line.lips && ao(d1, d1.lastIndex) < 0.0
        val h4Bull = h4Line.lips > h4Line.teeth && h4Line.teeth > h4Line.jaw &&
            h4.last().c > h4Line.lips && ao(h4, h4.lastIndex) > 0.0
        val h4Bear = h4Line.lips < h4Line.teeth && h4Line.teeth < h4Line.jaw &&
            h4.last().c < h4Line.lips && ao(h4, h4.lastIndex) < 0.0
        // Apply D1/H4 context before choosing between fresh confirmed H1 hypotheses.
        // H4-aligned context ranks before a reversal; signal recency breaks ties.
        val signal = freshSignals.maxWithOrNull(
            compareBy<CampaignSignalN> {
                directionalSignalContextRank(it.type, d1Bull, d1Bear, h4Bull, h4Bear)
            }.thenBy { it.signalBarTimeMs }
        )?.takeIf {
            directionalSignalContextRank(it.type, d1Bull, d1Bear, h4Bull, h4Bear) > 0
        } ?: run {
            lastError = "WILLIAMS_DIRECTIONAL_CONTEXT_BLOCKED: neither LONG nor SHORT is allowed by D1/H4 for $symbol"
            return
        }
        val direction = when (campaignSignalDirection(signal.type)) {
            "LONG" -> NativeFuturesCampaignCoordinator.Direction.LONG
            "SHORT" -> NativeFuturesCampaignCoordinator.Direction.SHORT
            else -> {
                lastError = "WILLIAMS_SIGNAL_DIRECTION_UNKNOWN: refusing to default an unknown signal to LONG for $symbol"
                return
            }
        }

        // M15 is execution/fill monitoring only. It must not invent a second
        // directional signal or veto a causally valid H1 Williams decision.
        // Closed/fresh M15 data is still required for execution monitoring.
        val m15BarMs = frameSeconds("15m") * 1000L
        val m15 = fetchCandles(symbol, "15m", 120)
            .filter { it.t > 0L && it.t + m15BarMs <= contextNow }
        if (m15.size < 45) {
            lastError = "M15_EXECUTION_BLOCKED: insufficient closed M15 history for $symbol"
            return
        }
        val lastClosedM15At = m15.last().t + m15BarMs
        if (contextNow - lastClosedM15At > 30L * 60L * 1000L) {
            lastError = "M15_EXECUTION_BLOCKED: latest closed M15 candle is stale for $symbol"
            return
        }

        val entries = futuresCampaignCoordinator.intentsSnapshot()
        val clientId = ("W2E_" + symbol.take(7) + "_" + signal.type.take(4) + "_" + signal.signalBarTimeMs.toString(36)).take(36)
        if ((0 until entries.length()).any { entries.optJSONObject(it)?.optString("client_id") == clientId }) return

        val exchangePositions = futuresCampaignCoordinator.positionsSnapshot()
        val openPositions = (0 until exchangePositions.length()).map { index ->
            val row = exchangePositions.optJSONObject(index)
                ?: throw IllegalStateException("Malformed Binance Futures position row during entry scan")
            val amount = row.optString("positionAmt").toDoubleOrNull()
                ?: throw IllegalStateException("Malformed Binance Futures position amount during entry scan")
            row to amount
        }.filter { (_, amount) -> kotlin.math.abs(amount) > 0.0 }.map { it.first }
        val slotBlocker = futuresPositionSlotBlocker(
            symbol = symbol,
            openPositionSymbols = openPositions.map { it.optString("symbol") },
            intents = entries,
            maxPositions = maxOpenPositions
        )
        if (slotBlocker != null) {
            lastError = slotBlocker
            return
        }

        val riskUsed = (0 until entries.length()).mapNotNull { entries.optJSONObject(it) }.sumOf { intent ->
            if (intent.optString("intent_role", "ENTRY") == "ENTRY" && isEntryCampaignReserved(intent)) {
                // Missing/corrupt risk is not zero risk. Reserve the full portfolio cap
                // conservatively so a damaged journal can never authorize excess exposure.
                conservativeRiskReservation(
                    intent.optDouble("risk_pct", Double.NaN),
                    maxTotalRiskPct
                )
            } else 0.0
        }.coerceAtMost(maxTotalRiskPct)
        val riskPct = min(campaignInitialRiskPct, min(maxRiskPerTradePct, maxTotalRiskPct - riskUsed))
        if (riskPct <= 0.0) return
        val account = futuresCampaignCoordinator.accountSnapshot()
        val parsedAccount = parseFuturesAccountSnapshot(account) ?: run {
            updateCachedFuturesAccount(account)
            lastError = restAccountLastError ?: "Futures account snapshot is invalid"
            return
        }
        val equity = parsedAccount.equityUsdt
        val available = parsedAccount.availableUsdt
        if (!futuresAccountFundedForEntry(available, equity)) return
        val contract = futuresCampaignCoordinator.contractPrice(symbol)
        val rules = symbolFilters(symbol)
        val trigger = if (direction == NativeFuturesCampaignCoordinator.Direction.LONG)
            normalizeTriggerPrice(signal.triggerPrice, rules.tick) else normalizePrice(signal.triggerPrice, rules.tick)
        val stop = if (direction == NativeFuturesCampaignCoordinator.Direction.LONG)
            normalizePrice(signal.protectivePrice, rules.tick) else normalizeTriggerPrice(signal.protectivePrice, rules.tick)
        if (trigger <= 0.0 || stop <= 0.0) return
        if (direction == NativeFuturesCampaignCoordinator.Direction.LONG) {
            if (trigger <= contract || stop >= trigger) return
        } else {
            if (trigger >= contract || stop <= trigger) return
        }
        val stopFraction = kotlin.math.abs(trigger - stop) / trigger
        if (!stopFraction.isFinite() || stopFraction <= 0.0) return
        val effectiveLoss = stopFraction + 2.0 * feeBufferPerSidePct + maxSlippagePct
        val maxRiskQuote = equity * riskPct
        val notional = min(equity * 0.25, min(available * 2.0 * 0.95, maxRiskQuote / effectiveLoss))
        // STOP_MARKET entries become market executions when triggered. Size them
        // to the common exact increment of LOT_SIZE and MARKET_LOT_SIZE; using
        // LOT_SIZE alone can leave a perfectly valid pending order that Binance
        // rejects or cannot execute when its trigger fires.
        val marketLotEnabled = rules.marketStep.isFinite() && rules.marketStep > 0.0
        val quantityStep = ExecutionMath.commonQuantityStep(rules.step, rules.marketStep)
        val minQuantity = if (marketLotEnabled) max(rules.minQty, rules.marketMinQty) else rules.minQty
        val maxQuantity = if (marketLotEnabled) min(rules.maxQty, rules.marketMaxQty) else rules.maxQty
        val qty = floorStep(notional / trigger, quantityStep)
        if (!qty.isFinite() || qty < minQuantity || qty > maxQuantity ||
            !ExecutionMath.isMultiple(qty, rules.step) ||
            (marketLotEnabled && !ExecutionMath.isMultiple(qty, rules.marketStep)) ||
            qty * trigger < rules.minNotional || qty * trigger > rules.maxNotional) {
            lastError = "FUTURES_QUANTITY_FILTER_BLOCKED: $symbol quantity does not satisfy LOT_SIZE and MARKET_LOT_SIZE"
            return
        }

        val plan = NativeFuturesCampaignCoordinator.EntryPlan(
            symbol = symbol,
            direction = direction,
            kind = NativeFuturesCampaignCoordinator.EntryKind.STOP,
            quantity = fmtQty(qty, rules.decimals),
            entryPrice = fmtPrice(trigger, rules.tick),
            protectiveStop = fmtPrice(stop, rules.tick),
            clientId = clientId,
            riskPct = riskPct,
            signalType = signal.type,
            signalBarTimeMs = signal.signalBarTimeMs
        )
        val result = withCampaignMutation(
            symbol = symbol,
            purpose = "WILLIAMS_PENDING_ENTRY"
        ) {
            // A scan may have started just before an operator enabled manual direction.
            // Reject the automatic entry safely instead of trading the opposite side.
            if (forceTestnetMode) {
                return@withCampaignMutation JSONObject()
                    .put("accepted", false)
                    .put("safe_rejection", true)
                    .put("mutation_attempted", false)
                    .put("blocker", "TESTNET_FORCE_MODE_ACTIVE: automatic entries suspended")
            }
            // Revalidate the H1 scenario at the last possible point before mutation.
            // A queued/stale candidate must never survive a slow scan or a door wait.
            val submitNow = futuresMarketData.exchangeNowMs()
            if (!isWithinWilliamsEntrySessionUtc(submitNow)) {
                return@withCampaignMutation JSONObject()
                    .put("accepted", false)
                    .put("safe_rejection", true)
                    .put("mutation_attempted", false)
                    .put("blocker", "WILLIAMS_ENTRY_SESSION_CLOSED: 08:00–20:00 UTC")
            }
            require(isSignalFresh(signal.signalBarTimeMs, submitNow, 5L * 60L * 60L * 1000L)) {
                "STALE_H1_SCENARIO: signal expired before Futures submission"
            }
            val submitM15 = fetchCandles(symbol, "15m", 120)
                .filter { it.t > 0L && it.t + m15BarMs <= submitNow }
            require(submitM15.size >= 45) {
                "M15_EXECUTION_BLOCKED: closed M15 history unavailable at submission"
            }
            val submitM15Last = submitM15.last()
            require(submitNow - (submitM15Last.t + m15BarMs) <= 30L * 60L * 1000L) {
                "M15_EXECUTION_BLOCKED: latest closed M15 candle is stale at submission"
            }
            // Revalidate the actual H1 trigger against the latest Futures mark
            // after network/door waits. Never convert a consumed stop-entry into a late
            // entry at the current market price.
            val submitContract = futuresCampaignCoordinator.contractPrice(symbol)
            require(
                if (direction == NativeFuturesCampaignCoordinator.Direction.LONG) submitContract < trigger
                else submitContract > trigger
            ) {
                "H1_TRIGGER_ALREADY_CROSSED: $symbol contract=$submitContract trigger=$trigger; pending entry not submitted"
            }
            // M15 contributes only execution readiness, never a new direction.
            futuresCampaignCoordinator.submitPendingEntry(plan)
        }
        lastOrder = result
        if (!result.optBoolean("accepted", false)) {
            lastError = result.optString("blocker", result.optString("error"))
            if (futuresCampaignCoordinator.state == NativeFuturesCampaignCoordinator.State.RECONCILE_REQUIRED) {
                reconcileRequired = true
                persistSafetyFlag("reconcile_required", true)
                stateMachine.force(TradingState.RECONCILE_REQUIRED, lastError ?: "Futures submission unresolved")
            }
        } else {
            // Force the next management pass to reconcile the new client id with
            // Binance before another symbol can be admitted. A fresh POST response
            // alone must not let the scanner skip position/protection reconciliation.
            lastRestReconcileMs = 0L
            stateMachine.force(TradingState.ENTRY_PENDING, "Futures conditional entry submitted; waiting for exchange fill/reconciliation")
        }
    }

    private fun scanDailyRiskAllowsEntry(): Boolean {
        val nowMs = System.currentTimeMillis()
        synchronized(this) {
            val checkedAt = lastScanDailyRiskCheckAtMs
            if (checkedAt > 0L && nowMs >= checkedAt && nowMs - checkedAt < 5_000L) {
                return lastScanDailyRiskCheckAllowed
            }
            // Reserve the refresh slot before network I/O; entry submission has its own fresh check.
            lastScanDailyRiskCheckAtMs = nowMs
        }
        val allowed = runCatching { checkFuturesDailyLoss() }.getOrElse { failure ->
            lastError = "FUTURES_DAILY_LOSS_CHECK_FAILED: " +
                (failure.message ?: failure.javaClass.simpleName)
            false
        }
        synchronized(this) {
            lastScanDailyRiskCheckAllowed = allowed
            lastScanDailyRiskCheckAtMs = System.currentTimeMillis()
        }
        return allowed
    }

    private fun scheduleNativePositionManagement() {
        synchronized(this) {
            if (nativeManagementRunning) return
            nativeManagementRunning = true
        }
        Thread {
            try {
                val managementOk = runCatching { manageNativeFuturesPositions() }.getOrElse { failure ->
                    lastError = "Futures position monitor: " +
                        (failure.message ?: failure.javaClass.simpleName)
                    false
                }
                runCatching { cancelPendingEntriesInvalidatedByMarket() }
                    .onFailure { failure ->
                        lastError = lastError ?: "TC2 pending-entry invalidation monitor: " +
                            (failure.message ?: failure.javaClass.simpleName)
                    }
                runCatching { cancelPendingEntriesOutsideWilliamsSession() }
                    .onFailure { failure ->
                        lastError = lastError ?: "Futures pending-entry monitor: " +
                            (failure.message ?: failure.javaClass.simpleName)
                    }
                if (!managementOk) {
                    scannerError = scannerError
                        ?: futuresCampaignCoordinator.lastBlocker
                        ?: "Futures position reconciliation/protection incomplete"
                }
            } finally {
                synchronized(this) { nativeManagementRunning = false }
            }
        }.apply {
            isDaemon = true
            name = "williams-futures-position-management"
            start()
        }
    }

    private fun performCampaignScan() {
        if (!running) {
            scannerState = "STOPPED"
            scannerError = "Runtime is stopped"
            return
        }

        // Market discovery must not be serialized behind account income, position
        // management, or entry-risk checks. Those checks still run immediately before
        // every order mutation through Execution Door; scanning remains read-only.
        scheduleNativePositionManagement()

        val universe = loadUniverse()
        scanSymbols = universe.first.toMutableList()
        val volumes = universe.second
        val spreads = universe.third
        val futures = scanSymbols.map { symbol ->
            scanExecutor.submit(Callable {
                try {
                    val candles = fetchCandles(symbol, "1h", 180)
                    if (candles.size < 45) throw IllegalStateException("Insufficient closed H1 candles: " + candles.size)
                    val result = analyseBase(symbol, candles, spreads[symbol] ?: -1.0, volumes[symbol] ?: 0.0)
                    // Every approved symbol publishes independently evaluated D1/H4/H1/M15
                    // closed-candle wave evidence; never infer a higher TF from H1 data.
                    val evidence = diagnosticWaveFrames(symbol, result.candles)
                    markScanProgress()
                    result.copy(waveFrames = evidence)
                } catch (x: Exception) {
                    markScanProgress()
                    scannerError = symbol + ": " + (x.message ?: x.javaClass.simpleName)
                    null
                }
            })
        }
        val ranked = mutableListOf<BaseAnalysis>()
        // Bound the whole symbol fan-out, not each Future independently. A per-symbol
        // 30s timeout multiplied by 10 contracts could leave the cockpit showing
        // RUNNING for five minutes when one or more REST requests stall.
        val scanDeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(60L)
        for ((index, future) in futures.withIndex()) {
            val remainingNanos = scanDeadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) {
                futures.drop(index).forEach { it.cancel(true) }
                scannerError = scannerError ?: "Futures H1 scan exceeded 60s total deadline; remaining symbols were not completed"
                break
            }
            runCatching {
                future.get(remainingNanos, TimeUnit.NANOSECONDS)
            }.getOrElse {
                future.cancel(true)
                scannerError = scannerError ?: "Futures H1 scanner timeout within 60s total deadline: " +
                    (it.message ?: it.javaClass.simpleName)
                null
            }?.let { if (it is BaseAnalysis) ranked.add(it) }
        }
        candidates = JSONArray().apply {
            ranked.sortedWith(compareByDescending<BaseAnalysis> { it.campaignReady }
                .thenByDescending { it.score }.thenBy { it.wave.exhaustionRisk }.thenBy { it.spreadPct })
                .take(20).forEach { put(toJson(it)) }
        }
        lastSymbolsScanned = ranked.size
        lastScanAt = System.currentTimeMillis()
        scanRunId = "futures-" + lastScanAt
        runCatching { PositionsWidgetProvider.refresh(context) }
        runCatching { primaryCandles = fetchCandles(primarySymbol, "1h", 180) }

        // Never overwrite a safety blocker with READY merely because market data was
        // scanned. Market-analysis success and permission to execute are separate facts.
        val coordinatorStateForCompletion = runCatching {
            futuresCampaignCoordinator.status().optString("state", "RECONCILE_REQUIRED")
                .uppercase(Locale.US)
        }.getOrDefault("RECONCILE_REQUIRED")
        val completeScan = ranked.size == coreSymbols.size
        val executionBlocker = scannerExecutionBlocker(
            paused = paused,
            reconcileRequired = reconcileRequired,
            killLatched = killLatched,
            accountReady = restAccountReady,
            marketDataFresh = marketDataFresh(),
            coordinatorState = coordinatorStateForCompletion
        )
        val missing = coreSymbols - ranked.map { it.symbol }.toSet()
        scannerState = when {
            !completeScan && ranked.isEmpty() -> "FAILED"
            !completeScan && executionBlocker != null -> "PARTIAL_BLOCKED"
            !completeScan -> "PARTIAL"
            executionBlocker != null -> "READY_BLOCKED"
            else -> "READY"
        }
        scannerError = when {
            executionBlocker != null -> {
                when (executionBlocker) {
                    "BLOCKED_KILL" -> "Kill switch is latched; read-only market scan completed, new entries remain disabled"
                    "BLOCKED_FUTURES_RECONCILIATION" -> futuresCampaignCoordinator.lastBlocker
                        ?: lastError
                        ?: "Futures positions/orders/protection reconciliation is required"
                    "PAUSED" -> lastError ?: "Runtime is paused; read-only market scan completed"
                    "BLOCKED_ACCOUNT" -> restAccountLastError ?: "Futures account snapshot is not ready; entries remain disabled"
                    "WAITING_FOR_MARKET_DATA" -> restLastError ?: "Futures market data is stale; entries remain disabled"
                    else -> executionBlocker
                }
            }
            !completeScan -> "Futures H1 scan incomplete. Missing: " + missing.joinToString(",")
            else -> null
        }
        if (forceTestnetMode) {
            scannerState = if (completeScan) "READY_BLOCKED" else "PARTIAL_BLOCKED"
            scannerError = "TESTNET_FORCE_MODE_ACTIVE: automatic entries suspended; select an explicit LONG/SHORT H1 entry in Cockpit"
            return
        }
        if (executionBlocker != null) return
        if (!completeScan) return
        if (!restAccountReady || !marketDataFresh()) return
        if (futuresCampaignCoordinator.state !in setOf(
                NativeFuturesCampaignCoordinator.State.READY,
                NativeFuturesCampaignCoordinator.State.OPEN_PROTECTED,
                NativeFuturesCampaignCoordinator.State.ENTRY_PENDING
            ) || !campaignCoordinatorReadyForEntry()
        ) return
        // Fetch the exchange snapshot exactly once. The previous implementation
        // performed a signed REST request for every array element while iterating;
        // this multiplied latency and rate-limit usage by the account's row count.
        val livePositionSnapshot = futuresCampaignCoordinator.positionsSnapshot()
        val openSymbols = mutableSetOf<String>()
        for (index in 0 until livePositionSnapshot.length()) {
            val row = livePositionSnapshot.optJSONObject(index)
                ?: throw IllegalStateException("Malformed Futures position snapshot row at index $index")
            val amount = row.optString("positionAmt").toDoubleOrNull()
                ?: throw IllegalStateException("Malformed Futures position amount for " + row.optString("symbol"))
            require(amount.isFinite()) {
                "Non-finite Futures position amount for " + row.optString("symbol")
            }
            if (amount == 0.0) continue
            val symbol = row.optString("symbol").uppercase(Locale.US)
            require(symbol in coreSymbols) {
                "Unapproved live Futures position $symbol detected during scan; reconciliation required"
            }
            openSymbols.add(symbol)
        }
        // A pending conditional order reserves its symbol before a position appears
        // in positionRisk. Excluding these symbols here lets the next scan consider
        // other contracts instead of repeatedly hitting the same occupied slot.
        val existingCampaignIntents = futuresCampaignCoordinator.intentsSnapshot()
        for (index in 0 until existingCampaignIntents.length()) {
            val intent = existingCampaignIntents.optJSONObject(index) ?: continue
            if (intent.optString("intent_role", "ENTRY") != "ENTRY" || !isEntryCampaignReserved(intent)) continue
            val reservedSymbol = intent.optString("symbol").uppercase(Locale.US)
            if (reservedSymbol in coreSymbols) openSymbols.add(reservedSymbol)
        }
        for (candidate in ranked
            .filter { it.campaignReady && it.symbol !in openSymbols }
            .sortedWith(compareByDescending<BaseAnalysis> { it.score }.thenBy { it.wave.exhaustionRisk })
        ) {
            val intentsBefore = futuresCampaignCoordinator.intentsSnapshot().length()
            submitNativeFuturesCampaignEntry(candidate)
            val intentsAfter = futuresCampaignCoordinator.intentsSnapshot().length()
            if (futuresCampaignCoordinator.state == NativeFuturesCampaignCoordinator.State.RECONCILE_REQUIRED ||
                !executionReady()
            ) break
            // At most one new account mutation per scan pass. If a candidate is
            // rejected before its intent is persisted, try the next valid symbol
            // instead of treating an older pending campaign as this attempt.
            if (intentsAfter > intentsBefore) break
        }
    }

    @Volatile private var lastWilliamsPendingInvalidationCheckMs: Long = 0L

    /**
     * A stop-entry that has not filled is still a hypothesis. If price first
     * violates its signal-bar protective extreme, or the order becomes stale,
     * cancel that symbol's pending order(s) with exchange acknowledgment.
     * Do NOT liquidate existing positions; coordinator handles partial fills
     * and unknown cancel results by requiring reconciliation.
     *
     * This is a conservative subset of the full book invalidation lifecycle:
     * WM1 angulation/context changes and H4/D1 wave reclassification still
     * require separately validated source-derived replay fixtures.
     */
    private fun cancelPendingEntriesInvalidatedByMarket() {
        val nowMs = System.currentTimeMillis()
        val elapsed = nowMs - lastWilliamsPendingInvalidationCheckMs
        if (elapsed in 0 until 30_000L) return
        lastWilliamsPendingInvalidationCheckMs = nowMs

        val intents = futuresCampaignCoordinator.intentsSnapshot()
        val pendingStatuses = setOf(
            "NEW", "PENDING", "WORKING", "PARTIALLY_FILLED",
            "SUBMISSION_UNKNOWN", "CANCEL_SUBMISSION_UNKNOWN", "ACCEPTED", "TRIGGERED"
        )
        val pending = (0 until intents.length()).mapNotNull { intents.optJSONObject(it) }
            .filter {
                it.optString("intent_role", "ENTRY") == "ENTRY" &&
                    campaignLifecycleStatus(it) == "ENTRY_PENDING" &&
                    exchangeOrderStatus(it).uppercase(Locale.US) in pendingStatuses
            }
        if (pending.isEmpty()) return

        val exchangeNow = futuresMarketData.exchangeNowMs()
        for (intent in pending) {
            val symbol = intent.optString("symbol").uppercase(Locale.US)
            if (symbol !in coreSymbols) continue
            val direction = intent.optString("direction").uppercase(Locale.US)
            val submittedAt = intent.optLong("created_at_ms", 0L)
            val stop = intent.optString("initial_protective_stop",
                intent.optString("protective_stop")).toDoubleOrNull()
            val signalType = intent.optString("signal_type").uppercase(Locale.US)
            val invalidMetadata = direction !in setOf("LONG", "SHORT") ||
                submittedAt <= 0L || submittedAt > exchangeNow ||
                stop == null || !stop.isFinite() || stop <= 0.0 ||
                (signalType.isNotBlank() && campaignSignalDirection(signalType) != direction)
            val expired = !invalidMetadata && exchangeNow - submittedAt >
                5L * 60L * 60L * 1000L
            // The historical signal/side are persisted BEFORE the Binance POST.
            // Only orders carrying validated TC2 provenance are re-evaluated
            // against live H4/D1 context. Untagged manual/legacy intents
            // are not retrospectively assigned an invented signal family.
            var directionalVeto = false
            if (!invalidMetadata && !expired && signalType.isNotBlank()) {
                val d1 = fetchCandles(symbol, "1d", startupHistoryLimit("1d"))
                    .filter { it.t > 0L && exchangeNow - it.t >= frameSeconds("1d") * 1000L }
                val h4 = fetchCandles(symbol, "4h", startupHistoryLimit("4h"))
                    .filter { it.t > 0L && exchangeNow - it.t >= frameSeconds("4h") * 1000L }
                // Data disagreement or unavailable context is NOT an affirmative
                // opposite-side signal. Do not fabricate directional decisions.
                if (d1.size >= 45 && h4.size >= 45 &&
                    candleSeriesCurrent(d1, "1d", exchangeNow) &&
                    candleSeriesCurrent(h4, "4h", exchangeNow)
                ) {
                    val d1Lines = alligator(d1, d1.lastIndex)
                    val h4Lines = alligator(h4, h4.lastIndex)
                    val d1Bull = d1Lines.lips > d1Lines.teeth && d1Lines.teeth > d1Lines.jaw &&
                        d1.last().c > d1Lines.lips && ao(d1, d1.lastIndex) > 0.0
                    val d1Bear = d1Lines.lips < d1Lines.teeth && d1Lines.teeth < d1Lines.jaw &&
                        d1.last().c < d1Lines.lips && ao(d1, d1.lastIndex) < 0.0
                    val h4Bull = h4Lines.lips > h4Lines.teeth && h4Lines.teeth > h4Lines.jaw &&
                        h4.last().c > h4Lines.lips && ao(h4, h4.lastIndex) > 0.0
                    val h4Bear = h4Lines.lips < h4Lines.teeth && h4Lines.teeth < h4Lines.jaw &&
                        h4.last().c < h4Lines.lips && ao(h4, h4.lastIndex) < 0.0
                    directionalVeto = directionalSignalContextRank(
                        signalType, d1Bull, d1Bear, h4Bull, h4Bear
                    ) == 0
                }
            }
            // An entry hypothesis is also invalidated when an H1 closing
            // extreme actually touches its structural protection. Only
            // fully closed bars newer than signal time are relevant.
            val signalTime = intent.optLong("signal_bar_time_ms", 0L)
            val crossedByClosedH1 = if (!invalidMetadata && signalTime > 0L) {
                fetchCandles(symbol, "1h", startupHistoryLimit("1h"))
                    .filter { it.t > signalTime &&
                        it.t > 0L && exchangeNow - it.t >= frameSeconds("1h") * 1000L }
                    .any { if (direction == "LONG") it.l <= stop!! else it.h >= stop!! }
            } else false
            // A current mark-price breach is independent of H1 close timing.
            // Do not invent a price when Binance is unavailable.
            val contract = if (invalidMetadata || expired) 0.0
                else futuresCampaignCoordinator.contractPrice(symbol)
            if (!invalidMetadata && !expired && (!contract.isFinite() || contract <= 0.0)) {
                throw IllegalStateException("$symbol pending-entry invalidation has no valid contract price")
            }
            val crossedStop = !invalidMetadata && !expired && (
                (direction == "LONG" && contract <= stop!!) ||
                (direction == "SHORT" && contract >= stop!!)
            )
            if (!invalidMetadata && !expired && !crossedStop &&
                !crossedByClosedH1 && !directionalVeto) continue

            val reason = when {
                invalidMetadata -> "TC2_PENDING_INVALID_METADATA"
                expired -> "TC2_PENDING_EXPIRED_5H"
                directionalVeto -> "TC2_PENDING_DIRECTION_CONTEXT_VETO"
                crossedByClosedH1 -> "TC2_PENDING_CLOSED_H1_INVALIDATION"
                else -> "TC2_PENDING_SIGNAL_EXTREME_INVALIDATED"
            }
            val result = withCampaignMutation(
                symbol = symbol,
                purpose = "TC2_INVALIDATED_PENDING_CANCEL",
                allowPaused = true,
                allowStopped = true,
                allowKillLatched = true,
                allowReconcileRequired = true,
                waitForDoorMs = 50_000L
            ) {
                futuresCampaignCoordinator.cancelPendingEntries(reason, setOf(symbol))
            }
            if (!result.optBoolean("ok", false)) {
                reconcileRequired = true
                persistSafetyFlag("reconcile_required", true)
                val message = "$symbol pending-entry cancel unresolved: " +
                    result.optString("blocker", result.optString("error", reason))
                lastError = message
                stateMachine.force(TradingState.RECONCILE_REQUIRED, message)
                throw IllegalStateException(message)
            }
        }
    }

    private fun cancelPendingEntriesOutsideWilliamsSession() {
        val exchangeNow = runCatching { futuresMarketData.exchangeNowMs() }.getOrElse { failure ->
            lastError = "FUTURES_SESSION_CLOCK_UNAVAILABLE_FOR_PENDING_CANCEL: " +
                (failure.message ?: failure.javaClass.simpleName)
            return
        }
        if (isWithinWilliamsEntrySessionUtc(exchangeNow)) return

        val intents = runCatching { futuresCampaignCoordinator.intentsSnapshot() }.getOrElse { failure ->
            lastError = "FUTURES_PENDING_ENTRY_SNAPSHOT_FAILED: " +
                (failure.message ?: failure.javaClass.simpleName)
            return
        }
        val pendingStatuses = setOf(
            "NEW", "PENDING", "WORKING", "PARTIALLY_FILLED",
            "SUBMISSION_UNKNOWN", "CANCEL_SUBMISSION_UNKNOWN", "ACCEPTED", "TRIGGERED"
        )
        val hasPendingEntry = (0 until intents.length()).any { i ->
            val intent = intents.optJSONObject(i) ?: return@any true
            intent.optString("intent_role", "ENTRY") == "ENTRY" &&
                campaignLifecycleStatus(intent) == "ENTRY_PENDING" &&
                exchangeOrderStatus(intent).uppercase(Locale.US) in pendingStatuses
        }
        if (!hasPendingEntry) return

        val result = runCatching {
            withCampaignMutation(
                symbol = "FUTURES_ACCOUNT",
                purpose = "OUT_OF_SESSION_CANCEL_PENDING_ENTRIES",
                allowPaused = true,
                allowStopped = true,
                allowKillLatched = true,
                allowReconcileRequired = true,
                waitForDoorMs = 50_000L
            ) {
                futuresCampaignCoordinator.cancelPendingEntries("OUTSIDE_WILLIAMS_ENTRY_SESSION")
            }
        }.getOrElse { failure ->
            JSONObject().put("ok", false).put("error", failure.message ?: failure.javaClass.simpleName)
        }
        if (!result.optBoolean("ok", false)) {
            lastError = "OUT_OF_SESSION_PENDING_CANCEL_UNRESOLVED: " +
                result.optString("blocker", result.optString("error", "unknown Futures cancellation state"))
            scannerError = lastError
        }
    }

    /** Canonical scanner entrypoint; legacy Spot-era scan/entry code has been removed. */
    private fun performScan() {
        performCampaignScan()
    }

    /**
     * Retired Spot-era order entry. All new entries must go through the Futures
     * CampaignEngine, Execution Door, risk reservation, and USD-M Futures adapter.
     */
    private fun submitOrderIntent(@Suppress("UNUSED_PARAMETER") candidate: BaseAnalysis) {
        error("Legacy order-intent execution is disabled; CampaignEngine is canonical")
    }

    // Retired Spot OCO trailing/market-exit path removed. Futures position management
    // is handled by manageNativeFuturesPositions() through the Execution Door.

    private fun recordCompletedTrade(
        stored: PositionState,
        exitPrice: Double,
        reason: String,
        raw: JSONObject?
    ) {
        val closedAt = System.currentTimeMillis()
        val (feeUsdt, feeKnown) =
            auditStore.estimateFeesUsdt(
                symbol = stored.symbol,
                openedAt = stored.openedAt,
                closedAt = closedAt,
                exitPrice = exitPrice
            )
        // Legacy PositionState carries the original entry signal type, allowing the
        // journal to distinguish a SELL-opened SHORT from a BUY-opened LONG.
        val direction = if (stored.signalType.startsWith("SHORT_", ignoreCase = true) ||
            stored.signalType.contains("_SHORT", ignoreCase = true)
        ) "SHORT" else "LONG"
        val estimatedInitialRisk = (stored.entry * stored.qty * stored.riskPct)
            .takeIf { it.isFinite() && it > 0.0 }
            ?: (kotlin.math.abs(stored.entry - stored.stop) * stored.qty)
        val metrics = calculateDirectionalTradeMetrics(
            direction = direction,
            entryPrice = stored.entry,
            exitPrice = exitPrice,
            quantity = stored.qty,
            feeUsdt = feeUsdt,
            initialRiskUsdt = estimatedInitialRisk
        )
        val gross = metrics.grossPnlUsdt
        val net = metrics.netPnlUsdt
        val rMultiple = metrics.rMultiple
        val outcome = metrics.outcome
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
            feeKnown = feeKnown,
            side = if (direction == "SHORT") "SELL" else "BUY"
        )
    }

    private fun scheduleFuturesStreamReconciliation(reason: String, force: Boolean = false) {
        synchronized(this) {
            userStreamSyncRequired = true
            lastUserStreamSyncAtMs = 0L
            if (futuresStreamReconcileRunning) {
                futuresStreamReconcilePending = true
                return
            }
            val nowMs = System.currentTimeMillis()
            if (!force && !shouldRetryUserStreamReconciliation(
                    connected = userStreamConnected,
                    syncRequired = userStreamSyncRequired,
                    reconciliationRunning = futuresStreamReconcileRunning,
                    lastAttemptMs = lastUserStreamReconcileAttemptMs,
                    nowMs = nowMs
                )) return
            lastUserStreamReconcileAttemptMs = nowMs
            userStreamReconcileStartedAtMs = nowMs
            userStreamReconcileFailure = null
            futuresStreamReconcileRunning = true
            futuresStreamReconcilePending = false
        }
        Thread {
            try {
                while (true) {
                    val connectionGenerationForAttempt = synchronized(this) {
                        futuresStreamReconcilePending = false
                        userStreamConnectionGeneration
                    }
                    val result = reconcileFuturesThroughDoor(
                        "USER_STREAM_RECONCILIATION",
                        allowStopped = true,
                        allowKillLatched = true
                    )
                    check(result.optBoolean("ok", false)) {
                        result.optString("blocker", "Futures reconciliation returned not-ok")
                    }
                    val repeat = synchronized(this) {
                        if (futuresStreamReconcilePending) {
                            true
                        } else if (!mayCommitUserStreamSync(
                                connected = userStreamConnected,
                                reconciliationPending = futuresStreamReconcilePending,
                                attemptGeneration = connectionGenerationForAttempt,
                                currentGeneration = userStreamConnectionGeneration
                            )
                        ) {
                            // This attempt may finish just as the socket changes. Never let
                            // an old-session REST snapshot clear the current session's latch.
                            userStreamSyncRequired = true
                            futuresStreamReconcileRunning = false
                            false
                        } else {
                            userStreamSyncRequired = false
                            lastUserStreamSyncAtMs = System.currentTimeMillis()
                            futuresStreamReconcileRunning = false
                            false
                        }
                    }
                    if (!repeat) break
                }
            } catch (failure: Exception) {
                val failureReason = "Futures user-stream reconciliation failed (" + reason + "): " +
                    (failure.message ?: failure.javaClass.simpleName)
                // Publish the actual failure to diagnostics before changing the safety latch.
                // Previously this path set the global blocker but left user_stream_last_error
                // null, hiding the cause of a permanently blocked private-stream sync.
                userStreamReconcileFailure = failureReason
                userStreamLastError = failureReason
                lastError = failureReason
                setReconcileRequired(failureReason)
            } finally {
                synchronized(this) {
                    futuresStreamReconcileRunning = false
                    futuresStreamReconcilePending = false
                    userStreamReconcileFinishedAtMs = System.currentTimeMillis()
                }
            }
        }.apply {
            isDaemon = true
            name = "williams-futures-stream-reconcile"
            start()
        }
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
            "ORDER_TRADE_UPDATE", "ACCOUNT_UPDATE", "ALGO_UPDATE",
            "ACCOUNT_CONFIG_UPDATE", "CONDITIONAL_ORDER_TRIGGER_REJECT" -> {
                scheduleFuturesStreamReconciliation(eventType)
                return
            }
            "listenKeyExpired" -> {
                // Binance sends this event before the private connection may close.
                // Do not stop the entire Android runtime: the stream worker creates a
                // fresh listenKey, reconnects, and the onOpen reconciliation keeps the
                // Execution Gate closed until REST confirms orders, positions and stops.
                userStreamSyncRequired = true
                userStreamLastError = "Futures listenKey expired; reconnecting and reconciling"
                lastError = userStreamLastError
                return
            }
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

                if (
                    campaignEngineEnabled &&
                    (
                        clientId.startsWith("W5E_") ||
                            clientId.startsWith("W5S_") ||
                            clientId.startsWith("W5X_")
                    )
                ) {
                    Thread {
                        runCatching {
                            Thread.sleep(120L)
                            requireSuccessfulFuturesReconciliation("FUTURES_CAMPAIGN_EVENT_RECONCILIATION")
                        }.onFailure {
                            if (!killLatched) {
                                setReconcileRequired(
                                    "campaign executionReport reconciliation failed: " +
                                        (it.message ?: it.javaClass.simpleName)
                                )
                            }
                        }
                    }.apply {
                        isDaemon = true
                        name = "williams-campaign-execution-reconcile"
                        start()
                    }
                    return
                }

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

    /** Private-stream recovery must reconcile Futures orders/positions through the Execution Door. */
    private fun recoverPendingEntries() {
        requireSuccessfulFuturesReconciliation("USER_STREAM_PENDING_RECOVERY")
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
    /** Retired open-order audit delegates to the exchange-authoritative Futures coordinator. */
    private fun auditManagedOpenOrders() {
        requireSuccessfulFuturesReconciliation("LEGACY_OPEN_ORDER_AUDIT")
    }

    /** Exchange reconciliation is exclusively owned by the USD-M Futures coordinator. */
    private fun reconcilePositionsWithExchange() {
        requireSuccessfulFuturesReconciliation("LEGACY_POSITION_RECONCILIATION_REQUEST")
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

    private fun recoverMissingStructuralStop(
        symbol: String,
        direction: String,
        markPrice: Double,
        quantity: Double,
        entryPrice: Double
    ): RecoveredStructuralStop? = runCatching {
        require(symbol.uppercase(Locale.US) in coreSymbols)
        require(markPrice.isFinite() && markPrice > 0.0)
        require(quantity.isFinite() && quantity > 0.0)
        require(entryPrice.isFinite() && entryPrice > 0.0)

        val nowMs = futuresMarketData.exchangeNowMs()
        val oneHourMs = frameSeconds("1h") * 1000L
        val closed = fetchCandles(symbol, "1h", 180).filter { candle ->
            futuresMarketData.isCandleOpenTimeClosed(candle.t, oneHourMs, nowMs)
        }
        require(closed.size >= 5) { "Five confirmed H1 bars are required for structural-stop recovery" }
        val latest = closed.last()
        require(nowMs >= latest.t + oneHourMs &&
            nowMs - (latest.t + oneHourMs) <= 2L * oneHourMs
        ) { "Latest closed H1 structure is stale" }

        val rules = symbolFilters(symbol)
        require(rules.tick.isFinite() && rules.tick > 0.0)
        val structure = closed.takeLast(5)
        val rawStop = when (direction.uppercase(Locale.US)) {
            "LONG" -> (structure.minOfOrNull { it.l } ?: return@runCatching null) - rules.tick
            "SHORT" -> (structure.maxOfOrNull { it.h } ?: return@runCatching null) + rules.tick
            else -> return@runCatching null
        }
        val stop = when (direction.uppercase(Locale.US)) {
            "LONG" -> normalizePrice(rawStop, rules.tick)
            "SHORT" -> normalizeTriggerPrice(rawStop, rules.tick)
            else -> return@runCatching null
        }
        require(stop.isFinite() && stop > 0.0 &&
            isProtectiveStopOnCorrectSide(direction, markPrice.toString(), stop.toString())
        ) { "Closed H1 structure cannot produce a stop on the protective side of current Futures mark" }
        if (rules.minPrice > 0.0) require(stop >= rules.minPrice)
        if (rules.maxPrice.isFinite()) require(stop <= rules.maxPrice)

        val account = parseFuturesAccountSnapshot(futuresCampaignCoordinator.accountSnapshot())
            ?: throw IllegalStateException("Futures account equity is unavailable for recovery-risk calculation")
        val riskPct = (kotlin.math.abs(entryPrice - stop) * quantity / account.equityUsdt)
        require(riskPct.isFinite() && riskPct > 0.0) {
            "Recovered structural-stop risk is unknown"
        }
        RecoveredStructuralStop(
            stopPrice = fmtPrice(stop, rules.tick),
            riskPct = riskPct
        )
    }.getOrNull()

    private fun normalizePrice(price: Double, tick: Double): Double {
        if (price <= 0.0 || tick <= 0.0) return price
        return floor(price / tick + 1e-12) * tick
    }

    private fun normalizeTriggerPrice(price: Double, tick: Double): Double {
        if (price <= 0.0 || tick <= 0.0) return price
        return ceil(price / tick - 1e-12) * tick
    }

    private fun symbolFilters(symbol: String): SymbolRules {
        val normalized = symbol.uppercase(Locale.US)
        require(normalized in coreSymbols) { "Symbol is outside the approved Futures universe" }
        val now = System.currentTimeMillis()
        val cached = futuresExchangeInfoCache
        val info = if (cached != null && now - cached.first < 300_000L) cached.second else {
            val loaded = futuresMarketData.exchangeInfo()
            futuresExchangeInfoCache = now to loaded
            loaded
        }
        val row = (info.optJSONArray("symbols") ?: JSONArray()).let { rows ->
            (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
                .firstOrNull { it.optString("symbol").uppercase(Locale.US) == normalized }
        } ?: throw IllegalStateException("Futures exchangeInfo has no contract $normalized")
        require(row.optString("status").equals("TRADING", true)) { "Futures contract $normalized is not TRADING" }
        val filters = row.optJSONArray("filters") ?: JSONArray()
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
            val f = filters.optJSONObject(i) ?: continue
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
                "MIN_NOTIONAL", "NOTIONAL" -> {
                    minNotional = max(minNotional, f.optString("notional", f.optString("minNotional")).toDoubleOrNull() ?: 0.0)
                    maxNotional = min(maxNotional, f.optString("maxNotional").toDoubleOrNull() ?: Double.POSITIVE_INFINITY)
                }
                "PERCENT_PRICE" -> {
                    percentUp = f.optString("multiplierUp").toDoubleOrNull() ?: percentUp
                    percentDown = f.optString("multiplierDown").toDoubleOrNull() ?: percentDown
                    avgPriceMins = f.optInt("avgPriceMins", avgPriceMins)
                }
                "MAX_NUM_ORDERS" -> maxNumOrders = f.optInt("limit", f.optInt("maxNumOrders", maxNumOrders))
                "MAX_NUM_ALGO_ORDERS" -> maxNumAlgoOrders = f.optInt("limit", f.optInt("maxNumAlgoOrders", maxNumAlgoOrders))
                "MAX_POSITION" -> maxPosition = f.optString("maxPosition").toDoubleOrNull() ?: maxPosition
            }
        }
        require(step > 0.0 && tick > 0.0 && minQty > 0.0) { "Futures contract filters are incomplete for $normalized" }
        val decimals = ExecutionMath.decimalPlacesForIncrement(step)
        return SymbolRules(
            step = step, tick = tick, decimals = decimals, minQty = minQty, maxQty = maxQty,
            marketStep = marketStep, marketMinQty = marketMinQty, marketMaxQty = marketMaxQty,
            minPrice = minPrice, maxPrice = maxPrice, minNotional = minNotional, maxNotional = maxNotional,
            quoteOrderQtyMarketAllowed = false, ocoAllowed = false,
            percentUp = percentUp, percentDown = percentDown, bidPercentUp = bidUp, bidPercentDown = bidDown,
            askPercentUp = askUp, askPercentDown = askDown, avgPriceMins = avgPriceMins,
            maxNumOrders = maxNumOrders, maxNumAlgoOrders = maxNumAlgoOrders,
            maxNumOrderLists = maxNumOrderLists, maxPosition = maxPosition
        )
    }

    private fun floorStep(value: Double, step: Double): Double =
        if (step <= 0.0) value else ExecutionMath.floorToStep(value, step)

    private fun fmtQty(value: Double, decimals: Int): String =
        ExecutionMath.floorToScale(value, decimals)

    /**
     * A full-position market exit must preserve the exchange position amount exactly.
     * Flooring it can leave residual exposure, while the coordinator correctly rejects
     * a requested quantity that differs from live positionRisk. Validate against both
     * LOT_SIZE and MARKET_LOT_SIZE and serialize the original decimal amount unchanged.
     */
    private fun fullMarketCloseQuantity(positionAmount: String, rules: SymbolRules): String =
        ExecutionMath.exactFullMarketCloseQuantity(
            positionAmount = positionAmount,
            lotMinQty = rules.minQty.toString(),
            lotMaxQty = rules.maxQty.takeIf { it.isFinite() }?.toString(),
            lotStep = rules.step.toString(),
            marketMinQty = rules.marketMinQty.toString(),
            marketMaxQty = rules.marketMaxQty.takeIf { it.isFinite() }?.toString(),
            marketStep = rules.marketStep.toString()
        )

    private fun fmtPrice(value: Double, tick: Double): String {
        val rounded = if (tick > 0.0) {
            ExecutionMath.priceToTick(value, tick)
        } else value
        val decimals = ExecutionMath.decimalPlacesForIncrement(tick)
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
                getBody("/fapi/v1/avgPrice?symbol=" + symbol)
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
        @Suppress("UNUSED_PARAMETER") symbol: String,
        @Suppress("UNUSED_PARAMETER") rules: SymbolRules
    ) {
        error("Legacy Spot OCO capacity checks are disabled; Futures coordinator owns order limits")
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
        val teeth = alligator(candles, candles.lastIndex).teeth
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
            getBody("/fapi/v1/depth?symbol=" + symbol + "&limit=100")
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

    /**
     * UI / legacy callers only receive last confirmed Futures account equity.
     * An unavailable account is 0, not a synthetic free-balance-plus-notional estimate.
     */
    private fun estimateManagedEquity(): Double =
        liveFuturesEquity.takeIf { it.isFinite() && it > 0.0 } ?: 0.0

    /**
     * Retired Spot-era direct execution implementation. Keeping a hard failure at
     * this entry point prevents future callers from bypassing the Futures coordinator.
     */
    private fun executeBuyWithProtection(
        @Suppress("UNUSED_PARAMETER") candidate: BaseAnalysis,
        @Suppress("UNUSED_PARAMETER") gated: Boolean = false
    ) {
        error("Legacy direct BUY execution is disabled; CampaignEngine is canonical")
    }

    private fun createProtection(
        @Suppress("UNUSED_PARAMETER") symbol: String,
        @Suppress("UNUSED_PARAMETER") qty: Double,
        @Suppress("UNUSED_PARAMETER") entry: Double,
        @Suppress("UNUSED_PARAMETER") stopDistance: Double
    ): Protection {
        error("Legacy Spot OCO protection is disabled; use Futures Algo Order protection")
    }

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

        val jawS = williamsAlligatorLine(candles, 13, 8).map { it ?: Double.NaN }
        val teethS = williamsAlligatorLine(candles, 8, 5).map { it ?: Double.NaN }
        val lipsS = williamsAlligatorLine(candles, 5, 3).map { it ?: Double.NaN }

        fun bullishAlligator(i: Int): Boolean =
            i in candles.indices &&
                jawS[i].isFinite() &&
                teethS[i].isFinite() &&
                lipsS[i].isFinite() &&
                lipsS[i] > teethS[i] &&
                teethS[i] > jawS[i] &&
                candles[i].c > lipsS[i]

        val candleHighs = candles.map { it.h }
        val candleLows = candles.map { it.l }
        fun upGroupStart(i: Int): Int = tc2FractalGroupStart(candleHighs, i, true)
        fun downGroupStart(i: Int): Int = tc2FractalGroupStart(candleLows, i, false)
        fun upFractal(i: Int): Boolean = tc2ConfirmedFractal(candleHighs, i, true)
        fun downFractal(i: Int): Boolean = tc2ConfirmedFractal(candleLows, i, false)

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

        val current = candles.lastIndex
        val out = mutableListOf<CampaignSignalN>()

        // Reject a pending stop-entry if any later closed bar already crossed it.
        // Otherwise a restart/scan gap could resurrect a consumed signal and enter late.
        fun triggerStillPending(signalIndex: Int, trigger: Double): Boolean {
            if (signalIndex !in candles.indices || !trigger.isFinite() || trigger <= 0.0) return false
            if (candles[current].c >= trigger) return false
            for (later in signalIndex + 1..current) {
                if (candles[later].h >= trigger) return false
            }
            return true
        }

        // WM1: bullish reversal bar. It is the context bar; execution waits
        // for a break above its high. Countertrend WM1 is intentionally allowed.
        val reversalStart = max(2, candles.lastIndex - 20)
        for (i in candles.lastIndex downTo reversalStart) {
            val priorLow = min(candles[i - 1].l, candles[i - 2].l)
            if (
                tc2BullishWiseManBarQualified(
                    high = candles[i].h, low = candles[i].l, close = candles[i].c,
                    priorLow = priorLow, jaw = jawS[i], teeth = teethS[i],
                    lips = lipsS[i], aoNow = ao(candles, i), aoPrevious = ao(candles, i - 1)
                ) && angulationScore(i) > 0.0
            ) {
                val trigger = candles[i].h + tick
                if (triggerStillPending(i, trigger)) {
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

        // WM2 (Super AO) is an independent Wise-Man setup. Do not require a
        // WM3/fractal event as a hidden prerequisite: the first presenting Wise-Man
        // may initiate a campaign, while the pending stop entry still needs a valid
        // trigger, risk approval, and exchange-side protection.
        // WM2: the trigger belongs to the third AO rising bar, not the first
        // bar found while walking backward. Super AO is independent of WM3/fractals.
        var streak = 0
        for (i in candles.lastIndex downTo 35) {
            if (ao(candles, i) > ao(candles, i - 1)) streak++ else break
        }
        if (streak >= 3) {
            val signalIndex = candles.lastIndex - (streak - 3)
            if (signalIndex in 35..candles.lastIndex) {
                val trigger = candles[signalIndex].h + tick
                if (triggerStillPending(signalIndex, trigger)) {
                    out += CampaignSignalN(
                        signalId = campaignSignalId(symbol, frame, "SUPER_AO", candles[signalIndex].t),
                        type = "SUPER_AO",
                        role = "ENTRY",
                        signalBarTimeMs = candles[signalIndex].t,
                        triggerPrice = trigger,
                        protectivePrice = candles[signalIndex].l - tick,
                        teethAtDetection = teethS[signalIndex].takeIf { it.isFinite() } ?: 0.0,
                        invalidationPrice = candles[signalIndex].l - tick,
                        reason = "WM2 Super AO: third rising AO bar; conditional trigger above price bar"
                    )
                }
            }
        }

        // WM3: most recent confirmed buy fractal. The pivot defines the level, but
        // the signal timestamp is the second right-hand bar close when the
        // five-bar fractal first becomes knowable. Never backdate a decision.
        val fractalCenter = latestUpFractal(candles.lastIndex - 2)
        if (fractalCenter != null) {
            val trigger = candles[fractalCenter].h + tick
            val currentTeeth = teethS[current]
            // TC2 ch.11: the FRACTAL must TRIGGER beyond Teeth, not form beyond
            // it. Never veto a confirmed pattern using its pivot-bar Teeth.
            if (
                currentTeeth.isFinite() && currentTeeth > 0.0 &&
                triggerStillPending(fractalCenter, trigger) &&
                trigger > currentTeeth
            ) {
                out += CampaignSignalN(
                    signalId = campaignSignalId(symbol, frame, "FRACTAL", candles[fractalCenter].t),
                    type = "FRACTAL",
                    role = "ENTRY",
                    signalBarTimeMs = candles[fractalCenter + 2].t,
                    triggerPrice = trigger,
                    protectivePrice = (upGroupStart(fractalCenter)..fractalCenter)
                        .minOf { candles[it].l } - tick,
                    teethAtDetection = currentTeeth,
                    invalidationPrice = (upGroupStart(fractalCenter)..fractalCenter)
                        .minOf { candles[it].l } - tick,
                    reason = "WM3 confirmed buy fractal; trigger must remain above Teeth"
                )
            }
        }

        // Keep at most one signal per family, ordered by formation time. The
        // campaign engine chooses the earliest still-valid first-entry signal.
        return out.distinctBy { it.signalId }.sortedBy { it.signalBarTimeMs }
    }

    /**
     * Bearish mirror of the confirmed Williams entry families.
     *
     * Reflecting OHLC around a fixed positive constant reverses price direction while
     * preserving candle geometry and indicator ordering. We reuse the same confirmed
     * fractal / AO / Alligator implementation, then map trigger and invalidation levels
     * back to real prices. This is signal generation only: the legacy Spot execution
     * path is deliberately not allowed to submit these or any other orders.
     */
    private fun shortCampaignSignals(
        symbol: String,
        candles: List<CandleN>,
        frame: String,
        tick: Double
    ): List<CampaignSignalN> {
        if (candles.size < 45 || tick <= 0.0) return emptyList()
        val reflection = candles.maxOf { it.h } + candles.minOf { it.l }
        if (!reflection.isFinite() || reflection <= 0.0) return emptyList()
        val reflected = candles.map { candle ->
            CandleN(
                t = candle.t,
                o = reflection - candle.o,
                h = reflection - candle.l,
                l = reflection - candle.h,
                c = reflection - candle.c,
                v = candle.v
            )
        }
        return longCampaignSignals(symbol, reflected, frame, tick).mapNotNull { signal ->
            val trigger = reflection - signal.triggerPrice
            val stop = reflection - signal.protectivePrice
            // A short entry must trigger below current price and its protective stop
            // must be above entry. Invalid reflected levels are rejected, never guessed.
            val current = candles.last().c
            if (!trigger.isFinite() || !stop.isFinite() ||
                trigger <= 0.0 || stop <= trigger || trigger >= current
            ) return@mapNotNull null
            signal.copy(
                signalId = campaignSignalId(
                    symbol, frame, "SHORT_" + signal.type, signal.signalBarTimeMs
                ),
                type = "SHORT_" + signal.type,
                triggerPrice = trigger,
                protectivePrice = stop,
                invalidationPrice = stop,
                reason = "Bearish mirror of confirmed Williams setup: " + signal.reason
            )
        }.distinctBy { it.signalId }.sortedBy { it.signalBarTimeMs }
    }

    private fun analyseBase(
        symbol: String,
        candles: List<CandleN>,
        spread: Double,
        volume: Double
    ): BaseAnalysis {
        // The caller supplies the decision series: performCampaignScan loads closed H1
        // candles explicitly. Do not silently re-fetch/fall back to another interval here;
        // relabelling M5/M15 candles as H1 would create false causal signals.
        val rawWorkCandles = candles
        // Williams signals and fractals are evaluated only on exchange-confirmed
        // closed bars. A forming H1 candle must never become a decision candle.
        val decisionFrame = if (campaignEngineEnabled) campaignExecutionTimeframe else interval
        val decisionNow = runCatching { futuresMarketData.exchangeNowMs() }
            .getOrDefault(System.currentTimeMillis())
        val decisionBarMs = frameSeconds(decisionFrame).coerceAtLeast(60L) * 1000L
        val workCandles = rawWorkCandles.filter { candle ->
            candle.t > 0L && candle.t + decisionBarMs <= decisionNow
        }
        val i = workCandles.lastIndex
        if (i < 40) {
            return BaseAnalysis(
                symbol = symbol,
                candles = workCandles,
                score = 0.0,
                signal = false,
                htfCandidate = false,
                wave = neutralWave(workCandles),
                atrPct = 0.0,
                riskPct = 0.0,
                riskReward = 0.0,
                spreadPct = spread,
                breakoutDistancePct = 0.0,
                obi = null,
                tradeFlowImbalance = null,
                reason = "Недостаточно свечей: " + workCandles.size + "/40"
            )
        }

        val closes = workCandles.map { it.c }
        val atrPct = atrPct(workCandles)
        val atrAbs =
            atrAbs(workCandles)

        val campaignRules = runCatching { symbolFilters(symbol) }.getOrNull()
        val campaignTick = campaignRules?.tick ?: 0.0
        val campaignSignals = if (campaignEngineEnabled && campaignTick > 0.0) {
            // Build both directional hypotheses from the same exchange-confirmed
            // closed H1 series. Candidates reach execution only through the native
            // Futures coordinator after context, risk, and reconciliation gates pass.
            (
                longCampaignSignals(
                    symbol = symbol,
                    candles = workCandles,
                    frame = campaignExecutionTimeframe,
                    tick = campaignTick
                ) + shortCampaignSignals(
                    symbol = symbol,
                    candles = workCandles,
                    frame = campaignExecutionTimeframe,
                    tick = campaignTick
                )
            ).distinctBy { it.signalId }.sortedBy { it.signalBarTimeMs }
        } else {
            emptyList()
        }

        val alligator = alligator(workCandles, i)
        val bullish =
            alligator.lips > alligator.teeth &&
                alligator.teeth > alligator.jaw &&
                closes[i] > alligator.lips

        val aoValue = ao(workCandles, i)
        val aoPositive = aoValue > 0.0

        val fractalIndex = latestConfirmedUpFractal(workCandles, i)
        val fractalHigh =
            fractalIndex?.let { workCandles[it].h }
        val teethSeries = williamsAlligatorLine(workCandles, 8, 5)
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
                aoPositive && ao(workCandles, i - 1) <= aoValue -> 20.0
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
            candles = workCandles,
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

        val primaryDecisionSignal = campaignEntryCandidate(baseCandidate)
        val shortSetup = primaryDecisionSignal?.type?.startsWith("SHORT_", ignoreCase = true) == true
        val decisionDirection = if (primaryDecisionSignal == null) setup.direction
            else if (shortSetup) "DOWN" else "UP"
        val htf4h = frames.firstOrNull { it.path.startsWith("4h:") }
        val htfConfirmed =
            htf4h != null && htf4h.direction == decisionDirection &&
                if (shortSetup) {
                    htf4h.alligatorBearish && !htf4h.aoPositive
                } else {
                    htf4h.alligatorBullish && htf4h.aoPositive
                }
        val htf1d = frames.firstOrNull { it.path.startsWith("1d:") }
        val dailyAligned = htf1d != null && htf1d.direction == decisionDirection &&
            if (shortSetup) htf1d.alligatorBearish && !htf1d.aoPositive
            else htf1d.alligatorBullish && htf1d.aoPositive

        if (htfConfirmed) bonus += 8.0
        if (dailyAligned) bonus += 4.0
        if (setup.position == 3) {
            bonus += 6.0
            exhaustion = min(exhaustion, 30.0)
        }

        val parentW5 = frames.filter {
            it.position == 5 && it.direction == decisionDirection
        }
        val childW3 = frames.filter {
            it.position == 3 && it.direction == decisionDirection &&
                if (shortSetup) it.alligatorBearish && !it.aoPositive
                else it.alligatorBullish && it.aoPositive
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
                it.position == 3 && it.direction == decisionDirection &&
                    (if (shortSetup) it.alligatorBearish && !it.aoPositive
                     else it.alligatorBullish && it.aoPositive) &&
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
                junior.direction == decisionDirection &&
                (if (shortSetup) junior.alligatorBearish && !junior.aoPositive
                 else junior.alligatorBullish && junior.aoPositive)

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

        // The campaign's sole decision is the confirmed-closed H1 Williams signal.
        // M15/M5 wave analysis is displayed as diagnostics only: it must never veto
        // or manufacture a campaign entry. submitNativeFuturesCampaignEntry performs
        // the authoritative closed-H4 context check and D1 macro veto immediately
        // before passing the signal through the risk gate and Execution Door.
        val finalSignal =
            if (campaignEngineEnabled) {
                primaryDecisionSignal != null &&
                    baseCandidate.atrPct in 0.0..0.08 &&
                    baseCandidate.spreadPct in 0.0..0.0015
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
            finalSignal && campaignEngineEnabled ->
                "H1 Williams " + (if (shortSetup) "SHORT" else "LONG") +
                    " conditional-stop candidate; closed H4 context, D1 veto, risk and exchange reconciliation are checked before any order"
            countertrendCorrectionImpulse ->
                "MTF diagnostic: possible countertrend correction structure; lower timeframes do not decide the campaign"
            setup.position == 5 && !nestedW3ParentW5 ->
                "MTF diagnostic: estimated Wave 5/exhaustion context"
            entrySignal && middle?.position == 3 ->
                "MTF diagnostic: $entryFrame Wave 3 inside parent $parentFrame Wave 3"
            entrySignal && nestedW3ParentW5 ->
                "MTF diagnostic: $entryFrame Wave 3 inside parent Wave 5"
            finalSignal ->
                "MTF context diagnostic completed; entry policy remains separate"
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
                entryFrame = if (campaignEngineEnabled) campaignExecutionTimeframe else entryFrame,
                entryWave = if (campaignEngineEnabled) setup.position else junior?.position ?: 0,
                parentFrame = parentFrame,
                parentWave = middle?.position ?: 0,
                entryConfidence = if (campaignEngineEnabled) setup.confidence else junior?.confidence ?: 0.0,
                nestedW3ParentW5 = nestedW3ParentW5,
                countertrendCorrectionImpulse = countertrendCorrectionImpulse
            ),
            reason = reason,
            campaignReady = campaignEngineEnabled && finalSignal
        )
    }

    private fun toJson(candidate: BaseAnalysis): JSONObject {
        val wave = candidate.wave
        // Match the exact signal selected by execution, rather than displaying
        // the oldest item in the candidate list as if it were the pending entry.
        val primarySignal = campaignEntryCandidate(candidate)
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
            .put("entry_signal_type", primarySignal?.type ?: JSONObject.NULL)
            .put("entry_trigger_price", primarySignal?.triggerPrice ?: JSONObject.NULL)
            .put("entry_protective_price", primarySignal?.protectivePrice ?: JSONObject.NULL)
            .put("wise_man_count", directionalAlignmentCount(candidate, primarySignal))
            .put("signal_family", "ALLIGATOR_AO_FRACTAL")
            .put("wave_score", displayWaveScore)
            .put("wave_position", wave.position)
            .put("wave_phase", wave.phase)
            .put("wave_confidence", wave.confidence)
            .put("wave_exhaustion_risk", wave.exhaustionRisk)
            .put("wave_model", "HEURISTIC_UNVERIFIED")
            .put("wave_timeframes", JSONArray().apply {
                candidate.waveFrames.forEach { put(it.asJson()) }
            })
            .put("wave_timeframes_complete", candidate.waveFrames.size == 4 &&
                candidate.waveFrames.all { it.state == "ESTIMATED" })
            .put("wave_timeframes_execution_authority", false)
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

    /**
     * Directional alignment proxy for the cockpit, not a substitute for the
     * book-defined Three Wise Men sequence. All three checks use the direction
     * and levels of the same primary signal that the execution path evaluates.
     */
    private fun directionalAlignmentCount(
        candidate: BaseAnalysis,
        signal: CampaignSignalN?
    ): Int {
        if (signal == null || candidate.candles.isEmpty()) return 0
        val short = signal.type.startsWith("SHORT_", ignoreCase = true)
        val lastIndex = candidate.candles.lastIndex
        val close = candidate.candles[lastIndex].c
        val lines = alligator(candidate.candles, lastIndex)
        val alligatorAligned = if (short) {
            lines.lips < lines.teeth && lines.teeth < lines.jaw && close < lines.lips
        } else {
            lines.lips > lines.teeth && lines.teeth > lines.jaw && close > lines.lips
        }
        val aoValue = ao(candidate.candles, lastIndex)
        val momentumAligned = if (short) aoValue.isFinite() && aoValue < 0.0
            else aoValue.isFinite() && aoValue > 0.0
        val triggerGeometryValid = if (short) {
            signal.triggerPrice.isFinite() && signal.protectivePrice.isFinite() &&
                signal.triggerPrice > 0.0 && signal.protectivePrice > signal.triggerPrice &&
                signal.triggerPrice < close
        } else {
            signal.triggerPrice.isFinite() && signal.protectivePrice.isFinite() &&
                signal.triggerPrice > close && signal.protectivePrice > 0.0 &&
                signal.protectivePrice < signal.triggerPrice
        }
        return listOf(alligatorAligned, momentumAligned, triggerGeometryValid).count { it }
    }

    /**
     * Williams Alligator line aligned to the candle on which it is displayed.
     * Uses Median Price, SMMA seeded by the first-period mean, and a historical
     * lookup for the forward plot displacement (never future candle data).
     */
    private fun williamsAlligatorLine(
        candles: List<CandleN>,
        period: Int,
        displacement: Int
    ): List<Double?> {
        val median = candles.map { (it.h + it.l) / 2.0 }
        return shiftAlligatorForDisplay(smmaSeededValues(median, period), displacement)
    }

    private fun alligator(
        candles: List<CandleN>,
        index: Int
    ): TripleValues {
        if (candles.isEmpty() || index !in candles.indices) {
            return TripleValues(0.0, 0.0, 0.0)
        }
        val jaw = williamsAlligatorLine(candles, 13, 8).getOrNull(index) ?: 0.0
        val teeth = williamsAlligatorLine(candles, 8, 5).getOrNull(index) ?: 0.0
        val lips = williamsAlligatorLine(candles, 5, 3).getOrNull(index) ?: 0.0
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
        if (index < 33 || index >= candles.size) return Double.NaN
        var fastSum = 0.0
        var slowSum = 0.0
        for (i in index - 4..index) fastSum += (candles[i].h + candles[i].l) / 2.0
        for (i in index - 33..index) slowSum += (candles[i].h + candles[i].l) / 2.0
        return fastSum / 5.0 - slowSum / 34.0
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
    ): Boolean = tc2ConfirmedFractal(candles.map { it.h }, i, true)

    private fun isDownFractal(
        candles: List<CandleN>,
        i: Int
    ): Boolean = tc2ConfirmedFractal(candles.map { it.l }, i, false)

    private fun fractalPivots(
        candles: List<CandleN>
    ): List<PivotN> {
        if (candles.size < 10) return emptyList()

        val highs = candles.map { it.h }
        val lows = candles.map { it.l }
        val pivots = mutableListOf<PivotN>()
        for (i in 2 until candles.size - 2) {
            // The wave engine must use the SAME book-compatible confirmed
            // fractal family as the TC2 entry engine, including equal extrema.
            val up = tc2ConfirmedFractal(highs, i, true)
            val down = tc2ConfirmedFractal(lows, i, false)

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

    /**
     * Pure wave evidence extracted as-of a particular exchange timestamp.
     * No chart labels from unconfirmed pivots or forming candles can escape.
     * Its lower-dimensional W count is diagnostic until cross-book replay.
     */
    private fun waveFrameEvidence(
        frame: String,
        candles: List<CandleN>,
        exchangeNow: Long
    ): WaveFrameEvidence {
        val barMs = frameSeconds(frame) * 1000L
        if (barMs <= 0L || exchangeNow <= 0L) {
            return WaveFrameEvidence(frame, "UNKNOWN", 0L, 0, reason = "INVALID_TIMEBASE")
        }
        val closedTimes = tc2ClosedWaveBarTimes(candles.map { it.t }, barMs, exchangeNow)
        val byTime = candles.associateBy { it.t }
        val closed = closedTimes.mapNotNull { byTime[it] }
        val lastClose = closed.lastOrNull()?.t?.plus(barMs) ?: 0L
        // No silent wave counts from incomplete, duplicate or non-UTC-aligned
        // candle histories. H1 and the configured structural frames are
        // exchange-aligned fixed-duration intervals.
        val duplicateOpens = candles.count { it.t in closedTimes } != closedTimes.size
        val continuous = closed.zipWithNext().all { (a, b) -> b.t - a.t == barMs }
        val aligned = closed.all { it.t % barMs == 0L }
        if (duplicateOpens || !continuous || !aligned) {
            return WaveFrameEvidence(frame, "INVALID_HISTORY", lastClose, closed.size,
                reason = "DUPLICATE_GAP_OR_UNALIGNED_CANDLE")
        }
        if (closed.size < 45) {
            return WaveFrameEvidence(frame, "INSUFFICIENT", lastClose, closed.size,
                reason = "AT_LEAST_45_CLOSED_BARS_REQUIRED")
        }
        // Currency exchange-aligned freshness, using only the last *completed* bar.
        if (exchangeNow - lastClose > 2L * barMs) {
            return WaveFrameEvidence(frame, "STALE", lastClose, closed.size,
                reason = "LAST_CLOSED_BAR_STALE")
        }
        val pivots = fractalPivots(closed)
        val info = waveInfo(closed, frame)
        val lastPivot = pivots.lastOrNull()
        val opposite = pivots.lastOrNull { it.kind != lastPivot?.kind }
        // Wave labels require confirmed swing evidence, not just an Alligator
        // hint or a positive AO. W5 needs five alternating confirmed pivots.
        val countSupported = when (info.position) {
            1 -> pivots.size >= 2
            2, 3 -> pivots.size >= 3
            4, 5 -> pivots.size >= 5
            else -> false
        }
        val primary = if (countSupported && info.phase in setOf("IMPULSE", "CORRECTION"))
            "W" + info.position else "UNKNOWN"
        val oppositePrice = opposite?.price ?: 0.0
        val i = closed.lastIndex
        val aNow = ao(closed, i)
        val aPrior = ao(closed, i - 1)
        val aoWindow = (0..4).map { ao(closed, i - it) }
        val accelerator = if (aoWindow.all { it.isFinite() }) aNow - aoWindow.average() else 0.0
        val lines = alligator(closed, i)
        val gator = when {
            lines.lips > lines.teeth && lines.teeth > lines.jaw -> "AWAKE_UP"
            lines.lips < lines.teeth && lines.teeth < lines.jaw -> "AWAKE_DOWN"
            else -> "SLEEPING_OR_TANGLED"
        }
        val counterPivot = when (info.direction) {
            "UP" -> pivots.lastOrNull { it.kind == "DOWN" }
            "DOWN" -> pivots.lastOrNull { it.kind == "UP" }
            else -> null
        }
        val geo = lastPivot?.let { pivot ->
            williamsSwingGeometry(
                pivot.price, closed.last().c,
                i - pivot.index, atrAbs(closed)
            )
        }
        return WaveFrameEvidence(
            timeframe = frame, state = if (countSupported) "ESTIMATED" else "UNCERTAIN",
            asOfCloseMs = lastClose, closedBars = closed.size,
            phase = if (countSupported) info.phase else "UNKNOWN",
            primary = primary,
            alternative = "UNKNOWN", // No alternative count verified, do not invent it.
            direction = if (countSupported) info.direction else "NEUTRAL",
            confidence = if (countSupported) info.confidence else 0.0,
            pivotCount = pivots.size,
            lastPivotKind = lastPivot?.kind ?: "NONE",
            lastPivotPrice = lastPivot?.price ?: 0.0,
            oppositePivotPrice = oppositePrice,
            swing = geo,
            lastClose = closed.last().c,
            ao = if (aNow.isFinite()) aNow else 0.0,
            aoDelta = if (aNow.isFinite() && aPrior.isFinite()) aNow - aPrior else 0.0,
            ac = if (accelerator.isFinite()) accelerator else 0.0,
            alligatorState = gator,
            invalidationPrice = counterPivot?.price ?: 0.0,
            reason = if (countSupported) "CONFIRMED_FRACTAL_WAVE_HYPOTHESIS"
                else "WAVE_DEGREE_NOT_PROVEN_BY_PIVOTS"
        )
    }

    private fun diagnosticWaveFrames(
        symbol: String,
        h1Closed: List<CandleN>
    ): List<WaveFrameEvidence> {
        val now = futuresMarketData.exchangeNowMs()
        return listOf("1d", "4h", "1h", "15m").map { frame ->
            try {
                val candles = if (frame == "1h") h1Closed
                    else fetchCandles(symbol, frame, startupHistoryLimit(frame))
                waveFrameEvidence(frame, candles, now)
            } catch (failure: Exception) {
                WaveFrameEvidence(frame, "UNAVAILABLE", 0L, 0,
                    reason = "HISTORY_UNAVAILABLE_" + failure.javaClass.simpleName)
            }
        }
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
            alligator(candles, candles.lastIndex)

        val bullish =
            alligatorValues.lips >
                alligatorValues.teeth &&
                alligatorValues.teeth >
                alligatorValues.jaw &&
                current >
                alligatorValues.lips
        val bearish =
            alligatorValues.lips <
                alligatorValues.teeth &&
                alligatorValues.teeth <
                alligatorValues.jaw &&
                current <
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
            alligatorBearish = bearish,
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
            alligatorBearish = false,
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
                // Market and account health are independent. An account schema/auth
                // error must not erase a successful public ticker snapshot.
                try {
                    val rows = futuresMarketData.tickerPrices()
                    var found = 0
                    for (i in 0 until rows.length()) {
                        val row = rows.optJSONObject(i) ?: continue
                        val symbol = row.optString("symbol").uppercase(Locale.US)
                        val price = row.optString("price").toDoubleOrNull() ?: 0.0
                        if (symbol in coreSymbols && price.isFinite() && price > 0.0) {
                            livePrices[symbol] = price
                            livePriceUpdatedAtMs[symbol] = System.currentTimeMillis()
                            if (symbol == primarySymbol) restLastTickerPrice = price
                            found++
                        }
                    }
                    restMarketReady = found == coreSymbols.size
                    restLastSuccessMs = System.currentTimeMillis()
                    restLastLatencyMs = System.currentTimeMillis() - started
                    restLastError = if (restMarketReady) null else
                        "Futures ticker returned $found/${coreSymbols.size} approved contracts"
                } catch (x: Exception) {
                    restLastError = x.javaClass.simpleName + ": " + (x.message ?: "")
                    restLastLatencyMs = System.currentTimeMillis() - started
                    restMarketReady = false
                }

                if (key().isNotBlank() && secret().isNotBlank() &&
                    System.currentTimeMillis() - lastAccountProbe >= 15_000L
                ) {
                    lastAccountProbe = System.currentTimeMillis()
                    try {
                        val account = futuresCampaignCoordinator.accountSnapshot()
                        if (!updateCachedFuturesAccount(account)) {
                            // Keep the specific sanitized schema diagnostic from the parser.
                            restAccountLastError = restAccountLastError
                                ?: "Futures account balance/equity validation failed"
                        }
                    } catch (x: Exception) {
                        restAccountReady = false
                        restAccountLastError = x.javaClass.simpleName + ": " + (x.message ?: "")
                    }
                } else if (key().isBlank() || secret().isBlank()) {
                    restAccountReady = false
                    restAccountLastError = "Binance Futures credentials not configured"
                }
                try { Thread.sleep(5_000L) }
                catch (_: InterruptedException) { break }
            }
        }.apply {
            isDaemon = true
            name = "williams-futures-rest-probe"
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
            .put("progress_total", coreSymbols.size)
            .put("scan_symbols", JSONArray().apply { scanSymbols.forEach { put(it) } })
            .put("approved_symbols", JSONArray().apply { coreSymbols.forEach { put(it) } })
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
                if (candidates.length() > 0) candidates.getJSONObject(0)
                else JSONObject().put("symbol", primarySymbol)
            )

    fun scanner(refresh: Boolean): JSONObject {
        if (refresh) requestScan()
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

        // Dashboard positions/PnL must come from the Futures exchange snapshot,
        // never the retired Spot PositionState cache.
        val credentialsConfigured = key().isNotBlank() && secret().isNotBlank()
        if (credentialsConfigured && fast) refreshFuturesAccountSnapshotIfNeeded()
        var futuresPositionSnapshotError: String? = null
        val futuresPositions = if (credentialsConfigured) {
            if (fast) {
                val (snapshot, refreshError) = fastFuturesPositionsSnapshot()
                futuresPositionSnapshotError = refreshError
                snapshot
            } else {
                runCatching { futuresPositionSnapshot() }.getOrElse { failure ->
                    futuresPositionSnapshotError = failure.message ?: failure.javaClass.simpleName
                    reconcileRequired = true
                    persistSafetyFlag("reconcile_required", true)
                    lastError = "Futures position snapshot unavailable; trading blocked: " + futuresPositionSnapshotError
                    stateMachine.force(TradingState.RECONCILE_REQUIRED, lastError ?: "Futures position snapshot unavailable")
                    JSONArray()
                }
            }
        } else {
            JSONArray()
        }
        val futuresPositionsSnapshotOk = credentialsConfigured && futuresPositionSnapshotError == null
        val firstPosition = futuresPositions.optJSONObject(0)
        var livePnl = 0.0
        var totalEntryNotional = 0.0
        for (i in 0 until futuresPositions.length()) {
            val row = futuresPositions.optJSONObject(i)
                ?: throw IllegalStateException("Malformed normalized Futures position row in dashboard snapshot")
            livePnl += row.optDouble("unrealized_pnl_usdt", 0.0)
            val entry = row.optDouble("entry", 0.0)
            val qty = row.optDouble("qty", 0.0)
            if (entry > 0.0 && qty > 0.0) totalEntryNotional += entry * qty
        }
        val livePnlPct = if (totalEntryNotional > 0.0) livePnl / totalEntryNotional else 0.0
        val futuresIntents = runCatching { futuresCampaignCoordinator.intentsSnapshot() }.getOrDefault(JSONArray())
        val futuresReservedRiskPct = (0 until futuresIntents.length()).sumOf { i ->
            val intent = futuresIntents.optJSONObject(i) ?: return@sumOf 0.0
            if (intent.optString("intent_role", "ENTRY") == "ENTRY" && isEntryCampaignReserved(intent)) {
                intent.optDouble("risk_pct", 0.0)
            } else 0.0
        }
        val utcDayStart = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = runCatching { futuresMarketData.exchangeNowMs() }.getOrElse { System.currentTimeMillis() }
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val futuresTradesToday = (0 until futuresIntents.length()).count { i ->
            val intent = futuresIntents.optJSONObject(i) ?: return@count false
            intent.optString("intent_role") == "EXIT" &&
                intent.optLong("created_at_ms", 0L) >= utcDayStart
        }
        val futuresDailyPnl = executionStore.readState("futures_daily_pnl_usdt")
            ?.toDoubleOrNull() ?: 0.0
        val futuresDailyLossPct = executionStore.readState("futures_daily_loss_pct")
            ?.toDoubleOrNull() ?: 0.0
        val futuresDailyCapitalFlow = executionStore.readState("futures_daily_capital_flow_usdt")
            ?.toDoubleOrNull() ?: 0.0

        if (!fast && key().isNotBlank() && secret().isNotBlank() && !restAccountReady) {
            runCatching {
                val account = futuresCampaignCoordinator.accountSnapshot()
                check(updateCachedFuturesAccount(account)) {
                    "Futures account returned invalid balance/equity values"
                }
            }.onFailure { lastError = "Futures account status: " + (it.message ?: it.javaClass.simpleName) }
        }

        val futuresRuntimeStatus = runCatching { futuresCampaignCoordinator.status() }
            .getOrElse { JSONObject().put("state", "RECONCILE_REQUIRED").put("blocker", it.message ?: it.javaClass.simpleName) }
        val coordinatorState = futuresRuntimeStatus.optString("state", "RECONCILE_REQUIRED")
            .uppercase(Locale.US)
        // In campaign mode the exchange coordinator is authoritative. The local FSM can
        // lag during a REST reconciliation; never display ENTRY_PENDING/READY as if it
        // were settled while the coordinator is still reconciling or protection is unknown.
        val effectiveDisplayState = when {
            !campaignEngineEnabled -> stateName()
            killLatched -> "KILL_SWITCH_LATCHED"
            !running -> "STOPPED"
            paused -> "PAUSED"
            coordinatorState == "READY" -> "READY_FLAT"
            coordinatorState == "OPEN_PROTECTED" -> "OPEN"
            coordinatorState == "ENTRY_PENDING" -> "ENTRY_PENDING"
            coordinatorState == "EXIT_PENDING" -> "EXIT_PENDING"
            else -> "RECONCILE_REQUIRED"
        }
        val coordinatorReconciliationInProgress =
            coordinatorState == "RECONCILE_REQUIRED" &&
                futuresRuntimeStatus.optString("blocker") == "exchange_reconciliation_in_progress"

        return JSONObject()
            .put("version", BuildConfig.VERSION_NAME)
            .put("symbol", primarySymbol)
            .put("interval", interval)
            .put("testnet", true)
            .put("running", running)
            .put("paused", paused)
            .put("recovered", !reconcileRequired && (futuresRuntimeStatus.optString("state") in setOf("READY", "OPEN_PROTECTED", "ENTRY_PENDING")))
            .put("state", effectiveDisplayState)
            .put("execution_enabled", executionReady())
            .put("position_symbol", firstPosition?.optString("symbol") ?: JSONObject.NULL)
            .put("position_direction", firstPosition?.optString("direction") ?: JSONObject.NULL)
            .put("position_qty", firstPosition?.opt("qty") ?: JSONObject.NULL)
            .put("position_entry", firstPosition?.opt("entry") ?: JSONObject.NULL)
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
                dashboardPrice(
                    livePrice = livePrices[primarySymbol],
                    restPrice = restLastTickerPrice,
                    lastClosedPrice = primaryCandles.lastOrNull()?.c,
                    livePriceFresh = livePriceUpdatedAtMs[primarySymbol]?.let { updatedAt ->
                        val nowMs = System.currentTimeMillis()
                        updatedAt > 0L && nowMs >= updatedAt && nowMs - updatedAt <= 30_000L
                    } == true,
                    restTickerFresh = restLastSuccessMs > 0L &&
                        System.currentTimeMillis() >= restLastSuccessMs &&
                        System.currentTimeMillis() - restLastSuccessMs <= 30_000L
                ) ?: JSONObject.NULL
            )
            .put(
                "quote_balance",
                if (liveUsdtBalanceKnown) liveUsdtBalance else JSONObject.NULL
            )
            .put("futures_equity", if (restAccountReady) liveFuturesEquity else JSONObject.NULL)
            .put("account_funded_for_entry", accountFundedForEntry())
            .put("rest_account_error", restAccountLastError ?: JSONObject.NULL)
            .put(
                "position",
                firstPosition?.let {
                    JSONObject()
                        .put("symbol", it.optString("symbol"))
                        .put("direction", it.optString("direction"))
                        .put("qty", it.opt("qty") ?: JSONObject.NULL)
                        .put("entry", it.opt("entry") ?: JSONObject.NULL)
                        .put("stop", it.opt("stop") ?: JSONObject.NULL)
                        .put("take", JSONObject.NULL)
                        .put("risk_pct", it.opt("risk_pct") ?: JSONObject.NULL)
                        .put("exchange_confirmed", it.optBoolean("exchange_confirmed", false))
                } ?: JSONObject.NULL
            )
            .put("positions", futuresPositions)
            .put("open_positions", futuresPositions.length())
            .put("max_open_positions", maxOpenPositions)
            .put("reserved_risk_pct", futuresReservedRiskPct)
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
            .put("positions_snapshot_ok", futuresPositionsSnapshotOk)
            .put("positions_snapshot_at_ms", cachedFuturesPositionsSnapshotAtMs)
            .put("positions_snapshot_age_ms", if (cachedFuturesPositionsSnapshotAtMs > 0L)
                (System.currentTimeMillis() - cachedFuturesPositionsSnapshotAtMs).coerceAtLeast(0L) else JSONObject.NULL)
            .put("positions_snapshot_error", futuresPositionSnapshotError ?: JSONObject.NULL)
            .put("pnl", if (futuresPositions.length() == 0 || !futuresPositionsSnapshotOk) JSONObject.NULL else livePnl)
            .put("pnl_pct", if (futuresPositions.length() == 0 || !futuresPositionsSnapshotOk) JSONObject.NULL else livePnlPct)
            .put(
                "take_profit_price",
                if (campaignEngineEnabled) JSONObject.NULL
                else JSONObject.NULL
            )
            .put("stop_loss_price", firstPosition?.opt("stop") ?: JSONObject.NULL)
            .put("stop_loss_pct", if (firstPosition == null || firstPosition.optString("stop").toDoubleOrNull()?.let { it > 0.0 } != true) JSONObject.NULL else kotlin.math.abs((firstPosition.optString("entry").toDoubleOrNull() ?: 0.0) - (firstPosition.optString("stop").toDoubleOrNull() ?: 0.0)) / (firstPosition.optString("entry").toDoubleOrNull() ?: 1.0))
            .put("take_profit_pct", if (campaignEngineEnabled) 0.0 else 0.04)
            .put("risk_per_trade_pct", maxRiskPerTradePct)
            .put("max_daily_loss_pct", 0.03)
            .put("force_testnet_mode", forceTestnetMode)
            .put("daily_risk_baseline_source", dailyRiskBaselineSource())
            .put("trades_today", futuresTradesToday)
            .put("daily_pnl_usdt", futuresDailyPnl)
            .put("daily_loss_pct", futuresDailyLossPct)
            .put("daily_capital_flow_usdt", futuresDailyCapitalFlow)
            .put("consecutive_losses", futuresLossStreakGuard().consecutiveLosses)
            .put("consecutive_losses_available", futuresLossStreakGuard().available)
            .put("consecutive_loss_blocker", futuresLossStreakGuard().reason ?: JSONObject.NULL)
            .put("consecutive_loss_cooldown_until_ms", futuresLossStreakGuard().cooldownUntilMs ?: JSONObject.NULL)
            .put("trading_mode", when {
                killLatched -> "KILL_SWITCH_LATCHED"
                reconcileRequired -> "RECONCILE_REQUIRED"
                paused -> "PAUSED"
                running -> "ACTIVE"
                else -> "STOPPED"
            })
            .put("server_time", System.currentTimeMillis())
            .put("market_ws_connected", marketSocketConnected)
            .put("public_ws_connected", publicSocketConnected)
            .put("public_stream_last_event_ms", publicSocketLastEventMs)
            .put("user_ws_connected", userStreamConnected)
            .put("user_stream_sync_required", userStreamSyncRequired)
            .put("user_stream_connection_generation", userStreamConnectionGeneration)
            .put("user_stream_last_event_ms", lastUserEventMs)
            .put("user_stream_last_successful_sync_ms", lastUserStreamSyncAtMs)
            .put("user_stream_last_error", userStreamLastError ?: JSONObject.NULL)
            .put("user_stream_reconcile_running", futuresStreamReconcileRunning)
            .put("user_stream_reconcile_started_at_ms", userStreamReconcileStartedAtMs)
            .put("user_stream_reconcile_finished_at_ms", userStreamReconcileFinishedAtMs)
            .put("user_stream_reconcile_duration_ms", if (userStreamReconcileStartedAtMs > 0L) ((if (futuresStreamReconcileRunning) System.currentTimeMillis() else userStreamReconcileFinishedAtMs) - userStreamReconcileStartedAtMs).coerceAtLeast(0L) else JSONObject.NULL)
            .put("user_stream_reconcile_failure", userStreamReconcileFailure ?: JSONObject.NULL)
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
            .put("account_snapshot_at_ms", futuresAccountSnapshotUpdatedAtMs)
            .put("account_snapshot_age_ms", if (futuresAccountSnapshotUpdatedAtMs > 0L)
                (System.currentTimeMillis() - futuresAccountSnapshotUpdatedAtMs).coerceAtLeast(0L) else JSONObject.NULL)
            .put("account_snapshot_refresh_running", futuresAccountSnapshotRefreshRunning)
            .put("account_snapshot_refresh_error", futuresAccountSnapshotRefreshError ?: JSONObject.NULL)
            .put("rest_last_success_ms", restLastSuccessMs)
            .put("rest_last_latency_ms", restLastLatencyMs)
            .put("rest_last_error", restLastError ?: JSONObject.NULL)
            .put("rest_ticker_price", if (restLastTickerPrice > 0.0) restLastTickerPrice else JSONObject.NULL)
            .put("exchange", "BINANCE_USDM_FUTURES_TESTNET")
            .put("execution_mode", "AUTONOMOUS_ANDROID")
            .put("decision_timeframe", "1h")
            .put("max_daily_loss_pct", 0.03)
            .put("futures_runtime", futuresRuntimeStatus)
            .put("futures_positions", futuresPositions)
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
        val symbol = if (requestedSymbol == null) primarySymbol else requestedSymbol.trim().uppercase(Locale.US)
        require(symbol in coreSymbols) { "Chart symbol is outside the approved Futures universe: $symbol" }
        val selectedInterval = if (requestedInterval == null) interval else requestedInterval.trim()
        require(selectedInterval in APPROVED_FUTURES_KLINE_INTERVALS) {
            "Unsupported Futures chart interval: $selectedInterval"
        }
        // Never return plausible H1 candles mislabeled as another symbol/interval after an invalid request or REST error.
        val sourceCandles = if (symbol == primarySymbol && selectedInterval == interval) {
            if (primaryCandles.isEmpty()) primaryCandles = fetchCandles(primarySymbol, selectedInterval, 150)
            primaryCandles
        } else {
            fetchCandles(symbol, selectedInterval, 150)
        }

        val output = JSONArray()
        val prices = sourceCandles.map { it.c }
        val medianPrices = sourceCandles.map { (it.h + it.l) / 2.0 }
        val aoValues = medianPrices.indices.map { index -> awesomeOscillatorAt(medianPrices, index) }
        val acValues = aoValues.indices.map { index -> acceleratorOscillatorAt(aoValues, index) }
        // The chart fetches a moving quote frequently, but Williams indicators use only
        // confirmed closed candles. Cache the expensive wave/indicator explanation by
        // latest closed-bar timestamp so one-second quote refreshes do not recompute it.
        val indicatorKey = symbol + ":" + selectedInterval
        val latestClosedCandleTime = sourceCandles.lastOrNull()?.t ?: 0L
        val cachedIndicatorSnapshot = indicatorSnapshots[indicatorKey]
        val indicatorSnapshot = if (
            cachedIndicatorSnapshot != null &&
            cachedIndicatorSnapshot.optBoolean("ready", false) &&
            cachedIndicatorSnapshot.optInt("candle_count", -1) == sourceCandles.size &&
            cachedIndicatorSnapshot.optLong("time", -1L) == latestClosedCandleTime
        ) {
            JSONObject(cachedIndicatorSnapshot.toString())
        } else {
            buildIndicatorSnapshot(sourceCandles, symbol, selectedInterval).also { snapshot ->
                if (snapshot.optBoolean("ready", false) && latestClosedCandleTime > 0L) {
                    indicatorSnapshots[indicatorKey] = JSONObject(snapshot.toString())
                }
            }
        }

        if (prices.isNotEmpty()) {
            val jaw = williamsAlligatorLine(sourceCandles, 13, 8)
            val teeth = williamsAlligatorLine(sourceCandles, 8, 5)
            val lips = williamsAlligatorLine(sourceCandles, 5, 3)
            val start = max(0, sourceCandles.size - 120)

            for (i in start until sourceCandles.size) {
                val c = sourceCandles[i]
                val range = (c.h - c.l).coerceAtLeast(0.0)
                val volumeProxy = range / c.v.coerceAtLeast(1e-12)
                output.put(
                    JSONObject()
                        .put("time", c.t)
                        .put("open", c.o)
                        .put("high", c.h)
                        .put("low", c.l)
                        .put("close", c.c)
                        .put("jaw", jaw.getOrNull(i)?.takeIf { it.isFinite() } ?: JSONObject.NULL)
                        .put("teeth", teeth.getOrNull(i)?.takeIf { it.isFinite() } ?: JSONObject.NULL)
                        .put("lips", lips.getOrNull(i)?.takeIf { it.isFinite() } ?: JSONObject.NULL)
                        // AO/AC are nullable during warm-up. Never let NaN/Infinity
                        // abort the entire chart response for a valid symbol/timeframe.
                        .put("ao", aoValues.getOrNull(i)?.takeIf { it.isFinite() } ?: JSONObject.NULL)
                        .put("ac", acValues.getOrNull(i)?.takeIf { it.isFinite() } ?: JSONObject.NULL)
                        .put("mfi_proxy", volumeProxy.takeIf { it.isFinite() } ?: JSONObject.NULL)
                        .put("signal_source", "DIAGNOSTIC_ONLY_H1_CAMPAIGN_AUTHORITY")
                        .put("fractal_up", isUpFractal(sourceCandles, i))
                        .put("fractal_down", isDownFractal(sourceCandles, i))
                )
            }
        }

        val nowMs = System.currentTimeMillis()
        val lastTradeAt = lastTradeUpdatedAtMs[symbol] ?: 0L
        val lastTradeQuote = lastTradePrices[symbol]?.takeIf {
            it.isFinite() && it > 0.0 && lastTradeAt > 0L && nowMs >= lastTradeAt &&
                nowMs - lastTradeAt <= 5_000L
        }
        val lastWsAt = livePriceUpdatedAtMs[symbol] ?: 0L
        val bookTickerQuote = livePrices[symbol]?.takeIf {
            it.isFinite() && it > 0.0 && lastWsAt > 0L && nowMs >= lastWsAt &&
                nowMs - lastWsAt <= 5_000L
        }
        var liveQuote: Double? = lastTradeQuote ?: bookTickerQuote
        var liveQuoteAtMs = when {
            lastTradeQuote != null -> lastTradeAt
            bookTickerQuote != null -> lastWsAt
            else -> 0L
        }
        var liveQuoteSource = when {
            lastTradeQuote != null -> "LAST_TRADE_WS"
            bookTickerQuote != null -> "BOOK_TICKER_WS"
            else -> "UNAVAILABLE"
        }
        if (liveQuote == null) {
            val cachedRest = chartRestQuoteCache[symbol]?.takeIf {
                it.first.isFinite() && it.first > 0.0 && nowMs >= it.second && nowMs - it.second <= 2_000L
            }
            val restQuote = cachedRest ?: runCatching {
                futuresMarketData.price(symbol).takeIf { it.isFinite() && it > 0.0 }?.let { it to nowMs }
            }.getOrNull()
            if (restQuote != null) {
                chartRestQuoteCache[symbol] = restQuote
                liveQuote = restQuote.first
                liveQuoteAtMs = restQuote.second
                liveQuoteSource = "REST_FALLBACK"
            }
        }
        return JSONObject()
            .put("symbol", symbol)
            .put("interval", selectedInterval)
            .put("live_price", liveQuote ?: JSONObject.NULL)
            .put("live_price_at_ms", liveQuoteAtMs)
            .put("live_price_source", liveQuoteSource)
            .put("indicator_snapshot", indicatorSnapshot)
            .put("candles", output)
    }

    fun marketTickers(): JSONObject {
        val rows = futuresMarketData.tickerPrices()
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
            pairs.put(JSONObject().put("symbol", symbol)
                .put("price", livePrices[symbol] ?: priceMap[symbol] ?: JSONObject.NULL))
        }
        return JSONObject().put("exchange", "BINANCE_USDM_FUTURES_TESTNET")
            .put("symbols", JSONArray(coreSymbols)).put("pairs", pairs)
    }

    fun portfolio(): JSONObject {
        val configured = key().isNotBlank() && secret().isNotBlank()
        if (!configured) {
            return JSONObject().put("configured", false)
                .put("exchange", "BINANCE_USDM_FUTURES_TESTNET")
                .put("positions", JSONArray()).put("assets", JSONArray())
        }
        val account = futuresCampaignCoordinator.accountSnapshot()
        check(updateCachedFuturesAccount(account)) {
            "Futures account returned invalid balance/equity values"
        }
        // Use the same validated normalized snapshot as risk/entry sizing. Reading
        // only root-level fields here silently displayed zero for valid Demo payloads
        // that expose USDT values under assets[] instead of the summary fields.
        val accountValues = parseFuturesAccountSnapshot(account)
            ?: throw IllegalStateException("Futures account snapshot changed during validation")
        val wallet = accountValues.walletUsdt
        val equity = accountValues.equityUsdt
        val available = accountValues.availableUsdt
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
            .put("wallet_balance_usdt", wallet)
            .put("unrealized_pnl_usdt", accountValues.unrealizedPnlUsdt ?: JSONObject.NULL)
            .put("position_notional_usdt", notional).put("positions_unrealized_pnl_usdt", posPnl)
            .put("assets", assets).put("positions", positions)
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

    private fun diagnosticSelfTests(
        statusSnapshot: JSONObject,
        coordinatorSnapshot: JSONObject
    ): JSONArray {
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
        test("futures_execution_adapter", "binance", futuresExecutionAdapterReady, "FAIL", "Native USD-M Futures adapter and local coordinator wired; readiness still depends on live exchange reconciliation")
        test("local_runtime_dispatch", "runtime", true, "FAIL", "Cockpit commands dispatch in-process; no remote backend or unauthenticated loopback listener")
        test("execution_gate", "execution", executionReady(), "FAIL", "execution_ready=" + executionReady() + "; blockers=" + executionBlockers().joinToString(","))
        val displayedSettings = settings()
        val maxTradesSettingIsHonest = displayedSettings.isNull("max_trades_per_day") &&
            displayedSettings.optString("max_trades_per_day_policy").startsWith("NO_FIXED_COUNT_CAP")
        test(
            "execution_config_audit", "configuration", maxTradesSettingIsHonest, "FAIL",
            "max_trades_per_day=" + displayedSettings.opt("max_trades_per_day") +
                "; policy=" + displayedSettings.optString("max_trades_per_day_policy") +
                "; a display-only zero must not masquerade as an execution limit"
        )
        val coordinatorState = coordinatorSnapshot.optString("state", "UNKNOWN")
        val coordinatorReady = coordinatorSnapshot.optBoolean("ready_for_new_entry", false)
        val coordinatorBlocker = coordinatorSnapshot.optString("blocker", "")
        val runtimeState = statusSnapshot.optJSONObject("execution_state_contract")
            ?.optString("state", statusSnapshot.optString("state", "UNKNOWN")) ?: "UNKNOWN"
        val dailyBaselineSource = statusSnapshot.optString("daily_risk_baseline_source", "UNKNOWN")
        val reconciliationRunning = statusSnapshot.optBoolean("futures_reconciliation_in_progress", false) ||
            statusSnapshot.optBoolean("user_stream_reconcile_running", false)
        val consistencyFindings = diagnosticConsistencyFindings(
            coordinatorState, coordinatorReady, coordinatorBlocker, runtimeState,
            statusSnapshot.optBoolean("reconcile_required", false),
            statusSnapshot.optBoolean("execution_enabled", false),
            dailyBaselineSource, reconciliationRunning
        )
        test(
            "diagnostic_consistency", "diagnostics", consistencyFindings.isEmpty(),
            "FAIL", if (consistencyFindings.isEmpty()) "Runtime/coordinator readiness claims are consistent"
            else consistencyFindings.joinToString(" | ")
        )
        val baselineKnown = dailyBaselineSource.isNotBlank() &&
            !dailyBaselineSource.equals("UNKNOWN", ignoreCase = true) &&
            !dailyBaselineSource.equals("null", ignoreCase = true)
        val baselineValue = runCatching {
            val now = futuresMarketData.exchangeNowMs()
            val utc = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            utc.timeInMillis = now
            utc.set(Calendar.HOUR_OF_DAY, 0)
            utc.set(Calendar.MINUTE, 0)
            utc.set(Calendar.SECOND, 0)
            utc.set(Calendar.MILLISECOND, 0)
            executionStore.readState("futures_day_start_equity_" + utc.timeInMillis)?.toDoubleOrNull()
        }.getOrNull()
        val baselineValueValid = baselineValue != null && baselineValue.isFinite() && baselineValue > 0.0
        val baselineExecutionContradiction = (!baselineKnown || !baselineValueValid) &&
            statusSnapshot.optBoolean("execution_enabled", false)
        test(
            "daily_risk_baseline", "risk", baselineKnown && baselineValueValid,
            "FAIL",
            "source=" + dailyBaselineSource +
                "; baseline_value_valid=" + baselineValueValid +
                "; execution_enabled=" + statusSnapshot.optBoolean("execution_enabled", false) +
                "; unknown source or missing/invalid UTC-day baseline is a BLOCKING failure"
        )
        val autoRunRequested = prefs.getBoolean("auto_run", false)
        val workerAlive = worker?.isAlive == true
        val runtimeSeverity = runtimeLifecycleSeverity(autoRunRequested, running, workerAlive)
        test(
            "runtime_lifecycle", "runtime", runtimeSeverity == "PASS", runtimeSeverity,
            "running=" + running + "; worker_alive=" + workerAlive +
                "; auto_run_requested=" + autoRunRequested + "; kill_latched=" + killLatched
        )
        val historySeverity = historyLifecycleSeverity(
            autoRunRequested = autoRunRequested,
            running = running,
            historyReady = historyReady,
            warmupRunning = historyWarmupRunning,
            activeTasks = activeHistoryTasks.size
        )
        test(
            "history_lifecycle", "database", historySeverity == "PASS", historySeverity,
            "state=" + historyState + "; ready=" + historyReady +
                "; warmup_running=" + historyWarmupRunning +
                "; active_tasks=" + activeHistoryTasks.size
        )
        test("credentials_state", "binance", key().isNotBlank() && secret().isNotBlank(), "WARN", if (key().isNotBlank() && secret().isNotBlank()) "Binance credentials configured" else "Binance credentials not configured")
        test("rest_market_data", "binance", restMarketReady, "FAIL", if (restMarketReady) "Ticker REST OK; price=" + restLastTickerPrice + "; latency_ms=" + restLastLatencyMs else "REST market failed: " + (restLastError ?: "pending"))
        test("rest_account", "binance", restAccountReady, "WARN", if (restAccountReady) "Signed account OK; USDT available=" + liveUsdtBalance + "; equity=" + liveFuturesEquity else "Account REST failed: " + (restAccountLastError ?: "pending"))
        test("market_websocket", "websocket", marketSocketConnected, "WARN", if (marketSocketConnected) "Market kline/aggTrade stream connected" else "Market stream disconnected")
        test("public_websocket", "websocket", publicSocketConnected && publicWebSocketFresh(), "WARN", if (publicSocketConnected && publicWebSocketFresh()) "Public depth/bookTicker stream connected and fresh" else "Public stream disconnected or stale")
        test("user_websocket", "websocket", userStreamConnected && !userStreamSyncRequired, "WARN", if (userStreamConnected && !userStreamSyncRequired) "Private stream connected and REST-reconciled" else "User stream degraded; connected=" + userStreamConnected + "; sync_required=" + userStreamSyncRequired + "; reconcile_running=" + futuresStreamReconcileRunning + "; last_error=" + (userStreamLastError ?: "none"))
        val userNowMs = System.currentTimeMillis()
        val userSyncAgeMs = if (lastUserStreamSyncAtMs > 0L && userNowMs >= lastUserStreamSyncAtMs)
            userNowMs - lastUserStreamSyncAtMs else Long.MAX_VALUE
        val userStreamFresh = userStreamSyncIsFresh(
            connected = userStreamConnected,
            syncRequired = userStreamSyncRequired,
            lastSuccessfulSyncMs = lastUserStreamSyncAtMs,
            nowMs = userNowMs
        )
        test(
            "user_stream_freshness", "websocket", userStreamFresh, "FAIL",
            "connected=" + userStreamConnected +
                "; sync_required=" + userStreamSyncRequired +
                "; last_successful_rest_sync_ms=" + lastUserStreamSyncAtMs +
                "; sync_age_ms=" + (if (userSyncAgeMs == Long.MAX_VALUE) "UNKNOWN" else userSyncAgeMs.toString()) +
                "; sync must belong to the current connected session; private event silence alone does not indicate a broken stream"
        )
        val streamReconcileStartedAtMs = statusSnapshot.optLong("user_stream_reconcile_started_at_ms", 0L)
        val streamReconcileFinishedAtMs = statusSnapshot.optLong("user_stream_reconcile_finished_at_ms", 0L)
        val streamReconcileRunning = statusSnapshot.optBoolean("user_stream_reconcile_running", false)
        val streamReconcileDurationMs = statusSnapshot.opt("user_stream_reconcile_duration_ms")
            .let { if (it is Number) it.toLong() else -1L }
        val streamTaskHealthy = reconciliationTaskLifecycleHealthy(
            running = streamReconcileRunning,
            startedAtMs = streamReconcileStartedAtMs,
            nowMs = userNowMs
        )
        test(
            "user_stream_reconciliation_lifecycle", "runtime", streamTaskHealthy, "FAIL",
            "running=" + streamReconcileRunning +
                "; started_at_ms=" + streamReconcileStartedAtMs +
                "; finished_at_ms=" + streamReconcileFinishedAtMs +
                "; duration_ms=" + (if (streamReconcileDurationMs >= 0L) streamReconcileDurationMs.toString() else "UNKNOWN") +
                "; max_running_duration_ms=300000" +
                "; last_failure=" + statusSnapshot.optString("user_stream_reconcile_failure", "none")
        )
        val userEventAgeMs = if (lastUserEventMs > 0L && userNowMs >= lastUserEventMs)
            userNowMs - lastUserEventMs else Long.MAX_VALUE
        test(
            "user_stream_event_observation", "websocket",
            lastUserEventMs == 0L || (userNowMs >= lastUserEventMs && userEventAgeMs <= 86_400_000L),
            "WARN",
            "last_event_ms=" + lastUserEventMs +
                "; age_ms=" + (if (userEventAgeMs == Long.MAX_VALUE) "NONE_RECORDED" else userEventAgeMs.toString()) +
                "; event age is informational because an idle account may produce no private events"
        )
        test("history_ready", "database", historyReady, "WARN", if (historyReady) "Market history is ready" else "history_state=" + historyState + "; error=" + (historyLastError ?: "pending"))
        val scannerProgressAgeMs = (System.currentTimeMillis() - scanLastProgressAt).coerceAtLeast(0L)
        val scannerUniverseMatches = scanSymbols.size == coreSymbols.size && scanSymbols.toSet() == coreSymbols.toSet()
        val scannerComplete = scannerState in setOf("READY", "READY_BLOCKED") &&
            !scanning && scannerUniverseMatches &&
            scanProgressSymbols == coreSymbols.size && lastSymbolsScanned == coreSymbols.size
        val scannerStartupGrace = scanStartedAt > 0L &&
            System.currentTimeMillis() >= scanStartedAt &&
            System.currentTimeMillis() - scanStartedAt <= 15_000L
        val scannerActivelyProgressing = scannerState == "RUNNING" && scanning && scannerUniverseMatches &&
            scanProgressSymbols in 0..coreSymbols.size &&
            (scannerProgressAgeMs <= 60_000L || (scanProgressSymbols == 0 && scannerStartupGrace))
        test(
            "scanner_state", "scanner", scannerComplete || scannerActivelyProgressing, "WARN",
            "state=" + scannerState + "; complete=" + scannerComplete +
                "; universe_matches=" + scannerUniverseMatches + "; run_id=" + scanRunId +
                "; progress=" + scanProgressSymbols + "/" + scanSymbols.size +
                "; last_symbols_scanned=" + lastSymbolsScanned + "; age_ms=" + scannerProgressAgeMs
        )
        test("state_machine", "runtime", stateMachine.state.name.isNotBlank(), "FAIL", "fsm_state=" + stateMachine.state.name)
        test("risk_limits", "risk", maxRiskPerTradePct > 0.0 && maxRiskPerTradePct <= 0.0025 && maxTotalRiskPct <= 0.006, "FAIL", "CONFIGURATION ONLY; per_trade=" + maxRiskPerTradePct + "; total=" + maxTotalRiskPct + "; daily_loss_limit=0.03; baseline readiness is checked separately by daily_risk_baseline")
        test(
            "self_heal_contract",
            "runtime",
            runtimeRecoveryContract(
                autoRunRequested = autoRunRequested,
                running = running,
                workerAlive = workerAlive,
                killLatched = killLatched
            ),
            "FAIL",
            "auto_run_requested=" + autoRunRequested +
                "; running=" + running +
                "; worker_alive=" + workerAlive +
                "; reconcile_required=" + reconcileRequired +
                "; kill_latched=" + killLatched
        )
        return tests
    }

    fun diagnostics(): JSONObject {
        // Capture each state source once so the report does not compare different
        // moments in time and manufacture contradictions from separate status reads.
        val statusSnapshot = status(fast = true)
        val coordinatorSnapshot = futuresCampaignCoordinator.status()
        val tests = diagnosticSelfTests(statusSnapshot, coordinatorSnapshot)
        val consistencyFindings = diagnosticConsistencyFindings(
            coordinatorState = coordinatorSnapshot.optString("state", "UNKNOWN"),
            coordinatorReady = coordinatorSnapshot.optBoolean("ready_for_new_entry", false),
            coordinatorBlocker = coordinatorSnapshot.optString("blocker", ""),
            runtimeState = statusSnapshot.optJSONObject("execution_state_contract")
                ?.optString("state", statusSnapshot.optString("state", "UNKNOWN")) ?: "UNKNOWN",
            runtimeReconcileRequired = statusSnapshot.optBoolean("reconcile_required", false),
            executionEnabled = statusSnapshot.optBoolean("execution_enabled", false),
            dailyRiskBaselineSource = statusSnapshot.optString("daily_risk_baseline_source", "UNKNOWN"),
            reconciliationRunning = statusSnapshot.optBoolean("futures_reconciliation_in_progress", false) ||
                statusSnapshot.optBoolean("user_stream_reconcile_running", false),
            topLevelRuntimeState = statusSnapshot.optString("state", "UNKNOWN")
        )
        val failedTests = (0 until tests.length()).count {
            tests.optJSONObject(it)?.optString("severity") == "FAIL"
        }
        val warningTests = (0 until tests.length()).count {
            tests.optJSONObject(it)?.optString("severity") == "WARN"
        }
        val diagnosticOverall = when {
            consistencyFindings.isNotEmpty() || failedTests > 0 -> "FAIL"
            warningTests > 0 -> "WARN"
            else -> "PASS"
        }
        return JSONObject()
            .put("runtime", "standalone")
            .put("device_local_api", "in-process://williams-runtime")
            .put("binance_testnet", true)
            .put("exchange_market", "USDⓈ-M_FUTURES_REQUIRED")
            .put("futures_execution_adapter_ready", futuresExecutionAdapterReady)
            .put("futures_campaign_coordinator", coordinatorSnapshot)
            .put("status", statusSnapshot)
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
            .put("diagnostic_findings", JSONArray(consistencyFindings))
            .put("diagnostic_summary", JSONObject()
                .put("overall", diagnosticOverall)
                .put("finding_count", consistencyFindings.size)
                .put("failed_test_count", failedTests)
                .put("warning_test_count", warningTests)
                .put("execution_allowed", statusSnapshot.optBoolean("execution_enabled", false) &&
                    consistencyFindings.isEmpty() && failedTests == 0)
                .put("top_level_runtime_state", statusSnapshot.optString("state", "UNKNOWN"))
                .put("contract_runtime_state", statusSnapshot.optJSONObject("execution_state_contract")
                    ?.optString("state", "UNKNOWN") ?: "UNKNOWN")
                .put("coordinator_state", coordinatorSnapshot.optString("state", "UNKNOWN"))
                .put("coordinator_blocker", coordinatorSnapshot.optString("blocker", "")))
            .put("diagnostic_contract_version", 8)
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

        // A kill latch is never cleared automatically, but it must not disable
        // safe monitoring. Start feeds/history in a no-entry mode so diagnostics and
        // position protection can recover while all new entries stay blocked.
        if (killLatched) {
            actions.put("blocked_by_kill_switch")
            if (!running) {
                val safeStart = runCatching { start() }.getOrElse {
                    JSONObject().put("started", false)
                        .put("blocker", it.message ?: it.javaClass.simpleName)
                }
                if (safeStart.optBoolean("started", false)) {
                    actions.put("safe_monitoring_started_under_kill_switch")
                } else if (safeStart.optString("reason") != "already_running") {
                    actions.put("safe_monitoring_start_blocked")
                    actions.put("startup_blocker:" + safeStart.optString("blocker", "unknown"))
                }
            }
            if (running && !historyReady && !historyWarmupRunning) {
                historyState = "RECOVERY_REQUESTED"
                warmCoreHistoryAsync(runtimeGeneration)
                actions.put("futures_history_reload_requested")
            }
            if (running) repairStaleMarketStreams()
            val after = status(fast = true)
            return JSONObject().put("changed", actions.length() > 1).put("safe_only", true)
                .put("actions", actions).put("execution_ready", false)
                .put("execution_blockers", JSONArray(executionBlockers()))
                .put("before", before).put("after", after)
        }

        // Recovering a stopped worker must actually restart it. START performs fresh
        // exchange position/order/protection reconciliation and validates the account.
        var startedHere = false
        if (!running) {
            if (reconcileRequired) {
                val recovery = runCatching { recover() }.getOrElse {
                    JSONObject().put("recovered", false).put("blocker", it.message ?: it.javaClass.simpleName)
                }
                if (!recovery.optBoolean("recovered", false)) {
                    actions.put("blocked_by_exchange_reconciliation")
                    val after = status(fast = true)
                    return JSONObject().put("changed", false).put("safe_only", true)
                        .put("actions", actions).put("runtime_recovery", recovery)
                        .put("execution_ready", false)
                        .put("execution_blockers", JSONArray(executionBlockers()))
                        .put("before", before).put("after", after)
                }
                actions.put("futures_exchange_reconciliation")
            }
            val started = runCatching { start() }.getOrElse {
                JSONObject().put("started", false).put("blocker", it.message ?: it.javaClass.simpleName)
            }
            if (!started.optBoolean("started", false) && started.optString("reason") != "already_running") {
                actions.put("runtime_start_blocked")
                val after = status(fast = true)
                return JSONObject().put("changed", false).put("safe_only", true)
                    .put("actions", actions).put("runtime_start", started)
                    .put("execution_ready", executionReady())
                    .put("execution_blockers", JSONArray(executionBlockers()))
                    .put("before", before).put("after", after)
            }
            startedHere = started.optBoolean("started", false)
            if (startedHere) actions.put("runtime_started_after_exchange_reconciliation")
        }

        if (reconcileRequired) {
            actions.put("blocked_by_reconciliation")
            val after = status(fast = true)
            return JSONObject().put("changed", actions.length() > 0).put("safe_only", true)
                .put("actions", actions).put("execution_ready", executionReady())
                .put("execution_blockers", JSONArray(executionBlockers()))
                .put("before", before).put("after", after)
        }

        lastSelfHealMs = System.currentTimeMillis()
        selfHealCount++
        if (running) {
            if (!historyReady && !historyWarmupRunning) {
                historyState = "RECOVERY_REQUESTED"
                warmCoreHistoryAsync(runtimeGeneration)
                actions.put("futures_history_reload_requested")
            }
            if (!startedHere) {
                val attempt = runCatching {
                    reconcileFuturesThroughDoor(
                        "SELF_HEAL_RECONCILIATION",
                        allowStopped = true,
                        allowKillLatched = true
                    )
                }
                val reconciliation = attempt.getOrNull()
                if (attempt.isSuccess && reconciliation?.optBoolean("ok", false) == true) {
                    actions.put("futures_exchange_reconciliation")
                } else {
                    reconcileRequired = true
                    val persisted = persistSafetyFlag("reconcile_required", true)
                    val reason = when {
                        attempt.isFailure -> attempt.exceptionOrNull()?.message
                            ?: attempt.exceptionOrNull()?.javaClass?.simpleName
                            ?: "Futures reconciliation threw an unknown exception"
                        reconciliation != null -> reconciliation.optString(
                            "blocker", "Futures reconciliation returned not-ready"
                        )
                        else -> "Futures reconciliation returned no result"
                    }
                    lastError = if (persisted) reason else
                        "$reason; additionally failed to persist reconciliation latch"
                    stateMachine.force(TradingState.RECONCILE_REQUIRED, lastError ?: reason)
                    actions.put("futures_reconciliation_blocked")
                }
            }
            repairStaleMarketStreams()
            if (historyReady && !scanning && !paused) {
                requestScan()
                actions.put("futures_scanner_reconnect_requested")
            }
            if (paused) {
                // Self-heal shares the same single-flight monitor as the runtime worker.
                scheduleNativePositionManagement()
                actions.put("futures_position_monitor_scheduled")
            }
        }

        val after = status(fast = true)
        return JSONObject().put("changed", actions.length() > 0).put("safe_only", true)
            .put("actions", actions).put("execution_ready", executionReady())
            .put("execution_blockers", JSONArray(executionBlockers()))
            .put("before", before).put("after", after)
    }

    fun settings(): JSONObject =
        JSONObject()
            .put("version", BuildConfig.VERSION_NAME)
            .put("campaign_engine", campaignEngineEnabled)
            .put("campaign_execution_timeframe", campaignExecutionTimeframe)
            .put("campaign_entry_mode", "CONDITIONAL_STOP")
            .put("campaign_fixed_take_profit", false)
            .put("campaign_entry_execution", "USD_M_FUTURES_TESTNET_COORDINATOR")
            .put("campaign_risk_limit_pct", campaignRiskLimitPct)
            .put("campaign_initial_risk_pct", campaignInitialRiskPct)
            .put("campaign_add_on_risk_cap_pct", campaignAddRiskCapPct)
            .put("campaign_trail_bars", campaignTrailBars)
            .put("symbol", primarySymbol)
            .put("interval", interval)
            // Legacy fixed-size/percentage stops are not used by the Futures campaign.
            // Expose neutral values so diagnostics cannot imply 95% sizing or a fixed TP.
            .put("position_fraction", 0.0)
            .put("stop_loss_pct", 0.0)
            .put("take_profit_pct", 0.0)
            .put("legacy_spot_settings_active", false)
            .put("position_sizing_model", "STRUCTURAL_STOP_RISK_BUDGET")
            .put("protective_stop_model", "STRUCTURAL_3_TO_5_H1_BARS")
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
            .put("testnet_live_execution", executionReady())
            .put("futures_execution_adapter_ready", futuresExecutionAdapterReady)
            .put("execution_block_reason", "Entries require reconciled Futures account, fresh H1 market data, risk approval, and exchange-confirmed protection")
            .put("risk_per_trade_pct", maxRiskPerTradePct)
            .put("max_daily_loss_pct", 0.03)
            .put("max_trades_per_day", JSONObject.NULL)
            .put("max_trades_per_day_policy", "NO_FIXED_COUNT_CAP; entries remain subject to campaign risk, daily loss, and Execution Gate")
            .put("max_consecutive_losses", 2)
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