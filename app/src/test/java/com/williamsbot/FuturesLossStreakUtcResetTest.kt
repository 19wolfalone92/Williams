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

    @Test fun recentSameDayLossesStillBlockWithVerifiedUtcClock() {
        val trades=JSONArray()
            .put(row("2026-10-20T06:00:00Z"))
            .put(row("2026-10-20T06:03:00Z"))
        val now=Instant.parse("2026-10-20T06:10:00Z").toEpochMilli()
        val dec=evaluateFuturesLossStreak(trades,now)
        assertEquals("FUTURES_CONSECUTIVE_LOSS_COOLDOWN",dec.reason)
        assertFalse(dec.allowNewEntries)
        assertEquals(Instant.parse("2026-10-20T06:33:00Z").toEpochMilli(),
            dec.cooldownUntilMs)
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
