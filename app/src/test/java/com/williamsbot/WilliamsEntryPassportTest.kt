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
