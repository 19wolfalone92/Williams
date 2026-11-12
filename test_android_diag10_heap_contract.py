"""Regression contracts for Williams Diagnostics (10): Android heap and STOP safety.

The crash capture points to NativeFuturesCampaignCoordinator.intents(); this static
contract prevents two unnecessary full SQLite JSON parses and excludes the
duplicated multi-megabyte decision tree from the diagnostic status snapshot.
Actual heap/FGS/STOP behavior still needs device and Binance Demo testing.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parent
RUNTIME = ROOT / "app/src/main/java/com/williamsbot/StandaloneRuntime.kt"
COORDINATOR = ROOT / "app/src/main/java/com/williamsbot/NativeFuturesCampaignCoordinator.kt"


def test_diagnostics_skips_status_decision_trace_allocation():
    src = RUNTIME.read_text(encoding="utf-8")
    assert "fun status(fast: Boolean = false, includeDecisionTraces: Boolean = true)" in src
    assert '.apply { if (includeDecisionTraces) put("decision_traces",decisionTraceSnapshot()) }' in src
    assert "status(fast = true, includeDecisionTraces = false)" in src
    assert 'check(!statusSnapshot.has("decision_traces"))' in src
    assert '.put("signal_decision_history",decisionTraceSnapshot(fullHistory=true))' in src
    # The complete forensic history remains available in the exported ZIP.
    archive = (
        ROOT / "app/src/main/java/com/williamsbot/DiagnosticsArchive.kt"
    ).read_text(encoding="utf-8")
    assert 'addJson(zip,"signal_decision_history.json",report.opt("signal_decision_history"))' in archive


def test_operator_takeover_uses_single_consistent_intents_snapshot():
    src = COORDINATOR.read_text(encoding="utf-8")
    start = src.index("fun approveOperatorStopSupervision(")
    end = src.index("val adapter = adapterFactory()", start)
    block = src[start:end]
    assert "val persistedIntents = intents()" in block
    assert "latestEntryIntentIndex(persistedIntents,symbol)" in block
    assert "persistedIntents.optJSONObject(it)" in block
    assert block.count("intents()") == 1


def test_diagnostics_never_changes_signed_futures_stop_authority():
    src = RUNTIME.read_text(encoding="utf-8")
    diagnostics = src[src.index("fun diagnostics(): JSONObject"):src.index("fun settings(): JSONObject")]
    assert "scheduleReadOnlySignedFillAudit()" in diagnostics
    assert "WilliamsSignedStopJoinAudit.inspect(" in diagnostics
    assert "diagnosticCoordinatorTransitionSeverity(" in diagnostics
