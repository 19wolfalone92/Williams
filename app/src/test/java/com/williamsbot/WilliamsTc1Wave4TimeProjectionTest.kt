package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** TC1 PDF63-64 / fig.7-21: forecast from W2 base, NOT W1. */
class WilliamsTc1Wave4TimeProjectionTest {
    private val h=3_600_000L

    private fun scenario(w4:Long,asOf:Long=100*h):JSONObject {
        val extrema=listOf(10*h,20*h,30*h,40*h,w4)
        val prices=listOf(100.0,130.0,115.0,155.0,123.0)
        val anchors=JSONArray()
        for(i in extrema.indices)
            anchors.put(JSONObject()
                .put("extreme_at",extrema[i])
                .put("known_at",extrema[i]+3*h)
                .put("price",prices[i])
                .put("kind",if(i%2==0) "LOW" else "HIGH"))
        return JSONObject().put("frame","1h").put("label","W4")
            .put("status","PROVISIONAL").put("scale_bars",120)
            .put("observed_at",asOf).put("direction","UP")
            .put("anchors",anchors)
    }

    @Test fun windowIsExactly138To162PercentOfW1W3FromW2NotW1() {
        val result=williamsTc1Wave4TimeProjection(scenario(60*h),100*h)
        assertEquals("TIME_WINDOW_PIVOT_INSIDE_UNCERTIFIED",
            result.getString("status"))
        // W1=20h, W3=40h, W2=30h -> [30+27.6, 30+32.4]h.
        assertEquals(30*h+27*h+2_160_000L,
            result.getLong("projection_earliest_ms"))
        assertEquals(30*h+32*h+1_440_000L,
            result.getLong("projection_latest_ms"))
        assertTrue(result.getBoolean("inside_projection"))
        assertEquals("1.38_TO_1.62",result.getString("author_ratios"))
        assertFalse(result.getBoolean("can_place_orders"))
        assertFalse(result.getBoolean("wave_degree_certified"))
        assertTrue(result.getBoolean("hypothetical_only"))
        assertTrue(result.getBoolean("not_book_confirmed_trade_entry"))
        val outside=williamsTc1Wave4TimeProjection(scenario(52*h),100*h)
        assertEquals("TIME_WINDOW_PIVOT_OUTSIDE_UNCERTIFIED",
            outside.getString("status"))
        assertFalse(outside.getBoolean("inside_projection"))
    }

    @Test fun wrongScaleFuturePivotAndFabricatedNonHourlyOrStaleWaveAreRejected() {
        val base=scenario(60*h)
        for(scale in listOf(20,99,141,300)) {
            val other=JSONObject(base.toString()).put("scale_bars",scale)
            assertEquals("UNVERIFIED",
                williamsTc1Wave4TimeProjection(other,100*h).getString("status"))
        }
        val newer=scenario(100*h).put("observed_at",103*h)
        assertEquals("UNVERIFIED",
            williamsTc1Wave4TimeProjection(newer,100*h).getString("status"))
        val notH1=JSONObject(base.toString()).put("frame","4h")
        assertEquals("UNVERIFIED",
            williamsTc1Wave4TimeProjection(notH1,100*h).getString("status"))
        val past=JSONObject(base.toString()).put("observed_at",101*h)
        assertEquals("UNVERIFIED",
            williamsTc1Wave4TimeProjection(past,100*h).getString("status"))
    }
}
