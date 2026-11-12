"""CI contract for historical TC2 exit comparison, no future data and no order authority."""
from pathlib import Path
import json

ROOT=Path(__file__).resolve().parent
LENS=(ROOT/"app/src/main/java/com/williamsbot/WilliamsTc2ExitHierarchyLens.kt").read_text()
RUNTIME=(ROOT/"app/src/main/java/com/williamsbot/StandaloneRuntime.kt").read_text()
ZIP=(ROOT/"app/src/main/java/com/williamsbot/DiagnosticsArchive.kt").read_text()
UI=(ROOT/"app/src/main/java/com/williamsbot/CockpitScreens.kt").read_text()
REG=json.loads((ROOT/"docs/williams_book_rule_registry.json").read_text())

def test_no_historical_stop_hypothesis_can_mutate_binance():
    assert '"can_move_stop_or_override_tc2_exit",false' in LENS
    assert '"trading_authority",false' in LENS
    assert '"book_exit_outcome_optimality_verified",false' in LENS
    assert '"price_extremes_are_executable_stops",false' in LENS
    for dangerous in ("futuresCampaignCoordinator", "submitOrder(", "cancelOrder(",
                      "replaceProtectiveStop(", "trailingAtMs =", "futuresMarketData.",
                      "getOrCreateExecutionSession(", "executionStore.put"):
        assert dangerous not in LENS, dangerous

def test_original_h1_cutoff_and_four_bar_alternative_are_auditable():
    assert "it.start+WILLIAMS_H1_MS<=closed" in LENS
    assert "it.start>=first && it.start<=last" in LENS
    assert "if(opened%WILLIAMS_H1_MS==0L)" in LENS
    assert "b.start-a.start!=WILLIAMS_H1_MS" in LENS
    for w in (3,4,5):
        assert f'boundary({w})' in LENS
    assert '"could_have_earned_more_by_holding_proven",false' in LENS

def test_exposes_result_in_zip_and_diagnostic_ui_without_creating_new_orders():
    key="tc2_book_exit_hierarchy"
    assert key in RUNTIME
    assert key+".json" in ZIP
    assert key in UI
    assert 'read_only' not in LENS or '"trading_authority",false' in LENS

def test_original_book_rule_registry_is_still_uncertified():
    assert len(REG["rules"]) == 63
    assert REG["whole_book_certified"] is False
    assert REG["historical_exit_book_lens"]["actual_signed_stop_equivalence_claimed"] is False
    assert REG["historical_exit_book_lens"]["whole_book_fidelity_99_percent_proven"] is False
    for rule_id in ("TC2.STOP.THREE_OR_FIVE","TC2.EXIT.STOP_AND_REVERSE",
                    "TC2.STOP.FOUR_BAR_ALTERNATIVE"):
        r=next(row for row in REG["rules"] if row["id"]==rule_id)
        assert r["can_place_orders_from_registry"] is False
        assert "WilliamsTc2ExitHierarchyLens.kt" in " ".join(r["modules"])
