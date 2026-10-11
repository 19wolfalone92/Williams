package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class ApiHttpContractTest {
    @Test
    fun unknownRouteStatusMapsTo404() {
        assertEquals(404, apiErrorStatus(ApiHttpException(404, "Not Found")))
    }

    @Test
    fun requestBodyReaderHandlesPartialReads() {
        val partial = object : ByteArrayInputStream(
            "café🙂".toByteArray(StandardCharsets.UTF_8)
        ) {
            override fun read(buffer: ByteArray, off: Int, len: Int): Int =
                super.read(buffer, off, minOf(2, len))
        }
        val expected = "café🙂"
        assertEquals(
            expected,
            readApiRequestBody(partial, expected.toByteArray(StandardCharsets.UTF_8).size)
        )
    }

    @Test
    fun shortBodyIsBadRequest() {
        val error = assertThrows(ApiHttpException::class.java) {
            readApiRequestBody(ByteArrayInputStream("abc".toByteArray()), 5)
        }
        assertEquals(400, error.statusCode)
    }

    @Test
    fun queryDuplicatesUseLastValueAndDecode() {
        assertEquals("2", parseApiQuery("/api/v1/trades?limit=1&limit=2")["limit"])
        assertEquals("BTC USDT", parseApiQuery("/x?symbol=BTC+USDT")["symbol"])
    }

    @Test
    fun malformedJsonMapsTo4xxAndUnexpectedErrorTo500() {
        assertEquals(400, apiErrorStatus(org.json.JSONException("bad json")))
        assertEquals(500, apiErrorStatus(IllegalStateException("internal")))
    }

    @Test
    fun tradesLimitDefaultsClampsAndRejectsNonIntegers() {
        assertEquals(50, apiTradesLimit(emptyMap()))
        assertEquals(2, apiTradesLimit(mapOf("limit" to "2")))
        assertEquals(200, apiTradesLimit(mapOf("limit" to "999")))
        assertEquals(1, apiTradesLimit(mapOf("limit" to "0")))
        val error = assertThrows(ApiHttpException::class.java) {
            apiTradesLimit(mapOf("limit" to "bad"))
        }
        assertEquals(422, error.statusCode)
    }

    @Test
    fun oversizedBodyIsRejected() {
        val error = assertThrows(ApiHttpException::class.java) {
            readApiRequestBody(ByteArrayInputStream(byteArrayOf()), MAX_API_REQUEST_BODY_BYTES + 1)
        }
        assertEquals(413, error.statusCode)
    }
}
