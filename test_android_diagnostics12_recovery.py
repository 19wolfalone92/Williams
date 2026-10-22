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


def test_signed_reconciliation_invalidates_only_read_only_ui_cache():
    runtime = (K / "StandaloneRuntime.kt").read_text()
    section = runtime.split("private fun reconcileFuturesThroughDoor(", 1)[1].split(
        "private fun requireSuccessfulFuturesReconciliation(", 1
    )[0]
    assert "futuresCampaignCoordinator.reconcile()" in section
    success = section.split('if (result.optBoolean("ok", false)) {', 1)[1]
    assert 'lastReconciliationResult = "SUCCESS"' in success
    for marker in (
        "futuresPositionSnapshotEpoch++",
        "cachedFuturesPositionEnvelope = null",
        "futuresPositionSnapshotLastAttemptMs = 0L",
    ):
        assert marker in success
    assert section.index("futuresCampaignCoordinator.reconcile()") < section.index(
        "futuresPositionSnapshotEpoch++"
    )
    # Never infer an exchange STOP from the journal or this UI cache.
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
