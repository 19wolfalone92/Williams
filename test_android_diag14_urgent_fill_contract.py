"""Static cross-layer guard for diagnostic #14 urgent fill vs signed STOP authority."""
from pathlib import Path

ROOT = Path(__file__).resolve().parent
RUNTIME = (ROOT / "app/src/main/java/com/williamsbot/StandaloneRuntime.kt").read_text(encoding="utf-8")
COORDINATOR = (ROOT / "app/src/main/java/com/williamsbot/NativeFuturesCampaignCoordinator.kt").read_text(encoding="utf-8")
RESEARCH = (ROOT / "tc2_time_machine_research.py").read_text(encoding="utf-8")


def test_private_ws_fill_wakes_reconciliation_before_serial_event_loop():
    start = RUNTIME.index("private fun handleUserEvent(event: JSONObject)")
    end = RUNTIME.index("private fun handleUserEventSerialized(event: JSONObject)", start)
    source = RUNTIME[start:end]
    assert 'userStreamSyncRequired = true' in source
    assert 'privateFuturesFillDemandsImmediateReconcile(event)' in source
    assert 'scheduleFuturesStreamReconciliation("PRIVATE_WS_FILL_URGENT", force = true)' in source
    assert source.index("scheduleFuturesStreamReconciliation") < source.index("tradingEventLoop.post")
    assert 'handleUserEventSerialized(event)' in source


def test_stop_post_is_still_guarded_by_durable_signed_coordinator():
    assert 'matchingIntent.put("protection_status", "SUBMISSION_UNKNOWN")' in COORDINATOR
    assert 'saveIntents(persisted)' in COORDINATOR
    assert "adapter.placeProtectiveStop(" in COORDINATOR
    assert "adapter.openAlgoOrders(symbol)" in COORDINATOR
    assert 'matchingIntent.put("campaign_status", "OPEN_PROTECTED")' in COORDINATOR
    assert "privateFuturesFillDemandsImmediateReconcile" not in COORDINATOR


def test_time_machine_cannot_place_orders_or_call_network():
    assert "from binance" not in RESEARCH.lower()
    assert "import requests" not in RESEARCH
    assert "adapter.place" not in RESEARCH
    assert "execution_barrier" not in RESEARCH.lower().replace("no production execution imports", "")
    assert 'READ_ONLY_STRESS_NOT_ENTRY_OR_EXIT_AUTHORITY' in RESEARCH
    assert '"exchange_orders_placed": 0' in RESEARCH
