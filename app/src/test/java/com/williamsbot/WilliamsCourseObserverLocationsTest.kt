package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsCourseObserverLocationsTest {
    private val h=3_600_000L

    private fun paper(profile:String=williamsCourseProfile)=JSONObject()
        .put("symbol","ETHUSDT").put("profile",profile)
        .put("status","PAPER_OBSERVING")
        .put("last_processed_open_ms",100*h)
        .put("last_closed_h1_at_ms",101*h)
        .put("source_signal_intervals_unobserved",0)
        .put("can_place_orders",false).put("events",JSONArray())
        .put("paper_position",JSONObject.NULL)

    private fun stageScenario(asOf:Long, close:Double):JSONObject {
        val anchors=JSONArray()
        for((i,price) in listOf(100.0,130.0,115.0).withIndex()) {
            anchors.put(JSONObject()
                .put("kind",if(i==1) "HIGH" else "LOW")
                .put("price",price)
                .put("extreme_at",(10+i*10)*h)
                .put("known_at",(14+i*10)*h))
        }
        return JSONObject().put("frame","1h")
            .put("status","PROVISIONAL").put("direction","UP")
            .put("label","W2").put("scenario_id","1h:UP:stage-W3")
            .put("anchors",anchors).put("observed_at",asOf)
            .put("observed_close_price",close)
    }

    private fun hierarchy(now:Long,close:Double):JSONObject=JSONObject()
        .put("as_of",now).put("frame_errors",JSONObject())
        .put("scenarios",JSONArray().put(stageScenario(now,close)))

    @Test fun verifiedBookPriceZoneIsRecordedAsWatchNotAsEntryOrExchangeFill() {
        val close=101*h
        val observed=close+12_345L
        val got=williamsCourseObserverLocations(paper(),"ETHUSDT",
            hierarchy(observed,115.0),close)
        assertEquals("CAUSAL_BOOK_ZONES_OBSERVED_UNCERTIFIED",
            got.getString("course_location_status"))
        val rows=got.getJSONArray("events")
        assertEquals(1,rows.length())
        val row=rows.getJSONObject(0)
        assertEquals("COURSE_H1_STAGE_PRICE_ZONE_WATCH",row.getString("event"))
        assertEquals("W3",row.getString("stage"))
        assertEquals("LONG",row.getString("direction"))
        assertEquals(115.0,row.getDouble("source_h1_close_price"),0.0)
        assertFalse(row.getBoolean("book_entry_confirmed"))
        assertFalse(row.getBoolean("book_exit_confirmed"))
        assertFalse(row.getBoolean("wave_degree_certified"))
        assertFalse(row.getBoolean("can_place_orders"))
        assertTrue(row.getBoolean("hypothetical_only"))
        assertEquals(6,row.getInt("illustrative_target_units"))
        val repeated=williamsCourseObserverLocations(got,"ETHUSDT",
            hierarchy(observed,115.0),close)
        assertEquals("ALREADY_OBSERVED",repeated.getString("course_location_status"))
        assertEquals(1,repeated.getJSONArray("events").length())
        assertNull(repeated.optJSONObject("paper_position"))
    }

    @Test fun failedOrGappedPaperReplayNeverCreatesCourseWaveBookEvents() {
        val close=101*h
        val hierarchy=hierarchy(close+1,115.0)
        for(status in listOf("UNRECOVERABLE_REPLAY_GAP",
            "CLOSED_H1_HISTORY_UNVERIFIED","H1_REPLAY_GAP")) {
            val bad=paper().put("status",status)
            val blocked=williamsCourseObserverLocations(bad,"ETHUSDT",hierarchy,close)
            assertEquals("SOURCE_PAPER_H1_REPLAY_UNVERIFIED",
                blocked.getString("course_location_status"))
            assertEquals(0,blocked.getJSONArray("events").length())
        }
        for(gap in listOf(1,2)) {
            val bad=paper().put("source_signal_intervals_unobserved",gap)
            val blocked=williamsCourseObserverLocations(bad,"ETHUSDT",hierarchy,close)
            assertEquals("SOURCE_PAPER_H1_REPLAY_UNVERIFIED",
                blocked.getString("course_location_status"))
            assertEquals(0,blocked.getJSONArray("events").length())
        }
        val stale=paper().put("last_closed_h1_at_ms",100*h)
        assertEquals(0,williamsCourseObserverLocations(stale,"ETHUSDT",
            hierarchy,close).getJSONArray("events").length())
    }

    @Test fun outOfZoneOrUnobservedH1NeverBecomesAFalseBookEntry() {
        val close=101*h
        // 160 is NOT an out-of-zone counterexample: it is a legitimate
        // Course W3_EXTENSION watch (160-115 > 1.1*(130-100)).
        // 125 is outside BOTH W3 retracement and W3 extension ranges.
        val out=williamsCourseObserverLocations(paper(),"ETHUSDT",
            hierarchy(close+1,125.0),close)
        assertEquals(0,out.getJSONArray("events").length())
        assertEquals("NO_SOURCE_CONFIRMED_PRICE_ZONE",out.getString("course_location_status"))
        val stale=williamsCourseObserverLocations(paper(),"ETHUSDT",
            hierarchy(close+2*h,115.0),close)
        assertEquals("H1_WAVE_SOURCE_UNVERIFIED",stale.getString("course_location_status"))
        assertEquals(0,stale.getJSONArray("events").length())
        val other=paper(williamsNtdProfile)
        val foreign=williamsCourseObserverLocations(other,"ETHUSDT",
            hierarchy(close+1,115.0),close)
        assertEquals("INVALID_SOURCE_OWNER",foreign.getString("course_location_status"))
        assertEquals(0,foreign.getJSONArray("events").length())
        assertFalse(foreign.getBoolean("can_place_orders"))
        val missing=hierarchy(close+1,115.0).put("frame_errors",
            JSONObject().put("1h","HISTORY_GAP"))
        val invalid=williamsCourseObserverLocations(paper(),"ETHUSDT",missing,close)
        assertEquals("H1_WAVE_SOURCE_UNVERIFIED",invalid.getString("course_location_status"))
        assertEquals(0,invalid.getJSONArray("events").length())
    }
}
