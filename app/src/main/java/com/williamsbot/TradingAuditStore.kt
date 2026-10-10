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

        createFuturesTables(db)
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

        createFuturesTables(db)
    }

    private fun createFuturesTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS futures_intents(
                intent_id TEXT PRIMARY KEY,
                client_id TEXT NOT NULL UNIQUE,
                symbol TEXT NOT NULL,
                operation TEXT NOT NULL,
                direction TEXT NOT NULL,
                side TEXT NOT NULL,
                status TEXT NOT NULL,
                params_json TEXT NOT NULL,
                response_json TEXT,
                error TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE INDEX IF NOT EXISTS idx_futures_intents_status
            ON futures_intents(status, updated_at)
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS futures_campaigns(
                symbol TEXT PRIMARY KEY,
                campaign_id TEXT NOT NULL,
                direction TEXT NOT NULL,
                state TEXT NOT NULL,
                entry_client_algo_id TEXT,
                protection_client_algo_id TEXT,
                raw_json TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE INDEX IF NOT EXISTS idx_futures_campaigns_state
            ON futures_campaigns(state, updated_at)
        """.trimIndent())
    }

    /**
     * A Futures mutation is allowed only after this synchronous durable row
     * has committed. A crash in SUBMITTING/UNKNOWN must be reconciled, never
     * resolved by generating a fresh client ID and retrying blindly.
     */
    @Synchronized
    fun saveFuturesIntentBeforeMutation(
        intentId: String,
        clientId: String,
        symbol: String,
        operation: String,
        direction: String,
        side: String,
        paramsJson: String
    ) {
        require(intentId.isNotBlank() && clientId.isNotBlank())
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("intent_id", intentId)
            put("client_id", clientId)
            put("symbol", symbol.uppercase())
            put("operation", operation.uppercase())
            put("direction", direction.uppercase())
            put("side", side.uppercase())
            put("status", "PENDING")
            put("params_json", paramsJson)
            put("created_at", now)
            put("updated_at", now)
        }
        val db = writableDatabase
        db.transaction {
            insertOrThrow("futures_intents", null, values)
        }
        val saved = futuresIntentById(intentId)
        check(saved != null && saved.optString("status") == "PENDING") {
            "Durable Futures intent could not be read back; mutation aborted"
        }
    }

    @Synchronized
    fun updateFuturesIntent(
        intentId: String,
        status: String,
        responseJson: String? = null,
        error: String? = null
    ) {
        require(intentId.isNotBlank())
        val values = ContentValues().apply {
            put("status", status.uppercase())
            if (responseJson != null) put("response_json", responseJson)
            put("error", error)
            put("updated_at", System.currentTimeMillis())
        }
        val changed = writableDatabase.update(
            "futures_intents",
            values,
            "intent_id=?",
            arrayOf(intentId)
        )
        check(changed == 1) { "Futures intent update lost durable row: $intentId" }
    }

    @Synchronized
    fun futuresIntentById(intentId: String): JSONObject? =
        writableDatabase.query(
            "futures_intents",
            arrayOf(
                "intent_id", "client_id", "symbol", "operation", "direction",
                "side", "status", "params_json", "response_json", "error",
                "created_at", "updated_at"
            ),
            "intent_id=?",
            arrayOf(intentId),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            JSONObject().apply {
                put("intent_id", cursor.getString(0))
                put("client_id", cursor.getString(1))
                put("symbol", cursor.getString(2))
                put("operation", cursor.getString(3))
                put("direction", cursor.getString(4))
                put("side", cursor.getString(5))
                put("status", cursor.getString(6))
                put("params_json", cursor.getString(7))
                if (!cursor.isNull(8)) put("response_json", cursor.getString(8))
                if (!cursor.isNull(9)) put("error", cursor.getString(9))
                put("created_at", cursor.getLong(10))
                put("updated_at", cursor.getLong(11))
            }
        }

    @Synchronized
    fun pendingFuturesIntents(): List<JSONObject> {
        val rows = mutableListOf<JSONObject>()
        writableDatabase.query(
            "futures_intents",
            arrayOf(
                "intent_id", "client_id", "symbol", "operation", "direction",
                "side", "status", "params_json", "response_json", "error",
                "created_at", "updated_at"
            ),
            "status IN ('PENDING','SUBMITTING','SUBMITTED','UNKNOWN','RECONCILE_REQUIRED')",
            null,
            null,
            null,
            "created_at ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += JSONObject().apply {
                    put("intent_id", cursor.getString(0))
                    put("client_id", cursor.getString(1))
                    put("symbol", cursor.getString(2))
                    put("operation", cursor.getString(3))
                    put("direction", cursor.getString(4))
                    put("side", cursor.getString(5))
                    put("status", cursor.getString(6))
                    put("params_json", cursor.getString(7))
                    if (!cursor.isNull(8)) put("response_json", cursor.getString(8))
                    if (!cursor.isNull(9)) put("error", cursor.getString(9))
                    put("created_at", cursor.getLong(10))
                    put("updated_at", cursor.getLong(11))
                }
            }
        }
        return rows
    }

    @Synchronized
    fun saveFuturesCampaign(symbol: String, campaign: JSONObject) {
        val normalized = symbol.uppercase()
        require(normalized.isNotBlank())
        val campaignId = campaign.optString("campaign_id")
        val direction = campaign.optString("direction").uppercase()
        val state = campaign.optString("state").uppercase()
        require(campaignId.isNotBlank() && direction in setOf("LONG","SHORT") && state.isNotBlank())
        val values = ContentValues().apply {
            put("symbol", normalized)
            put("campaign_id", campaignId)
            put("direction", direction)
            put("state", state)
            put("entry_client_algo_id", campaign.optString("entry_client_algo_id"))
            put("protection_client_algo_id", campaign.optString("protection_client_algo_id"))
            put("raw_json", campaign.toString())
            put("updated_at", System.currentTimeMillis())
        }
        val inserted = writableDatabase.insertWithOnConflict(
            "futures_campaigns",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        check(inserted != -1L) { "Failed to persist Futures campaign state for $normalized" }
    }

    @Synchronized
    fun futuresCampaign(symbol: String): JSONObject? =
        writableDatabase.query(
            "futures_campaigns",
            arrayOf("raw_json"),
            "symbol=?",
            arrayOf(symbol.uppercase()),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else JSONObject(cursor.getString(0))
        }

    @Synchronized
    fun activeFuturesCampaigns(): List<JSONObject> {
        val rows = mutableListOf<JSONObject>()
        writableDatabase.query(
            "futures_campaigns",
            arrayOf("raw_json"),
            "state NOT IN ('CLOSED','FLAT')",
            null,
            null,
            null,
            "updated_at ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) rows += JSONObject(cursor.getString(0))
        }
        return rows
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
        val normalizedSide = side.trim().uppercase()
        require(normalizedSide in setOf("BUY", "SELL", "LONG", "SHORT")) {
            "trade side must be an explicit supported direction"
        }
        val values = ContentValues()
        values.put("trade_id", tradeId)
        values.put("symbol", symbol)
        values.put("side", normalizedSide)
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
        val inserted = writableDatabase.insertWithOnConflict(
            "trades",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        check(inserted != -1L) { "Failed to persist closed trade history for $symbol/$tradeId" }
    }

    @Synchronized
    fun recentClosedFuturesTrades(limit: Int = 500): List<JSONObject> {
        val safeLimit = limit.coerceIn(1, 1000)
        val rows = mutableListOf<JSONObject>()
        writableDatabase.query(
            "trades",
            arrayOf("net_pnl", "closed_at", "side", "reason"),
            "side IN ('LONG','SHORT')",
            null,
            null,
            null,
            "closed_at DESC, trade_id DESC",
            safeLimit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(
                    JSONObject()
                        .put("net_pnl", cursor.getString(0))
                        .put("closed_at", cursor.getString(1))
                        .put("reason", cursor.getString(3) ?: "")
                )
            }
        }
        return rows
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
