package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression for 8.6-second flat-history STOP cleanup observed on Samsung 4.64.16. */
class HistoricalFlatSignedAlgoPreflightTest {
    private fun algo(symbol:String,id:String):JSONObject =
        JSONObject().put("symbol",symbol).put("clientAlgoId",id)
            .put("algoStatus","NEW").put("algoId","12345")

    @Test fun absentExactHistoricalStopNeedsNoAdditionalPerSymbolGet() {
        val signedAll = JSONArray()
        val originalIds = setOf("W2PS_BTCUSDT_HIST_A")
        assertFalse(historicalFlatAlgoNeedsSymbolRefresh(
            signedAll,"BTCUSDT",originalIds))
        // A live unrelated STOP on a different pair is not a historic BTC STOP.
        signedAll.put(algo("ETHUSDT","W2PS_ETHUSDT_HIST_A"))
        assertFalse(historicalFlatAlgoNeedsSymbolRefresh(
            signedAll,"BTCUSDT",originalIds))
        // A prefix match is NEVER identity.
        signedAll.put(algo("BTCUSDT","W2PS_BTCUSDT_HIST_A_2"))
        assertFalse(historicalFlatAlgoNeedsSymbolRefresh(
            signedAll,"BTCUSDT",originalIds))
    }

    @Test fun matchingOldStopTriggersFreshSignedSymbolGetBeforeAnyCancel() {
        val signedAll = JSONArray().put(algo("BTCUSDT","W2PS_BTCUSDT_HIST_A"))
        assertTrue(historicalFlatAlgoNeedsSymbolRefresh(
            signedAll,"BTCUSDT",setOf("W2PS_BTCUSDT_HIST_A")))
        // Wrong pair is not an acceptable owner for the same clientAlgoId.
        assertFalse(historicalFlatAlgoNeedsSymbolRefresh(
            signedAll,"SOLUSDT",setOf("W2PS_BTCUSDT_HIST_A")))
        assertTrue(historicalFlatAlgoNeedsSymbolRefresh(
            signedAll,"btcusdt",setOf("W2PS_BTCUSDT_HIST_A")))
    }

    @Test fun incompleteOrAmbiguousSignedDataFallsBackToFreshPerSymbolProof() {
        val ids = setOf("W2PS_BTCUSDT_HIST_A")
        assertTrue(historicalFlatAlgoNeedsSymbolRefresh(
            JSONArray().put(JSONObject().put("symbol","BTCUSDT")),"BTCUSDT",ids))
        assertTrue(historicalFlatAlgoNeedsSymbolRefresh(
            JSONArray().put(JSONObject().put("clientAlgoId","unrelated")),"BTCUSDT",ids))
        assertTrue(historicalFlatAlgoNeedsSymbolRefresh(
            JSONArray().put("malformed"),"BTCUSDT",ids))
        assertTrue(historicalFlatAlgoNeedsSymbolRefresh(
            JSONArray(),"",ids))
        assertTrue(historicalFlatAlgoNeedsSymbolRefresh(
            JSONArray(),"BTCUSDT",emptySet()))
    }

    @Test fun previousReplacementAndBridgeIdsAreIndependentlyChecked() {
        val ids = setOf("W2PS_OLD","W2PS_PREVIOUS","W2PS_BRIDGE","W2PS_NEW")
        for (id in ids) {
            assertTrue(historicalFlatAlgoNeedsSymbolRefresh(
                JSONArray().put(algo("BTCUSDT",id)), "BTCUSDT", ids))
        }
    }
}
