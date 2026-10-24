package com.williamsbot

import java.math.BigDecimal
import java.math.RoundingMode
import org.json.JSONObject

/** Course PDF78-84 proportions, independent of wave proof and order authority. */
internal const val WILLIAMS_COURSE_QUANTITY_POLICY="COURSE_EXACT_CONTRACT_UNITS_V1"
private val courseCorrectionTargets=mapOf("A_ENTRY" to 3,"A_REDUCE" to 1,"C_ADD" to 4,"CORRECTION_EXIT" to 0)
private val courseQuantityNext=mapOf("W1" to listOf("W2"),"W2" to listOf("W3"),"W3" to listOf("W3_EXTENSION","W4"),
    "W3_EXTENSION" to listOf("W4"),"W4" to listOf("W5"),"W5" to listOf("EXIT"),
    "A_ENTRY" to listOf("A_REDUCE"),"A_REDUCE" to listOf("C_ADD"),"C_ADD" to listOf("CORRECTION_EXIT"))
private fun courseDecimal(value:Any?):BigDecimal {
    require(value !is Boolean) { "COURSE_QUANTITY_MALFORMED" }
    val n=value.toString().toBigDecimalOrNull() ?: error("COURSE_QUANTITY_MALFORMED")
    require(n.precision()<=40 && kotlin.math.abs(n.scale().toLong())<=24) { "COURSE_QUANTITY_MALFORMED" }
    return n
}
private fun coursePlain(n:BigDecimal)=n.stripTrailingZeros().toPlainString()
private fun courseQuantityBlocked(reason:String)=JSONObject().put("status","BLOCKED").put("reason",reason)
    .put("policy",WILLIAMS_COURSE_QUANTITY_POLICY).put("can_place_orders",false)
private fun courseQuantityResult(action:String,from:String,stage:String,unit:BigDecimal,before:BigDecimal,
    quantity:BigDecimal,target:BigDecimal,units:Int,step:BigDecimal)=JSONObject().put("status","EXACT_COURSE_QUANTITY")
    .put("policy",WILLIAMS_COURSE_QUANTITY_POLICY).put("action",action).put("from_stage",from).put("stage",stage)
    .put("unit_quantity",coursePlain(unit)).put("before_quantity",coursePlain(before)).put("quantity",coursePlain(quantity))
    .put("target_quantity",coursePlain(target)).put("target_units",units).put("quantity_step",coursePlain(step)).put("can_place_orders",false)

internal fun williamsCourseQuantityStep(lotStep:Any?,marketStep:Any?="0"):BigDecimal {
    val a=courseDecimal(lotStep);val b=courseDecimal(marketStep)
    require(a.signum()>0 && b.signum()>=0) { "COURSE_QUANTITY_GRID_UNVERIFIED" }
    return ExecutionMath.commonQuantityStep(a,b)
}

internal fun williamsCourseInitialQuantity(riskCap:Any?,lotStep:Any?,marketStep:Any?="0",stage:String="W1"):JSONObject=try {
    if(stage !in listOf("W1","A_ENTRY")) courseQuantityBlocked("COURSE_INITIAL_STAGE_MUST_START_WITH_THREE_UNITS")
    else {
        val cap=courseDecimal(riskCap);val step=williamsCourseQuantityStep(lotStep,marketStep)
        if(cap.signum()<=0) courseQuantityBlocked("COURSE_INITIAL_RISK_CAP_UNVERIFIED") else {
            val unit=cap.divide(step*BigDecimal(3),0,RoundingMode.FLOOR)*step
            if(unit.signum()<=0) courseQuantityBlocked("COURSE_THREE_UNITS_BELOW_EXCHANGE_GRID") else
                courseQuantityResult("ENTRY","",stage,unit,BigDecimal.ZERO,unit*BigDecimal(3),unit*BigDecimal(3),3,step)
        }
    }
} catch(_:Exception) { courseQuantityBlocked("COURSE_QUANTITY_OR_GRID_MALFORMED") }

internal fun williamsCourseStageQuantity(unitQuantity:Any?,fromStage:String,stage:String,ownedQuantity:Any?,lotStep:Any?,
    marketStep:Any?="0",riskCap:Any?=null,w3ToW1Ratio:Double?=null):JSONObject {
    if(stage !in courseQuantityNext[fromStage].orEmpty()) return courseQuantityBlocked("COURSE_NON_SEQUENTIAL_QUANTITY_STAGE")
    val units=courseCorrectionTargets[fromStage] ?: williamsCourseTarget(fromStage,w3ToW1Ratio)
    val targetUnits=courseCorrectionTargets[stage] ?: williamsCourseTarget(stage,w3ToW1Ratio)
    if(units==null || targetUnits==null) return courseQuantityBlocked("COURSE_STAGE_OR_W3_W1_RATIO_UNCONFIRMED")
    return try {
        val unit=courseDecimal(unitQuantity);val owned=courseDecimal(ownedQuantity);val step=williamsCourseQuantityStep(lotStep,marketStep)
        when {
            unit.signum()<=0 || owned.signum()<=0 -> courseQuantityBlocked("COURSE_ACTUAL_UNIT_OR_OWNED_QUANTITY_UNVERIFIED")
            unit.remainder(step).signum()!=0 -> courseQuantityBlocked("COURSE_UNIT_NOT_ON_EXCHANGE_GRID")
            owned.compareTo(unit*BigDecimal(units))!=0 -> courseQuantityBlocked("COURSE_OWNED_STAGE_QUANTITY_CHANGED")
            else -> {
                val target=unit*BigDecimal(targetUnits);val delta=target-owned
                when {
                    delta.signum()==0 -> courseQuantityBlocked("COURSE_STAGE_HAS_NO_QUANTITY_CHANGE")
                    delta.signum()>0 && (riskCap==null || courseDecimal(riskCap)<delta) -> courseQuantityBlocked("COURSE_FULL_TRANCHE_EXCEEDS_RISK_OR_MARGIN_CAP")
                    else -> courseQuantityResult(if(delta.signum()>0) "ADD" else "REDUCE",fromStage,stage,unit,owned,delta.abs(),target,targetUnits,step)
                        .apply { if(w3ToW1Ratio!=null && w3ToW1Ratio.isFinite()) put("w3_to_w1_ratio",w3ToW1Ratio) }
                }
            }
        }
    } catch(_:Exception) { courseQuantityBlocked("COURSE_QUANTITY_OR_GRID_MALFORMED") }
}

/** Signed actual child/order identity must already be attributed by the caller. */
internal fun williamsCourseOwnedQuantityBlocker(unitQuantity:Any?,stage:String,ownedQuantity:Any?,lotStep:Any?,
    marketStep:Any?="0",w3ToW1Ratio:Double?=null):String?=try {
    val units=courseCorrectionTargets[stage] ?: williamsCourseTarget(stage,w3ToW1Ratio)
    val unit=courseDecimal(unitQuantity);val owned=courseDecimal(ownedQuantity);val step=williamsCourseQuantityStep(lotStep,marketStep)
    when {
        units==null || units<=0 || unit.signum()<=0 || owned.signum()<=0 -> "COURSE_ACTUAL_UNIT_OR_OWNED_QUANTITY_UNVERIFIED"
        unit.remainder(step).signum()!=0 -> "COURSE_UNIT_NOT_ON_EXCHANGE_GRID"
        owned.compareTo(unit*BigDecimal(units))!=0 -> "COURSE_OWNED_STAGE_QUANTITY_CHANGED"
        else -> null
    }
} catch(_:Exception) { "COURSE_QUANTITY_OR_GRID_MALFORMED" }

internal fun williamsCourseFillBlocker(proposal:JSONObject,order:JSONObject,position:JSONObject,symbol:String,direction:String,
    expectedClientId:String,expectedOrderId:String?=null,protectionVerified:Boolean):String? {
    if(proposal.optString("status")!="EXACT_COURSE_QUANTITY" || proposal.optString("policy")!=WILLIAMS_COURSE_QUANTITY_POLICY ||
        proposal.optString("action") !in listOf("ENTRY","ADD","REDUCE"))
        return "COURSE_EXACT_QUANTITY_PROPOSAL_UNVERIFIED"
    try {
        val derived=if(proposal.getString("action")=="ENTRY") williamsCourseInitialQuantity(proposal.opt("quantity"),proposal.opt("quantity_step"),stage=proposal.getString("stage"))
            else williamsCourseStageQuantity(proposal.opt("unit_quantity"),proposal.getString("from_stage"),proposal.getString("stage"),
                proposal.opt("before_quantity"),proposal.opt("quantity_step"),riskCap=proposal.opt("quantity"),
                w3ToW1Ratio=if(proposal.has("w3_to_w1_ratio")) proposal.optDouble("w3_to_w1_ratio") else null)
        if(derived.optString("status")!="EXACT_COURSE_QUANTITY" || proposal.opt("can_place_orders")!=false ||
            listOf("action","from_stage","stage","target_units").any { proposal.opt(it).toString()!=derived.opt(it).toString() } ||
            listOf("unit_quantity","quantity_step","before_quantity","quantity","target_quantity").any {
                courseDecimal(proposal.opt(it)).compareTo(courseDecimal(derived.opt(it)))!=0 })
            return "COURSE_EXACT_QUANTITY_PROPOSAL_UNVERIFIED"
    } catch(_:Exception) { return "COURSE_EXACT_QUANTITY_PROPOSAL_UNVERIFIED" }
    val reduce=proposal.optString("action")=="REDUCE"
    val side=when(direction) { "LONG","BUY"->"BUY";"SHORT","SELL"->"SELL";else->return "COURSE_SIGNED_STAGE_FILL_IDENTITY_UNVERIFIED" }
    val expectedSide=if(reduce) (if(side=="BUY") "SELL" else "BUY") else side
    val rawReduce=order.opt("reduceOnly")
    if(expectedClientId.isBlank() || order.optString("clientOrderId")!=expectedClientId || order.optString("symbol")!=symbol ||
        position.optString("symbol")!=symbol || order.optString("side")!=expectedSide || order.optString("positionSide","BOTH")!="BOTH" ||
        position.optString("positionSide")!="BOTH" || order.optString("orderId").isBlank() ||
        expectedOrderId!=null && order.optString("orderId")!=expectedOrderId ||
        (if(reduce) rawReduce!=true && rawReduce!="true" else rawReduce!=false && rawReduce!="false"))
        return "COURSE_SIGNED_STAGE_FILL_IDENTITY_UNVERIFIED"
    if(order.optString("status")!="FILLED") return "COURSE_STAGE_REQUIRES_FULL_TERMINAL_FILL"
    val target:BigDecimal
    try {
        val unit=courseDecimal(proposal.opt("unit_quantity"));val before=courseDecimal(proposal.opt("before_quantity"))
        val quantity=courseDecimal(proposal.opt("quantity"));target=courseDecimal(proposal.opt("target_quantity"))
        val step=courseDecimal(proposal.opt("quantity_step"));val actual=courseDecimal(position.opt("positionAmt"))*(if(side=="BUY") BigDecimal.ONE else -BigDecimal.ONE)
        if(unit.signum()<=0 || step.signum()<=0 || unit.remainder(step).signum()!=0 || quantity.signum()<=0 ||
            quantity.remainder(step).signum()!=0 || target.signum()<0 || target.remainder(step).signum()!=0 ||
            before.signum()<0 || before.remainder(step).signum()!=0 || target.compareTo(unit*BigDecimal(proposal.getInt("target_units")))!=0 ||
            target.compareTo(before+(if(reduce) -quantity else quantity))!=0 || courseDecimal(order.opt("origQty")).compareTo(quantity)!=0 ||
            courseDecimal(order.opt("executedQty")).compareTo(quantity)!=0 || actual.compareTo(target)!=0)
            return "COURSE_STAGE_FILL_OR_POSITION_QUANTITY_MISMATCH"
    } catch(_:Exception) { return "COURSE_STAGE_FILL_OR_POSITION_QUANTITY_MISMATCH" }
    return if(target.signum()>0 && !protectionVerified) "COURSE_STAGE_PROTECTION_NOT_VERIFIED" else null
}
