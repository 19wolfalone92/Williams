package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuturesStopTrailPolicyTest {
    private fun bars(highs: List<Double>, lows: List<Double>): List<FuturesTrailBar> =
        highs.indices.map { i -> FuturesTrailBar(i * 3_600_000L, highs[i], lows[i]) }

    @Test fun adoptedPositionKeepsItsExistingStopUntilFirstPostApprovalClosedH1() {
        val hour=3_600_000L
        val approved=19*hour+47*60_000L
        val history=(14..18).map { i -> FuturesTrailBar(i*hour,103.0+i-14,100.0+i-14) }
        assertNull(planWilliamsCampaignStop("LONG",95.0,101.0,95.0,115.0,1.0,approved,history))
        val next=history.drop(1)+FuturesTrailBar(19*hour,108.0,105.0)
        val proposal=planWilliamsCampaignStop("LONG",95.0,101.0,95.0,115.0,1.0,approved,next)
        assertEquals("TC2_LAST_5_CLOSED_BARS",proposal?.reason)
        assertEquals(100.0,proposal!!.stop,1e-9)
    }

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

    @Test fun invalidH1TimingCannotCauseEitherSideToReplaceAStop() {
        val history=bars(
            highs=listOf(110.0,109.0,108.0,107.0,106.0,105.0),
            lows=listOf(99.0,98.0,97.0,96.0,95.0,94.0)
        )
        for(side in listOf("LONG","SHORT")) {
            val old=if(side=="LONG") 80.0 else 120.0
            val mark=if(side=="LONG") 130.0 else 70.0
            assertNotNull(planWilliamsCampaignStop(side,old,100.0,null,mark,.1,1L,history))
            val shifted=history.map {it.copy(openTimeMs=it.openTimeMs+900_000L)}
            val negative=history.mapIndexed {i,b->if(i==0) b.copy(openTimeMs=-3_600_000L) else b}
            for(invalid in listOf(shifted,negative)) {
                assertNull(planWilliamsCampaignStop(side,old,100.0,null,mark,.1,1L,invalid))
                assertNull(planFuturesStopTrail(side,old,100.0,old,mark,.1,1L,invalid))
            }
        }
    }

    @Test fun tc2CampaignNeverTrailsOnlyBecauseOneRWasReachedLongOrShort() {
        // Neither series has a post-entry fractal, and its five-bar stop
        // would not tighten the existing exchange-confirmed protection.
        val longBars=bars(
            highs=listOf(105.0,106.0,107.0,108.0,109.0),
            lows=listOf(92.0,93.0,94.0,95.0,96.0)
        )
        val shortBars=bars(
            highs=listOf(108.0,107.0,106.0,105.0,104.0),
            lows=listOf(92.0,91.0,90.0,89.0,88.0)
        )
        assertNull(planWilliamsCampaignStop("LONG",99.0,100.0,90.0,125.0,
            0.1,1L,longBars))
        assertNull(planWilliamsCampaignStop("SHORT",101.0,100.0,110.0,75.0,
            0.1,1L,shortBars))
    }

    @Test fun tc2CampaignStillUsesBookFiveClosedBarsBothDirections() {
        val longBars=bars(
            highs=listOf(105.0,106.0,107.0,108.0,109.0),
            lows=listOf(92.0,93.0,94.0,95.0,96.0)
        )
        val shortBars=bars(
            highs=listOf(108.0,107.0,106.0,105.0,104.0),
            lows=listOf(92.0,91.0,90.0,89.0,88.0)
        )
        val long=planWilliamsCampaignStop("LONG",90.0,100.0,90.0,125.0,
            0.1,1L,longBars)
        val short=planWilliamsCampaignStop("SHORT",110.0,100.0,110.0,75.0,
            0.1,1L,shortBars)
        assertEquals("TC2_LAST_5_CLOSED_BARS",long?.reason)
        assertEquals(91.9,long!!.stop,1e-7)
        assertEquals("TC2_LAST_5_CLOSED_BARS",short?.reason)
        assertEquals(108.1,short!!.stop,1e-7)
    }

    @Test fun tc2BookStopUsesExactlyOneDecimalTickWithoutFloatQuotient() {
        // TC2 chapter 12 PDF pp.156,165: structural stops immediately
        // beyond the source high/low; 27-Oct book/source parity regression.
        assertEquals(79524.7, tc2ExactStructuralStop("LONG",79524.8,.1)!!,0.0)
        assertEquals(79524.9, tc2ExactStructuralStop("SHORT",79524.8,.1)!!,0.0)
        assertEquals(2296.99, tc2ExactStructuralStop("LONG",2297.0,.01)!!,0.0)
        assertEquals(2297.01, tc2ExactStructuralStop("SHORT",2297.0,.01)!!,0.0)
        assertEquals(.0135, tc2ExactStructuralStop("LONG",.0136,.0001)!!,0.0)
        assertNull(tc2ExactStructuralStop("SHORT",99.005,.01))
        assertNull(tc2ExactStructuralStop("LONG",Double.NaN,.01))
        assertNull(tc2ExactStructuralStop("LONG",100.0,0.0))
    }

    @Test fun tc2FiveClosedBarTrailPreservesExactBookTickForLongAndShort() {
        val longBars=bars(
            highs=listOf(79529.0,79530.0,79531.0,79532.0,79533.0),
            lows=listOf(79524.8,79525.0,79526.0,79527.0,79528.0)
        )
        val long=planWilliamsCampaignStop(
            "LONG",79500.0,79510.0,79500.0,79550.0,.1,1L,longBars
        )
        assertEquals("TC2_LAST_5_CLOSED_BARS",long?.reason)
        assertEquals(79524.7,long!!.stop,0.0)
        val shortBars=bars(
            highs=listOf(2297.0,2296.0,2295.0,2294.0,2293.0),
            lows=listOf(2292.0,2291.0,2290.0,2289.0,2288.0)
        )
        val short=planWilliamsCampaignStop(
            "SHORT",2305.0,2300.0,2305.0,2280.0,.01,1L,shortBars
        )
        assertEquals("TC2_LAST_5_CLOSED_BARS",short?.reason)
        assertEquals(2297.01,short!!.stop,0.0)
    }
}
