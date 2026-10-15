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
    fun h1_is_the_only_decision_frame() {
        val h1 = historyReadinessStatus("1h", 179L, false)
        org.junit.Assert.assertEquals(true, h1.decisionFrame)
        org.junit.Assert.assertEquals(true, h1.decisionReady)
        org.junit.Assert.assertEquals(false, h1.contextFrame)
        org.junit.Assert.assertEquals(false, h1.executionMonitoringFrame)
    }

    @Test
    fun d1_and_h4_are_context_while_m15_is_execution_monitoring() {
        for ((frame, count) in listOf("4h" to 119L, "1d" to 89L)) {
            val status = historyReadinessStatus(frame, count, false)
            org.junit.Assert.assertEquals(true, status.workingSetReady)
            org.junit.Assert.assertEquals(false, status.decisionFrame)
            org.junit.Assert.assertEquals(false, status.decisionReady)
            org.junit.Assert.assertEquals(true, status.contextFrame)
            org.junit.Assert.assertEquals(true, status.contextReady)
            org.junit.Assert.assertEquals(false, status.executionMonitoringFrame)
            assertEquals("WORKING_SET_READY", status.scope)
        }

        val m15 = historyReadinessStatus("15m", 90L, false)
        org.junit.Assert.assertEquals(true, m15.workingSetReady)
        org.junit.Assert.assertEquals(false, m15.decisionFrame)
        org.junit.Assert.assertEquals(false, m15.decisionReady)
        org.junit.Assert.assertEquals(false, m15.contextFrame)
        org.junit.Assert.assertEquals(true, m15.executionMonitoringFrame)
        org.junit.Assert.assertEquals(true, m15.executionReady)
        assertEquals("WORKING_SET_READY", m15.scope)
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
