package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsWaveMomentumEvidenceTest {
    private val h=3_600_000L
    private fun prices(direction:String):List<WilliamsWaveBar> = (0 until 120).map { i->
        val p=if(i==70) 150.0 else 100.0
        val b=WilliamsWaveBar((1000+i)*h,p,p+1,p-1,p,1.0)
        if(direction=="UP") b else b.copy(o=300-b.o,h=300-b.l,l=300-b.h,c=300-b.c)
    }
    private fun values(direction:String,peak:Int=72):MutableList<Double> = (0 until 120).map { i->
        val a=when { i<33->Double.NaN;i==peak->10.0;i>=90->-1.0;else->2.0 }
        if(direction=="UP") a else -a
    }.toMutableList()

    @Test fun sharedSourceExamplesAndCounterexamplesKeepTheirCausalTimes() {
        val cases=JSONObject(requireNotNull(javaClass.getResource(
            "/williams_w3_momentum_source_contract.json")).readText()).getJSONArray("cases")
        for(i in 0 until cases.length()) for(dir in listOf("UP","DOWN")) {
            val case=cases.getJSONObject(i)
            val a=values(dir,case.getInt("peak_index"))
            val bars=prices(dir).toMutableList()
            if(case.has("other_peak")) {
                val other=case.getJSONArray("other_peak")
                a[other.getInt(0)]=other.getDouble(1)*(if(dir=="UP") 1 else -1)
            }
            if(case.has("higher_bar")) {
                val index=case.getInt("higher_bar")
                bars[index]=if(dir=="UP") bars[index].copy(h=160.0) else bars[index].copy(l=140.0)
            }
            val end=case.getInt("end_index")
            val result=evaluateWilliamsThirdMomentum(bars,a,40,70,end,dir,h)
            assertEquals(case.getString("id"),case.getString("expected"),result.status)
            if(case.has("lag")) {
                assertEquals(case.getInt("lag"),result.lagBars)
                assertEquals((1000+case.getInt("known_bar"))*h,result.knownAt)
            }
            for(j in end+1 until a.size) { a[j]=10000.0;bars[j]=bars[j].copy(h=99999.0) }
            assertEquals(result,evaluateWilliamsThirdMomentum(bars,a,40,70,end,dir,h))
        }
    }

    @Test fun hierarchyAndPhaseExposeEvidenceAndExplainUnconfirmedMomentum() {
        val case=JSONObject(requireNotNull(javaClass.getResource("/williams_wave_contract.json")).readText())
            .getJSONArray("cases").getJSONObject(0)
        val rows=case.getJSONArray("bars")
        val bars=(0 until rows.length()).map { i -> val b=rows.getJSONArray(i)
            WilliamsWaveBar(b.getLong(0),b.getDouble(1),b.getDouble(2),b.getDouble(3),b.getDouble(4),b.getDouble(5)) }
        val report=buildWilliamsWaveHierarchy(mapOf("1h" to bars),case.getLong("as_of"))
        val scenario=report.scenarios.first { it.anchors.size==6 && it.status=="PROVISIONAL" }
        val evidence=requireNotNull(scenario.thirdMomentum)
        assertEquals("TC1_PDF60_COURSE_PDF42",evidence.getString("source"))
        val frames=williamsMarketPhaseContext(report.toJson()).getJSONArray("frames")
        val frame=(0 until frames.length()).map { frames.getJSONObject(it) }.single { it.getString("frame")=="1h" }
        val all=frame.getJSONArray("hypotheses")
        val item=(0 until all.length()).map { all.getJSONObject(it) }.single { it.getString("scenario_id")==scenario.id }
        assertEquals(evidence.toString(),item.getJSONObject("third_momentum").toString())
        assertTrue(williamsThirdMomentumLabel(evidence).startsWith("W3:"))
        assertTrue(williamsThirdMomentumLabel(williamsThirdMomentumUnavailable(
            "SCALE_PRICE_EXTREME_MISSING_OR_AMBIGUOUS").toJson()).contains("нет однозначной привязки"))
    }

    @Test fun globalPeakTwoBarsAfterTheActualThirdPriceExtremeIsEvidenceInBothDirections() {
        for(dir in listOf("UP","DOWN")) {
            val e=evaluateWilliamsThirdMomentum(prices(dir),values(dir),40,70,110,dir,h)
            assertTrue(e.toJson().toString(),e.observed)
            assertEquals(2,e.lagBars)
            assertEquals((1000+74)*h,e.knownAt)
            assertFalse(e.toJson().getBoolean("wave_degree_confirmed"))
            assertFalse(e.toJson().getBoolean("can_close_positions"))
        }
    }

    @Test fun earlierStrongerMomentumDisqualifiesAConvenientLocalThirdPeak() {
        val a=values("UP");a[50]=12.0
        val e=evaluateWilliamsThirdMomentum(prices("UP"),a,40,70,110,"UP",h)
        assertFalse(e.observed)
        assertEquals("GLOBAL_PEAK_OUTSIDE_W3_PRICE_LAG_WINDOW",e.status)
        assertEquals(12.0,e.globalPeak!!,0.0)
    }

    @Test fun lagZeroBeforePriceOrMoreThanFiveBarsCannotBecomeBookEvidence() {
        for(peak in listOf(69,70,76)) {
            val e=evaluateWilliamsThirdMomentum(prices("UP"),values("UP",peak),40,70,110,"UP",h)
            assertFalse("peak=$peak",e.observed)
        }
    }

    @Test fun competingEqualPeakAndWrongPriceMaximumStayUnconfirmed() {
        val a=values("UP");a[50]=10.0
        assertEquals("MULTIPLE_GLOBAL_PEAKS_DEGREE_UNCONFIRMED",
            evaluateWilliamsThirdMomentum(prices("UP"),a,40,70,110,"UP",h).status)
        val b=prices("UP").toMutableList();b[69]=b[69].copy(h=160.0)
        assertFalse(evaluateWilliamsThirdMomentum(b,values("UP"),40,70,110,"UP",h).observed)
    }

    @Test fun anUnturnedLastPeakAndMissingWarmupAreNotConfirmed() {
        assertEquals("GLOBAL_PEAK_TURN_NOT_YET_OBSERVED",
            evaluateWilliamsThirdMomentum(prices("UP"),values("UP"),40,70,72,"UP",h).status)
        val a=values("UP");a[40]=Double.NaN
        assertFalse(evaluateWilliamsThirdMomentum(prices("UP"),a,40,70,110,"UP",h).observed)
    }

    @Test fun futureMomentumCannotRewriteTheAlreadyClosedWaveEvidence() {
        val a=values("UP");val baseline=evaluateWilliamsThirdMomentum(prices("UP"),a,40,70,110,"UP",h)
        for(i in 111 until a.size) a[i]=10000.0
        assertEquals(baseline,evaluateWilliamsThirdMomentum(prices("UP"),a,40,70,110,"UP",h))
    }
}
