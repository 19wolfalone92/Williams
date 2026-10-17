package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WilliamsFuturesStopTrailTest {
    private fun longBars(vararg lows: Double): List<FuturesTrailBar> =
        lows.map { FuturesTrailBar(high = it + 2.0, low = it) }

    private fun shortBars(vararg highs: Double): List<FuturesTrailBar> =
        highs.map { FuturesTrailBar(high = it, low = it - 2.0) }

    private fun propose(
        direction: String,
        bars: List<FuturesTrailBar>,
        entry: Double,
        initial: Double,
        current: Double,
        mark: Double
    ): FuturesTrailProposal? = proposeWilliamsFuturesStopTrail(
        direction, bars, entry, initial, current, mark, tickSize = 0.1,
        feeBufferPerSide = 0.001, slippageBuffer = 0.0015
    )

    @Test fun longMovesBehindLatestConfirmedSupportFractal() {
        val bars = longBars(110.0, 112.0, 115.0, 112.0, 109.0, 113.0, 116.0, 117.0, 118.0)
        val result = propose("LONG", bars, entry = 100.0, initial = 90.0,
            current = 99.0, mark = 125.0)
        assertEquals("CONFIRMED_H1_FRACTAL", result!!.source)
        assertEquals(4, result.fractalCenterIndex)
        assertEquals(108.9, result.stopPrice, 1e-7)
    }

    @Test fun shortMovesBehindLatestConfirmedResistanceFractal() {
        val bars = shortBars(90.0, 89.0, 88.0, 89.0, 92.0, 87.0, 86.0, 85.0, 84.0)
        val result = propose("SHORT", bars, entry = 100.0, initial = 110.0,
            current = 103.0, mark = 80.0)
        assertEquals("CONFIRMED_H1_FRACTAL", result!!.source)
        assertEquals(4, result.fractalCenterIndex)
        assertEquals(92.1, result.stopPrice, 1e-7)
    }

    @Test fun oneRMoveActivatesCostBufferedBreakevenEvenWithoutFractals() {
        val long = propose("LONG", emptyList(), entry = 100.0, initial = 95.0,
            current = 95.0, mark = 106.0)
        assertEquals("BREAKEVEN_AFTER_1R", long!!.source)
        assertEquals(100.4, long.stopPrice, 1e-7)
        val short = propose("SHORT", emptyList(), entry = 100.0, initial = 105.0,
            current = 105.0, mark = 94.0)
        assertEquals("BREAKEVEN_AFTER_1R", short!!.source)
        assertEquals(99.6, short.stopPrice, 1e-7)
    }

    @Test fun breakevenDoesNotActivateBeforeOneR() {
        assertNull(propose("LONG", emptyList(), 100.0, 95.0, 95.0, 104.9))
        assertNull(propose("SHORT", emptyList(), 100.0, 105.0, 105.0, 95.1))
    }

    @Test fun alreadyTighterStopNeverLoosens() {
        assertNull(propose("LONG", emptyList(), 100.0, 95.0, 102.0, 110.0))
        assertNull(propose("SHORT", emptyList(), 100.0, 105.0, 98.0, 90.0))
    }

    @Test fun anUnconfirmedPivotOnLastOrPenultimateBarIsIgnored() {
        val bars = longBars(110.0, 111.0, 112.0, 113.0, 114.0, 115.0, 108.0, 116.0)
        assertNull(propose("LONG", bars, 100.0, 90.0, 99.0, 105.0))
    }

    @Test fun equalLowPlateauUsesLastPivotOnlyAndNeedsTwoClosedRightBars() {
        val bars = longBars(110.0, 111.0, 110.0, 108.0, 108.0, 110.0, 111.0, 112.0, 113.0)
        val result = propose("LONG", bars, 100.0, 90.0, 99.0, 120.0)
        assertEquals("CONFIRMED_H1_FRACTAL", result!!.source)
        assertEquals(4, result.fractalCenterIndex)
        assertEquals(107.9, result.stopPrice, 1e-7)
    }

    @Test fun noImmediateTriggerAndInvalidDataAreFailClosed() {
        assertNull(propose("LONG", emptyList(), 100.0, 95.0, 100.0, 100.35))
        assertNull(propose("LONG", emptyList(), 100.0, 100.0, 95.0, 120.0))
        assertNull(propose("SHORT", emptyList(), 100.0, 95.0, 105.0, 70.0))
        assertNull(propose("LONG", listOf(FuturesTrailBar(Double.NaN, 90.0)),
            100.0, 90.0, 90.0, 120.0))
    }

    @Test fun longShortFractalMirrorsKeepStopOnCorrectSideOfMark() {
        val long = propose("LONG",
            longBars(100.0, 102.0, 103.0, 101.0, 98.0, 103.0, 105.0, 106.0),
            100.0, 90.0, 90.0, 101.0)
        assertTrue(long == null || long.stopPrice < 101.0)
        val short = propose("SHORT",
            shortBars(100.0, 98.0, 97.0, 99.0, 102.0, 95.0, 93.0, 91.0),
            100.0, 110.0, 110.0, 99.0)
        assertTrue(short == null || short.stopPrice > 99.0)
    }
}
