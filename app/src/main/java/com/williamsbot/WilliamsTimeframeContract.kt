package com.williamsbot

/**
 * Owner-approved bot contract, not a Bill Williams book rule.
 * Only H1 creates canonical campaign signals. D1/H4 are context; M15 is
 * execution/fill monitoring; M5 is diagnostics and replay only.
 */
internal object WilliamsTimeframeContract {
    const val MACRO_CONTEXT = "1d"
    const val HIGHER_CONTEXT = "4h"
    const val CANONICAL_DECISION = "1h"
    const val EXECUTION_MONITOR = "15m"
    const val DIAGNOSTIC_REPLAY = "5m"

    fun canCreateCanonicalSignal(frame: String): Boolean =
        frame.trim().lowercase() == CANONICAL_DECISION

    /** Returns a fail-closed reason for entry provenance; null means only this
     * deterministic metadata gate passed. Live H1 candle/freshness/structure
     * checks remain mandatory in StandaloneRuntime before mutation.
     */
    fun entryProvenanceBlocker(
        timeframe: String,
        signalBarTimeMs: Long,
        confirmationTimeMs: Long,
        expiresAtMs: Long,
        nowMs: Long,
        symbolMatches: Boolean,
        signalIdPresent: Boolean,
        campaignIdPresent: Boolean,
        triggerPrice: Double,
        invalidationPrice: Double
    ): String? {
        if (!canCreateCanonicalSignal(timeframe)) return "ENTRY_TIMEFRAME_BLOCKED"
        val frameMs = 60L * 60L * 1000L
        if (
            !symbolMatches || !signalIdPresent || !campaignIdPresent ||
            signalBarTimeMs <= 0L ||
            confirmationTimeMs < signalBarTimeMs + frameMs ||
            confirmationTimeMs > nowMs ||
            expiresAtMs <= nowMs ||
            !triggerPrice.isFinite() || triggerPrice <= 0.0 ||
            !invalidationPrice.isFinite() || invalidationPrice <= 0.0
        ) return "ENTRY_PROVENANCE_BLOCKED"
        return null
    }
}
