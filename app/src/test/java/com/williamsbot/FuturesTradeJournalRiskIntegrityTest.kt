package com.williamsbot

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class FuturesTradeJournalRiskIntegrityTest {
    private fun storedJournal(raw: String?): SharedPreferences =
        Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getString" -> raw
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SharedPreferences

    @Test fun malformedJsonNeverBecomesRiskApprovedEmptyJournal() {
        assertNull(TradeJournal.tradesForRisk(storedJournal("{damaged")))
        assertNull(TradeJournal.tradesForRisk(storedJournal("[null]")))
        assertNull(TradeJournal.tradesForRisk(storedJournal("null")))
    }

    @Test fun genuinelyEmptyJsonArrayIsValidForInitialRiskSession() {
        assertEquals(0, TradeJournal.tradesForRisk(storedJournal("[]"))!!.length())
    }

    @Test fun feeUnknownAndNegativeBreakevenMustFailClosed() {
        val now=Instant.parse("2026-11-19T13:10:00Z").toEpochMilli()
        val closed=Instant.parse("2026-11-19T13:00:00Z").toEpochMilli()
        fun raw(outcome:String,pnl:Double):JSONObject = JSONObject()
            .put("trade_audit_source","BINANCE_USDM_USER_TRADES")
            .put("id","FUTURES:wolf1").put("status","CLOSED")
            .put("closed_at",closed).put("pnl",pnl)
            .put("outcome",outcome).put("pnl_complete",true)
        val contradiction=evaluateFuturesLossStreak(
            JSONArray().put(raw("BREAKEVEN",-4.0)),now)
        assertFalse(contradiction.allowNewEntries)
        assertEquals("FUTURES_LOSS_STREAK_PNL_UNKNOWN",contradiction.reason)
        val fees=evaluateFuturesLossStreak(
            JSONArray().put(raw("LOSS",-4.0).put("fee_known",false)),now)
        assertFalse(fees.allowNewEntries)
        assertEquals("FUTURES_LOSS_STREAK_PNL_UNKNOWN",fees.reason)
        val incomplete=evaluateFuturesLossStreak(
            JSONArray().put(raw("LOSS",-4.0).remove("pnl_complete")),now)
        assertFalse(incomplete.allowNewEntries)
        assertEquals("FUTURES_LOSS_STREAK_PNL_UNKNOWN",incomplete.reason)
    }
}
