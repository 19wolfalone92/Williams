package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuturesEntryRiskAssessmentTest {
    @Test
    fun longRiskIncludesStructuralStopFeesAndSlippage() {
        val check = assessFuturesEntryRisk(
            direction = "LONG",
            quantity = "5",
            entryPrice = "100",
            protectiveStop = "99.5",
            equityUsdt = 5_000.0,
            availableUsdt = 2_000.0,
            riskBudgetPct = 0.002
        )

        assertNull(check.blocker)
        assertEquals(500.0, check.notionalUsdt, 1e-9)
        assertEquals(2.5, check.grossStopLossUsdt, 1e-9)
        assertEquals(1.75, check.estimatedFeesAndSlippageUsdt, 1e-9)
        assertEquals(4.25, check.estimatedAllInLossUsdt, 1e-9)
        assertEquals(0.00085, check.estimatedAllInRiskPct, 1e-12)
        assertEquals(250.0, check.initialMarginUsdt, 1e-9)
    }

    @Test
    fun shortRiskUsesStopAboveEntryAndStillPassesWhenWithinBudget() {
        val check = assessFuturesEntryRisk(
            direction = "SHORT",
            quantity = "5",
            entryPrice = "100",
            protectiveStop = "100.5",
            equityUsdt = 5_000.0,
            availableUsdt = 2_000.0,
            riskBudgetPct = 0.002
        )
        assertNull(check.blocker)
        assertEquals(4.25, check.estimatedAllInLossUsdt, 1e-9)
    }

    @Test
    fun coordinatorRejectsSizeWhoseAllInRiskExceedsTheReservedBudget() {
        val check = assessFuturesEntryRisk(
            direction = "LONG",
            quantity = "20",
            entryPrice = "100",
            protectiveStop = "99.5",
            equityUsdt = 5_000.0,
            availableUsdt = 2_000.0,
            riskBudgetPct = 0.002
        )
        assertNotNull(check.blocker)
        assertTrue(check.blocker!!.contains("FUTURES_RISK_BUDGET_EXCEEDED"))
        assertTrue(check.estimatedAllInRiskPct > 0.002)
    }

    @Test
    fun wrongSideStopIsRejectedForBothDirections() {
        val long = assessFuturesEntryRisk(
            "LONG", "1", "100", "101", 5_000.0, 2_000.0, 0.002
        )
        val short = assessFuturesEntryRisk(
            "SHORT", "1", "100", "99", 5_000.0, 2_000.0, 0.002
        )
        assertTrue(long.blocker!!.contains("wrong side"))
        assertTrue(short.blocker!!.contains("wrong side"))
    }

    @Test
    fun insufficientAvailableMarginIsRejectedEvenWhenStopRiskIsSmall() {
        val check = assessFuturesEntryRisk(
            direction = "LONG",
            quantity = "20",
            entryPrice = "100",
            protectiveStop = "99.99",
            equityUsdt = 100_000.0,
            availableUsdt = 1_000.0,
            riskBudgetPct = 0.002
        )
        assertNotNull(check.blocker)
        assertTrue(check.blocker!!.contains("FUTURES_MARGIN_BUDGET_EXCEEDED"))
    }

    @Test
    fun actualExchangePositionRiskIsRecomputedForLongAndShort() {
        val long = assessActualFuturesPositionRisk(
            direction = "LONG",
            quantity = 5.0,
            actualEntryPrice = 100.0,
            originalStructuralStop = 99.5,
            equityUsdt = 5_000.0,
            reservedRiskPct = 0.002
        )
        val short = assessActualFuturesPositionRisk(
            direction = "SHORT",
            quantity = 5.0,
            actualEntryPrice = 100.0,
            originalStructuralStop = 100.5,
            equityUsdt = 5_000.0,
            reservedRiskPct = 0.002
        )
        assertNull(long.blocker)
        assertNull(short.blocker)
        assertEquals(4.25, long.estimatedAllInLossUsdt, 1e-9)
        assertEquals(long.estimatedAllInLossUsdt, short.estimatedAllInLossUsdt, 1e-9)
        assertEquals(10.0, long.reservedRiskUsdt, 1e-9)
    }

    @Test
    fun actualRiskBreachAndMissingOriginalGeometryFailClosedAfterFill() {
        val gapOrQuantityBreach = assessActualFuturesPositionRisk(
            direction = "LONG",
            quantity = 30.0,
            actualEntryPrice = 100.0,
            originalStructuralStop = 99.5,
            equityUsdt = 5_000.0,
            reservedRiskPct = 0.002
        )
        val invalidStop = assessActualFuturesPositionRisk(
            direction = "SHORT",
            quantity = 1.0,
            actualEntryPrice = 100.0,
            originalStructuralStop = 99.0,
            equityUsdt = 5_000.0,
            reservedRiskPct = 0.002
        )
        val unknownEquity = assessActualFuturesPositionRisk(
            "LONG", 1.0, 100.0, 99.0, 0.0, 0.002
        )
        assertTrue(gapOrQuantityBreach.blocker!!.contains("ACTUAL_FUTURES_RISK_EXCEEDS_RESERVATION"))
        assertTrue(invalidStop.blocker!!.contains("SHORT original structural stop"))
        assertTrue(unknownEquity.blocker!!.contains("equity is unavailable"))
    }

    @Test
    fun invalidAccountDataAndOverlargeRiskReservationFailClosed() {
        val noEquity = assessFuturesEntryRisk(
            "LONG", "1", "100", "99", 0.0, 100.0, 0.002
        )
        val overBudget = assessFuturesEntryRisk(
            "LONG", "1", "100", "99", 5_000.0, 2_000.0, 0.003
        )
        assertNotNull(noEquity.blocker)
        assertNotNull(overBudget.blocker)
    }
}
