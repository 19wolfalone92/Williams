package com.williamsbot

import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class FuturesFinalTransportGateTest {
    @Test fun exchangeCooldownRejectionBeforePostHasADefiniteNotSubmittedOutcome() {
        var posts = 0
        val guard = BinanceRateGuard()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val r = chain.request()
            if (r.method == "POST") posts++
            response(r, if (r.url.encodedPath == "/fapi/v1/time")
                """{"serverTime":${System.currentTimeMillis()}}""" else """{"dualSidePosition":false}""")
        }.build()
        val adapter = BinanceUsdMFuturesTestnetAdapter("key", "secret", client, guard,
            beforeExposureMutation = { guard.observe(Headers.headersOf("Retry-After", "120"), 429) })
        try {
            adapter.newOrder("BTCUSDT", "BUY", "1", false, "W2E_COOLDOWN")
            fail("Long cooldown must fail before POST")
        } catch (expected: FuturesExposureNotSubmittedException) {
            assertTrue(expected.message.orEmpty().contains("cooldown retained"))
        }
        assertEquals(0, posts)
        assertTrue(guard.snapshot().getLong("cooldown_ms") > 110_000L)
    }

    private fun response(request: Request, body: String, code: Int = 200): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(code).message("test").body(body.toResponseBody()).build()

    @Test fun disconnectDuringRateLimitWaitPreventsBothLongAndShortEntryPosts() {
        for (direction in listOf("LONG", "SHORT")) {
            val admitted = CountDownLatch(1)
            val connected = AtomicBoolean(true)
            val failure = AtomicReference<Throwable>()
            val requests = java.util.concurrent.CopyOnWriteArrayList<Request>()
            val governor = BinanceRateGuard()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val r = chain.request(); requests += r
                response(r, when (r.url.encodedPath) {
                    "/fapi/v1/time" -> """{"serverTime":${System.currentTimeMillis()}}"""
                    "/fapi/v1/positionSide/dual" -> """{"dualSidePosition":false}"""
                    "/fapi/v2/ticker/price" -> """{"symbol":"BTCUSDT","price":"100"}"""
                    else -> "{}"
                })
            }.build()
            val adapter = BinanceUsdMFuturesTestnetAdapter("key", "secret", client, governor,
                beforeExposureMutation = {
                    governor.observe(Headers.headersOf("Retry-After", "1"), 429)
                    admitted.countDown()
                },
                beforeExposureTransport = { check(connected.get()) { "PRIVATE_STREAM_LOST" } })
            val task = Thread {
                try {
                    adapter.placeConditionalEntry("BTCUSDT", direction, "STOP", "1",
                        if (direction == "LONG") "101" else "99", "W2E_FINAL_$direction")
                } catch (e: Throwable) { failure.set(e) }
            }
            task.start()
            try {
                assertTrue(admitted.await(5L, TimeUnit.SECONDS))
                connected.set(false)
                task.join(5_000L)
                assertFalse(task.isAlive)
                assertTrue(failure.get().toString(), failure.get() is FuturesExposureNotSubmittedException)
                assertFalse(requests.any { it.method == "POST" })
            } finally { task.interrupt(); task.join(2_000L) }
        }
    }

    @Test fun reduceOnlyEmergencyExitAndProtectiveStopDoNotRequireNewEntryAuthority() {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val r = chain.request(); requests += r
            response(r, when (r.url.encodedPath) {
                "/fapi/v1/time" -> """{"serverTime":${System.currentTimeMillis()}}"""
                "/fapi/v1/positionSide/dual" -> """{"dualSidePosition":false}"""
                "/fapi/v1/premiumIndex" -> """{"symbol":"BTCUSDT","markPrice":"100"}"""
                else -> "{}"
            })
        }.build()
        val adapter = BinanceUsdMFuturesTestnetAdapter("key", "secret", client,
            beforeExposureTransport = { error("New exposure is forbidden") })
        adapter.closeMarketPosition("BTCUSDT", "LONG", "1", "W2X_SAFETY")
        adapter.placeProtectiveStop("BTCUSDT", "LONG", "95", "W2P_SAFETY")
        assertEquals(2, requests.count { it.method == "POST" })
    }

    @Test fun explicitTimestampRejectionRechecksAuthorityBeforeItsOnlyPermittedRetry() {
        var posts = 0
        var ready = true
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val r = chain.request()
            when (r.url.encodedPath) {
                "/fapi/v1/time" -> response(r, """{"serverTime":${System.currentTimeMillis()}}""")
                "/fapi/v1/positionSide/dual" -> response(r, """{"dualSidePosition":false}""")
                else -> {
                    posts++; ready = false
                    response(r, """{"code":-1021,"msg":"rejected timestamp"}""", 400)
                }
            }
        }.build()
        val adapter = BinanceUsdMFuturesTestnetAdapter("key", "secret", client,
            beforeExposureTransport = { check(ready) })
        try {
            adapter.newOrder("BTCUSDT", "BUY", "1", false, "W2E_CLOCK_REVOKED")
            fail("Revoked entry must not be retried")
        } catch (_: FuturesExposureNotSubmittedException) { }
        assertEquals(1, posts)
    }
}
