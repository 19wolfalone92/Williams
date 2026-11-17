"""Regression contracts for the 4.64.14 Samsung diagnostic audit.

These are narrowly scoped source-integration guards in addition to the Android
unit tests for actual Binance exchange-clock and monthly candle boundaries.
No network or Binance credentials are needed.
"""
from pathlib import Path

ROOT = Path(__file__).parent
RT = ROOT / "app/src/main/java/com/williamsbot/StandaloneRuntime.kt"
PROJECTION = ROOT / "app/src/main/java/com/williamsbot/WilliamsDiagnosticConfiguration.kt"


def test_strategy_audit_uses_authoritative_exchange_utc_not_stale_ui_clock():
    source = RT.read_text(encoding="utf-8")
    block = source.split("private fun strategyDiagnosticEvidence(): JSONArray", 1)[1].split(
        "private fun scheduleReadOnlySignedFillAudit()", 1)[0]
    assert "runCatching { futuresMarketData.exchangeNowMs() }.getOrNull()" in block
    assert "val clockFresh = verifiedNow != null &&" in block
    assert "closedNativeStrategyCandles(source)" in block
    assert 'EXCHANGE_CLOCK_STALE:' in block
    assert '"NOT_VERIFIED"' in block
    assert "private fun strategyDiagnosticEvidence()" in source


def test_complete_history_skips_redundant_kline_get_until_next_exchange_bar_closed():
    source = RT.read_text(encoding="utf-8")
    function = source.split("private fun fetchFullHistory(", 1)[1].split(
        "@Synchronized\n    private fun loadUniverse()", 1)[0]
    assert "if (!historyStore.isComplete(normalizedSymbol, frame))" in function
    complete = function.split("} else {", 1)[1]
    assertion = "if (futuresMarketData.isLastClosedCandleCurrent(newest, frame, nowForTail))"
    assert "val nowForTail = futuresMarketData.exchangeNowMs()" in complete
    assert assertion in complete
    assert complete.index(assertion) < complete.index("for (page in 0 until pageLimit)")
    assert "historyStore.loadRecent(normalizedSymbol, frame, 150)" in complete
    # Incomplete history is still backward-paginated, never marked complete by
    # a local cache freshness heuristic.
    assert "futuresMarketData.klines(" in function.split("} else {",1)[0]


def test_full_trace_is_available_once_in_status_not_built_twice_for_zip():
    source = RT.read_text(encoding="utf-8")
    diagnostic = source.split("fun diagnostics(): JSONObject", 1)[1].split(
        "\n    fun ", 1)[0]
    assert "val configurationSnapshot=diagnosticSettings()" in diagnostic
    assert '.put("status", statusSnapshot)' in diagnostic
    assert '.put("settings", configurationSnapshot)' in diagnostic
    assert "private fun diagnosticSettings(): JSONObject = buildSettings(includeDecisionTraces = false)" in source
    assert "fun settings(): JSONObject = buildSettings(includeDecisionTraces = true)" in source
    assert '.apply { if (includeDecisionTraces) put("decision_traces",decisionTraceSnapshot()) }' in source
    # Public status retains full traces, but forensic status must not eagerly
    # build them just to discard them before ZIP export.
    assert "fun status(fast: Boolean = false, includeDecisionTraces: Boolean = true)" in source
    assert "status(fast = true, includeDecisionTraces = false)" in diagnostic
    assert 'check(!statusSnapshot.has("decision_traces"))' in diagnostic
    assert '.put("signal_decision_history",decisionTraceSnapshot(fullHistory=true))' in diagnostic
    # An omitted tree MUST NOT make provenance disappear or claim execution.
    projection = PROJECTION.read_text(encoding="utf-8")
    assert '"status.decision_traces"' in projection
    assert '"decision_traces"' in projection
    assert '"execution_allowed", true' not in projection
