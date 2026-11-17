package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

class WilliamsScannerClockOutageTest {
    private val clock = "FETCH_H1_CLOSED_CANDLES: IOException: USD-M Futures clock expired; bounded probe retry pending"

    @Test fun commonPublicUtcOutageIsNotFifteenIndependentAlgorithmFailures() {
        val affected=(1..15).associate { "PAIR$it" to clock }
        assertTrue(scannerAllSymbolClockOutage(15, 0, affected))
        assertFalse(scannerAllSymbolClockOutage(15, 1, affected))
        assertFalse(scannerAllSymbolClockOutage(15, 0, affected - "PAIR1"))
        assertFalse(scannerAllSymbolClockOutage(0, 0, emptyMap()))
    }

    @Test fun genuinePerSymbolFailuresStayFailures() {
        val mixed=(1..15).associate { "PAIR$it" to clock }.toMutableMap()
        mixed["PAIR3"]="ANALYSE_TC2_NTD_COURSE_H1: IllegalArgumentException: invalid OHLC"
        assertFalse(scannerAllSymbolClockOutage(15, 0, mixed))
        assertTrue(scannerAllSymbolClockOutage(15,0,(1..15).associate {
            "PAIR$it" to "IOException: USD-M Futures server clock sample unverified: rtt_ms=5000"
        }))
    }
}
