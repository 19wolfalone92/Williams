package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** TC1 ch.7, NTD ch.9, TC2 ch.9-12. Labels are competing causal hypotheses.
 * Timestamp/price containment is observable; an infallible Elliott count is not.
 * This module cannot submit, cancel or reverse an exchange order. */
internal val williamsWaveFrames = linkedMapOf(
    "1m" to 60_000L,"3m" to 180_000L,"5m" to 300_000L,
    "15m" to 900_000L, "30m" to 1_800_000L, "1h" to 3_600_000L,
    "2h" to 7_200_000L, "4h" to 14_400_000L, "6h" to 21_600_000L,
    "8h" to 28_800_000L, "12h" to 43_200_000L, "1d" to 86_400_000L
)
internal val williamsRequiredWaveFrames = listOf("1d", "6h", "1h", "15m")
internal data class WilliamsWaveBar(
    val t: Long, val o: Double, val h: Double, val l: Double, val c: Double, val v: Double = 0.0
)
internal data class WilliamsWaveAnchor(
    val kind: String, val price: Double, val index: Int, val extremeAt: Long, val knownAt: Long
) {
    fun toJson(): JSONObject = JSONObject().put("kind", kind).put("price", price)
        .put("index", index).put("extreme_at", extremeAt).put("known_at", knownAt)
}
internal data class WilliamsWaveScenario(
    val id: String, val frame: String, val direction: String, val label: String,
    val anchors: List<WilliamsWaveAnchor>, var observedAt: Long, val legStart: Long,
    var legLow: Double, var legHigh: Double, val invalidation: Double,
    var status: String = "PROVISIONAL", val invalidReasons: MutableList<String> = mutableListOf(),
    var scaleFrame: String = "", var scaleBars: Int = 0, var w4Zero: String = "UNCONFIRMED",
    var divergence: Boolean = false, var momentumShift: Boolean = false,
    var terminalFractal: Boolean = false, var squat: Boolean = false, var targetZone: Boolean = false,
    val alternatives: MutableList<String> = mutableListOf()
) {
    fun toJson(): JSONObject = JSONObject().put("scenario_id", id).put("frame", frame)
        .put("direction", direction).put("label", label)
        .put("anchors", JSONArray(anchors.map { it.toJson() })).put("observed_at", observedAt)
        .put("leg_start", legStart).put("leg_low", legLow).put("leg_high", legHigh)
        .put("invalidation", invalidation).put("status", status)
        .put("invalid_reasons", JSONArray(invalidReasons)).put("scale_frame", scaleFrame)
        .put("scale_bars", scaleBars).put("w4_zero", w4Zero).put("divergence", divergence)
        .put("momentum_shift", momentumShift).put("terminal_fractal", terminalFractal)
        .put("squat", squat).put("target_zone", targetZone).put("alternatives", JSONArray(alternatives))
}
internal data class WilliamsWaveLink(val parent: String, val child: String, val parentLeg: String, val relationship: String) {
    fun toJson(): JSONObject = JSONObject().put("parent", parent).put("child", child)
        .put("parent_leg", parentLeg).put("relationship", relationship)
        .put("proof", "TIME_AND_PRICE_CONTAINMENT").put("wave_genealogy_confirmed", false)
}
internal data class WilliamsWaveHierarchy(
    val asOf: Long, val scenarios: List<WilliamsWaveScenario>, val links: List<WilliamsWaveLink>,
    val frameErrors: Map<String, String>, val warnings: List<String>
) {
    fun toJson(): JSONObject = JSONObject().put("schema_version", 1).put("as_of", asOf)
        .put("wave_labels", "HYPOTHESES_NOT_CERTAINTY").put("chain", JSONArray(williamsRequiredWaveFrames))
        .put("scenarios", JSONArray(scenarios.map { it.toJson() }))
        .put("links", JSONArray(links.map { it.toJson() })).put("frame_errors", JSONObject(frameErrors))
        .put("warnings", JSONArray(warnings.map {
            JSONObject().put("scenario_id", it).put("state", "EXHAUSTION_WARNING")
                .put("can_close", false).put("can_reverse", false)
        }))
}

internal fun closedWilliamsWaveBars(bars: List<WilliamsWaveBar>, frame: String, asOf: Long): List<WilliamsWaveBar> {
    val duration = requireNotNull(williamsWaveFrames[frame]) { "UNSUPPORTED_FRAME" }
    val closed = bars.filter { it.t <= asOf - duration }
    require(closed.size >= 40) { "INSUFFICIENT_CLOSED_HISTORY" }
    for ((i, b) in closed.withIndex()) {
        require(b.t >= 0 && listOf(b.o, b.h, b.l, b.c, b.v).all { it.isFinite() } &&
            minOf(b.o, b.l, b.c) > 0 && b.h >= maxOf(b.o, b.c, b.l) &&
            b.l <= minOf(b.o, b.c) && b.v >= 0) { "INVALID_OHLCV" }
        require(i == 0 || b.t - closed[i-1].t == duration) { "GAPPED_OR_UNORDERED_HISTORY" }
    }
    require(asOf - (closed.last().t + duration) < duration * 2) { "STALE_CLOSED_HISTORY" }
    return closed
}

internal fun confirmedWilliamsWaveAnchors(bars: List<WilliamsWaveBar>, duration: Long): List<WilliamsWaveAnchor> {
    val raw = mutableListOf<WilliamsWaveAnchor>()
    for (i in 2 until bars.size - 2) {
        val matches = mutableListOf<WilliamsWaveAnchor>()
        for (kind in listOf("LOW", "HIGH")) {
            val extreme = if (kind == "LOW") bars[i].l else bars[i].h
            var confirmation: Int? = null
            var good = true
            for (step in listOf(-1, 1)) {
                var count = 0
                var j = i + step
                while (j in bars.indices && count < 2) {
                    val value = if (kind == "LOW") bars[j].l else bars[j].h
                    if (value == extreme) {
                        if (step == 1) { good = false; break }
                    } else if ((value > extreme) == (kind == "LOW")) count++
                    else { good = false; break }
                    if (step == 1 && count == 2) confirmation = j
                    j += step
                }
                good = good && count == 2
            }
            if (good && confirmation != null) matches += WilliamsWaveAnchor(
                kind, extreme, i, bars[i].t, bars[confirmation].t + duration
            )
        }
        // A simultaneous high/low has no knowable intrabar order.
        if (matches.size == 1) raw += matches
    }
    val alternating = mutableListOf<WilliamsWaveAnchor>()
    for (p in raw) {
        if (alternating.isEmpty() || p.kind != alternating.last().kind) alternating += p
        else if (if (p.kind=="HIGH") p.price>alternating.last().price else p.price<alternating.last().price)
            alternating[alternating.lastIndex] = p
    }
    return alternating
}

private fun waveAo(bars: List<WilliamsWaveBar>): List<Double> {
    val mid = bars.map { (it.h + it.l) / 2 }
    return bars.indices.map { i -> if (i < 33) Double.NaN
        else (i-4..i).sumOf { mid[it] } / 5 - (i-33..i).sumOf { mid[it] } / 34 }
}

private fun enrichWaveRhythm(h: WilliamsWaveScenario, frames: Map<String, List<WilliamsWaveBar>>) {
    if (h.anchors.size < 4) return
    val start = h.anchors.first().extremeAt
    val stop = h.observedAt
    val options = frames.mapNotNull { (frame, bars) ->
        val indexes = bars.indices.filter { bars[it].t >= start && bars[it].t < stop }
        if (indexes.isEmpty() || indexes.first() < 33 || bars.first().t > start) null else frame to indexes
    }
    val choice = options.minWithOrNull(compareBy<Pair<String, List<Int>>> { if (it.second.size in 100..140) 0 else 1 }
        .thenBy { abs(it.second.size - 120) }.thenBy { it.first })
    if (choice == null) { h.w4Zero = "MISSING_SCALE_HISTORY"; return }
    val (frame, indexes) = choice
    h.scaleFrame = frame
    h.scaleBars = indexes.size
    val bars = frames.getValue(frame)
    val osc = waveAo(bars)
    val sign = if (h.direction == "UP") 1 else -1
    val p = h.anchors
    var thirdEnd = p[3].extremeAt + 5 * williamsWaveFrames.getValue(frame)
    if (p.size >= 5) thirdEnd = minOf(thirdEnd, p[4].extremeAt)
    val w3Indexes = indexes.filter { bars[it].t in p[2].extremeAt..thirdEnd }
    if (w3Indexes.isEmpty()) { h.w4Zero = "MISSING_W3_MOMENTUM"; return }
    val w3 = w3Indexes.maxBy { sign * osc[it] }
    val end = if (p.size >= 5) p[4].extremeAt else stop
    val cross = indexes.any { it > w3 && bars[it].t <= end && sign * osc[it] < 0 }
    h.w4Zero = when {
        h.scaleBars !in 100..140 -> "WRONG_SCALE"
        sign * osc[w3] <= 0 -> "MISSING_W3_MOMENTUM"
        cross -> "MINIMUM_ONLY"
        else -> "NO_ZERO_CROSS"
    }
    if (p.size >= 6) {
        fun peak(a: Long, b: Long): Double? = indexes.filter { bars[it].t in a..b && osc[it].isFinite() }
            .maxOfOrNull { sign * osc[it] }
        val third = peak(p[2].extremeAt, thirdEnd)
        val fifth = peak(p[4].extremeAt, minOf(stop, p[5].extremeAt + 5 * williamsWaveFrames.getValue(frame)))
        h.divergence = third != null && fifth != null && fifth > 0 && fifth < third && sign * (p[5].price-p[3].price) > 0
        val extension = abs(p[3].price-p[0].price)
        val t1 = p[4].price + sign * extension * .62
        val t2 = p[4].price + sign * extension
        h.targetZone = p[5].price in minOf(t1,t2)..maxOf(t1,t2)
    }
    if (h.w4Zero != "MINIMUM_ONLY") h.alternatives += "W3_EXTENSION_OR_WRONG_DEGREE"
}

internal fun williamsHypothesesFromAnchors(
    frame: String, bars: List<WilliamsWaveBar>, anchors: List<WilliamsWaveAnchor>
): List<WilliamsWaveScenario> {
    val out = mutableListOf<WilliamsWaveScenario>()
    val osc = waveAo(bars)
    val duration = williamsWaveFrames.getValue(frame)
    for (n in 2..minOf(9, anchors.size)) {
        val p = anchors.takeLast(n)
        val sign = if (p[0].kind == "LOW") 1 else -1
        val price = p.map { sign * it.price }
        if (p.any { it.knownAt > bars.last().t + duration } ||
            p.zipWithNext().any { (a,b) -> a.extremeAt >= b.extremeAt }) continue
        val reasons = mutableListOf<String>()
        if (price[1] <= price[0]) reasons += "W1_NOT_IMPULSIVE"
        if (n >= 3 && !(price[0] < price[2] && price[2] < price[1])) reasons += "W2_INVALIDATES_ORIGIN"
        if (n >= 4 && price[3] <= price[1]) reasons += "W3_DOES_NOT_EXTEND_W1"
        if (n >= 5 && !(price[1] < price[4] && price[4] < price[3])) reasons += "CLASSIC_W4_OVERLAP_OR_INVALID_RETRACEMENT"
        if (n >= 6) {
            val legs = listOf(price[1]-price[0],price[3]-price[2],price[5]-price[4])
            if (legs.min() <= 0 || legs[1] < minOf(legs[0],legs[2])) reasons += "W3_SHORTEST_OR_NEGATIVE_MOTIVE_LEG"
        }
        val label = if (n <= 5) "W$n" else mapOf(6 to "A",7 to "B",8 to "C",9 to "ABC_COMPLETE").getValue(n)
        if (n >= 7 && price[6] >= price[5]) reasons += "A_NOT_CORRECTIVE"
        if (n >= 8 && price[7] <= price[6]) reasons += "B_NOT_RETRACING_A"
        if (n >= 9 && price[8] >= price[6]) reasons += "C_NOT_EXTENDING_A_COMPLEX_CORRECTION_POSSIBLE"
        val inv = if (n <= 3) p[0].price else if (n <= 5) p[1].price else p[5].price
        if (n <= 5 && sign * (bars.last().c-inv) <= 0) reasons += "LIVE_PRICE_INVALIDATED_COUNT"
        val leg = bars.filter { it.t >= p.last().extremeAt }
        val direction = if (sign == 1) "UP" else "DOWN"
        val h = WilliamsWaveScenario("$frame:$direction:${p.first().extremeAt}:$n",frame,direction,label,p,
            bars.last().t+duration,p.last().extremeAt,leg.minOf { it.l },leg.maxOf { it.h },inv,
            if (reasons.isEmpty()) "PROVISIONAL" else "INVALIDATED",reasons)
        val activeSign = if (n in listOf(2,4,6,8)) -sign else sign
        if (n < 9 && reasons.isEmpty() && activeSign * (bars.last().c-p.last().price) <= 0) {
            h.status = "UNRESOLVED"; h.alternatives += "PRIOR_LEG_EXTENSION_OR_UNCONFIRMED_TURN"
        }
        if (n >= 6) {
            h.terminalFractal = true
            h.alternatives += if (price[5] <= price[3]) "TRUNCATED_W5" else "EXTENDED_IMPULSE"
        }
        if (n >= 8 && price[7] >= price[5]) h.alternatives += "IRREGULAR_CORRECTION"
        val lastAo = osc.takeLast(5).filter { it.isFinite() }
        h.momentumShift = lastAo.size == 5 && sign * lastAo.last() < sign * lastAo.average() &&
            sign * lastAo.last() < sign * lastAo[lastAo.lastIndex-1]
        val terminal = p[minOf(5,n-1)].index
        for (j in maxOf(1,terminal-1)..minOf(bars.lastIndex,terminal+1)) {
            val a = bars[j-1]; val b = bars[j]
            if (a.v > 0 && b.v > a.v && (b.h-b.l)/b.v < (a.h-a.l)/a.v) h.squat = true
        }
        out += h
    }
    return out
}

internal fun linkWilliamsWaveHypotheses(scenarios: List<WilliamsWaveScenario>): List<WilliamsWaveLink> {
    val links = mutableListOf<WilliamsWaveLink>()
    for (child in scenarios.filter { it.status == "PROVISIONAL" }) {
        val parents = scenarios.filter { p -> p.status == "PROVISIONAL" &&
            williamsWaveFrames.getValue(p.frame) > williamsWaveFrames.getValue(child.frame) &&
            p.legStart <= child.anchors.first().extremeAt && child.observedAt <= p.observedAt &&
            child.anchors.all { it.price in p.legLow..p.legHigh } }
        val nearest = parents.minOfOrNull { williamsWaveFrames.getValue(it.frame) } ?: continue
        for (parent in parents.filter { williamsWaveFrames.getValue(it.frame) == nearest }) {
            val expected = if (parent.label in listOf("W2","W4","A","C")) {
                if (parent.direction == "UP") "DOWN" else "UP"
            } else parent.direction
            links += WilliamsWaveLink(parent.id,child.id,parent.label,
                if (child.direction == expected) "ALIGNED" else "COUNTERTREND")
        }
    }
    return links
}

internal fun buildWilliamsWaveHierarchy(frames: Map<String,List<WilliamsWaveBar>>, asOf: Long): WilliamsWaveHierarchy {
    val valid = linkedMapOf<String,List<WilliamsWaveBar>>()
    val errors = linkedMapOf<String,String>()
    for (frame in (frames.keys + williamsRequiredWaveFrames).sortedByDescending { williamsWaveFrames[it] ?: 0 }) {
        runCatching { closedWilliamsWaveBars(frames[frame].orEmpty(),frame,asOf) }
            .onSuccess { valid[frame] = it }.onFailure { errors[frame] = it.message ?: "INVALID_HISTORY" }
    }
    val scenarios = mutableListOf<WilliamsWaveScenario>()
    val warnings = mutableListOf<String>()
    for ((frame,bars) in valid) {
        if (frame !in williamsRequiredWaveFrames) continue
        val hypotheses = williamsHypothesesFromAnchors(frame,bars,
            confirmedWilliamsWaveAnchors(bars,williamsWaveFrames.getValue(frame)))
        for (h in hypotheses) {
            val observed = valid.filterKeys { williamsWaveFrames.getValue(it) <= williamsWaveFrames.getValue(frame) }
                .flatMap { (f,series) -> series.filter { it.t >= h.legStart && it.t + williamsWaveFrames.getValue(f) <= asOf } }
            h.observedAt = asOf
            if (observed.isNotEmpty()) {
                h.legLow = observed.minOf { it.l }; h.legHigh = observed.maxOf { it.h }
                if (h.anchors.size <= 5 && (h.direction == "UP" && h.legLow <= h.invalidation ||
                    h.direction == "DOWN" && h.legHigh >= h.invalidation)) {
                    h.status = "INVALIDATED"; h.invalidReasons += "LOWER_FRAME_OBSERVED_INVALIDATION"
                }
            }
            enrichWaveRhythm(h,valid)
            if (h.status == "PROVISIONAL" && h.momentumShift && h.anchors.size >= 4) warnings += h.id
        }
        scenarios += hypotheses
        val osc = waveAo(bars)
        if (osc.size >= 40 && osc.takeLast(5).all { it.isFinite() }) {
            val sign = if (osc.last() > 0) 1 else -1
            if (sign * osc.last() < sign * osc[osc.lastIndex-1] &&
                sign * osc[osc.lastIndex-1] < sign * osc[osc.lastIndex-2]) warnings += "$frame:MOMENTUM_FADE_BEFORE_PIVOT"
        }
    }
    return WilliamsWaveHierarchy(asOf,scenarios,linkWilliamsWaveHypotheses(scenarios),errors,warnings)
}

internal fun suggestedWilliamsAnalyticalFrames(report: WilliamsWaveHierarchy): List<String> {
    val choices=mutableListOf<String>()
    for (frame in listOf("6h","1d","1h")) {
        val h=report.scenarios.filter { it.frame==frame && it.status=="PROVISIONAL" &&
            it.anchors.size>=4 && it.w4Zero!="MINIMUM_ONLY" }.maxByOrNull { it.anchors.size } ?: continue
        val choice=suggestWilliamsWaveScale(report.asOf-h.anchors.first().extremeAt,williamsWaveFrames)
        if (choice?.withinBookRange==true && choice.timeframe !in choices) choices+=choice.timeframe
    }
    return choices.take(2)
}

internal data class WilliamsCorrectionContext(
    val allowed: Boolean = false, val reason: String = "NO_CONFIRMED_CORRECTION_PATH",
    val parentId: String = "", val parentInvalidation: Double = 0.0, val decisionId: String = "",
    val parentFloor: Double = 0.0, val parentCeiling: Double = 0.0
) {
    fun toJson(): JSONObject = JSONObject().put("allowed",allowed).put("mode","CORRECTION").put("reason",reason)
        .put("parent_scenario_id",parentId).put("parent_invalidation",parentInvalidation).put("decision_scenario_id",decisionId)
        .put("parent_floor",parentFloor).put("parent_ceiling",parentCeiling)
}
internal fun williamsCorrectionEntryContext(
    report: WilliamsWaveHierarchy, side: String, signalAt: Long, signalType: String
): WilliamsCorrectionContext {
    val direction = when (side) { "LONG","BUY" -> "UP"; "SHORT","SELL" -> "DOWN"; else -> "" }
    if (direction.isEmpty() || signalType !in listOf("REVERSAL","SHORT_REVERSAL","SUPER_AO","SHORT_SUPER_AO","FRACTAL","SHORT_FRACTAL"))
        return WilliamsCorrectionContext(reason="CORRECTION_REQUIRES_H1_WISE_MAN")
    if (report.frameErrors.isNotEmpty())
        return WilliamsCorrectionContext(reason="CORRECTION_HISTORY_INCOMPLETE")
    if (signalAt <= 0 || signalAt + 3_600_000L > report.asOf || report.asOf-signalAt > 18_000_000L)
        return WilliamsCorrectionContext(reason="CORRECTION_H1_SIGNAL_NOT_CLOSED_OR_FRESH")
    val approvals=linkedMapOf<String,WilliamsCorrectionContext>()
    val byId = report.scenarios.filter { it.status == "PROVISIONAL" }.associateBy { it.id }
    for (senior in byId.values) {
        if (!(senior.frame == "6h" && senior.direction != direction && senior.label in listOf("W4","A","C") &&
            senior.w4Zero == "MINIMUM_ONLY" && senior.momentumShift &&
            senior.anchors[3].knownAt <= signalAt + 3_600_000L)) continue
        val daily = report.links.any { it.child == senior.id && byId[it.parent]?.frame == "1d" }
        val decisions = report.links.filter { it.parent == senior.id && it.relationship == "ALIGNED" }
            .mapNotNull { byId[it.child] }.filter { it.frame == "1h" && it.direction == direction }
        for (decision in decisions) {
            val junior = report.links.any { it.parent == decision.id && it.relationship == "ALIGNED" &&
                byId[it.child]?.let { s -> s.frame == "15m" && s.direction == direction } == true }
            if (!daily || !junior) continue
            val conflict = byId.values.any { it.frame == "6h" && it.direction == senior.direction &&
                it.label in listOf("W3","W5") && it.w4Zero != "MINIMUM_ONLY" &&
                it.anchors.first().extremeAt <= senior.anchors[3].extremeAt }
            if (conflict) return WilliamsCorrectionContext(reason="COMPETING_SENIOR_IMPULSE_UNRESOLVED")
            val bounds = if (senior.label == "W4") listOf(senior.anchors[1].price,senior.anchors[3].price).sorted()
                else {
                    val terminal = listOf(senior.anchors[5].price) +
                        (if (senior.anchors.size >= 8) listOf(senior.anchors[7].price) else emptyList())
                    if (senior.direction == "UP") listOf(0.0,terminal.max()) else listOf(terminal.min(),0.0)
                }
            approvals[senior.id] = WilliamsCorrectionContext(true,"H1_WISE_MAN_IN_CONTAINED_H6_CORRECTION",senior.id,
                senior.invalidation,decision.id,bounds[0],bounds[1])
        }
    }
    if (approvals.size==1) return approvals.values.first()
    if (approvals.size>1) return WilliamsCorrectionContext(reason="COMPETING_CORRECTION_PARENTS_UNRESOLVED")
    return WilliamsCorrectionContext()
}

internal fun williamsCorrectionPriceAllowed(context: JSONObject, mark: Double): Boolean {
    val lower = context.optDouble("parent_floor",Double.NaN)
    val upper = context.optDouble("parent_ceiling",Double.NaN)
    return williamsCorrectionBoundsValid(context) && mark.isFinite() && mark > 0 &&
        (lower == 0.0 || mark > lower) && (upper == 0.0 || mark < upper) && (lower > 0 || upper > 0)
}

internal fun williamsCorrectionBoundsValid(context: JSONObject): Boolean {
    val lower = context.optDouble("parent_floor",Double.NaN)
    val upper = context.optDouble("parent_ceiling",Double.NaN)
    return lower.isFinite() && upper.isFinite() && lower >= 0 && upper >= 0 && (lower > 0 || upper > 0) &&
        (lower == 0.0 || upper == 0.0 || lower < upper)
}

internal fun williamsOpposingLevelTriggered(
    side: String, trigger: Double, signalClosedAt: Long, openedAt: Long, mark: Double,
    subsequent: List<WilliamsWaveBar>, now: Long, expiresAt: Long
): Boolean {
    if (side !in listOf("LONG","SHORT") || !trigger.isFinite() || trigger <= 0 ||
        !mark.isFinite() || mark <= 0 || openedAt <= 0 || signalClosedAt <= openedAt ||
        signalClosedAt > now || now >= expiresAt) return false
    return if (side == "LONG") mark >= trigger || subsequent.any { it.t >= signalClosedAt && it.h >= trigger }
    else mark <= trigger || subsequent.any { it.t >= signalClosedAt && it.l <= trigger }
}
