package com.williamsbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FuturesContextPolicyTest {
    private val long = FuturesContextState(true, false, true, 1.0)
    private val short = FuturesContextState(false, true, true, -1.0)
    private val neutral = FuturesContextState(false, false, false, 0.0)

    @Test
    fun h1OwnsDirectionH4IsContextOnlyAndD1IsTheMacroAirbag() {
        assertTrue(FuturesContextPolicy.allows("LONG", long, long, neutral))
        assertTrue(FuturesContextPolicy.allows("LONG", long, short, neutral))
        assertTrue(FuturesContextPolicy.allows("LONG", long, neutral, neutral))
        assertFalse(FuturesContextPolicy.allows("LONG", long, long, short))
        assertFalse(FuturesContextPolicy.allows("LONG", neutral, long, neutral))
        assertTrue(FuturesContextPolicy.allows("SHORT", short, long, neutral))
        assertFalse(FuturesContextPolicy.allows("SHORT", short, neutral, long))
    }

    @Test
    fun h1AlligatorRegimeDoesNotRequireAoZeroLineSignForEveryWiseManSignal() {
        val bullishH1WithNegativeAo = FuturesContextState(true, false, true, -0.5)
        val neutralH4 = FuturesContextState(false, false, false, 0.0)
        val neutralD1 = FuturesContextState(false, false, false, 0.0)

        // WM2/WM3 carry their own AO/fractal evidence. A generic AO>0 gate would
        // incorrectly erase a valid H1 setup whenever AO is below zero.
        assertTrue(
            FuturesContextPolicy.allows(
                "LONG", bullishH1WithNegativeAo, neutralH4, neutralD1
            )
        )
        assertTrue(
            FuturesContextPolicy.allowsEarlyReversal(
                "LONG", neutral, neutralH4, neutralD1, angulationScore = 2.0
            )
        )
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
    fun eachWiseManUsesItsOwnBookDefinedEvidenceGate() {
        // WM1 needs positive angulation evidence and can precede the turn.
        assertTrue(
            FuturesContextPolicy.allowsSignal(
                "LONG", "REVERSAL", 2.5, neutral, short, neutral
            )
        )

        // WM2's AO colour run and WM3's Teeth/trigger relation are validated
        // by their own detectors; they must not inherit a universal H1 trend
        // alignment gate merely because H1 has not yet fully turned.
        assertTrue(
            FuturesContextPolicy.allowsSignal(
                "LONG", "SUPER_AO", 0.0, neutral, short, neutral
            )
        )
        assertTrue(
            FuturesContextPolicy.allowsSignal(
                "SHORT", "FRACTAL", 0.0, neutral, long, neutral
            )
        )

        // The selected D1 macro-airbag remains an explicit system overlay.
        assertFalse(
            FuturesContextPolicy.allowsSignal(
                "LONG", "SUPER_AO", 0.0, neutral, neutral, short
            )
        )
        assertFalse(
            FuturesContextPolicy.allowsSignal(
                "SHORT", "FRACTAL", 0.0, neutral, neutral, long
            )
        )
        assertFalse(
            FuturesContextPolicy.allowsSignal(
                "LONG", "UNKNOWN", 0.0, neutral, neutral, neutral
            )
        )
    }

    @Test
    fun shortIsMirroredAndNeutralD1DoesNotCreateAnEntry() {
        assertTrue(FuturesContextPolicy.allows("SHORT", short, short, neutral))
        assertTrue(FuturesContextPolicy.allows("SHORT", short, long, neutral))
        assertFalse(FuturesContextPolicy.allows("SHORT", short, short, long))
        assertFalse(FuturesContextPolicy.allows("BUY", long, long, neutral))
    }
}
