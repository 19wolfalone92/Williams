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

    @Test fun firstEditionW4ZeroCrossNeedsRealCrossAnd100To140BarsAndCausalPivot() {
        val now=100*hour
        fun wave(zero:String,scale:Int):JSONObject {
            val h=hierarchy(now)
            val scenario=h.getJSONArray("scenarios").getJSONObject(0)
            val old=scenario.getJSONArray("anchors")
            scenario.put("anchors",JSONArray((0..4).map { old.getJSONObject(it) }))
                .put("label","W4").put("w4_zero",zero).put("scale_bars",scale)
            return h
        }
        val correct=williamsTc1ResearchAdvance(null,"BTCUSDT",
            wave("MINIMUM_ONLY",120),now)
        val entries=correct.getJSONArray("events")
        assertEquals(1,entries.length())
        val item=entries.getJSONObject(0)
        assertEquals("TC1_H1_W4_AO_ZERO_CROSS_WATCH",item.getString("event"))
        assertEquals("TC1_PDF61_65",item.getString("source_book"))
        assertEquals("MINIMUM_ONLY",item.getString("source_ao_zero_status"))
        assertEquals(120,item.getInt("source_degree_scale_bars"))
        assertFalse(item.getBoolean("wave_degree_certified"))
        assertFalse(item.getBoolean("can_place_orders"))
        assertTrue(item.isNull("actual_book_entry_price"))
        assertTrue(item.isNull("actual_book_exit_price"))
        val next=wave("MINIMUM_ONLY",120).put("as_of",now+hour)
        next.getJSONArray("scenarios").getJSONObject(0)
            .put("observed_at",now+hour)
        val duplicate=williamsTc1ResearchAdvance(correct,"BTCUSDT",
            next,now+hour)
        assertEquals(1,duplicate.getJSONArray("events").length())
        for(s in listOf("NEAR_ZERO_UNCONFIRMED","NO_ZERO_CROSS",
            "MISSING_W3_MOMENTUM","WRONG_SCALE"))
            assertEquals(0,williamsTc1ResearchAdvance(null,"BTCUSDT",
                wave(s,120),now).getJSONArray("events").length())
        for(scale in listOf(99,141))
            assertEquals(0,williamsTc1ResearchAdvance(null,"BTCUSDT",
                wave("MINIMUM_ONLY",scale),now).getJSONArray("events").length())
        val tooEarly=wave("MINIMUM_ONLY",120)
        tooEarly.getJSONArray("scenarios").getJSONObject(0)
            .getJSONArray("anchors").getJSONObject(4)
            .put("known_at",now+hour)
        assertEquals(0,williamsTc1ResearchAdvance(null,"BTCUSDT",
            tooEarly,now).getJSONArray("events").length())
    }

    @Test fun bookW5WatchCannotBeInventedByMalformedFutureOrWrongDirectionPivots() {
        val now=100*hour
        val good=hierarchy(now)
        assertEquals(1,williamsTc1ResearchAdvance(null,"SOLUSDT",good,now)
            .getJSONArray("events").length())
        val altered=JSONObject(good.toString())
        altered.getJSONArray("scenarios").getJSONObject(0)
            .getJSONArray("anchors").getJSONObject(3)
            .put("known_at",now+hour)
        assertEquals(0,williamsTc1ResearchAdvance(null,"SOLUSDT",altered,now)
            .getJSONArray("events").length())
        val malformed=JSONObject(good.toString())
        malformed.getJSONArray("scenarios").getJSONObject(0)
            .getJSONArray("anchors").getJSONObject(4)
            .put("kind","UNCONFIRMED")
        assertEquals(0,williamsTc1ResearchAdvance(null,"SOLUSDT",malformed,now)
            .getJSONArray("events").length())
        val wrongDirection=JSONObject(good.toString())
        wrongDirection.getJSONArray("scenarios").getJSONObject(0)
            .put("direction","DOWN")
        assertEquals(0,williamsTc1ResearchAdvance(null,"SOLUSDT",wrongDirection,now)
            .getJSONArray("events").length())
        val oldSnapshot=JSONObject(good.toString())
        oldSnapshot.getJSONArray("scenarios").getJSONObject(0)
            .put("observed_at",now-hour)
        assertEquals(0,williamsTc1ResearchAdvance(null,"SOLUSDT",oldSnapshot,now)
            .getJSONArray("events").length())
        val nonmonotonic=JSONObject(good.toString())
        nonmonotonic.getJSONArray("scenarios").getJSONObject(0)
            .getJSONArray("anchors").getJSONObject(4)
            .put("extreme_at",15*hour)
        assertEquals(0,williamsTc1ResearchAdvance(null,"SOLUSDT",nonmonotonic,now)
            .getJSONArray("events").length())
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
