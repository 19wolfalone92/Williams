package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsDurableHistoryEvidenceTest {
    private val symbols=listOf("SOLUSDT","BTCUSDT")
    private val frames=listOf("1h","1d")
    private fun row(symbol:String,frame:String,complete:Boolean) =
        JSONObject().put("symbol",symbol).put("interval",frame)
            .put("full_history_complete",complete)

    @Test fun completedSqliteRowsMustNotBeUndercountedByWorkerPassCounter() {
        // User diagnostics (3): background worker counter still 0/120
        // even while 30 full-history D1/W1/M1 pairs were already durable.
        val items=JSONArray()
            .put(row("SOLUSDT","1h",false)).put(row("SOLUSDT","1d",true))
            .put(row("BTCUSDT","1h",false)).put(row("BTCUSDT","1d",true))
        val status=JSONObject()
            .put("history",JSONObject().put("items",items))
            .put("persistent_history_complete_pairs",0)
            .put("persistent_history_total_pairs",4)
            .put("persistent_history_last_error",JSONObject.NULL)
            .put("historical_wave_replay",JSONObject().put("items",JSONArray()))
        val evidence=williamsDurableHistoryEvidence(status,symbols,frames)
        assertEquals(2,evidence.getInt("sqlite_full_history_pairs"))
        assertEquals(0,evidence.getInt("worker_completed_pairs_after_last_pass"))
        assertEquals(4,evidence.getInt("observed_symbol_frame_pairs"))
        assertEquals("NOT_VERIFIED",evidence.getString("severity"))
        assertFalse(evidence.getBoolean("book_wave_accuracy_certified"))
        assertEquals("",evidence.getString("sync_error"))
    }

    @Test fun missingRowOrDuplicateEvidenceCannotCertifyArchive() {
        val all=JSONArray().put(row("SOLUSDT","1h",true))
            .put(row("SOLUSDT","1d",true))
            .put(row("BTCUSDT","1h",true))
            .put(row("BTCUSDT","1d",true))
            .put(row("SOLUSDT","1d",true))
        val status=JSONObject().put("history",JSONObject().put("items",all))
            .put("persistent_history_complete_pairs",4)
            .put("persistent_history_total_pairs",4)
        val e=williamsDurableHistoryEvidence(status,symbols,frames)
        assertEquals("NOT_VERIFIED",e.getString("severity"))
        assertEquals(3,e.getInt("observed_symbol_frame_pairs"))
        assertEquals(3,e.getInt("sqlite_full_history_pairs"))
    }

    @Test fun fullSqliteAndReplayProofOnlyCertifiesDataProcessingNotWaveAccuracy() {
        val all=JSONArray()
        for(symbol in symbols) for(frame in frames) all.put(row(symbol,frame,true))
        val replay=JSONArray()
            .put(JSONObject().put("symbol","SOLUSDT").put("processed_h1_closes",12))
            .put(JSONObject().put("symbol","BTCUSDT").put("processed_h1_closes",12))
        val status=JSONObject().put("history",JSONObject().put("items",all))
            .put("persistent_history_complete_pairs",4)
            .put("persistent_history_total_pairs",4)
            .put("historical_wave_replay",JSONObject().put("items",replay))
        val e=williamsDurableHistoryEvidence(status,symbols,frames)
        assertEquals("PASS",e.getString("severity"))
        assertEquals(4,e.getInt("sqlite_full_history_pairs"))
        assertEquals(2,e.getInt("h1_replayed_symbols"))
        assertFalse(e.getBoolean("book_wave_accuracy_certified"))
        status.put("persistent_history_complete_pairs",5)
        val ahead=williamsDurableHistoryEvidence(status,symbols,frames)
        assertEquals("WARNING",ahead.getString("severity"))
        assertTrue(ahead.getBoolean("worker_counter_ahead_of_sqlite"))
    }

    @Test fun storageAlertIsWarningWithoutClaimingWaveFailure() {
        val s=JSONObject()
            .put("persistent_history_storage_low",true)
            .put("persistent_history_last_error","LOW_DEVICE_STORAGE")
        val e=williamsDurableHistoryEvidence(s,symbols,frames)
        assertEquals("WARNING",e.getString("severity"))
        assertEquals(0,e.getInt("sqlite_full_history_pairs"))
        assertFalse(e.getBoolean("book_wave_accuracy_certified"))
    }
}
