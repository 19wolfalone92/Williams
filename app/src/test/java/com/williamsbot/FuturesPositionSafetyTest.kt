package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

class FuturesPositionSafetyTest {
    @Test fun liquidationMustBeBeyondTheStopWithTheRequiredBufferOnBothSides() {
        assertEquals("PASS", assessFuturesLiquidationProtection("LONG", "95", "90").status)
        assertEquals("PASS", assessFuturesLiquidationProtection("SHORT", "105", "110").status)
        assertEquals("UNSAFE", assessFuturesLiquidationProtection("LONG", "95", "96").status)
        assertEquals("UNSAFE", assessFuturesLiquidationProtection("SHORT", "105", "104").status)
        assertEquals("UNSAFE", assessFuturesLiquidationProtection("LONG", "95", "94.5").status)
        assertEquals("UNSAFE", assessFuturesLiquidationProtection("SHORT", "105", "105.5").status)
    }
    @Test fun absentZeroOrNonFinitePricesNeverAuthorizeAnAutomaticExit() {
        listOf(null, "0", "NaN", "Infinity", "-1", "malformed").forEach {
            assertEquals("UNKNOWN", assessFuturesLiquidationProtection("LONG", "95", it).status)
        }
        assertEquals("UNKNOWN", assessFuturesLiquidationProtection("LONG", "95", "90", Double.NaN).status)
    }
}
