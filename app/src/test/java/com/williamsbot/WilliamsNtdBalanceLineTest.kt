package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WilliamsNtdBalanceLineTest {
    private fun bars(highs: List<Double>, lows: List<Double> = highs.map { it-2.0 },
                     jaw: Double = 100.0, teeth: Double = 99.0, lips: Double = 101.0,
                     zone: String = "GRAY"): List<WilliamsNtdBar> = highs.indices.map { index ->
        val delta = when (index) {
            highs.lastIndex-1 -> when (zone) { "RED" -> 2.0; "GREEN" -> 1.0; else -> 0.0 }
            highs.lastIndex -> when (zone) { "RED" -> 1.0; "GREEN" -> 2.0; else -> 0.0 }
            else -> 0.0
        }
        WilliamsNtdBar(highs[index],lows[index],jaw,teeth,lips,delta,delta)
    }

    @Test fun buyAwayFromJawNeedsOneReferenceHigh() {
        val result=williamsNtdBalanceLine(bars(listOf(110.0,108.0,106.0,105.0,104.0)),"LONG",.1)
        assertEquals("CANDIDATE",result.status)
        assertEquals(1,result.requiredSteps)
        assertEquals(4,result.baseIndex)
        assertEquals(3,result.referenceIndex)
        assertEquals(105.1,result.triggerPrice,1e-8)
        assertFalse(result.canPlaceOrders)
    }

    @Test fun buyBelowJawNeedsTwoReferenceHighs() {
        val result=williamsNtdBalanceLine(bars(listOf(108.0,106.0,104.0,101.0,98.0)),"LONG",.1)
        assertEquals("CANDIDATE",result.status)
        assertEquals(2,result.requiredSteps)
        assertEquals(104.1,result.triggerPrice,1e-8)
    }

    @Test fun redZoneDoublesRequirementsForBuy() {
        val result=williamsNtdBalanceLine(
            bars(listOf(115.0,110.0,107.0,104.0,102.0,98.0),zone="RED"),"LONG",.1)
        assertEquals("CANDIDATE",result.status)
        assertEquals(4,result.requiredSteps)
        assertEquals(110.1,result.triggerPrice,1e-8)
        assertEquals("INCOMPLETE",williamsNtdBalanceLine(
            bars(listOf(106.0,104.0,102.0,98.0,98.0),zone="RED"),"LONG",.1).status)
    }

    @Test fun sellBelowJawAndGreenZoneRule() {
        val lows=listOf(70.0,80.0,90.0,95.0,96.0)
        val away=williamsNtdBalanceLine(
            bars(lows.map { it+2.0 },lows,110.0,111.0,112.0),"SHORT",.1)
        assertEquals("CANDIDATE",away.status)
        assertEquals(1,away.requiredSteps)
        assertEquals(94.9,away.triggerPrice,1e-8)
        val highs=listOf(72.0,77.0,82.0,87.0,92.0,97.0)
        val green=williamsNtdBalanceLine(
            bars(highs,highs.map { it-2.0 },90.0,91.0,92.0,"GREEN"),"SHORT",.1)
        assertEquals("CANDIDATE",green.status)
        assertEquals(4,green.requiredSteps)
        assertEquals(74.9,green.triggerPrice,1e-8)
    }

    @Test fun touchedOldStopIsRejectedWhenTheScannerWasOffline() {
        val result=williamsNtdBalanceLine(bars(listOf(110.0,108.0,106.0,104.0,105.0,107.0)),"LONG",.1)
        assertEquals("BLOCKED",result.status)
        assertEquals("TRIGGER_ALREADY_TOUCHED",result.reason)
    }

    @Test fun outsideMouthGateAndMalformedInputsBlockResearchCandidate() {
        val mouth=williamsNtdBalanceLine(bars(listOf(110.0,108.0,106.0,105.0,104.0),
            jaw=200.0,teeth=199.0,lips=201.0),"LONG",.1)
        assertEquals("INSIDE_ALLIGATOR_MOUTH",mouth.reason)
        assertEquals("INVALID_TICK",williamsNtdBalanceLine(bars(listOf(110.0,108.0,106.0,105.0,104.0)),
            "LONG",0.0).reason)
        assertEquals("INVALID_SIDE",williamsNtdBalanceLine(bars(listOf(110.0,108.0,106.0,105.0,104.0)),
            "BUY",.1).reason)
    }

    @Test fun flatPricesAndMissingAoCannotProduceSignal() {
        val flat=bars(List(6){100.0})
        assertEquals("INCOMPLETE",williamsNtdBalanceLine(flat,"LONG",.1).status)
        val invalid=flat.toMutableList()
        invalid[invalid.lastIndex]=invalid.last().copy(ao=Double.NaN)
        assertEquals("ZONE_NOT_READY",williamsNtdBalanceLine(invalid,"LONG",.1).reason)
    }
}
