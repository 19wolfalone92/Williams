package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsBookCausalUnderstandingTest {
    private val now = 1_800_000_001_000L
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
    private fun closedH1Source(upward:Boolean=true):JSONArray=JSONArray().apply {
        // One exact H1 interval per bar; third GREEN AO occurs at index 36.
        // A quadratic convex midprice creates increasing 5-34 AO values.
        for(i in 0..36) {
            val at=event-(36-i)*WILLIAMS_H1_MS
            val mid=if(upward) 100.0+i*i*.02 else 160.0-i*i*.02
            put(JSONArray().put(at).put(mid).put(mid+1.0).put(mid-1.0).put(mid).put(1000.0))
        }
    }
    private fun candidate(s:JSONObject,details:JSONObject?=null):JSONObject =
        JSONObject().put("symbol","BTCUSDT").put("closed_h1_as_of_ms",close)
            .put("campaign_signals",JSONArray().put(s))
            .put("decision_trace",JSONObject().put("candles",closedH1Source()).put("signals",
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
        assertEquals("TC2.WM2.THREE_AO_BARS",evidence.getString("rule_id"))
        assertEquals("INTERNAL_BOOK_FORMATION_PROOF_PRESENT",evidence.getString("state"))
        assertEquals("PASS",evidence.getString("independent_closed_h1_third_ao_status"))
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

    @Test fun independentAoDetectsContradictoryRealClosedH1AndMissingCandles() {
        val signal=signal()
        val oppositeSource=candidate(signal)
        oppositeSource.getJSONObject("decision_trace").put("candles",closedH1Source(false))
        val contradiction=WilliamsBookCausalUnderstanding.assess(
            scanner(oppositeSource),JSONObject(),now).getJSONArray("observed_candidates")
            .getJSONObject(0).getJSONArray("signals").getJSONObject(0)
        assertEquals("FAIL",contradiction.getString("independent_closed_h1_third_ao_status"))
        assertEquals("INDEPENDENT_CLOSED_H1_AO_CONTRADICTS_SETUP",
            contradiction.getString("state"))
        val missingSource=candidate(signal)
        missingSource.getJSONObject("decision_trace").remove("candles")
        val missing=WilliamsBookCausalUnderstanding.assess(
            scanner(missingSource),JSONObject(),now).getJSONArray("observed_candidates")
            .getJSONObject(0).getJSONArray("signals").getJSONObject(0)
        assertEquals("NOT_VERIFIED",missing.getString("independent_closed_h1_third_ao_status"))
        assertEquals("INDEPENDENT_CLOSED_H1_AO_NOT_VERIFIED",missing.getString("state"))
    }

    @Test fun duplicatedSignalIdCannotChooseLastProofAndClaimBookUnderstanding() {
        val c=candidate(signal())
        val signal=c.getJSONArray("campaign_signals").getJSONObject(0)
        val detail=c.getJSONObject("decision_trace")
            .getJSONArray("signals").getJSONObject(0)
        c.getJSONObject("decision_trace").getJSONArray("signals")
            .put(JSONObject(detail.toString()))
        c.getJSONArray("campaign_signals").put(JSONObject(signal.toString()))
        val report=WilliamsBookCausalUnderstanding.assess(scanner(c),JSONObject(),now)
        assertEquals(0,report.getInt("internal_h1_setup_proof_count"))
        assertEquals("SOURCE_SIGNAL_IDENTITY_AMBIGUOUS",report.getJSONArray("observed_candidates")
            .getJSONObject(0).getJSONArray("signals").getJSONObject(0).getString("state"))
        val errors=report.getJSONArray("source_contract_discrepancies").toString()
        assertTrue(errors.contains("DUPLICATE_SOURCE_TRACE_SIGNAL"))
        assertTrue(errors.contains("DUPLICATE_LIVE_SIGNAL"))
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
        assertEquals("NTD.FIRST_FRACTAL",evidence.getString("rule_id"))
        assertFalse(williamsVerifiedNewRiskSource(williamsNtdProfile))
        assertFalse(williamsVerifiedNewRiskSource(williamsCourseProfile))
    }

    @Test fun closedH1OpposingBothSeniorFramesIsNotProofSeniorTrendReversed() {
        fun frame(period:String,side:String,closeAt:Long):JSONObject=JSONObject()
            .put("frame",period).put("state","PHASE_CANDIDATE")
            .put("distinct_structural_hypotheses",1)
            .put("market_reading",JSONObject().put("state","OBSERVED")
                .put("observed_bias",side).put("as_of_close_ms",closeAt))
        val c=candidate(signal())
        val phase=JSONObject().put("as_of",now)
            .put("frames",JSONArray()
                .put(frame("1h","DOWN",now-1_000L))
                .put(frame("4h","UP",now-2_000L))
                .put(frame("1d","UP",now-3_000L)))
        c.getJSONObject("decision_trace").put("market_phase",phase)
        val a=WilliamsBookCausalUnderstanding.assess(scanner(c),JSONObject(),now)
        val context=a.getJSONArray("observed_candidates").getJSONObject(0)
            .getJSONObject("senior_frame_context")
        assertEquals("OBSERVED_CONTEXT_ONLY",context.getString("state"))
        assertEquals("OPPOSING_OBSERVED_BIAS_NOT_CONFIRMED_REVERSAL",
            context.getString("h1_vs_senior_context"))
        assertFalse(context.getBoolean("senior_trend_end_proven"))
        assertFalse(context.getBoolean("can_veto_tc2_exit"))
        assertFalse(context.getBoolean("can_place_orders"))
        assertEquals(3,context.getJSONArray("frame_readings").length())
        // A missing D1 historical chart prevents an assertive senior reading.
        phase.getJSONArray("frames").remove(2)
        val missing=WilliamsBookCausalUnderstanding.assess(scanner(c),JSONObject(),now)
            .getJSONArray("observed_candidates").getJSONObject(0)
            .getJSONObject("senior_frame_context")
        assertEquals("NOT_VERIFIED",missing.getString("state"))
        assertEquals("INSUFFICIENT_SENIOR_EVIDENCE",missing.getString("h1_vs_senior_context"))
    }

    @Test fun tc1MfiAndFiveMagicSignsRemainHistoricalObserverWithSharedCourseVolume() {
        val c=candidate(signal())
        val trace=c.getJSONObject("decision_trace")
        trace.put("as_of_close_ms",close)
            .put("tc1_zero_point_observation",JSONObject()
                .put("schema","TC1_ZERO_POINT_FIVE_SIGNS_V1")
                .put("state","OBSERVATIONAL_PARTIAL_NOT_EXECUTABLE")
                .put("zero_point_confirmed",false)
                .put("wave_degree_certified",false))
        val proof=WilliamsBookCausalUnderstanding.assess(scanner(c),JSONObject(),now)
        val source=proof.getJSONArray("observed_candidates").getJSONObject(0)
            .getJSONObject("tc1_historical_observation")
        assertEquals("TC1_HISTORICAL",source.getString("source"))
        assertEquals("OBSERVE",source.getString("execution_role"))
        assertEquals("HISTORICAL_TC1_MFI_PROXY_OBSERVED",source.getString("state"))
        assertEquals("TC1_CH6_MFI_FOUR_WINDOWS_PROXY_V1",
            source.getJSONObject("mfi_proxy").getString("schema"))
        assertFalse(source.getBoolean("book_tick_volume_verified"))
        assertTrue(source.getBoolean("course_level_1_reuses_same_volume_not_independent_confirmation"))
        assertEquals("PARTIAL_CANDIDATE_NOT_WAVE_PROOF",
            source.getString("five_magic_bullets_source_state"))
        assertFalse(source.getBoolean("zero_point_certified"))
        assertFalse(source.getBoolean("can_place_orders"))
        trace.remove("as_of_close_ms")
        val missing=WilliamsBookCausalUnderstanding.assess(scanner(c),JSONObject(),now)
            .getJSONArray("observed_candidates").getJSONObject(0)
            .getJSONObject("tc1_historical_observation")
        assertEquals("NOT_VERIFIED",missing.getString("state"))
        assertFalse(missing.getBoolean("can_place_orders"))
    }

    @Test fun originalSourcePagesNeverMasqueradeAsWholeBookCertification() {
        val a=WilliamsBookCausalUnderstanding.assess(JSONObject(),JSONObject(),now)
        val pages=a.getJSONArray("source_book_pdf_references")
        assertEquals(14,pages.length())
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
