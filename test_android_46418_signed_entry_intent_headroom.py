"""Structural regression: never persist a new TC2 ENTRY intent with an almost-expired
signed Binance account/positions/STOP proof.

Android Kotlin unit tests cover precise time boundaries and the unchanged
90-second transport guard. This check verifies that the helper is actually
wired into the entry order path (not merely defined and tested in isolation).
"""
from pathlib import Path


RUNTIME = Path(__file__).resolve().parent / (
    "app/src/main/java/com/williamsbot/StandaloneRuntime.kt"
)


def test_tc2_final_h1_guard_precedes_new_exchange_intent():
    runtime = RUNTIME.read_text(encoding="utf-8")
    entry = runtime.split("private fun submitNativeFuturesCampaignEntryChecked(", 1)[1]
    entry = entry.split("private fun scanDailyRiskAllowsEntry()", 1)[0]
    guard = entry.index(
        "if (!entrySignedProofHasPreIntentHeadroom("
        "lastRestReconcileMs,System.currentTimeMillis()))"
    )
    submit = entry.index("futuresCampaignCoordinator.submitPendingEntry(plan)")
    assert guard < submit
    gate = entry[guard:submit]
    assert '.put("safe_rejection",true)' in gate
    assert '.put("mutation_attempted",false)' in gate
    assert '.put("order_mutation_attempted",false)' in gate
    assert '.put("entry_signed_refresh_required",true)' in gate
    assert "AFTER_SAFE_DEFER" in entry[submit:]
    assert "scanRetryAfterReconciliation = true" in entry[submit:]


def test_pre_intent_headroom_stricter_than_still_unchanged_transport_ttl():
    runtime = RUNTIME.read_text(encoding="utf-8")
    helper = runtime.split(
        "internal fun entrySignedProofHasPreIntentHeadroom(", 1
    )[1].split("/** Prefer a live WS quote", 1)[0]
    assert "signedFuturesReconciliationFresh(lastSuccessfulAtMs, nowMs, 70_000L)" in helper
    assert "maximumAgeMs: Long = 90_000L" in runtime
