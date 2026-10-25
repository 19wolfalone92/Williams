package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

class WilliamsMarketGeometryTest {
    private fun bars(highs:List<Double>)=highs.mapIndexed { i,h -> WilliamsWaveBar((i+1)*3_600_000L,h-1,h,h-3,h-1,10.0) }
    private fun mirror(b:List<WilliamsWaveBar>)=b.map { it.copy(o=250-it.o,h=250-it.l,l=250-it.h,c=250-it.c) }
    @Test fun tc2Wm1Pdf125AoColourIsExpectedButNotAnAdditionalBookEntryPrerequisite() {
        // TC2 PDF125: AO red/green is "we expect", not a stated "only if"
        // condition. The REQUIRED elements are the divergent price bar,
        // absence of Alligator mouth penetration and increasing angulation.
        val reversed=WilliamsWaveBar(3_600_000L,92.0,96.0,89.0,94.0,5.0)
        assertTrue(validWilliamsReversalBar("LONG",reversed,90.0,
            listOf(103.0,102.0,101.0),angulation=1.0))
        assertEquals("WM1_AO_NOT_RED",williamsFirstWiseManAoBlocker("LONG",2.0,1.0))
        val source=org.json.JSONObject()
            .put("wm1_ao_color","GREEN")
            .put("wm1_ao_context_status","WM1_AO_NOT_RED")
            .put("wm1_ao_color_is_mandatory",false)
        val short=org.json.JSONObject(reflectedTc2SetupContext("REVERSAL",source.toString(),250.0))
        assertEquals("RED",short.getString("wm1_ao_color"))
        assertEquals("WM1_AO_NOT_GREEN",short.getString("wm1_ao_context_status"))
        assertFalse(short.getBoolean("wm1_ao_color_is_mandatory"))
        val matching=org.json.JSONObject(reflectedTc2SetupContext("REVERSAL",
            source.put("wm1_ao_color","RED")
                .put("wm1_ao_context_status","EXPECTED_COUNTERTREND_COLOUR").toString(),250.0))
        assertEquals("GREEN",matching.getString("wm1_ao_color"))
        assertEquals("EXPECTED_COUNTERTREND_COLOUR",matching.getString("wm1_ao_context_status"))
    }

    @Test fun tc2Wm1AoMustStillRunAgainstTheCountertrendEntryForBothSides() {
        assertNull(williamsFirstWiseManAoBlocker("LONG",-2.0,-1.5))
        assertNull(williamsFirstWiseManAoBlocker("LONG",3.0,3.5))
        assertNull(williamsFirstWiseManAoBlocker("SHORT",-1.0,-2.0))
        assertNull(williamsFirstWiseManAoBlocker("SHORT",4.0,3.0))
        assertEquals("WM1_AO_NOT_RED",williamsFirstWiseManAoBlocker("LONG",3.5,3.0))
        assertEquals("WM1_AO_NOT_GREEN",williamsFirstWiseManAoBlocker("SHORT",1.0,1.5))
        assertEquals("WM1_AO_UNVERIFIED",williamsFirstWiseManAoBlocker("LONG",Double.NaN,1.0))
        assertEquals("WM1_AO_UNVERIFIED",williamsFirstWiseManAoBlocker("SHORT",1.0,Double.POSITIVE_INFINITY))
    }
    @Test fun mouthAnchoredPriceEdgesAreComparedWithTheSameAlligatorPath() {
        val b=bars(listOf(101.0,98.0,94.0,89.0,84.0));val j=listOf(100.0,99.0,98.0,97.0,96.0)
        val t=listOf(100.0,99.2,98.4,97.6,96.8);val l=listOf(100.0,99.5,99.0,98.5,98.0)
        for(short in listOf(false,true)) {
            val p=if(short) williamsAngulation("SHORT",mirror(b),j.map { 250-it },t.map { 250-it },l.map { 250-it })
                else williamsAngulation("LONG",b,j,t,l)
            assertTrue(p.valid);assertEquals(0,p.anchor);assertEquals(5,p.examinedBars)
        }
    }
    @Test fun retreatingAlligatorDoesNotCreateAnOppositePricePath() {
        val b=bars(listOf(101.0,102.0,103.0,104.0,105.0));val j=listOf(100.0,105.0,110.0,115.0,120.0)
        assertFalse(williamsAngulation("LONG",b,j,j,j).valid)
        assertFalse(williamsAngulation("SHORT",mirror(b),j.map { 250-it },j.map { 250-it },j.map { 250-it }).valid)
        assertEquals("MOUTH_CROSSING_UNOBSERVED",williamsAngulation("LONG",bars(listOf(91.0,88.0,84.0)),List(3){100.0},List(3){100.0},List(3){100.0}).reason)
    }
    @Test fun tc2AngulationBookCounterexamplesRejectParallelAndMouthTouch() {
        // Qualitative TC2 ch9 figs 9.5 and 9.11 are hand-labelled
        // concepts, NOT digitizations of the printed graph pixels.
        val parallel=bars(listOf(101.0,96.0,93.0,90.0,87.0))
        val jaw=listOf(100.0,99.0,96.0,93.0,90.0)
        for(short in listOf(false,true)) {
            val input=if(short) mirror(parallel) else parallel
            val lines=if(short) jaw.map {250-it} else jaw
            val side=if(short) "SHORT" else "LONG"
            val proof=williamsAngulation(side,input,lines,lines,lines)
            assertFalse(proof.valid)
            assertEquals("PARALLEL_AFTER_MOUTH_EXIT",proof.reason)
        }
        val decline=bars(listOf(101.0,98.0,94.0,89.0,84.0))
        val jawDown=listOf(100.0,99.0,98.0,97.0,96.0)
        val teeth=listOf(100.0,99.2,98.4,97.6,96.8)
        val lips=listOf(100.0,99.5,99.0,98.5,98.0)
        val touching=decline.dropLast(1)+decline.last().copy(o=96.0,h=97.0,l=95.0,c=96.0)
        assertEquals("SIGNAL_BAR_TOUCHES_MOUTH",
            williamsAngulation("LONG",touching,jawDown,teeth,lips).reason)
        assertTrue(williamsAngulation("LONG",decline,jawDown,teeth,lips).valid)
    }
    @Test fun tc2AngulationClosedCandleContinuityAndEvidenceAreRequired() {
        val input=bars(listOf(101.0,98.0,94.0,89.0,84.0))
        val jaw=listOf(100.0,99.0,98.0,97.0,96.0)
        val t=listOf(100.0,99.2,98.4,97.6,96.8)
        val lips=listOf(100.0,99.5,99.0,98.5,98.0)
        val proof=williamsAngulation("LONG",input,jaw,t,lips,3_600_000L)
        assertTrue(proof.valid)
        assertEquals(1,proof.firstOutside)
        assertTrue(proof.outsideGapGrowthPct!!>0)
        assertEquals("MOUTH_ANCHORED_EDGE_SLOPES",proof.toJson().getString("method"))
        val missing=input.mapIndexed { i,b ->
            if(i>=2) b.copy(t=b.t+3_600_000L) else b
        }
        assertEquals("CANDLE_PATH_GAP",
            williamsAngulation("LONG",missing,jaw,t,lips,3_600_000L).reason)
        assertEquals("CANDLE_PATH_GAP",
            williamsAngulation("LONG",input,jaw,t,lips,900_000L).reason)
    }
    @Test fun narrowingOrderedTrendIsNotSleeping() {
        val b=bars(listOf(113.0,114.0,115.0,116.0,117.0))
        val j=listOf(100.0,102.0,104.0,106.0,108.0);val t=listOf(105.0,106.0,107.0,108.0,109.0);val l=listOf(110.0,110.5,111.0,111.5,112.0)
        assertEquals("SATIATED",williamsAlligatorPhases(b,j,t,l).last())
        assertFalse(williamsNtdInitialContext(b,j,t,l,4,.1).getBoolean("eligible"))
    }
    @Test fun sleepAwakeningAndEatingAreDistinct() {
        val b=bars(listOf(101.0,101.0,101.0,107.0,112.0))
        assertEquals(listOf("UNKNOWN","SLEEPING","SLEEPING","AWAKENING","EATING"),williamsAlligatorPhases(b,
            listOf(100.0,100.0,100.0,101.0,102.0),listOf(100.0,100.0,100.0,102.0,104.0),listOf(100.0,100.0,100.0,103.0,106.0)))
    }
    @Test fun tc2FractalMustBreakBeyondCurrentTeethEvenWhenPivotWasInside() {
        val b=bars(listOf(101.0,102.0,105.0,103.0,102.0,103.0))
        val t=listOf(106.0,106.0,106.0,106.0,106.0,104.0)
        val f=selectWilliamsPendingFractal("LONG",b,t,.1)!!
        assertEquals(2,f.center);assertEquals(4,f.confirmation)
        // TC2 ch.11 pp.147-149: the breakout, not the formation pivot,
        // must be above Teeth. 105.1 > 105.05 is a textbook-valid level.
        assertNotNull(selectWilliamsPendingFractal("LONG",b,List(6){105.05},.1))
        assertNull(williamsBookFractalTeethBlocker("FRACTAL","LONG",105.1,105.05,.1))
        assertNull(selectWilliamsPendingFractal("LONG",b,List(6){105.1},.1))
        assertNotNull(williamsBookFractalTeethBlocker("FRACTAL","LONG",105.1,105.1,.1))
        val short=selectWilliamsPendingFractal("SHORT",mirror(b),t.map { 250-it },.1)!!
        assertEquals(250-f.trigger,short.trigger,1e-8)
        assertNotNull(selectWilliamsPendingFractal("SHORT",mirror(b),List(6){144.95},.1))
        assertNull(williamsBookFractalTeethBlocker("SHORT_FRACTAL","SHORT",144.9,144.95,.1))
        assertNotNull(williamsBookFractalTeethBlocker("SHORT_FRACTAL","SHORT",144.9,144.9,.1))
    }
    @Test fun wm3CannotConfirmAcrossMissingOrNonAlignedHoursForEitherSide() {
        val hour=3_600_000L
        val raw=bars(listOf(101.0,102.0,105.0,103.0,102.0,103.0))
        val teeth=listOf(106.0,106.0,106.0,106.0,106.0,104.0)
        for(short in listOf(false,true)) {
            val side=if(short) "SHORT" else "LONG"
            val series=if(short) mirror(raw) else raw
            val lines=if(short) teeth.map {250-it} else teeth
            assertNotNull(selectWilliamsPendingFractal(side,series,lines,.1))
            val gap=series.mapIndexed { i,b->if(i>=3) b.copy(t=b.t+hour) else b }
            assertNull(selectWilliamsPendingFractal(side,gap,lines,.1))
            assertNull(selectWilliamsPendingFractal(side,series.map {it.copy(t=it.t+60_000L)},lines,.1))
        }
    }

    @Test fun courseSignalLineSideIsIndependentOfLastAoColour() {
        val b=bars((0 until 47).map { 101+it*.2+5*kotlin.math.sin(it*.4) })
        assertEquals("DOWN",williamsCourseRhythm(b).second)
        assertEquals("UP",williamsCourseRhythm(mirror(b)).second)
    }
}
