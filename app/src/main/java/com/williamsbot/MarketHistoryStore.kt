package com.williamsbot

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Only bounded DERIVED INDICATORS are saved here, not the whole chart:
 * market OHLCV remains in SQLite candles. Revision updates happen in the
 * exact same transaction as candle upserts; no stale result survives a
 * correction, reconnect or process death.
 */
internal val closedIndicatorCacheSchema = listOf(
    """CREATE TABLE IF NOT EXISTS closed_indicator_cache_revision(
        symbol TEXT NOT NULL, interval TEXT NOT NULL,
        revision INTEGER NOT NULL DEFAULT 0,
        PRIMARY KEY(symbol,interval))""",
    """CREATE TABLE IF NOT EXISTS closed_indicator_cache(
        symbol TEXT NOT NULL, interval TEXT NOT NULL,
        model TEXT NOT NULL, source_revision INTEGER NOT NULL,
        source_first_ms INTEGER NOT NULL, source_last_ms INTEGER NOT NULL,
        source_rows INTEGER NOT NULL, payload TEXT NOT NULL,
        PRIMARY KEY(symbol,interval,source_first_ms,source_last_ms,source_rows))"""
)

// Gap edges are keyed by their right-hand stored candle. A late INSERT splits
// or removes the affected edges in the same transaction as OHLCV and revision.
internal val durableHistoryGapSchema = listOf(
    """CREATE TABLE IF NOT EXISTS history_gap(
        symbol TEXT NOT NULL, interval TEXT NOT NULL, right_open INTEGER NOT NULL,
        start_ms INTEGER NOT NULL, PRIMARY KEY(symbol,interval,right_open))""",
    """CREATE TABLE IF NOT EXISTS history_gap_audit(
        symbol TEXT NOT NULL, interval TEXT NOT NULL, scanned_through INTEGER NOT NULL,
        PRIMARY KEY(symbol,interval))""",
    """CREATE TABLE IF NOT EXISTS history_invalid_grid(
        symbol TEXT NOT NULL, interval TEXT NOT NULL, open_ms INTEGER NOT NULL,
        PRIMARY KEY(symbol,interval,open_ms))""",
    """CREATE TRIGGER IF NOT EXISTS history_gap_after_insert AFTER INSERT ON candles BEGIN
        DELETE FROM history_invalid_grid WHERE symbol=NEW.symbol AND interval=NEW.interval
            AND open_ms=NEW.open_time;
        DELETE FROM history_gap WHERE symbol=NEW.symbol AND interval=NEW.interval
            AND (right_open=NEW.open_time OR right_open=(
                SELECT MIN(open_time) FROM candles WHERE symbol=NEW.symbol AND interval=NEW.interval
                AND open_time>NEW.open_time));
        INSERT OR REPLACE INTO history_gap(symbol,interval,right_open,start_ms)
            SELECT NEW.symbol,NEW.interval,NEW.open_time,p.close_time+1 FROM candles p
            WHERE p.symbol=NEW.symbol AND p.interval=NEW.interval AND p.open_time=(
                SELECT MAX(open_time) FROM candles WHERE symbol=NEW.symbol AND interval=NEW.interval
                AND open_time<NEW.open_time) AND p.close_time+1<NEW.open_time;
        INSERT OR REPLACE INTO history_gap(symbol,interval,right_open,start_ms)
            SELECT NEW.symbol,NEW.interval,n.open_time,NEW.close_time+1 FROM candles n
            WHERE n.symbol=NEW.symbol AND n.interval=NEW.interval AND n.open_time=(
                SELECT MIN(open_time) FROM candles WHERE symbol=NEW.symbol AND interval=NEW.interval
                AND open_time>NEW.open_time) AND NEW.close_time+1<n.open_time;
    END"""
)

internal const val closedIndicatorModelVersion = "WILLIAMS_CLOSED_INDICATORS_V2"

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
        "6h" -> 140
        "4h", "1d", "15m" -> 45
        else -> 40
    }
    val count = candleCount.coerceAtLeast(0L)
    val workingReady = count >= minimum
    val decisionFrame = interval == "1h"
    val contextFrame = interval in listOf("4h","6h","1d")
    val executionMonitoringFrame = interval == "15m"
    val decisionReady = decisionFrame && count >= 150
    val contextReady = contextFrame && count >= (if (interval=="6h") 140 else 45)
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
 * A completed historical backfill means the oldest exchange candle was reached.
 * Adding a recent closed candle cannot make that fact untrue.
 */
internal fun historicBackfillComplete(prior: Boolean, update: Boolean?): Boolean =
    prior || update == true

/**
 * Persistent market-history store.
 *
 * Full history lives only on the device database. Callers load a bounded
 * working set for calculations, so the complete history is never retained in RAM.
 */
class MarketHistoryStore(context: Context, databaseName: String = "williams_market_history.db") :
    SQLiteOpenHelper(context, databaseName, null, 6) {

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
                historical_revision INTEGER NOT NULL DEFAULT 0,
                complete INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                PRIMARY KEY(symbol, interval)
            )
            """.trimIndent()
        )
        deviceWaveReplaySchema.forEach(db::execSQL)
        closedIndicatorCacheSchema.forEach(db::execSQL)
        durableHistoryGapSchema.forEach(db::execSQL)
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
        if(oldVersion<3) deviceWaveReplaySchema.forEach(db::execSQL)
        if(oldVersion<4) closedIndicatorCacheSchema.forEach(db::execSQL)
        if(oldVersion<5) durableHistoryGapSchema.forEach(db::execSQL)
        if(oldVersion<6) db.execSQL(
            "ALTER TABLE sync_state ADD COLUMN historical_revision INTEGER NOT NULL DEFAULT 0"
        )
    }

    @Synchronized
    fun upsertBatch(
        symbol: String,
        interval: String,
        candles: List<Candle>,
        complete: Boolean? = null,
        missingOnly: Boolean = false,
        expectedRevision: Long? = null
    ) {
        if (candles.isEmpty()) return
        val db = writableDatabase
        db.transaction {
            // The primary key lookup also distinguishes a correction from an
            // identical delivery. Never replace identical rows: replacement
            // fires history/replay triggers and invalidates derived results.
            val prior = db.query(
                "sync_state",
                arrayOf("oldest_open_time", "newest_open_time", "candle_count", "historical_revision"),
                "symbol=? AND interval=?", arrayOf(symbol, interval),
                null, null, null
            ).use { cursor ->
                if (cursor.moveToFirst()) longArrayOf(cursor.getLong(0),
                    cursor.getLong(1), cursor.getLong(2), cursor.getLong(3)) else null
            }
            val insertOnly = missingOnly || (expectedRevision!=null &&
                expectedRevision!=indicatorCacheRevision(symbol,interval))
            val priorHistoricalRevision = prior?.getOrNull(3) ?: 0L
            val priorNewestOpenTime = prior?.getOrNull(1) ?: 0L
            var added = 0L
            var changed = false
            var historicalChanged = false
            var oldest = prior?.get(0) ?: 0L
            var newest = prior?.get(1) ?: 0L
            val insert = ContentValues()
            for (c in candles) {
                require(nativeHistoryCloseMs(c.openTime, interval) == c.closeTime &&
                    listOf(c.open, c.high, c.low, c.close).all { it.isFinite() && it > 0.0 } &&
                    c.high >= maxOf(c.open, c.close, c.low) &&
                    c.low <= minOf(c.open, c.close) &&
                    c.volume.isFinite() && c.volume >= 0.0) { "INVALID_HISTORY_CANDLE" }
                val existing = db.query(
                    "candles", arrayOf("close_time", "open", "high", "low", "close", "volume"),
                    "symbol=? AND interval=? AND open_time=?",
                    arrayOf(symbol, interval, c.openTime.toString()), null, null, null
                ).use { cursor ->
                    if (!cursor.moveToFirst()) null else
                        cursor.getLong(0) == c.closeTime &&
                        cursor.getDouble(1) == c.open && cursor.getDouble(2) == c.high &&
                        cursor.getDouble(3) == c.low && cursor.getDouble(4) == c.close &&
                        cursor.getDouble(5) == c.volume
                }
                if (existing == true || (insertOnly && existing != null)) continue
                // Only a pure append after the previously stored newest candle
                // can continue the recursive indicator state. A correction or
                // any insertion inside/before the known range changes its seed.
                if (existing == false ||
                    (existing == null && priorNewestOpenTime > 0L && c.openTime <= priorNewestOpenTime)
                ) historicalChanged = true
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
                check(db.insertWithOnConflict("candles", null, insert,
                    SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "HISTORY_WRITE_FAILED" }
                changed = true
                if (existing == null) {
                    added++
                    oldest = if (oldest == 0L) c.openTime else minOf(oldest, c.openTime)
                    newest = maxOf(newest, c.openTime)
                }
            }

            // Existing sync_state is maintained in the same transaction as
            // candles. Only rebuild the aggregate for an absent/invalid row.
            val priorValid = prior != null && prior[2] > 0L &&
                prior[0] > 0L && prior[1] >= prior[0]
            val row = if (priorValid && prior != null) {
                longArrayOf(oldest, newest, prior[2] + added)
            } else db.query(
                "candles", arrayOf("MIN(open_time)", "MAX(open_time)", "COUNT(*)"),
                "symbol=? AND interval=?", arrayOf(symbol, interval), null, null, null
            ).use { cursor ->
                check(cursor.moveToFirst())
                longArrayOf(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2))
            }
            val state = ContentValues()
            state.put("symbol", symbol)
            state.put("interval", interval)
            state.put("oldest_open_time", row[0])
            state.put("newest_open_time", row[1])
            state.put("candle_count", row[2])
            state.put("historical_revision", priorHistoricalRevision + if (historicalChanged) 1L else 0L)
            // "complete" records that the exchange's earliest available candle
            // was reached. Live increments must NEVER erase that durable marker.
            val previousComplete = db.query(
                "sync_state", arrayOf("complete"), "symbol=? AND interval=?",
                arrayOf(symbol, interval), null, null, null
            ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) == 1 }
            val completeInt = if (historicBackfillComplete(previousComplete, complete)) 1 else 0
            if (!changed && priorValid && (completeInt == 1) == previousComplete) return@transaction
            state.put("complete", completeInt)
            state.put("updated_at", System.currentTimeMillis())
            db.insertWithOnConflict(
                "sync_state",
                null,
                state,
                SQLiteDatabase.CONFLICT_REPLACE
            )
            // If the previous database was fully scanned, every additional or
            // corrected row in this transaction was validated above and its
            // adjacent edges maintained by the trigger. Advance without rescanning.
            if(changed) {
                val priorAudit = db.rawQuery("SELECT scanned_through FROM history_gap_audit WHERE symbol=? AND interval=?",
                    arrayOf(symbol,interval)).use { if(it.moveToFirst()) it.getLong(0) else 0L }
                if((priorValid && prior!=null && priorAudit==prior[1]) ||
                    (added>0L && row[2]==added)) {
                    db.execSQL("INSERT OR REPLACE INTO history_gap_audit(symbol,interval,scanned_through) VALUES(?,?,?)",
                        arrayOf<Any>(symbol,interval,row[1]))
                }
            }
            // Any overlapping REST correction or freshly CLOSED WS candle
            // invalidates the derived indicator cache, including when the
            // latest open time is unchanged but its OHLCV was corrected.
            // Portable Android SQLite syntax; no reliance on modern UPSERT.
            if (!changed) return@transaction
            db.execSQL("INSERT OR IGNORE INTO closed_indicator_cache_revision(" +
                "symbol,interval,revision) VALUES(?,?,0)",arrayOf(symbol,interval))
            db.execSQL("UPDATE closed_indicator_cache_revision SET revision=revision+1 " +
                "WHERE symbol=? AND interval=?",arrayOf(symbol,interval))
        }
    }

    data class MissingRange(val startMs: Long, val endExclusiveMs: Long)

    /** Scan existing v4 history in bounded pages, not a blocking migration.
     * All subsequent writes also maintain gap edges transactionally. */
    @Synchronized
    fun auditHistoryGapPage(symbol: String, interval: String, limit: Int = 4096) {
        val db = writableDatabase
        db.transaction {
            val cursorMs = rawQuery("SELECT scanned_through FROM history_gap_audit WHERE symbol=? AND interval=?",
                arrayOf(symbol,interval)).use { if(it.moveToFirst()) it.getLong(0) else 0L }
            var previousClose = if (cursorMs == 0L) null else rawQuery(
                "SELECT close_time FROM candles WHERE symbol=? AND interval=? AND open_time=?",
                arrayOf(symbol,interval,cursorMs.toString())
            ).use { if(it.moveToFirst()) it.getLong(0) else null }
            var last = cursorMs
            rawQuery("SELECT open_time,close_time FROM candles WHERE symbol=? AND interval=? AND open_time>? "+
                "ORDER BY open_time LIMIT ?",arrayOf(symbol,interval,cursorMs.toString(),
                limit.coerceIn(1,4096).toString())).use { rows ->
                while(rows.moveToNext()) {
                    val opened = rows.getLong(0)
                    val closed = rows.getLong(1)
                    if(nativeHistoryCloseMs(opened,interval)!=closed) {
                        execSQL("INSERT OR IGNORE INTO history_invalid_grid(symbol,interval,open_ms) VALUES(?,?,?)",
                            arrayOf<Any>(symbol,interval,opened))
                    }
                    val prior = previousClose
                    if(prior != null && prior < opened-1L) {
                        execSQL("INSERT OR REPLACE INTO history_gap(symbol,interval,right_open,start_ms) VALUES(?,?,?,?)",
                            arrayOf<Any>(symbol,interval,opened,prior+1L))
                    }
                    previousClose = closed
                    last = opened
                }
            }
            if(last>cursorMs) execSQL("INSERT OR REPLACE INTO history_gap_audit(symbol,interval,scanned_through) VALUES(?,?,?)",
                arrayOf<Any>(symbol,interval,last))
        }
    }

    @Synchronized
    fun firstMissingRange(symbol: String, interval: String): MissingRange? =
        readableDatabase.rawQuery("SELECT start_ms,right_open FROM history_gap WHERE symbol=? AND interval=? "+
            "ORDER BY right_open LIMIT 1",arrayOf(symbol,interval)).use {
            if(it.moveToFirst()) MissingRange(it.getLong(0),it.getLong(1)) else null
        }

    @Synchronized
    fun historyContinuityAudited(symbol: String, interval: String): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM sync_state s JOIN history_gap_audit a ON a.symbol=s.symbol AND a.interval=s.interval "+
                "WHERE s.symbol=? AND s.interval=? AND s.candle_count>0 AND a.scanned_through=s.newest_open_time "+
                "AND NOT EXISTS(SELECT 1 FROM history_gap g WHERE g.symbol=s.symbol AND g.interval=s.interval) "+
                "AND NOT EXISTS(SELECT 1 FROM history_invalid_grid i WHERE i.symbol=s.symbol AND i.interval=s.interval)",
            arrayOf(symbol,interval)).use { it.moveToFirst() }

    private fun historySeedRevision(db:SQLiteDatabase,symbol:String,interval:String):Long =
        db.rawQuery(
            "SELECT historical_revision FROM sync_state WHERE symbol=? AND interval=?",
            arrayOf(symbol,interval)
        ).use { if(it.moveToFirst()) it.getLong(0) else 0L }

    @Synchronized
    fun historySeedRevision(symbol:String,interval:String):Long =
        historySeedRevision(readableDatabase,symbol,interval)

    @Synchronized
    fun indicatorCacheRevision(symbol:String,interval:String):Long =
        readableDatabase.rawQuery(
            "SELECT revision FROM closed_indicator_cache_revision WHERE symbol=? AND interval=?",
            arrayOf(symbol,interval)
        ).use { if(it.moveToFirst()) it.getLong(0) else 0L }

    /** Read a result only for an identical CLOSED working set and candle revision. */
    @Synchronized
    fun loadClosedIndicatorSnapshot(
        symbol:String, interval:String, firstMs:Long, lastMs:Long, rows:Int, fingerprint:String
    ): JSONObject? {
        if(firstMs<=0L || lastMs<firstMs || rows<40) return null
        return readableDatabase.rawQuery(
            "SELECT payload,source_revision FROM closed_indicator_cache " +
                "WHERE symbol=? AND interval=? AND model=? " +
                "AND source_first_ms=? AND source_last_ms=? AND source_rows=?",
            arrayOf(symbol,interval,"$closedIndicatorModelVersion:$fingerprint",
                firstMs.toString(),lastMs.toString(),rows.toString())
        ).use { c ->
            if(!c.moveToFirst() || c.getLong(1)!=indicatorCacheRevision(symbol,interval))
                null
            else runCatching { JSONObject(c.getString(0)) }.getOrNull()
        }
    }

    /** Persist only computed read-only indicators, never candles or order decisions. */
    @Synchronized
    fun saveClosedIndicatorSnapshot(
        symbol:String, interval:String, firstMs:Long, lastMs:Long, rows:Int,
        revision:Long, fingerprint:String, payload:JSONObject
    ):Boolean {
        if(firstMs<=0L || lastMs<firstMs || rows<40 ||
            !payload.optBoolean("ready",false) ||
            payload.optLong("time")!=lastMs ||
            revision!=indicatorCacheRevision(symbol,interval)) return false
        val json=payload.toString()
        if(json.length>65_536) return false
        val db=writableDatabase
        return db.transaction {
            // A matching latest timestamp is not proof of matching OHLCV.
            // Re-read the exact persisted window under the SAME transaction.
            val actual=loadRecentSnapshot(symbol,interval,rows,lastMs)
            if(actual.revision!=revision || !actual.continuous ||
                actual.candles.size!=rows || actual.candles.first().openTime!=firstMs ||
                actual.candles.last().openTime!=lastMs || actual.fingerprint!=fingerprint)
                return@transaction false
            db.delete("closed_indicator_cache",
                "symbol=? AND interval=? AND source_revision<>?",
                arrayOf(symbol,interval,revision.toString()))
            val row=ContentValues().apply {
                put("symbol",symbol);put("interval",interval)
                put("model","$closedIndicatorModelVersion:$fingerprint")
                put("source_revision",revision)
                put("source_first_ms",firstMs)
                put("source_last_ms",lastMs)
                put("source_rows",rows)
                put("payload",json)
            }
            val saved=db.insertWithOnConflict("closed_indicator_cache",null,row,
                SQLiteDatabase.CONFLICT_REPLACE)!=-1L
            if(saved) db.execSQL(
                "DELETE FROM closed_indicator_cache WHERE symbol=? AND interval=? AND rowid NOT IN ("+
                "SELECT rowid FROM closed_indicator_cache WHERE symbol=? AND interval=? "+
                "ORDER BY rowid DESC LIMIT 8)",arrayOf(symbol,interval,symbol,interval))
            saved
        }
    }

    @Synchronized
    fun markComplete(symbol: String, interval: String) {
        val db=writableDatabase
        db.transaction {
            val state = ContentValues()
            state.put("symbol", symbol)
            state.put("interval", interval)
            state.put("oldest_open_time", oldest(symbol, interval))
            state.put("newest_open_time", newest(symbol, interval))
            state.put("candle_count", count(symbol, interval))
            state.put("historical_revision", historySeedRevision(db,symbol,interval))
            state.put("complete", 1)
            state.put("updated_at", System.currentTimeMillis())
            db.insertWithOnConflict("sync_state",null,state,SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    @Synchronized
    fun setError(symbol: String, interval: String, message: String?) {
        val db=writableDatabase
        db.transaction {
            val complete=rawQuery(
                "SELECT complete FROM sync_state WHERE symbol=? AND interval=?",
                arrayOf(symbol,interval)
            ).use { it.moveToFirst() && it.getInt(0)==1 }
            val values = ContentValues()
            values.put("symbol", symbol)
            values.put("interval", interval)
            values.put("oldest_open_time", oldest(symbol, interval))
            values.put("newest_open_time", newest(symbol, interval))
            values.put("candle_count", count(symbol, interval))
            values.put("historical_revision", historySeedRevision(db,symbol,interval))
            values.put("complete", if (complete) 1 else 0)
            values.put("updated_at", System.currentTimeMillis())
            values.put("last_error", message)
            db.insertWithOnConflict("sync_state",null,values,SQLiteDatabase.CONFLICT_REPLACE)
        }
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

    /** One SQLite transaction owns both the row content and its revision.
     * Other SQLiteOpenHelper instances cannot interleave a write here. */
    internal data class CandleSnapshot(
        val symbol:String, val interval:String, val revision:Long,
        val candles:List<Candle>, val fingerprint:String, val continuous:Boolean,
        val seedRevision:Long=0L
    ) {
        val environment:String get() = "BINANCE_USDM_DEMO"
        val source:String get() = "CONTRACT_PRICE"
    }

    @Synchronized
    internal fun loadRecentSnapshot(symbol:String,interval:String,limit:Int,
        beforeMs:Long?=null):CandleSnapshot {
        val db=writableDatabase
        return db.transaction {
            val rows=java.util.Collections.unmodifiableList(
                ArrayList(loadRecent(symbol,interval,limit,beforeMs)))
            val continuous=rows.isNotEmpty() && rows.all {
                nativeHistoryCloseMs(it.openTime,interval)==it.closeTime &&
                    validNativeCandle(CandleN(it.openTime,it.open,it.high,it.low,it.close,it.volume))
            } && rows.zipWithNext().all { (a,b)->a.closeTime+1L==b.openTime }
            val fingerprint=closedCandleFingerprint(rows.map {
                CandleN(it.openTime,it.open,it.high,it.low,it.close,it.volume)
            })
            CandleSnapshot(symbol,interval,indicatorCacheRevision(symbol,interval),rows,
                fingerprint,continuous,historySeedRevision(db,symbol,interval))
        }
    }

    @Synchronized
    internal fun waveSourceRevision(symbol:String):Long=readableDatabase.rawQuery(
        "SELECT revision FROM market_history_revision WHERE symbol=?",arrayOf(symbol)
    ).use { if(it.moveToFirst()) it.getLong(0) else 0L }

    @Synchronized
    internal fun waveClockPage(symbol:String,after:Long,through:Long,limit:Int=32):List<Long> {
        val out=mutableListOf<Long>()
        readableDatabase.rawQuery(
            deviceWaveReplayClockSql,
            arrayOf(symbol,after.toString(),through.toString(),through.toString(),limit.coerceIn(1,64).toString())
        ).use { c->while(c.moveToNext()) out+=c.getLong(0) }
        return out
    }

    @Synchronized
    internal fun waveTargetWindow(symbol:String,frame:String,start:Long,end:Long,asOf:Long):List<Candle> {
        val ms=williamsWaveFrames[frame]?:return emptyList()
        if(start<=0 || end<=start || end>asOf || (end-start)/ms>450) return emptyList()
        val out=mutableListOf<Candle>()
        readableDatabase.rawQuery(
            "SELECT open_time,close_time,open,high,low,close,volume FROM candles "+
                "WHERE symbol=? AND interval=? AND open_time>=? AND open_time<? AND close_time<? "+
                "ORDER BY open_time LIMIT 512",
            arrayOf(symbol,frame,(start-35*ms).coerceAtLeast(0).toString(),end.toString(),asOf.toString())
        ).use { c->while(c.moveToNext()) out+=Candle(c.getLong(0),c.getDouble(2),c.getDouble(3),
            c.getDouble(4),c.getDouble(5),c.getDouble(6),c.getLong(1)) }
        return out
    }

    @Synchronized
    internal fun waveArchiveReady(symbol:String,frames:List<String>):Boolean {
        val ready=mutableSetOf<String>()
        readableDatabase.rawQuery(
            "SELECT interval FROM sync_state WHERE symbol=? AND complete=1",arrayOf(symbol)
        ).use { c->while(c.moveToNext()) ready+=c.getString(0) }
        return ready.containsAll(frames) && frames.all { historyContinuityAudited(symbol,it) }
    }

    private fun packWaveJson(value:JSONObject):ByteArray {
        val out=ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(value.toString().toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    private fun unpackWaveJson(value:ByteArray):JSONObject {
        val out=ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(value)).use { input->
            val buffer=ByteArray(4096)
            while(true) {
                val n=input.read(buffer);if(n<0) break
                require(out.size()+n<=2*1024*1024) { "DEVICE_WAVE_CHECKPOINT_TOO_LARGE" }
                out.write(buffer,0,n)
            }
        }
        return JSONObject(out.toString("UTF-8"))
    }

    @Synchronized
    internal fun prepareWaveReplay(symbol:String):DeviceWaveReplayCursor {
        val db=writableDatabase
        return db.transaction {
            var resetReason="NEW"
            db.rawQuery("SELECT model,last_closed_ms,processed,chain_sha256,checkpoint,dirty_from_ms "+
                "FROM wave_replay_state WHERE symbol=?",arrayOf(symbol)).use { c->
                if(c.moveToFirst()) {
                    resetReason=when {
                        c.getString(0)!=deviceWaveReplayModel->"MODEL_CHANGED_RESTARTED"
                        c.getLong(5)>0->"SOURCE_CHANGED_RESTARTED"
                        else->"CHECKPOINT_RECOVERED_RESTARTED"
                    }
                    if(c.getString(0)==deviceWaveReplayModel && c.getLong(5)==0L) {
                        if(c.getLong(1)==0L && c.isNull(4)) return@transaction DeviceWaveReplayCursor()
                        val parsed=runCatching {
                            val json=unpackWaveJson(c.getBlob(4));val cursor=deviceWaveCursorFromJson(json)
                            require(cursor.lastClosedMs==c.getLong(1) && cursor.processed==c.getLong(2) &&
                                cursor.chain==c.getString(3)) { "CHECKPOINT_CURSOR_MISMATCH" }
                            require(canonicalWaveJson(JSONObject(cursor.records))==
                                canonicalWaveJson(JSONObject(adaptiveReplayRecords(json.getJSONObject("report")))))
                            val reportHash=waveEvidenceSha256(canonicalWaveJson(json.getJSONObject("report")))
                            db.rawQuery("SELECT state_sha256,chain_sha256 FROM wave_replay_journal "+
                                "WHERE symbol=? AND closed_ms=?",arrayOf(symbol,cursor.lastClosedMs.toString())).use { last->
                                require(last.moveToFirst() && last.getString(0)==reportHash &&
                                    last.getString(1)==cursor.chain) { "CHECKPOINT_STATE_HASH_MISMATCH" }
                            }
                            cursor
                        }
                        parsed.getOrNull()?.let { return@transaction it }
                    }
                }
            }
            delete("wave_replay_journal","symbol=?",arrayOf(symbol))
            delete("wave_replay_state","symbol=?",arrayOf(symbol))
            val state=ContentValues().apply {
                put("symbol",symbol);put("model",deviceWaveReplayModel)
                put("status",resetReason);put("updated_at",System.currentTimeMillis())
            }
            insertOrThrow("wave_replay_state",null,state)
            DeviceWaveReplayCursor()
        }
    }

    @Synchronized
    internal fun commitWaveReplay(
        symbol:String,expected:DeviceWaveReplayCursor,sourceRevision:Long,step:DeviceWaveReplayStep
    ):Boolean {
        val payload=packWaveJson(step.journal)
        val checkpoint=packWaveJson(step.checkpoint)
        require(step.cursor.lastClosedMs>expected.lastClosedMs && step.cursor.processed==expected.processed+1)
        return writableDatabase.transaction {
            val prior=rawQuery(deviceWaveReplayCommitGuardSql,arrayOf(symbol,expected.lastClosedMs.toString(),
                deviceWaveReplayModel,expected.chain,expected.processed.toString(),sourceRevision.toString()))
                .use { it.moveToFirst() }
            if(!prior) return@transaction false
            val bytes=rawQuery("SELECT COALESCE(SUM(journal_bytes),0) FROM wave_replay_state",null)
                .use { it.moveToFirst();it.getLong(0) }
            check(bytes+payload.size<512L*1024*1024) { "DEVICE_WAVE_JOURNAL_STORAGE_BUDGET" }
            val row=ContentValues().apply {
                put("symbol",symbol);put("closed_ms",step.cursor.lastClosedMs)
                put("state_sha256",step.stateHash);put("chain_sha256",step.cursor.chain);put("payload",payload)
            }
            insertOrThrow("wave_replay_journal",null,row)
            execSQL("UPDATE wave_replay_state SET last_closed_ms=?,processed=?,chain_sha256=?,checkpoint=?, "+
                "journal_bytes=journal_bytes+?,status='REPLAYING',last_error=NULL,updated_at=? WHERE symbol=?",
                arrayOf(step.cursor.lastClosedMs,step.cursor.processed,step.cursor.chain,checkpoint,
                    payload.size,System.currentTimeMillis(),symbol))
            true
        }
    }

    @Synchronized
    internal fun waveReplayStatusUpdate(symbol:String,status:String,error:String?=null) {
        val values=ContentValues().apply {
            put("status",status);put("last_error",error);put("updated_at",System.currentTimeMillis())
        }
        val initial=ContentValues().apply {
            put("symbol",symbol);put("model",deviceWaveReplayModel)
        }
        writableDatabase.insertWithOnConflict("wave_replay_state",null,initial,SQLiteDatabase.CONFLICT_IGNORE)
        writableDatabase.update("wave_replay_state",values,"symbol=? AND dirty_from_ms=0",arrayOf(symbol))
    }

    @Synchronized
    internal fun waveReplayStatus():JSONObject {
        val rows=JSONArray()
        readableDatabase.rawQuery("SELECT symbol,model,last_closed_ms,processed,journal_bytes,status,"+
            "dirty_from_ms,last_error,updated_at FROM wave_replay_state ORDER BY symbol",null).use { c->
            while(c.moveToNext()) rows.put(JSONObject().put("symbol",c.getString(0)).put("model",c.getString(1))
                .put("last_replayed_closed_ms",c.getLong(2)).put("processed_h1_closes",c.getLong(3))
                .put("journal_compressed_bytes",c.getLong(4)).put("status",c.getString(5))
                .put("invalidated_from_ms",c.getLong(6)).put("last_error",if(c.isNull(7)) JSONObject.NULL else c.getString(7))
                .put("updated_at_ms",c.getLong(8)))
        }
        return JSONObject().put("schema","DEVICE_H1_WAVE_ARCHIVE_REPLAY_V2")
            .put("clock_frame","1h").put("analysis_window_limit",deviceWaveReplayLimit)
            .put("timeline","ALL_STORED_CLOSED_H1_CANDLES")
            .put("journal_detail","STATE_HASHES_AND_STRUCTURAL_REVISIONS; LATEST_FULL_CHECKPOINT")
            .put("items",rows).put("can_place_orders",false).put("can_close_positions",false)
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
                "newest_open_time",
                "last_error"
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
                    .put("last_error",if(cursor.isNull(6)) JSONObject.NULL else cursor.getString(6))
            }
        }

        val audit = HashMap<Pair<String,String>,Long>()
        readableDatabase.rawQuery("SELECT symbol,interval,scanned_through FROM history_gap_audit",null).use { c ->
            while(c.moveToNext()) audit[c.getString(0) to c.getString(1)]=c.getLong(2)
        }
        val gaps = HashMap<Pair<String,String>,Triple<Long,Long,Long>>()
        readableDatabase.rawQuery("SELECT g.symbol,g.interval,g.n,g.first_start,"+
            "(SELECT MAX(c.open_time) FROM candles c WHERE c.symbol=g.symbol AND c.interval=g.interval "+
            "AND c.open_time<g.first_start) FROM "+
            "(SELECT symbol,interval,COUNT(*) AS n,MIN(start_ms) AS first_start FROM history_gap GROUP BY symbol,interval) g",null).use { c ->
            while(c.moveToNext()) gaps[c.getString(0) to c.getString(1)]=Triple(c.getLong(2),c.getLong(3),c.getLong(4))
        }
        val invalid = HashSet<Pair<String,String>>()
        readableDatabase.rawQuery("SELECT DISTINCT symbol,interval FROM history_invalid_grid",null).use { c ->
            while(c.moveToNext()) invalid.add(c.getString(0) to c.getString(1))
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
                val key=symbol to interval
                val earliestReached = row.optBoolean("complete", false)
                val auditedThrough = audit[key] ?: 0L
                val gap = gaps[key]
                val verified = count>0L && auditedThrough==row.optLong("newest_open_time") &&
                    gap==null && key !in invalid
                val complete = earliestReached && verified
                row.put("earliest_history_reached",earliestReached)
                    .put("complete",complete)
                    .put("continuity_audited",verified)
                    .put("gap_audited_through_ms",auditedThrough)
                    .put("known_gap_count",gap?.first ?: 0L)
                    .put("first_missing_open_ms",gap?.second ?: JSONObject.NULL)
                    .put("legacy_grid_invalid",key in invalid)
                    .put("last_continuous_verified_open_ms",
                        if(key in invalid || auditedThrough==0L) JSONObject.NULL
                        else if(gap==null || gap.second>auditedThrough) auditedThrough
                        else if(gap.third>0L) minOf(auditedThrough,gap.third) else JSONObject.NULL)
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
