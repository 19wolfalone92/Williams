package com.williamsbot

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import org.json.JSONArray

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
        "EXCHANGE_RESPONSE_OK", "EXCHANGE_NOT_CONFIRMED", "EXCHANGE_EXCEPTION_UNVERIFIED"))
    require(evidence.optString("schema") == "WILLIAMS_TC2_STOP_REVIEW_V1")
    require(evidence.optString("direction") == direction)
    if (phase == "REVIEW_DEFERRED")
        require(evidence.optString("decision") == "DEFER" &&
            evidence.optString("blocker").isNotBlank()) {
            "Deferred audit must preserve its actual refusal reason"
        }
    if (phase == "PROPOSED_BEFORE_MUTATION" || phase == "EXCHANGE_RESPONSE_OK" ||
        phase == "EXCHANGE_NOT_CONFIRMED" || phase == "EXCHANGE_EXCEPTION_UNVERIFIED") {
        require(evidence.optString("decision") == "PROPOSE_TIGHTEN_ONLY" &&
            evidence.optString("proposed_binance_protection_client_id").isNotBlank()) {
            "Stop mutation audit requires a traceable actual proposal and client ID"
        }
    }
    if (phase == "EXCHANGE_RESPONSE_OK" || phase == "EXCHANGE_NOT_CONFIRMED") {
        require(exchangeOutcome != null && exchangeOutcome.optBoolean("ok") ==
            (phase == "EXCHANGE_RESPONSE_OK")) {
            "Exchange status must agree with the signed coordinator response"
        }
    }
    if (phase == "EXCHANGE_RESPONSE_OK") {
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

/**
 * Persistent exchange/order/execution/state audit trail.
 *
 * The raw event JSON is kept so a later reconciliation can reconstruct what
 * Binance told the bot, without relying on RAM or SharedPreferences.
 */
class TradingAuditStore(context: Context) :
    SQLiteOpenHelper(context, "williams_trading_audit.db", null, 5) {

    private val lastCampaignObservations = mutableMapOf<String, String>()
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
        writableDatabase.execSQL("DELETE FROM events WHERE event_type LIKE 'COCKPIT_%' AND id NOT IN (SELECT id FROM events WHERE event_type LIKE 'COCKPIT_%' ORDER BY id DESC LIMIT 1000)")
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
            recordChangedCampaignObservation(lastCampaignObservations,id,fingerprint) {
            val audit = CockpitDiagnostics.lifecycleAudit(item)
            recordCockpitEvent("EXECUTION", item.optString("symbol"), item.optString("direction"),
                campaignLifecycleStatus(item), "Post-change audit=${audit.optString("severity")}; " + fields.joinToString("; ") { "$it=${item.optString(it)}" })
            if (audit.optString("severity") == "FAIL" ||
                item.optString("protection_status") in setOf("SUBMISSION_UNKNOWN", "UNRESOLVED_EXTRA_ORDER")) {
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

    /** One query serves all ten symbols; no per-symbol polling scan. */
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
    fun lastCockpitIncident(): JSONObject? = readableDatabase.query("events", arrayOf("raw_json"),
        "event_type = ?", arrayOf("COCKPIT_INCIDENT"), null, null, "id DESC", "1").use {
        if (it.moveToFirst()) JSONObject(it.getString(0)) else null
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

        db.execSQL("""
            CREATE TABLE events(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                event_type TEXT NOT NULL,
                event_time INTEGER DEFAULT 0,
                received_at INTEGER NOT NULL,
                raw_json TEXT NOT NULL
            )
        """.trimIndent())

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
        h1EntryPassportSchema.forEach(db::execSQL)
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
        val baseAsset = symbol.removeSuffix("USDT")
        var feeUsdt = 0.0
        var known = true

        writableDatabase.query(
            "executions",
            arrayOf(
                "commission",
                "commission_asset",
                "last_price",
                "transaction_time"
            ),
            "symbol=? AND transaction_time>=? AND transaction_time<=?",
            arrayOf(
                symbol,
                openedAt.toString(),
                closedAt.toString()
            ),
            null,
            null,
            "transaction_time ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val commission = cursor.getDouble(0)
                if (commission <= 0.0) continue
                val asset = cursor.getString(1) ?: ""
                val price = cursor.getDouble(2).takeIf { it > 0.0 } ?: exitPrice
                when (asset) {
                    "USDT" -> feeUsdt += commission
                    baseAsset -> feeUsdt += commission * price
                    "" -> Unit
                    else -> known = false
                }
            }
        }

        return feeUsdt to known
    }

    @Synchronized
    fun executionEvents(orderId: String): List<JSONObject> {
        val rows = mutableListOf<JSONObject>()
        writableDatabase.query(
            "executions",
            arrayOf("raw_json"),
            "order_id=?",
            arrayOf(orderId),
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

    @Synchronized
    fun recordUserEvent(event: JSONObject) {
        val eventType = event.optString("e", "unknown")
        val eventTime = event.optLong("E", 0L)
        // Keep the raw User Stream handler's existing best-effort insert contract.
        // Strict durability is scoped to campaign observations before caching them.
        recordRawEvent(event, requireDurable = false)

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
        execution.put("commission", payload.optString("n").toDoubleOrNull() ?: 0.0)
        execution.put("commission_asset", payload.optString("N"))
        execution.put("event_time", executionEventTime)
        execution.put("transaction_time", transactionTime)
        execution.put("raw_json", event.toString())
        writableDatabase.insertWithOnConflict(
            "executions",
            null,
            execution,
            SQLiteDatabase.CONFLICT_IGNORE
        )

        val order = ContentValues()
        order.put("client_order_id", clientId.ifBlank { orderId })
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
        writableDatabase.insertWithOnConflict(
            "orders",
            null,
            order,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }
}
