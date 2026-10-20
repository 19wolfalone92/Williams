package com.williamsbot

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

class FuturesDailyEntryHaltTest {
    private fun at(s:String)=Instant.parse(s).toEpochMilli()
    private val haltedDay=at("2026-10-19T00:00:00Z").toString()

    @Test fun dailyHaltExpiresExactlyAtNextUtcMidnight() {
        val before=evaluateFuturesDailyEntryHalt(haltedDay,at("2026-10-19T23:59:59.999Z"),true)
        assertFalse(before.allowNewEntries)
        assertEquals(at("2026-10-20T00:00:00Z"),before.untilMs)
        val after=evaluateFuturesDailyEntryHalt(haltedDay,at("2026-10-20T00:00:00Z"),true)
        assertTrue(after.allowNewEntries)
        assertNull(after.reason)
        assertNull(after.untilMs)
    }

    @Test fun minskMidnightIsStillPreviousUtcRiskDay() {
        assertFalse(evaluateFuturesDailyEntryHalt(haltedDay,at("2026-10-20T00:00:00+03:00"),true).allowNewEntries)
        assertTrue(evaluateFuturesDailyEntryHalt(haltedDay,at("2026-10-20T03:00:00+03:00"),true).allowNewEntries)
    }

    @Test fun persistedHaltSurvivesRestartAndCannotBeExpiredByHandsetTimezone() {
        val previous=TimeZone.getDefault()
        try {
            for(zone in listOf("Europe/Minsk","UTC","America/New_York")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                val reloaded=String(haltedDay.toCharArray())
                assertFalse(evaluateFuturesDailyEntryHalt(reloaded,at("2026-10-19T23:00:00Z"),true).allowNewEntries)
                assertTrue(evaluateFuturesDailyEntryHalt(reloaded,at("2026-10-20T00:00:00Z"),true).allowNewEntries)
            }
        } finally { TimeZone.setDefault(previous) }
    }

    @Test fun clockFailureAndRollbackCannotUnlockTheHalt() {
        assertFalse(evaluateFuturesDailyEntryHalt(haltedDay,at("2026-10-21T00:00:00Z"),false).allowNewEntries)
        assertFalse(evaluateFuturesDailyEntryHalt(haltedDay,at("2026-10-18T00:00:00Z"),true).allowNewEntries)
        assertFalse(evaluateFuturesDailyEntryHalt(haltedDay,0,true).allowNewEntries)
    }

    @Test fun malformedDurableHaltDoesNotAuthorizeAnEntry() {
        for(value in listOf("", "null", "oops", "0", "-1", "1720000000001", Long.MAX_VALUE.toString())) {
            val result=evaluateFuturesDailyEntryHalt(value,at("2026-10-20T00:00:00Z"),true)
            assertFalse(value,result.available)
            assertFalse(value,result.allowNewEntries)
        }
    }

    @Test fun newDayCanAcquireItsOwnHaltAndTheOldRecordDoesNotNeedDeletion() {
        assertTrue(evaluateFuturesDailyEntryHalt(haltedDay,at("2026-10-22T00:00:00Z"),true).allowNewEntries)
        val today=at("2026-10-20T00:00:00Z").toString()
        assertFalse(evaluateFuturesDailyEntryHalt(today,at("2026-10-20T12:00:00Z"),true).allowNewEntries)
        assertTrue(evaluateFuturesDailyEntryHalt(null,at("2026-10-20T12:00:00Z"),true).allowNewEntries)
    }
}
