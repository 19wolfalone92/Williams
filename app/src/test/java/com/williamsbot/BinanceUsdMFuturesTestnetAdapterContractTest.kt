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
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test

class BinanceUsdMFuturesTestnetAdapterContractTest {
    @Test
    fun selectedLeverageIsSignedAndConfirmedForEveryApprovedContract() {
        val captured = mutableListOf<Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            captured += request
            val body = if (request.url.encodedPath == "/fapi/v1/time") {
                """{"serverTime":${System.currentTimeMillis()}}"""
            } else {
                val p = formParams(request)
                JSONObject().put("symbol", p["symbol"]).put("leverage", p["leverage"]!!.toInt())
                    .put("maxNotionalValue", "1000000").toString()
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val adapter = BinanceUsdMFuturesTestnetAdapter("key", "secret", http)
        BinanceUsdMFuturesTestnetAdapter.APPROVED_SYMBOLS.withIndex().forEach { (index, symbol) ->
            val leverage = index + 1
            assertEquals(leverage, adapter.setLeverage(symbol, leverage).getInt("leverage"))
        }
        val posts = captured.filter { it.method == "POST" }
        assertEquals(10, posts.size)
        posts.forEach {
            assertEquals("demo-fapi.binance.com", it.url.host)
            assertEquals("/fapi/v1/leverage", it.url.encodedPath)
            assertTrue(formParams(it).containsKey("signature"))
            assertTrue(formParams(it).containsKey("timestamp"))
        }
    }

    @Test
    fun wrongOrMissingLeverageConfirmationCannotAuthorizeAnOrder() {
        listOf("{}", """{"symbol":"ETHUSDT","leverage":5,"maxNotionalValue":"1000000"}""",
            """{"symbol":"BTCUSDT","leverage":2,"maxNotionalValue":"1000000"}""",
            """{"symbol":"BTCUSDT","leverage":5,"maxNotionalValue":"NaN"}"""
        ).forEach { confirmation ->
            var orderCalls = 0
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                if (request.url.encodedPath == "/fapi/v1/algoOrder") orderCalls++
                val body = if (request.url.encodedPath == "/fapi/v1/time")
                    """{"serverTime":${System.currentTimeMillis()}}""" else confirmation
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }.build()
            val adapter = BinanceUsdMFuturesTestnetAdapter("key", "secret", http)
            try {
                adapter.setLeverage("BTCUSDT", 5)
                adapter.placeConditionalEntry("BTCUSDT", "LONG", "STOP", "1", "101", "TEST")
                throw AssertionError("unconfirmed leverage must fail")
            } catch (_: IllegalStateException) { }
            assertEquals(0, orderCalls)
        }
    }

    @Test
    fun timestampTextInAnUnrelatedErrorNeverAuthorizesMutationReplay() {
        var posts = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = when (request.url.encodedPath) {
                "/fapi/v1/time" -> """{"serverTime":${System.currentTimeMillis()}}"""
                "/fapi/v1/positionSide/dual" -> """{"dualSidePosition":false}"""
                else -> {
                    posts++
                    """{"code":-1000,"msg":"upstream mentions -1021 but execution is unknown"}"""
                }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (request.url.encodedPath == "/fapi/v1/order") 503 else 200)
                .message("test").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            BinanceUsdMFuturesTestnetAdapter("key", "secret", http)
                .newOrder("BTCUSDT", "BUY", "0.01", false, "timestamp_text")
            throw AssertionError("ambiguous order must fail")
        } catch (expected: FuturesHttpException) {
            assertEquals(-1000, expected.exchangeCode)
        }
        assertEquals(1, posts)
    }

    @Test
    fun explicitTimestampRejectionMayBeResynchronizedAndRetriedOnce() {
        var posts = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = when (request.url.encodedPath) {
                "/fapi/v1/time" -> """{"serverTime":${System.currentTimeMillis()}}"""
                "/fapi/v1/positionSide/dual" -> """{"dualSidePosition":false}"""
                else -> {
                    posts++
                    if (posts == 1) """{"code":-1021,"msg":"timestamp rejected"}"""
                    else """{"orderId":7,"status":"FILLED"}"""
                }
            }
            val rejected = request.url.encodedPath == "/fapi/v1/order" && posts == 1
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (rejected) 400 else 200).message("test")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val result = BinanceUsdMFuturesTestnetAdapter("key", "secret", http)
            .newOrder("BTCUSDT", "BUY", "0.01", false, "timestamp_retry")
        assertEquals(7L, result.getLong("orderId"))
        assertEquals(2, posts)
    }

    @Test(expected = FuturesHttpException::class)
    fun negativeExchangeCodeInsideHttp200IsStillAFailure() {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("""{"code":-2015,"msg":"rejected"}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        BinanceUsdMFuturesTestnetAdapter("key", "secret", http).account()
    }

    @Test
    fun http200EmptyKlinesBodyRetriesReadAndNeverParsesEmptyMarketAsHistory() {
        var attempts = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            attempts++
            val text = if (attempts == 1) "" else "[[1000]]"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(text.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val result = BinanceUsdMFuturesTestnetAdapter("unit-test-key", "unit-test-secret", client)
            .klines("DOGEUSDT", "1h", 1)
        assertEquals(2, attempts)
        assertEquals(1, result.length())
    }

    @Test
    fun permanentlyEmptyFuturesReadFailsAfterBoundedGetRetries() {
        var attempts = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            attempts++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("".toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            BinanceUsdMFuturesTestnetAdapter("unit-test-key", "unit-test-secret", client)
                .klines("DOGEUSDT", "1h", 1)
            throw AssertionError("An empty 200 response must never become a valid kline snapshot")
        } catch (expected: java.io.IOException) {
            assertTrue(expected.message.orEmpty().contains("Empty USD-M Futures GET"))
        }
        assertEquals(4, attempts)
    }

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
        assertEquals("CONTRACT_PRICE", longParams["workingType"])
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

    @Test fun bookEntriesUseContractBreakoutsWhenMarkDivergesInEitherDirection() {
        for(side in listOf("LONG","SHORT")) for(kind in listOf("STOP","TAKE_PROFIT")) {
            val above=(side=="LONG")== (kind=="STOP")
            val trigger=if(above) "101" else "99"
            val requests=mutableListOf<Request>()
            // MARK already crossed; the trade-price H1 breakout has NOT.
            testAdapter(requests,mark=if(above) 103.0 else 97.0)
                .placeConditionalEntry("BTCUSDT",side,kind,"0.01",trigger,"BASIS_${side}_$kind")
            val submitted=formParams(requests.single { it.method=="POST" && it.url.encodedPath=="/fapi/v1/algoOrder" })
            assertEquals("CONTRACT_PRICE",submitted["workingType"])
            assertFalse(requests.any { it.url.encodedPath=="/fapi/v1/premiumIndex" })

            val consumed=mutableListOf<Request>()
            val quote="""{"symbol":"BTCUSDT","price":"${if(above) 102 else 98}"}"""
            try {
                testAdapter(consumed,contractJson=quote)
                    .placeConditionalEntry("BTCUSDT",side,kind,"0.01",trigger,"CONSUMED_${side}_$kind")
                fail("A consumed CONTRACT trigger must not reach Binance POST")
            } catch(_:IllegalArgumentException) { }
            assertFalse(consumed.any { it.method=="POST" })
        }
    }

    @Test fun missingInvalidOrForeignContractQuotesCannotAuthorizeAnEntry() {
        for(quote in listOf("{}", """{"symbol":"ETHUSDT","price":"100"}""",
            """{"symbol":"BTCUSDT","price":"NaN"}""", """{"symbol":"BTCUSDT","price":"0"}""")) {
            val requests=mutableListOf<Request>()
            try {
                testAdapter(requests,contractJson=quote)
                    .placeConditionalEntry("BTCUSDT","LONG","STOP","0.01","101","INVALID_BASIS")
                fail("Unverified contract quote must not reach POST")
            } catch(_:Exception) { }
            assertFalse(requests.any { it.method=="POST" })
        }
    }

    private fun testAdapter(captured: MutableList<Request>, mark:Double=100.0,
        contractJson:String="""{"symbol":"BTCUSDT","price":"100"}"""): BinanceUsdMFuturesTestnetAdapter {
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            captured += request
            val body = when (request.url.encodedPath) {
                "/fapi/v1/time" -> """{"serverTime":${System.currentTimeMillis()}}"""
                "/fapi/v1/positionSide/dual" -> """{"dualSidePosition":false}"""
                "/fapi/v1/premiumIndex" -> """{"symbol":"BTCUSDT","markPrice":"$mark"}"""
                "/fapi/v2/ticker/price" -> contractJson
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


    @Test fun staleSyncOrAndroidWallClockJumpAlwaysRequiresServerRefresh() {
        val wall=1_800_000_000_000L
        val mono=900_000_000_000L
        assertTrue(futuresClockSyncNeeded(false,wall,mono,wall,mono))
        assertTrue(!futuresClockSyncNeeded(true,wall,mono,wall+29_999L,mono+29_999_000_000L))
        assertTrue(futuresClockSyncNeeded(true,wall,mono,wall+30_001L,mono+30_001_000_000L))
        assertTrue(futuresClockSyncNeeded(true,wall,mono,wall+2_500L,mono+1_000_000_000L))
        assertTrue(futuresClockSyncNeeded(true,wall,mono,wall+1_000L,mono-1))
    }

    @Test fun signatureIsGeneratedAfterPotentiallySlowExposurePreflight() {
        var preflightCompleted=0L
        var submitted=0L
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request()
            val body=when(request.url.encodedPath) {
                "/fapi/v1/time" -> """{"serverTime":${System.currentTimeMillis()}}"""
                "/fapi/v1/positionSide/dual" -> """{"dualSidePosition":false}"""
                "/fapi/v1/order" -> {
                    submitted=(request.body as FormBody).let { b ->
                        (0 until b.size).first { b.name(it)=="timestamp" }.let { b.value(it).toLong() }
                    }
                    """{"orderId":222,"status":"NEW"}"""
                }
                else -> "{}"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val adapter=BinanceUsdMFuturesTestnetAdapter("key","secret",client,
            beforeExposureMutation={
                Thread.sleep(220L)
                preflightCompleted=System.currentTimeMillis()
            })
        adapter.newOrder("BTCUSDT","BUY","0.01",false,"CLOCK_PREFLIGHT_ORDER")
        assertTrue("Timestamp must be created after slow preflight; old version signed first",
            submitted >= preflightCompleted-30L)
    }

    @Test fun monotonicExchangeTimestampTracksSkewAndConcurrentSignedReadsShareOneSync() {
        val calls=java.util.concurrent.atomic.AtomicInteger()
        val skew=25_000L
        val stamps=java.util.concurrent.CopyOnWriteArrayList<Long>()
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request()
            val body=if(request.url.encodedPath=="/fapi/v1/time") {
                calls.incrementAndGet()
                """{"serverTime":${System.currentTimeMillis()+skew}}"""
            } else {
                val timestamp=request.url.queryParameter("timestamp")!!.toLong()
                stamps.add(timestamp-System.currentTimeMillis())
                "{}"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val adapter=BinanceUsdMFuturesTestnetAdapter("key","secret",client)
        val pool=java.util.concurrent.Executors.newFixedThreadPool(6)
        try {
            val tasks=(1..12).map { pool.submit(java.util.concurrent.Callable<Boolean> {
                adapter.account();true
            }) }
            tasks.forEach { assertTrue(it.get(20,java.util.concurrent.TimeUnit.SECONDS)) }
        } finally { pool.shutdownNow() }
        assertEquals(1,calls.get())
        assertEquals(12,stamps.size)
        assertTrue(stamps.all { it in 23_500L..26_500L })
    }

    @Test fun persistentExplicitTimestampRejectionNeverRetriesFinancialPostMoreThanOnce() {
        var posts=0
        var syncs=0
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            val r=chain.request()
            val isPost=r.url.encodedPath=="/fapi/v1/order"
            if(isPost)posts++
            if(r.url.encodedPath=="/fapi/v1/time")syncs++
            val body=when(r.url.encodedPath) {
                "/fapi/v1/time" -> """{"serverTime":${System.currentTimeMillis()}}"""
                "/fapi/v1/positionSide/dual" -> """{"dualSidePosition":false}"""
                else -> """{"code":-1021,"msg":"Timestamp outside recvWindow"}"""
            }
            Response.Builder().request(r).protocol(Protocol.HTTP_1_1)
                .code(if(isPost)400 else 200).message("test")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            BinanceUsdMFuturesTestnetAdapter("key","secret",client)
                .newOrder("BTCUSDT","BUY","0.01",false,"NO_BLIND_RETRY")
            throw AssertionError("A second -1021 must block execution")
        } catch(e:FuturesHttpException) {
            assertEquals(-1021,e.exchangeCode)
        }
        assertEquals(2,posts)
        assertEquals(2,syncs)
    }

    @Test fun invalidClockProbeIsRetriedAsIndependentReadBeforeAnySignedAccountGet() {
        var timeCalls = 0
        var accountCalls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = if (request.url.encodedPath == "/fapi/v1/time") {
                timeCalls++
                if (timeCalls == 1) """{"serverTime":0}"""
                else """{"serverTime":${System.currentTimeMillis()}}"""
            } else {
                accountCalls++
                assertTrue("Only a signed GET can follow a verified clock probe",
                    request.url.queryParameter("timestamp") != null)
                "{}"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        BinanceUsdMFuturesTestnetAdapter("key", "secret", client).account()
        assertEquals(2, timeCalls)
        assertEquals(1, accountCalls)
    }

    @Test fun boundedSlowClockAllowsSignedRecoveryReadsButNeverMutation() {
        var clockGets = 0
        var accountGets = 0
        var mutationPosts = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = when (request.url.encodedPath) {
                "/fapi/v1/time" -> {
                    clockGets++
                    Thread.sleep(3_200L)
                    """{"serverTime":${System.currentTimeMillis()}}"""
                }
                "/fapi/v3/account" -> {
                    accountGets++
                    assertNotNull(request.url.queryParameter("timestamp"))
                    "{}"
                }
                else -> {
                    mutationPosts++
                    "{}"
                }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val adapter = BinanceUsdMFuturesTestnetAdapter("key", "secret", client)
        adapter.account()
        assertEquals(1, clockGets)
        assertEquals(1, accountGets)
        try {
            adapter.setLeverage("BTCUSDT", 10)
            fail("A read-only clock sample with >3s RTT cannot authorize a signed POST")
        } catch (_: FuturesClockSyncException) { }
        assertEquals(0, mutationPosts)
        assertEquals("Rejected write must not secretly refresh the clock and retry", 1, clockGets)
    }

    @Test fun independentClockSampleValidationRejectsDeviceSuspendAndSlowGet() {
        assertTrue(futuresClockProbeUsableForSignedRead(1L, 4_000L, 4_000L, 1_800_000_000_000L))
        assertTrue(!futuresClockProbeUsableForSignedRead(1L, 8_001L, 8_001L, 1_800_000_000_000L))
        assertTrue(!futuresClockProbeUsableForSignedRead(1L, 4_000L, 6_100L, 1_800_000_000_000L))
        assertTrue(!futuresClockProbeUsableForSignedRead(0L, 2_000L, 2_000L, 1_800_000_000_000L))
        assertTrue(!futuresClockProbeUsableForSignedRead(1L, 2_000L, 2_000L, 0L))
    }

    @Test fun slowClockResponseCannotContaminateNextIndependentSample() {
        var timeCalls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath == "/fapi/v1/time") {
                timeCalls++
                if (timeCalls == 1) Thread.sleep(8_100L)
            }
            val response = """{"serverTime":${System.currentTimeMillis()}}"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val adapter = BinanceUsdMFuturesTestnetAdapter("key", "secret", client)
        assertTrue(adapter.serverTime().getLong("serverTime") > 0L)
        assertEquals("The slow first GET must not be used for signing", 2, timeCalls)
    }

    @Test fun twoInvalidTimeSamplesFailClosedWithoutAnySignedRequest() {
        var timeCalls = 0
        var signedCalls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath == "/fapi/v1/time") timeCalls++
            else signedCalls++
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("""{"serverTime":0}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            BinanceUsdMFuturesTestnetAdapter("key", "secret", client).account()
            fail("Unverified exchange clock must never authorize signed requests")
        } catch (_: java.io.IOException) { }
        assertEquals(2, timeCalls)
        assertEquals(0, signedCalls)
    }


    @Test fun clockTransportFailureDoesNotCascadeIntoRepeatedSignedGetRetries() {
        var probes = 0
        var signedCalls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath == "/fapi/v1/time") {
                probes++
                throw java.io.IOException("Injected network outage on read-only clock probe")
            }
            signedCalls++
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            BinanceUsdMFuturesTestnetAdapter("key", "secret", client).account()
            fail("Signing must fail when both clock probes cannot reach Binance")
        } catch (_: FuturesClockSyncException) { }
        assertEquals("An outer signed GET must not cause 8 nested time probes", 2, probes)
        assertEquals(0, signedCalls)
    }

}
