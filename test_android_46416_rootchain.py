"""Safety-critical chain regression for Android 4.64.16.

Paired with Kotlin FuturesSafetyMonitorPriorityTest, which exercises actual
scheduling values. These guards prevent re-introducing a misleading historical
incident or the double serialization of full durable Futures intent JSON.
"""
from pathlib import Path

ROOT=Path(__file__).parent
RUNTIME=(ROOT/"app/src/main/java/com/williamsbot/StandaloneRuntime.kt").read_text(encoding="utf-8")
COORDINATOR=(ROOT/"app/src/main/java/com/williamsbot/NativeFuturesCampaignCoordinator.kt").read_text(encoding="utf-8")
AUDIT=(ROOT/"app/src/main/java/com/williamsbot/TradingAuditStore.kt").read_text(encoding="utf-8")


def test_scanning_is_never_a_hard_blocker_for_overdue_signed_stop_supervision():
    block=RUNTIME.split("internal fun shouldScheduleIndependentFuturesMonitor(",1)[1].split(
        "internal fun shouldScheduleReadOnlyScan(",1)[0]
    assert "nowMs <= 0L || reconciliationActive" in block
    assert "scanning || reconciliationActive" not in block
    assert "OPEN_PROTECTED" in block and "ENTRY_PENDING" in block
    assert '"READY" -> 45_000L' in block
    assert "lastSuccessfulMs" in block and "lastAttemptMs" in block
    assert "return false" in block


def test_native_intents_snapshot_is_already_isolated_and_never_stringified_twice():
    assert 'fun intentsSnapshot(): JSONArray = intents()' in COORDINATOR
    assert 'fun intentsSnapshot(): JSONArray = JSONArray(intents().toString())' not in COORDINATOR
    assert 'val raw = executionStore.readState(intentsKey)' in COORDINATOR
    assert 'JSONArray(raw)' in COORDINATOR
    assert 'check(executionStore.writeState(intentsKey, durableSnapshot))' in COORDINATOR
    assert 'observationSink?.invoke(JSONArray(durableSnapshot))' in COORDINATOR


def test_slow_signed_reconciliation_phase_is_observable_without_relaxing_proofs():
    assert 'reconciliationPhaseElapsedMs' in COORDINATOR
    assert 'reconciliationPhaseDurationsJson' in COORDINATOR
    assert '.put("reconciliation_phase_elapsed_ms",' in COORDINATOR
    assert '.put("reconciliation_phase_durations_ms",' in COORDINATOR
    assert 'reconciliationRunning = true' in COORDINATOR
    assert 'state = State.RECONCILE_REQUIRED' in COORDINATOR
    assert 'exchange_snapshot_complete' in COORDINATOR


def test_historical_incident_includes_age_and_never_claims_current_exchange_stop():
    assert 'fun lastCockpitIncident(): JSONObject?' in AUDIT
    assert 'arrayOf("raw_json", "received_at")' in AUDIT
    assert '"stored_incident_age_ms"' in AUDIT
    assert '"HISTORICAL_SQLITE_INCIDENT_NOT_LIVE_BINANCE_PROOF"' in AUDIT
