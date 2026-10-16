package com.williamsbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TC2 ch.9, ch.11-12: standalone book guards on the Android Futures path.
 * These deliberately do not place real orders or claim Testnet readiness.
 */
class WilliamsTc2RulesTest {
    @Test
    fun bullishWm1RequiresEntireBarOutsideMouthAndRedAo() {
        assertTrue(tc2BullishWiseManBarQualified(
            high = 98.8, low = 96.0, close = 98.0, priorLow = 99.0,
            jaw = 100.0, teeth = 99.8, lips = 99.7,
            aoNow = -0.5, aoPrevious = -0.3
        ))
        assertFalse(tc2BullishWiseManBarQualified(
            high = 103.0, low = 96.0, close = 102.0, priorLow = 99.0,
            jaw = 100.0, teeth = 99.8, lips = 99.7,
            aoNow = -0.5, aoPrevious = -0.3
        ))
        assertFalse(tc2BullishWiseManBarQualified(
            high = 98.8, low = 96.0, close = 98.0, priorLow = 99.0,
            jaw = 100.0, teeth = 99.8, lips = 99.7,
            aoNow = -0.1, aoPrevious = -0.3
        ))
    }

    @Test
    fun opposingUntriggeredSetupNeverClosesLongOrShort() {
        assertFalse(tc2OppositeBreakoutTriggered(true, 100.0, 95.0))
        assertFalse(tc2OppositeBreakoutTriggered(false, 100.0, 105.0))
        assertTrue(tc2OppositeBreakoutTriggered(true, 95.0, 95.0))
        assertTrue(tc2OppositeBreakoutTriggered(false, 105.0, 105.0))
        assertFalse(tc2OppositeBreakoutTriggered(true, Double.NaN, 95.0))
        assertFalse(tc2OppositeBreakoutTriggered(false, -1.0, 105.0))
    }
    @Test
    fun nineBarFractalWithSeparatedEqualHighsWaitsForConfirmation() {
        val highs = listOf(8.0, 9.0, 10.0, 9.0, 10.0, 9.0, 10.0, 8.0, 9.0)
        assertFalse(tc2ConfirmedFractal(highs.take(8), 6, true))
        assertTrue(tc2ConfirmedFractal(highs, 6, true))
        assertTrue(tc2FractalGroupStart(highs, 6, true) == 2)
    }

    @Test
    fun nineBarFractalMirrorsToDownside() {
        val lows = listOf(12.0, 11.0, 10.0, 11.0, 10.0, 11.0, 10.0, 12.0, 11.0)
        assertTrue(tc2ConfirmedFractal(lows, 6, false))
        assertTrue(tc2FractalGroupStart(lows, 6, false) == 2)
    }

    @Test
    fun waveEvidenceNeverIncludesAnOpenOrFutureBar() {
        val hour = 3_600_000L
        val now = 6L * hour + 30L * 60_000L
        val opens = listOf(0L, hour, 2 * hour, 3 * hour, 4 * hour,
            5 * hour, 6 * hour, 7 * hour, 4 * hour)
        val closed = tc2ClosedWaveBarTimes(opens, hour, now)
        assertTrue(closed == listOf(hour, 2 * hour, 3 * hour, 4 * hour, 5 * hour))
        assertFalse(closed.contains(6 * hour))
        assertFalse(closed.contains(7 * hour))
    }

    @Test
    fun waveEvidenceRejectsInvalidTimebases() {
        assertTrue(tc2ClosedWaveBarTimes(listOf(1L, 2L), 0L, 100L).isEmpty())
        assertTrue(tc2ClosedWaveBarTimes(listOf(1L, 2L), 5L, 0L).isEmpty())
    }

    @Test
    fun pendingContextVetoIsMirroredAndReversalExceptionIsExplicit() {
        // The same policy used at entry must be re-evaluated before keeping
        // a previously queued conditional order active.
        assertTrue(directionalSignalContextRank("FRACTAL", true, false, true, false) == 2)
        assertTrue(directionalSignalContextRank("FRACTAL", false, true, true, false) == 0)
        assertTrue(directionalSignalContextRank("SHORT_FRACTAL", false, true, false, true) == 2)
        assertTrue(directionalSignalContextRank("SHORT_FRACTAL", true, false, false, true) == 0)
        assertTrue(directionalSignalContextRank("REVERSAL", false, false, false, false) == 1)
        assertTrue(directionalSignalContextRank("SHORT_REVERSAL", false, false, false, false) == 1)
        assertTrue(directionalSignalContextRank("NOT_A_SIGNAL", false, false, false, false) == 0)
    }

}
