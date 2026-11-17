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
        assertFalse(privateUserEventInboxAllowsExposure(org.json.JSONObject()
            .put("schema", "PRIVATE_USER_EVENT_INBOX_V1")
            .put("account_fingerprint_present", false)
            .put("pending_count", 0)
            .put("processed_count", 0)
            .put("other_account_pending_count", 0)))
        assertTrue(privateUserEventInboxAllowsExposure(org.json.JSONObject()
            .put("schema", "PRIVATE_USER_EVENT_INBOX_V1")
            .put("account_fingerprint_present", true)
            .put("pending_count", 0)
            .put("processed_count", 7)
            .put("other_account_pending_count", 2)))
    }

    @Test
    fun replayedOrOutOfOrderWsCannotRewindDurableOrderStatusOrFill() {
        fun allow(oldTime:Long,oldId:String,oldQty:String,oldStatus:String,
            at:Long,id:String,qty:String,status:String)=privateOrderProjectionMayAdvance(
            oldTime,oldId,oldQty,oldStatus,at,id,qty,status)
        assertTrue(allow(1_000L,"42","0","NEW",2_000L,"42","0.5","PARTIALLY_FILLED"))
        assertTrue(allow(1_000L,"42","0.5","PARTIALLY_FILLED",
            2_000L,"42","1","FILLED"))
        assertFalse(allow(2_000L,"42","1","FILLED",1_000L,"42","0","NEW"))
        assertFalse(allow(2_000L,"42","1","FILLED",3_000L,"42","1","NEW"))
        assertFalse(allow(2_000L,"42","1","FILLED",3_000L,"42","1","CANCELED"))
        assertFalse(allow(2_000L,"42","0.5","PARTIALLY_FILLED",
            3_000L,"42","0.2","PARTIALLY_FILLED"))
        assertFalse(allow(2_000L,"42","0.5","PARTIALLY_FILLED",
            3_000L,"42","0.5","NEW"))
        assertFalse(allow(2_000L,"42","0.5","PARTIALLY_FILLED",
            3_000L,"999","0.5","FILLED"))
        assertFalse(allow(2_000L,"42","0.5","PARTIALLY_FILLED",
            3_000L,"42","bad","FILLED"))
        assertFalse(allow(2_000L,"42","0.5","PARTIALLY_FILLED",
            0L,"42","0.5","FILLED"))
    }

    @Test
    fun failedDrainDoesNotSpinButNewReceiptOrHealthyDrainWakesIt() {
        assertFalse(privateEventDrainMayReschedule(false, false, 4L, 4L))
        assertFalse(privateEventDrainMayReschedule(null, false, 4L, 4L))
        assertTrue(privateEventDrainMayReschedule(true, false, 4L, 4L))
        assertFalse(privateEventDrainMayReschedule(true, true, 4L, 4L))
        assertTrue(privateEventDrainMayReschedule(true, true, 4L, 5L))
    }
}
