package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderBookCacheSequenceTest {
    private fun snapshot(lastUpdateId: Long): JSONObject =
        JSONObject()
            .put("lastUpdateId", lastUpdateId)
            .put("bids", JSONArray().put(JSONArray().put("100.0").put("1.0")))
            .put("asks", JSONArray().put(JSONArray().put("101.0").put("1.0")))

    private fun levels(price: String, qty: String): JSONArray =
        JSONArray().put(JSONArray().put(price).put(qty))

    @Test
    fun contiguousDepthUpdatesAreAccepted() {
        val cache = OrderBookCache()
        assertTrue(cache.seed("BTCUSDT", snapshot(100)))
        assertTrue(cache.apply("BTCUSDT", 101, 102, levels("100.0", "2.0"), levels("101.0", "2.0")))
        assertTrue(cache.status().getJSONObject("books").getJSONObject("BTCUSDT").getLong("last_update_id") == 102L)
    }

    @Test
    fun sequenceGapIsRejectedForRestResync() {
        val cache = OrderBookCache()
        assertTrue(cache.seed("BTCUSDT", snapshot(100)))
        assertFalse(cache.apply("BTCUSDT", 105, 106, levels("100.0", "3.0"), levels("101.0", "3.0")))
        assertTrue(cache.status().getJSONObject("books").getJSONObject("BTCUSDT").getLong("last_update_id") == 100L)
    }
}
