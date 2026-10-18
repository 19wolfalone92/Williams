package com.williamsbot

import java.security.MessageDigest

/**
 * Trading Chaos Course Level III pp77-84: the illustrated staged position
 * sequence 3 -> 1 -> 6 -> 10 -> 3 -> 6 -> flat. These are ABSTRACT
 * risk slots, not exchange quantities; this is not TC2's 1:5:4:3:2.
 *
 * Non-executable policy until position ownership, fill reconciliation,
 * protection, precision and book scenario are proven on Binance Demo.
 */
internal val courseStages=listOf("W1","W2","W3","W3_EXTENSION","W4","W5","EXIT")
internal val courseTargets=mapOf("W1" to 3,"W2" to 1,"W3" to 6,
    "W3_EXTENSION" to 10,"W4" to 3,"W5" to 6,"EXIT" to 0)

internal data class WilliamsCourseState(
    val campaignId:String, val side:String, val stage:String="",
    val confirmedUnits:Int=0, val revision:Int=0, val lastSignalCloseMs:Long=0L,
    val confirmedFillIds:Set<String> = emptySet()
)
internal data class WilliamsCourseProposal(
    val status:String, val stage:String, val action:String="NONE",
    val signedUnitDelta:Int=0, val targetUnits:Int=0,
    val idempotencyKey:String="", val reason:String="",
    val canPlaceOrders:Boolean=false
)

internal fun planWilliamsCourse(
    s:WilliamsCourseState, nextStage:String, signalCloseMs:Long,
    nowMs:Long, waveConfirmed:Boolean, exchangeReconciled:Boolean,
    protectionVerified:Boolean, newRiskAdmitted:Boolean,
    maxRiskSlots:Int=10, maxSignalAgeMs:Long=18_000_000L
):WilliamsCourseProposal {
    fun blocked(why:String)=WilliamsCourseProposal("BLOCKED",nextStage,
        targetUnits=s.confirmedUnits,reason=why)
    if(s.campaignId.isBlank() || s.side !in listOf("LONG","SHORT") ||
       s.stage !in (courseStages+listOf("")) || s.confirmedUnits<0 || s.revision<0)
        return blocked("INVALID_STATE")
    val expected=when(s.stage) {
        "" -> "W1"
        "W1" -> "W2"
        "W2" -> "W3"
        "W3" -> "W3_EXTENSION"
        "W3_EXTENSION" -> "W4"
        "W4" -> "W5"
        "W5" -> "EXIT"
        else -> ""
    }
    if(nextStage!=expected) return blocked("NON_SEQUENTIAL_STAGE")
    if(signalCloseMs<=0 || signalCloseMs>nowMs ||
       nowMs-signalCloseMs>maxSignalAgeMs || signalCloseMs<=s.lastSignalCloseMs)
        return blocked("SIGNAL_NOT_CLOSED_FRESH_AND_NEW")
    if(!waveConfirmed) return blocked("WAVE_DEGREE_UNCONFIRMED")
    if(!exchangeReconciled) return blocked("EXCHANGE_RECONCILIATION_REQUIRED")
    val target=courseTargets[nextStage] ?: return blocked("UNKNOWN_STAGE")
    if(maxRiskSlots<0 || (target>maxRiskSlots && target>s.confirmedUnits))
        return blocked("RISK_SLOT_CAP")
    val delta=target-s.confirmedUnits
    if(delta!=0 && !protectionVerified) return blocked("PROTECTIVE_STOP_NOT_VERIFIED")
    if(delta>0 && !newRiskAdmitted) return blocked("RISK_GATE_BLOCKS_NEW_EXPOSURE")
    val action=if(delta>0) "ADD" else if(delta<0) "REDUCE" else "HOLD"
    val raw="${s.campaignId}|${s.side}|$nextStage|$signalCloseMs|${s.revision}"
    val digest=MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(24)
    return WilliamsCourseProposal("PROPOSED",nextStage,action,delta,target,
        "COURSE_$digest","COURSE_LEVEL_III_ILLUSTRATIVE_STAGE_NOT_EXECUTABLE")
}

/** Only an independently verified exchange FILL may advance persisted state. */
internal fun acknowledgeWilliamsCourseFill(
    s:WilliamsCourseState, proposed:WilliamsCourseProposal,
    fillId:String, filledDelta:Int, verifiedUnits:Int, signalCloseMs:Long,
    exchangeReconciled:Boolean, protectiveStopVerified:Boolean
):WilliamsCourseState {
    require(fillId.isNotBlank() && proposed.status=="PROPOSED" && !proposed.canPlaceOrders) {
        "UNVERIFIABLE_COURSE_FILL"
    }
    if(fillId in s.confirmedFillIds) return s
    require(exchangeReconciled && protectiveStopVerified) { "RECONCILIATION_OR_PROTECTION_MISSING" }
    val expected=if(s.stage.isEmpty()) "W1" else courseStages.getOrNull(courseStages.indexOf(s.stage)+1)
    require(proposed.stage==expected && proposed.idempotencyKey.isNotBlank()) { "STALE_COURSE_PROPOSAL" }
    require(filledDelta==proposed.signedUnitDelta && verifiedUnits==s.confirmedUnits+filledDelta &&
        verifiedUnits==proposed.targetUnits && verifiedUnits>=0 && signalCloseMs>s.lastSignalCloseMs) {
        "FILL_QUANTITY_OR_TIME_MISMATCH"
    }
    return s.copy(stage=proposed.stage,confirmedUnits=verifiedUnits,revision=s.revision+1,
        lastSignalCloseMs=signalCloseMs,confirmedFillIds=s.confirmedFillIds+fillId)
}

internal fun williamsCourseActiveContext(report:WilliamsWaveHierarchy,side:String,originAt:Long?=null):org.json.JSONObject? {
    val direction=when(side) { "LONG","BUY"->"UP";"SHORT","SELL"->"DOWN";else->return null }
    if("1h" in report.frameErrors) return null
    val candidates=report.scenarios.filter { it.frame=="1h" && it.status=="PROVISIONAL" && it.direction==direction &&
        it.activeLabel in listOf("W1","W2","W3","W4","W5","A","C") &&
        (originAt==null || it.anchors.first().extremeAt==originAt) &&
        (originAt!=null || it.activeLabel in listOf("W1","W3","W5")) }
    if(candidates.size!=1) return null
    val h=candidates.single()
    return org.json.JSONObject().put("origin_at",h.anchors.first().extremeAt).put("phase",h.activeLabel)
        .put("scenario_id",h.id).put("known_at",h.anchors.last().knownAt).put("observed_at",h.observedAt)
}
internal fun williamsCourseNextStage(stage:String,phase:String,newFormation:Boolean=false):String?=when {
    stage=="W1" && phase=="W2" -> "W2"
    stage=="W2" && phase=="W3" && newFormation -> "W3"
    stage=="W3" && phase=="W3" && newFormation -> "W3_EXTENSION"
    stage in listOf("W3","W3_EXTENSION") && phase=="W4" -> "W4"
    stage=="W4" && phase=="W5" && newFormation -> "W5"
    else -> null
}
