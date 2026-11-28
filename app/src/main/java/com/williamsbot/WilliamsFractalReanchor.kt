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
 * Retire a confirmed-cancel re-anchor fence only when market structure proves
 * that its exact replacement is no longer actionable. TC2 signals have no
 * arbitrary wall-clock expiry: an unknown or incomplete history keeps the
 * fence in place, while a newer confirmed WM3, structural invalidation, or
 * already-consumed trigger retires it immediately.
 */
internal fun tc2ReanchorTargetRetirementReason(
    direction:String,
    signalId:String,
    signalAt:Long,
    trigger:Double,
    protective:Double,
    closedH1Bars:List<WilliamsWaveBar>,
    now:Long,
    newerConfirmedSignalId:String?=null
):String? {
    if(!newerConfirmedSignalId.isNullOrBlank() && newerConfirmedSignalId!=signalId)
        return "REANCHOR_TARGET_SUPERSEDED_BY_NEWER_CONFIRMED_WM3"
    if(direction !in setOf("LONG","SHORT") || signalId.isBlank() || signalAt<=0L || now<=0L ||
        !trigger.isFinite() || trigger<=0.0 || !protective.isFinite() || protective<=0.0 ||
        (if(direction=="LONG") protective>=trigger else protective<=trigger))
        return null
    if(williamsClosedH1HistoryBlocker(closedH1Bars,now)!=null ||
        !validWilliamsEditionBars(closedH1Bars) ||
        closedH1Bars.firstOrNull()?.t?.let { it<=signalAt+WILLIAMS_H1_MS }!=true)
        return null
    val parent=WilliamsH1Parent(signalId,direction,signalAt,trigger,protective,Long.MAX_VALUE)
    val invalidation=williamsH1ParentInvalidation(parent,closedH1Bars,closedH1Bars.last().c,now)
    if(invalidation=="H1_PARENT_STRUCTURAL_INVALIDATION")
        return "REANCHOR_TARGET_STRUCTURALLY_INVALIDATED"
    if(williamsBookH1TriggerConsumed(direction,signalAt,trigger,now,closedH1Bars))
        return "REANCHOR_TARGET_TRIGGER_ALREADY_CONSUMED"
    return null
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

/** Read-only pre-cancel proof. A newer formation is not authorization to
 * destroy an existing resting order. The exact same source/price proof must
 * pass again after the exchange confirms cancellation; this is not atomic.
 */
internal fun tc2ReanchorMarketBlocker(
    old:JSONObject, replacement:JSONObject, contract:Double,
    closed:List<WilliamsWaveBar>, recent:List<WilliamsWaveBar>, tick:Double, now:Long
):String? {
    val direction=old.optString("direction")
    val oldTrigger=old.optString("entry_price").toDoubleOrNull() ?: return "OLD_TRIGGER_UNVERIFIED"
    val at=replacement.optLong("signal_bar_time_ms")
    val trigger=replacement.optDouble("trigger_price",Double.NaN)
    val stop=replacement.optDouble("protective_price",Double.NaN)
    if(direction !in setOf("LONG","SHORT") || listOf(oldTrigger,contract,trigger,stop,tick).any { !it.isFinite() || it<=0 })
        return "REANCHOR_PRICE_PROOF_UNVERIFIED"
    val long=direction=="LONG"
    if(if(long) contract>=oldTrigger else contract<=oldTrigger)
        return "REANCHOR_OLD_ORDER_TRIGGER_CROSSED_RECONCILE"
    if(if(long) contract>=trigger || contract<=stop || stop>=trigger
       else contract<=trigger || contract>=stop || stop<=trigger)
        return "REANCHOR_NEW_LEVEL_NOT_ACTIONABLE"
    if(williamsClosedH1HistoryBlocker(closed,now)!=null || !validWilliamsEditionBars(closed))
        return "REANCHOR_CLOSED_HISTORY_UNVERIFIED"
    if(williamsBookH1ObservedBreakoutBlocker(direction,old.optLong("signal_bar_time_ms"),
            oldTrigger,now,closed,recent)!=null)
        return "REANCHOR_OLD_ORDER_TRIGGER_OR_HISTORY_UNVERIFIED"
    val parent=WilliamsH1Parent(replacement.optString("signal_id"),direction,at,trigger,stop,Long.MAX_VALUE)
    williamsH1ParentInvalidation(parent,closed,contract,now)?.let { return it }
    williamsBookH1ObservedBreakoutBlocker(direction,at,trigger,now,closed,recent)?.let { return it }
    return williamsBookFractalTeethBlocker(replacement.optString("type"),direction,trigger,
        williamsBookH1ActivationTeeth(closed,now),tick)
}
