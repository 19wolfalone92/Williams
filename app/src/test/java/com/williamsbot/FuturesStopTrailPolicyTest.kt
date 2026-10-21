package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuturesStopTrailPolicyTest {
    private fun bars(highs: List<Double>, lows: List<Double>): List<FuturesTrailBar> =
        highs.indices.map { i -> FuturesTrailBar(i * 3_600_000L, highs[i], lows[i]) }

    @Test fun confirmedDownFractalTrailsLongAfterEntryButBeforeOneR() {
        val result = planFuturesStopTrail(
            "LONG", oldStop = 90.0, actualEntry = 100.0, initialStop = 90.0,
            markPrice = 105.0, tickSize = 0.1, openedObservedAtMs = 2 * 3_600_000L,
            bars = bars(
                highs = listOf(102.0, 101.0, 103.0, 100.0, 102.0, 103.0, 104.0),
                lows = listOf(99.0, 98.0, 97.0, 95.0, 99.0, 100.0, 101.0)
            )
        )
        assertEquals("CONFIRMED_H1_FRACTAL", result?.reason)
        assertEquals(94.9, result!!.stop, 1e-9)
        assertEquals(3 * 3_600_000L, result.fractalAtMs)
    }

    @Test fun confirmedUpFractalTrailsShortAfterEntryButBeforeOneR() {
        val result = planFuturesStopTrail(
            "SHORT", oldStop = 110.0, actualEntry = 100.0, initialStop = 110.0,
            markPrice = 95.0, tickSize = 0.1, openedObservedAtMs = 2 * 3_600_000L,
            bars = bars(
                highs = listOf(103.0, 102.0, 104.0, 105.0, 103.0, 102.0, 101.0),
                lows = listOf(98.0, 97.0, 96.0, 99.0, 96.0, 95.0, 94.0)
            )
        )
        assertEquals("CONFIRMED_H1_FRACTAL", result?.reason)
        assertEquals(105.1, result!!.stop, 1e-9)
    }

    @Test fun favorableOneRAloneNeverProposesBreakEven() {
        val long = planFuturesStopTrail(
            "LONG", 90.0, 100.0, 90.0, 111.0, 0.1, 0L, emptyList()
        )
        val short = planFuturesStopTrail(
            "SHORT", 110.0, 100.0, 110.0, 89.0, 0.1, 0L, emptyList()
        )
        assertNull(long)
        assertNull(short)
        // Even legacy explicit enablement cannot silently reactivate the
        // unbooked 1R algorithm for either direction.
        assertNull(planFuturesStopTrail("LONG", 90.0, 100.0, 90.0, 111.0,
            0.1, 0L, emptyList(), breakEvenEnabled=true))
        assertNull(planFuturesStopTrail("SHORT", 110.0, 100.0, 110.0, 89.0,
            0.1, 0L, emptyList(), breakEvenEnabled=true))
    }

    @Test fun confirmedFractalRemainsActiveAfterDisablingOneR() {
        val result = planFuturesStopTrail(
            "LONG", oldStop = 90.0, actualEntry = 100.0, initialStop = 90.0,
            markPrice = 118.0, tickSize = 0.1, openedObservedAtMs = 2 * 3_600_000L,
            bars = bars(
                highs = listOf(109.0, 110.0, 111.0, 112.0, 115.0, 116.0, 117.0),
                lows = listOf(105.0, 106.0, 107.0, 104.0, 108.0, 109.0, 110.0)
            )
        )
        assertEquals("CONFIRMED_H1_FRACTAL", result?.reason)
        assertEquals(103.9, result!!.stop, 1e-9)
    }

    @Test fun noLastTwoBarsMeansNoConfirmedFractal() {
        val result = planFuturesStopTrail(
            "LONG", oldStop = 90.0, actualEntry = 100.0, initialStop = 90.0,
            markPrice = 105.0, tickSize = 0.1, openedObservedAtMs = 1L,
            bars = bars(
                highs = listOf(102.0, 103.0, 104.0, 105.0, 106.0),
                lows = listOf(100.0, 99.0, 98.0, 97.0, 96.0)
            )
        )
        assertNull(result)
    }

    @Test fun preEntryFractalIsNotUsedToTrailLivePosition() {
        val result = planFuturesStopTrail(
            "LONG", oldStop = 90.0, actualEntry = 100.0, initialStop = 90.0,
            markPrice = 105.0, tickSize = 0.1, openedObservedAtMs = 4 * 3_600_000L,
            bars = bars(
                highs = listOf(102.0, 101.0, 103.0, 100.0, 102.0, 103.0, 104.0),
                lows = listOf(99.0, 98.0, 97.0, 95.0, 99.0, 100.0, 101.0)
            )
        )
        assertNull(result)
    }

    @Test fun smallRiskAndFavorableMarkCannotInventBreakEven() {
        val result = planFuturesStopTrail(
            "LONG", 99.0, 100.0, 99.95, 100.1, 0.1, 0L, emptyList()
        )
        assertNull(result)
    }

    @Test fun neverWidensStopOrDuplicatesCurrentProtection() {
        val result = planFuturesStopTrail(
            "LONG", 101.0, 100.0, 90.0, 111.0, 0.1, 0L, emptyList()
        )
        assertNull(result)
    }

    @Test fun rejectsUnknownInputsAndWrongInitialSide() {
        assertNull(planFuturesStopTrail("LONG", 90.0, 100.0, 110.0, 111.0, 0.1, 0L, emptyList()))
        assertNull(planFuturesStopTrail("SHORT", 110.0, 100.0, 90.0, 89.0, 0.1, 0L, emptyList()))
        assertNull(planFuturesStopTrail("SHORT", 110.0, 100.0, 110.0, Double.NaN, 0.1, 0L, emptyList()))
        assertNull(planFuturesStopTrail("SIDEWAYS", 90.0, 100.0, 90.0, 111.0, 0.1, 0L, emptyList()))
        assertTrue(planFuturesStopTrail("LONG", 90.0, 100.0, 90.0, 109.0, 0.1, 0L, emptyList()) == null)
    }

    @Test fun equalLowPlateauTrailsOnlyAfterTwoRightClosedBars() {
        val result = planFuturesStopTrail(
            "LONG", oldStop = 90.0, actualEntry = 100.0, initialStop = 90.0,
            markPrice = 105.0, tickSize = 0.1, openedObservedAtMs = 1 * 3_600_000L,
            bars = bars(
                highs = listOf(103.0, 104.0, 105.0, 104.0, 104.0, 105.0, 106.0),
                lows = listOf(102.0, 101.0, 100.0, 95.0, 95.0, 98.0, 99.0)
            )
        )
        assertEquals("CONFIRMED_H1_FRACTAL", result?.reason)
        assertEquals(94.9, result!!.stop, 1e-7)
        assertEquals(4 * 3_600_000L, result.fractalAtMs)
    }

    @Test fun equalHighPlateauTrailsShort() {
        val result = planFuturesStopTrail(
            "SHORT", oldStop = 110.0, actualEntry = 100.0, initialStop = 110.0,
            markPrice = 95.0, tickSize = 0.1, openedObservedAtMs = 1 * 3_600_000L,
            bars = bars(
                highs = listOf(103.0, 102.0, 103.0, 106.0, 106.0, 102.0, 101.0),
                lows = listOf(99.0, 98.0, 99.0, 102.0, 102.0, 98.0, 97.0)
            )
        )
        assertEquals("CONFIRMED_H1_FRACTAL", result?.reason)
        assertEquals(106.1, result!!.stop, 1e-7)
    }

    @Test fun olderValidPostEntryLongFractalIsNotMaskedByUnusableNewerOne() {
        // Newer down-fractal would put a LONG stop ABOVE the current mark.
        // Earlier confirmed pivot is still a strictly protective improvement.
        val lows = listOf(100.0, 99.0, 98.0, 96.0, 100.0,
            110.0, 109.0, 105.0, 107.0, 108.0, 110.0)
        val result = planFuturesStopTrail(
            "LONG", oldStop = 90.0, actualEntry = 100.0, initialStop = 90.0,
            markPrice = 104.0, tickSize = 0.1, openedObservedAtMs = 1L,
            bars = bars(lows.map { it + 2.0 }, lows)
        )
        assertEquals("CONFIRMED_H1_FRACTAL", result?.reason)
        assertEquals(95.9, result!!.stop, 1e-8)
        assertEquals(3 * 3_600_000L, result.fractalAtMs)
    }

    @Test fun olderValidPostEntryShortFractalIsNotMaskedByUnusableNewerOne() {
        val highs = listOf(99.0, 100.0, 103.0, 105.0, 99.0,
            91.0, 92.0, 94.0, 90.0, 89.0, 88.0)
        val result = planFuturesStopTrail(
            "SHORT", oldStop = 110.0, actualEntry = 100.0, initialStop = 110.0,
            markPrice = 95.0, tickSize = 0.1, openedObservedAtMs = 1L,
            bars = bars(highs, highs.map { it - 5.0 })
        )
        assertEquals("CONFIRMED_H1_FRACTAL", result?.reason)
        assertEquals(105.1, result!!.stop, 1e-8)
        assertEquals(3 * 3_600_000L, result.fractalAtMs)
    }

    @Test fun unbookedOneRNeverPlacesNearMarketStop() {
        // Favorable price alone does not authorize a stop, even beyond 1R.
        val blocked = planFuturesStopTrail(
            "LONG", oldStop = 99.9, actualEntry = 100.0, initialStop = 99.95,
            markPrice = 100.55, tickSize = 0.1, openedObservedAtMs = 0L,
            bars = emptyList()
        )
        assertNull(blocked)
    }
    @Test fun tc2BookTrailRequiresVerifiedOpeningTimeButKeepsProtection() {
        val input=bars(
            highs=listOf(110.0,109.0,108.0,107.0,106.0),
            lows=listOf(99.0,98.0,97.0,96.0,95.0)
        )
        // This unknown opening observation must not move an exchange stop
        // based on candles which may predate the actual live position.
        assertNull(planWilliamsCampaignStop("SHORT",120.0,100.0,120.0,94.0,
            0.1,0L,input))
        val known=planWilliamsCampaignStop("SHORT",120.0,100.0,120.0,94.0,
            0.1,2*3_600_000L,input)
        assertEquals("TC2_LAST_5_CLOSED_BARS",known?.reason)
        assertEquals(110.1,known!!.stop,1e-7)
    }


    @Test fun missingH1BarsCannotBecomeAThreeOrFiveBarStop() {
        val regular=bars(listOf(105.0,106.0,107.0,108.0,109.0),listOf(95.0,96.0,97.0,98.0,99.0))
        val gapped=regular.mapIndexed {i,b->if(i>=2) b.copy(openTimeMs=b.openTimeMs+3_600_000L) else b}
        for(direction in listOf("LONG","SHORT")) {
            val old=if(direction=="LONG") 80.0 else 120.0
            val mark=if(direction=="LONG") 115.0 else 85.0
            assertNull(planWilliamsCampaignStop(direction,old,100.0,null,mark,.1,1L,gapped))
            assertNotNull(planWilliamsCampaignStop(direction,old,100.0,null,mark,.1,1L,regular))
        }
    }
}
