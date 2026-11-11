"""Read-only tests: source canon, no false four-book certification or trade authority.

PDFs stay private. The canonical registry contains page numbers, hashes and
bounded rule/test provenance; no book contents are copied into the APK.
"""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent
REGISTRY = ROOT / "docs" / "williams_book_rule_registry.json"
REASONER = ROOT / "app/src/main/java/com/williamsbot/WilliamsBookCausalUnderstanding.kt"
RUNTIME = ROOT / "app/src/main/java/com/williamsbot/StandaloneRuntime.kt"
ZIP = ROOT / "app/src/main/java/com/williamsbot/DiagnosticsArchive.kt"
UI = ROOT / "app/src/main/java/com/williamsbot/CockpitScreens.kt"


def test_four_original_sources_are_not_confused_with_execution_permissions():
    data = json.loads(REGISTRY.read_text(encoding="utf-8"))
    assert set(data["source_catalog"]) >= {"TC1", "NTD", "TC2", "COURSE"}
    assert data["whole_book_certified"] is False
    assert data["source_review_complete"] is False
    assert data["execution_authority"] is False
    assert data["physical_android_demo_verified"] is False
    ids = [r["id"] for r in data["rules"]]
    assert len(ids) == len(set(ids))
    assert len(ids) >= 60
    allowed = {"BOUNDED_TESTED", "IMPLEMENTED_PARTIAL", "PROJECT_POLICY", "PENDING"}
    for rule in data["rules"]:
        assert rule["status"] in allowed, rule["id"]
        assert rule["can_place_orders_from_registry"] is False, rule["id"]
        assert rule["book"] in data["source_catalog"], rule["id"]
        pages = data["source_catalog"][rule["book"]]["pdf_pages"]
        assert all(type(p) is int and 1 <= p <= pages for p in rule["pdf_pages"]), rule["id"]
        if rule["status"] == "BOUNDED_TESTED":
            assert rule.get("tests"), rule["id"]


def test_reasoner_references_only_canonical_rules_and_has_no_mutation_api():
    registry = json.loads(REGISTRY.read_text(encoding="utf-8"))
    allowed = {r["id"] for r in registry["rules"]}
    source = REASONER.read_text(encoding="utf-8")
    references = set(re.findall(r'Rule\("([^"]+)"', source))
    assert len(references) == registry["runtime_reasoning_audit"]["cited_rule_count"]
    assert references <= allowed, sorted(references - allowed)
    assert 'williamsTc2Profile' in source and 'williamsNtdProfile' in source
    assert 'profile!=williamsTc2Profile -> "OBSERVER_FORMATION_ONLY"' in source
    assert '"senior_trend_reversal_inferred_from_h1_opposite",false' in source
    assert '"knowledge_accuracy_percent_calibrated",false' in source
    assert '"whole_books_understood_and_certified",false' in source
    assert '"actual_exchange_trigger_verified",false' in source
    for unsafe in ("withCampaignMutation(", "futuresMarketData.", "executionStore.",
                   "submitPendingEntry(", "cancelOrder(", "replaceStop("):
        assert unsafe not in source, unsafe


def test_new_book_evidence_export_is_visible_and_not_an_order_gate():
    label = "book_causal_understanding"
    assert label in RUNTIME.read_text(encoding="utf-8")
    assert label + '.json' in ZIP.read_text(encoding="utf-8")
    assert label in UI.read_text(encoding="utf-8")
    src = REASONER.read_text(encoding="utf-8")
    assert 'scanner.optInt("symbols_scanned",-1)==APPROVED_FUTURES_SYMBOLS.size' in src
    assert "nowMs-at<=120_000L" in src
    assert 'val rawSignals=if(validH1) rows(candidate?.optJSONArray("campaign_signals"))' in src
    assert "val signals=rawSignals.take(8)" in src
    assert '"INDEPENDENT_CLOSED_H1_AO_CONTRADICTS_SETUP"' in src
    assert 'independentThirdAoProof(' in src
    assert '"can_change_stop_or_exit",false' in src


def test_actual_live_tc2_fractal_selector_has_new_extended_figure_regression():
    test = (ROOT / "app/src/test/java/com/williamsbot/WilliamsBookRulesContractTest.kt").read_text(encoding="utf-8")
    assert "liveTc2ThirdWiseManSelectorMatchesFiveSixSevenNineClosedBarFigures" in test
    assert "selectWilliamsPendingFractal(side,bars,teeth,.1)" in test
    assert "selectWilliamsPendingFractal(side,bars.dropLast(1)" in test
