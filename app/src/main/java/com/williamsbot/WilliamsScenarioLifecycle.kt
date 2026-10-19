package com.williamsbot

import org.json.JSONObject

/** Causal price evidence shared by pre-entry scenario cancellation and position exits.
 * Wave labels/divergence never enter this policy. A two-boundary intrabar touch
 * without an observable ordering is explicitly ambiguous. */
internal data class WilliamsLevelEvent(
    val state:String, val knownAt:Long=0L, val basis:String="NONE"
) {
    fun toJson():JSONObject=JSONObject().put("state",state)
        .put("known_at_ms",if(knownAt>0) knownAt else JSONObject.NULL).put("basis",basis)
}

internal fun evaluateWilliamsLevelEvent(
    side:String, trigger:Double, signalClosedAt:Long, scopeStartedAt:Long,
    mark:Double, history:List<WilliamsWaveBar>, now:Long, expiresAt:Long,
    frameMs:Long=3_600_000L, invalidation:Double?=null
):WilliamsLevelEvent {
    fun event(state:String,at:Long=0L,basis:String="NONE")=WilliamsLevelEvent(state,at,basis)
    if(side !in listOf("LONG","SHORT") || !trigger.isFinite() || trigger<=0 ||
        !mark.isFinite() || mark<=0 || scopeStartedAt<=0 || signalClosedAt<=scopeStartedAt ||
        signalClosedAt>now || frameMs<=0 || expiresAt<=signalClosedAt)
        return event("UNVERIFIED",basis="INVALID_EVENT_INPUT")
    val long=side=="LONG"
    if(invalidation!=null && (!invalidation.isFinite() || invalidation<=0 ||
        if(long) invalidation>=trigger else invalidation<=trigger))
        return event("UNVERIFIED",basis="INVALID_BOUNDARY")
    fun hitTrigger(price:Double)=if(long) price>=trigger else price<=trigger
    fun hitInvalid(price:Double)=invalidation!=null && if(long) price<=invalidation else price>=invalidation
    val horizon=minOf(now,expiresAt)
    // Ignore forming/future candles, including their extrema. Validate the
    // complete prefix only up to the first terminal event; later gaps cannot
    // erase a crossing that was already proven before them.
    val closed=history.filter { it.t>=signalClosedAt && it.t<=horizon-frameMs }
    var expected=signalClosedAt
    for(b in closed) {
        if(b.t!=expected || listOf(b.o,b.h,b.l,b.c).any { !it.isFinite() || it<=0 } ||
            b.h<maxOf(b.o,b.l,b.c) || b.l>minOf(b.o,b.h,b.c))
            return event("UNVERIFIED",basis="CLOSED_HISTORY_NOT_PROVEN")
        val known=b.t+frameMs
        if(hitTrigger(b.o)) return event("TRIGGERED",known,"CLOSED_BAR_OPEN")
        if(hitInvalid(b.o)) return event("INVALIDATED",known,"CLOSED_BAR_OPEN")
        val crossed=hitTrigger(if(long) b.h else b.l)
        val broken=hitInvalid(if(long) b.l else b.h)
        if(crossed && broken) return event("AMBIGUOUS",known,"BOTH_BOUNDARIES_IN_ONE_BAR")
        if(broken) return event("INVALIDATED",known,"CLOSED_BAR_BOUNDARY")
        if(crossed) return event("TRIGGERED",known,"CLOSED_BAR_EXTREME")
        expected=known
    }
    if(expected<=horizon-frameMs) return event("UNVERIFIED",basis="CLOSED_HISTORY_NOT_PROVEN")
    if(now>=expiresAt) return event("EXPIRED")
    if(hitInvalid(mark)) return event("INVALIDATED",now,"LIVE_MARK")
    if(hitTrigger(mark)) return event("TRIGGERED",now,"LIVE_MARK")
    return event("WAITING")
}

internal data class WilliamsScenarioSignal(
    val id:String,val direction:String,val profile:String,val semantics:String,
    val signalAt:Long,val trigger:Double,val invalidation:Double?,val expiresAt:Long
)
internal data class WilliamsScenarioOpposition(val signal:WilliamsScenarioSignal,val event:WilliamsLevelEvent) {
    fun toJson():JSONObject=JSONObject().put("signal_id",signal.id).put("direction",signal.direction)
        .put("strategy_profile",signal.profile).put("execution_semantics",signal.semantics)
        .put("signal_bar_time_ms",signal.signalAt).put("trigger",signal.trigger)
        .put("invalidation",signal.invalidation ?: JSONObject.NULL).put("event",event.toJson())
}
internal data class WilliamsScenarioAssessment(
    val opposition:WilliamsScenarioOpposition?,val uncertain:List<WilliamsScenarioOpposition>
) {
    val blocker:String? get()=when {
        opposition!=null -> "H1_PARENT_OPPOSITE_TRIGGERED"
        uncertain.any { it.event.state=="AMBIGUOUS" } -> "H1_OPPOSITION_AMBIGUOUS"
        uncertain.isNotEmpty() -> "H1_OPPOSITION_HISTORY_UNVERIFIED"
        else -> null
    }
}

/** Source extractors supply only eligible entry signals / NTD close events.
 * The same evidence revokes an unfilled parent and closes an older position. */
internal fun williamsScenarioOpposition(
    direction:String,startedAt:Long,signals:List<WilliamsScenarioSignal>,
    bars:List<WilliamsWaveBar>,mark:Double,now:Long,frameMs:Long=3_600_000L
):WilliamsScenarioOpposition? {
    if(direction !in listOf("LONG","SHORT") || startedAt<=0 || frameMs<=0) return null
    return assessWilliamsScenarioOpposition(direction,startedAt,signals,bars,mark,now,frameMs).opposition
}

internal fun assessWilliamsScenarioOpposition(
    direction:String,startedAt:Long,signals:List<WilliamsScenarioSignal>,
    bars:List<WilliamsWaveBar>,mark:Double,now:Long,frameMs:Long=3_600_000L
):WilliamsScenarioAssessment {
    require(direction in listOf("LONG","SHORT") && startedAt>0 && frameMs>0) { "INVALID_SCENARIO_SCOPE" }
    val events=signals.filter { it.direction in listOf("LONG","SHORT") && it.direction!=direction }
        .mapNotNull { signal ->
            if(signal.signalAt<=0 || signal.signalAt>Long.MAX_VALUE-frameMs) return@mapNotNull null
            val known=signal.signalAt+frameMs
            if(known<=startedAt || known>now) return@mapNotNull null
            val event=when {
                signal.semantics=="STOP_ENTRY" -> evaluateWilliamsLevelEvent(signal.direction,signal.trigger,
                    known,startedAt,mark,bars,now,signal.expiresAt,frameMs,signal.invalidation)
                signal.semantics=="MARKET_ON_CLOSE" && signal.profile=="NTD_FIVE_DIMENSIONS" &&
                    known>startedAt && now>=known && (now-known)/frameMs<5 ->
                        WilliamsLevelEvent("TRIGGERED",known,"CONFIRMED_CLOSE_EVENT")
                else -> return@mapNotNull null
            }
            WilliamsScenarioOpposition(signal,event)
        }
    return WilliamsScenarioAssessment(events.filter { it.event.state=="TRIGGERED" }
        .minWithOrNull(compareBy<WilliamsScenarioOpposition> { it.event.knownAt }.thenBy { it.signal.id }),
        events.filter { it.event.state in listOf("AMBIGUOUS","UNVERIFIED") })
}
