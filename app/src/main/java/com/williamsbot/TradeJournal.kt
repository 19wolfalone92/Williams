package com.williamsbot

import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

/**
 * Local encrypted trade journal.
 * Stores entry-time facts separately from post-trade conclusions so hindsight
 * does not overwrite what the engine actually knew when the trade was opened.
 */
object TradeJournal {
    private const val KEY = "trade_journal_v1"
    private const val MAX_ROWS = 500

    @Synchronized
    fun recordEntry(
        prefs: SharedPreferences,
        symbol: String,
        entry: Double,
        qty: Double,
        stop: Double,
        take: Double,
        riskPct: Double,
        notional: Double,
        candidate: JSONObject,
        direction: String = run {
            val signal = candidate.optString("direction")
                .ifBlank { candidate.optString("signal_type").ifBlank { candidate.optString("signal") } }
            if (signal.contains("SHORT", ignoreCase = true) ||
                signal.equals("SELL", ignoreCase = true)
            ) "SHORT" else "LONG"
        }
    ): String {
        require(direction == "LONG" || direction == "SHORT") { "Trade direction must be LONG or SHORT" }
        val id = "J" + System.currentTimeMillis() + "_" +
            symbol + "_" + (1000..9999).random()
        val row = JSONObject()
            .put("id", id)
            .put("symbol", symbol)
            .put("side", if (direction == "LONG") "BUY" else "SELL")
            .put("direction", direction)
            .put("status", "OPEN")
            .put("opened_at", System.currentTimeMillis())
            .put("entry_price", entry)
            .put("qty", qty)
            .put("notional_usdt", notional)
            .put("stop_price", stop)
            .put("take_profit", take)
            .put("risk_pct", riskPct)
            .put("planned_rr", if (entry > stop) (take-entry)/(entry-stop) else 0.0)
            .put("entry_snapshot", candidate)
            .put("entry_reason", candidate.optString("reason"))
            .put("strategy_version", BuildConfig.VERSION_NAME)
            .put("mfe_pct", 0.0)
            .put("mae_pct", 0.0)
            .put("mfe_r", 0.0)
            .put("mae_r", 0.0)
        val all = read(prefs)
        all.put(row)
        trim(all)
        write(prefs, all)
        return id
    }

    @Synchronized
    fun updateExcursion(
        prefs: SharedPreferences,
        symbol: String,
        price: Double
    ) {
        if (price <= 0.0) return
        val all = read(prefs)
        for (i in 0 until all.length()) {
            val row = all.getJSONObject(i)
            if (row.optString("symbol") != symbol || row.optString("status") != "OPEN") continue
            val entry = row.optDouble("entry_price", 0.0)
            val stop = row.optDouble("stop_price", 0.0)
            if (entry <= 0.0) continue
            val direction = row.optString("direction").ifBlank {
                if (row.optString("side").equals("SELL", true)) "SHORT" else "LONG"
            }
            val rawMovePct = (price - entry) / entry
            val movePct = if (direction == "SHORT") -rawMovePct else rawMovePct
            val riskPct = max(kotlin.math.abs(entry - stop) / entry, 0.000001)
            row.put("mfe_pct", max(row.optDouble("mfe_pct", 0.0), movePct))
            row.put("mae_pct", kotlin.math.min(row.optDouble("mae_pct", 0.0), movePct))
            row.put("mfe_r", max(row.optDouble("mfe_r", 0.0), movePct / riskPct))
            row.put("mae_r", kotlin.math.min(row.optDouble("mae_r", 0.0), movePct / riskPct))
            row.put("last_price", price)
            row.put("last_mark_at", System.currentTimeMillis())
        }
        write(prefs, all)
    }

    @Synchronized
    fun close(
        prefs: SharedPreferences,
        symbol: String,
        exitPrice: Double,
        reason: String,
        order: JSONObject? = null
    ) {
        val all = read(prefs)
        for (i in 0 until all.length()) {
            val row = all.getJSONObject(i)
            if (row.optString("symbol") != symbol || row.optString("status") != "OPEN") continue
            val entry = row.optDouble("entry_price", 0.0)
            val qty = row.optDouble("qty", 0.0)
            val notional = row.optDouble("notional_usdt", 0.0)
            val direction = row.optString("direction").ifBlank {
                if (row.optString("side").equals("SELL", true)) "SHORT" else "LONG"
            }
            val rawMove = if (entry > 0.0) exitPrice - entry else 0.0
            val pnl = (if (direction == "SHORT") -rawMove else rawMove) * qty
            val pnlPct = if (notional > 0.0) pnl / notional else 0.0
            val riskPct = max(kotlin.math.abs(entry - row.optDouble("stop_price", entry)) / entry, 0.000001)
            row.put("status", "CLOSED")
                .put("closed_at", System.currentTimeMillis())
                .put("exit_price", exitPrice)
                .put("pnl", pnl)
                .put("pnl_pct", pnlPct)
                .put("r_multiple", pnlPct / riskPct)
                .put("exit_reason", reason)
                .put("outcome", when {
                    pnl > 0.0 -> "WIN"
                    pnl < 0.0 -> "LOSS"
                    else -> "BREAKEVEN"
                })
                .put("post_trade_classification", classify(row, pnl, reason))
            if (order != null) row.put("exit_order", order)
            break
        }
        write(prefs, all)
    }

    /**
     * Idempotently write a completed native USD-M Futures campaign reconstructed from
     * authenticated exchange trade fills. A trade with unconvertible commissions is
     * retained but marked incomplete and excluded from aggregate net-PnL statistics.
     */
    @Synchronized
    fun recordExchangeCompleted(
        prefs: SharedPreferences,
        id: String,
        symbol: String,
        direction: String,
        entryPrice: Double,
        exitPrice: Double,
        qty: Double,
        grossPnlUsdt: Double,
        feeUsdt: Double,
        feeKnown: Boolean,
        initialRiskUsdt: Double,
        openedAtMs: Long,
        closedAtMs: Long,
        reason: String,
        entrySnapshot: JSONObject,
        entrySlippagePct: Double,
        fillCount: Int
    ) {
        require(id.isNotBlank() && symbol.isNotBlank())
        require(direction == "LONG" || direction == "SHORT")
        require(entryPrice.isFinite() && entryPrice > 0.0 &&
            exitPrice.isFinite() && exitPrice > 0.0 &&
            qty.isFinite() && qty > 0.0
        )
        require(grossPnlUsdt.isFinite() && feeUsdt.isFinite() &&
            initialRiskUsdt.isFinite() && initialRiskUsdt > 0.0
        )
        require(openedAtMs > 0L && closedAtMs >= openedAtMs)
        val notional = entryPrice * qty
        val netPnl = grossPnlUsdt - feeUsdt
        val complete = feeKnown && netPnl.isFinite() && notional.isFinite()
        val rMultiple = netPnl / initialRiskUsdt
        val equitySnapshot = entrySnapshot.optDouble("risk_equity_snapshot_usdt", 0.0)
        val outcome = if (!complete) "UNKNOWN_FEES" else when {
            netPnl > 0.0 -> "WIN"
            netPnl < 0.0 -> "LOSS"
            else -> "BREAKEVEN"
        }
        val row = JSONObject()
            .put("id", id)
            .put("symbol", symbol)
            .put("side", if (direction == "LONG") "BUY" else "SELL")
            .put("direction", direction)
            .put("status", "CLOSED")
            .put("opened_at", openedAtMs)
            .put("closed_at", closedAtMs)
            .put("entry_price", entryPrice)
            .put("exit_price", exitPrice)
            .put("qty", qty)
            .put("notional_usdt", notional)
            .put("gross_pnl_usdt", grossPnlUsdt)
            .put("fee_usdt", feeUsdt)
            .put("fee_known", feeKnown)
            .put("pnl", netPnl)
            .put("net_pnl_usdt", netPnl)
            .put("pnl_complete", complete)
            .put("pnl_basis", if (complete) "EXCHANGE_REALIZED_PNL_LESS_FEES" else "PARTIAL_FEES_UNKNOWN")
            .put("pnl_pct", if (notional > 0.0) netPnl / notional else 0.0)
            .put("initial_risk_usdt", initialRiskUsdt)
            .put("risk_pct", if (equitySnapshot > 0.0) initialRiskUsdt / equitySnapshot else 0.0)
            .put("r_multiple", rMultiple)
            .put("outcome", outcome)
            .put("exit_reason", reason)
            .put("entry_reason", entrySnapshot.optString("kind"))
            .put("entry_snapshot", entrySnapshot)
            .put("strategy_version", BuildConfig.VERSION_NAME)
            .put("trade_audit_source", "BINANCE_USDM_USER_TRADES")
            .put("entry_slippage_pct", entrySlippagePct)
            .put("fill_count", fillCount)
            .put("classification", if (complete) "exchange_reconciled" else "fees_incomplete")
            .put("post_trade_classification", if (complete) "exchange_reconciled" else "fees_incomplete")
        val all = read(prefs)
        var replaced = false
        for (i in 0 until all.length()) {
            val old = all.optJSONObject(i) ?: continue
            if (old.optString("id") == id) {
                all.put(i, row)
                replaced = true
                break
            }
        }
        if (!replaced) all.put(row)
        trim(all)
        write(prefs, all)
    }

    @Synchronized
    fun trades(prefs: SharedPreferences): JSONArray {
        val src = read(prefs)
        val out = JSONArray()
        for (i in src.length()-1 downTo 0) out.put(src.getJSONObject(i))
        return out
    }

    @Synchronized
    fun stats(prefs: SharedPreferences): JSONObject {
        val rows = read(prefs)
        var closed = 0
        var wins = 0
        var losses = 0
        var pnl = 0.0
        var r = 0.0
        var pnlComplete = 0
        var pnlIncomplete = 0
        for (i in 0 until rows.length()) {
            val x = rows.getJSONObject(i)
            if (x.optString("status") != "CLOSED") continue
            closed++
            if (x.optBoolean("pnl_complete", true)) {
                pnl += x.optDouble("pnl", 0.0)
                r += x.optDouble("r_multiple", 0.0)
                pnlComplete++
                when (x.optString("outcome")) {
                    "WIN" -> wins++
                    "LOSS" -> losses++
                }
            } else {
                pnlIncomplete++
            }
        }
        return JSONObject()
            .put("total", rows.length())
            .put("open", rows.length() - closed)
            .put("closed", closed)
            .put("wins", wins)
            .put("losses", losses)
            .put("win_rate", if (pnlComplete > 0) wins.toDouble()/pnlComplete else 0.0)
            .put("pnl", pnl)
            .put("sum_r", r)
            .put("pnl_complete_trades", pnlComplete)
            .put("pnl_incomplete_trades", pnlIncomplete)
    }

    private fun classify(row: JSONObject, pnl: Double, reason: String): String {
        if (reason.contains("RECONCILE", true)) return "technical_or_recovery"
        if (reason.contains("MANUAL", true)) return "manual_exit"
        if (pnl >= 0.0) return "signal_valid_or_normal_variance"
        val wave = row.optJSONObject("entry_snapshot")?.optInt("wave_position", 0) ?: 0
        val exhaustion = row.optJSONObject("entry_snapshot")?.optDouble("wave_exhaustion_risk", 0.0) ?: 0.0
        if (wave == 5 && exhaustion >= 0.55) return "wave_5_exhaustion"
        if (wave == 5) return "late_or_exhausted_wave_5"
        if (wave == 3) return "wave_3_signal_failed"
        val htf = row.optJSONObject("entry_snapshot")?.optBoolean("htf_confirmed", true) ?: true
        if (!htf) return "higher_timeframe_contradiction"
        return "losing_signal_needs_review"
    }

    private fun read(prefs: SharedPreferences): JSONArray =
        runCatching { JSONArray(prefs.getString(KEY, "[]") ?: "[]") }.getOrElse { JSONArray() }

    private fun write(prefs: SharedPreferences, value: JSONArray) {
        prefs.edit {
            putString(KEY, value.toString())
        }
    }

    private fun trim(value: JSONArray) {
        while (value.length() > MAX_ROWS) value.remove(0)
    }
}

/** Result of the entry-only Futures loss-streak circuit breaker. */
internal data class FuturesLossStreakDecision(
    val available: Boolean,
    val consecutiveLosses: Int,
    val allowNewEntries: Boolean,
    val cooldownUntilMs: Long?,
    val reason: String?
)

/**
 * Evaluate only exchange-reconciled native Futures closes for the current UTC day.
 * Unknown fee/PnL on a newer closed Futures campaign fails closed: it must not be
 * silently counted as a win or skipped while calculating the loss streak.
 */
internal fun evaluateFuturesLossStreak(
    tradeRows: JSONArray,
    nowMs: Long,
    maximumConsecutiveLosses: Int = 2,
    cooldownMs: Long = 30L * 60L * 1000L,
    hardPauseAt: Int = 3
): FuturesLossStreakDecision {
    if (nowMs <= 0L || maximumConsecutiveLosses <= 0 || cooldownMs < 0L ||
        hardPauseAt <= maximumConsecutiveLosses
    ) return FuturesLossStreakDecision(false, 0, false, null, "FUTURES_LOSS_STREAK_CONFIG_INVALID")

    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    utc.timeInMillis = nowMs
    utc.set(Calendar.HOUR_OF_DAY, 0)
    utc.set(Calendar.MINUTE, 0)
    utc.set(Calendar.SECOND, 0)
    utc.set(Calendar.MILLISECOND, 0)
    val dayStart = utc.timeInMillis

    val closed = (0 until tradeRows.length()).mapNotNull { index ->
        tradeRows.optJSONObject(index)
    }.filter { row ->
        val source = row.optString("trade_audit_source")
        val id = row.optString("id")
        val isNativeFutures = source == "BINANCE_USDM_USER_TRADES" || id.startsWith("FUTURES:")
        isNativeFutures && row.optString("status").equals("CLOSED", true) &&
            row.optLong("closed_at", 0L) >= dayStart && row.optLong("closed_at", 0L) <= nowMs
    }.sortedByDescending { it.optLong("closed_at", 0L) }

    var losses = 0
    var mostRecentLossAt = 0L
    for (row in closed) {
        val outcome = row.optString("outcome").trim().uppercase(Locale.US)
        if (outcome == "UNKNOWN_FEES" || !row.optBoolean("pnl_complete", true) ||
            !row.optDouble("pnl", Double.NaN).isFinite()
        ) {
            return FuturesLossStreakDecision(
                false, losses, false, null, "FUTURES_LOSS_STREAK_PNL_UNKNOWN"
            )
        }
        when (outcome) {
            "WIN", "BREAKEVEN" -> break
            "LOSS" -> {
                losses++
                if (mostRecentLossAt == 0L) mostRecentLossAt = row.optLong("closed_at", 0L)
            }
            else -> return FuturesLossStreakDecision(
                false, losses, false, null, "FUTURES_LOSS_STREAK_OUTCOME_UNKNOWN"
            )
        }
    }

    if (losses >= hardPauseAt) {
        return FuturesLossStreakDecision(
            true, losses, false, null, "FUTURES_CONSECUTIVE_LOSS_HARD_PAUSE"
        )
    }
    if (losses >= maximumConsecutiveLosses && mostRecentLossAt > 0L) {
        val cooldownUntil = mostRecentLossAt + cooldownMs
        if (nowMs < cooldownUntil) {
            return FuturesLossStreakDecision(
                true, losses, false, cooldownUntil, "FUTURES_CONSECUTIVE_LOSS_COOLDOWN"
            )
        }
    }
    return FuturesLossStreakDecision(true, losses, true, null, null)
}
