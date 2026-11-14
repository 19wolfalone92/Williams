"""Validate the bounded numeric specification and Appendix7 source provenance.

This is a metadata/fixture integrity check, not execution of the runtime tests
or an independent certification of books, charts, trading or platform code.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC_FILE = 'TC2_INDICATOR_ATOMIC_SPEC_20261114.json'
ALIGNMENT_FILE = 'TC2_APPENDIX7_CODE_ALIGNMENT_20261114.json'
FIXTURE = 'app/src/test/resources/tc2_indicator_atomic_contract.json'
IDS = {'TC2.ALLIGATOR.MEDIAN_SMMA_DISPLAY','TC2.AO.NUMERIC_AND_COLOUR'}
FALSE_FLAGS = ('independent_second_review','can_place_orders','training_allowed')


def audit(root: Path = ROOT, *, check_fixture: bool = True) -> dict:
    folder = root/'research'
    sources = {s['id']:s for s in json.loads((folder/'SOURCE_CATALOG.json').read_text())}
    pages = {(p['source_id'],p['pdf_page']):p for p in
             map(json.loads,(folder/'PRIMARY_SOURCE_PAGE_REGISTER.jsonl').read_text().splitlines())}
    notes = json.loads((folder/'review_notes.json').read_text())
    registry = {r['RULE_ID']:r for r in json.loads((folder/'MASTER_RULE_REGISTRY.json').read_text())['rules']}
    spec = json.loads((folder/SPEC_FILE).read_text())
    alignment = json.loads((folder/ALIGNMENT_FILE).read_text())
    errors = []

    def pin(entry):
        sid,n = entry.get('source_id'),entry.get('pdf_page')
        source = sources.get(sid,{})
        row = pages.get((sid,n),{}) if type(n) is int else {}
        note = notes.get(f'{sid}:{n}',{})
        if (not row or entry.get('source_sha256') != source.get('sha256')
                or entry.get('acquired_text_sha256') != row.get('acquired_text_sha256')):
            errors.append('NUMERIC_SOURCE_PIN_INVALID')
        if (not row.get('verification_status','').startswith('REVIEWED')
                or not note.get('verification_status','').startswith('REVIEWED')):
            errors.append('NUMERIC_SOURCE_WITHOUT_READING_RECEIPT')

    if (spec.get('schema') != 'TC2_NUMERIC_ATOMIC_SPEC_V1'
            or spec.get('scope') != 'TWO_NUMERIC_TRANSFORMS_NOT_ENTIRE_BOOK_OR_WM2_LIFECYCLE'
            or any(spec.get(k) is not False for k in FALSE_FLAGS+(
                'expert_gold','entire_book_certified','source_chart_ohlc_recovered',
                'printed_easylanguage_compiler_verified'))):
        errors.append('NUMERIC_FALSE_CERTIFICATION_OR_AUTHORITY')
    definitions = {s.get('rule_id'):s for s in spec.get('specs',[])}
    if len(spec.get('specs',[])) != 2 or set(definitions) != IDS:
        errors.append('NUMERIC_ATOMIC_RULE_SET_INVALID')
    alligator = definitions.get('TC2.ALLIGATOR.MEDIAN_SMMA_DISPLAY',{})
    ao = definitions.get('TC2.AO.NUMERIC_AND_COLOUR',{})
    if (alligator.get('median') != '(H+L)/2'
            or alligator.get('period_shift_pairs') != {'jaw':[13,8],'teeth':[8,5],'lips':[5,3]}
            or alligator.get('recurrence') != 'raw[t]=(raw[t-1]*(N-1)+median[t])/N'
            or alligator.get('first_raw_indexes') != {'jaw':12,'teeth':7,'lips':4}
            or alligator.get('first_display_indexes') != {'jaw':20,'teeth':12,'lips':7}):
        errors.append('ALLIGATOR_ATOMIC_PARAMETERS_INVALID')
    if (ao.get('median') != '(H+L)/2' or ao.get('fast') != 5 or ao.get('slow') != 34
            or ao.get('formula') != 'SMA5(median)-SMA34(median)'
            or [ao.get(k) for k in ('first_value_index','first_colour_index','first_possible_third_change_index')] != [33,34,36]
            or ao.get('colours') != {'GREEN':'AO[t]>AO[t-1]','RED':'AO[t]<AO[t-1]',
                                    'FLAT':'AO[t]==AO[t-1]','UNKNOWN':'Either compared value is unavailable or nonfinite.'}
            or any(ao.get(k) is not False for k in ('zero_sign_defines_colour',
                'price_candle_body_defines_colour','wm2_lifecycle_complete'))):
        errors.append('AO_ATOMIC_PARAMETERS_INVALID')
    source_keys=[]
    for entry in spec.get('source_pages',[]):
        pin(entry);source_keys.append((entry.get('source_id'),entry.get('pdf_page')))
    if len(source_keys)!=6 or set(source_keys)!={('TC2_EN',p) for p in (155,156,157,245,246,247)}:
        errors.append('NUMERIC_SOURCE_PAGE_SET_INVALID')
    for rule_id in IDS:
        rule = registry.get(rule_id,{})
        definition = rule.get('SIGNAL_DEFINITION',{})
        if (rule.get('FULL_ATOMIC_SPEC_COMPLETE') is not True
                or rule.get('execution_authority') is not False or rule.get('training_allowed') is not False
                or definition.get('schema') != spec.get('schema')
                or definition.get('spec_id') != rule_id
                or definition.get('spec_file') != 'research/'+SPEC_FILE
                or definition.get('scope') != 'NUMERIC_TRANSFORM_ONLY'
                or definition.get('directional_candidate_support') is not False
                or definition.get('fixture') != FIXTURE
                or definition.get('fixture_sha256') != spec.get('fixture_sha256')):
            errors.append('NUMERIC_REGISTRY_SPEC_MISMATCH')
    if (spec.get('fixture') != FIXTURE or spec.get('numeric_cases')!=15 or spec.get('ordinal_cases')!=14):
        errors.append('NUMERIC_FIXTURE_REFERENCE_INVALID')
    fixture_verified=False
    if check_fixture:
        path=root/FIXTURE
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest()!=spec.get('fixture_sha256'):
            errors.append('NUMERIC_FIXTURE_BYTES_MISMATCH')
        else:
            data=json.loads(path.read_text());fixture_verified=True
            if (data.get('schema')!='TC2_INDICATOR_ATOMIC_FIXTURE_V1'
                    or data.get('scope')!='CONSTRUCTED_NUMERIC_VECTORS_NOT_BOOK_CHART_OHLC'
                    or len(data.get('numeric_cases',[]))!=15 or len(data.get('ordinal_cases',[]))!=14
                    or any(data.get(k) is not False for k in ('expert_gold','can_place_orders','training_allowed'))):
                errors.append('NUMERIC_FIXTURE_SCOPE_INVALID')
    if (alignment.get('schema')!='TC2_APPENDIX7_ALIGNMENT_V1'
            or any(alignment.get(k) is not False for k in FALSE_FLAGS+('expert_gold',
                'entire_translations_certified','all_screenshot_semantics_verified','printed_easylanguage_compiler_verified'))):
        errors.append('APPENDIX7_FALSE_CERTIFICATION_OR_AUTHORITY')
    keys=[]
    for entry in alignment.get('pages',[]):
        pin(entry);sid,n=entry.get('source_id'),entry.get('pdf_page');keys.append((sid,n))
        if any(entry.get(k) is not False for k in FALSE_FLAGS):
            errors.append('APPENDIX7_PAGE_HAS_UNSUPPORTED_AUTHORITY')
        english=entry.get('english_pages',[])
        if entry.get('page_kind')=='VISUALLY_CONFIRMED_BLANK':
            row=pages.get((sid,n),{});note=notes.get(f'{sid}:{n}',{})
            if (sid,n)!=('TC2_RU_314',294) or english or note.get('visual_review') is not True or row.get('text_layer_characters')!=0:
                errors.append('APPENDIX7_BLANK_WITHOUT_RENDER_OR_FALSE_MATCH')
        elif not english:
            errors.append('APPENDIX7_ENGLISH_MAPPING_MISSING')
        for evidence in english:
            pin(evidence)
            if evidence.get('source_id')!='TC2_EN' or evidence.get('pdf_page') not in range(240,248):
                errors.append('APPENDIX7_ENGLISH_MAPPING_OUTSIDE_UNIT')
    expected={('TC2_RU_224',p) for p in range(218,225)}|{('TC2_RU_314',p) for p in range(293,303)}
    if len(keys)!=17 or set(keys)!=expected:
        errors.append('APPENDIX7_PAGE_SET_INVALID')
    return {'schema':'TC2_NUMERIC_ATOMIC_AUDIT_V1','integrity_valid':not errors,'errors':sorted(set(errors)),
            'bounded_numeric_definitions':len(definitions),'appendix7_page_comparisons':len(set(keys)),
            'numeric_fixture_cases':15,'ordinal_fixture_cases':14,'fixture_bytes_verified':fixture_verified,
            'runtime_contract_executed_by_this_audit':False,'independent_second_review':False,
            'entire_book_certified':False,'can_place_orders':False,'training_allowed':False}


def main() -> int:
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path)
    args=parser.parse_args()
    result=audit();text=json.dumps(result,ensure_ascii=False,indent=2)+'\n'
    if args.output:args.output.write_text(text)
    print(text,end='')
    return 0 if result['integrity_valid'] else 1


if __name__=='__main__':
    raise SystemExit(main())
