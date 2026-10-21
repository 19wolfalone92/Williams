package com.williamsbot

import org.json.JSONObject

internal fun williamsFrameLabel(frame:String):String = when(frame) {
    "1d" -> "D1"; "6h" -> "H6"; "4h" -> "H4"; "1h" -> "H1"; "15m" -> "M15"; else -> frame
}
internal fun williamsHistoryReason(reason:String):String = when {
    reason=="WAVE_ANALYSIS_NOT_RUN" -> "волновой анализ не выполнялся"
    reason=="INSUFFICIENT_CLOSED_HISTORY" || reason=="INSUFFICIENT_HISTORY" -> "недостаточно закрытых свечей"
    reason=="STALE_CLOSED_HISTORY" || reason=="STALE_HISTORY" -> "история устарела"
    reason=="GAPPED_OR_UNORDERED_HISTORY" -> "пропуск или нарушение порядка свечей"
    reason=="INVALID_OHLCV" || reason=="HISTORY_UNVERIFIED" -> "качество свечей не подтверждено"
    reason.startsWith("HISTORY_READ_FAILED") -> "не удалось прочитать историю"
    else -> "данные анализа недоступны"
}
internal fun williamsAlligatorStateLabel(state:String):String = when(state) {
    "SLEEPING" -> "сон"; "AWAKENING" -> "пробуждение"; "EATING" -> "развитие тренда"
    "SATIATED" -> "затухание движения"; else -> "состояние не определено"
}
internal fun williamsMarketObservationLabel(frame:JSONObject):String {
    val prefix=williamsFrameLabel(frame.optString("frame"))+" · "
    val reading=frame.optJSONObject("market_reading")
        ?: return prefix+"сводка индикаторов не передана"
    if(reading.optString("state")!="OBSERVED") return prefix+
        williamsHistoryReason(reading.optString("history_error").ifBlank { reading.optString("state") })+
        " · свечей: ${reading.optInt("closed_bar_count")}/${reading.optInt("minimum_bars",39)}"
    val bias=when(reading.optString("observed_bias")) {
        "UP" -> "восходящий контекст"; "DOWN" -> "нисходящий контекст"; else -> "смешанный контекст"
    }
    val price=when(reading.optString("price_vs_mouth")) {
        "ABOVE" -> "выше пасти"; "BELOW" -> "ниже пасти"; else -> "внутри пасти"
    }
    return prefix+bias+" · "+williamsAlligatorStateLabel(reading.optJSONObject("alligator")?.optString("state").orEmpty())+
        " · закрытие $price"
}
internal fun williamsOscillatorObservationLabel(reading:JSONObject):String {
    fun sign(key:String)=when(reading.optString(key)) { "POSITIVE" -> ">0"; "NEGATIVE" -> "<0"; else -> "=0" }
    fun direction(key:String)=when(reading.optString(key)) { "UP" -> "растёт"; "DOWN" -> "снижается"; else -> "без изменения" }
    val zone=when(reading.optString("zone")) { "GREEN" -> "зелёная"; "RED" -> "красная"; else -> "серая" }
    return "AO ${sign("ao_sign")}, ${direction("ao_direction")} · AC ${sign("ac_sign")}, ${direction("ac_direction")} · зона $zone"
}
internal fun williamsAlligatorTailLabel(reading:JSONObject):String? {
    val tail=reading.optJSONObject("alligator_known_tail") ?: return null
    val order=when(tail.optString("order")) { "UP" -> "вверх"; "DOWN" -> "вниз"; else -> "переплетены" }
    val width=when(tail.optString("width_change")) { "WIDENING" -> "расширение"; "NARROWING" -> "сужение"; else -> "ширина без изменения" }
    return "Правый край Аллигатора: $order · $width · по известным средним"
}
internal fun williamsWaveObservationLabel(frame:JSONObject):String = "Эллиотт: "+when(frame.optString("state")) {
    "NOT_ANALYSED","UNAVAILABLE" -> williamsHistoryReason(frame.optString("history_error"))
    "UNRESOLVED" -> "однозначная разметка не найдена · проверено сценариев: ${frame.optInt("examined_scenarios")}"
    "ALTERNATIVES" -> "несколько допустимых разметок"
    else -> "предварительная гипотеза"
}
