import json
import shutil

import pytest

from tools.audit_tc2_indicator_spec import ROOT, FIXTURE, SPEC_FILE, ALIGNMENT_FILE, audit
from tools.audit_williams_master_research import audit as master_audit


def _copy(tmp_path):
    shutil.copytree(ROOT/'research',tmp_path/'research')
    target=tmp_path/FIXTURE;target.parent.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(ROOT/FIXTURE,target)


def test_numeric_spec_completion_does_not_certify_wm2_lifecycle_or_books():
    report=audit()
    assert report['integrity_valid'],report['errors']
    assert report['fixture_bytes_verified']
    assert report['bounded_numeric_definitions']==2
    assert report['appendix7_page_comparisons']==17
    assert not report['runtime_contract_executed_by_this_audit']
    assert not report['independent_second_review']
    assert not report['entire_book_certified']
    assert not report['can_place_orders']
    master=master_audit()
    assert master['integrity_valid'],master['errors']
    assert master['atomic_specifications']['complete_bounded_definitions']==6
    assert not master['execution_safety']['main_merge_allowed']
    rules=json.loads((ROOT/'research/MASTER_RULE_REGISTRY.json').read_text())['rules']
    wm2=next(r for r in rules if r['RULE_ID']=='TC2.WM2.THREE_AO_BARS')
    assert not wm2['FULL_ATOMIC_SPEC_COMPLETE']


@pytest.mark.parametrize('fault,error',[
    ('SHIFT','ALLIGATOR_ATOMIC_PARAMETERS_INVALID'),
    ('MIDPOINT','AO_ATOMIC_PARAMETERS_INVALID'),
    ('COLOUR','AO_ATOMIC_PARAMETERS_INVALID'),
    ('WARMUP','AO_ATOMIC_PARAMETERS_INVALID'),
    ('LIFECYCLE','AO_ATOMIC_PARAMETERS_INVALID'),
    ('EXPERT','NUMERIC_FALSE_CERTIFICATION_OR_AUTHORITY'),
    ('PIN','NUMERIC_SOURCE_PIN_INVALID'),
    ('REGISTRY','NUMERIC_REGISTRY_SPEC_MISMATCH'),
    ('FIXTURE','NUMERIC_FIXTURE_BYTES_MISMATCH'),
    ('ALIGNMENT','APPENDIX7_PAGE_SET_INVALID'),
    ('BLANK','APPENDIX7_BLANK_WITHOUT_RENDER_OR_FALSE_MATCH'),
])
def test_spec_metadata_cannot_hide_wrong_math_source_or_unsupported_authority(tmp_path,fault,error):
    _copy(tmp_path)
    path=tmp_path/'research'/SPEC_FILE;data=json.loads(path.read_text())
    if fault=='SHIFT':data['specs'][0]['period_shift_pairs']['jaw']=[13,5]
    elif fault=='MIDPOINT':data['specs'][1]['median']='(H-L)/2'
    elif fault=='COLOUR':data['specs'][1]['zero_sign_defines_colour']=True
    elif fault=='WARMUP':data['specs'][1]['first_possible_third_change_index']=37
    elif fault=='LIFECYCLE':data['specs'][1]['wm2_lifecycle_complete']=True
    elif fault=='EXPERT':data['independent_second_review']=True
    elif fault=='PIN':data['source_pages'][0]['acquired_text_sha256']='0'*64
    elif fault=='REGISTRY':
        registry=tmp_path/'research/MASTER_RULE_REGISTRY.json';r=json.loads(registry.read_text())
        next(x for x in r['rules'] if x['RULE_ID']=='TC2.AO.NUMERIC_AND_COLOUR')['execution_authority']=True
        registry.write_text(json.dumps(r))
    elif fault=='FIXTURE':
        with (tmp_path/FIXTURE).open('a') as stream:stream.write(' ')
    elif fault in ('ALIGNMENT','BLANK'):
        alignment=tmp_path/'research'/ALIGNMENT_FILE;a=json.loads(alignment.read_text())
        if fault=='ALIGNMENT':a['pages'].pop()
        else:next(x for x in a['pages'] if x['page_kind']=='VISUALLY_CONFIRMED_BLANK')['english_pages']=[a['pages'][0]['english_pages'][0]]
        alignment.write_text(json.dumps(a))
    path.write_text(json.dumps(data))
    assert error in audit(tmp_path)['errors']
    if fault!='FIXTURE':
        assert not master_audit(tmp_path)['integrity_valid']
