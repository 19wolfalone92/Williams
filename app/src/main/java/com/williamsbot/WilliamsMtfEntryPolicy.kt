package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal const val WILLIAMS_H1_MS = 3_600_000L
internal const val WILLIAMS_M15_MS = 900_000L
internal const val WILLIAMS_MTF_ENTRY_MODE = "H1_CONTEXT_M15_FRACTAL"

/** User-selected execution refinement, not an additional canonical Williams entry family. */
internal data class WilliamsH1Parent(
    val signalId: String, val direction: String, val signalAt: Long,
    val trigger: Double, val invalidation: Double,
    val expiresAt: Long = signalAt + 5 * WILLIAMS_H1_MS
) {
    val knownAt: Long get() = signalAt + WILLIAMS_H1_MS
    fun toJson(): JSONObject = JSONObject().put("signal_id",signalId).put("direction",direction)
        .put("signal_bar_time_ms",signalAt).put("known_at_ms",knownAt)
        .put("expires_at_ms",expiresAt).put("trigger",trigger).put("invalidation",invalidation)
}

internal data class WilliamsM15Entry(
    val parent: WilliamsH1Parent, val pivotAt: Long, val confirmedAt: Long,
    val trigger: Double, val stop: Double, val teeth: Double, val tick: Double
) {
    fun toJson(): JSONObject = JSONObject().put("mode",WILLIAMS_MTF_ENTRY_MODE)
        .put("parent",parent.toJson()).put("execution_frame","15m")
        .put("direction",parent.direction).put("pivot_at_ms",pivotAt)
        .put("confirmed_at_ms",confirmedAt).put("trigger",trigger).put("protective_stop",stop)
        .put("teeth",teeth).put("tick",tick)
    fun clientId(symbol:String):String {
        val key=parent.signalId+":"+pivotAt
        val hash=MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .take(10).joinToString("") { "%02x".format(it.toInt() and 255) }
        return "W2E_"+symbol.take(8)+"_M15_"+hash.take(18)
    }
}

internal data class WilliamsM15Decision(val reason:String,val entry:WilliamsM15Entry?=null)

internal fun williamsM15EntryFromJson(proof:JSONObject):WilliamsM15Entry? = runCatching {
    val p=proof.getJSONObject("parent")
    val parent=WilliamsH1Parent(p.getString("signal_id"),p.getString("direction"),p.getLong("signal_bar_time_ms"),
        p.getDouble("trigger"),p.getDouble("invalidation"),p.getLong("expires_at_ms"))
    WilliamsM15Entry(parent,proof.getLong("pivot_at_ms"),proof.getLong("confirmed_at_ms"),
        proof.getDouble("trigger"),proof.getDouble("protective_stop"),proof.getDouble("teeth"),proof.getDouble("tick"))
}.getOrNull()

/** Revalidate an already-armed level even if a newer, farther fractal now exists. */
internal fun williamsPendingM15Invalidation(entry:WilliamsM15Entry,bars:List<WilliamsWaveBar>,teeth:List<Double>,
    mark:Double,now:Long):String? {
    williamsH1ParentInvalidation(entry.parent,bars,mark,now)?.let { return it }
    if(bars.size<45 || teeth.size!=bars.size || !teeth.last().isFinite() ||
        bars.last().t+WILLIAMS_M15_MS>now || now-(bars.last().t+WILLIAMS_M15_MS)>2*WILLIAMS_M15_MS ||
        bars.zipWithNext().any { (a,b) -> b.t-a.t!=WILLIAMS_M15_MS }) return "M15_PENDING_HISTORY_UNVERIFIED"
    val long=entry.parent.direction=="LONG"
    if(if(long) entry.trigger<=teeth.last() else entry.trigger>=teeth.last()) return "M15_PENDING_TEETH_INVALIDATED"
    if((if(long) mark>=entry.trigger || mark<=entry.stop else mark<=entry.trigger || mark>=entry.stop) ||
        bars.filter { it.t>=entry.confirmedAt }.any {
            if(long) it.h>=entry.trigger || it.l<=entry.stop else it.l<=entry.trigger || it.h>=entry.stop
        }) return "M15_PENDING_LEVEL_ALREADY_TOUCHED"
    return null
}

internal fun williamsH1ParentInvalidation(
    parent:WilliamsH1Parent, bars:List<WilliamsWaveBar>, mark:Double, now:Long
):String? {
    if(parent.signalId.isBlank() || parent.direction !in setOf("LONG","SHORT") || parent.signalAt<=0 ||
        !parent.trigger.isFinite() || !parent.invalidation.isFinite() || parent.trigger<=0 || parent.invalidation<=0 ||
        parent.knownAt>now || parent.expiresAt<=parent.knownAt ||
        (if(parent.direction=="LONG") parent.invalidation>=parent.trigger else parent.invalidation<=parent.trigger))
        return "H1_PARENT_INVALID"
    if(now>=parent.expiresAt) return "H1_PARENT_EXPIRED"
    if(!mark.isFinite() || mark<=0) return "M15_MARK_UNAVAILABLE"
    val long=parent.direction=="LONG"
    if((if(long) mark<=parent.invalidation else mark>=parent.invalidation) ||
        bars.filter { it.t>=parent.knownAt }.any {
            if(long) it.l<=parent.invalidation else it.h>=parent.invalidation
        }) return "H1_PARENT_STRUCTURAL_INVALIDATION"
    return null
}

/** Only closed, contiguous M15 data; a pivot needs two CLOSED bars on its right. */
internal fun selectWilliamsH1M15Entry(
    parent:WilliamsH1Parent,bars:List<WilliamsWaveBar>,teeth:List<Double>,tick:Double,mark:Double,now:Long
):WilliamsM15Decision {
    fun deny(s:String)=WilliamsM15Decision(s)
    if(bars.size<45 || bars.size!=teeth.size || !tick.isFinite() || tick<=0 ||
        bars.any { it.t<=0 || it.t+WILLIAMS_M15_MS>now ||
            listOf(it.o,it.h,it.l,it.c).any { p -> !p.isFinite() || p<=0 } ||
            it.h<maxOf(it.o,it.c,it.l) || it.l>minOf(it.o,it.c,it.h) } ||
        bars.zipWithNext().any { (a,b) -> b.t-a.t!=WILLIAMS_M15_MS })
        return deny("M15_CLOSED_CONTIGUOUS_HISTORY_REQUIRED")
    if(now-(bars.last().t+WILLIAMS_M15_MS)>2*WILLIAMS_M15_MS) return deny("M15_HISTORY_STALE")
    williamsH1ParentInvalidation(parent,bars,mark,now)?.let { return deny(it) }
    val fractal=selectWilliamsPendingFractal(parent.direction,bars,teeth,tick)
        ?: return deny("WAIT_M15_CONFIRMED_FRACTAL")
    val pivot=bars[fractal.center].t
    val confirmed=bars[fractal.confirmation].t+WILLIAMS_M15_MS
    // A pre-existing junior pattern must not be presented as confirmation of a newer H1 setup.
    if(pivot<parent.signalAt || confirmed<=parent.knownAt) return deny("WAIT_M15_FRACTAL_AFTER_H1")
    val long=parent.direction=="LONG"
    if(if(long) mark>=fractal.trigger else mark<=fractal.trigger) return deny("M15_TRIGGER_ALREADY_CROSSED")
    if((if(long) fractal.protective<parent.invalidation || fractal.protective>=fractal.trigger
        else fractal.protective>parent.invalidation || fractal.protective<=fractal.trigger))
        return deny("M15_STOP_OUTSIDE_H1_SCENARIO")
    if(bars.filter { it.t>=confirmed }.any {
            if(long) it.l<=fractal.protective else it.h>=fractal.protective
        } || (if(long) mark<=fractal.protective else mark>=fractal.protective))
        return deny("M15_STRUCTURAL_STOP_ALREADY_INVALIDATED")
    return WilliamsM15Decision("M15_FRACTAL_CONFIRMED_WAIT_BREAKOUT",
        WilliamsM15Entry(parent,pivot,confirmed,fractal.trigger,fractal.protective,fractal.currentTeeth,tick))
}

/** A filled parent cannot start another initial campaign; confirmed zero-fill cancellations can retry. */
internal fun williamsH1ParentAlreadyFilled(intents:JSONArray,parentId:String):Boolean =
    (0 until intents.length()).mapNotNull { intents.optJSONObject(it) }.any {
        it.optString("intent_role","ENTRY")=="ENTRY" && it.optString("signal_id")==parentId &&
            (it.optLong("position_open_observed_at_ms")>0 ||
                (it.optString("confirmed_position_qty").toDoubleOrNull() ?: 0.0)>0 ||
                (it.optString("executed_qty").toDoubleOrNull() ?: 0.0)>0 ||
                it.optString("actual_entry_order_id").toLongOrNull()?.let { id -> id>0 }==true)
    }

/** Last-door schema/price/time check; fresh indicator and parent recomputation happens in Runtime. */
internal fun williamsMtfProofBlocker(setup:String,direction:String,parentId:String,entry:Double,stop:Double,now:Long):String? {
    val proof=runCatching { JSONObject(setup).getJSONObject("mtf_entry") }.getOrNull()
        ?: return "M15_FRACTAL_PROOF_REQUIRED"
    val p=proof.optJSONObject("parent") ?: return "M15_H1_PARENT_PROOF_REQUIRED"
    val parent=WilliamsH1Parent(p.optString("signal_id"),p.optString("direction"),
        p.optLong("signal_bar_time_ms"),p.optDouble("trigger",Double.NaN),
        p.optDouble("invalidation",Double.NaN),p.optLong("expires_at_ms"))
    val tick=proof.optDouble("tick",Double.NaN)
    val confirmed=proof.optLong("confirmed_at_ms")
    val pivot=proof.optLong("pivot_at_ms")
    if(proof.optString("mode")!=WILLIAMS_MTF_ENTRY_MODE || parent.signalId!=parentId ||
        proof.optString("execution_frame")!="15m" || direction!=parent.direction ||
        proof.optString("direction")!=direction || !tick.isFinite() || tick<=0 ||
        !entry.isFinite() || !stop.isFinite() || entry<=0 || stop<=0 ||
        confirmed<=parent.knownAt || confirmed>now || pivot<parent.signalAt ||
        confirmed-pivot<3*WILLIAMS_M15_MS || pivot%WILLIAMS_M15_MS!=0L ||
        confirmed%WILLIAMS_M15_MS!=0L ||
        kotlin.math.abs(proof.optDouble("trigger",Double.NaN)-entry)>tick*.1 ||
        kotlin.math.abs(proof.optDouble("protective_stop",Double.NaN)-stop)>tick*.1 ||
        !proof.optDouble("trigger",Double.NaN).isFinite() || !proof.optDouble("protective_stop",Double.NaN).isFinite() ||
        (if(direction=="LONG") stop<parent.invalidation || stop>=entry else stop>parent.invalidation || stop<=entry))
        return "M15_FRACTAL_PROOF_MISMATCH"
    return williamsH1ParentInvalidation(parent,emptyList(),entry,now)
}
