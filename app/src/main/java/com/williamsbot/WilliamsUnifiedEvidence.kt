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
    // This deployment executes independently owned TC2/NTD lifecycles and observes Course.
    // The old evidence page required WILLIAMS_UNIFIED, thereby declaring
    // every correct 4.38 TC2-live scan WRONG_PROFILE.
    val coordinator = status.optJSONObject("futures_runtime")
        ?: status.optJSONObject("futures_campaign_coordinator") ?: JSONObject()
    val ownershipSnapshotPresent=coordinator.has("pending_intent_details")
    val activeIntents = CockpitDiagnostics.rows(coordinator.optJSONArray("pending_intent_details"))
    val scanning = scanner.optBoolean("scanning", true)
    val at = scanner.optLong("last_scan_at", 0L)
    val recent = CockpitDiagnostics.fresh(at, nowMs, 120_000L)
    // READY_BLOCKED means all ten H1 analyses finished, but execution is
    // intentionally blocked (e.g., a confirmed Futures risk breach).
    // It is a complete observation, NOT a ready-to-trade authorization.
    val complete = !scanning &&
        scanner.optString("scanner_state") in setOf("READY","READY_BLOCKED") && recent &&
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
    var tc2Conflicts = 0
    var activeCampaigns = 0
    var observerProofComplete = true
    val visited = mutableSetOf<String>()
    val bySymbol = candidates.groupBy { it.optString("symbol") }
    for (symbol in APPROVED_FUTURES_SYMBOLS) {
        val rows = bySymbol[symbol].orEmpty()
        if (rows.size > 1) errors.put("DUPLICATE_CANDIDATE:$symbol")
        val candidate = rows.firstOrNull()
        if (candidate != null) visited += symbol
        val rawSignals = CockpitDiagnostics.rows(candidate?.optJSONArray("campaign_signals"))
        val latestObserverRows = CockpitDiagnostics.rows(candidate?.optJSONArray("edition_observations"))
        val expectedH1Close=candidate?.optLong("closed_h1_as_of_ms",0L) ?: 0L
        // Distinguish an observed empty result from a failed/missing source
        // extraction. No signal is not evidence that all books were evaluated.
        val observerProof = JSONObject()
        for(source in williamsSourceProfiles) {
            val rows=latestObserverRows.filter { it.optString("profile")==source }
            val report=rows.singleOrNull()
            val state=when {
                candidate==null || report==null || rows.size!=1 -> "UNAVAILABLE"
                report.optString("execution_role")!=(if(williamsVerifiedNewRiskSource(source)) "LIVE" else "OBSERVE") ->
                    "ROLE_MISMATCH"
                expectedH1Close<=0L || expectedH1Close>nowMs ||
                    report.optLong("as_of_close_ms")!=expectedH1Close -> "H1_SNAPSHOT_MISMATCH"
                report.optString("state")=="ANALYSIS_FAILED" -> "ANALYSIS_FAILED"
                report.optString("state") in setOf("NO_VALID_FORMATION","FORMATION_OBSERVED") -> report.optString("state")
                else -> "UNKNOWN_SOURCE_STATE"
            }
            observerProof.put(source,JSONObject().put("state",state)
                .put("execution_role",if(williamsVerifiedNewRiskSource(source)) "LIVE" else "OBSERVE")
                .put("as_of_close_ms",report?.optLong("as_of_close_ms") ?: JSONObject.NULL)
                .put("reported_signal_count",report?.optInt("signal_count") ?: JSONObject.NULL)
                .put("error_type",report?.opt("error_type") ?: JSONObject.NULL))
            if(state !in setOf("NO_VALID_FORMATION","FORMATION_OBSERVED"))
                observerProofComplete=false
            if(state=="ANALYSIS_FAILED" || state=="ROLE_MISMATCH")
                errors.put("SOURCE_EXTRACTION_"+state+":"+symbol+":"+source)
        }
        val evidence = JSONArray()
        val sourceCounts = JSONObject()
        for (source in williamsSourceProfiles) sourceCounts.put(source, 0)
        val freshSides = mutableSetOf<String>()
        val tc2FreshSides = mutableSetOf<String>()
        val unique = mutableSetOf<String>()
        val pairOwners=activeIntents.filter { it.optString("symbol")==symbol &&
            it.optString("role")=="ENTRY" &&
            it.optString("campaign_status") !in setOf("CLOSED_OR_STOPPED","CLOSED") }
        if(pairOwners.size>1) errors.put("MULTIPLE_ACTIVE_CAMPAIGN_OWNERS:$symbol")
        val owner=pairOwners.singleOrNull()
        val ownerSource=owner?.optString("strategy_profile","UNKNOWN") ?: if(ownershipSnapshotPresent) "NONE" else "UNKNOWN"
        if(owner!=null) {
            activeCampaigns++
            if(ownerSource !in williamsSourceProfiles)
                errors.put("UNKNOWN_PINNED_CAMPAIGN_SOURCE:$symbol")
        }
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
            // TC2 WM3 H1 fractals persist until a newer valid replacement or
            // actual breakout; the old five-hour label falsely hid them.
            val fresh = valid && williamsH1SignalTimeEligible(stamp,family,source,nowMs)
            if (fresh && role == "ENTRY" && side != null) {
                freshSides += side
                if(williamsVerifiedNewRiskSource(source)) tc2FreshSides += side
            }
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
                .put("signal_time_eligible", fresh)
                .put("validity_policy",if(williamsStructuralFractal(family,source))
                    "UNBROKEN_UNSUPERSEDED_FRACTAL" else "PROJECT_AGE_WINDOW")
                .put("execution_role",if(williamsVerifiedNewRiskSource(source)) "LIVE" else "OBSERVE")
                .put("book_origin_known", source in williamsSourceProfiles)
                .put("structure_valid",valid)
                .put("reason", signal.optString("reason"))
                .put("exchange_order_authorized", false)
                .put("admission_proof", "NOT_EVALUATED_BY_CACHED_SCANNER"))
        }
        val conflict = freshSides.size > 1
        val liveConflict = tc2FreshSides.size > 1
        if (conflict) conflicts++
        if (liveConflict) tc2Conflicts++
        perPair.put(JSONObject().put("symbol", symbol)
            .put("campaign_occupied",pairOwners.isNotEmpty())
            .put("active_owner_count",pairOwners.size)
            .put("owner_strategy_profile",ownerSource)
            .put("owner_direction",owner?.optString("direction") ?: JSONObject.NULL)
            .put("owner_signal_id",owner?.optString("signal_id") ?: JSONObject.NULL)
            .put("owner_campaign_state",owner?.optString("campaign_status") ?: JSONObject.NULL)
            .put("owner_strategy_binding_verified",owner?.optBoolean("strategy_ownership_verified") ?: false)
            .put("owner_protection_status",owner?.optString("protection_status") ?: JSONObject.NULL)
            .put("owner_last_reconciled_ms",owner?.optLong("last_reconciled_ms") ?: JSONObject.NULL)
            .put("owner_snapshot_state",when {
                !ownershipSnapshotPresent -> "UNAVAILABLE"
                pairOwners.size>1 -> "MULTIPLE_NONTERMINAL_ENTRIES"
                owner==null -> "NO_NONTERMINAL_ENTRY_INTENT"
                else -> "PINNED_SOURCE_OBSERVED_NOT_LIVE_EXCHANGE_PROOF"
            })
            .put("other_strategies_can_trade_this_pair",false)
            .put("live_tc2_opposing_entries_before_context",liveConflict)
            .put("observed", candidate != null)
            .put("source_counts", sourceCounts)
            .put("edition_observation_proof",observerProof)
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
        selected != williamsUnifiedProfile && !williamsVerifiedNewRiskSource(selected) -> "WRONG_PROFILE"
        !recent -> "STALE_OR_MISSING_SCAN"
        scanning -> "SCAN_IN_PROGRESS"
        !complete || visited.size != 10 -> "INCOMPLETE_SCAN"
        errors.length() > 0 -> "INVALID_SNAPSHOT"
        !observerProofComplete -> "INCOMPLETE_OBSERVER_PROOF"
        else -> "COMPLETE_OBSERVATION_ONLY"
    }
    return JSONObject()
        .put("schema_version", 1)
        .put("mode", selected)
        .put("execution_policy",williamsExecutionPolicySnapshot())
        .put("active_campaigns_in_snapshot",activeCampaigns)
        .put("coordinator_snapshot_present",ownershipSnapshotPresent)
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
        .put("observer_proof_complete",observerProofComplete)
        .put("by_source", sourceSummary)
        .put("by_symbol", perPair)
        .put("source_contract_errors", errors)
        .put("pre_context_opposing_symbol_count", conflicts)
        .put("live_tc2_opposing_symbol_count",tc2Conflicts)
        .put("is_execution_decision", false)
        .put("completeness_warning", "Cached H1 signals only. TC2 and NTD have independent source-owned H1 execution, risk and Binance checks; Course OBSERVE only. Source snapshots are not fills, owner evidence, or PnL. No M15 confirmation is required.")
        .put("last_runtime_error_unattributed", status.optString("last_error").take(300))
}
