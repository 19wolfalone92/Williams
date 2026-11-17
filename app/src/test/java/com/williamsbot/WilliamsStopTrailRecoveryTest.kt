package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsStopTrailRecoveryTest {
    private val now=10_000L
    private fun evidence()=JSONObject().put("schema","WILLIAMS_TC2_STOP_REVIEW_V1")
        .put("direction","SHORT").put("decision","PROPOSE_TIGHTEN_ONLY")
        .put("old_stop",0.156).put("candidate_stop",0.15580000000000002)
        .put("submitted_stop_price","0.1558")
        .put("proposed_binance_protection_client_id","W2T_TEST_NEW")
        .put("h1_full_closed_source",JSONArray().put(JSONObject().put("high",0.1557)))
    private fun prior()=JSONArray().put(stopTrailDecisionAuditEvent(
        "ADAUSDT","SHORT","test-entry","PROPOSED_BEFORE_MUTATION",evidence(),recordedAtMs=100L)
        .put("audit_event_id",12L)).put(stopTrailDecisionAuditEvent(
        "ADAUSDT","SHORT","test-entry","EXCHANGE_NOT_CONFIRMED",evidence(),
        JSONObject().put("ok",false).put("blocker","Old cancellation unresolved"),200L)
        .put("audit_event_id",13L))
    private fun owner()=JSONObject().put("symbol","ADAUSDT").put("direction","SHORT")
        .put("client_id","test-entry").put("intent_role","ENTRY")
        .put("strategy_profile",williamsTc2Profile).put("strategy_ownership_verified",true)
        .put("protection_status","PROTECTED").put("protection_client_id","W2T_TEST_NEW")
        .put("protective_stop","0.1558")
        .put("last_native_stop_replacement",JSONObject().put("phase","COMPLETED")
            .put("created_at_ms",150L).put("resolved_at_ms",300L)
            .put("old_stop","0.156").put("new_stop","0.1558")
            .put("new_id","W2T_TEST_NEW").put("final_outcome","ACKNOWLEDGED"))
        .put("last_confirmed_trail",JSONObject().put("client_id","W2T_TEST_NEW")
            .put("old_stop","0.156").put("new_stop","0.1558")
            .put("strategy_profile",williamsTc2Profile).put("observed_at_ms",301L))

    @Test fun diagnostic19RecoveryAppendsCompletionWithOriginalFailureAndCandleEvidence() {
        val history=prior()
        val before=history.toString()
        val event=requireNotNull(williamsStopTrailRecoveryEvent(owner(),history,now))
        assertEquals("RECOVERY_COMPLETED",event.getString("phase"))
        assertEquals(now,event.getLong("E"))
        val out=event.getJSONObject("exchange_outcome")
        assertEquals(300L,out.getLong("recovery_completed_at_ms"))
        assertEquals(12L,out.getLong("original_proposal_event_id"))
        assertEquals(13L,out.getLong("original_unconfirmed_event_id"))
        assertEquals("PERSISTED_COORDINATOR_COMPLETION_NOT_CURRENT_SIGNED_GET",out.getString("recovery_proof"))
        assertFalse(out.getBoolean("can_place_orders"))
        assertFalse(out.getBoolean("can_manage_stops"))
        assertEquals(1,event.getJSONObject("decision_evidence").getJSONArray("h1_full_closed_source").length())
        assertEquals(before,history.toString())
    }

    @Test fun missingOriginalOrAmbiguousProposalCannotInventARecoveredDecision() {
        assertNull(williamsStopTrailRecoveryEvent(owner(),JSONArray(),now))
        val withoutFailure=JSONArray().put(prior().getJSONObject(0))
        assertNull(williamsStopTrailRecoveryEvent(owner(),withoutFailure,now))
        val duplicate=prior().put(JSONObject(prior().getJSONObject(0).toString()).put("audit_event_id",11L))
        assertNull(williamsStopTrailRecoveryEvent(owner(),duplicate,now))
    }

    @Test fun signedCompletionCanResolveAnUnknownPostReplyWithoutRewritingItsTransportOutcome() {
        val row=owner()
        row.getJSONObject("last_native_stop_replacement").put("final_outcome","SUBMISSION_UNKNOWN")
        val event=requireNotNull(williamsStopTrailRecoveryEvent(row,prior(),now))
        assertEquals("SUBMISSION_UNKNOWN",event.getJSONObject("exchange_outcome")
            .getJSONObject("replacement").getString("final_outcome"))
        assertEquals("RECOVERY_COMPLETED",event.getString("phase"))
    }

    @Test fun anotherOwnerSideStopIdPriceOrSourceCannotResolveThisFailure() {
        for((key,value) in listOf("client_id" to "other-entry","symbol" to "BTCUSDT",
            "direction" to "LONG","strategy_profile" to williamsNtdProfile,
            "protection_client_id" to "another-stop","protective_stop" to "0.1559",
            "protection_status" to "REPLACEMENT_PENDING_PROTECTED"))
            assertNull(key,williamsStopTrailRecoveryEvent(owner().put(key,value),prior(),now))
        assertNull(williamsStopTrailRecoveryEvent(owner().put("strategy_ownership_verified",false),prior(),now))
        assertNull(williamsStopTrailRecoveryEvent(owner().put("native_stop_replacement",JSONObject()),prior(),now))
        val wrong=prior();wrong.getJSONObject(1).getJSONObject("decision_evidence")
            .put("submitted_stop_price","0.1559")
        assertNull(williamsStopTrailRecoveryEvent(owner(),wrong,now))
    }

    @Test fun incompleteFutureOrWrongTransactionAndUnrelatedFailuresStayUnverified() {
        for((key,value) in listOf("phase" to "CANCEL_BRIDGE","final_outcome" to "UNKNOWN",
            "new_id" to "another-stop","old_stop" to "0.157","resolved_at_ms" to now+1L)) {
            val candidate=owner();candidate.getJSONObject("last_native_stop_replacement").put(key,value)
            assertNull(key,williamsStopTrailRecoveryEvent(candidate,prior(),now))
        }
        val tooOld=prior();tooOld.getJSONObject(1).put("E",149L)
        assertNull(williamsStopTrailRecoveryEvent(owner(),tooOld,now))
        val tooLate=prior();tooLate.getJSONObject(1).put("E",301L)
        assertNull(williamsStopTrailRecoveryEvent(owner(),tooLate,now))
        val wrongMove=owner();wrongMove.getJSONObject("last_confirmed_trail").put("client_id","another-stop")
        assertNull(williamsStopTrailRecoveryEvent(wrongMove,prior(),now))
    }
}
