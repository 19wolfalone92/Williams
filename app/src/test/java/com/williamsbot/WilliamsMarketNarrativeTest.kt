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
    @Test fun seniorComplexCorrectionIsExplainedButCannotActAsTc2ExecutionAuthority() {
        val raw=JSONObject().put("as_of",now)
            .put("frame_errors",JSONObject())
            .put("complex_wave_patterns",JSONArray().put(JSONObject()
                .put("frame","4h")
                .put("pattern","CONTRACTING_TRIANGLE_ABCDE_CANDIDATE")
                .put("known_at",now-4*williamsWaveFrames.getValue("4h"))
                .put("start_at",now-16*williamsWaveFrames.getValue("4h"))
                .put("end_at",now-6*williamsWaveFrames.getValue("4h"))
                .put("internal_proof",JSONObject().put("status","PRICE_SUBDIVISIONS_UNVERIFIED"))
                .put("recursive_proof",JSONObject().put("status","UNRESOLVED"))))
        val phase=williamsMarketPhaseContext(raw)
        val contexts=phase.getJSONArray("complex_correction_hypotheses")
        assertEquals(1,contexts.length())
        val hypothesis=contexts.getJSONObject(0)
        assertEquals("4h",hypothesis.getString("frame"))
        assertTrue(hypothesis.getBoolean("fresh_at_frame"))
        assertTrue(hypothesis.getBoolean("current_price_range_unverified"))
        assertFalse(hypothesis.getBoolean("can_veto_entries"))
        assertFalse(phase.getBoolean("complex_hypotheses_can_veto_trading"))
        // Supply observed H4 bar state and show Russian market context.
        val narrated=williamsMarketNarrative(JSONObject()
            .put("as_of",now)
            .put("frames",JSONArray().put(frame("4h","UP")))
            .put("complex_correction_hypotheses",contexts))
        val h4=narrated.getJSONArray("frames").getJSONObject(2)
        assertEquals(1,h4.getInt("complex_correction_alternatives"))
        assertTrue(h4.getString("complex_correction_explanation_ru").contains("не доказаны"))
        assertFalse(narrated.getBoolean("complex_research_can_place_or_cancel_orders"))
        assertFalse(narrated.getBoolean("can_place_orders"))
    }

    @Test fun staleOrUnavailableRowsCannotBecomeFreshObservations() {
        val stale=frame("1h","UP",now-3_600_000L)
        val unavailable=frame("15m","DOWN").put("state","UNAVAILABLE")
        val narrative=williamsMarketNarrative(JSONObject().put("as_of",now)
            .put("frames",JSONArray().put(stale).put(unavailable)))
        assertEquals(0,narrative.getInt("observed_frame_count"))
        assertFalse(narrative.getBoolean("junior_bias_opposes_h1"))
    }
}
