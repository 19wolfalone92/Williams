package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Read-only H1/M15 vs parent structure: never project a junior change into
 * a confirmed parent reversal; no synthetic book-trading approvals. */
class WilliamsMarketNarrativeTest {
    private val now=1_792_578_000_000L
    private fun realClosedHistory(sign:Int=1)=williamsObservedMarketFrames.associateWith { frame ->
        val ms=williamsWaveFrames.getValue(frame);val end=now/ms*ms
        (0 until 80).map { i -> WilliamsWaveBar(
            end-(80-i)*ms,100.0+sign*i*.1,101.0+sign*i*.1,99.0+sign*i*.1,
            100.5+sign*i*.1,1000.0+i
        ) }
    }
    @Test fun realFiveFrameClosedHistoryBecomesNarrativeNotFiveNotAnalysedRows() {
        val observed=buildWilliamsObservedMarketContext(realClosedHistory(),now)
        val phase=williamsMarketPhaseContext(observed)
        val narration=phase.getJSONObject("market_narrative")
        assertEquals(now,narration.getLong("as_of"))
        assertEquals(5,narration.getInt("observed_frame_count"))
        assertEquals(5,narration.getInt("expected_frame_count"))
        val frames=narration.getJSONArray("frames")
        assertEquals(listOf("1d","6h","4h","1h","15m"),
            (0 until frames.length()).map { frames.getJSONObject(it).getString("frame") })
        for(i in 0 until frames.length()) {
            val frame=frames.getJSONObject(i)
            assertTrue(frame.getBoolean("chart_observed"))
            assertFalse(frame.getBoolean("wave_degree_confirmed"))
            assertFalse(frame.getBoolean("can_close"))
            assertFalse(frame.getBoolean("can_place_orders"))
            assertTrue(frame.getString("explanation_ru").contains("контекст"))
        }
        assertFalse(narration.getBoolean("can_close"))
        assertFalse(narration.getBoolean("can_reverse"))
    }

    private fun frame(frame:String,bias:String,asOf:Long=now):JSONObject =
        JSONObject().put("frame",frame).put("state","UNRESOLVED")
            .put("hypotheses",JSONArray()).put("market_reading",JSONObject()
                .put("state","OBSERVED").put("as_of_close_ms",asOf)
                .put("observed_bias",bias).put("alligator",JSONObject().put("state","EATING"))
                .put("ao",1.5).put("ac",0.3)
                .put("ao_direction","DOWN").put("ac_direction","DOWN"))
    @Test fun m15OppositionIsNotAConfirmedH1OrH4Reversal() {
        val f=JSONArray().put(frame("1d","UP")).put(frame("6h","UP"))
            .put(frame("4h","UP")).put(frame("1h","UP")).put(frame("15m","DOWN"))
        val narrative=williamsMarketNarrative(JSONObject().put("as_of",now).put("frames",f))
        assertTrue(narrative.getBoolean("junior_bias_opposes_h1"))
        assertTrue(narrative.getString("cross_timeframe_caution_ru").contains("НЕ подтверждён"))
        assertFalse(narrative.getBoolean("can_place_orders"))
        assertFalse(narrative.getBoolean("wave_genealogy_confirmed"))
        assertEquals(5,narrative.getInt("observed_frame_count"))
    }

    @Test fun missingOrFutureChartDataAreUnknownRatherThanFabricatedWaveLabels() {
        val rows=JSONArray().put(frame("1h","UP",now+3_600_000L))
            .put(frame("15m","DOWN"))
        val narrative=williamsMarketNarrative(JSONObject().put("as_of",now).put("frames",rows))
        assertEquals(1,narrative.getInt("observed_frame_count"))
        val frames=narrative.getJSONArray("frames")
        assertFalse(frames.getJSONObject(3).getBoolean("chart_observed"))
        assertFalse(narrative.getBoolean("junior_bias_opposes_h1"))
        assertEquals("UNKNOWN",frames.getJSONObject(3).getString("observed_bias"))
        assertTrue(frames.getJSONObject(0).getString("explanation_ru").contains("неизвестны"))
    }
    @Test fun noAnalysisMeansNothingIsCertified() {
        val narrative=williamsMarketNarrative(JSONObject())
        assertEquals(0,narrative.getInt("observed_frame_count"))
        assertFalse(narrative.getBoolean("can_close"))
        assertFalse(narrative.getBoolean("confidence_calibrated"))
        assertTrue(narrative.getJSONArray("frames").getJSONObject(0)
            .getString("explanation_ru").contains("не запускался"))
    }
}
