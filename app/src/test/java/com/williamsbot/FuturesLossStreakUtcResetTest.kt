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
            .put("pnl",when(outcome) {
                "LOSS" -> -1.25
                "BREAKEVEN" -> 0.0
                else -> 1.0
            })

    @Test fun threeConsecutiveLossesBlockEntriesUntilNextUtcDay() {
        val trades=JSONArray()
            .put(row("2026-10-19T20:00:00Z"))
            .put(row("2026-10-19T21:00:00Z"))
            .put(row("2026-10-19T22:00:00Z"))
        val before=Instant.parse("2026-10-19T23:59:59Z").toEpochMilli()
        val after=Instant.parse("2026-10-20T00:00:01Z").toEpochMilli()
        val blocked=evaluateFuturesLossStreak(trades,before)
        assertEquals("FUTURES_CONSECUTIVE_LOSS_HARD_PAUSE",blocked.reason)
        assertEquals(3,blocked.consecutiveLosses)
        assertFalse(blocked.allowNewEntries)
        val reset=evaluateFuturesLossStreak(trades,after)
        assertTrue(reset.available)
        assertTrue(reset.allowNewEntries)
        assertEquals(0,reset.consecutiveLosses)
        assertNull(reset.reason)
        assertEquals(Instant.parse("2026-10-20T00:00:00Z").toEpochMilli(),
            futuresUtcDayStartMs(after))
    }

    @Test fun twoLossesAreNotThreeConsecutiveAndDoNotBlock() {
        val rows=JSONArray().put(row("2026-10-20T06:00:00Z"))
            .put(row("2026-10-20T06:03:00Z"))
        val result=evaluateFuturesLossStreak(rows,
            Instant.parse("2026-10-20T06:10:00Z").toEpochMilli())
        assertTrue(result.available)
        assertTrue(result.allowNewEntries)
        assertEquals(2,result.consecutiveLosses)
        assertNull(result.reason)
    }

    @Test fun winBetweenThreeDailyLossesResetsConsecutiveCount() {
        val rows=JSONArray()
            .put(row("2026-10-20T06:00:00Z","LOSS"))
            .put(row("2026-10-20T07:00:00Z","WIN"))
            .put(row("2026-10-20T08:00:00Z","LOSS"))
            .put(row("2026-10-20T09:00:00Z","LOSS"))
        val result=evaluateFuturesLossStreak(rows,
            Instant.parse("2026-10-20T09:01:00Z").toEpochMilli())
        assertTrue(result.allowNewEntries)
        assertEquals(2,result.consecutiveLosses)
        assertNull(result.reason)
    }

    @Test fun breakEvenAlsoInterruptsTheLosingStreak() {
        val rows=JSONArray()
            .put(row("2026-10-20T06:00:00Z","LOSS"))
            .put(row("2026-10-20T07:00:00Z","BREAKEVEN"))
            .put(row("2026-10-20T08:00:00Z","LOSS"))
            .put(row("2026-10-20T09:00:00Z","LOSS"))
        val result=evaluateFuturesLossStreak(rows,
            Instant.parse("2026-10-20T09:01:00Z").toEpochMilli())
        assertTrue(result.allowNewEntries)
        assertEquals(2,result.consecutiveLosses)
    }

    @Test fun threeConsecutiveLossesStayBlockedEvenIfProtectedOldPositionLaterWins() {
        val rows=JSONArray()
            .put(row("2026-10-20T06:00:00Z","LOSS"))
            .put(row("2026-10-20T07:00:00Z","LOSS"))
            .put(row("2026-10-20T08:00:00Z","LOSS"))
            .put(row("2026-10-20T09:00:00Z","WIN"))
        val result=evaluateFuturesLossStreak(rows,
            Instant.parse("2026-10-20T09:01:00Z").toEpochMilli())
        assertFalse(result.allowNewEntries)
        assertEquals("FUTURES_CONSECUTIVE_LOSS_HARD_PAUSE",result.reason)
    }

    @Test fun unknownAndContradictoryPnlFailClosed() {
        val unknown=row("2026-10-20T06:00:00Z").put("pnl_complete",false)
        val time=Instant.parse("2026-10-20T06:20:00Z").toEpochMilli()
        assertEquals("FUTURES_LOSS_STREAK_PNL_UNKNOWN",
            evaluateFuturesLossStreak(JSONArray().put(unknown),time).reason)
        val wrong=row("2026-10-20T06:00:00Z","LOSS").put("pnl",1.0)
        assertEquals("FUTURES_LOSS_STREAK_PNL_UNKNOWN",
            evaluateFuturesLossStreak(JSONArray().put(wrong),time).reason)
    }

    @Test fun unrelatedSpotAndPreviousDayFuturesTradesDoNotAffectCurrentStreak() {
        val rows=JSONArray()
            .put(row("2026-10-19T21:00:00Z"))
            .put(row("2026-10-19T21:10:00Z"))
            .put(row("2026-10-19T21:20:00Z"))
            .put(row("2026-10-20T04:00:00Z",id="spot:manual")
                .put("trade_audit_source","MANUAL"))
        val result=evaluateFuturesLossStreak(rows,
            Instant.parse("2026-10-20T06:38:00Z").toEpochMilli())
        assertEquals(0,result.consecutiveLosses)
        assertTrue(result.allowNewEntries)
    }
}
