"""Check TC2 section provenance and printed arithmetic without certifying trades.

Section intervals are navigation, not paragraph alignment. The numeric checks
use only printed numbers; no chart OHLC, fills, costs or capital are inferred.
"""
from __future__ import annotations

import argparse
from decimal import Decimal, InvalidOperation, localcontext
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EDITIONS = {'TC2_EN', 'TC2_RU_224', 'TC2_RU_314'}
UNITS = ([f'CH{i:02}' for i in range(1, 14)] + ['EPILOGUE']
         + [f'APPENDIX{i}' for i in range(1, 8)] + ['BIBLIOGRAPHY', 'INDEX'])


def _number(value: object) -> Decimal:
    if not isinstance(value, str):
        raise ValueError('DECIMAL_STRING_REQUIRED')
    try:
        result = Decimal(value)
    except InvalidOperation as exc:
        raise ValueError('INVALID_DECIMAL') from exc
    if not result.is_finite() or result <= 0:
        raise ValueError('POSITIVE_FINITE_NUMBER_REQUIRED')
    return result


def _quantities(values: list, size: int) -> list[int]:
    if len(values) != size or any(type(q) is not int or q <= 0 for q in values):
        raise ValueError('POSITIVE_WHOLE_QUANTITIES_REQUIRED')
    return values


def printed_arithmetic(dataset: dict) -> dict:
    """Explicit conditional arithmetic; unknown weights/costs stay unknown."""
    if any(dataset.get(k) is not False for k in [
            'execution_authority', 'training_allowed', 'expert_gold', 'is_backtest']):
        raise ValueError('NUMERIC_INPUT_HAS_UNSUPPORTED_AUTHORITY')
    gold = dataset['gold']
    profits = [_number(p) for p in gold['profit_per_contract_usd']]
    if len(profits) != 4:
        raise ValueError('FOUR_PRINTED_GOLD_ROWS_REQUIRED')
    counts = _quantities(gold['executed_quantities'], len(profits))
    plan = _quantities(gold['planned_quantities'], 5)
    printed_rows = [_number(p) for p in gold['printed_weighted_profit_usd']]
    if len(printed_rows) != len(profits) or plan[:-1] != counts:
        raise ValueError('INCONSISTENT_PRINTED_GOLD_ALLOCATION')
    if gold['fifth_tranche_in_printed_profit_table'] is not False:
        raise ValueError('UNSUPPORTED_FIFTH_GOLD_PROFIT_ROW')
    costco = dataset['costco']
    if (costco.get('prices_are_approximate') is not True
            or costco.get('entry_quantities_known') is not False
            or costco.get('fees_and_slippage_known') is not False):
        raise ValueError('UNSUPPORTED_COSTCO_PRECISION_OR_COSTS')
    entries = [_number(p) for p in costco['entry_prices_usd']]
    if len(entries) != 3:
        raise ValueError('THREE_PRINTED_COSTCO_ENTRIES_REQUIRED')
    exit_price = _number(costco['exit_price_usd'])
    printed_costco = _number(costco['printed_combined_profit_usd'])
    with localcontext() as context:
        context.prec = 40
        weighted = [p * q for p, q in zip(profits, counts)]
        equal_total = sum(profits)
        weighted_total = sum(weighted)
        deltas = [exit_price - p for p in entries]
        combined = sum(deltas)
        gold_result = {
            'equal_one_per_entry_total_usd': format(equal_total, '.2f'),
            'weighted_rows_usd': [format(p, '.2f') for p in weighted],
            'weighted_total_usd': format(weighted_total, '.2f'),
            'printed_rows_reconcile': weighted == printed_rows,
            'printed_totals_reconcile': (
                equal_total == _number(gold['printed_equal_total_usd'])
                and weighted_total == _number(gold['printed_weighted_total_usd'])),
            'equal_position_contracts': len(profits),
            'weighted_position_contracts': sum(counts),
            'planned_capacity_contracts': sum(plan),
            'fifth_tranche_included_in_result': False,
            'actual_fifth_entry_execution_known': False,
            'gross_dollar_ratio_different_exposures': format(weighted_total / equal_total, '.8f'),
            'equal_risk_improvement_certified': False,
            'percentage_return': None,
            'net_profit_usd': None,
            'matched_capital_and_margin_path_known': False,
        }
        costco_result = {
            'assumption': 'ONE_SHARE_PER_ENTRY_FOR_ARITHMETIC_ONLY_NOT_KNOWN_ALLOCATION',
            'individual_price_differences_usd': [format(p, '.2f') for p in deltas],
            'conditional_three_share_combined_gross_usd': format(combined, '.2f'),
            'conditional_gross_per_final_share_usd': format(combined / len(entries), '.2f'),
            'printed_combined_profit_usd': format(printed_costco, '.2f'),
            'printed_minus_conditional_sum_usd': format(printed_costco - combined, '.2f'),
            'literal_displayed_sum_matches_printed': combined == printed_costco,
            'discrepancy_status': 'APPROXIMATE_PRICES_AND_UNKNOWN_ALLOCATION_NOT_CERTIFIED_ERRATUM',
            'net_profit_usd': None,
            'inferred_fee_to_force_reconciliation_usd': None,
            'actual_entry_quantities': None,
        }
    return {'gold': gold_result, 'costco': costco_result,
            'historical_fills_verified': False, 'costs_verified': False,
            'is_backtest': False, 'expert_gold': False,
            'execution_authority': False, 'training_allowed': False}


def audit(root: Path = ROOT) -> dict:
    folder = root / 'research'
    sources = {s['id']: s for s in json.loads((folder / 'SOURCE_CATALOG.json').read_text())}
    rows = [json.loads(line) for line in (folder / 'PRIMARY_SOURCE_PAGE_REGISTER.jsonl').read_text().splitlines()]
    pages = {(p['source_id'], p['pdf_page']): p for p in rows}
    notes = json.loads((folder / 'review_notes.json').read_text())
    rules = {r['RULE_ID'] for r in json.loads((folder / 'MASTER_RULE_REGISTRY.json').read_text())['rules']}
    structure = json.loads((folder / 'TC2_EDITION_STRUCTURE_MAP.json').read_text())
    conflicts = json.loads((folder / 'TC2_TRANSLATION_CONFLICT_REGISTER.json').read_text())
    raw_numbers = (folder / 'TC2_CH12_EXPLICIT_NUMBERS.json').read_bytes()
    numbers = json.loads(raw_numbers)
    errors = []
    if (structure.get('schema') != 'TC2_EDITION_STRUCTURE_MAP_V1'
            or structure.get('scope') != 'PHYSICAL_SECTION_INTERVALS_ONLY'
            or structure.get('physical_section_partition_complete') is not True
            or [u.get('unit_id') for u in structure['canonical_units']] != UNITS):
        errors.append('STRUCTURE_SCHEMA_OR_UNITS_INVALID')
    if any(structure.get(k) is not False for k in [
            'semantic_alignment_complete', 'page_bijection_claimed', 'constant_offset_claimed',
            'all_pages_read_claimed', 'independent_second_review', 'can_place_orders', 'training_allowed']):
        errors.append('STRUCTURE_FALSE_SEMANTIC_OR_AUTHORITY_CLAIM')
    ids = [e.get('source_id') for e in structure['editions']]
    if len(ids) != len(set(ids)) or set(ids) != EDITIONS:
        errors.append('STRUCTURE_EDITION_SET_INVALID')
    covered = {}
    heading_count = 0
    for edition in structure['editions']:
        sid = edition.get('source_id')
        source = sources.get(sid)
        if (sid not in EDITIONS or not source
                or edition.get('source_sha256') != source['sha256']
                or edition.get('physical_pages') != source['pdf_pages']):
            errors.append('STRUCTURE_SOURCE_PIN_MISMATCH')
            continue
        expected_units = ['FRONT_MATTER'] + (UNITS[:-2] if sid == 'TC2_RU_224' else UNITS)
        if sid == 'TC2_RU_314':
            expected_units += ['PUBLISHER_PARATEXT']
        if [s.get('unit_id') for s in edition['segments']] != expected_units:
            errors.append('STRUCTURE_SECTION_SET_OR_ORDER_INVALID')
        absent = ['BIBLIOGRAPHY', 'INDEX'] if sid == 'TC2_RU_224' else []
        if edition.get('absent_units') != absent or edition.get('absence_scope') != 'SUPPLIED_FILE_ONLY':
            errors.append('STRUCTURE_FALSE_ABSENCE_CLAIM')
        physical = []
        for segment in edition['segments']:
            span = segment.get('pdf_span')
            if (not isinstance(span, list) or len(span) != 2
                    or any(type(p) is not int for p in span)
                    or not 1 <= span[0] <= span[1] <= source['pdf_pages']):
                errors.append('STRUCTURE_SPAN_INVALID')
                continue
            physical.extend(range(span[0], span[1] + 1))
            if segment['unit_id'] == 'FRONT_MATTER':
                if (segment.get('heading_review') is not None
                        or segment.get('relation') != 'EDITION_SPECIFIC_PARATEXT_NOT_SEMANTICALLY_ALIGNED'):
                    errors.append('STRUCTURE_PARATEXT_FALSE_EQUIVALENCE')
                continue
            expected_relation = ('EDITION_SPECIFIC_PARATEXT_NOT_SEMANTICALLY_ALIGNED'
                                 if segment['unit_id'] == 'PUBLISHER_PARATEXT'
                                 else 'CORRESPONDING_SECTION_NOT_PAGE_BIJECTION')
            if segment.get('relation') != expected_relation:
                errors.append('STRUCTURE_RELATION_INVALID')
            receipt = segment.get('heading_review') or {}
            page = pages.get((sid, span[0]), {})
            if (receipt.get('pdf_page') != span[0]
                    or receipt.get('acquired_text_sha256') != page.get('acquired_text_sha256')
                    or receipt.get('scope') != 'TOP_49_PERCENT_HEADING_CROP_ONLY'
                    or receipt.get('rendered_heading_checked') is not True
                    or receipt.get('full_page_reading_claimed') is not False):
                errors.append('STRUCTURE_HEADING_RECEIPT_INVALID')
            heading_count += 1
        if physical != list(range(1, source['pdf_pages'] + 1)):
            errors.append('STRUCTURE_PAGE_PARTITION_INVALID')
        covered[sid] = len(set(physical))

    def validate_evidence(evidence: list, require_read: bool) -> None:
        evidence_ids = [span.get('source_id') for span in evidence]
        if len(evidence_ids) != len(set(evidence_ids)) or set(evidence_ids) != EDITIONS:
            errors.append('TC2_EVIDENCE_EDITION_SET_INVALID')
        for span in evidence:
            sid = span.get('source_id')
            source = sources.get(sid)
            if sid not in EDITIONS or not source or span.get('source_sha256') != source['sha256']:
                errors.append('TC2_EVIDENCE_SOURCE_PIN_MISMATCH')
                continue
            nums = span.get('pdf_pages')
            if (not isinstance(nums, list) or not nums
                    or any(type(p) is not int or not 1 <= p <= source['pdf_pages'] for p in nums)
                    or len(nums) != len(set(nums))):
                errors.append('TC2_EVIDENCE_PAGE_SET_INVALID')
                continue
            if require_read:
                for p in nums:
                    row = pages.get((sid, p), {})
                    note = notes.get(f'{sid}:{p}', {})
                    if (not row.get('verification_status', '').startswith('REVIEWED')
                            or note.get('verification_status') != row.get('verification_status')
                            or note.get('content') != row.get('content')):
                        errors.append('TC2_EVIDENCE_WITHOUT_READING_RECEIPT')

    if (conflicts.get('schema') != 'TC2_TRANSLATION_CONFLICT_REGISTER_V1'
            or any(conflicts.get(k) is not False for k in ['independent_second_review',
                    'can_place_orders', 'training_allowed', 'entire_translations_certified'])):
        errors.append('TC2_CONFLICT_FALSE_CERTIFICATION')
    conflict_ids = []
    for conflict in conflicts['conflicts']:
        conflict_ids.append(conflict.get('conflict_id'))
        if (any(conflict.get(k) is not False for k in [
                'independent_second_review', 'execution_authority', 'training_allowed'])
                or conflict.get('all_cited_pages_read') is not True):
            errors.append('TC2_CONFLICT_FALSE_CERTIFICATION')
        validate_evidence(conflict['evidence'], require_read=True)
        for span in conflict['evidence']:
            if not any(notes.get(f"{span['source_id']}:{p}", {}).get('visual_review') is True
                       for p in span['pdf_pages']):
                errors.append('TC2_CONFLICT_EDITION_WITHOUT_RENDER_RECEIPT')
        if any(r not in rules for r in conflict['related_rules']):
            errors.append('TC2_CONFLICT_RULE_UNRESOLVED')
    if any(not isinstance(cid, str) or not cid for cid in conflict_ids) or len(conflict_ids) != len(set(conflict_ids)):
        errors.append('TC2_CONFLICT_IDS_INVALID')
    if numbers.get('schema') != 'TC2_CH12_EXPLICIT_NUMBERS_V1':
        errors.append('TC2_NUMERIC_SCHEMA_INVALID')
    validate_evidence(numbers['source_pages'], require_read=True)
    try:
        arithmetic = printed_arithmetic(numbers)
    except (ValueError, InvalidOperation) as exc:
        errors.append(f'TC2_NUMERIC_INPUT_INVALID:{exc}')
        arithmetic = None
    return {'schema': 'TC2_TRANSLATION_AUDIT_V1', 'integrity_valid': not errors,
            'errors': sorted(set(errors)), 'structure': {
                'physical_pages_partitioned': sum(covered.values()), 'per_source': covered,
                'heading_crop_receipts': heading_count,
                'heading_crops_count_as_full_page_reading': False,
                'semantic_alignment_complete': False, 'constant_offset_used': False},
            'chapter12_comparison_records': len(conflict_ids),
            'numeric_input_sha256': hashlib.sha256(raw_numbers).hexdigest(),
            'printed_arithmetic': arithmetic,
            'independent_second_review': False, 'expert_gold': False,
            'can_place_orders': False, 'training_allowed': False}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--require-complete', action='store_true')
    args = parser.parse_args()
    report = audit()
    output = json.dumps(report, ensure_ascii=False, indent=2) + '\n'
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output)
    print(output, end='')
    return 1 if not report['integrity_valid'] else 2 if args.require_complete else 0


if __name__ == '__main__':
    raise SystemExit(main())
