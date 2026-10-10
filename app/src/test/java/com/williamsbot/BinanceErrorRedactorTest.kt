package com.williamsbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BinanceErrorRedactorTest {
    @Test
    fun responseMarkersAreNotRenderedInErrorSummary() {
        val marker = "SYNTHETIC_SECRET_MARKER_7d9a"
        val body = """{"code":-1000,"msg":"$marker","apiSecret":"$marker"}"""
        val summary = BinanceErrorRedactor.safeFailure(429, body)
        assertFalse(summary.contains(marker))
        assertTrue(summary.contains("HTTP 429"))
        assertTrue(summary.contains(BinanceErrorRedactor.REDACTED_BODY))
    }

    @Test
    fun arbitraryCodeStringIsNotReflected() {
        val marker = "SYNTHETIC_SECRET_MARKER_7d9a"
        val body = """{"code":"$marker","msg":"error"}"""
        val summary = BinanceErrorRedactor.safeFailure(400, body)
        assertFalse(summary.contains(marker))
        assertTrue(summary.contains("API code=unknown"))
    }

    @Test
    fun onlyNumericApiCodeIsPreserved() {
        assertTrue(BinanceErrorRedactor.safeFailure(400, """{"code":-1021}""").contains("API code=-1021"))
    }
}
