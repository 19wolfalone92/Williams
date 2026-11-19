package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only retrospective of entry rejection reasons that survive in the
 * bounded Android cockpit event timeline. This is NOT a signed Binance
 * acceptance receipt, a current execution gate, or proof that an order was
 * never submitted. No REST calls, API secrets, client order IDs or mutations.
 */
internal object WilliamsEntryFailureForensics {
    private const val MAX_WINDOW_MS = 86_400_000L
    private const val MAX_EXAMPLES = 16

    private fun rootCause(detail: String): String = when {
        detail.contains("FUTURES_ENTRY_POST_RECEIPT_IDENTITY_OR_QUANTITY_UNVERIFIED") ->
            "BINANCE_ENTRY_POST_ACK_IDENTITY_UNKNOWN"
        // A local H1 book rule declining a consumed/unknown trigger is not
        // evidence that a submitted order was rejected by Binance.
        detail.contains("H1_BOOK_BREAKOUT_WAS_ALREADY_CONSUMED_OR_HISTORY_UNVERIFIED") ->
            "LOCAL_H1_TRIGGER_CONSUMED_OR_UNVERIFIED"
        detail.contains("FUTURES_HALF_MARGIN_UNKNOWN:FOREIGN_ASSET_IDENTITY") ->
            "FOREIGN_ASSET_IDENTITY"
        detail.contains("FUTURES_HALF_MARGIN_UNKNOWN:") ->
            "ACCOUNT_MARGIN_PROOF_UNKNOWN"
        detail.contains("FUTURES_HALF_MARGIN_EXCEEDED") ->
            "ACCOUNT_MARGIN_50_PERCENT_EXCEEDED"
        detail.contains("FUTURES_LEVERAGE_STOP_BUFFER") ->
            "ISOLATED_LEVERAGE_STOP_BUFFER"
        detail.contains("Futures server clock probe failed", ignoreCase = true) ||
            detail.contains("FUTURES_SERVER_CLOCK", ignoreCase = true) ->
            "SIGNED_EXCHANGE_CLOCK_UNVERIFIED"
        detail.contains("SIGNED_RECONCILIATION_STALE") ->
            "SIGNED_RECONCILIATION_STALE"
        detail.contains("FUTURES_ENTRY_CONTEXT_REJECTED") ->
            "ENTRY_CONTEXT_OTHER"
        detail.contains("REJECT", ignoreCase = true) -> "REJECT_OTHER"
        else -> "UNCLASSIFIED_RESPONSE"
    }

    private fun nextCheck(code: String): String = when (code) {
        "BINANCE_ENTRY_POST_ACK_IDENTITY_UNKNOWN" ->
            "POST returned an incomplete or mismatching signed Binance order receipt. Do NOT retry POST; reconcile exact symbol/clientAlgoId or clientOrderId, open orders, fills and STOP."
        "LOCAL_H1_TRIGGER_CONSUMED_OR_UNVERIFIED" ->
            "Check prior closed H1 breakout history against its original trigger; do not replay an already consumed or unverified price trigger."
        "FOREIGN_ASSET_IDENTITY" ->
            "Inspect signed account.assets asset-code shape; never treat an unknown asset as USDT collateral."
        "ACCOUNT_MARGIN_PROOF_UNKNOWN" ->
            "Compare two complete signed account/order snapshots and the exact rejected proof field."
        "ACCOUNT_MARGIN_50_PERCENT_EXCEEDED" ->
            "Inspect position and open-order liabilities and 50% margin headroom."
        "ISOLATED_LEVERAGE_STOP_BUFFER" ->
            "Verify signed isolated leverage brackets, quantity and structural stop; never move the book stop to force entry."
        "SIGNED_EXCHANGE_CLOCK_UNVERIFIED" ->
            "Verify an unsigned Binance Demo UTC probe and subsequent signed REST reconciliation; no device-clock fallback."
        "SIGNED_RECONCILIATION_STALE" ->
            "Request a fresh signed exchange reconciliation; do not reuse expired account/STOP evidence."
        else -> "Inspect original local execution log and signed exchange state before attempting any new order."
    }

    /**
     * An event retained in the 24h ring buffer may predate the installed APK.
     * The last Android PACKAGE_REPLACED_OBSERVED event only bounds observations;
     * it does NOT prove that the newly installed version executed a trade.
     */
    fun lastObservedPackageUpdateAt(events: JSONArray?, capturedAtMs: Long): Long {
        if (capturedAtMs <= 0L) return 0L
        return CockpitDiagnostics.rows(events).asSequence()
            .filter { it.optString("code") == "PACKAGE_REPLACED_OBSERVED" }
            .map { it.optLong("at_ms", 0L) }
            .filter { it > 0L && it <= capturedAtMs }
            .maxOrNull() ?: 0L
    }

    fun summarize(events: JSONArray?, capturedAtMs: Long, packageUpdateAtMs: Long = 0L): JSONObject {
        val counts = linkedMapOf<String, Int>()
        val samples = JSONArray()
        val sampleKeys = mutableSetOf<String>()
        var rejected = 0
        var localH1Declines = 0
        var clockFailures = 0
        var uncertainPending = 0
        var validEvents = 0
        var latestRejectionAtMs = 0L
        val knownUpdate = packageUpdateAtMs > 0L && packageUpdateAtMs <= capturedAtMs
        var rejectedSinceUpdate = 0
        var clockFailuresSinceUpdate = 0
        var unknownSubmissionsSinceUpdate = 0
        val rows = CockpitDiagnostics.rows(events)
            .sortedByDescending { it.optLong("E") }
        for (row in rows) {
            val at = row.optLong("E")
            if (capturedAtMs <= 0L || at <= 0L || at > capturedAtMs ||
                capturedAtMs - at > MAX_WINDOW_MS) continue
            validEvents++
            val event = row.optString("e")
            val state = row.optString("state")
            val detail = row.optString("detail")
            val isRejection = event == "COCKPIT_ORDER_RESPONSE" &&
                state == "UNCONFIRMED_OR_REJECTED"
            val isClockFailure = event == "COCKPIT_RECONCILIATION" &&
                detail.contains("ok=false") && rootCause(detail) == "SIGNED_EXCHANGE_CLOCK_UNVERIFIED"
            if (event == "COCKPIT_EXECUTION" && state == "ENTRY_PENDING" &&
                detail.contains("SUBMISSION_UNKNOWN")) {
                uncertainPending++
                if (knownUpdate && at >= packageUpdateAtMs) unknownSubmissionsSinceUpdate++
            }
            if (!isRejection && !isClockFailure) continue
            if (isRejection) {
                rejected++
                if (rootCause(detail) == "LOCAL_H1_TRIGGER_CONSUMED_OR_UNVERIFIED") localH1Declines++
                if (knownUpdate && at >= packageUpdateAtMs) rejectedSinceUpdate++
                latestRejectionAtMs = maxOf(latestRejectionAtMs, at)
            } else {
                clockFailures++
                if (knownUpdate && at >= packageUpdateAtMs) clockFailuresSinceUpdate++
            }
            val cause = rootCause(detail)
            counts[cause] = (counts[cause] ?: 0) + 1
            val symbol = row.optString("symbol").takeIf {
                it.matches(Regex("[A-Z0-9]{1,24}"))
            } ?: ""
            // Deduplicate identical retries for readability, without hiding
            // the raw frequency in occurrence_counts.
            if (samples.length() < MAX_EXAMPLES && sampleKeys.add("$symbol|$cause")) {
                samples.put(JSONObject()
                    .put("symbol", symbol)
                    .put("observed_at_ms", at)
                    .put("age_ms_at_capture", capturedAtMs - at)
                    .put("root_cause_code", cause)
                    .put("next_check", nextCheck(cause)))
            }
        }
        val occurrences = JSONObject()
        counts.toSortedMap().forEach { (code, count) -> occurrences.put(code, count) }
        return JSONObject()
            .put("schema", "WILLIAMS_ENTRY_FAILURE_FORENSICS_V1")
            .put("read_only", true)
            .put("can_place_or_retry_orders", false)
            .put("captures_current_execution_gate", false)
            .put("source", "BOUNDED_COCKPIT_EVENT_TIMELINE")
            .put("source_event_count", rows.size)
            .put("valid_event_count_last_24h", validEvents)
            .put("history_may_be_truncated", true)
            .put("rejected_order_response_count_24h", rejected)
            .put("local_h1_pre_submission_decline_count_24h", localH1Declines)
            // Remaining rejections are not proof of a Binance rejection either;
            // they include local margin and clock safety gates.
            .put("other_entry_response_rejection_count_24h", rejected - localH1Declines)
            .put("clock_reconciliation_failure_count_24h", clockFailures)
            .put("submission_unknown_observation_count_24h", uncertainPending)
            .put("latest_rejected_order_response_at_ms", latestRejectionAtMs)
            .put("last_package_update_observed_at_ms",
                if (knownUpdate) packageUpdateAtMs else JSONObject.NULL)
            .put("post_package_update_counts_available", knownUpdate)
            .put("rejected_order_response_count_since_package_update",
                if (knownUpdate) rejectedSinceUpdate else JSONObject.NULL)
            .put("clock_failure_count_since_package_update",
                if (knownUpdate) clockFailuresSinceUpdate else JSONObject.NULL)
            .put("submission_unknown_count_since_package_update",
                if (knownUpdate) unknownSubmissionsSinceUpdate else JSONObject.NULL)
            .put("latest_rejection_predates_package_update",
                knownUpdate && latestRejectionAtMs > 0L && latestRejectionAtMs < packageUpdateAtMs)
            .put("occurrence_counts", occurrences)
            .put("representative_failures", samples)
            .put("interpretation",
                "An app-observed rejection is not a signed exchange non-placement receipt. " +
                "Local H1 trigger denials are pre-submission strategy safeguards, not exchange rejections. " +
                "A later READY snapshot does not erase historical rejections. " +
                "An empty bounded timeline is NOT proof of correct Demo execution.")
    }
}
