package com.williamsbot

/**
 * Williams Alligator line series. The inputs are median prices, and each
 * output is shifted into its displayed/operative bar position: Jaw 13/8,
 * Teeth 8/5, Lips 5/3. The SMMA seed is kept consistent with the existing
 * native strategy implementation; exact historical seed conventions remain
 * an explicit source-validation item rather than an unstated assumption.
 */
internal data class WilliamsAlligatorSeries(
    val jaw: List<Double>,
    val teeth: List<Double>,
    val lips: List<Double>
) {
    fun validAt(index: Int): Boolean =
        jaw.getOrNull(index)?.let { it.isFinite() && it > 0.0 } == true &&
            teeth.getOrNull(index)?.let { it.isFinite() && it > 0.0 } == true &&
            lips.getOrNull(index)?.let { it.isFinite() && it > 0.0 } == true
}

internal object WilliamsAlligatorMath {
    const val JAW_PERIOD = 13
    const val JAW_SHIFT = 8
    const val TEETH_PERIOD = 8
    const val TEETH_SHIFT = 5
    const val LIPS_PERIOD = 5
    const val LIPS_SHIFT = 3

    fun calculate(highs: List<Double>, lows: List<Double>): WilliamsAlligatorSeries {
        if (highs.size != lows.size) {
            val invalid = List(maxOf(highs.size, lows.size)) { Double.NaN }
            return WilliamsAlligatorSeries(invalid, invalid, invalid)
        }
        val medianPrices = highs.indices.map { i ->
            val high = highs[i]
            val low = lows[i]
            if (high.isFinite() && low.isFinite() && high > 0.0 && low > 0.0 && high >= low) {
                (high + low) / 2.0
            } else {
                Double.NaN
            }
        }
        return calculateFromMedianPrices(medianPrices)
    }

    fun calculateFromMedianPrices(medianPrices: List<Double>): WilliamsAlligatorSeries {
        val safeMedianPrices = medianPrices.map { value ->
            value.takeIf { it.isFinite() && it > 0.0 } ?: Double.NaN
        }
        return WilliamsAlligatorSeries(
            jaw = shift(smma(safeMedianPrices, JAW_PERIOD), JAW_SHIFT),
            teeth = shift(smma(safeMedianPrices, TEETH_PERIOD), TEETH_SHIFT),
            lips = shift(smma(safeMedianPrices, LIPS_PERIOD), LIPS_SHIFT)
        )
    }

    private fun smma(values: List<Double>, period: Int): List<Double> {
        if (values.isEmpty()) return emptyList()
        val output = MutableList(values.size) { Double.NaN }
        output[0] = values[0]
        for (i in 1 until values.size) {
            val prior = output[i - 1]
            val current = values[i]
            output[i] = if (prior.isFinite() && current.isFinite()) {
                (prior * (period - 1) + current) / period
            } else {
                Double.NaN
            }
        }
        return output
    }

    private fun shift(values: List<Double>, bars: Int): List<Double> =
        List(values.size) { index ->
            val source = index - bars
            if (source >= 0) values[source] else Double.NaN
        }
}
