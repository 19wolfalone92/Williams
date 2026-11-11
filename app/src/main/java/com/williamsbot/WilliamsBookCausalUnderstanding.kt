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
        Rule("TC2.WM1.DIVERGENT", "TC2", listOf(122,123,124,125),
            "Countertrend reversal beyond the entire Alligator mouth; strict half-close, opposing AO colour and visually motivated angulation.",
            "ANGULATION_NUMERICAL_PROXY"),
        Rule("TC2.WM2.THIRD_AO", "TC2", listOf(135,136,137,139,156),
            "Three directional AO changes; pending stop-entry belongs to the corresponding third price bar.",
            "BOUNDED_RULE_TESTED"),
        Rule("TC2.WM3.FRACTAL", "TC2", listOf(145,146,147,156),
            "Closed causal 5-plus-bar confirmed fractal; actual breakout outside Teeth, not merely a formation outside Teeth.",
            "EXTENDED_GEOMETRY_PARTIALLY_VALIDATED"),
        Rule("TC2.STOP.STRUCTURE", "TC2", listOf(131,132,156,165),
            "Initial Wise Man stop and monotone structural 3-5 bar trailing are different decisions.",
            "SIGNED_EXCHANGE_STOP_NOT_PROVED_BY_BOOK"),
        Rule("TC2.OPPOSITE.EXIT", "TC2", listOf(153,154,155,156,163),
            "A fresh opposing Wise Man LEVEL may trigger exit/reversal; its appearance is not a fill or proof of D1/H4 trend end.",
            "SOURCE_EVENT_MUST_BE_REVALIDATED"),
        Rule("TC1.MFI.FOUR_WINDOWS", "TC1", listOf(40,41,42,43,44,45),
            "Green, Fade, Fake and Squat require comparisons with prior volume and MFI; crypto base volume is only a proxy for source tick volume.",
            "PROXY_OBSERVE_ONLY"),
        Rule("NTD.FRACTAL.FIRST", "NTD", listOf(38,39,40,41,42,43),
            "The first NTD entry has its own external fractal/Alligator lifecycle; not a mandatory first entry for TC2.",
            "OBSERVE_ONLY"),
        Rule("NTD.FIVE_DIMENSIONS", "NTD", listOf(55,56,72,73,91,92,93,94),
            "AO, AC, zone and Balance Line are edition-specific observations, not TC2 trade votes.",
            "OBSERVE_ONLY"),
        Rule("COURSE.HUMP_THUMB_RHYTHM", "COURSE", listOf(61,62,69,70,71,77,81,90),
            "Hump, Thumb, 5/13/34 rhythm and wave allocations require independent original-figure verification.",
            "OCR_FIGURES_NOT_FULLY_CERTIFIED")
    )
    private fun rows(a:JSONArray?):List<JSONObject> =
        if(a==null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    private fun family(type:String):String = type.removePrefix("LONG_").removePrefix("SHORT_")
    private fun direction(type:String):String = if(type.startsWith("SHORT_")) "SHORT" else "LONG"
    private fun tc2Rule(family:String):String = when(family) {
        "REVERSAL" -> "TC2.WM1.DIVERGENT"
        "SUPER_AO" -> "TC2.WM2.THIRD_AO"
        "FRACTAL" -> "TC2.WM3.FRACTAL"
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
     * A signal that is only present in a scanner is not a filled order. A
     * scanner's first-party setup_context is useful evidence but is not an
     * independent rereading of the author's figure from a real chart.
     */
    private fun signalEvidence(
        signal:JSONObject, rawDetail:JSONObject?, closedH1At:Long, nowMs:Long
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
            williamsNtdProfile -> "NTD.FIVE_DIMENSIONS"
            williamsCourseProfile -> "COURSE.HUMP_THUMB_RHYTHM"
            else -> "UNKNOWN_SOURCE"
        }
        val hasInternalProof=when {
            profile!=williamsTc2Profile -> false
            f=="REVERSAL" ->
                context.optBoolean("wm1_ao_qualifies_book_countertrend") &&
                    context.optJSONObject("angulation")!=null
            f=="SUPER_AO" ->
                context.optLong("third_ao_bar_at_ms",-1L)==at &&
                    context.optString("initial_stop_rule")=="TC2_SUPER_AO_SIGNAL_BAR"
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
            .put("wm1_angulation_is_numerical_proxy",profile==williamsTc2Profile && f=="REVERSAL")
            .put("wm3_teeth_at_actual_trigger_not_proven",profile==williamsTc2Profile && f=="FRACTAL")
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
        for(symbol in APPROVED_FUTURES_SYMBOLS) {
            val matching=candidates.filter { it.optString("symbol")==symbol }
            val candidate=matching.singleOrNull()
            if(matching.size>1) errors.put("DUPLICATE_CANDIDATE:$symbol")
            val close=candidate?.optLong("closed_h1_as_of_ms",0L) ?: 0L
            val validH1=close>0L && close<=nowMs &&
                nowMs-close<=WILLIAMS_H1_MS+120_000L
            val details=rows(candidate?.optJSONObject("decision_trace")?.optJSONArray("signals"))
                .associateBy { it.optString("signal_id") }
            val signals=if(validH1) rows(candidate?.optJSONArray("campaign_signals")).take(16)
                else emptyList()
            val summaries=JSONArray()
            for(signal in signals) {
                val evidence=signalEvidence(signal,details[signal.optString("signal_id")],close,nowMs)
                if(evidence.optString("state")=="INTERNAL_BOOK_FORMATION_PROOF_PRESENT") observed++
                else unverified++
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
                .put("h1_as_of_close_ms",if(validH1) close else JSONObject.NULL)
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
