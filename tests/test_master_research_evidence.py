import json
import shutil
from pathlib import Path

from tools.audit_williams_master_research import ROOT, audit


def test_inventory_complete_does_not_claim_books_complete():
    report=audit()
    assert report['integrity_valid'], report['errors']
    assert report['sources']['physical_pages']==1213
    assert report['sources']['pdf_files']==6
    assert report['source_coverage']['independently_verified']==0
    assert report['figure_coverage']['percent'] is None
    assert not report['canonical_consistency']['whole_book_certified']
    assert report['rule_extraction']['percent'] is None
    assert not report['execution_safety']['main_merge_allowed']


def test_english_first_pass_does_not_certify_other_books_or_independent_review():
    coverage=audit()['source_coverage']
    english=coverage['per_source']['TC2_EN']
    assert english['all_physical_pages_have_first_pass_receipts']
    assert english['first_pass_receipts']==257
    assert english['independently_verified']==0
    assert not english['full_content_certified']
    assert english['classified_page_kinds']['VISUALLY_CONFIRMED_BLANK']==12
    assert not coverage['per_source']['TC2_RU_224']['all_physical_pages_have_first_pass_receipts']


def test_tc1_complete_text_pass_keeps_original_identity_and_execution_gaps():
    tc1=audit()['source_coverage']['per_source']['TC1_RU']
    assert tc1['first_pass_receipts']==140
    assert tc1['all_physical_pages_have_first_pass_receipts']
    assert tc1['visual_receipts']<140
    assert tc1['independently_verified']==0
    catalog=json.loads((ROOT/'research/SOURCE_CATALOG.json').read_text())
    source=next(s for s in catalog if s['id']=='TC1_RU')
    assert source['role']=='RESEARCH_ONLY'
    assert not source['publication_identity_complete']
    assert not source['full_content_review_complete']


def test_ntd_complete_text_pass_is_not_full_visual_or_independent_certification():
    report=audit()
    ntd=report['source_coverage']['per_source']['NTD_RU']
    assert ntd['first_pass_receipts']==156
    assert ntd['all_physical_pages_have_first_pass_receipts']
    assert ntd['visual_receipts']<156
    assert ntd['classified_page_kinds']['VISUALLY_CONFIRMED_BLANK']==1
    assert not ntd['full_content_certified']
    assert ntd['independently_verified']==0
    assert report['source_conflicts']['per_source']['NTD_RU']==15
    rules=json.loads((ROOT/'research/MASTER_RULE_REGISTRY.json').read_text())['rules']
    assert all(not r['FULL_ATOMIC_SPEC_COMPLETE'] for r in rules if r['SOURCE_ID']=='NTD_RU')


def test_course_scan_first_pass_does_not_certify_external_tutorial_or_all_diagrams():
    course=audit()['source_coverage']['per_source']['COURSE_EN']
    assert course['first_pass_receipts']==122
    assert course['all_physical_pages_have_first_pass_receipts']
    assert 0<course['visual_receipts']<122
    assert not course['full_content_certified']
    source=next(s for s in json.loads((ROOT/'research/SOURCE_CATALOG.json').read_text()) if s['id']=='COURSE_EN')
    assert not source['external_course_media_complete']
    assert not source['full_content_review_complete']
    reviews=json.loads((ROOT/'research/review_notes.json').read_text())
    assert reviews['COURSE_EN:56']['page_kind']=='GRAPHIC_DOMINANT'
    assert reviews['COURSE_EN:56']['visual_review']
    assert reviews['COURSE_EN:122']['page_kind']=='EXTERNAL_MATERIAL_NOTICE'


def test_new_ocr_hash_cannot_replace_the_original_acquisition_identity(tmp_path):
    folder=copy_receipts(tmp_path)
    path=folder/'COURSE_REVIEW_OCR_PROVENANCE.json'
    data=json.loads(path.read_text())
    entry=next(e for e in data['pages'] if e['original_acquired_text_sha256']!=e['derived_ocr_sha256'])
    entry['original_acquired_text_sha256']=entry['derived_ocr_sha256']
    path.write_text(json.dumps(data))
    assert 'COURSE_REVIEW_OCR_ORIGINAL_ACQUISITION_MISMATCH' in audit(tmp_path)['errors']


def test_course_ocr_receipt_and_page_set_cannot_be_fabricated(tmp_path):
    folder=copy_receipts(tmp_path)
    path=folder/'COURSE_REVIEW_OCR_PROVENANCE.json'
    data=json.loads(path.read_text())
    data['pages'][0]['derived_ocr_sha256']='0'*64
    data['pages'][-1]=data['pages'][0].copy()
    data['whole_content_certified']=True
    path.write_text(json.dumps(data))
    errors=audit(tmp_path)['errors']
    assert 'COURSE_REVIEW_OCR_RECEIPT_MISMATCH' in errors
    assert 'COURSE_REVIEW_OCR_PAGE_SET_INVALID' in errors
    assert 'COURSE_REVIEW_OCR_UNSUPPORTED_CERTIFICATION_OR_PROVENANCE' in errors


def test_empty_ocr_cannot_be_counted_as_read_without_visual_evidence(tmp_path):
    folder=copy_receipts(tmp_path)
    path=folder/'COURSE_REVIEW_OCR_PROVENANCE.json'
    data=json.loads(path.read_text())
    blank_text=next(p for p in data['pages'] if p['pdf_page']==56)
    assert blank_text['derived_ocr_characters']==0
    blank_text['review_method']='OCR_TEXT_FIRST_PASS'
    path.write_text(json.dumps(data))
    note_path=folder/'review_notes.json'
    notes=json.loads(note_path.read_text())
    notes['COURSE_EN:56'].update(visual_review=False,verification_status='REVIEWED_TEXT_ONLY')
    note_path.write_text(json.dumps(notes))
    page_path=folder/'PRIMARY_SOURCE_PAGE_REGISTER.jsonl'
    pages=[json.loads(line) for line in page_path.read_text().splitlines()]
    next(p for p in pages if p['source_id']=='COURSE_EN' and p['pdf_page']==56).update(
        visual_review=False,verification_status='REVIEWED_TEXT_ONLY')
    page_path.write_text(''.join(json.dumps(p)+'\n' for p in pages))
    assert 'COURSE_REVIEW_OCR_UNSUPPORTED_REVIEW_METHOD' in audit(tmp_path)['errors']


def test_missing_external_course_media_cannot_be_certified_by_reading_the_scan(tmp_path):
    folder=copy_receipts(tmp_path)
    path=folder/'SOURCE_CATALOG.json'
    data=json.loads(path.read_text())
    next(s for s in data if s['id']=='COURSE_EN')['external_course_media_complete']=True
    path.write_text(json.dumps(data))
    assert 'COURSE_EXTERNAL_MATERIAL_FALSE_CERTIFICATION' in audit(tmp_path)['errors']


def copy_receipts(tmp_path):
    shutil.copytree(ROOT/'research',tmp_path/'research')
    return tmp_path/'research'


def test_forged_page_verification_fails_even_if_all_pages_are_present(tmp_path):
    folder=copy_receipts(tmp_path)
    path=folder/'PRIMARY_SOURCE_PAGE_REGISTER.jsonl'
    rows=[json.loads(l) for l in path.read_text().splitlines()]
    rows[0]['verification_status']='VERIFIED'
    path.write_text(''.join(json.dumps(r)+'\n' for r in rows))
    assert 'UNSUPPORTED_VERIFIED_PAGE' in audit(tmp_path)['errors']


def test_a_missing_page_cannot_be_hidden_by_duplicate_row(tmp_path):
    folder=copy_receipts(tmp_path)
    path=folder/'PRIMARY_SOURCE_PAGE_REGISTER.jsonl'
    lines=path.read_text().splitlines();lines[-1]=lines[0]
    path.write_text('\n'.join(lines)+'\n')
    assert 'PAGE_SET_MISSING_DUPLICATE_OR_OUT_OF_RANGE' in audit(tmp_path)['errors']


def test_imported_knowledge_cannot_gain_order_or_training_authority(tmp_path):
    folder=copy_receipts(tmp_path)
    path=folder/'MASTER_RULE_REGISTRY.json'
    payload=json.loads(path.read_text());payload['rules'][0]['execution_authority']=True
    path.write_text(json.dumps(payload))
    assert 'RULE_HAS_UNAPPROVED_AUTHORITY' in audit(tmp_path)['errors']


def test_reviewed_stage_requires_the_matching_semantic_receipt(tmp_path):
    folder=copy_receipts(tmp_path)
    path=folder/'PRIMARY_SOURCE_PAGE_REGISTER.jsonl'
    rows=[json.loads(l) for l in path.read_text().splitlines()]
    row=next(r for r in rows if r['verification_status']=='TEXT_ACQUIRED_UNVERIFIED')
    row['verification_status']='REVIEWED_SINGLE_PASS';row['visual_review']=False
    path.write_text(''.join(json.dumps(r)+'\n' for r in rows))
    assert 'PAGE_REVIEW_RECEIPT_MISMATCH' in audit(tmp_path)['errors']
    assert 'REVIEWED_VISUAL_STAGE_WITHOUT_RENDER_REVIEW' in audit(tmp_path)['errors']


def test_translation_map_cannot_refer_to_a_different_pdf_edition(tmp_path):
    folder=copy_receipts(tmp_path);path=folder/'TC2_TRANSLATION_PAGE_MAP.json'
    payload=json.loads(path.read_text());payload['anchors'][0]['pages'][0]['source_sha256']='0'*64
    path.write_text(json.dumps(payload))
    assert 'CROSSWALK_SOURCE_SHA_MISMATCH' in audit(tmp_path)['errors']


def test_reinventoried_blank_or_navigation_kind_must_match_the_manual_receipt(tmp_path):
    folder=copy_receipts(tmp_path)
    path=folder/'PRIMARY_SOURCE_PAGE_REGISTER.jsonl'
    rows=[json.loads(line) for line in path.read_text().splitlines()]
    blank=next(row for row in rows if row.get('page_kind')=='VISUALLY_CONFIRMED_BLANK')
    blank.pop('page_kind')
    path.write_text(''.join(json.dumps(row)+'\n' for row in rows))
    assert 'PAGE_KIND_REVIEW_RECEIPT_MISMATCH' in audit(tmp_path)['errors']


def test_new_figure_cannot_claim_visual_interpretation_of_an_unseen_page(tmp_path):
    folder=copy_receipts(tmp_path)
    path=folder/'FIGURE_INTERPRETATION_REGISTER.jsonl'
    rows=[json.loads(line) for line in path.read_text().splitlines()]
    figure=next(f for f in rows if f['source_id']=='TC1_RU')
    figure['pdf_page']=1
    path.write_text(''.join(json.dumps(row)+'\n' for row in rows))
    assert 'FIGURE_WITHOUT_MATCHING_VISUAL_RECEIPT' in audit(tmp_path)['errors']


def test_source_disagreement_receipt_is_not_independent_certification(tmp_path):
    folder=copy_receipts(tmp_path)
    path=folder/'TC1_SOURCE_CONFLICT_REGISTER.json'
    payload=json.loads(path.read_text())
    payload['conflicts'][0]['source_sha256']='0'*64
    payload['conflicts'][0]['independent_second_review']=True
    path.write_text(json.dumps(payload))
    errors=audit(tmp_path)['errors']
    assert 'SOURCE_CONFLICT_PIN_MISMATCH' in errors
    assert 'SOURCE_CONFLICT_UNSUPPORTED_AUTHORITY_OR_CERTIFICATION' in errors


def test_ntd_conflict_cannot_be_relabelled_as_another_book_or_unseen_page(tmp_path):
    folder=copy_receipts(tmp_path)
    path=folder/'NTD_SOURCE_CONFLICT_REGISTER.json'
    payload=json.loads(path.read_text())
    conflict=payload['conflicts'][0]
    conflict['source_id']='TC2_EN'
    conflict['source_sha256']='0'*64
    conflict['visual_evidence_pages']=[90]
    path.write_text(json.dumps(payload))
    errors=audit(tmp_path)['errors']
    assert 'SOURCE_CONFLICT_PIN_MISMATCH' in errors
    assert 'SOURCE_CONFLICT_WITHOUT_VISUAL_RECEIPT' in errors


def test_ntd_source_conflict_cannot_acquire_trade_or_training_authority(tmp_path):
    folder=copy_receipts(tmp_path)
    path=folder/'NTD_SOURCE_CONFLICT_REGISTER.json'
    payload=json.loads(path.read_text())
    payload['conflicts'][0]['execution_authority']=True
    payload['training_allowed']=True
    path.write_text(json.dumps(payload))
    assert 'SOURCE_CONFLICT_UNSUPPORTED_AUTHORITY_OR_CERTIFICATION' in audit(tmp_path)['errors']
