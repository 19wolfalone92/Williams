package com.williamsbot

data class FuturesContextState(
    val bullish: Boolean,
    val bearish: Boolean,
    val awake: Boolean,
    val ao: Double
)

/**
 * Canonical H1 entry context contract:
 * H1 and H4 must permit the direction; D1 is a macro veto only when it
 * actively permits the opposite direction. Neutral D1 does not invent a signal.
 */
object FuturesContextPolicy {
    /**
     * Signal-specific admission. WM1 is a reversal/early-warning pattern, so
     * requiring H1 and H4 to already show the new directional Alligator order
     * can erase the very first Wise-Man signal. Its own structural proof is
     * validated by the detector; D1 remains a macro veto when it actively
     * permits the opposite direction. WM2/WM3 keep the stricter trend-context
     * gate. This is an explicit safety overlay, not a new Williams indicator.
     */
    fun allowsSignal(
        direction: String,
        signalType: String,
        operativeH1: FuturesContextState,
        parentH4: FuturesContextState,
        macroD1: FuturesContextState
    ): Boolean {
        val side = direction.trim().uppercase()
        if (side !in setOf("LONG", "SHORT")) return false
        if (!operativeH1.ao.isFinite() || !parentH4.ao.isFinite() || !macroD1.ao.isFinite()) return false
        val type = signalType.trim().uppercase()
        if (type == "REVERSAL") {
            val opposite = if (side == "LONG") "SHORT" else "LONG"
            fun permits(state: FuturesContextState, wanted: String): Boolean =
                state.awake && when (wanted) {
                    "LONG" -> state.bullish && state.ao > 0.0
                    "SHORT" -> state.bearish && state.ao < 0.0
                    else -> false
                }
            // Do not require the H1/H4 trend to have already turned; do not
            // permit a reversal that directly fights an active D1 macro state.
            return !permits(macroD1, opposite)
        }
        return allows(side, operativeH1, parentH4, macroD1)
    }

    fun allows(
        direction: String,
        operativeH1: FuturesContextState,
        parentH4: FuturesContextState,
        macroD1: FuturesContextState
    ): Boolean {
        val side = direction.trim().uppercase()
        if (side !in setOf("LONG", "SHORT")) return false
        fun permits(state: FuturesContextState, wanted: String): Boolean =
            state.awake && state.ao.isFinite() && when (wanted) {
                "LONG" -> state.bullish && state.ao > 0.0
                "SHORT" -> state.bearish && state.ao < 0.0
                else -> false
            }
        val opposite = if (side == "LONG") "SHORT" else "LONG"
        return permits(operativeH1, side) &&
            permits(parentH4, side) &&
            !permits(macroD1, opposite)
    }
}
