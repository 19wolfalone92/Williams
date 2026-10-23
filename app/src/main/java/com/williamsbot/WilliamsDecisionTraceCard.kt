package com.williamsbot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

@Composable
internal fun WilliamsDecisionTraceCard(raw:String,symbol:String) {
    val trace=remember(raw,symbol) { runCatching { JSONObject(raw).optJSONObject(symbol) }.getOrNull() }
    var expanded by remember(symbol) { mutableStateOf(false) }
    fun rows(a:JSONArray?)=if(a==null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    fun time(t:Long)=if(t>0L) Instant.ofEpochMilli(t).toString() else "неизвестно"
    fun value(obj:JSONObject,key:String)=if(obj.isNull(key) || !obj.has(key)) "нет данных" else obj.opt(key).toString()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
            Text("История решений · $symbol",style=MaterialTheme.typography.titleMedium)
            trace?.optJSONObject("runtime_preflight")?.let { check ->
                Text("Проверка движка: "+time(check.optLong("observed_at_ms")),style=MaterialTheme.typography.bodySmall)
                Text(russianExecutionBlocker(check.optJSONObject("outcome")?.optString("blocker").orEmpty()),style=MaterialTheme.typography.bodySmall)
            }
            val history=trace?.optJSONObject("signal_history")
            val historySignals=rows(history?.optJSONArray("signals"))
            if(history?.optString("state")=="HISTORY_UNAVAILABLE") Text("История сигналов недоступна",color=MaterialTheme.colorScheme.error)
            historySignals.takeLast(5).forEach { item ->
                Text("${russianDirection(item.optString("direction"))} · ${russianSignalFamily(item.optString("family"))} · ${williamsTriggerStateLabel(item.optString("state"))}",style=MaterialTheme.typography.bodySmall)
                Text("Формирование: ${time(item.optLong("formation_at_ms"))} · подтверждение: ${time(item.optLong("known_at_ms"))}\nТриггер ${item.opt("trigger")} · впервые записан: ${time(item.optLong("first_seen_at_ms"))}",style=MaterialTheme.typography.bodySmall)
            }
            trace?.optJSONObject("entry_passport")?.let { WilliamsEntryPassportCard(it) }
            if(trace==null || !trace.has("decision")) {
                Text("Анализ закрытых H1-свечей ещё не получен. Вывод о направлении не сделан.")
            } else {
                val decision=trace.optJSONObject("decision") ?: JSONObject()
                Text("Анализ: "+when(decision.optString("state")) {
                    "CONTEXT_REQUIRED" -> "встречные формации; требуется проверка контекста"
                    "CANDIDATE_REQUIRES_PREFLIGHT" -> "формация найдена; требуется проверка допуска"
                    else -> "новый вход не подтверждён"
                })
                Text("Закрытие H1: ${time(trace.optLong("as_of_close_ms"))} · свечей: ${trace.optInt("closed_bar_count")}",style=MaterialTheme.typography.bodySmall)
                val close=trace.optLong("as_of_close_ms")
                if(close<=0L || System.currentTimeMillis()-close>3_600_000L) Text("Снимок устарел. Текущая ситуация не подтверждена.",color=MaterialTheme.colorScheme.error)
                val reasons=decision.optJSONArray("reasons")
                if(reasons!=null && reasons.length()>0) Text("Причины: "+(0 until reasons.length()).joinToString("; ") { russianExecutionBlocker(reasons.optString(it)) },style=MaterialTheme.typography.bodySmall)
                Text("Волны: "+when(trace.optString("wave_state")) { "PROVISIONAL" -> "предварительная гипотеза";"ALTERNATIVES" -> "несколько допустимых гипотез";else -> "структура не определена" })
                rows(trace.optJSONArray("wave_hypotheses")).take(3).forEach { hypothesis ->
                    Text("${hypothesis.optString("frame")} · ${hypothesis.optString("active_label")} · ${russianDirection(hypothesis.optString("active_direction"))} · ${hypothesis.optString("scenario_id")}",style=MaterialTheme.typography.bodySmall)
                }
                trace.optJSONObject("market_reading")?.takeIf { it.optString("state")=="OBSERVED" }?.let { reading ->
                    val alligator=when(reading.optJSONObject("alligator")?.optString("state")) {
                        "SLEEPING" -> "сон: линии переплетены"
                        "AWAKENING" -> "пробуждение"
                        "EATING" -> "развитие тренда"
                        "SATIATED" -> "затухание движения"
                        else -> "не определён"
                    }
                    Text("Аллигатор H1: $alligator",style=MaterialTheme.typography.bodySmall)
                    Text("Ритм: AO ${russianDirection(reading.optString("ao_direction"))} · AC ${russianDirection(reading.optString("ac_direction"))} · TWR ${russianDirection(reading.optJSONObject("twr")?.optString("direction"))}",style=MaterialTheme.typography.bodySmall)
                    Text(when(reading.optString("wave_phase")) {
                        "START_CANDIDATE" -> "Гипотеза: начало движения"
                        "CORRECTION_CANDIDATE" -> "Гипотеза: коррекция"
                        "CONTINUATION_CANDIDATE" -> "Гипотеза: продолжение импульса"
                        "LATE_IMPULSE_CANDIDATE" -> "Гипотеза: поздняя стадия импульса; следим за истощением"
                        "ALTERNATIVES" -> "Волновая фаза: несколько разметок"
                        else -> "Волновая фаза не определена"
                    },style=MaterialTheme.typography.bodySmall)
                    if(expanded) Text("Profitunity MACD 5/34/5 использует компоненты AO/AC. Совпадение индикаторов не подтверждает номер волны.",style=MaterialTheme.typography.bodySmall)
                }
                trace.optJSONObject("market_phase")?.let { phase ->
                    Text("Состояние рынка по ТФ · закрытые свечи",style=MaterialTheme.typography.titleSmall)
                    rows(phase.optJSONObject("market_narrative")?.optJSONArray("structural_relations")).forEach {
                        Text(it.optString("explanation_ru"),style=MaterialTheme.typography.bodySmall)
                    }
                    if(expanded) rows(phase.optJSONObject("wave_interpretation")?.optJSONArray("scopes"))
                        .filter { it.optInt("relation_count")>0 }.forEach { scope ->
                            rows(scope.optJSONArray("relations")).forEach { relation ->
                                Text("${relation.optString("parent")} → ${relation.optString("child")} · "+
                                    "уровень отмены ${relation.opt("parent_invalidation")} · "+
                                    "опоры известны ${time(relation.optLong("anchors_known_at"))}",style=MaterialTheme.typography.bodySmall)
                            }
                        }
                    if(expanded) rows(phase.optJSONArray("recursive_degree_evidence"))
                        .take(6).forEach { degree ->
                            val path=degree.optJSONArray("frame_path") ?: JSONArray()
                            val tf=(0 until path.length()).joinToString(" → ") { i -> path.optString(i) }
                            Text("Подволны: ${degree.optString("pattern")} · ${tf.ifBlank { "нет полной цепочки" }}",
                                style=MaterialTheme.typography.bodySmall)
                            Text(degree.optString("explanation_ru")+
                                " · ${degree.optString("reason")} · Course PDF59–60",
                                style=MaterialTheme.typography.bodySmall,color=AppColors.textMuted)
                        }
                    rows(phase.optJSONArray("frames")).forEach { frame ->
                        val hypotheses=rows(frame.optJSONArray("hypotheses"))
                        Text(williamsMarketObservationLabel(frame),style=MaterialTheme.typography.bodySmall)
                        frame.optJSONObject("market_reading")?.takeIf { it.optString("state")=="OBSERVED" }?.let { reading ->
                            Text(williamsOscillatorObservationLabel(reading),style=MaterialTheme.typography.bodySmall)
                            williamsAlligatorTailLabel(reading)?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
                            Text("Закрытие: ${time(reading.optLong("as_of_close_ms"))} · свечей: ${reading.optInt("closed_bar_count")}",style=MaterialTheme.typography.bodySmall)
                            if(expanded) reading.optJSONObject("range_location")?.let { range ->
                                val location=range.optDouble("position",Double.NaN)
                                if(location.isFinite()) Text("В диапазоне ${range.optInt("bars")} свечей: ${"%.1f".format(java.util.Locale.ROOT,location*100)}% от минимума к максимуму; это не номер волны.",style=MaterialTheme.typography.bodySmall)
                            }
                        }
                        val labels=hypotheses.map { it.optString("active_label")+" "+russianDirection(it.optString("active_direction")) }.distinct()
                        Text(williamsWaveObservationLabel(frame)+if(labels.isEmpty()) "" else " · "+labels.joinToString(" / "),style=MaterialTheme.typography.bodySmall)
                        if(expanded) hypotheses.take(3).forEach { h ->
                            val signs=h.optJSONArray("signs") ?: JSONArray()
                            val names=(0 until signs.length()).map { i -> when(signs.optString(i)) {
                                "AO_DIVERGENCE" -> "дивергенция AO"
                                "MOMENTUM_SHIFT" -> "ослабление импульса"
                                "TERMINAL_FRACTAL" -> "конечный фрактал"
                                "TARGET_ZONE" -> "целевая область"
                                "SQUAT_VOLUME_PROXY" -> "Squat по биржевому объёму"
                                else -> signs.optString(i)
                            } }
                            Text("${h.optString("scenario_id")} · признаки: "+names.joinToString().ifBlank { "не наблюдаются" }+
                                "\nAO W4: ${h.optString("w4_zero")} · масштаб ${h.optString("scale_frame")} / ${h.optInt("scale_bars")} свечей",style=MaterialTheme.typography.bodySmall)
                            h.optJSONObject("third_momentum")?.let { momentum ->
                                Text(williamsThirdMomentumLabel(momentum),style=MaterialTheme.typography.bodySmall)
                                val known=momentum.optLong("peak_turn_known_at_ms",0L)
                                if(known>0L) Text("Поворот пика известен: ${time(known)} · TC1 PDF60 / Course PDF42",style=MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Text("Контекст описывает наблюдаемое состояние. Номер волны — гипотеза; это не вероятность следующего движения.",style=MaterialTheme.typography.bodySmall)
                    Text(when(phase.optString("warning_scope")) {
                        "M15_ONLY_WARNING" -> "Признаки истощения на M15. Завершение H1 этим не подтверждено."
                        "H1_WARNING" -> "Признаки истощения на H1; требуется проверка основания выхода."
                        "SENIOR_WARNING" -> "Признаки истощения на старшем масштабе; требуется проверка основания выхода."
                        else -> "Подтверждённого основания выхода из волновой оценки нет."
                    },style=MaterialTheme.typography.bodySmall)
                }
                rows(trace.optJSONObject("parent_lifecycle")?.optJSONArray("parents")).filter { it.optString("state")!="VALID_CONTEXT" }.takeLast(3).forEach { parent ->
                    Text(if(parent.optString("state")=="REVOKED") "Сценарий ${parent.optString("direction")} отменён: сработал более поздний противоположный H1-сигнал."
                        else "Актуальность H1-сценария не подтверждена: порядок событий или история требуют проверки.",style=MaterialTheme.typography.bodySmall)
                }
                val indicators=trace.optJSONObject("indicators") ?: JSONObject()
                Text("AO: ${value(indicators,"ao")} · AC: ${value(indicators,"ac")}",style=MaterialTheme.typography.bodySmall)
                rows(trace.optJSONArray("edition_observations")).filter { it.optString("state")=="ANALYSIS_FAILED" }.forEach {
                    Text("${it.optString("profile")}: анализ не выполнен (${it.optString("error_type")}); вывод о сигналах неизвестен.",
                        color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                }
                rows(trace.optJSONArray("books")).forEach { book ->
                    val label=when(book.optString("profile")) { williamsTc2Profile -> "TC2";williamsNtdProfile -> "NTD";else -> "Course" }
                    val evidence=rows(book.optJSONArray("evidence"))
                    val role=if(book.optString("execution_role")=="LIVE") "торговля" else "наблюдение"
                    Text(label+" · "+role+": "+if(book.optString("state")=="ANALYSIS_FAILED") "анализ недоступен; сигнал неизвестен" else if(evidence.isEmpty()) "нет действующей формации" else evidence.take(3).joinToString("; ") {
                        "${russianDirection(it.optString("direction"))} · ${russianSignalFamily(it.optString("family",it.optString("type")))} @ ${it.opt("trigger")} · ${time(it.optLong("signal_bar_time_ms"))}"
                    },style=MaterialTheme.typography.bodySmall)
                }
                val execution=trace.optJSONObject("execution")
                if(execution!=null) {
                    val outcome=execution.optJSONObject("outcome") ?: JSONObject()
                    Text("Последняя проверка допуска: "+time(execution.optLong("observed_at_ms")),style=MaterialTheme.typography.bodySmall)
                    Text(russianExecutionBlocker(outcome.optString("blocker").ifBlank { outcome.optString("state").ifBlank { "Результат сохранён в диагностике" } }),style=MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick={ expanded=!expanded }) { Text(if(expanded) "Свернуть доказательства" else "Свечи, дивергенции и цепочка ордеров") }
                if(expanded) {
                    Text("История решений · последние 20 событий",style=MaterialTheme.typography.bodySmall)
                    rows(history?.optJSONArray("events")).takeLast(20).reversed().forEach { e ->
                        Text("${time(e.optLong("observed_at_ms"))} · ${e.optString("reason_category")} · ${williamsTriggerStateLabel(e.optString("state"))}\n${russianDirection(e.optString("direction"))} · ${russianSignalFamily(e.optString("family"))} @ ${e.opt("trigger")} · ${russianExecutionBlocker(e.optString("reason"))}"+
                            if(e.optString("attribution")=="PAIR_GATE") "\nОграничение пары; индивидуальный допуск не выполнялся." else "",style=MaterialTheme.typography.bodySmall)
                    }
                    Text("Аллигатор: челюсти=${value(indicators,"jaw")} · зубы=${value(indicators,"teeth")} · губы=${value(indicators,"lips")}",style=MaterialTheme.typography.bodySmall)
                    val context=trace.optJSONObject("admission_context")
                    if(context!=null) {
                        Text("Контекст последней проверки: "+time(context.optLong("observed_at_ms")),style=MaterialTheme.typography.bodySmall)
                        for(frame in listOf("d1","h4")) context.optJSONObject(frame)?.let { row ->
                            Text("${frame.uppercase()} · ${time(row.optLong("bar_time_ms"))} · C=${row.opt("close")} · AO=${row.opt("ao")} · Jaw/Teeth/Lips=${row.opt("jaw")}/${row.opt("teeth")}/${row.opt("lips")}",style=MaterialTheme.typography.bodySmall)
                        }
                    }
                    val divergences=rows(trace.optJSONArray("divergences"))
                    if(divergences.isEmpty()) Text("Для проверки дивергенции недостаточно подтверждённых экстремумов.",style=MaterialTheme.typography.bodySmall)
                    divergences.forEach { d ->
                        val a=d.getJSONObject("first");val b=d.getJSONObject("second")
                        Text("${d.optString("direction")}: ${if(d.optBoolean("observed")) "наблюдается" else "не подтверждена"}\n${time(a.optLong("bar_time_ms"))}: цена=${a.opt("price")}, AO=${a.opt("ao")}\n${time(b.optLong("bar_time_ms"))}: цена=${b.opt("price")}, AO=${b.opt("ao")}\nВторой экстремум подтверждён: ${time(b.optLong("confirmed_at_ms"))}",style=MaterialTheme.typography.bodySmall)
                    }
                    rows(trace.optJSONArray("wave_hypotheses")).take(3).forEach { h ->
                        Text("Опоры ${h.optString("scenario_id")}: "+h.optJSONArray("anchors").toString(),style=MaterialTheme.typography.bodySmall)
                    }
                    val chain=rows(trace.optJSONArray("order_chain"))
                    Text(if(trace.optString("order_chain_state")=="UNAVAILABLE_RECONCILE_REQUIRED") "Журнал недоступен; нужна сверка исполнения и защиты." else if(chain.isEmpty()) "Ордеров этой пары в журнале нет." else "Цепочка из журнала исполнения:",style=MaterialTheme.typography.bodySmall)
                    chain.forEach { item -> Text("${item.optString("client_id")} · ${russianStatus(item.optString("order_status"))} · ${russianStatus(item.optString("campaign_status"))}\nЗащита: ${russianStatus(item.optString("protection_status"))} @ ${item.optString("protective_stop")}",style=MaterialTheme.typography.bodySmall) }
                    val candles=trace.optJSONArray("candles") ?: JSONArray()
                    Text("Последние свечи H1 · время, O/H/L/C/V",style=MaterialTheme.typography.bodySmall)
                    for(i in maxOf(0,candles.length()-5) until candles.length()) {
                        val c=candles.getJSONArray(i)
                        Text(time(c.getLong(0))+" · "+(1..5).joinToString(" / ") { c.opt(it).toString() },style=MaterialTheme.typography.bodySmall)
                    }
                    Text("Полные доказательства доступны в экспорте диагностики. Волновая разметка и дивергенция сами по себе не разрешают ордер.",style=MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
