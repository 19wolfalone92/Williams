package com.williamsbot

import android.app.Activity
import android.app.Instrumentation
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle

/**
 * Device-only, isolated test APK. Uses a separately named disposable database,
 * never the operator's application database, credentials or exchange.
 */
class HistoryStoreInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result=Bundle()
        try {
            exerciseHistory()
            result.putString("history_result","HISTORY_SQLITE_PASS")
            result.putString("stream","\nHISTORY_SQLITE_PASS\n")
            finish(Activity.RESULT_OK,result)
        } catch(failure:Throwable) {
            result.putString("stream","\nHISTORY_SQLITE_FAIL: "+failure.stackTraceToString()+"\n")
            finish(Activity.RESULT_CANCELED,result)
        }
    }

    private fun exerciseHistory() {
        val app=targetContext
        val dbName="williams_history_instrumentation_test.db"
        app.deleteDatabase(dbName)
        val h=3_600_000L
        val symbol="BTCUSDT"
        val frame="1h"
        fun candle(hour:Long,close:Double=10.0)=MarketHistoryStore.Candle(
            hour*h,10.0,12.0,9.0,close,5.0,(hour+1)*h-1L)
        try {
            MarketHistoryStore(app,dbName).use { store ->
                store.upsertBatch(symbol,frame,listOf(candle(10),candle(11),candle(15)))
                check(store.firstMissingRange(symbol,frame)==MarketHistoryStore.MissingRange(12*h,15*h))
                check(store.historySeedRevision(symbol,frame)==0L)
                val rev=store.indicatorCacheRevision(symbol,frame)
                store.upsertBatch(symbol,frame,listOf(candle(11)))
                check(store.indicatorCacheRevision(symbol,frame)==rev)
                check(store.historySeedRevision(symbol,frame)==0L)
                store.upsertBatch(symbol,frame,listOf(candle(11,11.0)))
                check(store.historySeedRevision(symbol,frame)==1L)
                store.upsertBatch(symbol,frame,listOf(candle(11,10.0),candle(13)),expectedRevision=rev)
                check(store.loadRecent(symbol,frame,10).first { it.openTime==11*h }.close==11.0)
                check(store.historySeedRevision(symbol,frame)==2L)
                check(store.firstMissingRange(symbol,frame)==MarketHistoryStore.MissingRange(12*h,13*h))
                check(!store.loadRecentSnapshot(symbol,frame,10).continuous)
                store.markComplete(symbol,frame)
                check(store.historySeedRevision(symbol,frame)==2L)
                store.setError(symbol,frame,"instrumentation")
                check(store.historySeedRevision(symbol,frame)==2L)
            }
            // Reconstruct a v4 sync_state and gap schema in the same disposable
            // database. Upgrade must add both the gap journal and v6 seed revision.
            SQLiteDatabase.openDatabase(app.getDatabasePath(dbName).path,null,
                SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("DROP TRIGGER history_gap_after_insert")
                db.execSQL("DROP TABLE history_gap")
                db.execSQL("DROP TABLE history_gap_audit")
                db.execSQL("DROP TABLE history_invalid_grid")
                db.execSQL("ALTER TABLE sync_state RENAME TO sync_state_v6")
                db.execSQL("""CREATE TABLE sync_state(
                    symbol TEXT NOT NULL, interval TEXT NOT NULL,
                    oldest_open_time INTEGER NOT NULL DEFAULT 0,
                    newest_open_time INTEGER NOT NULL DEFAULT 0,
                    candle_count INTEGER NOT NULL DEFAULT 0,
                    complete INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL DEFAULT 0,
                    last_error TEXT,
                    PRIMARY KEY(symbol,interval))""")
                db.execSQL("""INSERT INTO sync_state(
                    symbol,interval,oldest_open_time,newest_open_time,candle_count,complete,updated_at,last_error)
                    SELECT symbol,interval,oldest_open_time,newest_open_time,candle_count,complete,updated_at,last_error
                    FROM sync_state_v6""")
                db.execSQL("DROP TABLE sync_state_v6")
                db.version=4
            }
            MarketHistoryStore(app,dbName).use { store ->
                check(store.count(symbol,frame)==4L)
                check(store.historySeedRevision(symbol,frame)==0L)
                check(!store.historyContinuityAudited(symbol,frame))
                store.auditHistoryGapPage(symbol,frame,2)
                check(!store.historyContinuityAudited(symbol,frame))
                store.auditHistoryGapPage(symbol,frame,2)
                check(store.firstMissingRange(symbol,frame)==MarketHistoryStore.MissingRange(12*h,13*h))
                store.upsertBatch(symbol,frame,listOf(candle(14),candle(12)),missingOnly=true)
                check(store.firstMissingRange(symbol,frame)==null)
                check(store.historyContinuityAudited(symbol,frame))
                val snapshot=store.loadRecentSnapshot(symbol,frame,10)
                check(snapshot.continuous && snapshot.candles.size==6)
                check(snapshot.seedRevision==1L)
                check(snapshot.candles.first { it.openTime==11*h }.close==11.0)
                check(store.isComplete(symbol,frame))
                val seedBeforeTail=store.historySeedRevision(symbol,frame)
                store.upsertBatch(symbol,frame,listOf(candle(16)))
                check(store.historySeedRevision(symbol,frame)==seedBeforeTail)
                store.upsertBatch(symbol,frame,listOf(candle(16,11.0)))
                check(store.historySeedRevision(symbol,frame)==seedBeforeTail+1L)
                store.markComplete(symbol,frame)
                store.setError(symbol,frame,null)
                check(store.historySeedRevision(symbol,frame)==seedBeforeTail+1L)
                val oldRevision=store.indicatorCacheRevision(symbol,frame)
                val bad=candle(17).copy(closeTime=18*h)
                check(runCatching { store.upsertBatch(symbol,frame,listOf(candle(17),bad)) }.isFailure)
                check(store.count(symbol,frame)==7L)
                check(store.indicatorCacheRevision(symbol,frame)==oldRevision)
                check(store.historySeedRevision(symbol,frame)==seedBeforeTail+1L)
            }
        } finally {
            app.deleteDatabase(dbName)
        }
    }
}
