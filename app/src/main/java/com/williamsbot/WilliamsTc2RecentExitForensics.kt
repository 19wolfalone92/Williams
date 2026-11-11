package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

/**
 * Compact, read-only human explanation of a real recorded exchange close.
 * It does not infer future trend outcomes, does not re-open an exited trade,
 * and never grants risk/STOP/Execution Door authority.
 */
internal object WilliamsTc2RecentExitForensics {
    fun summarize(trades: JSONArray?): JSONObject {
        val out = JSONArray()
        if (trades != null) for (i in 0 until trades.length()) {
            if (out.length() >= 10) break
            val trade = trades.optJSONObject(i) ?: continue
            if (trade.optString("status") != "CLOSED") continue
            val reason = trade.optString("exit_reason", "UNVERIFIED")
            val context = trade.optJSONObject("entry_snapshot")
                ?.optJSONObject("edition_management")
                ?.optJSONObject("exit_evidence")
            val observedReason = context?.optString("reason").orEmpty()
            val agreement = observedReason.isNotBlank() && observedReason == reason
            val evidence = context?.optJSONObject("opposition_check")
            val opponent = evidence?.optJSONObject("opposition")
            val lifecycle = evidence?.optJSONObject("opposition_revalidation")
            val triggered = opponent?.optJSONObject("event")?.optString("state") == "TRIGGERED"
            val verifiedOpposition = reason in setOf(
                "OPPOSITE_WILLIAMS_LEVEL_TRIGGERED", "OPPOSITE_WILLIAMS_TRIGGER_IN_HIGHER_CORRECTION"
            ) && agreement && triggered && lifecycle?.optString("status") == "FRESH_CONFIRMED_OPPOSITE"
            val reasonRu = when {
                verifiedOpposition -> "Записан выход по сработавшему противоположному H1 Wise Man; это не подтверждает разворот старшего тренда."
                reason.startsWith("OPPOSITE_") -> "В журнале указан встречный сигнал, но полная H1 цепочка причины здесь не подтверждена."
                reason == "EXCHANGE_RECONCILED_CLOSE" -> "Биржа подтвердила закрытие; точная первичная причина не восстановлена этим наблюдением."
                else -> "Причина из биржевого журнала; проверка окончания тренда отдельно не выполнена."
            }
            val entry = trade.optDouble("entry_price",Double.NaN)
            val exit = trade.optDouble("exit_price",Double.NaN)
            val quantity = trade.optDouble("qty",Double.NaN)
            val trigger = opponent?.optDouble("trigger",Double.NaN) ?: Double.NaN
            val contract = evidence?.optDouble("contract_price",Double.NaN) ?: Double.NaN
            out.put(JSONObject()
                .put("symbol",trade.optString("symbol"))
                .put("direction",trade.optString("direction"))
                .put("opened_at_ms",trade.optLong("opened_at"))
                .put("closed_at_ms",trade.optLong("closed_at"))
                .put("entry_price",if(entry.isFinite()) entry else JSONObject.NULL)
                .put("exit_price",if(exit.isFinite()) exit else JSONObject.NULL)
                .put("quantity",if(quantity.isFinite()) quantity else JSONObject.NULL)
                .put("net_pnl_usdt",trade.opt("net_pnl_usdt") ?: JSONObject.NULL)
                .put("recorded_exit_reason",reason)
                .put("source_evidence_agrees",agreement)
                .put("opposite_h1_signal_confirmed_in_saved_evidence",verifiedOpposition)
                .put("opposite_signal_type",opponent?.optString("signal_id")
                    ?.split(":")?.getOrNull(2) ?: JSONObject.NULL)
                .put("opposite_trigger",if(trigger.isFinite()) trigger else JSONObject.NULL)
                .put("observed_contract_price",if(contract.isFinite()) contract else JSONObject.NULL)
                .put("closed_h1_after_first_trigger",
                    lifecycle?.optInt("closed_h1_after_first_trigger",-1)?.takeIf { it>=0 } ?: JSONObject.NULL)
                .put("why_closed_ru",reasonRu)
                .put("higher_trend_reversal_independently_proved",false)
                .put("can_override_exit_or_stop",false))
        }
        return JSONObject()
            .put("schema","WILLIAMS_TC2_EXIT_FORENSICS_V1")
            .put("recent_closed_trades",out)
            .put("trade_count",out.length())
            .put("read_only",true)
            .put("trading_authority",false)
            .put("source_note","Reconstructs recorded exit_evidence; not a counterfactual replay, TC2 proof-of-correctness or forecast.")
    }
}
