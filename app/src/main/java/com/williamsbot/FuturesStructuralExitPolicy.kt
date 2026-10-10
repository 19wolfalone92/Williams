package com.williamsbot

data class WilliamsExitBar(
    val close: Double,
    val jaw: Double,
    val teeth: Double,
    val lips: Double,
    val ao: Double
)

/**
 * Shared deterministic hard-exit predicate for native Futures.
 * It mirrors the Python campaign contract on two closed H1 bars:
 * opposite Alligator ordering, close beyond Teeth, and opposite AO.
 * AC is not an additional hard-exit gate in this policy.
 */
object FuturesStructuralExitPolicy {
    fun shouldExit(direction: String, lastTwoClosedBars: List<WilliamsExitBar>): Boolean {
        val side = direction.trim().uppercase()
        if (side !in setOf("LONG", "SHORT") || lastTwoClosedBars.size != 2) return false
        return lastTwoClosedBars.all { bar ->
            val values = listOf(bar.close, bar.jaw, bar.teeth, bar.lips, bar.ao)
            if (!values.all(Double::isFinite) || bar.teeth <= 0.0) {
                false
            } else if (side == "LONG") {
                bar.close < bar.lips &&
                    bar.lips < bar.teeth &&
                    bar.teeth < bar.jaw &&
                    bar.close < bar.teeth &&
                    bar.ao < 0.0
            } else {
                bar.close > bar.lips &&
                    bar.lips > bar.teeth &&
                    bar.teeth > bar.jaw &&
                    bar.close > bar.teeth &&
                    bar.ao > 0.0
            }
        }
    }
}
