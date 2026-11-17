package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WilliamsTc2RiskBudgetTest {
    @Test
    fun canonicalInitialUnitReservesTheFullOneFiveFourThreeTwoBookCapacity() {
        // TC2 unit quantities total 15; the largest later tranche is 5.
        // With caps 0.20% initial, 0.60% campaign and 0.25% per-entry,
        // the engineering initial-unit reservation is 0.04%, not 0.20%.
        val initial = williamsTc2InitialUnitRisk(
            initialCap = 0.002,
            campaignCap = 0.006,
            entryCap = 0.0025
        )
        assertEquals(0.0004, initial, 1e-12)
        assertTrue(initial <= 0.002)
        assertTrue(initial * 15 <= 0.006 + 1e-12)
    }

    @Test
    fun invalidOrUnknownLimitsFailClosed() {
        assertEquals(0.0, williamsTc2InitialUnitRisk(Double.NaN, 0.006, 0.0025), 0.0)
        assertEquals(0.0, williamsTc2InitialUnitRisk(0.002, Double.POSITIVE_INFINITY, 0.0025), 0.0)
        assertEquals(0.0, williamsTc2InitialUnitRisk(0.002, 0.006, 0.0), 0.0)
    }
}
