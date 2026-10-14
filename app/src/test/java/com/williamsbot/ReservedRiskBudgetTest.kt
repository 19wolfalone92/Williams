package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Test

class ReservedRiskBudgetTest {
    @Test
    fun validStoredRiskReservationIsPreservedWithinPortfolioCap() {
        assertEquals(0.0025, conservativeRiskReservation(0.0025, 0.006), 0.0)
        assertEquals(0.006, conservativeRiskReservation(0.02, 0.006), 0.0)
    }

    @Test
    fun missingOrInvalidRiskReservationConsumesTheRemainingCampaignBudget() {
        val cap = 0.006
        assertEquals(cap, conservativeRiskReservation(0.0, cap), 0.0)
        assertEquals(cap, conservativeRiskReservation(-0.002, cap), 0.0)
        assertEquals(cap, conservativeRiskReservation(Double.NaN, cap), 0.0)
        assertEquals(cap, conservativeRiskReservation(Double.POSITIVE_INFINITY, cap), 0.0)
    }

    @Test
    fun invalidPortfolioCapFailsClosed() {
        assertEquals(1.0, conservativeRiskReservation(0.0025, 0.0), 0.0)
        assertEquals(1.0, conservativeRiskReservation(0.0025, Double.NaN), 0.0)
    }
}
