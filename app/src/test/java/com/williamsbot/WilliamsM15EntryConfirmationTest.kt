package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

class WilliamsM15EntryConfirmationTest {
    private val step = 900_000L
    private val start = 1_800_000_000_000L
    private fun candles(highs: List<Double>, lows: List<Double>, lastClose: Double = (highs.last()+lows.last())/2) =
        highs.indices.map { i -> WilliamsM15Bar(start+i*step, highs[i], lows[i],
            if (i==highs.lastIndex) lastClose else (highs[i]+lows[i])/2) }
    private val shortHighs = listOf(103.0,102.0,101.0,101.0,101.0,101.0,101.0,99.0,99.0)
    private val shortLows  = listOf(99.0,98.0,97.0,96.0,95.0,96.0,97.0,94.0,93.0)
    private val longHighs = shortLows.map { 200.0-it }
    private val longLows = shortHighs.map { 200.0-it }

    @Test fun bearishFractalNeedsTwoRightBarsAndSubsequentBreak() {
        val all = candles(shortHighs, shortLows,94.0)
        val result = williamsM15ReversalEvidence("SHORT",start+4*step,start+all.size*step,.1,all)
        assertTrue(result.confirmed)
        assertEquals(start+4*step,result.pivotOpenMs)
        assertEquals(start+7*step,result.confirmedAtMs)
        assertEquals(start+8*step,result.breakoutAtMs)
        assertFalse(williamsM15ReversalEvidence("SHORT",start+4*step,start+7*step,.1,all.take(7)).confirmed)
    }
    @Test fun bullishFractalIsSymmetricAndCloseMustRetainPivot() {
        val all = candles(longHighs,longLows,107.0)
        assertTrue(williamsM15ReversalEvidence("LONG",start+4*step,start+9*step,.1,all).confirmed)
        val retrace=candles(longHighs,longLows,105.0)
        assertFalse(williamsM15ReversalEvidence("LONG",start+4*step,start+9*step,.1,retrace).confirmed)
    }
    @Test fun confirmationCannotPrecedeH1Knowledge() {
        val all=candles(shortHighs,shortLows,94.0)
        assertFalse(williamsM15ReversalEvidence("SHORT",start+8*step,start+9*step,.1,all).confirmed)
    }
    @Test fun noBreakoutNoProof() {
        val lows=shortLows.toMutableList().apply { this[7]=95.2;this[8]=95.3 }
        val all=candles(shortHighs,lows,96.0)
        assertFalse(williamsM15ReversalEvidence("SHORT",start+4*step,start+9*step,.1,all).confirmed)
    }
    @Test fun rejectGapsUnclosedCandlesAndInvalidValues() {
        val all=candles(shortHighs,shortLows,94.0)
        val missing=all.filterIndexed { i, _ -> i!=3 }
        assertEquals("UNCLOSED_OR_GAPPED_M15",williamsM15ReversalEvidence("SHORT",start+4*step,start+9*step,.1,missing).state)
        assertEquals("UNCLOSED_OR_GAPPED_M15",williamsM15ReversalEvidence("SHORT",start+4*step,start+8*step,.1,all).state)
        assertEquals("INVALID_INPUT",williamsM15ReversalEvidence("SHORT",start+4*step,start+9*step,0.0,all).state)
    }
    @Test fun onlyEarlyUnalignedWm1RequiresConfirmation() {
        assertTrue(williamsRequiresM15ReversalProof("SHORT_REVERSAL",false,false))
        assertTrue(williamsRequiresM15ReversalProof("REVERSAL",false,true))
        assertFalse(williamsRequiresM15ReversalProof("SHORT_REVERSAL",false,true))
        assertFalse(williamsRequiresM15ReversalProof("SHORT_FRACTAL",false,false))
        assertFalse(williamsRequiresM15ReversalProof("SUPER_AO",false,false))
    }
}
