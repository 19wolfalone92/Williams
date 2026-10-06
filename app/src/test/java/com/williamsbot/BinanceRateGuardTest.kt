package com.williamsbot

import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class BinanceRateGuardTest {
    @Test
    fun learnsTenSecondOrderLimitFromExchangeInfo() {
        val guard = BinanceRateGuard()
        val info = JSONObject()
            .put("rateLimits", JSONArray()
                .put(
                    JSONObject()
                        .put("rateLimitType", "REQUEST_WEIGHT")
                        .put("interval", "MINUTE")
                        .put("intervalNum", 1)
                        .put("limit", 6000)
                )
                .put(
                    JSONObject()
                        .put("rateLimitType", "ORDERS")
                        .put("interval", "SECOND")
                        .put("intervalNum", 10)
                        .put("limit", 75)
                )
                .put(
                    JSONObject()
                        .put("rateLimitType", "ORDERS")
                        .put("interval", "MINUTE")
                        .put("intervalNum", 1)
                        .put("limit", 1200)
                )
            )

        guard.updateFromExchangeInfo(info)

        assertEquals(75L, guard.orderLimit10s)
        assertEquals(1200L, guard.orderLimit1m)
        assertEquals(6000L, guard.requestWeightLimit1m)
    }

    @Test
    fun recordsTenSecondAndMinuteOrderCounters() {
        val guard = BinanceRateGuard()
        val headers = Headers.headersOf(
            "X-MBX-ORDER-COUNT-10S", "12",
            "X-MBX-ORDER-COUNT-1M", "91",
            "X-MBX-USED-WEIGHT-1M", "321"
        )

        guard.observe(headers, 200, isOrder = true)
        val snapshot = guard.snapshot()

        assertEquals(12L, snapshot.getLong("order_count_10s"))
        assertEquals(91L, snapshot.getLong("order_count_1m"))
        assertEquals(321L, snapshot.getLong("used_weight_1m"))
    }
}
