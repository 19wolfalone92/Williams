package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Causal TC2 figure 11.1D source boundary: historical seven/nine-bar
 * final geometry may have a DIFFERENT but already-confirmed six/eight-bar
 * prefix. Never reject a real-time valid prefix using unavailable future. */
class WilliamsFractalSourceAmbiguityTest {
    @Test fun conservativeTimingAndOriginalIdentityMatchSharedSourceBoundaryCases() {
        val data=javaClass.classLoader!!.getResourceAsStream("tc2_fractal_source_ambiguity.json")!!
            .bufferedReader().use {JSONObject(it.readText())}.getJSONArray("cases")
        for(c in 0 until data.length()) {
            val case=data.getJSONObject(c);val id=case.getString("id")
            val rows=case.getJSONArray("bars")
            val bars=List(rows.length()) {i->val r=rows.getJSONArray(i)
                WilliamsWaveBar(r.getLong(0),r.getDouble(1),r.getDouble(2),
                    r.getDouble(3),r.getDouble(4),r.getDouble(5)) }
            for(end in 5..bars.size) {
                val actual=selectWilliamsPendingFractal(case.getString("side"),
                    bars.take(end),List(end){case.getDouble("teeth")},.1)
                val expected=case.optJSONObject("expected")
                if (end==bars.size) {
                    if(expected==null) assertNull(id,actual)
                    else {
                        assertNotNull(id,actual)
                        assertEquals(id,expected.getInt("center"),actual!!.center)
                        assertEquals(id,expected.getInt("group_start"),actual.groupStart)
                        assertEquals(id,expected.getInt("confirmation"),actual.confirmation)
                        assertEquals(id,expected.getDouble("trigger"),actual.trigger,1e-10)
                        assertEquals(id,expected.getDouble("protective"),actual.protective,1e-10)
                    }
                }
                // At any prefix, a candidate is only legal once its OWN
                // two strictly lower/higher right flanks are closed. Do not
                // compare with the final future pivot or future center.
                if(actual!=null) {
                    assertTrue(id,actual.confirmation<end)
                    assertTrue(id,actual.center+2<=actual.confirmation)
                    assertTrue(id,actual.groupStart<=actual.center)
                    val kind=if(case.getString("side")=="LONG") "HIGH" else "LOW"
                    assertTrue(id,confirmedWilliamsEditionFractals(bars.take(end)).any {
                        it.kind==kind && it.center==actual.center &&
                            it.groupStart==actual.groupStart &&
                            it.confirmation==actual.confirmation })
                }
            }
        }
    }

    @Test fun fullGroupMustStartAfterOpeningBeforeItCanProposeFractalTrailing() {
        val lows=listOf(111.0,110.0,108.0,110.0,108.0,110.0,108.0,110.0,111.0)
        val raw=lows.mapIndexed {i,l->FuturesTrailBar((i+1)*3_600_000L,120.0,l)}
        for(short in listOf(false,true)) {
            val bars=if(short) raw.map {it.copy(high=250-it.low,low=250-it.high)} else raw
            val side=if(short) "SHORT" else "LONG"
            val old=if(short) 150.0 else 100.0;val entry=if(short) 135.0 else 115.0
            assertNotNull(planFuturesStopTrail(side,old,entry,old,125.0,.1,3_600_000L,bars))
            assertNull(planFuturesStopTrail(side,old,entry,old,125.0,.1,4*3_600_000L,bars))
        }
    }

    @Test fun unknownLeftFlankBeforeWholeGroupCannotCreateEntryOrWave() {
        val highs=listOf(105.0,103.0,105.0,103.0,105.0,103.0,102.0)
        val raw=highs.mapIndexed {i,h->WilliamsWaveBar((i+1)*3_600_000L,100.0,h,99.0,100.0,10.0)}
        for(short in listOf(false,true)) {
            val bars=if(short) raw.map {it.copy(o=250-it.o,h=250-it.l,l=250-it.h,c=250-it.c)} else raw
            val side=if(short) "SHORT" else "LONG";val kind=if(short) "LOW" else "HIGH"
            assertFalse(confirmedWilliamsEditionFractals(bars).any {it.kind==kind})
            assertFalse(confirmedWilliamsWaveAnchors(bars,3_600_000L).any {it.kind==kind})
            assertNull(selectWilliamsPendingFractal(side,bars,List(bars.size){if(short) 150.0 else 100.0},.1))
        }
    }

    @Test fun completedOlderGroupDoesNotMergeWithLaterEqualPivot() {
        val highs=listOf(101.0,102.0,105.0,103.0,102.0,105.0,103.0,102.0)
        val raw=highs.mapIndexed {i,h->WilliamsWaveBar((i+1)*3_600_000L,100.0,h,99.0,100.0,10.0)}
        for(short in listOf(false,true)) {
            val bars=if(short) raw.map {it.copy(o=250-it.o,h=250-it.l,l=250-it.h,c=250-it.c)} else raw
            val kind=if(short) "LOW" else "HIGH"
            val pivots=confirmedWilliamsEditionFractals(bars).filter {it.kind==kind}
            assertEquals(listOf(Triple(2,2,4),Triple(5,5,7)),
                pivots.map {Triple(it.center,it.groupStart,it.confirmation)})
        }
    }
}
