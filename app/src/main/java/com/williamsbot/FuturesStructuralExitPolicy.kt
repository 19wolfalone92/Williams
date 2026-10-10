package com.williamsbot

data class WilliamsExitBar(
    val close: Double,
    val jaw: Double,
    val teeth: Double,
    val lips: Double,
    val ao: Double
)

/**
 * Optional two-bar Alligator/AO exit overlay for native Futures.
 *
 * It is disabled in the TC2 core profile by default because it is not a
 * substitute for the source-profile's price-bar structural stop. It remains
 * available only when tc2_two_bar_reversal_exit is explicitly enabled.
 * AC is not an additional gate in this overlay.
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
