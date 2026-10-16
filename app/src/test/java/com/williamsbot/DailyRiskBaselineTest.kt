package com.williamsbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyRiskBaselineTest {
    @Test
    fun unverifiedOrInvalidUtcDailyRiskBaselineNeverOpensExecutionGate() {
        assertFalse(futuresDailyRiskBaselineEstablished(null, "INITIAL_FLAT_SNAPSHOT"))
        assertFalse(futuresDailyRiskBaselineEstablished("5000", null))
        assertFalse(futuresDailyRiskBaselineEstablished("5000", "UNKNOWN"))
        assertFalse(futuresDailyRiskBaselineEstablished("5000", " null "))
        assertFalse(futuresDailyRiskBaselineEstablished("NaN", "INITIAL_FLAT_SNAPSHOT"))
        assertFalse(futuresDailyRiskBaselineEstablished("Infinity", "INITIAL_FLAT_SNAPSHOT"))
        assertFalse(futuresDailyRiskBaselineEstablished("0", "INITIAL_FLAT_SNAPSHOT"))
        assertFalse(futuresDailyRiskBaselineEstablished("-10", "INITIAL_FLAT_SNAPSHOT"))
        assertTrue(futuresDailyRiskBaselineEstablished("4998.03288909", "INITIAL_FLAT_SNAPSHOT"))
        assertTrue(futuresDailyRiskBaselineEstablished("4500", "RECONSTRUCTED_FROM_FUTURES_INCOME"))
    }

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

    @Test
    fun baselineSourceRepairRequiresAnExistingValidPersistedBaseline() {
        assertTrue(canRepairDailyRiskBaselineSource("5000.00", null))
        assertTrue(canRepairDailyRiskBaselineSource("5000.00", "UNKNOWN"))
        assertTrue(canRepairDailyRiskBaselineSource("5000.00", "null"))
        assertFalse(canRepairDailyRiskBaselineSource(null, null))
        assertFalse(canRepairDailyRiskBaselineSource("0", "UNKNOWN"))
        assertFalse(canRepairDailyRiskBaselineSource("-1", "UNKNOWN"))
        assertFalse(canRepairDailyRiskBaselineSource("NaN", "UNKNOWN"))
        assertFalse(canRepairDailyRiskBaselineSource("5000", "EXCHANGE_ACCOUNT_SNAPSHOT"))
    }
}
