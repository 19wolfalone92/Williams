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


def test_cockpit_avoids_giant_string_and_scopes_details_to_selected_symbol():
    # The old 15-symbol String route caused a confirmed Android OOM.
    # The UI now uses a direct JSONObject envelope; diagnostics retain full history.
    assert 'x.cockpit(includeDecisionTraces = false).toString()' in RUNTIME
    assert 'x.status(fast = fast, includeDecisionTraces = !fast).toString()' in RUNTIME
    assert "fun cockpit(" in RUNTIME
    assert "includeDecisionTraces: Boolean = true" in RUNTIME
    assert "includeTradeHistory: Boolean = true" in RUNTIME
    assert "selectedTraceSymbols = scope" in RUNTIME
    # Even detailed Market/Analysis tabs need only the candidate scalar keys;
    # the selected symbol's full trace is in STATUS, not the 15-pair scanner list.
    assert "scannerSnapshot(compactForCockpit = true)" in RUNTIME
    assert 'val requestedDetails = tab == 1 || tab == 3' in UI
    assert 'api.cockpitSnapshot(includeDecisionTraces = requestedDetails,' in UI
    assert 'StandaloneRuntime.cockpitSnapshot(appContext, includeDecisionTraces,' in UI
    assert "loadAll(false, waitForLock = true)" in UI
    assert "val decoded = withContext(Dispatchers.Default)" in UI
    assert 'api.post("/api/v1/scanner/refresh")' in UI
    assert 'if (includeTradeHistory) trades() else JSONArray()' in RUNTIME


def test_compact_projection_keeps_every_field_read_by_android_candidate_parser():
    parser = UI.split("private fun parseCandidates(array: JSONArray)", 1)[1].split(
        "private fun parseTrades", 1
    )[0]
    fields = set(re.findall(r'\bx\.opt(?:Double|Int|String|Boolean|JSONObject|JSONArray)\("([^"]+)"', parser))
    declared = set(re.findall(r'"([^"]+)"', PROJECTION.split("setOf(", 1)[1].split(")", 1)[0]))
    assert len(fields) >= 30
    assert declared == fields, (sorted(fields - declared), sorted(declared - fields))
    assert "if (original.has(field))" in PROJECTION


def test_safety_critical_event_actor_uses_durable_spool_before_bounded_ram_queue():
    # As of 4.64.37, the private-stream inbox is SQLite-durable BEFORE
    # scheduling. An unbounded actor queue is now an OOM hazard; the actor
    # must explicitly reject overflow/shutdown, while the runtime retains
    # signed-reconcile entry blocking and the pending durable receipt.
    assert "ArrayBlockingQueue<Runnable>(maxQueuedTasks)" in EVENT_LOOP
    assert "executor.execute(task)" in EVENT_LOOP
    assert "if (!executor.isShutdown)" not in EVENT_LOOP
    assert "CallerRunsPolicy" not in EVENT_LOOP.split("class TradingEventLoop", 1)[1]
    assert "DiscardOldestPolicy" not in EVENT_LOOP
    producer = RUNTIME.split("private fun handleUserEvent(event: JSONObject)", 1)[1].split(
        "private fun schedulePrivateEventDrain()", 1
    )[0]
    assert producer.index("auditStore.enqueuePrivateUserEvent(event, accountFingerprint)") < producer.index(
        "schedulePrivateEventDrain()"
    )
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


def test_depth_observer_has_one_inflight_resync_per_symbol_and_no_order_milestone():
    fn = RUNTIME.split("private fun consumeDepthUpdate(data: JSONObject)", 1)[1].split(
        "private fun scheduleIndicatorRebuild(", 1
    )[0]
    assert "depthResyncInFlight.add(symbol)" in fn
    assert "depthResyncInFlight.remove(symbol)" in fn
    assert "finally {" in fn
    assert "syncOrderBookSnapshot(symbol)" in fn
    assert "placeProtectiveStop" not in fn
    assert 'depth_resync_inflight_symbols' in RUNTIME


def test_monotonic_binance_cooldown_is_locked_and_overflow_safe():
    assert "internal fun binanceRateGuardCooldownDeadline(" in RATEGUARD
    assert "Long.MAX_VALUE" in RATEGUARD
    assert "synchronized(lock) {" in RATEGUARD
    assert "cooldownUntilMs = max(cooldownUntilMs, deadline)" in RATEGUARD
    assert "if (value < 0L) return@forEach" in RATEGUARD
    assert "if (newLimit <= 0L) continue" in RATEGUARD
    assert "binanceRateGuardSaturatingAdd(" in RATEGUARD


def test_financial_stop_post_still_persist_before_network_and_signed_verify_after():
    # There is also an earlier SUBMISSION_UNKNOWN recovery branch; scope to
    # the precise durable-write-before-network protective POST site.
    marker = ('matchingIntent.put("protection_status", "SUBMISSION_UNKNOWN")'
              '\n                            .put("protection_attempt_at_ms", clock())')
    stop = COORDINATOR[COORDINATOR.index(marker):]
    assert stop.index("saveIntents(persisted)") < stop.index("adapter.placeProtectiveStop(")
    assert stop.index("adapter.placeProtectiveStop(") < stop.index(
        "val refreshed = adapter.openAlgoOrders(symbol)"
    )
    assert "findProtectiveStop(" in stop
