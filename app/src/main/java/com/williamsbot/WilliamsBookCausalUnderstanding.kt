package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

/**
 * Finite, source-cited expert reasoning over already captured H1 signals and
 * authenticated trade HISTORY. This is NOT an LLM, a backtest, book certification
 * or a second trading engine. All claims fail closed when their required
 * observations are stale, contradictory or absent.
 *
 * Book page coordinates are physical PDF pages in the owner's four source files.
 * No page text or images are shipped in the APK.
 */
internal object WilliamsBookCausalUnderstanding {
    private data class Rule(
        val id:String, val book:String, val pages:List<Int>, val meaning:String,
        val sourceStatus:String
    )
    private val rules=listOf(
        Rule("TC2.WM1.DIVERGENT_BAR", "TC2", listOf(122,123,124,125),
            "Countertrend reversal beyond whole Alligator mouth, strict upper/lower-half close.",
            "BOUNDED_SOURCE_RULE"),
        Rule("TC2.WM1.ANGULATION", "TC2", listOf(122,123,124),
            "Price path must angle away from Jaw and Teeth, not run parallel after mouth passage.",
            "ENGINEERING_NUMERICAL_PROXY_NOT_VISUAL_CERTIFICATION"),
        Rule("TC2.WM1.AO_CONTEXT", "TC2", listOf(125,131),
            "Countertrend WM1 takes the AO colour of the previous move, distinct from AO sign.",
            "BOUNDED_SOURCE_RULE"),
        Rule("TC2.WM2.THREE_AO_BARS", "TC2", listOf(135,136,137,139),
            "Three consecutive same-colour AO bars (three changes in value); the third identifies pending entry.",
            "BOUNDED_SOURCE_RULE"),
        Rule("TC2.WM3.ACTIVATION_TEETH", "TC2", listOf(145,146,147),
            "Fractal must BREAK beyond Teeth rather than form beyond Teeth.",
            "ACTUAL_TRIGGER_NOT_PROVED_BY_SCANNER"),
        Rule("TC2.FRACTAL.EQUAL_EXTREMES", "TC2", listOf(145,146),
            "Five- to nine-bar tied extrema with two strict confirming neighbours per side.",
            "SYNTHETIC_FIGURES_TESTED_NOT_EXHAUSTIVE"),
        Rule("TC2.STOP.THREE_OR_FIVE", "TC2", listOf(131,132,156,165),
            "First protective stop and 3/5-bar trailing are separate book decisions.",
            "ACTUAL_SIGNED_BINANCE_STOP_NOT_BOOK_VERIFIED"),
        Rule("TC2.EXIT.STOP_AND_REVERSE", "TC2", listOf(153,154,155,156,163),
            "Opposite Wise Man TRIGGER can prompt exit/reversal; not proof senior trend ended.",
            "IMPLEMENTED_PARTIAL_NO_AUTOMATIC_REVERSE"),
        Rule("TC1.MFI.RANGE_OVER_VOLUME", "TC1", listOf(40,41,42,43,44,45),
            "Original tick volume differs from crypto base-asset volume; MFI windows remain proxies.",
            "PROXY_OBSERVE_ONLY"),
        Rule("NTD.FIRST_FRACTAL", "NTD", listOf(38,39,40,41,42,43),
            "NTD first external fractal sequence does not override TC2 first-presenting Wise Man.",
            "OBSERVE_ONLY"),
        Rule("NTD.INDICATORS", "NTD", listOf(55,56,72,73,91,92,93,94),
            "AO, AC, zone and Balance Line require NTD-specific sequence and source policy.",
            "OBSERVE_ONLY"),
        Rule("COURSE.HUMP", "COURSE", listOf(61,62,63,64,65,66,67),
            "Hump formation includes distinct Start and Signal ownership.",
            "OCR_VISUAL_CASES_NOT_FULLY_CERTIFIED"),
        Rule("COURSE.THUMB.FINGERS", "COURSE", listOf(69,70,71),
            "Three structural fingers exclude inner bars and require volume confirmation.",
            "OCR_VISUAL_CASES_NOT_FULLY_CERTIFIED"),
        Rule("COURSE.RHYTHM", "COURSE", listOf(42,43,44,45,87,88,89,90),
            "5/13/34 wave rhythm and phase must be interpreted in native Course context.",
            "OCR_VISUAL_CASES_NOT_FULLY_CERTIFIED")
    )
    private fun rows(a:JSONArray?):List<JSONObject> =
        if(a==null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    private fun family(type:String):String = type.removePrefix("LONG_").removePrefix("SHORT_")
    private fun direction(type:String):String = if(type.startsWith("SHORT_")) "SHORT" else "LONG"
    private fun tc2Rule(family:String):String = when(family) {
        "REVERSAL" -> "TC2.WM1.DIVERGENT_BAR"
        "SUPER_AO" -> "TC2.WM2.THREE_AO_BARS"
        "FRACTAL" -> "TC2.WM3.ACTIVATION_TEETH"
        else -> "UNMAPPED_WILLIAMS_FAMILY"
    }
    private fun ruleCatalog():JSONArray=JSONArray(rules.map { rule ->
        JSONObject().put("rule_id",rule.id).put("book",rule.book)
            .put("pdf_pages",JSONArray(rule.pages))
            .put("source_status",rule.sourceStatus)
            .put("interpretation",rule.meaning)
            .put("whole_book_certified",false)
            .put("can_place_orders",false)
    })
    /**
     * Independently recompute TC2 third-AO from the real, chronological
     * CLOSED H1 candles copied into the decision trace, not from the native
     * producer's own saved 'third_ao_bar_at_ms' assertion. This detects false
     * self-certification when those two sources disagree.
     *
     * Return NOT_VERIFIED when insufficient source history is retained. Never
     * manufacture a book PASS from a signal-only object.
     */
    private fun independentThirdAoProof(
        type:String,signalBarAt:Long,closedH1At:Long,candles:JSONArray?
    ):String {
        if(candles==null || candles.length()<37 || candles.length()>160) return "NOT_VERIFIED"
        data class Bar(val at:Long,val mid:Double)
        val bars=mutableListOf<Bar>()
        for(i in 0 until candles.length()) {
            val row=candles.optJSONArray(i) ?: return "NOT_VERIFIED"
            if(row.length()<6) return "NOT_VERIFIED"
            val at=row.optLong(0,-1L)
            val open=row.optDouble(1,Double.NaN)
            val high=row.optDouble(2,Double.NaN)
            val low=row.optDouble(3,Double.NaN)
            val close=row.optDouble(4,Double.NaN)
            if(at<0L || at%WILLIAMS_H1_MS!=0L ||
                at>Long.MAX_VALUE-WILLIAMS_H1_MS ||
                at+WILLIAMS_H1_MS>closedH1At ||
                listOf(open,high,low,close).any { !it.isFinite() || it<=0.0 } ||
                high<maxOf(open,low,close) || low>minOf(open,close) ||
                bars.lastOrNull()?.let { at-it.at != WILLIAMS_H1_MS }==true)
                return "NOT_VERIFIED"
            bars+=Bar(at,(high+low)/2.0)
        }
        val index=bars.indexOfFirst { it.at==signalBarAt }
        if(index<36) return "NOT_VERIFIED"
        fun ao(i:Int):Double {
            fun mean(n:Int)=bars.subList(i-n+1,i+1).sumOf { it.mid }/n
            return mean(5)-mean(34)
        }
        val history=(index-3..index).map(::ao)
        if(history.any { !it.isFinite() }) return "NOT_VERIFIED"
        val rising=type.removePrefix("SHORT_")=="SUPER_AO" && !type.startsWith("SHORT_")
        val falling=type=="SHORT_SUPER_AO"
        if(!rising && !falling) return "NOT_VERIFIED"
        return if(history.zipWithNext().all { (a,b) ->
                if(rising) b>a else b<a }) "PASS" else "FAIL"
    }

    /**
     * A signal that is only present in a scanner is not a filled order. A
     * scanner's first-party setup_context is useful evidence but is not an
     * independent rereading of the author's figure from a real chart.
     */
    private fun signalEvidence(
        signal:JSONObject, rawDetail:JSONObject?, closedH1At:Long, nowMs:Long,
        closedH1Source:JSONArray?
    ):JSONObject {
        val profile=signal.optString("strategy_profile")
        val type=signal.optString("type")
        val f=family(type)
        val side=direction(type)
        val at=signal.optLong("signal_bar_time_ms",0L)
        val trigger=signal.optDouble("trigger_price",Double.NaN)
        val stop=signal.optDouble("protective_price",Double.NaN)
        val hasCausalBar=at>0L && at<=Long.MAX_VALUE-WILLIAMS_H1_MS &&
            at+WILLIAMS_H1_MS<=closedH1At && closedH1At<=nowMs
        val validRiskLevels=trigger.isFinite() && stop.isFinite() && trigger>0 && stop>0 &&
            (if(side=="LONG") stop<trigger else stop>trigger)
        val context=rawDetail?.optJSONObject("setup_context") ?: JSONObject()
        val rule=if(profile==williamsTc2Profile) tc2Rule(f) else when(profile) {
            williamsNtdProfile -> if(f=="FRACTAL") "NTD.FIRST_FRACTAL" else "NTD.INDICATORS"
            williamsCourseProfile -> when(f) {
                "COURSE_HUMP" -> "COURSE.HUMP"
                "COURSE_THUMB" -> "COURSE.THUMB.FINGERS"
                else -> "COURSE.RHYTHM"
            }
            else -> "UNKNOWN_SOURCE"
        }
        val independentAo=if(profile==williamsTc2Profile && f=="SUPER_AO")
            independentThirdAoProof(type,at,closedH1At,closedH1Source)
        else "NOT_APPLICABLE"
        val hasInternalProof=when {
            profile!=williamsTc2Profile -> false
            f=="REVERSAL" ->
                context.optBoolean("wm1_ao_qualifies_book_countertrend") &&
                    context.optJSONObject("angulation")!=null
            f=="SUPER_AO" ->
                context.optLong("third_ao_bar_at_ms",-1L)==at &&
                    context.optString("initial_stop_rule")=="TC2_SUPER_AO_SIGNAL_BAR" &&
                    independentAo=="PASS"
            f=="FRACTAL" ->
                (rawDetail?.optLong("formation_at_ms",0L) ?: 0L) in 1L until at &&
                    (if(side=="LONG") trigger>signal.optDouble("teeth_at_detection",Double.NaN)
                     else trigger<signal.optDouble("teeth_at_detection",Double.NaN))
            else -> false
        }
        val state=when {
            profile !in williamsSourceProfiles -> "SOURCE_UNKNOWN"
            !hasCausalBar -> "NOT_VERIFIED_CLOSED_H1"
            !validRiskLevels -> "INVALID_PRICE_OR_PROTECTION_GEOMETRY"
            profile==williamsTc2Profile && rule=="UNMAPPED_WILLIAMS_FAMILY" -> "UNMAPPED_TC2_SIGNAL"
            profile!=williamsTc2Profile -> "OBSERVER_FORMATION_ONLY"
            f=="SUPER_AO" && independentAo=="FAIL" ->
                "INDEPENDENT_CLOSED_H1_AO_CONTRADICTS_SETUP"
            f=="SUPER_AO" && independentAo=="NOT_VERIFIED" ->
                "INDEPENDENT_CLOSED_H1_AO_NOT_VERIFIED"
            !hasInternalProof -> "INTERNAL_BOOK_FORMATION_PROOF_INCOMPLETE"
            else -> "INTERNAL_BOOK_FORMATION_PROOF_PRESENT"
        }
        return JSONObject()
            .put("signal_id",signal.optString("signal_id").take(160))
            .put("rule_id",rule).put("source",profile).put("family",f).put("direction",side)
            .put("signal_bar_time_ms",at)
            .put("known_after_h1_close_ms",if(hasCausalBar) at+WILLIAMS_H1_MS else JSONObject.NULL)
            .put("state",state)
            .put("can_place_orders_from_this_evidence",false)
            .put("can_close_from_this_evidence",false)
            .put("trigger_is_only_a_pending_level",true)
            .put("actual_exchange_trigger_verified",false)
            .put("first_party_setup_proof_not_independent",true)
            .put("independent_closed_h1_third_ao_status",independentAo)
            .put("wm1_angulation_is_numerical_proxy",profile==williamsTc2Profile && f=="REVERSAL")
            .put("wm3_teeth_at_actual_trigger_not_proven",profile==williamsTc2Profile && f=="FRACTAL")
    }

    /**
     * TC1 is an independent historical edition, NOT a fourth entry-voting
     * source. Prior diagnostic output advertised TC1_HISTORICAL but lacked
     * its per-symbol evidence. Carry forward its *real* MFI window (with
     * explicitly NON-book base-asset volume) and already-computed five-sign
     * Zero Point observation. Course Level I reuses the SAME market volume,
     * not a second independent vote. Missing / stale bars remain unknown.
     */
    private fun tc1SourceEvidence(trace:JSONObject?,closedH1:Long,validH1:Boolean):JSONObject {
        val out=JSONObject().put("source","TC1_HISTORICAL")
            .put("execution_role","OBSERVE")
            .put("can_place_orders",false).put("can_close_positions",false)
            .put("wave_degree_certified",false)
            .put("book_tick_volume_verified",false)
            .put("course_level_1_reuses_same_volume_not_independent_confirmation",true)
            .put("state","NOT_VERIFIED")
        if(!validH1 || trace==null || trace.optLong("as_of_close_ms",0L)!=closedH1)
            return out
        val candles=trace.optJSONArray("candles") ?: return out
        if(candles.length()<2) return out
        val bars=mutableListOf<WilliamsWaveBar>()
        for(i in candles.length()-2 until candles.length()) {
            val row=candles.optJSONArray(i) ?: return out
            if(row.length()<6) return out
            val t=row.optLong(0,-1L)
            val o=row.optDouble(1,Double.NaN)
            val h=row.optDouble(2,Double.NaN)
            val l=row.optDouble(3,Double.NaN)
            val c=row.optDouble(4,Double.NaN)
            val v=row.optDouble(5,Double.NaN)
            if(t<0L || listOf(o,h,l,c,v).any { !it.isFinite() || it<=0.0 })
                return out
            bars+=WilliamsWaveBar(t,o,h,l,c,v)
        }
        val mfi=williamsTc1MarketFacilitation(bars,"1h",closedH1)
        val zero=trace.optJSONObject("tc1_zero_point_observation")
        // A stored TC1 classification does not establish the wave degree,
        // since tick-volume and annotated W3/W5 geometry are unavailable.
        val partialZero=zero?.optString("schema")=="TC1_ZERO_POINT_FIVE_SIGNS_V1" &&
            zero.optString("state")=="OBSERVATIONAL_PARTIAL_NOT_EXECUTABLE" &&
            !zero.optBoolean("zero_point_confirmed") &&
            !zero.optBoolean("wave_degree_certified")
        return out.put("state",if(mfi.optString("status") in setOf(
                "OBSERVED_BASE_VOLUME_PROXY","TIED_NEITHER_UP_NOR_DOWN"))
                "HISTORICAL_TC1_MFI_PROXY_OBSERVED" else "TC1_SOURCE_UNVERIFIED")
            .put("mfi_proxy",mfi)
            .put("five_magic_bullets_source_state",
                if(partialZero) "PARTIAL_CANDIDATE_NOT_WAVE_PROOF" else "NOT_VERIFIED")
            .put("zero_point_certified",false)
            .put("can_place_orders",false)
    }

    /**
     * Compare independent CLOSED D1/H4/H1 observations, not wave labels inferred
     * from an H1 counter-signal. A disagreement explains WHY an H1 reversal
     * should not be described as the end of the senior move. This is strictly
     * a historical context summary; not a book veto, trade action or forecast.
     */
    private fun seniorFrameContext(candidate:JSONObject?, nowMs:Long, validH1:Boolean):JSONObject {
        val unverified=JSONObject().put("state","NOT_VERIFIED")
            .put("h1_vs_senior_context","INSUFFICIENT_SENIOR_EVIDENCE")
            .put("senior_trend_end_proven",false)
            .put("can_veto_tc2_exit",false).put("can_place_orders",false)
        if(!validH1 || candidate==null) return unverified
        val phase=candidate.optJSONObject("decision_trace")?.optJSONObject("market_phase")
            ?: return unverified
        val asOf=phase.optLong("as_of",0L)
        if(asOf<=0L || asOf>nowMs) return unverified
        val frames=rows(phase.optJSONArray("frames"))
        val extracted=JSONArray()
        val biases=linkedMapOf<String,String>()
        for (frame in listOf("1h","4h","1d")) {
            val rows=frames.filter { it.optString("frame")==frame }
            val item=rows.singleOrNull()
            val reading=item?.optJSONObject("market_reading")
            val asOfClose=reading?.optLong("as_of_close_ms",0L) ?: 0L
            val frameMs=williamsWaveFrames[frame] ?: 0L
            val fresh=frameMs>0L && asOfClose>0L && asOfClose<=asOf &&
                asOf-asOfClose<=frameMs+120_000L
            val verified=item?.optString("state") in setOf("PHASE_CANDIDATE","ALTERNATIVES") &&
                reading?.optString("state")=="OBSERVED" && fresh
            val bias=reading?.optString("observed_bias")?.takeIf {
                verified && it in setOf("UP","DOWN","MIXED")
            } ?: "UNVERIFIED"
            biases[frame]=bias
            extracted.put(JSONObject().put("frame",frame)
                .put("state",if(verified) "OBSERVED_BUT_NOT_FORECAST" else "NOT_VERIFIED")
                .put("observed_bias",bias)
                .put("book_wave_hypotheses_are_provisional",true)
                .put("competing_wave_structures",item?.optInt("distinct_structural_hypotheses") ?: JSONObject.NULL)
                .put("causal_closed_at_ms",if(fresh) asOfClose else JSONObject.NULL)
                .put("can_authorize_close_or_hold",false))
        }
        val h1=biases["1h"];val seniors=listOf(biases["4h"],biases["1d"])
        val status=when {
            h1 !in setOf("UP","DOWN") || seniors.any { it !in setOf("UP","DOWN") } ->
                "INSUFFICIENT_SENIOR_EVIDENCE"
            seniors.all { it==h1 } -> "OBSERVED_BIAS_ALIGNED_NOT_FORECAST"
            seniors.all { it!=h1 } -> "OPPOSING_OBSERVED_BIAS_NOT_CONFIRMED_REVERSAL"
            else -> "MIXED_SENIOR_OBSERVATIONS_NOT_REVERSAL_PROOF"
        }
        return JSONObject().put("state",if(status=="INSUFFICIENT_SENIOR_EVIDENCE")
            "NOT_VERIFIED" else "OBSERVED_CONTEXT_ONLY")
            .put("as_of_ms",asOf)
            .put("frame_readings",extracted)
            .put("h1_vs_senior_context",status)
            .put("senior_trend_end_proven",false)
            .put("can_veto_tc2_exit",false).put("can_place_orders",false)
    }

    fun assess(scanner:JSONObject, exitForensics:JSONObject, nowMs:Long):JSONObject {
        val state=scanner.optString("scanner_state","NOT_VERIFIED")
        val at=scanner.optLong("last_scan_at",0L)
        val recent=at>0L && nowMs>=at && nowMs-at<=120_000L
        val complete=recent && !scanner.optBoolean("scanning",false) &&
            state in setOf("READY","READY_BLOCKED") &&
            scanner.optInt("symbols_scanned",-1)==APPROVED_FUTURES_SYMBOLS.size
        val candidates=if(complete) rows(scanner.optJSONArray("candidates")) else emptyList()
        val inspected=JSONArray()
        val errors=JSONArray()
        var observed=0
        var unverified=0
        var independentAoContradictions=0
        var independentAoNotVerified=0
        for(symbol in APPROVED_FUTURES_SYMBOLS) {
            val matching=candidates.filter { it.optString("symbol")==symbol }
            val candidate=matching.singleOrNull()
            if(matching.size>1) errors.put("DUPLICATE_CANDIDATE:$symbol")
            val close=candidate?.optLong("closed_h1_as_of_ms",0L) ?: 0L
            val validH1=close>0L && close<=nowMs &&
                nowMs-close<=WILLIAMS_H1_MS+120_000L
            val detailRows=rows(candidate?.optJSONObject("decision_trace")?.optJSONArray("signals"))
            val duplicateTraceIds=detailRows.groupBy { it.optString("signal_id") }
                .filter { (id,list) -> id.isNotBlank() && list.size>1 }.keys
            for(id in duplicateTraceIds.take(8))
                errors.put("DUPLICATE_SOURCE_TRACE_SIGNAL:$symbol:${id.take(80)}")
            // A duplicated signal ID is ambiguous, even if two JSON objects
            // happen to contain equal values; do not let associateBy choose
            // whichever version appeared last.
            val details=detailRows.filter { it.optString("signal_id") !in duplicateTraceIds }
                .associateBy { it.optString("signal_id") }
            val rawSignals=if(validH1) rows(candidate?.optJSONArray("campaign_signals"))
                else emptyList()
            val duplicateLiveIds=rawSignals.groupBy { it.optString("signal_id") }
                .filter { (id,list) -> id.isNotBlank() && list.size>1 }.keys
            for(id in duplicateLiveIds.take(8))
                errors.put("DUPLICATE_LIVE_SIGNAL:$symbol:${id.take(80)}")
            val signals=rawSignals.take(8)
            val summaries=JSONArray()
            for(signal in signals) {
                val signalId=signal.optString("signal_id")
                val evidence=signalEvidence(signal,details[signalId],
                    close,nowMs,candidate?.optJSONObject("decision_trace")?.optJSONArray("candles"))
                if(signalId.isBlank() || signalId in duplicateTraceIds || signalId in duplicateLiveIds)
                    evidence.put("state","SOURCE_SIGNAL_IDENTITY_AMBIGUOUS")
                when(evidence.optString("state")) {
                    "INTERNAL_BOOK_FORMATION_PROOF_PRESENT" -> observed++
                    "INDEPENDENT_CLOSED_H1_AO_CONTRADICTS_SETUP" -> {
                        unverified++
                        independentAoContradictions++
                        errors.put("WM2_AO_SOURCE_CONTRADICTION:$symbol:${signalId.take(80)}")
                    }
                    "INDEPENDENT_CLOSED_H1_AO_NOT_VERIFIED" -> {
                        unverified++
                        independentAoNotVerified++
                    }
                    else -> unverified++
                }
                summaries.put(evidence)
            }
            val editions=rows(candidate?.optJSONArray("edition_observations"))
            val provenance=JSONArray(williamsSourceProfiles.map { source ->
                val named=editions.filter { it.optString("profile")==source }
                val info=named.singleOrNull()
                val expected=if(source==williamsTc2Profile) "LIVE" else "OBSERVE"
                val matched=info!=null && info.optString("execution_role")==expected &&
                    info.optLong("as_of_close_ms")==close && validH1
                if(info!=null && !matched) errors.put("SOURCE_EVIDENCE_MISMATCH:$symbol:$source")
                JSONObject().put("source",source).put("expected_role",expected)
                    .put("observed_role",info?.optString("execution_role") ?: JSONObject.NULL)
                    .put("as_of_close_ms",if(matched) close else JSONObject.NULL)
                    .put("state",if(matched) info!!.optString("state") else "NOT_VERIFIED")
                    .put("can_place_orders",false)
            })
            inspected.put(JSONObject().put("symbol",symbol)
                .put("state",when {
                    !complete -> "SCANNER_NOT_COMPLETE_OR_STALE"
                    candidate==null -> "CANDIDATE_NOT_EXPORTED"
                    !validH1 -> "CLOSED_H1_NOT_VERIFIED"
                    else -> "OBSERVATION_ONLY"
                })
                .put("signals",summaries).put("source_observations",provenance)
                .put("tc1_historical_observation",tc1SourceEvidence(
                    candidate?.optJSONObject("decision_trace"),close,validH1))
                .put("signals_omitted_for_bounded_diagnostics",
                    if(validH1) (rows(candidate?.optJSONArray("campaign_signals")).size-8).coerceAtLeast(0) else 0)
                .put("h1_as_of_close_ms",if(validH1) close else JSONObject.NULL)
                .put("senior_frame_context",seniorFrameContext(candidate,nowMs,validH1))
                .put("senior_trend_end_proven",false)
                .put("wave_degree_certified",false)
                .put("can_place_orders",false))
        }
        val exits=rows(exitForensics.optJSONArray("recent_closed_trades"))
        val opposite=exits.count { it.optBoolean("opposite_h1_signal_confirmed_in_saved_evidence") }
        val unknown=exits.count {
            it.optString("recorded_exit_reason")=="EXCHANGE_RECONCILED_CLOSE" ||
                (it.optString("recorded_exit_reason").startsWith("OPPOSITE_") &&
                    !it.optBoolean("opposite_h1_signal_confirmed_in_saved_evidence"))
        }
        return JSONObject()
            .put("schema","WILLIAMS_FOUR_BOOK_CAUSAL_UNDERSTANDING_V1")
            .put("observation_only",true).put("execution_source",williamsTc2Profile)
            .put("observer_sources",JSONArray(listOf(williamsNtdProfile,williamsCourseProfile,"TC1_HISTORICAL")))
            .put("source_catalog_scope","SELECTED_VERIFIABLE_RULES_NOT_FULL_BOOK")
            .put("source_book_pdf_references",ruleCatalog())
            .put("whole_books_understood_and_certified",false)
            .put("independent_visual_figure_consensus_proven",false)
            .put("knowledge_accuracy_percent_calibrated",false)
            .put("scanner_data_usable_for_book_observations",complete)
            .put("scanner_state",state)
            .put("scanner_snapshot_at_ms",at)
            .put("symbols_expected",APPROVED_FUTURES_SYMBOLS.size)
            .put("symbols_with_observation",candidates.count {
                it.optLong("closed_h1_as_of_ms",0L)>0L })
            .put("internal_h1_setup_proof_count",observed)
            .put("incomplete_or_non_tc2_signal_proof_count",unverified)
            .put("independent_third_ao_source_contradictions",independentAoContradictions)
            .put("independent_third_ao_unverified",independentAoNotVerified)
            .put("observed_candidates",inspected)
            .put("source_contract_discrepancies",errors)
            .put("confirmed_h1_opposing_exit_count",opposite)
            .put("unattributed_or_incomplete_exit_count",unknown)
            .put("senior_trend_reversal_inferred_from_h1_opposite",false)
            .put("actual_fill_and_protection_externally_verified",false)
            .put("trading_authority",false).put("can_place_orders",false)
            .put("can_change_stop_or_exit",false)
            .put("interpretation","Signal formed != trigger crossed != Binance filled != STOP signed. H1 counter-signal != D1/H4 trend reversal. Missing evidence stays unknown.")
    }
}
