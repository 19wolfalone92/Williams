package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignedBinancePositionDirectionTest {
    @Test fun signedShortAlwaysBeatsStaleLongCampaignLabel() {
        val signed = JSONObject().put("symbol", "ETHUSDT").put("positionAmt", "-0.395")
            .put("direction", "LONG").put("qty", 0.395)
        assertEquals("SHORT", signedBinancePositionDirection(signed))
    }

    @Test fun signedLongAlwaysBeatsStaleShortCampaignLabel() {
        val signed = JSONObject().put("symbol", "ETHUSDT").put("positionAmt", "0.395")
            .put("direction", "SHORT").put("qty", 0.395)
        assertEquals("LONG", signedBinancePositionDirection(signed))
    }

    @Test fun flatMissingAndMalformedSignedPositionsDoNotBecomeLong() {
        assertNull(signedBinancePositionDirection(JSONObject().put("positionAmt", "0.0")))
        assertNull(signedBinancePositionDirection(JSONObject().put("positionAmt", "broken")))
        assertNull(signedBinancePositionDirection(JSONObject().put("direction", "LONG")))
    }
}
