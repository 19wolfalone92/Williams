package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DiagnosticsArchiveIntegrityTest {
    private val at=1_800_000_000_000L
    private val required=listOf(
        "archive_metadata.json","diagnostic_report.json","test_results.json",
        "diagnostic_chain_audit.json","diagnostic_chain_comparison.json",
        "futures_fill_stop_timing.json","runtime_resource_diagnostics.json",
        "futures_positions_signed_snapshot.json",
        "futures_exchange_stop_proofs.json","futures_intent_stop_lifecycle.json",
        "futures_exit_reconciliation.json","signal_decision_history.json",
        "observer_paper_full_history.ndjson","observer_paper_full_history_metadata.json",
        "stop_trail_decisions_full.json"
    )

    private fun createZip(missing:String?=null, expectedRows:Int=2,
        actualRows:Int=2, timestamp:Long=at):File {
        val file=File.createTempFile("williams-diagnostic-integrity-", ".zip")
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            for(entry in required) {
                if(entry==missing) continue
                zip.putNextEntry(ZipEntry(entry))
                val text=when(entry) {
                    "archive_metadata.json" -> JSONObject()
                        .put("report_at_ms",timestamp).toString()
                    "observer_paper_full_history.ndjson" ->
                        (0 until actualRows).joinToString("") { "{\"event\":$it}\n" }
                    "observer_paper_full_history_metadata.json" -> JSONObject()
                        .put("record_count",expectedRows)
                        .put("export_complete",true).toString()
                    else -> "{}"
                }
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return file
    }

    @Test fun fullValidatedZipMatchesReportStampAndObserverRowCount() {
        val file=createZip()
        try { verifyWilliamsDiagnosticZip(file,at) }
        finally { file.delete() }
    }

    @Test fun missingSignedStopOrTimingEvidencePreventsReplacingLatest() {
        val file=createZip(missing="futures_exchange_stop_proofs.json")
        try {
            val failure=runCatching { verifyWilliamsDiagnosticZip(file,at) }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure!!.message.orEmpty().contains("DIAG_ZIP_REQUIRED_ENTRY_MISSING"))
        } finally { file.delete() }
    }

    @Test fun lostPaperRowsOrWrongReportTimestampMakeArchiveInvalid() {
        val missingRow=createZip(expectedRows=2,actualRows=1)
        val staleStamp=createZip(timestamp=at-5000)
        try {
            assertTrue(runCatching { verifyWilliamsDiagnosticZip(missingRow,at) }
                .exceptionOrNull()?.message.orEmpty().contains("DIAG_ZIP_OBSERVER_HISTORY_INCOMPLETE"))
            assertTrue(runCatching { verifyWilliamsDiagnosticZip(staleStamp,at) }
                .exceptionOrNull()?.message.orEmpty().contains("DIAG_ZIP_CAPTURE_TIMESTAMP_MISMATCH"))
        } finally {
            missingRow.delete()
            staleStamp.delete()
        }
    }
}
