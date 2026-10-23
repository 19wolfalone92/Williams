"""Check every supplied source page separately from software/test coverage.

Acquisition and visual overview never mean verified words or digitized figures.
This command checks evidence links; it does not execute tests or grant orders.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
STAGES={"TEXT_ACQUIRED","VISUAL_OVERVIEW","RULES_REVIEWED","VERIFIED"}


def audit_page_coverage(catalog, ledger, root=ROOT, source_dir=None):
    errors=[]
    books=ledger.get("books",{})
    totals=Counter()
    pending={}
    source_hashes_verified=source_dir is not None
    for name,source in catalog.items():
        book=books.get(name,{})
        if book.get("sha256")!=source.get("sha256"):
            errors.append("SOURCE_HASH_MISMATCH:"+name)
        if source_dir is not None:
            file=Path(source_dir)/source["filename"]
            if not file.is_file() or hashlib.sha256(file.read_bytes()).hexdigest()!=source["sha256"]:
                errors.append("SOURCE_BYTES_UNVERIFIED:"+name)
                source_hashes_verified=False
        pages=book.get("pages",[])
        numbers=[p.get("pdf_page") for p in pages]
        expected=set(range(1,source["pdf_pages"]+1))
        if len(numbers)!=len(expected) or len(set(numbers))!=len(numbers) or set(numbers)!=expected:
            errors.append("MISSING_DUPLICATE_OR_OUT_OF_RANGE_PAGE:"+name)
        pending[name]=[]
        for page in pages:
            number=page.get("pdf_page")
            stage=page.get("review_stage")
            totals["pages"]+=1
            totals[stage]+=1
            if stage not in STAGES:
                errors.append(f"INVALID_REVIEW_STAGE:{name}:{number}")
            if page.get("can_place_orders") is not False:
                errors.append(f"PAGE_CANNOT_AUTHORIZE_TRADES:{name}:{number}")
            if not isinstance(page.get("acquired_text_sha256"),str) or len(page["acquired_text_sha256"])!=64:
                errors.append(f"ACQUISITION_HASH_MISSING:{name}:{number}")
            verified=stage=="VERIFIED" and page.get("words_verified") is True and page.get("figures_verified") is True
            if verified:
                # A verified trading page needs actual chart/case linkage;
                # labels or a top-level boolean cannot manufacture coverage.
                refs=page.get("evidence_refs",[])
                if not refs or not page.get("review_notes"):
                    errors.append(f"VERIFICATION_EVIDENCE_MISSING:{name}:{number}")
                for ref in refs:
                    path=(root/ref).resolve()
                    if not path.is_relative_to(root.resolve()) or not path.is_file():
                        errors.append(f"PAGE_EVIDENCE_LINK_MISSING:{name}:{number}")
                if page.get("contains_trading_figure") is not False and not page.get("independent_figure_cases"):
                    errors.append(f"INDEPENDENT_FIGURE_CASES_MISSING:{name}:{number}")
            else:
                pending[name].append(number)
    if set(books)!=set(catalog):
        errors.append("SOURCE_CATALOG_SCOPE_MISMATCH")
    complete=not errors and all(not p for p in pending.values()) and not ledger.get("missing_source_material")
    if ledger.get("source_review_complete") is True and not complete:
        errors.append("UNSUPPORTED_SOURCE_REVIEW_CERTIFICATION")
    return {"page_ledger_valid":not errors,"source_review_complete":complete,
        "required_pages":sum(s["pdf_pages"] for s in catalog.values()),"page_counts":dict(totals),
        "source_hashes_verified_against_bytes":source_hashes_verified,
        "pending_pages":pending,"missing_source_material":ledger.get("missing_source_material",[]),
        "tests_executed_by_this_command":False,"can_place_orders":False,"errors":errors}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ledger",type=Path,default=ROOT/'docs/williams_source_page_coverage.json')
    parser.add_argument("--source-dir",type=Path)
    parser.add_argument("--require-complete",action="store_true")
    args=parser.parse_args()
    registry=json.loads((ROOT/'docs/williams_book_rule_registry.json').read_text())
    result=audit_page_coverage(registry['source_catalog'],json.loads(args.ledger.read_text()),source_dir=args.source_dir)
    print(json.dumps(result,ensure_ascii=False,indent=2))
    return 1 if not result['page_ledger_valid'] else 2 if args.require_complete and not result['source_review_complete'] else 0


if __name__=='__main__':
    raise SystemExit(main())
