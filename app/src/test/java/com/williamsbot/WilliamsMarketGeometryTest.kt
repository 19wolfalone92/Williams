package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

class WilliamsMarketGeometryTest {
    private fun bars(highs:List<Double>)=highs.mapIndexed { i,h -> WilliamsWaveBar((i+1)*3_600_000L,h-1,h,h-3,h-1,10.0) }
    private fun mirror(b:List<WilliamsWaveBar>)=b.map { it.copy(o=250-it.o,h=250-it.l,l=250-it.h,c=250-it.c) }
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
    @Test fun insideFractalMayBecomeValidLaterButEntryBufferCannotQualifyIt() {
        val b=bars(listOf(101.0,102.0,105.0,103.0,102.0,103.0))
        val t=listOf(106.0,106.0,106.0,106.0,106.0,104.0)
        val f=selectWilliamsPendingFractal("LONG",b,t,.1)!!
        assertEquals(2,f.center);assertEquals(4,f.confirmation)
        assertNull(selectWilliamsPendingFractal("LONG",b,List(6){105.05},.1))
        assertNotNull(williamsBookFractalTeethBlocker("FRACTAL","LONG",105.1,105.05,.1))
        val short=selectWilliamsPendingFractal("SHORT",mirror(b),t.map { 250-it },.1)!!
        assertEquals(250-f.trigger,short.trigger,1e-8)
    }
    @Test fun courseSignalLineSideIsIndependentOfLastAoColour() {
        val b=bars((0 until 47).map { 101+it*.2+5*kotlin.math.sin(it*.4) })
        assertEquals("DOWN",williamsCourseRhythm(b).second)
        assertEquals("UP",williamsCourseRhythm(mirror(b)).second)
    }
}
