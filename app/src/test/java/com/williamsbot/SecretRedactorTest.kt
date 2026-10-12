package com.williamsbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRedactorTest {
    @Test
    fun redactsSensitiveQueryJsonAndHeaderFieldsButPreservesOrderEvidence() {
        val sentinels = listOf(
            "TEST_API_KEY_000000000000",
            "TEST_API_SECRET_111111111111",
            "TEST_SIGNATURE_222222222222",
            "TEST_AUTH_333333333333",
            "TEST_TOKEN_444444444444"
        )
        val input = """{"symbol":"BTCUSDT","apiKey":"TEST_API_KEY_000000000000","apiSecret":"TEST_API_SECRET_111111111111","signature":"TEST_SIGNATURE_222222222222","authorization":"Bearer TEST_AUTH_333333333333","token":"TEST_TOKEN_444444444444"}"""
        val redacted = SecretRedactor.redact(input)!!
        assertTrue(redacted.contains("BTCUSDT"))
        assertTrue(redacted.contains("[REDACTED]"))
        assertFalse(sentinels.any { redacted.contains(it) })

        val query = "symbol=BTCUSDT&signature=TEST_SIGNATURE_222222222222&recvWindow=5000"
        val safeQuery = SecretRedactor.redact(query)!!
        assertTrue(safeQuery.contains("symbol=BTCUSDT"))
        assertFalse(safeQuery.contains("TEST_SIGNATURE_222222222222"))

        val header = "Authorization: Bearer TEST_AUTH_333333333333"
        assertFalse(SecretRedactor.redact(header)!!.contains("TEST_AUTH_333333333333"))
    }

    @Test
    fun nullAndNonSensitivePayloadsRemainStable() {
        assertTrue(SecretRedactor.redact(null) == null)
        assertTrue(SecretRedactor.redact("""{"symbol":"ETHUSDT","status":"FILLED"}""") == """{"symbol":"ETHUSDT","status":"FILLED"}""")
    }
}
