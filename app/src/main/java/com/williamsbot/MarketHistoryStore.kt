package com.williamsbot

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import org.json.JSONArray
import org.json.JSONObject

internal data class HistoryReadinessStatus(
    val minimumWorkingSetCount: Int,
    val workingSetReady: Boolean,
    val decisionFrame: Boolean,
    val decisionReady: Boolean,
    val contextFrame: Boolean,
    val contextReady: Boolean,
    val executionMonitoringFrame: Boolean,
    val executionReady: Boolean,
    val scope: String
)

/**
 * "complete" means exhaustive historical backfill reached the exchange's available
 * beginning. A bounded working set can be ready without exhaustive backfill.
 * H1 alone makes trading decisions; D1/H4 are context and M15 is execution monitoring.
 */
internal fun historyReadinessStatus(
    interval: String,
    candleCount: Long,
    fullHistoryComplete: Boolean
): HistoryReadinessStatus {
    val minimum = when (interval) {
        "1h" -> 150
        "4h", "1d", "15m" -> 45
        else -> 40
    }
    val count = candleCount.coerceAtLeast(0L)
    val workingReady = count >= minimum
    val decisionFrame = interval == "1h"
    val contextFrame = interval == "4h" || interval == "1d"
    val executionMonitoringFrame = interval == "15m"
    val decisionReady = decisionFrame && count >= 150
    val contextReady = contextFrame && count >= 45
    val executionReady = executionMonitoringFrame && count >= 45
    val scope = when {
        fullHistoryComplete && workingReady -> "FULL_HISTORY"
        fullHistoryComplete -> "FULL_HISTORY_BELOW_WORKING_SET_MINIMUM"
        workingReady -> "WORKING_SET_READY"
        else -> "WARMING_UP"
    }
    return HistoryReadinessStatus(
        minimumWorkingSetCount = minimum,
        workingSetReady = workingReady,
        decisionFrame = decisionFrame,
        decisionReady = decisionReady,
        contextFrame = contextFrame,
        contextReady = contextReady,
        executionMonitoringFrame = executionMonitoringFrame,
        executionReady = executionReady,
        scope = scope
    )
}

/**
 * Persistent market-history store.
 *
 * Full history lives only on the device database. Callers load a bounded
 * working set for calculations, so the complete history is never retained in RAM.
 */
class MarketHistoryStore(context: Context) :
    SQLiteOpenHelper(context, "williams_market_history.db", null, 2) {

    data class Candle(
        val openTime: Long,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Double,
        val closeTime: Long
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE candles(
                symbol TEXT NOT NULL,
                interval TEXT NOT NULL,
                open_time INTEGER NOT NULL,
                close_time INTEGER NOT NULL,
                open REAL NOT NULL,
                high REAL NOT NULL,
                low REAL NOT NULL,
                close REAL NOT NULL,
                volume REAL NOT NULL,
                PRIMARY KEY(symbol, interval, open_time)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX idx_candles_lookup
            ON candles(symbol, interval, open_time DESC)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE sync_state(
                symbol TEXT NOT NULL,
                interval TEXT NOT NULL,
                oldest_open_time INTEGER NOT NULL DEFAULT 0,
                newest_open_time INTEGER NOT NULL DEFAULT 0,
                candle_count INTEGER NOT NULL DEFAULT 0,
                complete INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                PRIMARY KEY(symbol, interval)
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        if (oldVersion < 2) {
            // Older installs may have the candles table but predate sync_state.
            // Create the readiness journal in-place and seed counts from the saved
            // candles so a schema upgrade cannot leave history permanently at zero.
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS sync_state(
                    symbol TEXT NOT NULL,
                    interval TEXT NOT NULL,
                    oldest_open_time INTEGER NOT NULL DEFAULT 0,
                    newest_open_time INTEGER NOT NULL DEFAULT 0,
                    candle_count INTEGER NOT NULL DEFAULT 0,
                    complete INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL DEFAULT 0,
                    last_error TEXT,
                    PRIMARY KEY(symbol, interval)
                )
                """.trimIndent()
            )
            val candlesTableExists = db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='candles' LIMIT 1",
                null
            ).use { cursor -> cursor.moveToFirst() }
            if (candlesTableExists) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_candles_lookup " +
                        "ON candles(symbol, interval, open_time DESC)"
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO sync_state(
                        symbol, interval, oldest_open_time, newest_open_time,
                        candle_count, complete, updated_at, last_error
                    )
                    SELECT symbol, interval, MIN(open_time), MAX(open_time),
                           COUNT(*), 0, 0, NULL
                    FROM candles
                    GROUP BY symbol, interval
                    """.trimIndent()
                )
            }
        }
    }

    @Synchronized
    fun upsertBatch(
        symbol: String,
        interval: String,
        candles: List<Candle>,
        complete: Boolean? = null
    ) {
        if (candles.isEmpty()) return
        val db = writableDatabase
        db.transaction {
            val insert = ContentValues()
            for (c in candles) {
                insert.clear()
                insert.put("symbol", symbol)
                insert.put("interval", interval)
                insert.put("open_time", c.openTime)
                insert.put("close_time", c.closeTime)
                insert.put("open", c.open)
                insert.put("high", c.high)
                insert.put("low", c.low)
                insert.put("close", c.close)
                insert.put("volume", c.volume)
                db.insertWithOnConflict(
                    "candles",
                    null,
                    insert,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }

            val row = db.query(
                "candles",
                arrayOf(
                    "MIN(open_time) AS oldest_open_time",
                    "MAX(open_time) AS newest_open_time",
                    "COUNT(*) AS candle_count"
                ),
                "symbol=? AND interval=?",
                arrayOf(symbol, interval),
                null,
                null,
                null
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    longArrayOf(
                        cursor.getLong(0),
                        cursor.getLong(1),
                        cursor.getLong(2)
                    )
                } else {
                    longArrayOf(0L, 0L, 0L)
                }
            }

            val state = ContentValues()
            state.put("symbol", symbol)
            state.put("interval", interval)
            state.put("oldest_open_time", row[0])
            state.put("newest_open_time", row[1])
            state.put("candle_count", row[2])
            val completeInt =
                when (complete) {
                    true -> 1
                    false -> 0
                    null -> writableDatabase.query(
                        "sync_state",
                        arrayOf("complete"),
                        "symbol=? AND interval=?",
                        arrayOf(symbol, interval),
                        null,
                        null,
                        null
                    ).use { cursor ->
                        if (cursor.moveToFirst()) cursor.getInt(0) else 0
                    }
                }
            state.put("complete", completeInt)
            state.put("updated_at", System.currentTimeMillis())
            db.insertWithOnConflict(
                "sync_state",
                null,
                state,
                SQLiteDatabase.CONFLICT_REPLACE
            )

        }
    }

    @Synchronized
    fun markComplete(symbol: String, interval: String) {
        val state = ContentValues()
        state.put("symbol", symbol)
        state.put("interval", interval)
        state.put("oldest_open_time", oldest(symbol, interval))
        state.put("newest_open_time", newest(symbol, interval))
        state.put("candle_count", count(symbol, interval))
        state.put("complete", 1)
        state.put("updated_at", System.currentTimeMillis())
        writableDatabase.insertWithOnConflict(
            "sync_state",
            null,
            state,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    @Synchronized
    fun setError(symbol: String, interval: String, message: String?) {
        val values = ContentValues()
        values.put("symbol", symbol)
        values.put("interval", interval)
        values.put("oldest_open_time", oldest(symbol, interval))
        values.put("newest_open_time", newest(symbol, interval))
        values.put("candle_count", count(symbol, interval))
        values.put("complete", if (isComplete(symbol, interval)) 1 else 0)
        values.put("updated_at", System.currentTimeMillis())
        values.put("last_error", message)
        writableDatabase.insertWithOnConflict(
            "sync_state",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    @Synchronized
    fun isComplete(symbol: String, interval: String): Boolean {
        writableDatabase.query(
            "sync_state",
            arrayOf("complete"),
            "symbol=? AND interval=?",
            arrayOf(symbol, interval),
            null,
            null,
            null
        ).use { cursor ->
            return cursor.moveToFirst() && cursor.getInt(0) == 1
        }
    }

    @Synchronized
    fun newest(symbol: String, interval: String): Long =
        scalarTime(symbol, interval, "MAX(open_time)")

    @Synchronized
    fun oldest(symbol: String, interval: String): Long =
        scalarTime(symbol, interval, "MIN(open_time)")

    @Synchronized
    fun count(symbol: String, interval: String): Long {
        writableDatabase.query(
            "candles",
            arrayOf("COUNT(*)"),
            "symbol=? AND interval=?",
            arrayOf(symbol, interval),
            null,
            null,
            null
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    @Synchronized
    fun loadRecent(
        symbol: String,
        interval: String,
        limit: Int,
        beforeMs: Long? = null
    ): List<Candle> {
        val safeLimit = limit.coerceIn(1, 20_000)
        val out = mutableListOf<Candle>()
        writableDatabase.query(
            "candles",
            arrayOf(
                "open_time",
                "close_time",
                "open",
                "high",
                "low",
                "close",
                "volume"
            ),
            if (beforeMs == null) "symbol=? AND interval=?" else "symbol=? AND interval=? AND open_time<=?",
            if (beforeMs == null) arrayOf(symbol, interval) else arrayOf(symbol, interval, beforeMs.toString()),
            null,
            null,
            "open_time DESC",
            safeLimit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out += Candle(
                    openTime = cursor.getLong(0),
                    closeTime = cursor.getLong(1),
                    open = cursor.getDouble(2),
                    high = cursor.getDouble(3),
                    low = cursor.getDouble(4),
                    close = cursor.getDouble(5),
                    volume = cursor.getDouble(6)
                )
            }
        }
        return out.asReversed()
    }

    @Synchronized
    fun status(symbols: List<String>, intervals: List<String>): JSONObject {
        // UI/diagnostics poll status frequently. Reading each symbol/timeframe with
        // four independent SQLite queries caused hundreds of DB reads per refresh.
        // sync_state is updated transactionally with every candle batch, so one scan
        // of that compact table gives the same history readiness/count contract.
        val known = HashMap<Pair<String, String>, JSONObject>()
        readableDatabase.query(
            "sync_state",
            arrayOf(
                "symbol",
                "interval",
                "complete",
                "candle_count",
                "oldest_open_time",
                "newest_open_time"
            ),
            null,
            null,
            null,
            null,
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val symbol = cursor.getString(0)
                val interval = cursor.getString(1)
                known[symbol to interval] = JSONObject()
                    .put("symbol", symbol)
                    .put("interval", interval)
                    .put("complete", cursor.getInt(2) == 1)
                    .put("count", cursor.getLong(3))
                    .put("oldest_open_time", cursor.getLong(4))
                    .put("newest_open_time", cursor.getLong(5))
            }
        }

        val rows = JSONArray()
        for (symbol in symbols) {
            for (interval in intervals) {
                val row = known[symbol to interval] ?: JSONObject()
                    .put("symbol", symbol)
                    .put("interval", interval)
                    .put("complete", false)
                    .put("count", 0L)
                    .put("oldest_open_time", 0L)
                    .put("newest_open_time", 0L)
                val count = row.optLong("count", 0L)
                val complete = row.optBoolean("complete", false)
                val readiness = historyReadinessStatus(interval, count, complete)
                row.put("full_history_complete", complete)
                    .put("minimum_working_set_count", readiness.minimumWorkingSetCount)
                    .put("working_set_ready", readiness.workingSetReady)
                    .put("decision_frame", readiness.decisionFrame)
                    .put("decision_history_ready", readiness.decisionReady)
                    .put("context_frame", readiness.contextFrame)
                    .put("context_history_ready", readiness.contextReady)
                    .put("execution_monitoring_frame", readiness.executionMonitoringFrame)
                    .put("execution_history_ready", readiness.executionReady)
                    .put("history_scope", readiness.scope)
                rows.put(row)
            }
        }
        return JSONObject().put("items", rows)
    }

    private fun scalarTime(
        symbol: String,
        interval: String,
        expression: String
    ): Long {
        writableDatabase.rawQuery(
            "SELECT $expression FROM candles WHERE symbol=? AND interval=?",
            arrayOf(symbol, interval)
        ).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) return 0L
            return cursor.getLong(0)
        }
    }
}
