from pathlib import Path

ROOT = Path(__file__).resolve().parent
src = (ROOT / "app/src/main/java/com/williamsbot/StandaloneRuntime.kt").read_text()
scanner = (ROOT / "app/src/main/java/com/williamsbot/WilliamsScannerClockOutage.kt").read_text()

def test_no_all_symbol_fail_when_shared_binance_utc_missing():
    assert 'scannerAllSymbolClockOutage(coreSymbols.size, ranked.size, symbolErrors)' in src
    assert 'clockUnavailable -> "WAITING_FOR_EXCHANGE_CLOCK"' in src
    assert 'if (!futuresMarketData.clockSnapshot().optBoolean("fresh", false))' in src
    assert "val completeScan = ranked.size == coreSymbols.size" in src
    assert 'if (!completeScan) { coreSymbols.forEach { rejectDecision' in src
    assert "completedAnalyses == 0" in scanner
    assert "symbolErrors.size == expectedSymbols" in scanner

def test_recoverable_utc_risk_failures_do_not_cancel_monitor_autorun():
    s = src.index("fun recover(): JSONObject")
    e = src.index("private fun getBody", s)
    assert 'putBoolean("auto_run", false)' not in src[s:e]
    for token in (
        "FUTURES_DAILY_BASELINE_PERSIST_FAILED",
        "FUTURES_DAILY_BASELINE_SOURCE_REPAIR_FAILED",
        "FUTURES_DAILY_ENTRY_HALT_PERSIST_FAILED",
        "FUTURES_DAILY_LOSS_CHECK_FAILED",
    ):
        pos = src.index(token)
        assert 'risk_daily_baseline_auto_paused' in src[max(0, pos-400):pos]
    # Operator STOP and KILL remain deliberate hard shutdowns.
    assert 'fun stop(): JSONObject' in src
    assert 'fun kill(): JSONObject' in src
    assert 'prefs.edit().putBoolean("auto_run", false).commit()' in src

def test_tc2_opposing_exit_keeps_same_strict_signed_proof():
    coordinator = (ROOT / "app/src/main/java/com/williamsbot/NativeFuturesCampaignCoordinator.kt").read_text()
    assert 'williamsTc2OppositeExitEvidenceBlocker' in coordinator
    assert 'findProtectiveStop(openAlgos' in coordinator
    assert '"TRIGGERED_WILLIAMS_EXIT"' in src
    assert '"decision_h1_opposition_"+symbol' in src
