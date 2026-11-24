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

    @Test fun missingSpreadBlocksOnlyThatContractAndNotItsPeers() {
        val blockers = scannerEntrySymbolBlockers(
            approvedSymbols = listOf("BTCUSDT", "ETHUSDT", "SOLUSDT"),
            analysisBlockers = emptyMap(),
            spreads = mapOf("BTCUSDT" to 0.0004, "ETHUSDT" to -1.0, "SOLUSDT" to 0.0007)
        )
        assertEquals(setOf("ETHUSDT"), blockers.keys)
        assertTrue(blockers.getValue("ETHUSDT").contains("SYMBOL_SPREAD_UNAVAILABLE"))
    }

    @Test fun analysisFailureRemainsThePrimaryPerSymbolBlocker() {
        val blockers = scannerEntrySymbolBlockers(
            approvedSymbols = listOf("BTCUSDT", "ETHUSDT"),
            analysisBlockers = mapOf("ETHUSDT" to "SYMBOL_SCAN_INCOMPLETE: HISTORY_GAP"),
            spreads = mapOf("BTCUSDT" to 0.0002)
        )
        assertEquals("SYMBOL_SCAN_INCOMPLETE: HISTORY_GAP", blockers.getValue("ETHUSDT"))
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
