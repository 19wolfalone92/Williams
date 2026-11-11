"""Regression evidence from physical Samsung Williams_Diagnostics (7).zip.

Archive captured 2026-11-11 05:28:22 UTC:
7 OS PACKAGE_UPDATED exits and 1 historical OutOfMemoryError CRASH;
one concurrent signed reconciliation progressed from RECONCILE_REQUIRED
to OPEN_PROTECTED while read-only diagnostics were being assembled.
This source-contract test NEVER downgrades an actual crash or enables entries.
"""
from pathlib import Path

ROOT=Path(__file__).resolve().parent
JAVA=ROOT/"app/src/main/java/com/williamsbot"


def test_signed_apk_updates_separate_from_actual_android_crash():
    lifecycle=(JAVA/"RuntimeServiceLifecycleAssessment.kt").read_text(encoding="utf-8")
    checks=(JAVA/"CockpitDiagnostics.kt").read_text(encoding="utf-8")
    assert 'reason == "PACKAGE_UPDATED"' in lifecycle
    assert '"expected_package_update_exit_count"' in lifecycle
    assert '"unexpected_process_exit_count"' in lifecycle
    assert 'reason in abnormalExits' in lifecycle
    assert 'abnormal>0' in lifecycle
    assert 'lifecycle.optInt("abnormal_process_exit_count")>0' in checks
    assert 'lifecycle.optInt("unexpected_process_exit_count")>0' in checks


def test_mixed_signed_reconciliation_snapshot_is_never_an_entry_permission():
    code=(JAVA/"StandaloneRuntime.kt").read_text(encoding="utf-8")
    assert "internal fun diagnosticCoordinatorTransitionSeverity(" in code
    assert 'snapshotBlocker == "exchange_reconciliation_in_progress"' in code
    assert 'endState in setOf("OPEN_PROTECTED","READY") -> "NOT_VERIFIED"' in code
    assert 'else -> "WARNING"' in code
    assert 'val gateReadyAtReport = !concurrentCoordinatorTransition && executionReady()' in code
    assert '"snapshot_non_atomic",concurrentCoordinatorTransition' in code
    assert '"new_entries_allowed", gateReadyAtReport &&' in code


def test_compact_recent_observer_preview_has_separate_full_forensic_stream():
    rt=(JAVA/"StandaloneRuntime.kt").read_text(encoding="utf-8")
    arch=(JAVA/"DiagnosticsArchive.kt").read_text(encoding="utf-8")
    assert 'observerPaperArchive.snapshot(symbol,source,50)' in rt
    assert 'observerPaperArchive.snapshot(symbol,source,500)' not in rt
    assert 'ZipEntry("observer_paper_full_history.ndjson")' in arch
    assert 'streamAllEvidenceNdjson(paperWriter)' in arch
    assert 'addJson(zip,"diagnostic_report.json",report)' in arch
