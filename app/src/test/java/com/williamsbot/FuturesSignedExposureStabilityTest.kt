package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Signed account snapshot at the beginning of a 14s reconciliation is not
 * proof that the same position still exists at the end of STOP cleanup.
 */
class FuturesSignedExposureStabilityTest {
    private fun pos(
        symbol:String="BTCUSDT", qty:String="1.2",
        side:String="BOTH", entry:String="100.000",
        leverage:String="2", margin:String="isolated"
    )=JSONObject()
        .put("symbol",symbol).put("positionAmt",qty)
        .put("positionSide",side).put("entryPrice",entry)
        .put("leverage",leverage).put("marginType",margin)

    @Test fun verifiedFlatRemainsFlatRegardlessOfZeroPositionFormatting() {
        assertTrue(futuresSignedExposureStable(JSONArray(),JSONArray()))
        assertTrue(futuresSignedExposureStable(
            JSONArray().put(pos(qty="0")),
            JSONArray().put(pos(qty="0.0000"))))
        assertTrue(futuresSignedExposureStable(
            JSONArray().put(pos(symbol="BTCUSDT",qty="0")),
            JSONArray().put(pos(symbol="ETHUSDT",qty="0"))))
    }

    @Test fun samePositionWithDifferentDecimalRepresentationIsStable() {
        assertTrue(futuresSignedExposureStable(
            JSONArray().put(pos(qty="1.20",entry="100.0000")),
            JSONArray().put(pos(qty="1.2",entry="100.0"))))
    }

    @Test fun entryFillOrPartialCloseAfterFirstSignedSnapshotMustBlock() {
        val flat=JSONArray().put(pos(qty="0"))
        val opened=JSONArray().put(pos(qty="0.4",entry="100"))
        assertFalse(futuresSignedExposureStable(flat,opened))
        assertFalse(futuresSignedExposureStable(opened,flat))
        assertFalse(futuresSignedExposureStable(
            JSONArray().put(pos(qty="1.2")),
            JSONArray().put(pos(qty="0.7"))))
        assertFalse(futuresSignedExposureStable(
            JSONArray().put(pos(qty="1.2")),
            JSONArray().put(pos(qty="-1.2"))))
    }

    @Test fun sameQuantityButDifferentOwnerRiskCannotBeCalledUnchanged() {
        assertFalse(futuresSignedExposureStable(
            JSONArray().put(pos(entry="100")),
            JSONArray().put(pos(entry="101"))))
        assertFalse(futuresSignedExposureStable(
            JSONArray().put(pos(leverage="2")),
            JSONArray().put(pos(leverage="5"))))
        assertFalse(futuresSignedExposureStable(
            JSONArray().put(pos(margin="isolated")),
            JSONArray().put(pos(margin="cross"))))
    }

    @Test fun malformedOrHedgeModeSignedResponseMustFailClosed() {
        assertFalse(futuresSignedExposureStable(
            JSONArray().put(pos(qty="NaN")),JSONArray()))
        assertFalse(futuresSignedExposureStable(
            JSONArray(),JSONArray().put(pos(qty="unknown"))))
        assertFalse(futuresSignedExposureStable(
            JSONArray().put(pos(qty="1.0")),JSONArray().put(pos(side="LONG"))))
        assertFalse(futuresSignedExposureStable(
            JSONArray().put(pos()).put(pos()),JSONArray().put(pos())))
        assertFalse(futuresSignedExposureStable(
            JSONArray(),JSONArray().put(JSONObject().put("symbol","BTCUSDT"))))
    }
}
