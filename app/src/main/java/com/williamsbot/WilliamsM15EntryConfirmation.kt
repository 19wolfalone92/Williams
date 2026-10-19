package com.williamsbot

/** Evidence from exchange-confirmed, contiguous M15 candles only. */
internal data class WilliamsM15Bar(
    val openMs: Long, val high: Double, val low: Double, val close: Double
)

internal data class WilliamsM15EntryEvidence(
    val state: String,
    val pivotOpenMs: Long? = null,
    val confirmedAtMs: Long? = null,
    val breakoutAtMs: Long? = null,
    val triggerPrice: Double? = null
) {
    val confirmed: Boolean get() = state == "CONFIRMED"
}

/** H1 remains the signal authority. Only a WM1 reversal lacking H4 trend
 * alignment needs supplementary M15 price-structure evidence before its
 * unfilled H1 stop-entry is armed. This is project policy, not a book rule.
 */
internal fun williamsRequiresM15ReversalProof(signalType: String, h4Bull: Boolean, h4Bear: Boolean): Boolean =
    when (signalType.uppercase()) {
        "REVERSAL", "LONG_REVERSAL" -> !h4Bull
        "SHORT_REVERSAL" -> !h4Bear
        else -> false
    }

/**
 * Look for a five-candle M15 fractal in the H1 direction, first confirmed
 * on/after the H1 signal close, then breached by a SUBSEQUENT closed M15
 * candle. A current close on the wrong side of the pivot revokes that proof.
 * All times are candle-open milliseconds, except confirmedAtMs/breakoutAtMs
 * which are candle-close milliseconds. No future or live M15 bars are used.
 * Stop-entry still belongs to the original H1 signal, not this M15 pivot.
 */
internal fun williamsM15ReversalEvidence(
    direction: String,
    h1KnownAtMs: Long,
    observedAtMs: Long,
    tick: Double,
    bars: List<WilliamsM15Bar>
): WilliamsM15EntryEvidence {
    val step = 900_000L
    if (direction !in setOf("LONG", "SHORT") || h1KnownAtMs <= 0 ||
        observedAtMs < h1KnownAtMs || !tick.isFinite() || tick <= 0 || bars.size < 6) {
        return WilliamsM15EntryEvidence("INVALID_INPUT")
    }
    if (bars.any { it.openMs <= 0 || it.openMs > observedAtMs - step ||
            !it.high.isFinite() || !it.low.isFinite() || !it.close.isFinite() ||
            it.low <= 0 || it.high < it.low || it.close !in it.low..it.high } ||
        bars.zipWithNext().any { (a,b) -> b.openMs - a.openMs != step }) {
        return WilliamsM15EntryEvidence("UNCLOSED_OR_GAPPED_M15")
    }
    val long = direction == "LONG"
    fun extreme(i: Int) = if (long) bars[i].high else bars[i].low
    for (i in bars.lastIndex - 3 downTo 2) {
        val p = extreme(i)
        // Two full bars on both sides. Equal highs/lows need a separate plateau
        // proof and are rejected here rather than guessed to be a valid pivot.
        if (!(1..2).all { d ->
                if (long) p > extreme(i-d) && p > extreme(i+d)
                else p < extreme(i-d) && p < extreme(i+d)
            }) continue
        val confirmation = bars[i+2].openMs + step
        if (confirmation < h1KnownAtMs) continue
        val trigger = if (long) p + tick else p - tick
        if (trigger <= 0) continue
        val breakout = (i+3..bars.lastIndex).firstOrNull { j ->
            bars[j].openMs >= confirmation &&
                (if (long) bars[j].high >= trigger else bars[j].low <= trigger)
        } ?: continue
        if (if (long) bars.last().close <= p else bars.last().close >= p) continue
        return WilliamsM15EntryEvidence("CONFIRMED", bars[i].openMs, confirmation,
            bars[breakout].openMs + step, trigger)
    }
    return WilliamsM15EntryEvidence("AWAITING_DIRECTIONAL_M15_FRACTAL_BREAK")
}
