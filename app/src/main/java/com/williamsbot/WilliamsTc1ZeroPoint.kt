package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

/**
 * Trading Chaos (1995), chapter 9, PDF pp. 81-84: five *separate* signs
 * around a POSSIBLE Zero Point, not an infallible Elliott wave detector.
 *
 * This is intentionally read-only. A pair of same-side fractals is NOT
 * sufficient to label wave 3 and wave 5, and Binance kline base volume
 * is not the source book's tick-volume/Profitunity MFI.
 */
internal fun williamsTc1ZeroPointObservations(
    input: List<WilliamsWaveBar>,
    asOfMs: Long,
    frameMs: Long = WILLIAMS_H1_MS
): JSONObject {
    fun empty(reason: String): JSONObject = JSONObject()
        .put("schema", "TC1_ZERO_POINT_FIVE_SIGNS_V1")
        .put("state", reason)
        .put("can_place_orders", false).put("can_close_positions", false)
        .put("wave_degree_certified", false)
        .put("zero_point_confirmed", false)
        .put("candidates", JSONArray())

    if (frameMs <= 0L || asOfMs <= 0L || input.size < 45 ||
        !validWilliamsEditionBars(input) ||
        input.any { it.t <= 0L || it.t > Long.MAX_VALUE - frameMs ||
            it.t + frameMs > asOfMs } ||
        input.zipWithNext().any { (a, b) -> b.t - a.t != frameMs }) {
        return empty("CLOSED_CONTIGUOUS_CANDLES_UNVERIFIED")
    }
    val median = input.map { (it.h + it.l) / 2.0 }
    val ao = input.indices.map { i ->
        if (i < 33) Double.NaN else
            (i - 4..i).sumOf { median[it] } / 5.0 -
                (i - 33..i).sumOf { median[it] } / 34.0
    }
    fun signal(i: Int): Double =
        if (i < 37 || (i - 4..i).any { !ao[it].isFinite() }) Double.NaN
        else (i - 4..i).sumOf { ao[it] } / 5.0

    val pivots = confirmedWilliamsEditionFractals(input)
    val output = JSONArray()
    for (kind in listOf("HIGH", "LOW")) {
        val lastTwo = pivots.filter { it.kind == kind && it.center >= 37 }.takeLast(2)
        val side = if (kind == "HIGH") "UP_TREND_EXHAUSTION" else "DOWN_TREND_EXHAUSTION"
        if (lastTwo.size < 2) {
            output.put(JSONObject().put("direction", side)
                .put("state", "TWO_CAUSAL_EXTREMES_NOT_OBSERVED")
                .put("wave_degree_certified", false))
            continue
        }
        val first = lastTwo[0]
        val last = lastTwo[1]
        val confirmationMs = input[last.confirmation].t + frameMs
        val directionSign = if (kind == "HIGH") 1.0 else -1.0
        // This is a W3/W5 candidate comparison, NOT proof either swing is W3 or W5.
        val priceMakesNewExtreme = directionSign * (last.price - first.price) > 0.0
        val momentumWeaker = directionSign * (ao[last.center] - ao[first.center]) < 0.0
        val divergence = priceMakesNewExtreme && momentumWeaker &&
            ao[first.center].isFinite() && ao[last.center].isFinite()

        // The book asks for a squat bar among the three price bars at the
        // candidate termination. Binance's base volume is an *unverified proxy*
        // for Profitunity tick volume, so this can never certify the 4th sign.
        val squatIndexes = (maxOf(1, last.center - 1)..minOf(input.lastIndex, last.center + 1))
            .filter { i ->
                val a = input[i - 1]; val b = input[i]
                a.v > 0 && b.v > a.v && (b.h - b.l) / b.v < (a.h - a.l) / a.v
            }

        // TC1 Profitunity MACD 5/34/5: AO minus its five-value SMA
        // signal. Detect only a *closed* opposite crossing; a same-bar
        // guessed reversal is never promoted to a confirmed Zero Point.
        val shift = (maxOf(38, last.center - 2)..minOf(input.lastIndex, last.center + 7))
            .firstOrNull { i ->
                val a = signal(i - 1); val b = signal(i)
                a.isFinite() && b.isFinite() &&
                    directionSign * (ao[i - 1] - a) >= 0.0 &&
                    directionSign * (ao[i] - b) < 0.0
            }
        val result = JSONObject()
            .put("direction", side)
            .put("state", "FIVE_SIGNS_PARTIALLY_OBSERVABLE_NO_WAVE_DEGREE")
            .put("putative_w3_extreme_at_ms", input[first.center].t)
            .put("putative_w5_extreme_at_ms", input[last.center].t)
            .put("w5_fractal_known_at_ms", confirmationMs)
            .put("price_extreme_makes_new_high_or_low", priceMakesNewExtreme)
            .put("ao_divergence_observed", divergence)
            .put("ao_at_putative_w3", ao[first.center])
            .put("ao_at_putative_w5", ao[last.center])
            .put("target_zone", "NOT_VERIFIED_NO_CAUSAL_PRICE_TARGET")
            .put("five_bar_fractal_confirmed", confirmationMs <= asOfMs)
            .put("squat_proxy_observed", squatIndexes.isNotEmpty())
            .put("squat_proxy_bar_times_ms", JSONArray(squatIndexes.map { input[it].t }))
            .put("squat_volume_source", "BINANCE_KLINE_BASE_VOLUME_PROXY_NOT_BOOK_TICK_VOLUME")
            .put("profitunity_5_34_5_opposite_cross_observed", shift != null)
            .put("profitunity_cross_known_at_ms",
                shift?.let { input[it].t + frameMs } ?: JSONObject.NULL)
            .put("full_wave_100_to_140_bar_scale", "NOT_VERIFIED_WAVE_START_UNKNOWN")
            .put("zero_point_confirmed", false)
            .put("wave_degree_certified", false)
            .put("can_place_orders", false).put("can_close_positions", false)
        output.put(result)
    }
    return JSONObject()
        .put("schema", "TC1_ZERO_POINT_FIVE_SIGNS_V1")
        .put("state", "OBSERVATIONAL_PARTIAL_NOT_EXECUTABLE")
        .put("source", "TRADING_CHAOS_1_CH9_PDF_81_84")
        .put("indicator", "PROFITUNITY_MACD_5_34_5_MEDIAN_PRICE")
        .put("as_of_ms", asOfMs)
        .put("source_closed_bars", input.size)
        .put("candidates", output)
        .put("can_place_orders", false).put("can_close_positions", false)
        .put("wave_degree_certified", false)
        .put("zero_point_confirmed", false)
}
