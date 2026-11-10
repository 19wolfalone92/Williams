"""Regression contract against 4.64.17 physical Android status diagnostics.

The exported ZIP contained native status.execution_enabled=true,
coordinator.ready_for_new_entry=true, scanner_state=RUNNING, and false
Cockpit new_entries_allowed with reason 'Сканер: Работает'.
An unrelated XRPUSDT entry also failed after lengthy checks because the
signed account proof was >90 seconds old at final exchange mutation time.

These are integration/source contracts; Android unit tests exercise the
actual Kotlin functions and safe boundary conditions.
"""
from pathlib import Path

root=Path(__file__).resolve().parent
ui=(root/"app/src/main/java/com/williamsbot/CockpitDiagnostics.kt").read_text(encoding="utf8")
rt=(root/"app/src/main/java/com/williamsbot/StandaloneRuntime.kt").read_text(encoding="utf8")
coordinator=(root/"app/src/main/java/com/williamsbot/NativeFuturesCampaignCoordinator.kt").read_text(encoding="utf8")


def test_read_only_scanner_never_becomes_an_extra_entry_permission_gate():
    first=ui.split("fun readiness(status: JSONObject, coordinator: JSONObject, now: Long)",1)[1].split(
        "fun ",1)[0]
    expression=first.split("val entries =",1)[1].split("val positions =",1)[0]
    assert "scannerReady" not in expression
    assert 'status.optBoolean("execution_enabled")' in expression
    assert 'coordinatorReady' in expression
    assert '!riskBudgetExhausted' in expression
    assert '"scanner_last_completed_observation_ready"' in first
    assert '"scanner_read_only_state"' in first


def test_preentry_signed_refresh_only_for_valid_h1_signal_and_before_exchange_sizing():
    block=rt.split("private fun performCampaignScan()",1)[1].split(
        "private fun cancelPendingEntriesOutsideWilliamsSession()",1)[0]
    assert "if (ranked.any { it.campaignReady } &&" in block
    assert 'signedFuturesEntryRefreshNeeded(lastRestReconcileMs, System.currentTimeMillis())' in block
    assert '"PRE_ENTRY_SIGNED_ACCOUNT_REFRESH"' in block
    assert "val refreshed = reconcileFuturesThroughDoor(" in block
    assert 'signedFuturesReconciliationFresh(' in block
    assert '45_000L' in block
    assert 'if (!refreshed.optBoolean("ok", false) || !stillFresh ||' in block
    assert block.index("val refreshed = reconcileFuturesThroughDoor(") < block.index(
        "val livePositionSnapshot = futuresCampaignCoordinator.positionsSnapshot()")
    assert 'PRE_ENTRY_SIGNED_EXCHANGE_PROOF_UNVERIFIED' in block
    assert 'submitNativeFuturesCampaignEntry(candidate)' in block
    # A second refresh happens AFTER sizing but BEFORE a durable ENTRY intent
    # is persisted; final H1 network proof may consume the remaining headroom.
    placement=rt.split("private fun submitNativeFuturesCampaignEntryChecked(",1)[1].split(
        "private fun scanDailyRiskAllowsEntry()",1)[0]
    assert 'minimumRemainingMs = 35_000L' in placement
    assert placement.index('"PRE_ENTRY_SIGNED_ACCOUNT_REFRESH"') < placement.index(
        'val result = withCampaignMutation(')
    assert 'PRE_ENTRY_SIGNED_HEADROOM_EXHAUSTED_BEFORE_INTENT' in placement
    assert 'lastRestReconcileMs, System.currentTimeMillis(), 70_000L' in placement
    assert placement.index('PRE_ENTRY_SIGNED_HEADROOM_EXHAUSTED_BEFORE_INTENT') < placement.index(
        'futuresCampaignCoordinator.submitPendingEntry(plan)')


def test_original_signed_futures_and_stop_fences_remain_unchanged():
    assert 'maximumAgeMs: Long = 90_000L' in rt
    assert 'signedFuturesReconciliationFresh(lastRestReconcileMs, System.currentTimeMillis())' in rt
    assert '!signedStopDisplayUnverified' in rt
    assert 'dailyRiskBaselineReady()' in rt
    assert 'dailyEntryHalt().allowNewEntries' in rt
    assert 'riskBudgetKnown' in ui
    assert 'riskBudgetExhausted' in ui
    assert 'val finalPositions=validateFuturesPositionSnapshot(adapter.positions())' in coordinator
    assert 'historical STOP still open after signed cleanup; new entry blocked' in coordinator
