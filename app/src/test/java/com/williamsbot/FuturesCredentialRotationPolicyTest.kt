package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FuturesCredentialRotationPolicyTest {
    private fun flat()=JSONArray().put(JSONObject()
        .put("symbol","BTCUSDT").put("positionAmt","0"))
    @Test fun onlyTwoSignedFlatPositionSnapshotsAndNoOpenOrdersAuthorizeRotation() {
        assertTrue(futuresOldAccountHasNoExposure(flat(),JSONArray(),JSONArray(),flat()))
        assertTrue(futuresOldAccountHasNoExposure(JSONArray(),JSONArray(),JSONArray(),JSONArray()))
        assertFalse(futuresOldAccountHasNoExposure(null,JSONArray(),JSONArray(),flat()))
        assertFalse(futuresOldAccountHasNoExposure(flat(),null,JSONArray(),flat()))
        assertFalse(futuresOldAccountHasNoExposure(flat(),JSONArray(),null,flat()))
        assertFalse(futuresOldAccountHasNoExposure(flat(),JSONArray(),JSONArray(),null))
        assertFalse(futuresOldAccountHasNoExposure(flat(),JSONArray().put(JSONObject()),JSONArray(),flat()))
        assertFalse(futuresOldAccountHasNoExposure(flat(),JSONArray(),JSONArray().put(JSONObject()),flat()))
        val filled=flat().apply { getJSONObject(0).put("positionAmt","0.15") }
        assertFalse(futuresOldAccountHasNoExposure(filled,JSONArray(),JSONArray(),flat()))
        assertFalse(futuresOldAccountHasNoExposure(flat(),JSONArray(),JSONArray(),filled))
        val malformed=JSONArray().put(JSONObject().put("symbol","BTCUSDT"))
        assertFalse(futuresOldAccountHasNoExposure(malformed,JSONArray(),JSONArray(),flat()))
        assertFalse(futuresOldAccountHasNoExposure(flat(),JSONArray(),JSONArray(),malformed))
    }
}
