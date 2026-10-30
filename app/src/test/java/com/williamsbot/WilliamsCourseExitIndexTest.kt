package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WilliamsCourseExitIndexTest {
    private fun previousSelection(rows: JSONArray, owner: String, symbol: String): JSONObject? =
        (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.lastOrNull {
            it.optString("intent_role") == "EXIT" &&
                it.optString("entry_client_id") == owner &&
                it.optString("symbol") == symbol &&
                it.optString("reason") == "COURSE_W5_TERMINAL_CONFIRMED" &&
                !it.optBoolean("partial_reduction", false) &&
                it.optString("exchange_status") == "EXIT_CONFIRMED" &&
                it.optString("order_status") == "FILLED"
        }

    private fun row(client: String, owner: String, symbol: String, reason: String, state: String) =
        JSONObject().put("intent_role", "EXIT").put("client_id", client)
            .put("entry_client_id", owner).put("symbol", symbol)
            .put("reason", reason).put("exchange_status", state)
            .put("order_status", "FILLED")

    @Test fun lastMatchingExitAndNegativeCasesMatchLegacySemantics() {
        val rows=JSONArray()
            .put(row("first", "A", "BTCUSDT", "COURSE_W5_TERMINAL_CONFIRMED", "EXIT_CONFIRMED"))
            .put(row("wrong reason", "A", "BTCUSDT", "MANUAL_EXIT", "EXIT_CONFIRMED"))
            .put(row("other symbol", "A", "ETHUSDT", "COURSE_W5_TERMINAL_CONFIRMED", "EXIT_CONFIRMED"))
            .put(row("pending", "A", "BTCUSDT", "COURSE_W5_TERMINAL_CONFIRMED", "EXIT_PENDING"))
            .put(row("second", "A", "BTCUSDT", "COURSE_W5_TERMINAL_CONFIRMED", "EXIT_CONFIRMED"))
            .put(row("partial", "A", "BTCUSDT", "COURSE_W5_TERMINAL_CONFIRMED", "EXIT_CONFIRMED")
                .put("partial_reduction",true))
            .put(JSONObject.NULL)
        val index=indexWilliamsCourseTerminalExits(rows)
        for ((owner,symbol) in listOf("A" to "BTCUSDT","A" to "ETHUSDT",
            "A" to "SOLUSDT","B" to "BTCUSDT")) {
            assertEquals(previousSelection(rows,owner,symbol)?.optString("client_id"),
                index[owner to symbol]?.optString("client_id"))
        }
        assertEquals("second",index["A" to "BTCUSDT"]?.optString("client_id"))
        assertNull(index["B" to "BTCUSDT"])
    }

    @Test fun largeHistoricalLedgerRequiresOnlyOneIndexRegardlessOfOwnerCount() {
        val rows=JSONArray()
        for (i in 0 until 1000) {
            rows.put(row("exit_$i","owner_$i","BTCUSDT",
                "COURSE_W5_TERMINAL_CONFIRMED","EXIT_CONFIRMED"))
        }
        val index=indexWilliamsCourseTerminalExits(rows)
        assertEquals(1000,index.size)
        assertEquals("exit_999",index["owner_999" to "BTCUSDT"]?.optString("client_id"))
    }
}
