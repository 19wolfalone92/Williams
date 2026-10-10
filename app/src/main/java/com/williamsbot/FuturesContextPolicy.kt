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
