package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsEntryPassportTest {
    private val now=1_800_000_000_000L
    private fun execution()=JSONObject().put("schema","WILLIAMS_ENTRY_DECISION_V2")
        .put("symbol","BTCUSDT").put("client_id","entryA").put("attempt_id","attemptA")
        .put("direction","LONG").put("strategy_profile",williamsTc2Profile).put("signal_id","signalA")
        .put("signal_type","FRACTAL").put("observed_at_ms",now-1000)
        .put("audit_event_id",2L).put("phase","COORDINATOR_RETURNED")
        .put("outcome",JSONObject().put("accepted",true))
    private fun owner()=JSONObject().put("symbol","BTCUSDT").put("client_id","entryA")
        .put("direction","LONG").put("strategy_profile",williamsTc2Profile).put("signal_id","signalA")
        .put("intent_role","ENTRY").put("order_status","FILLED").put("campaign_status","OPEN_PROTECTED")
        .put("protection_status","PROTECTED").put("protection_client_id","stopA")
        .put("strategy_ownership_verified",true).put("protective_stop","95").put("last_reconciled_ms",now-500)
    private fun view(execution:JSONObject?=execution(),rows:JSONArray=JSONArray().put(owner()),
        audit:JSONObject?=null,at:Long=now,fresh:Boolean=true,journal:Boolean=true)=
        williamsEntryPassport("BTCUSDT",execution,rows,audit,at,fresh,journal)

    @Test fun exactEntryAndStopIdentityAreExportedWithoutGrantingTradeAuthority() {
        val p=view()
        assertEquals("INTENT_JOURNALED",p.getString("state"))
        assertEquals("EXCHANGE_PROTECTION_OBSERVED",p.getString("protection_observation"))
        assertEquals("stopA",p.getJSONObject("protection_owner").getString("protection_client_id"))
        assertFalse(p.getBoolean("trade_authority"))
        assertFalse(p.getBoolean("m15_confirmation_required"))
        val chain=williamsExecutionChain(JSONArray().put(owner()),"BTCUSDT").getJSONObject(0)
        assertEquals("stopA",chain.getString("protection_client_id"))
        assertEquals(now-500,chain.getLong("last_reconciled_ms"))
    }

    @Test fun diagnostic19OwnedPositionIsNotHiddenByTheSavedNewZeroAlgoResponse() {
        val row=owner().put("order_status","NEW").put("executed_qty","0")
            .put("confirmed_position_qty","329")
        val p=view(rows=JSONArray().put(row))
        assertEquals("POSITION_RECONCILED",p.getString("state"))
        val observation=p.getJSONObject("execution_observation")
        assertEquals("OWNED_POSITION_RECONCILED",observation.getString("position_observation"))
        assertEquals("329",observation.getString("confirmed_position_qty"))
        assertEquals("EXACT_FILL_NOT_VERIFIED",observation.getString("order_fill_observation"))
        assertFalse(observation.getBoolean("exact_entry_fill_verified"))
        assertFalse(observation.getBoolean("can_place_orders"))
        assertEquals("NEW",p.getJSONObject("order").getString("order_status"))
        assertEquals("0",row.getString("executed_qty"))
    }

    @Test fun diag7_btcConditionalParentNewZeroAndExactSignedChildAreSeparateObservations() {
        val row=owner().put("order_status","NEW").put("executed_qty","0")
            .put("confirmed_position_qty","0.0014")
            .put("actual_entry_order_id","123456")
            .put("actual_entry_order_status","FILLED")
            .put("actual_entry_executed_qty","0.0014")
        val o=williamsEntryExecutionObservation(row,now,true)
        assertEquals("OWNED_POSITION_RECONCILED",o.getString("position_observation"))
        assertEquals("NEW",o.getString("recorded_order_status"))
        assertEquals("0",o.getString("recorded_executed_qty"))
        assertTrue(o.getBoolean("matching_engine_child_order_id_present"))
        assertEquals("FILLED",o.getString("matching_engine_child_order_status"))
        assertEquals("0.0014",o.getString("matching_engine_child_executed_qty"))
        assertEquals("CACHED_EXACT_CHILD_SIGNED_GET_RECORDED",
            o.getString("matching_engine_child_observation"))
        assertTrue(o.getBoolean("parent_new_zero_with_recorded_child_fill"))
        // A durable cached signed order row does not certify live fill/STOP timing
        // and can never authorize an order without fresh coordinator controls.
        assertFalse(o.getBoolean("exact_entry_fill_verified"))
        assertFalse(o.getBoolean("child_snapshot_is_current_execution_authority"))
        assertFalse(o.getBoolean("can_place_orders"))
        assertFalse(o.getBoolean("can_manage_stops"))
        assertEquals("NEW",row.getString("order_status"))
        assertEquals("0",row.getString("executed_qty"))
    }

    @Test fun diag7_missingOrZeroSignedChildCannotBeInferredFromOpenPosition() {
        val row=owner().put("order_status","NEW").put("executed_qty","0")
            .put("confirmed_position_qty","0.0014")
            .put("actual_entry_order_status","FILLED")
            .put("actual_entry_executed_qty","0.0014")
        for (bad in listOf(
            JSONObject(row.toString()),
            JSONObject(row.toString()).put("actual_entry_order_id","123").put("actual_entry_executed_qty","0"),
            JSONObject(row.toString()).put("actual_entry_order_id","123").put("actual_entry_order_status","NEW"),
            JSONObject(row.toString()).put("actual_entry_order_id","123").put("actual_entry_executed_qty","NaN")
        )) {
            val evidence=williamsEntryExecutionObservation(bad,now,true)
            assertEquals("CHILD_FILL_NOT_VERIFIED",
                evidence.getString("matching_engine_child_observation"))
            assertFalse(evidence.getBoolean("parent_new_zero_with_recorded_child_fill"))
            assertFalse(evidence.getBoolean("exact_entry_fill_verified"))
        }
    }

    @Test fun oldUnownedClosedOrParentQuantityCannotBecomeAnEntryFill() {
        val row=owner().put("order_status","NEW").put("executed_qty","0")
            .put("confirmed_position_qty","329")
        val invalid=listOf(row.toString()).flatMap { listOf(
            JSONObject(it).put("last_reconciled_ms",now-30_001),
            JSONObject(it).put("last_reconciled_ms",now+1),
            JSONObject(it).put("strategy_ownership_verified",false),
            JSONObject(it).put("campaign_status","CLOSED_OR_STOPPED"),
            JSONObject(it).put("intent_role","ADD_ON"),
            JSONObject(it).put("confirmed_position_qty","NaN")) }
        for(candidate in invalid) {
            val observation=williamsEntryExecutionObservation(candidate,now,true)
            assertEquals("NOT_VERIFIED",observation.getString("position_observation"))
            assertTrue(observation.isNull("confirmed_position_qty"))
            assertFalse(observation.getBoolean("exact_entry_fill_verified"))
        }
        assertEquals("NOT_VERIFIED",williamsEntryExecutionObservation(row,now,false)
            .getString("position_observation"))
    }

    @Test fun anotherPairSideSourceSignalOrDuplicateCannotLendItsStop() {
        for((key,value) in listOf("symbol" to "ETHUSDT","direction" to "SHORT",
            "strategy_profile" to williamsNtdProfile,"signal_id" to "other","client_id" to "other")) {
            val p=view(rows=JSONArray().put(owner().put(key,value)))
            assertFalse(key,p.has("protection_owner"))
            assertFalse(key,p.getBoolean("identity_match"))
        }
        assertFalse(view(rows=JSONArray().put(owner()).put(owner())).has("protection_owner"))
        assertEquals("NO_EXECUTION_DECISION",view(execution=execution().put("symbol","ETHUSDT")).getString("state"))
    }

    @Test fun boundedOrderDisplayDoesNotHideTheOlderMatchingEntryFromItsPassport() {
        val rows=JSONArray().put(owner())
        repeat(30) { rows.put(owner().put("client_id","later$it").put("signal_id","later$it")) }
        assertEquals(12,williamsExecutionChain(rows,"BTCUSDT").length())
        assertEquals("entryA",view(rows=rows).getJSONObject("order").getString("client_id"))
    }

    @Test fun staleFutureClockAndClosedCampaignCannotClaimFreshProtection() {
        assertEquals("JOURNAL_PROTECTION_STALE",view(at=now+31_000).getString("protection_observation"))
        assertEquals("JOURNAL_PROTECTION_STALE",view(rows=JSONArray().put(owner()
            .put("last_reconciled_ms",now+1))).getString("protection_observation"))
        assertEquals("TIME_UNVERIFIED",view(fresh=false).getString("protection_observation"))
        assertEquals("CAMPAIGN_NOT_OPEN",view(rows=JSONArray().put(owner()
            .put("campaign_status","CLOSED_OR_STOPPED"))).getString("protection_observation"))
        assertEquals("PROTECTION_NOT_CONFIRMED",view(rows=JSONArray().put(owner()
            .put("previous_protection_client_id","unresolved"))).getString("protection_observation"))
    }

    @Test fun lostReplyIsUnknownUntilTheExactIntentIsLaterReconciled() {
        val decision=execution().put("phase","EXECUTION_EXCEPTION_UNVERIFIED")
            .put("outcome",JSONObject().put("accepted",false).put("outcome_unknown",true))
        val unknown=view(execution=decision,rows=JSONArray())
        assertEquals("SUBMISSION_OUTCOME_UNKNOWN",unknown.getString("state"))
        assertEquals("EXCHANGE_RECONCILED_AFTER_UNKNOWN",view(execution=decision).getString("state"))
        assertEquals("SUBMISSION_OUTCOME_UNKNOWN",view(execution=decision,
            rows=JSONArray().put(owner().put("last_reconciled_ms",now-2000))).getString("state"))
    }

    @Test fun aNewerDurablePreparedAttemptBeatsAnOlderAcceptedDecisionCache() {
        val audit=JSONObject(execution().toString()).put("schema",entryPassportEventSchema)
            .put("client_id","entryB").put("attempt_id","attemptB").put("signal_id","signalB")
            .put("phase","PREPARED").put("outcome",JSONObject.NULL).put("audit_event_id",3L)
        val p=view(audit=audit)
        assertEquals("entryB",p.getString("client_id"))
        assertEquals("PREPARED_NOT_CONFIRMED",p.getString("state"))
        assertFalse(p.has("protection_owner"))
        assertEquals("entryB",view(execution=null,audit=audit).getString("client_id"))
    }

    @Test fun noMutationRejectionAndUnavailableJournalAreNotInventedFills() {
        val rejected=execution().put("outcome",JSONObject().put("accepted",false)
            .put("safe_rejection",true).put("mutation_attempted",false))
        assertEquals("REJECTED_WITHOUT_MUTATION",view(execution=rejected).getString("state"))
        val unavailable=view(journal=false)
        assertEquals("JOURNAL_UNAVAILABLE",unavailable.getString("state"))
        assertFalse(unavailable.has("protection_owner"))
    }

    @Test fun aFilledAdditionUsesOnlyItsExactlyMatchingParentCampaign() {
        val addition=owner().put("intent_role","ADD_ON").put("entry_client_id","entryA")
            .put("client_id","addA").put("signal_id","addSignal")
        val decision=execution().put("client_id","addA").put("entry_client_id","entryA")
            .put("signal_id","addSignal")
        val p=view(execution=decision,rows=JSONArray().put(owner()).put(addition))
        assertEquals("SHARED_PARENT_CAMPAIGN",p.getString("protection_scope"))
        assertEquals("SHARED_CAMPAIGN_PROTECTION_OBSERVED",p.getString("protection_observation"))
        assertEquals("entryA",p.getString("protection_owner_client_id"))
        assertFalse(view(execution=decision,rows=JSONArray().put(owner().put("direction","SHORT"))
            .put(addition)).has("protection_owner"))
    }

    @Test fun auditEvidenceCopiesAndOnlySuccessfulObservationsEnterTheDeduplicationCache() {
        val cache=mutableMapOf<String,String>()
        var writes=0
        repeat(2) {
            try { recordChangedCampaignObservation(cache,"entry","new") { writes++;error("disk full") } }
            catch(_:IllegalStateException) { }
            assertTrue(cache.isEmpty())
        }
        recordChangedCampaignObservation(cache,"entry","new") { writes++ }
        recordChangedCampaignObservation(cache,"entry","new") { fail("Duplicate durable observation") }
        assertEquals(3,writes)
        assertEquals("new",cache["entry"])
    }
}
