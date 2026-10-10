package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FuturesLossGuardTest {
    private fun trade(pnl: Double, closedAt: Long) =
        JSONObject().put("net_pnl", pnl.toString()).put("closed_at", closedAt.toString())

    @Test
    fun blocksAtConsecutiveLossLimit() {
        assertEquals(
            "consecutive loss limit reached: 2 >= 2",
            FuturesLossGuard.violation(
                listOf(trade(-2.0, 9_900), trade(-1.0, 9_800), trade(4.0, 9_700)),
                maxConsecutiveLosses = 2,
                cooldownMinutes = 0,
                nowMs = 10_000
            )
        )
    }

    @Test
    fun winningOrFlatCloseResetsConsecutiveLosses() {
        assertNull(
            FuturesLossGuard.violation(
                listOf(trade(0.0, 9_900), trade(-2.0, 9_800), trade(-1.0, 9_700)),
                maxConsecutiveLosses = 2,
                cooldownMinutes = 0,
                nowMs = 10_000
            )
        )
    }

    @Test
    fun enforcesCooldownAfterLatestLoss() {
        val reason = FuturesLossGuard.violation(
            listOf(trade(-2.0, 9_000)),
            maxConsecutiveLosses = 3,
            cooldownMinutes = 1,
            nowMs = 10_000
        )
        assertEquals("post-loss cooldown active for 50 more seconds", reason)
    }

    @Test
    fun malformedHistoryFailsClosed() {
        val malformed = JSONObject().put("net_pnl", "NaN").put("closed_at", "9000")
        val reason = FuturesLossGuard.violation(
            listOf(malformed),
            maxConsecutiveLosses = 2,
            cooldownMinutes = 30,
            nowMs = 10_000
        )
        assertEquals("closed Futures trade has invalid PnL/timestamp", reason)
    }
}
