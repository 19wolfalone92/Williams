package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsTc1ResearchDiaryTest {
    private val hour=3_600_000L

    private fun hierarchy(now:Long):JSONObject {
        val anchors=JSONArray()
        val prices=listOf(100.0,120.0,110.0,140.0,125.0,150.0)
        for(i in 0..5) anchors.put(JSONObject()
            .put("kind",if(i%2==0) "LOW" else "HIGH")
            .put("extreme_at",(20+i*10)*hour)
            .put("known_at",(23+i*10)*hour)
            .put("price",prices[i]))
        val scenario=JSONObject().put("frame","1h").put("status","PROVISIONAL")
            .put("label","W5").put("direction","UP")
            .put("scenario_id","1h:UP:synthetic-W5")
            .put("anchors",anchors).put("scale_bars",120)
            .put("observed_at",now).put("target_zone",true)
            .put("terminal_fractal",true).put("squat",true)
            .put("momentum_shift",true)
        return JSONObject().put("as_of",now)
            .put("frame_errors",JSONObject())
            .put("scenarios",JSONArray().put(scenario))
    }

    @Test fun firstEditionKeepsItsOwnNoFillWaveEvidenceAndNeverCallsItABookTrade() {
        val now=100*hour
        val initial=williamsTc1ResearchAdvance(null,"BTCUSDT",hierarchy(now),now)
        assertEquals("TC1_WAVE_EVIDENCE_ONLY_NOT_A_PAPER_EXECUTOR",initial.getString("status"))
        assertFalse(initial.getBoolean("can_place_orders"))
        assertFalse(initial.getBoolean("can_close"))
        assertFalse(initial.getBoolean("full_book_entry_exit_reconstructed"))
        val events=initial.getJSONArray("events")
        assertEquals(1,events.length())
        val row=events.getJSONObject(0)
        assertEquals(WILLIAMS_TC1_OBSERVER,row.getString("profile"))
        assertEquals("TC1_H1_W5_TERMINAL_WATCH",row.getString("event"))
        assertEquals(150.0,row.getDouble("source_pivot_price"),1e-9)
        assertEquals("MFI_DIVERGENCE_UNVERIFIED",row.getString("five_bullets_status"))
        assertFalse(row.getBoolean("mfi_canonical_verified"))
        assertTrue(row.isNull("actual_book_entry_price"))
        assertTrue(row.isNull("actual_book_exit_price"))
        assertEquals(1,williamsTc1ResearchAdvance(initial,"BTCUSDT",
            hierarchy(now),now).getJSONArray("events").length())
        assertEquals(1,williamsTc1ResearchAdvance(initial,"BTCUSDT",
            hierarchy(now+hour),now+hour).getJSONArray("events").length())
    }

    @Test fun missingWaveSnapshotAndWrongSourceCannotCreateTradingEvidence() {
        val now=100*hour
        val unavailable=williamsTc1ResearchAdvance(null,"ADAUSDT",hierarchy(now),now+1)
        assertEquals("TC1_H1_WAVE_HISTORY_UNVERIFIED",unavailable.getString("status"))
        assertTrue(unavailable.optJSONArray("events")==null)
        val initial=williamsTc1ResearchAdvance(null,"ADAUSDT",hierarchy(now),now)
        val moved=williamsTc1ResearchAdvance(initial,"SOLUSDT",hierarchy(now+hour),now+hour)
        assertEquals("SOURCE_IDENTITY_MISMATCH",moved.getString("status"))
        assertEquals("ADAUSDT",moved.getString("symbol"))
        assertFalse(moved.getBoolean("can_place_orders"))
    }
}
