package com.williamsbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyRiskBaselineTest {
    @Test
    fun baselineCanBeInitializedForFlatIdleAccountWithNoTradingIncome() {
        assertTrue(canInitializeDailyRiskBaseline(
            hasOpenPosition = false,
            hasActiveExecution = false,
            hasTradingIncome = false
        ))
    }

    @Test
    fun baselineCannotResetAfterOpenPositionPendingExecutionOrTradingPnl() {
        assertFalse(canInitializeDailyRiskBaseline(true, false, false))
        assertFalse(canInitializeDailyRiskBaseline(false, true, false))
        assertFalse(canInitializeDailyRiskBaseline(false, false, true))
        assertFalse(canInitializeDailyRiskBaseline(true, true, true))
    }
}
