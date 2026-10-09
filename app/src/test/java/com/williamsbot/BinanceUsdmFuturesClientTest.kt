package com.williamsbot

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BinanceUsdmFuturesClientTest {
    private fun client(): BinanceUsdmFuturesClient =
        BinanceUsdmFuturesClient(
            apiKey = "demo-api-key",
            apiSecret = "demo-api-secret",
            http = OkHttpClient()
        )

    @Test
    fun futuresAdapterIsPinnedToDemoEndpoint() {
        assertEquals(
            "https://demo-fapi.binance.com",
            client().baseUrlForTest()
        )
    }

    @Test
    fun entrySideMappingKeepsShortDistinctFromSpotSellToClose() {
        val api = client()
        assertEquals("BUY", api.directionToEntrySide("LONG"))
        assertEquals("SELL", api.directionToEntrySide("SHORT"))
    }

    @Test
    fun exitAndProtectionSideMappingAlwaysReduceTheNamedDirection() {
        val api = client()
        assertEquals("SELL", api.directionToExitSide("LONG"))
        assertEquals("BUY", api.directionToExitSide("SHORT"))
        assertEquals("SELL", api.directionToProtectiveSide("LONG"))
        assertEquals("BUY", api.directionToProtectiveSide("SHORT"))
    }

    @Test
    fun unknownDirectionFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            client().directionToEntrySide("SELL")
        }
        assertThrows(IllegalArgumentException::class.java) {
            client().directionToExitSide("FLAT")
        }
    }

    @Test
    fun formEncodingIsStableForSignedQuery() {
        assertEquals(
            "symbol=BTCUSDT&recvWindow=5000&timestamp=123",
            BinanceUsdmFuturesClient.formEncode(
                linkedMapOf(
                    "symbol" to "BTCUSDT",
                    "recvWindow" to "5000",
                    "timestamp" to "123"
                )
            )
        )
    }

    @Test
    fun hmacSha256MatchesKnownVector() {
        assertEquals(
            "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
            BinanceUsdmFuturesClient.hmacSha256(
                "The quick brown fox jumps over the lazy dog",
                "key"
            )
        )
    }
}
