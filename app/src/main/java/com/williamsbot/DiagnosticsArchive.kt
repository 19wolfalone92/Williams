package com.williamsbot

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipFile

/**
 * Keeps the most recent sanitized diagnostics bundle on-device.
 *
 * The runtime report is already secret-free. This class never reads or exports
 * Binance API credentials, backup passwords, or auth tokens.
 */
object DiagnosticsArchive {
    private const val DIR = "diagnostics"
    private const val LATEST = "Williams_Diagnostics_Latest.zip"

    /** Read only bounded metadata from the previous ZIP. Never open the
     * entire historical report or rely on its last-modified wall-clock time.
     * An absent/corrupt previous archive is NOT proof of error resolution.
     */
    fun compareWithLatest(context: Context, current: org.json.JSONObject): org.json.JSONObject {
        val latest = latest(context)
        var prior: org.json.JSONArray? = null
        var priorAt = 0L
        if (latest != null) runCatching {
            ZipFile(latest).use { archive ->
                val metadata = readSmallZipEntry(archive,"archive_metadata.json")
                val checks = readSmallZipEntry(archive,"unresolved_diagnostic_checks.json")
                if(metadata != null && checks != null) {
                    priorAt = org.json.JSONObject(metadata).optLong("report_at_ms")
                    prior = org.json.JSONArray(checks)
                }
            }
        }
        return williamsDiagnosticComparison(prior,
            current.optJSONArray("unresolved_diagnostic_checks"),
            priorAt,current.optLong("created_at_ms"))
    }

    private fun readSmallZipEntry(archive: ZipFile, name: String): String? {
        val entry = archive.getEntry(name) ?: return null
        if (entry.size !in 1L..262_144L) return null
        return archive.getInputStream(entry).use { input ->
            val output=java.io.ByteArrayOutputStream()
            val buffer=ByteArray(8192)
            while(true) {
                val n=input.read(buffer)
                if(n<0) break
                if(output.size()+n>262_144) return@use null
                output.write(buffer,0,n)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }


    /**
     * Do not serialize the whole report into a 9+ MB String or pretty-print it:
     * Android can have less than 4 MB of free Java heap after a full check.
     * Serialize each JSON subtree DIRECTLY to a single ZIP stream instead.
     * The diagnostic report stays immutable and the previous ZIP is not
     * replaced until the new one was fully written and closed.
     */
    @Synchronized
    fun writeLatest(context: Context, report: org.json.JSONObject): File {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val output = File(dir, LATEST)
        val temporary = File.createTempFile("williams-diagnostics-", ".zip", dir)
        try {
            ZipOutputStream(FileOutputStream(temporary)).use { zip ->
                addJson(zip,"archive_metadata.json",
                    org.json.JSONObject()
                        .put("schema","WILLIAMS_ARCHIVE_META_V1")
                        .put("report_at_ms",report.optLong("created_at_ms"))
                        .put("diagnostic_contract_version",report.optInt("diagnostic_contract_version")))
                addJson(zip,"diagnostic_report.json",report)
                addJson(zip,"system_info.json",report.optJSONObject("system_info") ?: org.json.JSONObject())
                addJson(zip,"configuration_sanitized.json",report.optJSONObject("configuration_sanitized") ?: org.json.JSONObject())
                addJson(zip,"test_results.json",report.optJSONArray("tests") ?: org.json.JSONArray())
                for(domain in listOf("runtime","scanner","execution","websocket","binance","database","ui")) {
                    add(zip,domain+".log",renderTests(report,domain))
                }
                val triage=report.optJSONObject("diagnostic_triage")
                    ?: williamsDiagnosticTriage(report.optJSONArray("tests") ?: org.json.JSONArray(),
                        report.optJSONObject("diagnostic_summary")?.optBoolean("execution_allowed")==true)
                val comparison=report.optJSONObject("diagnostic_comparison")
                addJson(zip,"diagnostic_triage.json",triage)
                addJson(zip,"diagnostic_comparison.json",comparison
                    ?: org.json.JSONObject().put("status","NOT_COMPARABLE"))
                add(zip,"START_HERE_RU.txt",buildTriageOverview(triage,comparison))
                addJson(zip,"event_timeline.json",report.optJSONArray("event_timeline") ?: org.json.JSONArray())
                addJson(zip,"android_background_health.json",report.optJSONObject("status")
                    ?.optJSONObject("android_runtime") ?: org.json.JSONObject())
                addJson(zip,"last_incident.json",report.optJSONObject("last_incident") ?: org.json.JSONObject())
                addJson(zip,"sqlite_health.json",report.optJSONObject("sqlite_health") ?: org.json.JSONObject())
                add(zip,"strategy.log",renderTests(report,"strategy"))
                addJson(zip,"williams_sources.json",report.optJSONObject("unified_strategy_evidence") ?: org.json.JSONObject())
                addJson(zip,"event_compaction.json",report.optJSONObject("event_compaction") ?: org.json.JSONObject())
                addJson(zip,"unresolved_diagnostic_checks.json",
                    report.optJSONArray("unresolved_diagnostic_checks") ?: org.json.JSONArray())
                addJson(zip,"scanner_snapshot.json",report.optJSONObject("scanner_snapshot") ?: org.json.JSONObject())
                addJson(zip,"chart_reading_audit.json",report.optJSONArray("chart_reading_audit") ?: org.json.JSONArray())
                addJson(zip,"book_rule_coverage.json",report.optJSONArray("book_rule_coverage") ?: org.json.JSONArray())
                addJson(zip,"tc1_zero_point_audit.json",report.optJSONArray("tc1_zero_point_audit") ?: org.json.JSONArray())
                addJson(zip,"futures_positions_signed_snapshot.json",
                    report.optJSONObject("status")?.optJSONArray("futures_positions") ?: org.json.JSONArray())
                addJson(zip,"futures_exchange_stop_proofs.json",
                    report.optJSONObject("futures_campaign_coordinator")
                        ?.optJSONArray("exchange_protection_observations") ?: org.json.JSONArray())
                addJson(zip,"futures_intent_stop_lifecycle.json",
                    report.optJSONObject("futures_campaign_coordinator")
                        ?.optJSONArray("pending_intent_details") ?: org.json.JSONArray())
                addJson(zip,"signal_decision_history.json",report.opt("signal_decision_history"))
                // The latest 300 decisions are from SQLite, without creating
                // a second giant JSON string or silently fabricating evidence.
                val stops: Any=runCatching {
                    TradingAuditStore(context).use { it.recentStopTrailDecisionEvents(300) }
                }.getOrElse {
                    org.json.JSONObject().put("status","NOT_AVAILABLE")
                        .put("reason","STOP_AUDIT_SQLITE_READ_FAILED")
                }
                addJson(zip,"stop_trail_decisions_full.json",stops)
            }
            // Move only a fully closed ZIP, preserving the old archive when
            // this write fails (including disk-full and process interruption).
            try {
                java.nio.file.Files.move(temporary.toPath(),output.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(temporary.toPath(),output.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { temporary.delete() }
        return output
    }

    private fun addJson(zip: ZipOutputStream,name:String,value:Any?) {
        zip.putNextEntry(ZipEntry(name))
        // Closing the Writer would also close the entire ZIP; only flush it.
        val writer=java.io.BufferedWriter(
            java.io.OutputStreamWriter(zip,Charsets.UTF_8),8192)
        writeDiagnosticsJsonStreaming(value,writer)
        writer.flush()
        zip.closeEntry()
    }

    private fun buildTriageOverview(triage: org.json.JSONObject,
        comparison: org.json.JSONObject?): String = buildString {
        appendLine("WILLIAMS BOT — КРАТКАЯ ДИАГНОСТИКА")
        appendLine("Итог компонентных проверок: ${triage.optString("overall")}")
        appendLine("Ошибки: ${triage.optInt("failed_count")}, предупреждения: ${triage.optInt("warning_count")}, непроверено: ${triage.optInt("unverified_count")}")
        appendLine("Новые входы разрешены по снимку: ${triage.optBoolean("new_entries_allowed_at_snapshot")}")
        appendLine("ВАЖНО: успех тестов НЕ означает 100% точность, верность всем книгам или испытания Binance Demo.")
        appendLine()
        val priority=triage.optJSONArray("priority_issues") ?: org.json.JSONArray()
        for (i in 0 until priority.length()) {
            val issue=priority.optJSONObject(i) ?: continue
            appendLine("${i+1}. ${issue.optString("severity")} | ${issue.optString("id")} | ${issue.optString("name")}")
            appendLine("   ${issue.optString("next_action")}")
            appendLine("   ${issue.optString("detail")}")
        }
        if (triage.optBoolean("priority_issues_truncated"))
            appendLine("Ещё есть неразрешённые пункты. Полный список — unresolved_diagnostic_checks.json.")
        if(comparison?.optString("status")=="COMPARABLE") {
            appendLine()
            appendLine("ИЗМЕНЕНИЯ ПО СРАВНЕНИЮ С ПРЕДЫДУЩЕЙ ДИАГНОСТИКОЙ:")
            appendLine("Новые проблемы: ${comparison.optInt("new_count")}; ухудшились: ${comparison.optInt("worsened_count")};")
            appendLine("стали менее серьёзными: ${comparison.optInt("less_severe_count")}; не наблюдаются: ${comparison.optInt("no_longer_reported_count")}.")
            appendLine("ВНИМАНИЕ: не наблюдаются ≠ доказанно исправлены.")
        } else {
            appendLine("Нет сопоставимого предыдущего диагностического снимка.")
        }
        appendLine("Для анализа STOP см. stop_trail_decisions_full.json, futures_exchange_stop_proofs.json.")
        appendLine("Для анализа волн см. chart_reading_audit.json, signal_decision_history.json.")
    }

    fun latest(context: Context): File? =
        File(context.filesDir, "$DIR/$LATEST")
            .takeIf { it.isFile && it.length() > 0L }

    /** Distinguish a successfully stored CURRENT diagnostic from an older
     * archive left behind after a failed write. Never silently export old data.
     */
    fun latestReportAtMs(context: Context): Long = runCatching {
        val file=latest(context) ?: return@runCatching 0L
        ZipFile(file).use { zip ->
            val raw=readSmallZipEntry(zip,"archive_metadata.json")
                ?: return@use 0L
            org.json.JSONObject(raw).optLong("report_at_ms")
        }
    }.getOrDefault(0L)

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
