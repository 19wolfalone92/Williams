package com.williamsbot

import java.math.BigDecimal
import kotlin.math.max

/**
 * Exactly one native Binance tick beyond a CLOSED bar's source extreme.
 * Do not divide, floor or ceil a floating-point (price +/- tick) expression:
 * 79524.8 + 0.1, for example, can round above the exact decimal and move the
 * submitted stop by a whole extra tick. Off-grid source data cannot authorize
 * a discretionary STOP replacement. Initial exchange-confirmed protection is
 * left untouched when the source cannot be validated.
 * TC2 chapter 12, PDF pp.156,165: three/five-bar and fractal stop placement.
 */
internal fun tc2ExactStructuralStop(direction: String, extreme: Double, tick: Double): Double? {
    if (direction !in setOf("LONG", "SHORT") ||
        !extreme.isFinite() || extreme <= 0.0 || !tick.isFinite() || tick <= 0.0) return null
    val price = BigDecimal.valueOf(extreme)
    val step = BigDecimal.valueOf(tick)
    if (price.remainder(step).signum() != 0) return null
    val result = price.add(if (direction == "LONG") step.negate() else step).toDouble()
    return result.takeIf { it.isFinite() && it > 0.0 }
}

/**
 * Futures trailing policy. Only exchange-confirmed OPEN_PROTECTED campaigns may
 * consume this proposal. This function never places or cancels Binance orders.
 *
 * Fractal trailing follows confirmed five-bar swing structure (two bars to the
 * right). TC2 uses structural 3/5-bar and confirmed-fractal protection ONLY.
 * Automatic 1R/cost-buffered break-even is retired and never proposed.
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

@Suppress("UNUSED_PARAMETER") // legacy arguments remain source-compatible, but cannot re-enable 1R
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
    feeAndSlippageAllowancePct: Double = 0.0035,
    breakEvenEnabled: Boolean = false
): FuturesStopTrailProposal? {
    if (direction != "LONG" && direction != "SHORT") return null
    if (!oldStop.isFinite() || !markPrice.isFinite() || !tickSize.isFinite() ||
        oldStop <= 0.0 || markPrice <= 0.0 || tickSize <= 0.0) return null
    if (bars.any { it.openTimeMs<0L || it.openTimeMs%WILLIAMS_H1_MS!=0L } ||
        bars.zipWithNext().any { (a,b) -> b.openTimeMs-a.openTimeMs != WILLIAMS_H1_MS }) return null

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
        // TC2 figs11.1/11.2: equality can be separated by an inner bar.
        // Count two strict neighbours to the left, skipping equal extremes,
        // exactly as for ENTRY. Only the last tied extremum is the pivot.
        for (i in bars.lastIndex - 2 downTo 2) {
            if (bars[i].openTimeMs < openedObservedAtMs) break
            val extreme = if (direction == "LONG") bars[i].low else bars[i].high
            fun sameExtreme(j: Int): Boolean =
                if (direction == "LONG") bars[j].low == extreme else bars[j].high == extreme
            if (sameExtreme(i + 1)) continue
            var start = i
            fun onProtectiveSide(j: Int): Boolean =
                if (direction == "LONG") bars[j].low > extreme else bars[j].high < extreme
            var count=0;var j=i-1
            while(j>=0 && count<2) {
                if(sameExtreme(j)) start=j
                else if(onProtectiveSide(j)) count++
                else break
                j--
            }
            if(count<2 || bars[start].openTimeMs<openedObservedAtMs ||
                !onProtectiveSide(i+1) || !onProtectiveSide(i+2)) continue
            val price = tc2ExactStructuralStop(direction, extreme, tickSize)
            if (price != null && usable(price)) {
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

    // There is deliberately no entry-price/1R trigger. A favorable price
    // excursion alone never moves TC2 protection without book structure.

    return if (direction == "LONG") proposals.maxByOrNull { it.stop }
    else proposals.minByOrNull { it.stop }
}

/** TC2 chapter 12: structural 3/5 CLOSED price-bar and confirmed fractal stop.
 * No discretionary 1R/break-even stop; unknown original R cannot prevent a
 * structurally valid protective move. */
internal fun planWilliamsCampaignStop(
    direction: String, oldStop: Double, actualEntry: Double, initialStop: Double?,
    markPrice: Double, tickSize: Double, openedObservedAtMs: Long,
    bars: List<FuturesTrailBar>, window: Int = 5, feeAllowance: Double = .0035
): FuturesStopTrailProposal? {
    // Never infer an H1 3/5 bar stop from pre-entry candles when the
    // exchange-confirmed position opening timestamp is unknown. Reconciliation
    // must establish ownership/protection first. No fallback to invented time.
    if (openedObservedAtMs<=0L) return null
    if (direction !in listOf("LONG","SHORT") || window !in listOf(3,5) ||
        listOf(oldStop,markPrice,tickSize).any { !it.isFinite() || it <= 0 } ||
        bars.any { !it.high.isFinite() || !it.low.isFinite() || it.low <= 0 || it.high < it.low ||
            it.openTimeMs<0L || it.openTimeMs%WILLIAMS_H1_MS!=0L } ||
        bars.zipWithNext().any { (a,b) -> b.openTimeMs-a.openTimeMs != WILLIAMS_H1_MS }) return null
    val proposals = mutableListOf<FuturesStopTrailProposal>()
    // Legacy signature retained for existing call sites. Break-even and its
    // fee buffer are intentionally ignored regardless of caller flags.
    planFuturesStopTrail(direction,oldStop,actualEntry,initialStop ?: Double.NaN,markPrice,tickSize,
        openedObservedAtMs,bars,breakEvenEnabled=false)?.let { proposals += it }
    if (bars.size >= window && bars.last().openTimeMs+3_600_000L > openedObservedAtMs) {
        val sourceExtreme = if (direction == "LONG") bars.takeLast(window).minOf { it.low }
            else bars.takeLast(window).maxOf { it.high }
        val price = tc2ExactStructuralStop(direction, sourceExtreme, tickSize)
        val gap = max(2*tickSize,markPrice*.00025)
        if (price != null && if (direction == "LONG") price > oldStop+tickSize*.5 && price+gap < markPrice
            else price < oldStop-tickSize*.5 && price-gap > markPrice) {
            proposals += FuturesStopTrailProposal(price,"TC2_LAST_${window}_CLOSED_BARS")
        }
    }
    return if (direction == "LONG") proposals.maxByOrNull { it.stop } else proposals.minByOrNull { it.stop }
}
