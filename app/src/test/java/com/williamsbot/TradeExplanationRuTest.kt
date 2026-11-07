package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

class TradeExplanationRuTest {
    @Test fun wm3HistoryExplainsActualStopEntryWithoutClaimingSignalIsFill() {
        val text = russianTradeEntryExplanation(
            "BOOK H1 WM3 eligible confirmed fractal outside Teeth; waiting for actual trigger; LONG stop-entry at H1 signal trigger (no M15 confirmation)",
            "LONG"
        )
        assertTrue(text.contains("TC2"))
        assertTrue(text.contains("фрактал"))
        assertTrue(text.contains("STOP-заявкой"))
        assertTrue(text.contains("M15"))
        assertTrue(text.contains("не доказывает исполнение"))
        assertFalse(text.contains("eligible"))
    }

    @Test fun unknownExchangeCloseNeverInventsStopOrManualExit() {
        val text = russianTradeExitExplanation("EXCHANGE_RECONCILED_CLOSE", true)
        assertTrue(text.contains("сверила закрытие"))
        assertTrue(text.contains("не установила"))
        assertTrue(text.contains("нельзя утверждать"))
        assertFalse(text.contains("EXCHANGE_RECONCILED_CLOSE"))
    }

    @Test fun actualStopProofAndUnverifiedCloseAreSeparateCases() {
        assertTrue(russianTradeExitExplanation("Стоп-лосс исполнен на Binance", true)
            .contains("дочернему ордеру"))
        val unknown = russianTradeExitExplanation("", false)
        assertTrue(unknown.contains("не подтверждена"))
        assertTrue(unknown.contains("нельзя приписывать"))
    }

    @Test fun observerSignalIsNotConfusedWithOppositeTc2Exit() {
        val text = russianTradeExitExplanation("OPPOSITE_WILLIAMS_LEVEL_TRIGGERED", true)
        assertTrue(text.contains("встречным сигналом"))
        assertTrue(text.contains("не доказывает разворот"))
        assertFalse(text.contains("OPPOSITE_WILLIAMS"))
    }

    @Test fun classificationsAreHumanReadableAndDistinguishFactsFromCauses() {
        assertTrue(russianTradeClassification("exchange_reconciled").contains("не причина выхода"))
        assertTrue(russianTradeClassification("fees_incomplete").contains("не восстановлена"))
        assertTrue(russianTradeClassification("exchange_reconciled", false).contains("неполная"))
        assertFalse(russianTradeClassification("unexpected_secret_code").contains("unexpected_secret_code"))
    }

    @Test fun provenanceAndManagementHaveSafeFallbacks() {
        assertTrue(russianTradeAuditEvidence("BINANCE_USDM_USER_TRADES", 4).contains("4"))
        assertTrue(russianTradeAuditEvidence("FUTURES_CAMPAIGN_SNAPSHOT", 0)
            .contains("не заменяет"))
        assertTrue(russianTradeManagementExplanation("exchange_reconciliation_in_progress")
            .contains("сверка"))
        assertTrue(russianTradeManagementExplanation("FUTURE_UNDOCUMENTED_EVENT")
            .contains("не"))
    }
}
