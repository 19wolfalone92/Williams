package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** The author-rule adviser may log a hypothetical close; it cannot trade. */
class WilliamsNtdObserverBookEvidenceTest {
    private val hour=3_600_000L
    private fun bars(n:Int)=(1..n).map { i ->
        WilliamsWaveBar(i*hour,100.0,105.0,95.0,100.0,100.0)
    }
    private fun paper(side:String,openedAt:Long,lastAt:Long)=JSONObject()
        .put("profile",williamsNtdProfile).put("symbol","BTCUSDT")
        .put("can_place_orders",false)
        .put("events",JSONArray())
        .put("last_processed_open_ms",lastAt)
        .put("paper_position",JSONObject()
            .put("signal_id","NTD:BTCUSDT:test")
            .put("direction",side)
            .put("entry_bar_open_ms",openedAt)
            .put("entry_price",if(side=="LONG") 110.0 else 90.0)
            .put("initial_stop",if(side=="LONG") 90.0 else 110.0))

    @Test fun textbookTeethCloseIsRecordedForBothSidesButNeverFilled() {
        for(side in listOf("LONG","SHORT")) {
            val rows=bars(48)
            val state=paper(side,46*hour,48*hour)
            val teeth=List(rows.size){ if(side=="LONG") 101.0 else 99.0 }
            val lips=List(rows.size){ if(side=="LONG") 102.0 else 98.0 }
            val ao=List(rows.size){0.0}
            val ac=List(rows.size){0.0}
            val result=williamsNtdObserverBookEvidence(state,"BTCUSDT",rows,
                teeth,lips,ao,ac,0.1,49*hour)
            assertEquals("SOURCE_BOOK_EXIT_CONDITION_OBSERVED",
                result.getString("ntd_book_evidence_status"))
            val a=result.getJSONArray("events")
            assertEquals(1,a.length())
            val event=a.getJSONObject(0)
            assertEquals("NTD_BOOK_CLOSE_EXIT_SIGNAL",event.getString("event"))
            assertEquals("TEETH_CLOSE_EXIT",event.getString("source_book_exit_rule"))
            assertEquals(100.0,event.getDouble("source_h1_close_price"),0.0)
            assertTrue(event.getBoolean("not_exchange_fill"))
            assertFalse(event.getBoolean("can_place_orders"))
            assertNotNull(result.optJSONObject("paper_position"))
            // A re-evaluation on the same H1 close must not duplicate records.
            val same=williamsNtdObserverBookEvidence(result,"BTCUSDT",rows,
                teeth,lips,ao,ac,0.1,49*hour)
            assertEquals(1,same.getJSONArray("events").length())
            assertEquals("ALREADY_OBSERVED",same.getString("ntd_book_evidence_status"))
        }
    }

    @Test fun noPostEntryBarOrUnverifiedCandleOrForeignSourceCanCreateExit() {
        val rows=bars(48)
        val teeth=List(rows.size){101.0}
        val lips=List(rows.size){102.0}
        val ao=List(rows.size){0.0}
        val ac=List(rows.size){0.0}
        val asOf=49*hour
        val entryAtLast=paper("LONG",48*hour,48*hour)
        val noPost=williamsNtdObserverBookEvidence(entryAtLast,"BTCUSDT",rows,
            teeth,lips,ao,ac,0.1,asOf)
        assertEquals("NO_POST_ENTRY_CLOSED_BAR",noPost.getString("ntd_book_evidence_status"))
        val noFuture=williamsNtdObserverBookEvidence(paper("LONG",46*hour,48*hour),
            "BTCUSDT",rows,teeth,lips,ao,ac,0.1,48*hour)
        assertEquals("SOURCE_H1_NOT_CURRENT",noFuture.getString("ntd_book_evidence_status"))
        val foreign=paper("LONG",46*hour,48*hour)
        foreign.put("profile",williamsCourseProfile)
        val wrong=williamsNtdObserverBookEvidence(foreign,"BTCUSDT",rows,
            teeth,lips,ao,ac,0.1,asOf)
        assertEquals("OBSERVER_SOURCE_AUTHORITY_INVALID",wrong.getString("ntd_book_evidence_status"))
        assertEquals(0,wrong.getJSONArray("events").length())
        val missing=williamsNtdObserverBookEvidence(paper("LONG",46*hour,48*hour),
            "BTCUSDT",rows,teeth.dropLast(1),lips,ao,ac,0.1,asOf)
        assertEquals("INDICATORS_OR_PRICE_HISTORY_UNVERIFIED",
            missing.getString("ntd_book_evidence_status"))
        assertEquals(0,missing.getJSONArray("events").length())
    }
}
