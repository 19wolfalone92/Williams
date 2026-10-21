package com.williamsbot

import java.math.BigDecimal
import org.json.JSONArray
import org.json.JSONObject

/** TC2 PDF p164 / printed p149: profitable position; contract/share units
 * 1,5,4,3,2. Risk/margin caps are separate vetoes, never substitute weights. */
internal const val WILLIAMS_TC2_PYRAMID_POLICY = "TC2_BOOK_QUANTITIES_1_5_4_3_2_V1"
internal val williamsTc2PyramidWeights = listOf(1,5,4,3,2)

internal fun williamsTc2InitialUnitRisk(initialCap:Double,campaignCap:Double,entryCap:Double):Double =
    if(listOf(initialCap,campaignCap,entryCap).any { !it.isFinite() || it<=0 }) 0.0
    else minOf(initialCap,campaignCap/15.0,entryCap/5.0)

internal fun williamsTc2PyramidPolicy(owner:JSONObject):JSONObject? {
    val durable=owner.optString("tc2_quantity_policy")
    val raw=owner.optString("setup_context","{}")
    if(durable.isNotBlank() && durable !in listOf("LEGACY_UNPINNED",WILLIAMS_TC2_PYRAMID_POLICY))
        return JSONObject().put("policy","UNKNOWN_DURABLE_POLICY")
    val context=runCatching { JSONObject(raw.ifBlank { "{}" }) }.getOrNull()
        ?: return JSONObject().put("policy","MALFORMED_SETUP_CONTEXT")
    if(durable==WILLIAMS_TC2_PYRAMID_POLICY && context.optJSONObject("tc2_reverse_pyramid")==null)
        return JSONObject().put("policy","MISSING_PINNED_POLICY")
    return context.optJSONObject("tc2_reverse_pyramid") ?: if(context.has("tc2_reverse_pyramid"))
        JSONObject().put("policy","MALFORMED_POLICY_PIN") else null
}

internal fun williamsTc2ProfitBlocker(direction:String,entry:Double,mark:Double,trigger:Double):String? = when {
    direction !in listOf("LONG","SHORT") || listOf(entry,mark,trigger).any { !it.isFinite() || it<=0 } ->
        "TC2_ADDITION_PROFIT_UNPROVEN"
    !(if(direction=="LONG") mark>entry && trigger>entry else mark<entry && trigger<entry) ->
        "TC2_ADDITION_REQUIRES_PROFITABLE_POSITION"
    else -> null
}

internal data class WilliamsTc2Tranche(val quantity:BigDecimal?=null,val number:Int=0,val blocker:String?=null)

internal fun williamsTc2NextTranche(owner:JSONObject,intents:JSONArray,ignoreClientId:String=""):WilliamsTc2Tranche {
    fun blocked(why:String)=WilliamsTc2Tranche(blocker=why)
    val policy=williamsTc2PyramidPolicy(owner) ?: return blocked("TC2_PYRAMID_POLICY_UNPINNED")
    if(policy.optString("policy")!=WILLIAMS_TC2_PYRAMID_POLICY) return blocked("TC2_PYRAMID_POLICY_UNRECOGNIZED")
    val unit=owner.optString("actual_entry_executed_qty").toBigDecimalOrNull()
        ?.takeIf { it.signum()>0 } ?: return blocked("TC2_PYRAMID_INITIAL_FILL_UNPROVEN")
    var total=unit
    var fills=0
    for(i in 0 until intents.length()) {
        val child=intents.optJSONObject(i) ?: return blocked("TC2_PYRAMID_FILL_LEDGER_UNPROVEN")
        if(child.optString("intent_role")!="ADD_ON" || child.optString("entry_client_id")!=owner.optString("client_id") ||
            child.optString("client_id")==ignoreClientId) continue
        if(exchangeOrderStatus(child) !in ENTRY_ORDER_TERMINAL_STATUSES) return blocked("TC2_PYRAMID_PENDING_TRANCHE")
        val executed=child.optString("actual_entry_executed_qty",child.optString("executed_qty")).toBigDecimalOrNull()
            ?: if(child.optBoolean("conditional_untriggered_terminal",false) ||
                child.has("entry_submission_attempted") && !child.optBoolean("entry_submission_attempted",true)) BigDecimal.ZERO
            else return blocked("TC2_PYRAMID_FILL_LEDGER_UNPROVEN")
        if(executed.signum()<0) return blocked("TC2_PYRAMID_FILL_LEDGER_UNPROVEN")
        if(executed.signum()==0) continue
        if(fills>=4) return blocked("TC2_PYRAMID_TOO_MANY_TRANCHES")
        val expected=unit.multiply(williamsTc2PyramidWeights[fills+1].toBigDecimal())
        if(executed.compareTo(expected)!=0 || child.optString("quantity").toBigDecimalOrNull()?.compareTo(expected)!=0)
            return blocked("TC2_PYRAMID_PARTIAL_OR_CHANGED_TRANCHE")
        if(child.optString("symbol")!=owner.optString("symbol") || child.optString("direction")!=owner.optString("direction"))
            return blocked("TC2_PYRAMID_FILL_OWNER_MISMATCH")
        total=total.add(executed);fills++
    }
    if(owner.optString("confirmed_position_qty").toBigDecimalOrNull()?.compareTo(total)!=0)
        return blocked("TC2_PYRAMID_OWNED_QUANTITY_CHANGED")
    if(fills==4) return blocked("TC2_PYRAMID_COMPLETE_15_UNITS")
    return WilliamsTc2Tranche(unit.multiply(williamsTc2PyramidWeights[fills+1].toBigDecimal()),fills+2)
}

internal fun williamsTc2TrancheQuantityBlocker(required:BigDecimal,admitted:String):String? =
    if(admitted.toBigDecimalOrNull()?.compareTo(required)==0) null else "TC2_PYRAMID_EXACT_BOOK_QUANTITY_REQUIRED"
