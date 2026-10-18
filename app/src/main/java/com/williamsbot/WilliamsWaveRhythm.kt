package com.williamsbot

import kotlin.math.abs

/**
 * Book-aligned *diagnostic* of the minimum Wave 4 momentum requirement.
 *
 * Sources: Bill Williams, Trading Chaos (1st edition), chapter 7, pp. 59-63;
 * Profitunity Trading Chaos Course, Level II, pp. 11-14.
 *
 * The zero-line rule concerns the 5/34 oscillator component of Profitunity
 * MACD 5/34/5, not the five-bar signal-line spread (AC). Williams used
 * approximately 100-140 bars for the wave sequence UNDER EXAMINATION.
 *
 * A cross can support the presence of W4 but NEVER proves W4 has ended, never
 * assigns parent wave degree, and is not permission to open/close Futures.
 */
internal enum class Wave4ZeroStatus {
    INVALID_STRUCTURE,
    WRONG_SCALE_TOO_FEW_BARS,
    WRONG_SCALE_TOO_MANY_BARS,
    ZERO_CROSSED_MINIMUM_ONLY,
    NEAR_ZERO_UNCONFIRMED,
    NO_ZERO_CROSS
}

internal data class Wave4ZeroEvidence(
    val status: Wave4ZeroStatus,
    val examinedBars: Int,
    val closestAbsAo: Double?,
    val w3AbsAo: Double?,
    val crossedBarIndex: Int? = null,
    val isTradingSignal: Boolean = false
)

/**
 * Only use closed AO values; indexes are indexes of actual PRICE swing bars,
 * not indices on which a pivot became known after two right-side closes.
 *
 * cycleStart / observedEnd describe the *same proposed wave degree*.
 * The 100-140 convention is diagnostic, not a mathematical validity rule.
 * Near-zero is explicitly an ENGINEERING hypothesis, never the book's cross.
 */
internal fun evaluateWilliamsWave4Zero(
    closedAo: List<Double>,
    cycleStart: Int,
    w3OscillatorExtreme: Int,
    observedEnd: Int,
    direction: String,
    nearRatioOfW3: Double = 0.05
): Wave4ZeroEvidence {
    val bars = if (observedEnd >= cycleStart && cycleStart >= 0) observedEnd - cycleStart + 1 else 0
    fun invalid() = Wave4ZeroEvidence(Wave4ZeroStatus.INVALID_STRUCTURE, bars, null, null)
    if (direction != "UP" && direction != "DOWN" ||
        cycleStart < 0 || w3OscillatorExtreme <= cycleStart ||
        observedEnd <= w3OscillatorExtreme || observedEnd >= closedAo.size ||
        !nearRatioOfW3.isFinite() || nearRatioOfW3 < 0.0 ||
        !closedAo.subList(cycleStart, observedEnd + 1).all { it.isFinite() }
    ) return invalid()

    val w3 = closedAo[w3OscillatorExtreme]
    if ((direction == "UP" && w3 <= 0.0) || (direction == "DOWN" && w3 >= 0.0)) return invalid()
    val correction = (w3OscillatorExtreme + 1..observedEnd)
    val nearest = correction.minOf { abs(closedAo[it]) }
    val cross = correction.firstOrNull { index ->
        if (direction == "UP") closedAo[index] < 0.0 else closedAo[index] > 0.0
    }

    // When the sequence is not represented at a suitable resolution,
    // neither a cross nor near-zero licenses assigning a wave number.
    val scale = when {
        bars < 100 -> Wave4ZeroStatus.WRONG_SCALE_TOO_FEW_BARS
        bars > 140 -> Wave4ZeroStatus.WRONG_SCALE_TOO_MANY_BARS
        cross != null -> Wave4ZeroStatus.ZERO_CROSSED_MINIMUM_ONLY
        nearest <= abs(w3) * nearRatioOfW3 -> Wave4ZeroStatus.NEAR_ZERO_UNCONFIRMED
        else -> Wave4ZeroStatus.NO_ZERO_CROSS
    }
    return Wave4ZeroEvidence(scale, bars, nearest, abs(w3), cross)
}

/**
 * A junior W3 apparently coinciding with a senior W5 is *not* an early
 * senior W3. The present runtime inference is provisional (TF labels only),
 * so it must never grant a positive quality bonus or reduce exhaustion risk.
 * This is an explicitly engineered conservative policy, not a Williams entry.
 */
internal fun provisionalWave3InsideWave5Caution(
    nestedCandidate: Boolean,
    currentExhaustion: Double
): Double {
    if (!currentExhaustion.isFinite()) return 100.0
    return if (nestedCandidate) maxOf(currentExhaustion, 65.0)
    else currentExhaustion
}

internal data class WilliamsWaveScaleChoice(
    val timeframe: String,
    val expectedBars: Int,
    val withinBookRange: Boolean
)

/**
 * Suggest only an ANALYTICAL candle resolution based on the duration of an
 * already observed portion of a proposed wave; no future time is fabricated.
 * A timeframe change must fetch its own genuine exchange-closed OHLC bars.
 */
internal fun suggestWilliamsWaveScale(
    observedDurationMs: Long,
    supportedFramesMs: Map<String, Long>
): WilliamsWaveScaleChoice? {
    if (observedDurationMs <= 0L) return null
    val options = supportedFramesMs.mapNotNull { (frame, duration) ->
        if (duration <= 0L || duration > observedDurationMs) null
        else {
            val count = observedDurationMs / duration
            if (count > Int.MAX_VALUE) null
            else WilliamsWaveScaleChoice(frame, count.toInt(), count in 100L..140L)
        }
    }
    // Prefer a bar count within the book's resolution window.
    // If none is available, select the closest resolution to its midpoint.
    return options.minWithOrNull(
        compareBy<WilliamsWaveScaleChoice> { if (it.withinBookRange) 0 else 1 }
            .thenBy { abs(it.expectedBars - 120) }
            .thenBy { it.timeframe }
    )
}
