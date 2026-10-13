package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class BinanceUsdMFuturesApiTest {
    @Test
    fun demoAndProductionUseFuturesOnlyHosts() {
        val demo = BinanceUsdMFuturesApi("key", "secret", demo = true)
        val production = BinanceUsdMFuturesApi("key", "secret", demo = false)
        assertEquals("https://demo-fapi.binance.com", demo.baseUrl)
        assertEquals("https://fapi.binance.com", production.baseUrl)
    }

    @Test
    fun signedQueryUsesHmacSha256AndIncludesTimestampAndRecvWindow() {
        val api = BinanceUsdMFuturesApi("key", "secret", demo = true)
        val query = api.signedQuery(linkedMapOf("symbol" to "BTCUSDT", "side" to "SELL"), 123456L)
        val values = query.split("&").associate {
            val parts = it.split("=", limit = 2)
            URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name()) to
                URLDecoder.decode(parts.getOrElse(1) { "" }, StandardCharsets.UTF_8.name())
        }
        assertEquals("123456", values["timestamp"])
        assertEquals("5000", values["recvWindow"])
        val unsigned = query.substringBefore("&signature=")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("secret".toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val expected = mac.doFinal(unsigned.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, values["signature"])
    }

    @Test
    fun hedgeModeDoesNotAllowReduceOnlyParameter() {
        val api = BinanceUsdMFuturesApi("key", "secret")
        val error = runCatching {
            api.placeOrder(
                symbol = "BTCUSDT",
                side = "SELL",
                type = "MARKET",
                clientOrderId = "wb21-exit-1",
                quantity = "0.01",
                positionSide = "LONG",
                reduceOnly = true
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("reduceOnly must be omitted"))
    }

    @Test
    fun closePositionProtectiveOrderRequiresStopPriceAndNoQuantity() {
        val api = BinanceUsdMFuturesApi("key", "secret")
        val missingStop = runCatching {
            api.placeOrder(
                symbol = "BTCUSDT",
                side = "SELL",
                type = "STOP_MARKET",
                clientOrderId = "wb21-stop-1",
                positionSide = "BOTH",
                closePosition = true
            )
        }.exceptionOrNull()
        assertTrue(missingStop is IllegalArgumentException)

        val quantityConflict = runCatching {
            api.placeOrder(
                symbol = "BTCUSDT",
                side = "SELL",
                type = "STOP_MARKET",
                clientOrderId = "wb21-stop-2",
                quantity = "0.01",
                stopPrice = "60000",
                closePosition = true
            )
        }.exceptionOrNull()
        assertTrue(quantityConflict is IllegalArgumentException)
    }
}
