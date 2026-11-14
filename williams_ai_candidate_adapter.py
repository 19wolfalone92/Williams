"""Versioned, offline research exchange for source-pinned AI candidates.

This module has no model, training, exchange or OrderIntent integration. A valid
packet establishes schema/provenance consistency, never semantic correctness or
permission to act. Callers must retain the original request outside model output.
"""
from __future__ import annotations

import hashlib
import json
import math
from pathlib import Path
import re
from typing import Any

REQUEST_SCHEMA = 'WILLIAMS_AI_RESEARCH_REQUEST_V1'
PROPOSAL_SCHEMA = 'WILLIAMS_AI_RESEARCH_PROPOSAL_V1'
OBSERVATION_SCHEMA = 'WILLIAMS_CLOSED_H1_OBSERVATION_V1'
RECEIPT_SCHEMA = 'WILLIAMS_AI_RESEARCH_RECEIPT_V1'
HOUR_MS = 3_600_000
# Engineering packet bounds, not a claimed book minimum or trading gate.
MIN_BARS, MAX_BARS = 45, 2000
AUTHORITY = dict.fromkeys((
    'execution_authority', 'can_place_orders', 'can_cancel_orders',
    'can_close_positions', 'can_move_stops', 'training_allowed',
), False)
DECISIONS = frozenset((
    'ABSTAIN', 'LONG_CANDIDATE', 'SHORT_CANDIDATE', 'CONFLICT', 'NEEDS_EXPERT_REVIEW',
))
PROVENANCE = frozenset(('SYNTHETIC', 'HISTORICAL_SEEN', 'PROSPECTIVE_UNREVIEWED'))
RULE_FIELDS = (
    'RULE_ID', 'SOURCE', 'SOURCE_ID', 'SOURCE_SHA256', 'EDITION', 'CHAPTER',
    'PAGE', 'FIGURE', 'CLASSIFICATION', 'ORIGINAL_MEANING', 'PRECONDITIONS',
    'SIGNAL_DEFINITION', 'CONFIRMATION', 'INVALIDATION', 'EXCEPTIONS',
    'CONFLICTS', 'IMPLEMENTATION_STATUS', 'FULL_ATOMIC_SPEC_COMPLETE',
    'source_reviewed_this_run', 'execution_authority', 'training_allowed',
)


class CandidateValidationError(ValueError):
    """A machine-readable rejection, with no coercion or partial acceptance."""


def _require(condition: bool, reason: str) -> None:
    if not condition:
        raise CandidateValidationError(reason)


def canonical_json(value: Any) -> str:
    try:
        return json.dumps(value, sort_keys=True, separators=(',', ':'),
                          ensure_ascii=False, allow_nan=False)
    except (TypeError, ValueError, OverflowError) as exc:
        raise CandidateValidationError('NON_JSON_OR_NONFINITE_VALUE') from exc


def digest(value: Any) -> str:
    return hashlib.sha256(canonical_json(value).encode('utf-8')).hexdigest()


def _unique_object(pairs: list[tuple[str, Any]]) -> dict:
    result = {}
    for key, value in pairs:
        _require(key not in result, 'DUPLICATE_JSON_KEY')
        result[key] = value
    return result


def _finite_float(value: str) -> float:
    number = float(value)
    _require(math.isfinite(number), 'NONFINITE_JSON_NUMBER')
    return number


def strict_loads(text: str) -> Any:
    _require(type(text) is str and len(text) <= 4_000_000, 'JSON_PACKET_TOO_LARGE')
    try:
        return json.loads(text, object_pairs_hook=_unique_object, parse_float=_finite_float,
                          parse_constant=lambda _: (_ for _ in ()).throw(
                              CandidateValidationError('NONFINITE_JSON_NUMBER')))
    except CandidateValidationError:
        raise
    except (ValueError, RecursionError) as exc:
        raise CandidateValidationError('INVALID_JSON') from exc


def _keys(value: Any, expected: set[str], reason: str) -> None:
    _require(type(value) is dict and set(value) == expected, reason)


def _authority(value: Any) -> None:
    _keys(value, set(AUTHORITY), 'AUTHORITY_FIELDS_INVALID')
    _require(all(v is False for v in value.values()), 'AUTHORITY_ESCALATION')


def _timestamp(value: Any) -> bool:
    return type(value) is int and 0 < value <= 2**53 - 1


def _number(value: Any, *, positive: bool) -> bool:
    if type(value) not in (int, float):
        return False
    try:
        return math.isfinite(value) and (value > 0 if positive else value >= 0)
    except OverflowError:
        return False


def validate_observation(observation: Any) -> None:
    _keys(observation, {'schema', 'symbol', 'interval', 'as_of_ms', 'provenance', 'bars'},
          'OBSERVATION_FIELDS_INVALID')
    _require(observation['schema'] == OBSERVATION_SCHEMA, 'OBSERVATION_VERSION_UNSUPPORTED')
    _require(type(observation['symbol']) is str and
             re.fullmatch(r'[A-Z0-9]{2,16}USDT', observation['symbol']) is not None,
             'SYMBOL_INVALID')
    _require(observation['interval'] == '1h', 'ONLY_CLOSED_H1_SUPPORTED')
    _require(_timestamp(observation['as_of_ms']), 'AS_OF_INVALID')
    _require(type(observation['provenance']) is str and observation['provenance'] in PROVENANCE,
             'OBSERVATION_PROVENANCE_INVALID')
    bars = observation['bars']
    _require(type(bars) is list and MIN_BARS <= len(bars) <= MAX_BARS, 'BAR_COUNT_INVALID')
    previous = None
    for bar in bars:
        _keys(bar, {'open_time_ms', 'open', 'high', 'low', 'close', 'volume'}, 'BAR_FIELDS_INVALID')
        t = bar['open_time_ms']
        _require(_timestamp(t) and t % HOUR_MS == 0, 'BAR_TIME_NOT_H1_ALIGNED')
        _require(previous is None or t == previous + HOUR_MS, 'BARS_NOT_CONTIGUOUS')
        _require(t + HOUR_MS <= observation['as_of_ms'], 'FORMING_OR_FUTURE_BAR')
        _require(all(_number(bar[k], positive=True) for k in ('open', 'high', 'low', 'close'))
                 and _number(bar['volume'], positive=False), 'BAR_NUMERIC_DATA_INVALID')
        _require(bar['low'] <= min(bar['open'], bar['close']) <=
                 max(bar['open'], bar['close']) <= bar['high'], 'OHLC_RANGE_INVALID')
        previous = t
    _require(observation['as_of_ms'] - (previous + HOUR_MS) < HOUR_MS, 'LAST_CLOSED_BAR_STALE')


class CandidateAdapter:
    """Immutable evidence snapshot per instance; construct a new one after edits."""

    def __init__(self, repository_root: str | Path | None = None):
        root = Path(repository_root) if repository_root else Path(__file__).resolve().parent
        folder = root / 'research'
        names = ('SOURCE_CATALOG.json', 'MASTER_RULE_REGISTRY.json',
                 'PRIMARY_SOURCE_PAGE_REGISTER.jsonl', 'review_notes.json')
        raw = {name: (folder / name).read_bytes() for name in names}
        self._snapshot = {name: hashlib.sha256(data).hexdigest() for name, data in raw.items()}
        catalog = strict_loads(raw[names[0]].decode('utf-8'))
        registry = strict_loads(raw[names[1]].decode('utf-8'))
        pages = [strict_loads(line) for line in raw[names[2]].decode('utf-8').splitlines()]
        self._reviews = strict_loads(raw[names[3]].decode('utf-8'))
        self._sources = {s['id']: s for s in catalog}
        self._rules = {r['RULE_ID']: r for r in registry['rules']}
        self._pages = {(p['source_id'], p['pdf_page']): p for p in pages}
        _require(len(self._sources) == len(catalog) and
                 len(self._rules) == len(registry['rules']) and len(self._pages) == len(pages),
                 'DUPLICATE_EVIDENCE_ID')
        _require(registry['execution_authority'] is False and registry['training_allowed'] is False,
                 'REGISTRY_AUTHORITY_INVALID')

    def build_request(self, *, source_id: str, rule_ids: list[str], observation: dict) -> dict:
        _require(type(source_id) is str and source_id in self._sources, 'UNKNOWN_SOURCE_ID')
        _require(type(rule_ids) is list and 0 < len(rule_ids) <= 32 and
                 all(type(r) is str for r in rule_ids), 'RULE_SELECTION_INVALID')
        _require(len(set(rule_ids)) == len(rule_ids), 'DUPLICATE_REQUESTED_RULE')
        validate_observation(observation)
        source = self._sources[source_id]
        rules, citations = [], []
        for rid in sorted(rule_ids):
            _require(rid in self._rules, 'UNKNOWN_RULE_ID')
            rule = self._rules[rid]
            _require(rule['SOURCE_ID'] == source_id and rule['SOURCE_SHA256'] == source['sha256']
                     and rule['EDITION'] == source['edition'] and rule['SOURCE'] == source['family'],
                     'RULE_EDITION_MISMATCH')
            _require(rule['execution_authority'] is False and rule['training_allowed'] is False,
                     'RULE_AUTHORITY_INVALID')
            _require(rule['source_reviewed_this_run'] is True, 'RULE_SOURCE_NOT_REVIEWED')
            _require(type(rule['FULL_ATOMIC_SPEC_COMPLETE']) is bool, 'RULE_SPEC_FLAG_INVALID')
            _require(type(rule['PAGE']) is list and bool(rule['PAGE']) and
                     all(type(p) is int and 1 <= p <= source['pdf_pages'] for p in rule['PAGE']),
                     'RULE_PAGES_INVALID')
            for n in sorted(set(rule['PAGE'])):
                page = self._pages.get((source_id, n))
                receipt = self._reviews.get(f'{source_id}:{n}')
                _require(page is not None and receipt is not None and
                         page['source_sha256'] == source['sha256'] and
                         page['verification_status'] in ('REVIEWED_TEXT_ONLY', 'REVIEWED_SINGLE_PASS', 'VERIFIED')
                         and page['can_place_orders'] is False and
                         page['content'] == receipt['content'] and
                         page['verification_status'] == receipt['verification_status'],
                         'CITATION_PAGE_NOT_REVIEWED')
                citations.append({'rule_id': rid, 'source_id': source_id,
                                  'source_sha256': source['sha256'], 'pdf_page': n})
            rules.append({k: rule[k] for k in RULE_FIELDS})
        packet = {
            'schema': REQUEST_SCHEMA,
            'source': {'source_id': source_id, 'source_sha256': source['sha256'],
                       'edition': source['edition'], 'family': source['family'],
                       'source_role': source['role']},
            'snapshot': self._snapshot,
            'observation': observation, 'observation_sha256': digest(observation),
            'rules': rules, 'allowed_citations': citations,
            'limits': {'semantic_entailment_verified': False, 'expert_gold': False,
                       'classification_is_not_order_permission': True,
                       'snapshot_hash_is_not_external_authentication': True},
            'authority': dict(AUTHORITY),
        }
        # Round trip detaches caller-owned dicts/lists from the retained request.
        packet['request_sha256'] = digest(packet)
        return strict_loads(canonical_json(packet))

    def validate_request(self, request: Any) -> None:
        _keys(request, {'schema', 'source', 'snapshot', 'observation', 'observation_sha256',
                        'rules', 'allowed_citations', 'limits', 'authority', 'request_sha256'},
              'REQUEST_FIELDS_INVALID')
        _require(request['schema'] == REQUEST_SCHEMA, 'REQUEST_VERSION_UNSUPPORTED')
        _keys(request['source'], {'source_id', 'source_sha256', 'edition', 'family', 'source_role'},
              'REQUEST_SOURCE_FIELDS_INVALID')
        _require(type(request['rules']) is list and all(type(r) is dict and 'RULE_ID' in r
                 for r in request['rules']), 'REQUEST_RULES_INVALID')
        _authority(request['authority'])
        expected = self.build_request(source_id=request['source']['source_id'],
                                      rule_ids=[r['RULE_ID'] for r in request['rules']],
                                      observation=request['observation'])
        _require(canonical_json(expected) == canonical_json(request), 'REQUEST_SNAPSHOT_OR_CONTENT_CHANGED')

    def validate_proposal(self, *, request: dict, proposal: Any) -> dict:
        self.validate_request(request)
        _keys(proposal, {'schema', 'request_sha256', 'observation_sha256', 'decision',
                         'rationale', 'citations', 'authority'}, 'PROPOSAL_FIELDS_INVALID')
        _require(proposal['schema'] == PROPOSAL_SCHEMA, 'PROPOSAL_VERSION_UNSUPPORTED')
        _authority(proposal['authority'])
        _require(proposal['request_sha256'] == request['request_sha256'] and
                 proposal['observation_sha256'] == request['observation_sha256'],
                 'PROPOSAL_BOUND_TO_DIFFERENT_REQUEST')
        _require(type(proposal['decision']) is str and proposal['decision'] in DECISIONS,
                 'DECISION_INVALID')
        _require(type(proposal['rationale']) is str and 0 < len(proposal['rationale'].strip()) <= 6000,
                 'RATIONALE_INVALID')
        citations = proposal['citations']
        _require(type(citations) is list and len(citations) <= 128, 'CITATIONS_INVALID')
        allowed = {canonical_json(c) for c in request['allowed_citations']}
        seen = set()
        for citation in citations:
            _keys(citation, {'rule_id', 'source_id', 'source_sha256', 'pdf_page'},
                  'CITATION_FIELDS_INVALID')
            _require(type(citation['pdf_page']) is int, 'CITATION_PAGE_INVALID')
            encoded = canonical_json(citation)
            _require(encoded in allowed, 'CITATION_OUTSIDE_REQUEST')
            _require(encoded not in seen, 'DUPLICATE_CITATION')
            seen.add(encoded)
        cited = {c['rule_id'] for c in citations}
        if proposal['decision'] in ('LONG_CANDIDATE', 'SHORT_CANDIDATE'):
            _require(bool(cited), 'DIRECTION_WITHOUT_CITATION')
            _require(request['source']['family'] == 'TC2' and
                     request['source']['source_role'] == 'PRIMARY_OPERATIONAL',
                     'DIRECTION_REQUIRES_OPERATIONAL_TC2_SOURCE')
            by_id = {r['RULE_ID']: r for r in request['rules']}
            _require(all(by_id[r]['CLASSIFICATION'] in ('CANONICAL', 'INTERPRETED') and
                         not by_id[r]['CONFLICTS'] for r in cited),
                     'DIRECTION_FROM_QUARANTINED_OR_NONMARKET_KNOWLEDGE')
            _require(all(by_id[r]['FULL_ATOMIC_SPEC_COMPLETE'] is True for r in cited),
                     'DIRECTION_REQUIRES_COMPLETE_RULE_SPEC')
        return {
            'schema': RECEIPT_SCHEMA, 'request_sha256': request['request_sha256'],
            'observation_sha256': request['observation_sha256'], 'proposal_sha256': digest(proposal),
            'decision': proposal['decision'], 'disposition': 'RESEARCH_ONLY_UNVERIFIED',
            'model_rationale_untrusted': proposal['rationale'], 'citations': strict_loads(canonical_json(citations)),
            'semantic_entailment_verified': False, 'expert_gold': False,
            'algorithm_guard_executed': False,
            'incomplete_rule_specifications': [r['RULE_ID'] for r in request['rules']
                                               if not r['FULL_ATOMIC_SPEC_COMPLETE']],
            'required_before_any_integration': ['INDEPENDENT_EXPERT_REVIEW', 'SOURCE_SPEC_COMPLETION',
                                                'DETERMINISTIC_ALGORITHM_GUARD', 'SEPARATE_EXECUTION_GATES'],
            'authority': dict(AUTHORITY),
        }
