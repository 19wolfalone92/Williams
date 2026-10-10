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
    fun shortIsMirroredAndNeutralD1DoesNotCreateAnEntry() {
        assertTrue(FuturesContextPolicy.allows("SHORT", short, short, neutral))
        assertFalse(FuturesContextPolicy.allows("SHORT", short, long, neutral))
        assertFalse(FuturesContextPolicy.allows("SHORT", short, short, long))
        assertFalse(FuturesContextPolicy.allows("BUY", long, long, neutral))
    }

    @Test
    fun wm1ReversalCanBeFirstSignalBeforeH1AndH4TrendAlignment() {
        assertTrue(FuturesContextPolicy.allowsSignal("LONG", "REVERSAL", neutral, short, neutral))
        assertTrue(FuturesContextPolicy.allowsSignal("SHORT", "REVERSAL", neutral, long, neutral))
    }

    @Test
    fun wm1StillHonorsActiveOppositeDailyMacroVeto() {
        assertFalse(FuturesContextPolicy.allowsSignal("LONG", "REVERSAL", neutral, short, short))
        assertFalse(FuturesContextPolicy.allowsSignal("SHORT", "REVERSAL", neutral, long, long))
    }

    @Test
    fun wm2AndWm3KeepDirectionalContextGate() {
        assertFalse(FuturesContextPolicy.allowsSignal("LONG", "SUPER_AO", long, short, neutral))
        assertTrue(FuturesContextPolicy.allowsSignal("LONG", "FRACTAL", long, long, neutral))
    }
}
