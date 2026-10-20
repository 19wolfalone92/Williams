package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

internal const val entryPassportEventSchema="WILLIAMS_H1_ENTRY_PASSPORT_EVENT_V2"

/** Additive audit schema; raw exchange intents remain the execution authority. */
internal val h1EntryPassportSchema=listOf(
    """CREATE TABLE IF NOT EXISTS h1_entry_passport_events(
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        symbol TEXT NOT NULL, client_id TEXT NOT NULL, attempt_id TEXT NOT NULL,
        direction TEXT NOT NULL, phase TEXT NOT NULL, observed_at_ms INTEGER NOT NULL,
        raw_json TEXT NOT NULL)""".trimIndent(),
    """CREATE INDEX IF NOT EXISTS ix_h1_passport_symbol_id
        ON h1_entry_passport_events(symbol,id)""".trimIndent(),
    """CREATE INDEX IF NOT EXISTS ix_h1_passport_client_id
        ON h1_entry_passport_events(client_id,id)""".trimIndent(),
    """CREATE TRIGGER IF NOT EXISTS h1_passport_no_update BEFORE UPDATE ON h1_entry_passport_events
        BEGIN SELECT RAISE(ABORT,'H1_PASSPORT_APPEND_ONLY'); END""".trimIndent(),
    """CREATE TRIGGER IF NOT EXISTS h1_passport_no_delete BEFORE DELETE ON h1_entry_passport_events
        BEGIN SELECT RAISE(ABORT,'H1_PASSPORT_APPEND_ONLY'); END""".trimIndent()
)

internal val latestH1EntryPassportsSql="""SELECT e.id,e.symbol,e.raw_json FROM h1_entry_passport_events e
    JOIN (SELECT symbol,MAX(id) AS last_id FROM h1_entry_passport_events GROUP BY symbol) latest
    ON e.id=latest.last_id""".trimIndent()

internal fun williamsEntryPassportEvent(
    plan:NativeFuturesCampaignCoordinator.EntryPlan,attemptId:String,phase:String,now:Long,
    outcome:JSONObject?=null
):JSONObject = JSONObject().put("schema",entryPassportEventSchema)
    .put("symbol",plan.symbol).put("direction",plan.direction.name)
    .put("client_id",plan.clientId).put("entry_client_id",plan.entryClientId)
    .put("attempt_id",attemptId).put("signal_id",plan.signalId)
    .put("signal_type",plan.signalType).put("strategy_profile",plan.strategyProfile)
    .put("signal_bar_time_ms",plan.signalBarTimeMs).put("signal_reason",safeDiagnosticText(plan.signalReason))
    .put("observed_at_ms",now).put("phase",phase)
    .put("entry_kind",plan.kind.name).put("execution_semantics",plan.executionSemantics)
    .put("entry_price",plan.entryPrice).put("protective_stop",plan.protectiveStop)
    .put("quantity",plan.quantity).put("risk_pct",plan.riskPct).put("leverage",plan.leverage)
    .put("trade_mode",plan.tradeMode)
    .put("setup_context",runCatching { JSONObject(plan.setupContext) }.getOrDefault(JSONObject()))
    .put("outcome",outcome?.let { JSONObject(it.toString()) }?:JSONObject.NULL)
    .put("frame","1h").put("m15_confirmation_required",false).put("trade_authority",false)

private fun passportObjectRows(rows:JSONArray)=
    (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }

/** Exact identities, not the bounded 12-row display chain, determine attribution. */
internal fun williamsEntryPassport(
    symbol:String,execution:JSONObject?,intents:JSONArray,latestAudit:JSONObject?,
    now:Long,exchangeClockFresh:Boolean,journalAvailable:Boolean=true
):JSONObject {
    val out=JSONObject().put("schema","WILLIAMS_H1_ENTRY_PASSPORT_V2")
        .put("symbol",symbol).put("frame","1h").put("trade_authority",false)
        .put("m15_confirmation_required",false).put("state","NO_EXECUTION_DECISION")
        .put("protection_observation","UNVERIFIED")
    val audit=latestAudit?.takeIf { it.optString("schema")==entryPassportEventSchema &&
        it.optString("symbol")==symbol }
    val cache=execution?.takeIf { it.optString("symbol",symbol)==symbol }
    val useAudit=audit!=null && (cache==null ||
        if(cache.optLong("audit_event_id",0L)>0L)
            audit.optLong("audit_event_id",0L)>cache.optLong("audit_event_id",0L)
        else audit.optLong("observed_at_ms")>cache.optLong("observed_at_ms") ||
            (audit.optLong("observed_at_ms")==cache.optLong("observed_at_ms") &&
                !cache.optJSONObject("outcome").let { it?.optBoolean("safe_rejection",false)?:false }))
    val selected=(if(useAudit) audit else cache)?:return out
    // Clone to prevent a later UI/JSON mutation from changing the saved evidence.
    out.put("decision",JSONObject(selected.toString()))
    for(key in listOf("client_id","entry_client_id","attempt_id","signal_id","signal_type",
        "strategy_profile","direction","signal_bar_time_ms","signal_reason","observed_at_ms",
        "phase","entry_price","protective_stop","quantity","risk_pct","leverage","setup_context"))
        if(selected.has(key)) out.put(key,selected.get(key))
    val phase=selected.optString("phase","COORDINATOR_RETURNED")
    val outcome=selected.optJSONObject("outcome")
    val accepted=outcome?.optBoolean("accepted",false)==true
    val uncertain=phase=="EXECUTION_EXCEPTION_UNVERIFIED" || outcome?.optBoolean("outcome_unknown",false)==true
    val explicitRejection=outcome?.optBoolean("safe_rejection",false)==true &&
        outcome.opt("mutation_attempted")==false
    val clientId=selected.optString("client_id")
    val side=selected.optString("direction")
    val profile=selected.optString("strategy_profile")
    val signalId=selected.optString("signal_id")
    val all=passportObjectRows(intents)
    val matches=all.filter { it.optString("symbol")==symbol &&
        it.optString("client_id")==clientId && clientId.isNotBlank() &&
        it.optString("direction")==side && side in setOf("LONG","SHORT") &&
        profile.isNotBlank() && it.optString("strategy_profile")==profile &&
        (signalId.isBlank() || it.optString("signal_id")==signalId) }
    val matched=matches.singleOrNull()
    val verifiedAfterAttempt=matched!=null && exchangeClockFresh &&
        matched.optLong("last_reconciled_ms",0L) in selected.optLong("observed_at_ms")..now &&
        matched.optString("order_status") in setOf("NEW","FILLED","PARTIALLY_FILLED","CANCELED","CANCELLED",
            "REJECTED","EXPIRED","EXPIRED_IN_MATCH","TRIGGERING","TRIGGERED","FINISHED")
    out.put("state",when {
        !journalAvailable -> "JOURNAL_UNAVAILABLE"
        selected.optString("symbol")!=symbol -> "LEGACY_DECISION_UNVERIFIED"
        (uncertain || phase=="PREPARED") && verifiedAfterAttempt -> "EXCHANGE_RECONCILED_AFTER_UNKNOWN"
        uncertain -> "SUBMISSION_OUTCOME_UNKNOWN"
        phase=="PREPARED" -> "PREPARED_NOT_CONFIRMED"
        explicitRejection -> "REJECTED_WITHOUT_MUTATION"
        accepted && matched!=null -> "INTENT_JOURNALED"
        accepted -> "ACCEPTED_UNVERIFIED"
        else -> "RESULT_REQUIRES_RECONCILIATION"
    }).put("journal_available",journalAvailable)
        .put("execution_outcome",outcome?:JSONObject.NULL)
        .put("identity_match",matched!=null && journalAvailable)
    if(matched==null || !journalAvailable) return out
    out.put("order",JSONObject(matched.toString()))
    val parentId=matched.optString("entry_client_id")
    val owner=if(matched.optString("intent_role","ENTRY")=="ENTRY") matched else all.filter {
        it.optString("intent_role","ENTRY")=="ENTRY" && it.optString("client_id")==parentId &&
            parentId.isNotBlank() && it.optString("symbol")==symbol &&
            it.optString("direction")==side && it.optString("strategy_profile")==profile
    }.singleOrNull()
    if(owner==null) return out
    val observedAt=owner.optLong("last_reconciled_ms",0L)
    val fresh=exchangeClockFresh && observedAt>0 && observedAt<=now && now-observedAt<=30_000L
    val active=owner.optString("campaign_status") in setOf("OPEN_PROTECTED","POSITION_OPEN")
    val protected=owner.optString("protection_status")=="PROTECTED" &&
        owner.optString("protection_client_id").isNotBlank() &&
        owner.optString("previous_protection_client_id").isBlank() &&
        owner.optBoolean("strategy_ownership_verified",false) &&
        owner.optString("protective_stop").toDoubleOrNull()?.let { it.isFinite() && it>0 }==true
    val observation=when {
        !active -> "CAMPAIGN_NOT_OPEN"
        !exchangeClockFresh -> "TIME_UNVERIFIED"
        !fresh -> "JOURNAL_PROTECTION_STALE"
        !protected -> "PROTECTION_NOT_CONFIRMED"
        parentId.isNotBlank() -> "SHARED_CAMPAIGN_PROTECTION_OBSERVED"
        else -> "EXCHANGE_PROTECTION_OBSERVED"
    }
    out.put("protection_owner",JSONObject(owner.toString()))
        .put("protection_owner_client_id",owner.optString("client_id"))
        .put("protection_observation",observation)
        .put("exchange_observed_at_ms",if(observedAt>0) observedAt else JSONObject.NULL)
        .put("protection_scope",if(parentId.isBlank()) "OWN_CAMPAIGN" else "SHARED_PARENT_CAMPAIGN")
        .put("protection_is_current_permission",false)
    return out
}

/** A failed durable append must not suppress the next observation. */
internal fun recordChangedCampaignObservation(
    cache:MutableMap<String,String>,id:String,fingerprint:String,record:()->Unit
) {
    if(cache[id]==fingerprint) return
    record()
    cache[id]=fingerprint
    if(cache.size>1000) cache.remove(cache.keys.first())
}
