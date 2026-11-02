import json
from pathlib import Path

import pytest

from tools.report_four_book_95_progress import evaluate, ROOT


def test_bounded_is_not_book_fidelity_certification():
    rows=[{"id":f"TC2.{i}","book":"TC2",
           "status":"BOUNDED_TESTED" if i<19 else "IMPLEMENTED_PARTIAL"}
          for i in range(20)]
    payload={"rules":rows,
             "whole_book_certified":False,
             "all_historical_figures_digitized":False,
             "physical_android_demo_verified":False}
    proof=evaluate(payload)
    assert proof["bounded_evidence_percent"]==95.0
    assert proof["target_met"] is True
    assert proof["book_fidelity_95_percent_certified"] is False
    assert proof["whole_book_certified"] is False
    assert proof["physical_android_demo_verified"] is False


def test_project_policies_are_never_counted_as_book_rule_hits():
    rows=[{"id":"a","book":"TC2","status":"BOUNDED_TESTED"},
          {"id":"b","book":"TC1","status":"PENDING"},
          {"id":"c","book":"TC2","status":"PROJECT_POLICY"},
          {"id":"d","book":"WAVE_MEMO","status":"BOUNDED_TESTED"}]
    proof=evaluate({"rules":rows})
    assert proof["scope_selected_rules"]==2
    assert proof["bounded_evidence_percent"]==50
    assert proof["remaining_rules_until_bounded_evidence_target"]==1
    assert proof["blocking_selected_rules"][0]["id"]=="b"


def test_repository_report_is_never_marked_certified_on_open_sources():
    book=json.loads((ROOT/"docs/williams_book_rule_registry.json").read_text())
    report=evaluate(book)
    assert report["scope_selected_rules"] >= 46
    assert report["source_verified_rule_count"] <= report["bounded_evidence_rules"]
    assert not report["book_fidelity_95_percent_certified"]
    if not book["physical_android_demo_verified"]:
        assert not report["physical_android_demo_verified"]


def test_invalid_scope_or_target_refused():
    with pytest.raises(ValueError):evaluate({"rules":[]})
    with pytest.raises(ValueError):evaluate({"rules":[{"id":"x","book":"TC2"}]},target=0)
    with pytest.raises(ValueError):evaluate({"rules":[
        {"id":"same","book":"TC1"},{"id":"same","book":"TC2"}
    ]})
