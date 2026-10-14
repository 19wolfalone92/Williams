package com.williamsbot

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BinanceUsdMFuturesMarketDataTest {
    @Test
    fun closedCandleBoundaryUsesExchangeTimeAndNeverAdmitsTheFormingCandle() {
        val marketData = BinanceUsdMFuturesMarketData()
        assertTrue(marketData.isCandleClosed(99L, 100L))
        assertTrue(!marketData.isCandleClosed(100L, 100L))
        assertTrue(!marketData.isCandleClosed(101L, 100L))
        assertTrue(!marketData.isCandleClosed(-1L, 100L))
    }

    @Test
    fun liveCandleOpenTimeFilterNeverReturnsFormingOrFutureCandles() {
        val marketData = BinanceUsdMFuturesMarketData()
        val oneHour = 60L * 60L * 1000L
        val closedOpen = 100L
        val formingOpen = closedOpen + oneHour
        val exchangeNow = formingOpen + 10L
        assertTrue(marketData.isCandleOpenTimeClosed(closedOpen, oneHour, exchangeNow))
        assertTrue(!marketData.isCandleOpenTimeClosed(formingOpen, oneHour, exchangeNow))
        assertTrue(!marketData.isCandleOpenTimeClosed(formingOpen + oneHour, oneHour, exchangeNow))
        assertTrue(!marketData.isCandleOpenTimeClosed(-1L, oneHour, exchangeNow))
    }

    @Test
    fun signalFreshnessUsesExchangeNowNotDecisionCandleOpen() {
        val fiveHours = 5L * 60L * 60L * 1000L
        assertTrue(isSignalFresh(1_000L, 1_000L + fiveHours, fiveHours))
        assertTrue(!isSignalFresh(1_000L, 1_001L + fiveHours, fiveHours))
        assertTrue(!isSignalFresh(1_001L, 1_000L, fiveHours))
        assertTrue(!isSignalFresh(0L, 1_000L, fiveHours))
    }

    @Test
    fun requestsUsdMFuturesKlinesAndPassesPaginationParameters() {
        var capturedUrl = ""
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            capturedUrl = chain.request().url.toString()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("[]".toResponseBody("application/json".toMediaType()))
                .build()
        }).build()

        val rows = BinanceUsdMFuturesMarketData(client).klines(
            symbol = "BTCUSDT",
            interval = "1h",
            limit = 1000,
            endTime = 1_700_000_000_000L
        )

        assertEquals(0, rows.length())
        assertTrue(capturedUrl.startsWith("https://demo-fapi.binance.com/fapi/v1/klines?"))
        assertTrue(capturedUrl.contains("symbol=BTCUSDT"))
        assertTrue(capturedUrl.contains("interval=1h"))
        assertTrue(capturedUrl.contains("limit=1000"))
        assertTrue(capturedUrl.contains("endTime=1700000000000"))
        assertTrue(!capturedUrl.contains("/api/v3/"))
    }

    @Test
    fun topOfBookReadsFreshUsdMFuturesBidAskForSelectedSymbol() {
        var capturedUrl = ""
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            capturedUrl = chain.request().url.toString()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"symbol":"ETHUSDT","bidPrice":"2499.5","askPrice":"2500.0"}"""
                    .toResponseBody("application/json".toMediaType()))
                .build()
        }).build()

        val (bid, ask) = BinanceUsdMFuturesMarketData(client).topOfBook("ETHUSDT")
        assertEquals(2499.5, bid, 0.0)
        assertEquals(2500.0, ask, 0.0)
        assertTrue(capturedUrl.startsWith("https://demo-fapi.binance.com/fapi/v1/ticker/bookTicker?"))
        assertTrue(capturedUrl.contains("symbol=ETHUSDT"))
    }

    @Test
    fun acceleratorOscillatorSubtractsFiveBarAoAverageAndRejectsWarmup() {
        val ao = listOf(1.0, 2.0, 3.0, 4.0, 8.0, 10.0)
        assertTrue(acceleratorOscillatorAt(ao, 3).isNaN())
        assertEquals(4.4, acceleratorOscillatorAt(ao, 4), 1e-12)
        assertEquals(4.6, acceleratorOscillatorAt(ao, 5), 1e-12)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSymbolsOutsideTheApprovedFuturesUniverse() {
        BinanceUsdMFuturesMarketData().klines("UNKNOWNUSDT", "1h", 100)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidPaginationRangeBeforeNetworkAccess() {
        BinanceUsdMFuturesMarketData().klines(
            symbol = "BTCUSDT",
            interval = "1h",
            limit = 100,
            startTime = 200L,
            endTime = 100L
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedIntervalsBeforeNetworkAccess() {
        BinanceUsdMFuturesMarketData().klines("BTCUSDT", "2m", 100)
    }
}
