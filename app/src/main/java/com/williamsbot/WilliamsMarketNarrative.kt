package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

/**
 * Human-readable market LOCATION, not a discretionary trading strategy.
 *
 * The five frames must be actual, independently closed exchange candles.
 * Confirmed facts (Alligator geometry / AO change) are intentionally
 * separated from competing Elliott hypotheses. A junior change of bias
 * cannot, by itself, invalidate a senior impulse or trigger a position exit.
 *
 * Used only by DecisionTrace and Cockpit. No mutation-capable dependency.
 */
internal fun williamsMarketNarrative(phase:JSONObject):JSONObject {
    val asOf=phase.optLong("as_of",0L)
    fun rows(array:JSONArray?):List<JSONObject> = if(array==null) emptyList() else
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    val rows=rows(phase.optJSONArray("frames"))
        .associateBy { it.optString("frame") }
    val order=listOf("1d","6h","4h","1h","15m")
    val names=mapOf("1d" to "D1","6h" to "H6","4h" to "H4","1h" to "H1","15m" to "M15")
    val details=JSONArray()
    val biases=linkedMapOf<String,String>()
    val reliable=linkedSetOf<String>()
    for(frame in order) {
        val row=rows[frame]
        val reading=row?.optJSONObject("market_reading")
        val readingReady=asOf>0L && row!=null && reading!=null &&
            reading.optString("state")=="OBSERVED" &&
            reading.optLong("as_of_close_ms") in 1L..asOf
        val phaseState=if(asOf<=0) "NOT_ANALYSED" else row?.optString("state") ?: "UNAVAILABLE"
        val bias=if(readingReady) reading!!.optString("observed_bias") else "UNKNOWN"
        val alligator=if(readingReady)
            reading!!.optJSONObject("alligator")?.optString("state","UNKNOWN") ?: "UNKNOWN"
        else "UNKNOWN"
        val oscillators=if(readingReady) JSONObject()
            .put("ao",reading!!.opt("ao") ?: JSONObject.NULL)
            .put("ac",reading.opt("ac") ?: JSONObject.NULL)
            .put("ao_direction",reading.optString("ao_direction","UNKNOWN"))
            .put("ac_direction",reading.optString("ac_direction","UNKNOWN"))
        else JSONObject().put("status","NOT_VERIFIED")
        val hypotheses=rows(row?.optJSONArray("hypotheses"))
        val unique=hypotheses.map { it.optString("active_label") to it.optString("active_direction") }
            .distinct()
        val wave=if(phaseState=="PHASE_CANDIDATE" && unique.size==1)
            "${unique.first().first} (${unique.first().second}), предварительная волновая гипотеза"
        else if(phaseState=="ALTERNATIVES" || unique.size>1)
            "несколько конкурирующих волновых гипотез"
        else "волновая стадия не подтверждена"
        val observed=if(readingReady) {
            reliable+=frame
            biases[frame]=bias
            val motion=when(bias) { "UP"->"восходящий";"DOWN"->"нисходящий";else->"смешанный" }
            val mouth=when(alligator) {
                "SLEEPING"->"Аллигатор спит"
                "AWAKENING"->"Аллигатор пробуждается"
                "EATING"->"Аллигатор движется в тренде"
                "SATIATED"->"Аллигатор насыщается"
                else->"фаза Аллигатора не подтверждена"
            }
            "${names.getValue(frame)}: ${motion} контекст; ${mouth}; ${wave}."
        } else {
            val reason=when {
                asOf<=0L -> "анализ не запускался"
                phaseState=="UNAVAILABLE" -> "нет проверенного анализа старшего ТФ"
                reading==null -> "нет отдельного подтверждённого чтения свечей"
                else -> "история отсутствует, неполна или устарела"
            }
            "${names.getValue(frame)}: ${reason}; направление и волна неизвестны."
        }
        details.put(JSONObject()
            .put("frame",frame).put("label",names.getValue(frame)).put("state",phaseState)
            .put("chart_observed",readingReady)
            .put("candle_closed_at_ms",if(readingReady) reading!!.optLong("as_of_close_ms") else JSONObject.NULL)
            .put("observed_bias",bias).put("alligator_phase",alligator)
            .put("indicators",oscillators)
            .put("wave_text",wave).put("hypothesis_count",hypotheses.size)
            .put("explanation_ru",observed)
            .put("wave_degree_confirmed",false)
            .put("can_place_orders",false).put("can_close",false))
    }
    val h1=biases["1h"]; val m15=biases["15m"]
    val opposed=h1 in listOf("UP","DOWN") && m15 in listOf("UP","DOWN") && h1!=m15
    val caution=if(opposed)
        "M15 направлен противоположно H1. Это может быть младшей коррекцией; разворот H1 или H4 НЕ подтверждён."
    else if(h1==null || m15==null)
        "Недостаточно проверенных H1/M15 свечей для сопоставления; старшую волну не угадывать."
    else "Смена направления младшего ТФ сама по себе не меняет степень волны старшего ТФ."
    return JSONObject().put("schema","WILLIAMS_MTF_MARKET_NARRATIVE_V1")
        .put("as_of",asOf).put("frames",details)
        .put("observed_frame_count",reliable.size)
        .put("expected_frame_count",order.size)
        .put("junior_bias_opposes_h1",opposed)
        .put("cross_timeframe_caution_ru",caution)
        .put("interpretation","CLOSED_CANDLE_OBSERVATION_PLUS_UNCONFIRMED_WAVE_HYPOTHESES")
        .put("source","LOCAL_INDEPENDENT_CLOSED_CANDLES")
        .put("confidence_calibrated",false)
        .put("wave_genealogy_confirmed",false)
        .put("can_place_orders",false).put("can_close",false).put("can_reverse",false)
}
