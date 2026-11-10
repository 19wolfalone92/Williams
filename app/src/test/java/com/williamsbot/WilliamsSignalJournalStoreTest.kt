package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regressions for per-intent-save disk pressure in native Futures reconciliation. */
class WilliamsSignalJournalStoreTest {
    private class FakeStore : FuturesCampaignStateStore {
        val values = mutableMapOf<String,String>()
        var reads = 0
        var writes = 0
        override fun readState(key:String):String? {
            reads++
            return values[key]
        }
        override fun writeState(key:String,value:String):Boolean {
            writes++
            values[key] = value
            return true
        }
    }

    private val now = 1_800_000_000_000L
    private fun sourceSignal(symbol:String,id:String):JSONObject =
        JSONObject().put("symbol",symbol).put("signal_id",id)
            .put("known_at_ms",now-3_600_000L)
            .put("last_seen_at_ms",now).put("expires_at_ms",now+3_600_000L)
            .put("type","FRACTAL").put("direction","LONG")
            .put("strategy_profile",williamsTc2Profile)
            .put("signal_bar_time_ms",now-3_600_000L)
    private fun owner(symbol:String,id:String,status:String):JSONObject =
        JSONObject().put("symbol",symbol).put("signal_id",id)
            .put("client_id","W2H_${symbol}_test")
            .put("intent_role","ENTRY").put("direction","LONG")
            .put("order_status",status).put("campaign_status","ENTRY_PENDING")
            .put("last_reconciled_ms",now-1000L)
            .put("entry_price","100").put("protective_stop","95")

    @Test fun unchangedSignedReconciliationNeverRewritesTheFifteenSignalTimelines() {
        val store=FakeStore()
        val all=JSONArray()
        for(symbol in APPROVED_FUTURES_SYMBOLS) {
            val id="signal_${symbol}"
            store.values["signal_timeline_${symbol}"]=JSONObject()
                .put("signals",JSONArray().put(sourceSignal(symbol,id)))
                .put("events",JSONArray()).put("snapshot_at_ms",now).toString()
            all.put(owner(symbol,id,"NEW"))
        }
        val journal=WilliamsSignalJournal(store)
        journal.orders(all,now)
        assertEquals(15,store.reads) // old code loaded every symbol twice
        assertEquals(15,store.writes)
        for(symbol in APPROVED_FUTURES_SYMBOLS) {
            val data=JSONObject(store.values.getValue("signal_timeline_${symbol}"))
            assertEquals(1,data.getJSONArray("events").length())
            assertEquals("BINANCE_PENDING",data.getJSONArray("events")
                .getJSONObject(0).getString("state"))
        }
        journal.orders(all,now)
        assertEquals(30,store.reads)
        assertEquals("No-op signed reconciliation must not rewrite SQLite",15,store.writes)

        // A genuine exchange status transition is still durable, without
        // rewriting fourteen other symbols' untouched histories.
        all.getJSONObject(0).put("order_status","FILLED")
        journal.orders(all,now+1000L)
        assertEquals(16,store.writes)
        val changed=JSONObject(store.values.getValue(
            "signal_timeline_${APPROVED_FUTURES_SYMBOLS.first()}"))
        assertEquals("FILLED",changed.getJSONArray("events")
            .getJSONObject(1).getString("state"))
    }

    @Test fun fullTwoHundredFiftySixEventRetentionStillPersistsNewStatus() {
        val store=FakeStore()
        val symbol="BTCUSDT"
        val oldEvents=JSONArray()
        for(index in 0 until 256) {
            oldEvents.put(JSONObject().put("event_id","previous_${index}")
                .put("state","PAST"))
        }
        store.values["signal_timeline_${symbol}"]=JSONObject()
            .put("signals",JSONArray().put(sourceSignal(symbol,"latest")))
            .put("events",oldEvents).put("snapshot_at_ms",now).toString()
        val journal=WilliamsSignalJournal(store)
        journal.orders(JSONArray().put(owner(symbol,"latest","FILLED")),now)
        assertEquals(1,store.writes)
        val events=JSONObject(store.values.getValue("signal_timeline_${symbol}"))
            .getJSONArray("events")
        assertEquals(256,events.length())
        assertFalse(events.getJSONObject(0).getString("event_id")=="previous_0")
        assertEquals("FILLED",events.getJSONObject(255).getString("state"))
        journal.orders(JSONArray().put(owner(symbol,"latest","FILLED")),now)
        assertEquals(1,store.writes)
    }

    @Test fun emptyHistoricalOwnersDoNotCreatePhantomSignalHistory() {
        val store=FakeStore()
        val journal=WilliamsSignalJournal(store)
        journal.orders(JSONArray().put(JSONObject().put("symbol","BTCUSDT")
            .put("client_id","historical").put("campaign_status","CLOSED_OR_STOPPED")),now)
        assertEquals(1,store.reads)
        assertEquals(0,store.writes)
        assertTrue(store.values.isEmpty())
    }
}
