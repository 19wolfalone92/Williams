package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsBookCausalUnderstandingTest {
    private val now = 1_800_000_000_000L
    private val close = now - 1_000L
    private val event = close - WILLIAMS_H1_MS
    private fun signal(type:String="SUPER_AO",profile:String=williamsTc2Profile,
                       signalAt:Long=event):JSONObject =
        JSONObject().put("signal_id","BTCUSDT:1h:$type:$signalAt")
            .put("type",type).put("strategy_profile",profile)
            .put("signal_bar_time_ms",signalAt)
            .put("trigger_price",101.0).put("protective_price",95.0)
            .put("teeth_at_detection",99.0)
    private fun source(profile:String,role:String):JSONObject =
        JSONObject().put("profile",profile).put("execution_role",role)
            .put("as_of_close_ms",close).put("state","NO_VALID_FORMATION")
    private fun candidate(s:JSONObject,details:JSONObject?=null):JSONObject =
        JSONObject().put("symbol","BTCUSDT").put("closed_h1_as_of_ms",close)
            .put("campaign_signals",JSONArray().put(s))
            .put("decision_trace",JSONObject().put("signals",
                JSONArray().put(details ?: JSONObject()
                    .put("signal_id",s.optString("signal_id"))
                    .put("setup_context",JSONObject()
                        .put("third_ao_bar_at_ms",s.optLong("signal_bar_time_ms"))
                        .put("initial_stop_rule","TC2_SUPER_AO_SIGNAL_BAR")))))
            .put("edition_observations",JSONArray()
                .put(source(williamsTc2Profile,"LIVE"))
                .put(source(williamsNtdProfile,"OBSERVE"))
                .put(source(williamsCourseProfile,"OBSERVE")))
    private fun scanner(c:JSONObject):JSONObject=JSONObject()
        .put("last_scan_at",now-10_000L)
        .put("scanner_state","READY_BLOCKED")
        .put("scanning",false).put("symbols_scanned",APPROVED_FUTURES_SYMBOLS.size)
        .put("candidates",JSONArray().put(c))

    @Test fun acceptedThirdAoFormationIsNotExchangeOrderOrMacroReversal() {
        val exit=JSONObject().put("recent_closed_trades",JSONArray().put(
            JSONObject().put("recorded_exit_reason","OPPOSITE_WILLIAMS_LEVEL_TRIGGERED")
                .put("opposite_h1_signal_confirmed_in_saved_evidence",true)))
        val proof=WilliamsBookCausalUnderstanding.assess(scanner(candidate(signal())),exit,now)
        assertTrue(proof.getBoolean("scanner_data_usable_for_book_observations"))
        assertEquals(williamsTc2Profile,proof.getString("execution_source"))
        assertFalse(proof.getBoolean("whole_books_understood_and_certified"))
        assertFalse(proof.getBoolean("can_place_orders"))
        assertFalse(proof.getBoolean("can_change_stop_or_exit"))
        assertFalse(proof.getBoolean("senior_trend_reversal_inferred_from_h1_opposite"))
        assertEquals(1,proof.getInt("internal_h1_setup_proof_count"))
        assertEquals(1,proof.getInt("confirmed_h1_opposing_exit_count"))
        val btc=proof.getJSONArray("observed_candidates").getJSONObject(0)
        val evidence=btc.getJSONArray("signals").getJSONObject(0)
        assertEquals("TC2.WM2.THIRD_AO",evidence.getString("rule_id"))
        assertEquals("INTERNAL_BOOK_FORMATION_PROOF_PRESENT",evidence.getString("state"))
        assertFalse(evidence.getBoolean("actual_exchange_trigger_verified"))
        assertTrue(evidence.getBoolean("trigger_is_only_a_pending_level"))
    }

    @Test fun corruptThirdAoBarAndFutureH1TimestampRemainUnverified() {
        val s=signal()
        val detail=JSONObject().put("signal_id",s.getString("signal_id"))
            .put("setup_context",JSONObject()
                .put("third_ao_bar_at_ms",event-WILLIAMS_H1_MS)
                .put("initial_stop_rule","TC2_SUPER_AO_SIGNAL_BAR"))
        val audit=WilliamsBookCausalUnderstanding.assess(scanner(candidate(s,detail)),JSONObject(),now)
        assertEquals("INTERNAL_BOOK_FORMATION_PROOF_INCOMPLETE",
            audit.getJSONArray("observed_candidates").getJSONObject(0)
                .getJSONArray("signals").getJSONObject(0).getString("state"))
        val future=signal(signalAt=now+WILLIAMS_H1_MS)
        val other=WilliamsBookCausalUnderstanding.assess(scanner(candidate(future)),JSONObject(),now)
        assertEquals("NOT_VERIFIED_CLOSED_H1",other.getJSONArray("observed_candidates")
            .getJSONObject(0).getJSONArray("signals").getJSONObject(0).getString("state"))
        assertEquals(0,other.getInt("internal_h1_setup_proof_count"))
    }

    @Test fun oldSnapshotNeverBecomesFreshBookEvidence() {
        val stale=scanner(candidate(signal())).put("last_scan_at",now-180_000L)
        val data=WilliamsBookCausalUnderstanding.assess(stale,JSONObject(),now)
        assertFalse(data.getBoolean("scanner_data_usable_for_book_observations"))
        assertEquals(0,data.getInt("internal_h1_setup_proof_count"))
        assertEquals("SCANNER_NOT_COMPLETE_OR_STALE",data.getJSONArray("observed_candidates")
            .getJSONObject(0).getString("state"))
        assertFalse(data.getBoolean("trading_authority"))
    }

    @Test fun ntdCourseRemainObserversEvenIfTheirSignalHasEntryRole() {
        val ntd=signal(type="FRACTAL",profile=williamsNtdProfile)
        val evidence=WilliamsBookCausalUnderstanding.assess(scanner(candidate(ntd)),JSONObject(),now)
            .getJSONArray("observed_candidates").getJSONObject(0).getJSONArray("signals").getJSONObject(0)
        assertEquals("OBSERVER_FORMATION_ONLY",evidence.getString("state"))
        assertFalse(evidence.getBoolean("can_place_orders_from_this_evidence"))
        assertEquals("NTD.FIVE_DIMENSIONS",evidence.getString("rule_id"))
        assertFalse(williamsVerifiedNewRiskSource(williamsNtdProfile))
        assertFalse(williamsVerifiedNewRiskSource(williamsCourseProfile))
    }

    @Test fun originalSourcePagesNeverMasqueradeAsWholeBookCertification() {
        val a=WilliamsBookCausalUnderstanding.assess(JSONObject(),JSONObject(),now)
        val pages=a.getJSONArray("source_book_pdf_references")
        assertEquals(9,pages.length())
        val sources=(0 until pages.length()).map { pages.getJSONObject(it).getString("book") }.toSet()
        assertEquals(setOf("TC1","TC2","NTD","COURSE"),sources)
        for(i in 0 until pages.length()) {
            val p=pages.getJSONObject(i)
            assertTrue(p.getJSONArray("pdf_pages").length()>0)
            assertFalse(p.getBoolean("whole_book_certified"))
            assertFalse(p.getBoolean("can_place_orders"))
        }
        assertFalse(a.getBoolean("knowledge_accuracy_percent_calibrated"))
    }
}
