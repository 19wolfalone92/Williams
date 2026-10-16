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
        assertEquals(200, snapshot.getInt("last_http_status"))
        assertEquals(1L, snapshot.getLong("observed_response_count"))
        assertEquals(0L, snapshot.getLong("rate_limit_response_count"))
    }

    @Test
    fun rateLimitResponsesAreCountedAndExposeCooldown() {
        val guard = BinanceRateGuard()
        guard.observe(Headers.headersOf("Retry-After", "2"), 429)
        val snapshot = guard.snapshot()
        assertEquals(429, snapshot.getInt("last_http_status"))
        assertEquals(1L, snapshot.getLong("rate_limit_response_count"))
        org.junit.Assert.assertTrue(snapshot.getLong("cooldown_ms") > 0L)
    }
    @Test
    fun sustainedUsagePressureDelaysOneRequestButDoesNotRescheduleItForever() {
        val guard = BinanceRateGuard()
        guard.observe(
            Headers.headersOf("X-MBX-USED-WEIGHT-1M", "5500"),
            200
        )
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val worker = Thread({
            runCatching { guard.beforeRequest() }.onFailure { failure.set(it) }
        }, "rate-guard-pressure-test").apply {
            isDaemon = true
            start()
        }
        worker.join(2000L)

        org.junit.Assert.assertFalse(
            "A request must leave the governor after its one pressure delay",
            worker.isAlive
        )
        org.junit.Assert.assertEquals(null, failure.get())
    }

    @Test
    fun interruptedGovernorWaitFailsClosedInsteadOfSendingTheRequest() {
        val guard = BinanceRateGuard()
        guard.beforeRequest()
        Thread.currentThread().interrupt()
        try {
            try {
                guard.beforeRequest()
                throw AssertionError("Interrupted governor wait must not allow a request")
            } catch (expected: IllegalStateException) {
                org.junit.Assert.assertTrue(expected.message.orEmpty().contains("request was not sent"))
            }
        } finally {
            // Do not leak the interrupt flag into the test runner's worker thread.
            Thread.interrupted()
        }
    }
}
