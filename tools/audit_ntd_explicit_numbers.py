"""Check visible NTD numbers, without treating reported fills as market evidence.

The table multiplier is inferred from the book's dollar column. It is not a
certified historical contract specification or a Binance tick/quantity rule.
"""
from __future__ import annotations

import argparse
from decimal import Decimal, InvalidOperation, localcontext
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def number(value: object) -> Decimal:
    if not isinstance(value, str):
        raise ValueError('DECIMAL_STRING_REQUIRED')
    try:
        result = Decimal(value)
    except InvalidOperation as exc:
        raise ValueError('INVALID_DECIMAL') from exc
    if not result.is_finite() or result <= 0:
        raise ValueError('POSITIVE_FINITE_NUMBER_REQUIRED')
    return result


def audit_table(table: dict) -> dict:
    if table['side'] != 'LONG':
        raise ValueError('ONLY_PRINTED_LONG_TABLES_SUPPORTED')
    if not table['rows']:
        raise ValueError('PRINTED_ROWS_REQUIRED')
    exit_price = number(table['exit_price'])
    multiplier = number(table['implied_usd_per_displayed_price_unit'])
    rows = []
    total_points = Decimal(0)
    total_gross = Decimal(0)
    printed_row_points = Decimal(0)
    seen = set()
    with localcontext() as context:
        context.prec = 40
        for row in table['rows']:
            row_id = row['row_number']
            if type(row_id) is not int or row_id <= 0 or row_id in seen:
                raise ValueError('INVALID_OR_DUPLICATE_ROW')
            seen.add(row_id)
            # Signal is deliberately retained as provenance, never as a fill.
            signal = number(row['signal_price'])
            fill = number(row['fill_price'])
            points = exit_price - fill
            gross = points * multiplier
            total_points += points
            total_gross += gross
            printed_row_points += number(row['reported_points'])
            rows.append({**row, 'calculated_points': str(points),
                         'calculated_gross_usd': format(gross, '.2f'),
                         'calculated_cumulative_usd': format(total_gross, '.2f'),
                         'signal_to_reported_fill_difference': str(fill - signal),
                         'points_match': points == number(row['reported_points']),
                         'gross_match': gross == number(row['reported_gross_usd']),
                         'cumulative_match': total_gross == number(row['reported_cumulative_usd'])})
    points_match = total_points == number(table['reported_total_points'])
    gross_match = total_gross == number(table['reported_total_gross_usd'])
    return {'case_id': table['case_id'], 'pdf_page': table['pdf_page'],
            'table_label': table['table_label'], 'rows': rows,
            'calculated_total_points': str(total_points),
            'sum_of_printed_row_points': str(printed_row_points),
            'calculated_total_gross_usd': format(total_gross, '.2f'),
            'total_points_match': points_match, 'total_gross_match': gross_match,
            'all_printed_cells_reconciled': points_match and gross_match
            and all(r['points_match'] and r['gross_match'] and r['cumulative_match'] for r in rows),
            'net_profit_usd': None, 'costs_verified': False,
            'historical_fills_verified': False, 'margin_path_verified': False,
            'historical_contract_units_verified': False}


def compound_illustration(spec: dict) -> dict:
    initial = number(spec['initial_usd'])
    rate = number(spec['constant_monthly_rate'])
    target = number(spec['target_usd'])
    if target <= initial:
        raise ValueError('TARGET_MUST_EXCEED_INITIAL')
    with localcontext() as context:
        context.prec = 80
        factor = (1 + rate) ** 12
        months = 0
        balance = initial
        # A malformed research input cannot start an unbounded computation.
        while balance < target and months < 1200:
            previous = balance
            balance *= 1 + rate
            months += 1
        if balance < target:
            raise ValueError('TARGET_NOT_REACHED_WITHIN_1200_MONTHS')
        return {'pdf_page': spec['pdf_page'],
                'annual_ending_balance_factor': str(factor),
                'annual_ending_balance_as_percent_of_initial': str(factor * 100),
                'annual_profit_percent': str((factor - 1) * 100),
                'first_whole_month_reaching_target': months,
                'previous_month_balance_usd': format(previous, '.2f'),
                'target_month_balance_usd': format(balance, '.2f'),
                'constant_monthly_rate_assumed': True,
                'arithmetic_average_return_is_not_geometric_return': True,
                'forecast': False, 'costs_verified': False}


def audit(path: Path = ROOT / 'research/NTD_EXPLICIT_NUMERIC_EXAMPLES.json') -> dict:
    raw = path.read_bytes()
    dataset = json.loads(raw)
    catalog = json.loads((ROOT / 'research/SOURCE_CATALOG.json').read_text())
    source = next(s for s in catalog if s['id'] == 'NTD_RU')
    if (dataset['source_id'], dataset['source_sha256']) != (source['id'], source['sha256']):
        raise ValueError('SOURCE_PIN_MISMATCH')
    if any(dataset.get(k) is not False for k in
           ['independent_second_review', 'can_place_orders', 'training_allowed']):
        raise ValueError('UNSUPPORTED_AUTHORITY_OR_CERTIFICATION')
    items = dataset['tables'] + dataset['arithmetic_claims'] + [dataset['compounding']]
    reviews = json.loads((ROOT / 'research/review_notes.json').read_text())
    for item in items:
        page = item['pdf_page']
        if type(page) is not int or not 1 <= page <= source['pdf_pages']:
            raise ValueError('INVALID_SOURCE_PAGE')
        if reviews.get(f'NTD_RU:{page}', {}).get('visual_review') is not True:
            raise ValueError('NUMERIC_CLAIM_WITHOUT_VISUAL_RECEIPT')
    ids = [t['case_id'] for t in dataset['tables']] + [c['claim_id'] for c in dataset['arithmetic_claims']]
    if len(ids) != len(set(ids)):
        raise ValueError('DUPLICATE_NUMERIC_CASE_ID')
    tables = [audit_table(t) for t in dataset['tables']]
    claims = []
    with localcontext() as context:
        context.prec = 40
        for claim in dataset['arithmetic_claims']:
            left, right = number(claim['left']), number(claim['right'])
            if claim['operator'] == 'multiply':
                calculated = left * right
            elif claim['operator'] == 'subtract':
                calculated = left - right
            else:
                raise ValueError('UNKNOWN_ARITHMETIC_OPERATOR')
            claims.append({**claim, 'calculated': str(calculated),
                           'matches_printed_result': calculated == number(claim['reported'])})
    return {'schema': 'NTD_PRINTED_NUMBER_AUDIT_V1', 'source_id': source['id'],
            'source_sha256': source['sha256'], 'input_sha256': hashlib.sha256(raw).hexdigest(),
            'provenance': 'VISIBLE_BOOK_REPORTED_NUMBERS_NOT_EXCHANGE_VERIFIED',
            'tables': tables, 'arithmetic_claims': claims,
            'compounding_illustration': compound_illustration(dataset['compounding']),
            'all_printed_claims_reconciled': all(t['all_printed_cells_reconciled'] for t in tables)
            and all(c['matches_printed_result'] for c in claims),
            'is_backtest': False, 'expert_gold': False, 'execution_authority': False,
            'can_place_orders': False, 'training_allowed': False}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, default=ROOT / 'research/NTD_EXPLICIT_NUMERIC_EXAMPLES.json')
    parser.add_argument('--output', type=Path)
    parser.add_argument('--require-reconciled', action='store_true')
    args = parser.parse_args(argv)
    result = audit(args.input)
    data = json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + '\n'
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(data)
    print(data, end='')
    return 2 if args.require_reconciled and not result['all_printed_claims_reconciled'] else 0


if __name__ == '__main__':
    raise SystemExit(main())
