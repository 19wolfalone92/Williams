package com.williamsbot

import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BinanceUsdMFuturesTestnetAdapterContractTest {
    @Test
    fun approvedUniverseIsExactlyTheRequiredTenUsdtContracts() {
        assertEquals(
            setOf(
                "BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT",
                "DOGEUSDT", "ADAUSDT", "LINKUSDT", "AVAXUSDT", "LTCUSDT"
            ),
            BinanceUsdMFuturesTestnetAdapter.APPROVED_SYMBOLS
        )
    }

    @Test
    fun chartIntervalContractIncludesAllSupportedUsdMFuturesPeriods() {
        assertEquals(
            listOf(
                "1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h",
                "6h", "8h", "12h", "1d", "3d", "1w", "1M"
            ),
            APPROVED_FUTURES_KLINE_INTERVALS
        )
        // Both chart reads and account/coordinator reads consume this canonical
        // allow-list, so weekly/monthly views cannot silently fail in one path.
        assertTrue(APPROVED_FUTURES_KLINE_INTERVALS.containsAll(listOf("3d", "1w", "1M")))
    }

    @Test
    fun universeContainsOnlyUsdtMarginedContracts() {
        assertTrue(
            BinanceUsdMFuturesTestnetAdapter.APPROVED_SYMBOLS.all {
                it.endsWith("USDT") && it.matches(Regex("[A-Z0-9]{5,20}"))
            }
        )
    }
    @Test
    fun adapterRetriesTransientSafeGetButNotRateLimitResponses() {
        var attempts = 0
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            attempts++
            val code = if (attempts == 1) 502 else 200
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 200) "OK" else "Bad Gateway")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }).build()

        BinanceUsdMFuturesTestnetAdapter("unit-test-key", "unit-test-secret", client).exchangeInfo()
        assertEquals(2, attempts)
    }

    @Test(expected = java.io.IOException::class)
    fun ambiguousOrderMutationIsNeverRetriedAfterServerFailure() {
        var orderPosts = 0
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val code = when (request.url.encodedPath) {
                "/fapi/v1/time" -> 200
                "/fapi/v1/positionSide/dual" -> 200
                "/fapi/v1/order" -> {
                    if (request.method == "POST") orderPosts++
                    503
                }
                else -> 200
            }
            val body = when (request.url.encodedPath) {
                "/fapi/v1/time" -> """{"serverTime":${System.currentTimeMillis()}}"""
                "/fapi/v1/positionSide/dual" -> """{"dualSidePosition":false}"""
                else -> """{"code":-1000,"msg":"temporary server error"}"""
            }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 200) "OK" else "Service Unavailable")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }).build()

        try {
            BinanceUsdMFuturesTestnetAdapter("unit-test-key", "unit-test-secret", client)
                .newOrder("BTCUSDT", "BUY", "0.01", false, "TEST_NO_RETRY")
        } finally {
            assertEquals(1, orderPosts)
        }
    }

    @Test
    fun malformedPositionRowsNeverLookLikeAFlatAccountDuringModeChange() {
        assertTrue(!hasLiveFuturesPosition(JSONArray()))
        assertTrue(!hasLiveFuturesPosition(JSONArray().put(JSONObject().put("positionAmt", "0"))))
        assertTrue(hasLiveFuturesPosition(JSONArray().put(JSONObject().put("positionAmt", "0.01"))))
        assertTrue(hasLiveFuturesPosition(JSONArray().put(JSONObject().put("positionAmt", "-0.01"))))
    }

    @Test(expected = IllegalStateException::class)
    fun malformedPositionAmountFailsClosedInsteadOfAssumingZero() {
        hasLiveFuturesPosition(JSONArray().put(JSONObject().put("positionAmt", "not-a-number")))
    }

    @Test(expected = IllegalStateException::class)
    fun malformedPositionRowFailsClosedInsteadOfAssumingNoPosition() {
        hasLiveFuturesPosition(JSONArray().put(JSONObject.NULL))
    }

    @Test
    fun longAndShortEntryAndExitSidesAreOppositeAndNeverAmbiguous() {
        assertEquals("BUY", BinanceUsdMFuturesTestnetAdapter.entrySide("LONG"))
        assertEquals("SELL", BinanceUsdMFuturesTestnetAdapter.entrySide("SHORT"))
        assertEquals("SELL", BinanceUsdMFuturesTestnetAdapter.closeSide("LONG"))
        assertEquals("BUY", BinanceUsdMFuturesTestnetAdapter.closeSide("SHORT"))
    }

    @Test
    fun pendingLongAndShortEntriesUseOppositeNonReduceOnlyConditionalSides() {
        val longRequests = mutableListOf<Request>()
        val longAdapter = testAdapter(longRequests)
        longAdapter.placeConditionalEntry(
            symbol = "BTCUSDT",
            direction = "LONG",
            triggerType = "STOP",
            quantity = "0.01",
            triggerPrice = "101",
            clientOrderId = "TEST_LONG_ENTRY"
        )
        val longParams = formParams(
            longRequests.single { it.url.encodedPath == "/fapi/v1/algoOrder" && it.method == "POST" }
        )
        assertEquals("BUY", longParams["side"])
        assertEquals("STOP_MARKET", longParams["type"])
        assertEquals("101", longParams["triggerPrice"])
        assertEquals("MARK_PRICE", longParams["workingType"])
        assertEquals("TEST_LONG_ENTRY", longParams["clientAlgoId"])
        assertTrue(!longParams.containsKey("reduceOnly"))
        assertTrue(!longParams.containsKey("closePosition"))

        val shortRequests = mutableListOf<Request>()
        val shortAdapter = testAdapter(shortRequests)
        shortAdapter.placeConditionalEntry(
            symbol = "BTCUSDT",
            direction = "SHORT",
            triggerType = "STOP",
            quantity = "0.01",
            triggerPrice = "99",
            clientOrderId = "TEST_SHORT_ENTRY"
        )
        val shortParams = formParams(
            shortRequests.single { it.url.encodedPath == "/fapi/v1/algoOrder" && it.method == "POST" }
        )
        assertEquals("SELL", shortParams["side"])
        assertEquals("STOP_MARKET", shortParams["type"])
        assertEquals("99", shortParams["triggerPrice"])
        assertEquals("TEST_SHORT_ENTRY", shortParams["clientAlgoId"])
        assertTrue(!shortParams.containsKey("reduceOnly"))
        assertTrue(!shortParams.containsKey("closePosition"))
    }

    @Test
    fun protectiveStopsCloseTheCorrectPositionSideWithoutQuantityOrReduceOnly() {
        val longRequests = mutableListOf<Request>()
        testAdapter(longRequests).placeProtectiveStop("BTCUSDT", "LONG", "95", "TEST_LONG_STOP")
        val longParams = formParams(
            longRequests.single { it.url.encodedPath == "/fapi/v1/algoOrder" && it.method == "POST" }
        )
        assertEquals("SELL", longParams["side"])
        assertEquals("STOP_MARKET", longParams["type"])
        assertEquals("true", longParams["closePosition"])
        assertEquals("false", longParams["priceProtect"])
        assertTrue(!longParams.containsKey("quantity"))
        assertTrue(!longParams.containsKey("reduceOnly"))

        val shortRequests = mutableListOf<Request>()
        testAdapter(shortRequests).placeProtectiveStop("BTCUSDT", "SHORT", "105", "TEST_SHORT_STOP")
        val shortParams = formParams(
            shortRequests.single { it.url.encodedPath == "/fapi/v1/algoOrder" && it.method == "POST" }
        )
        assertEquals("BUY", shortParams["side"])
        assertEquals("STOP_MARKET", shortParams["type"])
        assertEquals("true", shortParams["closePosition"])
        assertEquals("false", shortParams["priceProtect"])
        assertTrue(!shortParams.containsKey("quantity"))
        assertTrue(!shortParams.containsKey("reduceOnly"))
    }

    @Test
    fun adapterFeedsResponseRateHeadersIntoSharedGovernor() {
        val guard = BinanceRateGuard()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("X-MBX-USED-WEIGHT-1M", "432")
                .header("X-MBX-ORDER-COUNT-10S", "3")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()

        BinanceUsdMFuturesTestnetAdapter(
            "unit-test-key", "unit-test-secret", client, guard
        ).exchangeInfo()

        assertEquals(432L, guard.usedWeight1m)
        assertEquals(3L, guard.orderCount10s)
    }

    private fun testAdapter(captured: MutableList<Request>): BinanceUsdMFuturesTestnetAdapter {
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            captured += request
            val body = when (request.url.encodedPath) {
                "/fapi/v1/time" -> """{"serverTime":${System.currentTimeMillis()}}"""
                "/fapi/v1/positionSide/dual" -> """{"dualSidePosition":false}"""
                "/fapi/v1/premiumIndex" -> """{"symbol":"BTCUSDT","markPrice":"100"}"""
                "/fapi/v1/algoOrder" -> """{"algoId":1,"algoStatus":"NEW","orderType":"STOP_MARKET"}"""
                else -> "{}"
            }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }).build()
        return BinanceUsdMFuturesTestnetAdapter("unit-test-key", "unit-test-secret", client)
    }

    private fun formParams(request: Request): Map<String, String> {
        val body = request.body as FormBody
        return (0 until body.size).associate { index ->
            body.name(index) to body.value(index)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidDirectionCannotReachOrderSideMapping() {
        BinanceUsdMFuturesTestnetAdapter.entrySide("FLAT")
    }

}
