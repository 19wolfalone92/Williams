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
    "8h" to 28_800_000L, "12h" to 43_200_000L, "1d" to 86_400_000L,
    "1w" to 604_800_000L
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
    val alternatives: MutableList<String> = mutableListOf(),
    var legObservationStart:Long = 0L
) {
    val activeLabel:String get()=when(anchors.size) { 2->"W2";3->"W3";4->"W4";5->"W5";6->"A";7->"B";8->"C";else->"NEXT_SEQUENCE_UNRESOLVED" }
    val activeDirection:String get()=if(activeLabel in listOf("W2","W4","A","C")) {
        if(direction=="UP") "DOWN" else "UP"
    } else direction
    fun toJson(): JSONObject = JSONObject().put("scenario_id", id)
        .put("last_confirmed_leg",label).put("active_label",activeLabel).put("active_direction",activeDirection).put("frame", frame)
        .put("direction", direction).put("label", label)
        .put("anchors", JSONArray(anchors.map { it.toJson() })).put("observed_at", observedAt)
        .put("leg_start", legStart).put("leg_low", legLow).put("leg_high", legHigh)
        .put("leg_observation_start",legObservationStart)
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
    val frameErrors: Map<String, String>, val warnings: List<String>,
    val frameCandleCounts:Map<String,Int> = emptyMap(),
    val complexStructures:List<JSONObject> = emptyList(),
    val adaptiveHistory:JSONObject? = null
) {
    private fun containedChain():Boolean {
        val byId=scenarios.associateBy { it.id }
        fun path(node:String,index:Int):Boolean {
            if (index==williamsRequiredWaveFrames.lastIndex) return true
            val next=williamsRequiredWaveFrames[index+1]
            return links.any {
                val frame=byId[it.child]?.frame
                it.parent==node && frame!=null &&
                    williamsWaveFrames.getValue(byId.getValue(node).frame)>williamsWaveFrames.getValue(frame) &&
                    williamsWaveFrames.getValue(frame)>=williamsWaveFrames.getValue(next) &&
                    path(it.child,if(frame==next) index+1 else index)
            }
        }
        return scenarios.any { it.frame=="1d" && path(it.id,0) }
    }
    fun toJson(): JSONObject = JSONObject().put("schema_version", 1).put("as_of", asOf)
        .put("wave_labels", "HYPOTHESES_NOT_CERTAINTY").put("chain", JSONArray(williamsRequiredWaveFrames))
        .put("scenarios", JSONArray(scenarios.map { it.toJson() }))
        .put("links", JSONArray(links.map { it.toJson() })).put("frame_errors", JSONObject(frameErrors))
        .put("frame_candle_counts",JSONObject(frameCandleCounts)).put("contained_chain_observed",containedChain())
        .put("analysed_frames",JSONArray(frameCandleCounts.keys.sortedByDescending { williamsWaveFrames.getValue(it) }))
        .put("full_history",false).put("can_place_orders",false)
        .put("adaptive_history",adaptiveHistory ?: JSONObject().put("status","NOT_YET_ANALYSED").put("can_place_orders",false))
        .put("complex_wave_patterns", if(complexStructures.isNotEmpty()) JSONArray(complexStructures.take(48)) else JSONArray(scenarios.filter { it.anchors.size >= 6 }
            .flatMap { scenario -> detectWilliamsComplexWave(scenario.anchors,scenario.direction,asOf)
                .map { it.toJson().put("frame",scenario.frame).put("source_scenario_id",scenario.id) } }.take(48)))
        .put("squat_volume_input","KLINE_BASE_VOLUME_PROXY_NOT_CANONICAL_TICK_VOLUME")
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

/**
 * AO proximity is NOT proof of a fourth-wave degree. Exact zero contact
 * counts as a zero-line event; heuristic proximity is only research evidence.
 * Never promote NEAR_ZERO_UNCONFIRMED into MINIMUM_ONLY trade permission.
 */
internal fun classifyWilliamsFourthAo(
    thirdPeak: Double, fourthMinimum: Double, scaleBars: Int
): String {
    if (scaleBars !in 100..140) return "WRONG_SCALE"
    if (!thirdPeak.isFinite() || thirdPeak <= 0.0 ||
        !fourthMinimum.isFinite()) return "MISSING_W3_MOMENTUM"
    if (fourthMinimum <= 0.0) return "MINIMUM_ONLY"
    // 10% of the W3 peak is a declared diagnostic approximation,
    // not an invariant extracted from a Williams book illustration.
    if (fourthMinimum / thirdPeak <= 0.10) return "NEAR_ZERO_UNCONFIRMED"
    return "NO_ZERO_CROSS"
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
    val fourthValues = indexes.filter { it > w3 && bars[it].t <= end && osc[it].isFinite() }
        .map { sign * osc[it] }
    h.w4Zero = classifyWilliamsFourthAo(
        sign * osc[w3], fourthValues.minOrNull() ?: Double.NaN, h.scaleBars
    )
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
        // W4/W1 overlap may be a leading/ending diagonal rather than a
        // normal impulse. Never elevate it to a tradable confirmed count:
        // price containment alone cannot prove diagonal subwave geometry.
        val diagonalOverlap = n >= 5 && price[2] < price[4] && price[4] <= price[1]
        if (n >= 5 && !(price[2] < price[4] && price[4] < price[3])) reasons += "W4_BREACHES_W3_OR_W4_END"
        if (n >= 6) {
            val legs = listOf(price[1]-price[0],price[3]-price[2],price[5]-price[4])
            if (legs.min() <= 0 || legs[1] < minOf(legs[0],legs[2])) reasons += "W3_SHORTEST_OR_NEGATIVE_MOTIVE_LEG"
        }
        // Five motive legs require six confirmed pivot endpoints.
        // Old n->label mapping called completed W5 an A correction.
        val label = if (n <= 6) "W${n-1}" else mapOf(7 to "A",8 to "B",9 to "C").getValue(n)
        if (n >= 7 && price[6] >= price[5]) reasons += "A_NOT_CORRECTIVE"
        if (n >= 8 && price[7] <= price[6]) reasons += "B_NOT_RETRACING_A"
        if (n >= 9 && price[8] >= price[6]) reasons += "C_NOT_EXTENDING_A_COMPLEX_CORRECTION_POSSIBLE"
        val inv = if (n <= 3) p[0].price else if(n<=6 && diagonalOverlap) p[2].price else if (n <= 6) p[1].price else p[5].price
        if (n <= 6 && sign * (bars.last().c-inv) <= 0) reasons += "LIVE_PRICE_INVALIDATED_COUNT"
        val leg = bars.filter { it.t >= p.last().extremeAt }
        val direction = if (sign == 1) "UP" else "DOWN"
        val h = WilliamsWaveScenario("$frame:$direction:${p.first().extremeAt}:$n",frame,direction,label,p,
            bars.last().t+duration,p.last().extremeAt,leg.minOf { it.l },leg.maxOf { it.h },inv,
            if (reasons.isNotEmpty()) "INVALIDATED" else if (diagonalOverlap) "ALTERNATIVE" else "PROVISIONAL",reasons)
        if (diagonalOverlap && reasons.isEmpty()) {
            h.alternatives += "UNVERIFIED_LEADING_OR_ENDING_DIAGONAL_W4_OVERLAP"
            if((price[1]-price[4])/(price[1]-price[0])<=.10)
                h.alternatives += "FUTURES_SHALLOW_W4_OVERLAP_10_PERCENT_W1_RANGE_POLICY"
        }
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
            val expected=parent.activeDirection
            links += WilliamsWaveLink(parent.id,child.id,parent.activeLabel,
                if (child.direction == expected) "ALIGNED" else "COUNTERTREND")
        }
    }
    return links
}

internal fun williamsWaveLegObservationStart(anchor:WilliamsWaveAnchor,frame:String,frames:Map<String,List<WilliamsWaveBar>>):Long {
    val end=anchor.extremeAt+williamsWaveFrames.getValue(frame)
    for(f in frames.keys.sortedBy { williamsWaveFrames.getValue(it) }) {
        val duration=williamsWaveFrames.getValue(f)
        if(duration>williamsWaveFrames.getValue(frame)) continue
        val part=frames.getValue(f).filter { it.t>=anchor.extremeAt && it.t<end }
        if(part.isEmpty() || part.first().t!=anchor.extremeAt || part.last().t+duration!=end) continue
        val matches=part.filter { kotlin.math.abs((if(anchor.kind=="HIGH") it.h else it.l)-anchor.price)<=maxOf(1e-9,anchor.price*1e-9) }
        if(matches.isNotEmpty()) return matches.last().t+duration
    }
    return end
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
        val hypotheses = williamsHypothesesFromAnchors(frame,bars,
            confirmedWilliamsWaveAnchors(bars,williamsWaveFrames.getValue(frame)))
        for (h in hypotheses) {
            h.legObservationStart=williamsWaveLegObservationStart(h.anchors.last(),frame,valid)
            val observed = valid.filterKeys { williamsWaveFrames.getValue(it) <= williamsWaveFrames.getValue(frame) }
                .flatMap { (f,series) -> series.filter { it.t >= h.legObservationStart && it.t + williamsWaveFrames.getValue(f) <= asOf } }
            h.observedAt = asOf
            if (observed.isNotEmpty()) {
                h.legLow = minOf(h.anchors.last().price,observed.minOf { it.l }); h.legHigh = maxOf(h.anchors.last().price,observed.maxOf { it.h })
                if (h.anchors.size <= 6 && (h.direction == "UP" && observed.minOf { it.l } <= h.invalidation ||
                    h.direction == "DOWN" && observed.maxOf { it.h } >= h.invalidation)) {
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
    val structures=mutableListOf<JSONObject>()
    val anchorsByFrame=valid.mapValues { (frame,bars)->confirmedWilliamsWaveAnchors(bars,williamsWaveFrames.getValue(frame)) }
    for(frame in valid.keys) {
        val childFrame=valid.keys.filter { williamsWaveFrames.getValue(it)<williamsWaveFrames.getValue(frame) }
            .maxByOrNull { williamsWaveFrames.getValue(it) }.orEmpty()
        val points=anchorsByFrame.getValue(frame)
        for(direction in listOf("UP","DOWN")) for(evidence in detectWilliamsComplexWave(points,direction,asOf)) {
            val parent=points.filter { it.extremeAt in evidence.startAt..evidence.endAt }
            val proof=enrichWilliamsComplexSubdivisions(evidence.pattern,parent,anchorsByFrame[childFrame].orEmpty(),
                williamsWaveFrames.getValue(frame),asOf)
            structures+=evidence.toJson().put("frame",frame).put("child_frame",childFrame)
                .put("source_scenario_id","$frame:$direction:${evidence.startAt}:${parent.size}").put("internal_proof",proof.toJson())
        }
    }
    return WilliamsWaveHierarchy(asOf,scenarios,linkWilliamsWaveHypotheses(scenarios),errors,warnings,valid.mapValues { it.value.size },structures)
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
        if (!(senior.frame == "6h" && senior.direction != direction && senior.activeLabel in listOf("W4","A","C") &&
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
                it.activeLabel in listOf("W3","W5") && it.w4Zero != "MINIMUM_ONLY" &&
                it.anchors.first().extremeAt <= senior.anchors[3].extremeAt }
            if (conflict) return WilliamsCorrectionContext(reason="COMPETING_SENIOR_IMPULSE_UNRESOLVED")
            val bounds = if (senior.activeLabel == "W4") listOf(senior.anchors[1].price,senior.anchors[3].price).sorted()
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
    subsequent: List<WilliamsWaveBar>, now: Long, expiresAt: Long,
    frameMs:Long=3_600_000L,invalidation:Double?=null
): Boolean = evaluateWilliamsLevelEvent(side,trigger,signalClosedAt,openedAt,mark,subsequent,
    now,expiresAt,frameMs,invalidation).state=="TRIGGERED"
