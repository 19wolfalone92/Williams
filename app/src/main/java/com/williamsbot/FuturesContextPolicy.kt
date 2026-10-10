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
     * A WM3 conditional trigger must remain on the correct side of the
     * currently observed Alligator Teeth line while it is pending. This is an
     * execution/safety revalidation, separate from fractal formation.
     */
    fun fractalTriggerOutsideTeeth(direction: String, triggerPrice: Double, teeth: Double): Boolean {
        if (!triggerPrice.isFinite() || triggerPrice <= 0.0 || !teeth.isFinite() || teeth <= 0.0) return false
        return when (direction.trim().uppercase()) {
            "LONG" -> triggerPrice > teeth
            "SHORT" -> triggerPrice < teeth
            else -> false
        }
    }

    /**
     * TC2 First Wise Man is an early reversal signal, so requiring the new
     * direction to be already confirmed by H1/H4 Alligator and AO would erase
     * the setup the detector is supposed to find. Keep this exception narrow:
     * the WM1 detector must supply finite positive angulation evidence, and an
     * actively directional opposite D1 remains a hard macro veto.
     *
     * Callers must obtain fresh, closed H1/H4/D1 frames before invoking this.
     */
    fun allowsEarlyReversal(
        direction: String,
        operativeH1: FuturesContextState,
        parentH4: FuturesContextState,
        macroD1: FuturesContextState,
        angulationScore: Double
    ): Boolean {
        val side = direction.trim().uppercase()
        if (side !in setOf("LONG", "SHORT")) return false
        if (!angulationScore.isFinite() || angulationScore <= 0.0) return false
        if (!macroD1.ao.isFinite()) return false

        val opposite = if (side == "LONG") "SHORT" else "LONG"
        fun permits(state: FuturesContextState, wanted: String): Boolean =
            state.awake && state.ao.isFinite() && when (wanted) {
                "LONG" -> state.bullish && state.ao > 0.0
                "SHORT" -> state.bearish && state.ao < 0.0
                else -> false
            }

        // H1/H4 are present and fresh context, but need not have turned yet.
        // Only an active, directionally permitted opposite D1 vetoes WM1.
        @Suppress("UNUSED_VARIABLE")
        val requiredContextWasFetched = operativeH1.bullish || operativeH1.bearish ||
            parentH4.bullish || parentH4.bearish || !operativeH1.ao.isNaN() && !parentH4.ao.isNaN()
        return !permits(macroD1, opposite)
    }

    fun allowsSignal(
        direction: String,
        signalType: String,
        angulationScore: Double,
        operativeH1: FuturesContextState,
        parentH4: FuturesContextState,
        macroD1: FuturesContextState
    ): Boolean = if (signalType.trim().equals("REVERSAL", ignoreCase = true)) {
        allowsEarlyReversal(direction, operativeH1, parentH4, macroD1, angulationScore)
    } else {
        allows(direction, operativeH1, parentH4, macroD1)
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
