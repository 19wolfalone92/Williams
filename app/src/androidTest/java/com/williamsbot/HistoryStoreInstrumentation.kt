package com.williamsbot

import android.app.Activity
import android.app.Instrumentation
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle

/**
 * Device-only, isolated test APK. Uses the instrumentation package database,
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
        val app=context
        check(app.packageName!=targetContext.packageName) { "test database must be isolated" }
        val dbName="williams_market_history.db"
        app.deleteDatabase(dbName)
        val h=3_600_000L
        val symbol="BTCUSDT"
        val frame="1h"
        fun candle(hour:Long,close:Double=10.0)=MarketHistoryStore.Candle(
            hour*h,10.0,12.0,9.0,close,5.0,(hour+1)*h-1L)
        try {
            MarketHistoryStore(app).use { store ->
                store.upsertBatch(symbol,frame,listOf(candle(10),candle(11),candle(15)))
                check(store.firstMissingRange(symbol,frame)==MarketHistoryStore.MissingRange(12*h,15*h))
                val rev=store.indicatorCacheRevision(symbol,frame)
                store.upsertBatch(symbol,frame,listOf(candle(11)))
                check(store.indicatorCacheRevision(symbol,frame)==rev)
                store.upsertBatch(symbol,frame,listOf(candle(11,11.0)))
                store.upsertBatch(symbol,frame,listOf(candle(11,10.0),candle(13)),expectedRevision=rev)
                check(store.loadRecent(symbol,frame,10).first { it.openTime==11*h }.close==11.0)
                check(store.firstMissingRange(symbol,frame)==MarketHistoryStore.MissingRange(12*h,13*h))
                check(!store.loadRecentSnapshot(symbol,frame,10).continuous)
                store.markComplete(symbol,frame)
            }
            // Reconstruct the previous schema from the same database file:
            // v5 adds exactly these three tables and trigger. Existing candle,
            // revision and replay schema/data remain intact.
            SQLiteDatabase.openDatabase(app.getDatabasePath(dbName).path,null,
                SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("DROP TRIGGER history_gap_after_insert")
                db.execSQL("DROP TABLE history_gap")
                db.execSQL("DROP TABLE history_gap_audit")
                db.execSQL("DROP TABLE history_invalid_grid")
                db.version=4
            }
            MarketHistoryStore(app).use { store ->
                check(store.count(symbol,frame)==4L)
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
                check(snapshot.candles.first { it.openTime==11*h }.close==11.0)
                check(store.isComplete(symbol,frame))
                val oldRevision=snapshot.revision
                val bad=candle(16).copy(closeTime=17*h)
                check(runCatching { store.upsertBatch(symbol,frame,listOf(candle(16),bad)) }.isFailure)
                check(store.count(symbol,frame)==6L)
                check(store.indicatorCacheRevision(symbol,frame)==oldRevision)
            }
        } finally {
            app.deleteDatabase(dbName)
        }
    }
}
