"""Diagnostics (12) regression: real WS, cached STOP proof, safe pending entries."""
from pathlib import Path

K = Path(__file__).resolve().parent / "app/src/main/java/com/williamsbot"


def test_market_feed_uses_independent_websocket_transport_and_freshness_contract():
    runtime = (K / "StandaloneRuntime.kt").read_text()
    assert "private val publicMarketWebSocketClient = http.newBuilder()" in runtime
    assert ".pingInterval(0, TimeUnit.SECONDS).build()" in runtime
    assert runtime.count(
        "publicMarketWebSocketClient.newWebSocket(request, object : WebSocketListener()"
    ) == 2
    assert ".pingInterval(30, TimeUnit.SECONDS)" in runtime
    assert "userStream.start()" in runtime
    assert "maxAgeMs: Long = 30_000L" in runtime
    assert 'streamNeedsReconnect(' in runtime
    assert 'marketSocketConnected' in runtime
    assert 'publicSocketConnected' in runtime


def test_reconciliation_retains_last_known_ui_rows_without_authorizing_stale_positions():
    runtime = (K / "StandaloneRuntime.kt").read_text()
    section = runtime.split("private fun reconcileFuturesThroughDoor(", 1)[1].split(
        "private fun requireSuccessfulFuturesReconciliation(", 1
    )[0]
    assert "futuresCampaignCoordinator.reconcile()" in section
    assert 'lastReconciliationResult = "SUCCESS"' in section
    # A 6-second reconciliation used to empty the UI cache every time,
    # causing the Active page to alternate between real rows and loading.
    # Neither successful nor blocked periodic reconciliation may erase the
    # last-known signed rows. Freshness is enforced separately below.
    assert "cachedFuturesPositionEnvelope = null" not in section
    assert "futuresPositionSnapshotEpoch++" not in section
    fast = runtime.split("private fun fastFuturesPositionsSnapshot()", 1)[1].split(
        "private fun futuresPositionSnapshot(): JSONArray", 1
    )[0]
    assert "CockpitDiagnostics.fresh(cachedAt, nowMs, 15_000L)" in fast
    assert "lastKnownFuturesPositionsForDisplay(" in fast
    assert '"Showing a stale cached Futures position snapshot' in runtime
    assert "futuresPositionsSnapshotError == null" in runtime
    assert '.put("positions_snapshot_ok", futuresPositionsSnapshotOk)' in runtime
    # Unknown or stale positions MUST still block new trading; the read-only
    # cached display cannot serve as position, ownership or STOP proof.
    snapshot = runtime.split("private fun futuresPositionSnapshot(): JSONArray {", 1)[1].split(
        "private fun sellFuturesPosition(", 1
    )[0]
    assert 'optJSONArray("exchange_protection_observations")' in snapshot
    assert "signedProof.compareTo(signedLive)==0" in snapshot
    assert '"UNVERIFIED_OR_STALE"' in snapshot


def test_lingering_exchange_pending_order_must_remain_execution_blocked():
    runtime = (K / "StandaloneRuntime.kt").read_text()
    assert "futuresCampaignCoordinator.status()" in runtime
    assert "campaignCoordinatorReadyForEntry()" in runtime
    assert "campaignReady" in runtime
    assert "STOP_ENTRY" in runtime


def test_unresolved_diagnostic_findings_not_suppressed_by_zero_contract_conflicts():
    runtime = (K / "StandaloneRuntime.kt").read_text()
    archive = (K / "DiagnosticsArchive.kt").read_text()
    diagnostic = (K / "CockpitDiagnostics.kt").read_text()
    assert '"EXE-10"' in runtime
    assert 'newerExchangeStopSymbols' in runtime
    assert 'unresolved_diagnostic_checks' in runtime
    assert 'unresolved_check_count' in runtime
    assert 'INTERNAL_CONTRACT_CONTRADICTIONS_ONLY' in runtime
    assert '"unresolved_diagnostic_checks.json"' in archive
    assert 'const val VERSION = 15' in diagnostic
