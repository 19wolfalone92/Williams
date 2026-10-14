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
    fun unknown_frames_fail_conservatively_to_the_h1_default() {
        assertEquals(StartupHistoryPolicy(180, 150), startupHistoryPolicy("5m"))
    }
}
