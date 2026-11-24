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

    private fun history(n:Int=150):List<CandleN> = (1..n).map {
        CandleN(it*3_600_000L,10.0,12.0,9.0,11.0,1.0)
    }

    @Test fun exactCurrentDurableHistoryAllowsOnlyTheSelectedContract() {
        val bars=history()
        assertTrue(scannerCandidateHistoryVerified(bars,bars,true,true,true))
        assertFalse(scannerCandidateHistoryVerified(bars,bars,true,false,true))
        assertFalse(scannerCandidateHistoryVerified(bars,bars,true,true,false))
        assertFalse(scannerCandidateHistoryVerified(bars,bars,false,true,true))
        assertFalse(scannerCandidateHistoryVerified(bars.takeLast(149),bars,true,true,true))
    }

    @Test fun historicalCorrectionGapAndFormingBarInvalidateCandidate() {
        val bars=history()
        val corrected=bars.toMutableList().apply { this[20]=this[20].copy(c=10.0) }
        assertFalse(scannerCandidateHistoryVerified(bars,corrected,true,true,true))
        val gapped=bars.toMutableList().apply { this[20]=this[20].copy(t=this[20].t+1) }
        assertFalse(scannerCandidateHistoryVerified(gapped,gapped,true,true,true))
        val forming=bars.toMutableList().apply { this[149]=this[149].copy(exchangeClosed=false) }
        assertFalse(scannerCandidateHistoryVerified(forming,forming,true,true,true))
        assertFalse(scannerCandidateHistoryVerified(bars,history(151),true,true,true))
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
