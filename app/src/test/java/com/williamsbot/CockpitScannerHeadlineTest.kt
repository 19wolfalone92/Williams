package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Test

/** Regression tests for the operator-visible H1 market count. */
class CockpitScannerHeadlineTest {
    @Test fun waitingForH1HistoryNeverLooksLikeTenScannedPairs() {
        val status = Status(scannerState = "WAITING_FOR_HISTORY", scannerConfiguredSymbols = 15,
            scannerSymbols = 0, h1ReadySymbolCount = 0)
        assertEquals("Загрузка истории H1 · 0/15 пар", cockpitScannerHeadline(status))
    }

    @Test fun inProgressUsesRealCompletedTasksNotPreviousScan() {
        val status = Status(scannerState = "RUNNING", scannerScanning = true,
            scannerProgressSymbols = 4, scannerSymbols = 15, scannerConfiguredSymbols = 15)
        assertEquals("Анализ выполняется · 4/15 пар", cockpitScannerHeadline(status))
    }

    @Test fun readyButExecutionBlockedIsNeverPresentedAsAllowed() {
        val status = Status(scannerState = "READY_BLOCKED", scannerSymbols = 15,
            scannerConfiguredSymbols = 15)
        assertEquals("Анализ завершён • входы заблокированы · 15/15 пар",
            cockpitScannerHeadline(status))
    }

    @Test fun oldOrCorruptCountCannotExceedApprovedUniverse() {
        val status = Status(scannerState = "PARTIAL_BLOCKED", scannerSymbols = 99,
            scannerConfiguredSymbols = 15)
        assertEquals("Анализ неполный • входы заблокированы · 15/15 пар",
            cockpitScannerHeadline(status))
    }
}
