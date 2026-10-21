package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

/** Fourth-wave AO zero contact is evidence, not a wave-degree certificate. */
class WilliamsFourthAoZeroTest {
    @Test fun bothSignsNormalizeToSameFourthWaveEvidence() {
        assertEquals("MINIMUM_ONLY",classifyWilliamsFourthAo(10.0,0.0,120))
        assertEquals("MINIMUM_ONLY",classifyWilliamsFourthAo(10.0,-0.001,120))
        assertEquals("NEAR_ZERO_UNCONFIRMED",classifyWilliamsFourthAo(10.0,0.5,120))
        assertEquals("NEAR_ZERO_UNCONFIRMED",classifyWilliamsFourthAo(10.0,1.0,120))
        assertEquals("NO_ZERO_CROSS",classifyWilliamsFourthAo(10.0,1.01,120))
        assertEquals("WRONG_SCALE",classifyWilliamsFourthAo(10.0,-1.0,30))
        assertEquals("WRONG_SCALE",classifyWilliamsFourthAo(10.0,-1.0,141))
        assertEquals("MISSING_W3_MOMENTUM",classifyWilliamsFourthAo(Double.NaN,0.0,120))
        assertEquals("MISSING_W3_MOMENTUM",classifyWilliamsFourthAo(0.0,0.0,120))
        assertEquals("MISSING_W3_MOMENTUM",classifyWilliamsFourthAo(10.0,Double.NaN,120))
    }
}
