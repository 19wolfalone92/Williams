package com.williamsbot

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
    SQLiteOpenHelper(context, "williams_trading_audit.db", null, 3) {

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
