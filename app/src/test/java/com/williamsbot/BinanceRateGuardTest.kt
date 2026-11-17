package com.williamsbot

import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class BinanceRateGuardTest {
    @Test fun longExchangeCooldownDoesNotStrandTheMonitoringWorkerOrPermitEarlyRequests() {
        val guard = BinanceRateGuard()
        guard.observe(Headers.headersOf("Retry-After", "120"), 429)
        val start = System.nanoTime()
        repeat(2) {
            try {
                guard.beforeRequest()
                throw AssertionError("Long Binance cooldown must not admit a request")
            } catch (expected: IllegalStateException) {
                org.junit.Assert.assertTrue(expected.message.orEmpty().contains("cooldown retained"))
            }
        }
        org.junit.Assert.assertTrue("Long cooldown must fail locally without sleeping", System.nanoTime()-start < 1_000_000_000L)
        org.junit.Assert.assertTrue(guard.snapshot().getLong("cooldown_ms") > 110_000L)
        assertEquals(2L, guard.snapshot().getLong("bounded_wait_rejected_count"))
    }

    @Test fun aShortLocalBudgetCannotOverrideExistingRateLimitReservation() {
        val guard = BinanceRateGuard()
        guard.beforeRequest()
        try {
            guard.beforeRequest(maximumWaitMs = 1L)
            throw AssertionError("A short budget must reject, never skip the reserved delay")
        } catch (_: IllegalStateException) { }
        assertEquals(1L, guard.snapshot().getLong("bounded_wait_rejected_count"))
    }

    @Test fun concurrent429ResponsesCannotShortenAnExistingCooldown() {
        val guard = BinanceRateGuard()
        guard.observe(Headers.headersOf("Retry-After", "180"), 429)
        val before = guard.cooldownUntilMs
        val start = java.util.concurrent.CountDownLatch(1)
        val threads = (0 until 24).map { i ->
            Thread {
                start.await()
                guard.observe(Headers.headersOf("Retry-After", if (i % 3 == 0) "180" else "1"), 429)
            }.apply { isDaemon = true; start() }
        }
        start.countDown()
        threads.forEach { it.join(3_000L) }
        org.junit.Assert.assertTrue("Concurrent rate-limit responses must finish", threads.none { it.isAlive })
        org.junit.Assert.assertTrue("A short retry may not release a longer cooldown",
            guard.cooldownUntilMs >= before)
        org.junit.Assert.assertTrue(guard.snapshot().getLong("cooldown_ms") > 170_000L)
        assertEquals(25L, guard.rateLimitResponseCount)
    }

    @Test fun maliciousRetryAfterCannotOverflowAndAllowFinancialRest() {
        val deadline = binanceRateGuardCooldownDeadline(1_800_000_000_000L, Long.MAX_VALUE.toString())
        assertEquals(Long.MAX_VALUE, deadline)
        assertEquals(Long.MAX_VALUE,
            binanceRateGuardSaturatingAdd(Long.MAX_VALUE - 20L, 100L))
        val guard = BinanceRateGuard()
        guard.observe(Headers.headersOf("Retry-After", Long.MAX_VALUE.toString()), 418)
        assertEquals(Long.MAX_VALUE, guard.cooldownUntilMs)
        try {
            guard.beforeRequest(isOrder = true, maximumWaitMs = 1L)
            throw AssertionError("Overflow must never turn a Binance ban into an allowed order")
        } catch (expected: IllegalStateException) {
            org.junit.Assert.assertTrue(expected.message.orEmpty().contains("request was not sent"))
        }
        org.junit.Assert.assertTrue(binanceRateGuardCooldownDeadline(1_800_000_000_000L, "-1") >
            1_800_000_000_000L)
    }

    @Test fun negativeExchangeHeadersAndNonpositiveLimitsDoNotDisableThrottling() {
        val guard = BinanceRateGuard()
        guard.observe(Headers.headersOf("X-MBX-USED-WEIGHT-1M", "5700"), 200)
        guard.observe(Headers.headersOf("X-MBX-USED-WEIGHT-1M", "-1"), 200)
        assertEquals(5700L, guard.usedWeight1m)
        guard.updateFromExchangeInfo(JSONObject().put("rateLimits", JSONArray()
            .put(JSONObject().put("rateLimitType", "REQUEST_WEIGHT")
                .put("interval", "MINUTE").put("intervalNum", 1).put("limit", -1))
            .put(JSONObject().put("rateLimitType", "ORDERS")
                .put("interval", "SECOND").put("intervalNum", 10).put("limit", 0))))
        assertEquals(6000L, guard.requestWeightLimit1m)
        assertEquals(50L, guard.orderLimit10s)
    }

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
