package com.williamsbot

/**
 * A Williams fractal is not always confirmed exactly two calendar bars after
 * its center. Equal right-side extremes do not count as lower highs / higher
 * lows, so some source-defined patterns need six or more bars.
 *
 * The returned confirmationIndex is the first bar that supplies the required
 * number of strictly qualifying right-side bars. Callers must use the actual
 * confirmation index for time-sensitive gates; centerIndex is for the pivot
 * price and its structural stop.
 */
internal data class WilliamsFractalConfirmation(
    val centerIndex: Int,
    val confirmationIndex: Int,
    val level: Double
)

internal object WilliamsFractalMath {
    const val DEFAULT_LEFT_BARS = 2
    const val DEFAULT_RIGHT_QUALIFIERS = 2

    fun confirmUp(
        highs: List<Double>,
        centerIndex: Int,
        throughIndex: Int = highs.lastIndex,
        leftBars: Int = DEFAULT_LEFT_BARS,
        requiredRightBars: Int = DEFAULT_RIGHT_QUALIFIERS
    ): WilliamsFractalConfirmation? {
        if (leftBars < 1 || requiredRightBars < 1 || centerIndex < leftBars) return null
        val limit = minOf(throughIndex, highs.lastIndex)
        if (centerIndex >= limit || centerIndex !in highs.indices) return null

        val pivot = highs[centerIndex]
        if (!pivot.isFinite()) return null
        for (i in centerIndex - leftBars until centerIndex) {
            val value = highs[i]
            if (!value.isFinite() || pivot <= value) return null
        }

        var lowerCount = 0
        for (i in centerIndex + 1..limit) {
            val value = highs[i]
            if (!value.isFinite() || value > pivot) return null
            if (value < pivot) lowerCount++
            // An equal high neither confirms nor invalidates the candidate.
            if (lowerCount >= requiredRightBars) {
                return WilliamsFractalConfirmation(centerIndex, i, pivot)
            }
        }
        return null
    }

    fun confirmDown(
        lows: List<Double>,
        centerIndex: Int,
        throughIndex: Int = lows.lastIndex,
        leftBars: Int = DEFAULT_LEFT_BARS,
        requiredRightBars: Int = DEFAULT_RIGHT_QUALIFIERS
    ): WilliamsFractalConfirmation? {
        if (leftBars < 1 || requiredRightBars < 1 || centerIndex < leftBars) return null
        val limit = minOf(throughIndex, lows.lastIndex)
        if (centerIndex >= limit || centerIndex !in lows.indices) return null

        val pivot = lows[centerIndex]
        if (!pivot.isFinite()) return null
        for (i in centerIndex - leftBars until centerIndex) {
            val value = lows[i]
            if (!value.isFinite() || pivot >= value) return null
        }

        var higherCount = 0
        for (i in centerIndex + 1..limit) {
            val value = lows[i]
            if (!value.isFinite() || value < pivot) return null
            if (value > pivot) higherCount++
            // An equal low neither confirms nor invalidates the candidate.
            if (higherCount >= requiredRightBars) {
                return WilliamsFractalConfirmation(centerIndex, i, pivot)
            }
        }
        return null
    }

    fun latestUp(
        highs: List<Double>,
        throughIndex: Int = highs.lastIndex,
        lookbackBars: Int = Int.MAX_VALUE
    ): WilliamsFractalConfirmation? = latest(
        highs = highs,
        throughIndex = throughIndex,
        lookbackBars = lookbackBars,
        confirmation = ::confirmUp
    )

    fun latestDown(
        lows: List<Double>,
        throughIndex: Int = lows.lastIndex,
        lookbackBars: Int = Int.MAX_VALUE
    ): WilliamsFractalConfirmation? = latest(
        highs = lows,
        throughIndex = throughIndex,
        lookbackBars = lookbackBars,
        confirmation = ::confirmDown
    )

    private fun latest(
        highs: List<Double>,
        throughIndex: Int,
        lookbackBars: Int,
        confirmation: (List<Double>, Int, Int, Int, Int) -> WilliamsFractalConfirmation?
    ): WilliamsFractalConfirmation? {
        if (highs.isEmpty() || throughIndex < 0 || lookbackBars < 1) return null
        val limit = minOf(throughIndex, highs.lastIndex)
        val earliest = maxOf(DEFAULT_LEFT_BARS, limit - lookbackBars + 1)
        for (center in limit downTo earliest) {
            val found = confirmation(
                highs,
                center,
                limit,
                DEFAULT_LEFT_BARS,
                DEFAULT_RIGHT_QUALIFIERS
            )
            if (found != null) return found
        }
        return null
    }
}
