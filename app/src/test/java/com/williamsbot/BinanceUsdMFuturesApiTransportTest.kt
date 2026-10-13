package com.williamsbot

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response as OkResponse
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class BinanceUsdMFuturesApiTransportTest {
    private fun response(chain: Interceptor.Chain, code: Int, body: String): OkResponse =
        OkResponse.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Gateway error")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    @Test
    fun safeOrderPostsOnceAndAcceptsKnownFilledStatus() {
        val calls = AtomicInteger(0)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            assertTrue(chain.request().url.toString().startsWith("https://demo-fapi.binance.com/fapi/v1/order?"))
            response(chain, 200, """{"symbol":"BTCUSDT","clientOrderId":"wb21-entry-1","status":"FILLED"}""")
        }.build()
        val api = BinanceUsdMFuturesApi("key", "secret", demo = true, client = client)
        val result = api.placeOrderSafe(
            symbol = "BTCUSDT",
            side = "BUY",
            type = "MARKET",
            clientOrderId = "wb21-entry-1",
            quantity = "0.01"
        )
        assertEquals("FILLED", result.getString("status"))
        assertEquals(1, calls.get())
    }

    @Test
    fun ambiguousPostIsReconciledAndNeverBlindlyResubmitted() {
        val calls = AtomicInteger(0)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            when (calls.incrementAndGet()) {
                1 -> response(chain, 504, """{"msg":"gateway timeout"}""")
                2 -> {
                    assertTrue(chain.request().url.toString().contains("/fapi/v1/order?"))
                    assertTrue(chain.request().url.toString().contains("origClientOrderId=wb21-entry-2"))
                    response(chain, 200, """{"symbol":"BTCUSDT","clientOrderId":"wb21-entry-2","status":"CANCELED"}""")
                }
                else -> error("A Futures mutation must never be blindly retried")
            }
        }.build()
        val api = BinanceUsdMFuturesApi("key", "secret", demo = true, client = client)
        val error = runCatching {
            api.placeOrderSafe(
                symbol = "BTCUSDT",
                side = "BUY",
                type = "MARKET",
                clientOrderId = "wb21-entry-2",
                quantity = "0.01"
            )
        }.exceptionOrNull()
        assertTrue(error is BinanceFuturesApiException)
        assertFalse((error as BinanceFuturesApiException).unknownExecution)
        assertTrue(error.message.orEmpty().contains("confirmed terminal"))
        assertEquals(2, calls.get())
    }
}
