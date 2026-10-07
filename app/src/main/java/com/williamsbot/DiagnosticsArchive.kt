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

    fun writeLatest(context: Context, reportJson: String): File {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val output = File(dir, LATEST)
        ZipOutputStream(FileOutputStream(output)).use { zip ->
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
        }
        return output
    }

    fun latest(context: Context): File? =
        File(context.filesDir, "$DIR/$LATEST")
            .takeIf { it.isFile && it.length() > 0L }

    private fun renderTests(report: org.json.JSONObject, domain: String): String {
        val out = StringBuilder()
        out.append("Williams diagnostics • ").append(report.optString("created_at")).append('\n')
        val tests = report.optJSONArray("tests") ?: org.json.JSONArray()
        for (i in 0 until tests.length()) {
            val test = tests.optJSONObject(i) ?: continue
            if (test.optString("domain") != domain) continue
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
        if (out.length == 0) out.append("No test records for this domain.\n")
        return out.toString()
    }

    private fun add(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}
