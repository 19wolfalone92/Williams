package com.williamsbot

import android.content.SharedPreferences
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
        candidate: JSONObject
    ): String {
        val id = "J" + System.currentTimeMillis() + "_" +
            symbol + "_" + (1000..9999).random()
        val row = JSONObject()
            .put("id", id)
            .put("symbol", symbol)
            .put("side", "BUY")
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
            .put("strategy_version", "4.16.0")
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
            val movePct = (price - entry) / entry
            val riskPct = max((entry - stop) / entry, 0.000001)
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
            val pnl = if (entry > 0.0) (exitPrice - entry) * qty else 0.0
            val pnlPct = if (notional > 0.0) pnl / notional else 0.0
            val riskPct = max((entry - row.optDouble("stop_price", entry)) / entry, 0.000001)
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
        for (i in 0 until rows.length()) {
            val x = rows.getJSONObject(i)
            if (x.optString("status") != "CLOSED") continue
            closed++
            pnl += x.optDouble("pnl", 0.0)
            r += x.optDouble("r_multiple", 0.0)
            when (x.optString("outcome")) {
                "WIN" -> wins++
                "LOSS" -> losses++
            }
        }
        return JSONObject()
            .put("total", rows.length())
            .put("open", rows.length() - closed)
            .put("closed", closed)
            .put("wins", wins)
            .put("losses", losses)
            .put("win_rate", if (closed > 0) wins.toDouble()/closed else 0.0)
            .put("pnl", pnl)
            .put("sum_r", r)
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
        prefs.edit().putString(KEY, value.toString()).apply()
    }

    private fun trim(value: JSONArray) {
        while (value.length() > MAX_ROWS) value.remove(0)
    }
}
