package com.williamsbot

/**
 * Williams five-bar fractal confirmation with equal-extreme group support.
 *
 * A candidate is not exposed until two qualifying bars to the right exist.
 * Adjacent equal extremes extend the formation; the last equal bar is the
 * candidate anchor. The returned confirmation index is the bar on which the
 * second qualifying right-side bar became available, not the pivot index.
 */
internal object WilliamsFractal {
    internal data class Confirmed(
        val centerIndex: Int,
        val confirmationIndex: Int,
        val level: Double
    )

    fun latestUp(highs: List<Double>, throughIndex: Int = highs.lastIndex): Confirmed? =
        latest(highs, throughIndex, lowerSide = true)

    fun latestDown(lows: List<Double>, throughIndex: Int = lows.lastIndex): Confirmed? =
        latest(lows, throughIndex, lowerSide = false)

    private fun latest(
        values: List<Double>,
        throughIndex: Int,
        lowerSide: Boolean
    ): Confirmed? {
        if (values.size < 5) return null
        val through = throughIndex.coerceAtMost(values.lastIndex)
        if (through < 4) return null

        for (center in through downTo 2) {
            val level = values[center]
            if (!level.isFinite()) continue

            // Adjacent equal extremes belong to one formation; only its last
            // bar can become the anchor once two right-side bars confirm it.
            if (center + 1 <= through && values[center + 1] == level) continue

            if (!leftSideQualifies(values, center, level, lowerSide)) continue
            val confirmation = rightConfirmation(values, center, through, level, lowerSide)
                ?: continue
            return Confirmed(center, confirmation, level)
        }
        return null
    }

    private fun leftSideQualifies(
        values: List<Double>,
        center: Int,
        level: Double,
        lowerSide: Boolean
    ): Boolean {
        var qualifying = 0
        var index = center - 1
        while (index >= 0 && qualifying < 2) {
            val value = values[index]
            if (!value.isFinite()) return false
            if (value == level) {
                index--
                continue
            }
            val valid = if (lowerSide) value < level else value > level
            if (!valid) return false
            qualifying++
            index--
        }
        return qualifying >= 2
    }

    private fun rightConfirmation(
        values: List<Double>,
        center: Int,
        through: Int,
        level: Double,
        lowerSide: Boolean
    ): Int? {
        var qualifying = 0
        for (index in center + 1..through) {
            val value = values[index]
            if (!value.isFinite()) return null
            if (value == level) continue
            val valid = if (lowerSide) value < level else value > level
            if (!valid) return null
            qualifying++
            if (qualifying == 2) return index
        }
        return null
    }
}
