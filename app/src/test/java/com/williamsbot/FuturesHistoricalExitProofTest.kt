package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FuturesHistoricalExitProofTest {
    private fun fixture(): Pair<JSONObject, JSONArray> {
        val old = JSONObject().put("symbol", "BNBUSDT").put("client_id", "old-entry")
            .put("intent_role", "ENTRY").put("campaign_status", "CLOSED_OR_STOPPED")
        val exit = JSONObject().put("symbol", "BNBUSDT").put("client_id", "old-exit")
            .put("entry_client_id", "old-entry").put("intent_role", "EXIT")
            .put("exchange_status", "EXIT_TERMINAL_POSITION_OPEN").put("order_status", "FILLED")
            .put("quantity", "0.22").put("executed_qty", "0.22")
            .put("created_at_ms", 900L).put("confirmed_flat_at_ms", 2_000L)
        val current = JSONObject().put("symbol", "BNBUSDT").put("client_id", "new-entry")
            .put("intent_role", "ENTRY").put("campaign_status", "OPEN_PROTECTED")
            .put("created_at_ms", 3_000L).put("quantity", "0.21")
        return exit to JSONArray().put(old).put(exit).put(current)
    }

    @Test fun olderFilledBnbExitKeepsItsOwnSignedFlatAcrossANewCampaign() {
        val (exit, intents) = fixture(); val old = intents.toString()
        assertTrue(priorCampaignExitHasSignedFlatProof(exit, intents, 5_000L))
        assertEquals(old, intents.toString())
        assertEquals("OPEN_PROTECTED", intents.getJSONObject(2).getString("campaign_status"))
        assertEquals(2_000L, exit.getLong("confirmed_flat_at_ms"))
    }

    @Test fun missingProofPartialFillWrongIdentityAndFutureTimesStayUnverified() {
        for (change in listOf<(JSONObject, JSONArray) -> Unit>(
            { e, _ -> e.remove("confirmed_flat_at_ms") },
            { e, _ -> e.put("confirmed_flat_at_ms", 6_000L) },
            { e, _ -> e.put("confirmed_flat_at_ms", 800L) },
            { e, _ -> e.put("executed_qty", "0.21") },
            { e, _ -> e.put("entry_client_id", "unknown") },
            { e, _ -> e.put("order_status", "PARTIALLY_FILLED") },
            { _, a -> a.getJSONObject(0).put("campaign_status", "OPEN_PROTECTED") },
            { _, a -> a.getJSONObject(2).put("created_at_ms", 1_000L) },
            { _, a -> a.getJSONObject(2).put("symbol", "XRPUSDT") },
            { _, a -> a.put(JSONObject(a.getJSONObject(0).toString())) }
        )) {
            val (exit, intents) = fixture(); change(exit, intents)
            assertFalse(intents.toString(), priorCampaignExitHasSignedFlatProof(exit, intents, 5_000L))
        }
    }
}
