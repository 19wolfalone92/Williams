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

object StandaloneRuntime {
    @SuppressLint("StaticFieldLeak")
    @Volatile private var server: StandaloneServer? = null

    /** No initialization, HTTP, SQLite, or exchange door on the service guard. */
    internal fun backgroundSnapshot():BackgroundRuntimeSnapshot = server?.backgroundSnapshot() ?: BackgroundRuntimeSnapshot()

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

    fun stop(): Boolean {
        // Keep the process singleton: an overlapping UI start must never create a
        // second NativeEngine while an old server still has a running worker.
        return server?.stop() ?: true
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
    "6h" -> StartupHistoryPolicy(240, 140)
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
    topLevelRuntimeState: String = runtimeState,
    exchangeClockFresh: Boolean = true
): List<String> = buildList {
    // Only an exchange reconciliation currently holding the coordinator in
    // RECONCILE_REQUIRED/in-progress can legitimately outrun the local FSM.
    // Another coincident task must not hide an unrelated coordinator failure.
    val transientReconciliation = reconciliationRunning &&
        coordinatorState == "RECONCILE_REQUIRED" &&
        coordinatorBlocker == "exchange_reconciliation_in_progress"
    if (!transientReconciliation && topLevelRuntimeState.isNotBlank() &&
        runtimeState.isNotBlank() && topLevelRuntimeState != runtimeState) {
        add("RUNTIME_STATE_MISMATCH: status.state=$topLevelRuntimeState but execution_state_contract.state=$runtimeState")
    }
    if (coordinatorState == "RECONCILE_REQUIRED" && !transientReconciliation && runtimeState != "RECONCILE_REQUIRED") {
        add("STATE_MISMATCH: coordinator requires reconciliation but runtime state is $runtimeState")
    }
    if (coordinatorState == "RECONCILE_REQUIRED" && !transientReconciliation && !runtimeReconcileRequired) {
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
    if (!exchangeClockFresh) {
        add("EXCHANGE_CLOCK_STALE: cached exchange time is too old to verify the UTC-day risk baseline")
        if (executionEnabled) add("CLOCK_UNSAFE_EXECUTION: new entries enabled despite stale Binance time")
    } else if (dailyRiskBaselineSource.isBlank() ||
        dailyRiskBaselineSource.equals("UNKNOWN", ignoreCase = true) ||
        dailyRiskBaselineSource.equals("null", ignoreCase = true)) {
        add("RISK_BASELINE_UNKNOWN: UTC daily-risk baseline source is not established")
        if (executionEnabled) {
            add("RISK_BASELINE_UNSAFE_EXECUTION: execution is enabled without a known UTC daily-risk baseline")
        }
    }
}

internal fun authoritativeRuntimeDisplayState(
    campaignMode: Boolean, running: Boolean, paused: Boolean, killLatched: Boolean,
    coordinatorState: String, fallbackState: String
): String = when {
    !campaignMode -> fallbackState
    killLatched -> "KILL_SWITCH_LATCHED"
    !running -> "STOPPED"
    paused -> "PAUSED"
    coordinatorState == "READY" -> "READY_FLAT"
    coordinatorState == "OPEN_PROTECTED" -> "OPEN"
    coordinatorState == "ENTRY_PENDING" -> "ENTRY_PENDING"
    coordinatorState == "EXIT_PENDING" -> "EXIT_PENDING"
    else -> "RECONCILE_REQUIRED"
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

/** 
 * A burst of private Binance events must not spin forever inside the same
 * reconciliation worker. After two complete signed snapshots, reschedule a
 * new attempt on the normal worker tick and RETAIN sync_required=true.
 * A bounded retry is never proof that new orders or STOPs are safe.
 */
internal fun privateStreamReconciliationMayRepeat(
    pending:Boolean,
    passes:Int,
    maximumPasses:Int=2
):Boolean = pending && passes>0 && maximumPasses>0 && passes<maximumPasses

/** A completed signed account audit can expose a genuine risk/owner blocker.
 * Receiving that snapshot synchronizes DATA; it never grants order authority.
 * Transport/identity/partial-audit failures must retain the WS resync latch. */
internal fun futuresSignedSnapshotComplete(result: JSONObject): Boolean =
    result.optBoolean("exchange_snapshot_complete", false) &&
        result.optJSONArray("exchange_protection_observations") != null &&
        result.optInt("positions_checked", -1) >= 0

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

/** A connected private WebSocket is not sufficient to submit NEW exposure. The
 * periodic signed account/position/STOP reconciliation must also have succeeded
 * recently. A stalled worker or a deferred lock must not indefinitely reuse
 * a once-valid REST snapshot. This fence does NOT prevent safety management.
 */
internal fun signedFuturesReconciliationFresh(
    lastSuccessfulAtMs: Long, nowMs: Long, maximumAgeMs: Long = 90_000L
): Boolean = lastSuccessfulAtMs > 0L && nowMs >= lastSuccessfulAtMs &&
    maximumAgeMs > 0L && nowMs - lastSuccessfulAtMs <= maximumAgeMs

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
 * Recover missing UTC risk evidence even if a previous failed preflight
 * put new entries on PAUSE. This schedules only the risk/reconciliation check:
 * it NEVER resumes execution and never clears the operator pause or a safety latch.
 * The executor remains fail-closed until a durable valid baseline is confirmed.
 */
internal fun shouldRetryDailyRiskBaseline(
    running: Boolean,
    @Suppress("UNUSED_PARAMETER") paused: Boolean,
    killLatched: Boolean,
    @Suppress("UNUSED_PARAMETER") reconcileRequired: Boolean,
    accountReady: Boolean,
    @Suppress("UNUSED_PARAMETER") userStreamConnected: Boolean,
    @Suppress("UNUSED_PARAMETER") userStreamSyncRequired: Boolean,
    baselineReady: Boolean
): Boolean = running && !killLatched && accountReady && !baselineReady

/**
 * Read-only discovery has fewer prerequisites than order execution. A pause,
 * kill switch, or unresolved exchange position must not stop H1 diagnostics;
 * the independent Execution Gate still blocks every new order mutation.
 */
/**
 * A live runtime with verified history and a failed H1 scan is a functional
 * failure, not a yellow warning. An incomplete startup is still a warning.
 */
internal fun scannerFailureSeverity(
    running: Boolean, historyReady: Boolean,
    scanning: Boolean, scannerState: String
): String = if (running && historyReady && !scanning && scannerState == "FAILED") {
    "FAIL"
} else {
    "WARN"
}

/** Source location is necessary to distinguish failed H1 fetch from TC2 rule bugs. */
internal fun h1ScannerExceptionDetail(stage: String, failure: Throwable): String {
    val root = generateSequence(failure) { it.cause }.last()
    val source = root.stackTrace.firstOrNull { it.className.startsWith("com.williamsbot.") }
    val at = source?.let { "${it.fileName}:${it.lineNumber}" } ?: "SOURCE_NOT_CAPTURED"
    val detail = safeDiagnosticText(root.message.orEmpty()).take(220)
    return "$stage: ${root.javaClass.simpleName} @ $at: $detail"
}

internal fun scannerDataReadinessState(
    running: Boolean,
    historyReady: Boolean,
    @Suppress("UNUSED_PARAMETER") marketDataFresh: Boolean
): String = when {
    !running -> "STOPPED"
    !historyReady -> "WAITING_FOR_HISTORY"
    // A stale/absent ticker quote may NEVER prevent read-only H1 analysis.
    // Quote/market freshness is still an independent new-entry execution gate.
    else -> "RUNNABLE"
}

/** The runtime worker runs every second for recovery, but a completed 10-pair
 * H1 REST scan must not be repeated every single second. Market WebSockets
 * and stop/position reconciliation remain independently scheduled.
 */
/**
 * Periodic signed Futures reconciliation must not be started by an H1 scan.
 * On a verified flat account, the private user stream handles exchange events,
 * with a 45-second independent REST fallback. Open exposure is monitored
 * frequently. A running scan or another reconciliation wins the I/O slot.
 */
internal fun shouldScheduleIndependentFuturesMonitor(
    nowMs: Long,
    lastSuccessfulMs: Long,
    lastAttemptMs: Long,
    coordinatorState: String,
    scanning: Boolean,
    reconciliationActive: Boolean,
    safetyLatchUnresolved: Boolean = false
): Boolean {
    if (nowMs <= 0L || scanning || reconciliationActive) return false
    val interval = if (safetyLatchUnresolved) 2_500L else when (coordinatorState) {
        "READY" -> 45_000L
        "OPEN_PROTECTED" -> 3_000L
        "ENTRY_PENDING" -> 2_500L
        "EXIT_PENDING" -> 1_500L
        else -> 5_000L
    }
    if (lastAttemptMs > 0L && nowMs >= lastAttemptMs &&
        nowMs - lastAttemptMs < maxOf(2_000L, interval)) return false
    return lastSuccessfulMs <= 0L || nowMs < lastSuccessfulMs ||
        nowMs - lastSuccessfulMs >= interval
}

internal fun shouldScheduleReadOnlyScan(nowMs:Long,lastCompletedMs:Long,minimumMs:Long=30_000L):Boolean =
    nowMs>0 && minimumMs>0 &&
        (lastCompletedMs==0L ||
            nowMs>=lastCompletedMs && nowMs-lastCompletedMs>=minimumMs)

/** Execution blockers remain visible after a successful read-only market scan. */
internal fun scannerExecutionBlocker(
    paused: Boolean,
    reconcileRequired: Boolean,
    killLatched: Boolean,
    accountReady: Boolean,
    marketDataFresh: Boolean,
    coordinatorState: String,
    coordinatorReadyForEntry:Boolean=false
): String? = when {
    killLatched -> "BLOCKED_KILL"
    reconcileRequired -> "BLOCKED_FUTURES_RECONCILIATION"
    coordinatorState.uppercase(Locale.US) in setOf(
        "UNINITIALIZED", "RECONCILE_REQUIRED", "POSITION_UNPROTECTED",
        "EXIT_PENDING"
    ) -> "BLOCKED_FUTURES_RECONCILIATION"
    // ENTRY_PENDING is normal for a known, reconciled STOP_ENTRY on a
    // different pair. The Futures coordinator must explicitly authorize
    // another symbol; unresolved or partially filled states still block.
    coordinatorState.uppercase(Locale.US)=="ENTRY_PENDING" && !coordinatorReadyForEntry ->
        "BLOCKED_FUTURES_RECONCILIATION"
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
/** A private-stream REST sync may defer behind another account-wide reconciliation.
 * Deferred never means exchange-verified; caller retains the sync-required latch.
 * Startup and operator recovery deliberately require a strict lock wait.
 */
internal fun deferrableFuturesReconciliation(purpose: String): Boolean =
    purpose == "POSITION_MANAGEMENT_RECONCILIATION" || purpose == "USER_STREAM_RECONCILIATION"

/** A private WS reconnect MUST eventually get an account-wide signed snapshot.
 * Position management may run every 2.5s while orphaned exposure is present;
 * a zero-timeout user-stream lock can then collide forever and show connected
 * WebSocket with last_successful_rest_sync_ms=0. Wait on the dedicated WS worker
 * for at most 15s, WITHOUT skipping the durable execution door. Maintenance
 * ticks stay nonblocking and all mutation/entry gates remain unchanged. */
internal fun futuresReconciliationLockBudgetMs(purpose:String,maximumWaitMs:Long):Long = when(purpose) {
    "POSITION_MANAGEMENT_RECONCILIATION" -> 0L
    "USER_STREAM_RECONCILIATION" -> minOf(15_000L,maximumWaitMs.coerceAtLeast(0L))
    else -> maximumWaitMs.coerceAtLeast(0L)
}

/** Read-only last-known signed positions fallback. The caller MUST set
 * positions_snapshot_ok=false; these rows do not prove a current position,
 * a working STOP, available risk budget or that the account is flat.
 */
/**
 * Stop movement is permitted only with fresh, quantity+side-matched signed
 * exchange evidence (the caller performs that match before invoking this).
 * An operator-approved, STOP-only observation is distinct from strategy
 * ownership. Arbitrary 'external stop exists' does not authorize trailing.
 */
internal fun williamsObservedStopCanTrail(observation: JSONObject?): Boolean {
    if (observation == null || !observation.optBoolean("automatic_trailing_authorized",false)) return false
    return when (observation.optString("stop_proof")) {
        "OWNED_STOP_SEEN" ->
            observation.optBoolean("strategy_ownership_verified",false) &&
                observation.optString("management_mode") == "STRATEGY_OWNED"
        "EXTERNAL_PROTECTED" ->
            !observation.optBoolean("strategy_ownership_verified",false) &&
                observation.optString("management_mode") in setOf("OPERATOR_APPROVED_STOP_ONLY", "AUTO_VERIFIED_STOP_ONLY")
        else -> false
    }
}

internal fun lastKnownFuturesPositionsForDisplay(
    envelope: Pair<String, Long>?, refreshError: String?
): Triple<JSONArray, String, Long> {
    val rows = envelope?.let { JSONArray(it.first) } ?: JSONArray()
    val error = refreshError ?: if (envelope == null)
        "Exchange position snapshot is loading; no cached snapshot yet"
    else "Showing a stale cached Futures position snapshot while refresh runs"
    return Triple(rows, error, envelope?.second ?: 0L)
}

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
/**
 * Recovery may release only the pause produced by temporary UTC baseline
 * uncertainty. An operator pause, kill, disabled runtime or outstanding
 * exchange reconciliation remains authoritative.
 */
/**
 * An expired conditional H1 entry does not become immortal just because
 * Binance reports TRIGGERING/TRIGGERED. Request a cancel but never assume
 * terminal state until exact exchange order and position proof are reconciled.
 */
internal fun expiredTriggeringEntryNeedsExchangeCancel(
    expired:Boolean, orderStatus:String, accountPositionKnownFlat:Boolean,
    explicitOperatorOrder:Boolean
):Boolean = expired && accountPositionKnownFlat && !explicitOperatorOrder &&
    orderStatus.uppercase(Locale.US) in setOf("TRIGGERING","TRIGGERED")

internal fun canReleaseVerifiedUtcRiskPause(
    baselineAndPnlVerified:Boolean,
    wasRiskAutoPaused:Boolean,
    runtimeRunning:Boolean,
    operatorPaused:Boolean,
    killLatched:Boolean,
    reconcileRequired:Boolean,
    testnetOverrideActive:Boolean
):Boolean = baselineAndPnlVerified && wasRiskAutoPaused &&
    runtimeRunning && !operatorPaused && !killLatched &&
    !reconcileRequired && !testnetOverrideActive

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

internal fun unfilledPendingIntentsAllowFlatDailyBaseline(
    intents: JSONArray,
    exchangeNowMs: Long,
    maximumReconciliationAgeMs: Long = 30_000L
): Boolean {
    if (exchangeNowMs <= 0L || maximumReconciliationAgeMs <= 0L) return false
    for (i in 0 until intents.length()) {
        val intent = intents.optJSONObject(i) ?: return false
        if (!isEntryCampaignReserved(intent)) continue
        if (intent.optString("intent_role", "ENTRY") != "ENTRY" ||
            campaignLifecycleStatus(intent) != "ENTRY_PENDING"
        ) return false
        if (exchangeOrderStatus(intent) !in setOf("NEW", "PENDING", "WORKING", "ACCEPTED"))
            return false
        val confirmedAt = intent.optLong("last_reconciled_ms", 0L)
        if (confirmedAt <= 0L || confirmedAt > exchangeNowMs ||
            exchangeNowMs - confirmedAt > maximumReconciliationAgeMs
        ) return false
        val quantity = intent.optString("executed_qty").toBigDecimalOrNull() ?: return false
        val actual = intent.optString("actual_entry_executed_qty", "0").toBigDecimalOrNull()
            ?: return false
        if (quantity.signum() != 0 || actual.signum() != 0) return false
    }
    return true
}

/** Read-only validation: entry readiness needs a durable UTC-day risk baseline and provenance. */
internal fun futuresDailyRiskBaselineEstablished(baselineValue: String?, source: String?): Boolean {
    val baseline = baselineValue?.toDoubleOrNull()
    val label = source?.trim().orEmpty()
    return baseline != null && baseline.isFinite() && baseline > 0.0 &&
        label.isNotBlank() && !label.equals("UNKNOWN", ignoreCase = true) &&
        !label.equals("null", ignoreCase = true)
}

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
    val family=type.removePrefix("SHORT_").removePrefix("LONG_")
    if(type in setOf("LONG_BREAKOUT","SHORT_BREAKOUT")) return if(type.startsWith("SHORT_")) "SHORT" else "LONG"
    if(family !in setOf("REVERSAL","SUPER_AO","FRACTAL","AO_SAUCER","AO_ZERO_LINE","AO_TWIN_PEAKS",
        "AC_SIGNAL","ZONE_ADD_ON","BALANCE_LINE","COURSE_HUMP","COURSE_THUMB","COURSE_SQUAT")) return null
    return if(type.startsWith("SHORT_")) "SHORT" else "LONG"
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
    val isReversal = type == "REVERSAL" || type.endsWith("_REVERSAL")
    // TC2 ch9: WM1 is explicitly counter-trend; D1 is not its validity test.
    if (isReversal) return 1
    if (isLong && d1Bear || isShort && d1Bull) return 0
    val h4Aligned = if (isLong) h4Bull else h4Bear
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

internal data class CandleN(
    val t: Long,
    val o: Double,
    val h: Double,
    val l: Double,
    val c: Double,
    val v: Double,
    val exchangeClosed: Boolean = true // REST/history defaults; WS must provide k.x
)

internal fun validNativeCandle(c:CandleN):Boolean = c.t>=0 &&
    listOf(c.o,c.h,c.l,c.c,c.v).all { it.isFinite() } && minOf(c.o,c.l,c.c)>0 &&
    c.h>=maxOf(c.o,c.l,c.c) && c.l<=minOf(c.o,c.c) && c.v>=0

internal fun upsertNativeCandleObservation(candles:MutableList<CandleN>,incoming:CandleN):Boolean {
    if (!validNativeCandle(incoming)) return false
    val existing=candles.indexOfLast { it.t==incoming.t }
    if (existing>=0) {
        if (candles[existing].exchangeClosed && !incoming.exchangeClosed) return false
        candles[existing]=incoming
    } else {
        val after=candles.indexOfFirst { it.t>incoming.t }
        if (after<0) candles.add(incoming) else candles.add(after,incoming)
    }
    while (candles.size>600) candles.removeAt(0)
    return true
}

internal fun closedNativeStrategyCandles(candles:List<CandleN>,clockClosed:(CandleN)->Boolean):List<CandleN> =
    candles.filter { it.exchangeClosed && clockClosed(it) }

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

/**
 * SHORT TC2 signals mirror LONG geometry. Older WM3 signals contain an
 * empty setup context; blindly calling JSONObject("") crashed ALL ten H1
 * scanner workers whenever a confirmed SHORT fractal was discovered.
 *
 * A documented legacy WM3 omission may be represented explicitly, whereas
 * absent WM1/WM2 evidence or malformed nonempty JSON must remain fail-closed.
 */
internal fun reflectedTc2SetupContext(
    signalType: String,
    originalContext: String,
    reflection: Double
): String {
    require(reflection.isFinite() && reflection > 0.0) { "TC2_SHORT_REFLECTION_INVALID" }
    val context = if (originalContext.isBlank()) {
        check(signalType == "FRACTAL") { "TC2_SOURCE_CONTEXT_MISSING:$signalType" }
        JSONObject().put("wise_man", "WM3")
            .put("legacy_source_context_missing", true)
    } else {
        JSONObject(originalContext)
    }
    context.optJSONObject("angulation")?.let { proof ->
        for (key in listOf("price_slope", "jaw_slope", "teeth_slope")) {
            if (!proof.isNull(key) && proof.has(key)) proof.put(key, -proof.getDouble(key))
        }
    }
    // SHORT uses a reflected price/AO sequence. Convert BOTH colour and
    // its diagnostic reason back into actual exchange-candle coordinates.
    // TC2 PDF125 EXPECTS, rather than requires, green on bearish WM1.
    if (signalType=="REVERSAL") {
        if (context.has("wm1_ao_color")) {
            val color=context.optString("wm1_ao_color")
            context.put("wm1_ao_color",when(color) {
                "RED" -> "GREEN"; "GREEN" -> "RED"; else -> color
            })
        }
        if (context.has("wm1_ao_context_status")) {
            val status=context.optString("wm1_ao_context_status")
            context.put("wm1_ao_context_status",when(status) {
                "WM1_AO_NOT_RED" -> "WM1_AO_NOT_GREEN"
                "WM1_AO_NOT_GREEN" -> "WM1_AO_NOT_RED"
                else -> status
            })
        }
    }
    if (context.has("risk_stop_at_price") && !context.isNull("risk_stop_at_price")) {
        val originalStop = context.getDouble("risk_stop_at_price")
        require(originalStop.isFinite()) { "TC2_SHORT_CONTEXT_STOP_INVALID" }
        context.put("risk_stop_at_price", reflection - originalStop)
    }
    return context.toString()
}

private data class CampaignSignalN(
    val signalId: String,
    val type: String,
    val role: String,
    val signalBarTimeMs: Long,
    val triggerPrice: Double,
    val protectivePrice: Double,
    val teethAtDetection: Double,
    val invalidationPrice: Double,
    val reason: String,
    val strategyProfile:String=williamsTc2Profile,
    val executionSemantics:String="STOP_ENTRY",
    val setupContext:String="",
    val formationAtMs:Long=signalBarTimeMs
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
    val campaignReady: Boolean = false,
    val waveHierarchy: WilliamsWaveHierarchy? = null
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

    @Volatile private var engine: NativeEngine? = null

    fun backgroundSnapshot():BackgroundRuntimeSnapshot = engine?.backgroundSnapshot() ?: BackgroundRuntimeSnapshot(
        autoRunRequested=runCatching { prefs.getBoolean("auto_run",false) }.getOrDefault(false))

    fun stop(): Boolean = engine?.stop()?.optBoolean("stopped", false) ?: true

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

    @Synchronized
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

            method == "GET" && path == "/api/v1/market/quote" ->
                x.marketQuote(params["symbol"]).toString()

            method == "GET" && path == "/api/v1/market/klines" ->
                x.klines(
                    requestedSymbol = params["symbol"],
                    requestedInterval = params["interval"],
                    requestedEndTime = params["until"]?.toLongOrNull(),
                    requestedBars = params["bars"]?.toIntOrNull() ?: 120
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

            method == "GET" && path == "/api/v1/cockpit" ->
                x.cockpit().toString()

            method == "POST" && path == "/api/v1/ui/event" ->
                x.uiEvent(JSONObject(body)).toString()

            method == "POST" && path == "/api/v1/control/block" ->
                x.blockNewEntries().toString()

            method == "POST" && path == "/api/v1/control/shutdown" ->
                x.stop().toString()

            method == "GET" && path == "/api/v1/diagnostics" ->
                x.diagnostics().toString()

            method == "POST" && path == "/api/v1/config/binance" ->
                x.configure(JSONObject(body)).toString()

            method == "POST" && path == "/api/v1/config/futures-leverage" ->
                x.configureFuturesLeverage(JSONObject(body)).toString()

            method == "DELETE" && path == "/api/v1/config/binance" ->
                x.clear().toString()

            method == "POST" && path == "/api/v1/control/start" ->
                x.start().toString()

            method == "POST" && path == "/api/v1/control/stop" ->
                x.stopNewEntries().toString()

            method == "POST" && path == "/api/v1/control/pause" ->
                x.pause().toString()

            method == "POST" && path == "/api/v1/control/resume" ->
                x.resume().toString()

            // Legacy endpoint remains recognisable to old installed UIs,
            // but NEVER dispatches a trading mutation. Do not silently
            // substitute an automatic order or weaken the execution gate.
            method == "POST" && path in setOf(
                "/api/v1/control/testnet-force-mode",
                "/api/v1/control/testnet-force-entry"
            ) -> JSONObject().put("accepted", false).put("enabled", false)
                .put("error", "OPERATOR_FORCED_TRADING_REMOVED")
                .put("message", "Ручные принудительные входы удалены. Обычные входы только по подтверждённым правилам Вильямса.")
                .toString()

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

            method == "POST" && path == "/api/v1/control/positions/supervise" ->
                x.approveExistingPositionSupervision(JSONObject(body)).toString()

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
    // The diagnostic 12 archive records repeated "sent ping but didn't
    // receive pong within 30000ms" while Binance's actual market stream
    // was needed for trading. Use payload freshness + bounded reconnect
    // for PUBLIC market streams instead of the client's active 30s PING.
    // Keep the existing 30s PING on the private USER stream and REST client;
    // fresh public market and bookTicker data remain mandatory to enter.
    private val publicMarketWebSocketClient = http.newBuilder()
        .pingInterval(0, TimeUnit.SECONDS).build()
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
    @Volatile private var futuresLeverageSettings = prefs.getString("futures_leverage_settings_v1", null)?.let {
        FuturesLeverageSettings.fromJson(JSONObject(it))
    } ?: FuturesLeverageSettings()
    @Volatile private var entryTimePolicy=prefs.getString("entry_time_policy_v1",null)?.let {
        WilliamsEntryTimePolicy.fromJson(JSONObject(it))
    } ?: WilliamsEntryTimePolicy()
    @Volatile private var williamsProfile=williamsRestoredExecutionSelection(
        prefs.getString("williams_strategy_profile_v1",null),prefs.getString("williams_execution_policy_v2",null))
    @Volatile private var futuresExchangeInfoCache: Pair<Long, JSONObject>? = null
    private val futuresCampaignCoordinator by lazy {
        NativeFuturesCampaignCoordinator(executionStore) {
            BinanceUsdMFuturesTestnetAdapter(key(), secret(), http, rateGuard,
                beforeExposureMutation = {
                    executionDoorBlocker(running, paused, reconcileRequired, killLatched)?.let { error(it) }
                    check(RuntimeServiceState.active) { "Runtime service is not active before Futures exposure mutation" }
                })
        }.also { coordinator ->
            coordinator.requireM15FractalForAutomatedEntries = false
            coordinator.m15EntryVerifier = null
            coordinator.requireBookH1ProofForAutomatedEntries = true
            coordinator.bookH1EntryVerifier = { plan -> verifyBookH1PlanBeforeSubmit(plan) }
            coordinator.bookNtdExitVerifier = { owner,reason -> verifyNtdExitBeforeSubmit(owner,reason) }
            coordinator.bookCourseTrimVerifier = { owner,quantity -> verifyCourseTrimBeforeSubmit(owner,quantity) }
            coordinator.bookCourseExitVerifier = { owner,reason -> verifyCourseExitBeforeSubmit(owner,reason) }
            coordinator.setEditionSelectionProvider { williamsProfile }
            coordinator.setEntryTimePolicyProvider { entryTimePolicy }
            coordinator.setEntryPassportAuditSink { event -> auditStore.recordEntryPassportEvent(event) }
            coordinator.setObservationSink { snapshot ->
                auditStore.recordCampaignSnapshot(snapshot)
                runCatching { WilliamsSignalJournal(executionStore).orders(snapshot,System.currentTimeMillis()) }
            }
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
    // Prime H1 for all ten symbols before optional context/chart frames.
    // Missing H4/D1/H6/M15 never turns off read-only H1 market analysis.
    private val startupFrames = listOf("1h", "4h", "1d", "6h", "15m")
    // Prioritize analysis horizons. Full exchange history of minute-by-minute
    // bars for ten pairs can consume gigabytes and is NOT safe to force on mobile.
    // 1m/3m/5m remain persisted on demand as bounded chart/monitoring history.
    private val durableBackfillFrames = listOf(
        "1h", "4h", "6h", "1d", "1w", "1M",
        "15m", "30m", "2h", "8h", "12h", "3d"
    )
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
    @Volatile private var deepHistoryBackfillRunning = false
    @Volatile private var deepHistoryPairsComplete = 0
    @Volatile private var deepHistoryLastError: String? = null
    @Volatile private var deepHistoryStorageLow = false
    @Volatile private var deepHistoryLastStartMs = 0L
    @Volatile private var historyWarmupRunning = false
    @Volatile private var historyReady = false // All ten H1 decision sets ready.
    @Volatile private var readOnlyH1Ready = false // At least one valid H1 working set.
    @Volatile private var h1HistoryReadyCount = 0
    @Volatile private var optionalContextHistoryReady = false
    @Volatile private var historyWarmupLastStartedMs = 0L
    private val historyWarmupRetryIntervalMs = 60_000L
    @Volatile private var scannerMarketMetadataWarning:String? = null
    private val activeHistoryTasks = java.util.concurrent.ConcurrentHashMap.newKeySet<java.util.concurrent.Future<Boolean>>()
    @Volatile private var historyState = "IDLE"
    @Volatile private var historyLastError: String? = null

    private val scanCacheTtlMs = 12_000L
    private val scanUniverseCacheTtlMs = 15_000L
    @Volatile private var cachedScanUniverse: Triple<List<String>, Map<String, Double>, Map<String, Double>>? = null
    @Volatile private var scanUniverseCacheAtMs = 0L
    private val dailyRiskCheckLock = Any()
    private val waveHierarchyCache = java.util.concurrent.ConcurrentHashMap<String, WilliamsWaveHierarchy>()
    // Best-effort finer candles are fetched only to improve local wave context,
    // never on the scan/Execution Door thread.
    private val adaptiveWaveContextCache =
        java.util.concurrent.ConcurrentHashMap<String, Pair<Long, JSONObject>>()
    // One rate-limited research download queue. Never occupy scanner warmup
    // or existing-position reconciliation executors with deep child history.
    private val adaptiveWaveFetchExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable,"williams-adaptive-child-wave").apply { isDaemon=true }
    }
    private val adaptiveWaveFetchAttemptMs =
        java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile private var adaptiveWaveFetchLastError: String? = null
    private val deviceWaveReplayExecutor=Executors.newSingleThreadScheduledExecutor { task->
        Thread(task,"williams-historical-wave-replay").apply {
            isDaemon=true;priority=Thread.NORM_PRIORITY-2
        }
    }
    @Volatile private var deviceWaveReplayTask:java.util.concurrent.ScheduledFuture<*>?=null
    @Volatile private var deviceWaveReplayState="IDLE"
    @Volatile private var deviceWaveReplayLastError:String?=null
    private var deviceWaveReplayNextPair=0
    private val dailyRiskPreflightLock = Any()
    @Volatile private var dailyRiskPreflightRunning = false
    @Volatile private var lastDailyRiskPreflightAtMs = 0L
    @Volatile private var lastScanDailyRiskCheckAtMs = 0L
    @Volatile private var lastScanDailyRiskCheckAllowed = false
    @Volatile private var lastNativeManagementAttemptMs = 0L
    @Volatile private var lastProtectedRiskCapManagementAtMs = 0L
    @Volatile private var lastExternalStopManagementAtMs = 0L
    private val deepWatchTopN = 10
    private val scannerUniverseLabel = "USD_M_FUTURES_TESTNET_10"

    @Volatile
    private var running = false

    @Volatile
    private var paused = prefs.getBoolean("operator_entries_paused", false)

    // Legacy forced-direction orders must still be RECOVERED from the
    // exchange journal, but no new operator override can ever be armed.
    @Volatile
    private var forceTestnetMode = false

    @Volatile
    private var nativeManagementRunning = false

    @Volatile
    private var scanning = false

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var lastScanAt = 0L

    @Volatile private var worker: Thread? = null
    @Volatile private var lastWorkerLoopElapsedMs=0L
    fun backgroundSnapshot():BackgroundRuntimeSnapshot = BackgroundRuntimeSnapshot(
        runCatching { prefs.getBoolean("auto_run",false) }.getOrDefault(false),running,
        worker?.isAlive==true,runtimeGeneration,lastWorkerLoopElapsedMs)
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
    private val futuresReconciliationLock = java.util.concurrent.locks.ReentrantLock(true)
    @Volatile private var futuresReconciliationRunning = false
    @Volatile private var futuresStreamReconcileRunning = false
    @Volatile private var futuresStreamReconcilePending = false
    @Volatile private var lastUserStreamReconcileAttemptMs = 0L
    @Volatile private var userStreamReconcileStartedAtMs = 0L
    @Volatile private var userStreamReconcileFinishedAtMs = 0L
    @Volatile private var userStreamReconcileFailure: String? = null
    @Volatile private var lastUserEventMs = 0L
    @Volatile private var lastUserStreamSyncAtMs = 0L
    // GET-only forensic data: never used by the Execution Door, position
    // ownership, STOP replacement, reconciliation or order admission.
    @Volatile private var signedFillAuditJson = """{"state":"NOT_REQUESTED"}"""
    @Volatile private var signedFillAuditRunning = false
    @Volatile private var signedFillAuditLastAttemptMs = 0L
    @Volatile private var liveUsdtBalance = 0.0
    @Volatile private var liveUsdtBalanceKnown = false
    @Volatile private var futuresAccountSnapshotUpdatedAtMs = 0L
    @Volatile private var futuresAccountSnapshotRefreshRunning = false
    @Volatile private var futuresAccountSnapshotLastAttemptMs = 0L
    @Volatile private var futuresAccountSnapshotRefreshError: String? = null
    // Publish bytes and their confirmation time together; a later refresh cannot relabel old rows as fresh.
    @Volatile private var cachedFuturesPositionEnvelope: FuturesPositionDisplayEnvelope? = null
    // An in-flight display REST read must not republish a pre-mutation STOP
    // snapshot after an exchange-confirmed structural stop replacement.
    @Volatile private var futuresPositionSnapshotEpoch = 0L
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
    private var exchangeClockProbeThread: Thread? = null
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
    // Re-evaluate confirmed H1 candidates immediately after a coincident
    // exchange reconciliation, without waiting for another 30-second scan.
    @Volatile private var scanRetryAfterReconciliation = false
    @Volatile private var scannerError: String? = null
    @Volatile private var lastScannerSymbolErrors = JSONObject()
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
        // Migrate installations left in legacy forced-direction mode.
        // DO NOT silently resume automatic entries after removing the UI:
        // pause, retain every existing position/STOP and require an explicit
        // operator START/RESUME after exchange reconciliation.
        if (prefs.getBoolean("force_testnet_mode", false) ||
            executionStore.readState("futures_testnet_force_mode") == "true") {
            paused = true
            val preferencesCleared = prefs.edit()
                .putBoolean("force_testnet_mode", false)
                .putBoolean("operator_entries_paused", true)
                .putBoolean("auto_run", false).commit()
            val databaseCleared =
                executionStore.writeState("futures_testnet_force_mode", "false")
            if (!preferencesCleared || !databaseCleared) {
                reconcileRequired = true
                persistSafetyFlag("reconcile_required", true)
                lastError = "LEGACY_FORCED_MODE_MIGRATION_UNVERIFIED"
            }
            runCatching { auditStore.recordCockpitEvent(
                "OPERATOR_DIRECTION_MODE_REMOVED", state="PAUSED",
                detail="Old forced override disabled; existing Binance orders must be reconciled before restart."
            ) }
        }
        startRestDataProbe()
        restoreExecutionAccumulators()
        // SQLite is authoritative for the execution-door lease. SharedPreferences
        // mirrors it for backward compatibility, but must not hide a stale SQLite lease.
        val interruptedGateSymbol =
            (prefs.getString("execution_gate_symbol", "") ?: "")
                .ifBlank { executionStore.readState("execution_gate_symbol").orEmpty() }
        if (interruptedGateSymbol.isNotBlank()) {
            // An interrupted financial mutation must fence EVERY new entry,
            // but must not silently opt the operator out of read-only market
            // supervision after reboot. The exchange owns the truth and safe
            // START will reconcile open positions/STOPs before any new risk.
            reconcileRequired = true
            persistSafetyFlag("reconcile_required", true)
            lastError =
                "Execution gate interrupted for " + interruptedGateSymbol +
                    "; REST reconciliation required; background monitoring opt-in retained"
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

    // The loss-streak UTC day must follow Binance Futures server time, not
    // the Android timezone or an ahead-of-server handset clock (-1021).
    // A stale clock fails closed for NEW entries, without stopping H1 analysis.
    private fun futuresLossStreakGuard(): FuturesLossStreakDecision =
        if(!futuresMarketData.clockSnapshot().optBoolean("fresh",false)) {
            FuturesLossStreakDecision(false,0,false,null,"FUTURES_LOSS_STREAK_UTC_CLOCK_UNVERIFIED")
        } else evaluateFuturesLossStreak(
            tradeRows = TradeJournal.trades(prefs),
            nowMs = futuresMarketData.cachedExchangeNowMs()
        )

    private fun dailyEntryHalt(
        nowMs:Long=futuresMarketData.cachedExchangeNowMs(),
        clockFresh:Boolean=futuresMarketData.clockSnapshot().optBoolean("fresh",false)
    ):FuturesDailyEntryHaltDecision = evaluateFuturesDailyEntryHalt(
        executionStore.readState("futures_daily_entry_halt_utc_day_start_ms"),nowMs,clockFresh)

    /** Read-only SQLite/clock check: never perform signed REST I/O on the Android UI. */
    private fun dailyRiskBaselineReady(): Boolean {
        if (!futuresMarketData.clockSnapshot().optBoolean("fresh", false)) return false
        val utc = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        utc.timeInMillis = futuresMarketData.cachedExchangeNowMs()
        utc.set(Calendar.HOUR_OF_DAY, 0)
        utc.set(Calendar.MINUTE, 0)
        utc.set(Calendar.SECOND, 0)
        utc.set(Calendar.MILLISECOND, 0)
        val suffix = utc.timeInMillis.toString()
        return futuresDailyRiskBaselineEstablished(
            executionStore.readState("futures_day_start_equity_" + suffix),
            executionStore.readState("futures_day_start_equity_source_" + suffix)
        )
    }

    private fun executionReady(): Boolean =
        futuresExecutionAdapterReady &&
            RuntimeServiceState.active &&
            !RuntimeServiceState.monitorHeartbeatStale &&
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
            signedFuturesReconciliationFresh(lastRestReconcileMs, System.currentTimeMillis()) &&
            restAccountReady &&
            accountFundedForEntry() &&
            dailyRiskBaselineReady() &&
            dailyEntryHalt().allowNewEntries &&
            historyReady &&
            futuresFsmAllowsEntry() &&
            futuresLossStreakGuard().allowNewEntries &&
            !equityCircuitBreaker.isTripped()

    private fun executionBlockers(): List<String> =
        buildList {
            if (!futuresExecutionAdapterReady) add("futures_execution_adapter_disabled")
            if (!RuntimeServiceState.active) add("foreground_service_not_confirmed")
            if (RuntimeServiceState.monitorHeartbeatStale) add("runtime_worker_heartbeat_stale")
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
            if (!signedFuturesReconciliationFresh(lastRestReconcileMs, System.currentTimeMillis()))
                add("signed_futures_account_position_stop_reconciliation_stale_or_unknown")
            if (!restAccountReady) add("rest_account_not_ready")
            else if (!accountFundedForEntry()) add("futures_account_unfunded_or_zero_usdt")
            if (!dailyRiskBaselineReady()) add("futures_daily_risk_baseline_unverified")
            dailyEntryHalt().reason?.let { add(it) }
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

    fun health(): JSONObject {
        val coordinator = runCatching { futuresCampaignCoordinator.status() }.getOrElse {
            JSONObject().put("state", "RECONCILE_REQUIRED").put("blocker", it.message)
        }
        val displayState = authoritativeRuntimeDisplayState(campaignEngineEnabled, running, paused,
            killLatched, coordinator.optString("state"), stateName())
        val activeReconciliation = futuresReconciliationRunning || coordinator.optBoolean("reconciliation_running", false)
        val enabled = executionReady() && !activeReconciliation && coordinator.optBoolean("ready_for_new_entry", false)
        return JSONObject()
            .put("ok", running && !reconcileRequired && !killLatched &&
                coordinator.optString("state") in setOf("READY", "OPEN_PROTECTED", "ENTRY_PENDING"))
            .put("execution_ready", enabled)
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
                enabled
            )
            .put("state", displayState)
            .put("open_positions", positionList().size)
            .put("max_open_positions", maxOpenPositions)
            .put("reserved_risk_pct", reservedRiskPct())
            .put("circuit_breaker_tripped", equityCircuitBreaker.isTripped())
            .put("managed_equity", estimateManagedEquity())
            .put("max_total_risk_pct", maxTotalRiskPct)
            .put("max_risk_per_trade_pct", maxRiskPerTradePct)
            .put("reconcile_required", reconcileRequired)
            .put("futures_coordinator_state", coordinator.optString("state"))
            .put("futures_coordinator_blocker", coordinator.opt("blocker") ?: JSONObject.NULL)
            .put("futures_reconciliation_in_progress", activeReconciliation)
            .put("last_reconciliation_attempt_at_ms", lastReconciliationAttemptAtMs)
            .put("last_reconciliation_completed_at_ms", lastReconciliationCompletedAtMs)
            .put("last_reconciliation_result", lastReconciliationResult)
            .put("last_reconciliation_lock_wait_ms", lastReconciliationLockWaitMs)
            .put("last_reconciliation_lock_wait_failure", lastReconciliationLockWaitFailure ?: JSONObject.NULL)
            .put("last_rest_reconcile_ms", lastRestReconcileMs)
            .put("execution_state_contract", JSONObject()
                .put("version", 1)
                .put("state", displayState)
                .put("execution_enabled", enabled)
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
    }

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
        val changed = oldKey != newKey || oldSecret != newSecret

        // Existing working identity must never be silently replaced by a
        // possibly incorrect or offline key. With an EMPTY vault, however,
        // the operator must be able to save a new encrypted credential even
        // when Binance is temporarily unreachable after phone reboot.
        val existingIdentity = oldKey.isNotBlank() && oldSecret.isNotBlank()
        var account: JSONObject? = null
        var validationError: String? = null
        try {
            val futures = BinanceUsdMFuturesTestnetAdapter(newKey, newSecret, http)
            futures.serverTime()
            account = futures.account()
            futures.requireOneWayMode()
        } catch (x: Exception) {
            validationError = safeDiagnosticText(x.message ?: x.javaClass.simpleName)
            if (existingIdentity && changed) {
                throw IllegalStateException(
                    "Existing Binance credentials kept; replacement could not be verified. " +
                        "Futures Demo: $validationError", x
                )
            }
        }

        // A single durable commit replaces the asynchronous apply()/rollback
        // race, where a failed REST request could erase freshly typed keys.
        check(prefs.edit().putString("api_key", newKey)
            .putString("api_secret", newSecret).commit()) {
            "Encrypted Binance credentials could not be written to device storage"
        }
        val persisted = key() == newKey && secret() == newSecret
        check(persisted) {
            "Encrypted Binance credentials did not survive immediate read-back"
        }
        if (validationError != null) {
            restAccountReady = false
            restAccountLastError = "Credentials stored; exchange verification pending: $validationError"
            // New exposure remains blocked until signed account and position
            // reconciliation succeeds. Do not erase the only saved credentials.
            reconcileRequired = true
            check(persistSafetyFlag("reconcile_required", true)) {
                "Credentials saved but SQLite reconciliation fence could not be written"
            }
        } else {
            val authenticatedAccount = checkNotNull(account)
            if (!updateCachedFuturesAccount(authenticatedAccount)) {
                // A malformed/temporarily incomplete signed account response
                // is a failed TRADING preflight, not evidence that encrypted
                // credential persistence failed after a verified commit.
                validationError = "Signed Futures account balances are incomplete"
                restAccountReady = false
                reconcileRequired = true
                persistSafetyFlag("reconcile_required", true)
            } else {
                restLastError = null
            }
        }
        return JSONObject()
            .put("configured", true)
            .put("saved_to_encrypted_storage", true)
            .put("testnet", true)
            .put("standalone", true)
            .put("account_type", "USD_M_FUTURES")
            .put("validation", if (validationError == null)
                "FUTURES_CREDENTIALS_AND_ONE_WAY_MODE_PASS"
                else "PENDING_EXCHANGE_VERIFICATION")
            .put("validation_detail", validationError ?: JSONObject.NULL)
            .put("read_back_verified", persisted)
            .put("execution_enabled", false)
            .put("execution_blocker", if (validationError == null)
                "start_required_for_local_Futures_reconciliation_and_scanner"
                else "CREDENTIALS_SAVED_BUT_EXCHANGE_RECONCILIATION_REQUIRED")
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
        cachedFuturesPositionEnvelope = null
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
        if (running && worker?.isAlive==true) return JSONObject().put("started", false).put("reason", "already_running")
        if(running) {
            // This synchronized start cannot race another start's initialization.
            // A dead worker cannot be accepted as an already-running monitor.
            running=false
            ++runtimeGeneration
        }

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
        startDeviceWaveReplay(generation)
        // Independent reconciliation/account gates block entries. Only an explicit
        // kill latch needs to force the pause flag during safe monitoring.
        paused = killLatched || prefs.getBoolean("operator_entries_paused", false) ||
            prefs.getBoolean("risk_daily_baseline_auto_paused", false)
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
        readOnlyH1Ready = false
        h1HistoryReadyCount = 0
        optionalContextHistoryReady = false
        historyState = "LOADING"
        historyLastError = null
        scannerState = "WAITING_FOR_HISTORY"
        scannerError = null
        scanRunId = ""
        scanRetryAfterReconciliation = false
        scanProgressSymbols = 0
        warmCoreHistoryAsync(generation)

        lastWorkerLoopElapsedMs=0L
        worker = Thread {
            try {
                while (running && generation == runtimeGeneration) {
                    lastWorkerLoopElapsedMs=android.os.SystemClock.elapsedRealtime()
                    try {
                        repairStaleMarketStreams()
                        // A connected private stream can still be unsynchronized after a
                        // transient REST/Execution-Door failure. Retry reconciliation with
                        // bounded backoff instead of leaving the runtime permanently blocked
                        // until another socket reconnect happens. The gate remains fail-closed.
                        if (userStreamConnected && userStreamSyncRequired && !futuresStreamReconcileRunning) {
                            scheduleFuturesStreamReconciliation("automatic sync retry")
                        }

                        // Establish the UTC daily-loss baseline independently of signals.
                        // Never serialize H1 discovery/UI behind signed account/income requests.
                        // Missing provenance blocks new entries, not existing-position management.
                        if (shouldRetryDailyRiskBaseline(
                                running = running,
                                paused = paused,
                                killLatched = killLatched,
                                reconcileRequired = reconcileRequired,
                                accountReady = restAccountReady,
                                userStreamConnected = userStreamConnected,
                                userStreamSyncRequired = userStreamSyncRequired,
                                baselineReady = dailyRiskBaselineReady()
                            )) {
                            scheduleDailyRiskBaselinePreflight()
                        }

                        if (!historyReady && !historyWarmupRunning &&
                            System.currentTimeMillis()-historyWarmupLastStartedMs >= historyWarmupRetryIntervalMs) {
                            warmCoreHistoryAsync(generation)
                        }
                        // The H1 read-only scanner works as soon as ONE
                        // decision frame is ready. Trading additionally
                        // requires all ten H1 series + independent safety
                        // admission; partial chart data is never an entry pass.
                        if(readOnlyH1Ready) {
                            if (historyReady) schedulePersistentHistoryBackfill(generation)
                            requestScan()
                        } else {
                            scannerState = "WAITING_FOR_HISTORY"
                            scannerError = historyLastError ?: "Waiting for the first closed H1 working set"
                        }
                        // Never initiate account-wide reconciliation INSIDE an H1 scan:
                        // the old scanner raced its own signed safety audit and finished
                        // READY_BLOCKED on every pass. Manage exposure independently,
                        // after read-only discovery gets priority. User-stream event
                        // reconciliation remains independent and immediate.
                        if (!nativeManagementRunning &&
                            shouldScheduleIndependentFuturesMonitor(
                                nowMs = System.currentTimeMillis(),
                                lastSuccessfulMs = lastRestReconcileMs,
                                lastAttemptMs = lastNativeManagementAttemptMs,
                                coordinatorState = futuresCampaignCoordinator.state.name,
                                scanning = scanning,
                                reconciliationActive = futuresReconciliationRunning || futuresStreamReconcileRunning,
                                safetyLatchUnresolved = reconcileRequired
                            )) scheduleNativePositionManagement()
                    } catch (x: Exception) {
                        lastError = x.javaClass.simpleName + ": " + (x.message ?: "")
                    }
                    try { Thread.sleep(1_000L) } catch (_: InterruptedException) { break }
                }
            } finally {
                if (running && generation == runtimeGeneration) {
                    lastWorkerLoopElapsedMs=0L
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
        // A newly started monitoring worker proves the Android runtime can
        // initialize again. Execution gates remain separate and fail-closed.
        RuntimeServiceState.refreshLease?.invoke()
        RuntimeStartupEvidence.clear(context)
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

    fun blockNewEntries(): JSONObject {
        killLatched = true
        paused = true
        check(persistSafetyFlag("kill_latched", true)) { "Cannot persist emergency entry block; entries remain blocked in memory" }
        val result = stopNewEntries()
        runCatching { auditStore.recordCockpitEvent("CONTROL", state = "EMERGENCY_ENTRY_BLOCK", detail = "Existing positions remain managed; explicit reset required") }
        return result.put("kill_switch_latched", true).put("reason", "Новые входы аварийно заблокированы. Сопровождение открытых позиций сохранено.")
    }

    fun stopNewEntries(): JSONObject {
        paused = true
        check(prefs.edit().putBoolean("operator_entries_paused", true).commit()) {
            "Cannot persist new-entry pause; monitoring continues"
        }
        val cancellation = if (key().isNotBlank() && secret().isNotBlank()) runCatching {
            withCampaignMutation("FUTURES_ACCOUNT", "OPERATOR_SUSPEND_ENTRIES",
                allowPaused = true, allowStopped = true, allowKillLatched = true,
                allowReconcileRequired = true, waitForDoorMs = 50_000L) {
                futuresCampaignCoordinator.cancelPendingEntries("OPERATOR_SUSPEND_ENTRIES")
            }
        }.getOrElse { JSONObject().put("ok", false).put("error", safeDiagnosticText(it.message.orEmpty())) }
        else JSONObject().put("ok", false).put("error", "Credentials unavailable")
        if (!cancellation.optBoolean("ok")) {
            reconcileRequired = true
            persistSafetyFlag("reconcile_required", true)
        }
        runCatching { auditStore.recordCockpitEvent("CONTROL", state = "ENTRIES_SUSPENDED",
            detail = "Pending cancellation confirmed=" + cancellation.optBoolean("ok") + "; monitoring_running=" + running) }
        return JSONObject().put("stopped", false).put("paused", true).put("monitoring_running", running)
            .put("pending_cancellation_confirmed", cancellation.optBoolean("ok"))
            .put("reason", "Новые входы остановлены; сопровождение позиций продолжается")
    }

    fun stop(): JSONObject {
        paused = true
        check(prefs.edit().putBoolean("operator_entries_paused", true).commit()) { "Cannot persist shutdown entry fence" }
        val cancellation = if (key().isNotBlank() && secret().isNotBlank()) {
            runCatching {
                withCampaignMutation(symbol = "FUTURES_ACCOUNT", purpose = "RUNTIME_STOP_CANCEL_PENDING",
                    allowPaused = true, allowStopped = true, allowKillLatched = true,
                    allowReconcileRequired = true, waitForDoorMs = 50_000L) {
                    val result = futuresCampaignCoordinator.cancelPendingEntries("RUNTIME_STOP")
                    val positions = futuresCampaignCoordinator.positionsSnapshot()
                    val intents = futuresCampaignCoordinator.intentsSnapshot()
                    val safe = canShutdownFuturesRuntime(result.optBoolean("ok"), positions, intents)
                    if (safe) {
                        // Fence queued mutations before releasing the account-wide door.
                        running = false
                        ++runtimeGeneration
                    }
                    result.put("flat_confirmed", safe)
                }
            }.getOrElse { JSONObject().put("ok", false).put("error", safeDiagnosticText(it.message.orEmpty())) }
        } else {
            val localEmpty = runCatching { futuresCampaignCoordinator.intentsSnapshot().length() == 0 }.getOrDefault(false)
            JSONObject().put("ok", localEmpty).put("flat_confirmed", localEmpty)
        }
        if (!cancellation.optBoolean("ok") || !cancellation.optBoolean("flat_confirmed")) {
            runCatching { auditStore.recordCockpitEvent("CONTROL", state = "SHUTDOWN_REFUSED", detail = "Position or pending order exists, or Binance state is unknown; monitoring preserved") }
            return JSONObject().put("stopped", false).put("monitoring_running", running)
                .put("reason", "Полное выключение запрещено: есть позиция, ожидающая заявка или состояние Binance неизвестно. Сопровождение сохранено.")
        }
        ++runtimeGeneration
        running = false
        deviceWaveReplayTask?.cancel(false)
        deviceWaveReplayState="STOPPED"
        paused = false
        runCatching { auditStore.recordCockpitEvent("CONTROL", state = "SHUTDOWN_CONFIRMED", detail = "Pending entries canceled and account flat confirmed after cancellation") }
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
        readOnlyH1Ready = false
        h1HistoryReadyCount = 0
        optionalContextHistoryReady = false
        historyState = "STOPPED"
        historyLastError = null
        activeHistoryTasks.forEach { it.cancel(true) }
        activeHistoryTasks.clear()

        RuntimeServiceState.refreshLease?.invoke()
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
        deviceWaveReplayTask?.cancel(false)
        deviceWaveReplayState="STOPPED"
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
        check(prefs.edit().putBoolean("operator_entries_paused", true).commit()) { "Pause persistence failed" }
        runCatching { auditStore.recordCockpitEvent("CONTROL", state = "SEARCH_PAUSED", detail = "Position management remains active; resting entries remain on Binance") }
        return JSONObject().put("paused", true)
    }

    fun resume(): JSONObject {
        check(prefs.edit().putBoolean("operator_entries_paused", false).commit()) { "Resume persistence failed" }
        paused = false
        runCatching { auditStore.recordCockpitEvent("CONTROL", state = "SEARCH_RESUMED") }
        return JSONObject().put("resumed", true)
    }

    /** Explicit operator exit; still uses the Futures Execution Door and exchange confirmation. */
    @Synchronized
    fun sell(symbol: String): JSONObject = sellFuturesPosition(symbol)

    fun approveExistingPositionSupervision(payload:JSONObject):JSONObject {
        val symbol=payload.getString("symbol").uppercase(Locale.US)
        val result=withCampaignMutation(
            symbol,"OPERATOR_APPROVE_EXISTING_STOP_ONLY",
            allowPaused=true,allowStopped=true,allowKillLatched=true,
            allowReconcileRequired=true,waitForDoorMs=15_000L
        ) {
            futuresCampaignCoordinator.approveOperatorStopSupervision(
                symbol,payload.getString("quantity"),payload.getString("entry_price"),
                payload.getString("stop"),payload.getString("confirmation"))
        }
        if(result.optBoolean("ok")) {
            runCatching { auditStore.recordCockpitEvent(
                "OPERATOR_SUPERVISION_APPROVED",symbol,state="STOP_ONLY",
                detail="Original book ENTRY not restored; no entry, addition or market exit authorized."
            ) }
            scheduleNativePositionManagement()
        }
        return result
    }

    // Manual forced trading removed. Old W2E_ / operator intents are
    // deliberately still recognised during reconciliation/recovery.

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
        marketSocket = publicMarketWebSocketClient.newWebSocket(request, object : WebSocketListener() {
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
        publicSocket = publicMarketWebSocketClient.newWebSocket(request, object : WebSocketListener() {
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
                // A temporary network/Android service failure must not erase
                // the user's explicit reboot auto-run opt-in. Recovery stays
                // fail-closed and requires a subsequent explicit START or boot.
                reconcileRequired = true
                persistSafetyFlag("reconcile_required", true)
                scannerState = "BLOCKED_RUNTIME_RECOVERY"
                scannerError = "Automatic recovery limited after three failures in 15 minutes; " +
                    "auto-run preference preserved; use START to retry safe Binance reconciliation"
                lastError = scannerError
                RuntimeServiceState.lastError = scannerError
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
                reconcileRequired = true
                persistSafetyFlag("reconcile_required", true)
                scannerState = "BLOCKED_RUNTIME_RECOVERY"
                scannerError = blocker
                lastError = "Runtime recovery blocked (" + reason + "): " + blocker
                RuntimeServiceState.lastError = safeDiagnosticText(lastError.orEmpty())
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
            k.optString("v").toDoubleOrNull() ?: return,
            exchangeClosed=k.optBoolean("x",false)
        )
        if (!validNativeCandle(candle)) {
            lastError="INVALID_MARKET_KLINE: $symbol $frame; observation ignored"
            return
        }
        val key = symbol + ":" + frame
        val list = liveCandleCache.computeIfAbsent(key) { mutableListOf() }
        synchronized(list) {
            if (!upsertNativeCandleObservation(list,candle)) return
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
            .put("wave", waveInfo(candles, frame).path)
    }

    private fun warmCoreHistoryAsync(generation: Long = runtimeGeneration) {
        if (historyWarmupRunning || !running || generation != runtimeGeneration) return
        if(historyWarmupLastStartedMs>0 &&
            System.currentTimeMillis()-historyWarmupLastStartedMs<historyWarmupRetryIntervalMs) return
        historyWarmupLastStartedMs=System.currentTimeMillis()
        historyWarmupRunning = true
        // A retry for optional D1/H4/M15 frames must not revoke a previously
        // verified H1 readiness signal or terminate working read-only charts.
        historyState = if(historyReady) "H1_READY_CONTEXT_LOADING" else "LOADING"
        historyLastError = null

        Thread {
            val tasks = mutableListOf<Pair<String, java.util.concurrent.Future<Boolean>>>()
            try {
                for (frame in startupFrames) {
                    for (symbol in coreSymbols) {
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
                var h1ReadyNow = 0
                var h1TasksResolved = 0
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
                    if(label.endsWith(":1h")) {
                        h1TasksResolved++
                        if(ok) h1ReadyNow++
                        h1HistoryReadyCount=h1ReadyNow
                        // Wait until the whole ten-symbol H1 warmup group
                        // settles. Starting a ten-way REST scan after the
                        // FIRST symbol finished caused a thundering herd that
                        // could starve the remaining H1 startup workers.
                        if(h1TasksResolved==coreSymbols.size) {
                            if(h1ReadyNow>0) readOnlyH1Ready=true
                            if(h1ReadyNow==coreSymbols.size) {
                                historyReady=true
                                historyState="H1_READY_CONTEXT_LOADING"
                            }
                        }
                    } else if (!ok) allReady = false
                    if (!ok) allReady = false
                }
                if (System.currentTimeMillis() >= deadline && !allReady) {
                    tasks.forEach { (_, future) -> if (!future.isDone) future.cancel(true) }
                }

                if (generation != runtimeGeneration || !running) return@Thread

                historyReady = h1ReadyNow==coreSymbols.size
                optionalContextHistoryReady=allReady
                historyState = when {
                    allReady && historyReady -> "READY"
                    historyReady -> "H1_READY_CONTEXT_PARTIAL"
                    readOnlyH1Ready -> "PARTIAL_H1_SCANNING_AVAILABLE"
                    else -> "WAITING_RETRY"
                }
                if (historyReady) schedulePersistentHistoryBackfill(generation)
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

    /**
     * The phone owns an append-only SQLite kline history. Fetch the exchange's
     * earliest available bar for the selected Williams analysis frames in
     * small restartable batches. A network outage cannot erase progress.
     *
     * One background worker + inter-pair pauses avoid a 10×15 REST stampede.
     * Existing H1 read-only scans and protective orders are never delayed on
     * exhaustive archival downloads.
     */
    @Synchronized
    private fun schedulePersistentHistoryBackfill(generation: Long) {
        if (!running || !historyReady || deepHistoryBackfillRunning ||
            generation != runtimeGeneration) return
        val now = System.currentTimeMillis()
        if (deepHistoryLastStartMs > 0L && now - deepHistoryLastStartMs < 60_000L) return
        deepHistoryBackfillRunning = true
        deepHistoryLastStartMs = now
        Thread {
            try {
                while (running && generation == runtimeGeneration &&
                    !Thread.currentThread().isInterrupted) {
                    var incomplete = 0
                    var successes = 0
                    deepHistoryLastError = null
                    for (frame in durableBackfillFrames) {
                        for (symbol in coreSymbols) {
                            if (!running || generation != runtimeGeneration ||
                                Thread.currentThread().isInterrupted) return@Thread
                            // Leave room for the Futures journal, SQLite WAL and Android.
                            val freeBytes = runCatching {
                                android.os.StatFs(context.filesDir.path).availableBytes
                            }.getOrDefault(0L)
                            if (freeBytes < 512L * 1024L * 1024L) {
                                deepHistoryStorageLow = true
                                deepHistoryLastError = "LOW_DEVICE_STORAGE: historical download paused; existing database preserved"
                                return@Thread
                            }
                            deepHistoryStorageLow = false
                            val result = runCatching { fetchFullHistory(symbol, frame, 4) }
                            if (result.isFailure) {
                                deepHistoryLastError = symbol + ":" + frame + " " +
                                    (result.exceptionOrNull()?.message ?: "history error")
                                incomplete++
                            } else {
                                successes++
                                if (!historyStore.isComplete(symbol, frame)) incomplete++
                            }
                            // Allow new-order protection, scanning and foreground UI
                            // to consume exchange-rate and device I/O budgets first.
                            try { Thread.sleep(200L) } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                                return@Thread
                            }
                        }
                    }
                    deepHistoryPairsComplete = durableBackfillFrames.size * coreSymbols.size - incomplete
                    // Once all backfill cursors reach the exchange beginning, the
                    // same job periodically catches up missed newer candles.
                    val intervalMs = if (incomplete == 0) 3_600_000L else 30_000L
                    try { Thread.sleep(intervalMs) } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    }
                }
            } finally {
                deepHistoryBackfillRunning = false
            }
        }.apply {
            isDaemon = true
            name = "williams-durable-history-sync"
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
                val closed = closedNativeStrategyCandles(live) {
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

    /**
     * Persist an exchange-history slice. Backwards sync always starts BEFORE
     * SQLite's oldest saved bar, so restarts never download the same six pages.
     * Once the exchange beginning was reached, only fetch missing new bars.
     *
     * Return only the small working set, never all historical rows in RAM.
     */
    private fun fetchFullHistory(
        symbol: String,
        frame: String = "1h",
        maxPages: Int = 4
    ): List<CandleN> {
        val normalizedSymbol = symbol.uppercase(Locale.US)
        val pageLimit = maxPages.coerceIn(1, 8)
        try {
            if (!historyStore.isComplete(normalizedSymbol, frame)) {
                val previousOldest = historyStore.oldest(normalizedSymbol, frame)
                var endTime = if (previousOldest > 0L) previousOldest - 1L
                    else futuresMarketData.exchangeNowMs()
                for (page in 0 until pageLimit) {
                    if (!running || Thread.currentThread().isInterrupted) break
                    val array = futuresMarketData.klines(
                        normalizedSymbol, frame, 1000, endTime = endTime
                    )
                    if (array.length() == 0) {
                        // No known candles means unavailable history, not a verified
                        // exchange beginning. Do not mark an empty database complete.
                        if (historyStore.count(normalizedSymbol, frame) > 0L)
                            historyStore.markComplete(normalizedSymbol, frame)
                        break
                    }
                    val exchangeNowMs = futuresMarketData.exchangeNowMs()
                    val batch = ArrayList<MarketHistoryStore.Candle>(array.length())
                    var oldestPageTime = Long.MAX_VALUE
                    for (i in 0 until array.length()) {
                        val row = array.getJSONArray(i)
                        val openTime = row.getLong(0)
                        val closeTime = row.getLong(6)
                        if (openTime <= 0L || openTime > endTime) {
                            throw IllegalStateException("Historical USD-M Futures pagination did not move backwards")
                        }
                        oldestPageTime = minOf(oldestPageTime, openTime)
                        if (!futuresMarketData.isCandleClosed(closeTime, exchangeNowMs)) continue
                        batch += MarketHistoryStore.Candle(
                            openTime, row.getString(1).toDouble(), row.getString(2).toDouble(),
                            row.getString(3).toDouble(), row.getString(4).toDouble(),
                            row.getString(5).toDouble(), closeTime
                        )
                    }
                    if (batch.isNotEmpty()) {
                        historyStore.upsertBatch(normalizedSymbol, frame, batch)
                    }
                    if (oldestPageTime <= 0L || oldestPageTime >= endTime) {
                        throw IllegalStateException("Historical pagination failed to advance")
                    }
                    if (array.length() < 1000) {
                        historyStore.markComplete(normalizedSymbol, frame)
                        break
                    }
                    endTime = oldestPageTime - 1L
                }
            } else {
                // Catch up any number of closed bars missed while the phone was
                // offline. A three-bar tail request missed gaps longer than 3 bars.
                var newest = historyStore.newest(normalizedSymbol, frame)
                if (newest <= 0L) throw IllegalStateException("Complete history has no persisted candles")
                for (page in 0 until pageLimit) {
                    if (!running || Thread.currentThread().isInterrupted) break
                    val array = futuresMarketData.klines(
                        normalizedSymbol, frame, 1000, startTime = newest + 1L
                    )
                    if (array.length() == 0) break
                    val now = futuresMarketData.exchangeNowMs()
                    val batch = ArrayList<MarketHistoryStore.Candle>(array.length())
                    for (i in 0 until array.length()) {
                        val row = array.getJSONArray(i)
                        val opened = row.getLong(0)
                        val closed = row.getLong(6)
                        if (opened <= newest) continue
                        if (!futuresMarketData.isCandleClosed(closed, now)) break
                        batch += MarketHistoryStore.Candle(
                            opened, row.getString(1).toDouble(), row.getString(2).toDouble(),
                            row.getString(3).toDouble(), row.getString(4).toDouble(),
                            row.getString(5).toDouble(), closed
                        )
                    }
                    if (batch.isEmpty()) break
                    historyStore.upsertBatch(normalizedSymbol, frame, batch)
                    val next = batch.last().openTime
                    if (next <= newest) throw IllegalStateException("History incremental cursor stalled")
                    newest = next
                    if (array.length() < 1000) break
                }
            }
        } catch (failure: Exception) {
            historyStore.setError(normalizedSymbol, frame, failure.message ?: failure.javaClass.simpleName)
            throw failure
        }
        return historyStore.loadRecent(normalizedSymbol, frame, 150).map {
            CandleN(it.openTime, it.open, it.high, it.low, it.close, it.volume)
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

        // Volume and public bid/ask are OPTIONAL read-only ranking context.
        // Previously one missing contract or a short-lived 24h ticker outage
        // threw here and cancelled analysis of ALL TEN H1 charts. Unknown
        // spreads remain -1 (never executable) and final live top-of-book,
        // account and spread preflights must still pass independently.
        val volumes = HashMap<String, Double>()
        val volumeError=runCatching { futuresMarketData.ticker24h() }
        val volumeRows=volumeError.getOrNull()
        if(volumeRows!=null) for(i in 0 until volumeRows.length()) {
            val item=volumeRows.optJSONObject(i) ?: continue
            val symbol=item.optString("symbol").uppercase(Locale.US)
            val volume=item.optString("quoteVolume").toDoubleOrNull()
            if(symbol in coreSymbols && volume!=null && volume.isFinite() && volume>0)
                volumes[symbol]=volume
        }
        val spreads = HashMap<String, Double>()
        val bookError=runCatching { futuresMarketData.bookTickers() }
        val bookRows=bookError.getOrNull()
        if(bookRows!=null) for(i in 0 until bookRows.length()) {
            val item=bookRows.optJSONObject(i) ?: continue
            val symbol=item.optString("symbol").uppercase(Locale.US)
            if(symbol !in coreSymbols) continue
            val bid=item.optString("bidPrice").toDoubleOrNull()
            val ask=item.optString("askPrice").toDoubleOrNull()
            if(bid!=null && ask!=null && bid.isFinite() && ask.isFinite() &&
                bid>0 && ask>=bid) spreads[symbol]=(ask-bid)/bid
        }
        val missingSpreads=coreSymbols.filter { spreads[it]==null }
        val missingVolumes=coreSymbols.filter { volumes[it]==null }
        scannerMarketMetadataWarning=if(missingSpreads.isEmpty() && missingVolumes.isEmpty()) null else
            "READ_ONLY_MARKET_METADATA_PARTIAL: unknown_spread=" + missingSpreads.joinToString(",") +
            "; unknown_volume=" + missingVolumes.joinToString(",") +
            "; volume_error=" + (volumeError.exceptionOrNull()?.javaClass?.simpleName ?: "none") +
            "; book_error=" + (bookError.exceptionOrNull()?.javaClass?.simpleName ?: "none")
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
            historyReady = readOnlyH1Ready,
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
            val retryAfterVerifiedReconciliation = scanRetryAfterReconciliation &&
                !futuresReconciliationRunning &&
                futuresCampaignCoordinator.state != NativeFuturesCampaignCoordinator.State.RECONCILE_REQUIRED
            if (scanning || (!retryAfterVerifiedReconciliation &&
                !shouldScheduleReadOnlyScan(System.currentTimeMillis(),lastScanAt))) return
            scanRetryAfterReconciliation = false
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
        executionDoorBlocker(running, paused, reconcileRequired, killLatched,
            allowPaused, allowStopped, allowKillLatched, allowReconcileRequired)?.let {
            executionGate.release(reservationKey)
            error(it)
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
        // Periodic management stays opportunistic; a private-stream sync waits
        // fairly for a bounded window to avoid starvation from fast maintenance.
        // An overlap still cannot grant new trading authority or create two workers.
        // Kill, startup, and explicit operator recovery remain strict.
        val deferrable = deferrableFuturesReconciliation(purpose)
        val lockAcquired = try {
            futuresReconciliationLock.tryLock(
                futuresReconciliationLockBudgetMs(purpose,waitForDoorMs),
                TimeUnit.MILLISECONDS
            )
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            lastReconciliationLockWaitFailure = "Interrupted while waiting for the account-wide reconciliation lock"
            false
        }
        lastReconciliationLockWaitMs = (System.currentTimeMillis() - waitStartedAtMs).coerceAtLeast(0L)
        if (!lockAcquired && deferrable &&
            !Thread.currentThread().isInterrupted && futuresReconciliationRunning) {
            // The owner continues to hold the Execution Door. No new entry is
            // enabled here; the periodic monitor rechecks on the next tick.
            return JSONObject().put("ok", false)
                .put("deferred_to_active_reconciliation", true)
                .put("blocker", "exchange_reconciliation_in_progress")
        }
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
            // The reconciliation Door has been released. Each verified safety
            // exit gets its own durable Door and stable id; the original blocked
            // result remains blocked until a subsequent fresh snapshot is safe.
            val requiredExits = result.optJSONArray("required_safety_exits") ?: JSONArray()
            val safetyExitResults = JSONArray()
            for (index in 0 until requiredExits.length()) {
                val request = requiredExits.getJSONObject(index)
                val outcome = runCatching {
                    val unprotected = request.optString("reason") == "UNPROTECTED_POSITION_GUARD"
                    withCampaignMutation(symbol = request.getString("symbol"),
                        purpose = if(unprotected) "UNPROTECTED_POSITION_GUARD_EXIT" else "LIQUIDATION_GUARD_EXIT",
                        allowPaused = true, allowStopped = true, allowKillLatched = true,
                        allowReconcileRequired = true, waitForDoorMs = waitForDoorMs
                    ) {
                        if(unprotected) futuresCampaignCoordinator.executeRequiredUnprotectedSafetyExit(request)
                        else futuresCampaignCoordinator.executeRequiredSafetyExit(request)
                    }
                }.getOrElse { JSONObject().put("ok", false).put("blocker", it.message ?: it.javaClass.simpleName) }
                safetyExitResults.put(JSONObject().put("symbol", request.optString("symbol")).put("result", outcome))
            }
            if (safetyExitResults.length() > 0) result.put("safety_exit_results", safetyExitResults)
            // The account Door is released, but this reconciliation still owns
            // futuresReconciliationLock. Run approved stop-only maintenance here:
            // a periodic management tick can be coalesced behind a continuous
            // private-stream reconciliation and never reach its failure branch.
            // Neither the reconciliation result nor entry authority is relaxed.
            runOperatorStopSupervisionIfDue()
            // Diagnostics 24: maintenance reconciliation repeats every ~6-7s.
            // Clearing the read-only signed position cache on EVERY result
            // (including blocked/unowned-stop results) made the Active screen
            // alternate between real positions and an empty loading state.
            // Keep last-known rows for display, including during failed/ongoing
            // REST refresh. The fast-status path marks them UNVERIFIED as soon
            // as their 15s signed-snapshot freshness window expires. It also
            // schedules another signed refresh. This display cache is NEVER an
            // execution admission, stop-proof, ownership or FLAT confirmation.
            // Explicit credential reset and acknowledged stop replacement still
            // clear or invalidate the cache through their own separate paths.
            if (result.optBoolean("ok", false)) {
                lastRestReconcileMs = System.currentTimeMillis()
                lastReconciliationResult = "SUCCESS"
            } else {
                lastReconciliationResult = "BLOCKED: " +
                    result.optString("blocker", result.optString("error", "unknown"))
            }
            runCatching { auditStore.recordReconciliationObservation(
                purpose, result.optString("state"), result.optBoolean("ok"),
                result.optString("blocker")) }
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
        // TC2's first still-valid presenting Wise Man. Consumed levels have
        // already been removed by their causal trigger checks.
        candidate.campaignSignals.filter { williamsVerifiedNewRiskSource(it.strategyProfile) &&
            williamsProfileAllowsSource(williamsProfile,it.strategyProfile) && it.role=="ENTRY" }.minByOrNull { it.signalBarTimeMs }

    // Legacy Spot-era campaign submission/management/recovery implementation removed.
    // Entry, trailing protection, and exits are owned by NativeFuturesCampaignCoordinator.

    /**
     * At most one preflight at a time, one attempt per minute while unverified.
     * The existing daily-loss function remains the authoritative fail-closed gate.
     */
    private fun scheduleDailyRiskBaselinePreflight() {
        val now = System.currentTimeMillis()
        synchronized(dailyRiskPreflightLock) {
            if (dailyRiskPreflightRunning ||
                (lastDailyRiskPreflightAtMs > 0L && now - lastDailyRiskPreflightAtMs < 60_000L)
            ) return
            dailyRiskPreflightRunning = true
            lastDailyRiskPreflightAtMs = now
        }
        Thread {
            try {
                runCatching { checkFuturesDailyLoss() }.onFailure { failure ->
                    lastError = "FUTURES_DAILY_RISK_PREFLIGHT_FAILED: " +
                        (failure.message ?: failure.javaClass.simpleName) +
                        "; new Futures entries remain blocked"
                }
            } finally {
                synchronized(dailyRiskPreflightLock) { dailyRiskPreflightRunning = false }
            }
        }.apply {
            isDaemon = true
            name = "williams-futures-daily-risk-preflight"
            start()
        }
    }

    private fun checkFuturesDailyLoss(): Boolean =
        synchronized(dailyRiskCheckLock) { checkFuturesDailyLossUnderLock() }

    private fun checkFuturesDailyLossUnderLock(): Boolean {
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
                // Resting, freshly reconciled ZERO-fill entries are not exposure.
                // Unknown, partial, triggering and stale intents remain blockers.
                val hasActiveExecution = !unfilledPendingIntentsAllowFlatDailyBaseline(
                    intents, exchangeNowMs
                )
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
                // Distinguish an automatic, recoverable daily-baseline pause
                // from the operator's explicit pause. Never erase either risk
                // evidence or the original 3% daily loss limit.
                check(prefs.edit().putBoolean("risk_daily_baseline_auto_paused", true).commit()) {
                    "Could not durably pause trades while the UTC baseline is unknown"
                }
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
            prefs.edit().putBoolean("risk_daily_baseline_auto_paused", true).commit()
            lastError = "FUTURES_DAILY_INCOME_UNKNOWN: cannot verify the UTC-day PnL ledger: " +
                (failure.message ?: failure.javaClass.simpleName)
            return false
        }
        val dailyIncome = summarizeFuturesDailyIncome(dailyIncomeRows) ?: run {
            paused = true
            prefs.edit().putBoolean("risk_daily_baseline_auto_paused", true).commit()
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
            prefs.edit().putBoolean("risk_daily_baseline_auto_paused", true).commit()
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
            prefs.edit().putBoolean("risk_daily_baseline_auto_paused", true).commit()
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
            // A daily entry limit must expire with its UTC session. Do not turn
            // it into the operator's indefinite pause or disable runtime auto-run.
            if(!executionStore.writeState("futures_daily_entry_halt_utc_day_start_ms",utcDayStartMs.toString())) {
                paused=true
                prefs.edit().putBoolean("auto_run",false).commit()
                lastError="FUTURES_DAILY_ENTRY_HALT_PERSIST_FAILED: entries paused; SQLite write failed"
                return false
            }
        }
        val halt=dailyEntryHalt(exchangeNowMs,true)
        if(!halt.allowNewEntries) {
            lastError = "FUTURES_DAILY_LOSS_LIMIT: 3% reached; new entries blocked"
            if(!halt.available) lastError=halt.reason
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
        if(lastError?.startsWith("FUTURES_DAILY_LOSS_LIMIT:")==true) lastError=null
        // UTC midnight clears the *loss streak*, not financial drawdown or
        // unresolved orders. A previously automatic UNKNOWN-baseline pause may
        // be released ONLY once the current UTC baseline, daily PnL and
        // exchange account have all passed this exact verification.
        val recoverablePause = prefs.getBoolean("risk_daily_baseline_auto_paused", false) ||
            (lastError?.startsWith("FUTURES_DAILY_BASELINE_UNKNOWN") == true)
        if (canReleaseVerifiedUtcRiskPause(
                baselineAndPnlVerified=true,
                wasRiskAutoPaused=recoverablePause,
                runtimeRunning=running,
                operatorPaused=prefs.getBoolean("operator_entries_paused", false),
                killLatched=killLatched,
                reconcileRequired=reconcileRequired,
                testnetOverrideActive=forceTestnetMode
            )) {
            // Other gates (coordinator, pending orders, WSS, fresh market, FSM)
            // remain authoritative; removing this PAUSE is not an entry permit.
            if (prefs.edit().putBoolean("risk_daily_baseline_auto_paused", false).commit()) {
                paused = false
                if (lastError?.startsWith("FUTURES_DAILY_BASELINE_UNKNOWN") == true)
                    lastError = null
            }
        }
        return true
    }

    private fun dailyRiskBaselineSource(fast: Boolean = false): String = runCatching {
        if (fast && !futuresMarketData.clockSnapshot().optBoolean("fresh")) return@runCatching "UNKNOWN"
        val now = if (fast) futuresMarketData.cachedExchangeNowMs() else futuresMarketData.exchangeNowMs()
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
    private fun fastFuturesPositionsSnapshot(): Triple<JSONArray, String?, Long> {
        val nowMs = System.currentTimeMillis()
        val envelope = cachedFuturesPositionEnvelope
        val cachedAt = envelope?.observedAtMs ?: 0L
        if (envelope != null && CockpitDiagnostics.fresh(cachedAt, nowMs, 15_000L)) {
            return Triple(JSONArray(envelope.positionsJson), futuresPositionSnapshotRefreshError, cachedAt)
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
            val generationAtStart = runtimeGeneration
            val apiKeyAtStart = key()
            val epochAtStart = futuresPositionSnapshotEpoch
            Thread {
                try {
                    futuresPositionSnapshot()
                    futuresPositionSnapshotRefreshError = null
                } catch (failure: Exception) {
                    if (generationAtStart != runtimeGeneration || apiKeyAtStart != key() ||
                        epochAtStart != futuresPositionSnapshotEpoch) return@Thread
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

        // In-flight refresh is not a verified FLAT. A previously signed row
        // remains visible as LAST KNOWN, but snapshot_ok stays false.
        return lastKnownFuturesPositionsForDisplay(
            cachedFuturesPositionEnvelope?.let { it.positionsJson to it.observedAtMs },
            futuresPositionSnapshotRefreshError
        )
    }

    private fun futuresPositionSnapshot(): JSONArray =
        JSONArray(futuresPositionDisplaySnapshot().positionsJson)

    private fun futuresPositionDisplaySnapshot(): FuturesPositionDisplayEnvelope {
        val snapshotEpoch = futuresPositionSnapshotEpoch
        val generationAtStart = runtimeGeneration
        val apiKeyAtStart = key()
        val out = JSONArray()
        if (key().isBlank() || secret().isBlank()) return FuturesPositionDisplayEnvelope("[]",0L,"[]")
        val signedSnapshot = futuresCampaignCoordinator.protectionDisplaySnapshot()
        val positions = signedSnapshot.positions
        val intents = runCatching { futuresCampaignCoordinator.intentsSnapshot() }.getOrDefault(JSONArray())
        // The persisted campaign STOP is only a planned value. A refreshed
        // signed Binance protection observation is separate and authoritative
        // even when a 4.52 upgrade lost the original local campaign journal.
        val observedStops = signedSnapshot.observations
        val snapshotAt = System.currentTimeMillis()
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
            val exchangeStop=(0 until observedStops.length()).mapNotNull {
                observedStops.optJSONObject(it)
            }.singleOrNull { proof ->
                val observedAt=proof.optLong("observed_at_ms")
                val signedProof=proof.optString("quantity").toBigDecimalOrNull()
                val signedLive=row.optString("positionAmt").toBigDecimalOrNull()
                proof.optString("symbol")==symbol && proof.optString("direction")==direction &&
                    signedProof!=null && signedLive!=null && signedProof.compareTo(signedLive)==0 &&
                    CockpitDiagnostics.fresh(observedAt,snapshotAt,CockpitDiagnostics.POSITION_TTL_MS) &&
                    proof.optString("stop_proof") in setOf("OWNED_STOP_SEEN","EXTERNAL_PROTECTED",
                        "NATIVE_REPLACEMENT_PROTECTION_SEEN")
            }
            // Do not mistake old journal stop level for an exchange-confirmed
            // working protective order.
            val stopProof=exchangeStop?.optString("stop_proof") ?: "UNVERIFIED_OR_STALE"
            val stop=exchangeStop?.optString("stop")?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it>0.0 } ?: JSONObject.NULL
            out.put(JSONObject()
                .put("symbol", symbol).put("direction", direction).put("side", direction)
                .put("positionAmt", signedQty).put("qty", qty).put("entry", entry).put("entryPrice", entry)
                .put("mark_price", mark).put("stop", stop)
                .put("stop_verification_status",stopProof)
                .put("stop_confirmed_at_ms",exchangeStop?.optLong("observed_at_ms") ?: JSONObject.NULL)
                // An explicitly operator-approved stop-only supervisor is a
                // separate, limited authority from strategy campaign ownership.
                // It can trail an exchange-verified STOP without adding risk.
                .put("stop_can_be_auto_trailed",williamsObservedStopCanTrail(exchangeStop))
                .put("stop_management_mode",exchangeStop?.optString("management_mode") ?: "UNVERIFIED")
                .put("strategy_ownership_verified",exchangeStop?.optBoolean(
                    "strategy_ownership_verified",false) ?: false)
                // An unowned / operator-supervised position has no trusted
                // initial campaign risk. Null is UNKNOWN, never 0% risk.
                .put("risk_pct", if(intent?.optBoolean("strategy_ownership_verified",false)==true)
                    intent.optDouble("risk_pct", Double.NaN).takeIf { it.isFinite() && it>0 }
                        ?: JSONObject.NULL
                    else JSONObject.NULL)
                .put("unrealized_pnl_usdt", pnl).put("margin_type", row.optString("marginType"))
                .put("leverage", row.optString("leverage")).put("exchange_confirmed", true))
        }
        check(CockpitDiagnostics.fresh(signedSnapshot.observedAtMs,System.currentTimeMillis(),
            CockpitDiagnostics.POSITION_TTL_MS)) { "Signed Futures display snapshot expired during normalization" }
        val envelope = FuturesPositionDisplayEnvelope(
            out.toString(), signedSnapshot.observedAtMs, observedStops.toString())
        synchronized(this) {
            check(generationAtStart == runtimeGeneration && apiKeyAtStart == key()) {
                "Credentials changed during signed Futures display refresh"
            }
            check(futuresPositionSnapshotEpoch == snapshotEpoch) {
                "Futures STOP changed during display refresh; newer exchange evidence is required"
            }
            if ((cachedFuturesPositionEnvelope?.observedAtMs ?: 0L) <= envelope.observedAtMs)
                cachedFuturesPositionEnvelope = envelope
            futuresPositionSnapshotRefreshError = null
        }
        return envelope
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
                futuresCampaignCoordinator.recordVerifiedCampaignResult(clientId,summary.netPnlUsdt,summary.feeKnown,summary.fillCount)
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
        if (reconciliation.optBoolean("deferred_to_active_reconciliation", false)) {
            // Coalesced behind an authoritative in-flight signed reconciliation:
            // do NOT create a false 50-second lock timeout or latch. The owner
            // still fences all new entries until its exchange proof is complete.
            return true
        }
        if (!reconciliation.optBoolean("ok", false)) {
            scannerError = reconciliation.optString("blocker", "Futures reconciliation failed")
            // Fence all new entries BEFORE deciding whether a zero-fill pending
            // order can be canceled to restore the exact 0.60% portfolio ceiling.
            reconcileRequired = true
            persistSafetyFlag("reconcile_required", true)
            stateMachine.force(TradingState.RECONCILE_REQUIRED, scannerError ?: "Futures reconciliation failed")
            val riskManagementNowMs = System.currentTimeMillis()
            val riskManagementLastMs = lastProtectedRiskCapManagementAtMs
            if (reconciliation.optBoolean("protected_over_cap_management_allowed", false) &&
                (riskManagementLastMs <= 0L || riskManagementNowMs - riskManagementLastMs >= 30_000L)
            ) {
                // Limit repeated signed Futures requests while a hard risk cap
                // is exceeded; preserve 24/7 exits and confirmed stop protection.
                lastProtectedRiskCapManagementAtMs = riskManagementNowMs
                // A failed risk-cap reconciliation is NOT a trading authorization.
                // Retain the durable latch and permit only confirmed risk-reducing
                // exits / stop improvements. New entries and add-ons remain forbidden.
                val riskManagement = runCatching {
                    manageVerifiedNativePositions(
                        futuresCampaignCoordinator.positionsSnapshot(),
                        futuresCampaignCoordinator.intentsSnapshot(),
                        riskOnly = true
                    )
                }
                riskManagement.onFailure { failure ->
                    lastError = "PROTECTED_RISK_CAP_MANAGEMENT_FAILED: " +
                        (failure.message ?: failure.javaClass.simpleName)
                    runCatching { auditStore.recordCockpitEvent(
                        "RISK_CAP_MANAGEMENT", state = "BLOCKED",
                        detail = lastError.orEmpty()
                    ) }
                }
            }
            // An unrelated orphan/manual algo must not freeze stop tightening
            // of a DIFFERENT fully identified live TC2 campaign. Coordinator
            // grants a 30-second, per-symbol, non-exposure risk-only scope,
            // verified by BOTH durable fill ownership and live protective stop.
            val isolated=reconciliation.optJSONArray("verified_external_stop_management_symbols")
                ?: JSONArray()
            if(isolated.length()>0) {
                val now=System.currentTimeMillis()
                if(lastExternalStopManagementAtMs<=0L ||
                    now-lastExternalStopManagementAtMs>=10_000L) {
                    lastExternalStopManagementAtMs=now
                    val authorized=(0 until isolated.length()).map {
                        isolated.optString(it)
                    }.filter { it.isNotBlank() }.toSet()
                    runCatching {
                        manageVerifiedNativePositions(
                            futuresCampaignCoordinator.positionsSnapshot(),
                            futuresCampaignCoordinator.intentsSnapshot(),
                            riskOnly=true,eligibleSymbols=authorized
                        )
                    }.onFailure { error ->
                        runCatching { auditStore.recordCockpitEvent(
                            "EXTERNAL_ORDER_ISOLATED_TRAIL",state="BLOCKED",
                            detail=error.message ?: error.javaClass.simpleName
                        ) }
                        lastError="OWNED_STOP_TRAILING_UNAVAILABLE: "+
                            (error.message ?: error.javaClass.simpleName)
                    }
                }
            }
            if (scannerError?.startsWith("ACTUAL_FUTURES_RISK_EXCEEDS_PORTFOLIO_CAP:")==true) {
                val candidate=runCatching {
                    proposePendingRiskRemediation(
                        scannerError.orEmpty(),
                        futuresCampaignCoordinator.positionsSnapshot(),
                        futuresCampaignCoordinator.intentsSnapshot()
                    )
                }.getOrNull()
                if(candidate!=null) {
                    val cancel=runCatching {
                        withCampaignMutation(
                            symbol=candidate.symbol,
                            purpose="ACTUAL_RISK_OVER_CAP_CANCEL_UNFILLED_PENDING",
                            allowPaused=true, allowStopped=true, allowKillLatched=true,
                            allowReconcileRequired=true
                        ) {
                            // The coordinator verifies the exact exchange order status,
                            // refreshes positions after cancellation and persists the
                            // terminal status. UNKNOWN or partial fills stay latched.
                            futuresCampaignCoordinator.cancelPendingEntries(
                                "ACTUAL_RISK_PORTFOLIO_CAP_RECOVERY",candidate.symbol)
                        }
                    }.getOrNull()
                    if(cancel?.optBoolean("ok")==true &&
                        !cancel.optBoolean("positions_require_reconciliation",true)) {
                        runCatching { auditStore.recordCockpitEvent("RISK_REMEDIATION",
                            candidate.symbol,state="PENDING_CANCEL_CONFIRMED",
                            detail="actual open risk=${candidate.measuredOpenRiskPct}; " +
                                "risk including pending=${candidate.totalWithPendingRiskPct}; " +
                                "Binance reconciliation still required; no new order") }
                    } else {
                        lastError="RISK_REMEDIATION_UNRESOLVED: pending entry cancellation " +
                            "not exchange-confirmed; do not bypass reconciliation"
                    }
                    // Even successful cancellation MUST NOT enable new entries
                    // until the next authoritative full Binance reconciliation.
                }
            }
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
        // Accepted conditional entries have a bounded H1 life. A correction
        // additionally needs its original parent to remain valid before fill.
        for (i in 0 until intents.length()) {
            val pending=intents.optJSONObject(i) ?: continue
            if (pending.optString("intent_role","ENTRY") !in setOf("ENTRY","ADD_ON") || campaignLifecycleStatus(pending)!="ENTRY_PENDING") continue
            val symbol=pending.optString("symbol")
            val signalAt=pending.optLong("signal_bar_time_ms",0L)
            val expires=pending.optLong("signal_expires_at_ms",if (signalAt>0) signalAt+18_000_000L else 0L)
            val expired=expires>0 && futuresMarketData.exchangeNowMs()>=expires
            var invalidCorrection=false
            var invalidEdition=williamsNewExposureBlocker(pending.optString("strategy_profile",williamsTc2Profile))!=null
            val profile=pending.optString("strategy_profile",williamsTc2Profile)
            // Re-anchor a still-unfilled conditional TC2 entry only after a newer
            // eligible WM3 was confirmed on closed H1 bars. Never reprice an order
            // directly on Binance; cancel its exact client id first.
            val exchangeRowsValid = (0 until positions.length()).all { index ->
                val row=positions.optJSONObject(index) ?: return@all false
                val qty=row.optString("positionAmt").toDoubleOrNull()
                qty!=null && qty.isFinite() && (row.optString("symbol")!=symbol || qty==0.0)
            }
            if (expiredTriggeringEntryNeedsExchangeCancel(
                    expired=expired,
                    orderStatus=exchangeOrderStatus(pending),
                    accountPositionKnownFlat=exchangeRowsValid,
                    explicitOperatorOrder=pending.optString("signal_type")=="OPERATOR_FORCED_DIRECTION"
                )) {
                // Earlier book/M15 branches did 'continue' on TRIGGERING,
                // leaving day-old expired orders blocking every new entry.
                // This is an informed exchange cancellation attempt, never a
                // local pretend reset of entry/fill or of the UTC risk baseline.
                val result=withCampaignMutation(
                    symbol,"EXPIRED_H1_TRIGGERING_CANCEL_RECONCILE",
                    allowPaused=true
                ) {
                    try {
                        futuresCampaignCoordinator.cancelPendingEntries(
                            "H1_SIGNAL_EXPIRED",symbol)
                    } finally {
                        reconcileRequired=true
                        check(persistSafetyFlag("reconcile_required",true)) {
                            "EXPIRED_H1_TRIGGERING_CANCEL_RECONCILIATION_FENCE_FAILED"
                        }
                    }
                }
                if (!result.optBoolean("ok",false))
                    lastError="EXPIRED_H1_TRIGGERING_CANCEL_NOT_CONFIRMED: "+
                        symbol+": "+result.optString("blocker")
                return false // Force fresh exchange position/fill/stop reconciliation.
            }
            if(pending.optString("intent_role","ENTRY")=="ENTRY" && exchangeRowsValid &&
                pending.optString("client_id").startsWith("W2E_") && pending.optString("signal_type")!="OPERATOR_FORCED_DIRECTION") {
                if(exchangeOrderStatus(pending) in setOf("TRIGGERING","TRIGGERED")) continue
                if(manageM15PendingEntry(pending)) return false
                continue // H1-only re-anchoring must never replace a junior-confirmed order.
            }
            // A book-H1 conditional entry is rechecked while waiting, because
            // Binance executes conditional stops asynchronously. In particular,
            // the WM3 fractal must still break on the correct side of Teeth.
            val bookAddition=pending.optString("intent_role")=="ADD_ON" &&
                williamsVerifiedNewRiskSource(profile) && pending.optString("entry_client_id").isNotBlank() &&
                pending.optString("client_id").startsWith("W2A_")
            if(((pending.optString("intent_role","ENTRY")=="ENTRY" && exchangeRowsValid &&
                    pending.optString("client_id").startsWith("W2H_")) || bookAddition) && !invalidEdition &&
                pending.optString("signal_type")!="OPERATOR_FORCED_DIRECTION") {
                if(exchangeOrderStatus(pending) in setOf("TRIGGERING","TRIGGERED")) continue
                val bookInvalidation=runCatching {
                    val now=futuresMarketData.exchangeNowMs()
                    val tick=symbolFilters(symbol).tick
                    val setup=runCatching { JSONObject(pending.optString("setup_context")) }.getOrDefault(JSONObject())
                    val parentId=pending.optString("signal_id")
                    val price=pending.optString("entry_price").toDoubleOrNull() ?: Double.NaN
                    val stop=pending.optString("protective_stop").toDoubleOrNull() ?: Double.NaN
                    val proofError=williamsBookH1ProofBlocker(setup.toString(),
                        pending.optString("direction"),parentId,pending.optString("signal_type"),
                        pending.optString("strategy_profile",williamsTc2Profile),price,stop,now,
                        pending.optString("entry_client_id"),pending.optString("reentry_owner_id"),
                        pending.optString("execution_semantics","STOP_ENTRY"),pending.optDouble("reference_close",0.0))
                    if(proofError!=null) proofError
                    else {
                        val h1=fetchCandles(symbol,"1h",220)
                        val valid=h1ContextCampaignSignals(symbol,h1,tick).any {
                            it.signalId==parentId && it.type==pending.optString("signal_type") &&
                            it.strategyProfile==profile && williamsBookSignalRoleAllowed(profile,it.type,it.role,
                                pending.optString("entry_client_id"),pending.optString("reentry_owner_id"))
                        }
                        if(!valid) h1ParentRejectionReason(symbol,parentId,"H1_BOOK_PARENT_INVALIDATED")
                        else {
                            val closed=h1.filter { it.t>0 && it.t+WILLIAMS_H1_MS<=now }
                            val waveBars=closed.map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) }
                            // TC2 ch11 fig 11.7: an unfilled STOP can become a
                            // late, unsuitable fractal as H1 enters congestion.
                            // Revoke only clearly unfilled exchange-acknowledged
                            // NEW/WORKING entries, not TRIGGERING, PARTIALLY_FILLED,
                            // unknown, filled positions or protective STOPs.
                            val correctionInvalidation=if(profile==williamsTc2Profile &&
                                williamsTc2PendingCongestionRecheckEligible(
                                    pending.optString("intent_role","ENTRY"),
                                    exchangeOrderStatus(pending),pending.optString("client_id"),
                                    knownFill=pending.optLong("fill_observed_at_ms")>0L ||
                                        listOf("executed_qty","actual_entry_executed_qty",
                                            "confirmed_position_qty").any { key ->
                                            pending.has(key) &&
                                                pending.optString(key).toBigDecimalOrNull()?.signum()!=0
                                        }))
                                williamsTc2CongestionEntryBlocker(profile,
                                    pending.optString("signal_type"),pending.optString("direction"),
                                    price,waveBars,now)
                            else null
                            if(correctionInvalidation!=null) correctionInvalidation
                            else {
                                val teeth=williamsBookH1ActivationTeeth(waveBars,now)
                                williamsBookFractalTeethBlocker(pending.optString("signal_type"),
                                    pending.optString("direction"),price,teeth,tick)
                            }
                        }
                    }
                }.getOrElse { failure ->
                    lastError="H1_BOOK_PENDING_REVALIDATION_UNAVAILABLE: "+symbol+": "+(failure.message ?: failure.javaClass.simpleName)
                    null // Never cancel an order on an uncertain network/market snapshot.
                }
                if(bookInvalidation!=null) {
                    val result=withCampaignMutation(symbol,"H1_BOOK_PENDING_INVALIDATED_CANCEL",allowPaused=true) {
                        try { futuresCampaignCoordinator.cancelPendingEntries(bookInvalidation,symbol) }
                        finally {
                            reconcileRequired=true
                            check(persistSafetyFlag("reconcile_required",true)) {
                                "H1_BOOK_CANCEL_RECONCILIATION_FENCE_FAILED"
                            }
                        }
                    }
                    if(!result.optBoolean("ok",false))
                        lastError="H1_BOOK_PENDING_CANCEL_UNRESOLVED: "+symbol+": "+result.optString("blocker")
                    return false // Reconcile fills and position ownership before further entry.
                }
            }
            if (profile==williamsTc2Profile && pending.optString("intent_role","ENTRY")=="ENTRY" &&
                exchangeRowsValid) {
                // If H1 refresh fails, do not interrupt the protective-stop
                // management of other already-open Futures positions.
                val next=runCatching {
                    val now=futuresMarketData.exchangeNowMs()
                    val tick=symbolFilters(symbol).tick
                    val bars=fetchCandles(symbol,"1h",220)
                        .filter { it.t>0L && it.t+3_600_000L<=now }
                    val newFractals=editionCampaignSignals(symbol,bars,"1h",tick,williamsTc2Profile)
                    val proposal=confirmedBetterFractal(pending,JSONArray().apply {
                        newFractals.forEach { signal ->
                            put(JSONObject().put("signal_id",signal.signalId).put("type",signal.type)
                                .put("role",signal.role).put("strategy_profile",signal.strategyProfile)
                                .put("execution_semantics",signal.executionSemantics)
                                .put("signal_bar_time_ms",signal.signalBarTimeMs)
                                .put("formation_at_ms",signal.formationAtMs)
                                .put("trigger_price",signal.triggerPrice)
                                .put("protective_price",signal.protectivePrice))
                        }
                    },futuresMarketData.price(symbol),tick,now)
                    proposal?.let { it to now }
                }.getOrNull()
                if(next!=null) {
                    val (replacement,now)=next
                    val oldClientId=pending.optString("client_id")
                    val cancel=withCampaignMutation(symbol,"TC2_FRACTAL_REANCHOR_CANCEL",allowPaused=true) {
                        // Fence concurrent scanners BEFORE releasing the durable
                        // Mutation Door. A freshly canceled old order must not
                        // be immediately resubmitted between DELETE and journaling.
                        try {
                            futuresCampaignCoordinator.cancelPendingEntries("NEWER_CONFIRMED_TC2_FRACTAL",symbol)
                        } finally {
                            reconcileRequired=true
                            check(persistSafetyFlag("reconcile_required",true)) {
                                "Cannot persist reconciliation fence for fractal cancel"
                            }
                        }
                    }
                    // Require an exact terminal old order, fresh flat exchange
                    // position, and no unresolved cancellation or partial fill.
                    val after=futuresCampaignCoordinator.intentsSnapshot()
                    val prior=(0 until after.length()).mapNotNull { after.optJSONObject(it) }
                        .singleOrNull { it.optString("client_id")==oldClientId }
                    val cancelled=cancel.optBoolean("ok") &&
                        !cancel.optBoolean("positions_require_reconciliation",true) &&
                        prior!=null &&
                        exchangeOrderStatus(prior) in ENTRY_ORDER_TERMINAL_STATUSES &&
                        campaignLifecycleStatus(prior)=="CLOSED_OR_STOPPED"
                    reconcileRequired=true
                    check(persistSafetyFlag("reconcile_required",true)) {
                        "Failed to persist reconciliation latch after fractal cancellation"
                    }
                    if(!cancelled) {
                        lastError="FRACTAL_REANCHOR_CANCEL_UNRESOLVED: $symbol/$oldClientId"
                        return false
                    }
                    val lock=JSONObject(replacement.toString()).put("symbol",symbol)
                        .put("direction",pending.optString("direction"))
                        .put("recorded_at_ms",now).put("old_client_id",oldClientId)
                    check(executionStore.writeState("tc2_fractal_reanchor_$symbol",lock.toString())) {
                        "Cannot durably record target fractal after cancel; no replacement order"
                    }
                    runCatching { auditStore.recordCockpitEvent("FRACTAL_REANCHOR",symbol,
                        pending.optString("direction"),"CANCEL_CONFIRMED",
                        "old=$oldClientId; new_signal="+replacement.optString("signal_id")+
                            "; from="+pending.optString("entry_price")+
                            "; to="+replacement.optDouble("trigger_price")+
                            "; fresh full risk sizing after next exchange reconciliation") }
                    return false // No second mutation before fresh position reconciliation.
                }
            }
            if(profile!=williamsTc2Profile && !invalidEdition && pending.optString("kind")!="MARKET") {
                val current=editionCampaignSignals(symbol,fetchCandles(symbol,"1h",220),"1h",symbolFilters(symbol).tick,profile)
                val expected=pending.optString("entry_price").toDoubleOrNull()
                invalidEdition=current.none { it.type==pending.optString("signal_type") &&
                    expected!=null && abs(it.triggerPrice-expected)<=symbolFilters(symbol).tick*.1 }
            }
            if (pending.optString("trade_mode")=="CORRECTION") {
                val prior=runCatching { JSONObject(pending.optString("wave_context_at_entry")) }.getOrNull()
                val current=williamsCorrectionEntryContext(waveHierarchyFor(symbol),pending.optString("direction"),
                    signalAt,pending.optString("signal_type"))
                invalidCorrection=!current.allowed || current.parentId!=prior?.optString("parent_scenario_id") ||
                    !williamsCorrectionPriceAllowed(current.toJson(),futuresMarketData.markPrice(symbol))
            }
            if (expired || invalidCorrection || invalidEdition) {
                val reason=if(williamsNewExposureBlocker(profile)!=null) "WILLIAMS_SOURCE_OBSERVATION_ONLY" else if (expired) "H1_SIGNAL_EXPIRED" else if(invalidEdition) "EDITION_FORMATION_INVALIDATED" else "CORRECTION_PARENT_INVALIDATED"
                val result=withCampaignMutation(symbol,"WILLIAMS_INVALIDATED_ENTRY_CANCEL",allowPaused=true) {
                    futuresCampaignCoordinator.cancelPendingEntries(reason,symbol)
                }
                if (!result.optBoolean("ok",false)) throw IllegalStateException(result.optString("blocker"))
                reconcileRequired=true
                persistSafetyFlag("reconcile_required",true)
                return false // force a fresh position/intent snapshot after a possible racing fill
            }
        }
        manageVerifiedNativePositions(positions, intents, riskOnly = false)
        if(processNativeTc2Reversals()) return false
        return true
    }

    /** Resume a durable opposite-Wise-Man handoff after close/restart. All
     * normal runtime, session, daily, portfolio and exchange risk doors apply. */
    private fun processNativeTc2Reversals():Boolean {
        if(forceTestnetMode || !executionReady() || !williamsProfileAllowsSource(williamsProfile,williamsTc2Profile)) return false
        val now=futuresMarketData.exchangeNowMs()
        if(!entryTimePolicy.allows(now)) return false
        val ledger=futuresCampaignCoordinator.intentsSnapshot()
        for(i in 0 until ledger.length()) {
            val owner=ledger.optJSONObject(i) ?: continue
            if(owner.optString("intent_role","ENTRY")!="ENTRY") continue
            val c=williamsTc2ReversalCandidate(ledger,owner.optString("client_id"),now) ?: continue
            val symbol=c.getString("symbol")
            try {
                if(!checkFuturesDailyLoss() || !executionReady()) return false
                val positions=futuresCampaignCoordinator.positionsSnapshot()
                val openSymbols=(0 until positions.length()).map { index ->
                    val p=positions.optJSONObject(index) ?: error("TC2_REVERSAL_POSITION_SNAPSHOT_MALFORMED")
                    val qty=p.optString("positionAmt").toBigDecimalOrNull() ?: error("TC2_REVERSAL_POSITION_QUANTITY_UNKNOWN")
                    p.optString("symbol").takeIf { qty.signum()!=0 }
                }.filterNotNull()
                val slot=futuresPositionSlotBlocker(symbol,openSymbols,ledger,maxOpenPositions)
                if(slot!=null) { rejectDecision(symbol,slot);continue }
                val side=c.getString("direction")
                val direction=NativeFuturesCampaignCoordinator.Direction.valueOf(side)
                val rules=symbolFilters(symbol)
                val trigger=c.getDouble("trigger")
                // Budget at the adverse end of the permitted trigger deviation,
                // rather than reusing the old campaign's risk or quantity.
                val entry=if(side=="LONG") normalizePrice(trigger*(1+maxSlippagePct),rules.tick)
                    else normalizeTriggerPrice(trigger*(1-maxSlippagePct),rules.tick)
                val stop=if(side=="LONG") normalizePrice(c.getDouble("protective_stop"),rules.tick)
                    else normalizeTriggerPrice(c.getDouble("protective_stop"),rules.tick)
                val contract=futuresMarketData.price(symbol)
                val mark=futuresMarketData.markPrice(symbol)
                val (bid,ask)=futuresMarketData.topOfBook(symbol)
                if((ask-bid)/bid !in 0.0..maxSpreadPct ||
                    (if(side=="LONG") ask>entry else bid<entry)) {
                    rejectDecision(symbol,"TC2_REVERSAL_EXECUTABLE_QUOTE_OUTSIDE_RISK_BOUND");continue
                }
                val blocker=williamsTc2ReversalEntryBlocker(ledger,c.getString("owner_id"),symbol,side,
                    c.getString("signal_id"),c.getString("signal_type"),c.getLong("signal_bar_time_ms"),
                    c.getString("client_id"),entry,stop,now,contract,mark,rules.tick)
                if(blocker!=null) { rejectDecision(symbol,blocker);continue }
                val account=parseFuturesAccountSnapshot(futuresCampaignCoordinator.accountSnapshot())
                    ?: error("TC2_REVERSAL_ACCOUNT_UNVERIFIED")
                check(futuresAccountFundedForEntry(account.availableUsdt,account.equityUsdt)) { "TC2_REVERSAL_FUNDS_UNVERIFIED" }
                val riskUsed=(0 until ledger.length()).mapNotNull { ledger.optJSONObject(it) }.sumOf {
                    if(it.optString("intent_role","ENTRY")=="ENTRY" && isEntryCampaignReserved(it))
                        conservativeRiskReservation(futuresCampaignReservedRisk(it,ledger),maxTotalRiskPct) else 0.0
                }
                val cap=williamsTc2InitialUnitRisk(campaignInitialRiskPct,campaignRiskLimitPct,maxRiskPerTradePct)
                val riskPct=min(cap,min(maxRiskPerTradePct,maxTotalRiskPct-riskUsed))
                check(riskPct>0) { "TC2_REVERSAL_PORTFOLIO_RISK_EXHAUSTED" }
                val leverage=futuresLeverageSettings.forSymbol(symbol)
                val loss=abs(entry-stop)/entry+2*feeBufferPerSidePct+maxSlippagePct
                val notional=min(account.equityUsdt*.25,min(account.availableUsdt*leverage*.95,account.equityUsdt*riskPct/loss))
                val step=ExecutionMath.commonQuantityStep(rules.step,rules.marketStep)
                val qty=floorStep(notional/entry,step)
                val marketLot=rules.marketStep.isFinite() && rules.marketStep>0
                check(qty.isFinite() && qty>=maxOf(rules.minQty,if(marketLot) rules.marketMinQty else rules.minQty) &&
                    qty<=minOf(rules.maxQty,if(marketLot) rules.marketMaxQty else rules.maxQty) &&
                    ExecutionMath.isMultiple(qty,rules.step) && (!marketLot || ExecutionMath.isMultiple(qty,rules.marketStep)) &&
                    qty*entry>=rules.minNotional && qty*entry<=rules.maxNotional) { "TC2_REVERSAL_QUANTITY_FILTER_BLOCKED" }
                val plan=NativeFuturesCampaignCoordinator.EntryPlan(symbol,direction,
                    NativeFuturesCampaignCoordinator.EntryKind.MARKET,fmtQty(qty,rules.decimals),fmtPrice(entry,rules.tick),
                    fmtPrice(stop,rules.tick),c.getString("client_id"),riskPct,
                    signalType=c.getString("signal_type"),signalBarTimeMs=c.getLong("signal_bar_time_ms"),
                    signalReason="TC2 book reversal after confirmed reduce-only flat",leverage=leverage,
                    strategyProfile=williamsTc2Profile,executionSemantics=WILLIAMS_TC2_REVERSAL,
                    setupContext=JSONObject().put("tc2_book_reversal",c)
                        .put("tc2_reverse_pyramid",JSONObject().put("policy",WILLIAMS_TC2_PYRAMID_POLICY)).toString(),
                    signalId=c.getString("signal_id"),reversalOwnerId=c.getString("owner_id"))
                val before=futuresCampaignCoordinator.intentsSnapshot().length()
                val result=withCampaignMutation(symbol,"TC2_CONFIRMED_FLAT_REVERSE_ENTRY") {
                    check(executionReady() && !forceTestnetMode && entryTimePolicy.allows(futuresMarketData.exchangeNowMs())) {
                        "TC2_REVERSAL_RUNTIME_CHANGED_BEFORE_SUBMISSION"
                    }
                    futuresCampaignCoordinator.submitPendingEntry(plan)
                }
                lastOrder=result
                if(result.optBoolean("accepted")) {
                    lastRestReconcileMs=0L
                    stateMachine.force(TradingState.ENTRY_PENDING,"TC2 reversal fill/protection reconciliation")
                } else rejectDecision(symbol,result.optString("blocker","TC2_REVERSAL_REJECTED"))
                if(futuresCampaignCoordinator.state==NativeFuturesCampaignCoordinator.State.RECONCILE_REQUIRED) {
                    reconcileRequired=true;persistSafetyFlag("reconcile_required",true)
                }
                return result.optBoolean("accepted") || futuresCampaignCoordinator.intentsSnapshot().length()!=before
            } catch(failure:Exception) {
                lastError="TC2_REVERSAL_REQUIRES_REVALIDATION: $symbol: "+(failure.message ?: failure.javaClass.simpleName)
                rejectDecision(symbol,lastError ?: "TC2_REVERSAL_BLOCKED")
            }
        }
        return false
    }

    /** Cancel first; a replacement is sized only after a fresh complete exchange reconciliation. */
    private fun manageM15PendingEntry(pending:JSONObject):Boolean {
        val symbol=pending.getString("symbol")
        val now=futuresMarketData.exchangeNowMs()
        val setup=runCatching { JSONObject(pending.optString("setup_context")) }.getOrDefault(JSONObject())
        val old=setup.optJSONObject("mtf_entry")?.let { williamsM15EntryFromJson(it) }
        // A 4.35 M15 order is not a valid 4.36 book-native entry. Cancel only
        // a proven ZERO-FILL pending order, then reconcile before new admission.
        // TRIGGERING/TRIGGERED/fills are handled by the ordinary recovery path.
        var reason="LEGACY_M15_ENTRY_POLICY_MIGRATED_TO_BOOK_H1"
        var replacement:WilliamsM15Entry?=null
        if(old!=null) {
            reason=williamsMtfProofBlocker(setup.toString(),pending.optString("direction"),pending.optString("signal_id"),
                pending.optString("entry_price").toDoubleOrNull() ?: Double.NaN,
                pending.optString("protective_stop").toDoubleOrNull() ?: Double.NaN,now).orEmpty()
        }
        if(old!=null && reason.isEmpty()) {
            // A history/network failure must not prevent management of other protected symbols.
            val assessment=runCatching {
                val h1=fetchCandles(symbol,"1h",220)
                val parents=h1ContextCampaignSignals(symbol,h1,symbolFilters(symbol).tick)
                val m15=fetchCandles(symbol,"15m",220).filter { it.t>0 && it.t+WILLIAMS_M15_MS<=now }
                val bars=m15.map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) }
                val teeth=williamsAlligatorLine(m15,8,5).map { it ?: Double.NaN }
                val mark=futuresMarketData.markPrice(symbol)
                val invalid=if(parents.none { it.signalId==old.parent.signalId })
                    h1ParentRejectionReason(symbol,old.parent.signalId,"H1_PARENT_NO_LONGER_VALID")
                    else williamsPendingM15Invalidation(old,bars,teeth,mark,now).orEmpty()
                val next=selectWilliamsH1M15Entry(old.parent,bars,teeth,old.tick,mark,now).entry
                val closer=next!=null && next.pivotAt>old.pivotAt &&
                    (if(old.parent.direction=="LONG") next.trigger<old.trigger-old.tick*.1 else next.trigger>old.trigger+old.tick*.1)
                Triple(invalid,if(closer) next else null,closer)
            }.getOrElse { failure ->
                rejectDecision(symbol,"M15_PENDING_REVALIDATION_UNAVAILABLE: "+failure.javaClass.simpleName)
                return false
            }
            reason=assessment.first
            replacement=assessment.second
            if(reason.isEmpty() && replacement==null) return false
            if(reason.isEmpty()) reason="NEWER_CLOSER_M15_FRACTAL"
        }
        val oldId=pending.getString("client_id")
        // Persist the replacement fence before canceling. Crash/retry cannot return to the old level.
        val fence=JSONObject().put("parent_id",old?.parent?.signalId ?: pending.optString("signal_id"))
            .put("pivot_at_ms",replacement?.pivotAt ?: -1L)
            .put("expires_at_ms",old?.parent?.expiresAt ?: now+5*WILLIAMS_H1_MS)
            .put("reason",reason).put("old_client_id",oldId)
        if(old!=null) check(executionStore.writeState("mtf_reanchor_"+symbol,fence.toString())) { "M15_CANCEL_FENCE_NOT_DURABLE" }
        val result=withCampaignMutation(symbol,"M15_PENDING_ENTRY_CANCEL",allowPaused=true) {
            try { futuresCampaignCoordinator.cancelPendingEntries(reason,symbol) }
            finally {
                reconcileRequired=true
                check(persistSafetyFlag("reconcile_required",true)) { "M15_CANCEL_RECONCILIATION_FENCE_FAILED" }
            }
        }
        val after=futuresCampaignCoordinator.intentsSnapshot()
        val prior=(0 until after.length()).mapNotNull { after.optJSONObject(it) }.singleOrNull { it.optString("client_id")==oldId }
        check(result.optBoolean("ok") && prior!=null && campaignLifecycleStatus(prior)=="CLOSED_OR_STOPPED" &&
            exchangeOrderStatus(prior) in ENTRY_ORDER_TERMINAL_STATUSES) { "M15_CANCEL_UNRESOLVED: replacement forbidden" }
        // Invalid/expired parent has no replacement. Retain a finite fence against rearming it;
        // a different/newer parent is allowed after the old one has expired/invalidated.
        if(replacement==null) check(executionStore.writeState("mtf_reanchor_"+symbol,"")) { "Cannot retire invalid M15 target" }
        auditStore.recordCockpitEvent("M15_ENTRY_CANCEL",symbol,pending.optString("direction"),"CANCEL_CONFIRMED",reason)
        return true
    }

    private var lastOperatorSupervisionAttemptMs:Long=0L
    @Volatile private var autonomousOrphanAnalysisRunning=false
    @Volatile private var autonomousOrphanAnalysisStartedAtMs=0L
    @Volatile private var autonomousRecoveredCandidateCursor=0

    /**
     * User-authorized on-device Demo recovery: analyse current signed Binance
     * exposure against closed H1/H4 and TC2 first, then require the coordinator
     * to independently verify exact position/STOP twice >=30s apart.
     * The result is STOP-ONLY supervision, NEVER strategy ownership.
     *
     * Review/admission may be deferred during bad data; existing Binance STOP
     * is left unchanged. This method can never open/close/add/reverse trades.
     */
    private fun analyzeAndEnrollAutonomousRecoveredStops() {
        if(!running || !userStreamConnected || userStreamSyncRequired ||
            killLatched || !campaignEngineEnabled) return
        val source=futuresCampaignCoordinator.status()
        val signedProofs=CockpitDiagnostics.rows(source.optJSONArray("exchange_protection_observations"))
        if(signedProofs.isEmpty()) return
        val now=System.currentTimeMillis()
        val signedPositions=futuresCampaignCoordinator.positionsSnapshot()
        val existing=futuresCampaignCoordinator.operatorSupervisionsSnapshot()
        val candidates=CockpitDiagnostics.rows(signedPositions).filter { row ->
            row.optString("symbol") in coreSymbols &&
                CockpitDiagnostics.rows(existing).none {
                    it.optString("symbol")==row.optString("symbol") &&
                        it.optString("state") in setOf("ACTIVE","REPLACEMENT_UNRESOLVED")
                }
        }
        if(candidates.isEmpty()) return
        // Fair rotation: one malformed/no-history symbol must not starve the
        // other orphan positions; bound signed GET and book reviews per sweep.
        val offset=autonomousRecoveredCandidateCursor%candidates.size
        val selected=(0 until minOf(2,candidates.size)).map {
            candidates[(offset+it)%candidates.size]
        }
        autonomousRecoveredCandidateCursor=(offset+selected.size)%candidates.size
        for(pos in selected) {
            val symbol=pos.optString("symbol")
            if(symbol !in coreSymbols ||
                CockpitDiagnostics.rows(existing).any { it.optString("symbol")==symbol &&
                    it.optString("state") in setOf("ACTIVE","REPLACEMENT_UNRESOLVED") }) continue
            val quantity=pos.optString("positionAmt").toBigDecimalOrNull() ?: continue
            if(quantity.signum()==0) continue
            val direction=if(quantity.signum()>0) "LONG" else "SHORT"
            val proof=signedProofs.singleOrNull {
                it.optString("symbol")==symbol && it.optString("direction")==direction
            } ?: continue
            if(autonomousRecoveredStopCandidateBlocker(pos,proof,now)!=null) continue
            val stop=proof.optString("stop").toDoubleOrNull() ?: continue
            val entry=pos.optString("entryPrice").toDoubleOrNull() ?: continue
            if(!stop.isFinite() || stop<=0.0 || !entry.isFinite() || entry<=0.0) continue
            runCatching {
                val h1=fetchCandles(symbol,"1h",180)
                val h4=fetchCandles(symbol,"4h",80)
                require(h1.size>=45 && h4.size>=45) {
                    "Book wave/Alligator evidence requires 45 H1/H4 candles"
                }
                val h1Wave=h1.map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) }
                val h4Wave=h4.map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) }
                val exchangeNow=futuresMarketData.exchangeNowMs()
                require(williamsClosedH1HistoryBlocker(h1Wave,exchangeNow)==null) {
                    "H1 closed-candle source not independently verified"
                }
                val mark=futuresMarketData.markPrice(symbol)
                val tick=symbolFilters(symbol).tick
                val review=reviewWilliamsTc2StopTightening(
                    direction=direction,oldStop=stop,entry=entry,initialStop=stop,
                    mark=mark,tick=tick,
                    openedAtMs=now,asOfMs=exchangeNow,
                    h1Input=h1Wave,h4Input=h4Wave,
                    feeAndSlippagePct=2.0*feeBufferPerSidePct+maxSlippagePct
                )
                // All initial analysis, including a WAIT / no-proposal decision,
                // must be durably auditable before optional future STOP trailing.
                val evidence=review.evidence
                    .put("autonomous_orphan_mode","AUTO_VERIFIED_STOP_ONLY")
                    .put("strategy_ownership_verified",false)
                    .put("no_add_close_reverse",true)
                val evidenceId=auditStore.recordStopTrailDecision(
                    symbol,direction,"AUTO_RECOVERY:"+symbol,
                    "CLOSED_H1_H4_REVIEW_BEFORE_ADOPTION",evidence)
                check(evidenceId>0L) { "Book analysis audit not durable" }
                val result=futuresCampaignCoordinator.enrollAutonomousRecoveredStopOnly(
                    symbol,direction,quantity.abs().toPlainString(),
                    pos.optString("entryPrice"),proof.optString("stop")
                )
                if(result.optString("status")=="AUTONOMOUS_STOP_ONLY_ACTIVATED") {
                    auditStore.recordCockpitEvent("AUTO_RECOVERED_STOP_ONLY",symbol,direction,
                        "TWO_SIGNED_PROOFS_H1_H4_ANALYZED",
                        "SL retained; no new position, no market close, no strategy ownership; evidence="+evidenceId)
                }
            }.onFailure {
                val detail="AUTONOMOUS_ORPHAN_ANALYSIS_BLOCKED "+symbol+": "+
                    (it.message ?: it.javaClass.simpleName)
                lastError=detail
                runCatching { auditStore.recordCockpitEvent("AUTO_RECOVERY",symbol,direction,
                    "NOT_AUTHORIZED",detail) }
            }
        }
    }

    private fun scheduleAutonomousOrphanAnalysis() {
        val now=System.currentTimeMillis()
        if(!running || !userStreamConnected || userStreamSyncRequired ||
            killLatched || !campaignEngineEnabled) return
        synchronized(this) {
            if(autonomousOrphanAnalysisRunning ||
                now-autonomousOrphanAnalysisStartedAtMs<30_000L) return
            autonomousOrphanAnalysisRunning=true
            autonomousOrphanAnalysisStartedAtMs=now
        }
        // Signed reads + H1/H4 analysis MUST NOT execute while holding the
        // account-wide futuresReconciliationLock; private-stream/fill/STOP
        // reconciliation must remain available every few seconds.
        Thread({
            try {
                analyzeAndEnrollAutonomousRecoveredStops()
            } catch(failure:Exception) {
                lastError="AUTONOMOUS_RECOVERY_ANALYSIS_UNAVAILABLE: "+
                    (failure.message ?: failure.javaClass.simpleName)
            } finally {
                autonomousOrphanAnalysisRunning=false
            }
        },"williams-recovery-book-analysis").apply { isDaemon=true; start() }
    }

    private fun runOperatorStopSupervisionIfDue() {
        val now=System.currentTimeMillis()
        if(lastOperatorSupervisionAttemptMs>0L &&
            now>=lastOperatorSupervisionAttemptMs && now-lastOperatorSupervisionAttemptMs<10_000L) return
        lastOperatorSupervisionAttemptMs=now
        // Never block this signed reconciliation's account lock on H1/H4
        // downloads or the independent two-snapshot adoption preflight.
        scheduleAutonomousOrphanAnalysis()
        if(futuresCampaignCoordinator.operatorSupervisionsSnapshot().length()==0) return
        // One failed maintenance observation must not erase the signed account
        // reconciliation result. Every mutation still acquires its own Door.
        runCatching { futuresCampaignCoordinator.refreshOperatorClosedTradeHistory() }
        runCatching { manageOperatorSupervisedPositions() }.onFailure {
            lastError="OPERATOR_STOP_SUPERVISION_BLOCKED: "+(it.message ?: it.javaClass.simpleName)
        }
    }

    private fun manageOperatorSupervisedPositions() {
        val records=futuresCampaignCoordinator.operatorSupervisionsSnapshot()
        for(i in 0 until records.length()) {
            val record=records.optJSONObject(i) ?: continue
            val symbol=record.optString("symbol")
            if(symbol !in coreSymbols) continue
            if(record.optString("state")=="REPLACEMENT_UNRESOLVED") {
                runCatching {
                    val result=withCampaignMutation(
                        symbol,"OPERATOR_STOP_ACK_RECOVERY",
                        allowPaused=true,allowStopped=true,allowKillLatched=true,
                        allowReconcileRequired=true,waitForDoorMs=15_000L
                    ) { futuresCampaignCoordinator.recoverOperatorSupervisedStop(symbol) }
                    if(!result.optBoolean("ok")) {
                        lastError="STOP_REPLACEMENT_UNRESOLVED "+symbol+": "+
                            result.optString("blocker","Binance proof incomplete")
                    }
                    futuresCampaignCoordinator.recordOperatorSupervisionObservation(symbol,
                        if(result.optBoolean("ok")) "RECOVERED" else "BLOCKED",
                        result.optString("blocker",result.optString("reason","Replacement confirmed by Binance")))
                }.onFailure { lastError="STOP_RECOVERY_BLOCKED "+symbol+": "+
                    (it.message ?: it.javaClass.simpleName)
                    runCatching { futuresCampaignCoordinator.recordOperatorSupervisionObservation(
                        symbol,"BLOCKED",lastError.orEmpty()) }
                }
                continue
            }
            if(record.optString("state")!="ACTIVE") continue
            runCatching {
                val direction=NativeFuturesCampaignCoordinator.Direction.valueOf(record.getString("direction"))
                val bars=fetchCandles(symbol,"1h",180)
                require(bars.size>=45) { "Need 45 H1 candles before supervised trailing" }
                val rules=symbolFilters(symbol)
                val mark=futuresMarketData.markPrice(symbol)
                val review=reviewWilliamsTc2StopTightening(
                    direction=direction.name,
                    oldStop=record.getDouble("stop"),
                    entry=record.getDouble("entry_price"),
                    initialStop=record.optDouble("initial_stop",record.getDouble("stop")),
                    mark=mark,tick=rules.tick,
                    openedAtMs=record.optLong("approved_at_ms",0L),
                    asOfMs=futuresMarketData.exchangeNowMs(),
                    h1Input=bars.map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) },
                    h4Input=fetchCandles(symbol,"4h",50).map {
                        WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v)
                    },
                    feeAndSlippagePct=2.0*feeBufferPerSidePct+maxSlippagePct
                )
                val plan=review.proposal
                val trailClientId=if(plan!=null) ("W2T_"+symbol.take(8)+"_"+
                    System.nanoTime().toString(36).takeLast(14)).take(36) else null
                val submittedStop=plan?.let { fmtPrice(it.stop,rules.tick) }
                if(trailClientId!=null)
                    review.evidence.put("proposed_binance_protection_client_id",trailClientId)
                        .put("submitted_stop_price",submittedStop)
                val supervisionId="SUPERVISION:"+symbol+":"+record.optLong("approved_at_ms",0L)
                // Append the complete decision before any optional Binance mutation.
                // If SQLite is not durable, voluntary trailing must NOT proceed.
                val decisionAuditId=if(plan!=null)
                    auditStore.recordStopTrailDecision(symbol,direction.name,supervisionId,
                        "PROPOSED_BEFORE_MUTATION",review.evidence)
                    else runCatching {
                        auditStore.recordStopTrailDecision(symbol,direction.name,supervisionId,
                            "REVIEW_DEFERRED",review.evidence)
                    }.getOrDefault(0L)
                val awaitingFirstClose=bars.last().t+WILLIAMS_H1_MS<=record.optLong("approved_at_ms",0L)
                futuresCampaignCoordinator.recordOperatorSupervisionObservation(symbol,
                    if(plan!=null) "STOP_PROPOSED" else if(awaitingFirstClose)
                        "WAITING_FOR_FIRST_CLOSED_H1" else "WAITING_FOR_STRUCTURE",
                    plan?.reason ?: if(awaitingFirstClose)
                        "No H1 candle has closed after operator approval; existing protection retained"
                        else review.blocker,
                    bars.last().t+WILLIAMS_H1_MS,plan?.stop)
                if(plan!=null) {
                    val clientId=trailClientId ?: error("MISSING_STOP_TRAIL_CLIENT_ID")
                    val outcome=try {
                        withCampaignMutation(
                            symbol,"OPERATOR_EXISTING_STOP_ONLY_TRAIL",
                            allowPaused=true,allowStopped=true,allowKillLatched=true,
                            allowReconcileRequired=true,waitForDoorMs=15_000L
                        ) {
                            futuresCampaignCoordinator.trailOperatorSupervisedStop(
                                symbol,direction,submittedStop ?: error("NO_SUBMITTED_STOP"),clientId)
                        }
                    } catch(failure:Exception) {
                        runCatching {
                            auditStore.recordStopTrailDecision(symbol,direction.name,supervisionId,
                                "EXCHANGE_EXCEPTION_UNVERIFIED",review.evidence,
                                JSONObject().put("error_type",failure.javaClass.simpleName)
                                    .put("retry_authorized",false))
                        }
                        throw failure
                    }
                    // The exchange response is evidence only when the coordinator
                    // has verified it. Never retry an ambiguous mutation for audit.
                    runCatching {
                        auditStore.recordStopTrailDecision(symbol,direction.name,supervisionId,
                            if(outcome.optBoolean("ok")) "EXCHANGE_RESPONSE_OK"
                            else "EXCHANGE_NOT_CONFIRMED",review.evidence,outcome)
                    }.onFailure {
                        lastError="POST_STOP_AUDIT_FAILED "+symbol+": "+
                            (it.message ?: it.javaClass.simpleName)
                    }
                    check(outcome.optBoolean("ok")) {
                        outcome.optString("blocker","Operator stop replacement unresolved")
                    }
                    auditStore.recordCockpitEvent("OPERATOR_PROTECTION_TRAILED",
                        symbol,state="EXCHANGE_CONFIRMED",
                        detail="TC2 H1/H4 stop reviewed; new_stop="+plan.stop+
                            "; decision_audit_id="+decisionAuditId+
                            "; full source candles and Binance outcome in SQLite; no book ownership claimed")
                    futuresCampaignCoordinator.recordOperatorSupervisionObservation(
                        symbol,"TRAILED",plan.reason,bars.last().t+WILLIAMS_H1_MS,plan.stop)
                }
            }.onFailure {
                val blocker="OPERATOR_STOP_SUPERVISION_BLOCKED "+symbol+": "+
                    (it.message ?: it.javaClass.simpleName)
                lastError=blocker
                runCatching { futuresCampaignCoordinator.recordOperatorSupervisionObservation(
                    symbol,"BLOCKED",blocker) }
                runCatching { auditStore.recordCockpitEvent(
                    "OPERATOR_STOP_SUPERVISION",symbol,state="BLOCKED",detail=blocker) }
            }
        }
    }

    private fun manageVerifiedNativePositions(
        positions: JSONArray,
        intents: JSONArray,
        riskOnly: Boolean,
        eligibleSymbols: Set<String>? = null
    ) {
        for (i in 0 until positions.length()) {
            val row = positions.optJSONObject(i)
                ?: throw IllegalStateException("Malformed Binance Futures position row during structural exit evaluation")
            val symbol = row.optString("symbol").uppercase(Locale.US)
            if (symbol !in coreSymbols || (eligibleSymbols!=null && symbol !in eligibleSymbols)) continue
            val signedQty = row.optString("positionAmt").toDoubleOrNull()
                ?: throw IllegalStateException("Malformed Binance Futures position amount for $symbol during structural exit evaluation")
            if (signedQty == 0.0) continue
            val direction = if (signedQty > 0.0) NativeFuturesCampaignCoordinator.Direction.LONG
                else NativeFuturesCampaignCoordinator.Direction.SHORT
            val dir = direction.name
            val candles = fetchCandles(symbol, "1h", 180)
            if (candles.size < 45) throw IllegalStateException(symbol + " needs at least 45 closed H1 candles")
            val rules = symbolFilters(symbol)
            val mark = futuresMarketData.markPrice(symbol)
            val intentIndex = latestEntryIntentIndex(intents, symbol)
            val intent = intentIndex?.let { intents.optJSONObject(it) }
                ?.takeIf { it.optString("direction").equals(dir, ignoreCase = true) &&
                    campaignLifecycleStatus(it) == "OPEN_PROTECTED" }
                ?: throw IllegalStateException(symbol + " has no latest exchange-confirmed protected campaign")
            val openedAt = intent.optLong("position_open_observed_at_ms",0L)
            require(mark.isFinite() && mark > 0) { "Invalid mark during Williams management" }
            val correctionProof = if (intent.optString("trade_mode") == "CORRECTION") {
                val proof = JSONObject(intent.optString("wave_context_at_entry"))
                require(williamsCorrectionBoundsValid(proof)) { "Correction proof invalid; retain confirmed protection" }
                proof
            } else null
            val boundaryExit = correctionProof != null && !williamsCorrectionPriceAllowed(correctionProof,mark)
            val profile=intent.optString("strategy_profile",williamsTc2Profile)
            // Only TC2's exchange-protected structural trailing may operate
            // in a partially reconciled account. Course/NTD exits have their
            // own authorization lifecycle and are NOT generic risk-only stops.
            if(eligibleSymbols!=null && profile!=williamsTc2Profile) continue
            require(profile in williamsEditionProfiles) { "Unknown durable strategy profile; retain protection" }
            val bars=candles.map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) }
            require(williamsClosedH1HistoryBlocker(bars,futuresMarketData.exchangeNowMs())==null) {
                "SOURCE_MANAGEMENT_CLOSED_H1_HISTORY_UNVERIFIED"
            }
            var editionExit="";var editionStop:Double?=null
            if(profile==williamsNtdProfile) {
                val aos=candles.indices.map { ao(candles,it) }
                val acs=candles.indices.map { i -> if(i>=4) nativeAcFromClosedAo(aos.subList(i-4,i+1)) else Double.NaN }
                val management=williamsNtdManagementPlan(dir,bars,
                    williamsAlligatorLine(candles,8,5).map { it ?: Double.NaN },
                    williamsAlligatorLine(candles,5,3).map { it ?: Double.NaN },aos,acs,rules.tick,openedAt,
                    intent.optJSONObject("edition_management") ?: JSONObject())
                val oldBookEvidence=intent.optJSONObject("edition_management")
                    ?.optString("zone_book_evidence").orEmpty()
                val newBookEvidence=management.state.optString("zone_book_evidence")
                if(newBookEvidence!=oldBookEvidence && newBookEvidence in setOf(
                    "HISTORICAL_BOOK_STOP_TOUCHED_RECONCILE_EXCHANGE",
                    "NTD_ZONE_HISTORY_WINDOW_UNVERIFIED",
                    "NTD_REPLAY_CONFLICTS_WITH_PERSISTED_STOP",
                    "NTD_BOOK_PRICE_OFF_EXCHANGE_TICK_GRID",
                    "NTD_H1_HISTORY_GAP"
                )) {
                    // Historical OHLC may prove a BOOK stop *could* have
                    // triggered, but it is NOT a signed Binance fill. Alert
                    // instead of inventing a retrospective market exit.
                    runCatching { auditStore.recordCockpitEvent(
                        "NTD_BOOK_EXIT_EVIDENCE_QUARANTINED",symbol=symbol,direction=dir,
                        state="EXISTING_BINANCE_STOP_RETAINED",
                        detail="reason="+newBookEvidence+
                            "; position and exchange order require signed reconciliation"
                    ) }.onFailure { lastError="NTD_BOOK_EXIT_AUDIT_FAILED "+symbol+": "+
                        (it.message ?: it.javaClass.simpleName) }
                }
                futuresCampaignCoordinator.saveManagementState(symbol,intent.getString("client_id"),management.state)
                intent.put("edition_management",management.state)
                editionExit=management.exitReason;editionStop=management.stop
                if(editionStop!=null && (if(dir=="LONG") mark<=editionStop else mark>=editionStop))
                    editionExit="NTD_FIVE_ZONE_PROTECTION_CROSSED"
            } else if(profile==williamsCourseProfile) {
                val management=JSONObject((intent.optJSONObject("edition_management") ?: JSONObject()).toString())
                // Only coordinator reconciliation can publish an exact signed
                // stage receipt. A target-shaped position or partial initial
                // fill must never manufacture a unit or advance this stage.
                futuresCampaignCoordinator.saveManagementState(symbol,intent.getString("client_id"),management)
                intent.put("edition_management",management)
                if(management.has("course_unit_quantity")) {
                    val proof=runCatching { courseTrimPlan(intent) }.getOrElse { JSONObject().put("status","BLOCKED")
                        .put("reason","COURSE_TRIM_SOURCE_UNAVAILABLE").put("error",it.javaClass.simpleName) }
                    management.put("course_stage_admission",proof)
                    futuresCampaignCoordinator.saveManagementState(symbol,intent.getString("client_id"),management)
                    if(proof.optString("status")=="OBSERVED_SOURCE_PINNED_REDUCTION") {
                        val stage=proof.getString("stage")
                        val reduction=proof.getDouble("reduction_quantity")
                        if(reduction>0 && reduction<abs(signedQty)) {
                            val seed=intent.getString("client_id")+":"+stage+":"+candles.last().t+":"+abs(signedQty)
                            val digest=java.security.MessageDigest.getInstance("SHA-256").digest(seed.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)
                            val cid="W2R_${symbol.take(7)}_$digest"
                            management.put("course_pending_stage",stage).put("course_pending_target",abs(signedQty)-reduction)
                                .put("course_pending_client_id",cid).put("course_pending_quantity",proof.getJSONObject("exact_quantity"))
                            futuresCampaignCoordinator.saveManagementState(symbol,intent.getString("client_id"),management)
                            val trimmed=withCampaignMutation(symbol,"COURSE_STAGE_REDUCTION",allowPaused=true, allowReconcileRequired=riskOnly) {
                                futuresCampaignCoordinator.closePosition(symbol,direction,fmtQty(reduction,rules.decimals),cid,"COURSE_STAGE_TRIM",partialReduction=true)
                            }
                            if(!trimmed.optBoolean("ok",false)) throw IllegalStateException(trimmed.optString("blocker","Course reduction requires reconciliation"))
                            continue
                        }
                    }
                }
                if(management.optString("course_stage") in setOf("W3","W5","A_ENTRY","A_REDUCE","C_ADD")) {
                    val terminal=runCatching { courseExitObservation(intent) }.getOrElse {
                        JSONObject().put("status","BLOCKED").put("blocker","COURSE_EXIT_SOURCE_UNAVAILABLE") }
                    management.put("course_terminal_admission",terminal)
                    futuresCampaignCoordinator.saveManagementState(symbol,intent.getString("client_id"),management)
                    if(terminal.optString("status")=="OBSERVED_COURSE_SOURCE_RULE") editionExit=terminal.getString("reason")
                }
                val higher=fetchCandles(symbol,"6h",100).map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) }
                val checked=closedWilliamsWaveBars(higher,"6h",futuresMarketData.exchangeNowMs())
                val airbag=williamsCourseAirbagStop(dir,checked,rules.tick)
                val courseContract=futuresMarketData.price(symbol)
                if(airbag!=null && (if(dir=="LONG") courseContract<=airbag else courseContract>=airbag)) editionExit="COURSE_HIGHER_FRAME_AIRBAG_TRIGGERED"
                val old=intent.getString("protective_stop").toDouble()
                val candidates=listOfNotNull(airbag,williamsCourseTwoFractalStop(dir,bars,rules.tick)).filter {
                    if(dir=="LONG") it>old && it<mark else it<old && it>mark
                }
                editionStop=if(dir=="LONG") candidates.maxOrNull() else candidates.minOrNull()
            }
            // TC2 ch11-12 (gold reversal, PDF154-157): a genuinely TRIGGERED
            // opposite WM1/WM2/WM3 entry signal can close the older campaign.
            // It must be the SAME TC2 source, after our entry, with causal H1
            // evidence and price still across its breakout trigger. A correction
            // wave hypothesis ALONE never orders a TC2 market exit. The broker
            // protection remains armed until the reduce-only exit is confirmed.
            val opposing = if(profile==williamsTc2Profile) {
                runCatching { triggeredOpposingSignal(symbol,direction,candles,rules.tick,futuresMarketData.price(symbol),openedAt,profile) }
                    .onFailure { lastError="TC2_OPPOSITION_EVIDENCE_UNAVAILABLE: $symbol: "+
                        (it.message ?: it.javaClass.simpleName) }.getOrNull()
            } else runCatching { triggeredOpposingSignal(symbol,direction,candles,rules.tick,futuresMarketData.price(symbol),openedAt,profile) }
                .onFailure { lastError="SOURCE_OPPOSITION_EVIDENCE_UNAVAILABLE: $symbol: "+(it.message ?: it.javaClass.simpleName) }.getOrNull()
            if(profile in setOf(williamsTc2Profile,williamsCourseProfile) && boundaryExit) {
                val management=JSONObject((intent.optJSONObject("edition_management") ?: JSONObject()).toString())
                    .put(if(profile==williamsCourseProfile) "course_boundary_advisory" else "tc2_correction_boundary_advisory",JSONObject()
                        .put("policy",if(profile==williamsCourseProfile) "COURSE_SOURCE_PRICE_RULE_REQUIRED" else "TC2_OPPOSITE_BOOK_TRIGGER_REQUIRED")
                        .put("observed_at_ms",futuresMarketData.exchangeNowMs())
                        .put("mark_price",mark).put("market_close_authorized",false))
                futuresCampaignCoordinator.saveManagementState(symbol,intent.getString("client_id"),management)
            }
            val tc2TriggerNow = opposing!=null &&
                opposing.strategyProfile==williamsTc2Profile &&
                opposing.role=="ENTRY" && opposing.executionSemantics=="STOP_ENTRY" &&
                opposing.type.removePrefix("SHORT_") in setOf("REVERSAL","SUPER_AO","FRACTAL") &&
                campaignSignalDirection(opposing.type)!=dir &&
                (executionStore.readState("decision_h1_opposition_"+symbol)?.let {
                    val quote=JSONObject(it).optDouble("contract_price",Double.NaN)
                    quote.isFinite() && quote>0 &&
                        (if(dir=="LONG") quote<=opposing.triggerPrice else quote>=opposing.triggerPrice)
                }==true)
            val exchangeEntryForExit=row.optString("entryPrice").toDoubleOrNull() ?: Double.NaN
            val oppositeProfitBlocker=if(profile in williamsSourceProfiles)
                williamsTc2OppositeExitProfitBlocker(dir,exchangeEntryForExit,mark,
                    2.0*feeBufferPerSidePct+maxSlippagePct,rules.tick) else null
            val actionableOpposing = opposing?.takeIf {
                williamsOpposingSignalCanMarketClose(profile) &&
                    oppositeProfitBlocker==null &&
                    (profile!=williamsTc2Profile ||
                        (!riskOnly && eligibleSymbols==null && tc2TriggerNow &&
                            oppositeProfitBlocker==null))
            }
            val courseReason=if(profile==williamsCourseProfile) {
                if(editionExit.isNotEmpty()) editionExit else if(actionableOpposing!=null) "COURSE_OPPOSITE_FORMATION_TRIGGERED" else null
            } else null
            val courseProof=if(courseReason!=null) runCatching { courseExitSourceProof(intent,courseReason) }.onFailure {
                val management=JSONObject((intent.optJSONObject("edition_management") ?: JSONObject()).toString())
                    .put("course_exit_advisory",JSONObject().put("reason",courseReason).put("blocker",it.message ?: it.javaClass.simpleName)
                        .put("market_close_authorized",false))
                futuresCampaignCoordinator.saveManagementState(symbol,intent.getString("client_id"),management)
                intent.put("edition_management",management)
            }.getOrNull() else null
            if(profile==williamsTc2Profile && opposing!=null && actionableOpposing==null) {
                val management=JSONObject((intent.optJSONObject("edition_management") ?: JSONObject()).toString())
                    .put("tc2_opposing_signal_advisory",JSONObject()
                        .put("policy","TC2_OPPOSITE_BOOK_TRIGGER_REQUIRED")
                        .put("signal_id",opposing.signalId).put("source",opposing.strategyProfile)
                        .put("observed_at_ms",futuresMarketData.exchangeNowMs())
                        .put("profit_exit_blocker",oppositeProfitBlocker ?: JSONObject.NULL)
                        .put("policy_note","OPPOSITE_SETUP_IS_WARNING_UNTIL_PROFITABLE_BOOK_EXIT")
                        .put("market_close_authorized",false))
                futuresCampaignCoordinator.saveManagementState(symbol,intent.getString("client_id"),management)
            }
            if (williamsStrategyCanMarketClose(profile) &&
                (profile!=williamsCourseProfile || courseProof!=null) &&
                (actionableOpposing != null ||
                    profile!=williamsTc2Profile && editionExit.isNotEmpty())) {
                val correction = if (actionableOpposing != null) williamsCorrectionEntryContext(waveHierarchyFor(symbol),
                    if (dir == "LONG") "SHORT" else "LONG",actionableOpposing.signalBarTimeMs,actionableOpposing.type) else WilliamsCorrectionContext()
                val exitReason = if(profile==williamsCourseProfile) courseReason!! else if(profile!=williamsTc2Profile && editionExit.isNotEmpty()) editionExit
                    else if (correction.allowed) "OPPOSITE_WILLIAMS_TRIGGER_IN_HIGHER_CORRECTION"
                    else "OPPOSITE_WILLIAMS_LEVEL_TRIGGERED"
                val decision = if(profile==williamsTc2Profile)
                    JSONObject(executionStore.readState("decision_h1_opposition_"+symbol).orEmpty()) else JSONObject()
                val proofAt = if(profile==williamsTc2Profile)
                    decision.optLong("observed_at_ms") else futuresMarketData.exchangeNowMs()
                val management=JSONObject((intent.optJSONObject("edition_management") ?: JSONObject()).toString())
                management.put("exit_evidence",(courseProof?.let { JSONObject(it.toString()) } ?: JSONObject()).put("reason",exitReason)
                    .put("signal_id",actionableOpposing?.signalId ?: JSONObject.NULL)
                    .put("observed_at_ms",proofAt).put("edition_exit",editionExit)
                    .put("correction_boundary_broken",boundaryExit).put("higher_correction",correction.toJson())
                    .put("opposition_check",if(actionableOpposing!=null)
                        JSONObject(executionStore.readState("decision_h1_opposition_"+symbol).orEmpty()) else JSONObject.NULL)
                    .put("reverse_candidate",if(profile==williamsTc2Profile && actionableOpposing!=null) JSONObject()
                        .put("symbol",symbol).put("direction",campaignSignalDirection(actionableOpposing.type))
                        .put("signal_id",actionableOpposing.signalId).put("signal_type",actionableOpposing.type)
                        .put("strategy_profile",profile).put("execution_semantics",actionableOpposing.executionSemantics)
                        .put("signal_bar_time_ms",actionableOpposing.signalBarTimeMs)
                        .put("trigger",actionableOpposing.triggerPrice).put("protective_stop",actionableOpposing.protectivePrice)
                        else JSONObject.NULL))
                futuresCampaignCoordinator.saveManagementState(symbol,intent.getString("client_id"),management)
                val closeQty = fullMarketCloseQuantity(row.optString("positionAmt"),rules)
                val clientId = ("W2O_"+symbol.take(8)+"_"+System.nanoTime().toString(16).takeLast(12)).take(36)
                val result = withCampaignMutation(symbol,"TRIGGERED_WILLIAMS_EXIT",allowPaused=true, allowReconcileRequired=riskOnly) {
                    futuresCampaignCoordinator.closePosition(symbol,direction,closeQty,clientId,exitReason)
                }
                if (!result.optBoolean("ok",false)) throw IllegalStateException(result.optString("blocker",result.optString("error")))
                continue
            }
            val oldStop = intent.optString("protective_stop").toDoubleOrNull()
                ?: throw IllegalStateException(symbol + " structural stop is missing")
            val originalStop = intent.optString("initial_protective_stop").toDoubleOrNull()
            val actualEntry = row.optString("entryPrice").toDoubleOrNull()
                ?: throw IllegalStateException(symbol + " Binance entry price is missing for trailing")
            val tc2Review = if(profile==williamsTc2Profile) reviewWilliamsTc2StopTightening(
                direction=dir,oldStop=oldStop,entry=actualEntry,initialStop=originalStop,
                mark=mark,tick=rules.tick,
                openedAtMs=intent.optLong("position_open_observed_at_ms",0L),
                asOfMs=futuresMarketData.exchangeNowMs(),
                h1Input=bars,
                h4Input=fetchCandles(symbol,"4h",50).map {
                    WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v)
                },
                feeAndSlippagePct=2.0*feeBufferPerSidePct+maxSlippagePct,
                campaignQuantity=kotlin.math.abs(signedQty)
            ) else null
            val plan = if(profile!=williamsTc2Profile) editionStop?.takeIf {
                if(dir=="LONG") it>oldStop && it<mark else it<oldStop && it>mark
            }?.let { FuturesStopTrailProposal(it,"${profile}_PROTECTION") } else tc2Review?.proposal
            if(tc2Review!=null && plan==null &&
                tc2Review.blocker !in setOf("NO_NEW_CONFIRMED_H1_PROTECTION_STRUCTURE")) {
                runCatching { auditStore.recordCockpitEvent(
                    "TC2_STOP_REVIEW_DEFERRED",symbol= symbol,direction=dir,
                    state="EXISTING_BINANCE_STOP_RETAINED",
                    detail="blocker="+tc2Review.blocker+
                        "; complete H1/H4 evidence stored as a structured stop decision"
                ) }
                runCatching {
                    auditStore.recordStopTrailDecision(symbol,dir,intent.optString("client_id"),
                        "REVIEW_DEFERRED",tc2Review.evidence)
                }.onFailure { lastError="DEFERRED_STOP_AUDIT_FAILED "+symbol+": "+
                    (it.message ?: it.javaClass.simpleName) }
            }
            if (plan != null) {
                // Bind the forensic PREPARED receipt to the very same client ID
                // the coordinator will persist before Binance network I/O.
                val clientId = ("W2T_" + symbol.take(8) + "_" +
                    System.nanoTime().toString(36).takeLast(14)).take(36)
                val submittedStop=fmtPrice(plan.stop,rules.tick)
                tc2Review?.evidence?.put("proposed_binance_protection_client_id",clientId)
                    ?.put("previous_binance_protection_client_id",intent.optString("protection_client_id"))
                    ?.put("submitted_stop_price",submittedStop)
                val decisionAuditId=if(tc2Review!=null) {
                    // Fail closed when forensic decision write is unavailable:
                    // no optional STOP swap without complete pre-mutation evidence.
                    auditStore.recordStopTrailDecision(symbol,dir,intent.getString("client_id"),
                        "PROPOSED_BEFORE_MUTATION",tc2Review.evidence)
                } else 0L
                // Distinct structural H1/fractal improvements require unique Binance
                // algo IDs; a timestamp alone cannot identify replacements.
                val result = try {
                    withCampaignMutation(
                        symbol = symbol,
                        purpose = "WILLIAMS_STRUCTURAL_STOP_TRAIL",
                        allowPaused = true, allowReconcileRequired = riskOnly
                    ) {
                        futuresCampaignCoordinator.trailProtectiveStop(
                            symbol, direction, submittedStop, clientId, reason = plan.reason
                        )
                    }
                } catch(failure:Exception) {
                    if(tc2Review!=null) runCatching {
                        auditStore.recordStopTrailDecision(symbol,dir,intent.getString("client_id"),
                            "EXCHANGE_EXCEPTION_UNVERIFIED",tc2Review.evidence,
                            JSONObject().put("error_type",failure.javaClass.simpleName)
                                .put("retry_authorized",false))
                    }
                    throw failure
                }
                if(tc2Review!=null) runCatching {
                    auditStore.recordStopTrailDecision(symbol,dir,intent.getString("client_id"),
                        if(result.optBoolean("ok",false)) "EXCHANGE_RESPONSE_OK"
                        else "EXCHANGE_NOT_CONFIRMED",tc2Review.evidence,result)
                }.onFailure { lastError="POST_STOP_AUDIT_FAILED "+symbol+": "+
                    (it.message ?: it.javaClass.simpleName) }
                if (!result.optBoolean("ok", false)) {
                    throw IllegalStateException(result.optString("blocker", result.optString("error")))
                }
                synchronized(this) {
                    futuresPositionSnapshotEpoch++
                    cachedFuturesPositionEnvelope = null
                    futuresPositionSnapshotLastAttemptMs = 0L
                }
                runCatching {
                    auditStore.recordCockpitEvent(
                        "PROTECTION_TRAILED", symbol = symbol, direction = dir,
                        state = "OPEN_PROTECTED",
                        detail = "${plan.reason}; previous=$oldStop; new=${plan.stop}; mark=$mark; entry=$actualEntry; "+
                            if(tc2Review!=null) "decision_audit_id=$decisionAuditId; full evidence in SQLite"
                            else "non_TC2_management"
                    )
                }
            }
        }
    }

    private fun submitNativeFuturesAddition(candidate:BaseAnalysis,owner:JSONObject) {
        val symbol=candidate.symbol;val profile=owner.optString("strategy_profile",williamsTc2Profile)
        // A verified old NTD/Course owner can retain exchange protection and
        // recovery, but observation-only sources must not create add-on risk.
        if(!williamsVerifiedNewRiskSource(profile)) {
            rejectDecision(symbol,"SOURCE_OBSERVATION_ONLY: source lifecycle not enabled")
            return
        }
        if(!williamsProfileAllowsSource(williamsProfile,profile) || !executionReady()) return
        val all=futuresCampaignCoordinator.intentsSnapshot()
        val parentId=owner.getString("client_id")
        val children=(0 until all.length()).mapNotNull { all.optJSONObject(it) }
            .filter { it.optString("intent_role")=="ADD_ON" && it.optString("entry_client_id")==parentId }
        if(children.any { exchangeOrderStatus(it) !in ENTRY_ORDER_TERMINAL_STATUSES }) return
        val latest=maxOf(owner.optLong("signal_bar_time_ms"),children.maxOfOrNull { it.optLong("signal_bar_time_ms") } ?: 0L)
        val now=futuresMarketData.exchangeNowMs();val direction=owner.getString("direction")
        fun additionClientId(signal:CampaignSignalN):String {
            val digest=java.security.MessageDigest.getInstance("SHA-256").digest(signal.signalId.toByteArray())
                .joinToString("") { "%02x".format(it) }.take(20)
            return "W2A_${symbol.take(7)}_$digest"
        }
        val rules=symbolFilters(symbol);val mark=futuresMarketData.markPrice(symbol)
        val contractPrice=futuresMarketData.price(symbol)
        val signal=candidate.campaignSignals.filter { it.strategyProfile==profile &&
            campaignSignalDirection(it.type)==direction && williamsAdditionTimeEligible(profile,it.signalBarTimeMs,it.type,
                owner.optLong("signal_bar_time_ms"),owner.optString("signal_type"),latest) &&
            williamsH1SignalTimeEligible(it.signalBarTimeMs,it.type,it.strategyProfile,now) &&
            (it.executionSemantics!="MARKET_ON_CLOSE" || williamsMarketOnCloseReason(it.signalBarTimeMs,3_600_000L,now,it.triggerPrice,mark)==null) &&
            children.none { child -> child.optString("client_id")==additionClientId(it) } }
            .minWithOrNull(compareBy<CampaignSignalN> { it.executionSemantics!="MARKET_ON_CLOSE" }.thenBy { it.signalBarTimeMs }) ?: return
        // Existing campaign stays protected; only an attempted ADD_ON adds risk.
        // TC2 ch11 fig.11.7 cautions against late trades in bounded correction.
        williamsTc2CongestionEntryBlocker(profile,signal.type,direction,signal.triggerPrice,
            candidate.candles.map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) },now)
            ?.let { return rejectDecision(symbol,it+": new add-on withheld, protected owner unchanged") }
        val market=signal.executionSemantics=="MARKET_ON_CLOSE"
        if(market && (profile!=williamsNtdProfile || signal.type.removePrefix("SHORT_")!="ZONE_ADD_ON" ||
            williamsMarketOnCloseReason(signal.signalBarTimeMs,3_600_000L,now,signal.triggerPrice,mark)!=null)) return
        val price=if(market) williamsNtdCloseEntryBound(direction,signal.triggerPrice,rules.tick) ?: return
            else signal.triggerPrice
        val stop=owner.getString("protective_stop").toDouble()
        if((if(direction=="LONG") stop>=price || !market && price<=contractPrice else stop<=price || !market && price>=contractPrice)) return
        if(profile in williamsExecutionProfiles) {
            williamsTc2ProfitBlocker(direction,owner.optString("actual_entry_price").toDoubleOrNull() ?: Double.NaN,mark,price)
                ?.let { return rejectDecision(symbol,it) }
        }
        // TC2 ch10/12 explicitly permits a countertrend WM1 followed by
        // H1 Super AO and H1 fractal add-ons before the D1/H4 Alligators turn.
        // A blanket D1/H4 veto here was silently suppressing a valid textbook
        // addition to an already owned/protected TC2 campaign. NTD/Course
        // legacy campaigns retain their existing separate higher-TF policy.
        if (williamsAdditionRequiresHigherFrameTrendVeto(profile)) {
            val d1=waveInfo(fetchCandles(symbol,"1d",90),"1d")
            val h4=waveInfo(fetchCandles(symbol,"4h",120),"4h")
            if(direction=="LONG" && (d1.alligatorBearish || h4.alligatorBearish) ||
                direction=="SHORT" && (d1.alligatorBullish || h4.alligatorBullish)) return
        }
        val reserved=(0 until all.length()).mapNotNull { all.optJSONObject(it) }.filter {
            it.optString("intent_role","ENTRY")=="ENTRY" && isEntryCampaignReserved(it)
        }.sumOf { futuresCampaignReservedRisk(it,all) }
        val risk=minOf(campaignAddRiskCapPct,maxRiskPerTradePct,maxTotalRiskPct-reserved,
            campaignRiskLimitPct-futuresCampaignReservedRisk(owner,all))
        if(risk<=0) return
        val account=parseFuturesAccountSnapshot(futuresCampaignCoordinator.accountSnapshot()) ?: return
        val leverage=owner.optInt("requested_leverage",0)
        if(leverage !in 1..125 || !futuresAccountFundedForEntry(account.availableUsdt,account.equityUsdt)) return
        val loss=abs(price-stop)/price+2*feeBufferPerSidePct+maxSlippagePct
        val notional=minOf(account.equityUsdt*.25,account.availableUsdt*leverage*.95,account.equityUsdt*risk/loss)
        val step=ExecutionMath.commonQuantityStep(rules.step,rules.marketStep)
        var quantity=floorStep(notional/price,step)
        val setup=runCatching { JSONObject(signal.setupContext) }.getOrDefault(JSONObject())
        if(profile==williamsTc2Profile && williamsTc2PyramidPolicy(owner)!=null) {
            val tranche=williamsTc2NextTranche(owner,all)
            tranche.blocker?.let { return rejectDecision(symbol,it) }
            val required=tranche.quantity!!
            if(required>quantity.toBigDecimal()) return rejectDecision(symbol,"TC2_PYRAMID_TRANCHE_EXCEEDS_RISK_OR_MARGIN_CAP")
            quantity=required.toDouble()
            setup.put("tc2_reverse_pyramid",JSONObject().put("policy",WILLIAMS_TC2_PYRAMID_POLICY)
                .put("tranche_number",tranche.number).put("entry_client_id",parentId)
                .put("quantity",required.toPlainString()))
        }
        val courseManagement=owner.optJSONObject("edition_management")
        if(profile==williamsCourseProfile && courseManagement?.has("course_unit_quantity")==true) {
            if(courseManagement.has("course_quantity_blocker")) return rejectDecision(symbol,"COURSE_PREVIOUS_EXACT_STAGE_FILL_UNVERIFIED")
            val context=williamsCourseActiveContext(waveHierarchyFor(symbol),direction,courseManagement.getLong("course_origin_at")) ?: return
            val targetStage=williamsCourseNextStage(courseManagement.getString("course_stage"),context.getString("phase"),true,
                context.optDouble("w3_to_w1_ratio",Double.NaN)) ?: return
            val exact=williamsCourseStageQuantity(courseManagement.opt("course_unit_quantity"),courseManagement.getString("course_stage"),
                targetStage,owner.getString("confirmed_position_qty"),rules.step,rules.marketStep,quantity,
                context.optDouble("w3_to_w1_ratio",Double.NaN))
            if(exact.optString("status")!="EXACT_COURSE_QUANTITY") return rejectDecision(symbol,exact.getString("reason"))
            if(exact.getString("action")!="ADD") return rejectDecision(symbol,"COURSE_ADDITION_MUST_INCREASE_OWNED_STAGE")
            quantity=exact.getString("quantity").toDouble()
            setup.put("course_target_stage",targetStage).put("course_target_quantity",exact.getString("target_quantity"))
                .put("course_exact_quantity",exact)
        }
        if(!quantity.isFinite() || quantity<=0) return
        if(profile in williamsExecutionProfiles) setup.put("book_h1_entry",williamsBookH1Proof(
            signal.signalId,direction,signal.type,profile,signal.signalBarTimeMs,
            (if(market) signal.triggerPrice else fmtPrice(price,rules.tick).toDouble()),
            (if(direction=="LONG") normalizePrice(signal.protectivePrice,rules.tick)
                else normalizeTriggerPrice(signal.protectivePrice,rules.tick)),
            rules.tick,parentId,stop,signal.executionSemantics,if(market) signal.triggerPrice else 0.0))
        val clientId=additionClientId(signal)
        if((0 until all.length()).any { all.optJSONObject(it)?.optString("client_id")==clientId }) return
        val plan=NativeFuturesCampaignCoordinator.EntryPlan(symbol,
            NativeFuturesCampaignCoordinator.Direction.valueOf(direction),
            if(market) NativeFuturesCampaignCoordinator.EntryKind.MARKET else NativeFuturesCampaignCoordinator.EntryKind.STOP,
            fmtQty(quantity,rules.decimals),fmtPrice(price,rules.tick),owner.getString("protective_stop"),clientId,risk,
            signalType=signal.type,signalBarTimeMs=signal.signalBarTimeMs,signalReason=signal.reason,leverage=leverage,
            strategyProfile=profile,entryClientId=parentId,executionSemantics=signal.executionSemantics,
            referenceClose=if(market) signal.triggerPrice else 0.0,setupContext=setup.toString(),signalId=signal.signalId)
        lastOrder=withCampaignMutation(symbol,"WILLIAMS_CAMPAIGN_ADDITION") {
            val submitNow=futuresMarketData.exchangeNowMs()
            check(williamsProfileAllowsSource(williamsProfile,profile)) { "ADDITION_SOURCE_PROFILE_CHANGED_BEFORE_SUBMISSION" }
            check(entryTimePolicy.allows(submitNow)) { "ENTRY_TIME_POLICY_BLOCKED_BEFORE_ADDITION" }
            check(checkFuturesDailyLoss()) { "DAILY_LOSS_OR_EQUITY_BASELINE_BLOCKED_BEFORE_ADDITION: "+lastError }
            val currentH1=fetchCandles(symbol,"1h",220)
            val fresh=editionCampaignSignals(symbol,currentH1,"1h",rules.tick,profile)
            check(fresh.any { it.signalId==signal.signalId && it.triggerPrice==signal.triggerPrice }) { "Addition formation no longer valid" }
            check(williamsTc2CongestionEntryBlocker(profile,signal.type,direction,signal.triggerPrice,
                currentH1.map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) },submitNow)==null) {
                WILLIAMS_TC2_CONGESTION_BLOCKER+": NEW ADDITION BLOCKED"
            }
            if(!market) {
                val current=futuresMarketData.price(symbol)
                check(if(direction=="LONG") current<price else current>price) { "Addition trigger already consumed" }
            }
            futuresCampaignCoordinator.submitPendingEntry(plan)
        }
        reconcileRequired=true
        persistSafetyFlag("reconcile_required",true)
    }

    private fun submitNativeFuturesCampaignEntry(candidate: BaseAnalysis) {
        try { submitNativeFuturesCampaignEntryChecked(candidate) } catch(failure:Exception) {
            rejectDecision(candidate.symbol,failure.message ?: failure.javaClass.simpleName)
            throw failure
        }
    }
    private fun submitNativeFuturesCampaignEntryChecked(candidate: BaseAnalysis) {
        val symbol=candidate.symbol
        // The operator-directed mode suppresses automatic direction selection even
        // when this scan began before the mode was armed.
        if (forceTestnetMode) return rejectDecision(symbol,"OPERATOR_DIRECTION_MODE_ACTIVE")
        // Explicit user policy gates new exposure only; ALWAYS is the default.
        // Position protection/management and explicit exits remain active 24/7.
        if (!executionReady() || !candidate.campaignReady) return rejectDecision(symbol,"RUNTIME_OR_CAMPAIGN_NOT_READY: running=$running; paused=$paused; reconcile=$reconcileRequired; "+candidate.reason)
        val sessionClockMs = runCatching { futuresMarketData.exchangeNowMs() }.getOrElse { failure ->
            lastError = "FUTURES_SESSION_CLOCK_UNAVAILABLE: " +
                (failure.message ?: failure.javaClass.simpleName)
            return rejectDecision(symbol,lastError ?: "ENTRY_GATE_BLOCKED")
        }
        if (!entryTimePolicy.allows(sessionClockMs)) {
            lastError = "ENTRY_TIME_POLICY_BLOCKED: "+entryTimePolicy.toJson()+"; protection and exits remain active"
            return rejectDecision(symbol,lastError ?: "ENTRY_GATE_BLOCKED")
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
        if (!dailyLossAllowsEntry) return rejectDecision(symbol,"DAILY_LOSS_OR_EQUITY_BASELINE_BLOCKED: "+lastError)
        if (!executionReady() || !candidate.campaignReady) return rejectDecision(symbol,"RUNTIME_OR_CAMPAIGN_NOT_READY: running=$running; paused=$paused; reconcile=$reconcileRequired; "+candidate.reason)
        if (candidate.spreadPct !in 0.0..maxSpreadPct) return rejectDecision(symbol,"SPREAD_LIMIT: ${candidate.spreadPct} > $maxSpreadPct")
        val topOfBook = runCatching { futuresMarketData.topOfBook(symbol) }.getOrElse { failure ->
            lastError = "FUTURES_ENTRY_TOP_OF_BOOK_FAILED: $symbol: " +
                (failure.message ?: failure.javaClass.simpleName)
            return rejectDecision(symbol,lastError ?: "ENTRY_GATE_BLOCKED")
        }
        val currentSpreadPct = (topOfBook.second - topOfBook.first) / topOfBook.first
        if (!currentSpreadPct.isFinite() || currentSpreadPct !in 0.0..maxSpreadPct) {
            lastError = "FUTURES_ENTRY_SPREAD_BLOCKED: $symbol live spread=" + currentSpreadPct
            return rejectDecision(symbol,lastError ?: "ENTRY_GATE_BLOCKED")
        }
        val existing=futuresCampaignCoordinator.intentsSnapshot()
        val owned=latestEntryIntentIndex(existing,symbol)?.let { existing.optJSONObject(it) }
        if(owned!=null && campaignLifecycleStatus(owned)=="OPEN_PROTECTED") {
            submitNativeFuturesAddition(candidate,owned)
            return
        }
        if(williamsProfile!=williamsUnifiedProfile && !williamsVerifiedNewRiskSource(williamsProfile)) {
            return rejectDecision(symbol,"SOURCE_OBSERVATION_ONLY: selected source lifecycle not enabled")
        }
        // Keep both directional hypotheses until D1/H4 context is known. Picking
        // the newest signal before applying the directional macro veto caused a
        // valid opposite-side alternative to be discarded prematurely.
        val contextNow = futuresMarketData.exchangeNowMs()
        fun profitReentry(signal:CampaignSignalN)=signal.strategyProfile==williamsNtdProfile &&
            williamsNtdReentryEligible(owned,symbol,campaignSignalDirection(signal.type).orEmpty(),signal.signalBarTimeMs)
        val freshSignals = candidate.campaignSignals.filter {
            williamsVerifiedNewRiskSource(it.strategyProfile) &&
                williamsProfileAllowsSource(williamsProfile,it.strategyProfile) &&
                (it.role=="ENTRY" || profitReentry(it)) &&
                (it.executionSemantics=="STOP_ENTRY" || profitReentry(it) && it.executionSemantics=="MARKET_ON_CLOSE" &&
                    williamsMarketOnCloseReason(it.signalBarTimeMs,3_600_000L,contextNow,it.triggerPrice,futuresMarketData.markPrice(symbol))==null) &&
                williamsH1SignalTimeEligible(it.signalBarTimeMs,it.type,it.strategyProfile,contextNow) &&
                !williamsH1ParentAlreadyFilled(existing,it.signalId,it.type,it.signalBarTimeMs)
        }
        if (freshSignals.isEmpty()) {
            lastError = "WILLIAMS_SIGNAL_EXPIRED: no fresh confirmed H1 hypothesis for $symbol"
            return rejectDecision(symbol,lastError ?: "ENTRY_GATE_BLOCKED")
        }

        // Project D1/H4 risk/context policy; execution uses the native H1 book trigger.
        val d1BarMs = frameSeconds("1d") * 1000L
        val h4BarMs = frameSeconds("4h") * 1000L
        val d1 = runCatching { fetchCandles(symbol,"1d",90) }.getOrDefault(emptyList())
            .filter { it.t>0L && it.t+d1BarMs<=contextNow }
        val h4 = runCatching { fetchCandles(symbol,"4h",120) }.getOrDefault(emptyList())
            .filter { it.t>0L && it.t+h4BarMs<=contextNow }
        fun htfProven(bars:List<CandleN>,frameMs:Long):Boolean =
            bars.size>=45 && bars.zipWithNext().all { (a,b) -> b.t-a.t==frameMs } &&
                contextNow-(bars.last().t+frameMs) in 0 until frameMs
        val d1Proven=htfProven(d1,d1BarMs)
        val h4Proven=htfProven(h4,h4BarMs)
        if((!d1Proven || !h4Proven) && freshSignals.any {
            williamsInitialHigherFrameHistoryRequired(it.strategyProfile)
        }) return rejectDecision(symbol,"HTF_HISTORY_UNVERIFIED_FOR_LEGACY_SOURCE: D1=$d1Proven; H4=$h4Proven")
        // An unverified higher timeframe must NEVER be silently interpreted
        // as neutral/confirmed market structure. The H1 TC2 source requires
        // no independent D1/H4 confirmation; mark both observations UNKNOWN.
        val d1Line=if(d1Proven) alligator(d1,d1.lastIndex) else null
        val h4Line=if(h4Proven) alligator(h4,h4.lastIndex) else null
        val d1Bull=d1Line!=null && d1Line.lips>d1Line.teeth && d1Line.teeth>d1Line.jaw &&
            d1.last().c>d1Line.lips && ao(d1,d1.lastIndex)>0.0
        val d1Bear=d1Line!=null && d1Line.lips<d1Line.teeth && d1Line.teeth<d1Line.jaw &&
            d1.last().c<d1Line.lips && ao(d1,d1.lastIndex)<0.0
        val h4Bull=h4Line!=null && h4Line.lips>h4Line.teeth && h4Line.teeth>h4Line.jaw &&
            h4.last().c>h4Line.lips && ao(h4,h4.lastIndex)>0.0
        val h4Bear=h4Line!=null && h4Line.lips<h4Line.teeth && h4Line.teeth<h4Line.jaw &&
            h4.last().c<h4Line.lips && ao(h4,h4.lastIndex)<0.0
        fun htfEvidence(frame:String,bars:List<CandleN>,line:TripleValues?,proven:Boolean):JSONObject =
            JSONObject().put("frame",frame).put("status",if(proven && line!=null) "PROVEN_CONTEXT" else "UNKNOWN")
                .put("closed_bar_count",bars.size).apply {
                    if(proven && line!=null) {
                        put("bar_time_ms",bars.last().t).put("close",bars.last().c)
                        put("ao",ao(bars,bars.lastIndex)).put("jaw",line.jaw)
                            .put("teeth",line.teeth).put("lips",line.lips)
                    } else {
                        put("reason","MISSING_GAPPED_OR_STALE_HTF_HISTORY")
                    }
                }
        check(executionStore.writeState("decision_context_"+symbol,JSONObject()
            .put("observed_at_ms",contextNow)
            .put("d1",htfEvidence("1d",d1,d1Line,d1Proven))
            .put("h4",htfEvidence("4h",h4,h4Line,h4Proven))
            .put("d1_bull",if(d1Proven) d1Bull else JSONObject.NULL)
            .put("d1_bear",if(d1Proven) d1Bear else JSONObject.NULL)
            .put("h4_bull",if(h4Proven) h4Bull else JSONObject.NULL)
            .put("h4_bear",if(h4Proven) h4Bear else JSONObject.NULL).toString())) {
            "H1_CONTEXT_EVIDENCE_NOT_DURABLE"
        }
        // Apply D1/H4 context before choosing between fresh confirmed H1 hypotheses.
        // H4-aligned context ranks before a reversal; signal recency breaks ties.
        val lastH1Index = candidate.candles.lastIndex
        val lastClosedAo = ao(candidate.candles, lastH1Index)
        val lastClosedAc = nativeAcFromClosedAo(
            (lastH1Index - 4..lastH1Index).map { ao(candidate.candles, it) }
        )
        fun nativeRank(type: String): Double = nativeDirectionalHeuristicRank(
            signalType = type,
            waveDirection = candidate.wave.direction,
            wavePosition = candidate.wave.position,
            exhaustionRisk = candidate.wave.exhaustionRisk,
            bullishAlligator = candidate.wave.alligatorBullish,
            bearishAlligator = candidate.wave.alligatorBearish,
            ao = lastClosedAo, ac = lastClosedAc,
            bearishDivergence = candidate.wave.aoBearishDivergence,
            bullishDivergence = candidate.wave.aoBullishDivergence
        )
        val hierarchy = waveHierarchyFor(symbol)
        val corrections = freshSignals.associate { it.signalId to williamsCorrectionEntryContext(
            hierarchy,campaignSignalDirection(it.type).orEmpty(),it.signalBarTimeMs,it.type) }
        // TC2 chapter 12 explicitly accepts any of WM1, WM2, WM3 as the
        // first presenting H1 entry. Older D1/H4 blanket trend veto wrongly
        // blocked initial H1 Super AO/fractal when a higher timeframe had not
        // yet turned. Keep D1/H4 as context/ranking evidence, not mandatory
        // confirmation. Final H1 proof, ownership, stop and risk gates remain.
        fun contextRank(signal: CampaignSignalN): Int = williamsBookInitialContextRank(
            signal.strategyProfile,signal.type,d1Bull,d1Bear,h4Bull,h4Bear,
            corrections[signal.signalId]?.allowed == true)
        // TC2 ch11 fig.11.7: g/h/j fractals inside corrective congestion are
        // not permission to create NEW risk. Numerical evidence is explicitly
        // PROJECT policy; never relabel as certain Elliott wave genealogy.
        // Screen all possible H1 Wise Men BEFORE choosing the oldest signal.
        val congestionBars=candidate.candles.map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) }
        val congestion=freshSignals.associate { signal ->
            signal.signalId to williamsTc2CongestionEvidence(congestionBars,contextNow,
                signal.type,campaignSignalDirection(signal.type).orEmpty(),signal.triggerPrice)
        }
        check(executionStore.writeState("decision_tc2_congestion_"+symbol,JSONObject()
            .put("as_of",contextNow).put("policy",WILLIAMS_TC2_CONGESTION_POLICY)
            .put("signals",JSONArray(freshSignals.map { signal ->
                JSONObject().put("signal_id",signal.signalId)
                    .put("report",congestion[signal.signalId])
            })).toString())) { "TC2_CONGESTION_DECISION_NOT_DURABLE" }
        val allowedSignals = freshSignals.filter { signal ->
            contextRank(signal)>0 &&
                (signal.strategyProfile!=williamsTc2Profile ||
                    congestion[signal.signalId]?.optBoolean("blocked",false)!=true)
        }
        if(allowedSignals.isEmpty() && congestion.values.any { it.optBoolean("blocked",false) })
            return rejectDecision(symbol,WILLIAMS_TC2_CONGESTION_BLOCKER+
                ": new entry deferred; existing position STOP and management unchanged")
        // A pending re-anchor must never hide a contradictory confirmed
        // hypothesis from another enabled Williams edition.
        if(williamsProfile==williamsUnifiedProfile && williamsUnifiedDirectionsConflict(
                allowedSignals.mapNotNull { campaignSignalDirection(it.type) })) {
            lastError="WILLIAMS_UNIFIED_DIRECTION_CONFLICT: fresh LONG and SHORT H1 entries disagree for $symbol; no order armed"
            return rejectDecision(symbol,lastError ?: "ENTRY_GATE_BLOCKED")
        }
        val storedReanchor = executionStore.readState("tc2_fractal_reanchor_$symbol")
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        val reanchorLock = if(storedReanchor!=null &&
            contextNow - storedReanchor.optLong("recorded_at_ms")>18_000_000L) {
            // Historical levels have already expired after five hours; do not
            // permanently freeze this symbol once that guard has elapsed.
            check(executionStore.writeState("tc2_fractal_reanchor_$symbol","")) {
                "Failed to expire durable TC2 re-anchor guard"
            }
            null
        } else storedReanchor
        // A previously confirmed cancellation pins the next attempt to its
        // exact new fractal. It must never fall back to a stale higher WM1/WM2.
        val selectableSignals = if (reanchorLock!=null) {
            allowedSignals.filter { signal ->
                signal.type in setOf("FRACTAL","SHORT_FRACTAL") &&
                    signal.strategyProfile==williamsTc2Profile &&
                    reanchorLock.optString("direction")==
                        campaignSignalDirection(signal.type) &&
                    reanchorLock.optString("signal_id")==signal.signalId &&
                    reanchorLock.optLong("recorded_at_ms") in 1L..contextNow &&
                    contextNow-reanchorLock.optLong("recorded_at_ms")<=18_000_000L
            }
        } else allowedSignals
        if(reanchorLock!=null && selectableSignals.isEmpty()) {
            return rejectDecision(symbol,"FRACTAL_REANCHOR_TARGET_NOT_CURRENTLY_VALID: no fallback to old level")
        }
        // Retired M15 cancel fences must not veto H1 book entries. The
        // separate TC2 H1 re-anchor fence still protects replacement orders.
        val baseline = selectableSignals.minWithOrNull(
            compareBy<CampaignSignalN> { it.signalBarTimeMs }
                .thenByDescending { contextRank(it) }.thenByDescending { nativeRank(it.type) }
        ) ?: run {
            lastError = "WILLIAMS_NO_BOOK_ELIGIBLE_H1_SIGNAL: no TC2 first-Wise-Man signal survived book and risk context on $symbol"
            return rejectDecision(symbol,lastError ?: "ENTRY_GATE_BLOCKED")
        }
        // Even with no old Binance order, prefer a newer confirmed WM3 level
        // when it has moved TOWARD current price. Do not blindly select the
        // oldest surviving Wise-Man hypothesis high above market.
        val currentContractPrice=futuresMarketData.price(symbol)
        val nearestFractal = if(reanchorLock==null && baseline.strategyProfile==williamsTc2Profile) {
            val side=campaignSignalDirection(baseline.type)
            selectableSignals.filter { alt ->
                alt.strategyProfile==williamsTc2Profile &&
                    alt.type==(if(side=="LONG") "FRACTAL" else "SHORT_FRACTAL") &&
                    alt.role=="ENTRY" && alt.signalBarTimeMs>baseline.signalBarTimeMs &&
                    alt.protectivePrice>0 && alt.triggerPrice.isFinite() &&
                    (if(side=="LONG") alt.triggerPrice>currentContractPrice &&
                        alt.triggerPrice<baseline.triggerPrice && alt.protectivePrice<alt.triggerPrice
                     else alt.triggerPrice<currentContractPrice &&
                        alt.triggerPrice>baseline.triggerPrice && alt.protectivePrice>alt.triggerPrice)
            }.maxByOrNull { it.signalBarTimeMs }
        } else null
        val signal = nearestFractal ?: baseline
        if(nearestFractal!=null) runCatching { auditStore.recordCockpitEvent(
            "FRACTAL_REANCHOR",symbol,campaignSignalDirection(signal.type).orEmpty(),"NO_EXISTING_ORDER",
            "old="+baseline.signalId+" @"+baseline.triggerPrice+
                "; newest confirmed WM3="+signal.signalId+" @"+signal.triggerPrice) }
        val correction = corrections[signal.signalId] ?: WilliamsCorrectionContext()
        val direction = when (campaignSignalDirection(signal.type)) {
            "LONG" -> NativeFuturesCampaignCoordinator.Direction.LONG
            "SHORT" -> NativeFuturesCampaignCoordinator.Direction.SHORT
            else -> {
                lastError = "WILLIAMS_SIGNAL_DIRECTION_UNKNOWN: refusing to default an unknown signal to LONG for $symbol"
                return
            }
        }

        // The selected book source and closed H1 bars authorize the entry.
        // No M15 candle, fractal or M15 re-anchor is required.
        val entries = futuresCampaignCoordinator.intentsSnapshot()
        if(williamsH1ParentAlreadyFilled(entries,signal.signalId,signal.type,signal.signalBarTimeMs))
            return rejectDecision(symbol,"H1_PARENT_ALREADY_FILLED: no repeated initial entry")
        val retryGeneration=williamsBookH1RetryGeneration(entries,signal.signalId)
            ?: return rejectDecision(symbol,"H1_PRIOR_ATTEMPT_FILL_OR_CANCELLATION_UNVERIFIED")
        val clientId = williamsBookH1ClientId(symbol,signal.signalId,retryGeneration)
        if ((0 until entries.length()).any { entries.optJSONObject(it)?.optString("client_id") == clientId })
            return rejectDecision(symbol,"FORMATION_ALREADY_ADMITTED: "+clientId)
        val currentH1=fetchCandles(symbol,"1h",220)
        if(williamsBookH1TriggerConsumed(direction.name,signal.signalBarTimeMs,signal.triggerPrice,
                contextNow,currentH1.map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) }))
            return rejectDecision(symbol,"H1_BOOK_TRIGGER_ALREADY_CONSUMED: no late return to old breakout")
        val closedH1=currentH1.filter { it.t>0 && it.t+WILLIAMS_H1_MS<=contextNow }
        val h1Teeth=williamsBookH1ActivationTeeth(closedH1.map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) },contextNow)
        williamsBookFractalTeethBlocker(signal.type,direction.name,signal.triggerPrice,h1Teeth,symbolFilters(symbol).tick)?.let {
            return rejectDecision(symbol,it)
        }

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
            return rejectDecision(symbol,lastError ?: "ENTRY_GATE_BLOCKED")
        }

        val riskUsed = (0 until entries.length()).mapNotNull { entries.optJSONObject(it) }.sumOf { intent ->
            if (intent.optString("intent_role", "ENTRY") == "ENTRY" && isEntryCampaignReserved(intent)) {
                // Missing/corrupt risk is not zero risk. Reserve the full portfolio cap
                // conservatively so a damaged journal can never authorize excess exposure.
                conservativeRiskReservation(
                    futuresCampaignReservedRisk(intent,entries),
                    maxTotalRiskPct
                )
            } else 0.0
        }.coerceAtMost(maxTotalRiskPct)
        val initialCap=if(signal.strategyProfile==williamsTc2Profile)
            williamsTc2InitialUnitRisk(campaignInitialRiskPct,campaignRiskLimitPct,maxRiskPerTradePct)
            else campaignInitialRiskPct
        val riskPct = min(initialCap, min(maxRiskPerTradePct, maxTotalRiskPct - riskUsed))
        if (riskPct <= 0.0) return rejectDecision(symbol,"PORTFOLIO_RISK_EXHAUSTED: used=$riskUsed; limit=$maxTotalRiskPct")
        val account = futuresCampaignCoordinator.accountSnapshot()
        val parsedAccount = parseFuturesAccountSnapshot(account) ?: run {
            updateCachedFuturesAccount(account)
            lastError = restAccountLastError ?: "Futures account snapshot is invalid"
            return rejectDecision(symbol,lastError ?: "ENTRY_GATE_BLOCKED")
        }
        val equity = parsedAccount.equityUsdt
        val available = parsedAccount.availableUsdt
        if (!futuresAccountFundedForEntry(available, equity)) return rejectDecision(symbol,"ACCOUNT_FUNDS_NOT_PROVEN")
        val mark = futuresMarketData.markPrice(symbol)
        val rules = symbolFilters(symbol)
        val contractPrice = futuresMarketData.price(symbol)
        // TC2 WM1 / WM2 / WM3 and NTD / Course preserve their H1 book
        // signal bar trigger, independent of any junior execution fractal.
        val marketReentry=profitReentry(signal) && signal.executionSemantics=="MARKET_ON_CLOSE"
        val reference=signal.triggerPrice
        val trigger = if(marketReentry) williamsNtdCloseEntryBound(direction.name,reference,rules.tick)
            ?: return rejectDecision(symbol,"NTD_CLOSE_BOUND_UNVERIFIED")
            else if (direction == NativeFuturesCampaignCoordinator.Direction.LONG)
                normalizeTriggerPrice(reference, rules.tick) else normalizePrice(reference, rules.tick)
        var stop = if (direction == NativeFuturesCampaignCoordinator.Direction.LONG)
            normalizePrice(signal.protectivePrice, rules.tick) else normalizeTriggerPrice(signal.protectivePrice, rules.tick)
        if(signal.strategyProfile==williamsCourseProfile) {
            val higher=closedWilliamsWaveBars(fetchCandles(symbol,"6h",100).map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) },"6h",contextNow)
            val airbag=williamsCourseAirbagStop(direction.name,higher,rules.tick) ?: return rejectDecision(symbol,"REQUIRED_ENTRY_EVIDENCE_UNAVAILABLE")
            val combined=williamsCourseInitialProtection(direction.name,stop,airbag,trigger)
                ?: return rejectDecision(symbol,"COURSE_FORMATION_OR_AIRBAG_ALREADY_INVALIDATED")
            stop=if(direction.name=="LONG") normalizePrice(combined,rules.tick)
                else normalizeTriggerPrice(combined,rules.tick)
        }
        if (trigger <= 0.0 || stop <= 0.0) return rejectDecision(symbol,"INVALID_TRIGGER_OR_STOP")
        if (direction == NativeFuturesCampaignCoordinator.Direction.LONG) {
            if (!marketReentry && trigger <= contractPrice || stop >= trigger) return rejectDecision(symbol,"LONG_TRIGGER_CONSUMED_OR_STOP_INVALID: contract=$contractPrice; trigger=$trigger; stop=$stop")
        } else {
            if (!marketReentry && trigger >= contractPrice || stop <= trigger) return rejectDecision(symbol,"SHORT_TRIGGER_CONSUMED_OR_STOP_INVALID: contract=$contractPrice; trigger=$trigger; stop=$stop")
        }
        val stopFraction = kotlin.math.abs(trigger - stop) / trigger
        if (!stopFraction.isFinite() || stopFraction <= 0.0) return rejectDecision(symbol,"INVALID_STRUCTURAL_RISK_DISTANCE")
        val effectiveLoss = stopFraction + 2.0 * feeBufferPerSidePct + maxSlippagePct
        val maxRiskQuote = equity * riskPct
        val entryLeverage = futuresLeverageSettings.forSymbol(symbol)
        val notional = min(equity * 0.25, min(available * entryLeverage * 0.95, maxRiskQuote / effectiveLoss))
        // STOP_MARKET entries become market executions when triggered. Size them
        // to the common exact increment of LOT_SIZE and MARKET_LOT_SIZE; using
        // LOT_SIZE alone can leave a perfectly valid pending order that Binance
        // rejects or cannot execute when its trigger fires.
        val marketLotEnabled = rules.marketStep.isFinite() && rules.marketStep > 0.0
        val quantityStep = ExecutionMath.commonQuantityStep(rules.step, rules.marketStep)
        val minQuantity = if (marketLotEnabled) max(rules.minQty, rules.marketMinQty) else rules.minQty
        val maxQuantity = if (marketLotEnabled) min(rules.maxQty, rules.marketMaxQty) else rules.maxQty
        var qty = floorStep(notional / trigger, quantityStep)
        val courseInitial=if(signal.strategyProfile==williamsCourseProfile) {
            val context=williamsCourseActiveContext(hierarchy,direction.name)
            if(context?.optString("phase")!="W1") return rejectDecision(symbol,"COURSE_INITIAL_PARENT_CHILD_DEGREE_UNPROVEN")
            val exact=williamsCourseInitialQuantity(qty,rules.step,rules.marketStep)
            if(exact.optString("status")!="EXACT_COURSE_QUANTITY") return rejectDecision(symbol,exact.getString("reason"))
            qty=exact.getString("quantity").toDouble()
            exact
        } else null
        if (!qty.isFinite() || qty < minQuantity || qty > maxQuantity ||
            !ExecutionMath.isMultiple(qty, rules.step) ||
            (marketLotEnabled && !ExecutionMath.isMultiple(qty, rules.marketStep)) ||
            qty * trigger < rules.minNotional || qty * trigger > rules.maxNotional) {
            lastError = "FUTURES_QUANTITY_FILTER_BLOCKED: $symbol quantity does not satisfy LOT_SIZE and MARKET_LOT_SIZE"
            return rejectDecision(symbol,lastError ?: "ENTRY_GATE_BLOCKED")
        }

        val plan = NativeFuturesCampaignCoordinator.EntryPlan(
            symbol = symbol,
            direction = direction,
            kind = if(marketReentry) NativeFuturesCampaignCoordinator.EntryKind.MARKET else NativeFuturesCampaignCoordinator.EntryKind.STOP,
            quantity = fmtQty(qty, rules.decimals),
            entryPrice = fmtPrice(trigger, rules.tick),
            protectiveStop = fmtPrice(stop, rules.tick),
            clientId = clientId,
            riskPct = riskPct,
            signalType = signal.type,
            signalId = signal.signalId,
            reentryOwnerId=if(profitReentry(signal)) owned!!.getString("client_id") else "",
            referenceClose=if(marketReentry) signal.triggerPrice else 0.0,
            leverage = entryLeverage,
            signalBarTimeMs = signal.signalBarTimeMs,
            signalReason = "BOOK H1 "+signal.reason+"; "+direction.name+
                " stop-entry at H1 signal trigger (no M15 confirmation)",
            tradeMode = if (correction.allowed) "CORRECTION" else "TREND",
            waveContext = if (correction.allowed) correction.toJson().toString() else "",
            strategyProfile=signal.strategyProfile,executionSemantics=signal.executionSemantics,
            setupContext=(runCatching { JSONObject(signal.setupContext) }.getOrDefault(JSONObject()).apply {
                put("parent_execution_semantics",signal.executionSemantics)
                if(signal.strategyProfile==williamsTc2Profile)
                    put("tc2_reverse_pyramid",JSONObject().put("policy",WILLIAMS_TC2_PYRAMID_POLICY))
                put("book_h1_entry",williamsBookH1Proof(signal.signalId,direction.name,
                    signal.type,signal.strategyProfile,signal.signalBarTimeMs,
                    (if(marketReentry) reference else trigger),stop,rules.tick,
                    executionSemantics=signal.executionSemantics,referenceClose=if(marketReentry) reference else 0.0,
                    reentryOwnerId=if(profitReentry(signal)) owned!!.getString("client_id") else ""))
                if(signal.strategyProfile==williamsCourseProfile) {
                    put("course_exact_quantity",courseInitial)
                    williamsCourseActiveContext(hierarchy,direction.name)?.let { context ->
                        put("course_origin_at",context.getLong("origin_at")).put("course_initial_stage",context.getString("phase"))
                        if(!context.isNull("w3_to_w1_ratio")) put("course_w3_to_w1_ratio",context.getDouble("w3_to_w1_ratio"))
                    }
                }
            }).toString()
        )
        runCatching { auditStore.recordCockpitEvent("SIGNAL_RISK", symbol, direction.name, signal.type,
            "${signal.reason}; H1 bar=${signal.signalBarTimeMs}; trigger=$trigger; stop=$stop; qty=$qty; reserved_risk=$riskPct") }
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
            require(williamsProfileAllowsSource(williamsProfile,signal.strategyProfile)) {
                "Williams source is no longer enabled after analysis"
            }
            val submitNow = futuresMarketData.exchangeNowMs()
            if (!entryTimePolicy.allows(submitNow)) {
                return@withCampaignMutation JSONObject()
                    .put("accepted", false)
                    .put("safe_rejection", true)
                    .put("mutation_attempted", false)
                    .put("blocker", "ENTRY_TIME_POLICY_BLOCKED: "+entryTimePolicy.toJson()+"")
            }
            require(williamsH1SignalTimeEligible(signal.signalBarTimeMs,signal.type,signal.strategyProfile,submitNow)) {
                "STALE_H1_SCENARIO: signal expired before Futures submission"
            }
            val verifiedParents=h1ContextCampaignSignals(symbol,fetchCandles(symbol,"1h",220),rules.tick)
            check(verifiedParents.any { it.signalId==signal.signalId && it.type==signal.type &&
                abs(it.triggerPrice-signal.triggerPrice)<=rules.tick*.1 &&
                abs(it.protectivePrice-signal.protectivePrice)<=rules.tick*.1 }) {
                "H1_BOOK_SIGNAL_NO_LONGER_VALID: no Futures order submitted"
            }
            val submitH1=fetchCandles(symbol,"1h",220)
            check(marketReentry || !williamsBookH1TriggerConsumed(direction.name,signal.signalBarTimeMs,trigger,
                submitNow,submitH1.map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) })) {
                "H1_BOOK_BREAKOUT_ALREADY_CONSUMED_BEFORE_SUBMISSION"
            }
            // Regression from 2026-10-25 15:00:00 UTC: Binance can still
            // return the PREVIOUS H1 forming/closed bar during the first
            // milliseconds of a new hour. The final coordinator verifier
            // correctly refuses this, but without a preflight the coordinator
            // has already persisted an ENTRY_PENDING / SUBMISSION_UNKNOWN
            // intent and then latches RECONCILE_REQUIRED even though NO
            // exchange entry request was sent. Check the same raw current bar
            // BEFORE creating an intent or changing exchange leverage.
            // This veto is never permission to bypass the final H1 verifier.
            if (!marketReentry) {
                val rawH1Result=runCatching { futuresMarketData.klines(symbol,"1h",2) }
                val rawH1=rawH1Result.getOrNull()
                val recentBars=runCatching {
                    (0 until (rawH1?.length() ?: 0)).map { idx ->
                        val row=rawH1!!.getJSONArray(idx)
                        WilliamsWaveBar(row.getLong(0),row.getDouble(1),row.getDouble(2),
                            row.getDouble(3),row.getDouble(4),row.getDouble(5))
                    }
                }.getOrDefault(emptyList())
                val gateAt=futuresMarketData.exchangeNowMs()
                val rawBlocker=williamsBookH1ObservedBreakoutBlocker(direction.name,
                    signal.signalBarTimeMs,trigger,gateAt,
                    submitH1.map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) },recentBars)
                if (rawBlocker!=null) {
                    return@withCampaignMutation JSONObject()
                        .put("accepted",false).put("safe_rejection",true)
                        .put("mutation_attempted",false).put("order_mutation_attempted",false)
                        .put("blocker",if(rawBlocker=="H1_CURRENT_BAR_OBSERVATION_UNVERIFIED")
                            "H1_CURRENT_BAR_NOT_PUBLISHED_YET_DEFER_ENTRY" else rawBlocker)
                        .put("raw_h1_fetch_error",rawH1Result.exceptionOrNull()?.javaClass?.simpleName ?: JSONObject.NULL)
                }
            }
            val submitH1Closed=submitH1.filter { it.t>0 && it.t+WILLIAMS_H1_MS<=submitNow }
            val submitTeeth=williamsBookH1ActivationTeeth(submitH1Closed.map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) },submitNow)
            check(williamsBookFractalTeethBlocker(signal.type,direction.name,trigger,submitTeeth,rules.tick)==null) {
                "H1_FRACTAL_TEETH_INVALID_BEFORE_SUBMISSION"
            }
            if (correction.allowed) {
                val currentCorrection = williamsCorrectionEntryContext(waveHierarchyFor(symbol,refresh=true),
                    direction.name,signal.signalBarTimeMs,signal.type)
                require(currentCorrection.allowed && currentCorrection.parentId == correction.parentId) {
                    "CORRECTION_CONTEXT_INVALIDATED: parent proof changed before exchange submission"
                }
                val live = futuresMarketData.markPrice(symbol)
                require(williamsCorrectionPriceAllowed(currentCorrection.toJson(),live)) {
                    "CORRECTION_PARENT_PRICE_INVALIDATED: mark crossed parent boundary"
                }
            }
            // Revalidate the H1 trade-price trigger against CONTRACT_PRICE
            // after network/door waits. Never convert a consumed stop-entry into a late
            // entry at the current market price.
            val submitMark = futuresMarketData.markPrice(symbol)
            val submitContractPrice = futuresMarketData.price(symbol)
            require(
                if(marketReentry) williamsMarketOnCloseReason(signal.signalBarTimeMs,3_600_000L,submitNow,signal.triggerPrice,submitMark)==null
                else if (direction == NativeFuturesCampaignCoordinator.Direction.LONG) submitContractPrice < trigger
                else submitContractPrice > trigger
            ) {
                "H1_BOOK_TRIGGER_ALREADY_CROSSED: $symbol contract=$submitContractPrice trigger=$trigger; pending entry not submitted"
            }
            check(williamsBookH1ProofBlocker(plan.setupContext,direction.name,signal.signalId,
                signal.type,signal.strategyProfile,trigger,stop,submitNow)==null) {
                "H1_BOOK_PROOF_INVALID_BEFORE_SUBMISSION"
            }
            futuresCampaignCoordinator.submitPendingEntry(plan)
        }
        if(!result.optBoolean("accepted",false)) rejectDecision(symbol,result.optString("blocker","EXECUTION_DOOR_REJECTED"))
        runCatching { auditStore.recordCockpitEvent("ORDER_RESPONSE", symbol, direction.name,
            if (result.optBoolean("accepted")) "ACCEPTED" else "UNCONFIRMED_OR_REJECTED", detail = result.optString("blocker", result.optString("error"))) }
        lastOrder = result
        if (!result.optBoolean("accepted", false)) {
            lastError = result.optString("blocker", result.optString("error"))
            if (futuresCampaignCoordinator.state == NativeFuturesCampaignCoordinator.State.RECONCILE_REQUIRED) {
                reconcileRequired = true
                persistSafetyFlag("reconcile_required", true)
                stateMachine.force(TradingState.RECONCILE_REQUIRED, lastError ?: "Futures submission unresolved")
            }
        } else {
            check(executionStore.writeState("mtf_reanchor_"+symbol,"")) { "Cannot retire migrated legacy M15 fence" }
            // A successfully persisted new Futures intent supersedes the
            // one-shot re-anchor hint; its own client id remains authoritative.
            if(reanchorLock!=null && signal.signalId==reanchorLock.optString("signal_id")) {
                check(executionStore.writeState("tc2_fractal_reanchor_$symbol","")) {
                    "Failed to clear consumed fractal replacement hint"
                }
            }
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

        // Read-only H1 discovery NEVER starts a signed account-wide reconciliation.
        // Scheduling management here put the coordinator into RECONCILE_REQUIRED
        // during every scan and suppressed all ten otherwise-valid TC2 entries.
        val universe = loadUniverse()
        scanSymbols = universe.first.toMutableList()
        val volumes = universe.second
        val spreads = universe.third
        val coarseMetadataMissing=coreSymbols.filter { symbol ->
            (volumes[symbol] ?: 0.0)<=0.0 || spreads[symbol]==null
        }
        val symbolErrors = java.util.concurrent.ConcurrentHashMap<String, String>()
        val futures = scanSymbols.map { symbol ->
            scanExecutor.submit(Callable {
                var stage = "FETCH_H1_CLOSED_CANDLES"
                try {
                    // The validated H1 startup history has 150 closed bars.
                    // Do not trigger extra REST traffic just to demand 180.
                    val candles = fetchCandles(symbol, "1h", 150)
                    if (candles.size < 45) throw IllegalStateException("Insufficient closed H1 candles: " + candles.size)
                    stage = "ANALYSE_TC2_NTD_COURSE_H1"
                    val result = analyseBase(symbol, candles, spreads[symbol] ?: -1.0, volumes[symbol] ?: 0.0)
                    markScanProgress()
                    result
                } catch (x: Exception) {
                    markScanProgress()
                    symbolErrors[symbol] = h1ScannerExceptionDetail(stage, x)
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
                .take(20).forEach { analysis ->
                    val row=toJson(analysis)
                    val h1=analysis.candles.map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) }
                    // Every scanned Futures symbol receives the same read-only
                    // regime diagnostics, including symbols without an entry.
                    // This is NOT a claim about exchange-owned positions.
                    val assessment=williamsTc2CongestionEvidence(h1,System.currentTimeMillis(),
                        "FRACTAL","LONG",h1.lastOrNull()?.c ?: Double.NaN)
                    row.put("h1_correction_regime",assessment.put("entry_relevant",false)
                        .put("blocked",false))
                    row.put("h1_correction_signal_checks",JSONArray(
                        analysis.campaignSignals.filter { it.strategyProfile==williamsTc2Profile &&
                            it.role=="ENTRY" }.map { signal ->
                            JSONObject().put("signal_id",signal.signalId)
                                .put("classification",williamsTc2CongestionEvidence(h1,
                                    System.currentTimeMillis(),signal.type,
                                    campaignSignalDirection(signal.type).orEmpty(),signal.triggerPrice))
                        }))
                    put(row)
                }
        }
        lastSymbolsScanned = ranked.size
        lastScannerSymbolErrors = JSONObject().apply {
            symbolErrors.toSortedMap().forEach { (symbol,reason) -> put(symbol,reason) }
        }
        lastScanAt = System.currentTimeMillis()
        scanRunId = "futures-" + lastScanAt
        runCatching { PositionsWidgetProvider.refresh(context) }
        runCatching { primaryCandles = fetchCandles(primarySymbol, "1h", 150) }

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
            // H1 detection remains independent of coarse volume/spread REST
            // outages. But NEVER create a Futures order while any approved
            // symbol has unverified market metadata.
            marketDataFresh = marketDataFresh() && coarseMetadataMissing.isEmpty(),
            coordinatorState = coordinatorStateForCompletion,
            coordinatorReadyForEntry = campaignCoordinatorReadyForEntry()
        )
        val missing = coreSymbols - ranked.map { it.symbol }.toSet()
        // A valid read-only scan can overlap an unrelated Binance recovery
        // and be temporarily blocked by the coordinator's in-flight marker.
        // Preserve the entry fence, but recheck promptly after it finishes.
        if (completeScan && executionBlocker == "BLOCKED_FUTURES_RECONCILIATION" &&
            futuresReconciliationRunning && !reconcileRequired) {
            scanRetryAfterReconciliation = true
        }
        scannerState = when {
            !completeScan && ranked.isEmpty() -> "FAILED"
            !completeScan && executionBlocker != null -> "PARTIAL_BLOCKED"
            !completeScan -> "PARTIAL"
            executionBlocker != null -> "READY_BLOCKED"
            else -> "READY"
        }
        scannerError = when {
            !completeScan && symbolErrors.isNotEmpty() ->
                "Не удалось завершить H1-анализ: " + symbolErrors.entries
                    .sortedBy { it.key }.take(3)
                    .joinToString("; ") { it.key + ": " + it.value } +
                    (if (symbolErrors.size>3) "; и ещё " + (symbolErrors.size-3) + " пар" else "")
            !completeScan && ranked.isEmpty() ->
                "Сканер не завершил анализ ни одной пары: " + missing.joinToString(",")
            executionBlocker != null -> {
                when (executionBlocker) {
                    "BLOCKED_KILL" -> "Kill switch is latched; read-only market scan completed, new entries remain disabled"
                    "BLOCKED_FUTURES_RECONCILIATION" -> futuresCampaignCoordinator.lastBlocker
                        ?: lastError
                        ?: "Futures positions/orders/protection reconciliation is required"
                    "PAUSED" -> lastError ?: "Runtime is paused; read-only market scan completed"
                    "BLOCKED_ACCOUNT" -> restAccountLastError ?: "Futures account snapshot is not ready; entries remain disabled"
                    "WAITING_FOR_MARKET_DATA" -> if(coarseMetadataMissing.isNotEmpty())
                        "Read-only H1 analysis completed; new entries blocked by unverified Binance quotes/volume: " +
                            coarseMetadataMissing.joinToString(",") +
                            (scannerMarketMetadataWarning?.let { "; $it" } ?: "")
                        else restLastError ?: "Futures market data is stale; entries remain disabled"
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
        if (executionBlocker != null) { coreSymbols.forEach { rejectDecision(it,executionBlocker) };return }
        if (!completeScan) { coreSymbols.forEach { rejectDecision(it,"SCAN_INCOMPLETE: "+missing.joinToString(",")) };return }
        if (!restAccountReady || !marketDataFresh()) { coreSymbols.forEach { rejectDecision(it,"ACCOUNT_OR_MARKET_DATA_NOT_READY") };return }
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
        ranked.filter { it.symbol in openSymbols }.forEach { rejectDecision(it.symbol,"SYMBOL_RESERVED_BY_POSITION_OR_PENDING_ORDER") }
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

    private fun cancelPendingEntriesOutsideWilliamsSession() {
        val exchangeNow = runCatching { futuresMarketData.exchangeNowMs() }.getOrElse { failure ->
            lastError = "FUTURES_SESSION_CLOCK_UNAVAILABLE_FOR_PENDING_CANCEL: " +
                (failure.message ?: failure.javaClass.simpleName)
            return
        }
        if (entryTimePolicy.allows(exchangeNow)) return

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
            intent.optString("intent_role", "ENTRY") in setOf("ENTRY","ADD_ON") &&
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
                futuresCampaignCoordinator.cancelPendingEntries("OUTSIDE_CONFIGURED_ENTRY_TIME")
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
                var reconciliationPasses=0
                while (true) {
                    reconciliationPasses++
                    val connectionGenerationForAttempt = synchronized(this) {
                        futuresStreamReconcilePending = false
                        userStreamConnectionGeneration
                    }
                    val result = reconcileFuturesThroughDoor(
                        "USER_STREAM_RECONCILIATION",
                        allowStopped = true,
                        allowKillLatched = true
                    )
                    if (result.optBoolean("deferred_to_active_reconciliation", false)) {
                        // No signed REST proof was taken. Surface lock contention
                        // to diagnostics, but do not call it a Binance auth error
                        // or persist a new kill/reconcile latch. The normal
                        // monitoring worker will retry after its bounded backoff.
                        userStreamReconcileFailure =
                            "PRIVATE_SIGNED_REST_SYNC_DEFERRED_ACCOUNT_LOCK; RETRY_PENDING"
                        break
                    }
                    check(futuresSignedSnapshotComplete(result)) {
                        result.optString("blocker", "Futures reconciliation returned not-ok")
                    }
                    val repeat = synchronized(this) {
                        if (futuresStreamReconcilePending) {
                            // A concurrent fill/STOP update invalidates this
                            // snapshot for the current live stream. Bound replay
                            // to avoid a hot loop that starves periodic position
                            // management and Android liveness checks.
                            userStreamSyncRequired = true
                            if(privateStreamReconciliationMayRepeat(true,reconciliationPasses)) {
                                true
                            } else {
                                userStreamReconcileFailure =
                                    "PRIVATE_STREAM_EVENTS_ARRIVED_DURING_SIGNED_SYNC; BOUNDED_RETRY_PENDING"
                                false
                            }
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
        if (eventType in setOf("ORDER_TRADE_UPDATE", "ALGO_UPDATE", "CONDITIONAL_ORDER_TRIGGER_REJECT")) {
            val order = event.optJSONObject("o") ?: event
            runCatching { auditStore.recordCockpitEvent("USER_WS", symbol = order.optString("s"),
                state = eventType, detail = "status=" + order.optString("X") + "; executed_qty=" + order.optString("z")) }
        }
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
        // On-grid decimal source values must not lose a whole tick because
        // Double division produces N - 1 ulp (BTC/ETH SHORT WM3 incident).
        return ExecutionMath.floorToStep(price, tick)
    }

    private fun normalizeTriggerPrice(price: Double, tick: Double): Double {
        if (price <= 0.0 || tick <= 0.0) return price
        return ExecutionMath.ceilToStep(price, tick)
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

    private fun editionCampaignSignals(symbol:String,candles:List<CandleN>,frame:String,tick:Double,
        profile:String=williamsUnifiedProfile,exitMode:Boolean=false):List<CampaignSignalN> {
        val bookSeriesBlocker = williamsEditionSignalSeriesBlocker(
            candles.map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) }, frame
        )
        if(bookSeriesBlocker != null) {
            // A gap across two supposed adjacent AO/fractal confirmation bars
            // cannot become an apparently normal TC2 signal. Invalid history
            // is NOT equivalent to a valid scan with no candidate.
            if(profile==williamsUnifiedProfile && !exitMode) for(source in williamsSourceProfiles) {
                executionStore.writeState("edition_observation_"+symbol+"_"+source,
                    JSONObject().put("profile", source).put("execution_role",
                        if(williamsVerifiedNewRiskSource(source)) "LIVE" else "OBSERVE")
                        .put("state", "HISTORY_INVALID")
                        .put("reason", bookSeriesBlocker)
                        .put("as_of_close_ms", candles.lastOrNull()?.let { it.t+WILLIAMS_H1_MS } ?: 0L)
                        .put("signal_count", 0).put("can_place_orders", false).toString())
            }
            return emptyList()
        }
        if(profile==williamsUnifiedProfile) {
            // One causal H1 snapshot, three independent rule extractors. Preserve
            // each source on its own signal for replay, journal and risk controls.
            return williamsSourceProfiles.flatMap { source ->
                val result=runCatching { editionCampaignSignals(symbol,candles,frame,tick,source,exitMode) }
                if(!exitMode) executionStore.writeState("edition_observation_"+symbol+"_"+source,JSONObject()
                    .put("profile",source).put("execution_role",if(williamsVerifiedNewRiskSource(source)) "LIVE" else "OBSERVE")
                    .put("state",if(result.isFailure) "ANALYSIS_FAILED" else if(result.getOrNull().isNullOrEmpty()) "NO_VALID_FORMATION" else "FORMATION_OBSERVED")
                    .put("error_type",result.exceptionOrNull()?.javaClass?.simpleName ?: JSONObject.NULL)
                    .put("as_of_close_ms",candles.lastOrNull()?.let { it.t+WILLIAMS_H1_MS } ?: 0L)
                    .put("signal_count",result.getOrNull()?.size ?: 0).put("can_place_orders",false).toString())
                result.getOrDefault(emptyList())
            }.distinctBy { it.signalId }.sortedWith(
                compareBy<CampaignSignalN> { it.signalBarTimeMs }.thenBy { it.signalId })
        }
        if(profile==williamsTc2Profile) return longCampaignSignals(symbol,candles,frame,tick)+shortCampaignSignals(symbol,candles,frame,tick)
        if(profile !in williamsSourceProfiles || frame!="1h" || candles.size<45) return emptyList()
        val bars=candles.map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) }
        if(!validWilliamsEditionBars(bars) || bars.zipWithNext().any { (a,b) -> b.t-a.t!=3_600_000L }) return emptyList()
        val jaw=williamsAlligatorLine(candles,13,8).map { it ?: Double.NaN }
        val teeth=williamsAlligatorLine(candles,8,5).map { it ?: Double.NaN }
        val lips=williamsAlligatorLine(candles,5,3).map { it ?: Double.NaN }
        val aos=candles.indices.map { ao(candles,it) }
        val acs=candles.indices.map { i -> if(i>=4) nativeAcFromClosedAo(aos.subList(i-4,i+1)) else Double.NaN }
        val rhythm=williamsCourseRhythm(bars)
        return listOf("LONG","SHORT").flatMap { side ->
            val direction=if(side=="LONG") "UP" else "DOWN"
            val setups=if(profile==williamsNtdProfile) williamsNtdSetups(side,bars,jaw,teeth,lips,aos,acs,tick,exitMode)
                else listOfNotNull(williamsCourseHumpSetup(side,bars,tick),williamsCourseThumbSetup(side,bars,tick),williamsCourseSquatSetup(side,bars,tick)).filter {
                    exitMode || when(it.kind) {
                        "COURSE_HUMP" -> rhythm.second==direction
                        "COURSE_SQUAT" -> rhythm.first==direction && rhythm.second==direction
                        else -> true
                    }
                }
            setups.filter { (profile==williamsNtdProfile && it.kind=="FRACTAL") ||
                bars.last().t-bars[it.knownIndex].t<18_000_000L }.map { setup ->
                val type=(if(side=="SHORT") "SHORT_" else "")+setup.kind
                val whenMs=bars[setup.knownIndex].t
                val id=if(setup.kind=="BALANCE_LINE") "$profile:$symbol:$frame:$side:BALANCE_LINE:${setup.context.getString("formation_key")}" else profile+":"+campaignSignalId(symbol,frame,type,whenMs)
                CampaignSignalN(id,type,setup.role,whenMs,
                    setup.trigger,setup.stop,teeth.last(),setup.invalidation,profile+": "+setup.kind,
                    profile,setup.executionSemantics,setup.context.toString(),
                    setup.context.optInt("formation_index",setup.context.optInt("base_index",setup.context.optInt("start_index",setup.context.optInt("thumb_index",setup.knownIndex))))
                        .takeIf { it in bars.indices }?.let { bars[it].t } ?: whenMs)
            }
        }.distinctBy { it.signalId }.sortedBy { it.signalBarTimeMs }
    }

    private fun longCampaignSignals(
        symbol: String,
        candles: List<CandleN>,
        frame: String,
        tick: Double,
        // The SHORT scanner reuses mirrored geometry; reflected floating
        // OHLC are not exchange grid prices and must not be grid-validated.
        sourceExchangePrices: Boolean = true
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

        val waveBars = candles.map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) }
        fun angulationProof(i:Int)=williamsAngulation("LONG",waveBars.take(i+1),
            jawS.take(i+1),teethS.take(i+1),lipsS.take(i+1),
            expectedStepMs=if(frame=="1h") WILLIAMS_H1_MS else null)

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
            val priorLow = candles[i - 1].l
            // TC2 PDF125 expects prior-trend AO colour; PDF131 adds that
            // without that momentum context this is NOT a countertrend trade.
            // Conservative source-qualified WM1 gate remains mandatory.
            val wm1AoBlocker=williamsFirstWiseManAoBlocker(
                "LONG",ao(candles,i),ao(candles,i-1))
            if (wm1AoBlocker==null &&
                validWilliamsReversalBar("LONG",waveBars[i],priorLow,
                    listOf(jawS[i],teethS[i],lipsS[i]),angulationProof(i).score)) {
                val levels=if(sourceExchangePrices)
                    exactTc2WiseManPriceBarLevels("LONG",waveBars[i],tick)
                    else Tc2WiseManPriceBarLevels(candles[i].h+tick,candles[i].l-tick,
                        candles[i].h,candles[i].l)
                if(levels==null) continue
                val trigger=levels.trigger
                if (triggerStillPending(i, trigger)) {
                    out += CampaignSignalN(
                        signalId = campaignSignalId(symbol, frame, "REVERSAL", candles[i].t),
                        type = "REVERSAL",
                        role = "ENTRY",
                        signalBarTimeMs = candles[i].t,
                        triggerPrice = trigger,
                        protectivePrice = levels.protective,
                        teethAtDetection = teethS[i].takeIf { it.isFinite() } ?: 0.0,
                        invalidationPrice = levels.protective,
                        reason = "WM1 bullish reversal with mouth-anchored angulation; waiting above signal-bar high",
                        setupContext = JSONObject()
                            .put("angulation",angulationProof(i).toJson())
                            .put("wm1_ao_color",when {
                                !ao(candles,i).isFinite() || !ao(candles,i-1).isFinite() -> "UNAVAILABLE"
                                ao(candles,i)<ao(candles,i-1) -> "RED"
                                ao(candles,i)>ao(candles,i-1) -> "GREEN"
                                else -> "UNCHANGED"
                            })
                            .put("wm1_ao_opposite_color_observed",
                                ao(candles,i).isFinite() && ao(candles,i-1).isFinite() &&
                                ao(candles,i)<ao(candles,i-1))
                            .put("wm1_ao_qualifies_book_countertrend",true)
                            .put("wm1_ao_color_is_mandatory",true)
                            .put("wm1_ao_rule_reference","TC2_PDF125_AND_131_COUNTERTREND_CONTEXT")
                            .toString()
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
                val sourceLevel=if(sourceExchangePrices)
                    exactTc2WiseManPriceBarLevels("LONG",waveBars[signalIndex],tick)
                    else Tc2WiseManPriceBarLevels(candles[signalIndex].h+tick,
                        candles[signalIndex].l-tick,candles[signalIndex].h,candles[signalIndex].l)
                if (sourceLevel!=null) {
                    val trigger=sourceLevel.trigger
                    val structuralStop = williamsTc2SuperAoInitialStop(
                        "LONG",waveBars,signalIndex,tick
                    )
                    if (structuralStop != null &&
                        abs(structuralStop-sourceLevel.protective)<=tick*.001 &&
                        structuralStop<trigger && triggerStillPending(signalIndex, trigger)) {
                        out += CampaignSignalN(
                        signalId = campaignSignalId(symbol, frame, "SUPER_AO", candles[signalIndex].t),
                        type = "SUPER_AO",
                        role = "ENTRY",
                        signalBarTimeMs = candles[signalIndex].t,
                        triggerPrice = trigger,
                        protectivePrice = structuralStop,
                        teethAtDetection = teethS[signalIndex].takeIf { it.isFinite() } ?: 0.0,
                        invalidationPrice = structuralStop,
                        reason = "WM2 Super AO: third rising AO bar; initial stop behind its signal price bar",
                        setupContext = JSONObject().put("initial_stop_rule","TC2_SUPER_AO_SIGNAL_BAR")
                            .put("initial_stop_window",1).put("third_ao_bar_at_ms",candles[signalIndex].t)
                            .put("risk_stop_at_price",structuralStop).toString()
                        )
                    }
                }
            }
        }

        // WM3: most recent confirmed buy fractal. The pivot defines the level, but
        // the signal timestamp is the second right-hand bar close when the
        // five-bar fractal first becomes knowable. Never backdate a decision.
        val fractal = selectWilliamsPendingFractal("LONG",waveBars,teethS,tick)
        if (fractal != null) {
            val exact=if(sourceExchangePrices)
                exactTc2LongFractalLevels(waveBars,
                    candles[fractal.center].t,candles[fractal.groupStart].t,
                    candles[fractal.confirmation].t,tick)
                else Tc2LongFractalSourceLevels(fractal.trigger,fractal.protective,
                    waveBars[fractal.center].h,waveBars[fractal.center].l)
            // A mirrored selection or binary rounding is not an authority
            // for an exchange tick; refuse on an actual source disagreement.
            if(exact!=null &&
                (!sourceExchangePrices || (abs(exact.trigger-fractal.trigger)<=tick*.001 &&
                    abs(exact.protective-fractal.protective)<=tick*.001)) &&
                triggerStillPending(fractal.center,exact.trigger)) {
                out += CampaignSignalN(
                    signalId=campaignSignalId(symbol,frame,"FRACTAL",candles[fractal.center].t),
                    type="FRACTAL",role="ENTRY",signalBarTimeMs=candles[fractal.confirmation].t,
                    triggerPrice=exact.trigger,protectivePrice=exact.protective,
                    teethAtDetection=fractal.currentTeeth,invalidationPrice=exact.protective,
                    reason="WM3 eligible confirmed fractal outside Teeth; waiting for actual trigger",
                    setupContext=JSONObject()
                        .put("wise_man","WM3")
                        .put("signal_family","CONFIRMED_FRACTAL")
                        .put("fractal_center_time_ms",candles[fractal.center].t)
                        .put("fractal_group_start_time_ms",candles[fractal.groupStart].t)
                        .put("fractal_confirmation_time_ms",candles[fractal.confirmation].t)
                        .put("teeth_filter_verified",true)
                        .apply { if(sourceExchangePrices) {
                            put("book_price_source","ORIGINAL_EXCHANGE_OHLC")
                                .put("book_pivot_high",exact.pivotHigh)
                                .put("book_stop_window_low",exact.stopWindowLow)
                                .put("book_tick",tick)
                                .put("book_trigger_exact",exact.trigger)
                                .put("book_protective_exact",exact.protective)
                        } }.toString(),
                    formationAtMs=candles[fractal.center].t)
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
        val sourceBars = candles.map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) }
        return longCampaignSignals(symbol, reflected, frame, tick,
            sourceExchangePrices=false).mapNotNull { signal ->
            // Reflection determines the BOOK signal geometry only; the original
            // Binance candle, not a reflected Double, determines monetary ticks.
            val sourceFractal = if (signal.type == "FRACTAL") {
                val proof = runCatching { JSONObject(signal.setupContext) }.getOrNull()
                    ?: return@mapNotNull null
                exactTc2ShortFractalLevels(sourceBars,
                    proof.optLong("fractal_center_time_ms", -1L),
                    proof.optLong("fractal_group_start_time_ms", -1L),
                    proof.optLong("fractal_confirmation_time_ms", -1L), tick)
                    ?: return@mapNotNull null
            } else null
            val sourceBarLevels=if(signal.type in setOf("REVERSAL","SUPER_AO")) {
                // WM1: reversal price bar. WM2: the third directional AO bar.
                // Both are identified by the original closed bar time.
                val originalBar=sourceBars.singleOrNull { it.t==signal.signalBarTimeMs }
                    ?: return@mapNotNull null
                exactTc2WiseManPriceBarLevels("SHORT",originalBar,tick)
                    ?: return@mapNotNull null
            } else null
            if(sourceFractal==null && sourceBarLevels==null)
                return@mapNotNull null
            val sourceTrigger=sourceFractal?.trigger ?: sourceBarLevels!!.trigger
            val sourceStop=sourceFractal?.protective ?: sourceBarLevels!!.protective
            val reflectedTrigger = reflection - signal.triggerPrice
            val reflectedStop = reflection - signal.protectivePrice
            // Reflected prices are only consistency evidence; never the order
            // price. Even WM1/WM2 may be one tick wrong after floating division.
            if(abs(reflectedTrigger-sourceTrigger)>tick*.001 ||
                abs(reflectedStop-sourceStop)>tick*.001)
                return@mapNotNull null
            val trigger=sourceTrigger
            val stop=sourceStop
            // A short entry must trigger below current price and its protective stop
            // must be above entry. Invalid reflected levels are rejected, never guessed.
            val current = candles.last().c
            if (!trigger.isFinite() || !stop.isFinite() ||
                trigger <= 0.0 || stop <= trigger || trigger >= current
            ) return@mapNotNull null
            val sourceContext = JSONObject(reflectedTc2SetupContext(signal.type, signal.setupContext, reflection))
            sourceContext.put("short_price_source","ORIGINAL_EXCHANGE_OHLC")
                .put("book_tick",tick)
                .put("book_trigger_exact",trigger)
                .put("book_protective_exact",stop)
            if(sourceFractal!=null) sourceContext
                .put("book_pivot_low",sourceFractal.pivotLow)
                .put("book_stop_window_high",sourceFractal.stopWindowHigh)
            if(sourceBarLevels!=null) sourceContext
                .put("book_source_bar_high",sourceBarLevels.sourceHigh)
                .put("book_source_bar_low",sourceBarLevels.sourceLow)
                .put("book_source_bar_time_ms",signal.signalBarTimeMs)
                .apply { if(has("risk_stop_at_price")) put("risk_stop_at_price",stop) }
            signal.copy(
                signalId = campaignSignalId(
                    symbol, frame, "SHORT_" + signal.type, signal.formationAtMs
                ),
                type = "SHORT_" + signal.type,
                triggerPrice = trigger,
                protectivePrice = stop,
                teethAtDetection = reflection-signal.teethAtDetection,
                setupContext = sourceContext.toString(),
                invalidationPrice = stop,
                reason = signal.reason.replace("bullish","bearish").replace("above signal-bar high","below signal-bar low")
                    .replace("rising AO","falling AO").replace("above price bar","below price bar")
            )
        }.distinctBy { it.signalId }.sortedBy { it.signalBarTimeMs }
    }

    private fun h1EntryParent(signal:CampaignSignalN)=WilliamsH1Parent(signal.signalId,
        campaignSignalDirection(signal.type).orEmpty(),signal.signalBarTimeMs,signal.triggerPrice,signal.protectivePrice,
        williamsH1SignalExpiry(signal.signalBarTimeMs,signal.type,signal.strategyProfile).takeIf { it>0L } ?: Long.MAX_VALUE)

    /** Reconstruct causal H1 prefixes so crossing the old H1 trigger does not erase the parent
     * while it waits for M15. Structural invalidation and the original five-hour expiry remain. */
    private fun h1ContextCampaignSignals(symbol:String,input:List<CandleN>,tick:Double):List<CampaignSignalN> {
        val now=futuresMarketData.exchangeNowMs()
        val bars=input.filter { it.t>0 && it.t+WILLIAMS_H1_MS<=now }
        if(williamsClosedH1HistoryBlocker(bars.map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) },now)!=null) return emptyList()
        val contractPrice=futuresMarketData.price(symbol)
        val out=mutableListOf<CampaignSignalN>()
        for(omitted in 0..minOf(4,bars.size-45)) {
            val prefix=bars.take(bars.size-omitted)
            val signals=editionCampaignSignals(symbol,prefix,"1h",tick,if(omitted==0) williamsUnifiedProfile else williamsExecutionProfile)
            out+=signals.filter { signal ->
                if(signal.strategyProfile!=williamsTc2Profile || signal.role!="ENTRY") omitted==0 &&
                    (signal.strategyProfile!=williamsNtdProfile || signal.executionSemantics=="MARKET_ON_CLOSE" ||
                        williamsH1ParentInvalidation(h1EntryParent(signal),
                            bars.map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) },contractPrice,now)==null)
                else williamsH1SignalTimeEligible(signal.signalBarTimeMs,signal.type,signal.strategyProfile,now) &&
                    williamsH1ParentInvalidation(h1EntryParent(signal),
                        bars.map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) },contractPrice,now)==null
            }
        }
        val oppositeCandidates=h1OppositionCandidates(symbol,bars,tick,williamsProfile)
        val sourceEvidence=oppositeCandidates.map { scenarioSignal(it) }
        val waveBars=bars.map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) }
        val lifecycle=JSONArray()
        val valid=out.distinctBy { it.signalId }.filter { signal ->
            if(signal.strategyProfile!=williamsExecutionProfile || signal.role!="ENTRY") return@filter true
            val parent=h1EntryParent(signal)
            val assessment=assessWilliamsScenarioOpposition(parent.direction,parent.knownAt,
                sourceEvidence.filter { it.profile==signal.strategyProfile },waveBars,contractPrice,now)
            lifecycle.put(JSONObject().put("parent_id",parent.signalId).put("direction",parent.direction)
                .put("state",if(assessment.blocker==null) "VALID_CONTEXT" else if(assessment.opposition!=null) "REVOKED" else "UNVERIFIED")
                .put("reason",assessment.blocker ?: "NO_LATER_TRIGGERED_OPPOSITION")
                .put("opposition",assessment.opposition?.toJson() ?: JSONObject.NULL)
                .put("uncertain",JSONArray(assessment.uncertain.map { it.toJson() })))
            assessment.blocker==null
        }.sortedBy { it.signalBarTimeMs }
        check(executionStore.writeState("decision_h1_parent_lifecycle_"+symbol,JSONObject()
            .put("observed_at_ms",now).put("trigger_price_basis","CONTRACT_PRICE").put("parents",lifecycle).toString())) { "H1_LIFECYCLE_PROOF_NOT_DURABLE" }
        return valid
    }

    private fun courseTrimPlan(owner:JSONObject):JSONObject {
        val management=owner.optJSONObject("edition_management") ?: JSONObject()
        fun blocked(reason:String)=JSONObject().put("status","BLOCKED").put("reason",reason)
        if(owner.optString("strategy_profile")!=williamsCourseProfile ||
            management.optString("course_unit_basis")!="CONFIRMED_INITIAL_FILL")
            return blocked("COURSE_ACTUAL_UNIT_BASIS_UNVERIFIED")
        val symbol=owner.getString("symbol");val now=futuresMarketData.exchangeNowMs()
        val bars=fetchCandles(symbol,"1h",260).map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) }
            .filter { it.t+WILLIAMS_H1_MS<=now }
        if(williamsClosedH1HistoryBlocker(bars,now)!=null) return blocked("COURSE_TRIM_H1_HISTORY_UNVERIFIED")
        val proof=williamsCourseTrimProof(waveHierarchyFor(symbol,refresh=true).toJson(),owner.getString("direction"),
            management.optLong("course_origin_at"),management.optString("course_stage"),
            owner.optLong("position_open_observed_at_ms"),now,bars)
        if(proof.optString("status")!="OBSERVED_SOURCE_PINNED_REDUCTION") return proof
        val setup=runCatching { JSONObject(owner.optString("setup_context")) }.getOrDefault(JSONObject())
        val rules=symbolFilters(symbol)
        val exact=williamsCourseStageQuantity(management.opt("course_unit_quantity"),management.optString("course_stage"),
            proof.getString("stage"),owner.optString("confirmed_position_qty"),rules.step,rules.marketStep,
            w3ToW1Ratio=management.optDouble("course_w3_to_w1_ratio",setup.optDouble("course_w3_to_w1_ratio",Double.NaN)))
        if(exact.optString("status")!="EXACT_COURSE_QUANTITY") return blocked(exact.getString("reason"))
        if(exact.getString("action")!="REDUCE") return blocked("COURSE_TRIM_MUST_REDUCE_OWNED_STAGE")
        val profit=williamsTc2OppositeExitProfitBlocker(owner.getString("direction"),
            owner.optString("actual_entry_price").toDoubleOrNull() ?: Double.NaN,futuresMarketData.markPrice(symbol),
            2*feeBufferPerSidePct+maxSlippagePct,rules.tick)
        if(profit!=null) return blocked(profit.replace("TC2_OPPOSITE_EXIT","COURSE_STAGE_TRIM"))
        return proof.put("target_quantity",exact.getString("target_quantity").toDouble())
            .put("reduction_quantity",exact.getString("quantity").toDouble()).put("exact_quantity",exact)
    }

    private fun verifyCourseTrimBeforeSubmit(owner:JSONObject,quantity:String) {
        val proof=courseTrimPlan(owner)
        check(proof.optString("status")=="OBSERVED_SOURCE_PINNED_REDUCTION") { proof.optString("reason","COURSE_SOURCE_TRIM_UNVERIFIED") }
        val management=owner.optJSONObject("edition_management") ?: error("COURSE_MANAGEMENT_MISSING")
        val requested=quantity.toBigDecimalOrNull()
        check(management.optString("course_pending_stage")==proof.getString("stage") && requested!=null &&
            requested.signum()>0 && requested.compareTo(proof.getJSONObject("exact_quantity").getString("quantity").toBigDecimal())==0 &&
            proof.getDouble("target_quantity")>0 && proof.getDouble("target_quantity")<owner.getString("confirmed_position_qty").toDouble()) {
            "COURSE_SOURCE_TRIM_STAGE_OR_QUANTITY_CHANGED"
        }
    }

    private fun courseCorrectionHandoff(owner:JSONObject):JSONObject {
        val management=owner.optJSONObject("edition_management") ?: JSONObject()
        val motiveId=management.optString("course_motive_owner_id")
        val all=futuresCampaignCoordinator.intentsSnapshot()
        val previous=(0 until all.length()).mapNotNull { all.optJSONObject(it) }.singleOrNull {
            it.optString("client_id")==motiveId && it.optString("intent_role","ENTRY")=="ENTRY" }
        val proof=previous?.optJSONObject("edition_management")?.optJSONObject("course_flat_handoff")
        check(previous!=null && campaignLifecycleStatus(previous)=="CLOSED_OR_STOPPED" && previous.optString("symbol")==owner.optString("symbol") &&
            previous.optString("direction")!=owner.optString("direction") && previous.optString("strategy_profile")==williamsCourseProfile &&
            previous.optString("close_reason")=="COURSE_W5_TERMINAL_CONFIRMED" && proof!=null &&
            proof.optString("source")=="SIGNED_FLAT_COURSE_MOTIVE" && proof.optString("motive_campaign_id")==motiveId &&
            proof.optLong("flat_confirmed_at_ms")>0 && proof.optLong("flat_confirmed_at_ms")<owner.optLong("position_open_observed_at_ms") &&
            previous.optString("course_correction_consumed_by")==owner.optString("client_id")) {
            "COURSE_CORRECTION_DURABLE_FLAT_HANDOFF_UNVERIFIED"
        }
        return JSONObject(proof!!.toString()).put("tick",symbolFilters(owner.getString("symbol")).tick)
    }

    private fun courseExitObservation(owner:JSONObject):JSONObject {
        check(owner.optString("strategy_profile")==williamsCourseProfile) { "COURSE_EXIT_OWNER_UNVERIFIED" }
        fun blocked(why:String)=JSONObject().put("status","BLOCKED").put("blocker",why)
        val management=owner.optJSONObject("edition_management") ?: JSONObject()
        if(management.optString("course_unit_basis")!="CONFIRMED_INITIAL_FILL") return blocked("COURSE_ACTUAL_UNIT_BASIS_UNVERIFIED")
        val stage=management.optString("course_stage");val symbol=owner.getString("symbol")
        val correction=if(stage in setOf("A_ENTRY","A_REDUCE","C_ADD")) courseCorrectionHandoff(owner) else JSONObject()
        val setup=runCatching { JSONObject(owner.optString("setup_context")) }.getOrDefault(JSONObject())
        val rules=symbolFilters(symbol)
        williamsCourseOwnedQuantityBlocker(management.opt("course_unit_quantity"),stage,owner.optString("confirmed_position_qty"),
            rules.step,rules.marketStep,management.optDouble("course_w3_to_w1_ratio",setup.optDouble("course_w3_to_w1_ratio",Double.NaN)))
            ?.let { return blocked(it) }
        val now=futuresMarketData.exchangeNowMs()
        val bars=fetchCandles(symbol,"1h",260).map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) }.filter { it.t+WILLIAMS_H1_MS<=now }
        if(williamsClosedH1HistoryBlocker(bars,now)!=null) return blocked("COURSE_EXIT_H1_HISTORY_UNVERIFIED")
        return williamsCourseExitPlan(waveHierarchyFor(symbol,refresh=true).toJson(),owner.getString("direction"),
            management.optLong("course_origin_at"),stage,owner.optLong("position_open_observed_at_ms"),now,bars,futuresMarketData.price(symbol),correction)
    }

    private fun courseExitSourceProof(owner:JSONObject,reason:String):JSONObject {
        check(owner.optString("strategy_profile")==williamsCourseProfile && reason in williamsCourseBookExitReasons && reason!="COURSE_STAGE_TRIM") {
            "COURSE_EXIT_REQUIRES_OWN_BOOK_RULE"
        }
        val symbol=owner.getString("symbol");val direction=owner.getString("direction")
        val now=futuresMarketData.exchangeNowMs();val tick=symbolFilters(symbol).tick
        val proof=if(reason in williamsCourseTerminalReasons+williamsCourseFailureReasons) {
            courseExitObservation(owner).also {
                check(it.optString("status")=="OBSERVED_COURSE_SOURCE_RULE" && it.optString("reason")==reason) {
                    it.optString("blocker","COURSE_SOURCE_EXIT_NO_LONGER_VALID") }
            }
        } else {
            val candles=fetchCandles(symbol,"1h",260).filter { it.t+WILLIAMS_H1_MS<=now }
            check(williamsClosedH1HistoryBlocker(candles.map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) },now)==null) { "COURSE_EXIT_H1_HISTORY_UNVERIFIED" }
            val contract=futuresMarketData.price(symbol)
            check(contract.isFinite() && contract>0) { "COURSE_EXIT_LIVE_CONTRACT_UNVERIFIED" }
            if(reason=="COURSE_HIGHER_FRAME_AIRBAG_TRIGGERED") {
                val higher=closedWilliamsWaveBars(fetchCandles(symbol,"6h",80).map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) },"6h",now)
                check(now-(higher.last().t+21_600_000L) in 0 until 21_600_000L) { "COURSE_AIRBAG_HISTORY_UNVERIFIED" }
                val boundary=williamsCourseAirbagStop(direction,higher,tick) ?: error("COURSE_AIRBAG_HISTORY_UNVERIFIED")
                check(if(direction=="LONG") contract<=boundary else contract>=boundary) { "COURSE_AIRBAG_NO_LONGER_CROSSED" }
                JSONObject().put("rule_id","COURSE:AIRBAG:${higher.last().t}").put("boundary",boundary)
            } else {
                val signal=triggeredOpposingSignal(symbol,NativeFuturesCampaignCoordinator.Direction.valueOf(direction),candles,tick,contract,
                    owner.optLong("position_open_observed_at_ms"),williamsCourseProfile) ?: error("COURSE_OPPOSITE_SOURCE_TRIGGER_UNVERIFIED")
                check(signal.strategyProfile==williamsCourseProfile && signal.role=="ENTRY" && signal.executionSemantics=="STOP_ENTRY" &&
                    campaignSignalDirection(signal.type)!=direction) { "COURSE_OPPOSITE_SOURCE_TRIGGER_UNVERIFIED" }
                JSONObject().put("rule_id","COURSE:OPPOSITION:${signal.signalId}").put("signal_id",signal.signalId).put("boundary",signal.triggerPrice)
            }
        }
        if(reason in williamsCourseTerminalReasons+williamsCourseOppositeReasons) {
            val blocker=williamsTc2OppositeExitProfitBlocker(direction,owner.optString("actual_entry_price").toDoubleOrNull() ?: Double.NaN,
                futuresMarketData.markPrice(symbol),2*feeBufferPerSidePct+maxSlippagePct,tick)
            check(blocker==null) { blocker!!.replace("TC2_OPPOSITE_EXIT","COURSE_DISCRETIONARY_EXIT") }
        }
        return proof.put("reason",reason).put("owner_id",owner.getString("client_id")).put("symbol",symbol).put("side",direction)
            .put("observed_at_ms",now).put("trigger_price_basis","CONTRACT_PRICE")
    }

    private fun verifyCourseExitBeforeSubmit(owner:JSONObject,reason:String) {
        val old=owner.optJSONObject("edition_management")?.optJSONObject("exit_evidence")
        val blocker=williamsCourseExitProofBlocker(old,owner.getString("client_id"),owner.getString("symbol"),owner.getString("direction"),
            reason,owner.optLong("position_open_observed_at_ms"),futuresMarketData.exchangeNowMs())
        check(blocker==null) { blocker!! }
        val fresh=courseExitSourceProof(owner,reason)
        val sameBoundary=old!!.has("boundary")==fresh.has("boundary") &&
            (!old.has("boundary") || old.optDouble("boundary",Double.NaN)==fresh.optDouble("boundary",Double.NaN))
        check(old.optString("rule_id")==fresh.optString("rule_id") && sameBoundary) {
            "COURSE_EXIT_SOURCE_CHANGED_BEFORE_SUBMIT"
        }
    }

    private fun verifyNtdExitBeforeSubmit(owner:JSONObject,reason:String) {
        check(owner.optString("strategy_profile")==williamsNtdProfile && reason in williamsNtdBookExitReasons) {
            "NTD_EXIT_OWNER_OR_RULE_CHANGED"
        }
        val symbol=owner.getString("symbol");val direction=owner.getString("direction")
        val now=futuresMarketData.exchangeNowMs();val tick=symbolFilters(symbol).tick
        val candles=fetchCandles(symbol,"1h",220).filter { it.t>0 && it.t+WILLIAMS_H1_MS<=now }
        val bars=candles.map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) }
        check(williamsClosedH1HistoryBlocker(bars,now)==null) { "NTD_EXIT_H1_HISTORY_UNVERIFIED" }
        if(reason in setOf("TEETH_CLOSE_EXIT","LIPS_CLOSE_EXIT","NTD_FIVE_ZONE_PROTECTION_CROSSED")) {
            val aos=candles.indices.map { ao(candles,it) }
            val acs=candles.indices.map { i -> if(i>=4) nativeAcFromClosedAo(aos.subList(i-4,i+1)) else Double.NaN }
            val plan=williamsNtdManagementPlan(direction,bars,
                williamsAlligatorLine(candles,8,5).map { it ?: Double.NaN },
                williamsAlligatorLine(candles,5,3).map { it ?: Double.NaN },aos,acs,tick,
                owner.optLong("position_open_observed_at_ms"),owner.optJSONObject("edition_management") ?: JSONObject())
            if(reason=="NTD_FIVE_ZONE_PROTECTION_CROSSED") {
                val stop=plan.stop ?: error("NTD_ZONE_PROTECTION_NOT_ACTIVE")
                val mark=futuresMarketData.markPrice(symbol)
                check(if(direction=="LONG") mark<=stop else mark>=stop) { "NTD_ZONE_STOP_NO_LONGER_CROSSED" }
            } else check(plan.exitReason==reason) { "NTD_CLOSED_LINE_EXIT_NO_LONGER_VALID" }
        } else {
            val signal=triggeredOpposingSignal(symbol,NativeFuturesCampaignCoordinator.Direction.valueOf(direction),
                candles,tick,futuresMarketData.price(symbol),owner.optLong("position_open_observed_at_ms"),williamsNtdProfile)
                ?: error("NTD_OPPOSITE_TRIGGER_NO_LONGER_VALID")
            val proof=owner.optJSONObject("edition_management")?.optJSONObject("exit_evidence")
            check(signal.strategyProfile==williamsNtdProfile && proof!=null && proof.optString("reason")==reason &&
                proof.optString("signal_id")==signal.signalId) { "NTD_OPPOSITE_SOURCE_PROOF_CHANGED" }
        }
    }

    /** Final non-M15 book-rule check executed by the coordinator AFTER
     * exchange leverage confirmation and immediately BEFORE Binance POST.
     * Historic M15 entry verifier stays available only for recovery.
     */
    private fun verifyBookH1PlanBeforeSubmit(plan:NativeFuturesCampaignCoordinator.EntryPlan) {
        check(!forceTestnetMode) { "OPERATOR_DIRECTION_MODE_ACTIVE_BEFORE_SUBMISSION" }
        val now=futuresMarketData.exchangeNowMs()
        val tick=symbolFilters(plan.symbol).tick
        if(plan.reversalOwnerId.isNotBlank()) {
            check(plan.strategyProfile==williamsTc2Profile && williamsProfileAllowsSource(williamsProfile,williamsTc2Profile)) {
                "TC2_REVERSAL_SOURCE_CHANGED"
            }
            val c=williamsTc2ReversalCandidate(futuresCampaignCoordinator.intentsSnapshot(),plan.reversalOwnerId,now,plan.clientId)
                ?: error("TC2_REVERSAL_DURABLE_HANDOFF_UNVERIFIED")
            val bars=fetchCandles(plan.symbol,"1h",220).filter { it.t>0 && it.t+WILLIAMS_H1_MS<=now }
            check(williamsClosedH1HistoryBlocker(bars.map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) },now)==null) {
                "TC2_REVERSAL_H1_HISTORY_UNVERIFIED"
            }
            val signal=h1OppositionCandidates(plan.symbol,bars,tick,williamsTc2Profile).singleOrNull {
                it.signalId==plan.signalId && it.type==plan.signalType && it.signalBarTimeMs==plan.signalBarTimeMs &&
                    abs(it.triggerPrice-c.getDouble("trigger"))<=tick*.11 &&
                    abs(it.protectivePrice-plan.protectiveStop.toDouble())<=tick*.11
            } ?: error("TC2_REVERSAL_H1_SIGNAL_CHANGED")
            val teeth=williamsBookH1ActivationTeeth(bars.map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) },now)
            check(williamsBookFractalTeethBlocker(signal.type,plan.direction.name,signal.triggerPrice,teeth,tick)==null) {
                "TC2_REVERSAL_FRACTAL_TEETH_INVALID"
            }
            // A confirmed FLAT reversal is still NEW directional exposure.
            // Existing position close/reconciliation was executed separately;
            // this check cannot hold back the old protective exit.
            check(williamsTc2CongestionEntryBlocker(williamsTc2Profile,signal.type,
                plan.direction.name,signal.triggerPrice,
                bars.map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) },now)==null) {
                WILLIAMS_TC2_CONGESTION_BLOCKER+": REVERSAL_NEW_EXPOSURE"
            }
            return
        }
        val target=plan.entryPrice.toDouble()
        val stop=plan.protectiveStop.toDouble()
        check(williamsProfileAllowsSource(williamsProfile,plan.strategyProfile)) { "H1_BOOK_EDITION_CHANGED" }
        check(williamsBookH1ProofBlocker(plan.setupContext,plan.direction.name,plan.signalId,
            plan.signalType,plan.strategyProfile,target,stop,now,plan.entryClientId,plan.reentryOwnerId,
            plan.executionSemantics,plan.referenceClose)==null) { "H1_BOOK_PROOF_UNVERIFIED" }
        val candles=fetchCandles(plan.symbol,"1h",220)
        val signals=h1ContextCampaignSignals(plan.symbol,candles,tick)
        val actual=signals.singleOrNull { it.signalId==plan.signalId &&
            it.type==plan.signalType && it.strategyProfile==plan.strategyProfile &&
            williamsBookSignalRoleAllowed(it.strategyProfile,it.type,it.role,plan.entryClientId,plan.reentryOwnerId) &&
            campaignSignalDirection(it.type)==plan.direction.name }
            ?: error(h1ParentRejectionReason(plan.symbol,plan.signalId,"H1_BOOK_PARENT_NO_LONGER_VALID"))
        // Re-evaluate H1 congestion at the final Binance mutation fence.
        // Applies only to new exposure (entries/additions), not owned STOPs
        // or discretionary/protective exits.
        check(williamsTc2CongestionEntryBlocker(plan.strategyProfile,plan.signalType,
            plan.direction.name,plan.entryPrice.toDouble(),
            candles.map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) },now)==null) {
            WILLIAMS_TC2_CONGESTION_BLOCKER
        }
        if(plan.executionSemantics=="MARKET_ON_CLOSE") {
            check(plan.strategyProfile==williamsNtdProfile && actual.executionSemantics=="MARKET_ON_CLOSE" &&
                abs(actual.triggerPrice-plan.referenceClose)<=tick*.11) { "NTD_CLOSE_EVENT_CHANGED" }
            return // coordinator independently verifies fresh contract/mark AND executable bid/ask bound
        }
        val expected=if(plan.direction.name=="LONG")
            normalizeTriggerPrice(actual.triggerPrice,tick)
            else normalizePrice(actual.triggerPrice,tick)
        check(abs(expected-target)<=tick*.11) { "H1_BOOK_ENTRY_PRICE_CHANGED" }
        val expectedStop=if(plan.direction.name=="LONG")
            normalizePrice(actual.protectivePrice,tick)
            else normalizeTriggerPrice(actual.protectivePrice,tick)
        if(plan.entryClientId.isBlank()) check(if(plan.direction.name=="LONG") stop>=expectedStop-tick*.11
              else stop<=expectedStop+tick*.11) { "H1_BOOK_PROTECTIVE_STOP_WIDENED" }
        // fetchCandles deliberately excludes the forming bar. Fetch its raw
        // extremes separately so a breakout followed by an intrahour retreat
        // cannot revive a consumed H1 entry (including a campaign addition).
        val recent=futuresMarketData.klines(plan.symbol,"1h",2)
        val recentBars=(0 until recent.length()).map { index ->
            val row=recent.getJSONArray(index)
            WilliamsWaveBar(row.getLong(0),row.getDouble(1),row.getDouble(2),
                row.getDouble(3),row.getDouble(4),row.getDouble(5))
        }
        val breakoutBlocker=williamsBookH1ObservedBreakoutBlocker(plan.direction.name,
            plan.signalBarTimeMs,target,futuresMarketData.exchangeNowMs(),
            candles.map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) },recentBars)
        check(breakoutBlocker==null) { breakoutBlocker ?: "H1_BREAKOUT_UNVERIFIED" }
        val closed=candles.filter { it.t>0 && it.t+WILLIAMS_H1_MS<=now }
        val teeth=williamsBookH1ActivationTeeth(closed.map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) },
            futuresMarketData.exchangeNowMs())
        check(williamsBookFractalTeethBlocker(plan.signalType,plan.direction.name,target,teeth,tick)==null) {
            "H1_BOOK_FRACTAL_NO_LONGER_OUTSIDE_TEETH"
        }
        val contractPrice=futuresMarketData.price(plan.symbol)
        check(if(plan.direction.name=="LONG") contractPrice<target else contractPrice>target) {
            "H1_BOOK_PENDING_BREAKOUT_ALREADY_CROSSED"
        }
    }

    private fun verifyMtfPlanBeforeSubmit(plan:NativeFuturesCampaignCoordinator.EntryPlan) {
        check(!forceTestnetMode) { "OPERATOR_DIRECTION_MODE_ACTIVE_BEFORE_SUBMISSION" }
        val now=futuresMarketData.exchangeNowMs()
        val evidence=williamsM15EntryFromJson(JSONObject(plan.setupContext).getJSONObject("mtf_entry"))
            ?: error("M15_ENTRY_PROOF_UNREADABLE")
        val parents=h1ContextCampaignSignals(plan.symbol,fetchCandles(plan.symbol,"1h",220),evidence.tick)
        check(parents.any { it.signalId==plan.signalId && campaignSignalDirection(it.type)==plan.direction.name }) {
            h1ParentRejectionReason(plan.symbol,plan.signalId,"H1_PARENT_INVALIDATED_BEFORE_SUBMISSION")
        }
        val candles=fetchCandles(plan.symbol,"15m",220).filter { it.t>0 && it.t+WILLIAMS_M15_MS<=now }
        val fresh=m15EntryDecision(plan.symbol,evidence.parent,candles,evidence.tick,now).entry
            ?: error("M15_ENTRY_NO_LONGER_VALID_BEFORE_SUBMISSION")
        check(fresh.pivotAt==evidence.pivotAt && fresh.confirmedAt==evidence.confirmedAt &&
            abs(fresh.trigger-plan.entryPrice.toDouble())<=evidence.tick*.1 &&
            (if(plan.direction.name=="LONG") plan.protectiveStop.toDouble()>=fresh.stop
                else plan.protectiveStop.toDouble()<=fresh.stop)) { "M15_ENTRY_CHANGED_BEFORE_SUBMISSION" }
    }

    private fun h1ParentRejectionReason(symbol:String,parentId:String,fallback:String):String {
        val rows=runCatching { JSONObject(executionStore.readState("decision_h1_parent_lifecycle_"+symbol).orEmpty())
            .getJSONArray("parents") }.getOrNull() ?: return fallback
        return (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.firstOrNull {
            it.optString("parent_id")==parentId && it.optString("state")!="VALID_CONTEXT"
        }?.optString("reason")?.takeIf { it.isNotBlank() } ?: fallback
    }

    private fun m15EntryDecision(symbol:String,parent:WilliamsH1Parent,candles:List<CandleN>,tick:Double,now:Long):WilliamsM15Decision {
        val bars=candles.map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) }
        val teeth=williamsAlligatorLine(candles,8,5).map { it ?: Double.NaN }
        val result=selectWilliamsH1M15Entry(parent,bars,teeth,tick,futuresMarketData.markPrice(symbol),now)
        check(executionStore.writeState("decision_m15_entry_"+symbol,JSONObject()
            .put("mode",WILLIAMS_MTF_ENTRY_MODE).put("observed_at_ms",now).put("parent",parent.toJson())
            .put("reason",result.reason).put("entry",result.entry?.toJson() ?: JSONObject.NULL)
            .put("candles",JSONArray(candles.takeLast(160).map { listOf(it.t,it.o,it.h,it.l,it.c,it.v) }))
            .toString())) { "M15_DECISION_PROOF_NOT_DURABLE" }
        return result
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
            h1ContextCampaignSignals(symbol,workCandles,campaignTick)
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
                campaignSignals.any { williamsVerifiedNewRiskSource(it.strategyProfile) && williamsProfileAllowsSource(williamsProfile,it.strategyProfile) } &&
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
            campaignReady = campaignEngineEnabled && campaignSignals.any {
                williamsVerifiedNewRiskSource(it.strategyProfile) && williamsProfileAllowsSource(williamsProfile,it.strategyProfile) }
        )
    }

    /**
     * Demand-load only a bounded REAL Binance child window if the observed
     * cycle would otherwise have fewer than ~100 bars on the parent frame.
     * Background-only, rate-limited; never grants entry/exit permission.
     */
    private fun requestMissingAdaptiveWaveFrames(
        symbol: String, report: JSONObject, cutoff: Long
    ) {
        if(!running) return
        val rows=report.optJSONArray("hypotheses") ?: return
        val allowed=setOf("30m","15m","5m","3m","1m")
        var queued=0
        for(i in rows.length()-1 downTo 0) {
            if(queued>=1) break
            val evidence=rows.optJSONObject(i) ?: continue
            val scale=evidence.optJSONObject("scale") ?: continue
            if(scale.optString("status")=="BOOK_WINDOW_OBSERVED") continue
            val frame=scale.optString("wanted_frame")
            val ms=williamsWaveFrames[frame] ?: continue
            if(frame !in allowed) continue
            val start=evidence.optLong("wave_start_ms",0L)
            val end=evidence.optLong("wave_end_ms",0L)
            if(start<=0L || end<=start || end>cutoff ||
                (end-start)/ms !in 40L..900L) continue
            val key=symbol+":"+frame+":"+(start/(15L*60L*1000L))
            val now=System.currentTimeMillis()
            val last=adaptiveWaveFetchAttemptMs[key] ?: 0L
            if(last>0L && now-last<15L*60L*1000L) continue
            adaptiveWaveFetchAttemptMs[key]=now
            queued++
            adaptiveWaveFetchExecutor.execute {
                runCatching {
                    val free=runCatching {
                        android.os.StatFs(context.filesDir.path).availableBytes
                    }.getOrDefault(0L)
                    if(free<512L*1024L*1024L)
                        throw IllegalStateException("WAVE_CHILD_DISK_BUDGET")
                    val from=(start-40L*ms).coerceAtLeast(0L)
                    val lastClosed=futuresMarketData.exchangeNowMs()
                    val until=minOf(lastClosed-ms,end+ms)
                    if(until<=from) return@runCatching
                    val array=futuresMarketData.klines(symbol,frame,1000,
                        startTime=from,endTime=until)
                    val batch=ArrayList<MarketHistoryStore.Candle>()
                    var lastOpen=Long.MIN_VALUE
                    for(j in 0 until array.length()) {
                        val row=array.getJSONArray(j)
                        val opened=row.getLong(0);val closed=row.getLong(6)
                        if(opened<=lastOpen || opened<from ||
                            opened>until)
                            throw IllegalStateException("WAVE_CHILD_INVALID_ORDER")
                        lastOpen=opened
                        if(!futuresMarketData.isCandleClosed(closed,lastClosed)) continue
                        batch+=MarketHistoryStore.Candle(opened,
                            row.getString(1).toDouble(),row.getString(2).toDouble(),
                            row.getString(3).toDouble(),row.getString(4).toDouble(),
                            row.getString(5).toDouble(),closed)
                    }
                    if(batch.isNotEmpty()) {
                        historyStore.upsertBatch(symbol,frame,batch)
                        // Wave report is recalculated at next 15m closure. It
                        // never sees exchange data not yet closed at that time.
                        adaptiveWaveContextCache.remove(symbol)
                        waveHierarchyCache.remove(symbol)
                    }
                }.onFailure { e ->
                    adaptiveWaveFetchLastError=frame+":"+
                        (e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    /** Bounded lower-priority slices replay the durable H1 decision timeline.
     * Scanner, Execution Door, reconciliation and stop ownership are not involved.
     */
    private fun startDeviceWaveReplay(generation:Long) {
        deviceWaveReplayTask?.cancel(false)
        val archiveFrames=listOf("1w","1d","12h","8h","6h","4h","2h","1h","30m","15m")
        fun active()=running && generation==runtimeGeneration && !Thread.currentThread().isInterrupted
        deviceWaveReplayTask=deviceWaveReplayExecutor.scheduleWithFixedDelay({
            if(!active()) return@scheduleWithFixedDelay
            if(nativeManagementRunning || futuresReconciliationRunning || futuresStreamReconcileRunning) {
                deviceWaveReplayState="WAITING_RUNTIME_PRIORITY"
                return@scheduleWithFixedDelay
            }
            val symbol=coreSymbols[deviceWaveReplayNextPair]
            deviceWaveReplayNextPair=(deviceWaveReplayNextPair+1)%coreSymbols.size
            runCatching {
                if(!historyStore.waveArchiveReady(symbol,archiveFrames)) {
                    deviceWaveReplayState="WAITING_FULL_ARCHIVE"
                    historyStore.waveReplayStatusUpdate(symbol,"WAITING_FULL_ARCHIVE")
                    return@runCatching
                }
                val free=android.os.StatFs(context.filesDir.path).availableBytes
                if(free<512L*1024*1024) {
                    deviceWaveReplayState="WAITING_DEVICE_STORAGE"
                    historyStore.waveReplayStatusUpdate(symbol,"WAITING_DEVICE_STORAGE")
                    return@runCatching
                }
                var cursor=historyStore.prepareWaveReplay(symbol)
                val through=futuresMarketData.exchangeNowMs()/3_600_000L*3_600_000L
                val closes=historyStore.waveClockPage(symbol,cursor.lastClosedMs,through)
                if(closes.isEmpty()) {
                    // 'archive ready' with six years of H1 bars but zero replay
                    // steps is NOT a successful catch-up. Never silently report
                    // zero processed candles as a completed historical audit.
                    if(cursor.processed==0L) {
                        throw IllegalStateException(
                            "FULL_H1_ARCHIVE_HAS_NO_REPLAY_CLOCK: $symbol; " +
                            "verify SQLite candle timestamps and rawQuery numeric binding"
                        )
                    }
                    deviceWaveReplayState="CAUGHT_UP_AVAILABLE_H1"
                    historyStore.waveReplayStatusUpdate(symbol,"CAUGHT_UP_AVAILABLE_H1")
                    return@runCatching
                }
                val started=System.nanoTime()
                for(asOf in closes) {
                    if(!active() || System.nanoTime()-started>350_000_000L) break
                    if(nativeManagementRunning || futuresReconciliationRunning || futuresStreamReconcileRunning) {
                        deviceWaveReplayState="WAITING_RUNTIME_PRIORITY"
                        break
                    }
                    val revision=historyStore.waveSourceRevision(symbol)
                    val history=linkedMapOf<String,List<WilliamsWaveBar>>()
                    for(frame in williamsWaveFrames.keys) {
                        val ms=williamsWaveFrames.getValue(frame)
                        val rows=historyStore.loadRecent(symbol,frame,deviceWaveReplayLimit,beforeMs=asOf-ms)
                            .filter { it.closeTime<asOf }.map { WilliamsWaveBar(it.openTime,it.open,it.high,it.low,it.close,it.volume) }
                        if(rows.isNotEmpty()) history[frame]=rows
                    }
                    // The bounded tail of a short TF may not reach an older
                    // parent. Read its exact missing window from SQLite only.
                    val probe=buildAdaptiveHistoricalWaveReport(history,asOf).getJSONArray("hypotheses")
                    val loaded=mutableSetOf<String>()
                    for(i in 0 until probe.length()) {
                        val row=probe.getJSONObject(i);val scale=row.getJSONObject("scale")
                        if(scale.optString("status")=="BOOK_WINDOW_OBSERVED") continue
                        val frame=scale.optString("wanted_frame");val ms=williamsWaveFrames[frame]?:continue
                        val start=row.getLong("wave_start_ms");val end=row.getLong("wave_end_ms")
                        val key="$frame:$start:$end"
                        if(!loaded.add(key) || (end-start)/ms !in 40L..450L ||
                            verifiedAdaptiveWaveWindow(history[frame].orEmpty(),frame,start,end,asOf,true).isNotEmpty()) continue
                        val extra=historyStore.waveTargetWindow(symbol,frame,start,end,asOf).map {
                            WilliamsWaveBar(it.openTime,it.open,it.high,it.low,it.close,it.volume)
                        }
                        history[frame]=(history[frame].orEmpty()+extra).associateBy { it.t }.values.sortedBy { it.t }
                    }
                    if(!active()) break
                    val step=buildDeviceWaveReplayStep(cursor,history,asOf)
                    if(!active()) break
                    if(!historyStore.commitWaveReplay(symbol,cursor,revision,step)) {
                        deviceWaveReplayState="SOURCE_CHANGED_RETRY"
                        break
                    }
                    cursor=step.cursor
                    deviceWaveReplayState="REPLAYING_H1_ARCHIVE"
                    deviceWaveReplayLastError=null
                }
            }.onFailure { e->
                deviceWaveReplayState="ANALYSIS_REPLAY_ERROR"
                deviceWaveReplayLastError=safeDiagnosticText(e.message?:e.javaClass.simpleName)
                runCatching { historyStore.waveReplayStatusUpdate(symbol,"ANALYSIS_REPLAY_ERROR",deviceWaveReplayLastError) }
            }
            if(!running) deviceWaveReplayState="STOPPED"
        },1L,1L,java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun waveHierarchyFor(symbol: String, refresh: Boolean = false): WilliamsWaveHierarchy {
        val now = futuresMarketData.exchangeNowMs()
        val cutoff = now / 900_000L * 900_000L
        waveHierarchyCache[symbol]?.takeIf { it.asOf == cutoff && !refresh }?.let { return it }
        val frames = linkedMapOf<String, List<WilliamsWaveBar>>()
        // H6 is additional stand-alone market context; it cannot form a
        // geometrically nested H6 -> H4 parent due the 6h/4h bar alignment.
        for (frame in (williamsRequiredWaveFrames + "6h").distinct()) {
            frames[frame] = runCatching { fetchCandles(symbol, frame, 240) }
                .getOrDefault(emptyList()).map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) }
        }
        var report = buildWilliamsWaveHierarchy(frames, cutoff)
        for (recommended in suggestedWilliamsAnalyticalFrames(report)) {
            if (recommended in frames) continue
            frames[recommended] = runCatching { fetchCandles(symbol,recommended,240) }
                .getOrDefault(emptyList()).map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) }
        }
        report = buildWilliamsWaveHierarchy(frames,cutoff)

        // A separate observational pass reads the device's deep SQLite
        // archive, not only the old 240 recent bars. It explicitly resolves
        // H1 inside H4 inside D1 and attempts genuinely finer real candles
        // to reach the approximate 100-140-bar analysis resolution.
        // Never allow a history/cache failure to alter the execution hierarchy.
        val adaptive = runCatching {
            val hourlyCutoff=cutoff/3_600_000L*3_600_000L
            adaptiveWaveContextCache[symbol]
                ?.takeIf { it.first==hourlyCutoff }?.second?.let { return@runCatching JSONObject(it.toString()) }
            val historicalFrames = listOf(
                "1w","1d","12h","8h","6h","4h","2h","1h",
                "30m","15m","5m","3m","1m"
            )
            val history = historicalFrames.associateWith { frame ->
                // Deep history remains fully durable in SQLite; the current
                // wave engine examines 1800 recent closed bars per degree.
                // Scanning 6000*13 rows per symbol would risk stalling
                // the read-only scanner on resource-constrained devices.
                historyStore.loadRecent(symbol,frame,1800,
                    beforeMs=hourlyCutoff-williamsWaveFrames.getValue(frame)).map {
                    WilliamsWaveBar(it.openTime,it.open,it.high,it.low,it.close,it.volume)
                }
            }
            val contextReport=buildAdaptiveHistoricalWaveReport(history,hourlyCutoff)
            adaptiveWaveContextCache[symbol]=hourlyCutoff to JSONObject(contextReport.toString())
            requestMissingAdaptiveWaveFrames(symbol,contextReport,hourlyCutoff)
            contextReport
        }.getOrElse { error ->
            JSONObject().put("schema","ADAPTIVE_WILLIAMS_WAVE_HISTORY_V1")
                .put("status","HISTORY_UNVERIFIED")
                .put("error_type",error.javaClass.simpleName)
                .put("can_place_orders",false)
        }
        report=report.copy(adaptiveHistory=adaptive)

        val previous = executionStore.readState("wave_hierarchy_" + symbol)
        val proof = report.toJson().toString()
        if (previous != proof) {
            val revisions = runCatching { JSONArray(executionStore.readState("wave_revisions_" + symbol) ?: "[]") }
                .getOrDefault(JSONArray())
            val summary = JSONObject().put("as_of",cutoff)
                .put("scenarios",JSONArray(report.scenarios.map { JSONObject().put("id",it.id).put("status",it.status)
                    .put("label",it.label).put("active_label",it.activeLabel).put("w4_zero",it.w4Zero) }))
                .put("links",JSONArray(report.links.map { it.toJson() }))
            val bounded = JSONArray()
            for (i in maxOf(0,revisions.length()-31) until revisions.length()) bounded.put(revisions.get(i))
            bounded.put(summary)
            if (!executionStore.writeState("wave_hierarchy_"+symbol,proof) ||
                !executionStore.writeState("wave_revisions_"+symbol,bounded.toString())) {
                report = report.copy(frameErrors=report.frameErrors + ("PERSISTENCE" to "WAVE_PROOF_NOT_DURABLE"))
            }
        }
        waveHierarchyCache[symbol] = report
        return report
    }

    private fun triggeredOpposingSignal(
        symbol: String, direction: NativeFuturesCampaignCoordinator.Direction,
        candles: List<CandleN>, tick: Double, contractPrice: Double, openedAt: Long, profile:String=williamsTc2Profile
    ): CampaignSignalN? {
        if(openedAt<=0L) return null
        val now = futuresMarketData.exchangeNowMs()
        val closed=candles.filter { it.t>0 && it.t+WILLIAMS_H1_MS<=now }
        val candidates=h1OppositionCandidates(symbol,closed,tick,profile)
        val assessment=assessWilliamsScenarioOpposition(direction.name,openedAt,candidates.map { scenarioSignal(it) },
            closed.map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) },contractPrice,now)
        val evidence=assessment.opposition
        check(executionStore.writeState("decision_h1_opposition_"+symbol,JSONObject()
            .put("observed_at_ms",now).put("scope_started_at_ms",openedAt).put("direction",direction.name)
            .put("trigger_price_basis","CONTRACT_PRICE").put("contract_price",contractPrice)
            .put("opposition",evidence?.toJson() ?: JSONObject.NULL)
            .put("uncertain",JSONArray(assessment.uncertain.map { it.toJson() })).toString())) { "H1_EXIT_PROOF_NOT_DURABLE" }
        if(assessment.uncertain.isNotEmpty()) return null
        return candidates.firstOrNull { it.signalId==evidence?.signal?.id }
    }

    private fun scenarioSignal(signal:CampaignSignalN)=WilliamsScenarioSignal(signal.signalId,
        campaignSignalDirection(signal.type).orEmpty(),signal.strategyProfile,signal.executionSemantics,
        signal.signalBarTimeMs,signal.triggerPrice,signal.invalidationPrice.takeIf { it.isFinite() && it>0 },
        williamsH1SignalExpiry(signal.signalBarTimeMs,signal.type,signal.strategyProfile).takeIf { it>0L } ?: Long.MAX_VALUE)

    private fun h1OppositionCandidates(symbol:String,candles:List<CandleN>,tick:Double,profile:String):List<CampaignSignalN> {
        if(candles.size<45) return emptyList()
        val out=mutableListOf<CampaignSignalN>()
        // Reconstruct only causal prefixes. This also catches a valid level
        // crossed in a completed H1 bar while the app/scanner was offline.
        for (omitted in 0..minOf(5,candles.size-45)) {
            val prefix = candles.take(candles.size-omitted)
            out+=editionCampaignSignals(symbol,prefix,"1h",tick,profile,exitMode=true)
                .filter { it.role=="ENTRY" && (it.strategyProfile!=williamsNtdProfile ||
                    it.type.removePrefix("SHORT_")=="FRACTAL") }
        }
        return out.distinctBy { it.signalId }
    }

    private fun enrichWithMtf(baseCandidate: BaseAnalysis): BaseAnalysis {
        val symbol = baseCandidate.symbol
        val hierarchy = waveHierarchyFor(symbol)
        val setup = baseCandidate.wave
        val primaryDecisionSignal = campaignEntryCandidate(baseCandidate)
        val shortSetup = primaryDecisionSignal?.type?.startsWith("SHORT_", ignoreCase = true) == true
        val decisionDirection = if (primaryDecisionSignal == null) setup.direction else if (shortSetup) "DOWN" else "UP"
        val htf4h = runCatching { waveInfo(fetchCandles(symbol,"4h",150),"4h") }.getOrNull()
        val htf1d = runCatching { waveInfo(fetchCandles(symbol,"1d",150),"1d") }.getOrNull()
        val htfConfirmed = htf4h != null && if (shortSetup) htf4h.alligatorBearish && !htf4h.aoPositive
            else htf4h.alligatorBullish && htf4h.aoPositive
        val dailyAligned = htf1d != null && if (shortSetup) htf1d.alligatorBearish && !htf1d.aoPositive
            else htf1d.alligatorBullish && htf1d.aoPositive
        val viable = hierarchy.scenarios.filter { it.status == "PROVISIONAL" }.associateBy { it.id }
        val decision = viable.values.filter { it.frame == "1h" }.maxByOrNull { it.anchors.size }
        val parent = hierarchy.links.filter { it.child == decision?.id }.mapNotNull { viable[it.parent] }
            .minByOrNull { williamsWaveFrames.getValue(it.frame) }
        val nestedW3ParentW5 = hierarchy.links.any { link ->
            val p = viable[link.parent]; val c = viable[link.child]
            p?.activeLabel == "W5" && c?.activeLabel == "W3" && p.direction == decisionDirection &&
                c.direction == decisionDirection && link.relationship == "ALIGNED"
        }
        val countertrendCorrectionImpulse = hierarchy.links.any { link ->
            viable[link.parent]?.activeLabel in listOf("W2","W4","A","C") && link.relationship == "ALIGNED"
        }
        val lateSenior = viable.values.any { it.frame in listOf("6h","1d") &&
            it.direction == decisionDirection && it.activeLabel in listOf("W5","A","B","C") }
        val exhaustion = if (nestedW3ParentW5 || lateSenior || setup.position==5)
            maxOf(65.0,setup.exhaustionRisk) else setup.exhaustionRisk
        val bonus = (if (htfConfirmed) 8.0 else 0.0) + (if (dailyAligned) 4.0 else 0.0) -
            (if (nestedW3ParentW5 || lateSenior) 12.0 else 0.0)
        // H1 is the sole signal authority. M15 never manufactures an entry.
        // Its contained structure validates the separately labelled correction
        // mode in submitNativeFuturesCampaignEntry; it does not veto TREND entries.
        val finalSignal = if (campaignEngineEnabled) {
            primaryDecisionSignal != null && baseCandidate.atrPct in 0.0..0.08 &&
                baseCandidate.spreadPct in 0.0..0.0015
        } else baseCandidate.signal
        var score = (baseCandidate.score+bonus).coerceIn(0.0,100.0)
        if (!finalSignal && baseCandidate.signal) score=minOf(score,84.0)
        val path = williamsRequiredWaveFrames.joinToString(" ; ") { frame ->
            val labels = viable.values.filter { it.frame==frame }.map { it.label+"→"+it.activeLabel }.distinct()
            frame+":"+(if (labels.isEmpty()) "?" else labels.joinToString("|"))
        }
        return baseCandidate.copy(score=score,signal=finalSignal,htfCandidate=htfConfirmed,
            wave=setup.copy(exhaustionRisk=exhaustion,path=path,entryFrame=campaignExecutionTimeframe,
                entryWave=setup.position,parentFrame=parent?.frame ?: "",parentWave=parent?.activeLabel?.removePrefix("W")?.toIntOrNull() ?: 0,
                entryConfidence=setup.confidence,nestedW3ParentW5=nestedW3ParentW5,
                countertrendCorrectionImpulse=countertrendCorrectionImpulse),
            reason=if (finalSignal && campaignEngineEnabled) "H1 Williams conditional candidate; context, wave correction mode, risk and exchange proof checked before submission"
                else baseCandidate.reason,
            campaignReady=campaignEngineEnabled && finalSignal,waveHierarchy=hierarchy)
    }

    private fun decisionTraceSnapshot(fullHistory:Boolean=false):JSONObject {
        val journal=runCatching { futuresCampaignCoordinator.intentsSnapshot() }
        val intents=journal.getOrDefault(JSONArray())
        val audit=runCatching { auditStore.latestEntryPassportEvents() }
        val passportNow=futuresMarketData.cachedExchangeNowMs()
        val passportClockFresh=futuresMarketData.clockSnapshot().optBoolean("fresh",false)
        return JSONObject().apply { BinanceUsdMFuturesTestnetAdapter.APPROVED_SYMBOLS.forEach { symbol ->
            val trace=runCatching { JSONObject(executionStore.readState("decision_trace_"+symbol).orEmpty()) }
                .getOrDefault(JSONObject().put("symbol",symbol).put("state","NOT_ANALYSED"))
            val execution=runCatching { JSONObject(executionStore.readState("decision_execution_"+symbol).orEmpty()) }.getOrNull()
            execution?.let { trace.put("execution",it) }
            runCatching { JSONObject(executionStore.readState("decision_runtime_"+symbol).orEmpty()) }.getOrNull()
                ?.let { trace.put("runtime_preflight",it) }
            trace.put("order_chain_state",if(journal.isSuccess) "JOURNAL_SNAPSHOT" else "UNAVAILABLE_RECONCILE_REQUIRED")
            runCatching { JSONObject(executionStore.readState("decision_context_"+symbol).orEmpty()) }.getOrNull()
                ?.let { trace.put("admission_context",it) }
            runCatching { JSONObject(executionStore.readState("decision_m15_entry_"+symbol).orEmpty()) }.getOrNull()
                ?.let { trace.put("mtf_entry",it) }
            runCatching { JSONObject(executionStore.readState("decision_h1_parent_lifecycle_"+symbol).orEmpty()) }.getOrNull()
                ?.let { trace.put("parent_lifecycle",it) }
            runCatching { JSONObject(executionStore.readState("decision_h1_opposition_"+symbol).orEmpty()) }.getOrNull()
                ?.let { trace.put("opposition_check",it) }
            trace.put("order_chain",williamsExecutionChain(intents,symbol))
            trace.put("entry_passport",williamsEntryPassport(symbol,execution,intents,
                audit.getOrNull()?.get(symbol),passportNow,passportClockFresh,journal.isSuccess)
                .put("audit_available",audit.isSuccess))
            if(fullHistory) trace.put("entry_passport_events",runCatching {
                auditStore.recentEntryPassportEvents(symbol,500)
            }.getOrElse { JSONArray() })
            trace.put("signal_history",runCatching {
                WilliamsSignalJournal(executionStore).view(symbol,intents,System.currentTimeMillis()).apply {
                    if(!fullHistory) {
                        val facts=getJSONArray("signals");val events=getJSONArray("events")
                        val all=(0 until facts.length()).map { facts.getJSONObject(it) }
                        put("signals",JSONArray((all.takeLast(24)+all.filter { it.optString("state") in setOf("BINANCE_PENDING","PENDING_UNVERIFIED","PARTIAL_FILL") }).distinctBy { it.optString("signal_id") }))
                        put("events",JSONArray((maxOf(0,events.length()-32) until events.length()).map { events.getJSONObject(it) }))
                        put("display_events_limit",32)
                    }
                }
            }.getOrElse { JSONObject().put("state","HISTORY_UNAVAILABLE").put("can_place_orders",false) })
            put(symbol,trace)
        } }
    }
    private fun rejectDecision(symbol:String,reason:String) {
        runCatching { WilliamsSignalJournal(executionStore).decision(symbol,"RUNTIME_PREFLIGHT",reason,System.currentTimeMillis()) }
        runCatching { executionStore.writeState("decision_runtime_"+symbol,JSONObject()
            .put("stage","RUNTIME_PREFLIGHT").put("observed_at_ms",System.currentTimeMillis())
            .put("outcome",JSONObject().put("accepted",false).put("mutation_attempted",false).put("blocker",safeDiagnosticText(reason))).toString()) }
    }

    /**
     * Long-term market LOCATION (not a wave label or an entry trigger).
     * All extrema come strictly from persistently stored, CLOSED Binance H1
     * candles. Unknown/insufficient history is not presented as a strong signal.
     */
    private fun persistentH1MarketLocation(symbol: String): JSONObject {
        val rows = historyStore.loadRecent(symbol, "1h", 6000)
        val last = rows.lastOrNull()
        val result = JSONObject().put("source", "DEVICE_SQLITE_CLOSED_H1")
            .put("used_for_order_entry", false)
            .put("full_history_backfilled", historyStore.isComplete(symbol, "1h"))
            .put("loaded_bars", rows.size)
            .put("first_bar_open_ms", rows.firstOrNull()?.openTime ?: JSONObject.NULL)
            .put("last_bar_open_ms", last?.openTime ?: JSONObject.NULL)
        if (last == null) return result.put("status", "NO_HISTORY")
        val exchangeTime = futuresMarketData.cachedExchangeNowMs()
        if (!futuresMarketData.isKlineOpenTimeClosed(last.openTime, "1h", exchangeTime)) {
            return result.put("status", "UNVERIFIED_LAST_H1_CLOSE")
        }
        for (period in listOf(150, 720, 2160, 6000)) {
            if (rows.size < period) {
                result.put("range_" + period + "_hours", JSONObject().put("status", "INSUFFICIENT_HISTORY"))
                continue
            }
            val sample = rows.takeLast(period)
            val low = sample.minOf { it.low }
            val high = sample.maxOf { it.high }
            val position = if (high > low)
                ((last.close - low) / (high - low)).coerceIn(0.0, 1.0)
                else null
            result.put("range_" + period + "_hours", JSONObject()
                .put("status", "OBSERVED_NOT_WAVE_CLASSIFICATION")
                .put("high", high).put("low", low)
                .put("last_close", last.close)
                .put("position_from_0_low_to_1_high", position ?: JSONObject.NULL))
        }
        return result.put("status", "HISTORICAL_CONTEXT")
    }

    private fun toJson(candidate: BaseAnalysis): JSONObject {
        val wave = candidate.wave
        // performCampaignScan deliberately does not call the old REST-heavy
        // enrichWithMtf path. Build the displayed context from real local data
        // here instead of serializing a null candidate.waveHierarchy as {}.
        val observedAt=futuresMarketData.exchangeNowMs()
        val contextErrors=linkedMapOf<String,String>()
        val localFrames=williamsObservedMarketFrames.associateWith { frame ->
            if(frame=="1h") candidate.candles.map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) }
            else runCatching {
                historyStore.loadRecent(candidate.symbol,frame,240,
                    beforeMs=observedAt-williamsWaveFrames.getValue(frame)).map {
                    WilliamsWaveBar(it.openTime,it.open,it.high,it.low,it.close,it.volume)
                }
            }.getOrElse { error ->
                contextErrors[frame]="HISTORY_READ_FAILED:"+error.javaClass.simpleName
                emptyList()
            }
        }
        val observedHierarchy=buildWilliamsObservedMarketContext(localFrames,observedAt,contextErrors)
        // Match the exact signal selected by execution, rather than displaying
        // the oldest item in the candidate list as if it were the pending entry.
        val primarySignal = campaignEntryCandidate(candidate)
        val signalStrength =
            if (candidate.signal) "CONFIRMED" else "WATCHING"

        val setupState =
            if (candidate.signal) "SIGNAL" else "WATCHING"

        val nestedW3 =
            wave.path.contains("W3")

        val nestedParentW5 = wave.nestedW3ParentW5

        // Never present a guessed junior W3 within a proposed senior W5 as a
        // 92/100 near-ideal entry. This remains an uncalibrated UI quality
        // indicator, NOT a statistical probability or exchange entry gate.
        val displayWaveScore =
            when {
                nestedParentW5 -> min(50.0, wave.confidence)
                wave.position == 3 -> min(86.0, wave.confidence)
                wave.position == 5 -> min(42.0, wave.confidence)
                else -> min(65.0, wave.confidence)
            }

        val riskReward =
            candidate.riskReward
        val lastH1 = candidate.candles.lastIndex
        val rhythm = candidate.waveHierarchy?.scenarios?.filter { it.frame == "1h" && it.status == "PROVISIONAL" }
            ?.maxByOrNull { it.anchors.size }
        val wave4Trace = JSONObject().put("status",rhythm?.w4Zero ?: "UNASSESSED")
            .put("examined_bars",rhythm?.scaleBars ?: JSONObject.NULL)
            .put("analytical_frame",rhythm?.scaleFrame ?: "")
            .put("source","TRADING_CHAOS_CH7_5_34_OSCILLATOR")
            .put("wave_labels","PROVISIONAL").put("wave4_completion_confirmed",false).put("can_place_orders",false)
        // NTD chapter 8 is a distinct fifth-dimension research profile:
        // never authorize a TC2 Futures order from these signals.
        val ntdBars = if (lastH1 >= 44) (maxOf(34, lastH1 - 30)..lastH1).map { index ->
            val candle = candidate.candles[index]
            val lines = alligator(candidate.candles, index)
            val values = (index - 4..index).map { ao(candidate.candles, it) }
            WilliamsNtdBar(
                h=candle.h, l=candle.l, jaw=lines.jaw, teeth=lines.teeth,
                lips=lines.lips, ao=values.last(), ac=nativeAcFromClosedAo(values)
            )
        } else emptyList()
        val ntdTick = runCatching { symbolFilters(candidate.symbol).tick }.getOrDefault(0.0)
        fun ntdTrace(side: String): JSONObject {
            val result = williamsNtdBalanceLine(ntdBars, side, ntdTick)
            return JSONObject().put("side",side).put("status",result.status)
                .put("base_index",result.baseIndex)
                .put("reference_index",result.referenceIndex)
                .put("required_reference_extremes",result.requiredSteps)
                .put("completed_reference_extremes",result.completedSteps)
                .put("trigger_price",if(result.triggerPrice>0) result.triggerPrice else JSONObject.NULL)
                .put("protective_reference",if(result.protectiveReference>0) result.protectiveReference else JSONObject.NULL)
                .put("zone",result.zone).put("jaw_relationship",result.relation)
                .put("reason",result.reason).put("can_place_orders",false)
        }
        val ntdTrace = JSONObject().put("profile","NTD_CH8_BALANCE_LINE_RESEARCH_ONLY")
            .put("long",ntdTrace("LONG")).put("short",ntdTrace("SHORT"))
            .put("can_place_orders",false)
        val h1Ao = ao(candidate.candles, lastH1)
        val h1Ac = if (lastH1 >= 4) nativeAcFromClosedAo(
            (lastH1 - 4..lastH1).map { ao(candidate.candles, it) }
        ) else Double.NaN
        fun waveRank(type: String) = nativeDirectionalHeuristicRank(
            signalType = type, waveDirection = wave.direction,
            wavePosition = wave.position, exhaustionRisk = wave.exhaustionRisk,
            bullishAlligator = wave.alligatorBullish,
            bearishAlligator = wave.alligatorBearish, ao = h1Ao, ac = h1Ac,
            bearishDivergence = wave.aoBearishDivergence,
            bullishDivergence = wave.aoBullishDivergence
        )
        val directionTrace = JSONObject()
            .put("long_rank_tiebreak", waveRank("LONG_REVERSAL"))
            .put("short_rank_tiebreak", waveRank("SHORT_REVERSAL"))
            .put("ao_divergence_bearish", wave.aoBearishDivergence)
            .put("ao_divergence_bullish", wave.aoBullishDivergence)
            .put("wave_label", wave.position)
            .put("wave_phase", wave.phase)
            .put("ratios_are_mandatory", false)
            .put("calibration", "ENGINEERING_HEURISTIC_UNCALIBRATED")
            .put("can_place_orders", false)

        val traceSignals=JSONArray(candidate.campaignSignals.map { signal -> JSONObject()
            .put("signal_id",signal.signalId).put("family",signal.type.removePrefix("SHORT_").removePrefix("LONG_")).put("type",signal.type)
            .put("direction",campaignSignalDirection(signal.type)).put("role",signal.role)
            .put("signal_bar_time_ms",signal.signalBarTimeMs).put("known_at_ms",signal.signalBarTimeMs+3_600_000L)
            .put("formation_at_ms",signal.formationAtMs).put("formation_time_basis",if(signal.formationAtMs==signal.signalBarTimeMs) "SIGNAL_BAR" else "SOURCE_PIVOT_OR_BASE")
            .put("formation_candle",candidate.candles.firstOrNull { it.t==signal.formationAtMs }?.let { JSONArray(listOf(it.t,it.o,it.h,it.l,it.c,it.v)) } ?: JSONObject.NULL)
            .put("confirmation_candle",candidate.candles.firstOrNull { it.t==signal.signalBarTimeMs }?.let { JSONArray(listOf(it.t,it.o,it.h,it.l,it.c,it.v)) } ?: JSONObject.NULL)
            .put("teeth_at_detection",signal.teethAtDetection.takeIf { it.isFinite() } ?: JSONObject.NULL)
            .put("expires_at_ms",if(signal.executionSemantics=="MARKET_ON_CLOSE") signal.signalBarTimeMs+3_600_000L+90_000L else williamsH1SignalExpiry(signal.signalBarTimeMs,signal.type,signal.strategyProfile))
            .put("trigger",signal.triggerPrice).put("protective_stop",signal.protectivePrice)
            .put("strategy_profile",signal.strategyProfile).put("execution_semantics",signal.executionSemantics)
            .put("setup_context",runCatching { JSONObject(signal.setupContext) }.getOrDefault(JSONObject())) })
        val line=alligator(candidate.candles,candidate.candles.lastIndex)
        val indicators=JSONObject().apply { mapOf("ao" to h1Ao,"ac" to h1Ac,"jaw" to line.jaw,"teeth" to line.teeth,"lips" to line.lips)
            .forEach { (name,value) -> put(name,if(value.isFinite()) value else JSONObject.NULL) } }
        // Root cause in Diagnostics (15): candidate.waveHierarchy is null on the
        // efficient H1 scan, even though observedHierarchy was fully built above.
        // Passing {} made ALL five decision_trace.market_phase frames
        // NOT_ANALYSED, while candidate.wave_hierarchy and market_readings
        // contained actual closed-candle data. Use exactly the same observed
        // hierarchy for the decision trace, never a guessed wave count.
        val trace=buildWilliamsDecisionTrace(candidate.symbol,williamsProfile,
            candidate.candles.map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) },futuresMarketData.exchangeNowMs(),
            indicators,traceSignals,observedHierarchy)
        // Read-only independent passports. Cached SQL history may be missing,
        // stale, or gapped; these are explicit states, never projected from H1.
        // Does not authorize or veto trading, and does not require M15 entry.
        val passports=JSONObject()
        val passportObservedAt=futuresMarketData.exchangeNowMs()
        val marketPhase=trace.getJSONObject("market_phase")
        for (frame in listOf("1d","6h","4h","1h","15m")) {
            val ms=williamsWaveFrames.getValue(frame)
            val reading=runCatching {
                val source=if(frame=="1h") candidate.candles.map {
                    WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v)
                } else historyStore.loadRecent(candidate.symbol,frame,160).map {
                    WilliamsWaveBar(it.openTime,it.open,it.high,it.low,it.close,it.volume)
                }
                val closed=source.filter { it.t>0L && it.t<=passportObservedAt-ms }.takeLast(160)
                val result=williamsMarketReading(closed,marketPhase,frame)
                    .put("closed_candle_count",closed.size)
                    .put("source",if(frame=="1h") "H1_CLOSED_SCANNER" else "SQLITE_CLOSED_HISTORY")
                val lastClose=closed.lastOrNull()?.t?.plus(ms) ?: 0L
                if(result.optString("state")=="OBSERVED" &&
                    (passportObservedAt<lastClose || passportObservedAt-lastClose>=ms*2)) {
                    result.put("state","STALE_HISTORY")
                }
                result
            }.getOrElse { failure ->
                JSONObject().put("schema","WILLIAMS_CHART_READING_V1")
                    .put("frame",frame).put("state","HISTORY_READ_FAILED")
                    .put("error_type",failure.javaClass.simpleName)
                    .put("can_place_orders",false).put("can_close",false)
            }
            passports.put(frame,reading)
        }
        trace.put("market_readings",passports)
        val observations=JSONArray(williamsSourceProfiles.mapNotNull { source ->
            executionStore.readState("edition_observation_"+candidate.symbol+"_"+source)
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
        })
        trace.put("edition_observations",observations)
        val traceBooks=trace.getJSONArray("books")
        for(index in 0 until observations.length()) {
            val observation=observations.getJSONObject(index)
            if(observation.optString("state")=="ANALYSIS_FAILED") for(j in 0 until traceBooks.length()) {
                val book=traceBooks.getJSONObject(j)
                if(book.optString("profile")==observation.optString("profile"))
                    book.put("state","ANALYSIS_FAILED").put("error_type",observation.optString("error_type"))
            }
        }
        runCatching { WilliamsSignalJournal(executionStore).observe(candidate.symbol,traceSignals,futuresMarketData.exchangeNowMs(),entryTimePolicy) }
        if(!candidate.campaignReady) rejectDecision(candidate.symbol,"H1_STRUCTURE_NOT_READY: "+candidate.reason)
        runCatching { executionStore.writeState("decision_trace_"+candidate.symbol,trace.toString()) }
        return JSONObject()
            .put("decision_trace",trace)
            .put("persistent_h1_market_location", runCatching {
                persistentH1MarketLocation(candidate.symbol)
            }.getOrElse {
                JSONObject().put("status", "HISTORY_READ_FAILED")
                    .put("used_for_order_entry", false)
            })
            .put("directional_context_native", directionTrace)
            .put("wave4_zero_line_diagnostic", wave4Trace)
            .put("ntd_balance_line_diagnostics", ntdTrace)
            .put("wave_hierarchy",observedHierarchy)
             .put("execution_policy",williamsExecutionPolicySnapshot()).put("strategy_profile", williamsProfile)
            .put("wave_scores_calibrated", false)
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
            .put("closed_h1_as_of_ms",candidate.candles.lastOrNull()?.let { it.t+WILLIAMS_H1_MS } ?: 0L)
            // Each source ran independently on this exact closed H1 series.
            // Preserve absent/failed observer evidence instead of quietly
            // equating "no signal" with "analyser could not execute".
            .put("edition_observations",observations)
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
                                .put("strategy_profile",s.strategyProfile).put("execution_semantics",s.executionSemantics)
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

    private fun aoBearishDivergence(candles: List<CandleN>): Boolean =
        nativeConfirmedAoLegDivergence(
            highs = candles.map { it.h }, lows = candles.map { it.l },
            ao = candles.indices.map { ao(candles, it) }, bearish = true
        )

    private fun aoBullishDivergence(candles: List<CandleN>): Boolean =
        nativeConfirmedAoLegDivergence(
            highs = candles.map { it.h }, lows = candles.map { it.l },
            ao = candles.indices.map { ao(candles, it) }, bearish = false
        )
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

    private fun waveInfo(candles: List<CandleN>, frame: String): WaveInfo {
        if (candles.size < 40 || frame !in williamsWaveFrames) return neutralWave(candles,frame)
        val bars = candles.map { WilliamsWaveBar(it.t,it.o,it.h,it.l,it.c,it.v) }
        val primary = williamsHypothesesFromAnchors(frame,bars,
            confirmedWilliamsWaveAnchors(bars,williamsWaveFrames.getValue(frame)))
            .filter { it.status == "PROVISIONAL" }.maxByOrNull { it.anchors.size }
        val lines = alligator(candles,candles.lastIndex)
        val current = candles.last().c
        val bullish = lines.lips > lines.teeth && lines.teeth > lines.jaw && current > lines.lips
        val bearish = lines.lips < lines.teeth && lines.teeth < lines.jaw && current < lines.lips
        val position = primary?.label?.removePrefix("W")?.toIntOrNull() ?: 0
        val late = position == 5 || primary?.label in listOf("A","B","C","ABC_COMPLETE")
        val divergence = if (primary?.direction == "DOWN") aoBullishDivergence(candles)
            else aoBearishDivergence(candles)
        val exhaustion = if (late) if (divergence) 85.0 else 65.0 else 25.0
        // This is an explicitly uncalibrated display score, not a posterior.
        val confidence = primary?.let { minOf(85.0,50.0+(it.anchors.size-2)*5.0) } ?: 0.0
        return WaveInfo(position=position,
            phase=if (primary == null) "UNKNOWN" else if (position in listOf(2,4) || late && position==0) "CORRECTION" else "IMPULSE",
            confidence=confidence,exhaustionRisk=exhaustion,direction=primary?.direction ?: "NEUTRAL",
            path=frame+":"+(primary?.label ?: "?"),alligatorBullish=bullish,alligatorBearish=bearish,
            aoPositive=ao(candles,candles.lastIndex)>0.0,aoBearishDivergence=aoBearishDivergence(candles),
            aoBullishDivergence=aoBullishDivergence(candles),
            currentLegPct=primary?.anchors?.lastOrNull()?.let { abs(current-it.price)/it.price*100.0 } ?: 0.0)
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
        // The signed account probe below can block on REST. Never couple that
        // latency to the 30-second Binance UTC-day clock safety gate. Only this
        // independent read-only clock pulse refreshes the cached server sample;
        // a failed /fapi/v1/time request still leaves all NEW entries blocked.
        exchangeClockProbeThread = Thread {
            while (restProbeRunning) {
                runCatching { futuresMarketData.exchangeNowMs() }
                try { Thread.sleep(5_000L) }
                catch (_: InterruptedException) { break }
            }
        }.apply {
            isDaemon = true
            name = "williams-futures-exchange-clock-probe"
            start()
        }
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
            .put("scanner_symbol_errors",lastScannerSymbolErrors)
            .put("market_metadata_warning",scannerMarketMetadataWarning ?: JSONObject.NULL)
            .put("h1_scanner_history_ready",readOnlyH1Ready)
            .put("h1_ready_symbol_count",h1HistoryReadyCount)
            .put("h1_all_ten_ready",historyReady)
            .put("optional_context_history_ready",optionalContextHistoryReady)
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
        if (!fast && (primaryCandles.isEmpty() || !candleSeriesCurrent(primaryCandles, interval))) {
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
        var positionObservedAtMs = 0L
        val futuresPositions = if (credentialsConfigured) {
            if (fast) {
                val (snapshot, refreshError, snapshotAt) = fastFuturesPositionsSnapshot()
                futuresPositionSnapshotError = refreshError
                positionObservedAtMs = snapshotAt
                snapshot
            } else {
                runCatching {
                    val display = futuresPositionDisplaySnapshot()
                    positionObservedAtMs = display.observedAtMs
                    JSONArray(display.positionsJson)
                }.getOrElse { failure ->
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
        val futuresPositionsSnapshotOk = credentialsConfigured && futuresPositionSnapshotError == null &&
            CockpitDiagnostics.fresh(positionObservedAtMs,System.currentTimeMillis(),CockpitDiagnostics.POSITION_TTL_MS)
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
                futuresCampaignReservedRisk(intent,futuresIntents)
            } else 0.0
        }
        val utcDayStart = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = if (fast) futuresMarketData.cachedExchangeNowMs() else runCatching { futuresMarketData.exchangeNowMs() }.getOrElse { System.currentTimeMillis() }
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

        // Snapshot reconciliation state around the coordinator call. The
        // coordinator's temporary in-progress state is not a durable runtime
        // inconsistency, and signed REST work can race status collection.
        val reconciliationAttemptBefore = lastReconciliationAttemptAtMs
        val reconciliationRunningBefore = futuresReconciliationRunning
        val futuresRuntimeStatus = runCatching { futuresCampaignCoordinator.status() }
            .getOrElse { JSONObject().put("state", "RECONCILE_REQUIRED").put("blocker", it.message ?: it.javaClass.simpleName) }
        // Attach protection from the SAME immutable position display generation.
        // The coordinator's execution state/proofs are never changed by UI GETs.
        cachedFuturesPositionEnvelope?.takeIf {
            it.observedAtMs == positionObservedAtMs && it.positionsJson == futuresPositions.toString()
        }?.let { display ->
            futuresRuntimeStatus.put("exchange_protection_observations",JSONArray(display.protectionJson))
                .put("protection_observation_scope","SIGNED_GET_ONLY_DISPLAY_NOT_EXECUTION_AUTHORITY")
        }
        val coordinatorReconciliationInProgress = futuresRuntimeStatus.optBoolean("reconciliation_running", false)
        val reconciliationObserved = coordinatorReconciliationInProgress ||
            reconciliationRunningBefore || futuresReconciliationRunning ||
            lastReconciliationAttemptAtMs != reconciliationAttemptBefore
        val coordinatorState = futuresRuntimeStatus.optString("state", "RECONCILE_REQUIRED")
            .uppercase(Locale.US)
        // In campaign mode the exchange coordinator is authoritative. The local FSM can
        // lag during a REST reconciliation; never display ENTRY_PENDING/READY as if it
        // were settled while the coordinator is still reconciling or protection is unknown.
        val effectiveDisplayState = authoritativeRuntimeDisplayState(campaignEngineEnabled, running, paused,
            killLatched, coordinatorState, stateName())
        val executionEnabledSnapshot = executionReady() && !reconciliationObserved &&
            futuresRuntimeStatus.optBoolean("ready_for_new_entry", false)

        return JSONObject()
            .put("version", BuildConfig.VERSION_NAME)
            .put("symbol", primarySymbol)
            .put("interval", interval)
            .put("testnet", true)
            .put("running", running)
            .put("futures_reconciliation_in_progress", reconciliationObserved)
            .put("last_reconciliation_attempt_at_ms", lastReconciliationAttemptAtMs)
            .put("last_reconciliation_completed_at_ms", lastReconciliationCompletedAtMs)
            .put("last_reconciliation_result", lastReconciliationResult)
             .put("execution_policy",williamsExecutionPolicySnapshot()).put("strategy_profile",williamsProfile)
            .put("entry_time_policy",entryTimePolicy.toJson())
            .put("decision_traces",decisionTraceSnapshot())
            .put("futures_leverage", futuresLeverageSettings.defaultLeverage)
            .put("futures_symbol_leverages", futuresLeverageSettings.resolvedJson())
            .put("futures_leverage_settings", futuresLeverageSettings.toJson())
            .put("new_entry_leverage_cap_x",MAX_CONFIGURED_FUTURES_LEVERAGE)
            .put("new_entry_leverage_compliant",futuresLeverageSettings.defaultLeverage in 1..MAX_CONFIGURED_FUTURES_LEVERAGE &&
                futuresLeverageSettings.symbolLeverages.values.all { it in 1..MAX_CONFIGURED_FUTURES_LEVERAGE })
            .put("new_entry_leverage_blocker",JSONObject.NULL)
            .put("new_entry_leverage_preflight","SIGNED_EXCHANGE_BRACKETS_AND_ISOLATED_STOP_BUFFER")
            .put("futures_margin_type", "ISOLATED")
            .put("futures_force_one_way", true)
            .put("paused", paused)
            .put("recovered", !reconcileRequired && (futuresRuntimeStatus.optString("state") in setOf("READY", "OPEN_PROTECTED", "ENTRY_PENDING")))
            .put("state", effectiveDisplayState)
            .put("execution_enabled", executionEnabledSnapshot)
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
                .put("state", effectiveDisplayState)
                .put("execution_enabled", executionEnabledSnapshot)
                .put("reconciliation_required", reconcileRequired)
                .put("user_stream_sync_required", userStreamSyncRequired)
                .put("kill_switch_latched", killLatched))
            .put("positions_snapshot_ok", futuresPositionsSnapshotOk)
            .put("positions_snapshot_at_ms", positionObservedAtMs)
            .put("positions_snapshot_age_ms", if (positionObservedAtMs > 0L)
                (System.currentTimeMillis() - positionObservedAtMs).coerceAtLeast(0L) else JSONObject.NULL)
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
            .put("daily_risk_baseline_source", dailyRiskBaselineSource(fast = fast))
            .put("trades_today", futuresTradesToday)
            .put("daily_pnl_usdt", futuresDailyPnl)
            .put("daily_loss_pct", futuresDailyLossPct)
            .put("daily_capital_flow_usdt", futuresDailyCapitalFlow)
            .put("daily_entry_halt",dailyEntryHalt().let { halt->
                JSONObject().put("available",halt.available).put("allow_new_entries",halt.allowNewEntries)
                    .put("until_ms",halt.untilMs?:JSONObject.NULL).put("reason",halt.reason?:JSONObject.NULL)
                    .put("reset_policy","UTC_00_00_ENTRY_ONLY; OPERATOR_PAUSE_AND_KILL_UNCHANGED")
            })
            .put("consecutive_losses", futuresLossStreakGuard().consecutiveLosses)
            .put("consecutive_losses_available", futuresLossStreakGuard().available)
            .put("consecutive_loss_blocker", futuresLossStreakGuard().reason ?: JSONObject.NULL)
            .put("consecutive_loss_cooldown_until_ms", futuresLossStreakGuard().cooldownUntilMs ?: JSONObject.NULL)
            .put("consecutive_loss_utc_day_start_ms",
                if(futuresMarketData.clockSnapshot().optBoolean("fresh",false))
                    futuresUtcDayStartMs(futuresMarketData.cachedExchangeNowMs())
                else JSONObject.NULL)
            .put("consecutive_loss_reset_policy","UTC_00_00_AUTOMATIC_IF_EXCHANGE_CLOCK_VERIFIED")
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
            .put("h1_scanner_history_ready",readOnlyH1Ready)
            .put("h1_ready_symbol_count",h1HistoryReadyCount)
            .put("optional_context_history_ready",optionalContextHistoryReady)
            .put("history_state", historyState)
            .put("history_last_error", historyLastError ?: JSONObject.NULL)
            .put("adaptive_wave_finer_history_last_error",
                adaptiveWaveFetchLastError ?: JSONObject.NULL)
            .put("historical_wave_replay",runCatching {
                historyStore.waveReplayStatus().put("worker_state",deviceWaveReplayState)
                    .put("worker_last_error",deviceWaveReplayLastError?:JSONObject.NULL)
            }.getOrElse { JSONObject().put("worker_state","STATUS_UNAVAILABLE")
                .put("can_place_orders",false).put("can_close_positions",false) })
            .put("persistent_history_sync_active", deepHistoryBackfillRunning)
            .put("persistent_history_frames", JSONArray(durableBackfillFrames))
            .put("persistent_history_complete_pairs", deepHistoryPairsComplete)
            .put("persistent_history_total_pairs", durableBackfillFrames.size * coreSymbols.size)
            .put("persistent_history_storage_low", deepHistoryStorageLow)
            .put("persistent_history_last_error", deepHistoryLastError ?: JSONObject.NULL)
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
            .put("scanner_symbol_errors", lastScannerSymbolErrors)
            .put("scanner_market_metadata_warning",scannerMarketMetadataWarning ?: JSONObject.NULL)
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
            .put("futures_reconciliation_in_progress", reconciliationObserved)
            .put("futures_reconciliation_last_result", lastReconciliationResult)
            .put("futures_reconciliation_last_completed_at_ms", lastReconciliationCompletedAtMs)
            .put("android_runtime", AndroidRuntimeHealth.snapshot(context))
            .put("exchange_clock", futuresMarketData.clockSnapshot())
            .put("quote_observations", JSONArray().apply {
                coreSymbols.forEach { symbol -> put(JSONObject().put("symbol", symbol)
                    .put("price", livePrices[symbol] ?: JSONObject.NULL)
                    .put("at_ms", livePriceUpdatedAtMs[symbol] ?: 0L)) }
            })
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
        requestedInterval: String? = null,
        requestedEndTime: Long? = null,
        requestedBars: Int = 120
    ): JSONObject {
        val symbol = if (requestedSymbol == null) primarySymbol else requestedSymbol.trim().uppercase(Locale.US)
        require(symbol in coreSymbols) { "Chart symbol is outside the approved Futures universe: $symbol" }
        val selectedInterval = if (requestedInterval == null) interval else requestedInterval.trim()
        require(selectedInterval in APPROVED_FUTURES_KLINE_INTERVALS) {
            "Unsupported Futures chart interval: $selectedInterval"
        }
        // Never return plausible H1 candles mislabeled as another symbol/interval after an invalid request or REST error.
        val bars = requestedBars.coerceIn(120, 6000)
        val historical = requestedEndTime != null
        val stored = if (historical || bars > 150) historyStore.loadRecent(symbol, selectedInterval, 6000, requestedEndTime)
            .filter { futuresMarketData.isKlineOpenTimeClosed(it.openTime, selectedInterval, futuresMarketData.exchangeNowMs()) }
            .map { CandleN(it.openTime, it.open, it.high, it.low, it.close, it.volume) } else emptyList()
        val sourceCandles = if (historical) stored else if (stored.size >= 150 && candleSeriesCurrent(stored, selectedInterval)) stored else if (symbol == primarySymbol && selectedInterval == interval) {
            if(primaryCandles.isEmpty() || !candleSeriesCurrent(primaryCandles, selectedInterval))
                primaryCandles=fetchCandles(primarySymbol,selectedInterval,150)
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
                if (!historical && snapshot.optBoolean("ready", false) && latestClosedCandleTime > 0L) {
                    indicatorSnapshots[indicatorKey] = JSONObject(snapshot.toString())
                }
            }
        }

        if (prices.isNotEmpty()) {
            val jaw = williamsAlligatorLine(sourceCandles, 13, 8)
            val teeth = williamsAlligatorLine(sourceCandles, 8, 5)
            val lips = williamsAlligatorLine(sourceCandles, 5, 3)
            val start = max(0, sourceCandles.size - bars)
            val observedSignals = if (historical || selectedInterval != "1h") emptyList() else
                CockpitDiagnostics.rows(CockpitDiagnostics.rows(candidates).firstOrNull { it.optString("symbol") == symbol }?.optJSONArray("campaign_signals"))

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
                        .put("signal_types", JSONArray(observedSignals.filter { it.optLong("signal_bar_time_ms") == c.t }.map { it.optString("type") }))
                        .put("fractal_up", isUpFractal(sourceCandles, i))
                        .put("fractal_down", isDownFractal(sourceCandles, i))
                )
            }
        }

        if (historical) return JSONObject().put("symbol", symbol).put("interval", selectedInterval)
            .put("candles", output).put("indicator_snapshot", indicatorSnapshot)
            .put("historical", true).put("until_ms", requestedEndTime)
            .put("live_price", JSONObject.NULL).put("live_price_source", "HISTORICAL")
            .put("live_price_at_ms", 0L)

        return marketQuote(symbol).put("interval", selectedInterval)
            .put("indicator_snapshot", indicatorSnapshot).put("candles", output)
            .put("h1_market_analysis_independent_of_entry_gate",true)
            .put("candles_fresh",candleSeriesCurrent(sourceCandles,selectedInterval))
            .put("last_closed_bar_open_ms",sourceCandles.lastOrNull()?.t ?: JSONObject.NULL)
            .put("scanner_state",scannerState)
            .put("execution_allowed",executionReady())
    }

    fun marketQuote(requestedSymbol: String?): JSONObject {
        val symbol = requestedSymbol?.trim()?.uppercase(Locale.US) ?: primarySymbol
        require(symbol in coreSymbols) { "Quote symbol is outside the approved Futures universe" }
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
            .put("live_price", liveQuote ?: JSONObject.NULL)
            .put("live_price_at_ms", liveQuoteAtMs)
            .put("live_price_source", liveQuoteSource)
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

    fun trades(): JSONArray = futuresVisibleTradeHistory(TradeJournal.trades(prefs),
        futuresCampaignCoordinator.operatorSupervisionsSnapshot(),futuresCampaignCoordinator.intentsSnapshot())

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
        test("execution_gate", "execution", executionReady(), "WARNING", "execution_ready=" + executionReady() + "; blockers=" + executionBlockers().joinToString(",") + "; an intentional entry block is not proof of component failure")
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
            dailyBaselineSource, reconciliationRunning,
            exchangeClockFresh = statusSnapshot.optJSONObject("exchange_clock")?.optBoolean("fresh") == true
        )
        test(
            "diagnostic_consistency", "diagnostics", consistencyFindings.isEmpty(),
            "FAIL", if (consistencyFindings.isEmpty()) "Runtime/coordinator readiness claims are consistent"
            else consistencyFindings.joinToString(" | ")
        )
        val exchangeClockFresh = statusSnapshot.optJSONObject("exchange_clock")?.optBoolean("fresh") == true
        val baselineKnown = exchangeClockFresh && dailyBaselineSource.isNotBlank() &&
            !dailyBaselineSource.equals("UNKNOWN", ignoreCase = true) &&
            !dailyBaselineSource.equals("null", ignoreCase = true)
        val baselineValue = if (!baselineKnown) null else runCatching {
            val now = futuresMarketData.cachedExchangeNowMs()
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
            "exchange_clock_fresh=" + exchangeClockFresh +
                "; source=" + dailyBaselineSource +
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
        val secureKeysPresent = key().isNotBlank() && secret().isNotBlank()
        val androidStartupError = RuntimeServiceState.lastError
        val rebootSeverity = when {
            !androidStartupError.isNullOrBlank() -> "FAIL"
            autoRunRequested && !secureKeysPresent -> "FAIL"
            autoRunRequested && (!running || !workerAlive) -> "FAIL"
            !autoRunRequested -> "NOT_VERIFIED"
            else -> "PASS"
        }
        test("reboot_credentials_and_engine", "runtime", rebootSeverity == "PASS", rebootSeverity,
            "app_id=" + context.packageName +
                "; version=" + BuildConfig.VERSION_NAME +
                "; auto_run_opt_in=" + autoRunRequested +
                "; encrypted_credentials_present=" + secureKeysPresent +
                "; engine_running=" + running + "; worker_alive=" + workerAlive +
                "; service_startup_error=" + (androidStartupError ?: "none") +
                "; NO raw Binance API credentials are exported")
        test("credentials_state", "binance", secureKeysPresent, "WARN",
            if (secureKeysPresent) "Binance credentials stored in encrypted app-private storage"
            else "Binance credentials unavailable in this Android package; changing debug/release variant or reinstalling does not migrate keys")
        test("rest_market_data", "binance", restMarketReady, "FAIL", if (restMarketReady) "Ticker REST OK; price=" + restLastTickerPrice + "; latency_ms=" + restLastLatencyMs else "REST market failed: " + (restLastError ?: "pending"))
        val signedReconcileNowMs = System.currentTimeMillis()
        val signedReconcileFresh = signedFuturesReconciliationFresh(lastRestReconcileMs, signedReconcileNowMs)
        test("signed_futures_reconciliation_freshness", "execution", signedReconcileFresh, "WARNING",
            "last_successful_at_ms=$lastRestReconcileMs; max_age_ms=90000; " +
                "age_ms=" + (if (lastRestReconcileMs > 0L && signedReconcileNowMs >= lastRestReconcileMs)
                    (signedReconcileNowMs-lastRestReconcileMs).toString() else "UNKNOWN") +
                "; stale evidence blocks new entries but does not block protective reconciliation")
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
        val coordinatorTaskHealthy = reconciliationTaskLifecycleHealthy(
            coordinatorSnapshot.optBoolean("reconciliation_running", false),
            coordinatorSnapshot.optLong("reconciliation_started_at_ms", 0L), userNowMs
        )
        test("campaign_reconciliation_lifecycle", "runtime", coordinatorTaskHealthy, "FAIL",
            "running=" + coordinatorSnapshot.optBoolean("reconciliation_running", false) +
                "; started_at_ms=" + coordinatorSnapshot.optLong("reconciliation_started_at_ms", 0L) +
                "; finished_at_ms=" + coordinatorSnapshot.optLong("reconciliation_finished_at_ms", 0L) +
                "; max_running_duration_ms=300000")
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
            "scanner_state", "scanner", scannerComplete || scannerActivelyProgressing,
            scannerFailureSeverity(running, historyReady, scanning, scannerState),
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

    fun cockpit(): JSONObject {
        val captured = status(fast = true)
        val scan = scannerSnapshot()
        val coherent = captured.optString("scanner_run_id") == scan.optString("run_id")
        captured.put("snapshot_consistent", coherent)
            .put("scanner_state", if (coherent) scan.optString("scanner_state") else "SNAPSHOT_UPDATING")
            .put("scanner_scanning", scan.optBoolean("scanning"))
            .put("scanner_error", scan.opt("scanner_error") ?: JSONObject.NULL)
            .put("scanner_progress_symbols", scan.optInt("progress_symbols"))
            .put("scanner_progress_total", scan.optInt("progress_total", 10))
            .put("scanner_configured_symbols", scan.optInt("progress_total", 10))
            .put("scanner_universe", scan.optJSONArray("scan_symbols") ?: JSONArray())
            .put("scanner_symbols", scan.optInt("symbols_scanned"))
            .put("allow_long", true).put("allow_short", true)
        val coordinator = captured.optJSONObject("futures_runtime") ?: JSONObject()
        val observedAt = System.currentTimeMillis()
        val readiness = CockpitDiagnostics.readiness(captured, coordinator, observedAt)
        captured.put("cockpit_readiness", readiness).put("snapshot_at_ms", observedAt)
        return JSONObject().put("snapshot_at_ms", observedAt).put("status", captured)
            .put("scanner", scan).put("trades", trades())
            .put("events", auditStore.recentCockpitEvents(30))
    }

    fun uiEvent(event: JSONObject): JSONObject {
        val action = event.optString("action")
        require(action in setOf("NAVIGATE", "REFRESH_FAILED", "DIAGNOSTICS", "CHART_SELECTED", "CONTROL")) { "Unknown UI event" }
        auditStore.recordCockpitEvent("UI", state = action, detail = safeDiagnosticText(event.optString("detail")))
        return JSONObject().put("recorded", true)
    }

    private fun strategyDiagnosticEvidence(): JSONArray {
        val now = futuresMarketData.cachedExchangeNowMs()
        val clockFresh = futuresMarketData.clockSnapshot().optBoolean("fresh")
        return JSONArray().apply {
            for (symbol in coreSymbols) {
                val key = "$symbol:1h"
                // Scanner fetches are cached under symbol:frame:limit, while
                // WebSocket snapshots use symbol:frame. Looking up only the
                // latter falsely reported 0/10 audited candles despite 6010
                // SQLite H1 candles per symbol in the attached diagnostics.
                // Diagnostics are read-only; prefer the durable closed history.
                val persisted = runCatching {
                    historyStore.loadRecent(symbol, "1h", 180)
                }.getOrDefault(emptyList())
                val source = if (persisted.size >= 40) persisted.map {
                    CandleN(it.openTime,it.open,it.high,it.low,it.close,it.volume)
                } else candleCache[key]?.second
                    ?: if (symbol == primarySymbol && interval == "1h") primaryCandles else emptyList()
                val candles = if (clockFresh) closedNativeStrategyCandles(source) { futuresMarketData.isKlineOpenTimeClosed(it.t, "1h", now) } else emptyList()
                val observed = JSONObject()
                if (candles.size >= 40) {
                    val index = candles.lastIndex
                    observed.put("jaw", williamsAlligatorLine(candles, 13, 8).last()?.takeIf { it.isFinite() } ?: JSONObject.NULL)
                        .put("teeth", williamsAlligatorLine(candles, 8, 5).last()?.takeIf { it.isFinite() } ?: JSONObject.NULL)
                        .put("lips", williamsAlligatorLine(candles, 5, 3).last()?.takeIf { it.isFinite() } ?: JSONObject.NULL)
                        .put("ao", ao(candles, index).takeIf { it.isFinite() } ?: JSONObject.NULL)
                        .put("ac", (ao(candles, index) - (0..4).map { ao(candles, index - it) }.average()).takeIf { it.isFinite() } ?: JSONObject.NULL)
                        .put("fractal_up_index", latestConfirmedUpFractal(candles, index) ?: JSONObject.NULL)
                        .put("fractal_down_index", candles.indices.lastOrNull { isDownFractal(candles, it) } ?: JSONObject.NULL)
                }
                val checks = if (!clockFresh) JSONObject().apply {
                    for (id in listOf("STR-01","STR-02","STR-03","STR-04")) {
                        put(id, JSONObject().put("severity","NOT_VERIFIED").put("detail",
                            "EXCHANGE_CLOCK_STALE: ${source.size} cached H1 candles exist but their closed-bar timestamps cannot be independently verified"))
                    }
                } else auditWilliamsIndicatorObservation(candles.map { (it.h + it.l) / 2.0 }, observed)
                put(JSONObject().put("symbol", symbol).put("interval", "1h")
                    .put("candle_count", candles.size)
                    .put("cached_candle_count", source.size)
                    .put("exchange_clock_fresh", clockFresh)
                    .put("last_bar_time_ms", candles.lastOrNull()?.t ?: 0L)
                    .put("observed", observed)
                    .put("checks", checks))
            }
        }
    }

    private fun scheduleReadOnlySignedFillAudit() {
        val nowMs=System.currentTimeMillis()
        synchronized(this) {
            if(signedFillAuditRunning ||
                (signedFillAuditLastAttemptMs>0L && nowMs-signedFillAuditLastAttemptMs<60_000L)) return
            signedFillAuditRunning=true
            signedFillAuditLastAttemptMs=nowMs
            // Preserve the last COMPLETED read-only snapshot across a refresh.
            // Otherwise every user-requested diagnostic >60s apart would show
            // only IN_PROGRESS and erase useful BNB closure evidence.
            if(JSONObject(signedFillAuditJson).optString("state")=="NOT_REQUESTED") {
                signedFillAuditJson=JSONObject().put("state","SIGNED_GET_IN_PROGRESS")
                    .put("requested_at_ms",nowMs).toString()
            }
        }
        Thread({
            val output=JSONObject().put("source","BINANCE_USDM_SIGNED_USER_TRADES_READ_ONLY")
                .put("report_generation_at_ms",nowMs)
                .put("can_authorize_exchange_mutations",false)
            try {
                val live=runCatching { status(fast=true).optJSONArray("futures_positions") }.getOrNull()
                val symbols=FuturesSignedTradeForensics.auditSymbols(
                    CockpitDiagnostics.rows(live).map { it.optString("symbol") } +
                        coreSymbols,limit=10)
                val audited=JSONArray()
                val endMs=System.currentTimeMillis()
                val sevenDaysMs=7L*24L*60L*60L*1000L
                val incomeStart=(endMs-sevenDaysMs).coerceAtLeast(0L)
                // One signed GET-only history sweep for all symbols. A failed or
                // saturated income read stays unknown; never call unknown funding zero.
                val incomeResult=runCatching {
                    val rows=futuresCampaignCoordinator.incomeHistory(incomeStart,endMs)
                    (0 until rows.length()).map { rows.getJSONObject(it) }
                }
                val incomeRows=incomeResult.getOrNull()
                output.put("funding_read",if(incomeRows==null) "INCOMPLETE" else "SIGNED_GET_COMPLETE")
                incomeResult.exceptionOrNull()?.let {
                    output.put("funding_error",safeDiagnosticText(it.message ?: it.javaClass.simpleName))
                }
                for(symbol in symbols) {
                    val ageMs=if(symbol in setOf("BNBUSDT","LTCUSDT")) sevenDaysMs
                        else 24L*60L*60L*1000L
                    // Never let one failing symbol erase independent signed
                    // forensics of BNB/LTC or other contracts.
                    val fromMs=(endMs-ageMs).coerceAtLeast(0L)
                    val trades=runCatching {
                        loadFuturesTradeRows(symbol,fromMs,endMs)
                    }.getOrNull()
                    audited.put(FuturesSignedTradeForensics.summarize(symbol,trades,endMs)
                        .put("window_start_ms",fromMs)
                        .put("window_end_ms",endMs)
                        .put("funding_evidence",FuturesSignedTradeForensics.summarizeFunding(
                            symbol,incomeRows,incomeStart,endMs)))
                }
                output.put("state","READ_COMPLETE_OR_EXPLICITLY_UNVERIFIED")
                    .put("symbols",audited)
                    .put("full_campaign_pnl_verified",false)
            } catch(failure:Exception) {
                output.put("state","SIGNED_GET_FAILED")
                    .put("error",safeDiagnosticText(failure.message ?: failure.javaClass.simpleName))
            } finally {
                signedFillAuditJson=output.toString()
                signedFillAuditRunning=false
            }
        },"williams-readonly-futures-fill-audit").apply { isDaemon=true; start() }
    }

    fun diagnostics(): JSONObject {
        // GET-only diagnostics run separately from the Android/UI thread and
        // never block STOP maintenance or create a position owner. A later
        // diagnostic export includes the completed snapshot.
        scheduleReadOnlySignedFillAudit()
        // SQLite indicator/history reads and supervised-history locking can take
        // longer than the strict 15s STOP TTL. Finish them BEFORE capturing the
        // signed position/protection generation used by every report check.
        val strategyEvidence = strategyDiagnosticEvidence()
        val tradeEvidence = JSONArray(CockpitDiagnostics.rows(trades()).take(20))
        runCatching { futuresPositionSnapshot() }.onFailure {
            futuresPositionSnapshotRefreshError = it.message ?: it.javaClass.simpleName
        }
        // Capture each state source once so the report does not compare different
        // moments in time and manufacture contradictions from separate status reads.
        val statusSnapshot = status(fast = true)
            .put("strategy_audit", strategyEvidence)
            .put("trade_observations", tradeEvidence)
            .put("signed_exchange_fill_forensics",JSONObject(signedFillAuditJson)
                .put("refresh_running",signedFillAuditRunning)
                .put("latest_refresh_requested_at_ms",signedFillAuditLastAttemptMs))
        val coordinatorSnapshot = statusSnapshot.optJSONObject("futures_runtime") ?: JSONObject()
        val tests = diagnosticSelfTests(statusSnapshot, coordinatorSnapshot)
        val observedAtMs = System.currentTimeMillis()
        val scannerEvidenceSnapshot = scannerSnapshot()
        val unifiedEvidence = buildWilliamsUnifiedEvidence(scannerEvidenceSnapshot, statusSnapshot, observedAtMs)
        val chartReadingAudit = JSONArray().apply {
            val inspected = CockpitDiagnostics.rows(scannerEvidenceSnapshot.optJSONArray("candidates"))
            for (candidate in inspected) {
                val symbol = candidate.optString("symbol")
                val readings = candidate.optJSONObject("decision_trace")
                    ?.optJSONObject("market_readings")
                for (frame in listOf("1d", "6h", "4h", "1h", "15m")) {
                    val reading = readings?.optJSONObject(frame)
                    put(JSONObject().put("symbol", symbol).put("interval", frame)
                        .put("state", reading?.optString("state") ?: "NOT_OBSERVED")
                        .put("closed_candle_count", reading?.optInt("closed_candle_count") ?: 0)
                        .put("as_of_close_ms", reading?.optLong("as_of_close_ms") ?: 0L)
                        .put("source", reading?.optString("source") ?: "UNKNOWN")
                        .put("can_place_orders", false).put("can_close", false))
                }
            }
        }
        val observedChartReadings = CockpitDiagnostics.rows(chartReadingAudit)
            .count { it.optString("state") == "OBSERVED" }
        val inspectedChartSymbols = CockpitDiagnostics.rows(scannerEvidenceSnapshot.optJSONArray("candidates"))
            .map { it.optString("symbol") }.toSet()
        // The Zero Point five-sign evidence comes from genuine closed H1
        // decisions; do not synthesize it from current price/other timeframes.
        val tc1ZeroPointAudit = JSONArray().apply {
            CockpitDiagnostics.rows(scannerEvidenceSnapshot.optJSONArray("candidates")).forEach { c ->
                val e = c.optJSONObject("decision_trace")?.optJSONObject("tc1_zero_point_observation")
                put(JSONObject().put("symbol", c.optString("symbol"))
                    .put("evidence", e ?: JSONObject().put("state", "NO_CAUSAL_TC1_EVIDENCE")))
            }
        }
        val bookRuleCoverage = JSONArray().apply {
            put(JSONObject().put("rule", "TC1_ZERO_POINT_FIVE_EVIDENCE")
                .put("source", "Trading Chaos 1st ed., chapter 9, PDF pp.82-84")
                .put("status", "PARTIAL_CAUSAL_EVIDENCE_NOT_BOOK_CERTIFIED")
                .put("detail", "Read-only actual H1 candidates now include Wave 3/5 divergence, " +
                    "target zone gap, confirmed fractal, squat volume proxy, 5/34/5 momentum; " +
                    "100-140 bar wave scale and lower-TF Wave-2 50-62% entry require causal historical replay. " +
                    "TC1 stop-and-reverse is NOT a TC2 automatic exit."))
            put(JSONObject().put("rule", "NTD_FRACTAL_TEETH_AT_ACTIVATION")
                .put("source", "New Trading Dimensions, chapter 4, PDF pp.48,52-53")
                .put("status", "CODE_PRESENT_REPLAY_NOT_VERIFIED")
                .put("detail", "Confirm confirmed fractal, Teeth position at actual breakout, first-fractal " +
                    "campaign sequence; never authorize fresh risk from NTD observation alone."))
            put(JSONObject().put("rule", "TC2_WM1_ANGULATION_FOUR_RULES")
                .put("source", "Trading Chaos 2nd ed., chapter 9, PDF pp.122-125")
                .put("status", "NUMERICAL_PROXY_NOT_BOOK_CERTIFIED")
                .put("detail", "Opening angle from crossing of Alligator mouth, jaw/teeth and price extremes; " +
                    "visual examples must be matched before declaring full conformance."))
            put(JSONObject().put("rule", "COURSE_RHYTHM_HUMP_THUMB_AIR_BAG_AND_STAGES")
                .put("source", "Trading Chaos Course, Level III, PDF pp.87-90")
                .put("status", "NOT_VERIFIED_ON_EXCHANGE")
                .put("detail", "The Level III worksheet sequences Two-Bar Reversal, 5/13/34 Rhythm, " +
                    "Hump Fractal and Thumb Trade before a documented executive decision; " +
                    "Air Bag trigger and stage manager need independent replay. Course remains OBSERVE."))
        }
        val cockpitTests = CockpitDiagnostics.checks(statusSnapshot, coordinatorSnapshot, scannerEvidenceSnapshot, observedAtMs)
        for (item in CockpitDiagnostics.rows(cockpitTests)) tests.put(item)
        val badSourceEvidence = unifiedEvidence.optJSONArray("source_contract_errors")?.length() ?: 0
        val fullSourceEvidence = unifiedEvidence.optString("status") == "COMPLETE_OBSERVATION_ONLY"
        tests.put(JSONObject().put("id", "STR-07").put("domain", "strategy")
            .put("name", "Происхождение сигналов TC2 / NTD / Course")
            .put("severity", when {
                badSourceEvidence > 0 -> "FAIL"
                // A Binance-recovered position with unknown entry provenance
                // must not masquerade as a malformed Williams book signal.
                // Nor may its unknown source grant PASS on ownership.
                unifiedEvidence.optInt("unattributed_recovered_positions") > 0 -> "NOT_VERIFIED"
                fullSourceEvidence && unifiedEvidence.optInt("observed_signal_count") > 0 -> "PASS"
                else -> "NOT_VERIFIED"
            }).put("detail", "Режим=${unifiedEvidence.optString("mode")}; " +
                "статус=${unifiedEvidence.optString("status")}; " +
                "достоверных источников=3; необработанных ошибок=$badSourceEvidence; " +
                "неатрибутированных восстановленных позиций=${unifiedEvidence.optInt("unattributed_recovered_positions")}; " +
                "сигналов=${unifiedEvidence.optInt("observed_signal_count")}. " +
                "PASS подтверждает только происхождение сигналов в полном снимке сканера, не корректность книг."))
        val tc1Evidence = CockpitDiagnostics.rows(tc1ZeroPointAudit)
            .mapNotNull { it.optJSONObject("evidence") }
        val tc1AuthorityViolation = tc1Evidence.any {
            it.optBoolean("can_place_orders") || it.optBoolean("can_close_positions") ||
                it.optBoolean("zero_point_confirmed") || it.optBoolean("wave_degree_certified")
        }
        tests.put(JSONObject().put("id", "STR-15").put("domain", "strategy")
            .put("name", "TC1 Zero Point: пять признаков без заглядывания вперёд")
            .put("severity", when {
                tc1AuthorityViolation -> "FAIL"
                tc1Evidence.size != coreSymbols.size -> "NOT_VERIFIED"
                tc1Evidence.any { it.optString("state") != "OBSERVATIONAL_PARTIAL_NOT_EXECUTABLE" } ->
                    "NOT_VERIFIED"
                else -> "PASS"
            }).put("detail", "Рассмотрено пар=${tc1Evidence.size}; " +
                "книжная целевая зона, MFI tick-volume и степень W3/W5 остаются " +
                "НЕПОДТВЕРЖДЁННЫМИ; это PASS только наблюдаемости, НЕ торгового сигнала."))
        tests.put(JSONObject().put("id", "STR-08").put("domain", "strategy")
            .put("name", "Причинные отказы входа на всех этапах")
            .put("severity", "NOT_VERIFIED")
            .put("detail", "Снимок сканера не хранит причины отклонения каждого несформированного " +
                "сигнала и окончательное решение D1/H4, риска и Binance. " +
                "Разбивка по книгам — наблюдаемость кандидатов, не доказательство допуска ордеров."))
        tests.put(JSONObject().put("id", "DATA-06").put("domain", "strategy")
            .put("name", "Что бот видит на D1/H6/H4/H1/M15 по 10 парам")
            .put("severity", when {
                inspectedChartSymbols.size != coreSymbols.size -> "NOT_VERIFIED"
                chartReadingAudit.length() != coreSymbols.size * 5 -> "NOT_VERIFIED"
                observedChartReadings == coreSymbols.size * 5 -> "PASS"
                else -> "WARNING"
            })
            .put("detail", "Реальные самостоятельные свечи: ${observedChartReadings}/${coreSymbols.size * 5}; " +
                "отсутствие истории/фазы не заменять анализом H1. Сведения в chart_reading_audit. " +
                "PASS свидетельствует о наличии данных, не о верном прогнозе."))
        tests.put(JSONObject().put("id", "STR-12").put("domain", "strategy")
            .put("name", "Историческая приёмка книжных правил по редакциям")
            .put("severity", "NOT_VERIFIED")
            .put("detail", "TC1 Zero Point 5 условий и коррекция Wave-2; NTD Teeth на пробое; " +
                "TC2 WM1 визуальная ангуляция; Course Rhythm/Hump/Thumb/Air Bag и стадии. " +
                "Нужны независимые эталоны, no-look-ahead replay и Binance Futures Demo; " +
                "обнаруженный аналитический сигнал не означает разрешение сделки."))
        for (item in CockpitDiagnostics.rows(tests)) {
            if (item.optString("severity") == "WARN") item.put("severity", "WARNING")
        }
        val cockpitReadiness = CockpitDiagnostics.readiness(statusSnapshot, coordinatorSnapshot, observedAtMs)
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
            topLevelRuntimeState = statusSnapshot.optString("state", "UNKNOWN"),
            exchangeClockFresh = statusSnapshot.optJSONObject("exchange_clock")?.optBoolean("fresh") == true
        )
        val sqliteHealth = executionStore.healthSnapshot()
        tests.put(JSONObject().put("id", "DB-01").put("name", "SQLite execution journal quick_check")
            .put("domain", "database").put("ok", sqliteHealth.optBoolean("ok"))
            .put("severity", if (sqliteHealth.optBoolean("ok")) "PASS" else "FAIL").put("detail", sqliteHealth.toString()))
        // Diagnostic 12 captured a signed BNB STOP at 14:26:28Z while
        // the position UI was still a snapshot from 14:26:17Z. Flag this
        // explicitly: a more recent STOP observation is evidence that the
        // cached UI must refresh, NOT fresh permission to trade.
        val positionDisplayAt = statusSnapshot.optLong("positions_snapshot_at_ms")
        val liveDisplayRows = CockpitDiagnostics.rows(statusSnapshot.optJSONArray("futures_positions"))
        val signedProofRows = CockpitDiagnostics.rows(
            coordinatorSnapshot.optJSONArray("exchange_protection_observations"))
        val newerExchangeStopSymbols = liveDisplayRows.filter { display ->
            signedProofRows.any { proof ->
                proof.optString("symbol") == display.optString("symbol") &&
                    proof.optString("direction") == display.optString("direction") &&
                    proof.optString("quantity").toBigDecimalOrNull()
                        ?.compareTo(display.optString("positionAmt").toBigDecimalOrNull() ?: return@any false) == 0 &&
                    proof.optLong("observed_at_ms") > positionDisplayAt &&
                    proof.optString("stop_proof") in setOf("OWNED_STOP_SEEN", "EXTERNAL_PROTECTED",
                        "NATIVE_REPLACEMENT_PROTECTION_SEEN")
            }
        }.map { it.optString("symbol") }
        tests.put(JSONObject().put("id", "EXE-10").put("domain", "execution")
            .put("name", "Свежесть биржевого SL относительно экрана")
            .put("severity", when {
                positionDisplayAt <= 0L || liveDisplayRows.isEmpty() -> "NOT_VERIFIED"
                newerExchangeStopSymbols.isNotEmpty() -> "WARNING"
                !statusSnapshot.optBoolean("positions_snapshot_ok") -> "NOT_VERIFIED"
                else -> "PASS"
            }).put("detail", "Signed STOP более новый, чем снимок UI для: " +
                newerExchangeStopSymbols.joinToString().ifBlank { "none" } +
                "; снимок позиции=" + positionDisplayAt +
                "; NEVER infer current protection from a stale cached screen."))
        // Operator-direction overrides were removed. A stale pre-upgrade
        // persisted flag must be reported as a safety defect, not a "mode".
        val removedModePersisted =
            prefs.getBoolean("force_testnet_mode",false) ||
            executionStore.readState("futures_testnet_force_mode") == "true"
        tests.put(JSONObject().put("id","SAF-LEGACY-01").put("domain","execution")
            .put("name","Отключён удалённый режим принудительных входов")
            .put("severity",if(!forceTestnetMode && !removedModePersisted) "PASS" else "FAIL")
            .put("detail","Legacy forced-entry override inaccessible in API; " +
                "prefs_or_db_flag_present=$removedModePersisted; runtime_override_active=$forceTestnetMode; " +
                "old exchange orders still recovered, never silently cancelled."))
        val requiredWaves = CockpitDiagnostics.rows(chartReadingAudit)
            .filter { it.optString("interval") in setOf("1d","4h","1h","15m") }
        val requiredKeys = coreSymbols.flatMap { symbol ->
            listOf("1d","4h","1h","15m").map { frame -> symbol to frame }
        }
        val absentRequired=requiredKeys.filter { (symbol,frame) ->
            requiredWaves.count {
                it.optString("symbol")==symbol && it.optString("interval")==frame &&
                    it.optString("state")=="OBSERVED" &&
                    it.optInt("closed_candle_count")>=40 &&
                    it.optLong("as_of_close_ms")>0
            } != 1
        }
        val observedRequired=requiredKeys.size-absentRequired.size
        val missingDescription=absentRequired.take(10)
            .joinToString("; ") { (symbol,frame) -> "$symbol/$frame" }
            .ifBlank { "none" } +
            if(absentRequired.size>10) "; plus ${absentRequired.size-10}" else ""
        tests.put(JSONObject().put("id","WAVE-04").put("domain","strategy")
            .put("name","Собственные закрытые свечи D1/H4/H1/M15 для всех десяти пар")
            .put("severity", when {
                requiredWaves.size != requiredKeys.size -> "NOT_VERIFIED"
                absentRequired.isEmpty() -> "PASS"
                else -> "WARNING"
            }).put("detail","Closed causal frames: $observedRequired/${requiredKeys.size}; " +
                "missing/underfilled: $missingDescription. " +
                "M15 is optional CONTEXT for initial TC2 H1 entries, not an invented entry requirement; " +
                "PASS proves data availability, not correct nested wave degree, book figures or accuracy."))
        val historyEvidence=williamsDurableHistoryEvidence(
            statusSnapshot,coreSymbols.toList(),durableBackfillFrames)
        tests.put(JSONObject().put("id","WAVE-ARCHIVE-01").put("domain","strategy")
            .put("name","Исторический архив и причинное воспроизведение волн")
            .put("severity",historyEvidence.getString("severity"))
            .put("detail",historyEvidence.getString("detail")))
        val stopAuditResult = runCatching { auditStore.recentStopTrailDecisionEvents(20) }
        val auditRows = stopAuditResult.getOrNull()?.length() ?: 0
        tests.put(JSONObject().put("id","EXE-SL-AUDIT").put("domain","execution")
            .put("name","Долговременный журнал решений по переносу SL")
            .put("severity",when {
                stopAuditResult.isFailure -> "FAIL"
                auditRows==0 -> "NOT_VERIFIED"
                else -> "PASS"
            }).put("detail","SQLite stop-trail audit readable=${stopAuditResult.isSuccess}; " +
                "recent_events=$auditRows; full source candles and signed outcomes are exported to ZIP; " +
                "the absence of records alone does NOT prove a STOP was missing."))
        // Snapshot races occur during BOTH safe pre-submit H1 rejection and
        // legitimate exchange-acknowledged pending STOP orders. Never guess
        // whether an order exists from coordinator state alone. A mixed-time
        // read remains fail-closed until the next signed reconciliation.
        val coordinatorStateAtEnd=futuresCampaignCoordinator.state.name
        val coordinatorStateAtStart=coordinatorSnapshot.optString("state","UNKNOWN")
        val concurrentCoordinatorTransition=coordinatorStateAtStart!=coordinatorStateAtEnd
        if(concurrentCoordinatorTransition) {
            tests.put(JSONObject().put("id","DIAG-STATE-RACE")
                .put("domain","runtime")
                .put("name","Состояние Futures изменилось во время диагностики")
                .put("severity","WARNING")
                .put("detail","Coordinator at snapshot=$coordinatorStateAtStart; " +
                    "at diagnostic end=$coordinatorStateAtEnd. " +
                    "ENTRY_PENDING may represent actual Binance-accepted STOP " +
                    "entry orders, a still-unconfirmed submission, or a transient " +
                    "state before rejection; it does NOT prove a filled position. " +
                    "Never assume exchange orders are absent or cancel them from " +
                    "this diagnostic. Obtain fresh signed positions, conditional " +
                    "orders and STOP proof before changing execution blocks."))
        }
        val unresolvedChecks = JSONArray().apply {
            for (test in CockpitDiagnostics.rows(tests)) {
                val severity = test.optString("severity")
                if (severity in setOf("FAIL", "WARNING", "WARN", "NOT_VERIFIED")) {
                    put(JSONObject()
                        .put("id", test.optString("id", test.optString("name")))
                        .put("domain", test.optString("domain"))
                        .put("severity", severity)
                        .put("name", test.optString("name")))
                }
            }
        }
        val checkedSummary = CockpitDiagnostics.summary(tests)
        val failedTests = checkedSummary.optInt("failed_test_count")
        val warningTests = checkedSummary.optInt("warning_test_count")
        val diagnosticOverall = if (consistencyFindings.isNotEmpty()) "FAIL" else checkedSummary.optString("overall")
        // This is a forensic read-only picture, NOT an authorization source.
        // The exchange coordinator can change state while JSON is built.
        val gateReadyAtReport = !concurrentCoordinatorTransition && executionReady()
        val gateReasonsAtReport = williamsDiagnosticGateReasons(
            executionBlockers(), concurrentCoordinatorTransition, gateReadyAtReport
        )
        val exposureCountAtReport=statusSnapshot.optJSONArray("futures_positions")?.length() ?: 0
        return JSONObject()
            .put("runtime", "standalone")
            .put("created_at_ms", observedAtMs)
            .put("created_at", java.time.Instant.ofEpochMilli(observedAtMs).toString())
            .put("cockpit_readiness", cockpitReadiness)
            .put("event_timeline", auditStore.recentCockpitEvents(150))
            .put("event_compaction", auditStore.cockpitReconciliationCompaction())
            .put("last_incident", auditStore.lastCockpitIncident() ?: JSONObject.NULL)
            .put("sqlite_health", sqliteHealth)
            .put("unified_strategy_evidence", unifiedEvidence)
            .put("scanner_snapshot", scannerEvidenceSnapshot)
            .put("chart_reading_audit", chartReadingAudit)
            .put("durable_history_evidence",historyEvidence)
            .put("tc1_zero_point_audit", tc1ZeroPointAudit)
            .put("book_rule_coverage", bookRuleCoverage)
            .put("signal_decision_history",decisionTraceSnapshot(fullHistory=true))
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
                .put("source_revision", BuildConfig.SOURCE_REVISION)
                .put("android_sdk", android.os.Build.VERSION.SDK_INT)
                .put("device_model", android.os.Build.MODEL)
                .put("manufacturer", android.os.Build.MANUFACTURER))
            .put("configuration_sanitized", settings())
            .put("tests", tests)
            .put("self_tests", tests)
            .put("diagnostic_findings", JSONArray(consistencyFindings))
            .put("unresolved_diagnostic_checks", unresolvedChecks)
            .put("diagnostic_triage",williamsDiagnosticTriage(tests,
                gateReadyAtReport &&
                    cockpitReadiness.optBoolean("new_entries_allowed") &&
                    consistencyFindings.isEmpty() && failedTests==0,
                livePositionCount=exposureCountAtReport))
            .put("diagnostic_summary", JSONObject()
                .put("overall", diagnosticOverall)
                .put("snapshot_non_atomic_coordinator_transition",concurrentCoordinatorTransition)
                .put("coordinator_state_at_diagnostic_end",coordinatorStateAtEnd)
                .put("finding_count", consistencyFindings.size)
                .put("unresolved_check_count", unresolvedChecks.length())
                .put("finding_count_scope", "INTERNAL_CONTRACT_CONTRADICTIONS_ONLY")
                .put("failed_test_count", failedTests)
                .put("warning_test_count", warningTests)
                .put("not_verified_test_count", checkedSummary.optInt("not_verified_test_count"))
                .put("passed_test_count", checkedSummary.optInt("passed_test_count"))
                .put("execution_allowed",gateReadyAtReport &&
                    cockpitReadiness.optBoolean("new_entries_allowed") &&
                    consistencyFindings.isEmpty() && failedTests == 0)
                .put("top_level_runtime_state", statusSnapshot.optString("state", "UNKNOWN"))
                .put("contract_runtime_state", statusSnapshot.optJSONObject("execution_state_contract")
                    ?.optString("state", "UNKNOWN") ?: "UNKNOWN")
                .put("coordinator_state", coordinatorSnapshot.optString("state", "UNKNOWN"))
                .put("coordinator_blocker", coordinatorSnapshot.optString("blocker", "")))
            .put("diagnostic_contract_version", CockpitDiagnostics.VERSION)
            .put("execution_gate", JSONObject()
                .put("scope", "TECHNICAL_EXECUTION_DOOR_ONLY")
                .put("snapshot_non_atomic",concurrentCoordinatorTransition)
                .put("new_entries_allowed", gateReadyAtReport &&
                    cockpitReadiness.optBoolean("new_entries_allowed"))
                .put("ready",gateReadyAtReport)
                .put("reasons",JSONArray(gateReasonsAtReport))
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

    @Synchronized
    fun configureFuturesLeverage(payload: JSONObject): JSONObject {
        val next = FuturesLeverageSettings.fromJson(payload)
        // Keep the operator's chosen leverage. FuturesLeverageSettings
        // already validates the requested 1..125 range for all ten pairs.
        // Binance brackets and the margin available BEFORE liquidation or
        // the structural STOP are authoritative on every new order.
        val profile=payload.optString("strategy_profile",williamsProfile)
        val nextTime=payload.optJSONObject("entry_time_policy")?.let { WilliamsEntryTimePolicy.fromJson(it) } ?: entryTimePolicy
        require(profile==williamsUnifiedProfile || williamsVerifiedNewRiskSource(profile)) {
            "Торговые источники: TC2 и NTD; Course работает в наблюдении"
        }
        fun persist() {
            check(prefs.edit().putString("entry_time_policy_v1",nextTime.toJson().toString()).putString("williams_strategy_profile_v1",profile)
                .putString("williams_execution_policy_v2",WILLIAMS_EXECUTION_POLICY)
                .putString("futures_leverage_settings_v1", next.toJson().toString()).commit()) {
                "Не удалось сохранить настройки стратегии"
            }
            futuresLeverageSettings=next
            williamsProfile=profile
            entryTimePolicy=nextTime
        }
        if(profile!=williamsProfile) futuresCampaignCoordinator.configureWhenFlat { persist() } else persist()
        runCatching { auditStore.recordCockpitEvent("LEVERAGE_SETTINGS", detail = next.toJson().toString()) }
        return JSONObject().put("saved", true).put("settings", next.toJson())
            .put("message", "Настройки сохранены для следующих входов; Binance подтвердит плечо перед ордером.")
    }

    fun settings(): JSONObject =
        JSONObject()
             .put("execution_policy",williamsExecutionPolicySnapshot()).put("strategy_profile",williamsProfile)
            .put("entry_time_policy",entryTimePolicy.toJson())
            .put("decision_traces",decisionTraceSnapshot())
            .put("futures_leverage", futuresLeverageSettings.defaultLeverage)
            .put("futures_symbol_leverages", futuresLeverageSettings.resolvedJson())
            .put("futures_leverage_settings", futuresLeverageSettings.toJson())
            .put("new_entry_leverage_cap_x",MAX_CONFIGURED_FUTURES_LEVERAGE)
            .put("new_entry_leverage_compliant",futuresLeverageSettings.defaultLeverage in 1..MAX_CONFIGURED_FUTURES_LEVERAGE &&
                futuresLeverageSettings.symbolLeverages.values.all { it in 1..MAX_CONFIGURED_FUTURES_LEVERAGE })
            .put("new_entry_leverage_blocker",JSONObject.NULL)
            .put("new_entry_leverage_preflight","SIGNED_EXCHANGE_BRACKETS_AND_ISOLATED_STOP_BUFFER")
            .put("futures_margin_type", "ISOLATED")
            .put("futures_force_one_way", true)
            .put("version", BuildConfig.VERSION_NAME)
            .put("campaign_engine", campaignEngineEnabled)
            .put("campaign_execution_timeframe", campaignExecutionTimeframe)
            .put("campaign_entry_mode", WILLIAMS_BOOK_H1_ENTRY_MODE)
            .put("campaign_context_timeframe", "1h")
            .put("campaign_trigger_timeframe", "1h")
            .put("campaign_m15_confirmation_required", false)
            .put("campaign_fixed_take_profit", false)
            .put("campaign_entry_execution", "USD_M_FUTURES_TESTNET_COORDINATOR")
            .put("campaign_risk_limit_pct", campaignRiskLimitPct)
            .put("campaign_initial_risk_pct", williamsTc2InitialUnitRisk(campaignInitialRiskPct,campaignRiskLimitPct,maxRiskPerTradePct))
            .put("campaign_initial_risk_cap_pct", campaignInitialRiskPct)
            .put("campaign_quantity_policy", WILLIAMS_TC2_PYRAMID_POLICY)
            .put("campaign_quantity_policy_scope", "NEW_AUTOMATED_TC2_CAMPAIGNS_ACTUAL_FIRST_FILL")
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
            .put("protective_stop_model", "TC2_3_OR_5_CLOSED_H1_BARS_AND_CONFIRMED_FRACTAL_NO_BREAKEVEN")
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
