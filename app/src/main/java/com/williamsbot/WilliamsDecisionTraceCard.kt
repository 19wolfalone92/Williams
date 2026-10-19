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
            Text("DecisionTrace · $symbol",style=MaterialTheme.typography.titleMedium)
            trace?.optJSONObject("runtime_preflight")?.let { check ->
                Text("Проверка Runtime: "+time(check.optLong("observed_at_ms")),style=MaterialTheme.typography.bodySmall)
                Text(check.optJSONObject("outcome")?.optString("blocker").orEmpty(),style=MaterialTheme.typography.bodySmall)
            }
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
                if(reasons!=null && reasons.length()>0) Text("Причины: "+(0 until reasons.length()).joinToString("; ") { reasons.optString(it) },style=MaterialTheme.typography.bodySmall)
                Text("Волны: "+when(trace.optString("wave_state")) { "PROVISIONAL" -> "предварительная гипотеза";"ALTERNATIVES" -> "несколько допустимых гипотез";else -> "структура не определена" })
                rows(trace.optJSONArray("wave_hypotheses")).take(3).forEach { hypothesis ->
                    Text("${hypothesis.optString("frame")} · ${hypothesis.optString("active_label")} · ${hypothesis.optString("active_direction")} · ${hypothesis.optString("scenario_id")}",style=MaterialTheme.typography.bodySmall)
                }
                val indicators=trace.optJSONObject("indicators") ?: JSONObject()
                Text("AO: ${value(indicators,"ao")} · AC: ${value(indicators,"ac")}",style=MaterialTheme.typography.bodySmall)
                rows(trace.optJSONArray("books")).forEach { book ->
                    val label=when(book.optString("profile")) { williamsTc2Profile -> "TC2";williamsNtdProfile -> "NTD";else -> "Course" }
                    val evidence=rows(book.optJSONArray("evidence"))
                    Text(label+": "+if(evidence.isEmpty()) "нет действующей формации" else evidence.take(3).joinToString("; ") {
                        "${it.optString("direction")} ${it.optString("family",it.optString("type"))} @ ${it.opt("trigger")} · ${time(it.optLong("signal_bar_time_ms"))}"
                    },style=MaterialTheme.typography.bodySmall)
                }
                val execution=trace.optJSONObject("execution")
                if(execution!=null) {
                    val outcome=execution.optJSONObject("outcome") ?: JSONObject()
                    Text("Последняя проверка допуска: "+time(execution.optLong("observed_at_ms")),style=MaterialTheme.typography.bodySmall)
                    Text(outcome.optString("blocker").ifBlank { outcome.optString("state").ifBlank { "Результат сохранён в диагностике" } },style=MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick={ expanded=!expanded }) { Text(if(expanded) "Свернуть доказательства" else "Свечи, дивергенции и цепочка ордеров") }
                if(expanded) {
                    Text("Alligator: Jaw=${value(indicators,"jaw")} · Teeth=${value(indicators,"teeth")} · Lips=${value(indicators,"lips")}",style=MaterialTheme.typography.bodySmall)
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
                    chain.forEach { item -> Text("${item.optString("client_id")} · ${item.optString("order_status")} · ${item.optString("campaign_status")}\nЗащита: ${item.optString("protection_status")} @ ${item.optString("protective_stop")}",style=MaterialTheme.typography.bodySmall) }
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
