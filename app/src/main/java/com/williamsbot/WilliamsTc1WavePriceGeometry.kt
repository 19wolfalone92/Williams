package com.williamsbot

import org.json.JSONObject
import java.util.Locale

/**
 * TC1 (Trading Chaos), Russian PDF pages 52–54:
 * W2 retracement normally 38–62% of W1; W3 often 1–1.62x W1;
 * W4 commonly retraces 38–50% of W3. These are TYPICAL outcomes,
 * never hard entry/exit conditions. The W5 origin cannot be established
 * until W4 is a confirmed pivot. All labels remain Elliott hypotheses.
 *
 * Causal, local, non-executable reader of original known pivots only.
 */
internal fun williamsTc1WavePriceGeometry(scenario:JSONObject):JSONObject {
    val result=JSONObject()
        .put("schema","TC1_CH7_FIBONACCI_WAVE_PRICE_EVIDENCE_V1")
        .put("reference","TC1_RU_PDF_52_54_PRINTED_52_54")
        .put("source_authority","BOOK_TYPICAL_RANGES_NOT_INVIOLABLE_TRADE_RULES")
        .put("status","UNVERIFIED_WAVE_ANCHORS")
        .put("wave_degree_proven",false)
        .put("internal_wave_subdivisions_proven",false)
        .put("is_tc2_entry_gate",false).put("can_place_orders",false)
        .put("can_move_stop",false).put("can_close",false)
        .put("w2_retracement",JSONObject().put("status","NOT_OBSERVED"))
        .put("w3_extension",JSONObject().put("status","NOT_OBSERVED"))
        .put("w4_retracement",JSONObject().put("status","NOT_OBSERVED"))
        .put("w5_projection",JSONObject().put("status","W4_NOT_CONFIRMED"))
        .put("abc_b_retracement",JSONObject().put("status","NO_CONFIRMED_CORRECTIVE_A_B"))
        .put("wave_alternation",JSONObject().put("status","INTERNAL_W2_W4_TYPES_UNVERIFIED"))
    val observed=scenario.optLong("observed_at",0L)
    val dir=scenario.optString("direction")
    val arr=scenario.optJSONArray("anchors")
    val frame=scenario.optString("frame")
    if(observed<=0L || dir !in setOf("UP","DOWN") ||
        frame !in williamsWaveFrames || arr==null || arr.length() !in 3..8 ||
        scenario.optString("status")!="PROVISIONAL") return result

    val pivot=(0 until arr.length()).map { arr.optJSONObject(it) ?: return result }
    val points=pivot.mapIndexed { i,p ->
        val kind=p.optString("kind")
        val price=p.optDouble("price",Double.NaN)
        val extreme=p.optLong("extreme_at",Long.MIN_VALUE)
        val known=p.optLong("known_at",Long.MIN_VALUE)
        val expected=if((i%2==0)==(dir=="UP")) "LOW" else "HIGH"
        if(kind!=expected || !price.isFinite() || price<=0.0 ||
            extreme<0L || known<extreme || known>observed ||
            extreme%williamsWaveFrames.getValue(frame)!=0L) return result
        Triple(price,extreme,known)
    }
    if(points.zipWithNext().any { (a,b)->a.second>=b.second || a.third>b.third }) return result
    val sign=if(dir=="UP") 1.0 else -1.0
    val values=points.map { sign*it.first }
    val one=values[1]-values[0]
    val two=values[1]-values[2]
    if(one<=0.0 || two<0.0 || values[2]<=values[0]) return result
    result.put("status","BOUNDED_PRICE_EVIDENCE")
        .put("frame",frame).put("direction",dir)
        .put("observed_at_ms",observed)
        .put("last_confirmed_anchor_ms",points.last().third)
        .put("confirmed_anchor_count",points.size)

    fun ratioState(value:Double,low:Double,high:Double):String=when {
        value<low-1e-9 -> "BELOW_TYPICAL_BOOK_BAND"
        value>high+1e-9 -> "ABOVE_TYPICAL_BOOK_BAND"
        else -> "WITHIN_TYPICAL_BOOK_BAND"
    }
    val w2ratio=two/one
    result.put("w2_retracement",JSONObject()
        .put("status",ratioState(w2ratio,.38,.62))
        .put("fraction",w2ratio).put("band_min",.38).put("band_max",.62)
        .put("first_wave_price_distance",one)
        .put("hard_trade_veto",false)
        .put("explanation_ru","Волна 2 откатила " +
            String.format(Locale.US,"%.2f",w2ratio*100) +
            "% длины предполагаемой волны 1. По TC1 наиболее типична область 38–62%, но это статистическое описание, а не обязательное условие входа."))
    if(points.size>=4) {
        val third=values[3]-values[2]
        if(third<=0.0 || values[3]<=values[1])
            return result.put("status","MOTIVE_PRICE_SEQUENCE_CONFLICT")
        val ratio=third/one
        val base=points[2].first
        result.put("w3_extension",JSONObject()
            .put("status",ratioState(ratio,1.0,1.62))
            .put("fraction",ratio).put("band_min",1.0).put("band_max",1.62)
            .put("projected_low_from_w2",base+sign*one)
            .put("projected_high_from_w2",base+sign*1.62*one)
            .put("w3_confirmed_price",points[3].first)
            .put("hard_trade_veto",false)
            .put("explanation_ru","Предполагаемая волна 3 прошла " +
                String.format(Locale.US,"%.2f",ratio) +
                " длины волны 1. В книге описана ориентировочная область 1,00–1,62; выход из неё не доказывает ошибочную разметку."))
        if(points.size>=5) {
            val fourth=values[3]-values[4]
            if(fourth<0.0 || values[4]<=values[2])
                return result.put("status","MOTIVE_PRICE_SEQUENCE_CONFLICT")
            val w4ratio=fourth/third
            val overlap=values[4]<=values[1]
            result.put("w4_retracement",JSONObject()
                .put("status",ratioState(w4ratio,.38,.50))
                .put("fraction",w4ratio).put("band_min",.38).put("band_max",.50)
                .put("overlaps_w1_price_territory",overlap)
                .put("ordinary_impulse_uncertain_if_overlap",overlap)
                .put("diagonal_or_alternative_count_requires_review",overlap)
                .put("hard_trade_veto",false)
                .put("explanation_ru","Предполагаемая волна 4 откатила " +
                    String.format(Locale.US,"%.2f",w4ratio*100) +
                    "% волны 3; типичная область по TC1 — 38–50%. " +
                    (if(overlap) "Цена пересекла территорию волны 1: обычный импульс под вопросом, возможна диагональ или другая разметка."
                    else "Перекрытия с территорией волны 1 по этим экстремумам не обнаружено.")))
            result.put("w5_projection",JSONObject()
                .put("status","W4_PIVOT_CONFIRMED_PROJECTION_BASE_ONLY")
                .put("origin_confirmed_at_ms",points[4].third)
                .put("origin_price",points[4].first)
                .put("price_target_not_invented",true)
                .put("explanation_ru","Точка отсчёта возможной волны 5 известна после подтверждения завершения волны 4. Книга не даёт права выводить универсальную цель по одним пяти экстремумам."))
        }
    }
    // TC1 ch7 PDF pp.54–55: for a simple five-three-five zigzag, B often
    // retraces no more than 62% of A; 50–62% is the author's zone for
    // considering a fractal + squat setup to trade C. The B/A price ratio
    // ALONE cannot prove a simple correction (internal A=5, B=3, C=5).
    // Do not turn the zone into an entry order or an automatic C forecast.
    if(points.size>=8) {
        val a=values[5]-values[6]
        val b=values[7]-values[6]
        if(a>0.0 && b>0.0) {
            val ba=b/a
            val zone=ba>=.50-1e-9 && ba<=.62+1e-9
            result.put("abc_b_retracement",JSONObject()
                .put("status",when {
                    zone -> "ZIGZAG_B_50_62_PRICE_ZONE_ONLY"
                    ba>=.90 -> "DEEP_B_MAY_BE_FLAT_OR_ALTERNATIVE_COUNT"
                    else -> "OUTSIDE_TYPICAL_ZIGZAG_B_ZONE"
                })
                .put("b_over_a",ba)
                .put("source_book_zone_min",.50).put("source_book_zone_max",.62)
                .put("zigzag_5_3_5_internal_degree_verified",false)
                .put("fractal_and_squat_confirmed_here",false)
                .put("c_wave_confirmed",false)
                .put("can_enter_c_wave",false)
                .put("explanation_ru","Предполагаемая B-волна откатила " +
                    String.format(Locale.US,"%.2f",ba*100) +
                    "% движения A. TC1 выделяет 50–62% для поиска отдельного фрактала и «приседающего»; без подтверждения внутренних подволн это не сигнал к торговле C."))
        }
    }
    return result
}
