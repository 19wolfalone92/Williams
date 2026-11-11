"""All-core diagnostics source/integration safety contract.

Tests the actual Android source, not optimistic roadmap prose.
These tests never call Binance, create orders, or use account credentials.
"""
from pathlib import Path

ROOT=Path(__file__).resolve().parent
SRC=ROOT/"app/src/main/java/com/williamsbot"
TESTS=ROOT/"app/src/test/java/com/williamsbot"


def read(name):
    return (SRC/name).read_text(encoding="utf-8")


def test_25_core_health_graph_keeps_unknown_distinct_from_fail_or_pass():
    graph=read("WilliamsDiagnosticChainAudit.kt")
    for domain in (
        "ANDROID_PROCESS","HEAP_PRESSURE","FOREGROUND_WORKER",
        "SQLITE_JOURNAL","EXCHANGE_CLOCK","PUBLIC_REST","PUBLIC_STREAMS",
        "SIGNED_ACCOUNT","NETWORK_GOVERNOR","PRIVATE_STREAM",
        "H1_HISTORY","WAVE_RESEARCH","SCANNER","EXECUTOR_BACKLOG",
        "TC2_RULES","RISK_GATE","EXECUTION_DOOR","PENDING_ORDERS",
        "POSITIONS","STOP_PROTECTION","STOP_TRAILING","EXITS_PNL",
        "RECONNECT_RECOVERY","UI_CONSISTENCY","FORENSIC_EXPORT",
    ):
        assert 'Core("'+domain+'"' in graph, domain
    assert '"NOT_VERIFIED"' in graph
    assert '"end_to_end_exchange_verified",false' in graph
    assert '"can_place_orders",false' in graph
    assert '"can_clear_execution_gate",false' in graph
    assert '"e2e_demo_certified",false' in graph
    assert "No API calls, order mutations, credentials" in graph
    assert "fun compare(" in graph
    assert '"no_longer_observed"' in graph


def test_report_uses_single_captured_snapshots_and_not_new_rest_on_graph():
    runtime=read("StandaloneRuntime.kt")
    diag=runtime.split("fun diagnostics(): JSONObject {",1)[1]
    assert "WilliamsDiagnosticChainAudit.build(" in diag
    assert 'statusSnapshot,coordinatorSnapshot,scannerEvidenceSnapshot,tests' in diag
    assert "val timelineSnapshot=auditStore.recentCockpitEvents(150)" in diag
    assert '.put("event_timeline", timelineSnapshot)' in diag
    assert '.put("futures_fill_stop_timing",fillStopTiming)' in diag
    assert '.put("runtime_resource_diagnostics",resourceDiagnostics)' in diag
    assert '.put("diagnostic_chain_audit",chainAudit)' in diag
    assert "val gateReadyAtReport = !concurrentCoordinatorTransition && executionReady()" in diag


def test_fill_to_stop_forensics_is_timing_only_never_placement_or_duplicate_stop():
    code=read("WilliamsFillStopTimingForensics.kt")
    assert "valid.sortBy { it.optLong(\"E\") }" in code
    assert '.put("detail",' not in code  # do not serialize arbitrary WS payloads
    assert '"SYMBOL_AND_EVENT_SEQUENCE_ONLY"' in code
    assert '"authenticated_exchange_timing_verified",false' in code
    assert '"independent_signed_protective_stop_placement_timing_verified",false' in code
    assert "SLOW_PROTECTION_WARNING_MS=5_000L" in code
    assert "stop" in code.lower()


def test_zip_checks_every_entry_crc_before_replacing_previous_report():
    archive=read("DiagnosticsArchive.kt")
    assert "internal fun verifyWilliamsDiagnosticZip(" in archive
    assert "CRC32()" in archive
    assert "ByteArray(8192)" in archive
    assert "entry.size==expanded && entry.crc==crc.value" in archive
    assert "DIAG_ZIP_OBSERVER_HISTORY_INCOMPLETE" in archive
    assert "verifyWilliamsDiagnosticZip(temporary,report.optLong(\"created_at_ms\"))" in archive
    assert archive.index("verifyWilliamsDiagnosticZip(temporary,report.optLong") < (
        archive.index("java.nio.file.Files.move(temporary.toPath(),output.toPath()"))
    for expected in (
        "diagnostic_chain_audit.json","diagnostic_chain_comparison.json",
        "futures_fill_stop_timing.json","runtime_resource_diagnostics.json",
        "observer_paper_full_history.ndjson",
    ):
        assert expected in archive, expected


def test_ui_can_inspect_all_cores_with_export_unchanged():
    ui=read("CockpitScreens.kt")
    runtime=read("StandaloneRuntime.kt")
    main=read("MainActivity.kt")
    assert 'CockpitCard("Ядра и связи' in ui
    assert 'CockpitCard("⏱ FILLED → STOP → защита"' in ui
    assert "var showAllDiagnosticCores by rememberSaveable" in ui
    assert 'diagnostic_chain_audit' in runtime
    assert 'diagnostic_chain_comparison' in runtime
    assert 'futures_fill_stop_timing' in runtime
    assert "compareChainWithLatest(context,report)" in main
    assert "DiagnosticsArchive.writeLatest(context,report)" in main


def test_all_unit_contracts_are_installed_and_have_no_demo_release_claim():
    for file in (
        "WilliamsDiagnosticChainAuditTest.kt",
        "WilliamsFillStopTimingForensicsTest.kt",
        "WilliamsDiagnosticResourceHealthTest.kt",
        "DiagnosticsArchiveIntegrityTest.kt",
    ):
        assert (TESTS/file).is_file(), file
    graph=read("WilliamsDiagnosticChainAudit.kt")
    assert '"independent_book_accuracy_certified",false' in graph
