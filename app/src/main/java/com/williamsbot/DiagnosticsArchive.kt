package com.williamsbot

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Keeps the most recent sanitized diagnostics bundle on-device.
 *
 * The runtime report is already secret-free. This class never reads or exports
 * Binance API credentials, backup passwords, or auth tokens.
 */
object DiagnosticsArchive {
    private const val DIR = "diagnostics"
    private const val LATEST = "Williams_Diagnostics_Latest.zip"

    @Synchronized
    fun writeLatest(context: Context, reportJson: String): File {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val output = File(dir, LATEST)
        val temporary = File.createTempFile("williams-diagnostics-", ".zip", dir)
        try {
        ZipOutputStream(FileOutputStream(temporary)).use { zip ->
            val report = org.json.JSONObject(reportJson)

            add(zip, "diagnostic_report.json", report.toString(2))
            add(
                zip,
                "system_info.json",
                report.optJSONObject("system_info")?.toString(2) ?: "{}"
            )
            add(
                zip,
                "configuration_sanitized.json",
                report.optJSONObject("configuration_sanitized")?.toString(2) ?: "{}"
            )
            add(
                zip,
                "test_results.json",
                report.optJSONArray("tests")?.toString(2) ?: "[]"
            )
            add(
                zip,
                "runtime.log",
                renderTests(report, "runtime")
            )
            add(
                zip,
                "scanner.log",
                renderTests(report, "scanner")
            )
            add(
                zip,
                "execution.log",
                renderTests(report, "execution")
            )
            add(
                zip,
                "websocket.log",
                renderTests(report, "websocket")
            )
            add(
                zip,
                "binance.log",
                renderTests(report, "binance")
            )
            add(
                zip,
                "database.log",
                renderTests(report, "database")
            )
            add(
                zip,
                "ui.log",
                renderTests(report, "ui")
            )
            add(zip, "event_timeline.json", report.optJSONArray("event_timeline")?.toString(2) ?: "[]")
            add(zip, "last_incident.json", report.optJSONObject("last_incident")?.toString(2) ?: "{}")
            add(zip, "sqlite_health.json", report.optJSONObject("sqlite_health")?.toString(2) ?: "{}")
            add(zip, "strategy.log", renderTests(report, "strategy"))
            add(zip, "williams_sources.json",
                report.optJSONObject("unified_strategy_evidence")?.toString(2) ?: "{}")
            add(zip, "event_compaction.json",
                report.optJSONObject("event_compaction")?.toString(2) ?: "{}")
            add(zip, "unresolved_diagnostic_checks.json",
                report.optJSONArray("unresolved_diagnostic_checks")?.toString(2) ?: "[]")
            // Dedicated forensic evidence: keep scanner, current signed STOP
            // observations, position snapshots and timeframe coverage together
            // with their original timestamps. Never derive a fresh proof from
            // a persisted campaign stop or from an older UI snapshot.
            add(zip, "scanner_snapshot.json",
                report.optJSONObject("scanner_snapshot")?.toString(2) ?: "{}")
            add(zip, "chart_reading_audit.json",
                report.optJSONArray("chart_reading_audit")?.toString(2) ?: "[]")
            add(zip, "book_rule_coverage.json",
                report.optJSONArray("book_rule_coverage")?.toString(2) ?: "[]")
            add(zip, "tc1_zero_point_audit.json",
                report.optJSONArray("tc1_zero_point_audit")?.toString(2) ?: "[]")
            add(zip, "futures_positions_signed_snapshot.json",
                report.optJSONObject("status")?.optJSONArray("futures_positions")?.toString(2) ?: "[]")
            add(zip, "futures_exchange_stop_proofs.json",
                report.optJSONObject("futures_campaign_coordinator")
                    ?.optJSONArray("exchange_protection_observations")?.toString(2) ?: "[]")
            add(zip, "futures_intent_stop_lifecycle.json",
                report.optJSONObject("futures_campaign_coordinator")
                    ?.optJSONArray("pending_intent_details")?.toString(2) ?: "[]")
            add(zip, "signal_decision_history.json",
                report.opt("signal_decision_history")?.toString() ?: "null")
        }
        java.nio.file.Files.move(temporary.toPath(), output.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.delete() }
        return output
    }

    fun latest(context: Context): File? =
        File(context.filesDir, "$DIR/$LATEST")
            .takeIf { it.isFile && it.length() > 0L }

    private fun renderTests(report: org.json.JSONObject, domain: String): String {
        val out = StringBuilder()
        out.append("Williams diagnostics • ").append(report.optString("created_at")).append('\n')
        var count = 0
        val tests = report.optJSONArray("tests") ?: org.json.JSONArray()
        for (i in 0 until tests.length()) {
            val test = tests.optJSONObject(i) ?: continue
            if (test.optString("domain") != domain) continue
            count++
            val severity = test.optString(
                "severity",
                if (test.optBoolean("ok", false)) "PASS" else "UNKNOWN"
            )
            out.append(severity)
                .append(" | ")
                .append(test.optString("name"))
                .append(" | ")
                .append(
                    test.optString(
                        "detail",
                        test.optString("message", "")
                    )
                )
                .append('\n')
        }
        for (event in CockpitDiagnostics.rows(report.optJSONArray("event_timeline")).asReversed()) {
            val type = event.optString("e")
            val matches = when (domain) {
                "ui" -> type == "COCKPIT_UI"
                "execution", "binance" -> type in setOf("COCKPIT_EXECUTION", "COCKPIT_RECONCILIATION", "COCKPIT_SIGNAL_RISK", "COCKPIT_ORDER_RESPONSE", "COCKPIT_USER_WS")
                "websocket" -> type == "COCKPIT_USER_WS"
                "runtime" -> type == "COCKPIT_CONTROL"
                else -> false
            }
            if (!matches) continue
            count++
            out.append(event.optLong("E")).append(" | ").append(type).append(" | ")
                .append(event.optString("symbol")).append(" | ").append(event.optString("state"))
                .append(" | ").append(safeDiagnosticText(event.optString("detail"))).append('\n')
        }

        if (domain == "strategy") {
            val evidence = report.optJSONObject("unified_strategy_evidence")
            if (evidence != null) {
                out.append("SOURCE SNAPSHOT | ")
                    .append(evidence.optString("status"))
                    .append(" | observed=").append(evidence.optInt("observed_signal_count"))
                    .append(" | opposite-before-context=")
                    .append(evidence.optInt("pre_context_opposing_symbol_count")).append('\n')
                for (source in williamsSourceProfiles) {
                    val row = evidence.optJSONObject("by_source")?.optJSONObject(source) ?: continue
                    out.append(source).append(" | all=").append(row.optInt("total"))
                        .append(" entry=").append(row.optInt("entry"))
                        .append(" add=").append(row.optInt("add_on"))
                        .append(" LONG=").append(row.optInt("long"))
                        .append(" SHORT=").append(row.optInt("short")).append('\n')
                }
            }
        }
        if (count == 0) out.append("NOT_VERIFIED | No observations recorded for this domain.\n")
        return out.toString()
    }

    private fun add(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}
