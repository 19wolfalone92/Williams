package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

/**
 * Source provenance is not the same as comprehension, chart-figure validation
 * or proof of profitable execution. The four originals total 642 physical PDF
 * pages; page indexing is exact, but source-level certification remains 0/57.
 *
 * Chapter names and short summaries are new metadata, not excerpts from books.
 * Keep the original proprietary course / private PDF files OFF the device,
 * public repository, and diagnostic exports.
 *
 * TC1 and NTD describe different strategy generations from TC2. Their book
 * chapters grant NO authority to modify TC2 H1 orders, risk or STOP.
 */
internal object WilliamsFourBookCurriculum {
    private data class Section(
        val source:String, val id:String, val start:Int, val end:Int,
        val kind:String, val topic:String, val linkedRuleIds:List<String>
    )

    private val chapters=listOf(
        Section("TC1","TC1.FRONT",1,5,"CONTEXT","Preface, introduction and source assumptions",listOf()),
        Section("TC1","TC1.01",6,13,"PSYCHOLOGY","Current trading reality and trader development",listOf()),
        Section("TC1","TC1.02",14,18,"THEORY","Understanding markets and non-linear process",listOf()),
        Section("TC1","TC1.03",19,26,"THEORY","Chaos/fractal theory and attractors",listOf()),
        Section("TC1","TC1.04",27,31,"PSYCHOLOGY","Mental structure and beliefs",listOf()),
        Section("TC1","TC1.05",32,36,"OBSERVATION","Navigation and observation of markets",listOf()),
        Section("TC1","TC1.06",37,48,"INDICATOR","Novice trader: volume, MFI and four windows",listOf("TC1.MFI.RANGE_OVER_VOLUME","TC1.MFI.WINDOW_GREEN","TC1.MFI.WINDOW_FADE","TC1.MFI.WINDOW_FAKE","TC1.MFI.WINDOW_SQUAT","ENGINEERING.MFI_PROXY","TC1.FIVE_TRADER_LEVELS")),
        Section("TC1","TC1.07",49,66,"WAVE","Advanced beginner: AO momentum and Elliott relationships",listOf("WAVE.AO.MINIMUM_W4","WAVE.DEGREE_SCOPED_INTERPRETATION","WAVE.REAL_TIMEFRAMES","WAVE.W3.GLOBAL_MOMENTUM_AND_PRICE_LAG","TC1.FIVE_TRADER_LEVELS")),
        Section("TC1","TC1.08",67,79,"FRACTAL","Fractal patterns and leverage",listOf("TC1.FIVE_MAGIC_BULLETS_SOURCE_SEPARATION")),
        Section("TC1","TC1.09",80,94,"WAVE","Competent trader: planned wave and correction campaigns",listOf("TC1.W2.RETRACE_38_62","TC1.W3.EXTENSION_1_TO_1_62","TC1.W4.RETRACE_38_50","TC1.W4.OVERLAP_ALTERNATIVE","TC1.W5.PROJECTION_ORIGIN_AFTER_W4","TC1.ZIGZAG.B_RETRACE_50_62","TC1.FIVE_TRADER_LEVELS")),
        Section("TC1","TC1.10",95,105,"MANAGEMENT","Profitunity trading partner, stop and thumb/air bag",listOf("TC1.FIVE_MAGIC_BULLETS_SOURCE_SEPARATION")),
        Section("TC1","TC1.11",106,126,"PSYCHOLOGY","Proficient trading and psychology",listOf("TC1.FIVE_TRADER_LEVELS")),
        Section("TC1","TC1.12",127,140,"PSYCHOLOGY","Expert trader and reflective practice",listOf("TC1.FIVE_TRADER_LEVELS")),
        Section("NTD","NTD.FRONT",1,9,"CONTEXT","Introduction, author premise, contents",listOf()),
        Section("NTD","NTD.01",10,20,"PSYCHOLOGY","Getting started and mental process",listOf()),
        Section("NTD","NTD.02",21,33,"THEORY","Chaos theory to trading process",listOf()),
        Section("NTD","NTD.03",34,40,"INDICATOR","Alligator, source SMMA and phase",listOf("NTD.INDICATORS")),
        Section("NTD","NTD.04",41,54,"FRACTAL","Fractal breakout: formation, confirmation and first entry",listOf("NTD.FIRST_FRACTAL")),
        Section("NTD","NTD.05",55,71,"INDICATOR","Awesome Oscillator: saucer, zero-line and twin peaks",listOf("NTD.AO.SAUCER","NTD.AO.ZERO_CROSS","NTD.AO.TWIN_PEAKS")),
        Section("NTD","NTD.06",72,90,"INDICATOR","Accelerator Oscillator: color and crossing behavior",listOf("NTD.AC.COUNTS")),
        Section("NTD","NTD.07",91,98,"MANAGEMENT","AO/AC trading zones, adds and fifth bar",listOf("NTD.ZONE.ADDITIONS","NTD.ZONE.FIFTH_TRAIL")),
        Section("NTD","NTD.08",99,114,"INDICATOR","Balance Line: Jaw and price extremum reference",listOf("NTD.BALANCE.LOCAL_BASE","NTD.BALANCE.REFERENCE_JAW")),
        Section("NTD","NTD.09",115,122,"MANAGEMENT","Profit-taking and exit structure",listOf("NTD.EXIT.TEETH_LIPS")),
        Section("NTD","NTD.10",123,138,"INTEGRATION","Complete five-dimension system and examples",listOf()),
        Section("NTD","NTD.11",139,156,"PSYCHOLOGY","Trading mastery and attitude",listOf("NTD.MASTERY_IS_NOT_MARKET_SIGNAL")),
        Section("TC2","TC2.FRONT",1,15,"CONTEXT","Second-edition prefaces and goals",listOf()),
        Section("TC2","TC2.01",16,27,"PSYCHOLOGY","Market as perceived by trader",listOf()),
        Section("TC2","TC2.02",28,40,"THEORY","Chaos theory and changing data",listOf()),
        Section("TC2","TC2.03",41,49,"PSYCHOLOGY","Personal structure and trading assumptions",listOf()),
        Section("TC2","TC2.04",50,62,"PSYCHOLOGY","Preparation to trade and evaluation",listOf()),
        Section("TC2","TC2.05",63,71,"PSYCHOLOGY","Trader typology and state",listOf()),
        Section("TC2","TC2.06",72,97,"PSYCHOLOGY","Mental training and trading mindset",listOf()),
        Section("TC2","TC2.07",98,106,"OBSERVATION","Navigation, price behavior and market information",listOf()),
        Section("TC2","TC2.08",107,119,"INDICATOR","Alligator equilibrium lines and market phases",listOf()),
        Section("TC2","TC2.09",120,134,"EXECUTION_RULE","First Wise Man: divergent price bar, angle, AO color",listOf("TC2.WM1.DIVERGENT_BAR","TC2.WM1.ANGULATION","TC2.WM1.AO_CONTEXT")),
        Section("TC2","TC2.10",135,143,"EXECUTION_RULE","Second Wise Man: third consecutive AO color",listOf("TC2.WM2.THREE_AO_BARS","TC2.WM2.FIRST_STOP")),
        Section("TC2","TC2.11",144,152,"EXECUTION_RULE","Third Wise Man: equal-extreme extended fractals and Teeth",listOf("TC2.WM3.ACTIVATION_TEETH","TC2.FRACTAL.EQUAL_EXTREMES")),
        Section("TC2","TC2.12",153,166,"EXECUTION_RULE","Three Wise Men campaigns, pyramid, STOP, exits",listOf("TC2.WISEMEN.FIRST_PRESENTING","TC2.PYRAMID.QUANTITIES","TC2.PYRAMID.PROFIT","TC2.STOP.THREE_OR_FIVE","TC2.STOP.FOUR_BAR_ALTERNATIVE","TC2.EXIT.STOP_AND_REVERSE","ENGINEERING.RISK_AND_LOT_LIMITS","ENGINEERING.NO_AUTOMATIC_1R")),
        Section("TC2","TC2.13",167,176,"PSYCHOLOGY","Recovering from losses and trader psychology",listOf()),
        Section("TC2","TC2.EPILOGUE",177,178,"CONTEXT","Epilogue",listOf()),
        Section("TC2","TC2.APP01",179,183,"OPERATOR_CHECKLIST","Trade preparation checklist and paper trading",listOf("TC2.APPENDIX1.PAPER_SOURCE_OPERATOR_CHECKLIST")),
        Section("TC2","TC2.APP02",184,194,"CONTEXT","Frequently asked questions and limits of book-only learning",listOf()),
        Section("TC2","TC2.APP03",195,202,"PSYCHOLOGY","Autogenic trader stress management",listOf()),
        Section("TC2","TC2.APP04",203,205,"LEGACY_PLATFORM","Investor's Dream indicator display reference",listOf()),
        Section("TC2","TC2.APP05",206,209,"LEGACY_PLATFORM","CQG indicator configuration reference",listOf()),
        Section("TC2","TC2.APP06",210,217,"LEGACY_PLATFORM","MetaStock indicator configuration reference",listOf()),
        Section("TC2","TC2.APP07",218,224,"LEGACY_PLATFORM","TradeStation indicator formulas and configuration",listOf()),
        Section("COURSE","COURSE.FRONT",1,14,"CONTEXT","Disclosure, introduction, five levels of development",listOf("COURSE.FIVE_TRAINING_LEVELS_AND_EXTERNAL_GAP")),
        Section("COURSE","COURSE.LEVEL1",15,30,"INDICATOR","Novice: OHLC identification, traded volume and MFI windows",listOf("TC1.MFI.RANGE_OVER_VOLUME","TC1.MFI.WINDOW_GREEN","TC1.MFI.WINDOW_FADE","TC1.MFI.WINDOW_FAKE","TC1.MFI.WINDOW_SQUAT","COURSE.FIVE_TRAINING_LEVELS_AND_EXTERNAL_GAP")),
        Section("COURSE","COURSE.LEVEL2A",31,60,"WAVE","Advanced beginner: Elliott wave grammar, 5/34, corrections",listOf("COURSE.W1_W2_W3_W4_LOCATION","COURSE.W2.EXACT_ORIGIN_100_PERCENT","COURSE.W3.EXTENSION_110","COURSE.W5.QUANTITY_6_OR_8","COURSE.RECURSIVE_DEGREE_PRICE_GRAMMAR","WAVE.COMPLETED_MOTIVE_REMAINS_HISTORICAL_EVIDENCE","COURSE.FIVE_TRAINING_LEVELS_AND_EXTERNAL_GAP")),
        Section("COURSE","COURSE.LEVEL2B",61,74,"FRACTAL","Fractal start, signal, stop, hump/thumb and squat",listOf("COURSE.HUMP","COURSE.THUMB.FINGERS","COURSE.THUMB.COLOUR_COMBINATION","COURSE.TERMINAL_SQUAT_EXTREME_THREE","COURSE.FIVE_TRAINING_LEVELS_AND_EXTERNAL_GAP")),
        Section("COURSE","COURSE.LEVEL3A",75,84,"MANAGEMENT","Competent trader: planned Profitunity wave campaigns",listOf("COURSE.W5.QUANTITY_6_OR_8","COURSE.W3.EXTENSION_110","COURSE.COMPLETE_CORRECTIVE_CAMPAIGN","COURSE.STAGE_TRIM.SOURCE_PINNING","COURSE.EXACT_CONTRACT_UNITS_AND_SIGNED_STAGE_FILLS","COURSE.SIGNED_FLAT_CORRECTION_HANDOFF","COURSE.TERMINAL_AND_FAILED_COUNT_EXIT","COURSE.FIVE_TRAINING_LEVELS_AND_EXTERNAL_GAP")),
        Section("COURSE","COURSE.LEVEL3B",85,98,"MANAGEMENT","Trading partner: air bag, rhythm, fractal, thumb, discipline",listOf("COURSE.AIRBAG","COURSE.RHYTHM","COURSE.TERMINAL_SQUAT_EXTREME_THREE","COURSE.TERMINAL_AND_FAILED_COUNT_EXIT","COURSE.THUMB.FINGERS","COURSE.THUMB.COLOUR_COMBINATION","COURSE.W1_W2_W3_W4_LOCATION","COURSE.STAGE_TRIM.SOURCE_PINNING","COURSE.FIVE_TRAINING_LEVELS_AND_EXTERNAL_GAP")),
        Section("COURSE","COURSE.LEVEL4A",99,103,"PSYCHOLOGY","Psychological facilitation introduction",listOf("COURSE.FIVE_TRAINING_LEVELS_AND_EXTERNAL_GAP")),
        Section("COURSE","COURSE.LEVEL4B",104,118,"PSYCHOLOGY","Trading brains, self-preservation, money, confidence and play",listOf("COURSE.FIVE_TRAINING_LEVELS_AND_EXTERNAL_GAP")),
        Section("COURSE","COURSE.LEVEL5A",119,121,"PSYCHOLOGY","Personal trading reflection workbook",listOf("COURSE.FIVE_TRAINING_LEVELS_AND_EXTERNAL_GAP")),
        Section("COURSE","COURSE.LEVEL5B",122,122,"EXTERNAL_SOURCE_MISSING","Missing external individual tutorial material",listOf("SOURCE.COURSE_EXTERNAL_TUTORIAL_MISSING","COURSE.FIVE_TRAINING_LEVELS_AND_EXTERNAL_GAP"))
    )
    private val volumes=linkedMapOf("TC1" to 140,"NTD" to 156,"TC2" to 224,"COURSE" to 122)

    private fun sourceSectionCatalog():JSONArray=JSONArray(chapters.map {
        JSONObject().put("section_id",it.id).put("book",it.source)
            .put("pdf_start_page",it.start).put("pdf_end_page",it.end)
            .put("topic",it.topic).put("kind",it.kind)
            .put("linked_canonical_book_rule_ids",JSONArray(it.linkedRuleIds))
            .put("source_page_indexed",true)
            .put("independent_figure_grade","NOT_GRADED")
            .put("complete_chapter_comprehension_proven",false)
            .put("can_place_orders",false).put("can_move_stop",false)
    })

    /**
     * Chapter-level checklist taken as a diagnostic research structure from:
     * TC2, Appendix 1, PDF pp179-183: source quality, tick/margin knowledge,
     * operator readiness, practice before capital and alternative connectivity.
     *
     * A successful market scan alone does NOT certify any of the other
     * checks. No fictitious paper-trading success or psychology score.
     */
    private fun appendixReadiness(scanner:JSONObject, nowMs:Long):JSONArray {
        val at=scanner.optLong("last_scan_at",0L)
        val valid=at>0L && at<=nowMs && nowMs-at<=120_000L &&
            !scanner.optBoolean("scanning",false) &&
            scanner.optString("scanner_state") in setOf("READY","READY_BLOCKED") &&
            scanner.optInt("symbols_scanned",-1)==APPROVED_FUTURES_SYMBOLS.size
        fun item(id:String,source:String,state:String,meaning:String):JSONObject=
            JSONObject().put("check_id",id).put("book_source_pdf_page",source)
                .put("state",state).put("meaning",meaning)
                .put("is_authorization_for_trade",false)
        return JSONArray().put(item("DATA_RECENT_SCAN","TC2:180",
            if(valid) "RECENT_SCANNER_OBSERVED" else "NOT_VERIFIED",
            "A fresh H1 sweep is only part of market data-source quality; it cannot certify all candles."))
            .put(item("PAPER_TRADE_PROVENANCE","TC2:180","NOT_VERIFIED",
                "The book recommends paper practice before committing real capital. No independent Demo track record is certified by this report."))
            .put(item("TICK_MARGIN_AND_ORDER_GRAMMAR","TC2:181","NOT_VERIFIED",
                "A scanned symbol is not independently certified tick, margin, or type-of-order understanding."))
            .put(item("SIGNED_PROTECTION_AND_RESTART","TC2:180-182","NOT_VERIFIED",
                "This source map is not an authenticated Binance FILLED to STOP receipt or offline continuity test."))
            .put(item("USER_READINESS_AND_PSYCHOLOGY","TC2:182;COURSE:99-118","OUTSIDE_AUTOMATIC_ASSESSMENT",
                "Personality and readiness must not be guessed from trade statistics or Android status."))
            .put(item("COURSE_LEVEL5_EXTERNAL_TUTORIAL","COURSE:122","SOURCE_MISSING",
                "Final course page refers to personalized external tutorial material absent from this PDF."))
    }

    /**
     * TC2 Appendix 1, PDF p182: the chart checklist includes observed trend,
     * Alligator state, AO sign/direction, Wise Man signal prices, risk/margin
     * and STOP references. It is a list of **questions**, NOT a rule that each
     * indicator must vote in the same direction. Use only already captured
     * closed H1 observations. Fields requiring fresh signed Binance proof
     * remain NOT_VERIFIED even when the chart itself is fresh.
     */
    private fun tc2ChartChecklist(scanner:JSONObject, nowMs:Long):JSONObject {
        val at=scanner.optLong("last_scan_at",0L)
        val global=at>0L && at<=nowMs && nowMs-at<=120_000L &&
            scanner.optString("scanner_state") in setOf("READY","READY_BLOCKED") &&
            !scanner.optBoolean("scanning",false) &&
            scanner.optInt("symbols_scanned",-1)==APPROVED_FUTURES_SYMBOLS.size
        val rows=scanner.optJSONArray("candidates")
        val candidates=if(global && rows!=null)
            (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
        else emptyList()
        var explained=0
        val per=JSONArray()
        for(symbol in APPROVED_FUTURES_SYMBOLS) {
            val matching=candidates.filter { it.optString("symbol")==symbol }
            val candidate=matching.singleOrNull()
            val close=candidate?.optLong("closed_h1_as_of_ms",0L) ?: 0L
            val trace=candidate?.optJSONObject("decision_trace")
            val reading=trace?.optJSONObject("market_reading")
            val verified=global && candidate!=null && close>0L && close<=nowMs &&
                nowMs-close<=WILLIAMS_H1_MS+120_000L &&
                trace.optLong("as_of_close_ms",0L)==close &&
                reading?.optString("state")=="OBSERVED" &&
                reading.optLong("as_of_close_ms",0L)==close
            if(verified) explained++
            val signals=candidate?.optJSONArray("campaign_signals")
            val tc2=if(verified && signals!=null) (0 until signals.length())
                .mapNotNull { signals.optJSONObject(it) }
                .filter { it.optString("strategy_profile")==williamsTc2Profile }
                .take(3)
            else emptyList()
            per.put(JSONObject().put("symbol",symbol)
                .put("chart_observation_state",
                    if(verified) "CLOSED_H1_SNAPSHOT_OBSERVED" else "NOT_VERIFIED")
                .put("as_of_closed_h1_ms",if(verified) close else JSONObject.NULL)
                .put("observed_trend",if(verified)
                    reading!!.optString("observed_bias","UNVERIFIED") else "UNVERIFIED")
                .put("observed_alligator",if(verified)
                    reading!!.optJSONObject("alligator")?.optString("state","UNVERIFIED") else "UNVERIFIED")
                .put("ao_direction",if(verified)
                    reading!!.optString("ao_direction","UNVERIFIED") else "UNVERIFIED")
                .put("tc2_wise_man_observed_signal_ids",JSONArray(tc2.map {
                    it.optString("signal_id").take(100)
                }))
                .put("initial_signed_risk_and_margin","NOT_VERIFIED_IN_THIS_CHART_OBSERVER")
                .put("exchange_confirmed_protective_stop","NOT_VERIFIED_IN_THIS_CHART_OBSERVER")
                .put("psychology_independently_assessed",false)
                .put("source_book_pdf_page",182)
                .put("can_approve_order",false).put("can_veto_tc2_order",false))
        }
        return JSONObject().put("schema","TC2_APPENDIX1_CLOSED_H1_CHART_CHECKLIST_V1")
            .put("source_pdf_pages",JSONArray(listOf(182,183)))
            .put("symbols_expected",APPROVED_FUTURES_SYMBOLS.size)
            .put("source_chart_observed_count",explained)
            .put("symbols",per)
            .put("trading_authority",false)
            .put("complete_market_account_and_stop_preflight_proven",false)
            .put("author_discretion_is_not_a_numeric_signal",true)
    }

    fun assess(scanner:JSONObject, nowMs:Long):JSONObject {
        val perSource=JSONArray()
        var allIndexed=0
        var fullGrade=0
        for((book,total) in volumes) {
            val parts=chapters.filter { it.source==book }.sortedBy { it.start }
            var next=1;var indexed=0
            for(part in parts) {
                check(part.start==next && part.end>=part.start && part.end<=total) {
                    "SOURCE_SECTION_INDEX_GAP:$book:${part.id}"
                }
                indexed+=part.end-part.start+1
                next=part.end+1
            }
            check(next==total+1 && indexed==total) { "SOURCE_PAGES_NOT_ALL_INDEXED:$book" }
            allIndexed+=indexed
            perSource.put(JSONObject().put("book",book).put("pdf_pages",total)
                .put("indexed_pdf_pages",indexed).put("indexed_sections",parts.size)
                .put("independent_figure_certified_sections",0)
                .put("ungraded_sections",parts.size)
                .put("contains_unexecutable_context",parts.any {
                    it.kind in setOf("PSYCHOLOGY","THEORY","CONTEXT","LEGACY_PLATFORM","EXTERNAL_SOURCE_MISSING")
                })
                .put("original_pdf_exported",false)
                .put("new_order_authority",false))
        }
        check(allIndexed==642 && chapters.size==57) { "SOURCE_CORPUS_MANIFEST_DRIFT" }
        return JSONObject()
            .put("schema","WILLIAMS_FOUR_BOOKS_FULL_CHAPTER_COVERAGE_V1")
            .put("source_pdf_total_pages",642)
            .put("source_pages_physically_indexed",allIndexed)
            .put("source_chapters_in_inventory",chapters.size)
            .put("chapters_with_independent_figure_exam",fullGrade)
            .put("independent_exam_required_before_accuracy_claim",true)
            .put("book_understanding_99_percent_proven",false)
            .put("whole_book_understood_or_certified",false)
            .put("original_book_pages_reproduced_in_apk",false)
            .put("source_original_course_fully_ocr_certified",false)
            .put("source_original_course_external_tutorial_complete",false)
            .put("source_catalog",perSource)
            .put("chapters",sourceSectionCatalog())
            .put("tc2_appendix_1_preflight_research",appendixReadiness(scanner,nowMs))
            .put("tc2_appendix_1_closed_h1_chart_checklist",tc2ChartChecklist(scanner,nowMs))
            .put("study_goal","99% reproducible agreement with independently annotated rules and book figures, not 99% winning trades.")
            .put("missing_key_evidence","Independent figure annotation + unknown source Course tutorial + real chart holdout + signed Demo execution.")
            .put("can_modify_tc2_execution",false)
            .put("trading_authority",false)
    }
}
