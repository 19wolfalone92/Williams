package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class FuturesLossStreakUtcResetTest {
    private fun row(time:String,outcome:String="LOSS",id:String="FUTURES:test"):JSONObject =
        JSONObject().put("id",id).put("trade_audit_source","BINANCE_USDM_USER_TRADES")
            .put("status","CLOSED").put("closed_at",Instant.parse(time).toEpochMilli())
            .put("outcome",outcome).put("pnl_complete",true)
            .put("pnl",if(outcome=="LOSS") -1.25 else 1.0)

    @Test fun threeLossesHardPauseOnSameUtcDayButResetAfterUtcMidnight() {
        val trades=JSONArray()
            .put(row("2026-10-19T20:00:00Z"))
            .put(row("2026-10-19T21:00:00Z"))
            .put(row("2026-10-19T22:00:00Z"))
        val before=Instant.parse("2026-10-19T23:59:59Z").toEpochMilli()
        val after=Instant.parse("2026-10-20T00:00:01Z").toEpochMilli()
        val blocked=evaluateFuturesLossStreak(trades,before)
        assertEquals("FUTURES_CONSECUTIVE_LOSS_HARD_PAUSE",blocked.reason)
        assertEquals(3,blocked.consecutiveLosses)
        assertEquals(3,blocked.dailyLossTrades)
        assertFalse(blocked.allowNewEntries)
        val reset=evaluateFuturesLossStreak(trades,after)
        assertTrue(reset.available)
        assertTrue(reset.allowNewEntries)
        assertEquals(0,reset.consecutiveLosses)
        assertNull(reset.reason)
        assertNull(reset.cooldownUntilMs)
        assertEquals(Instant.parse("2026-10-20T00:00:00Z").toEpochMilli(),
            futuresUtcDayStartMs(after))
    }

    @Test fun twoConfirmedSameDayLossesAreNotTheThreeTradeBlock() {
        val trades=JSONArray()
            .put(row("2026-10-20T06:00:00Z"))
            .put(row("2026-10-20T06:03:00Z"))
        val now=Instant.parse("2026-10-20T06:10:00Z").toEpochMilli()
        val dec=evaluateFuturesLossStreak(trades,now)
        assertTrue(dec.available)
        assertTrue(dec.allowNewEntries)
        assertEquals(2,dec.dailyLossTrades)
        assertEquals(2,dec.consecutiveLosses)
        assertNull(dec.reason)
        assertNull(dec.cooldownUntilMs)
    }

    @Test fun threeLossesAcrossOneUtcDayBlockEvenWithWinBetween() {
        val trades=JSONArray()
            .put(row("2026-10-20T06:00:00Z", "LOSS"))
            .put(row("2026-10-20T07:00:00Z", "WIN"))
            .put(row("2026-10-20T08:00:00Z", "LOSS"))
            .put(row("2026-10-20T09:00:00Z", "LOSS"))
        val before=Instant.parse("2026-10-20T09:01:00Z").toEpochMilli()
        val blocked=evaluateFuturesLossStreak(trades,before)
        assertFalse(blocked.allowNewEntries)
        assertEquals(3,blocked.dailyLossTrades)
        assertEquals(2,blocked.consecutiveLosses)
        assertEquals("FUTURES_DAILY_LOSS_TRADE_LIMIT",blocked.reason)
        val next=Instant.parse("2026-10-21T00:01:00Z").toEpochMilli()
        val reset=evaluateFuturesLossStreak(trades,next)
        assertEquals(0,reset.dailyLossTrades)
        assertTrue(reset.allowNewEntries)
    }

    @Test fun contradictoryLossOutcomeAndPnlAreNeverCountedAsVerifiedLosses() {
        val rows=JSONArray().put(row("2026-10-20T09:00:00Z","LOSS").put("pnl", 1.0))
        val decision=evaluateFuturesLossStreak(rows,Instant.parse("2026-10-20T09:20:00Z").toEpochMilli())
        assertFalse(decision.available)
        assertFalse(decision.allowNewEntries)
        assertEquals("FUTURES_LOSS_STREAK_PNL_UNKNOWN",decision.reason)
    }

    @Test fun unknownCurrentDayFuturesPnLStillFailsClosed() {
        val row=row("2026-10-20T06:00:00Z").put("pnl_complete",false)
        val dec=evaluateFuturesLossStreak(JSONArray().put(row),
            Instant.parse("2026-10-20T06:20:00Z").toEpochMilli())
        assertFalse(dec.allowNewEntries)
        assertEquals("FUTURES_LOSS_STREAK_PNL_UNKNOWN",dec.reason)
    }

    @Test fun oldSpotOrOldFuturesTradesNeverCarryHardPauseForward() {
        val old=JSONArray().put(row("2026-10-19T21:00:00Z"))
            .put(row("2026-10-19T21:10:00Z"))
            .put(row("2026-10-19T21:20:00Z"))
            .put(row("2026-10-20T04:00:00Z",id="spot:manual").put("trade_audit_source","MANUAL"))
        val decision=evaluateFuturesLossStreak(old,
            Instant.parse("2026-10-20T06:38:00Z").toEpochMilli())
        assertEquals(0,decision.consecutiveLosses)
        assertTrue(decision.allowNewEntries)
    }
}
