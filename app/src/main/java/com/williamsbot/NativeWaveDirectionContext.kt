package com.williamsbot

import kotlin.math.max
import kotlin.math.min

/**
 * Diagnostic-only native counterpart of the Python Futures directional overlay.
 * These signed points only break ties between *already valid* closed-H1 Wise-Man
 * entry signals AFTER the authoritative D1/H4 gate. This is an uncalibrated
 * engineering heuristic, NOT an original Bill Williams book entry rule.
 *
 * Wave numbers and 50% retracements are provisional, never absolute forecasts.
 */
internal fun nativeDirectionalHeuristicRank(
    signalType: String,
    waveDirection: String,
    wavePosition: Int,
    exhaustionRisk: Double,
    bullishAlligator: Boolean,
    bearishAlligator: Boolean,
    ao: Double,
    ac: Double,
    bearishDivergence: Boolean,
    bullishDivergence: Boolean
): Double {
    val side = campaignSignalDirection(signalType) ?: return 0.0
    val long = side == "LONG"
    var score = 0.0
    if (bullishAlligator.xor(bearishAlligator)) {
        score += if (bullishAlligator == long) 3.0 else -3.0
    }
    if (ao.isFinite() && ao != 0.0) {
        score += if ((ao > 0) == long) 1.0 else -1.0
    }
    if (ac.isFinite() && ac != 0.0) {
        score += if ((ac > 0) == long) 0.5 else -0.5
    }
    val proposed = if (long) "UP" else "DOWN"
    when (waveDirection) {
        proposed -> score += 1.0
        "UP", "DOWN" -> score -= 1.0
    }
    // Exhaustion is caution, never an opposite trade trigger or a hard veto.
    if (waveDirection == proposed && wavePosition == 5 &&
        exhaustionRisk.isFinite() && exhaustionRisk >= 60.0) score -= 1.0
    if (if (long) bullishDivergence else bearishDivergence) score += 2.0
    if (if (long) bearishDivergence else bullishDivergence) score -= 2.0
    return score.coerceIn(-8.0, 8.0)
}

/** Accelerator Oscillator = current AO minus SMA of the latest five closed AO bars. */
internal fun nativeAcFromClosedAo(closedAo: List<Double>): Double {
    if (closedAo.size < 5 || !closedAo.takeLast(5).all { it.isFinite() }) {
        return Double.NaN
    }
    val five = closedAo.takeLast(5)
    return five.last() - five.average()
}

/**
 * Compare price extremes with AO extrema of the corresponding *complete legs*.
 * Require five-bar Williams-style confirmed swings (two candles each side).
 * A momentum peak may occur several bars before a price extremum.
 *
 * The last bar supplied must already be closed. No future bars are used and
 * no order authorisation results from this routine.
 */
internal fun nativeConfirmedAoLegDivergence(
    highs: List<Double>,
    lows: List<Double>,
    ao: List<Double>,
    bearish: Boolean,
    lookback: Int = 60
): Boolean {
    val n = highs.size
    if (n < 45 || lows.size != n || ao.size != n || lookback < 10) return false
    if (!highs.all { it.isFinite() } || !lows.all { it.isFinite() }) return false
    val from = max(2, n - 2 - lookback)
    val peaks = mutableListOf<Int>()
    val opposite = mutableListOf<Int>()
    for (i in from until n - 2) {
        val high = highs[i] > highs[i - 1] && highs[i] > highs[i - 2] &&
            highs[i] > highs[i + 1] && highs[i] > highs[i + 2]
        val low = lows[i] < lows[i - 1] && lows[i] < lows[i - 2] &&
            lows[i] < lows[i + 1] && lows[i] < lows[i + 2]
        if (high && low) continue // do not invent an ordering for dual pivots
        if (if (bearish) high else low) peaks.add(i)
        if (if (bearish) low else high) opposite.add(i)
    }
    if (peaks.size < 2 || opposite.size < 2) return false
    val valid = mutableListOf<Pair<Int, Double>>()
    for (peak in peaks) {
        val previousOpposite = opposite.lastOrNull { it < peak } ?: continue
        val numbers = ((previousOpposite + 1)..peak).mapNotNull { bar ->
            ao[bar].takeIf { it.isFinite() }
        }
        if (numbers.isEmpty()) continue
        val extreme = if (bearish) numbers.maxOrNull()!! else numbers.minOrNull()!!
        valid.add(peak to extreme)
    }
    if (valid.size < 2) return false
    val (oldPivot, oldAo) = valid[valid.lastIndex - 1]
    val (newPivot, newAo) = valid.last()
    // Extremes on the proper side of zero reduce cross-regime false positives.
    return if (bearish) {
        highs[newPivot] > highs[oldPivot] && oldAo > 0.0 && newAo > 0.0 && newAo < oldAo
    } else {
        lows[newPivot] < lows[oldPivot] && oldAo < 0.0 && newAo < 0.0 && newAo > oldAo
    }
}
