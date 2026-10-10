package com.williamsbot

import android.content.SharedPreferences
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Durable UTC daily equity breaker. Restarts never reset today's loss budget. */
class EquityCircuitBreaker(
    private val maxDrawdownPct: Double = 0.01,
    private val stateStore: SharedPreferences? = null
) {
    data class Snapshot(
        val equity: Double,
        val startEquity: Double,
        val drawdownPct: Double,
        val tripped: Boolean,
        val baselineValid: Boolean
    )

    companion object {
        private const val KEY_DAY = "risk_daily_day_utc"
        private const val KEY_START_EQUITY = "risk_daily_start_equity"
        private const val KEY_TRIPPED = "risk_daily_tripped"
        private const val KEY_LAST_EQUITY = "risk_last_observed_equity"
        private const val KEY_LAST_AT = "risk_last_observed_equity_at_ms"
        private const val UTC_BOUNDARY_TOLERANCE_MS = 5 * 60 * 1000L
    }

    private var dayKey = stateStore?.getString(KEY_DAY, "") ?: ""
    private var startEquity =
        stateStore?.getString(KEY_START_EQUITY, "0")?.toDoubleOrNull() ?: 0.0
    private var tripped = stateStore?.getBoolean(KEY_TRIPPED, false) ?: false
    private var lastObservedEquity =
        stateStore?.getString(KEY_LAST_EQUITY, "0")?.toDoubleOrNull() ?: 0.0
    private var lastObservedAtMs = stateStore?.getLong(KEY_LAST_AT, 0L) ?: 0L
    private var baselineValid = true

    init {
        require(maxDrawdownPct.isFinite() && maxDrawdownPct > 0.0 && maxDrawdownPct <= 0.01) {
            "Daily loss limit must be finite and no greater than 1%"
        }
        val hasPersistedRiskState =
            stateStore?.contains(KEY_DAY) == true || stateStore?.contains(KEY_START_EQUITY) == true
        baselineValid = !hasPersistedRiskState ||
            (dayKey.isNotBlank() && startEquity.isFinite() && startEquity > 0.0)
        if (!baselineValid) tripped = true
    }

    @Synchronized
    fun observe(equity: Double, nowMs: Long = System.currentTimeMillis()): Snapshot {
        if (!equity.isFinite() || equity <= 0.0 || nowMs <= 0L) {
            return Snapshot(equity, startEquity, 0.0, true, baselineValid)
        }

        val today = Instant.ofEpochMilli(nowMs)
            .atZone(ZoneOffset.UTC)
            .toLocalDate()
            .toString()
        val utcMidnightMs = LocalDate.parse(today)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()

        if (dayKey.isBlank() && startEquity <= 0.0 && stateStore?.contains(KEY_DAY) != true) {
            // Only a genuinely new install may bootstrap from current equity.
            dayKey = today
            startEquity = equity
            tripped = false
            baselineValid = true
        } else if (today != dayKey) {
            val boundarySnapshotIsTrustworthy =
                lastObservedEquity.isFinite() && lastObservedEquity > 0.0 &&
                lastObservedAtMs >= utcMidnightMs - UTC_BOUNDARY_TOLERANCE_MS &&
                lastObservedAtMs < utcMidnightMs && lastObservedAtMs <= nowMs

            dayKey = today
            if (boundarySnapshotIsTrustworthy) {
                startEquity = lastObservedEquity
                tripped = false
                baselineValid = true
            } else {
                // Starting from current equity after a long offline interval
                // could forgive losses earlier that UTC day.
                baselineValid = false
                tripped = true
            }
        } else if (!baselineValid || !startEquity.isFinite() || startEquity <= 0.0) {
            baselineValid = false
            tripped = true
        }

        val drawdown = if (baselineValid) {
            ((startEquity - equity) / startEquity).coerceAtLeast(0.0)
        } else 0.0
        // Floating-point division can represent an exact 1% drawdown just below
        // the configured boundary. Epsilon corrects representation error only.
        if (baselineValid && drawdown + 1e-12 >= maxDrawdownPct) tripped = true

        lastObservedEquity = equity
        lastObservedAtMs = nowMs
        persistState()

        return Snapshot(equity, startEquity, drawdown, tripped || !baselineValid, baselineValid)
    }

    /** Startup compatibility API; it cannot reset a saved same-day baseline or trip. */
    @Synchronized
    fun reset(equity: Double, nowMs: Long = System.currentTimeMillis()) {
        observe(equity, nowMs)
    }

    @Synchronized
    fun isTripped(): Boolean = tripped || !baselineValid

    @Synchronized
    fun hasValidBaseline(): Boolean = baselineValid

    private fun persistState() {
        val editor = stateStore?.edit() ?: return
        val saved = editor
            .putString(KEY_DAY, dayKey)
            .putString(KEY_START_EQUITY, startEquity.toString())
            .putBoolean(KEY_TRIPPED, tripped || !baselineValid)
            .putString(KEY_LAST_EQUITY, lastObservedEquity.toString())
            .putLong(KEY_LAST_AT, lastObservedAtMs)
            .commit()
        if (!saved) {
            baselineValid = false
            tripped = true
        }
    }
}
