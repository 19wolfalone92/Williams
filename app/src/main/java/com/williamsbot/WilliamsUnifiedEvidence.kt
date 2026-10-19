package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only account-independent proof of what the latest scanner snapshot
 * actually observed. This is NOT an execution decision or a book-fidelity
 * score: the D1/H4 risk, live mark, reconciliation, and order gates are
 * evaluated later, and cannot be reconstructed from a cached scanner result.
 */
internal fun buildWilliamsUnifiedEvidence(
    scanner: JSONObject, status: JSONObject, nowMs: Long
): JSONObject {
    val selected = status.optString("strategy_profile")
    val scanning = scanner.optBoolean("scanning", true)
    val at = scanner.optLong("last_scan_at", 0L)
    val recent = CockpitDiagnostics.fresh(at, nowMs, 120_000L)
    val complete = !scanning && scanner.optString("scanner_state") == "READY" && recent &&
        scanner.optInt("symbols_scanned", -1) == 10
    val candidates = CockpitDiagnostics.rows(scanner.optJSONArray("candidates"))
    val sourceSummary = JSONObject()
    for (source in williamsSourceProfiles) {
        sourceSummary.put(source, JSONObject().put("total", 0).put("entry", 0)
            .put("add_on", 0).put("long", 0).put("short", 0))
    }
    val perPair = JSONArray()
    val errors = JSONArray()
    var total = 0
    var conflicts = 0
    val visited = mutableSetOf<String>()
    val bySymbol = candidates.groupBy { it.optString("symbol") }
    for (symbol in APPROVED_FUTURES_SYMBOLS) {
        val rows = bySymbol[symbol].orEmpty()
        if (rows.size > 1) errors.put("DUPLICATE_CANDIDATE:$symbol")
        val candidate = rows.firstOrNull()
        if (candidate != null) visited += symbol
        val rawSignals = CockpitDiagnostics.rows(candidate?.optJSONArray("campaign_signals"))
        val evidence = JSONArray()
        val sourceCounts = JSONObject()
        for (source in williamsSourceProfiles) sourceCounts.put(source, 0)
        val freshSides = mutableSetOf<String>()
        val unique = mutableSetOf<String>()
        for (signal in rawSignals) {
            total++
            val source = signal.optString("strategy_profile")
            val family = signal.optString("type")
            val role = signal.optString("role")
            val side = campaignSignalDirection(family)
            val id = signal.optString("signal_id")
            val stamp = signal.optLong("signal_bar_time_ms")
            val trigger = signal.optDouble("trigger_price", Double.NaN)
            val stop = signal.optDouble("protective_price", Double.NaN)
            val valid = source in williamsSourceProfiles && id.isNotBlank() &&
                unique.add(id) && role in setOf("ENTRY", "ADD_ON") &&
                side in setOf("LONG", "SHORT") && stamp > 0 && stamp <= nowMs - 3_600_000L &&
                trigger.isFinite() && stop.isFinite() && trigger > 0 && stop > 0 &&
                (if (side == "LONG") stop < trigger else stop > trigger)
            if (!valid) errors.put("INVALID_SOURCE_OR_SIGNAL:$symbol:${id.take(90)}")
            val fresh = valid && nowMs - stamp <= 5L * 3_600_000L
            if (fresh && role == "ENTRY" && side != null) freshSides += side
            if (source in williamsSourceProfiles) {
                sourceCounts.put(source, sourceCounts.optInt(source) + 1)
                val stats = sourceSummary.getJSONObject(source)
                stats.put("total", stats.optInt("total") + 1)
                if (role == "ENTRY") stats.put("entry", stats.optInt("entry") + 1)
                if (role == "ADD_ON") stats.put("add_on", stats.optInt("add_on") + 1)
                if (side == "LONG") stats.put("long", stats.optInt("long") + 1)
                if (side == "SHORT") stats.put("short", stats.optInt("short") + 1)
            }
            evidence.put(JSONObject().put("source", source).put("signal_id", id)
                .put("family", family).put("role", role)
                .put("direction", side ?: "UNKNOWN").put("signal_bar_time_ms", stamp)
                .put("trigger_price", trigger.takeIf { it.isFinite() } ?: JSONObject.NULL)
                .put("protective_price", stop.takeIf { it.isFinite() } ?: JSONObject.NULL)
                .put("five_hour_freshness", fresh).put("structure_valid", valid)
                .put("book_origin_known", source in williamsSourceProfiles)
                .put("reason", signal.optString("reason"))
                .put("exchange_order_authorized", false)
                .put("admission_proof", "NOT_EVALUATED_BY_CACHED_SCANNER"))
        }
        val conflict = freshSides.size > 1
        if (conflict) conflicts++
        perPair.put(JSONObject().put("symbol", symbol)
            .put("observed", candidate != null)
            .put("source_counts", sourceCounts)
            .put("observed_signal_count", rawSignals.size)
            .put("fresh_opposing_entries_before_context", conflict)
            .put("signals", evidence)
            .put("no_signal_explanation", when {
                candidate == null -> "NO_SCANNER_CANDIDATE"
                rawSignals.isEmpty() -> "NO_SIGNALS_IN_SNAPSHOT; detector rejection details not persisted"
                else -> JSONObject.NULL
            }))
    }
    for (symbol in bySymbol.keys) if (symbol !in APPROVED_FUTURES_SYMBOLS)
        errors.put("UNKNOWN_SYMBOL:${symbol.take(25)}")
    val snapshot = when {
        selected != williamsUnifiedProfile -> "WRONG_PROFILE"
        !recent -> "STALE_OR_MISSING_SCAN"
        scanning -> "SCAN_IN_PROGRESS"
        !complete || visited.size != 10 -> "INCOMPLETE_SCAN"
        errors.length() > 0 -> "INVALID_SNAPSHOT"
        else -> "COMPLETE_OBSERVATION_ONLY"
    }
    return JSONObject()
        .put("schema_version", 1)
        .put("mode", selected)
        .put("status", snapshot)
        .put("provenance", "CACHED_CLOSED_H1_SCANNER")
        .put("checked_at_ms", nowMs)
        .put("last_scan_at_ms", at)
        .put("scanner_run_id", scanner.optString("run_id"))
        .put("scanner_state", scanner.optString("scanner_state"))
        .put("scanner_in_progress", scanning)
        .put("observed_symbols", visited.size)
        .put("expected_symbols", 10)
        .put("observed_signal_count", total)
        .put("by_source", sourceSummary)
        .put("by_symbol", perPair)
        .put("source_contract_errors", errors)
        .put("pre_context_opposing_symbol_count", conflicts)
        .put("is_execution_decision", false)
        .put("completeness_warning", "Cached source signals only. No detector rejection causes, D1/H4 admission, M15 fill, position, or live Binance approval inferred.")
        .put("last_runtime_error_unattributed", status.optString("last_error").take(300))
}
