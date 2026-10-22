"""Durability and reboot recovery source contracts (no real secret material)."""
from pathlib import Path

BASE = Path(__file__).resolve().parent / "app" / "src" / "main"
KOTLIN = BASE / "java" / "com" / "williamsbot"


def source(name):
    return (KOTLIN / name).read_text(encoding="utf-8")


def test_binance_keys_are_committed_atomically_not_applied_then_rolled_back():
    runtime = source("StandaloneRuntime.kt")
    block = runtime.split("fun configure(apiKey: String, apiSecret: String): JSONObject", 1)[1].split("fun clear(): JSONObject", 1)[0]
    assert 'putString("api_key", newKey)' in block
    assert 'putString("api_secret", newSecret).commit()' in block
    assert 'saved_to_encrypted_storage' in block
    assert 'read_back_verified' in block
    assert '.apply()' not in block
    assert 'putString("api_key", oldKey)' not in block
    assert 'putString("api_secret", oldSecret)' not in block
    assert block.index('futures.serverTime()') < block.index('val persisted = key() == newKey')
    assert "PENDING_EXCHANGE_VERIFICATION" in block
    assert "persistSafetyFlag(\"reconcile_required\", true)" in block


def test_startup_does_not_collapse_service_presence_into_engine_proof():
    service = source("TradingForegroundService.kt")
    boot = source("TradingBootReceiver.kt")
    runtime = source("StandaloneRuntime.kt")
    assert "StandaloneRuntime.autostart(applicationContext)" in service
    assert "RuntimeServiceState.lastError = safeDiagnosticText" in service
    assert "BOOT_SECURE_PREFS_UNREADABLE" in boot
    assert "BOOT_FGS_START_BLOCKED" in boot
    assert 'getBoolean("auto_run", false)' in boot
    assert "reboot_credentials_and_engine" in runtime
    assert "worker_alive=" in runtime
    assert "encrypted_credentials_present=" in runtime


def test_recoverable_missing_daily_risk_data_keeps_supervision_but_blocks_entries():
    runtime = source("StandaloneRuntime.kt")
    assert 'prefs.getBoolean("risk_daily_baseline_auto_paused", false)' in runtime
    assert 'putBoolean("risk_daily_baseline_auto_paused", true)' in runtime
    for reason in ("FUTURES_DAILY_BASELINE_UNKNOWN", "FUTURES_DAILY_INCOME_UNKNOWN",
                   "FUTURES_DAILY_UNREALIZED_PNL_UNKNOWN", "FUTURES_DAILY_PNL_INVALID"):
        assert reason in runtime
    assert "persistSafetyFlag(\"reconcile_required\", true)" in runtime


def test_boot_failure_survives_process_death_without_storing_secrets():
    health = source("AndroidRuntimeHealth.kt")
    receiver = source("TradingBootReceiver.kt")
    service = source("TradingForegroundService.kt")
    runtime = source("StandaloneRuntime.kt")
    activity = source("MainActivity.kt")
    assert 'private const val PREFS = "williams_startup_health"' in health
    assert 'putString("last_failure_code", safeCode)' in health
    assert 'last_boot_startup_failure' in health
    assert "BOOT_SECURE_PREFS_UNREADABLE" in receiver
    assert "RuntimeStartupEvidence.failure" in receiver
    assert "ENGINE_AUTOSTART_FAILED" in service
    assert "RuntimeStartupEvidence.clear(context)" in runtime
    assert "RuntimeStartupEvidence.snapshot(context)" in activity
    assert 'putString("api_key"' not in health
    assert 'putString("api_secret"' not in health


def test_app_variant_identity_is_visible_without_leaking_api_secrets():
    health = source("AndroidRuntimeHealth.kt")
    main = source("MainActivity.kt")
    assert 'put("application_id", context.packageName)' in health
    assert "BuildConfig.VERSION_NAME" in health
    assert "context.packageName" in main
    assert 'putString("api_key"' not in main
    assert 'putString("api_secret"' not in main
    assert "saved_to_encrypted_storage" in main
