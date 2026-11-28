package com.williamsbot

import org.json.JSONObject

/**
 * A local, short-lived receipt from the production source verifier, not a
 * signal detector and not a reusable authorization. All fields are tied to
 * the exact effective order plan, including campaign ownership and quantity.
 */
internal data class Tc2H1SourceReceipt(
    val boundPlan:NativeFuturesCampaignCoordinator.EntryPlan,
    val closedAtMs:Long,
    val historyRevision:Long,
    val historySha256:String,
    val closedCount:Int,
    val currentExchangeMs:()->Long?,
    val currentRevision:()->Long
) {
    fun blocker(plan:NativeFuturesCampaignCoordinator.EntryPlan):String? {
        val prefix="TC2_CANONICAL_ADMISSION:"
        if(boundPlan!=plan || plan.signalId.isBlank() || plan.clientId.isBlank() ||
            plan.strategyProfile!=williamsTc2Profile) return prefix+"PLAN_CHANGED"
        if(!canonicalAlligatorWarmupReady(closedCount) || historyRevision<=0L ||
            closedAtMs<=0L || closedAtMs%WILLIAMS_H1_MS!=0L ||
            !Regex("[0-9a-f]{64}").matches(historySha256)) return prefix+"SOURCE_RECEIPT_INVALID"
        val now=runCatching { currentExchangeMs() }.getOrNull()
            ?: return prefix+"CLOCK_UNVERIFIED"
        if(now<closedAtMs || now-closedAtMs>=WILLIAMS_H1_MS) return prefix+"NEW_H1_BEFORE_TRANSPORT"
        if(runCatching { currentRevision() }.getOrNull()!=historyRevision)
            return prefix+"SOURCE_REVISION_CHANGED"
        return null
    }
    fun toJson():JSONObject=JSONObject()
        .put("schema","R49_TC2_H1_SOURCE_RECEIPT_V1")
        .put("symbol",boundPlan.symbol).put("signal_id",boundPlan.signalId)
        .put("signal_type",boundPlan.signalType).put("direction",boundPlan.direction.name)
        .put("client_id",boundPlan.clientId).put("entry_client_id",boundPlan.entryClientId)
        .put("reversal_owner_id",boundPlan.reversalOwnerId)
        .put("signal_bar_time_ms",boundPlan.signalBarTimeMs)
        .put("entry_price",boundPlan.entryPrice).put("protective_stop",boundPlan.protectiveStop)
        .put("quantity",boundPlan.quantity).put("closed_at_ms",closedAtMs)
        .put("history_revision",historyRevision).put("closed_ohlcv_sha256",historySha256)
        .put("closed_count",closedCount)
}
