package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic qualitative cases; no claim of original chart OHLC or expert gold. */
class WilliamsWm1SingleBarAngulationTest {
    @Test fun bothSidesMatchSharedQualitativeSourceCases() {
        val data=javaClass.classLoader!!.getResourceAsStream("tc2_wm1_angulation_contract.json")!!
            .bufferedReader().use { JSONObject(it.readText()) }.getJSONArray("cases")
        for(i in 0 until data.length()) {
            val case=data.getJSONObject(i);val id=case.getString("id")
            val input=case.getJSONArray("bars")
            val bars=List(input.length()) { b->val r=input.getJSONArray(b)
                WilliamsWaveBar(r.getLong(0),r.getDouble(1),r.getDouble(2),
                    r.getDouble(3),r.getDouble(4),r.getDouble(5)) }
            fun line(name:String):List<Double> { val a=case.getJSONArray(name)
                return List(a.length()) { if(a.isNull(it)) Double.NaN else a.getDouble(it) } }
            val proof=williamsAngulation(case.getString("side"),bars,line("jaw"),line("teeth"),line("lips"),3_600_000L)
            assertEquals(id,case.getBoolean("valid"),proof.valid)
            assertEquals(id,case.getString("reason"),proof.reason)
            val diagnostic=proof.toJson()
            if(proof.valid) {
                assertEquals(id,case.getInt("baseline"),diagnostic.getInt("gap_growth_baseline_index"))
                assertEquals(id,case.getString("basis"),diagnostic.getString("gap_growth_basis"))
                assertTrue(id,proof.outsideGapGrowthPct!!>0)
            }
            assertNotNull(diagnostic.toString())
        }
    }

    @Test fun selfCreatedAngleStillRequiresDivergentPriceBarAndPriorTrendAo() {
        val raw=listOf(
            WilliamsWaveBar(3_600_000L,100.0,102.0,98.0,100.0,10.0),
            WilliamsWaveBar(7_200_000L,100.0,102.0,98.0,100.0,10.0),
            WilliamsWaveBar(10_800_000L,70.0,95.0,60.0,90.0,10.0))
        for(short in listOf(false,true)) {
            val side=if(short) "SHORT" else "LONG"
            val bars=if(short) raw.map {it.copy(o=250-it.o,h=250-it.l,l=250-it.h,c=250-it.c)} else raw
            val line=List(3) {if(short) 150.0 else 100.0}
            val p=williamsAngulation(side,bars,line,line,line,3_600_000L)
            assertTrue(p.valid)
            val previous=if(short) bars[1].h else bars[1].l
            assertTrue(validWilliamsReversalBar(side,bars.last(),previous,List(3){line.last()},p.score))
            val midpoint=bars.last().copy(c=(bars.last().h+bars.last().l)/2)
            assertFalse(validWilliamsReversalBar(side,midpoint,previous,List(3){line.last()},p.score))
            assertNull(williamsFirstWiseManAoBlocker(side,if(short) 1.0 else -1.0,0.0))
            assertNotNull(williamsFirstWiseManAoBlocker(side,if(short) -1.0 else 1.0,0.0))
            assertNotNull(williamsFirstWiseManAoBlocker(side,Double.NaN,0.0))
        }
    }

    @Test fun finiteInputOverflowFailsClosedAndRemainsJsonSerializable() {
        val bars=listOf(
            WilliamsWaveBar(3_600_000L,1.6e308,1.75e308,1.5e308,1.6e308,10.0),
            WilliamsWaveBar(7_200_000L,1.6e308,1.75e308,1.5e308,1.6e308,10.0),
            WilliamsWaveBar(10_800_000L,1.1e308,1.4e308,1.0e308,1.3e308,10.0),
            WilliamsWaveBar(14_400_000L,1.0e308,1.3e308,0.9e308,1.2e308,10.0))
        val line=List(4){1.65e308}
        val proof=williamsAngulation("LONG",bars,line,line,line,3_600_000L)
        assertFalse(proof.valid)
        assertEquals("NUMERICAL_GEOMETRY_UNVERIFIED",proof.reason)
        assertNotNull(proof.toJson().toString())
    }
}
