package com.williamsbot

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Closed-H1 protection policy for an exchange-confirmed Futures position.
 *
 * Williams structure: tighten behind the most recent *confirmed* five-bar fractal
 * on the protective side (LOW fractal for LONG, HIGH fractal for SHORT). The
 * additional +1R break-even rule is a risk overlay, not a quoted Williams rule.
 * No order mutation is performed here; the coordinator validates replacement
 * protection against Binance and atomically journals the change.
 */
internal data class FuturesTrailBar(val high: Double, val low: Double)

internal data class FuturesTrailProposal(
    val stopPrice: Double,
    val source: String,
    val fractalCenterIndex: Int? = null
)

internal fun proposeWilliamsFuturesStopTrail(
    direction: String,
    closedBars: List<FuturesTrailBar>,
    entryPrice: Double,
    initialStop: Double,
    currentStop: Double,
    markPrice: Double,
    tickSize: Double,
    feeBufferPerSide: Double,
    slippageBuffer: Double,
    breakevenTriggerR: Double = 1.0
): FuturesTrailProposal? {
    val isLong = direction == "LONG"
    if (!isLong && direction != "SHORT") return null
    val prices = listOf(entryPrice, initialStop, currentStop, markPrice, tickSize)
    if (prices.any { !it.isFinite() || it <= 0.0 }) return null
    if (listOf(feeBufferPerSide, slippageBuffer, breakevenTriggerR)
            .any { !it.isFinite() || it < 0.0 } || breakevenTriggerR == 0.0) return null
    if ((isLong && initialStop >= entryPrice) || (!isLong && initialStop <= entryPrice)) return null
    if (closedBars.any { !it.high.isFinite() || !it.low.isFinite() ||
                it.low <= 0.0 || it.high < it.low }) return null

    // Avoid immediate exchange-side trigger/rejection and never loosen protection.
    // A fixed minimum + price-relative distance also absorbs single-tick mark noise.
    val markGap = max(2.0 * tickSize, markPrice * 0.00025)
    fun permitted(stop: Double): Boolean = stop.isFinite() && stop > 0.0 &&
        (if (isLong) stop > currentStop + tickSize * 0.001 &&
            stop + markGap < markPrice
        else stop < currentStop - tickSize * 0.001 &&
            stop - markGap > markPrice)

    fun quantize(price: Double): Double =
        if (isLong) floor(price / tickSize + 1e-10) * tickSize
        else ceil(price / tickSize - 1e-10) * tickSize

    var fractal: FuturesTrailProposal? = null
    // Only closed H1 bars. The last two bars to the right of the pivot must
    // already exist and be closed. Equal-extreme plateaus are grouped, as for
    // the Williams signal detector, rather than counted as multiple fractals.
    if (closedBars.size >= 5) {
        for (i in closedBars.lastIndex - 2 downTo max(2, closedBars.lastIndex - 32)) {
            val extreme = if (isLong) closedBars[i].low else closedBars[i].high
            fun equalAt(j: Int): Boolean =
                (if (isLong) closedBars[j].low else closedBars[j].high) == extreme
            if (equalAt(i + 1)) continue // only the last member of a plateau
            var start = i
            while (start > 0 && equalAt(start - 1)) start--
            if (start < 2) continue
            fun lowerThanExtreme(j: Int): Boolean =
                if (isLong) closedBars[j].low > extreme else closedBars[j].high < extreme
            if (lowerThanExtreme(start - 1) && lowerThanExtreme(start - 2) &&
                lowerThanExtreme(i + 1) && lowerThanExtreme(i + 2)) {
                val stop = quantize(if (isLong) extreme - tickSize else extreme + tickSize)
                if (permitted(stop)) {
                    fractal = FuturesTrailProposal(stop, "CONFIRMED_H1_FRACTAL", i)
                }
                // Use the latest confirmed pivot only. Do not select a stale
                // earlier fractal merely because the new structure is unsuitable.
                break
            }
        }
    }

    var breakeven: FuturesTrailProposal? = null
    val initialRiskDistance = kotlin.math.abs(entryPrice - initialStop)
    val favourableMove = if (isLong) markPrice - entryPrice else entryPrice - markPrice
    if (favourableMove >= initialRiskDistance * breakevenTriggerR) {
        // Estimated fee/slippage allowance. This is NOT a guaranteed net-zero
        // fill; gaps, funding and execution slippage can still create a loss.
        val costDistance = entryPrice * (2.0 * feeBufferPerSide + slippageBuffer)
        val grossBe = if (isLong) entryPrice + costDistance else entryPrice - costDistance
        val stop = if (isLong) ceil(grossBe / tickSize - 1e-10) * tickSize
                   else floor(grossBe / tickSize + 1e-10) * tickSize
        if (permitted(stop)) breakeven = FuturesTrailProposal(stop, "BREAKEVEN_AFTER_1R")
    }

    return listOfNotNull(fractal, breakeven)
        .let { candidates ->
            if (isLong) candidates.maxByOrNull { it.stopPrice }
            else candidates.minByOrNull { it.stopPrice }
        }
}
