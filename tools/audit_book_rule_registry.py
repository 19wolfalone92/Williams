"""Validate source/test links; never mistake selected fixtures for full books.

This command does NOT execute tests or authorize exchange operations. Default
exit0 means registry integrity only; --require-complete exits2 for open scope.
"""
from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import re
try:
    from tools.audit_source_page_coverage import audit_page_coverage
except ModuleNotFoundError:
    from audit_source_page_coverage import audit_page_coverage

ROOT=Path(__file__).resolve().parents[1]
STATUSES={"BOUNDED_TESTED","IMPLEMENTED_PARTIAL","PENDING","SOURCE_CONFLICT",
          "PROJECT_POLICY","USER_POLICY","SOURCE_VERIFIED"}


def audit_registry(registry,root=ROOT):
    errors=[]
    sources=registry.get("source_catalog",{})
    for name,source in sources.items():
        if not re.fullmatch(r"[0-9a-f]{64}",source.get("sha256","")) or source.get("pdf_pages",0)<=0:
            errors.append("INVALID_SOURCE_CATALOG:"+name)
    coverage_path=(root/registry.get("page_coverage_file","")).resolve()
    try:
        if not coverage_path.is_relative_to(root.resolve()):
            raise ValueError("PAGE_COVERAGE_OUTSIDE_REPOSITORY")
        coverage=audit_page_coverage(sources,json.loads(coverage_path.read_text()),root)
        errors.extend("PAGE_COVERAGE:"+e for e in coverage["errors"])
    except (OSError,ValueError):
        coverage={"source_review_complete":False,"required_pages":0}
        errors.append("MISSING_OR_INVALID_PAGE_COVERAGE")
    fixture_path=root/registry.get("fixture_file","")
    try:
        fixtures=json.loads(fixture_path.read_text())
    except (OSError,ValueError):
        fixtures={};errors.append("MISSING_OR_INVALID_SHARED_FIXTURES")
    case_ids={f"{section}/{case['id']}" for section,items in fixtures.items() if isinstance(items,list)
              for case in items if isinstance(case,dict) and "id" in case}
    seen=set()
    rules=registry.get("rules",[])
    if not rules:errors.append("EMPTY_RULE_SCOPE")
    for rule in rules:
        rid=rule.get("id","")
        if not rid or rid in seen:errors.append("DUPLICATE_OR_MISSING_RULE_ID:"+rid)
        seen.add(rid)
        status=rule.get("status")
        if status not in STATUSES:errors.append("INVALID_RULE_STATUS:"+rid)
        source=sources.get(rule.get("book"))
        pages=rule.get("pdf_pages",[])
        if not source or not pages or any(not isinstance(p,int) or p<1 or p>source["pdf_pages"] for p in pages):
            errors.append("INVALID_SOURCE_PAGE:"+rid)
        if rule.get("can_place_orders_from_registry") is not False:
            errors.append("REGISTRY_CANNOT_AUTHORIZE_ORDERS:"+rid)
        for rel in rule.get("modules",[]):
            path=(root/rel).resolve()
            if not path.is_relative_to(root.resolve()) or not path.is_file():
                errors.append("MISSING_MODULE:"+rid+":"+rel)
        refs=rule.get("tests",[])
        if status in {"BOUNDED_TESTED","SOURCE_VERIFIED"} and not (
            any(ref.split("::")[0].endswith(".py") for ref in refs) and
            any(ref.split("::")[0].endswith(".kt") for ref in refs)):
            errors.append("PYTHON_KOTLIN_EVIDENCE_REQUIRED:"+rid)
        for ref in refs:
            rel,sep,name=ref.partition("::");path=(root/rel).resolve()
            if not sep or not path.is_relative_to(root.resolve()) or not path.is_file():
                errors.append("INVALID_TEST_LINK:"+rid+":"+ref);continue
            if not re.search(r"\b(?:def|fun)\s+"+re.escape(name)+r"\s*\(",path.read_text()):
                errors.append("MISSING_TEST_FUNCTION:"+rid+":"+ref)
        for ref in rule.get("fixtures",[]):
            available=case_ids
            if rule.get("fixture_file"):
                try:
                    extra=(root/rule["fixture_file"]).resolve()
                    if not extra.is_relative_to(root.resolve()):raise ValueError("INVALID_FIXTURE_PATH")
                    payload=json.loads(extra.read_text())
                    available={f"{section}/{c['id']}" for section,items in payload.items() if isinstance(items,list)
                        for c in items if isinstance(c,dict) and "id" in c}
                except (OSError,ValueError):
                    available=set()
            if ref not in available:errors.append("MISSING_FIXTURE:"+rid+":"+ref)
    blockers=[r["id"] for r in rules if r.get("status") not in {"SOURCE_VERIFIED","PROJECT_POLICY"}]
    source_scope_complete=coverage["source_review_complete"] and all(registry.get(key) is True for key in (
        "source_review_complete","all_historical_figures_digitized","physical_android_demo_verified"))
    certified=bool(source_scope_complete and not blockers and not errors and
                   registry.get("scope")=="COMPLETE_REVIEWED_BOOK_SCOPE")
    if registry.get("whole_book_certified") is True and not certified:
        errors.append("UNSUPPORTED_WHOLE_BOOK_CERTIFICATION")
    return {"registry_valid":not errors,"whole_book_certified":certified,
            "scope":registry.get("scope"),"rule_count":len(rules),
            "status_counts":dict(sorted(Counter(r.get("status") for r in rules).items())),
            "source_scope_complete":source_scope_complete,"blocking_rule_ids":blockers,
            "page_review_complete":coverage["source_review_complete"],"required_source_pages":coverage["required_pages"],
            "tests_executed_by_this_command":False,"can_place_orders":False,"errors":errors}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--registry",type=Path,default=ROOT/"docs/williams_book_rule_registry.json")
    parser.add_argument("--require-complete",action="store_true")
    args=parser.parse_args()
    report=audit_registry(json.loads(args.registry.read_text()))
    print(json.dumps(report,ensure_ascii=False,indent=2))
    return 1 if not report["registry_valid"] else 2 if args.require_complete and not report["whole_book_certified"] else 0


if __name__=="__main__":raise SystemExit(main())
