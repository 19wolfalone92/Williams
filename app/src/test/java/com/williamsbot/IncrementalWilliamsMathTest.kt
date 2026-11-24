package com.williamsbot

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class IncrementalWilliamsMathTest {
    private val bars=Random(92).let { rng ->
        (1..800).map { i -> ClosedMedianBar(i*3600000L,100.0+rng.nextDouble(-12.0,12.0)) }
    }
    private fun full(input:List<ClosedMedianBar>):List<WilliamsMathPoint> {
        val median=input.map { it.median }
        val ao=median.indices.map { awesomeOscillatorAt(median,it) }
        val ac=ao.indices.map { acceleratorOscillatorAt(ao,it) }
        val jaw=shiftAlligatorForDisplay(smmaSeededValues(median,13),8)
        val teeth=shiftAlligatorForDisplay(smmaSeededValues(median,8),5)
        val lips=shiftAlligatorForDisplay(smmaSeededValues(median,5),3)
        return median.indices.map { WilliamsMathPoint(ao[it],ac[it],jaw[it],teeth[it],lips[it]) }
    }
    @Test fun unchangedWindowDoesNoMoreMathAndAppendOnlyComputesNewBars() {
        val state=IncrementalWilliamsMath()
        val initial=bars.take(150)
        assertEquals(full(initial),state.update("BTC:1h",initial))
        assertEquals(150L,state.appendedPoints)
        assertEquals(full(initial),state.update("BTC:1h",initial.toList()))
        assertEquals(150L,state.appendedPoints)
        assertEquals(full(bars),state.update("BTC:1h",bars))
        assertEquals(800L,state.appendedPoints)
        assertEquals(1L,state.rebuilds)
        assertEquals(full(bars),IncrementalWilliamsMath().update("BTC:1h",bars))
    }
    @Test fun historicalCorrectionAndShiftedSeedRebuildWithoutDrift() {
        val state=IncrementalWilliamsMath()
        state.update("BTC:1h",bars)
        val corrected=bars.mapIndexed { i,b -> if(i==31) b.copy(median=b.median+3.5) else b }
        assertEquals(full(corrected),state.update("BTC:1h",corrected))
        assertEquals(full(corrected.drop(1)),state.update("BTC:1h",corrected.drop(1)))
        assertEquals(3L,state.rebuilds)
    }
    @Test fun flatPricesDoNotInventAoColourOrCrossing() {
        val flat=(1..100).map { ClosedMedianBar(it*3600000L,0.1) }
        assertEquals(full(flat),IncrementalWilliamsMath().update("FLAT:1h",flat))
    }
}
