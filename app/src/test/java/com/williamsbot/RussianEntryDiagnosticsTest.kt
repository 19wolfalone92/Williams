package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

class RussianEntryDiagnosticsTest {
    @Test fun knownEntryAndHistoryBlockersExplainTheCauseInRussian() {
        val samples=mapOf(
            "H1_FRACTAL_TEETH_INVALID_BEFORE_SUBMISSION: BTCUSDT" to "Зубов",
            "H1_BOOK_TRIGGER_ALREADY_CROSSED: mark=100" to "Уровень входа",
            "ENTRY_PASSPORT_AUDIT_UNAVAILABLE: disk full" to "не отправлялась",
            "CONDITIONAL_TRIGGER_IN_PROGRESS" to "дочерний",
            "PORTFOLIO_RISK_EXHAUSTED" to "риск",
            "H1_PRIOR_ATTEMPT_FILL_OR_CANCELLATION_UNVERIFIED" to "не подтверждён",
            "WILLIAMS_SOURCE_OBSERVATION_ONLY" to "наблюдения")
        for((code,words) in samples) assertTrue(code,russianExecutionBlocker(code).contains(words))
    }
    @Test fun directionsWiseMenAndScannerStatesHaveReadableLabels() {
        assertTrue(russianSignalFamily("SHORT_FRACTAL").contains("Третий Мудрец"))
        assertTrue(russianSignalFamily("SUPER_AO").contains("Второй Мудрец"))
        assertTrue(russianSignalFamily("REVERSAL").contains("Первый Мудрец"))
        assertEquals("Продажа (SHORT)",russianDirection("SHORT"))
        assertEquals("H1 готов · контекст загружается",russianStatus("H1_READY_CONTEXT_LOADING"))
        assertEquals("Входы ограничены до сверки Binance",russianStatus("BLOCKED_FUTURES_RECONCILIATION"))
        // Unknown technical codes are retained rather than silently guessed.
        assertEquals("FUTURE_UNKNOWN_CODE",russianStatus("FUTURE_UNKNOWN_CODE"))
    }
}
