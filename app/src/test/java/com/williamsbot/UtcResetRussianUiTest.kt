package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

class UtcResetRussianUiTest {
    @Test fun resetAtUtcMidnightIsNotAResetOfAccountLossOrPendingOrders() {
        val info=russianLossResetExplanation(0,true,false,1)
        assertTrue(info.contains("обнулена"))
        assertTrue(info.contains("суточная база риска"))
        assertTrue(info.contains("ожидающая заявка"))
        assertFalse(info.contains("входы разрешены"))
    }

    @Test fun unavailableExchangeClockCannotClaimLossesCleared() {
        assertTrue(russianLossResetExplanation(0,false,false,0).contains("не подтверждён"))
    }

    @Test fun financialRiskErrorIsReadableWithoutRelaxingBlocker() {
        val msg=russianExecutionBlocker(
            "FUTURES_DAILY_BASELINE_UNKNOWN: cannot reconstruct UTC-day starting equity")
        assertTrue(msg.contains("Новые входы"))
        assertEquals("Ожидается исполнение заявки",russianStatus("ENTRY_PENDING"))
        assertEquals("Не установлено",russianStatus("UNKNOWN"))
    }

    @Test fun expiredTriggeringH1OrderMustNotStayReservedForever() {
        assertTrue(expiredTriggeringEntryNeedsExchangeCancel(
            true,"TRIGGERING",true,false))
        assertTrue(expiredTriggeringEntryNeedsExchangeCancel(
            true,"TRIGGERED",true,false))
        assertFalse(expiredTriggeringEntryNeedsExchangeCancel(
            false,"TRIGGERING",true,false))
        assertFalse(expiredTriggeringEntryNeedsExchangeCancel(
            true,"PARTIALLY_FILLED",true,false))
        assertFalse(expiredTriggeringEntryNeedsExchangeCancel(
            true,"TRIGGERING",false,false))
        assertFalse(expiredTriggeringEntryNeedsExchangeCancel(
            true,"TRIGGERING",true,true))
    }

    @Test fun riskPauseReleasesOnlyOnFullyVerifiedDayAndNotForOperatorPause() {
        fun allowed(verified:Boolean=true, risk:Boolean=true, running:Boolean=true,
            operator:Boolean=false, kill:Boolean=false, reconcile:Boolean=false,
            forced:Boolean=false):Boolean=canReleaseVerifiedUtcRiskPause(
                verified,risk,running,operator,kill,reconcile,forced)
        assertTrue(allowed())
        assertFalse(allowed(verified=false))
        assertFalse(allowed(risk=false))
        assertFalse(allowed(running=false))
        assertFalse(allowed(operator=true))
        assertFalse(allowed(kill=true))
        assertFalse(allowed(reconcile=true))
        assertFalse(allowed(forced=true))
    }
}
