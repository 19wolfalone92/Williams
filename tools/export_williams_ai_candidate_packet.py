"""Build or check offline AI research packets; never calls a model or exchange."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from williams_ai_candidate_adapter import CandidateAdapter, CandidateValidationError, strict_loads


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    build = sub.add_parser('build')
    build.add_argument('--source-id', required=True)
    build.add_argument('--rule-id', action='append', required=True)
    build.add_argument('--observation', type=Path, required=True)
    check = sub.add_parser('validate')
    check.add_argument('--request', type=Path, required=True)
    check.add_argument('--proposal', type=Path, required=True)
    for p in (build, check):
        p.add_argument('--out', type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        adapter = CandidateAdapter(ROOT)
        if args.command == 'build':
            result = adapter.build_request(source_id=args.source_id, rule_ids=args.rule_id,
                                           observation=strict_loads(args.observation.read_text()))
        else:
            result = adapter.validate_proposal(request=strict_loads(args.request.read_text()),
                                               proposal=strict_loads(args.proposal.read_text()))
        # An invalid packet never produces/replaces an output file.
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + '\n')
    except (CandidateValidationError, OSError, KeyError) as exc:
        print(f'REJECTED: {exc}', file=sys.stderr)
        return 2
    print(f'RESEARCH_ONLY: {args.out}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
