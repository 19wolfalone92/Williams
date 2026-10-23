package com.williamsbot

import org.json.JSONObject
import org.json.JSONArray
import java.security.MessageDigest
import kotlin.math.abs

/** Native H1 stop-entry for the selected Williams edition. No M15 confirmation.
 * TC2: WM1 above/below reversal bar; WM2 third Super AO price bar; WM3
 * confirmed H1 fractal. NTD/Course keep their own independently valid H1
 * signal families. This is NOT a cross-edition hybrid entry rule.
 */
internal const val WILLIAMS_BOOK_H1_ENTRY_MODE = "BOOK_NATIVE_H1_TRIGGER"

internal fun williamsBookH1ClientId(symbol:String,signalId:String,retryGeneration:Int=0):String {
    require(retryGeneration>=0)
    val identity="H1_BOOK|"+signalId+(if(retryGeneration==0) "" else "|CANCEL_RETRY|"+retryGeneration)
    val digest=MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray(Charsets.UTF_8))
        .take(11).joinToString("") { "%02x".format(it.toInt() and 255) }
    return "W2H_"+symbol.take(8)+"_"+digest
}

/** A new id is permitted only after exact terminal cancellation plus a fresh
 * exchange-flat observation. Unknown replies and any possible fill never retry.
 * The original signal identity remains pinned across all attempts. */
internal fun williamsBookH1RetryGeneration(intents:JSONArray,parentId:String):Int? {
    val previous=(0 until intents.length()).mapNotNull { intents.optJSONObject(it) }.filter {
        it.optString("intent_role","ENTRY")=="ENTRY" && it.optString("signal_id")==parentId }
    if(previous.isEmpty()) return 0
    if(williamsH1ParentAlreadyFilled(intents,parentId)) return null
    val terminal=setOf("CANCELED","CANCELLED","EXPIRED","EXPIRED_IN_MATCH","REJECTED")
    if(previous.any { row ->
        val child=row.optString("conditional_actual_order_id")
        campaignLifecycleStatus(row)!="CLOSED_OR_STOPPED" ||
            row.optString("cancel_reconciled_status") !in terminal || row.optLong("cancel_reconciled_at_ms")<=0L ||
            row.optString("position_observed_after_cancel").toBigDecimalOrNull()?.signum()!=0 ||
            row.optLong("fill_observed_at_ms")>0L || (child.isNotBlank() && child!="0") ||
            listOf("executed_qty","actual_entry_executed_qty","confirmed_position_qty").any { key ->
                row.has(key) && row.optString(key).toBigDecimalOrNull()?.signum()!=0 }
    }) return null
    return previous.size
}

/** Signed by the decision engine, independently checked by the Futures
 * coordinator and refreshed against CLOSED H1 bars before Binance POST.
 * Entry must remain on the book signal price, never an M15 substitute.
 */
internal fun williamsBookH1Proof(
    signalId:String, direction:String,signalType:String,source:String,
    signalAtMs:Long,bookTrigger:Double,bookStop:Double,
    tick:Double,entryClientId:String="",campaignStop:Double=bookStop
):JSONObject = JSONObject()
    .put("mode",WILLIAMS_BOOK_H1_ENTRY_MODE).put("frame","1h")
    .put("signal_id",signalId).put("direction",direction)
    .put("signal_type",signalType).put("source",source)
    .put("trigger_price_basis","CONTRACT_PRICE")
    .put("signal_bar_time_ms",signalAtMs)
    .put("known_at_ms",signalAtMs+WILLIAMS_H1_MS)
    .put("expires_at_ms",williamsH1SignalExpiry(signalAtMs,signalType,source))
    .put("validity_policy",if(williamsStructuralFractal(signalType,source)) "UNBROKEN_UNSUPERSEDED_FRACTAL" else "PROJECT_AGE_WINDOW")
    .put("book_trigger",bookTrigger).put("book_stop",bookStop)
    .put("intent_role",if(entryClientId.isBlank()) "ENTRY" else "ADD_ON")
    .put("entry_client_id",entryClientId).put("campaign_stop",campaignStop)
    .put("tick",tick)

/** Structural, identity and price schema checks; NOT a replacement for
 * fresh H1 evidence from the native Runtime at the exchange mutation door.
 */
internal fun williamsBookH1ProofBlocker(
    setup:String,direction:String,signalId:String,signalType:String,source:String,
    entry:Double,stop:Double,now:Long,entryClientId:String=""
):String? {
    val proof=runCatching { JSONObject(setup).getJSONObject("book_h1_entry") }.getOrNull()
        ?:return "H1_BOOK_PROOF_REQUIRED"
    val type=proof.optString("signal_type")
    val proofDirection=proof.optString("direction")
    val proofSource=proof.optString("source")
    val start=proof.optLong("signal_bar_time_ms")
    val known=proof.optLong("known_at_ms")
    val expires=proof.optLong("expires_at_ms")
    val tick=proof.optDouble("tick",Double.NaN)
    val bookTrigger=proof.optDouble("book_trigger",Double.NaN)
    val bookStop=proof.optDouble("book_stop",Double.NaN)
    val addition=entryClientId.isNotBlank()
    if(proof.optString("intent_role","ENTRY")!=(if(addition) "ADD_ON" else "ENTRY") ||
        proof.optString("entry_client_id")!=entryClientId ||
        addition && (source!=williamsTc2Profile ||
            signalType.removePrefix("SHORT_").removePrefix("LONG_") !in setOf("SUPER_AO","FRACTAL") ||
            !proof.optDouble("campaign_stop",Double.NaN).isFinite() ||
            abs(proof.optDouble("campaign_stop")-stop)>tick*.11))
        return "H1_BOOK_CAMPAIGN_PROOF_MISMATCH"
    if(proof.optString("mode")!=WILLIAMS_BOOK_H1_ENTRY_MODE ||
        proof.optString("trigger_price_basis")!="CONTRACT_PRICE" ||
        proof.optString("frame")!="1h" ||
        proof.optString("signal_id")!=signalId || signalId.isBlank() ||
        proofDirection!=direction || direction !in setOf("LONG","SHORT") ||
        type!=signalType || type.isBlank() ||
        proofSource!=source || source !in williamsSourceProfiles ||
        start<=0 || start%WILLIAMS_H1_MS!=0L ||
        known!=start+WILLIAMS_H1_MS || expires!=williamsH1SignalExpiry(start,type,source) ||
        proof.optString("validity_policy")!=(if(williamsStructuralFractal(type,source)) "UNBROKEN_UNSUPERSEDED_FRACTAL" else "PROJECT_AGE_WINDOW") ||
        !williamsH1SignalTimeEligible(start,type,source,now) ||
        !tick.isFinite() || tick<=0 ||
        listOf(bookTrigger,bookStop,entry,stop).any { !it.isFinite() || it<=0 } ||
        abs(bookTrigger-entry)>tick*.11 ||
        (if(direction=="LONG") bookStop>=bookTrigger || stop>=entry || !addition && stop<bookStop-tick*.11
         else bookStop<=bookTrigger || stop<=entry || !addition && stop>bookStop+tick*.11)) return "H1_BOOK_PROOF_MISMATCH_OR_EXPIRED"
    // Only Course may tighten the original stop using its independent higher
    // timeframe Air Bag. Other editions must keep the source signal's stop.
    // Additions use the existing campaign stop, independently checked against
    // its protected owner by the coordinator, rather than resetting that stop
    // to the new signal bar. The original book stop remains evidence only.
    if(!addition && source!=williamsCourseProfile && abs(stop-bookStop)>tick*.11)
        return "H1_BOOK_STOP_CHANGED"
    return null
}

/** Exchange stop orders cannot ask the Android scanner to revalidate Teeth
 * synchronously at the trigger. Only arm an H1 fractal when the RED line is
 * already correctly positioned; cancel pending orders upon invalidation.
 * The original book rule concerns the line at activation.
 */
internal fun williamsBookFractalTeethBlocker(
    signalType:String,direction:String,entry:Double,teeth:Double,tick:Double=0.0
):String? {
    if(signalType.removePrefix("SHORT_").removePrefix("LONG_")!="FRACTAL") return null
    if(!entry.isFinite() || entry<=0 || !teeth.isFinite() || teeth<=0 || !tick.isFinite() || tick<0)
        return "H1_FRACTAL_TEETH_UNVERIFIED"
    // TC2 chapter 11, PDF pp. 147-149: the breakout itself must occur
    // beyond the RED Teeth at activation, not the formation-time pivot.
    // The older rule (entry -/+ tick vs Teeth) silently imposed a stricter,
    // non-book filter and rejected genuine one-tick breakouts.
    return when(direction) {
        "LONG" -> if(entry>teeth) null else "H1_FRACTAL_NOT_ABOVE_TEETH"
        "SHORT" -> if(entry<teeth) null else "H1_FRACTAL_NOT_BELOW_TEETH"
        else -> "H1_FRACTAL_DIRECTION_INVALID"
    }
}

/** The red line displaced by five bars beside the forming H1 bar is already
 * known from CLOSED prices. Using the last closed bar's displayed line would
 * lag the actual activation check by one H1 bar. No forming OHLC is needed. */
internal fun williamsBookH1ActivationTeeth(closedBars:List<WilliamsWaveBar>,now:Long):Double {
    if(williamsClosedH1HistoryBlocker(closedBars,now)!=null || !validWilliamsEditionBars(closedBars))
        return Double.NaN
    return smmaSeededValues(closedBars.map { (it.h+it.l)/2.0 },8)
        .getOrNull(closedBars.size-5)?.takeIf { it.isFinite() && it>0 } ?: Double.NaN
}

/** Do not re-submit a late H1 entry if its original breakout happened after
 * the signal was known, even if the mark later retraced behind the trigger.
 * Accept open H1 bars only for this historical-consumption veto, never for
 * computing a NEW entry signal.
 */
internal fun williamsBookH1TriggerConsumed(
    direction:String,signalAtMs:Long,trigger:Double,
    observedAtMs:Long,bars:List<WilliamsWaveBar>
):Boolean {
    if(direction !in setOf("LONG","SHORT") || signalAtMs<=0L ||
        !trigger.isFinite() || trigger<=0 || observedAtMs<signalAtMs+WILLIAMS_H1_MS)
        return true
    if(bars.isEmpty() || bars.any { it.t<0 || it.t>observedAtMs ||
            !it.h.isFinite() || !it.l.isFinite() || it.l<=0 || it.h<it.l } ||
        bars.first().t>signalAtMs+WILLIAMS_H1_MS ||
        bars.zipWithNext().any { (a,b) -> b.t-a.t!=WILLIAMS_H1_MS }) return true
    return bars.any { b -> b.t>=signalAtMs+WILLIAMS_H1_MS &&
        (if(direction=="LONG") b.h>=trigger else b.l<=trigger) }
}

/** Include the fresh forming H1 bar only as evidence that a trigger was already
 * touched. It must never be used to form WM1/WM2/WM3 or their Alligator lines. */
internal fun williamsBookH1ObservedBreakoutBlocker(
    direction:String,signalAtMs:Long,trigger:Double,observedAtMs:Long,
    closedBars:List<WilliamsWaveBar>,recentBars:List<WilliamsWaveBar>
):String? {
    if(observedAtMs<=0 || recentBars.isEmpty() || recentBars.any { b ->
            b.t<=0 || b.t%WILLIAMS_H1_MS!=0L || b.t>observedAtMs ||
                listOf(b.o,b.h,b.l,b.c).any { !it.isFinite() || it<=0 } ||
                b.h<maxOf(b.o,b.l,b.c) || b.l>minOf(b.o,b.c)
        } || recentBars.zipWithNext().any { (a,b) -> b.t-a.t!=WILLIAMS_H1_MS } ||
        recentBars.last().t!=observedAtMs/WILLIAMS_H1_MS*WILLIAMS_H1_MS)
        return "H1_CURRENT_BAR_OBSERVATION_UNVERIFIED"
    val merged=(closedBars+recentBars).associateBy { it.t }.values.sortedBy { it.t }
    return if(williamsBookH1TriggerConsumed(direction,signalAtMs,trigger,observedAtMs,merged))
        "H1_BOOK_BREAKOUT_WAS_ALREADY_CONSUMED_OR_HISTORY_UNVERIFIED" else null
}
