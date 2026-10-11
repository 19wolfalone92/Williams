package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.Reader
import java.io.StringReader

class ApiHttpContractTest {
    @Test
    fun unknownRouteStatusMapsTo404() {
        assertEquals(404, apiErrorStatus(ApiHttpException(404, "Not Found")))
    }

    @Test
    fun requestBodyReaderHandlesPartialReads() {
        val partial = object : Reader() {
            private val source = "abcdef"
            private var offset = 0

            override fun read(buffer: CharArray, off: Int, len: Int): Int {
                if (offset >= source.length) return -1
                val count = minOf(2, len, source.length - offset)
                source.toCharArray(buffer, off, offset, offset + count)
                offset += count
                return count
            }

            override fun close() = Unit
        }
        assertEquals("abcdef", readApiRequestBody(partial, 6))
    }

    @Test
    fun shortBodyIsBadRequest() {
        val error = assertThrows(ApiHttpException::class.java) {
            readApiRequestBody(StringReader("abc"), 5)
        }
        assertEquals(400, error.statusCode)
    }

    @Test
    fun queryDuplicatesUseLastValueAndDecode() {
        assertEquals("2", parseApiQuery("/api/v1/trades?limit=1&limit=2")["limit"])
        assertEquals("BTC USDT", parseApiQuery("/x?symbol=BTC+USDT")["symbol"])
    }

    @Test
    fun malformedClientInputMapsTo4xxAndUnexpectedErrorTo500() {
        assertEquals(400, apiErrorStatus(IllegalArgumentException("bad input")))
        assertEquals(500, apiErrorStatus(IllegalStateException("internal")))
    }

    @Test
    fun oversizedBodyIsRejected() {
        val error = assertThrows(ApiHttpException::class.java) {
            readApiRequestBody(StringReader(""), MAX_API_REQUEST_BODY_BYTES + 1)
        }
        assertEquals(413, error.statusCode)
    }
}
