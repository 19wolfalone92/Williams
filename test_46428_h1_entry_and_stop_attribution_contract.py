"""Offline static safety contracts for Williams Bot 4.64.28.

These tests cannot prove live Binance STOP execution. They guard causality and
owner-binding against accidental regression during fast Android refactoring.
"""
from pathlib import Path

ROOT=Path(__file__).resolve().parent
ANDROID=ROOT/"app/src/main/java/com/williamsbot"
RUNTIME=(ANDROID/"StandaloneRuntime.kt").read_text("utf-8")
AUDIT=(ANDROID/"TradingAuditStore.kt").read_text("utf-8")
ATTR=(ANDROID/"NativeStopExitAttribution.kt").read_text("utf-8")
UI=(ANDROID/"WilliamsDecisionTraceCard.kt").read_text("utf-8")
HISTORY=(ANDROID/"TradeJournal.kt").read_text("utf-8")


def test_no_symbol_only_exit_intent_attribution():
    completed=RUNTIME.split("private fun journalClosedNativeFuturesTrades()",1)[1].split(
        "private fun manageNativeFuturesPositions()",1
    )[0]
    assert 'it.optString("entry_client_id") == clientId' in completed
    assert '!it.optBoolean("partial_reduction", false)' in completed
    assert 'it.optString("symbol").equals(symbol, true)' in completed
    assert "verifiedNativeStopExitAttribution(" in completed
    assert 'if(stopAttribution.optString("status") == "VERIFIED_OWNED_PROTECTIVE_STOP_FILLED")' in completed
    assert '"EXCHANGE_RECONCILED_CLOSE"' in completed
    assert "exitAttribution = stopAttribution" in completed


def test_exact_proof_requires_both_owned_algo_and_signed_trade_child_id():
    assert 'algo.optString("caid") == stopId' in ATTR
    assert 'algo.optString("ai") == exitOrderId' in ATTR
    assert 'it.optString("buyer").equals(closingIsBuyer.toString(), true)' in ATTR
    assert '"MULTIPLE_SIGNED_EXIT_ORDERS_CANNOT_ATTRIBUTE_SINGLE_STOP"' in ATTR
    assert 'owner.has("native_stop_replacement")' in ATTR
    assert '"VERIFIED_PROTECTIVE_STOP_FILLED"' in ATTR
    assert 'privateEvents.optJSONObject' in ATTR
    assert 'source", "BINANCE_SIGNED_USER_TRADES_AND_PRIVATE_ALGO_UPDATE_EXACT_ORDER_ID"' in ATTR
    assert "futuresCampaignCoordinator.submitPendingEntry" not in ATTR
    assert "placeProtectiveStop" not in ATTR


def test_full_exchange_history_and_close_reason_remain_durable():
    assert 'put("exit_attribution", exitAttribution ?: JSONObject()' in HISTORY
    assert 'put("exit_reason", reason)' in HISTORY
    assert '"SIGNED_CLOSING_ORDER_ID_MISSING"' in ATTR
    assert 'event_type = ? AND event_time >= ? AND event_time <= ?' in AUDIT
    assert 'arrayOf("ALGO_UPDATE"' in AUDIT
    assert "if (cursor.position >= 500) return null" in AUDIT


def test_live_tc2_raw_h1_miss_reason_is_not_a_new_trade_gate():
    scanner=RUNTIME.split('private fun h1ContextCampaignSignals(',1)[1].split(
        'private fun courseTrimPlan(',1
    )[0]
    assert 'val rawCurrent = mutableListOf<CampaignSignalN>()' in scanner
    assert 'rawCurrent.addAll(signals)' in scanner
    assert 'val valid=out.distinctBy' in scanner
    assert 'signal.signalId !in qualifiedIds' in scanner
    assert '"decision_h1_entry_funnel_"+symbol' in scanner
    assert 'no_order_without_execution_door' in scanner
    assert 'can_place_orders",false' in scanner
    assert 'return valid' in scanner
    assert 'trace.put("h1_entry_source_funnel",funnel)' in RUNTIME
    assert 'evidence_scope","RAW_CLOSED_H1_BEFORE_PARENT_LIFECYCLE"' in RUNTIME
    assert 'evidence_scope","QUALIFIED_H1_CONTEXT_AFTER_LIFECYCLE"' in RUNTIME
    assert 'Russian' not in scanner
    assert 'russianH1EntryFunnelReason(' in UI
    assert 'Почему нет нового входа по TC2' in UI


def test_version_is_consistent_and_main_signing_not_touched():
    assert (ROOT/"VERSION").read_text("utf-8").strip()=="4.64.28"
    assert 'versionName = "4.64.28"' in (ROOT/"app/build.gradle.kts").read_text("utf-8")
    assert "VERSION = '4.64.28'" in (ROOT/"server.py").read_text("utf-8")
