package com.williamsbot

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Append-only on-device TC1/NTD/Course observer study archive.
 *
 * Separate SQLite database from exchange intents, fills, risk and TradeJournal.
 * Never reports Binance orders/executions. Replaying after a crash is safe
 * because every event has a deterministic durable primary key.
 *
 * No 240-event truncation: the companion in-memory/kv snapshot is bounded
 * for fast scans, but every event is retained here for future book analysis.
 */
internal class WilliamsObserverPaperArchive(context:Context) :
    SQLiteOpenHelper(context.applicationContext,"williams_observer_paper.sqlite",null,1) {

    override fun onCreate(db:SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS observer_events(
                event_id TEXT PRIMARY KEY NOT NULL,
                symbol TEXT NOT NULL,
                profile TEXT NOT NULL,
                occurred_at_ms INTEGER NOT NULL,
                kind TEXT NOT NULL,
                signal_id TEXT NOT NULL,
                evidence_json TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS observer_by_source ON observer_events(symbol, profile, occurred_at_ms)")
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int) = onCreate(db)

    private fun eventId(row:JSONObject,symbol:String,profile:String):String {
        val key=listOf(symbol,profile,row.optString("event"),
            row.optLong("at_ms").toString(),row.optString("signal_id"),
            row.optString("direction"),row.optString("signal_type"))
            .joinToString("|")
        return MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    @Synchronized
    fun append(symbol:String,profile:String,snapshot:JSONObject) {
        require(profile in setOf(williamsNtdProfile,williamsCourseProfile,WILLIAMS_TC1_OBSERVER))
        require(snapshot.optString("symbol")==symbol && snapshot.optString("profile")==profile)
        val rows=snapshot.optJSONArray("events") ?: JSONArray()
        writableDatabase.transaction {
            for(i in 0 until rows.length()) {
                val event=rows.optJSONObject(i) ?: error("MALFORMED_OBSERVER_EVENT")
                require(event.optString("profile")==profile && event.optString("symbol")==symbol &&
                    event.optBoolean("hypothetical_only",false) && event.optLong("at_ms")>0L) {
                    "OBSERVER_ARCHIVE_EVENT_SOURCE_UNVERIFIED"
                }
                // The Android insertWithOnConflict(...IGNORE) return value
                // is -1 for an existing primary key. That is SUCCESSFUL
                // idempotent replay, not a disk failure.
                execSQL("""
                    INSERT OR IGNORE INTO observer_events
                    (event_id,symbol,profile,occurred_at_ms,kind,signal_id,evidence_json)
                    VALUES(?,?,?,?,?,?,?)
                """.trimIndent(),arrayOf<Any>(
                    eventId(event,symbol,profile),symbol,profile,
                    event.getLong("at_ms"),event.optString("event"),
                    event.optString("signal_id"),event.toString()
                ))
            }
        }
    }

    /** Bounded export view, not a bounded retention policy. */
    @Synchronized
    fun snapshot(symbol:String,profile:String,limit:Int=250):JSONObject {
        require(symbol.matches(Regex("[A-Z0-9]{4,20}")) &&
            profile in setOf(williamsNtdProfile,williamsCourseProfile,WILLIAMS_TC1_OBSERVER))
        require(limit in 1..5_000)
        val db=readableDatabase
        val count=db.rawQuery(
            "SELECT COUNT(*) FROM observer_events WHERE symbol=? AND profile=?",
            arrayOf(symbol,profile)).use { cursor ->
            if(cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
        val collected=mutableListOf<JSONObject>()
        db.rawQuery("""
            SELECT evidence_json FROM observer_events
            WHERE symbol=? AND profile=?
            ORDER BY occurred_at_ms DESC, event_id DESC LIMIT ?
        """.trimIndent(),arrayOf(symbol,profile,limit.toString())).use { cursor ->
            while(cursor.moveToNext()) collected+=JSONObject(cursor.getString(0))
        }
        return JSONObject()
            .put("schema","WILLIAMS_OBSERVER_PAPER_ARCHIVE_V1")
            .put("symbol",symbol).put("profile",profile)
            .put("storage","APP_PRIVATE_SQLITE_APPEND_ONLY")
            .put("retained_event_count",count)
            .put("returned_event_count",collected.size)
            .put("export_truncated",count>collected.size)
            .put("events",JSONArray(collected.reversed()))
            .put("exchange_orders",false).put("hypothetical_only",true)
            .put("can_place_orders",false)
    }
}
