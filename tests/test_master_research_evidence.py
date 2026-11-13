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
