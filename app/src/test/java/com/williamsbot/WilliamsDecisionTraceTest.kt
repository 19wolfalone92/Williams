package com.williamsbot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class WilliamsDecisionTraceTest {
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
    @Test fun missingEvidenceDoesNotInventMarketStructureOrGrantExchangePermission() {
        val trace=buildWilliamsDecisionTrace("BTCUSDT",williamsUnifiedProfile,emptyList(),100_000_000L,JSONObject(),JSONArray(),JSONObject())
        assertEquals("UNRESOLVED",trace.getString("wave_state"))
        assertEquals("BLOCKED",trace.getJSONObject("decision").getString("state"))
        assertEquals(0,trace.getJSONArray("divergences").length())
        val frames=trace.getJSONObject("market_phase").getJSONArray("frames")
        assertEquals(5,frames.length())
        val h4=(0 until frames.length()).map { frames.getJSONObject(it) }
            .single { it.getString("frame")=="4h" }
        assertEquals("UNAVAILABLE",h4.getString("state"))
        assertEquals(0,h4.getInt("hypothesis_count"))
        assertFalse(h4.getBoolean("wave_degree_confirmed"))
        assertFalse(h4.getBoolean("probability_calibrated"))
        assertFalse(h4.getBoolean("can_place_orders"))
    }
}
