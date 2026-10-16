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

}
