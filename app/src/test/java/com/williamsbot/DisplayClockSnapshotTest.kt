package com.williamsbot

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class DisplayClockSnapshotTest {
    @Test fun displayClockDoesNotContactBinanceOrClaimFreshSync() {
        var requests = 0
        val market = BinanceUsdMFuturesMarketData(OkHttpClient.Builder().addInterceptor {
            requests++
            throw AssertionError("Display snapshot attempted network I/O")
        }.build())
        assertTrue(market.cachedExchangeNowMs() > 0L)
        assertFalse(market.clockSnapshot().getBoolean("fresh"))
        assertEquals(0, requests)
    }
    @Test fun authoritativeClockStillRequiresExchangeAndDisplayReusesItsOffset() {
        var requests = 0
        val remote = System.currentTimeMillis() + 60_000L
        val market = BinanceUsdMFuturesMarketData(OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            assertEquals("/fapi/v1/time", chain.request().url.encodedPath)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("{\"serverTime\":$remote}".toResponseBody()).build()
        }.build())
        market.exchangeNowMs()
        assertEquals(1, requests)
        assertTrue(market.clockSnapshot().getBoolean("fresh"))
        assertTrue(market.cachedExchangeNowMs() - System.currentTimeMillis() > 59_000L)
        assertEquals(1, requests)
    }
}

