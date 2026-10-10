package com.williamsbot

/**
 * Pure source-profile calculation for TC2's price-bar structural trail.
 *
 * This does not round to exchange ticks: it returns one tick beyond the
 * selected closed-bar extreme. The exchange adapter applies direction-aware
 * PRICE_FILTER rounding before placing a replacement protective order.
 */
data class FuturesPriceBarTrailCandidate(
    val direction: String,
    val trailingBars: Int,
    val structuralExtreme: Double,
    val rawStopPrice: Double
)

object FuturesPriceBarTrailPolicy {
    fun propose(
        direction: String,
        lows: List<Double>,
        highs: List<Double>,
        tickSize: Double,
        trailingBars: Int = 3
    ): FuturesPriceBarTrailCandidate {
        val side = direction.trim().uppercase()
        require(side == "LONG" || side == "SHORT") {
            "TC2 trailing direction must be LONG or SHORT"
        }
        require(trailingBars == 3 || trailingBars == 5) {
            "TC2 trailingBars must be 3 or 5"
        }
        require(tickSize.isFinite() && tickSize > 0.0) {
            "TC2 tick size must be finite and positive"
        }
        require(lows.size == highs.size && lows.size >= trailingBars) {
            "TC2 trailing requires equal-length high/low lists with enough closed bars"
        }

        val selectedLows = lows.takeLast(trailingBars)
        val selectedHighs = highs.takeLast(trailingBars)
        require(selectedLows.all { it.isFinite() && it > 0.0 }) {
            "TC2 trailing lows contain invalid values"
        }
        require(selectedHighs.all { it.isFinite() && it > 0.0 }) {
            "TC2 trailing highs contain invalid values"
        }

        val extreme = if (side == "LONG") selectedLows.minOrNull()!! else selectedHighs.maxOrNull()!!
        val rawStop = if (side == "LONG") extreme - tickSize else extreme + tickSize
        require(rawStop.isFinite() && rawStop > 0.0) {
            "TC2 raw structural stop is invalid"
        }
        return FuturesPriceBarTrailCandidate(side, trailingBars, extreme, rawStop)
    }
}
