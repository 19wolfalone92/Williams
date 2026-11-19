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

    @Test fun priorUtcSessionEntryWithUnknownCloseTimeCannotSilentlySkipTodaysLosses() {
        val openedYesterday = Instant.parse("2026-11-18T18:00:00Z").toEpochMilli()
        val oldFill = Instant.parse("2026-11-18T18:10:00Z").toEpochMilli()
        val unresolved = owner(status="RETRY", closedAt=0L)
            .put("created_at_ms", openedYesterday)
            .put("fill_observed_at_ms", oldFill)
        assertEquals("FUTURES_LOSS_STREAK_CLOSE_TIME_UNVERIFIED",
            futuresUnjournalledClosedCampaignBlocker(
                JSONArray().put(unresolved), JSONArray(), now))
    }

    @Test fun authenticatedCrossDayCloseJournalProvesItsSessionEvenWhenOwnerTimestampMissing() {
        val openedYesterday = Instant.parse("2026-11-18T18:00:00Z").toEpochMilli()
        val actualToday = Instant.parse("2026-11-19T09:00:00Z").toEpochMilli()
        val unresolved = owner(status="RECORDED", closedAt=0L)
            .put("created_at_ms", openedYesterday)
            .put("fill_observed_at_ms", openedYesterday + 10_000L)
        val journal = JSONArray().put(JSONObject()
            .put("id","FUTURES:wb-abc")
            .put("trade_audit_source","BINANCE_USDM_USER_TRADES")
            .put("status","CLOSED").put("closed_at",actualToday)
            .put("pnl_complete",true).put("fee_known",true)
            .put("pnl",-5.0).put("outcome","LOSS"))
        assertNull(futuresUnjournalledClosedCampaignBlocker(
            JSONArray().put(unresolved),journal,now))
        val streak = evaluateFuturesLossStreak(journal,now)
        assertEquals(1,streak.consecutiveLosses)
        assertTrue(streak.allowNewEntries)
    }

    @Test fun partialOrUnverifiedCrossDayJournalCannotProveCloseTime() {
        val unresolved = owner(status="RECORDED", closedAt=0L)
            .put("created_at_ms", Instant.parse("2026-11-18T18:00:00Z").toEpochMilli())
        val incomplete = JSONArray().put(JSONObject()
            .put("id","FUTURES:wb-abc")
            .put("trade_audit_source","BINANCE_USDM_USER_TRADES")
            .put("status","CLOSED")
            .put("closed_at", Instant.parse("2026-11-19T10:00:00Z").toEpochMilli())
            .put("pnl_complete",false).put("fee_known",false)
            .put("pnl",-1.0))
        assertEquals("FUTURES_LOSS_STREAK_CLOSE_TIME_UNVERIFIED",
            futuresUnjournalledClosedCampaignBlocker(
                JSONArray().put(unresolved),incomplete,now))
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
