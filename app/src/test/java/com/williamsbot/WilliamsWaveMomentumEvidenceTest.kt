package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

class WilliamsWaveMomentumEvidenceTest {
    private val h=3_600_000L
    private fun prices(direction:String):List<WilliamsWaveBar> = (0 until 120).map { i->
        val p=if(i==70) 150.0 else 100.0
        val b=WilliamsWaveBar((1000+i)*h,p,p+1,p-1,p,1.0)
        if(direction=="UP") b else b.copy(o=300-b.o,h=300-b.l,l=300-b.h,c=300-b.c)
    }
    private fun values(direction:String,peak:Int=72):MutableList<Double> = (0 until 120).map { i->
        val a=when { i<33->Double.NaN;i==peak->10.0;i>=90->-1.0;else->2.0 }
        if(direction=="UP") a else -a
    }.toMutableList()

    @Test fun globalPeakTwoBarsAfterTheActualThirdPriceExtremeIsEvidenceInBothDirections() {
        for(dir in listOf("UP","DOWN")) {
            val e=evaluateWilliamsThirdMomentum(prices(dir),values(dir),40,70,110,dir,h)
            assertTrue(e.toJson().toString(),e.observed)
            assertEquals(2,e.lagBars)
            assertEquals((1000+74)*h,e.knownAt)
            assertFalse(e.toJson().getBoolean("wave_degree_confirmed"))
            assertFalse(e.toJson().getBoolean("can_close_positions"))
        }
    }

    @Test fun earlierStrongerMomentumDisqualifiesAConvenientLocalThirdPeak() {
        val a=values("UP");a[50]=12.0
        val e=evaluateWilliamsThirdMomentum(prices("UP"),a,40,70,110,"UP",h)
        assertFalse(e.observed)
        assertEquals("GLOBAL_PEAK_OUTSIDE_W3_PRICE_LAG_WINDOW",e.status)
        assertEquals(12.0,e.globalPeak!!,0.0)
    }

    @Test fun lagZeroBeforePriceOrMoreThanFiveBarsCannotBecomeBookEvidence() {
        for(peak in listOf(69,70,76)) {
            val e=evaluateWilliamsThirdMomentum(prices("UP"),values("UP",peak),40,70,110,"UP",h)
            assertFalse("peak=$peak",e.observed)
        }
    }

    @Test fun competingEqualPeakAndWrongPriceMaximumStayUnconfirmed() {
        val a=values("UP");a[50]=10.0
        assertEquals("MULTIPLE_GLOBAL_PEAKS_DEGREE_UNCONFIRMED",
            evaluateWilliamsThirdMomentum(prices("UP"),a,40,70,110,"UP",h).status)
        val b=prices("UP").toMutableList();b[69]=b[69].copy(h=160.0)
        assertFalse(evaluateWilliamsThirdMomentum(b,values("UP"),40,70,110,"UP",h).observed)
    }

    @Test fun anUnturnedLastPeakAndMissingWarmupAreNotConfirmed() {
        assertEquals("GLOBAL_PEAK_TURN_NOT_YET_OBSERVED",
            evaluateWilliamsThirdMomentum(prices("UP"),values("UP"),40,70,72,"UP",h).status)
        val a=values("UP");a[40]=Double.NaN
        assertFalse(evaluateWilliamsThirdMomentum(prices("UP"),a,40,70,110,"UP",h).observed)
    }

    @Test fun futureMomentumCannotRewriteTheAlreadyClosedWaveEvidence() {
        val a=values("UP");val baseline=evaluateWilliamsThirdMomentum(prices("UP"),a,40,70,110,"UP",h)
        for(i in 111 until a.size) a[i]=10000.0
        assertEquals(baseline,evaluateWilliamsThirdMomentum(prices("UP"),a,40,70,110,"UP",h))
    }
}
