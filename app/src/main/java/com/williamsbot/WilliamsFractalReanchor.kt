package com.williamsbot
import org.json.JSONArray
import org.json.JSONObject

/**
 * Choose the most recently CONFIRMED same-side TC2 WM3 pivot after a baseline
 * H1 signal. A newer pivot may already have triggered: this fact blocks a
 * NEW entry through the independent raw-H1 consumption check; it is NOT
 * permission to return to an older still-untouched lower/higher fractal.
 *
 * Caller supplies confirmed H1 candidate facts. This is selection only;
 * it neither places orders nor asserts Binance fill/position state.
 */
internal fun latestConfirmedTc2FractalId(
    direction:String, baselineSignalAt:Long, signals:JSONArray, now:Long
):String? {
    val type=when(direction) { "LONG"->"FRACTAL";"SHORT"->"SHORT_FRACTAL";else->return null }
    if(baselineSignalAt<=0 || now<=0) return null
    return CockpitDiagnostics.rows(signals).filter { s ->
        val signalAt=s.optLong("signal_bar_time_ms")
        val pivotAt=s.optLong("formation_at_ms")
        val trigger=s.optDouble("trigger_price",Double.NaN)
        val stop=s.optDouble("protective_price",Double.NaN)
        s.optString("type")==type &&
            s.optString("strategy_profile")==williamsTc2Profile &&
            s.optString("role")=="ENTRY" && s.optString("execution_semantics")=="STOP_ENTRY" &&
            s.optString("signal_id").isNotBlank() &&
            signalAt>baselineSignalAt && signalAt>pivotAt && pivotAt>0L &&
            signalAt+WILLIAMS_H1_MS<=now &&
            trigger.isFinite() && trigger>0.0 && stop.isFinite() && stop>0.0 &&
            (if(direction=="LONG") stop<trigger else stop>trigger)
    }.maxWithOrNull(compareBy<JSONObject> { it.optLong("formation_at_ms") }
        .thenBy { it.optLong("signal_bar_time_ms") })?.optString("signal_id")?.takeIf { it.isNotBlank() }
}

/**
 * Find a newer, more favorable confirmed WM3 pivot to revoke a resting old
 * STOP entry. This also deliberately returns a candidate when price has
 * ALREADY crossed the newer level: the old exchange order must be reconciled
 * and canceled; the newer signal must NEVER be blindly submitted behind price.
 * The entry door independently checks the complete raw/closed H1 breakout
 * history and a fresh Binance snapshot before any later submission.
 */
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
    val oldPivot=if(pending.optString("signal_type").removePrefix("SHORT_")=="FRACTAL")
        oldTime-7_200_000L else oldTime
    val oldPrice=pending.optString("entry_price").toDoubleOrNull() ?: return null
    if(oldTime<=0 || !oldPrice.isFinite() || oldPrice<=0) return null
    val type=if(side=="LONG") "FRACTAL" else "SHORT_FRACTAL"
    return CockpitDiagnostics.rows(signals).filter { s ->
        val at=s.optLong("signal_bar_time_ms")
        val pivot=s.optLong("formation_at_ms")
        val price=s.optDouble("trigger_price",Double.NaN)
        val stop=s.optDouble("protective_price",Double.NaN)
        s.optString("type")==type &&
        s.optString("strategy_profile")==williamsTc2Profile &&
        s.optString("role")=="ENTRY" && s.optString("execution_semantics")=="STOP_ENTRY" &&
        s.optString("signal_id").isNotBlank() &&
        s.optString("signal_id")!=pending.optString("signal_id") &&
        at>oldTime && pivot in (oldPivot+1L) until at &&
        now>=at+WILLIAMS_H1_MS &&
        price.isFinite() && stop.isFinite() &&
        (if(side=="LONG") price<oldPrice-tick/2 &&
             stop>0 && stop<price
         else price>oldPrice+tick/2 && stop>price)
    }.maxWithOrNull(compareBy<JSONObject> { it.optLong("formation_at_ms") }
        .thenBy { it.optLong("signal_bar_time_ms") })?.let { newer ->
            JSONObject(newer.toString()).put("new_trigger_already_crossed_at_quote",
                if(side=="LONG") mark>=newer.optDouble("trigger_price") else mark<=newer.optDouble("trigger_price"))
        }
}
