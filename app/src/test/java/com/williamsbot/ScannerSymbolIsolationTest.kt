package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

class ScannerSymbolIsolationTest {
    @Test fun oneFailedSymbolDoesNotBlockSuccessfullyAnalyzedSymbols() {
        val blockers = scannerSymbolIsolationBlockers(
            approvedSymbols = listOf("BTCUSDT", "ETHUSDT", "SOLUSDT"),
            analyzedSymbols = listOf("BTCUSDT", "SOLUSDT"),
            symbolErrors = mapOf("ETHUSDT" to "HISTORY_GAP: 12:00-14:00")
        )
        assertEquals(setOf("ETHUSDT"), blockers.keys)
        assertTrue(blockers.getValue("ETHUSDT").contains("HISTORY_GAP"))
        assertFalse(blockers.containsKey("BTCUSDT"))
        assertFalse(blockers.containsKey("SOLUSDT"))
    }

    @Test fun aCompletedAnalysisWinsOverAStaleErrorEntry() {
        val blockers = scannerSymbolIsolationBlockers(
            approvedSymbols = listOf("BTCUSDT", "ETHUSDT"),
            analyzedSymbols = listOf("BTCUSDT", "ETHUSDT"),
            symbolErrors = mapOf("ETHUSDT" to "OLD_ERROR")
        )
        assertTrue(blockers.isEmpty())
    }

    @Test fun missingCompletionWithoutExceptionIsStillExplicitlyBlocked() {
        val blockers = scannerSymbolIsolationBlockers(
            approvedSymbols = listOf("BTCUSDT", "ETHUSDT"),
            analyzedSymbols = listOf("BTCUSDT"),
            symbolErrors = emptyMap()
        )
        assertEquals("SYMBOL_SCAN_INCOMPLETE: NO_ANALYSIS_RESULT", blockers.getValue("ETHUSDT"))
    }
}
