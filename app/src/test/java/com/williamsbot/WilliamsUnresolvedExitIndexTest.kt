package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class WilliamsUnresolvedExitIndexTest {
    private fun oldRule(rows: JSONArray, symbol: String): Boolean =
        (0 until rows.length()).any { i ->
            val exit = rows.optJSONObject(i) ?: return@any true
            exit.optString("intent_role") == "EXIT" &&
                exit.optString("symbol").equals(symbol, true) &&
                exit.optString("exchange_status").uppercase(Locale.US) != "EXIT_CONFIRMED"
        }

    @Test fun indexMatchesLegacyFailClosedRuleForMixedHistoricalJournal() {
        val rows=JSONArray()
        for (i in 0 until 900) {
            rows.put(JSONObject().put("intent_role",if(i%3==0)"EXIT" else "ENTRY")
                .put("symbol",if(i%2==0)"BTCUSDT" else "ETHUSDT")
                .put("exchange_status",if(i%5==0)"FILLED" else "EXIT_CONFIRMED"))
        }
        rows.put(JSONObject().put("intent_role","EXIT").put("symbol","avAxusdt")
            .put("exchange_status","exit_confirmed"))
        val index=buildWilliamsUnresolvedExitIndex(rows)
        for (symbol in listOf("BTCUSDT","ETHUSDT","AVAXUSDT","BNBUSDT","SOLUSDT")) {
            assertEquals("Legacy parity for $symbol",oldRule(rows,symbol),
                index.mayHaveUnresolvedExit(symbol))
        }
        assertFalse(index.mayHaveUnresolvedExit("SOLUSDT"))
        assertFalse(index.unknownMalformedIntent)
    }

    @Test fun invalidRowsKeepOriginalGlobalUnresolvedExitVeto() {
        val rows=JSONArray().put(JSONObject().put("intent_role","ENTRY")
            .put("symbol","BTCUSDT")).put(JSONObject.NULL)
        val index=buildWilliamsUnresolvedExitIndex(rows)
        assertTrue(index.unknownMalformedIntent)
        for(symbol in listOf("BTCUSDT","ETHUSDT","DOGEUSDT"))
            assertEquals(oldRule(rows,symbol),index.mayHaveUnresolvedExit(symbol))
    }

    @Test fun exitStatusAndSymbolMustRemainCaseInsensitiveButRoleMustBeExact() {
        val rows=JSONArray()
            .put(JSONObject().put("intent_role","EXIT").put("symbol","solusdt")
                .put("exchange_status","EXIT_PENDING"))
            .put(JSONObject().put("intent_role","exit").put("symbol","BNBUSDT")
                .put("exchange_status","PENDING"))
            .put(JSONObject().put("intent_role","EXIT").put("symbol","ETHUSDT")
                .put("exchange_status","eXiT_cOnFiRmEd"))
        val index=buildWilliamsUnresolvedExitIndex(rows)
        assertTrue(index.mayHaveUnresolvedExit("SOLUSDT"))
        assertFalse(index.mayHaveUnresolvedExit("BNBUSDT"))
        assertFalse(index.mayHaveUnresolvedExit("ETHUSDT"))
    }
}
