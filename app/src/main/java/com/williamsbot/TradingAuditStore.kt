package com.williamsbot

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import org.json.JSONObject
import org.json.JSONArray
import java.security.MessageDigest

/**
 * Full, machine-readable TC2 decision event. Keep it OUTSIDE the bounded
 * COCKPIT_* projection: lossless forensic evidence must survive UI rotation.
 * A pre-mutation receipt is mandatory for discretionary STOP tightening.
 */
internal fun stopTrailDecisionAuditEvent(
    symbol: String, direction: String, campaignId: String, phase: String,
    evidence: JSONObject, exchangeOutcome: JSONObject? = null,
    recordedAtMs: Long = System.currentTimeMillis()
): JSONObject {
    require(symbol.isNotBlank() && campaignId.isNotBlank() && recordedAtMs > 0L)
    require(direction == "LONG" || direction == "SHORT")
    require(phase in setOf("REVIEW_DEFERRED", "PROPOSED_BEFORE_MUTATION",
        "EXCHANGE_RESPONSE_OK", "EXCHANGE_NOT_CONFIRMED", "EXCHANGE_EXCEPTION_UNVERIFIED",
        "RECOVERY_COMPLETED"))
    require(evidence.optString("schema") == "WILLIAMS_TC2_STOP_REVIEW_V1")
    require(evidence.optString("direction") == direction)
    if (phase == "REVIEW_DEFERRED")
        require(evidence.optString("decision") == "DEFER" &&
            evidence.optString("blocker").isNotBlank()) {
            "Deferred audit must preserve its actual refusal reason"
        }
    if (phase == "PROPOSED_BEFORE_MUTATION" || phase == "EXCHANGE_RESPONSE_OK" ||
        phase == "EXCHANGE_NOT_CONFIRMED" || phase == "EXCHANGE_EXCEPTION_UNVERIFIED" ||
        phase == "RECOVERY_COMPLETED") {
        require(evidence.optString("decision") == "PROPOSE_TIGHTEN_ONLY" &&
            evidence.optString("proposed_binance_protection_client_id").isNotBlank()) {
            "Stop mutation audit requires a traceable actual proposal and client ID"
        }
    }
    if (phase == "EXCHANGE_RESPONSE_OK" || phase == "EXCHANGE_NOT_CONFIRMED" || phase == "RECOVERY_COMPLETED") {
        require(exchangeOutcome != null && exchangeOutcome.optBoolean("ok") ==
            (phase != "EXCHANGE_NOT_CONFIRMED")) {
            "Exchange status must agree with the signed coordinator response"
        }
    }
    if (phase == "EXCHANGE_RESPONSE_OK" || phase == "RECOVERY_COMPLETED") {
        val proposedId=evidence.getString("proposed_binance_protection_client_id")
        // Compare exact decimal strings submitted to Binance, not unrounded
        // IEEE-754 proposal doubles (e.g. DOGE 0.07687000000000001).
        val proposedStop=evidence.optString("submitted_stop_price")
            .ifBlank { evidence.getDouble("candidate_stop").toString() }.toBigDecimal()
        val stop=exchangeOutcome?.opt("stop")
        val observedPrice=when(stop) {
            is JSONObject -> stop.optString("triggerPrice").ifBlank { stop.optString("stopPrice") }
            is String, is Number -> stop.toString()
            else -> ""
        }.toBigDecimalOrNull()
        val observedId=when(stop) {
            is JSONObject -> stop.optString("clientAlgoId")
            else -> exchangeOutcome?.optString("protection_client_id").orEmpty()
        }
        require(observedPrice!=null && observedPrice.compareTo(proposedStop)==0 &&
            observedId==proposedId && (stop !is JSONObject ||
                stop.optString("algoStatus",stop.optString("status")) in setOf("NEW","WORKING","ACCEPTED"))) {
            "An accepted receipt must reference the exact confirmed Binance stop ID and trigger"
        }
    }
    if(phase=="RECOVERY_COMPLETED") {
        val receipt=requireNotNull(exchangeOutcome)
        val tx=requireNotNull(receipt.optJSONObject("replacement"))
        require(receipt.optString("recovery_proof")=="PERSISTED_COORDINATOR_COMPLETION_NOT_CURRENT_SIGNED_GET" &&
            receipt.optLong("original_proposal_event_id")>0L &&
            receipt.optLong("original_unconfirmed_event_id")>receipt.optLong("original_proposal_event_id") &&
            tx.optString("phase")=="COMPLETED" &&
            tx.optString("final_outcome") in setOf("ACKNOWLEDGED","SUBMISSION_UNKNOWN") &&
            tx.optString("new_id")==evidence.optString("proposed_binance_protection_client_id") &&
            tx.optString("new_stop").toBigDecimalOrNull()?.compareTo(
                receipt.optString("stop").toBigDecimalOrNull())==0 &&
            tx.optLong("created_at_ms")>0L && tx.optLong("resolved_at_ms") in tx.optLong("created_at_ms")..recordedAtMs &&
            receipt.optLong("recovery_completed_at_ms")==tx.optLong("resolved_at_ms") &&
            receipt.opt("can_place_orders")==false && receipt.opt("can_manage_stops")==false) {
            "Recovery needs exact completed coordinator evidence and original decision identities"
        }
    }
    if (phase == "EXCHANGE_EXCEPTION_UNVERIFIED")
        require(exchangeOutcome?.optBoolean("retry_authorized",true)==false) {
            "Unknown stop exchange outcome is never authorization to retry"
        }
    return JSONObject().put("e", "WILLIAMS_STOP_TRAIL_DECISION_V1")
        .put("E", recordedAtMs).put("schema", "WILLIAMS_STOP_TRAIL_DECISION_V1")
        .put("symbol", symbol).put("direction", direction)
        .put("campaign_id", campaignId).put("phase", phase)
        .put("source_revision", BuildConfig.SOURCE_REVISION)
        .put("app_version", BuildConfig.VERSION_NAME)
        .put("evidence_untruncated", true)
        .put("decision_evidence", JSONObject(evidence.toString()))
        .put("exchange_outcome", exchangeOutcome?.let { JSONObject(it.toString()) } ?: JSONObject.NULL)
}

/** Repeated deferrals on the same closed H1 candle are one market decision.
 * A change in reason, candidate or established stop starts a new observation.
 * Never deduplicate attempted/confirmed Binance mutations. */
internal fun stopTrailDeferredFingerprint(campaignId:String, evidence:JSONObject):String {
    val lastH1=evidence.optJSONArray("h1_last_twelve_closed")
    val lastH4=evidence.optJSONArray("h4_last_twelve_closed")
    fun lastClose(rows:JSONArray?):Long =
        rows?.optJSONObject((rows.length()-1).coerceAtLeast(0))
            ?.optLong("open_time_ms",0L) ?: 0L
    val h1At=lastClose(lastH1)
    val noClosedBarsBucket=if(h1At>0L) 0L else evidence.optLong("as_of_ms") / 3_600_000L
    return listOf(campaignId,evidence.optString("direction"),
        evidence.optString("blocker"),evidence.optString("source_rule"),
        evidence.optString("old_stop"),evidence.optString("candidate_stop"),
        h1At.toString(),lastClose(lastH4).toString(),noClosedBarsBucket.toString())
        .joinToString("|")
}

/** Binance's numeric trade ID is stable across duplicate WS receipts even
 * when the enclosing event and transaction timestamps are different. Never
 * assume that an order-status update or nontrade receipt is a unique fill. */
internal fun privateBinanceTradeHasStableId(kind:String,tradeId:String):Boolean =
    kind=="TRADE" && tradeId.toLongOrNull()?.let { it>0L } == true

/** Binance USD-M client-order IDs and numeric order IDs are only unique within
 * one symbol. Historical SQLite "orders" rows used a GLOBAL primary key and
 * could overwrite BTC with an unrelated ETH order, silently corrupting the
 * private order-status and cumulative-fill audit projection.
 *
 * The external client ID remains unchanged on Binance and in raw_json.
 * Only the internal SQLite lookup key receives this symbol namespace. */
internal fun privateFuturesOrderProjectionKey(
    symbol:String,clientId:String,orderId:String
):String {
    require(symbol.matches(Regex("[A-Z0-9]{2,24}"))) { "PRIVATE_ORDER_SYMBOL_INVALID" }
    val owner=clientId.ifBlank { orderId }
    require(owner.isNotBlank() && !owner.contains('|')) { "PRIVATE_ORDER_IDENTITY_INVALID" }
    return "$symbol|$owner"
}

/** A single in-place v7→v8 rewrite preserves old forensic rows (no DROP).
 * No existing Binance client-order ID can contain '|', so a previously
 * unscoped record cannot be mistaken for a new scoped one. */
internal const val PRIVATE_FUTURES_ORDER_SCOPE_V8_SQL =
    "UPDATE orders SET client_order_id = symbol || '|' || client_order_id"

/** A delayed/replayed private WS receipt is evidence, never permission to
 * rewind the durable order projection. A later signed REST reconciliation is
 * the only financial authority; this guard protects the audit timeline. */
internal fun privateOrderProjectionMayAdvance(
    previousAtMs:Long, previousOrderId:String, previousExecuted:String, previousStatus:String,
    incomingAtMs:Long, incomingOrderId:String, incomingExecuted:String, incomingStatus:String
):Boolean {
    val previousQty=previousExecuted.toBigDecimalOrNull() ?: return false
    val incomingQty=incomingExecuted.toBigDecimalOrNull() ?: return false
    if(previousAtMs<0L || incomingAtMs<=0L || incomingAtMs<previousAtMs ||
        incomingOrderId.isBlank() ||
        (previousOrderId.isNotBlank() && previousOrderId!=incomingOrderId) ||
        previousQty.signum()<0 || incomingQty.signum()<0 ||
        incomingQty.compareTo(previousQty)<0 || incomingStatus.isBlank()) return false
    val terminal=setOf("FILLED","CANCELED","CANCELLED","EXPIRED","EXPIRED_IN_MATCH","REJECTED")
    if(previousStatus in terminal && incomingStatus!=previousStatus) return false
    if(previousStatus=="PARTIALLY_FILLED" && incomingStatus=="NEW") return false
    return true
}

/**
 * Persistent exchange/order/execution/state audit trail.
 *
 * The raw event JSON is kept so a later reconciliation can reconstruct what
 * Binance told the bot, without relying on RAM or SharedPreferences.
 */
/**
 * The first journal observation after a process restart includes hundreds of
 * already-terminal intents. Their complete state is in the durable execution
 * ledger; do not replay them as newly occurred UI execution events.
 * Active or malformed owners and genuine new transitions remain observable.
 */
internal fun campaignIsHistoricalTerminalForCockpit(intent:JSONObject):Boolean {
    val role=intent.optString("intent_role","ENTRY")
    val lifecycle=campaignLifecycleStatus(intent)
    val exchangeStatus=intent.optString("exchange_status").uppercase(java.util.Locale.US)
    // EXIT receipts have no campaign_status but a signed EXIT_CONFIRMED means
    // the corresponding historical order is settled. Never infer that the
    // *position* is flat from a bare FILLED or an EXIT_PENDING order.
    return lifecycle == "CLOSED_OR_STOPPED" ||
        (role == "EXIT" && exchangeStatus == "EXIT_CONFIRMED")
}

internal fun campaignCockpitEventRequired(
    previouslyObserved:Boolean, historicalTerminal:Boolean, severity:String,
    hasDurableIdentity:Boolean
):Boolean = !hasDurableIdentity || previouslyObserved ||
    !historicalTerminal || severity == "FAIL"

/** A persisted terminal campaign is not an active missing-STOP incident.
 * Signed exchange reconciliation, not UI telemetry, handles its orphan STOP.
 */
internal fun campaignCockpitIncidentRequired(
    historicalTerminal:Boolean, severity:String, protectionStatus:String
):Boolean = severity == "FAIL" ||
    (!historicalTerminal &&
        protectionStatus in setOf("SUBMISSION_UNKNOWN", "UNRESOLVED_EXTRA_ORDER"))

// Distinct bounded retention: UI lifecycle events cannot evict safety incidents.
// Native execution/STOP exchange receipts are outside both queries.
internal const val COCKPIT_PROJECTION_TRIM_SQL =
    "DELETE FROM events WHERE event_type LIKE 'COCKPIT_%' AND event_type != 'COCKPIT_INCIDENT' AND id NOT IN (SELECT id FROM events WHERE event_type LIKE 'COCKPIT_%' AND event_type != 'COCKPIT_INCIDENT' ORDER BY id DESC LIMIT 1000)"
internal const val COCKPIT_INCIDENT_TRIM_SQL =
    "DELETE FROM events WHERE event_type = 'COCKPIT_INCIDENT' AND id NOT IN (SELECT id FROM events WHERE event_type = 'COCKPIT_INCIDENT' ORDER BY id DESC LIMIT 100)"


internal data class DurablePrivateUserEvent(val eventId: Long, val rawJson: String)

internal fun futuresApiKeyFingerprint(apiKey: String): String {
    require(apiKey.isNotBlank()) { "PRIVATE_EVENT_ACCOUNT_IDENTITY_UNAVAILABLE" }
    return privateEventSha256(apiKey)
}

internal fun privateUserEventFingerprint(rawJson: String): String {
    require(rawJson.isNotBlank()) { "PRIVATE_EVENT_PAYLOAD_EMPTY" }
    return privateEventSha256(rawJson)
}

internal fun privateEventDrainMayReschedule(
    pending: Boolean?, failed: Boolean, generationAtStart: Long, currentGeneration: Long
): Boolean = pending == true && (!failed || currentGeneration > generationAtStart)

private fun privateEventSha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

class TradingAuditStore(context: Context) :
    SQLiteOpenHelper(context, "williams_trading_audit.db", null, 8) {

    private val lastCampaignObservations = mutableMapOf<String, String>()
    // Only COCKPIT_* UI projection is batch-trimmed. Signed exchange and STOP
    // audit events are separate, unbounded durable records.
    private var cockpitEventsSinceTrim = 0
    private val lastDeferredStopTrailFingerprint = linkedMapOf<String, String>()
    // Reduce repeating successful reconciliation messages in a bounded UI
    // event window. Failures and recovery transitions are NEVER compacted.
    // All exchange/order/position snapshots remain durable in their own stores.
    private val lastHealthyReconciliationByPurpose = mutableMapOf<String, Long>()
    private var healthyReconciliationMessagesCoalesced = 0L

    @Synchronized
    fun recordReconciliationObservation(purpose: String, state: String, ok: Boolean, blocker: String) {
        val now = System.currentTimeMillis()
        val fingerprint = "${purpose.take(80)}|${state.take(40)}"
        if (ok) {
            val recordedAt = lastHealthyReconciliationByPurpose[fingerprint] ?: 0L
            if (recordedAt > 0L && now >= recordedAt && now - recordedAt < 15L * 60L * 1000L) {
                healthyReconciliationMessagesCoalesced++
                return
            }
        }
        recordCockpitEvent("RECONCILIATION", state = state,
            detail = "${purpose.take(80)}; ok=$ok; ${blocker.take(240)}")
        // Record the heartbeat time only AFTER durable Cockpit event insertion:
        // a transient SQLite failure must not silence the next observation.
        if (ok) {
            if (lastHealthyReconciliationByPurpose.size >= 32) lastHealthyReconciliationByPurpose.clear()
            lastHealthyReconciliationByPurpose[fingerprint] = now
        } else {
            lastHealthyReconciliationByPurpose.clear()
        }
    }

    @Synchronized
    fun cockpitReconciliationCompaction(): JSONObject =
        JSONObject().put("healthy_messages_coalesced_since_process_start",
            healthyReconciliationMessagesCoalesced)
            .put("maximum_success_heartbeat_gap_minutes", 15)
            .put("failure_messages_coalesced", 0)
            .put("disclaimer", "UI Cockpit events only; no financial order or exchange reconciliation data discarded")

    @Synchronized
    fun recordCockpitEvent(stage: String, symbol: String = "", direction: String = "", state: String = "", detail: String = "") {
        val event = JSONObject().put("e", "COCKPIT_" + stage.take(40))
            .put("E", System.currentTimeMillis()).put("symbol", symbol.take(20))
            .put("direction", direction.take(10)).put("state", safeDiagnosticText(state))
            .put("detail", safeDiagnosticText(detail))
        recordRawEvent(event, requireDurable = true)
        // The old code executed an ORDER BY + subquery + DELETE after EVERY
        // Cockpit event, including dozens of campaign status observations.
        // Keep at most 1031 short-lived UI events between trims while avoiding
        // this disk-write spike in signed Futures reconciliation/STOP monitoring.
        // This never deletes non-Cockpit order, fill, or STOP decision receipts.
        cockpitEventsSinceTrim++
        if (cockpitEventsSinceTrim >= 32) {
            // Incident archives are NOT UI timeline rows; including them in
            // the original 1000-row projection evicted real UI context and
            // destroyed forensic incidents just because healthy events arrived.
            writableDatabase.execSQL(COCKPIT_PROJECTION_TRIM_SQL)
            writableDatabase.execSQL(COCKPIT_INCIDENT_TRIM_SQL)
            cockpitEventsSinceTrim = 0
        }
    }

    /** The events table retains every non-COCKPIT decision, with no 1600-char
     * truncation, no bounded UI eviction and no silent failed inserts. */
    @Synchronized
    fun recordStopTrailDecision(
        symbol: String, direction: String, campaignId: String, phase: String,
        evidence: JSONObject, exchangeOutcome: JSONObject? = null
    ): Long {
        // Guard against constant background scans exhausting phone storage.
        // The FIRST refusal and each new closed H1 / reason are still persisted.
        val deferredKey=if(phase=="REVIEW_DEFERRED") "$symbol|$campaignId|$direction" else null
        val deferredFingerprint=deferredKey?.let {
            stopTrailDeferredFingerprint(campaignId,evidence)
        }
        if(deferredKey!=null && lastDeferredStopTrailFingerprint[deferredKey]==deferredFingerprint)
            return 0L // Explicitly coalesced, never a fake successful insert ID.
        val event = stopTrailDecisionAuditEvent(
            symbol, direction, campaignId, phase, evidence, exchangeOutcome)
        val row = ContentValues().apply {
            put("event_type", event.getString("e"))
            put("event_time", event.getLong("E"))
            put("received_at", System.currentTimeMillis())
            put("raw_json", event.toString())
        }
        val eventId=writableDatabase.insertOrThrow("events", null, row)
        if(deferredKey!=null && deferredFingerprint!=null) {
            if(lastDeferredStopTrailFingerprint.size>=64 &&
                !lastDeferredStopTrailFingerprint.containsKey(deferredKey))
                lastDeferredStopTrailFingerprint.clear()
            lastDeferredStopTrailFingerprint[deferredKey]=deferredFingerprint
        }
        return eventId
    }

    /** Read a campaign's full chronological decision chain from durable SQLite,
     * not the capped recentCockpitEvents projection. */
    @Synchronized
    fun recentStopTrailDecisions(campaignId: String, limit: Int = 100): JSONArray {
        require(campaignId.isNotBlank())
        val results = JSONArray()
        readableDatabase.query("events", arrayOf("id", "raw_json"),
            "event_type = ?", arrayOf("WILLIAMS_STOP_TRAIL_DECISION_V1"),
            null, null, "id DESC").use { cursor ->
            while (cursor.moveToNext() && results.length() < limit.coerceIn(1, 2000)) {
                val row = JSONObject(cursor.getString(1))
                if (row.optString("campaign_id") == campaignId)
                    results.put(row.put("audit_event_id", cursor.getLong(0)))
            }
        }
        return results
    }

    /** Export a bounded number of WHOLE recent events; SQLite itself remains
     * authoritative and is not compacted by the diagnostics export limit. */
    @Synchronized
    fun recentStopTrailDecisionEvents(limit: Int = 300): JSONArray {
        val result = JSONArray()
        readableDatabase.query("events", arrayOf("id", "raw_json"),
            "event_type = ?", arrayOf("WILLIAMS_STOP_TRAIL_DECISION_V1"),
            null, null, "id DESC", limit.coerceIn(1, 2000).toString()).use { cursor ->
            while (cursor.moveToNext())
                result.put(JSONObject(cursor.getString(1))
                    .put("audit_event_id", cursor.getLong(0)))
        }
        return result
    }

    @Synchronized
    fun recordCampaignSnapshot(intents: JSONArray) {
        for (item in CockpitDiagnostics.rows(intents)) {
            val id = item.optString("client_id")
            val fields = listOf("campaign_status", "order_status", "exchange_status", "protection_status", "protective_stop", "actual_risk_audit_status")
            val fingerprint = fields.joinToString("|") { item.optString(it) }
            val previouslyObserved = lastCampaignObservations.containsKey(id)
            recordChangedCampaignObservation(lastCampaignObservations,id,fingerprint) {
            recordStopTrailRecovery(item)
            val audit = CockpitDiagnostics.lifecycleAudit(item)
            val lifecycle = campaignLifecycleStatus(item)
            val severity = audit.optString("severity")
            val historicalTerminal = campaignIsHistoricalTerminalForCockpit(item)
            if (!campaignCockpitEventRequired(previouslyObserved,historicalTerminal,severity,id.isNotBlank()))
                return@recordChangedCampaignObservation
            recordCockpitEvent("EXECUTION", item.optString("symbol"), item.optString("direction"),
                lifecycle, "Post-change audit=${severity}; " + fields.joinToString("; ") { "$it=${item.optString(it)}" })
            if (campaignCockpitIncidentRequired(historicalTerminal,severity,item.optString("protection_status"))) {
                // A bounded durable incident keeps the events immediately preceding a failure.
                val incident = recentCockpitEvents(80)
                val values = ContentValues().apply {
                    put("event_type", "COCKPIT_INCIDENT")
                    put("event_time", System.currentTimeMillis())
                    put("received_at", System.currentTimeMillis())
                    put("raw_json", JSONObject().put("e", "COCKPIT_INCIDENT").put("events", incident).toString())
                }
                check(writableDatabase.insert("events", null, values)>0L) { "COCKPIT_INCIDENT_WRITE_FAILED" }
            }
            }
        }
    }

    /** Completion and its unique marker commit together. A failed append is
     * retried on the next observation; restarts cannot duplicate a recovery. */
    private fun recordStopTrailRecovery(owner:JSONObject) {
        val tx=owner.optJSONObject("last_native_stop_replacement") ?: return
        if(tx.optString("phase")!="COMPLETED" || owner.has("native_stop_replacement") ||
            owner.optString("strategy_profile")!=williamsTc2Profile ||
            owner.optString("protection_status")!="PROTECTED") return
        val campaignId=owner.optString("client_id")
        val newId=tx.optString("new_id")
        if(campaignId.isBlank() || newId.isBlank()) return
        val db=writableDatabase
        db.transaction {
            db.query("stop_trail_recovery_receipts",arrayOf("recovery_event_id"),
                "campaign_id=? AND protection_client_id=?",arrayOf(campaignId,newId),
                null,null,null,"1").use { if(it.moveToFirst()) return@transaction }
            val prior=JSONArray()
            db.query("events",arrayOf("id","raw_json"),
                "event_type=? AND instr(raw_json,?)>0 AND instr(raw_json,?)>0",
                arrayOf("WILLIAMS_STOP_TRAIL_DECISION_V1",
                    "\"campaign_id\":"+JSONObject.quote(campaignId),
                    "\"proposed_binance_protection_client_id\":"+JSONObject.quote(newId)),
                null,null,"id ASC").use { cursor ->
                while(cursor.moveToNext()) prior.put(JSONObject(cursor.getString(1))
                    .put("audit_event_id",cursor.getLong(0)))
            }
            val event=williamsStopTrailRecoveryEvent(owner,prior,System.currentTimeMillis()) ?: return@transaction
            val eventId=db.insertOrThrow("events",null,ContentValues().apply {
                put("event_type",event.getString("e"));put("event_time",event.getLong("E"))
                put("received_at",System.currentTimeMillis());put("raw_json",event.toString())
            })
            db.insertOrThrow("stop_trail_recovery_receipts",null,ContentValues().apply {
                put("campaign_id",campaignId);put("protection_client_id",newId)
                put("proposal_event_id",event.getJSONObject("exchange_outcome").getLong("original_proposal_event_id"))
                put("recovery_event_id",eventId)
            })
        }
    }

    @Synchronized
    fun recentCockpitEvents(limit: Int = 100): JSONArray {
        val result = JSONArray()
        readableDatabase.query("events", arrayOf("raw_json", "received_at"),
            "event_type LIKE 'COCKPIT_%' AND event_type != 'COCKPIT_INCIDENT'", null,
            null, null, "id DESC", limit.coerceIn(1, 1000).toString()).use { cursor ->
            while (cursor.moveToNext()) {
                result.put(JSONObject(cursor.getString(0)).put("received_at_ms", cursor.getLong(1)))
            }
        }
        return result
    }


    /** Append facts before any entry mutation; never rewrite an older attempt. */
    @Synchronized
    internal fun recordEntryPassportEvent(event:JSONObject):Long = runCatching {
        require(event.optString("schema")==entryPassportEventSchema)
        require(event.optString("symbol").isNotBlank() && event.optString("client_id").isNotBlank() &&
            event.optString("attempt_id").isNotBlank() && event.optString("direction") in setOf("LONG","SHORT") &&
            event.optString("phase") in setOf("PREPARED","COORDINATOR_RETURNED","EXECUTION_EXCEPTION_UNVERIFIED"))
        val row=ContentValues().apply {
            put("symbol",event.getString("symbol"));put("client_id",event.getString("client_id"))
            put("attempt_id",event.getString("attempt_id"));put("direction",event.getString("direction"))
            put("phase",event.getString("phase"));put("observed_at_ms",event.getLong("observed_at_ms"))
            put("raw_json",event.toString())
        }
        writableDatabase.insertOrThrow("h1_entry_passport_events",null,row)
    }.getOrDefault(0L)

    /** One query serves all approved Futures symbols; no per-symbol polling scan. */
    @Synchronized
    internal fun latestEntryPassportEvents():Map<String,JSONObject> {
        val result=linkedMapOf<String,JSONObject>()
        readableDatabase.rawQuery(latestH1EntryPassportsSql,null).use { c->
            while(c.moveToNext()) result[c.getString(1)]=JSONObject(c.getString(2))
                .put("audit_event_id",c.getLong(0))
        }
        return result
    }

    @Synchronized
    internal fun recentEntryPassportEvents(symbol:String,limit:Int=100):JSONArray {
        val result=JSONArray()
        readableDatabase.query("h1_entry_passport_events",arrayOf("id","raw_json"),"symbol=?",
            arrayOf(symbol),null,null,"id DESC",limit.coerceIn(1,500).toString()).use { c->
            while(c.moveToNext()) result.put(JSONObject(c.getString(1)).put("audit_event_id",c.getLong(0)))
        }
        return result
    }

    @Synchronized
    fun lastCockpitIncident(): JSONObject? = readableDatabase.query("events",
        arrayOf("raw_json", "received_at"),
        "event_type = ?", arrayOf("COCKPIT_INCIDENT"), null, null, "id DESC", "1").use {
        if (!it.moveToFirst()) return@use null
        val storedAt = it.getLong(1)
        // The latest SQLite incident can be hours older than the installed APK.
        // Preserve its forensic contents but NEVER present it as a current
        // signed exchange protection observation or evidence of new errors.
        JSONObject(it.getString(0))
            .put("stored_incident_at_ms", storedAt)
            .put("stored_incident_age_ms", if (storedAt > 0L)
                (System.currentTimeMillis()-storedAt).coerceAtLeast(0L) else JSONObject.NULL)
            .put("source_scope", "HISTORICAL_SQLITE_INCIDENT_NOT_LIVE_BINANCE_PROOF")
    }


    private fun ensurePrivateUserEventInboxSchema(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS private_user_event_inbox(
                event_id INTEGER PRIMARY KEY NOT NULL,
                account_fingerprint TEXT NOT NULL,
                event_key TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'PENDING'
                    CHECK(status IN ('PENDING','PROCESSED')),
                attempt_count INTEGER NOT NULL DEFAULT 0,
                last_error TEXT NOT NULL DEFAULT '',
                received_at_ms INTEGER NOT NULL,
                processed_at_ms INTEGER,
                UNIQUE(account_fingerprint,event_key)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE INDEX IF NOT EXISTS idx_private_user_event_pending
            ON private_user_event_inbox(account_fingerprint,status,event_id)
        """.trimIndent())
    }

    override fun onCreate(db: SQLiteDatabase) {
        h1EntryPassportSchema.forEach(db::execSQL)
        db.execSQL("""
            CREATE TABLE orders(
                client_order_id TEXT PRIMARY KEY,
                symbol TEXT NOT NULL,
                order_id TEXT,
                order_list_id TEXT,
                side TEXT,
                type TEXT,
                status TEXT,
                orig_qty REAL DEFAULT 0,
                executed_qty REAL DEFAULT 0,
                cumulative_quote_qty REAL DEFAULT 0,
                avg_price REAL DEFAULT 0,
                last_event_time INTEGER DEFAULT 0,
                updated_at INTEGER NOT NULL,
                raw_json TEXT NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE executions(
                event_key TEXT PRIMARY KEY,
                symbol TEXT,
                order_id TEXT,
                trade_id TEXT,
                execution_type TEXT,
                order_status TEXT,
                last_qty REAL DEFAULT 0,
                last_price REAL DEFAULT 0,
                commission REAL DEFAULT 0,
                commission_asset TEXT,
                event_time INTEGER DEFAULT 0,
                transaction_time INTEGER DEFAULT 0,
                raw_json TEXT NOT NULL
            )
        """.trimIndent())

        // Future receipts use the same stable trade identity even when
        // their event transaction time changes. Old database copies are
        // handled by the read-before-insert compatibility query above.
        db.execSQL("""
            CREATE INDEX IF NOT EXISTS idx_executions_trade_identity
            ON executions (symbol, order_id, trade_id, execution_type)
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE events(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                event_type TEXT NOT NULL,
                event_time INTEGER DEFAULT 0,
                received_at INTEGER NOT NULL,
                raw_json TEXT NOT NULL
            )
        """.trimIndent())
        stopTrailRecoverySchema.forEach(db::execSQL)
        ensurePrivateUserEventInboxSchema(db)

        db.execSQL("""
            CREATE TABLE rest_calls(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                method TEXT NOT NULL,
                path TEXT NOT NULL,
                status_code INTEGER NOT NULL,
                request_json TEXT,
                response_json TEXT,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE trades(
                trade_id TEXT PRIMARY KEY,
                symbol TEXT NOT NULL,
                side TEXT NOT NULL,
                entry_price REAL NOT NULL,
                exit_price REAL NOT NULL,
                qty REAL NOT NULL,
                notional_usdt REAL NOT NULL,
                gross_pnl REAL NOT NULL,
                net_pnl REAL NOT NULL,
                r_multiple REAL NOT NULL,
                opened_at INTEGER NOT NULL,
                closed_at INTEGER NOT NULL,
                outcome TEXT NOT NULL,
                reason TEXT,
                raw_json TEXT,
                fee_usdt REAL NOT NULL DEFAULT 0,
                fee_known INTEGER NOT NULL DEFAULT 1
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE state_transitions(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                from_state TEXT NOT NULL,
                to_state TEXT NOT NULL,
                reason TEXT,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        // SQLiteOpenHelper already holds the upgrade transaction. Preserve
        // historical order/execution identities and do not overwrite any row.
        if (oldVersion < 8) db.execSQL(PRIVATE_FUTURES_ORDER_SCOPE_V8_SQL)
        db.execSQL("""
            CREATE INDEX IF NOT EXISTS idx_executions_trade_identity
            ON executions (symbol, order_id, trade_id, execution_type)
        """.trimIndent())
        h1EntryPassportSchema.forEach(db::execSQL)
        stopTrailRecoverySchema.forEach(db::execSQL)
        ensurePrivateUserEventInboxSchema(db)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS trades(
                trade_id TEXT PRIMARY KEY,
                symbol TEXT NOT NULL,
                side TEXT NOT NULL,
                entry_price REAL NOT NULL,
                exit_price REAL NOT NULL,
                qty REAL NOT NULL,
                notional_usdt REAL NOT NULL,
                gross_pnl REAL NOT NULL,
                net_pnl REAL NOT NULL,
                r_multiple REAL NOT NULL,
                opened_at INTEGER NOT NULL,
                closed_at INTEGER NOT NULL,
                outcome TEXT NOT NULL,
                reason TEXT,
                raw_json TEXT
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS trades(
                trade_id TEXT PRIMARY KEY,
                symbol TEXT NOT NULL,
                side TEXT NOT NULL,
                entry_price REAL NOT NULL,
                exit_price REAL NOT NULL,
                qty REAL NOT NULL,
                notional_usdt REAL NOT NULL,
                gross_pnl REAL NOT NULL,
                net_pnl REAL NOT NULL,
                r_multiple REAL NOT NULL,
                opened_at INTEGER NOT NULL,
                closed_at INTEGER NOT NULL,
                outcome TEXT NOT NULL,
                reason TEXT,
                raw_json TEXT,
                fee_usdt REAL NOT NULL DEFAULT 0,
                fee_known INTEGER NOT NULL DEFAULT 1
            )
        """.trimIndent())

        try {
            db.execSQL("ALTER TABLE trades ADD COLUMN fee_usdt REAL NOT NULL DEFAULT 0")
        } catch (_: Exception) {
        }
        try {
            db.execSQL("ALTER TABLE trades ADD COLUMN fee_known INTEGER NOT NULL DEFAULT 1")
        } catch (_: Exception) {
        }

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS rest_calls(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                method TEXT NOT NULL,
                path TEXT NOT NULL,
                status_code INTEGER NOT NULL,
                request_json TEXT,
                response_json TEXT,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())
    }

    /** Account-free v7/v8 trade/WS journal cannot safely be reinterpreted
     * as another Binance account's history. Until the journal is migrated to
     * an account-scoped schema, prevent credentials rotation if ANY account-
     * bound execution/order/trade row exists. Does not delete old records. */
    @Synchronized
    fun hasUnscopedHistoricalFinancialRows():Boolean =
        readableDatabase.rawQuery(
            "SELECT EXISTS(SELECT 1 FROM executions LIMIT 1) " +
                "OR EXISTS(SELECT 1 FROM orders LIMIT 1) " +
                "OR EXISTS(SELECT 1 FROM trades LIMIT 1)",null
        ).use { it.moveToFirst() && it.getInt(0)!=0 }

    @Synchronized
    fun recordRestCall(
        method: String,
        path: String,
        statusCode: Int,
        requestJson: String?,
        responseJson: String?
    ) {
        val values = ContentValues()
        values.put("method", method)
        values.put("path", path)
        values.put("status_code", statusCode)
        values.put("request_json", requestJson)
        values.put("response_json", responseJson)
        values.put("created_at", System.currentTimeMillis())
        writableDatabase.insert("rest_calls", null, values)
    }

    @Synchronized
    fun recordTrade(
        tradeId: String,
        symbol: String,
        entryPrice: Double,
        exitPrice: Double,
        qty: Double,
        notionalUsdt: Double,
        grossPnl: Double,
        netPnl: Double,
        rMultiple: Double,
        openedAt: Long,
        closedAt: Long,
        outcome: String,
        reason: String,
        rawJson: String?,
        feeUsdt: Double = 0.0,
        feeKnown: Boolean = true,
        side: String = "BUY"
    ) {
        require(side == "BUY" || side == "SELL") { "Trade entry side must be BUY (LONG) or SELL (SHORT)" }
        val values = ContentValues()
        values.put("trade_id", tradeId)
        values.put("symbol", symbol)
        values.put("side", side)
        values.put("entry_price", entryPrice)
        values.put("exit_price", exitPrice)
        values.put("qty", qty)
        values.put("notional_usdt", notionalUsdt)
        values.put("gross_pnl", grossPnl)
        values.put("net_pnl", netPnl)
        values.put("r_multiple", rMultiple)
        values.put("opened_at", openedAt)
        values.put("closed_at", closedAt)
        values.put("outcome", outcome)
        values.put("reason", reason)
        values.put("raw_json", rawJson)
        values.put("fee_usdt", feeUsdt)
        values.put("fee_known", if (feeKnown) 1 else 0)
        writableDatabase.insertWithOnConflict(
            "trades",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    @Synchronized
    fun estimateFeesUsdt(
        symbol: String,
        openedAt: Long,
        closedAt: Long,
        exitPrice: Double
    ): Pair<Double, Boolean> {
        val ledger=FuturesTradeFeeLedger(
            if(symbol.endsWith("USDT")) symbol.removeSuffix("USDT") else "",
            exitPrice
        )
        readableDatabase.query(
            "executions",
            arrayOf(
                "order_id", "trade_id", "execution_type",
                "commission", "commission_asset", "last_price", "raw_json"
            ),
            "symbol=? AND transaction_time>=? AND transaction_time<=?",
            arrayOf(symbol, openedAt.toString(), closedAt.toString()),
            null, null, "transaction_time ASC"
        ).use { cursor ->
            while(cursor.moveToNext()) {
                ledger.add(
                    orderId=cursor.getString(0).orEmpty(),
                    tradeId=cursor.getString(1).orEmpty(),
                    executionType=cursor.getString(2).orEmpty(),
                    commission=if(cursor.isNull(3)) Double.NaN else cursor.getDouble(3),
                    commissionAsset=cursor.getString(4).orEmpty(),
                    fillPrice=if(cursor.isNull(5)) Double.NaN else cursor.getDouble(5),
                    signedRawEvidenceValid=futuresTradeFeeHasCompleteRawEvidence(
                        if(cursor.isNull(6)) null else cursor.getString(6),
                        symbol,cursor.getString(0).orEmpty(),cursor.getString(1).orEmpty()
                    )
                )
            }
        }
        return ledger.result()
    }

    /**
     * Read-only, bounded private Binance ALGO_UPDATE receipts for a campaign
     * close. A page touching the limit is incomplete evidence, not a proof.
     * Financial/STOP event retention is unchanged.
     */
    @Synchronized
    fun recentPrivateAlgoExitEvents(
        symbol: String, closedAtMs: Long, lookbackMs: Long = 300_000L
    ): JSONArray? {
        if (symbol.isBlank() || closedAtMs <= 0L || lookbackMs !in 1L..3_600_000L)
            return null
        val rows = JSONArray()
        val lower = (closedAtMs - lookbackMs).coerceAtLeast(0L)
        readableDatabase.query("events", arrayOf("raw_json"),
            "event_type = ? AND event_time >= ? AND event_time <= ?",
            arrayOf("ALGO_UPDATE", lower.toString(), (closedAtMs + 30_000L).toString()),
            null, null, "id DESC", "501").use { cursor ->
            while (cursor.moveToNext()) {
                val event = JSONObject(cursor.getString(0))
                if (event.optJSONObject("o")?.optString("s") == symbol) rows.put(event)
                // A page may truncate other symbols. Do not claim complete proof
                // from any potentially truncated source evidence.
                if (cursor.position >= 500) return null
            }
        }
        return rows
    }

    @Synchronized
    fun executionEvents(orderId: String, symbol: String): List<JSONObject> {
        require(orderId.isNotBlank() && symbol.isNotBlank()) {
            "EXECUTION_HISTORY_REQUIRES_SYMBOL_AND_ORDER_ID"
        }
        val rows = mutableListOf<JSONObject>()
        readableDatabase.query(
            "executions",
            arrayOf("raw_json"),
            "order_id=? AND symbol=?",
            arrayOf(orderId, symbol),
            null,
            null,
            "transaction_time ASC, event_time ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                runCatching {
                    rows.add(JSONObject(cursor.getString(0)))
                }
            }
        }
        return rows
    }

    @Synchronized
    fun recordState(from: TradingState, to: TradingState, reason: String) {
        val values = ContentValues()
        values.put("from_state", from.name)
        values.put("to_state", to.name)
        values.put("reason", reason)
        values.put("created_at", System.currentTimeMillis())
        writableDatabase.insert("state_transitions", null, values)
    }

    /** Both callers hold this store's monitor. A cockpit append must succeed before dedup. */
    private fun recordRawEvent(event: JSONObject, requireDurable: Boolean) {
        val values = ContentValues()
        values.put("event_type", event.optString("e", "unknown"))
        values.put("event_time", event.optLong("E", 0L))
        values.put("received_at", System.currentTimeMillis())
        values.put("raw_json", event.toString())
        if (requireDurable) writableDatabase.insertOrThrow("events", null, values)
        else writableDatabase.insert("events", null, values)
    }


    /**
     * Persist a private Binance event and its pending-delivery receipt in one
     * SQLite transaction before any in-memory actor work is queued.
     * Exact duplicate payloads are idempotent per API-key fingerprint.
     */
    @Synchronized
    fun enqueuePrivateUserEvent(event: JSONObject, accountFingerprint: String): Long {
        require(accountFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "PRIVATE_EVENT_ACCOUNT_IDENTITY_INVALID"
        }
        val raw = event.toString()
        val eventKey = privateUserEventFingerprint(raw)
        val db = writableDatabase
        var resultId = 0L
        db.transaction {
            val existing = db.rawQuery(
                "SELECT event_id FROM private_user_event_inbox WHERE account_fingerprint=? AND event_key=? LIMIT 1",
                arrayOf(accountFingerprint, eventKey)
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
            if (existing > 0L) {
                resultId = existing
            } else {
                val receivedAt = System.currentTimeMillis()
                val eventValues = ContentValues().apply {
                    put("event_type", event.optString("e", "unknown"))
                    put("event_time", event.optLong("E", 0L))
                    put("received_at", receivedAt)
                    put("raw_json", raw)
                }
                val eventId = db.insertOrThrow("events", null, eventValues)
                val inboxValues = ContentValues().apply {
                    put("event_id", eventId)
                    put("account_fingerprint", accountFingerprint)
                    put("event_key", eventKey)
                    put("status", "PENDING")
                    put("received_at_ms", receivedAt)
                }
                db.insertOrThrow("private_user_event_inbox", null, inboxValues)
                resultId = eventId
            }
        }
        check(resultId > 0L) { "PRIVATE_EVENT_INBOX_PERSISTENCE_FAILED" }
        return resultId
    }

    /** Oldest-first replay preserves callback receipt order across process restarts. */
    @Synchronized
    internal fun pendingPrivateUserEvents(
        accountFingerprint: String,
        limit: Int = 64
    ): List<DurablePrivateUserEvent> {
        require(accountFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(limit in 1..512)
        val rows = mutableListOf<DurablePrivateUserEvent>()
        readableDatabase.rawQuery(
            """
            SELECT e.id,e.raw_json
            FROM private_user_event_inbox i
            JOIN events e ON e.id=i.event_id
            WHERE i.account_fingerprint=? AND i.status='PENDING'
            ORDER BY i.event_id ASC LIMIT ?
            """.trimIndent(),
            arrayOf(accountFingerprint, limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += DurablePrivateUserEvent(cursor.getLong(0), cursor.getString(1))
            }
        }
        return rows
    }

    /** Acknowledgement is durable and is written only after event handling succeeds. */
    @Synchronized
    fun completePrivateUserEvent(eventId: Long, accountFingerprint: String) {
        require(eventId > 0L && accountFingerprint.matches(Regex("[0-9a-f]{64}")))
        val db = writableDatabase
        db.transaction {
            val changed = db.update(
                "private_user_event_inbox",
                ContentValues().apply {
                    put("status", "PROCESSED")
                    put("processed_at_ms", System.currentTimeMillis())
                    put("last_error", "")
                },
                "event_id=? AND account_fingerprint=? AND status='PENDING'",
                arrayOf(eventId.toString(), accountFingerprint)
            )
            if (changed == 0) {
                val alreadyProcessed = db.rawQuery(
                    "SELECT 1 FROM private_user_event_inbox WHERE event_id=? AND account_fingerprint=? AND status='PROCESSED'",
                    arrayOf(eventId.toString(), accountFingerprint)
                ).use { it.moveToFirst() }
                check(alreadyProcessed) { "PRIVATE_EVENT_ACK_IDENTITY_OR_ROW_MISMATCH" }
            }
        }
    }

    /** A failure increments diagnostics but never removes or acknowledges the event. */
    @Synchronized
    fun failPrivateUserEvent(eventId: Long, accountFingerprint: String, reason: String) {
        require(eventId > 0L && accountFingerprint.matches(Regex("[0-9a-f]{64}")))
        writableDatabase.execSQL(
            "UPDATE private_user_event_inbox SET attempt_count=attempt_count+1,last_error=? WHERE event_id=? AND account_fingerprint=? AND status='PENDING'",
            arrayOf<Any>(safeDiagnosticText(reason).take(500), eventId, accountFingerprint)
        )
        val stillExists = readableDatabase.rawQuery(
            "SELECT 1 FROM private_user_event_inbox WHERE event_id=? AND account_fingerprint=?",
            arrayOf(eventId.toString(), accountFingerprint)
        ).use { it.moveToFirst() }
        check(stillExists) { "PRIVATE_EVENT_FAILURE_IDENTITY_OR_ROW_MISMATCH" }
    }

    @Synchronized
    fun privateUserEventInboxSnapshot(accountFingerprint: String): JSONObject {
        require(accountFingerprint.matches(Regex("[0-9a-f]{64}")))
        val db = readableDatabase
        fun count(sql: String, args: Array<String>): Long =
            db.rawQuery(sql, args).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        val pending = count(
            "SELECT COUNT(*) FROM private_user_event_inbox WHERE account_fingerprint=? AND status='PENDING'",
            arrayOf(accountFingerprint))
        val processed = count(
            "SELECT COUNT(*) FROM private_user_event_inbox WHERE account_fingerprint=? AND status='PROCESSED'",
            arrayOf(accountFingerprint))
        val otherPending = count(
            "SELECT COUNT(*) FROM private_user_event_inbox WHERE account_fingerprint<>? AND status='PENDING'",
            arrayOf(accountFingerprint))
        val oldest = db.rawQuery(
            "SELECT MIN(received_at_ms) FROM private_user_event_inbox WHERE account_fingerprint=? AND status='PENDING'",
            arrayOf(accountFingerprint)
        ).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L }
        val attempts = count(
            "SELECT COALESCE(SUM(attempt_count),0) FROM private_user_event_inbox WHERE account_fingerprint=? AND status='PENDING'",
            arrayOf(accountFingerprint))
        return JSONObject()
            .put("schema", "PRIVATE_USER_EVENT_INBOX_V1")
            .put("account_fingerprint_present", true)
            .put("pending_count", pending)
            .put("processed_count", processed)
            .put("other_account_pending_count", otherPending)
            .put("oldest_pending_received_at_ms", if (oldest > 0L) oldest else JSONObject.NULL)
            .put("oldest_pending_age_ms", if (oldest > 0L) (System.currentTimeMillis() - oldest).coerceAtLeast(0L) else JSONObject.NULL)
            .put("pending_retry_attempts", attempts)
            .put("delivery_contract", "DURABLE_AT_LEAST_ONCE_UNTIL_HANDLER_ACK")
            .put("financial_truth", "SIGNED_BINANCE_RECONCILIATION_ONLY")
    }

    @Synchronized
    fun hasPendingPrivateUserEvents(accountFingerprint: String): Boolean =
        pendingPrivateUserEvents(accountFingerprint, 1).isNotEmpty()

    @Synchronized
    fun recordUserEvent(event: JSONObject, rawEventAlreadyPersisted: Boolean = false) {
        val eventType = event.optString("e", "unknown")
        val eventTime = event.optLong("E", 0L)
        // The inbox already inserted the raw event atomically with its PENDING
        // receipt. Other callers retain the historical best-effort insert path.
        if (!rawEventAlreadyPersisted) recordRawEvent(event, requireDurable = false)

        // Binance USD-M Futures sends ORDER_TRADE_UPDATE with all execution fields
        // nested in "o"; Spot-style executionReport fields live at the root. Normalize
        // both shapes before writing the same durable execution/order tables.
        if (eventType != "executionReport" && eventType != "ORDER_TRADE_UPDATE") return
        val payload = normalizedExecutionPayload(event) ?: return
        val executionEventTime = payload.optLong("E", eventTime)
        val transactionTime = payload.optLong("T", executionEventTime)

        val symbol = payload.optString("s")
        val orderId = payload.optString("i")
        if (symbol.isBlank() || orderId.isBlank()) return
        val clientId = payload.optString("c")
        val scopedOrderKey = privateFuturesOrderProjectionKey(symbol,clientId,orderId)
        val tradeId = payload.optString("t", "-1")
        val executionType = payload.optString("x")
        val status = payload.optString("X")
        val eventKey = listOf(
            symbol,
            orderId,
            tradeId,
            executionType,
            transactionTime.toString()
        ).joinToString(":")

        val execution = ContentValues()
        execution.put("event_key", eventKey)
        execution.put("symbol", symbol)
        execution.put("order_id", orderId)
        execution.put("trade_id", tradeId)
        execution.put("execution_type", executionType)
        execution.put("order_status", status)
        execution.put("last_qty", payload.optString("l").toDoubleOrNull() ?: 0.0)
        execution.put("last_price", payload.optString("L").toDoubleOrNull() ?: 0.0)
        // Preserve UNKNOWN, not a fictitious zero fee, if a signed receipt
        // lacks a finite nonnegative commission. SQLite NULL is recoverable
        // as unverified evidence during replay and historical fee estimation.
        val privateCommission=payload.optString("n").toDoubleOrNull()
        if(privateCommission!=null && privateCommission.isFinite() && privateCommission>=0.0)
            execution.put("commission",privateCommission)
        else execution.putNull("commission")
        execution.put("commission_asset", payload.optString("N"))
        execution.put("event_time", executionEventTime)
        execution.put("transaction_time", transactionTime)
        execution.put("raw_json", event.toString())
        val executionDb = writableDatabase
        // Historical event_key included transaction time. A replayed Binance
        // TRADE with the same symbol/order/trade id but a DIFFERENT T would
        // double-count the financial execution after process restart. Query
        // the old rows as well as new rows; do not destructively migrate
        // existing financial/audit history.
        val alreadyKnownTrade = privateBinanceTradeHasStableId(executionType,tradeId) &&
            executionDb.rawQuery(
                "SELECT 1 FROM executions WHERE symbol=? AND order_id=? AND trade_id=? " +
                    "AND execution_type='TRADE' LIMIT 1",
                arrayOf(symbol,orderId,tradeId)
            ).use { it.moveToFirst() }
        if (!alreadyKnownTrade) {
            val executionInsert = executionDb.insertWithOnConflict(
                "executions", null, execution, SQLiteDatabase.CONFLICT_IGNORE
            )
            if (executionInsert == -1L) {
                val persisted = executionDb.rawQuery(
                    "SELECT 1 FROM executions WHERE event_key=? LIMIT 1", arrayOf(eventKey)
                ).use { it.moveToFirst() }
                check(persisted) { "PRIVATE_EVENT_EXECUTION_JOURNAL_WRITE_FAILED" }
            }
        }

        val order = ContentValues()
        order.put("client_order_id", scopedOrderKey)
        order.put("symbol", symbol)
        order.put("order_id", orderId)
        order.put("order_list_id", payload.optString("g"))
        order.put("side", payload.optString("S"))
        order.put("type", payload.optString("o"))
        order.put("status", status)
        order.put("orig_qty", payload.optString("q").toDoubleOrNull() ?: 0.0)
        order.put("executed_qty", payload.optString("z").toDoubleOrNull() ?: 0.0)
        val quote = payload.optString("Z").toDoubleOrNull() ?: 0.0
        order.put("cumulative_quote_qty", quote)
        val executed = payload.optString("z").toDoubleOrNull() ?: 0.0
        order.put("avg_price", if (executed > 0.0) quote / executed else 0.0)
        order.put("last_event_time", executionEventTime)
        order.put("updated_at", System.currentTimeMillis())
        order.put("raw_json", event.toString())
        // An older WS event can arrive while the actor is catching up after
        // restart, including just before the out-of-order REST fence fires.
        // Store its raw receipt + execution, but NEVER replace a newer order
        // projection or regress FILLED/terminal status or executed quantity.
        val persisted = executionDb.query(
            "orders", arrayOf("last_event_time","order_id","executed_qty","status"),
            "client_order_id=?", arrayOf(scopedOrderKey),
            null,null,null,"1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else JSONObject()
                .put("at",cursor.getLong(0))
                .put("order_id",cursor.getString(1).orEmpty())
                .put("qty",cursor.getDouble(2).toString())
                .put("status",cursor.getString(3).orEmpty())
        }
        if (persisted!=null && !privateOrderProjectionMayAdvance(
                persisted.getLong("at"),persisted.getString("order_id"),
                persisted.getString("qty"),persisted.getString("status"),
                executionEventTime,orderId,payload.optString("z"),status
            )) return
        check(executionDb.insertWithOnConflict(
            "orders",
            null,
            order,
            SQLiteDatabase.CONFLICT_REPLACE
        ) != -1L) { "PRIVATE_EVENT_ORDER_JOURNAL_WRITE_FAILED" }
    }
}
