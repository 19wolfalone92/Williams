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
        nowMs: Long,
        maxStopOutsPerUtcDay: Int = 2
    ): String? {
        if (maxConsecutiveLosses < 0 || cooldownMinutes < 0 || maxStopOutsPerUtcDay < 0 || nowMs <= 0L) {
            return "invalid loss-guard configuration/time"
        }
        val utcDayStart = java.time.Instant.ofEpochMilli(nowMs)
            .atZone(java.time.ZoneOffset.UTC).toLocalDate()
            .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        var stopOutsToday = 0
        for (trade in closedTradesNewestFirst) {
            val closedAt = trade.optString("closed_at").toLongOrNull()
                ?: return "closed Futures trade has missing/malformed close timestamp"
            if (closedAt <= 0L || closedAt > nowMs) {
                return "closed Futures trade has invalid close timestamp"
            }
            if (closedAt >= utcDayStart) {
                if (!trade.has("fee_known") || !trade.optBoolean("fee_known", false)) {
                    return "today's Futures trade has incomplete commission accounting"
                }
                val pnl = trade.optString("net_pnl").toDoubleOrNull()
                    ?: return "closed Futures trade has missing/malformed net PnL"
                if (!pnl.isFinite()) return "closed Futures trade has invalid PnL"
                if (pnl < 0.0 && trade.optString("reason").uppercase().contains("STOP")) {
                    stopOutsToday++
                }
            }
        }
        if (maxStopOutsPerUtcDay > 0 && stopOutsToday >= maxStopOutsPerUtcDay) {
            return "UTC daily stop-out limit reached: $stopOutsToday >= $maxStopOutsPerUtcDay"
        }
        var consecutiveLosses = 0
        var mostRecentLossAt = 0L
        var firstClose = true
        for (trade in closedTradesNewestFirst) {
            val closedAt = trade.optString("closed_at").toLongOrNull()
                ?: return "closed Futures trade has missing/malformed close timestamp"
            if (closedAt <= 0L || closedAt > nowMs) {
                return "closed Futures trade has invalid close timestamp"
            }
            val feeKnown = trade.has("fee_known") && trade.optBoolean("fee_known", false)
            if (firstClose && !feeKnown) {
                val withinCooldown = cooldown > 0 &&
                    nowMs - closedAt < cooldown.toLong() * 60_000L
                if (withinCooldown) {
                    return "latest Futures trade has incomplete commission accounting during cooldown window"
                }
                break
            }
            if (closedAt >= utcDayStart && !feeKnown) {
                return "today's Futures trade has incomplete commission accounting"
            }
            val pnl = trade.optString("net_pnl").toDoubleOrNull()
                ?: return "closed Futures trade has missing/malformed net PnL"
            if (!pnl.isFinite()) return "closed Futures trade has invalid PnL"
            if (firstClose) {
                firstClose = false
                if (pnl < 0.0) mostRecentLossAt = closedAt else break
            }
            // The consecutive-loss threshold resets at UTC midnight. The
            // latest-loss cooldown remains active across that boundary.
            if (closedAt < utcDayStart) break
            if (pnl < 0.0) consecutiveLosses++ else break
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
