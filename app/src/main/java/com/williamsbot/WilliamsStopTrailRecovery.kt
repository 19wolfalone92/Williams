package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

/** A separate idempotency index; never rewrite a failed exchange receipt. */
internal val stopTrailRecoverySchema=listOf(
    """CREATE TABLE IF NOT EXISTS stop_trail_recovery_receipts(
        campaign_id TEXT NOT NULL, protection_client_id TEXT NOT NULL,
        proposal_event_id INTEGER NOT NULL, recovery_event_id INTEGER NOT NULL,
        PRIMARY KEY(campaign_id,protection_client_id))""".trimIndent(),
    "CREATE INDEX IF NOT EXISTS ix_stop_trail_events ON events(event_type,id)"
)

/** Bind a persisted coordinator completion to the original full proposal
 * and an inconclusive result for that SAME campaign/id/price. This may be
 * observed after restart; it is not a new signed GET or trading authority. */
internal fun williamsStopTrailRecoveryEvent(
    owner:JSONObject,prior:JSONArray,now:Long
):JSONObject? {
    val tx=owner.optJSONObject("last_native_stop_replacement") ?: return null
    val id=owner.optString("client_id")
    val next=tx.optString("new_id")
    val created=tx.optLong("created_at_ms")
    val completed=tx.optLong("resolved_at_ms")
    val symbol=owner.optString("symbol")
    val side=owner.optString("direction")
    fun equal(a:String,b:String):Boolean = a.toBigDecimalOrNull()?.let { left ->
        b.toBigDecimalOrNull()?.let { right -> left.signum()>0 && left.compareTo(right)==0 }
    }==true
    if(id.isBlank() || !next.matches(Regex("[A-Za-z0-9_./:-]{1,36}")) || symbol.isBlank() ||
        side !in setOf("LONG","SHORT") || created<=0L || completed<created || now<completed ||
        tx.optString("phase")!="COMPLETED" ||
        tx.optString("final_outcome") !in setOf("ACKNOWLEDGED","SUBMISSION_UNKNOWN") ||
        owner.has("native_stop_replacement") ||
        owner.optString("intent_role","ENTRY")!="ENTRY" ||
        owner.optString("strategy_profile")!=williamsTc2Profile ||
        !owner.optBoolean("strategy_ownership_verified",false) ||
        owner.optString("protection_status")!="PROTECTED" ||
        owner.optString("protection_client_id")!=next ||
        owner.optString("previous_protection_client_id").isNotBlank() ||
        !equal(owner.optString("protective_stop"),tx.optString("new_stop"))) return null
    val move=owner.optJSONObject("last_confirmed_trail") ?: return null
    if(move.optString("client_id")!=next ||
        move.optString("strategy_profile")!=owner.optString("strategy_profile") ||
        move.optLong("observed_at_ms") !in completed..now ||
        !equal(move.optString("old_stop"),tx.optString("old_stop")) ||
        !equal(move.optString("new_stop"),tx.optString("new_stop"))) return null
    val matching=(0 until prior.length()).mapNotNull { prior.optJSONObject(it) }.filter { event ->
        val evidence=event.optJSONObject("decision_evidence")
        event.optString("campaign_id")==id && event.optString("symbol")==symbol &&
            event.optString("direction")==side &&
            evidence?.optString("proposed_binance_protection_client_id")==next &&
            evidence.optString("schema")=="WILLIAMS_TC2_STOP_REVIEW_V1" &&
            equal(evidence.optString("submitted_stop_price").ifBlank {
                evidence.optString("candidate_stop") },tx.optString("new_stop")) &&
            equal(evidence.optString("old_stop"),tx.optString("old_stop"))
    }
    val proposal=matching.filter { it.optString("phase")=="PROPOSED_BEFORE_MUTATION" &&
        it.optLong("E") in 1L..created && it.optLong("audit_event_id")>0L }.singleOrNull() ?: return null
    val proposalId=proposal.getLong("audit_event_id")
    val failed=matching.filter { it.optString("phase") in
        setOf("EXCHANGE_NOT_CONFIRMED","EXCHANGE_EXCEPTION_UNVERIFIED") &&
        it.optLong("E") in created..completed && it.optLong("audit_event_id")>proposalId
    }.maxByOrNull { it.optLong("audit_event_id") } ?: return null
    val outcome=JSONObject().put("ok",true).put("stop",tx.getString("new_stop"))
        .put("protection_client_id",next)
        .put("recovery_proof","PERSISTED_COORDINATOR_COMPLETION_NOT_CURRENT_SIGNED_GET")
        .put("recovery_completed_at_ms",completed)
        .put("original_proposal_event_id",proposalId)
        .put("original_unconfirmed_event_id",failed.getLong("audit_event_id"))
        .put("original_proposal_source_revision",proposal.optString("source_revision"))
        .put("original_proposal_app_version",proposal.optString("app_version"))
        .put("replacement",JSONObject(tx.toString()))
        .put("can_place_orders",false).put("can_manage_stops",false)
    return stopTrailDecisionAuditEvent(symbol,side,id,"RECOVERY_COMPLETED",
        proposal.getJSONObject("decision_evidence"),outcome,now)
}
