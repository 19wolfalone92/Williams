package com.williamsbot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class WilliamsDecisionTraceTest {

    @Test fun diagnosticArchiveDoesNotClaimBearishWaveAoDivergenceAcrossZero() {
        // Exact directional shape in 4.64.3 BNBUSDT audit:
        // higher price high, AO first positive and second negative.
        assertFalse(williamsCausalLocalAoDivergence(
            true,753.29,753.37,1.3316764705882633,-4.787735294117738))
        assertTrue(williamsCausalLocalAoDivergence(true,100.0,105.0,8.0,3.0))
        assertFalse(williamsCausalLocalAoDivergence(true,100.0,105.0,3.0,8.0))
        assertFalse(williamsCausalLocalAoDivergence(true,100.0,105.0,0.0,-2.0))
        assertTrue(williamsCausalLocalAoDivergence(false,100.0,95.0,-8.0,-3.0))
        assertFalse(williamsCausalLocalAoDivergence(false,100.0,95.0,-3.0,2.0))
        assertFalse(williamsCausalLocalAoDivergence(false,100.0,101.0,-3.0,-2.0))
        assertFalse(williamsCausalLocalAoDivergence(true,100.0,105.0,Double.NaN,1.0))
        assertFalse(williamsCausalLocalAoDivergence(false,100.0,95.0,Double.POSITIVE_INFINITY,-1.0))
    }
    @Test fun pythonAndAndroidUseTheSameClosedCandleEvidenceAndDoNotConsumeFutureBars() {
        val cases=JSONObject(requireNotNull(javaClass.getResource("/williams_decision_trace_contract.json")).readText()).getJSONArray("cases")
        for(i in 0 until cases.length()) {
            val c=cases.getJSONObject(i);val b=c.getJSONArray("bars")
            val bars=(0 until b.length()).map { b.getJSONArray(it) }.map { WilliamsWaveBar(it.getLong(0),it.getDouble(1),it.getDouble(2),it.getDouble(3),it.getDouble(4),it.getDouble(5)) }
            val raw=c.getJSONArray("signals");val signals=JSONArray((0 until raw.length()).map { raw.getJSONObject(it) }.map {
                JSONObject().put("signal_id",it.getString("profile")+it.getString("family")+it.getString("side"))
                    .put("direction",it.getString("side")).put("family",it.getString("family")).put("role",it.getString("role"))
                    .put("signal_bar_time_ms",it.getLong("at")).put("strategy_profile",it.getString("profile"))
                    .put("trigger",it.getDouble("trigger")).put("protective_stop",it.getDouble("stop")) })
            fun trace(input:List<WilliamsWaveBar>)=buildWilliamsDecisionTrace("BTCUSDT",williamsUnifiedProfile,input,c.getLong("now"),JSONObject(),signals,JSONObject())
            val trace=trace(bars)
            assertEquals(c.getString("expected_state"),trace.getJSONObject("decision").getString("state"))
            assertFalse(trace.getJSONObject("decision").getBoolean("can_place_orders"))
            assertEquals(trace.toString(),trace(bars+WilliamsWaveBar(c.getLong("now"),1.0,1e20,.01,1.0,1e20)).toString())
            assertEquals(3,trace.getJSONArray("books").length())
            val d=trace.getJSONArray("divergences")
            for(j in 0 until d.length()) assertTrue(d.getJSONObject(j).getJSONObject("second").getLong("confirmed_at_ms")<=trace.getLong("as_of_close_ms"))
        }
    }
    @Test fun sleepingFractalGetsCausalContextWithoutInventedTradeProhibition() {
        val h=3_600_000L;val now=400*h
        val bars=(0 until 80).map { i ->
            WilliamsWaveBar(now-(80-i)*h,100.0,100.5,99.5,100.0,1000.0)
        }
        val signal=JSONObject().put("signal_id","test_sleeping_wm3")
            .put("role","ENTRY").put("direction","SHORT")
            .put("family","FRACTAL").put("strategy_profile",williamsTc2Profile)
            .put("signal_bar_time_ms",bars[bars.size-3].t)
        fun trace(series:List<WilliamsWaveBar>)=buildWilliamsDecisionTrace("BTCUSDT",williamsTc2Profile,
            series,now,JSONObject(),JSONArray().put(signal),JSONObject().put("as_of",now))
        val report=trace(bars)
        val context=report.getJSONArray("entry_market_contexts").getJSONObject(0)
        assertEquals("OBSERVED",context.getString("h1_reading_state"))
        assertEquals("SLEEPING",context.getString("alligator_phase"))
        assertTrue(context.getJSONArray("flags").toString().contains("SLEEPING_RANGE_POTENTIAL_BREAKOUT"))
        assertTrue(context.getJSONArray("flags").toString().contains("WAVE_STRUCTURE_UNRESOLVED"))
        assertFalse(context.getBoolean("context_is_trade_veto"))
        assertFalse(context.getBoolean("can_place_orders"))
        assertFalse(context.getBoolean("probability_calibrated"))
        assertEquals("UNRESOLVED",report.getString("wave_state"))
        assertEquals(report.toString(),trace(bars+WilliamsWaveBar(now,1.0,1e9,.1,1.0,1e9)).toString())
    }

    @Test fun missingEvidenceDoesNotInventMarketStructureOrGrantExchangePermission() {
        val trace=buildWilliamsDecisionTrace("BTCUSDT",williamsUnifiedProfile,emptyList(),100_000_000L,JSONObject(),JSONArray(),JSONObject())
        assertEquals("UNRESOLVED",trace.getString("wave_state"))
        assertEquals("BLOCKED",trace.getJSONObject("decision").getString("state"))
        assertEquals(0,trace.getJSONArray("divergences").length())
        val frames=trace.getJSONObject("market_phase").getJSONArray("frames")
        assertEquals(5,frames.length())
        val h4=(0 until frames.length()).map { frames.getJSONObject(it) }
            .single { it.getString("frame")=="4h" }
        assertEquals("NOT_ANALYSED",h4.getString("state"))
        assertEquals("WAVE_ANALYSIS_NOT_RUN",h4.getString("history_error"))
        assertEquals("WAVE_ANALYSIS_NOT_RUN",h4.getString("reason"))
        assertEquals(0,h4.getInt("hypothesis_count"))
        assertFalse(h4.getBoolean("wave_degree_confirmed"))
        assertFalse(h4.getBoolean("probability_calibrated"))
        assertFalse(h4.getBoolean("can_place_orders"))
    }
}
