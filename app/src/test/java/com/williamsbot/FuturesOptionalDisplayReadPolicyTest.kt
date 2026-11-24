package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

class FuturesOptionalDisplayReadPolicyTest {
    @Test fun dashboardReadIsDeferredDuringEachIndependentP0SafetySweep() {
        assertFalse(optionalFuturesDisplayReadAllowed(true,false,false))
        assertFalse(optionalFuturesDisplayReadAllowed(false,true,false))
        assertFalse(optionalFuturesDisplayReadAllowed(false,false,true))
        assertFalse(optionalFuturesDisplayReadAllowed(true,true,true))
    }
    @Test fun dashboardReadCanRunOnlyOutsideSignedSafetyReconciliation() {
        assertTrue(optionalFuturesDisplayReadAllowed(false,false,false))
    }
}
