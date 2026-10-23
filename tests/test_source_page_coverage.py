from copy import deepcopy
import json
from pathlib import Path

from tools.audit_source_page_coverage import audit_page_coverage

ROOT=Path(__file__).resolve().parents[1]


def load():
    catalog=json.loads((ROOT/'docs/williams_book_rule_registry.json').read_text())['source_catalog']
    ledger=json.loads((ROOT/'docs/williams_source_page_coverage.json').read_text())
    return catalog,ledger


def test_all_648_pages_are_inventoried_without_confusing_ocr_with_verification():
    catalog,ledger=load()
    result=audit_page_coverage(catalog,ledger)
    assert result['page_ledger_valid'],result['errors']
    assert result['required_pages']==648 and result['page_counts']['pages']==648
    assert not result['source_review_complete']
    assert not result['source_hashes_verified_against_bytes']
    assert not result['can_place_orders']
    assert result['pending_pages']['COURSE']==list(range(1,123))
    assert result['missing_source_material']


def test_overview_or_an_empty_ocr_page_cannot_be_promoted_to_word_and_figure_proof():
    catalog,ledger=load()
    forged=deepcopy(ledger)
    forged['source_review_complete']=True
    assert 'UNSUPPORTED_SOURCE_REVIEW_CERTIFICATION' in audit_page_coverage(catalog,forged)['errors']
    page=forged['books']['COURSE']['pages'][55]
    page.update(review_stage='VERIFIED',words_verified=True,figures_verified=True)
    assert any('VERIFICATION_EVIDENCE_MISSING:COURSE:56' in e
               for e in audit_page_coverage(catalog,forged)['errors'])


def test_missing_duplicate_page_or_substituted_source_hash_fails_inventory():
    catalog,ledger=load()
    ledger['books']['COURSE']['pages'][1]=deepcopy(ledger['books']['COURSE']['pages'][0])
    ledger['books']['TC1']['sha256']='0'*64
    errors=audit_page_coverage(catalog,ledger)['errors']
    assert 'MISSING_DUPLICATE_OR_OUT_OF_RANGE_PAGE:COURSE' in errors
    assert 'SOURCE_HASH_MISMATCH:TC1' in errors
