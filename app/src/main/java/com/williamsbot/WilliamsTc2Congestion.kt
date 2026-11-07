package com.williamsbot

import org.json.JSONObject
import org.json.JSONArray

/**
 * TC2 chapter 11, figure 11.7 / Russian PDF page 151: Williams explicitly
 * declines late fractals g/h/j when the market has become corrective/bounded.
 * The author does NOT specify a numerical 24-bar congestion formula.
 *
 * This is a labelled PROJECT RISK POLICY, not an Elliott-wave certification.
 * It withholds NEW TC2 exposure only when the chart has a causally
 * confirmed, unresolved structural COMPLEX-correction grammar (flat, triangle or
 * constrained corrective combination) and entry remains inside its range.
 * A mere sideways market, simple ZIGZAG A-B-C, incomplete/running flat,
 * or W-X-Y_UNRESOLVED placeholder is NOT sufficient to block. A *completed*
 * regular/expanded FLAT is a complex correction per TC1 chapter 7.
 * A real breakout outside the structure passes.
 * Every WM1/WM2/WM3 signal is evaluated on the same basis.
 *
 * Existing positions, stops, risk reductions and book exits are NEVER gated.
 * Closed and contiguous H1 only; no M15 confirmation and no future candles.
 */
internal const val WILLIAMS_TC2_CONGESTION_POLICY="TC2_COMPLEX_CORRECTIONS_ONLY_V4_EVALUATE_ALL_BEFORE_DISPLAY"
internal const val WILLIAMS_TC2_CONGESTION_BLOCKER="TC2_COMPLEX_CORRECTION_PRICE_GRAMMAR_INSIDE_H1_RANGE"

/** Only price grammars that actually distinguish a complex correction from
 * ordinary A-B-C; the generic 8/12-anchor WXY_UNRESOLVED labels are excluded.
 * All are provisional Elliott *candidates*, never unique wave counts.
 */
internal val WILLIAMS_TC2_COMPLEX_PATTERNS=setOf(
    // TC1 ch7 PDF55-56 classifies flat and irregular/expanded flat as
    // COMPLEX, not the simple 5-3-5 zigzag. Demand completed, confirmed
    // A-B-C price extrema (C reaches/breaks A); truncated/running flats
    // with unproven C remain alternatives, not automatic new-entry vetoes.
    "REGULAR_FLAT_ABC_CANDIDATE",
    "EXPANDED_FLAT_ABC_CANDIDATE",
    "CONTRACTING_TRIANGLE_ABCDE_CANDIDATE",
    "EXPANDING_TRIANGLE_ABCDE_CANDIDATE",
    "BARRIER_TRIANGLE_ABCDE_CANDIDATE",
    "W_X_Y_ZIGZAG_COMBINATION_CANDIDATE",
    "W_X_Y_X_Z_ZIGZAG_COMBINATION_CANDIDATE"
)

/** No veto from flatness or an unresolved W-X-Y label.
 *
 * Important: a fully confirmed triangle/flat is a historical price event.
 * It does NOT cease to exist merely because 12 more H1 bars have closed or
 * the latest six pivots have shifted. Retain every confirmed candidate
 * inside the bounded observed H1 history, and decide whether it is STILL
 * ACTIVE only from subsequent CLOSED H1 price outside its own boundaries
 * in williamsTc2CongestionEvidence. Never backdate "known_at".
 *
 * Only the last 120 alternating pivots are scanned, so this cannot turn a
 * long-running Android scanner into an unbounded history computation.
 * This bounded retention is project policy, NOT a Williams time cutoff.
 */
internal fun williamsTc2RecentComplexPriceCandidates(
    anchors:List<WilliamsWaveAnchor>,asOf:Long
):List<WilliamsComplexEvidence> {
    if(asOf<=0L || anchors.size<6 || anchors.any {
        it.kind !in setOf("LOW","HIGH") || !it.price.isFinite() ||
            it.price<=0.0 || it.knownAt>asOf || it.knownAt<it.extremeAt
    } || anchors.zipWithNext().any { (a,b) ->
        a.kind==b.kind || a.extremeAt>=b.extremeAt || a.knownAt>b.knownAt
    }) return emptyList()
    val retained=anchors.takeLast(120)
    val observed=mutableListOf<WilliamsComplexEvidence>()
    for(prefixSize in 6..retained.size) {
        val confirmedPrefix=retained.take(prefixSize)
        for(side in listOf("UP","DOWN"))
            observed+=detectWilliamsComplexWave(confirmedPrefix,side,asOf)
                .filter { it.pattern in WILLIAMS_TC2_COMPLEX_PATTERNS &&
                    it.knownAt in 1L..asOf && it.endAt<=asOf }
    }
    // Do NOT discard old candidates here. A valid unresolved correction can
    // be older than 48 newer *resolved* candidates. Cutting to 48 before
    // checking their current price status could incorrectly permit an entry.
    // A fixed-size display sample is selected AFTER all live statuses and the
    // trade veto have been evaluated.
    return observed.distinctBy { listOf(it.pattern,it.direction,it.startAt,it.endAt) }
        .sortedWith(compareByDescending<WilliamsComplexEvidence> { it.knownAt }
            .thenByDescending { it.endAt })
}

/** Bounded cockpit projection, never the entry decision itself.
 * Prioritise unresolved structures INSIDE the requested entry price, then
 * other unresolved structures; last show resolved historical candidates.
 * The decision engine must always examine the FULL bounded source list.
 */
internal fun williamsTc2ComplexPatternRu(code:String):String = when(code) {
    "REGULAR_FLAT_ABC_CANDIDATE" -> "обычная плоская коррекция A–B–C (предположение)"
    "EXPANDED_FLAT_ABC_CANDIDATE" -> "расширенная плоская коррекция A–B–C (предположение)"
    "CONTRACTING_TRIANGLE_ABCDE_CANDIDATE" -> "сужающийся треугольник A–B–C–D–E (предположение)"
    "EXPANDING_TRIANGLE_ABCDE_CANDIDATE" -> "расширяющийся треугольник A–B–C–D–E (предположение)"
    "BARRIER_TRIANGLE_ABCDE_CANDIDATE" -> "треугольник с горизонтальной границей (предположение)"
    "W_X_Y_ZIGZAG_COMBINATION_CANDIDATE" -> "составная коррекция W–X–Y (предположение)"
    "W_X_Y_X_Z_ZIGZAG_COMBINATION_CANDIDATE" -> "составная коррекция W–X–Y–X–Z (предположение)"
    else -> "тип сложной коррекции не установлен"
}

internal fun williamsTc2ComplexEntryBlocked(all:List<JSONObject>):Boolean =
    all.any { !it.optBoolean("range_resolved") && it.optBoolean("crosses_entry") }

internal fun williamsTc2ComplexStructureDisplay(
    all:List<JSONObject>,maxVisible:Int=48
):List<JSONObject> {
    if(maxVisible<=0) return emptyList()
    return all.filter { it.optBoolean("crosses_entry") } .take(maxVisible) +
        all.filter { !it.optBoolean("crosses_entry") && !it.optBoolean("range_resolved") }
            .take((maxVisible-all.count { it.optBoolean("crosses_entry") }).coerceAtLeast(0)) +
        all.filter { it.optBoolean("range_resolved") }
            .take((maxVisible-all.count { !it.optBoolean("range_resolved") }).coerceAtLeast(0))
}


internal fun williamsTc2CongestionEvidence(
    closedBars:List<WilliamsWaveBar>,nowMs:Long,
    signalType:String,direction:String,trigger:Double
):JSONObject {
    val result=JSONObject().put("schema","WILLIAMS_TC2_CONGESTION_V1")
        .put("policy",WILLIAMS_TC2_CONGESTION_POLICY)
        .put("book_reference","TC2_CH11_FIG_11_7_PDF_151")
        .put("complex_correction_grammar_reference","RESEARCH_ELLIOTT_COMPLEX_PRICE_GRAMMAR")
        .put("classification_is_provisional",true)
        .put("numerical_thresholds_are_engineering",true)
        .put("complex_candidate_retention","CONFIRMED_PRICE_PIVOT_PREFIXES_UNTIL_CLOSED_H1_BREAKOUT")
        .put("source","CLOSED_CONTIGUOUS_H1_CANDLES")
        .put("can_place_orders",false).put("can_close",false)
        .put("can_change_stops",false)
        .put("blocked",false).put("state","HISTORY_UNVERIFIED")
        .put("reason_code","NOT_EVALUATED")
    val family=signalType.removePrefix("SHORT_").removePrefix("LONG_")
    if(direction !in setOf("LONG","SHORT") || family !in setOf("REVERSAL","SUPER_AO","FRACTAL") ||
        !trigger.isFinite() || trigger<=0.0) return result.put("reason_code","INVALID_SIGNAL_EVIDENCE")
    if(closedBars.size<60 || nowMs<=0L ||
        williamsClosedH1HistoryBlocker(closedBars,nowMs)!=null ||
        !validWilliamsEditionBars(closedBars)) return result.put("reason_code","INSUFFICIENT_OR_STALE_H1")
    val window=closedBars.takeLast(24)
    val highs=window.maxOf { it.h }
    val lows=window.minOf { it.l }
    val breaksRange=if(direction=="LONG") trigger>highs else trigger<lows
    val mids=closedBars.map { (it.h+it.l)/2.0 }
    val teethRaw=smmaSeededValues(mids,8)
    val jaw=shiftAlligatorForDisplay(smmaSeededValues(mids,13),8)
    val teeth=shiftAlligatorForDisplay(teethRaw,5)
    val lips=shiftAlligatorForDisplay(smmaSeededValues(mids,5),3)
    val phases=williamsAlligatorPhases(closedBars,
        jaw.map { it?:Double.NaN },teeth.map { it?:Double.NaN },
        lips.map { it?:Double.NaN })
    val start=closedBars.size-24
    if((start until closedBars.size).any { i ->
        listOf(teeth[i],jaw[i],lips[i]).any { it==null || !it.isFinite() || it<=0.0 }
    }) return result.put("reason_code","ALLIGATOR_HISTORY_UNVERIFIED")
    fun crossings(values:List<Double>):Int {
        var prior=0
        var changes=0
        for(value in values) {
            val sign=when { value>0.0 -> 1; value<0.0 -> -1; else -> 0 }
            if(sign==0) continue
            if(prior!=0 && sign!=prior) changes++
            prior=sign
        }
        return changes
    }
    val teethCrosses=crossings((start until closedBars.size).map { i ->
        closedBars[i].c-teeth[i]!!
    })
    val ao=(start until closedBars.size).map { i ->
        fun mean(n:Int)=mids.subList(i-n+1,i+1).average()
        mean(5)-mean(34)
    }
    if(ao.any { !it.isFinite() }) return result.put("reason_code","AO_HISTORY_UNVERIFIED")
    val aoFlips=crossings(ao)
    val lastEight=phases.takeLast(8)
    val nonTrending=lastEight.count { it in setOf("SLEEPING","SATIATED") }
    // These numerical range observations are only CONTEXT, not a veto.
    // Complex flats/triangles/combinations need confirmed multi-swing
    // geometry. Simple ABC zigzags and unresolved corrections remain tradable.
    val rangeObserved=teethCrosses>=3 && aoFlips>=2 && nonTrending>=4
    val confirmedPivots=confirmedWilliamsWaveAnchors(closedBars,WILLIAMS_H1_MS)
    val structural=williamsTc2RecentComplexPriceCandidates(confirmedPivots,nowMs)
    val structures=structural.mapNotNull { pattern ->
        val involved=confirmedPivots.filter { it.extremeAt in pattern.startAt..pattern.endAt }
        if(involved.size<6) null else {
            val patternHigh=involved.maxOf { it.price }
            val patternLow=involved.minOf { it.price }
            // The last known confirmation pivot is an historical candidate,
            // not a permanent active correction. A LATER *closed* H1 candle
            // decisively beyond its price limits resolves the old range.
            // A wick alone is NOT a confirmed breakout; a forming H1 candle
            // is NEVER examined. This post-confirmation close policy is an
            // explicit engineering interpretation, not a Williams formula.
            val resolution=closedBars.firstOrNull { b ->
                b.t>=pattern.knownAt && (b.c>patternHigh || b.c<patternLow)
            }
            val unresolved=resolution==null
            JSONObject().put("pattern",pattern.pattern).put("known_at_ms",pattern.knownAt)
                .put("start_at_ms",pattern.startAt).put("end_at_ms",pattern.endAt)
                .put("range_high",patternHigh).put("range_low",patternLow)
                .put("state",if(unresolved) "UNRESOLVED_PRICE_RANGE" else "RESOLVED_BY_CLOSED_H1_PRICE")
                .put("range_resolved",!unresolved)
                .put("first_closed_breakout_at_ms",resolution?.t?.plus(WILLIAMS_H1_MS) ?: JSONObject.NULL)
                .put("resolved_direction",if(resolution==null) JSONObject.NULL
                    else if(resolution.c>patternHigh) "UP" else "DOWN")
                .put("inside_range",trigger in patternLow..patternHigh)
                .put("crosses_entry",unresolved && trigger in patternLow..patternHigh)
        }
    }
    val active=structures.filter { !it.optBoolean("range_resolved") }
    val insideComplex=williamsTc2ComplexEntryBlocked(structures)
    val displayed=williamsTc2ComplexStructureDisplay(structures)
    val blockingExample=active.firstOrNull { it.optBoolean("crosses_entry") }
    // H1 entry outside a STILL-ACTIVE candidate is a possible breakout.
    // A closed breakout resolves the stale range and never resurrects its
    // inside-price veto. Other H1 Wise-Man and Binance checks still apply.
    val block=insideComplex
    val state=when {
        active.isNotEmpty() -> "COMPLEX_CORRECTION_CANDIDATE"
        structures.isNotEmpty() -> "COMPLEX_CORRECTION_RESOLVED"
        rangeObserved -> "ORDINARY_CONGESTION_OR_CORRECTION_NOT_COMPLEX"
        else -> "NO_COMPLEX_CORRECTION_EVIDENCE"
    }
    val explanationRu=when {
        block -> "На закрытых H1 выявлена геометрия возможной сложной коррекции " +
            "(плоская/неправильная коррекция, треугольник или комбинация). " +
            "Обнаруженный паттерн: ${williamsTc2ComplexPatternRu(blockingExample?.optString("pattern").orEmpty())}. " +
            "Диапазон: ${blockingExample?.optDouble("range_low") ?: Double.NaN} – " +
            "${blockingExample?.optDouble("range_high") ?: Double.NaN}; " +
            "запрошенный уровень входа: $trigger. " +
            "Подтверждение структуры по H1 (время Unix мс): " +
            "${blockingExample?.optLong("known_at_ms") ?: 0L}. " +
            "Цена входа остаётся внутри ещё не разрешённого по закрытию диапазона. " +
            "Новая заявка TC2 не разрешена; " +
            "уже открытая позиция и её биржевой STOP продолжают сопровождаться. " +
            "Волновая разметка является гипотезой, а фильтр — дополнительной политикой риска."
        active.isNotEmpty() -> "Есть геометрические признаки сложной коррекции на H1, " +
            "но уровень входа расположен вне её ценового диапазона. " +
            "Фильтр сложной коррекции не запрещает этот сигнал, однако отдельные " +
            "условия Мудреца, риск и проверка Binance всё ещё обязательны."
        structures.isNotEmpty() -> "Ранее наблюдавшаяся возможная сложная коррекция " +
            "вышла за свои границы: после подтверждения фигуры закрытая свеча H1 " +
            "завершилась вне диапазона. Старую структуру больше нельзя считать активной; " +
            "торговля по этому признаку не запрещена, но сигнал TC2 проверяется отдельно."
        rangeObserved -> "Зафиксированы признаки бокового движения (пересечения " +
            "Аллигатора и AO), но доказанной геометрии сложной коррекции нет. " +
            "Обычный диапазон и простую A–B–C фильтр не блокирует."
        else -> "По имеющимся закрытым H1 нет достаточных геометрических признаков " +
            "сложной коррекции. Это не доказывает отсутствие коррекции; " +
            "новый вход возможен только по подтверждённому сигналу TC2 и при защите Binance."
    }
    return result.put("state",state).put("explanation_ru",explanationRu)
        .put("source_book_reference","TC1_CH7_PDF_53_57_TC2_CH11_FIG_11_7_PDF_151")
        .put("confidence_calibrated",false)
        .put("reason_code",when {
            block -> WILLIAMS_TC2_CONGESTION_BLOCKER
            active.isNotEmpty() && !insideComplex -> "ENTRY_OUTSIDE_COMPLEX_STRUCTURE"
            structures.isNotEmpty() && active.isEmpty() -> "COMPLEX_RANGE_RESOLVED_BY_CLOSED_H1"
            rangeObserved -> "ONLY_CONGESTION_NOT_COMPLEX_NO_VETO"
            else -> "NO_COMPLEX_CORRECTION_PROOF"
        }).put("blocked",block).put("window_bars",window.size)
        .put("range_high",highs).put("range_low",lows)
        .put("signal_type",family).put("direction",direction)
        .put("trigger",trigger).put("breakout_outside_range",breaksRange)
        .put("teeth_close_crossings",teethCrosses).put("ao_zero_crossings",aoFlips)
        .put("satiated_or_sleeping_last_8",nonTrending)
        .put("complex_structures",JSONArray(displayed))
        .put("complex_structure_count",structures.size)
        .put("complex_structure_display_count",displayed.size)
        .put("complex_structure_display_truncated",displayed.size<structures.size)
        .put("complex_structure_display_note_ru",if(displayed.size<structures.size)
            "Показаны ${displayed.size} из ${structures.size} гипотез. Решение о блокировке рассчитано по ВСЕМ проверенным кандидатам; более старые активные структуры не исключались из проверки."
            else "Показаны все ${structures.size} проверенных гипотез сложной коррекции.")
        .put("blocking_complex_structure",blockingExample ?: JSONObject.NULL)
        .put("active_inside_entry_count",active.count { it.optBoolean("crosses_entry") })
        .put("active_complex_structure_count",active.size)
        .put("complex_structure_requires_unresolved_closed_price_range",true)
        .put("complex_structure_blocks_only_when_inside",true)
        .put("simple_zigzag_abc_is_allowed",true)
        .put("completed_complex_flat_can_block",true)
        .put("running_or_incomplete_flat_does_not_auto_block",true)
        .put("generic_wxy_unresolved_is_not_a_veto",true)
        .put("as_of_close_ms",closedBars.last().t+WILLIAMS_H1_MS)
}

internal fun williamsTc2CongestionEntryBlocker(
    source:String,signalType:String,direction:String,trigger:Double,
    closedBars:List<WilliamsWaveBar>,nowMs:Long
):String? = if(source!=williamsTc2Profile) null else {
    val report=williamsTc2CongestionEvidence(closedBars,nowMs,signalType,direction,trigger)
    if(report.optBoolean("blocked",false)) WILLIAMS_TC2_CONGESTION_BLOCKER else null
}

/**
 * An H1 range reclassification is never proof that a Binance algo order has
 * not already started filling. Request revocation only for exchange-known
 * untriggered conditional entry states. The coordinator still must cancel,
 * verify the terminal exchange response, and reconcile positions before any
 * further new order. A position or its protective STOP is never a candidate.
 */
internal fun williamsTc2PendingCongestionRecheckEligible(
    role:String,exchangeStatus:String,clientId:String,
    knownFill:Boolean=false
):Boolean = !knownFill && role in setOf("ENTRY","ADD_ON") &&
    (clientId.startsWith("W2H_") || clientId.startsWith("W2A_")) &&
    exchangeStatus.uppercase(java.util.Locale.US) in
        setOf("NEW","PENDING","WORKING","ACCEPTED")
