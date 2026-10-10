package com.williamsbot
import java.util.Locale

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/**
 * Persistent exchange/order/execution/state audit trail.
 *
 * The raw event JSON is kept so a later reconciliation can reconstruct what
 * Binance told the bot, without relying on RAM or SharedPreferences.
 */
class TradingAuditStore(context: Context) :
    SQLiteOpenHelper(context, "williams_trading_audit.db", null, 4) {

    override fun onCreate(db: SQLiteDatabase) {
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
        ensureExecutionMutationSchema(db)
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
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
        ensureExecutionMutationSchema(db)
    }

    private fun ensureExecutionMutationSchema(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS execution_intents(
                intent_id TEXT PRIMARY KEY,
                logical_key TEXT NOT NULL UNIQUE,
                client_order_id TEXT UNIQUE,
                target_client_order_id TEXT,
                symbol TEXT NOT NULL,
                operation TEXT NOT NULL,
                method TEXT NOT NULL,
                path TEXT NOT NULL,
                target_order_id TEXT,
                target_order_list_id TEXT,
                request_json TEXT NOT NULL,
                status TEXT NOT NULL,
                http_status INTEGER,
                exchange_order_id TEXT,
                exchange_order_list_id TEXT,
                exchange_status TEXT,
                executed_qty TEXT,
                cumulative_quote_qty TEXT,
                response_json TEXT,
                reason TEXT,
                attempt_count INTEGER NOT NULL DEFAULT 1,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_execution_intents_status_symbol " +
                "ON execution_intents(status, symbol)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_execution_intents_target_order " +
                "ON execution_intents(target_order_id, status)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_execution_intents_target_list " +
                "ON execution_intents(target_order_list_id, status)"
        )
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

    /**
     * Durable write-ahead journal for every signed Binance order mutation.
     * Any exception from this method must prevent the network request.
     */
    @Synchronized
    fun beginExecutionMutation(
        intentId: String,
        logicalKey: String,
        clientOrderId: String?,
        targetClientOrderId: String?,
        symbol: String,
        operation: String,
        method: String,
        path: String,
        targetOrderId: String?,
        targetOrderListId: String?,
        requestJson: String
    ): String {
        require(intentId.isNotBlank() && logicalKey.isNotBlank())
        require(symbol.isNotBlank() && requestJson.isNotBlank())
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            // Admission is serialized inside the same SQLite transaction as
            // INSERT. A preflight COUNT outside this transaction is insufficient:
            // two different CIDs for one symbol could otherwise both observe zero.
            val blocking = db.rawQuery(
                "SELECT intent_id,symbol,operation,status FROM execution_intents " +
                    "WHERE status IN ('PENDING','SUBMITTING','AMBIGUOUS','RECONCILE_REQUIRED') " +
                    "AND symbol=? AND intent_id<>? LIMIT 1",
                arrayOf(symbol, intentId)
            ).use { cursor ->
                if (!cursor.moveToFirst()) null else
                    "intent=" + cursor.getString(0) +
                        " symbol=" + cursor.getString(1) +
                        " operation=" + cursor.getString(2) +
                        " status=" + cursor.getString(3)
            }
            if (blocking != null) {
                throw IllegalStateException(
                    "concurrent/previous mutation blocks this symbol: " + blocking
                )
            }
            if (operation == "ENTRY_ORDER") {
                val globalBlocking = db.rawQuery(
                    "SELECT intent_id,symbol,operation,status FROM execution_intents " +
                        "WHERE status IN ('PENDING','SUBMITTING','AMBIGUOUS','RECONCILE_REQUIRED') " +
                        "AND intent_id<>? LIMIT 1",
                    arrayOf(intentId)
                ).use { cursor ->
                    if (!cursor.moveToFirst()) null else
                        "intent=" + cursor.getString(0) +
                            " symbol=" + cursor.getString(1) +
                            " operation=" + cursor.getString(2) +
                            " status=" + cursor.getString(3)
                }
                if (globalBlocking != null) {
                    throw IllegalStateException(
                        "global unresolved execution mutation blocks new exposure: " + globalBlocking
                    )
                }
            }
            val existing = db.rawQuery(
                "SELECT intent_id,logical_key,client_order_id,symbol,operation,method,path," +
                    "target_order_id,target_order_list_id,request_json,status " +
                    "FROM execution_intents WHERE logical_key=? OR " +
                    "(client_order_id IS NOT NULL AND client_order_id=?)",
                arrayOf(logicalKey, clientOrderId.orEmpty())
            ).use { cursor ->
                if (!cursor.moveToFirst()) null else mapOf(
                    "intent_id" to cursor.getString(0),
                    "logical_key" to cursor.getString(1),
                    "client_order_id" to cursor.getString(2),
                    "symbol" to cursor.getString(3),
                    "operation" to cursor.getString(4),
                    "method" to cursor.getString(5),
                    "path" to cursor.getString(6),
                    "target_order_id" to cursor.getString(7),
                    "target_order_list_id" to cursor.getString(8),
                    "request_json" to cursor.getString(9),
                    "status" to cursor.getString(10)
                )
            }
            if (existing != null) {
                val exactSame = existing["logical_key"] == logicalKey &&
                    existing["client_order_id"].orEmpty() == clientOrderId.orEmpty() &&
                    existing["symbol"] == symbol &&
                    existing["operation"] == operation &&
                    existing["method"] == method &&
                    existing["path"] == path &&
                    existing["target_order_id"].orEmpty() == targetOrderId.orEmpty() &&
                    existing["target_order_list_id"].orEmpty() == targetOrderListId.orEmpty() &&
                    existing["request_json"] == requestJson
                if (!exactSame) {
                    throw IllegalStateException(
                        "execution identity collision; immutable request fields differ"
                    )
                }
                val priorStatus = existing["status"].orEmpty().uppercase()
                val safeToRetry = (method == "POST" && priorStatus == "ABORTED_NOT_FOUND") ||
                    (method == "DELETE" && priorStatus == "RECONCILED_ACTIVE")
                if (!safeToRetry) {
                    throw IllegalStateException(
                        "duplicate/ambiguous execution mutation refused: status=$priorStatus"
                    )
                }
                val reset = ContentValues().apply {
                    put("status", "PENDING")
                    putNull("http_status")
                    putNull("exchange_order_id")
                    putNull("exchange_order_list_id")
                    putNull("exchange_status")
                    putNull("executed_qty")
                    putNull("cumulative_quote_qty")
                    putNull("response_json")
                    putNull("reason")
                    put("attempt_count", 1 + getAttemptCount(db, existing["intent_id"].orEmpty()))
                    put("updated_at", now)
                }
                val reusedId = existing["intent_id"].orEmpty()
                if (reusedId != intentId) {
                    throw IllegalStateException("stable mutation intent ID changed on safe retry")
                }
                val updated = db.update(
                    "execution_intents",
                    reset,
                    "intent_id=?",
                    arrayOf(reusedId)
                )
                if (updated != 1) {
                    throw IllegalStateException("safe retry could not update the existing intent")
                }
                db.setTransactionSuccessful()
            } else {
                val values = ContentValues().apply {
                    put("intent_id", intentId)
                    put("logical_key", logicalKey)
                    if (clientOrderId.isNullOrBlank()) putNull("client_order_id")
                    else put("client_order_id", clientOrderId)
                    if (targetClientOrderId.isNullOrBlank()) putNull("target_client_order_id")
                    else put("target_client_order_id", targetClientOrderId)
                    put("symbol", symbol)
                    put("operation", operation)
                    put("method", method)
                    put("path", path)
                    if (targetOrderId.isNullOrBlank()) putNull("target_order_id")
                    else put("target_order_id", targetOrderId)
                    if (targetOrderListId.isNullOrBlank()) putNull("target_order_list_id")
                    else put("target_order_list_id", targetOrderListId)
                    put("request_json", requestJson)
                    put("status", "PENDING")
                    put("attempt_count", 1)
                    put("created_at", now)
                    put("updated_at", now)
                }
                db.insertOrThrow("execution_intents", null, values)
                db.setTransactionSuccessful()
            }
        } finally {
            db.endTransaction()
        }

        val row = db.rawQuery(
            "SELECT intent_id,logical_key,request_json,status FROM execution_intents WHERE intent_id=?",
            arrayOf(intentId)
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else listOf(
                cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3)
            )
        }
        if (row == null || row[0] != intentId || row[1] != logicalKey ||
            row[2] != requestJson || row[3] != "PENDING"
        ) {
            throw IllegalStateException("execution intent commit/read-back verification failed")
        }
        return intentId
    }

    private fun getAttemptCount(db: SQLiteDatabase, intentId: String): Int =
        db.rawQuery(
            "SELECT attempt_count FROM execution_intents WHERE intent_id=?",
            arrayOf(intentId)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    @Synchronized
    fun markExecutionMutationSubmitting(intentId: String) {
        val values = ContentValues().apply {
            put("status", "SUBMITTING")
            put("updated_at", System.currentTimeMillis())
        }
        val updated = writableDatabase.update(
            "execution_intents",
            values,
            "intent_id=? AND status='PENDING'",
            arrayOf(intentId)
        )
        if (updated != 1) {
            throw IllegalStateException("cannot durably advance execution intent to SUBMITTING")
        }
        val persisted = writableDatabase.rawQuery(
            "SELECT status FROM execution_intents WHERE intent_id=?",
            arrayOf(intentId)
        ).use { cursor -> cursor.moveToFirst() && cursor.getString(0) == "SUBMITTING" }
        if (!persisted) {
            throw IllegalStateException("SUBMITTING commit/read-back verification failed")
        }
    }

    @Synchronized
    fun recordExecutionMutationResult(
        intentId: String,
        httpStatus: Int,
        finalStatus: String,
        responseJson: String,
        reason: String = ""
    ) {
        require(finalStatus in setOf("SUBMITTED", "AMBIGUOUS", "RECONCILE_REQUIRED", "REJECTED"))
        val response = runCatching { JSONObject(responseJson) }.getOrNull()
        val orderStatus = response?.optString("status").orEmpty().ifBlank {
            response?.optString("listStatusType").orEmpty()
        }
        val values = ContentValues().apply {
            put("status", finalStatus)
            put("http_status", httpStatus)
            put("exchange_order_id", response?.optString("orderId")?.takeIf { it.isNotBlank() })
            put("exchange_order_list_id", response?.optString("orderListId")?.takeIf { it.isNotBlank() })
            put("exchange_status", orderStatus)
            put("executed_qty", response?.optString("executedQty")?.takeIf { it.isNotBlank() })
            put("cumulative_quote_qty", response?.optString("cummulativeQuoteQty")?.takeIf { it.isNotBlank() })
            put("response_json", responseJson)
            put("reason", reason)
            put("updated_at", System.currentTimeMillis())
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val updated = db.update(
                "execution_intents",
                values,
                "intent_id=? AND status IN ('PENDING','SUBMITTING','AMBIGUOUS','RECONCILE_REQUIRED')",
                arrayOf(intentId)
            )
            if (updated != 1) {
                throw IllegalStateException("exchange result has no matching unresolved execution intent")
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        val row = db.rawQuery(
            "SELECT status,http_status,response_json,exchange_status FROM execution_intents WHERE intent_id=?",
            arrayOf(intentId)
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else listOf(
                cursor.getString(0),
                cursor.getInt(1).toString(),
                cursor.getString(2),
                cursor.getString(3).orEmpty()
            )
        }
        if (row == null || row[0] != finalStatus ||
            row[1] != httpStatus.toString() || row[2] != responseJson ||
            row[3] != orderStatus
        ) {
            throw IllegalStateException("exchange result commit/read-back verification failed")
        }
    }

    @Synchronized
    fun markExecutionMutationAmbiguous(intentId: String, reason: String) {
        val values = ContentValues().apply {
            put("status", "AMBIGUOUS")
            put("reason", reason)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update(
            "execution_intents",
            values,
            "intent_id=? AND status IN ('PENDING','SUBMITTING','SUBMITTED','REJECTED','RECONCILE_REQUIRED')",
            arrayOf(intentId)
        )
        val persisted = writableDatabase.rawQuery(
            "SELECT status FROM execution_intents WHERE intent_id=?",
            arrayOf(intentId)
        ).use { cursor -> cursor.moveToFirst() && cursor.getString(0) == "AMBIGUOUS" }
        if (!persisted) {
            throw IllegalStateException("execution mutation could not be durably marked AMBIGUOUS")
        }
    }

    @Synchronized
    fun reconcileExecutionMutationByClientOrderId(
        clientOrderId: String,
        exchangeStatus: String,
        responseJson: String?,
        notFound: Boolean = false
    ) {
        if (clientOrderId.isBlank()) return
        val observed = runCatching {
            responseJson?.let { JSONObject(it) }
        }.getOrNull()
        val reports = observed?.optJSONArray("orderReports")
        var reportHasFill = false
        var reportStatesKnown = reports != null && reports.length() > 0
        if (reports != null) {
            for (i in 0 until reports.length()) {
                val leg = reports.optJSONObject(i)
                if (leg == null) {
                    reportStatesKnown = false
                    continue
                }
                val legStatus = leg.optString("status").uppercase(Locale.US)
                val legQty = leg.optString("executedQty").toDoubleOrNull()
                if (legQty == null || legQty < 0.0 ||
                    legStatus in setOf("PARTIALLY_FILLED", "FILLED") ||
                    (legQty > 0.0)
                ) {
                    reportHasFill = true
                }
                if (legStatus !in setOf(
                        "NEW", "PENDING_NEW", "FILLED", "PARTIALLY_FILLED",
                        "CANCELED", "CANCELLED", "EXPIRED", "REJECTED"
                    )
                ) reportStatesKnown = false
            }
        }
        val executed = observed?.optString("executedQty")?.toDoubleOrNull() ?: 0.0
        val normalizedExchangeStatus = exchangeStatus.uppercase(Locale.US)
        val orderKnownZeroFill = normalizedExchangeStatus in setOf(
            "NEW", "PENDING_NEW", "CANCELED", "CANCELLED", "EXPIRED", "REJECTED"
        ) && executed <= 0.0
        val ocoKnownNoFill = reports != null && reportStatesKnown && !reportHasFill &&
            (0 until reports.length()).all { index ->
                val state = reports.optJSONObject(index)?.optString("status").orEmpty().uppercase(Locale.US)
                state in setOf("NEW", "PENDING_NEW", "CANCELED", "CANCELLED", "EXPIRED", "REJECTED")
            }
        val status = when {
            notFound -> "ABORTED_NOT_FOUND"
            reportHasFill || executed > 0.0 ||
                normalizedExchangeStatus in setOf("PARTIALLY_FILLED", "FILLED") ->
                "RECONCILE_REQUIRED"
            orderKnownZeroFill || ocoKnownNoFill -> "RECONCILED"
            else -> "RECONCILE_REQUIRED"
        }
        val values = ContentValues().apply {
            put("status", status)
            put("exchange_status", exchangeStatus)
            put("response_json", responseJson)
            put("reason", when {
                notFound -> "authoritative Binance -2013: client order not found"
                status == "RECONCILE_REQUIRED" -> "authoritative GET found an order that requires fill/position accounting"
                else -> "authoritative GET by clientOrderId confirmed a known zero-fill state"
            })
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update(
            "execution_intents",
            values,
            "client_order_id=? AND status IN ('PENDING','SUBMITTING','AMBIGUOUS','RECONCILE_REQUIRED')",
            arrayOf(clientOrderId)
        )
    }

    @Synchronized
    @Synchronized
    fun reconcileExecutionMutationByTargetClientOrderId(
        targetClientOrderId: String,
        exchangeStatus: String,
        executedQty: Double,
        responseJson: String?
    ) {
        if (targetClientOrderId.isBlank()) return
        val normalized = exchangeStatus.uppercase(Locale.US)
        val status = when {
            executedQty < 0.0 || executedQty > 0.0 ||
                normalized in setOf("PARTIALLY_FILLED", "FILLED") -> "RECONCILE_REQUIRED"
            normalized in setOf("NEW", "PENDING_NEW") -> "RECONCILED_ACTIVE"
            normalized in setOf("CANCELED", "CANCELLED", "EXPIRED", "REJECTED") -> "RECONCILED"
            else -> "RECONCILE_REQUIRED"
        }
        val values = ContentValues().apply {
            put("status", status)
            put("exchange_status", exchangeStatus)
            put("executed_qty", executedQty.toString())
            put("response_json", responseJson)
            put("reason", "authoritative Binance GET by target origClientOrderId")
            put("updated_at", System.currentTimeMillis())
        }
        val updated = writableDatabase.update(
            "execution_intents",
            values,
            "target_client_order_id=? AND method='DELETE' " +
                "AND status IN ('PENDING','SUBMITTING','AMBIGUOUS','RECONCILE_REQUIRED')",
            arrayOf(targetClientOrderId)
        )
        if (updated == 0) return
        val persisted = writableDatabase.rawQuery(
            "SELECT COUNT(*) FROM execution_intents WHERE target_client_order_id=? AND method='DELETE' AND status=?",
            arrayOf(targetClientOrderId, status)
        ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) >= updated }
        if (!persisted) throw IllegalStateException("target clientOrderId reconciliation read-back failed")
    }

    fun reconcileExecutionMutationByTargetOrderId(
        targetOrderId: String,
        exchangeStatus: String,
        executedQty: Double,
        responseJson: String?
    ) {
        if (targetOrderId.isBlank()) return
        val status = when {
            executedQty > 0.0 || exchangeStatus in setOf("PARTIALLY_FILLED", "FILLED") -> "RECONCILE_REQUIRED"
            exchangeStatus in setOf("NEW", "PENDING_NEW") -> "RECONCILED_ACTIVE"
            exchangeStatus in setOf("CANCELED", "CANCELLED", "EXPIRED", "REJECTED") -> "RECONCILED"
            else -> "RECONCILE_REQUIRED"
        }
        val values = ContentValues().apply {
            put("status", status)
            put("exchange_status", exchangeStatus)
            put("executed_qty", executedQty.toString())
            put("response_json", responseJson)
            put("reason", "authoritative GET by target orderId")
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update(
            "execution_intents",
            values,
            "target_order_id=? AND method='DELETE' AND status IN ('PENDING','SUBMITTING','AMBIGUOUS','RECONCILE_REQUIRED')",
            arrayOf(targetOrderId)
        )
    }

    @Synchronized
    fun reconcileExecutionMutationByTargetOrderListId(
        targetOrderListId: String,
        responseJson: String?
    ) {
        if (targetOrderListId.isBlank()) return
        val response = runCatching { responseJson?.let { JSONObject(it) } }.getOrNull()
        val reports = response?.optJSONArray("orderReports")
        val listStatus = response?.optString("listOrderStatus").orEmpty().uppercase(Locale.US)
            .ifBlank { response?.optString("listStatusType").orEmpty().uppercase(Locale.US) }
        var anyFill = false
        var allKnown = reports != null && reports.length() > 0
        var allOpen = allKnown
        var allTerminal = allKnown
        if (reports != null) {
            for (i in 0 until reports.length()) {
                val leg = reports.optJSONObject(i)
                if (leg == null) {
                    allKnown = false
                    allOpen = false
                    allTerminal = false
                    continue
                }
                val state = leg.optString("status").uppercase(Locale.US)
                val qty = leg.optString("executedQty").toDoubleOrNull()
                if (qty == null || qty < 0.0 ||
                    state in setOf("PARTIALLY_FILLED", "FILLED") || qty > 0.0
                ) anyFill = true
                if (state !in setOf(
                        "NEW", "PENDING_NEW", "FILLED", "PARTIALLY_FILLED",
                        "CANCELED", "CANCELLED", "EXPIRED", "REJECTED"
                    )
                ) allKnown = false
                if (state !in setOf("NEW", "PENDING_NEW") || qty != 0.0) allOpen = false
                if (state !in setOf("CANCELED", "CANCELLED", "EXPIRED", "REJECTED") || qty != 0.0) allTerminal = false
            }
        }
        val status = when {
            anyFill || !allKnown -> "RECONCILE_REQUIRED"
            allOpen -> "RECONCILED_ACTIVE"
            allTerminal -> "RECONCILED"
            listStatus in setOf("EXEC_STARTED", "EXECUTING") -> "RECONCILE_REQUIRED"
            else -> "RECONCILE_REQUIRED"
        }
        val db = writableDatabase
        val values = ContentValues().apply {
            put("status", status)
            put("exchange_status", listStatus)
            put("response_json", responseJson)
            put("reason", "authoritative Binance GET by orderListId; legs=" + (reports?.length() ?: 0))
            put("updated_at", System.currentTimeMillis())
        }
        val updated = db.update(
            "execution_intents",
            values,
            "target_order_list_id=? AND method='DELETE' AND status IN ('PENDING','SUBMITTING','AMBIGUOUS','RECONCILE_REQUIRED')",
            arrayOf(targetOrderListId)
        )
        if (updated == 0) return
        val reread = db.rawQuery(
            "SELECT COUNT(*) FROM execution_intents WHERE target_order_list_id=? AND method='DELETE' AND status=?",
            arrayOf(targetOrderListId, status)
        ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) >= updated }
        if (!reread) throw IllegalStateException("order-list reconciliation read-back failed")
    }

    @Synchronized
    fun unresolvedExecutionMutationCount(symbol: String? = null): Int {
        val where = if (symbol.isNullOrBlank()) {
            "status IN ('PENDING','SUBMITTING','AMBIGUOUS','RECONCILE_REQUIRED')"
        } else {
            "status IN ('PENDING','SUBMITTING','AMBIGUOUS','RECONCILE_REQUIRED') AND symbol=?"
        }
        val args = if (symbol.isNullOrBlank()) emptyArray() else arrayOf(symbol)
        return writableDatabase.rawQuery(
            "SELECT COUNT(*) FROM execution_intents WHERE $where",
            args
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
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
        feeKnown: Boolean = true
    ) {
        val values = ContentValues()
        values.put("trade_id", tradeId)
        values.put("symbol", symbol)
        values.put("side", "BUY")
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

    @Synchronized
    fun recordUserEvent(event: JSONObject) {
        val eventType = event.optString("e", "unknown")
        val eventTime = event.optLong("E", 0L)
        val values = ContentValues()
        values.put("event_type", eventType)
        values.put("event_time", eventTime)
        values.put("received_at", System.currentTimeMillis())
        values.put("raw_json", event.toString())
        writableDatabase.insert("events", null, values)

        if (eventType != "executionReport") return

        val symbol = event.optString("s")
        val orderId = event.optString("i")
        val clientId = event.optString("c")
        val tradeId = event.optString("t", "-1")
        val executionType = event.optString("x")
        val status = event.optString("X")
        val eventKey = listOf(
            symbol,
            orderId,
            tradeId,
            executionType,
            eventTime.toString()
        ).joinToString(":")

        val execution = ContentValues()
        execution.put("event_key", eventKey)
        execution.put("symbol", symbol)
        execution.put("order_id", orderId)
        execution.put("trade_id", tradeId)
        execution.put("execution_type", executionType)
        execution.put("order_status", status)
        execution.put("last_qty", event.optString("l").toDoubleOrNull() ?: 0.0)
        execution.put("last_price", event.optString("L").toDoubleOrNull() ?: 0.0)
        execution.put("commission", event.optString("n").toDoubleOrNull() ?: 0.0)
        execution.put("commission_asset", event.optString("N"))
        execution.put("event_time", eventTime)
        execution.put("transaction_time", event.optLong("T", 0L))
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
        order.put("order_list_id", event.optString("g"))
        order.put("side", event.optString("S"))
        order.put("type", event.optString("o"))
        order.put("status", status)
        order.put("orig_qty", event.optString("q").toDoubleOrNull() ?: 0.0)
        order.put("executed_qty", event.optString("z").toDoubleOrNull() ?: 0.0)
        val quote = event.optString("Z").toDoubleOrNull() ?: 0.0
        order.put("cumulative_quote_qty", quote)
        val executed = event.optString("z").toDoubleOrNull() ?: 0.0
        order.put("avg_price", if (executed > 0.0) quote / executed else 0.0)
        order.put("last_event_time", eventTime)
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
