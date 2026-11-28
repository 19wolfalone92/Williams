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
        val correctedPoints=full(corrected)
        assertEquals(correctedPoints,state.update("BTC:1h",corrected))
        assertEquals(correctedPoints.drop(1),state.update("BTC:1h",corrected.drop(1)))
        assertEquals(2L,state.rebuilds)
    }

    @Test fun slidingWindowAppendsOnlyNewBarsAndKeepsTheOriginalRecursiveSeed() {
        val state=IncrementalWilliamsMath()
        val initial=bars.take(400)
        assertEquals(full(initial),state.update("BTC:1h",initial))
        val all=bars.take(401)
        val nextWindow=all.takeLast(400)
        assertEquals(full(all).takeLast(400),state.update("BTC:1h",nextWindow))
        assertEquals(1L,state.rebuilds)
        assertEquals(401L,state.appendedPoints)

        val allAgain=bars.take(402)
        assertEquals(full(allAgain).takeLast(400),
            state.update("BTC:1h",allAgain.takeLast(400)))
        assertEquals(1L,state.rebuilds)
        assertEquals(402L,state.appendedPoints)
    }

    @Test fun smallerViewportDoesNotDiscardTheLiveAccumulator() {
        val state=IncrementalWilliamsMath()
        val initial=bars.take(400)
        val fullInitial=state.update("BTC:1h",initial)
        assertEquals(fullInitial.takeLast(150),state.update("BTC:1h",initial.takeLast(150)))
        assertEquals(1L,state.rebuilds)

        val all=bars.take(401)
        assertEquals(full(all).takeLast(150),
            state.update("BTC:1h",all.takeLast(150)))
        assertEquals(1L,state.rebuilds)
        assertEquals(401L,state.appendedPoints)
    }

    @Test fun seedIdentitySurvivesWindowSlideAndChangesOnHistoricalRebuild() {
        val state=IncrementalWilliamsMath()
        val initial=bars.take(400)
        state.update("BTC:1h:seed=0",initial)
        val originalSeed=state.seedOpenMs
        assertEquals(initial.first().openMs,originalSeed)

        val slid=bars.take(401).takeLast(400)
        state.update("BTC:1h:seed=0",slid)
        assertEquals(originalSeed,state.seedOpenMs)

        state.update("BTC:1h:seed=1",slid)
        assertEquals(slid.first().openMs,state.seedOpenMs)
    }

    @Test fun r49FiveHundredBarsConvergeAlligatorSeedButTwoHundredTwentyDoNotForJaw() {
        val history = (1..1000).map { ClosedMedianBar(it * 3_600_000L, 100_000.0) } +
            (1001..1220).map { ClosedMedianBar(it * 3_600_000L, 0.0) }
        val reference = full(history).last()
        val warm500 = full(history.takeLast(WILLIAMS_ALLIGATOR_WARMUP_BARS)).last()
        val warm220 = full(history.takeLast(220)).last()
        assertTrue(kotlin.math.abs(reference.jaw!! - warm500.jaw!!) <= 1e-8)
        assertTrue(kotlin.math.abs(reference.teeth!! - warm500.teeth!!) <= 1e-8)
        assertTrue(kotlin.math.abs(reference.lips!! - warm500.lips!!) <= 1e-8)
        assertTrue(kotlin.math.abs(reference.jaw!! - warm220.jaw!!) > 1e-8)
    }

    @Test fun flatPricesDoNotInventAoColourOrCrossing() {
        val flat=(1..100).map { ClosedMedianBar(it*3600000L,0.1) }
        assertEquals(full(flat),IncrementalWilliamsMath().update("FLAT:1h",flat))
    }
}
