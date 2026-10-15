package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WilliamsIndicatorParityTest {
    @Test
    fun smmaUsesTheFirstPeriodSmaAsItsSeed() {
        val values = (1..15).map { it.toDouble() }
        val result = smmaSeededValues(values, 13)
        assertEquals(12, result.take(12).count { it == null })
        assertEquals(7.0, result[12]!!, 1e-12)
        assertEquals(98.0 / 13.0, result[13]!!, 1e-12)
    }

    @Test
    fun displayedLineAtCurrentCandleUsesOnlyPastSmmaValue() {
        val raw = smmaSeededValues((1..40).map { it.toDouble() }, 13)
        val displayed = shiftAlligatorForDisplay(raw, 8)
        assertNull(displayed[7])
        assertEquals(raw[12]!!, displayed[20]!!, 0.0)
        assertTrue(raw[20]!! != displayed[20]!!)
    }

    @Test
    fun awesomeOscillatorUsesExactlyThirtyFourAndFiveMedianPrices() {
        val medians = (1..40).map { it.toDouble() }
        assertTrue(awesomeOscillatorAt(medians, 32).isNaN())
        assertEquals(14.5, awesomeOscillatorAt(medians, 33), 1e-12)
        assertEquals(14.5, awesomeOscillatorAt(medians, 34), 1e-12)
    }

    @Test
    fun invalidPeriodIsRejectedAndNonFiniteWindowsFailClosed() {
        try {
            smmaSeededValues(listOf(1.0, 2.0), 0)
            throw AssertionError("period zero must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        val values = (1..40).map { it.toDouble() }.toMutableList().apply { this[33] = Double.NaN }
        assertTrue(awesomeOscillatorAt(values, 33).isNaN())
    }

    @Test
    fun longAndShortWilliamsSignalsResolveToTheirOwnDirectionAndContext() {
        assertEquals("LONG", campaignSignalDirection("REVERSAL"))
        assertEquals("LONG", campaignSignalDirection("SUPER_AO"))
        assertEquals("LONG", campaignSignalDirection("FRACTAL"))
        assertEquals("SHORT", campaignSignalDirection("SHORT_REVERSAL"))
        assertEquals("SHORT", campaignSignalDirection("SHORT_SUPER_AO"))
        assertEquals("SHORT", campaignSignalDirection("SHORT_FRACTAL"))
        assertNull(campaignSignalDirection("UNCLASSIFIED_SIGNAL"))

        // D1 opposition vetoes both directions symmetrically; aligned H4 ranks first.
        assertEquals(0, directionalSignalContextRank("REVERSAL", false, true, true, false))
        assertEquals(0, directionalSignalContextRank("SHORT_REVERSAL", true, false, false, true))
        assertEquals(2, directionalSignalContextRank("FRACTAL", true, false, true, false))
        assertEquals(2, directionalSignalContextRank("SHORT_FRACTAL", false, true, false, true))
        assertEquals(1, directionalSignalContextRank("SHORT_REVERSAL", false, false, false, false))
    }

    @Test
    fun campaignAddOnsMustMatchTheOpenPositionDirection() {
        assertTrue(isCampaignAddOnSignalForDirection("SUPER_AO", "REVERSAL"))
        assertTrue(isCampaignAddOnSignalForDirection("FRACTAL", "FRACTAL"))
        assertTrue(isCampaignAddOnSignalForDirection("SHORT_SUPER_AO", "SHORT_REVERSAL"))
        assertTrue(isCampaignAddOnSignalForDirection("SHORT_FRACTAL", "SHORT_FRACTAL"))
        assertTrue(!isCampaignAddOnSignalForDirection("SHORT_SUPER_AO", "REVERSAL"))
        assertTrue(!isCampaignAddOnSignalForDirection("SUPER_AO", "SHORT_REVERSAL"))
        assertTrue(!isCampaignAddOnSignalForDirection("SHORT_REVERSAL", "SHORT_REVERSAL"))
        assertTrue(!isCampaignAddOnSignalForDirection("SHORT_FRACTAL", ""))
    }
}
