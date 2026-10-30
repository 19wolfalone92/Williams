package com.williamsbot

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Course PDF83-84: separate corrective owner after the motive is confirmed flat.
 * Abstract 3 -> 1 -> 4 -> 0, never Binance lots. PDF84's 'leaving 1 long'
 * contradicts selling three and covering two, its diagram and later text;
 * the consistent residual is one SHORT after a bullish motive (and mirrored).
 * No observer proposal can submit an order or implicitly reverse ownership. */
internal val courseCorrectionStages=listOf("A_ENTRY","A_REDUCE","C_ADD","CORRECTION_EXIT")
internal val courseCorrectionTargets=courseCorrectionStages.zip(listOf(3,1,4,0)).toMap()

internal data class WilliamsCourseCorrectionState(
    val campaignId:String,val motiveCampaignId:String,val motiveSide:String,
    val flatConfirmedAtMs:Long,val stage:String="",val confirmedUnits:Int=0,
    val revision:Int=0,val lastSignalCloseMs:Long=0L,val fillIds:Set<String> = emptySet()
) { val side:String get()=if(motiveSide=="LONG") "SHORT" else "LONG" }

internal data class WilliamsCourseCorrectionEvidence(
    val motiveExtreme:Double,val firstAExtreme:Double,val aEnd:Double,val price:Double,
    val knownAtMs:Long,val sameDegreeConfirmed:Boolean,val motiveFiveComplete:Boolean,
    val aComplete:Boolean=false,val cFiveComplete:Boolean=false
)
internal data class WilliamsCourseCorrectionProposal(
    val status:String,val stage:String,val side:String,val delta:Int=0,val targetUnits:Int=0,
    val key:String="",val reason:String="",val canPlaceOrders:Boolean=false
)
private fun correctionKey(s:WilliamsCourseCorrectionState,stage:String,close:Long):String {
    val raw="${s.campaignId}|${s.motiveCampaignId}|${s.motiveSide}|${s.flatConfirmedAtMs}|$stage|$close|${s.revision}"
    return "COURSE_ABC_"+MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(24)
}
private fun nextCorrectionStage(stage:String):String?=if(stage.isEmpty()) courseCorrectionStages.first()
    else courseCorrectionStages.getOrNull(courseCorrectionStages.indexOf(stage)+1)

internal fun planWilliamsCourseCorrection(
    s:WilliamsCourseCorrectionState,stage:String,e:WilliamsCourseCorrectionEvidence,
    closeMs:Long,nowMs:Long,motiveClosedAndFlat:Boolean,exchangeReconciled:Boolean,
    protectionVerified:Boolean,riskAdmitted:Boolean,maxUnits:Int=10
):WilliamsCourseCorrectionProposal {
    fun blocked(why:String)=WilliamsCourseCorrectionProposal("BLOCKED",stage,s.side,
        targetUnits=s.confirmedUnits,reason=why)
    if(s.campaignId.isBlank() || s.motiveCampaignId.isBlank() || s.campaignId==s.motiveCampaignId ||
        s.motiveSide !in listOf("LONG","SHORT") || s.stage !in courseCorrectionStages+listOf("") ||
        s.confirmedUnits!=(if(s.stage.isEmpty()) 0 else courseCorrectionTargets[s.stage]) || s.revision<0)
        return blocked("INVALID_CORRECTIVE_OWNER_STATE")
    if(stage!=nextCorrectionStage(s.stage)) return blocked("NON_SEQUENTIAL_CORRECTIVE_STAGE")
    if(s.flatConfirmedAtMs<=0 || !motiveClosedAndFlat || closeMs<=s.flatConfirmedAtMs)
        return blocked("MOTIVE_FLAT_NOT_CONFIRMED_BEFORE_NEW_CAMPAIGN")
    if(closeMs<=s.lastSignalCloseMs || closeMs>nowMs || nowMs-closeMs>18_000_000L ||
        e.knownAtMs<=s.flatConfirmedAtMs || e.knownAtMs>closeMs)
        return blocked("CORRECTION_EVIDENCE_NOT_CAUSAL_FRESH_AND_NEW")
    if(!e.sameDegreeConfirmed || !e.motiveFiveComplete)
        return blocked("CORRECTION_WAVE_DEGREE_UNCONFIRMED")
    if(!exchangeReconciled) return blocked("EXCHANGE_RECONCILIATION_REQUIRED")
    // A's eventual end is not yet known at its first retracement entry.
    val prices=listOf(e.motiveExtreme,e.firstAExtreme,e.price)+if(stage=="A_ENTRY") emptyList() else listOf(e.aEnd)
    if(prices.any { !it.isFinite() || it<=0 })
        return blocked("CORRECTION_PRICE_UNVERIFIED")
    val sign=if(s.motiveSide=="LONG") 1.0 else -1.0
    val first=sign*(e.motiveExtreme-e.firstAExtreme)
    val aLength=sign*(e.motiveExtreme-e.aEnd)
    if(first<=0 || (stage!="A_ENTRY" && aLength<=0)) return blocked("CORRECTION_DIRECTION_UNVERIFIED")
    when(stage) {
        "A_ENTRY" -> {
            val ratio=sign*(e.price-e.firstAExtreme)/first
            if(ratio<.5-1e-12 || ratio>.62+1e-12) return blocked("A_ENTRY_OUTSIDE_50_62_RETRACEMENT")
        }
        "A_REDUCE","C_ADD" -> {
            if(!e.aComplete) return blocked("A_END_NOT_CONFIRMED")
            if(stage=="C_ADD") {
                val ratio=sign*(e.price-e.aEnd)/aLength
                if(ratio<.38-1e-12 || ratio>.5+1e-12) return blocked("B_OUTSIDE_38_50_RETRACEMENT_OF_A")
            }
        }
        "CORRECTION_EXIT" -> if(!e.cFiveComplete) return blocked("C_FIFTH_NOT_CONFIRMED")
    }
    val target=courseCorrectionTargets.getValue(stage)
    val delta=target-s.confirmedUnits
    if(!protectionVerified) return blocked("PROTECTIVE_STOP_NOT_VERIFIED")
    if(delta>0 && (!riskAdmitted || target>maxUnits)) return blocked("RISK_GATE_BLOCKS_NEW_EXPOSURE")
    return WilliamsCourseCorrectionProposal("PROPOSED",stage,s.side,delta,target,
        correctionKey(s,stage,closeMs),"COURSE_PDF83_84_ABSTRACT_UNITS")
}

internal fun acknowledgeWilliamsCourseCorrectionFill(
    s:WilliamsCourseCorrectionState,p:WilliamsCourseCorrectionProposal,fillId:String,
    delta:Int,units:Int,closeMs:Long,exchangeReconciled:Boolean,protectionVerified:Boolean
):WilliamsCourseCorrectionState {
    require(fillId.isNotBlank() && p.status=="PROPOSED" && !p.canPlaceOrders) { "CORRECTION_FILL_UNVERIFIED" }
    if(fillId in s.fillIds) return s
    require(s.stage in courseCorrectionStages+listOf("") && s.revision>=0 &&
        s.confirmedUnits==(if(s.stage.isEmpty()) 0 else courseCorrectionTargets[s.stage])) {
        "INVALID_CORRECTIVE_OWNER_STATE"
    }
    require(exchangeReconciled && protectionVerified && p.stage==nextCorrectionStage(s.stage) &&
        p.side==s.side && p.key==correctionKey(s,p.stage,closeMs) &&
        p.targetUnits==courseCorrectionTargets[p.stage] && units==p.targetUnits &&
        delta==p.delta && delta==units-s.confirmedUnits &&
        closeMs>maxOf(s.lastSignalCloseMs,s.flatConfirmedAtMs)) { "STALE_FOREIGN_OR_PARTIAL_CORRECTION_FILL" }
    return s.copy(stage=p.stage,confirmedUnits=units,revision=s.revision+1,
        lastSignalCloseMs=closeMs,fillIds=s.fillIds+fillId)
}

internal fun williamsCourseCorrectionPolicySnapshot()=JSONObject()
    .put("source","COURSE_PDF83_84").put("stages",JSONArray(courseCorrectionStages))
    .put("targets",JSONArray(listOf(3,1,4,0)))
    .put("a_entry_retracement",JSONArray(listOf(.5,.62))).put("b_retracement_of_a",JSONArray(listOf(.38,.5)))
    .put("reverse_policy","CLOSE_OWNED_EXPOSURE_CONFIRM_FLAT_THEN_NEW_RISK_GATES")
    .put("quantity_basis","ILLUSTRATIVE_UNITS_NOT_BINANCE_LOTS")
    .put("source_discrepancy","PDF84_LEAVING_1_LONG_CONTRADICTS_SHORT_ARITHMETIC")
    .put("can_place_orders",false)
