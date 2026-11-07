package com.williamsbot

import org.json.JSONObject
import kotlin.math.max

/**
 * Read-only comparison of the THREE price-bar STOP alternatives in Trading Chaos
 * 2nd edition, chapter 12, Russian PDF pp.161 and 165 (printed pp.146, 150).
 *
 * The book explicitly permits a trailing STOP based on the last 3–5 bars;
 * some traders prefer four or five bars to allow more room. There is no
 * universally prescribed winning window. This observer neither chooses a live method nor creates or
 * changes Binance orders. Existing signed STOP and production TC2 selection
 * remain the sole authority for actual risk management.
 *
 * Requires a contiguous, fully closed H1 prefix. We deliberately require
 * complete bars AFTER the position's first observed timestamp; it is not
 * safe to call earlier history "post entry". This conservative extra gate
 * is an engineering protection and is not quoted as a book rule.
 */
internal fun tc2ThreeFiveBarStudy(
    direction: String,
    oldStop: Double,
    markPrice: Double,
    tickSize: Double,
    firstObservedPositionAtMs: Long,
    asOfMs: Long,
    closedH1: List<FuturesTrailBar>
): JSONObject {
    val study = JSONObject()
        .put("schema", "TC2_THREE_FOUR_FIVE_CLOSED_H1_SHADOW_V2")
        .put("edition", "TC2")
        .put("source_pdf_page", 165)
        .put("source_pdf_pages",org.json.JSONArray(listOf(161,165)))
        .put("frame", "1h")
        .put("execution_authority", false)
        .put("source", "CLOSED_H1_ONLY")
        .put("explanation_ru", "По TC2 сопоставляем три разрешённых автором варианта переноса STOP: за 3, 4 или 5 последними закрытыми барами H1. Это только исследование, биржевой STOP автоматически не изменяется.")
        .put("first_observed_position_at_ms", firstObservedPositionAtMs)
        .put("as_of_ms", asOfMs)
        .put("window3", JSONObject())
        .put("window4", JSONObject())
        .put("window5", JSONObject())
    fun invalid(reason: String): JSONObject {
        study.put("series_state", reason)
            .put("explanation_ru", "Сопоставление STOP на 3/4/5 барах не подтверждено: " +
                when(reason) {
                    "INVALID_OWNER_PRICE_OR_TIME" -> "цена, направление, шаг биржи или время позиции не прошли проверку."
                    "CURRENT_STOP_AND_MARK_PRICE_INCONSISTENT" -> "действующий STOP противоречит текущей цене MARK."
                    "CLOSED_H1_CONTINUITY_OR_OHLC_UNVERIFIED" -> "закрытые H1 свечи содержат разрыв, незакрытый час или ошибочный максимум/минимум."
                    "STALE_OR_MISSING_LATEST_CLOSED_H1" -> "последняя завершённая H1 свеча отсутствует или устарела."
                    else -> "нет достаточных проверяемых исходных данных."
                })
        for(window in listOf(3, 4, 5)) {
            study.put("window$window", JSONObject().put("window", window)
                .put("status", "UNVERIFIED_INPUT")
                .put("reason", reason).put("execution_authority", false)
                .put("explanation_ru",study.getString("explanation_ru")))
        }
        return study
    }
    if(direction !in setOf("LONG", "SHORT") || asOfMs<=0L ||
        firstObservedPositionAtMs<=0L || asOfMs<firstObservedPositionAtMs ||
        !oldStop.isFinite() || oldStop<=0.0 ||
        !markPrice.isFinite() || markPrice<=0.0 ||
        !tickSize.isFinite() || tickSize<=0.0)
        return invalid("INVALID_OWNER_PRICE_OR_TIME")
    if((direction=="LONG" && oldStop>=markPrice) ||
        (direction=="SHORT" && oldStop<=markPrice))
        return invalid("CURRENT_STOP_AND_MARK_PRICE_INCONSISTENT")
    // Never permit a partial current H1 bar or a fabricated calendar gap
    // to enter this rule comparison.
    if(closedH1.any { b ->
            b.openTimeMs<0L || b.openTimeMs%WILLIAMS_H1_MS!=0L ||
            b.openTimeMs>asOfMs-WILLIAMS_H1_MS ||
            !b.high.isFinite() || !b.low.isFinite() ||
            b.low<=0.0 || b.high<b.low
        } || closedH1.zipWithNext().any { (a,b) ->
            b.openTimeMs-a.openTimeMs!=WILLIAMS_H1_MS
        })
        return invalid("CLOSED_H1_CONTINUITY_OR_OHLC_UNVERIFIED")

    // A contiguous but old 3/5-bar tail is not the current trailing basis.
    // Missing the latest completed H1 must remain UNVERIFIED rather than
    // show a seemingly executable historic source level.
    val lastClose=closedH1.lastOrNull()?.openTimeMs?.plus(WILLIAMS_H1_MS)
    if(lastClose==null || asOfMs-lastClose !in 0 until WILLIAMS_H1_MS)
        return invalid("STALE_OR_MISSING_LATEST_CLOSED_H1")
    study.put("series_state", "CLOSED_H1_VALIDATED")
        .put("last_closed_bar_at_ms",
            closedH1.lastOrNull()?.openTimeMs ?: JSONObject.NULL)
        .put("known_at_ms", closedH1.lastOrNull()
            ?.let { it.openTimeMs+WILLIAMS_H1_MS } ?: JSONObject.NULL)
    val minGap=max(2.0*tickSize,markPrice*0.00025)
    for(window in listOf(3,4,5)) {
        val evidence=JSONObject().put("window", window)
            .put("book_source_pdf_page",if(window==4) 161 else 165)
            .put("status", "NOT_OBSERVED")
            .put("execution_authority", false)
            .put("initial_stop_preserved", true)
            .put("minimum_mark_gap",minGap)
        val bars=closedH1.takeLast(window)
        when {
            bars.size<window -> {
                evidence.put("status", "NOT_ENOUGH_CLOSED_H1_BARS")
                    .put("observed_closed", bars.size)
                    .put("explanation_ru","Для варианта $window баров недостаточно закрытых H1 свечей: найдено только ${bars.size}. Биржевой STOP остаётся без изменений.")
            }
            bars.any { it.openTimeMs<firstObservedPositionAtMs } -> {
                val observed=bars.count { it.openTimeMs>=firstObservedPositionAtMs }
                evidence.put("status", "NOT_ENOUGH_FULL_POST_OBSERVATION_BARS")
                    .put("observed_post_position_bars",observed)
                    .put("explanation_ru","После первого достоверного наблюдения позиции подтверждено только $observed из $window завершённых H1 баров. Перенос STOP по данному варианту не рассматривается.")
            }
            else -> {
                val extreme=if(direction=="LONG") bars.minOf { it.low }
                    else bars.maxOf { it.high }
                evidence.put("source_extreme", extreme)
                    .put("source_first_bar_ms", bars.first().openTimeMs)
                    .put("source_last_bar_ms", bars.last().openTimeMs)
                val stop=tc2ExactStructuralStop(direction,extreme,tickSize)
                if(stop==null) {
                    evidence.put("status", "SOURCE_EXTREME_OFF_EXCHANGE_TICK_GRID")
                        .put("explanation_ru","Экстремум $extreme за $window баров не соответствует шагу цены Binance $tickSize. Уровень STOP нельзя округлять произвольно.")
                } else {
                    val tightening=if(direction=="LONG") stop>oldStop+tickSize*0.5
                        else stop<oldStop-tickSize*0.5
                    val safeMarkGap=if(direction=="LONG")
                        stop+minGap<markPrice
                    else stop-minGap>markPrice
                    val improvement=if(direction=="LONG") stop-oldStop else oldStop-stop
                    evidence.put("source_stop_price", stop)
                        .put("tightens_existing_stop", tightening)
                        .put("passes_mark_gap", safeMarkGap)
                        .put("stop_risk_reduction_per_unit", improvement)
                        .put("status", when {
                            !tightening -> "NOT_TIGHTER_THAN_EXISTING_STOP"
                            !safeMarkGap -> "PROPOSAL_TOO_CLOSE_TO_MARK"
                            else -> "SHADOW_ELIGIBLE_NOT_AN_ORDER"
                        })
                        .put("explanation_ru",when {
                            !tightening -> "Расчётный STOP $stop за $window барами не улучшает действующую защиту $oldStop. Ослабление STOP запрещено."
                            !safeMarkGap -> "Расчётный STOP $stop за $window барами расположен опасно близко к текущей MARK $markPrice. Реальное изменение ордера не допускается."
                            else -> "По $window завершённым H1 барам возможен теоретический более строгий STOP $stop, вместо $oldStop. Это только гипотеза для сравнения по TC2, а НЕ команда отправки ордера Binance."
                        })
                }
            }
        }
        study.put("window$window",evidence)
    }
    return study
}
