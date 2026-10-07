package com.williamsbot

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent market-history store.
 *
 * Full history lives only on the device database. Callers load a bounded
 * working set for calculations, so the complete history is never retained in RAM.
 */
class MarketHistoryStore(context: Context) :
    SQLiteOpenHelper(context, "williams_market_history.db", null, 1) {

    companion object {
        /**
         * Validates a fetched closed-candle segment before persistence.
         *
         * The expectedRemoteTimestamp is the exclusive upper bound: no candle
         * may start at or after it, and the final candle must end exactly at it.
         */
        fun validateFetchedGap(
            candles: List<Candle>,
            gapStart: Long,
            expectedRemoteTimestamp: Long,
            intervalMs: Long
        ): Boolean {
            if (candles.isEmpty() || intervalMs <= 0L) return false
            if (expectedRemoteTimestamp <= gapStart) return false
            if (candles.first().openTime != gapStart) return false

            for (i in candles.indices) {
                val candle = candles[i]
                if (candle.openTime < gapStart) return false
                if (candle.openTime >= expectedRemoteTimestamp) return false
                if (candle.closeTime >= expectedRemoteTimestamp) return false
                if (i > 0) {
                    val previous = candles[i - 1]
                    if (candle.openTime - previous.openTime != intervalMs) {
                        return false
                    }
                }
            }

            return candles.last().openTime + intervalMs == expectedRemoteTimestamp
        }
    }

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
    ) = Unit

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
    fun setError(
        symbol: String,
        interval: String,
        message: String?,
        complete: Boolean? = null
    ) {
        val values = ContentValues()
        values.put("symbol", symbol)
        values.put("interval", interval)
        values.put("oldest_open_time", oldest(symbol, interval))
        values.put("newest_open_time", newest(symbol, interval))
        values.put("candle_count", count(symbol, interval))
        values.put(
            "complete",
            when (complete) {
                true -> 1
                false -> 0
                null -> if (isComplete(symbol, interval)) 1 else 0
            }
        )
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
        limit: Int
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
            "symbol=? AND interval=?",
            arrayOf(symbol, interval),
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
        val rows = JSONArray()
        for (symbol in symbols) {
            for (interval in intervals) {
                rows.put(
                    JSONObject()
                        .put("symbol", symbol)
                        .put("interval", interval)
                        .put("complete", isComplete(symbol, interval))
                        .put("count", count(symbol, interval))
                        .put("oldest_open_time", oldest(symbol, interval))
                        .put("newest_open_time", newest(symbol, interval))
                )
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
