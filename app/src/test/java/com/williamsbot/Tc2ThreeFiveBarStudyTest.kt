package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

class Tc2ThreeFiveBarStudyTest {
    private val h = WILLIAMS_H1_MS

    private fun longBars(): List<FuturesTrailBar> = listOf(
        98.0, 100.0, 102.0, 103.0, 105.0, 107.0
    ).mapIndexed { i, low -> FuturesTrailBar((15+i)*h,low+2.0,low) }

    private fun shortBars(): List<FuturesTrailBar> = listOf(
        122.0, 120.0, 118.0, 117.0, 115.0, 113.0
    ).mapIndexed { i, high -> FuturesTrailBar((15+i)*h,high,high-2.0) }

    @Test fun longAndShortMirrorExactlyOnThreeAndFiveClosedBars() {
        for(side in listOf("LONG","SHORT")) {
            val long=side=="LONG"
            val study=tc2ThreeFiveBarStudy(side, if(long) 95.0 else 125.0,
                if(long) 130.0 else 90.0,1.0,15*h,21*h,
                if(long) longBars() else shortBars())
            assertEquals("CLOSED_H1_VALIDATED",study.getString("series_state"))
            assertFalse(study.getBoolean("execution_authority"))
            assertEquals(165,study.getInt("source_pdf_page"))
            val three=study.getJSONObject("window3")
            val five=study.getJSONObject("window5")
            assertEquals("SHADOW_ELIGIBLE_NOT_AN_ORDER",three.getString("status"))
            assertEquals("SHADOW_ELIGIBLE_NOT_AN_ORDER",five.getString("status"))
            assertEquals(if(long) 102.0 else 118.0,three.getDouble("source_stop_price"),1e-9)
            assertEquals(if(long) 99.0 else 121.0,five.getDouble("source_stop_price"),1e-9)
            assertTrue(three.getDouble("stop_risk_reduction_per_unit") >
                five.getDouble("stop_risk_reduction_per_unit"))
        }
    }

    @Test fun thirdCompletedHourCanBeComparedButFiveIsNotPretended() {
        val result=tc2ThreeFiveBarStudy("LONG",95.0,130.0,1.0,18*h,21*h,longBars())
        assertEquals("SHADOW_ELIGIBLE_NOT_AN_ORDER",
            result.getJSONObject("window3").getString("status"))
        assertEquals("NOT_ENOUGH_FULL_POST_OBSERVATION_BARS",
            result.getJSONObject("window5").getString("status"))
        assertEquals(3,result.getJSONObject("window5").getInt("observed_post_position_bars"))
    }

    @Test fun futureFormingCandleOrMissingHourFailsBothWindowsClosed() {
        val forming=tc2ThreeFiveBarStudy("LONG",95.0,130.0,1.0,
            15*h,20*h,longBars())
        assertEquals("CLOSED_H1_CONTINUITY_OR_OHLC_UNVERIFIED",
            forming.getString("series_state"))
        assertEquals("UNVERIFIED_INPUT",forming.getJSONObject("window3").getString("status"))
        val gap=longBars().toMutableList().also {
            val b=it[3];it[3]=b.copy(openTimeMs=b.openTimeMs+5*h)
        }
        val invalid=tc2ThreeFiveBarStudy("LONG",95.0,130.0,1.0,
            15*h,30*h,gap)
        assertEquals("CLOSED_H1_CONTINUITY_OR_OHLC_UNVERIFIED",
            invalid.getString("series_state"))
    }

    @Test fun staleButOtherwiseContiguousH1IsNotCurrentStopEvidence() {
        val stale=tc2ThreeFiveBarStudy(
            "LONG",95.0,130.0,1.0,15*h,25*h,longBars())
        assertEquals("STALE_OR_MISSING_LATEST_CLOSED_H1",
            stale.getString("series_state"))
        assertEquals("UNVERIFIED_INPUT",
            stale.getJSONObject("window5").getString("status"))
    }

    @Test fun sourceOffTickAndTooCloseToMarkNeverBecomeExecutableOrders() {
        val sourceOffGrid=longBars().toMutableList().also {
            val b=it[1]; it[1]=b.copy(low=100.5)
        }
        val study=tc2ThreeFiveBarStudy("LONG",95.0,130.0,1.0,
            15*h,21*h,sourceOffGrid)
        assertEquals("SOURCE_EXTREME_OFF_EXCHANGE_TICK_GRID",
            study.getJSONObject("window5").getString("status"))
        val tooClose=tc2ThreeFiveBarStudy("LONG",95.0,102.0,1.0,15*h,21*h,longBars())
        assertEquals("PROPOSAL_TOO_CLOSE_TO_MARK",
            tooClose.getJSONObject("window3").getString("status"))
        assertFalse(tooClose.getJSONObject("window3").getBoolean("execution_authority"))
    }

    @Test fun reviewerRetainsBothBookObservationsEvenIfH4Unavailable() {
        val bars=(0 until 60).map { i ->
            val low=100.0+i*0.5
            WilliamsWaveBar(i*h,low+1,low+3,low,low+2,1.0)
        }
        val outcome=reviewWilliamsTc2StopTightening(
            direction="LONG",oldStop=100.0,entry=110.0,initialStop=100.0,
            mark=145.0,tick=0.5,openedAtMs=50*h,asOfMs=60*h,
            h1Input=bars,h4Input=emptyList()
        )
        assertNull(outcome.proposal)
        assertEquals("CLOSED_H4_HISTORY_NOT_VERIFIED",outcome.blocker)
        val study=outcome.evidence.getJSONObject("tc2_three_five_bar_shadow")
        assertFalse(study.getBoolean("execution_authority"))
        assertEquals("SHADOW_ELIGIBLE_NOT_AN_ORDER",
            study.getJSONObject("window3").getString("status"))
        assertEquals("SHADOW_ELIGIBLE_NOT_AN_ORDER",
            study.getJSONObject("window5").getString("status"))
    }
}
