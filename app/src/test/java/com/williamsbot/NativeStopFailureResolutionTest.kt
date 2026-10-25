package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeStopFailureResolutionTest {
    @Test fun onlyExactCompletedCurrentReplacementResolvesItsOwnPastFailure() {
        val failure=JSONObject().put("at_ms",130L)
            .put("phase","CANCEL_BRIDGE").put("message","Temporary stop child unverified")
        val owner=JSONObject().put("last_native_stop_failure",failure)
        val tx=JSONObject().put("created_at_ms",100L).put("phase","CANCEL_BRIDGE")
        annotateNativeStopFailureResolution(owner,tx,"W2T_SOLUSDT_xyz",150L)
        assertFalse(failure.has("resolution"))
        tx.put("phase","COMPLETED")
        annotateNativeStopFailureResolution(owner,tx,"W2T_SOLUSDT_xyz",150L)
        assertEquals("EXCHANGE_CONFIRMED_NEW_STOP_ACTIVE",failure.getString("resolution"))
        assertEquals("W2T_SOLUSDT_xyz",failure.getString("resolution_protection_client_id"))
        assertEquals("Temporary stop child unverified",failure.getString("message"))
        annotateNativeStopFailureResolution(owner,tx,"W2T_OTHER_ID",200L)
        assertEquals("W2T_SOLUSDT_xyz",failure.getString("resolution_protection_client_id"))
    }

    @Test fun successInNextCampaignCannotMislabelAnOldFailure() {
        val failure=JSONObject().put("at_ms",30L).put("message","OLD")
        val owner=JSONObject().put("last_native_stop_failure",failure)
        annotateNativeStopFailureResolution(owner,JSONObject()
            .put("phase","COMPLETED").put("created_at_ms",100L),"W2T_BTC",110L)
        assertFalse(failure.has("resolution"))
        annotateNativeStopFailureResolution(owner,JSONObject()
            .put("phase","COMPLETED").put("created_at_ms",20L),"",110L)
        assertFalse(failure.has("resolution"))
    }
}
