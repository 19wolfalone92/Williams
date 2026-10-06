package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OrderBookCacheTest {
    private fun levels(vararg rows: Pair<String, String>): JSONArray =
        JSONArray().apply {
            rows.forEach { (price, qty) ->
                put(JSONArray().put(price).put(qty))
            }
        }

    @Test
    fun applies_contiguous_depth_updates_and_estimates_slippage() {
        val cache = OrderBookCache(maxLevels = 10)
        cache.seed(
            "BTCUSDT",
            JSONObject()
                .put("lastUpdateId", 100)
                .put("bids", levels("99" to "5"))
                .put("asks", levels("101" to "5", "102" to "5"))
        )

        assertEquals(
            true,
            cache.apply(
                "BTCUSDT",
                101,
                102,
                levels("99" to "4"),
                levels("101" to "1")
            )
        )

        val estimate = cache.estimateBuy("BTCUSDT", 101.0)
        assertNotNull(estimate)
        assertEquals(101.0, estimate!!.first, 0.000001)
        assertEquals(0.0, estimate.second, 0.000001)
    }

    @Test
    fun rejects_sequence_gap_and_keeps_old_book() {
        val cache = OrderBookCache()
        cache.seed(
            "ETHUSDT",
            JSONObject()
                .put("lastUpdateId", 50)
                .put("bids", levels("99" to "1"))
                .put("asks", levels("101" to "1"))
        )

        assertEquals(
            false,
            cache.apply(
                "ETHUSDT",
                52,
                53,
                levels("99" to "0"),
                levels("101" to "0")
            )
        )

        assertNull(cache.estimateBuy("UNKNOWN", 10.0))
    }
}
