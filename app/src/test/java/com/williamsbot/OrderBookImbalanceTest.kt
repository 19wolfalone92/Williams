package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderBookImbalanceTest {
    private fun levels(vararg rows: Pair<String, String>): JSONArray =
        JSONArray().apply { rows.forEach { (p, q) -> put(JSONArray().put(p).put(q)) } }

    @Test
    fun computes_positive_and_fresh_book() {
        val cache = OrderBookCache(maxLevels = 20)
        cache.seed("BTCUSDT", JSONObject()
            .put("lastUpdateId", 1)
            .put("bids", levels("100" to "9", "99" to "1"))
            .put("asks", levels("101" to "1", "102" to "1")))
        assertEquals(0.666666, cache.imbalance("BTCUSDT", 20) ?: error("missing imbalance"), 0.00001)
        assertTrue(cache.isFresh("BTCUSDT"))
    }
}
