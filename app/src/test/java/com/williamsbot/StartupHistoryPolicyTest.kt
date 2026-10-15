package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupHistoryPolicyTest {
    @Test
    fun h1_remains_the_full_decision_history_requirement() {
        val policy = startupHistoryPolicy("1h")
        assertEquals(180, policy.requestedCount)
        assertEquals(150, policy.minimumCount)
    }

    @Test
    fun context_and_monitoring_frames_do_not_require_150_bars() {
        assertEquals(StartupHistoryPolicy(90, 45), startupHistoryPolicy("1d"))
        assertEquals(StartupHistoryPolicy(120, 45), startupHistoryPolicy("4h"))
        assertEquals(StartupHistoryPolicy(90, 45), startupHistoryPolicy("15m"))
    }

    @Test
    fun diagnostic_distinguishes_ready_h1_working_set_from_full_backfill() {
        val status = historyReadinessStatus("1h", 179, false)
        assertEquals(150, status.minimumWorkingSetCount)
        org.junit.Assert.assertEquals(true, status.workingSetReady)
        org.junit.Assert.assertEquals(true, status.decisionFrame)
        org.junit.Assert.assertEquals(true, status.decisionReady)
        assertEquals("WORKING_SET_READY", status.scope)
    }

    @Test
    fun strategy_context_frames_use_their_own_minimums() {
        for ((frame, count) in listOf("4h" to 119L, "1d" to 89L, "15m" to 90L)) {
            val status = historyReadinessStatus(frame, count, false)
            org.junit.Assert.assertEquals(true, status.workingSetReady)
            org.junit.Assert.assertEquals(true, status.decisionReady)
            assertEquals("WORKING_SET_READY", status.scope)
        }
    }

    @Test
    fun empty_or_short_history_is_reported_as_warming_up() {
        val minute = historyReadinessStatus("1m", 0L, false)
        org.junit.Assert.assertEquals(false, minute.workingSetReady)
        org.junit.Assert.assertEquals(false, minute.decisionReady)
        assertEquals("WARMING_UP", minute.scope)

        val shortH1 = historyReadinessStatus("1h", 149L, false)
        org.junit.Assert.assertEquals(false, shortH1.workingSetReady)
        org.junit.Assert.assertEquals(false, shortH1.decisionReady)
        assertEquals("WARMING_UP", shortH1.scope)
    }

    @Test
    fun full_backfill_is_reported_separately_from_working_set_readiness() {
        val status = historyReadinessStatus("1h", 180L, true)
        assertEquals(true, status.workingSetReady)
        assertEquals("FULL_HISTORY", status.scope)
    }

}
