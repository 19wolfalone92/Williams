package com.williamsbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FuturesWilliamsSignalMathTest {
    @Test
    fun superAoRequiresThreeConsecutiveGreenBars() {
        assertTrue(hasThreeSameColorAo(listOf(1.0, 1.1, 1.2, 1.3), "LONG"))
        assertFalse(hasThreeSameColorAo(listOf(1.0, 1.1, 1.2), "LONG"))
        assertFalse(hasThreeSameColorAo(listOf(1.0, 1.2, 1.1, 1.3), "LONG"))
    }

    @Test
    fun superAoRequiresThreeConsecutiveRedBars() {
        assertTrue(hasThreeSameColorAo(listOf(1.3, 1.2, 1.1, 1.0), "SHORT"))
        assertFalse(hasThreeSameColorAo(listOf(1.3, 1.2, 1.1), "SHORT"))
        assertFalse(hasThreeSameColorAo(listOf(1.3, 1.1, 1.2, 1.0), "SHORT"))
    }

    @Test
    fun superAoRejectsUnknownDirectionAndNonFiniteValues() {
        assertFalse(hasThreeSameColorAo(listOf(1.0, 1.1, 1.2, 1.3), "BUY"))
        assertFalse(hasThreeSameColorAo(listOf(1.0, Double.NaN, 1.2, 1.3), "LONG"))
    }


    @Test
    fun wm2FiresOnlyOnThirdSameColorAoBarNotLaterContinuationBars() {
        val fiveRising = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        assertTrue(isThirdSameColorAoBar(fiveRising, 3, "LONG"))
        assertFalse(isThirdSameColorAoBar(fiveRising, 4, "LONG"))

        val fiveFalling = listOf(5.0, 4.0, 3.0, 2.0, 1.0)
        assertTrue(isThirdSameColorAoBar(fiveFalling, 3, "SHORT"))
        assertFalse(isThirdSameColorAoBar(fiveFalling, 4, "SHORT"))

        val resetThenRise = listOf(1.0, 2.0, 1.0, 2.0, 3.0, 4.0)
        assertTrue(isThirdSameColorAoBar(resetThenRise, 5, "LONG"))
    }

    @Test
    fun wm1AngulationRequiresIncreasingSeparationFromJaw() {
        val jaw = listOf(10.0, 10.0, 10.0, 10.0, 10.0)
        val risingLows = listOf(9.0, 8.0, 7.0, 6.0, 5.0)
        val risingHighs = listOf(11.0, 11.0, 11.0, 11.0, 11.0)
        assertTrue(hasIncreasingWilliamsAngulation(jaw, risingLows, risingHighs, 4, "LONG"))
        assertFalse(hasIncreasingWilliamsAngulation(jaw, risingLows.reversed(), risingHighs, 4, "LONG"))
        assertFalse(hasIncreasingWilliamsAngulation(jaw, risingLows, risingHighs, 4, "BUY"))
    }
}

    @Test
    fun staleOlderTriggerCanBeSkippedInFavorOfLaterActionableSignal() {
        assertFalse(isActionableWilliamsTrigger("LONG", 100.0, 99.0, 98.0))
        assertTrue(isActionableWilliamsTrigger("LONG", 100.0, 102.0, 98.0))
        assertFalse(isActionableWilliamsTrigger("SHORT", 100.0, 101.0, 102.0))
        assertTrue(isActionableWilliamsTrigger("SHORT", 100.0, 98.0, 102.0))
    }

    @Test
    fun actionableTriggerRejectsMalformedOrWrongDirectionGeometry() {
        assertFalse(isActionableWilliamsTrigger("LONG", Double.NaN, 102.0, 98.0))
        assertFalse(isActionableWilliamsTrigger("SHORT", 100.0, 98.0, 99.0))
        assertFalse(isActionableWilliamsTrigger("UNKNOWN", 100.0, 102.0, 98.0))
    }
