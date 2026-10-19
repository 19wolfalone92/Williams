package com.williamsbot
import org.json.JSONArray
import org.json.JSONObject

/** Read-only proof of a fresher confirmed WM3 level; never sends an order. */
internal fun confirmedBetterFractal(pending:JSONObject, signals:JSONArray, mark:Double, tick:Double, now:Long):JSONObject? {
    if(!mark.isFinite() || mark<=0 || !tick.isFinite() || tick<=0 || now<=0) return null
    if(pending.optString("intent_role","ENTRY")!="ENTRY" || pending.optString("kind")!="STOP" ||
        pending.optString("strategy_profile")!=williamsTc2Profile ||
        campaignLifecycleStatus(pending)!="ENTRY_PENDING" ||
        exchangeOrderStatus(pending) !in setOf("NEW","WORKING","PENDING","ACCEPTED")) return null
    val side=pending.optString("direction")
    if(side!="LONG" && side!="SHORT") return null
    if(pending.optString("signal_type").removePrefix("SHORT_") !in setOf("REVERSAL","SUPER_AO","FRACTAL")) return null
    val oldTime=pending.optLong("signal_bar_time_ms")
    val oldPrice=pending.optString("entry_price").toDoubleOrNull() ?: return null
    if(oldTime<=0 || !oldPrice.isFinite() || oldPrice<=0) return null
    val type=if(side=="LONG") "FRACTAL" else "SHORT_FRACTAL"
    return CockpitDiagnostics.rows(signals).filter { it.optString("type")==type &&
        it.optString("strategy_profile")==williamsTc2Profile &&
        it.optString("role")=="ENTRY" && it.optString("execution_semantics")=="STOP_ENTRY" &&
        it.optString("signal_id").isNotBlank() &&
        it.optString("signal_id")!=pending.optString("signal_id") &&
        it.optLong("signal_bar_time_ms")>oldTime &&
        it.optLong("formation_at_ms") in 1L until it.optLong("signal_bar_time_ms") &&
        now>=it.optLong("signal_bar_time_ms")+3_600_000L &&
        now-it.optLong("signal_bar_time_ms")<=18_000_000L &&
        it.optDouble("trigger_price",Double.NaN).isFinite() &&
        it.optDouble("protective_price",Double.NaN).isFinite() &&
        (if(side=="LONG") it.optDouble("trigger_price")>mark+tick/2 &&
             it.optDouble("trigger_price")<oldPrice-tick/2 &&
             it.optDouble("protective_price")>0 && it.optDouble("protective_price")<it.optDouble("trigger_price")
         else it.optDouble("trigger_price")<mark-tick/2 &&
             it.optDouble("trigger_price")>oldPrice+tick/2 &&
             it.optDouble("protective_price")>it.optDouble("trigger_price")) }
        .maxByOrNull { it.optLong("signal_bar_time_ms") }
}
