package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

/** NTD chapter 4 (PDF43) first outside fractal. Android is the executor,
 * while independent Python verifies these original source bars in parallel.
 * None of these synthetic observations certifies signed Binance STOPs.
 */
class WilliamsNtdFirstFractalExactSourceTest {
    private fun bars(side:String,count:Int=50):List<WilliamsWaveBar> =
        (0 until count).map { i ->
            val hi=if(i==43) 105.0 else 100.0
            val lo=if(i==43) 95.0 else 98.0
            val normal=WilliamsWaveBar((i+1L)*WILLIAMS_H1_MS,99.0,hi,lo,99.0,100.0)
            if(side=="LONG") normal else normal.copy(
                o=300.0-normal.o,h=300.0-normal.l,
                l=300.0-normal.h,c=300.0-normal.c)
        }

    private fun get(side:String,series:List<WilliamsWaveBar>,tick:Double=0.1):List<WilliamsEditionSetup> {
        val n=series.size
        val chartTeeth=if(side=="LONG") 99.0 else 201.0
        val line=List(n){chartTeeth}
        return williamsNtdSetups(side,series,line,line,line,
            List(n){0.0},List(n){0.0},tick,exitMode=true,
            includePendingAo=false).filter { it.kind=="FRACTAL" }
    }

    @Test fun sourceFractalTriggerAndProtectionAreExactlyOneExchangeTick() {
        for(side in listOf("LONG","SHORT")) {
            val values=get(side,bars(side))
            assertEquals(side,1,values.size)
            val f=values.single()
            assertEquals(if(side=="LONG") 105.1 else 194.9,f.trigger,0.0)
            assertEquals(if(side=="LONG") 94.9 else 205.1,f.stop,0.0)
            assertEquals(45,f.knownIndex) // confirmed after two right-hand closed H1 bars
        }
    }

    @Test fun hundredLaterClosedHoursDoNotExpireUntouchedSourceFractal() {
        for(side in listOf("LONG","SHORT")) {
            val source=get(side,bars(side,150))
            assertEquals(side,1,source.size)
            assertEquals(45,source.single().knownIndex)
        }
    }

    @Test fun offGridSourceAndOffGridStructuralStopFailClosed() {
        for(side in listOf("LONG","SHORT")) {
            val original=bars(side)
            val badPivot=original.toMutableList()
            badPivot[43]=if(side=="LONG") badPivot[43].copy(h=105.05)
                else badPivot[43].copy(l=194.95)
            assertTrue(side+" off-grid pivot",get(side,badPivot).isEmpty())
            val badStop=original.toMutableList()
            badStop[43]=if(side=="LONG") badStop[43].copy(l=95.05)
                else badStop[43].copy(h=204.95)
            assertTrue(side+" off-grid stop",get(side,badStop).isEmpty())
            for(tick in listOf(0.0,-0.1,Double.NaN,Double.POSITIVE_INFINITY)) {
                assertTrue(side+" bad tick "+tick,get(side,original,tick).isEmpty())
            }
        }
    }

    @Test fun sourceFractalBreakoutWhileOfflineNeverArmsStaleStop() {
        for(side in listOf("LONG","SHORT")) {
            val data=bars(side).toMutableList()
            data[49]=if(side=="LONG") data[49].copy(h=106.0)
                else data[49].copy(l=194.0)
            assertTrue(side,get(side,data).isEmpty())
        }
    }

    @Test fun unknownLaterExtremeCannotProveAnUntouchedFractal() {
        for(side in listOf("LONG","SHORT")) {
            for(bad in listOf(Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,-1.0)) {
                val data=bars(side).toMutableList()
                data[48]=if(side=="LONG") data[48].copy(h=bad) else data[48].copy(l=bad)
                assertTrue(side+" unknown later price "+bad,get(side,data).isEmpty())
            }
        }
    }
}
