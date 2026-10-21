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

    @Test fun unknownFrameNeverInventsAnIndicatorOrWaveReading() {
        val result=williamsMarketReading(emptyList(),JSONObject(),"2d")
        assertEquals("UNSUPPORTED_FRAME",result.getString("state"))
        assertFalse(result.getBoolean("can_place_orders"))
    }
}
