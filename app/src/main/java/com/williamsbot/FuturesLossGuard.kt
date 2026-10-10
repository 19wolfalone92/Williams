package com.williamsbot

import org.json.JSONObject
import kotlin.math.ceil

/**
 * Deterministic entry lockout based only on finalized, persisted Futures trades.
 * Input must be newest-first. Malformed history fails closed.
 */
object FuturesLossGuard {
    fun violation(
        closedTradesNewestFirst: List<JSONObject>,
        maxConsecutiveLosses: Int,
        cooldownMinutes: Int,
        nowMs: Long
    ): String? {
        if (maxConsecutiveLosses < 0 || cooldownMinutes < 0 || nowMs <= 0L) {
            return "invalid loss-guard configuration/time"
        }
        var consecutiveLosses = 0
        var mostRecentLossAt = 0L
        for (trade in closedTradesNewestFirst) {
            val pnl = trade.optString("net_pnl").toDoubleOrNull()
                ?: return "closed Futures trade has missing/malformed net PnL"
            val closedAt = trade.optString("closed_at").toLongOrNull()
                ?: return "closed Futures trade has missing/malformed close timestamp"
            if (!pnl.isFinite() || closedAt <= 0L || closedAt > nowMs) {
                return "closed Futures trade has invalid PnL/timestamp"
            }
            if (pnl < 0.0) {
                consecutiveLosses++
                if (mostRecentLossAt == 0L) mostRecentLossAt = closedAt
            } else {
                break
            }
        }
        if (maxConsecutiveLosses > 0 && consecutiveLosses >= maxConsecutiveLosses) {
            return "consecutive loss limit reached: $consecutiveLosses >= $maxConsecutiveLosses"
        }
        if (cooldownMinutes > 0 && mostRecentLossAt > 0L) {
            val remainingMs = cooldownMinutes.toLong() * 60_000L - (nowMs - mostRecentLossAt)
            if (remainingMs > 0L) {
                return "post-loss cooldown active for ${ceil(remainingMs / 1000.0).toLong()} more seconds"
            }
        }
        return null
    }
}
