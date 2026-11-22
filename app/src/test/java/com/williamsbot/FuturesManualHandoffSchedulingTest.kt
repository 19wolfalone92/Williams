package com.williamsbot

import org.junit.Assert.*
import org.junit.Test

class FuturesManualHandoffSchedulingTest {
    @Test fun firstUnresolvedStopCannotStarveOtherSymbols() {
        val candidates=listOf("BTCUSDT","ETHUSDT","LTCUSDT")
        var cursor=0
        val selected=mutableListOf<String>()
        repeat(12) {
            val choice=nextFuturesManualHandoffCandidate(candidates,cursor)
            cursor=choice.second
            selected.add(checkNotNull(choice.first))
        }
        assertEquals(candidates.toSet(),selected.toSet())
        assertEquals(4,selected.count { it=="ETHUSDT" })
        assertEquals(4,selected.count { it=="LTCUSDT" })
        assertEquals(4,selected.count { it=="BTCUSDT" })
    }

    @Test fun invalidAndRepeatedSymbolsNeverIntroduceFinancialHandoffHints() {
        val one=nextFuturesManualHandoffCandidate(
            listOf("ILLEGAL","ETHUSDT","ETHUSDT","BTCUSDT"),Int.MAX_VALUE)
        assertTrue(one.first in setOf("ETHUSDT","BTCUSDT"))
        val none=nextFuturesManualHandoffCandidate(
            listOf("UNKNOWN","USD_REAL_ACCOUNT"),2)
        assertNull(none.first)
        assertEquals(0,none.second)
        val negative=nextFuturesManualHandoffCandidate(listOf("ETHUSDT"),-1)
        assertEquals("ETHUSDT",negative.first)
        assertEquals(0,negative.second)
    }
}
