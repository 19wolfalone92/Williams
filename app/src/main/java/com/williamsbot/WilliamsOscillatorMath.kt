package com.williamsbot

/**
 * Pure Bill Williams oscillator calculations shared by Spot campaign decisions,
 * diagnostics, and golden-vector tests.
 *
 * AO = SMA(5, Median Price) - SMA(34, Median Price).
 * AC = AO - SMA(5, AO).
 *
 * The AO slow window is exactly 34 observations: [index - 33, index].
 * Missing/warm-up values stay NaN and must not be interpreted as zero/momentum.
 */
internal object WilliamsOscillatorMath {
    const val AO_FAST_PERIOD = 5
    const val AO_SLOW_PERIOD = 34
    const val AC_SMA_PERIOD = 5

    fun ao(medianPrices: List<Double>, index: Int): Double {
        if (index < AO_SLOW_PERIOD - 1 || index >= medianPrices.size) return Double.NaN
        val fastStart = index - AO_FAST_PERIOD + 1
        val slowStart = index - AO_SLOW_PERIOD + 1
        val fast = medianPrices.subList(fastStart, index + 1)
        val slow = medianPrices.subList(slowStart, index + 1)
        if (!fast.all { it.isFinite() } || !slow.all { it.isFinite() }) return Double.NaN
        return fast.average() - slow.average()
    }

    fun aoSeries(medianPrices: List<Double>): List<Double> =
        medianPrices.indices.map { index -> ao(medianPrices, index) }

    fun ac(medianPrices: List<Double>, index: Int): Double {
        if (index >= medianPrices.size || index < 0) return Double.NaN
        val ao = aoSeries(medianPrices)
        val start = index - AC_SMA_PERIOD + 1
        if (start < 0) return Double.NaN
        val window = ao.subList(start, index + 1)
        if (!window.all { it.isFinite() }) return Double.NaN
        val current = ao[index]
        return current - window.average()
    }

    fun acSeries(medianPrices: List<Double>): List<Double> =
        medianPrices.indices.map { index -> ac(medianPrices, index) }

    /**
     * True only on the third consecutive rising/falling AO bar. A green
     * histogram bar is AO[i] > AO[i-1]; the three green bars therefore need
     * four finite AO values including the comparison baseline.
     */
    fun isThirdSameColorAoBar(
        aoValues: List<Double>,
        index: Int,
        side: String
    ): Boolean {
        if (index < 3 || index >= aoValues.size) return false
        val values = aoValues.subList(index - 3, index + 1)
        if (!values.all { it.isFinite() }) return false
        return when (side.uppercase()) {
            "LONG" -> values[1] > values[0] &&
                values[2] > values[1] &&
                values[3] > values[2]
            "SHORT" -> values[1] < values[0] &&
                values[2] < values[1] &&
                values[3] < values[2]
            else -> false
        }
    }
}
