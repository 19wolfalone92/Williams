package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FuturesIntentPresentationTest {
    private fun bnbExit() = JSONObject().put("symbol", "BNBUSDT").put("direction", "SHORT")
        .put("role", "EXIT").put("order_status", "EXIT_TERMINAL_POSITION_OPEN")
        .put("exchange_order_status", "FILLED").put("kind", "MARKET_REDUCE_ONLY")
        .put("quantity", "0.22").put("executed_qty", "0.22").put("campaign_status", "")

    @Test fun screenshotBnbCloseIsHistoryAndNeverDisplacesActualCampaigns() {
        val exit = bnbExit()
        val liveBnb = JSONObject().put("symbol", "BNBUSDT").put("direction", "LONG")
            .put("role", "ENTRY").put("campaign_status", "OPEN_PROTECTED").put("confirmed_position_qty", "0.21")
        val liveXrp = JSONObject().put("symbol", "XRPUSDT").put("direction", "SHORT")
            .put("role", "ENTRY").put("campaign_status", "OPEN_PROTECTED")
        val input = JSONArray().put(exit).put(liveBnb).put(liveXrp)
        val before = input.toString()
        val result = separateFuturesIntentPresentation(input)
        assertEquals(2, result.active.length())
        assertEquals("LONG", result.active.getJSONObject(0).getString("direction"))
        assertEquals("XRPUSDT", result.active.getJSONObject(1).getString("symbol"))
        assertEquals(1, result.exitReconciliation.length())
        assertEquals(before, input.toString())
        assertFalse(result.exitReconciliation.getJSONObject(0).has("confirmed_flat_at_ms"))
        assertTrue(futuresExitPresentationText(exit).contains("а не действующий ордер"))
    }

    @Test fun onlyExplicitTerminalExitStatusesGoToHistory() {
        for (status in listOf("EXIT_TERMINAL_POSITION_OPEN", "EXIT_CONFIRMED", "FILLED", "FINISHED",
            "CANCELED", "CANCELLED", "REJECTED", "EXPIRED", "EXPIRED_IN_MATCH")) {
            assertTrue(status, isTerminalFuturesExitIntent(bnbExit().put("order_status", status)))
        }
        for (status in listOf("NEW", "PARTIALLY_FILLED", "SUBMISSION_UNKNOWN", "CANCEL_SUBMISSION_UNKNOWN", "UNKNOWN", "")) {
            val exit = bnbExit().put("order_status", status)
            val result = separateFuturesIntentPresentation(JSONArray().put(exit))
            assertFalse(status, isTerminalFuturesExitIntent(exit))
            assertEquals(1, result.active.length())
            assertEquals(0, result.exitReconciliation.length())
        }
    }

    @Test fun filledEntryAndAdditionAreNotTerminalExits() {
        for (role in listOf("ENTRY", "ADD_ON")) {
            val item = bnbExit().put("role", role).put("order_status", "FILLED")
            assertFalse(isTerminalFuturesExitIntent(item))
            assertEquals(1, separateFuturesIntentPresentation(JSONArray().put(item)).active.length())
        }
    }

    @Test fun rawLedgerAndMixedCaseRolesAreRecognizedWithoutInventingOwnership() {
        val exit = bnbExit().apply { remove("role"); remove("order_status") }
            .put("intent_role", "exit").put("exchange_status", "EXIT_TERMINAL_POSITION_OPEN")
        assertEquals("EXIT", futuresIntentRole(exit))
        assertTrue(isTerminalFuturesExitIntent(exit))
        assertFalse(exit.has("strategy_ownership_verified"))
        assertEquals("ENTRY", futuresIntentRole(JSONObject()))
    }

    @Test fun oldNewAdaIntentIsNotAConfirmedLiveBinanceOrder() {
        val now = 1_793_258_185_000L
        val ada = JSONObject().put("symbol", "ADAUSDT")
            .put("role", "ENTRY").put("campaign_status", "ENTRY_PENDING")
            .put("order_status", "NEW").put("last_reconciled_ms", now - 282_621L)
        assertFalse(futuresPendingEntryReceiptFresh(ada, now))
        assertTrue("A stale record must be retained, not destroyed or canceled",
            separateFuturesIntentPresentation(JSONArray().put(ada)).active.length() == 1)
        assertEquals("NEW", ada.getString("order_status"))
        assertTrue(futuresPendingEntryReceiptFresh(ada.put("last_reconciled_ms", now - 1_000L), now))
        assertFalse(futuresPendingEntryReceiptFresh(ada.put("last_reconciled_ms", now + 1_000L), now))
        assertFalse(futuresPendingEntryReceiptFresh(ada.put("last_reconciled_ms", 0L), now))
        assertFalse(futuresPendingEntryReceiptFresh(ada.put("order_status", "SUBMISSION_UNKNOWN"), now))
    }

}
