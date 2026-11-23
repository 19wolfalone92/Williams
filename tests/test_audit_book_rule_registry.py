"""Machine-readable source-index registry is NOT a claim of expert-grade book certification."""
from __future__ import annotations

import json
from collections import Counter
from pathlib import Path

REGISTRY = Path(__file__).resolve().parents[1] / "audits/2026-11-23/book_rules_registry.json"
PAGES = {"TC1": 140, "NTD": 156, "TC2": 224, "COURSE": 122}
SCHEMA_FIELDS = {
    "rule_id", "book", "chapter", "pdf_page", "printed_page", "figure_id",
    "rule_definition", "preconditions", "confirmation", "invalidation",
    "known_at", "allowed_action", "code_locations", "test_ids",
    "reference_chart_ids", "verification_status", "engineering_extensions",
    "unresolved_ambiguities",
}
STATES = {"BOOK_VERIFIED", "PARTIAL", "CONFLICT", "ENGINEERING_EXTENSION", "NOT_VERIFIED"}


def test_registry_covers_real_642_page_57_section_manifest_without_inventing_figure_grades():
    catalog = json.loads(REGISTRY.read_text(encoding="utf-8"))
    assert catalog["book_pages"] == PAGES
    assert catalog["total_pages"] == sum(PAGES.values()) == 642
    assert len(catalog["sections"]) == catalog["total_section_records"] == 57
    assert len(catalog["rules"]) == catalog["total_section_rule_links"] == 92
    assert all(row["chapter_comprehension_status"] == "NOT_VERIFIED"
               and row["figure_review_status"] == "NOT_VERIFIED"
               for row in catalog["sections"])
    for book, last in PAGES.items():
        rows = sorted((s for s in catalog["sections"] if s["book"] == book),
                      key=lambda x: x["pdf_start_page"])
        cursor = 1
        for row in rows:
            assert row["pdf_start_page"] == cursor
            assert row["pdf_end_page"] >= row["pdf_start_page"]
            cursor = row["pdf_end_page"] + 1
        assert cursor == last + 1


def test_every_registry_rule_has_source_section_and_no_false_acceptance():
    catalog = json.loads(REGISTRY.read_text(encoding="utf-8"))
    sections = {(s["book"], s["section_id"]): s for s in catalog["sections"]}
    for rule in catalog["rules"]:
        assert SCHEMA_FIELDS.issubset(rule)
        assert rule["verification_status"] in STATES
        section = sections[rule["book"], rule["chapter"]]
        assert rule["rule_id"] in section["rule_ids"]
        assert rule["verification_status"] != "BOOK_VERIFIED"
        if rule["pdf_page"] is not None:
            assert section["pdf_start_page"] <= rule["pdf_page"] <= section["pdf_end_page"]
        if rule["verification_status"] == "ENGINEERING_EXTENSION":
            assert rule["rule_id"].startswith("ENGINEERING.")
        if rule["verification_status"] == "PARTIAL":
            assert rule["evidence_proven"] is False
            assert rule["code_locations"] and rule["test_ids"]
    assert Counter(r["verification_status"] for r in catalog["rules"]) == {
        "NOT_VERIFIED": 87, "PARTIAL": 2, "ENGINEERING_EXTENSION": 3
    }
