package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CockpitTraceScopeTest {
    @Test fun only_primary_and_selected_symbols_are_loaded_into_the_cockpit() {
        assertEquals(setOf("BTCUSDT"), cockpitTraceSymbols("BTCUSDT", null))
        assertEquals(setOf("BTCUSDT", "ETHUSDT"), cockpitTraceSymbols("BTCUSDT", "ETHUSDT"))
        assertEquals(setOf("BTCUSDT"), cockpitTraceSymbols("BTCUSDT", "BTCUSDT"))
        assertTrue(cockpitTraceSymbols("BTCUSDT", "ETHUSDT").size <= 2)
    }

    @Test fun arbitrary_symbol_does_not_expand_the_trace_scope() {
        assertEquals(setOf("BTCUSDT"), cockpitTraceSymbols("BTCUSDT", "INVALIDUSDT"))
        assertTrue(cockpitTraceSymbols("UNKNOWN", "INVALIDUSDT").isEmpty())
    }
}
