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


def test_add_on_signed_proof_and_safe_rejection_never_poison_parent():
    runtime = RUNTIME.read_text(encoding="utf-8")
    addition = runtime.split("private fun submitNativeFuturesAddition(", 1)[1]
    addition = addition.split("private fun refreshSignedProofForEntryIfNeeded(", 1)[0]
    assert 'refreshSignedProofForEntryIfNeeded(symbol,"ADD_ON_PREFLIGHT")' in addition
    assert addition.index('refreshSignedProofForEntryIfNeeded(symbol,"ADD_ON_PREFLIGHT")') < (
        addition.index('withCampaignMutation(symbol,"WILLIAMS_CAMPAIGN_ADDITION")'))
    guard = addition.index('entrySignedProofHasPreIntentHeadroom(')
    intent = addition.index('futuresCampaignCoordinator.submitPendingEntry(plan)')
    assert guard < intent
    assert '.put("safe_rejection",true)' in addition[guard:intent]
    assert '.put("mutation_attempted",false)' in addition[guard:intent]
    assert '.put("entry_signed_refresh_required",true)' in addition[guard:intent]
    assert 'refreshSignedProofForEntryIfNeeded(symbol,"ADD_ON_AFTER_SAFE_DEFER")' in addition
    # Only accepted add-on exposure can proactively demand reconciliation;
    # a genuinely ambiguous mutation is already fail-closed by the wrapper.
    accepted = addition.index('if (addResult.optBoolean("accepted",false))')
    latch = addition.index('reconcileRequired=true', accepted)
    assert accepted < latch
    assert 'lastRestReconcileMs=0L' in addition[accepted:latch]
    assert addition.count("reconcileRequired=true") == 1
    assert "rejectDecision(symbol,addResult.optString" in addition


def test_normal_add_on_h1_invalidations_are_proven_zero_mutation_not_account_outages():
    runtime = RUNTIME.read_text(encoding="utf-8")
    addition = runtime.split("private fun submitNativeFuturesAddition(", 1)[1]
    addition = addition.split("private fun refreshSignedProofForEntryIfNeeded(", 1)[0]
    assert "fun safeAddOnReadOnlyVeto(reason: String)" in addition
    assert '.put("safe_rejection",true)' in addition
    assert '.put("mutation_attempted",false)' in addition
    for expected in (
        "ADDITION_SOURCE_PROFILE_CHANGED_BEFORE_SUBMISSION",
        "ENTRY_TIME_POLICY_BLOCKED_BEFORE_ADDITION",
        "ADDITION_H1_FORMATION_INVALIDATED_BEFORE_POST",
        "ADDITION_H1_TRIGGER_ALREADY_CONSUMED",
    ):
        assert "safeAddOnReadOnlyVeto(" in addition
        assert expected in addition
    # Unknown daily risk or exchange failures still raise into the durable
    # fail-closed wrapper; do not classify their outcomes as a safe veto.
    assert 'check(checkFuturesDailyLoss())' in addition
    assert "futuresCampaignCoordinator.submitPendingEntry(plan)" in addition
