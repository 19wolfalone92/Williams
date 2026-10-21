"""A partial source audit must never be relabelled as whole-book approval."""
from copy import deepcopy
import json
from pathlib import Path

from tools.audit_book_rule_registry import audit_registry

def registry():
    return json.loads((Path(__file__).resolve().parents[1]/"docs/williams_book_rule_registry.json").read_text())

def test_real_registry_is_linked_but_cannot_certify_unreviewed_books_or_device():
    report=audit_registry(registry())
    assert report["registry_valid"],report["errors"]
    assert not report["whole_book_certified"] and report["blocking_rule_ids"]
    assert not report["can_place_orders"] and not report["tests_executed_by_this_command"]
    forged=deepcopy(registry());forged["whole_book_certified"]=True
    assert "UNSUPPORTED_WHOLE_BOOK_CERTIFICATION" in audit_registry(forged)["errors"]

def test_removed_test_or_book_page_cannot_silently_keep_evidence_status():
    changed=registry();rule=changed["rules"][0]
    rule["tests"][0]="tests/test_williams_book_rules_contract.py::nonexistent_book_fixture"
    rule["pdf_pages"]=[9999]
    report=audit_registry(changed)
    assert not report["registry_valid"]
    assert any(e.startswith("INVALID_SOURCE_PAGE:") for e in report["errors"])
    assert any(e.startswith("MISSING_TEST_FUNCTION:") for e in report["errors"])
