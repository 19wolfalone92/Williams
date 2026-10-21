package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real timeframe closed-bar geometry; this cannot authorize exchange mutations. */
class WilliamsMultiTimeframeReadingTest {
    @Test fun eachFrameComputesItsOwnCausalCandleTimeAndRemainsReadOnly() {
        for (frame in listOf("1d","6h","4h","1h","15m")) {
            val ms=williamsWaveFrames.getValue(frame)
            val bars=(0 until 46).map { i ->
                val low=100.0+i*.1
                WilliamsWaveBar((i+1)*ms,low+1,low+2,low,low+1.5,100.0)
            }
            val snapshot=JSONObject().put("frames", JSONArray().put(
                JSONObject().put("frame",frame).put("state","UNRESOLVED")
                    .put("hypotheses",JSONArray())))
            val passport=williamsMarketReading(bars,snapshot,frame)
            assertEquals(frame,passport.getString("frame"))
            assertEquals(passport.toString(),"OBSERVED",passport.getString("state"))
            assertEquals(bars.last().t+ms,passport.getLong("as_of_close_ms"))
            assertEquals("UNRESOLVED",passport.getString("wave_phase"))
            assertFalse(passport.getBoolean("can_place_orders"))
            assertFalse(passport.getBoolean("can_close"))
            val gapped=bars.toMutableList().also { rows ->
                rows[21]=rows[21].copy(t=rows[21].t+ms/2)
            }
            assertEquals("HISTORY_UNVERIFIED",
                williamsMarketReading(gapped,snapshot,frame).getString("state"))
        }
    }

    @Test fun independentH4WaveAlternativesUseRealClosedBarsWithoutCertifyingDirection() {
        val contract=JSONObject(requireNotNull(javaClass.getResource("/williams_wave_contract.json")).readText())
        val first=contract.getJSONArray("cases").getJSONObject(0).getJSONArray("bars")
        val candles=(0 until first.length()).map { i ->
            val row=first.getJSONArray(i)
            WilliamsWaveBar(row.getLong(0)*4,row.getDouble(1),row.getDouble(2),
                row.getDouble(3),row.getDouble(4),row.getDouble(5))
        }
        val reading=williamsMarketReading(candles,JSONObject(),"4h")
        assertEquals("OBSERVED",reading.getString("state"))
        assertEquals("4h",reading.getString("frame"))
        val hypotheses=reading.getJSONArray("independent_wave_hypotheses")
        assertTrue(hypotheses.length()>0)
        assertEquals(hypotheses.length(),reading.getInt("independent_wave_hypothesis_count"))
        val knownLimit=candles.last().t+williamsWaveFrames.getValue("4h")
        for(i in 0 until hypotheses.length()) {
            val h=hypotheses.getJSONObject(i)
            assertTrue(h.getLong("last_pivot_known_at")<=knownLimit)
            assertFalse(h.getBoolean("wave_degree_confirmed"))
            assertFalse(h.getBoolean("can_place_orders"))
        }
        assertEquals("UNRESOLVED",reading.getString("wave_phase"))
    }

    @Test fun unknownFrameNeverInventsAnIndicatorOrWaveReading() {
        val result=williamsMarketReading(emptyList(),JSONObject(),"2d")
        assertEquals("UNSUPPORTED_FRAME",result.getString("state"))
        assertFalse(result.getBoolean("can_place_orders"))
    }
}
