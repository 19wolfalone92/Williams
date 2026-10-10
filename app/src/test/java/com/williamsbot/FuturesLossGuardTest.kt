package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FuturesLossGuardTest {
    private fun trade(pnl: Double, closedAt: Long) =
        JSONObject().put("net_pnl", pnl.toString()).put("closed_at", closedAt.toString()).put("fee_known", true)

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
        assertEquals("post-loss cooldown active for 59 more seconds", reason)
    }

    @Test
    fun malformedHistoryFailsClosed() {
        val malformed = JSONObject().put("net_pnl", "NaN").put("closed_at", "9000").put("fee_known", true)
        val reason = FuturesLossGuard.violation(
            listOf(malformed),
            maxConsecutiveLosses = 2,
            cooldownMinutes = 30,
            nowMs = 10_000
        )
        assertEquals("closed Futures trade has invalid PnL", reason)
    }


    @Test
    fun incompleteFeeAccountingBlocksCurrentDayRiskDecision() {
        val unknown = trade(-1.0, 9_900).put("fee_known", false)
        val reason = FuturesLossGuard.violation(
            listOf(unknown, trade(-2.0, 9_800)),
            maxConsecutiveLosses = 2,
            cooldownMinutes = 0,
            nowMs = 10_000,
            maxStopOutsPerUtcDay = 2
        )
        assertEquals("today's Futures trade has incomplete commission accounting", reason)
    }

    @Test
    fun olderUnknownFeeDoesNotOverrideLaterKnownWinningClose() {
        val unknownYesterday = trade(-1.0, 9_000).put("fee_known", false)
        val knownWinToday = trade(1.0, 86_401_000)
        assertEquals(
            null,
            FuturesLossGuard.violation(
                listOf(knownWinToday, unknownYesterday),
                maxConsecutiveLosses = 2,
                cooldownMinutes = 0,
                nowMs = 86_410_000,
                maxStopOutsPerUtcDay = 2
            )
        )
    }

    @Test
    fun dailyStopOutLimitUsesUtcDayAndOnlyLosingStopExits() {
    val reason = FuturesLossGuard.violation(
        listOf(
            JSONObject().put("net_pnl", "-2").put("closed_at", "9900").put("reason", "STOP_LOSS").put("fee_known", true),
            JSONObject().put("net_pnl", "3").put("closed_at", "9800").put("reason", "STRUCTURAL_EXIT").put("fee_known", true)
        ),
        maxConsecutiveLosses = 3,
        cooldownMinutes = 0,
        nowMs = 10_000,
        maxStopOutsPerUtcDay = 1
    )
    assertEquals("UTC daily stop-out limit reached: 1 >= 1", reason)
}
}
