package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

class WilliamsSetupRulesTest {
    @Test fun tc2Wm1CloseAtExactlyHalfIsNotABookReversalForEitherSide() {
        // Trading Chaos 2, ch9 PDF122, figures 9.1/9.2:
        // bullish close STRICTLY in upper half, bearish in lower half.
        // At the midpoint neither direction has sufficient price proof.
        val midpoint = WilliamsWaveBar(3_600_000L,94.0,99.0,90.0,94.5)
        val up = midpoint.copy(c=94.50001)
        val below = midpoint.copy(c=94.49999)
        val alligator = listOf(100.0,101.0,102.0)
        assertFalse(validWilliamsReversalBar("LONG",midpoint,91.0,alligator,1.0))
        assertTrue(validWilliamsReversalBar("LONG",up,91.0,alligator,1.0))
        assertFalse(validWilliamsReversalBar("LONG",below,91.0,alligator,1.0))
        fun mirror(bar:WilliamsWaveBar)=bar.copy(o=250.0-bar.o,
            h=250.0-bar.l,l=250.0-bar.h,c=250.0-bar.c)
        val mirrorMouth=alligator.map { 250.0-it }
        assertFalse(validWilliamsReversalBar("SHORT",mirror(midpoint),159.0,mirrorMouth,1.0))
        assertTrue(validWilliamsReversalBar("SHORT",mirror(up),159.0,mirrorMouth,1.0))
        assertFalse(validWilliamsReversalBar("SHORT",mirror(below),159.0,mirrorMouth,1.0))
    }

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
    @Test fun sourceFractalSelectorUsesDecimalExactBinanceTicksOrFailsClosed() {
        fun make(highs:List<Double>)=highs.mapIndexed { i,x ->
            WilliamsWaveBar(i*3_600_000L,x-1,x,x-4,x-1)
        }
        val highs=listOf(79521.0,79522.0,79524.8,79523.0,79522.2,79522.3)
        val bars=make(highs)
        val levels=selectWilliamsPendingFractal("LONG",bars,List(6){79524.0},0.1)!!
        assertEquals(79524.9,levels.trigger,0.0)
        assertTrue(ExecutionMath.isMultiple(levels.trigger,0.1))
        assertTrue(ExecutionMath.isMultiple(levels.protective,0.1))
        // No guessing on an off-grid high, even if a later order layer
        // could round it. Unknown contract precision means no new entry.
        val corrupted=bars.toMutableList().apply { this[2]=this[2].copy(h=79524.805) }
        assertNull(selectWilliamsPendingFractal("LONG",corrupted,List(6){79524.0},0.1))
        val shortBars=bars.map { it.copy(o=160000.0-it.o,
            h=160000.0-it.l,l=160000.0-it.h,c=160000.0-it.c) }
        val short=selectWilliamsPendingFractal("SHORT",shortBars,
            List(6){160000.0-79524.0},0.1)!!
        assertTrue(ExecutionMath.isMultiple(short.trigger,0.1))
        assertTrue(ExecutionMath.isMultiple(short.protective,0.1))
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
    @Test fun tc2Figure11_1DFractalConfirmsAtSixthBarDespiteEqualFifthHigh() {
        // Original TC2 2nd edition, PDF physical p166, Fig 11.1(D):
        // two strictly lower highs on each side; equal fifth-bar high
        // extends confirmation to the sixth closed bar, not a veto.
        val h=3_600_000L
        val highs=listOf(101.0,102.0,105.0,103.0,105.0,104.0)
        val bars=highs.mapIndexed { i,x ->
            WilliamsWaveBar((i+1)*h,x-2.0,x,x-5.0,x-1.0)
        }
        assertNull(selectWilliamsPendingFractal("LONG",bars.take(5),List(5){104.0},0.1))
        val found=selectWilliamsPendingFractal("LONG",bars,List(6){104.0},0.1)!!
        assertEquals(2,found.center)
        assertEquals(5,found.confirmation)
        assertEquals(105.1,found.trigger,1e-8)
        val exact=exactTc2LongFractalLevels(bars,3*h,3*h,6*h,0.1)!!
        assertEquals(105.1,exact.trigger,1e-8)
        assertEquals(found.protective,exact.protective,1e-8)
        assertNull(exactTc2LongFractalLevels(bars,3*h,3*h,5*h,0.1))
        // A sixth candle crossing the trigger consumes the signal.
        val crossed=bars.toMutableList().apply {
            this[5]=this[5].copy(h=106.0)
        }
        assertNull(selectWilliamsPendingFractal("LONG",crossed,List(6){104.0},0.1))
        fun mirror(x:WilliamsWaveBar)=x.copy(o=220-x.o,h=220-x.l,l=220-x.h,c=220-x.c)
        val shortBars=bars.map(::mirror)
        val short=selectWilliamsPendingFractal("SHORT",shortBars,List(6){116.0},0.1)!!
        assertEquals(2,short.center)
        assertEquals(5,short.confirmation)
        assertEquals(114.9,short.trigger,1e-8)
        val shortExact=exactTc2ShortFractalLevels(shortBars,3*h,3*h,6*h,0.1)!!
        assertEquals(short.trigger,shortExact.trigger,1e-8)
        assertEquals(short.protective,shortExact.protective,1e-8)
        assertNull(exactTc2ShortFractalLevels(shortBars,3*h,3*h,5*h,0.1))
    }

    @Test fun offGridShortReflectionPreservesCausalGeometryButNotOrderAuthority() {
        val h=3_600_000L
        val lows=listOf(118.0,117.0,115.0,116.0,115.0,116.0)
        val originals=lows.mapIndexed { i,low ->
            WilliamsWaveBar((i+1)*h,low+2.0,low+5.0,low,low+3.0)
        }
        // A non-tick-aligned reflection is a valid geometric transformation,
        // but it is NEVER an executable source-price representation.
        val mirrorAt=220.005
        val mirror=originals.map { it.copy(
            o=mirrorAt-it.o,h=mirrorAt-it.l,
            l=mirrorAt-it.h,c=mirrorAt-it.c)
        }
        val teeth=List(6){104.0}
        assertNull(selectWilliamsPendingFractal("LONG",mirror,teeth,0.1))
        val geometric=selectWilliamsPendingFractal("LONG",mirror,teeth,0.1,
            requireSourceTickGrid=false)!!
        assertEquals(2,geometric.center)
        assertEquals(5,geometric.confirmation)
        val sourceExact=exactTc2ShortFractalLevels(originals,3*h,3*h,6*h,0.1)!!
        assertEquals(114.9,sourceExact.trigger,1e-9)
        assertEquals(122.1,sourceExact.protective,1e-9) // max source HIGH within confirmed stop window
        assertEquals(sourceExact.trigger,mirrorAt-geometric.trigger,1e-9)
        assertEquals(sourceExact.protective,mirrorAt-geometric.protective,1e-9)
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


    @Test fun shortFractalUsesActualExchangeLowAndExactlyOneTick() {
        // Regression from diagnostics 2026-10-26: BTC 79524.8 -> 79524.7
        // and ETH 2297.00 -> 2296.99, never an extra tick from reflection.
        fun source(pivot:Double):List<WilliamsWaveBar> {
            val lows=listOf(pivot+3,pivot+2,pivot,pivot+1,pivot+1.5,pivot+2)
            return lows.mapIndexed { i,low ->
                WilliamsWaveBar(i*3_600_000L,low+1,low+4,low,low+2)
            }
        }
        val btc=exactTc2ShortFractalLevels(source(79524.8),2*3_600_000L,2*3_600_000L,4*3_600_000L,.1)!!
        val eth=exactTc2ShortFractalLevels(source(2297.0),2*3_600_000L,2*3_600_000L,4*3_600_000L,.01)!!
        assertEquals(79524.7,btc.trigger,0.0)
        assertEquals(2296.99,eth.trigger,0.0)
        assertEquals(79531.9,btc.protective,1e-9)
        assertEquals(2304.01,eth.protective,1e-9)
        assertTrue(ExecutionMath.isMultiple(btc.trigger,.1))
        assertTrue(ExecutionMath.isMultiple(eth.trigger,.01))
    }

    @Test fun shortFractalStopHonoursPlateauAndNeverReadsFutureBars() {
        val h=3_600_000L
        val lows=listOf(108.0,105.0,100.0,100.0,104.0,105.0,106.0)
        val bars=lows.mapIndexed { i,low ->
            WilliamsWaveBar(i*h,low+2,low+10,low,low+3)
        }.toMutableList()
        val actual=exactTc2ShortFractalLevels(bars,3*h,2*h,5*h,.1)!!
        assertEquals(99.9,actual.trigger,0.0)
        assertEquals(115.1,actual.protective,1e-9) // five bars incl. plateau
        bars[6]=bars[6].copy(h=99999.0)
        assertEquals(actual,exactTc2ShortFractalLevels(bars,3*h,2*h,5*h,.1))
        bars[3]=bars[3].copy(l=100.005)
        assertNull(exactTc2ShortFractalLevels(bars,3*h,2*h,5*h,.1))
        assertNull(exactTc2ShortFractalLevels(bars,3*h,2*h,4*h,.1))
        assertNull(exactTc2ShortFractalLevels(bars,3*h,4*h,5*h,.1))
    }


    @Test fun wm1Wm2SignalPriceBarUsesOriginalDecimalTicksInBothDirections() {
        // TC2 Russian PDF 125 / 132 / 137 / 139:
        // the bearish reversal and third bearish AO price bar both sell one
        // tick below their own low, never a reflected-Double approximation.
        val btc=WilliamsWaveBar(3_600_000L,79524.5,79524.8,79523.2,79524.7)
        val buy=exactTc2WiseManPriceBarLevels("LONG",btc,.1)!!
        val sell=exactTc2WiseManPriceBarLevels("SHORT",btc,.1)!!
        assertEquals(79524.9,buy.trigger,0.0)
        assertEquals(79523.1,buy.protective,0.0)
        assertEquals(79523.1,sell.trigger,0.0)
        assertEquals(79524.9,sell.protective,0.0)
        for(v in listOf(buy.trigger,buy.protective,sell.trigger,sell.protective))
            assertTrue(ExecutionMath.isMultiple(v,.1))
        val eth=WilliamsWaveBar(7_200_000L,2295.0,2297.0,2294.31,2295.5)
        assertEquals(2294.30,exactTc2WiseManPriceBarLevels("SHORT",eth,.01)!!.trigger,0.0)
        assertEquals(2297.01,exactTc2WiseManPriceBarLevels("SHORT",eth,.01)!!.protective,0.0)
        assertEquals(2297.01,exactTc2WiseManPriceBarLevels("LONG",eth,.01)!!.trigger,0.0)
        assertEquals(2294.30,exactTc2WiseManPriceBarLevels("LONG",eth,.01)!!.protective,0.0)
    }

    @Test fun tc2SignalBarPriceProofFailsClosedOnOffGridOrInvalidCandle() {
        val bar=WilliamsWaveBar(0L,100.0,101.0,99.0,100.5)
        assertNotNull(exactTc2WiseManPriceBarLevels("SHORT",bar,.01))
        assertNull(exactTc2WiseManPriceBarLevels("SHORT",bar.copy(l=99.005),.01))
        assertNull(exactTc2WiseManPriceBarLevels("LONG",bar.copy(h=101.005),.01))
        assertNull(exactTc2WiseManPriceBarLevels("LONG",bar.copy(o=102.0),.01))
        assertNull(exactTc2WiseManPriceBarLevels("LONG",bar.copy(c=Double.NaN),.01))
        assertNull(exactTc2WiseManPriceBarLevels("SHORT",bar.copy(t=-1L),.01))
        assertNull(exactTc2WiseManPriceBarLevels("SHORT",bar,.0))
        assertNull(exactTc2WiseManPriceBarLevels("INVALID",bar,.01))
    }

    @Test fun longSourceFractalExactTick() {
        val h=3_600_000L
        val highs=listOf(101.0,102.0,105.0,103.0,102.0,101.0)
        val bars=highs.mapIndexed { i,high ->
            WilliamsWaveBar(i*h,high-2.0,high,high-4.0,high-1.0)
        }
        val found=exactTc2LongFractalLevels(bars,2*h,2*h,4*h,.1)!!
        assertEquals(105.1,found.trigger,0.0)
        assertEquals(96.9,found.protective,0.0)
        assertNull(exactTc2LongFractalLevels(bars,2*h,2*h,3*h,.1))
        assertNull(exactTc2LongFractalLevels(bars,2*h,3*h,4*h,.1))
        val later=bars+WilliamsWaveBar(6*h,100.0,999.0,99.0,100.0)
        assertEquals(found,exactTc2LongFractalLevels(later,2*h,2*h,4*h,.1))
    }

}
