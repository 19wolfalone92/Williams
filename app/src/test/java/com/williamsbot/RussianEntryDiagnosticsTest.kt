package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

class RussianEntryDiagnosticsTest {
    @Test fun stopSupervisionExplainsItsOwnAuthorityAndFailuresInRussian() {
        for((code,words) in mapOf(
            "SOLUSDT has operator-approved stop-only supervision; new entries blocked" to "существующего SL",
            "SOLUSDT has an exchange-protected position but no verified local campaign ownership" to "Биржевой SL подтверждён",
            "OPERATOR_STOP_SUPERVISION_BLOCKED SOLUSDT: Other open orders on supervised symbol" to "другая заявка",
            "Position changed during uncertain replacement; operator resolution required" to "изменились",
            "New stop not exchange-confirmed; original retained; no POST retry" to "повторная отправка запрещена")) {
            assertTrue(code,russianExecutionBlocker(code).contains(words))
        }
    }
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
