package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

class PrivateUserEventInboxTest {
    @Test
    fun accountFingerprintIsStableNonSecretAndAccountScoped() {
        val apiKeyA = "demo-api-key-account-a"
        val apiKeyB = "demo-api-key-account-b"
        val fingerprintA = futuresApiKeyFingerprint(apiKeyA)
        assertEquals(fingerprintA, futuresApiKeyFingerprint(apiKeyA))
        assertNotEquals(fingerprintA, futuresApiKeyFingerprint(apiKeyB))
        assertTrue(fingerprintA.matches(Regex("[0-9a-f]{64}")))
        assertFalse(fingerprintA.contains(apiKeyA))
    }

    @Test
    fun eventFingerprintDeduplicatesExactPayloadButSeparatesChangedPayload() {
        val first = """{"e":"ORDER_TRADE_UPDATE","E":1000,"o":{"s":"BTCUSDT","i":7,"t":9}}"""
        val second = """{"e":"ORDER_TRADE_UPDATE","E":1001,"o":{"s":"BTCUSDT","i":7,"t":10}}"""
        assertEquals(privateUserEventFingerprint(first), privateUserEventFingerprint(first))
        assertNotEquals(privateUserEventFingerprint(first), privateUserEventFingerprint(second))
        assertTrue(privateUserEventFingerprint(first).matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun stateAndProtectionUpdatesDemandImmediateSignedReconciliation() {
        val required = listOf(
            "ORDER_TRADE_UPDATE",
            "ACCOUNT_UPDATE",
            "ALGO_UPDATE",
            "ACCOUNT_CONFIG_UPDATE",
            "CONDITIONAL_ORDER_TRIGGER_REJECT"
        )
        required.forEach { eventType ->
            assertTrue(eventType, privateFuturesStateEventDemandsImmediateReconcile(
                org.json.JSONObject().put("e", eventType)
            ))
        }
        listOf("listenKeyExpired", "serverShutdown", "outboundAccountPosition", "unknown").forEach { eventType ->
            assertFalse(eventType, privateFuturesStateEventDemandsImmediateReconcile(
                org.json.JSONObject().put("e", eventType)
            ))
        }
    }

    @Test
    fun newExposureRequiresAValidEmptyInboxForCurrentCredential() {
        assertFalse(privateUserEventInboxAllowsExposure(null))
        assertFalse(privateUserEventInboxAllowsExposure(org.json.JSONObject()))
        assertFalse(privateUserEventInboxAllowsExposure(org.json.JSONObject()
            .put("schema", "PRIVATE_USER_EVENT_INBOX_V1")
            .put("pending_count", 1)
            .put("other_account_pending_count", 0)))
        assertFalse(privateUserEventInboxAllowsExposure(org.json.JSONObject()
            .put("schema", "PRIVATE_USER_EVENT_INBOX_V1")
            .put("pending_count", 0)))
        assertTrue(privateUserEventInboxAllowsExposure(org.json.JSONObject()
            .put("schema", "PRIVATE_USER_EVENT_INBOX_V1")
            .put("pending_count", 0)
            .put("other_account_pending_count", 2)))
    }

    @Test
    fun failedDrainDoesNotSpinButNewReceiptOrHealthyDrainWakesIt() {
        assertFalse(privateEventDrainMayReschedule(false, false, 4L, 4L))
        assertTrue(privateEventDrainMayReschedule(true, false, 4L, 4L))
        assertFalse(privateEventDrainMayReschedule(true, true, 4L, 4L))
        assertTrue(privateEventDrainMayReschedule(true, true, 4L, 5L))
    }
}
