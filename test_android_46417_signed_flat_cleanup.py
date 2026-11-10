"""Fail-closed contract for 4.64.17 signed historical STOP preflight.

The account-wide signed algo-order GET may suppress redundant per-symbol
negative lookups ONLY when the exact prior STOP ID is absent. A fresh signed
account-wide sweep after ALL archival cleanup is mandatory in every case;
unknown POST/DELETE state must never be reclassified as a clean account.
"""
from pathlib import Path

SRC=(Path(__file__).resolve().parent/"app/src/main/java/com/williamsbot/NativeFuturesCampaignCoordinator.kt").read_text(encoding="utf-8")


def test_negative_preflight_uses_actual_signed_algo_response_not_cached_ui_or_local_ledger():
    code=SRC.split('noteReconciliationPhase("SETTLING_HISTORICAL_FLAT_CAMPAIGNS")',1)[1].split(
        'noteReconciliationPhase("COURSE_FILL_AND_FLAT_HANDOFF_REPLAY")',1)[0]
    assert "historicalFlatAlgoNeedsSymbolRefresh(" in code
    assert "allOpenAlgoOrders, symbol, knownProtectionIds" in code
    assert "if (requiresFreshSymbolQuery &&" in code
    assert "adapter.openAlgoOrders(symbol)" in code
    assert "historicalFlatMustRefresh.add(symbol)" in code
    assert "historicalFlatSymbolQueried.remove(symbol)" in code


def test_preflight_never_waives_identity_or_exchange_position_checks_before_cancel():
    code=SRC.split('noteReconciliationPhase("SETTLING_HISTORICAL_FLAT_CAMPAIGNS")',1)[1].split(
        'noteReconciliationPhase("COURSE_FILL_AND_FLAT_HANDOFF_REPLAY")',1)[0]
    # The next signed lookup is authoritative before any DELETE.
    assert code.index("adapter.openAlgoOrders(symbol)") < code.index("adapter.cancelAlgoOrderById(symbol, algoId)")
    assert "val freshPositions = validateFuturesPositionSnapshot(adapter.positions())" in code
    assert "expectedStop != null && actualStop?.compareTo(expectedStop) == 0" in code
    assert 'order.optString("side") ==' in code
    assert 'order.optString("workingType") == "MARK_PRICE"' in code
    assert 'isActiveFuturesProtectionStatus(order.optString("algoStatus"))' in code
    assert "correctCoverage" in code


def test_original_post_cleanup_signed_account_wide_verification_is_still_mandatory():
    code=SRC.split('noteReconciliationPhase("SETTLING_HISTORICAL_FLAT_CAMPAIGNS")',1)[1].split(
        'noteReconciliationPhase("COURSE_FILL_AND_FLAT_HANDOFF_REPLAY")',1)[0]
    assert "val finalAlgos = adapter.allOpenAlgoOrders()" in code
    assert 'historical STOP still open after signed cleanup; new entry blocked' in code
    assert code.index("val finalAlgos = adapter.allOpenAlgoOrders()") > code.index(
        "adapter.cancelAlgoOrderById(symbol, algoId)")
    assert code.index("val finalAlgos = adapter.allOpenAlgoOrders()") < code.index(
        "saveIntents(persisted)") if code.count("saveIntents(persisted)")==1 else True


def test_helper_defaults_to_extra_signed_get_on_ambiguous_or_invalid_preflight():
    src=SRC.split("internal fun historicalFlatAlgoNeedsSymbolRefresh(",1)[1].split(
        "/** Missing/unknown algo status",1)[0]
    assert "storedProtectionIds.isEmpty()" in src
    assert "signedAccountAlgos.optJSONObject(index) ?: return true" in src
    assert 'seenSymbol.isBlank() || seenId.isBlank()' in src
    assert 'seenId in storedProtectionIds' in src
