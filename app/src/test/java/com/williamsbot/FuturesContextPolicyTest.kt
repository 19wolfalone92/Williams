package com.williamsbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FuturesContextPolicyTest {
    private val long = FuturesContextState(true, false, true, 1.0)
    private val short = FuturesContextState(false, true, true, -1.0)
    private val neutral = FuturesContextState(false, false, false, 0.0)

    @Test
    fun longNeedsH1AndH4AndOnlyVetoesOnActiveBearishD1() {
        assertTrue(FuturesContextPolicy.allows("LONG", long, long, neutral))
        assertFalse(FuturesContextPolicy.allows("LONG", long, short, neutral))
        assertFalse(FuturesContextPolicy.allows("LONG", long, long, short))
        assertFalse(FuturesContextPolicy.allows("LONG", neutral, long, neutral))
    }

    @Test
    fun fractalTriggerMustRemainOutsideCurrentTeethAndMalformedLevelsFailClosed() {
        assertTrue(FuturesContextPolicy.fractalTriggerOutsideTeeth("LONG", 101.0, 100.0))
        assertFalse(FuturesContextPolicy.fractalTriggerOutsideTeeth("LONG", 100.0, 100.0))
        assertFalse(FuturesContextPolicy.fractalTriggerOutsideTeeth("LONG", 99.0, 100.0))
        assertTrue(FuturesContextPolicy.fractalTriggerOutsideTeeth("SHORT", 99.0, 100.0))
        assertFalse(FuturesContextPolicy.fractalTriggerOutsideTeeth("SHORT", 100.0, 100.0))
        assertFalse(FuturesContextPolicy.fractalTriggerOutsideTeeth("SHORT", 101.0, 100.0))
        assertFalse(FuturesContextPolicy.fractalTriggerOutsideTeeth("LONG", Double.NaN, 100.0))
        assertFalse(FuturesContextPolicy.fractalTriggerOutsideTeeth("LONG", 101.0, Double.NaN))
        assertFalse(FuturesContextPolicy.fractalTriggerOutsideTeeth("FLAT", 101.0, 100.0))
    }

    @Test
    fun earlyWm1MayLeadH1AndH4ButRequiresAngulationAndNoOppositeD1Veto() {
        assertTrue(
            FuturesContextPolicy.allowsEarlyReversal(
                "LONG", neutral, short, neutral, angulationScore = 2.5
            )
        )
        assertTrue(
            FuturesContextPolicy.allowsEarlyReversal(
                "SHORT", neutral, long, neutral, angulationScore = 2.5
            )
        )
        assertFalse(
            FuturesContextPolicy.allowsEarlyReversal(
                "LONG", neutral, neutral, short, angulationScore = 2.5
            )
        )
        assertFalse(
            FuturesContextPolicy.allowsEarlyReversal(
                "SHORT", neutral, neutral, long, angulationScore = 2.5
            )
        )
        assertFalse(
            FuturesContextPolicy.allowsEarlyReversal(
                "LONG", neutral, neutral, neutral, angulationScore = 0.0
            )
        )
        assertFalse(
            FuturesContextPolicy.allowsEarlyReversal(
                "LONG", neutral, neutral, neutral, angulationScore = Double.NaN
            )
        )
        assertFalse(
            FuturesContextPolicy.allowsEarlyReversal(
                "FLAT", neutral, neutral, neutral, angulationScore = 2.5
            )
        )
    }

    @Test
    fun onlyReversalSignalsUseTheEarlyWm1AdmissionMode() {
        assertTrue(
            FuturesContextPolicy.allowsSignal(
                "LONG", "REVERSAL", 2.5, neutral, short, neutral
            )
        )
        assertFalse(
            FuturesContextPolicy.allowsSignal(
                "LONG", "FRACTAL", 2.5, neutral, short, neutral
            )
        )
    }

    @Test
    fun shortIsMirroredAndNeutralD1DoesNotCreateAnEntry() {
        assertTrue(FuturesContextPolicy.allows("SHORT", short, short, neutral))
        assertFalse(FuturesContextPolicy.allows("SHORT", short, long, neutral))
        assertFalse(FuturesContextPolicy.allows("SHORT", short, short, long))
        assertFalse(FuturesContextPolicy.allows("BUY", long, long, neutral))
    }
}
