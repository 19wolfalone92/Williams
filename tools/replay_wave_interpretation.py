"""Replay read-only structure at successive H1 closes with future poisoning.

Inputs are genuine independent exchange klines, not resampled/fabricated lower
bars. This checks causal computations, not trading profitability or the truth
of Elliott labels. No reference wave annotation means no accuracy statistic.
"""
import argparse
from collections import Counter
from dataclasses import replace
import hashlib
import json
from pathlib import Path
import sys

ROOT=Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0,str(ROOT))

from williams_market_phase import observed_market_context,market_phase_context
from williams_wave_hierarchy import FRAME_MS,WaveBar


def replay_file(path, snapshots=72, step_hours=1):
    payload=json.loads(path.read_text())
    history={frame:[WaveBar(*row) for row in bars] for frame,bars in payload['frames'].items()}
    end=min(rows[-1].t+FRAME_MS[frame] for frame,rows in history.items())
    cutoffs=[end-i*FRAME_MS['1h']*step_hours for i in reversed(range(snapshots))]
    counts=Counter();checks=Counter();failures=[];examples={}
    for as_of in cutoffs:
        full=observed_market_context(history,as_of)
        prefix={f:[b for b in rows if b.t+FRAME_MS[f]<=as_of] for f,rows in history.items()}
        truncated=observed_market_context(prefix,as_of)
        poisoned={f:[b if b.t+FRAME_MS[f]<=as_of else replace(b,o=-1,h=float('nan'),l=-999,c=-1,v=-1)
                     for b in rows] for f,rows in history.items()}
        unknown_future=observed_market_context(poisoned,as_of)
        checks['prefix_comparisons']+=1;checks['poisoned_future_comparisons']+=1
        if full!=truncated:
            failures.append({'as_of':as_of,'kind':'PREFIX_MISMATCH'})
        if full!=unknown_future:
            failures.append({'as_of':as_of,'kind':'FUTURE_POISONING_CHANGED_PAST'})
        if full['frame_errors']:
            failures.append({'as_of':as_of,'kind':'FRAME_HISTORY_UNVERIFIED','frames':full['frame_errors']})
        result=market_phase_context(full)['wave_interpretation']
        if any(result[k] for k in ('can_place_orders','can_close','can_reverse','wave_genealogy_confirmed')):
            failures.append({'as_of':as_of,'kind':'UNAUTHORIZED_WAVE_AUTHORITY'})
        for scope in result['scopes']:
            counts[scope['state']]+=1
            if scope['state'] not in {'UNRESOLVED','HISTORY_UNVERIFIED'} and scope['state'] not in examples:
                examples[scope['state']]={'as_of':as_of,'parent_frame':scope['parent_frame'],
                    'child_frame':scope['child_frame'],'relation_count':scope['relation_count'],
                    'candidate_states':scope['candidate_states']}
        # Serialization is also checked: no numerical NaN can become evidence.
        json.dumps(full,allow_nan=False)
    return {'symbol':payload['symbol'],'input_sha256':hashlib.sha256(path.read_bytes()).hexdigest(),
        'source':payload['source'],'independent_frame_bars':{f:len(v) for f,v in history.items()},
        'first_as_of':cutoffs[0],'last_as_of':cutoffs[-1],'snapshots':len(cutoffs),
        'checks':dict(checks),'scope_state_counts':dict(counts),'examples':examples,'failures':failures,
        'passed':not failures,'reference_wave_labels_supplied':False,'forecast_accuracy_certified':False,
        'profitability_tested':False,'can_place_orders':False}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory',type=Path)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--snapshots',type=int,default=72)
    parser.add_argument('--step-hours',type=int,default=1)
    args=parser.parse_args()
    if args.snapshots<1 or args.step_hours<1:
        parser.error('Positive snapshot count and step required')
    results=[]
    for path in sorted(args.directory.glob('*.json')):
        result=replay_file(path,args.snapshots,args.step_hours)
        results.append(result)
        print(json.dumps({'symbol':result['symbol'],'passed':result['passed'],
            'snapshots':result['snapshots'],'scope_states':result['scope_state_counts']},ensure_ascii=False),flush=True)
    output={'schema':'WILLIAMS_WAVE_INTERPRETATION_REPLAY_V1','pairs':len(results),'results':results,
        'snapshots':sum(r['snapshots'] for r in results),'passed':bool(results) and all(r['passed'] for r in results),
        'scope':'CAUSALITY_AND_IMPLEMENTATION_INVARIANTS_NOT_WAVE_ACCURACY_OR_PROFITABILITY',
        'forecast_accuracy_certified':False,'profitability_tested':False,'can_place_orders':False}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(output,ensure_ascii=False,indent=2)+'\n')
    return 0 if output['passed'] else 1


if __name__=='__main__':
    raise SystemExit(main())
