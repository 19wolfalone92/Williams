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
    fun legacyRuntimePublicTransportAcceptsOnlyExplicitUsdMFuturesReadRoutes() {
        assertEquals("/fapi/v1/time", normalizeFuturesPublicPath("/fapi/v1/time"))
        assertEquals(
            "/fapi/v1/exchangeInfo?symbol=BTCUSDT",
            normalizeFuturesPublicPath("/fapi/v1/exchangeInfo?symbol=BTCUSDT")
        )
        assertEquals(
            "/fapi/v1/depth?symbol=BTCUSDT&limit=100",
            normalizeFuturesPublicPath("/fapi/v1/depth?symbol=BTCUSDT&limit=100")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun legacySpotPublicRoutesAreRejectedInsteadOfTranslated() {
        normalizeFuturesPublicPath("/api/v3/ticker/price?symbol=BTCUSDT")
    }

    @Test(expected = IllegalArgumentException::class)
    fun publicTransportCannotBeUsedForOrderMutations() {
        normalizeFuturesPublicPath("/fapi/v1/order")
    }

    @Test(expected = IllegalArgumentException::class)
    fun publicTransportRejectsExternalOrProtocolRelativeRoutes() {
        normalizeFuturesPublicPath("//evil.example/path")
    }

    @Test
    fun chartContractSharesTheExactTenSymbolsAndAllFifteenNativeIntervals() {
        assertEquals(
            listOf("BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT",
                "DOGEUSDT", "ADAUSDT", "LINKUSDT", "AVAXUSDT", "LTCUSDT"),
            APPROVED_FUTURES_SYMBOLS
        )
        assertEquals(APPROVED_FUTURES_SYMBOLS.toSet(), BinanceUsdMFuturesTestnetAdapter.APPROVED_SYMBOLS)
        assertEquals(
            listOf("1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h",
                "6h", "8h", "12h", "1d", "3d", "1w", "1M"),
            APPROVED_FUTURES_KLINE_INTERVALS
        )
        assertEquals(APPROVED_FUTURES_KLINE_INTERVALS.toSet(),
            BinanceUsdMFuturesMarketData().supportedIntervals)
    }

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
    fun monthlyKlinesCloseOnUtcCalendarMonthBoundaryNotFixedThirtyDays() {
        val marketData = BinanceUsdMFuturesMarketData()
        val januaryOpen = java.time.Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        val februaryOpen = java.time.Instant.parse("2026-02-01T00:00:00Z").toEpochMilli()
        val marchOpen = java.time.Instant.parse("2026-03-01T00:00:00Z").toEpochMilli()

        assertEquals(februaryOpen - 1L, marketData.klineCloseTimeMs(januaryOpen, "1M"))
        assertTrue(!marketData.isKlineOpenTimeClosed(
            januaryOpen, "1M",
            java.time.Instant.parse("2026-01-31T12:00:00Z").toEpochMilli()
        ))
        assertTrue(marketData.isKlineOpenTimeClosed(januaryOpen, "1M", februaryOpen))

        // February is shorter than 30 days in 2026. Its closed candle must be
        // admitted at the real March boundary, not hidden for two more days.
        assertEquals(marchOpen - 1L, marketData.klineCloseTimeMs(februaryOpen, "1M"))
        assertTrue(marketData.isKlineOpenTimeClosed(februaryOpen, "1M", marchOpen))
    }

    @Test
    fun monthlyLastClosedCacheExpiresWhenTheNextCalendarMonthHasClosed() {
        val marketData = BinanceUsdMFuturesMarketData()
        val januaryOpen = java.time.Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        val februaryOpen = java.time.Instant.parse("2026-02-01T00:00:00Z").toEpochMilli()
        val marchOpen = java.time.Instant.parse("2026-03-01T00:00:00Z").toEpochMilli()
        val aprilOpen = java.time.Instant.parse("2026-04-01T00:00:00Z").toEpochMilli()
        val februaryEnd = februaryOpen + 28L * 24L * 60L * 60L * 1000L - 1L
        val marchEnd = aprilOpen - 1L

        assertTrue(marketData.isLastClosedCandleCurrent(
            januaryOpen, "1M", februaryEnd
        ))
        assertTrue(!marketData.isLastClosedCandleCurrent(
            januaryOpen, "1M", marchOpen
        ))
        assertTrue(marketData.isLastClosedCandleCurrent(
            februaryOpen, "1M", marchEnd
        ))
        assertTrue(!marketData.isLastClosedCandleCurrent(
            februaryOpen, "1M", aprilOpen
        ))
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
    fun emptyHttp200ForH1HistoryRetriesAndThenResumesNormalAnalysis() {
        var attempts = 0
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            attempts++
            val body = if (attempts == 1) "" else "[[1000]]"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }).build()
        val result = BinanceUsdMFuturesMarketData(client)
            .klines("DOGEUSDT", "1h", 1)
        assertEquals(2, attempts)
        assertEquals(1, result.length())
    }

    @Test
    fun repeatedEmptyHttp200KlinesCannotBeMisreadAsValidHistory() {
        var attempts = 0
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            attempts++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("".toResponseBody("application/json".toMediaType())).build()
        }).build()
        try {
            BinanceUsdMFuturesMarketData(client).klines("DOGEUSDT", "1h", 1)
            throw AssertionError("Empty HTTP 200 cannot silently authorize H1 scanner")
        } catch (e: java.io.IOException) {
            assertTrue(e.message.orEmpty().contains("Empty USD-M Futures market data GET"))
        }
        assertEquals(4, attempts)
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
    fun marketDataRetriesTransientServerFailuresButOnlyAsSafeGets() {
        var attempts = 0
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            attempts++
            val code = if (attempts == 1) 503 else 200
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 200) "OK" else "Service Unavailable")
                .body("[]".toResponseBody("application/json".toMediaType()))
                .build()
        }).build()

        assertEquals(0, BinanceUsdMFuturesMarketData(client).ticker24h().length())
        assertEquals(2, attempts)
    }

    @Test(expected = java.io.IOException::class)
    fun marketDataDoesNotRetryRateLimitResponses() {
        var attempts = 0
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            attempts++
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(429)
                .message("Too Many Requests")
                .body("""{"code":-1003,"msg":"rate limited"}""".toResponseBody("application/json".toMediaType()))
                .build()
        }).build()

        try {
            BinanceUsdMFuturesMarketData(client).ticker24h()
        } finally {
            assertEquals(1, attempts)
        }
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
