"""Validate master evidence integrity without equating acquisition with review."""
from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import re

ROOT=Path(__file__).resolve().parents[1]
STAGES={'IMAGE_ONLY_PENDING_OCR','OCR_ACQUIRED_UNVERIFIED','TEXT_ACQUIRED_UNVERIFIED',
        'REVIEWED_TEXT_ONLY','REVIEWED_SINGLE_PASS','VERIFIED'}


def audit(root=ROOT):
    folder=root/'research'
    catalog=json.loads((folder/'SOURCE_CATALOG.json').read_text())
    sources={s['id']:s for s in catalog}
    pages=[json.loads(line) for line in (folder/'PRIMARY_SOURCE_PAGE_REGISTER.jsonl').read_text().splitlines()]
    figures=[json.loads(line) for line in (folder/'FIGURE_INTERPRETATION_REGISTER.jsonl').read_text().splitlines()]
    registry=json.loads((folder/'MASTER_RULE_REGISTRY.json').read_text())
    reviews=json.loads((folder/'review_notes.json').read_text())
    crosswalk=json.loads((folder/'TC2_TRANSLATION_PAGE_MAP.json').read_text())
    errors=[]
    keys=[(p['source_id'],p['pdf_page']) for p in pages]
    expected={(s['id'],n) for s in catalog for n in range(1,s['pdf_pages']+1)}
    if len(keys)!=len(set(keys)) or set(keys)!=expected:
        errors.append('PAGE_SET_MISSING_DUPLICATE_OR_OUT_OF_RANGE')
    ids=[r['RULE_ID'] for r in registry['rules']]
    if len(ids)!=len(set(ids)):errors.append('DUPLICATE_RULE_ID')
    for p in pages:
        source=sources[p['source_id']]
        if p['source_sha256']!=source['sha256']:errors.append('PAGE_SOURCE_SHA_MISMATCH')
        if p['verification_status'] not in STAGES:errors.append('UNKNOWN_PAGE_STAGE')
        if not re.fullmatch('[0-9a-f]{64}',p['acquired_text_sha256']):errors.append('INVALID_ACQUISITION_HASH')
        if p['can_place_orders'] is not False:errors.append('PAGE_HAS_TRADE_AUTHORITY')
        if p['verification_status']=='VERIFIED' and not (p['visual_review'] and p['independent_second_review']):
            errors.append('UNSUPPORTED_VERIFIED_PAGE')
        if p['verification_status'].startswith('REVIEWED'):
            note=reviews.get(f"{p['source_id']}:{p['pdf_page']}")
            if not note or any(p.get(a)!=note.get(b) for a,b in [
                ('content','content'),('verification_status','verification_status'),
                ('visual_review','visual_review'),('extracted_rules','rules'),('figures','figures')]):
                errors.append('PAGE_REVIEW_RECEIPT_MISMATCH')
            if p['verification_status']=='REVIEWED_SINGLE_PASS' and p['visual_review'] is not True:
                errors.append('REVIEWED_VISUAL_STAGE_WITHOUT_RENDER_REVIEW')
        if any(r not in ids for r in p['extracted_rules']):errors.append('PAGE_RULE_ID_UNRESOLVED')
    seen=set()
    for f in figures:
        if f['figure_id'] in seen:errors.append('DUPLICATE_FIGURE')
        seen.add(f['figure_id'])
        if (f['source_id'],f['pdf_page']) not in expected:errors.append('FIGURE_PAGE_UNRESOLVED')
        if f['source_sha256']!=sources[f['source_id']]['sha256']:errors.append('FIGURE_SOURCE_SHA_MISMATCH')
        if f['can_place_orders'] is not False:errors.append('FIGURE_HAS_TRADE_AUTHORITY')
        if f['exact_ohlc_available'] is False and f['qualitative_only'] is not True:errors.append('FALSE_DIGITIZATION_CLAIM')
    for r in registry['rules']:
        if r['execution_authority'] is not False or r['training_allowed'] is not False:
            errors.append('RULE_HAS_UNAPPROVED_AUTHORITY')
        if r['SOURCE_ID']:
            source=sources.get(r['SOURCE_ID'])
            if not source or r['SOURCE_SHA256']!=source['sha256']:errors.append('RULE_SOURCE_MISMATCH')
            if any(type(n) is not int or n<1 or n>source['pdf_pages'] for n in r['PAGE']):
                errors.append('RULE_PAGE_OUT_OF_RANGE')
    if crosswalk.get('can_place_orders') is not False or crosswalk.get('independent_second_review') is not False:
        errors.append('CROSSWALK_UNSUPPORTED_AUTHORITY_OR_CERTIFICATION')
    anchors=set()
    for anchor in crosswalk['anchors']:
        if anchor['anchor_id'] in anchors:errors.append('DUPLICATE_TRANSLATION_ANCHOR')
        anchors.add(anchor['anchor_id'])
        if {p['source_id'] for p in anchor['pages']}!={'TC2_EN','TC2_RU_224','TC2_RU_314'}:
            errors.append('CROSSWALK_MISSING_EDITION')
        for span in anchor['pages']:
            source=sources.get(span['source_id'])
            if not source or span['source_sha256']!=source['sha256']:
                errors.append('CROSSWALK_SOURCE_SHA_MISMATCH')
            if not span['pdf_pages'] or any((span['source_id'],p) not in expected for p in span['pdf_pages']):
                errors.append('CROSSWALK_PAGE_OUT_OF_RANGE')
    certified=sum(p['verification_status']=='VERIFIED' for p in pages)
    reviewed=sum(p['verification_status'].startswith('REVIEWED') for p in pages)
    counts=Counter(p['verification_status'] for p in pages)
    per_source={}
    for sid,source in sources.items():
        group=[p for p in pages if p['source_id']==sid]
        first_pass=[p for p in group if p['verification_status'].startswith('REVIEWED')]
        per_source[sid]={
            'physical_pages':source['pdf_pages'],
            'first_pass_receipts':len(first_pass),
            'visual_receipts':sum(p['visual_review'] is True for p in first_pass),
            'independently_verified':sum(p['verification_status']=='VERIFIED' for p in group),
            'all_physical_pages_have_first_pass_receipts':len(first_pass)==source['pdf_pages'],
            'classified_page_kinds':dict(Counter(p.get('page_kind','PREVIOUS_RECEIPT_UNCLASSIFIED') for p in first_pass)),
            'full_content_certified':False,
        }
    return {'schema':'WILLIAMS_MASTER_PROGRESS_V1','integrity_valid':not errors,'errors':sorted(set(errors)),
      'status':'IN_PROGRESS','sources':{'families':len({s['family'] for s in catalog}),
        'pdf_files':len(sources),'physical_pages':len(pages),'inventory_rows':len(pages)},
      'source_coverage':{'semantically_reviewed_single_pass':reviewed,'independently_verified':certified,
        'total_physical_pages':len(pages),'stage_counts':dict(counts),
        'inventory_is_not_full_reading':True,
        'physical_receipts_include_front_matter_blank_and_navigation_pages':True,
        'per_source':per_source},
      'figure_coverage':{'qualitative_single_review_entries':len(figures),
        'independent_second_reviews':0,'total_substantive_figures':None,'percent':None},
      'rule_extraction':{'registered':len(ids),'rechecked_against_sources_this_run':sum(r['source_reviewed_this_run'] for r in registry['rules']),
        'total_rules_in_books':None,'percent':None},
      'rule_implementation_coverage':None,'rule_test_coverage_of_all_books':None,
      'canonical_consistency':{'confirmed_numeric_defect_classes_fixed':4,'whole_book_certified':False},
      'execution_safety':{'signed_demo_e2e':False,'physical_android_e2e':False,'main_merge_allowed':False},
      'decision_explainability_coverage':None,'can_place_orders':False}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path)
    parser.add_argument('--require-complete',action='store_true')
    args=parser.parse_args()
    report=audit()
    data=json.dumps(report,ensure_ascii=False,indent=2)+'\n'
    if args.output:
        args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(data)
    print(data,end='')
    return 1 if not report['integrity_valid'] else 2 if args.require_complete else 0


if __name__=='__main__':raise SystemExit(main())
