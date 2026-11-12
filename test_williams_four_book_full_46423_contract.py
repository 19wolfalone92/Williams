"""Exact four-source 642-page catalogue and trading-authority firewall.

This suite deliberately does NOT transform page indexing or source rule counts
into a pseudo-accuracy percentage. Real visual figure exams and Demo receipts
remain outside the current evidence.
"""
import json
import re
from pathlib import Path

ROOT=Path(__file__).resolve().parent
MAP=json.loads((ROOT/"docs/williams_four_book_full_section_inventory_46423.json").read_text(encoding="utf-8"))
REG=json.loads((ROOT/"docs/williams_book_rule_registry.json").read_text(encoding="utf-8"))
RUNTIME=(ROOT/"app/src/main/java/com/williamsbot/StandaloneRuntime.kt").read_text(encoding="utf-8")
SRC=(ROOT/"app/src/main/java/com/williamsbot/WilliamsFourBookCurriculum.kt").read_text(encoding="utf-8")
ZIP=(ROOT/"app/src/main/java/com/williamsbot/DiagnosticsArchive.kt").read_text(encoding="utf-8")
UI=(ROOT/"app/src/main/java/com/williamsbot/CockpitScreens.kt").read_text(encoding="utf-8")
CAUSAL=(ROOT/"app/src/main/java/com/williamsbot/WilliamsBookCausalUnderstanding.kt").read_text(encoding="utf-8")

def test_all_642_pages_are_mapped_once_to_one_of_four_original_source_sections():
    assert MAP["total_pages"]==642
    assert MAP["source_pages"]=={"TC1":140,"NTD":156,"TC2":224,"COURSE":122}
    sections=MAP["sections"]
    assert len(sections)==57
    for book,count in MAP["source_pages"].items():
        s=sorted((x for x in sections if x["book"]==book),
                 key=lambda x:x["pdf_start_page"])
        observed=[]
        for chapter in s:
            assert chapter["independent_rule_exam_passed"] is False
            assert chapter["complete_figure_validation"] is False
            assert chapter["execution_authority_from_syllabus"] is False
            observed.extend(range(chapter["pdf_start_page"],chapter["pdf_end_page"]+1))
        assert observed==list(range(1,count+1))
    assert sum(x["pdf_end_page"]-x["pdf_start_page"]+1 for x in sections)==642

def test_inventory_is_grounded_to_canonical_registry_without_ghost_rules():
    ids={r["id"] for r in REG["rules"]}
    linked={rule for x in MAP["sections"] for rule in x["registry_rule_ids"]}
    assert len(REG["rules"])>=67
    assert linked<=ids
    assert {r["id"] for r in REG["rules"] if r["book"]!="WAVE_MEMO"}<=linked
    assert MAP["unlinked_registry_rules"]==[]
    assert REG["whole_book_certified"] is False
    assert REG["source_review_complete"] is False
    assert REG["all_historical_figures_digitized"] is False
    assert REG["physical_android_demo_verified"] is False
    assert REG["full_book_chapter_inventory"]["source_total_physical_pdf_pages"]==642
    assert REG["full_book_chapter_inventory"]["section_count"]==57
    assert REG["full_book_chapter_inventory"]["sections_independently_visual_exam_passed"]==0

def test_kotlin_corpus_is_exactly_synchronized_with_referenced_section_json():
    tags=re.findall(r'Section\("([^"]+)","([^"]+)",(\d+),(\d+),"([^"]+)"',SRC)
    got={(book,id_,int(first),int(last),kind) for book,id_,first,last,kind in tags}
    expected={(x["book"],x["section_id"],x["pdf_start_page"],x["pdf_end_page"],x["kind"])
              for x in MAP["sections"]}
    assert got==expected
    for book in MAP["source_pages"]:
        assert f'"{book}" to {MAP["source_pages"][book]}' in SRC
    assert 'check(allIndexed==642 && chapters.size==57)' in SRC

def test_book_study_cannot_auto_certify_99_percent_or_place_a_stop():
    assert MAP["whole_book_source_completeness_proven"] is False
    assert MAP["knowledge_accuracy_99_percent_proven"] is False
    assert MAP["scanned_course_full_ocr_verified"] is False
    assert '"book_understanding_99_percent_proven",false' in SRC
    assert '"whole_book_understood_or_certified",false' in SRC
    assert '"can_modify_tc2_execution",false' in SRC
    assert '"trading_authority",false' in SRC
    assert '"course_level_1_reuses_same_volume_not_independent_confirmation",true' in CAUSAL
    assert '"TC1_HISTORICAL"' in CAUSAL
    for forbidden in ("submitPendingEntry(", "trailProtectiveStop(", "cancelOrder(",
                      "withCampaignMutation(", "futuresCampaignCoordinator."):
        assert forbidden not in SRC,forbidden

def test_all_book_chapter_records_reach_real_phone_diagnostics_and_zip():
    key="four_book_full_chapter_coverage"
    assert key in RUNTIME
    assert key+".json" in ZIP
    assert key in UI
    assert 'WilliamsFourBookCurriculum.assess(' in RUNTIME
    assert '"COURSE:122","SOURCE_MISSING"' in SRC
    assert 'TRADING' not in MAP.get("claimed_accuracy","")

def test_course_level_five_is_an_external_source_not_fabricated_from_scans():
    course=[x for x in MAP["sections"] if x["book"]=="COURSE"]
    assert course[-1]["section_id"]=="COURSE.LEVEL5B"
    assert course[-1]["pdf_start_page"]==122
    assert course[-1]["kind"]=="EXTERNAL_SOURCE_MISSING"
    assert REG["full_book_chapter_inventory"]["course_external_personal_tutorial_available"] is False
    assert "SOURCE.COURSE_EXTERNAL_TUTORIAL_MISSING" in {r["id"] for r in REG["rules"]}
