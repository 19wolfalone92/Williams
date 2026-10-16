package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TC2 ch.9, ch.11-12: standalone book guards on the Android Futures path.
 * These deliberately do not place real orders or claim Testnet readiness.
 */
class WilliamsTc2RulesTest {
    @Test
    fun bullishWm1RequiresEntireBarOutsideMouthAndRedAo() {
        assertTrue(tc2BullishWiseManBarQualified(
            high = 98.8, low = 96.0, close = 98.0, priorLow = 99.0,
            jaw = 100.0, teeth = 99.8, lips = 99.7,
            aoNow = -0.5, aoPrevious = -0.3
        ))
        assertFalse(tc2BullishWiseManBarQualified(
            high = 103.0, low = 96.0, close = 102.0, priorLow = 99.0,
            jaw = 100.0, teeth = 99.8, lips = 99.7,
            aoNow = -0.5, aoPrevious = -0.3
        ))
        assertFalse(tc2BullishWiseManBarQualified(
            high = 98.8, low = 96.0, close = 98.0, priorLow = 99.0,
            jaw = 100.0, teeth = 99.8, lips = 99.7,
            aoNow = -0.1, aoPrevious = -0.3
        ))
    }

    @Test
    fun opposingUntriggeredSetupNeverClosesLongOrShort() {
        assertFalse(tc2OppositeBreakoutTriggered(true, 100.0, 95.0))
        assertFalse(tc2OppositeBreakoutTriggered(false, 100.0, 105.0))
        assertTrue(tc2OppositeBreakoutTriggered(true, 95.0, 95.0))
        assertTrue(tc2OppositeBreakoutTriggered(false, 105.0, 105.0))
        assertFalse(tc2OppositeBreakoutTriggered(true, Double.NaN, 95.0))
        assertFalse(tc2OppositeBreakoutTriggered(false, -1.0, 105.0))
    }
    @Test
    fun nineBarFractalWithSeparatedEqualHighsWaitsForConfirmation() {
        val highs = listOf(8.0, 9.0, 10.0, 9.0, 10.0, 9.0, 10.0, 8.0, 9.0)
        assertFalse(tc2ConfirmedFractal(highs.take(8), 6, true))
        assertTrue(tc2ConfirmedFractal(highs, 6, true))
        assertTrue(tc2FractalGroupStart(highs, 6, true) == 2)
    }

    @Test
    fun nineBarFractalMirrorsToDownside() {
        val lows = listOf(12.0, 11.0, 10.0, 11.0, 10.0, 11.0, 10.0, 12.0, 11.0)
        assertTrue(tc2ConfirmedFractal(lows, 6, false))
        assertTrue(tc2FractalGroupStart(lows, 6, false) == 2)
    }

    @Test
    fun waveEvidenceNeverIncludesAnOpenOrFutureBar() {
        val hour = 3_600_000L
        val now = 6L * hour + 30L * 60_000L
        val opens = listOf(0L, hour, 2 * hour, 3 * hour, 4 * hour,
            5 * hour, 6 * hour, 7 * hour, 4 * hour)
        val closed = tc2ClosedWaveBarTimes(opens, hour, now)
        assertTrue(closed == listOf(hour, 2 * hour, 3 * hour, 4 * hour, 5 * hour))
        assertFalse(closed.contains(6 * hour))
        assertFalse(closed.contains(7 * hour))
    }

    @Test
    fun waveEvidenceRejectsInvalidTimebases() {
        assertTrue(tc2ClosedWaveBarTimes(listOf(1L, 2L), 0L, 100L).isEmpty())
        assertTrue(tc2ClosedWaveBarTimes(listOf(1L, 2L), 5L, 0L).isEmpty())
    }

    @Test
    fun pendingContextVetoIsMirroredAndReversalExceptionIsExplicit() {
        // The same policy used at entry must be re-evaluated before keeping
        // a previously queued conditional order active.
        assertTrue(directionalSignalContextRank("FRACTAL", true, false, true, false) == 2)
        assertTrue(directionalSignalContextRank("FRACTAL", false, true, true, false) == 0)
        assertTrue(directionalSignalContextRank("SHORT_FRACTAL", false, true, false, true) == 2)
        assertTrue(directionalSignalContextRank("SHORT_FRACTAL", true, false, false, true) == 0)
        assertTrue(directionalSignalContextRank("REVERSAL", false, false, false, false) == 1)
        assertTrue(directionalSignalContextRank("SHORT_REVERSAL", false, false, false, false) == 1)
        assertTrue(directionalSignalContextRank("NOT_A_SIGNAL", false, false, false, false) == 0)
    }

    @Test
    fun geometricalWavePrincipleIsScaleInvariantButTimeSensitive() {
        // TC1 fig.7-14: same two-unit price move, two vs ten bars => different
        // geometry. The model must not try to match the bitmap of the drawing.
        val swift = williamsSwingGeometry(100.0, 102.0, 2, 1.0)!!
        val slow = williamsSwingGeometry(100.0, 102.0, 10, 1.0)!!
        val scaled = williamsSwingGeometry(1000.0, 1020.0, 2, 10.0)!!
        assertEquals(swift.distanceAtr, scaled.distanceAtr, 1e-9)
        assertEquals(swift.velocityAtrPerBar, scaled.velocityAtrPerBar, 1e-9)
        assertTrue(swift.velocityAtrPerBar > slow.velocityAtrPerBar)
        assertTrue(slow.triangularAreaAtrBars > swift.triangularAreaAtrBars)
        assertEquals(swift.distanceAtr, slow.distanceAtr, 1e-9)
        assertTrue(williamsSwingGeometry(100.0, 102.0, 0, 1.0) == null)
        assertTrue(williamsSwingGeometry(100.0, 102.0, 2, 0.0) == null)
    }

    @Test
    fun fractalAndReversalPrinciplesGeneralizeWithoutPixelMatching() {
        val original = listOf(8.0, 9.0, 10.0, 9.0, 10.0, 9.0, 10.0, 8.0, 9.0)
        for (scale in listOf(0.001, 1.0, 100.0)) {
            val shifted = original.map { it * scale + 100.0 }
            assertTrue(tc2ConfirmedFractal(shifted, 6, true))
            assertEquals(2, tc2FractalGroupStart(shifted, 6, true))
        }
        for (scale in listOf(0.1, 1.0, 100.0)) {
            val qualified = tc2BullishWiseManBarQualified(
                high = 98.8 * scale, low = 96.0 * scale,
                close = 98.0 * scale, priorLow = 99.0 * scale,
                jaw = 100.0 * scale, teeth = 99.8 * scale,
                lips = 99.7 * scale, aoNow = -0.5 * scale,
                aoPrevious = -0.3 * scale
            )
            assertTrue(qualified)
        }
    }

    @Test
    fun longShortAddOnRequiresAnActualSameDirectionWiseManFamily() {
        assertTrue(isCampaignAddOnSignalForDirection("LONG_SUPER_AO", "LONG_REVERSAL"))
        assertTrue(isCampaignAddOnSignalForDirection("LONG_FRACTAL", "LONG_SUPER_AO"))
        assertTrue(isCampaignAddOnSignalForDirection("SHORT_SUPER_AO", "SHORT_REVERSAL"))
        assertTrue(isCampaignAddOnSignalForDirection("SHORT_FRACTAL", "SHORT_SUPER_AO"))
        assertFalse(isCampaignAddOnSignalForDirection("SHORT_FRACTAL", "LONG_REVERSAL"))
        assertFalse(isCampaignAddOnSignalForDirection("LONG_SUPER_AO", "SHORT_REVERSAL"))
        assertFalse(isCampaignAddOnSignalForDirection("LONG_REVERSAL", "LONG_FRACTAL"))
        assertFalse(isCampaignAddOnSignalForDirection("SHORT_UNKNOWN", "SHORT_REVERSAL"))
        assertFalse(isCampaignAddOnSignalForDirection("LONG_FRACTAL", ""))
        assertTrue(campaignSignalDirection("LONG_FRACTAL") == "LONG")
        assertTrue(campaignSignalDirection("SHORT_FRACTAL") == "SHORT")
        assertTrue(campaignSignalDirection("SHORT_NOT_A_REAL_SIGNAL") == null)
        assertTrue(campaignSignalDirection("LONG_NOT_A_REAL_SIGNAL") == null)
    }

    @Test
    fun reversePyramidUsesBookContractRatioAndNeverCreatesExtraContracts() {
        assertTrue(williamsReversePyramidUnits(15L) == listOf(1L, 5L, 4L, 3L, 2L))
        assertTrue(williamsReversePyramidUnits(150L) == listOf(10L, 50L, 40L, 30L, 20L))
        for (availableUnits in listOf(16L, 20L, 71L, 15001L)) {
            val allocation = williamsReversePyramidUnits(availableUnits)!!
            assertEquals(5, allocation.size)
            assertEquals(availableUnits, allocation.sum())
            assertTrue(allocation.all { it > 0L })
            assertTrue(allocation[1] > allocation[2])
            assertTrue(allocation[2] > allocation[3])
            assertTrue(allocation[3] > allocation[4])
        }
        assertTrue(williamsReversePyramidUnits(14L) == null)
        assertTrue(williamsReversePyramidUnits(0L) == null)
        assertTrue(williamsReversePyramidUnits(Long.MAX_VALUE) == null)
    }

}
