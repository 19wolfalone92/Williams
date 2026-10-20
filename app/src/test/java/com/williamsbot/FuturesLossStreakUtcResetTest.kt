package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FuturesLossStreakUtcResetTest {
    private fun utc(s:String)=Instant.parse(s).toEpochMilli()
    private fun trade(id:Int,time:Long,outcome:String="LOSS",complete:Boolean=true)=JSONObject()
        .put("id","FUTURES:$id").put("trade_audit_source","BINANCE_USDM_USER_TRADES")
        .put("status","CLOSED").put("closed_at",time)
        .put("outcome",outcome).put("pnl",if(outcome=="LOSS") -1.0 else 1.0)
        .put("pnl_complete",complete)
    @Test fun thirdLossPausesCurrentUtcDayButAutomaticallyReevaluatesOnNextUtcDay() {
        val rows=JSONArray()
            .put(trade(1,utc("2026-10-19T10:00:00Z")))
            .put(trade(2,utc("2026-10-19T11:00:00Z")))
            .put(trade(3,utc("2026-10-19T12:00:00Z")))
        val first=evaluateFuturesLossStreak(rows,utc("2026-10-19T17:00:00Z"))
        assertTrue(first.available)
        assertFalse(first.allowNewEntries)
        assertEquals("FUTURES_CONSECUTIVE_LOSS_HARD_PAUSE",first.reason)
        assertNull(first.cooldownUntilMs) // Hard-pause is UTC-day scoped, not 30m cooldown.
        assertEquals(utc("2026-10-20T00:00:00Z"),futuresLossStreakNextUtcResetMs(utc("2026-10-19T17:00:00Z")))
        val next=evaluateFuturesLossStreak(rows,utc("2026-10-20T00:00:00Z"))
        assertTrue(next.available)
        assertTrue(next.allowNewEntries)
        assertEquals(0,next.consecutiveLosses)
        assertNull(next.reason)
        assertEquals(utc("2026-10-21T00:00:00Z"),futuresLossStreakNextUtcResetMs(utc("2026-10-20T00:00:00Z")))
    }
    @Test fun secondLossHasThirtyMinuteCooldownWithoutBypassingThirdLossHardPause() {
        val rows=JSONArray()
            .put(trade(1,utc("2026-10-20T07:00:00Z")))
            .put(trade(2,utc("2026-10-20T07:10:00Z")))
        val at=utc("2026-10-20T07:20:00Z")
        val blocked=evaluateFuturesLossStreak(rows,at)
        assertFalse(blocked.allowNewEntries)
        assertEquals("FUTURES_CONSECUTIVE_LOSS_COOLDOWN",blocked.reason)
        assertEquals(utc("2026-10-20T07:40:00Z"),blocked.cooldownUntilMs)
        assertTrue(evaluateFuturesLossStreak(rows,utc("2026-10-20T07:40:00Z")).allowNewEntries)
    }
    @Test fun unknownNewFuturesPnlStillBlocksTradingRatherThanBeingMistakenForAReset() {
        val rows=JSONArray().put(trade(9,utc("2026-10-20T08:00:00Z"),complete=false))
        val decision=evaluateFuturesLossStreak(rows,utc("2026-10-20T08:15:00Z"))
        assertFalse(decision.available)
        assertFalse(decision.allowNewEntries)
        assertEquals("FUTURES_LOSS_STREAK_PNL_UNKNOWN",decision.reason)
    }
}
