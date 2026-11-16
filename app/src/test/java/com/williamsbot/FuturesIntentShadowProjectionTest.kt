package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FuturesIntentShadowProjectionTest {
    @Test
    fun projectionPreservesOrderIdentityAndRiskRelevantFields() {
        val raw = JSONArray()
            .put(JSONObject()
                .put("client_id", "ENTRY_BTC_1")
                .put("symbol", "BTCUSDT")
                .put("intent_role", "ENTRY")
                .put("campaign_status", "OPEN_PROTECTED")
                .put("order_status", "FILLED")
                .put("exchange_status", "FILLED")
                .put("protection_status", "PROTECTED")
                .put("strategy_profile", williamsTc2Profile))
            .put(JSONObject()
                .put("client_id", "ADD_BTC_2")
                .put("symbol", "BTCUSDT")
                .put("intent_role", "ADD_ON")
                .put("campaign_status", "ENTRY_PENDING")
                .put("order_status", "NEW")
                .put("exchange_status", "NEW")
                .put("protection_status", "PROTECTED")
                .put("strategy_profile", williamsTc2Profile))
            .toString()

        val projection = projectFuturesCampaignIntents(raw)
        assertNotNull(projection)
        assertEquals(2, projection!!.rows.size)
        assertEquals(0, projection.rows[0].ordinal)
        assertEquals("ENTRY_BTC_1", projection.rows[0].clientId)
        assertEquals("OPEN_PROTECTED", projection.rows[0].campaignStatus)
        assertEquals("ADD_BTC_2", projection.rows[1].clientId)
        assertEquals("ADD_ON", projection.rows[1].intentRole)
        assertTrue(projection.payloadSha256.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun malformedLedgerAndNonObjectRowsDoNotProduceAFalseProjection() {
        assertNull(projectFuturesCampaignIntents(""))
        assertNull(projectFuturesCampaignIntents("not-json"))
        assertNull(projectFuturesCampaignIntents("""["not-an-intent-object"]"""))
        assertNull(projectFuturesCampaignIntents("""[null]"""))
    }

    @Test
    fun exactProjectionParityPassesButChangedRowIsWarning() {
        val raw = JSONArray().put(JSONObject()
            .put("client_id", "ENTRY_ETH_1")
            .put("symbol", "ETHUSDT")
            .put("intent_role", "ENTRY")
            .put("campaign_status", "ENTRY_PENDING")
            .put("order_status", "NEW")
            .put("exchange_status", "NEW")
            .put("protection_status", "NOT_SUBMITTED")
            .put("strategy_profile", williamsTc2Profile)).toString()
        val canonical = projectFuturesCampaignIntents(raw)!!
        assertEquals("PASS", futuresIntentShadowParity(canonical, canonical.rows).getString("severity"))
        val damaged = canonical.rows.single().copy(orderStatus = "FILLED")
        val mismatch = futuresIntentShadowParity(canonical, listOf(damaged))
        assertEquals("WARNING", mismatch.getString("severity"))
        assertFalse(mismatch.getBoolean("parity"))
        assertFalse(mismatch.getBoolean("authority_switch_allowed"))
    }

    @Test
    fun missingCanonicalOrShadowIsNeverReportedAsPass() {
        assertEquals("NOT_VERIFIED", futuresIntentShadowParity(null, emptyList())
            .getString("severity"))
        val canonical = projectFuturesCampaignIntents("[]")!!
        assertEquals("NOT_VERIFIED", futuresIntentShadowParity(canonical, null)
            .getString("severity"))
    }
}
