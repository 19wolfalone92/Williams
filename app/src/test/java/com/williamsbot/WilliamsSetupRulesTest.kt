package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

class WilliamsSetupRulesTest {
    @Test fun divergentBarPiercingTheMouthIsRejectedInBothDirections() {
        val valid=WilliamsWaveBar(0,91.0,94.0,88.0,93.0)
        val pierced=valid.copy(h=97.0,c=95.0)
        assertTrue(validWilliamsReversalBar("LONG",valid,90.0,listOf(96.0,98.0,100.0),1.0))
        assertFalse(validWilliamsReversalBar("LONG",pierced,90.0,listOf(96.0,98.0,100.0),1.0))
        fun mirror(b:WilliamsWaveBar)=b.copy(o=220-b.o,h=220-b.l,l=220-b.h,c=220-b.c)
        assertTrue(validWilliamsReversalBar("SHORT",mirror(valid),130.0,listOf(124.0,122.0,120.0),1.0))
        assertFalse(validWilliamsReversalBar("SHORT",mirror(pierced),130.0,listOf(124.0,122.0,120.0),1.0))
        assertFalse(validWilliamsReversalBar("LONG",valid,90.0,listOf(96.0,98.0,100.0),0.0))
    }
    @Test fun newerIneligibleFractalCannotMaskAnOlderValidPendingLevel() {
        val highs=listOf(101.0,102.0,105.0,103.0,102.0,103.0,104.0,102.0,101.0,102.0,103.0)
        val bars=highs.mapIndexed { i,h -> WilliamsWaveBar(i*3_600_000L,h-1,h,h-5,h-1) }
        val teeth=List(highs.size){ if (it==6) 106.0 else 104.5 }
        val long=selectWilliamsPendingFractal("LONG",bars,teeth,.1)!!
        assertEquals(2,long.center)
        assertEquals(4,long.confirmation)
        assertEquals(105.1,long.trigger,1e-8)
        val mirrored=bars.map { it.copy(o=220-it.o,h=220-it.l,l=220-it.h,c=220-it.c) }
        val short=selectWilliamsPendingFractal("SHORT",mirrored,teeth.map { 220-it },.1)!!
        assertEquals(2,short.center)
        assertEquals(220-long.trigger,short.trigger,1e-8)
    }
    @Test fun plateauUsesWholeOppositeExtremeAndAConsumedLevelIsNotRearmed() {
        val highs=listOf(101.0,102.0,105.0,105.0,103.0,102.0)
        val bars=highs.mapIndexed { i,h -> WilliamsWaveBar(i*3_600_000L,h-1,h,if (i==3) 98.0 else h-5,h-1) }
        val f=selectWilliamsPendingFractal("LONG",bars,List(bars.size){104.0},.1)!!
        assertEquals(3,f.center)
        assertEquals(2,f.groupStart)
        assertEquals(5,f.confirmation)
        assertEquals(96.9,f.protective,1e-8) // five closed bars, including the whole plateau
        val crossed=bars+WilliamsWaveBar(6*3_600_000L,103.0,106.0,102.0,104.0)
        assertNull(selectWilliamsPendingFractal("LONG",crossed,List(crossed.size){104.0},.1))
    }
    @Test fun superAoInitialStopUsesSignalBarAndNotSubsequentTrailingWindow() {
        val h=3_600_000L
        val lows=listOf(100.0,94.0,97.0,99.0,98.0,101.0,102.0)
        val bars=lows.mapIndexed { i,low ->
            WilliamsWaveBar((i+1)*h,low+1.0,low+4.0,low,low+2.0,10.0)
        }
        // TC2 PDF156, printed141: the explicitly permitted signal-bar
        // initial stop is distinct from later trailing over 3–5 bars.
        val proposed=williamsTc2SuperAoInitialStop("LONG",bars,4,.1)
        assertEquals(97.9,proposed!!,1e-9)
        assertTrue(proposed>bars[1].l-.1)
    }

    @Test fun bearishSuperAoInitialStopIsTheMirroredSignalBarHigh() {
        val highs=listOf(105.0,114.0,107.0,109.0,108.0,106.0,104.0)
        val bars=highs.mapIndexed { i,high ->
            WilliamsWaveBar((i+1)*3_600_000L,high-2,high,high-4,high-1,10.0)
        }
        assertEquals(108.1,williamsTc2SuperAoInitialStop("SHORT",bars,4,.1)!!,1e-9)
    }

    @Test fun superAoStopsCannotLookAheadOrReadUnclosedFuturePriceBars() {
        val h=3_600_000L
        val bars=(0..7).map { i ->
            WilliamsWaveBar((i+1)*h,100.0,102.0,99.0,100.0,10.0)
        }.toMutableList()
        val result=williamsTc2SuperAoInitialStop("LONG",bars,4,.01)
        bars[5]=bars[5].copy(l=1.0) // The following H1 candle MUST have no effect.
        bars[6]=bars[6].copy(h=5000.0)
        assertEquals(result,williamsTc2SuperAoInitialStop("LONG",bars,4,.01))
        assertNull(williamsTc2SuperAoInitialStop("LONG",bars,-1,.01))
        assertNull(williamsTc2SuperAoInitialStop("LONG",bars,4,0.0))
        assertNull(williamsTc2SuperAoInitialStop("SIDEWAYS",bars,4,.01))
        assertNull(williamsTc2SuperAoInitialStop("SHORT",bars,bars.size,.01))
        bars[4]=bars[4].copy(o=Double.NaN)
        assertNull(williamsTc2SuperAoInitialStop("LONG",bars,4,.01))
    }

}
