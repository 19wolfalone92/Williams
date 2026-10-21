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
        val input=bars(listOf(108.0,106.0,104.0,101.0,98.0)).toMutableList()
        // Signal-reference at index 3 is BELOW historic Jaw=120 even
        // though the current base is evaluated against Jaw=100.
        input[3]=input[3].copy(jaw=120.0)
        val result=williamsNtdBalanceLine(input,"LONG",.1)
        assertEquals("CANDIDATE",result.status)
        assertEquals(2,result.requiredSteps)
        assertEquals(104.1,result.triggerPrice,1e-8)
    }

    @Test fun redZoneDoublesRequirementsForBuy() {
        val adverse=bars(listOf(115.0,110.0,107.0,104.0,102.0,98.0),zone="RED").toMutableList()
        adverse[4]=adverse[4].copy(jaw=110.0)
        val result=williamsNtdBalanceLine(adverse,"LONG",.1)
        assertEquals("CANDIDATE",result.status)
        assertEquals(4,result.requiredSteps)
        assertEquals(110.1,result.triggerPrice,1e-8)
        val incomplete=bars(listOf(106.0,104.0,102.0,98.0,98.0),zone="RED").toMutableList()
        incomplete[2]=incomplete[2].copy(jaw=120.0)
        assertEquals("INCOMPLETE",williamsNtdBalanceLine(incomplete,"LONG",.1).status)
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
        assertEquals("BLOCKED",williamsNtdBalanceLine(flat,"LONG",.1).status)
        assertEquals("NO_NEW_REFERENCE_EXTREME",williamsNtdBalanceLine(flat,"LONG",.1).reason)
        val invalid=flat.toMutableList()
        invalid[invalid.lastIndex]=invalid.last().copy(ao=Double.NaN)
        assertEquals("ZONE_NOT_READY",williamsNtdBalanceLine(invalid,"LONG",.1).reason)
    }

    @Test fun bookFigureSignalBarNotBaseJawDeterminesRequiredReferenceCount() {
        val source=bars(listOf(108.0,106.0,104.0,101.0,98.0)).toMutableList()
        val above=williamsNtdBalanceLine(source,"LONG",.1)
        assertEquals(1,above.requiredSteps)
        val below=source.toMutableList()
        below[3]=below[3].copy(jaw=120.0)
        val corrected=williamsNtdBalanceLine(below,"LONG",.1)
        assertEquals(2,corrected.requiredSteps)
        assertEquals("CANDIDATE",corrected.status)
        assertEquals(104.1,corrected.triggerPrice,1e-8)
        assertFalse(corrected.canPlaceOrders)
    }

    @Test fun ntdStandaloneAndSourceEditionBalanceSignalsAgreeOnBookFigExamples() {
        val cases=org.json.JSONObject(requireNotNull(
            javaClass.getResource("/williams_editions_contract.json")).readText()).getJSONArray("cases")
        var compared=0
        for(i in 0 until cases.length()) {
            val sample=cases.getJSONObject(i)
            if(sample.getString("operation")!="BALANCE") continue
            val rows=sample.getJSONArray("bars")
            val originals=(0 until rows.length()).map { j ->
                val v=rows.getJSONArray(j)
                WilliamsWaveBar(v.getLong(0),v.getDouble(1),v.getDouble(2),
                    v.getDouble(3),v.getDouble(4),v.getDouble(5))
            }
            fun indicator(k:String)=(0 until sample.getJSONArray(k).length()).map { j ->
                sample.getJSONArray(k).getDouble(j)
            }
            val jaw=indicator("jaw");val teeth=indicator("teeth");val lips=indicator("lips")
            val ao=indicator("ao");val ac=indicator("ac")
            val research=originals.indices.map { j ->
                WilliamsNtdBar(originals[j].h,originals[j].l,jaw[j],teeth[j],
                    lips[j],ao[j],ac[j])
            }
            val side=sample.getString("side")
            val tick=sample.getDouble("tick")
            val standalone=williamsNtdBalanceLine(research,side,tick)
            val edition=williamsNtdBalanceLineSetup(side,originals,jaw,teeth,lips,
                ao,ac,tick)
            assertEquals(sample.toString(), "CANDIDATE", standalone.status)
            assertTrue(sample.toString(),edition!=null)
            assertEquals(sample.toString(),sample.getDouble("expected_trigger"),
                standalone.triggerPrice,1e-8)
            assertEquals(sample.toString(),edition!!.trigger,standalone.triggerPrice,1e-8)
            // Different fields: one is a structural reference, the other
            // is the native module's protective STOP one tick past the base.
            assertFalse(standalone.canPlaceOrders)
            compared++
        }
        assertEquals(8,compared)
    }
}
