package com.williamsbot

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import org.json.JSONObject

/**
 * Persistent exchange/order/execution/state audit trail.
 *
 * The raw event JSON is kept so a later reconciliation can reconstruct what
 * Binance told the bot, without relying on RAM or SharedPreferences.
 */
class TradingAuditStore(context: Context) :
    SQLiteOpenHelper(context, "williams_trading_audit.db", null, 5) {

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
            CREATE TABLE signal_claims(
                signal_id TEXT PRIMARY KEY,
                canonical_payload TEXT NOT NULL,
                claimed_at INTEGER NOT NULL
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

        db.execSQL("""
            CREATE TABLE mutation_intents(
                mutation_id TEXT PRIMARY KEY,
                method TEXT NOT NULL,
                path TEXT NOT NULL,
                symbol TEXT,
                client_order_id TEXT,
                request_params TEXT,
                state TEXT NOT NULL,
                response_code INTEGER,
                response_json TEXT,
                error TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent())
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        db.execSQL("""
            CREATE TABLE mutation_intents(
                mutation_id TEXT PRIMARY KEY,
                method TEXT NOT NULL,
                path TEXT NOT NULL,
                symbol TEXT,
                client_order_id TEXT,
                request_params TEXT,
                state TEXT NOT NULL,
                response_code INTEGER,
                response_json TEXT,
                error TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
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

        if (oldVersion < 4) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS signal_claims(
                    signal_id TEXT PRIMARY KEY,
                    canonical_payload TEXT NOT NULL,
                    claimed_at INTEGER NOT NULL
                )
            """.trimIndent())
        }
    }

    /**
     * Durable one-time signal claim. Returns CLAIMED, DUPLICATE or CONFLICT.
     * A caller must invoke this before any entry/add-on network mutation.
     * SQLite errors, commit failures and post-commit read-back failures throw.
     */
    @Synchronized
    fun claimSignalOnce(signalId: String, canonicalPayload: String): String {
        require(signalId.isNotBlank()) { "signal_id is required" }
        require(canonicalPayload.isNotBlank()) { "canonical signal payload is required" }
        val db = writableDatabase
        var inserted = false
        var stored: String? = null
        db.transaction {
            val values = ContentValues().apply {
                put("signal_id", signalId)
                put("canonical_payload", canonicalPayload)
                put("claimed_at", System.currentTimeMillis())
            }
            inserted = db.insertWithOnConflict(
                "signal_claims",
                null,
                values,
                SQLiteDatabase.CONFLICT_IGNORE
            ) != -1L
            db.rawQuery(
                "SELECT canonical_payload FROM signal_claims WHERE signal_id=?",
                arrayOf(signalId)
            ).use { cursor ->
                if (!cursor.moveToFirst()) error("signal claim read-before-commit returned no row")
                stored = cursor.getString(0)
            }
        }

        // Explicit post-commit read-back: failure must stop the caller even if
        // the durable claim may already exist.
        var verified: String? = null
        readableDatabase.rawQuery(
            "SELECT canonical_payload FROM signal_claims WHERE signal_id=?",
            arrayOf(signalId)
        ).use { cursor ->
            if (!cursor.moveToFirst()) error("signal claim post-commit read-back returned no row")
            verified = cursor.getString(0)
        }
        if (verified != stored) error("signal claim post-commit read-back mismatch")
        if (inserted) return "CLAIMED"
        return if (stored == canonicalPayload) "DUPLICATE" else "CONFLICT"
    }
    /**
     * Write-ahead record for every signed POST/DELETE. The caller must not
     * touch the exchange unless this row and its post-insert read-back succeed.
     */
    @Synchronized
    fun beginMutation(
        method: String,
        path: String,
        requestParams: String
    ): String {
        val id = java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val symbol = requestParams.split("&")
            .firstOrNull { it.startsWith("symbol=") }
            ?.substringAfter("=")
            ?.let { android.net.Uri.decode(it) }
        val clientOrderId = requestParams.split("&")
            .firstOrNull {
                it.startsWith("newClientOrderId=") ||
                    it.startsWith("listClientOrderId=")
            }
            ?.substringAfter("=")
            ?.let { android.net.Uri.decode(it) }
        val values = ContentValues().apply {
            put("mutation_id", id)
            put("method", method)
            put("path", path)
            put("symbol", symbol)
            put("client_order_id", clientOrderId)
            put("request_params", requestParams)
            put("state", "PREPARED")
            put("created_at", now)
            put("updated_at", now)
        }
        val db = writableDatabase
        db.insertOrThrow("mutation_intents", null, values)
        db.rawQuery(
            "SELECT state, method, path FROM mutation_intents WHERE mutation_id=?",
            arrayOf(id)
        ).use { cursor ->
            check(cursor.moveToFirst()) { "mutation intent read-back missing" }
            check(cursor.getString(0) == "PREPARED") { "mutation intent state mismatch" }
            check(cursor.getString(1) == method && cursor.getString(2) == path) {
                "mutation intent identity mismatch"
            }
        }
        return id
    }

    @Synchronized
    fun completeMutation(
        mutationId: String,
        responseCode: Int,
        responseJson: String
    ) {
        val values = ContentValues().apply {
            put("state", "HTTP_RESPONSE")
            put("response_code", responseCode)
            put("response_json", responseJson)
            put("updated_at", System.currentTimeMillis())
        }
        val db = writableDatabase
        check(db.update(
            "mutation_intents",
            values,
            "mutation_id=? AND state IN ('PREPARED','UNKNOWN')",
            arrayOf(mutationId)
        ) == 1) { "mutation intent completion update missing" }
        db.rawQuery(
            "SELECT state, response_code FROM mutation_intents WHERE mutation_id=?",
            arrayOf(mutationId)
        ).use { cursor ->
            check(cursor.moveToFirst()) { "mutation completion read-back missing" }
            check(cursor.getString(0) == "HTTP_RESPONSE" && cursor.getInt(1) == responseCode) {
                "mutation completion read-back mismatch"
            }
        }
    }

    @Synchronized
    fun markMutationUnknown(mutationId: String, error: String) {
        val values = ContentValues().apply {
            put("state", "UNKNOWN")
            put("error", error.take(1000))
            put("updated_at", System.currentTimeMillis())
        }
        val db = writableDatabase
        check(db.update(
            "mutation_intents",
            values,
            "mutation_id=? AND state='PREPARED'",
            arrayOf(mutationId)
        ) == 1) { "unknown mutation state update missing" }
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
