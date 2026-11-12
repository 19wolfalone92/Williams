"""Offline source contracts for Williams Bot 4.64.27 safe performance refactor.

Source tests cannot substitute signed Binance Demo execution or Android heap
profiling; they prevent accidental strategy/STOP and dashboard regressions.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent
ANDROID = ROOT / "app/src/main/java/com/williamsbot"
RUNTIME = (ANDROID / "StandaloneRuntime.kt").read_text("utf-8")
UI = (ANDROID / "MainActivity.kt").read_text("utf-8")
RATEGUARD = (ANDROID / "BinanceRateGuard.kt").read_text("utf-8")
PROJECTION = (ANDROID / "CockpitCandidateDisplayProjection.kt").read_text("utf-8")
EVENT_LOOP = (ANDROID / "TradingEventLoop.kt").read_text("utf-8")
COORDINATOR = (ANDROID / "NativeFuturesCampaignCoordinator.kt").read_text("utf-8")


def test_compact_cockpit_is_opt_in_and_does_not_strip_market_decision_trace():
    assert 'x.cockpit(includeDecisionTraces = !params["compact"].equals("true", true))' in RUNTIME
    assert "fun cockpit(includeDecisionTraces: Boolean = true)" in RUNTIME
    assert "status(fast = true, includeDecisionTraces = includeDecisionTraces)" in RUNTIME
    assert "scannerSnapshot(compactForCockpit = !includeDecisionTraces)" in RUNTIME
    assert 'val compact = tab == 0 || tab == 2' in UI
    assert 'if (compact) "/api/v1/cockpit?compact=true"' in UI
    assert "loadAll(false, waitForLock = true)" in UI


def test_compact_projection_keeps_every_field_read_by_android_candidate_parser():
    parser = UI.split("private fun parseCandidates(array: JSONArray)", 1)[1].split(
        "private fun parseTrades", 1
    )[0]
    fields = set(re.findall(r'\bx\.opt(?:Double|Int|String|Boolean|JSONObject|JSONArray)\("([^"]+)"', parser))
    declared = set(re.findall(r'"([^"]+)"', PROJECTION.split("setOf(", 1)[1].split(")", 1)[0]))
    assert len(fields) >= 30
    assert declared == fields, (sorted(fields - declared), sorted(declared - fields))
    assert "if (original.has(field))" in PROJECTION


def test_safety_critical_event_actor_never_drops_events_for_speed():
    assert "LinkedBlockingQueue<Runnable>()" in EVENT_LOOP
    assert "CallerRunsPolicy" not in EVENT_LOOP.split("class TradingEventLoop", 1)[1]
    assert "DiscardOldestPolicy" not in EVENT_LOOP
    assert "executor.execute(task)" in EVENT_LOOP
    assert "tradingEventLoop.queueDepth()" in RUNTIME
    assert 'userStreamSyncRequired = true' in RUNTIME
    assert 'scheduleFuturesStreamReconciliation("PRIVATE_WS_FILL_URGENT", force = true)' in RUNTIME


def test_throttled_indicators_keep_every_closed_bar_sqlite_write_and_generation_guard():
    stream = RUNTIME.split("private fun consumeKlineStream(", 1)[1].split(
        "private fun warmCoreHistoryAsync(", 1
    )[0]
    assert "indicatorGeneration.compute(key)" in stream
    assert "scheduleIndicatorRebuild(key, symbol, frame, list)" in stream
    assert "historyStore.upsertBatch(" in stream
    assert "indicatorExecutor.execute" in stream
    rebuild = RUNTIME.split("private fun scheduleIndicatorRebuild(", 1)[1].split(
        "private fun consumeKlineStream(", 1
    )[0]
    assert "capturedGeneration = indicatorGeneration[key]" in rebuild
    assert "if (indicatorGeneration[key] == capturedGeneration)" in rebuild
    assert "indicatorRebuildPending.remove(key)" in rebuild
    assert "if (indicatorGeneration[key] != capturedGeneration)" in rebuild


def test_tc2_shadow_observer_never_blocks_h1_analysis_or_shares_live_exit_queue():
    scan = RUNTIME.split("private fun performCampaignScan()", 1)[1].split(
        "private fun performScan()", 1
    )[0]
    assert "scannerShadowResearchExecutor.execute" in scan
    assert 'val shadowKey = "SCANNER_SHADOW:" + symbol' in scan
    assert "tc2ExitResearchEnqueued.remove(shadowKey)" in scan
    assert scan.index("scannerShadowResearchExecutor.execute") < scan.index(
        'stage = "ANALYSE_TC2_NTD_COURSE_H1"'
    )
    assert "java.util.concurrent.ArrayBlockingQueue<Runnable>(maxScanSymbols)" in RUNTIME
    assert "tc2ExitResearchExecutor.execute" in RUNTIME


def test_monotonic_binance_cooldown_is_locked_and_overflow_safe():
    assert "internal fun binanceRateGuardCooldownDeadline(" in RATEGUARD
    assert "Long.MAX_VALUE" in RATEGUARD
    assert "synchronized(lock) {" in RATEGUARD
    assert "cooldownUntilMs = max(cooldownUntilMs, deadline)" in RATEGUARD
    assert "binanceRateGuardSaturatingAdd(" in RATEGUARD


def test_financial_stop_post_still_persist_before_network_and_signed_verify_after():
    stop = COORDINATOR[COORDINATOR.index('matchingIntent.put("protection_status", "SUBMISSION_UNKNOWN")'):]
    assert stop.index("saveIntents(persisted)") < stop.index("adapter.placeProtectiveStop(")
    assert stop.index("adapter.placeProtectiveStop(") < stop.index("adapter.openAlgoOrders(symbol)")
    assert "findProtectiveStop(" in stop
