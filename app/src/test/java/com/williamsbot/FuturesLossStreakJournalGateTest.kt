package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class FuturesLossStreakJournalGateTest {
    private val now=Instant.parse("2026-11-19T14:00:00Z").toEpochMilli()
    private val close=Instant.parse("2026-11-19T12:20:00Z").toEpochMilli()

    private fun owner(
        status:String = "RETRY",
        seenFill:Boolean = true,
        closedAt:Long = close,
        clientId:String = "wb-abc"
    ):JSONObject = JSONObject()
        .put("intent_role","ENTRY")
        .put("campaign_status","CLOSED_OR_STOPPED")
        .put("client_id", clientId)
        .put("trade_journal_status",status)
        .put("closed_observed_at_ms",closedAt)
        .put("fill_observed_at_ms",if(seenFill) close-100000L else 0L)

    @Test fun signedFilledCloseNotYetJournaledBlocksNewFuturesTrades() {
        val reason=futuresUnjournalledClosedCampaignBlocker(
            JSONArray().put(owner()),JSONArray(),now)
        assertEquals("FUTURES_LOSS_STREAK_EXCHANGE_CLOSE_UNJOURNALLED",reason)
    }

    @Test fun recordedClaimMissingActualJournalRowAlsoBlocks() {
        val reason=futuresUnjournalledClosedCampaignBlocker(
            JSONArray().put(owner(status="RECORDED")),JSONArray(),now)
        assertEquals("FUTURES_LOSS_STREAK_EXCHANGE_CLOSE_UNJOURNALLED",reason)
    }

    @Test fun signedFillWithPersistedJournalRowAllowsNormalStreakEvaluation() {
        val journal=JSONArray().put(JSONObject().put("id","FUTURES:wb-abc"))
        val reason=futuresUnjournalledClosedCampaignBlocker(
            JSONArray().put(owner(status="RECORDED")),journal,now)
        assertNull(reason)
    }

    @Test fun unfilledRejectedOrdersDoNotBlockTradingByTheirAbsenceFromJournal() {
        assertNull(futuresUnjournalledClosedCampaignBlocker(
            JSONArray().put(owner(status="NO_FILLED_TRADES",seenFill=false)),
            JSONArray(),now))
    }

    @Test fun oldUtcDayUnjournalledCloseDoesNotAffectCurrentDayStreak() {
        val old=Instant.parse("2026-11-18T23:55:00Z").toEpochMilli()
        assertNull(futuresUnjournalledClosedCampaignBlocker(
            JSONArray().put(owner(closedAt=old)),JSONArray(),now))
    }

    @Test fun futureCloseTimeCannotAuthorizeRisk() {
        val future=now+60000L
        assertEquals("FUTURES_LOSS_STREAK_CLOSE_TIME_UNVERIFIED",
            futuresUnjournalledClosedCampaignBlocker(
                JSONArray().put(owner(closedAt=future)),JSONArray(),now))
    }

    @Test fun malformedIntentRecordIsNotAnEmptyClosedHistory() {
        assertEquals("FUTURES_LOSS_STREAK_INTENTS_UNVERIFIED",
            futuresUnjournalledClosedCampaignBlocker(
                JSONArray().put("damaged"),JSONArray(),now))
    }
}
