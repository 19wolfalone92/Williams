package com.williamsbot

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Futures trailing policy. Only exchange-confirmed OPEN_PROTECTED campaigns may
 * consume this proposal. This function never places or cancels Binance orders.
 *
 * Fractal trailing follows confirmed five-bar swing structure (two bars to the
 * right); fee-aware 1R break-even is an explicitly engineered risk overlay,
 * NOT a claim that Bill Williams prescribed a universal 1R rule.
 */
internal data class FuturesTrailBar(
    val openTimeMs: Long,
    val high: Double,
    val low: Double
)

internal data class FuturesStopTrailProposal(
    val stop: Double,
    val reason: String,
    val fractalAtMs: Long? = null
)

internal fun planFuturesStopTrail(
    direction: String,
    oldStop: Double,
    actualEntry: Double,
    initialStop: Double,
    markPrice: Double,
    tickSize: Double,
    openedObservedAtMs: Long,
    bars: List<FuturesTrailBar>,
    breakEvenAtR: Double = 1.0,
    feeAndSlippageAllowancePct: Double = 0.0035
): FuturesStopTrailProposal? {
    if (direction != "LONG" && direction != "SHORT") return null
    if (listOf(oldStop, actualEntry, initialStop, markPrice, tickSize, breakEvenAtR,
            feeAndSlippageAllowancePct).any { !it.isFinite() }) return null
    if (oldStop <= 0.0 || actualEntry <= 0.0 || initialStop <= 0.0 ||
        markPrice <= 0.0 || tickSize <= 0.0 || breakEvenAtR <= 0.0 ||
        feeAndSlippageAllowancePct < 0.0) return null
    if (direction == "LONG" && initialStop >= actualEntry) return null
    if (direction == "SHORT" && initialStop <= actualEntry) return null

    fun floorTick(price: Double): Double = floor(price / tickSize + 1e-12) * tickSize
    fun ceilTick(price: Double): Double = ceil(price / tickSize - 1e-12) * tickSize
    // Avoid sending Binance a replacement which can immediately trigger or
    // be rejected due to a mark-price tick during the REST round trip.
    val minimumMarkGap = max(2.0 * tickSize, markPrice * 0.00025)
    fun usable(price: Double): Boolean =
        price.isFinite() && price > 0.0 && if (direction == "LONG") {
            price > oldStop + tickSize * 0.5 && price + minimumMarkGap < markPrice
        } else {
            price < oldStop - tickSize * 0.5 && price - minimumMarkGap > markPrice
        }

    val proposals = mutableListOf<FuturesStopTrailProposal>()
    if (bars.size >= 5 && openedObservedAtMs > 0L &&
        bars.all { it.high.isFinite() && it.low.isFinite() && it.high >= it.low &&
            it.low > 0.0 }) {
        // The most recent five-bar confirmed fractal only. The pivot must
        // have formed after the live position was first observed. Group
        // equal-extreme plateaus just like Williams' ENTRY detector: only the
        // final bar of a plateau is eligible, with two closed bars to the right.
        for (i in bars.lastIndex - 2 downTo 2) {
            if (bars[i].openTimeMs < openedObservedAtMs) break
            val extreme = if (direction == "LONG") bars[i].low else bars[i].high
            fun sameExtreme(j: Int): Boolean =
                if (direction == "LONG") bars[j].low == extreme else bars[j].high == extreme
            if (sameExtreme(i + 1)) continue
            var start = i
            while (start > 0 && sameExtreme(start - 1)) start--
            if (start < 2 || bars[start].openTimeMs < openedObservedAtMs) continue
            fun onProtectiveSide(j: Int): Boolean =
                if (direction == "LONG") bars[j].low > extreme else bars[j].high < extreme
            if (!(onProtectiveSide(start - 1) && onProtectiveSide(start - 2) &&
                onProtectiveSide(i + 1) && onProtectiveSide(i + 2))) continue
            val price = if (direction == "LONG") floorTick(extreme - tickSize)
                else ceilTick(extreme + tickSize)
            if (usable(price)) {
                // The most recent *usable* confirmed post-entry pivot is the
                // appropriate candidate. A more recent pivot may produce a
                // stop already crossed by mark price; do not let it mask an
                // older, still-valid improvement.
                proposals += FuturesStopTrailProposal(price, "CONFIRMED_H1_FRACTAL", bars[i].openTimeMs)
                break
            }
            // Keep searching older confirmed post-entry pivots; never widen
            // the stop or cross the mark-price safety gap.
        }
    }

    val oneR = abs(initialStop - actualEntry)
    val favorable = if (direction == "LONG") markPrice - actualEntry else actualEntry - markPrice
    if (oneR > 0.0 && favorable >= breakEvenAtR * oneR) {
        // Cover a configured fee/slippage allowance. Exact net flatness cannot
        // be guaranteed across funding, gaps and unexpected execution slippage.
        val rawBreakEven = if (direction == "LONG") {
            actualEntry * (1.0 + feeAndSlippageAllowancePct)
        } else {
            actualEntry * (1.0 - feeAndSlippageAllowancePct)
        }
        val breakEven = if (direction == "LONG") ceilTick(rawBreakEven) else floorTick(rawBreakEven)
        if (usable(breakEven)) {
            proposals += FuturesStopTrailProposal(breakEven, "COST_BUFFERED_BREAKEVEN_1R")
        }
    }

    return if (direction == "LONG") proposals.maxByOrNull { it.stop }
    else proposals.minByOrNull { it.stop }
}
