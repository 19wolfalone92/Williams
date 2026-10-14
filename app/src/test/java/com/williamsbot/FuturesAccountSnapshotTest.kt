package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FuturesAccountSnapshotTest {
    @Test
    fun parsesOfficialRootSummaryWithStringFields() {
        val account = JSONObject()
            .put("availableBalance", "123.45")
            .put("totalWalletBalance", "125.00")
            .put("totalUnrealizedProfit", "-1.50")
            .put("totalMarginBalance", "123.50")
        val parsed = parseFuturesAccountSnapshot(account)!!
        assertEquals(123.45, parsed.availableUsdt, 0.0)
        assertEquals(125.00, parsed.walletUsdt, 0.0)
        assertEquals(123.50, parsed.equityUsdt, 0.0)
    }

    @Test
    fun parsesNumericJsonAndKeepsAnEmptyDemoWalletKnownAsZero() {
        val account = JSONObject()
            .put("availableBalance", 0.0)
            .put("totalWalletBalance", 0.0)
            .put("totalUnrealizedProfit", 0.0)
            .put("totalMarginBalance", 0.0)
        val parsed = parseFuturesAccountSnapshot(account)!!
        assertEquals(0.0, parsed.availableUsdt, 0.0)
        assertEquals(0.0, parsed.walletUsdt, 0.0)
        assertEquals(0.0, parsed.equityUsdt, 0.0)
        assertEquals(false, futuresAccountFundedForEntry(parsed.availableUsdt, parsed.equityUsdt))
    }

    @Test
    fun fallsBackToUsdtAssetFieldsWhenRootSummaryIsOmitted() {
        val account = JSONObject().put(
            "assets",
            org.json.JSONArray().put(
                JSONObject()
                    .put("asset", "BTC")
                    .put("walletBalance", "10")
            ).put(
                JSONObject()
                    .put("asset", "USDT")
                    .put("availableBalance", "9.5")
                    .put("walletBalance", "10")
                    .put("unrealizedProfit", "-0.25")
                    .put("marginBalance", "9.75")
            )
        )
        val parsed = parseFuturesAccountSnapshot(account)!!
        assertEquals(9.5, parsed.availableUsdt, 0.0)
        assertEquals(10.0, parsed.walletUsdt, 0.0)
        assertEquals(9.75, parsed.equityUsdt, 0.0)
    }

    @Test
    fun rejectsMissingOrInvalidFieldsInsteadOfInventingBalance() {
        assertNull(parseFuturesAccountSnapshot(JSONObject()))
        assertNull(parseFuturesAccountSnapshot(JSONObject()
            .put("availableBalance", "bad")
            .put("totalWalletBalance", "10")
            .put("totalUnrealizedProfit", "0")
            .put("totalMarginBalance", "10")))
        assertNull(parseFuturesAccountSnapshot(JSONObject()
            .put("availableBalance", "-1")
            .put("totalWalletBalance", "10")
            .put("totalUnrealizedProfit", "0")
            .put("totalMarginBalance", "10")))
    }
}
