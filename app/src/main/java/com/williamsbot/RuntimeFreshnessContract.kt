package com.williamsbot

/**
 * Deterministic freshness rules shared by runtime diagnostics and UI/tests.
 * Unknown timestamps, failed reads, and wall-clock reversal fail closed.
 */
object RuntimeFreshnessContract {
    fun ageMs(lastSuccessAtMs: Long, nowMs: Long): Long? {
        if (lastSuccessAtMs <= 0L || nowMs < lastSuccessAtMs) return null
        return nowMs - lastSuccessAtMs
    }

    fun isFresh(
        available: Boolean,
        lastSuccessAtMs: Long,
        nowMs: Long,
        maxAgeMs: Long
    ): Boolean {
        if (!available || maxAgeMs < 0L) return false
        val age = ageMs(lastSuccessAtMs, nowMs) ?: return false
        return age <= maxAgeMs
    }

    fun isScanSnapshotFresh(
        state: String,
        lastScanAtMs: Long,
        nowMs: Long,
        maxAgeMs: Long,
        error: String?
    ): Boolean =
        state in setOf("READY", "RUNNING") &&
            error.isNullOrBlank() &&
            isFresh(
                available = true,
                lastSuccessAtMs = lastScanAtMs,
                nowMs = nowMs,
                maxAgeMs = maxAgeMs
            )
}
