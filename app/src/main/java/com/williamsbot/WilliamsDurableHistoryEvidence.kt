package com.williamsbot

import org.json.JSONObject

/**
 * A historical-worker counter is updated only after its full 120-pair pass.
 * The durable SQLite read-only history summary can already contain completed
 * symbol/frame pairs while this counter still reads ZERO. Never confuse them.
 *
 * A completed archive or replay proves data processing, not independently
 * correct Elliott/Williams wave labels or actual financial performance.
 */
internal fun williamsDurableHistoryEvidence(
    status:JSONObject,
    symbols:List<String>,
    frames:List<String>
):JSONObject {
    val required=symbols.distinct().flatMap { symbol ->
        frames.distinct().map { frame -> symbol to frame }
    }
    val archived=CockpitDiagnostics.rows(status.optJSONObject("history")?.optJSONArray("items"))
    val observed=required.count { (symbol,frame) ->
        archived.count { it.optString("symbol")==symbol &&
            it.optString("interval")==frame }==1
    }
    val completed=required.count { (symbol,frame) ->
        archived.count { it.optString("symbol")==symbol &&
            it.optString("interval")==frame &&
            it.optBoolean("full_history_complete") }==1
    }
    val workCounter=status.optInt("persistent_history_complete_pairs",-1)
    val workTotal=status.optInt("persistent_history_total_pairs",-1)
    val replay=CockpitDiagnostics.rows(
        status.optJSONObject("historical_wave_replay")?.optJSONArray("items"))
    val replayed=symbols.distinct().count { symbol ->
        replay.count { it.optString("symbol")==symbol &&
            it.optLong("processed_h1_closes")>0L }==1
    }
    val archiveFullyObserved=required.isNotEmpty() && observed==required.size
    val workerAheadOfDurable=archiveFullyObserved && workCounter>completed
    val deviceStorageLow=status.optBoolean("persistent_history_storage_low")
    val error=status.opt("persistent_history_last_error")?.toString()
        ?.takeUnless { it.equals("null",true) || it.isBlank() } ?: ""
    val severity=when {
        deviceStorageLow || error.isNotBlank() || workerAheadOfDurable -> "WARNING"
        !archiveFullyObserved || completed<required.size ||
            replayed<symbols.distinct().size -> "NOT_VERIFIED"
        else -> "PASS"
    }
    return JSONObject()
        .put("schema","WILLIAMS_DURABLE_HISTORY_V1")
        .put("severity",severity)
        .put("required_symbol_frame_pairs",required.size)
        .put("observed_symbol_frame_pairs",observed)
        .put("sqlite_full_history_pairs",completed)
        .put("worker_completed_pairs_after_last_pass",workCounter)
        .put("worker_expected_pairs",workTotal)
        .put("worker_counter_can_lag",true)
        .put("worker_counter_ahead_of_sqlite",workerAheadOfDurable)
        .put("h1_replayed_symbols",replayed)
        .put("h1_expected_symbols",symbols.distinct().size)
        .put("storage_low",deviceStorageLow)
        .put("sync_error",safeDiagnosticText(error).take(250))
        .put("book_wave_accuracy_certified",false)
        .put("detail","Durable SQLite complete=$completed/${required.size}, " +
            "observed frame pairs=$observed/${required.size}; " +
            "worker full-pass counter=$workCounter/$workTotal (may lag); " +
            "H1 replayed=$replayed/${symbols.distinct().size}; " +
            "storage_low=$deviceStorageLow, error=${safeDiagnosticText(error).take(160)}. " +
            "Working-set READY is not full archive completeness; " +
            "even successful replay is not independently validated wave accuracy.")
}
