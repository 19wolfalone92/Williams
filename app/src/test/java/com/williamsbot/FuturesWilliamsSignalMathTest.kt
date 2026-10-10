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
    fun wm1AngulationScoreIsFinitePositiveEvidenceAndRejectsInvalidDirection() {
        val jaw = listOf(10.0, 10.0, 10.0, 10.0, 10.0)
        val lows = listOf(9.0, 8.0, 7.0, 6.0, 5.0)
        val highs = listOf(11.0, 11.0, 11.0, 11.0, 11.0)
        val closes = listOf(10.0, 9.8, 9.5, 9.0, 8.5)

        val score = williamsAngulationScore(
            jaw, lows, highs, closes, 4, "LONG",
            teeth = jaw, lips = jaw
        )
        assertTrue(score != null && score.isFinite() && score > 0.0)
        assertTrue(williamsAngulationScore(jaw, lows, highs, closes, 4, "BUY") == null)
        assertTrue(williamsAngulationScore(
            jaw, lows.reversed(), highs, closes, 4, "LONG",
            teeth = jaw, lips = jaw
        ) == null)
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
    @Test
    fun wm1AngulationRequiresPriceEdgeToDivergeAndSignalExtremeOutsideWholeMouth() {
        val jaw = listOf(10.0, 10.0, 10.0, 10.0, 10.0)
        val teeth = listOf(10.5, 10.5, 10.5, 10.5, 10.5)
        val lips = listOf(9.5, 9.5, 9.5, 9.5, 9.5)
        val lows = listOf(9.0, 8.0, 7.0, 6.0, 5.0)
        val highs = listOf(11.0, 11.5, 12.0, 12.5, 13.0)
        val closes = listOf(10.0, 9.5, 9.0, 8.5, 8.0)

        assertTrue(
            williamsAngulationScore(
                jaw, lows, highs, closes, 4, "LONG", teeth = teeth, lips = lips
            ) != null
        )

        // Separation from Jaw increases, but the final low has not moved
        // outside the entire mouth (the lower Lips line is 97).
        val jawAt100 = listOf(100.0, 100.0, 100.0, 100.0, 100.0)
        val lowNearMouth = listOf(100.5, 100.3, 100.1, 99.8, 99.5)
        val highNearMouth = listOf(102.0, 102.0, 102.0, 102.0, 102.0)
        val closeNearMouth = listOf(101.0, 101.0, 100.8, 100.5, 100.0)
        assertTrue(
            williamsAngulationScore(
                jawAt100, lowNearMouth, highNearMouth, closeNearMouth, 4, "LONG",
                teeth = listOf(101.0, 101.0, 101.0, 101.0, 101.0),
                lips = listOf(97.0, 97.0, 97.0, 97.0, 97.0)
            ) == null
        )
    }

    @Test
    fun wm1AngulationUsesMirroredSteeplyDivergingHighEdgeForShort() {
        val jaw = listOf(10.0, 10.0, 10.0, 10.0, 10.0)
        val lows = listOf(8.0, 8.0, 8.0, 8.0, 8.0)
        val highs = listOf(11.0, 12.0, 13.0, 14.0, 15.0)
        val closes = listOf(10.5, 11.0, 11.5, 12.0, 13.0)
        val teeth = listOf(10.2, 10.2, 10.2, 10.2, 10.2)
        val lips = listOf(9.8, 9.8, 9.8, 9.8, 9.8)
        val score = williamsAngulationScore(
            jaw, lows, highs, closes, 4, "SHORT", teeth = teeth, lips = lips
        )
        assertTrue(score != null && score.isFinite() && score > 0.0)
    }

}
