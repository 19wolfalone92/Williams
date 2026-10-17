package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WilliamsWaveRhythmTest {
    private fun fixture(w3: Double, end: Double, n: Int = 120): MutableList<Double> =
        MutableList(n) { index -> when (index) { 55 -> w3; 95 -> end; else -> w3 / 2.0 } }

    @Test fun downwardW3RequiresUpwardZeroCrossInWave4() {
        val ao = fixture(-8.0, 0.1)
        val r = evaluateWilliamsWave4Zero(ao, 0, 55, 119, "DOWN")
        assertEquals(Wave4ZeroStatus.ZERO_CROSSED_MINIMUM_ONLY, r.status)
        assertEquals(95, r.crossedBarIndex)
        assertEquals(120, r.examinedBars)
        assertFalse(r.isTradingSignal)
    }

    @Test fun upwardW3RequiresDownwardZeroCrossInWave4() {
        val r = evaluateWilliamsWave4Zero(fixture(8.0, -0.2), 0, 55, 119, "UP")
        assertEquals(Wave4ZeroStatus.ZERO_CROSSED_MINIMUM_ONLY, r.status)
        assertEquals(95, r.crossedBarIndex)
    }

    @Test fun nearZeroDoesNotClaimBookMinimumIsMet() {
        val r = evaluateWilliamsWave4Zero(fixture(-8.0, -0.2), 0, 55, 119, "DOWN")
        assertEquals(Wave4ZeroStatus.NEAR_ZERO_UNCONFIRMED, r.status)
        assertNull(r.crossedBarIndex)
        assertEquals(0.2, r.closestAbsAo!!, 1e-8)
    }

    @Test fun wrongScaleBlocksConfirmationDespiteCross() {
        val r = evaluateWilliamsWave4Zero(fixture(-8.0, 0.1, 75), 0, 55, 74, "DOWN")
        // no cross on this 75-bar sequence, but scale remains unsuitable
        assertEquals(Wave4ZeroStatus.WRONG_SCALE_TOO_FEW_BARS, r.status)
        val shortButCrossed = fixture(-8.0, 0.1, 95)
        shortButCrossed[80] = 0.1
        val observed = evaluateWilliamsWave4Zero(shortButCrossed, 0, 55, 94, "DOWN")
        assertEquals(Wave4ZeroStatus.WRONG_SCALE_TOO_FEW_BARS, observed.status)
        assertEquals(80, observed.crossedBarIndex)
    }

    @Test fun excessiveScaleDoesNotMasqueradeAsValidatedW4() {
        val r = evaluateWilliamsWave4Zero(fixture(-8.0, 0.1, 150), 0, 55, 149, "DOWN")
        assertEquals(Wave4ZeroStatus.WRONG_SCALE_TOO_MANY_BARS, r.status)
        assertEquals(95, r.crossedBarIndex)
    }

    @Test fun invalidOrUnclosedInputsFailClosed() {
        val ao = fixture(-8.0, 0.1)
        assertEquals(Wave4ZeroStatus.INVALID_STRUCTURE,
            evaluateWilliamsWave4Zero(ao, 0, 55, 120, "DOWN").status)
        assertEquals(Wave4ZeroStatus.INVALID_STRUCTURE,
            evaluateWilliamsWave4Zero(ao, 0, 55, 119, "UNKNOWN").status)
        ao[100] = Double.NaN
        assertEquals(Wave4ZeroStatus.INVALID_STRUCTURE,
            evaluateWilliamsWave4Zero(ao, 0, 55, 119, "DOWN").status)
        assertEquals(Wave4ZeroStatus.INVALID_STRUCTURE,
            evaluateWilliamsWave4Zero(fixture(8.0, 0.1), 0, 55, 119, "DOWN").status)
    }

    @Test fun noZeroCrossAwayFromZeroIsNotWave4Confirmation() {
        val r = evaluateWilliamsWave4Zero(fixture(-8.0, -2.0), 0, 55, 119, "DOWN")
        assertEquals(Wave4ZeroStatus.NO_ZERO_CROSS, r.status)
    }

    @Test fun proposedJuniorW3InsideSeniorW5CannotReduceExhaustionRisk() {
        assertEquals(65.0, provisionalWave3InsideWave5Caution(true, 10.0), 0.0)
        assertEquals(87.0, provisionalWave3InsideWave5Caution(true, 87.0), 0.0)
        assertEquals(12.0, provisionalWave3InsideWave5Caution(false, 12.0), 0.0)
        assertEquals(100.0, provisionalWave3InsideWave5Caution(true, Double.NaN), 0.0)
    }

    @Test fun scaleSelectionPrefers100To140BarsWithoutFabricatingMoreHistory() {
        val frames = linkedMapOf("1h" to 3_600_000L, "15m" to 900_000L, "5m" to 300_000L)
        val match = suggestWilliamsWaveScale(30L * 3_600_000L, frames)
        assertEquals("15m", match!!.timeframe)
        assertEquals(120, match.expectedBars)
        assertTrue(match.withinBookRange)

        val fallback = suggestWilliamsWaveScale(5L * 3_600_000L, frames)
        assertEquals("5m", fallback!!.timeframe)
        assertEquals(60, fallback.expectedBars)
        assertFalse(fallback.withinBookRange)
        assertNull(suggestWilliamsWaveScale(0L, frames))
    }
}
