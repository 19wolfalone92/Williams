package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeWaveDirectionContextTest {
    @Test fun bullishTrendSupportsLongAndOpposesShortSymmetrically() {
        val common = NativeWaveInput(
            waveDirection = "UP", wavePosition = 3, exhaustionRisk = 20.0,
            bullishAlligator = true, bearishAlligator = false, ao = 5.0, ac = 2.0
        )
        val long = common.rank("LONG_REVERSAL")
        val short = common.rank("SHORT_REVERSAL")
        assertTrue(long > 0.0)
        assertTrue(short < 0.0)
        assertEquals(-short, long, 0.000001)
    }

    @Test fun bearishTrendSupportsShortAndOpposesLongSymmetrically() {
        val common = NativeWaveInput(
            waveDirection = "DOWN", wavePosition = 3, exhaustionRisk = 20.0,
            bullishAlligator = false, bearishAlligator = true, ao = -5.0, ac = -2.0
        )
        assertTrue(common.rank("SHORT_REVERSAL") > 0.0)
        assertTrue(common.rank("LONG_REVERSAL") < 0.0)
    }

    @Test fun bearishDivergenceIsContextNotEntryAuthorization() {
        val common = NativeWaveInput(
            waveDirection = "UP", wavePosition = 5, exhaustionRisk = 80.0,
            bullishAlligator = true, bearishAlligator = false,
            ao = 1.0, ac = -0.5, bearishDivergence = true
        )
        val cautionLong = common.rank("LONG_REVERSAL")
        val reverseContext = common.rank("SHORT_REVERSAL")
        assertTrue(cautionLong < common.copy(bearishDivergence = false).rank("LONG_REVERSAL"))
        assertTrue(reverseContext > common.copy(bearishDivergence = false).rank("SHORT_REVERSAL"))
        // These are numerical tie-break points. No execution API is exposed.
        assertTrue(cautionLong in -8.0..8.0)
        assertTrue(reverseContext in -8.0..8.0)
    }

    @Test fun malformedUnknownSignalsNeverDefaultToLong() {
        val common = NativeWaveInput("UP", 3, 0.0, true, false, 4.0, 3.0)
        assertEquals(0.0, common.rank("UNKNOWN_WAVE"), 0.0)
    }

    @Test fun aoToAcUsesFiveClosedBarsAndNeverFutureBars() {
        assertEquals(2.0, nativeAcFromClosedAo(listOf(1.0, 1.0, 1.0, 1.0, 3.5)), 0.00001)
        assertTrue(nativeAcFromClosedAo(listOf(1.0, 2.0)).isNaN())
        assertTrue(nativeAcFromClosedAo(listOf(1.0, 2.0, Double.NaN, 4.0, 5.0)).isNaN())
    }

    @Test fun bearishDivUsesEarlierAoPeakWithinEachConfirmedRise() {
        val highs = MutableList(100) { 10.0 }
        val lows = MutableList(100) { 8.0 }
        val ao = MutableList(100) { 0.1 }
        lows[40] = 5.0; highs[48] = 20.0
        lows[70] = 6.0; highs[78] = 22.0
        ao[45] = 5.0  // AO high is earlier than PRICE high 48
        ao[75] = 2.0  // AO high is earlier than PRICE high 78
        assertTrue(nativeConfirmedAoLegDivergence(highs, lows, ao, bearish = true))
        ao[75] = 6.0
        assertFalse(nativeConfirmedAoLegDivergence(highs, lows, ao, bearish = true))
    }

    @Test fun bullishDivIsMirrored() {
        val highs = MutableList(100) { 10.0 }
        val lows = MutableList(100) { 8.0 }
        val ao = MutableList(100) { -0.1 }
        highs[40] = 13.0; lows[48] = 4.0
        highs[70] = 14.0; lows[78] = 2.0
        ao[45] = -5.0
        ao[75] = -2.0
        assertTrue(nativeConfirmedAoLegDivergence(highs, lows, ao, bearish = false))
        ao[75] = -6.0
        assertFalse(nativeConfirmedAoLegDivergence(highs, lows, ao, bearish = false))
    }

    @Test fun unconfirmedFutureHighCannotGenerateDivergence() {
        val highs = MutableList(100) { 10.0 }
        val lows = MutableList(100) { 8.0 }
        val ao = MutableList(100) { 0.1 }
        lows[40] = 5.0
        highs[48] = 20.0
        lows[70] = 6.0
        // Bar 99 remains the forming/last closed edge; cannot be a five-bar
        // confirmed pivot because two right-hand candles have not closed.
        highs[99] = 24.0
        ao[98] = 0.2
        assertFalse(nativeConfirmedAoLegDivergence(highs, lows, ao, bearish = true))
    }

    private data class NativeWaveInput(
        val waveDirection: String,
        val wavePosition: Int,
        val exhaustionRisk: Double,
        val bullishAlligator: Boolean,
        val bearishAlligator: Boolean,
        val ao: Double,
        val ac: Double,
        val bearishDivergence: Boolean = false,
        val bullishDivergence: Boolean = false
    ) {
        fun rank(type: String) = nativeDirectionalHeuristicRank(
            signalType = type,
            waveDirection = waveDirection, wavePosition = wavePosition,
            exhaustionRisk = exhaustionRisk,
            bullishAlligator = bullishAlligator, bearishAlligator = bearishAlligator,
            ao = ao, ac = ac,
            bearishDivergence = bearishDivergence,
            bullishDivergence = bullishDivergence
        )
    }
}
