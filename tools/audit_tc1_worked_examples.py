"""Audit explicitly printed TC1 prices/quantities, never digitized OHLC or fills.

FIFO is the allocation stated in the worked tables. No exchange/model calls;
costs, funding, historical fills and account margin paths remain unverified.
"""
from __future__ import annotations

import argparse
from decimal import Decimal, InvalidOperation, localcontext
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def number(value: object) -> Decimal:
    # Decimal strings preserve quarter-point quotes and cents. Floats/bools are
    # deliberately rejected rather than silently becoming a different quote.
    if not isinstance(value, str):
        raise ValueError('DECIMAL_STRING_REQUIRED')
    try:
        result = Decimal(value)
    except InvalidOperation as exc:
        raise ValueError('INVALID_DECIMAL') from exc
    if not result.is_finite() or result <= 0:
        raise ValueError('POSITIVE_FINITE_NUMBER_REQUIRED')
    return result


def usd(value: Decimal) -> str:
    return format(value, '.2f')


def replay(side: str, point_value: str, events: list[dict]) -> dict:
    """Realized gross only; CLOSE cannot secretly reverse or over-close."""
    if side not in {'LONG', 'SHORT'}:
        raise ValueError('INVALID_SIDE')
    multiplier = number(point_value)
    sign = Decimal(1 if side == 'LONG' else -1)
    lots: list[dict] = []
    receipts = []
    seen: set[str] = set()
    total = Decimal(0)
    with localcontext() as context:
        context.prec = 40
        for event in events:
            event_id = event['event_id']
            if not isinstance(event_id, str) or not event_id or event_id in seen:
                raise ValueError('INVALID_OR_DUPLICATE_EVENT_ID')
            seen.add(event_id)
            quantity = event['quantity']
            if type(quantity) is not int or quantity <= 0:
                raise ValueError('POSITIVE_WHOLE_CONTRACTS_REQUIRED')
            price = number(event['price'])
            allocations = []
            realized = Decimal(0)
            if event['action'] == 'OPEN':
                lots.append({'event_id': event_id, 'quantity': quantity, 'price': price})
            elif event['action'] == 'CLOSE':
                if quantity > sum(lot['quantity'] for lot in lots):
                    raise ValueError('CLOSE_EXCEEDS_OPEN_QUANTITY')
                remaining = quantity
                while remaining:
                    lot = lots[0]
                    count = min(remaining, lot['quantity'])
                    pnl = sign * (price - lot['price']) * count * multiplier
                    realized += pnl
                    allocations.append({'entry_event_id': lot['event_id'], 'quantity': count,
                                        'entry_price': str(lot['price']), 'gross_usd': usd(pnl)})
                    lot['quantity'] -= count
                    remaining -= count
                    if not lot['quantity']:
                        lots.pop(0)
            else:
                raise ValueError('INVALID_ACTION')
            total += realized
            receipts.append({'event_id': event_id, 'realized_gross_usd': usd(realized),
                             'cumulative_realized_gross_usd': usd(total),
                             'open_quantity': sum(lot['quantity'] for lot in lots),
                             'lot_allocations': allocations})
    return {'events': receipts, 'realized_gross_usd': usd(total),
            'open_quantity': sum(lot['quantity'] for lot in lots),
            'open_lots': [{**lot, 'price': str(lot['price'])} for lot in lots],
            'net_profit_usd': None, 'costs_verified': False,
            'margin_path_verified': False, 'historical_fills_verified': False}


def audit_case(case: dict) -> dict:
    result = replay(case['side'], case['point_value_usd'], case['events'])
    by_id = {event['event_id']: event for event in result['events']}
    first = case['events'][0]
    initial_loss = abs(number(first['price']) - number(case['initial_stop_price']))
    initial_loss *= first['quantity'] * number(case['point_value_usd'])
    checks = []
    for claim in case['printed_claims']:
        if claim['metric'] == 'initial_stop_loss':
            calculated = initial_loss
        else:
            event = by_id[claim['event_id']]
            if claim['metric'] == 'event_gross':
                calculated = Decimal(event['realized_gross_usd'])
            elif claim['metric'] == 'cumulative_gross':
                calculated = Decimal(event['cumulative_realized_gross_usd'])
            elif claim['metric'] == 'allocation_gross':
                calculated = Decimal(event['lot_allocations'][claim['allocation_index']]['gross_usd'])
            else:
                raise ValueError('UNKNOWN_CLAIM_METRIC')
        reported = Decimal(claim['reported_usd'])
        if not reported.is_finite():
            raise ValueError('NONFINITE_PRINTED_CLAIM')
        checks.append({**claim, 'calculated_usd': usd(calculated),
                       'reported_minus_calculated_usd': usd(reported - calculated),
                       'matches_literal_body_ledger': reported == calculated})
    alternatives = []
    for variant in case.get('explicit_chart_variants', []):
        events = [{**event, 'price': variant['chart_price']}
                  if event['event_id'] == variant['event_id'] else dict(event)
                  for event in case['events']]
        if not any(e['event_id'] == variant['event_id'] for e in case['events']):
            raise ValueError('VARIANT_EVENT_NOT_FOUND')
        variant_result = replay(case['side'], case['point_value_usd'], events)
        alternatives.append({**variant, 'realized_gross_usd': variant_result['realized_gross_usd'],
                             'status': 'ONE_PRICE_VARIANT_OTHER_BODY_PRICES_UNCHANGED_NOT_ACTUAL_PNL'})
    hypothetical = None
    if case.get('hypothetical_stop'):
        stop = case['hypothetical_stop']
        index = next(i for i, e in enumerate(case['events']) if e['event_id'] == stop['after_event_id'])
        prefix = case['events'][:index + 1]
        held = replay(case['side'], case['point_value_usd'], prefix)['open_quantity']
        hypothetical = replay(case['side'], case['point_value_usd'], prefix + [{
            'event_id': 'hypothetical_stop', 'action': 'CLOSE', 'quantity': held,
            'price': stop['price']}])['events'][-1]
        hypothetical['status'] = 'UNEXECUTED_STOP_SCENARIO_EXCLUDED_FROM_REALIZED_LEDGER'
    internal = []
    for equation in case.get('printed_subtractions', []):
        calculated = number(equation['minuend_usd']) - number(equation['subtrahend_usd'])
        internal.append({**equation, 'calculated_usd': usd(calculated),
                         'matches_printed_result': calculated == number(equation['reported_usd'])})
    return {'case_id': case['case_id'], 'source_pages': case['source_pages'],
            'body_ledger': result, 'printed_claim_checks': checks,
            'explicit_chart_variants': alternatives, 'hypothetical_stop': hypothetical,
            'printed_internal_arithmetic': internal,
            'all_printed_claims_reconciled': all(c['matches_literal_body_ledger'] for c in checks)
            and all(e['matches_printed_result'] for e in internal)}


def audit(path: Path = ROOT / 'research/TC1_EXPLICIT_NUMERIC_EXAMPLES.json') -> dict:
    raw = path.read_bytes()
    dataset = json.loads(raw)
    catalog = json.loads((ROOT / 'research/SOURCE_CATALOG.json').read_text())
    source = next(source for source in catalog if source['id'] == 'TC1_RU')
    if (dataset['source_id'], dataset['source_sha256']) != (source['id'], source['sha256']):
        raise ValueError('SOURCE_PIN_MISMATCH')
    for case in dataset['cases']:
        pages = case['source_pages']
        if not pages or any(type(p) is not int or not 1 <= p <= source['pdf_pages'] for p in pages):
            raise ValueError('INVALID_SOURCE_PAGE')
    cases = [audit_case(case) for case in dataset['cases']]
    return {'schema': 'TC1_PRINTED_NUMBER_AUDIT_V1', 'source_id': source['id'],
            'source_sha256': source['sha256'], 'input_sha256': hashlib.sha256(raw).hexdigest(),
            'provenance': 'BOOK_REPORTED_NUMBERS_NOT_EXCHANGE_VERIFIED',
            'allocation': 'FIFO_EXPLICITLY_USED_IN_PRINTED_TABLES',
            'cases': cases, 'all_printed_claims_reconciled': all(c['all_printed_claims_reconciled'] for c in cases),
            'is_backtest': False, 'expert_gold': False, 'execution_authority': False,
            'can_place_orders': False, 'training_allowed': False}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, default=ROOT / 'research/TC1_EXPLICIT_NUMERIC_EXAMPLES.json')
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
