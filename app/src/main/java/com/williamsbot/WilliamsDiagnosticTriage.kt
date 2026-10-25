package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only, deterministic triage of ONE complete diagnostic snapshot.
 * A PASS from a component test never means a verified Binance execution,
 * correct wave degree, complete book coverage or profitable trading.
 * Never submits orders or stores API credentials.
 */
internal fun williamsDiagnosticTriage(
    tests: JSONArray,
    entriesAllowed: Boolean,
    livePositionCount: Int = 0
): JSONObject {
    val data = CockpitDiagnostics.rows(tests)
    val rank = mapOf("FAIL" to 0, "WARNING" to 1, "WARN" to 1,
        "NOT_VERIFIED" to 2, "PASS" to 3)
    val counts = linkedMapOf("FAIL" to 0, "WARNING" to 0,
        "NOT_VERIFIED" to 0, "PASS" to 0)
    val issues = mutableListOf<JSONObject>()
    val domains = JSONObject()
    for (item in data) {
        val severity = when(item.optString("severity").uppercase()) {
            "FAIL" -> "FAIL"
            "WARNING", "WARN" -> "WARNING"
            "PASS" -> "PASS"
            else -> "NOT_VERIFIED"
        }
        counts[severity] = counts.getValue(severity)+1
        val domain = item.optString("domain", "unknown").ifBlank { "unknown" }
        val byDomain = domains.optJSONObject(domain) ?: JSONObject()
            .put("FAIL", 0).put("WARNING", 0).put("NOT_VERIFIED", 0).put("PASS", 0)
            .also { domains.put(domain,it) }
        byDomain.put(severity,byDomain.optInt(severity)+1)
        if (severity!="PASS") {
            val id = item.optString("id").ifBlank { item.optString("name") }
            val suggested = when {
                id.contains("stop",true) || id.startsWith("EXE-") ->
                    "Сверить открытые позиции, клиентские ID заявок и защитные STOP с Binance Demo. Не отправлять повторные заявки вслепую."
                domain=="websocket" || domain=="binance" ->
                    "Проверить интернет, время Binance, REST/WSS и повторить только чтение/сверку."
                domain=="database" || domain=="runtime" ->
                    "Проверить хранилище, автозапуск и состояние Runtime; сохранить ZIP до перезапуска."
                domain=="strategy" || id.startsWith("STR-") || id.startsWith("DATA-") ->
                    "Проверить реальные закрытые свечи, D1/H4/H1/M15, книжное правило и независимую разметку; сигналы не равны исполнению."
                else -> "Открыть подробности проверки, устранить причину и повторить диагностику."
            }
            issues += JSONObject().put("id",id).put("domain",domain)
                .put("severity",severity).put("name",item.optString("name"))
                .put("detail",item.optString("detail").take(1200))
                .put("next_action",suggested)
        }
    }
    // Prioritize immediate capital/exchange protection BEFORE strategy
    // research whenever severity ties. A wave evidence gap must not push
    // a failing STOP or reconciliation check out of the first 12 alerts.
    fun impact(row:JSONObject):Int {
        val d=row.optString("domain").lowercase()
        val id=row.optString("id").uppercase()
        return when {
            d=="risk" || d=="execution" || id.startsWith("EXE-") ||
                id.contains("STOP") || id.contains("PROTECTION") -> 0
            d=="binance" -> 1
            d=="database" || d=="runtime" -> 2
            d=="websocket" -> 3
            d=="strategy" || id.startsWith("WAVE") || id.startsWith("STR-") -> 4
            else -> 5
        }
    }
    // A live position whose STOP is NOT_VERIFIED is more urgent than
    // ordinary transport/UI warnings. Do not misclassify unknown as FAIL;
    // elevate only its diagnostic visibility until signed exchange proof
    // is refreshed. Never automatically place/cancel/replace orders here.
    val criticalLiveProtection = setOf("EXE-03","EXE-04","EXE-08","RISK-01","RISK-04")
    fun urgentLiveProof(row:JSONObject):Boolean =
        livePositionCount>0 && row.optString("id") in criticalLiveProtection &&
            row.optString("severity")=="NOT_VERIFIED"
    val sorted = issues.sortedWith(compareBy<JSONObject> {
        if(urgentLiveProof(it)) -1 else rank[it.optString("severity")] ?: 3
    }.thenBy { impact(it) }.thenBy { it.optString("id") })
    val overall=when {
        data.isEmpty() -> "NOT_VERIFIED"
        counts.getValue("FAIL")>0 -> "FAIL"
        counts.getValue("WARNING")>0 -> "WARNING"
        counts.getValue("NOT_VERIFIED")>0 -> "NOT_VERIFIED"
        else -> "PASS"
    }
    return JSONObject().put("schema","WILLIAMS_DIAGNOSTIC_TRIAGE_V1")
        .put("overall",overall).put("component_test_count",data.size)
        .put("issue_count",issues.size).put("passed_count",counts.getValue("PASS"))
        .put("failed_count",counts.getValue("FAIL"))
        .put("warning_count",counts.getValue("WARNING"))
        .put("unverified_count",counts.getValue("NOT_VERIFIED"))
        .put("domains",domains)
        .put("priority_issues",JSONArray(sorted.take(12)))
        .put("priority_issues_truncated",sorted.size>12)
        .put("live_position_count_at_snapshot",livePositionCount.coerceAtLeast(0))
        .put("exchange_stop_proof_refresh_urgent",sorted.any(::urgentLiveProof))
        .put("new_entries_allowed_at_snapshot",entriesAllowed)
        .put("accuracy_certified",false)
        .put("book_100_percent_verified",false)
        .put("demo_end_to_end_verified",false)
        .put("status_note",when {
            data.isEmpty() -> "Нет данных проверки: это не успешная диагностика."
            overall=="PASS" -> "Компонентные проверки пройдены; книги, рыночная точность и Binance Demo НЕ сертифицированы."
            else -> "Показаны первоочередные дефекты и неопределённости. Диагностика не снимает торговые блокировки."
        })
}
