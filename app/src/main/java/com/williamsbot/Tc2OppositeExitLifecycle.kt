package com.williamsbot

import org.json.JSONObject

/**
 * Exit-only lifecycle sanity for a triggered opposing TC2 Wise-Man signal.
 *
 * A signal can have TRIGGERED in a historical H1 bar and then FAIL at its
 * opposite book price boundary before the Android scanner returns. The
 * generic level-event detector intentionally records the FIRST terminal
 * event for entry-cancellation replay; that historic fact must NOT be reused
 * as authority to market-close an unrelated, still protected campaign.
 *
 * This policy never predicts direction or changes a Binance STOP. It only
 * refuses stale/incomplete evidence before a voluntary opposite exit.
 * TC2 ch. 12 PDF pp. 153-165: valid Wise-Man price level, not any AO colour
 * change or unproven chart hypothesis, is the candidate exit authority.
 */
internal data class Tc2OppositeLifecycleEvidence(
    val valid: Boolean,
    val reason: String,
    val observedAfterTrigger: Int
) {
    fun toJson(): JSONObject = JSONObject()
        .put("status", if (valid) "FRESH_CONFIRMED_OPPOSITE" else "NO_EXIT_AUTHORITY")
        .put("reason", reason)
        .put("closed_h1_after_first_trigger", observedAfterTrigger)
        .put("may_override_exchange_stop", false)
}

/**
 * All closed H1 candles between the FIRST proven opposite price trigger and
 * NOW must be verifiable and must NOT have crossed the signal's opposite
 * invalidation boundary. A later tick crossing of an already invalidated
 * historical signal is not a NEW Wise-Man signal.
 *
 * Exactly current, live CONTRACT-price triggers have no post-trigger H1
 * history. The owner, source, trigger price and transport freshness are
 * independently validated by the Futures coordinator.
 */
internal fun tc2OppositeExitLifecycle(
    signal: WilliamsScenarioSignal,
    event: WilliamsLevelEvent,
    closedH1: List<WilliamsWaveBar>,
    now: Long,
    frameMs: Long = WILLIAMS_H1_MS
): Tc2OppositeLifecycleEvidence {
    fun denied(reason:String,count:Int=0) =
        Tc2OppositeLifecycleEvidence(false,reason,count)
    if(signal.profile!=williamsTc2Profile ||
        signal.semantics!="STOP_ENTRY" ||
        signal.direction !in setOf("LONG","SHORT") ||
        !signal.trigger.isFinite() || signal.trigger<=0.0 ||
        frameMs<=0L || now<=0L ||
        signal.expiresAt<=now || signal.signalAt<=0L ||
        event.state!="TRIGGERED" || event.knownAt<=0L || event.knownAt>now)
        return denied("OPPOSITE_SOURCE_TIME_OR_EVENT_UNVERIFIED")
    val invalidation=signal.invalidation
        ?.takeIf { it.isFinite() && it>0.0 && (
            if(signal.direction=="LONG") it<signal.trigger else it>signal.trigger)
        } ?: return denied("OPPOSITE_PRICE_INVALIDATION_BOUNDARY_UNVERIFIED")

    if(event.basis=="LIVE_MARK") {
        // The initial evaluator calls this LIVE_MARK; the caller separately
        // uses CONTRACT_PRICE at a fresh signed Binance mutation preflight.
        if(event.knownAt!=now) return denied("OPPOSITE_LIVE_TRIGGER_EXPIRED")
        return Tc2OppositeLifecycleEvidence(true,"CURRENT_CONTRACT_TRIGGER",0)
    }
    if(event.basis !in setOf("CLOSED_BAR_EXTREME","CLOSED_BAR_OPEN") ||
        event.knownAt%frameMs!=0L ||
        signal.signalAt>=event.knownAt ||
        event.knownAt>now)
        return denied("OPPOSITE_FIRST_TRIGGER_TIME_UNVERIFIED")

    val completedThrough=now/frameMs*frameMs
    if(completedThrough<event.knownAt)
        return denied("OPPOSITE_POST_TRIGGER_TIME_UNVERIFIED")
    val bars=closedH1.filter { b -> b.t>=event.knownAt && b.t<completedThrough }
    var expected=event.knownAt
    var count=0
    for(b in bars) {
        if(b.t!=expected ||
            listOf(b.o,b.h,b.l,b.c).any { !it.isFinite() || it<=0.0 } ||
            b.h<maxOf(b.o,b.l,b.c) || b.l>minOf(b.o,b.h,b.c))
            return denied("OPPOSITE_POST_TRIGGER_CLOSED_H1_GAP_OR_INVALID",count)
        count++
        // An earlier SELL signal dies when a later completed bar exceeds
        // its protective upper extreme; BUY is the exact mirrored case.
        val invalidated=if(signal.direction=="LONG") b.l<=invalidation
            else b.h>=invalidation
        if(invalidated)
            return denied("OPPOSITE_SIGNAL_INVALIDATED_AFTER_FIRST_TRIGGER",count)
        expected+=frameMs
    }
    if(expected!=completedThrough)
        return denied("OPPOSITE_POST_TRIGGER_CLOSED_H1_GAP_OR_INVALID",count)
    return Tc2OppositeLifecycleEvidence(true,"CLOSED_H1_SIGNAL_REMAINS_VALID",count)
}
