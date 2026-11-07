package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

class Tc2H1RawConsistencyTest {
    private val hour=WILLIAMS_H1_MS
    private val at=1000*hour
    private val now=at+3*hour+45_000L

    private fun bar(atMs:Long, high:Double=109.0,low:Double=97.0,vol:Double=10.0) =
        WilliamsWaveBar(atMs,100.0,high,low,100.0,vol)

    @Test fun contradictoryLastClosedHourCannotHideAlreadyConsumedLongBreakout() {
        val closed=listOf(bar(at),bar(at+hour),bar(at+2*hour,high=111.0))
        val raw=listOf(bar(at+2*hour,high=109.0),bar(at+3*hour,high=108.0))
        // Previously associateBy used the raw 109 instead of confirmed 111,
        // erasing the touch at 110 and potentially rearming an old TC2 entry.
        assertEquals("H1_CLOSED_BAR_RAW_EXCHANGE_MISMATCH",
            williamsBookH1ObservedBreakoutBlocker("LONG",at,110.0,now,closed,raw))
    }

    @Test fun shortSideContradictionAlsoRefusesBeforeExchangeMutation() {
        val closed=listOf(bar(at),bar(at+hour),bar(at+2*hour,low=94.0))
        val raw=listOf(bar(at+2*hour,low=96.0),bar(at+3*hour,low=97.0))
        assertEquals("H1_CLOSED_BAR_RAW_EXCHANGE_MISMATCH",
            williamsBookH1ObservedBreakoutBlocker("SHORT",at,95.0,now,closed,raw))
    }

    @Test fun consistentOverlapStillAllowsARealUnconsumedSetup() {
        val closed=listOf(bar(at),bar(at+hour),bar(at+2*hour))
        val raw=listOf(bar(at+2*hour,vol=11.0),bar(at+3*hour,high=108.0))
        // Changed volume alone is not a conflicting OHLC level: the book
        // triggers depend on price, and the raw volume can finalize later.
        assertNull(williamsBookH1ObservedBreakoutBlocker(
            "LONG",at,110.0,now,closed,raw))
        assertNull(williamsBookH1ObservedBreakoutBlocker(
            "SHORT",at,95.0,now,closed,raw))
    }

    @Test fun consistentHistoricalTouchIsStillConsumed() {
        val closed=listOf(bar(at),bar(at+hour),bar(at+2*hour,high=111.0))
        val raw=listOf(bar(at+2*hour,high=111.0),bar(at+3*hour))
        assertEquals("H1_BOOK_BREAKOUT_WAS_ALREADY_CONSUMED_OR_HISTORY_UNVERIFIED",
            williamsBookH1ObservedBreakoutBlocker(
                "LONG",at,110.0,now,closed,raw))
    }

    @Test fun duplicatedCachedClosedTimeCannotBeOverwrittenByMap() {
        val duplicate=bar(at+hour)
        val closed=listOf(bar(at),duplicate,duplicate,bar(at+2*hour))
        val raw=listOf(bar(at+2*hour),bar(at+3*hour))
        assertEquals("H1_CLOSED_HISTORY_CONTINUITY_OR_OHLC_UNVERIFIED",
            williamsBookH1ObservedBreakoutBlocker("LONG",at,110.0,now,closed,raw))
    }

    @Test fun russianDiagnosticExplainsWhyExchangeEntryWasNotPlaced() {
        assertTrue(russianExecutionBlocker("H1_CLOSED_BAR_RAW_EXCHANGE_MISMATCH")
            .contains("Новый вход запрещён"))
        assertTrue(williamsRuRuntimeMessage("H1_CLOSED_BAR_RAW_EXCHANGE_MISMATCH")
            .contains("свежими данными Binance"))
    }
}
