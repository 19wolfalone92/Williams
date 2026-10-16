package com.williamsbot

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FuturesActualPositionRiskReservationTest {
    @Test
    fun allowsSmallFillCostAdjustmentWhenBothCapsRemainSatisfied() {
        assertNull(
            actualRiskReservationAdjustmentBlocker(
                actualRiskPct = 0.00202,
                otherActiveRiskPcts = listOf(0.002, 0.001)
            )
        )
    }

    @Test
    fun blocksAdjustmentAbovePerEntryCap() {
        assertNotNull(
            actualRiskReservationAdjustmentBlocker(
                actualRiskPct = 0.00251,
                otherActiveRiskPcts = emptyList()
            )
        )
    }

    @Test
    fun blocksAdjustmentThatWouldExceedPortfolioCap() {
        assertNotNull(
            actualRiskReservationAdjustmentBlocker(
                actualRiskPct = 0.0022,
                otherActiveRiskPcts = listOf(0.002, 0.002)
            )
        )
    }

    @Test
    fun blocksAdjustmentWhenAnotherActiveReservationIsUnknown() {
        assertNotNull(
            actualRiskReservationAdjustmentBlocker(
                actualRiskPct = 0.00202,
                otherActiveRiskPcts = listOf(Double.NaN)
            )
        )
    }
}
