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
    conflict_registers=[(sid,json.loads((folder/name).read_text())) for sid,name in [
        ('TC1_RU','TC1_SOURCE_CONFLICT_REGISTER.json'),
        ('NTD_RU','NTD_SOURCE_CONFLICT_REGISTER.json'),
        ('COURSE_EN','COURSE_SOURCE_CONFLICT_REGISTER.json')]]
    course_ocr=json.loads((folder/'COURSE_REVIEW_OCR_PROVENANCE.json').read_text())
    errors=[]
    if sources['COURSE_EN'].get('external_course_media_complete') is not False:
        errors.append('COURSE_EXTERNAL_MATERIAL_FALSE_CERTIFICATION')
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
                ('visual_review','visual_review'),('extracted_rules','rules'),('figures','figures'),
                ('review_text_sha256','review_text_sha256'),('review_text_provenance','review_text_provenance')]):
                errors.append('PAGE_REVIEW_RECEIPT_MISMATCH')
            if p['verification_status']=='REVIEWED_SINGLE_PASS' and p['visual_review'] is not True:
                errors.append('REVIEWED_VISUAL_STAGE_WITHOUT_RENDER_REVIEW')
            if note and 'page_kind' in note and p.get('page_kind')!=note['page_kind']:
                errors.append('PAGE_KIND_REVIEW_RECEIPT_MISMATCH')
        if any(r not in ids for r in p['extracted_rules']):errors.append('PAGE_RULE_ID_UNRESOLVED')
    seen=set()
    for f in figures:
        if f['figure_id'] in seen:errors.append('DUPLICATE_FIGURE')
        seen.add(f['figure_id'])
        if (f['source_id'],f['pdf_page']) not in expected:errors.append('FIGURE_PAGE_UNRESOLVED')
        if f['source_sha256']!=sources[f['source_id']]['sha256']:errors.append('FIGURE_SOURCE_SHA_MISMATCH')
        if f['can_place_orders'] is not False:errors.append('FIGURE_HAS_TRADE_AUTHORITY')
        if f['exact_ohlc_available'] is False and f['qualitative_only'] is not True:errors.append('FALSE_DIGITIZATION_CLAIM')
        receipt=reviews.get(f"{f['source_id']}:{f['pdf_page']}", {})
        if receipt.get('visual_review') is not True or f['figure_label'] not in receipt.get('figures', []):
            errors.append('FIGURE_WITHOUT_MATCHING_VISUAL_RECEIPT')
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
    conflict_ids=set()
    conflicts_per_source={}
    for sid,conflicts in conflict_registers:
        if (conflicts.get('source_id')!=sid
                or conflicts.get('source_sha256')!=sources[sid]['sha256']):
            errors.append('SOURCE_CONFLICT_REGISTER_PIN_MISMATCH')
        if (conflicts.get('can_place_orders') is not False or conflicts.get('training_allowed') is not False
                or conflicts.get('independent_second_review') is not False):
            errors.append('SOURCE_CONFLICT_UNSUPPORTED_AUTHORITY_OR_CERTIFICATION')
        conflicts_per_source[sid]=len(conflicts['conflicts'])
        for conflict in conflicts['conflicts']:
            if conflict['conflict_id'] in conflict_ids:errors.append('DUPLICATE_SOURCE_CONFLICT')
            conflict_ids.add(conflict['conflict_id'])
            if (conflict.get('source_id')!=sid
                    or conflict.get('source_sha256')!=sources[sid]['sha256']):
                errors.append('SOURCE_CONFLICT_PIN_MISMATCH')
            if (conflict.get('execution_authority') is not False or conflict.get('training_allowed') is not False
                    or conflict.get('independent_second_review') is not False):
                errors.append('SOURCE_CONFLICT_UNSUPPORTED_AUTHORITY_OR_CERTIFICATION')
            if not conflict['pdf_pages'] or any((sid,p) not in expected for p in conflict['pdf_pages']):
                errors.append('SOURCE_CONFLICT_PAGE_OUT_OF_RANGE')
            for p in conflict['visual_evidence_pages']:
                receipt=reviews.get(f'{sid}:{p}',{})
                if p not in conflict['pdf_pages'] or receipt.get('visual_review') is not True:
                    errors.append('SOURCE_CONFLICT_WITHOUT_VISUAL_RECEIPT')
    if (course_ocr.get('schema')!='COURSE_DERIVED_OCR_20261113_V1'
            or course_ocr.get('source_id')!='COURSE_EN'
            or course_ocr.get('source_sha256')!=sources['COURSE_EN']['sha256']):
        errors.append('COURSE_REVIEW_OCR_SOURCE_OR_SCHEMA_MISMATCH')
    if (any(course_ocr.get(k) is not False for k in ['whole_content_certified',
            'independent_second_review','can_place_orders','training_allowed','raw_ocr_published'])
            or course_ocr.get('new_ocr_is_not_original_acquisition') is not True
            or course_ocr.get('original_acquisition_hashes_preserved') is not True):
        errors.append('COURSE_REVIEW_OCR_UNSUPPORTED_CERTIFICATION_OR_PROVENANCE')
    ocr_pages=course_ocr['pages']
    ocr_keys=[p['pdf_page'] for p in ocr_pages]
    if (len(ocr_keys)!=len(set(ocr_keys)) or any(type(p) is not int for p in ocr_keys)
            or set(ocr_keys)!=set(range(1,sources['COURSE_EN']['pdf_pages']+1))):
        errors.append('COURSE_REVIEW_OCR_PAGE_SET_INVALID')
    page_by_key={(p['source_id'],p['pdf_page']):p for p in pages}
    for entry in ocr_pages:
        key=f"COURSE_EN:{entry['pdf_page']}"
        receipt=reviews.get(key,{})
        page=page_by_key.get(('COURSE_EN',entry['pdf_page']),{})
        digest=entry.get('derived_ocr_sha256','')
        if not re.fullmatch('[0-9a-f]{64}',digest):errors.append('COURSE_REVIEW_OCR_INVALID_HASH')
        if (page.get('review_text_sha256')!=digest or receipt.get('review_text_sha256')!=digest
                or page.get('review_text_provenance')!=course_ocr['schema']
                or receipt.get('review_text_provenance')!=course_ocr['schema']):
            errors.append('COURSE_REVIEW_OCR_RECEIPT_MISMATCH')
        if entry.get('original_acquired_text_sha256')!=page.get('acquired_text_sha256'):
            errors.append('COURSE_REVIEW_OCR_ORIGINAL_ACQUISITION_MISMATCH')
        expected_method='OCR_PLUS_RENDER_FIRST_PASS' if receipt.get('visual_review') else 'OCR_TEXT_FIRST_PASS'
        if (entry.get('review_method')!=expected_method
                or type(entry.get('derived_ocr_characters')) is not int
                or entry['derived_ocr_characters']<0
                or (entry['derived_ocr_characters']==0 and receipt.get('visual_review') is not True)):
            errors.append('COURSE_REVIEW_OCR_UNSUPPORTED_REVIEW_METHOD')
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
      'source_conflicts':{'visible_single_review_entries':len(conflict_ids),
        'per_source':conflicts_per_source,
        'independent_second_reviews':0,'unseen_original_english_errata_certified':False},
      'rule_extraction':{'registered':len(ids),'rechecked_against_sources_this_run':sum(r['source_reviewed_this_run'] for r in registry['rules']),
        'total_rules_in_books':None,'percent':None},
      'rule_implementation_coverage':None,'rule_test_coverage_of_all_books':None,
      'canonical_consistency':{'confirmed_numeric_defect_classes_fixed':6,'whole_book_certified':False},
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
