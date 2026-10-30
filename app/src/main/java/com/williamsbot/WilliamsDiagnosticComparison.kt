package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

/**
 * Diff two READ-ONLY diagnostic snapshots. "No longer observed" is not
 * proof of a fix: a missing/shorter scanner snapshot can hide a defect.
 * Unknown prior data MUST NOT manufacture recovery or improvements.
 */
internal fun williamsDiagnosticComparison(
    previous: JSONArray?,
    current: JSONArray?,
    previousAtMs: Long,
    currentAtMs: Long
): JSONObject {
    val comparable = previous != null && current != null &&
        previousAtMs > 0L && currentAtMs > previousAtMs &&
        currentAtMs - previousAtMs < 30L * 24L * 60L * 60L * 1000L
    val result=JSONObject().put("schema","WILLIAMS_DIAGNOSTIC_COMPARISON_V1")
        .put("status",if(comparable) "COMPARABLE" else "NOT_COMPARABLE")
        .put("previous_at_ms",if(previousAtMs>0) previousAtMs else JSONObject.NULL)
        .put("current_at_ms",if(currentAtMs>0) currentAtMs else JSONObject.NULL)
        .put("new_issues",JSONArray()).put("worsened",JSONArray())
        .put("less_severe",JSONArray()).put("no_longer_reported",JSONArray())
        .put("unchanged_issue_count",0)
        .put("note","Исчезновение ошибки в следующем снимке НЕ доказывает устранение. " +
            "Только реальные доказательства из Binance и независимые тесты подтверждают восстановление.")
    if(!comparable) return result
    fun normalized(data:JSONArray): Map<String, JSONObject> {
        val index=linkedMapOf<String,JSONObject>()
        for (item in CockpitDiagnostics.rows(data)) {
            val domain=item.optString("domain").ifBlank { "unknown" }
            val id=item.optString("id").ifBlank { item.optString("name") }
            if(id.isBlank()) continue
            val severity=when(item.optString("severity").uppercase()) {
                "FAIL" -> "FAIL"
                "WARN","WARNING" -> "WARNING"
                "NOT_VERIFIED" -> "NOT_VERIFIED"
                else -> continue
            }
            val entry=JSONObject().put("id",id).put("domain",domain)
                .put("name",item.optString("name")).put("severity",severity)
            val key="$domain/$id"
            // Duplicate ids can appear in multiple diagnostic domains.
            // Within a domain, preserve the WORST duplicate observation.
            val old=index[key]
            val ranks=mapOf("FAIL" to 0,"WARNING" to 1,"NOT_VERIFIED" to 2)
            if(old==null ||
                (ranks[severity] ?: 3) < (ranks[old.optString("severity")] ?: 3))
                index[key]=entry
        }
        return index
    }
    val prior=normalized(previous!!)
    val now=normalized(current!!)
    val rank=mapOf("FAIL" to 0,"WARNING" to 1,"NOT_VERIFIED" to 2)
    var unchanged=0
    for ((key, row) in now.toSortedMap()) {
        val old=prior[key]
        when {
            old == null -> result.getJSONArray("new_issues").put(row)
            rank.getValue(row.getString("severity")) <
                rank.getValue(old.getString("severity")) ->
                result.getJSONArray("worsened").put(JSONObject(row.toString())
                    .put("previous_severity",old.getString("severity")))
            rank.getValue(row.getString("severity")) >
                rank.getValue(old.getString("severity")) ->
                result.getJSONArray("less_severe").put(JSONObject(row.toString())
                    .put("previous_severity",old.getString("severity")))
            else -> unchanged++
        }
    }
    for ((key,row) in prior.toSortedMap()) {
        if(key !in now) result.getJSONArray("no_longer_reported").put(row)
    }
    result.put("unchanged_issue_count",unchanged)
    result.put("new_count",result.getJSONArray("new_issues").length())
    result.put("worsened_count",result.getJSONArray("worsened").length())
    result.put("less_severe_count",result.getJSONArray("less_severe").length())
    result.put("no_longer_reported_count",
        result.getJSONArray("no_longer_reported").length())
    return result
}
