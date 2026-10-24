package com.williamsbot

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

internal val williamsNtdFamilies=setOf("FRACTAL","AO_SAUCER","AO_ZERO_LINE","AO_TWIN_PEAKS",
    "AC_SIGNAL","ZONE_ADD_ON","BALANCE_LINE")
internal const val WILLIAMS_NTD_CLOSE_MAX_DEVIATION=.0015
internal val williamsNtdBookExitReasons=setOf("TEETH_CLOSE_EXIT","LIPS_CLOSE_EXIT","NTD_FIVE_ZONE_PROTECTION_CROSSED",
    "NTD_OPPOSITE_SIGNAL_TRIGGERED","OPPOSITE_WILLIAMS_LEVEL_TRIGGERED","OPPOSITE_WILLIAMS_TRIGGER_IN_HIGHER_CORRECTION")

/** NTD PDF98: zone additions execute at the close, never on a fictitious
 * pending breakout. Reserve risk at a bounded adverse execution price. */
internal fun williamsNtdCloseEntryBound(direction:String,reference:Double,tick:Double):Double? {
    if(direction !in setOf("LONG","SHORT") || !reference.isFinite() || reference<=0 ||
        !tick.isFinite() || tick<=0) return null
    val factor=if(direction=="LONG") 1+WILLIAMS_NTD_CLOSE_MAX_DEVIATION else 1-WILLIAMS_NTD_CLOSE_MAX_DEVIATION
    return BigDecimal.valueOf(reference).multiply(BigDecimal.valueOf(factor))
        .divide(BigDecimal.valueOf(tick),0,if(direction=="LONG") RoundingMode.FLOOR else RoundingMode.CEILING)
        .multiply(BigDecimal.valueOf(tick)).toDouble().takeIf { it>0 &&
            (if(direction=="LONG") it>=reference else it<=reference) }
}

internal fun williamsNtdCloseEntryBlocker(direction:String,signalAt:Long,reference:Double,bound:Double,
    now:Long,contract:Double,mark:Double,bid:Double,ask:Double,tick:Double):String? {
    val expected=williamsNtdCloseEntryBound(direction,reference,tick)
        ?:return "NTD_CLOSE_BOUND_UNVERIFIED"
    if(!bound.isFinite() || abs(bound-expected)>tick*.11) return "NTD_CLOSE_BOUND_CHANGED"
    for(price in listOf(contract,mark)) {
        williamsMarketOnCloseReason(signalAt,WILLIAMS_H1_MS,now,reference,price)?.let { return it }
    }
    if(listOf(bid,ask).any { !it.isFinite() || it<=0 } || ask<bid)
        return "NTD_CLOSE_EXECUTABLE_QUOTE_UNVERIFIED"
    val executable=if(direction=="LONG") ask else bid
    if(abs(executable/reference-1)>WILLIAMS_NTD_CLOSE_MAX_DEVIATION ||
        (if(direction=="LONG") executable>bound else executable<bound)) return "NTD_CLOSE_EXECUTABLE_QUOTE_OUTSIDE_BOUND"
    return null
}

/** A detector's ADD_ON may never become an initial entry merely because the
 * account is flat. Only a fully authenticated profitable NTD cycle can grant
 * that exception; its durable owner is independently checked by coordinator. */
internal fun williamsBookSignalRoleAllowed(source:String,type:String,role:String,
    entryOwner:String="",reentryOwner:String=""):Boolean {
    val family=type.removePrefix("SHORT_").removePrefix("LONG_")
    if(role !in setOf("ENTRY","ADD_ON")) return false
    if(source!=williamsNtdProfile) return role=="ENTRY" || entryOwner.isNotBlank() &&
        source==williamsTc2Profile && family in setOf("SUPER_AO","FRACTAL")
    if(family !in williamsNtdFamilies) return false
    return if(entryOwner.isNotBlank() || reentryOwner.isNotBlank()) true else family=="FRACTAL" && role=="ENTRY"
}

internal fun williamsNtdFirstEntryBlocker(setup:String,type:String,reentryOwner:String=""):String? {
    if(reentryOwner.isNotBlank()) return null // durable profitable-cycle proof is mandatory elsewhere
    if(type.removePrefix("SHORT_").removePrefix("LONG_")!="FRACTAL") return "NTD_FIRST_EXTERNAL_FRACTAL_REQUIRED"
    val proof=runCatching { JSONObject(setup).getJSONObject("ntd_initial") }.getOrNull()
        ?:return "NTD_SLEEP_OR_FIRST_BREAKOUT_NOT_PROVEN"
    return if(proof.optBoolean("eligible") && proof.optLong("sleep_at_ms")>0L) null
        else "NTD_SLEEP_OR_FIRST_BREAKOUT_NOT_PROVEN"
}
