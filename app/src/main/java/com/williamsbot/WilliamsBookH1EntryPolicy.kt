package com.williamsbot

import org.json.JSONObject
import java.security.MessageDigest
import kotlin.math.abs

/** Native H1 stop-entry for the selected Williams edition. No M15 confirmation.
 * TC2: WM1 above/below reversal bar; WM2 third Super AO price bar; WM3
 * confirmed H1 fractal. NTD/Course keep their own independently valid H1
 * signal families. This is NOT a cross-edition hybrid entry rule.
 */
internal const val WILLIAMS_BOOK_H1_ENTRY_MODE = "BOOK_NATIVE_H1_TRIGGER"

internal fun williamsBookH1ClientId(symbol:String,signalId:String):String {
    val digest=MessageDigest.getInstance("SHA-256")
        .digest(("H1_BOOK|"+signalId).toByteArray(Charsets.UTF_8))
        .take(11).joinToString("") { "%02x".format(it.toInt() and 255) }
    return "W2H_"+symbol.take(8)+"_"+digest
}

/** Signed by the decision engine, independently checked by the Futures
 * coordinator and refreshed against CLOSED H1 bars before Binance POST.
 * Entry must remain on the book signal price, never an M15 substitute.
 */
internal fun williamsBookH1Proof(
    signalId:String, direction:String,signalType:String,source:String,
    signalAtMs:Long,bookTrigger:Double,bookStop:Double,
    tick:Double
):JSONObject = JSONObject()
    .put("mode",WILLIAMS_BOOK_H1_ENTRY_MODE).put("frame","1h")
    .put("signal_id",signalId).put("direction",direction)
    .put("signal_type",signalType).put("source",source)
    .put("signal_bar_time_ms",signalAtMs)
    .put("known_at_ms",signalAtMs+WILLIAMS_H1_MS)
    .put("expires_at_ms",signalAtMs+5*WILLIAMS_H1_MS)
    .put("book_trigger",bookTrigger).put("book_stop",bookStop)
    .put("tick",tick)

/** Structural, identity and price schema checks; NOT a replacement for
 * fresh H1 evidence from the native Runtime at the exchange mutation door.
 */
internal fun williamsBookH1ProofBlocker(
    setup:String,direction:String,signalId:String,signalType:String,source:String,
    entry:Double,stop:Double,now:Long
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
    if(proof.optString("mode")!=WILLIAMS_BOOK_H1_ENTRY_MODE ||
        proof.optString("frame")!="1h" ||
        proof.optString("signal_id")!=signalId || signalId.isBlank() ||
        proofDirection!=direction || direction !in setOf("LONG","SHORT") ||
        type!=signalType || type.isBlank() ||
        proofSource!=source || source !in williamsSourceProfiles ||
        start<=0 || start%WILLIAMS_H1_MS!=0L ||
        known!=start+WILLIAMS_H1_MS || expires!=start+5*WILLIAMS_H1_MS ||
        known>now || now>=expires ||
        !tick.isFinite() || tick<=0 ||
        listOf(bookTrigger,bookStop,entry,stop).any { !it.isFinite() || it<=0 } ||
        abs(bookTrigger-entry)>tick*.11 ||
        (if(direction=="LONG") bookStop>=bookTrigger || stop>=entry || stop<bookStop-tick*.11
         else bookStop<=bookTrigger || stop<=entry || stop>bookStop+tick*.11)) return "H1_BOOK_PROOF_MISMATCH_OR_EXPIRED"
    // Only Course may tighten the original stop using its independent higher
    // timeframe Air Bag. Other editions must keep the source signal's stop.
    if(source!=williamsCourseProfile && abs(stop-bookStop)>tick*.11)
        return "H1_BOOK_STOP_CHANGED"
    return null
}

/** Exchange stop orders cannot ask the Android scanner to revalidate Teeth
 * synchronously at the trigger. Only arm an H1 fractal when the RED line is
 * already correctly positioned; cancel pending orders upon invalidation.
 * The original book rule concerns the line at activation.
 */
internal fun williamsBookFractalTeethBlocker(
    signalType:String,direction:String,entry:Double,teeth:Double
):String? {
    if(signalType.removePrefix("SHORT_").removePrefix("LONG_")!="FRACTAL") return null
    if(!entry.isFinite() || entry<=0 || !teeth.isFinite() || teeth<=0)
        return "H1_FRACTAL_TEETH_UNVERIFIED"
    return when(direction) {
        "LONG" -> if(entry>teeth) null else "H1_FRACTAL_NOT_ABOVE_TEETH"
        "SHORT" -> if(entry<teeth) null else "H1_FRACTAL_NOT_BELOW_TEETH"
        else -> "H1_FRACTAL_DIRECTION_INVALID"
    }
}
