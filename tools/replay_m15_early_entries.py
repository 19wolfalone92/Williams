"""Offline M15 research: causal observations and OHLC level outcomes, never orders.

Stop-before-1R is an explicit research proxy, NOT a definition of book-rule failure,
full campaign PnL, fill simulation, or proof of false-positive-free trading.
"""
from __future__ import annotations
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import sys
import time
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import pandas as pd
from strategy import calculate_indicators,canonical_indicator_config_from_env
from williams_profiles import extract_profile_signals
from williams_edition_rules import UNIFIED,COURSE,course_airbag_stop
from williams_wave_hierarchy import wave_bars_from_dataframe

M15=900_000
TICKS={'BTCUSDT':.1,'ETHUSDT':.01,'BNBUSDT':.01,'SOLUSDT':.001,'XRPUSDT':.0001,'DOGEUSDT':.00001,'ADAUSDT':.0001,'LINKUSDT':.001,'AVAXUSDT':.001,'LTCUSDT':.01}


def frame(rows):
    return pd.DataFrame([dict(open_time_ms=int(r[0]),open=float(r[1]),high=float(r[2]),low=float(r[3]),close=float(r[4]),volume=float(r[5])) for r in rows])


def closed_hours(rows,now):
    groups={}
    for row in rows:
        stamp=int(row[0]);hour=stamp//3_600_000*3_600_000
        if stamp+M15<=now and hour+3_600_000<=now:groups.setdefault(hour,[]).append(row)
    return [[hour,float(g[0][1]),max(float(r[2]) for r in g),min(float(r[3]) for r in g),float(g[-1][4]),sum(float(r[5]) for r in g)]
        for hour,g in sorted(groups.items()) if len(g)==4 and [int(r[0])-hour for r in g]==[0,M15,2*M15,3*M15]]


def canonical(specs,now):
    return [s for s in specs if s.role.value=='ENTRY' and s.execution_semantics=='STOP_ENTRY' and
        s.signal_bar_time_ms+M15<=now<min(s.expires_at_ms or 2**63-1,s.signal_bar_time_ms+5*M15)]


def extract(symbol,data,interval):
    return [s for side in ('LONG','SHORT') for s in extract_profile_signals(symbol,data,profile=UNIFIED,side=side,timeframe=interval,tick_size=TICKS[symbol])]


def outcome(signal,rows,known,stop=None,horizon_bars=16):
    """Only future bars; ambiguous OHLC paths are never counted as successful."""
    trigger=signal.trigger_price;stop=signal.protective_reference if stop is None else stop
    long=signal.side=='BUY';distance=(trigger-stop) if long else (stop-trigger)
    if distance<=0:return dict(result='INVALID_PROTECTION')
    target=trigger+distance if long else trigger-distance
    expires=min(signal.expires_at_ms or 2**63-1,signal.signal_bar_time_ms+5*M15)
    future=[r for r in rows if known<=int(r[0])<known+horizon_bars*M15]
    triggered=False;trigger_at=0
    for r in future:
        stamp=int(r[0]);high=float(r[2]);low=float(r[3])
        hit_trigger=high>=trigger if long else low<=trigger
        hit_stop=low<=stop if long else high>=stop
        hit_target=high>=target if long else low<=target
        if not triggered:
            if stamp>=expires:return dict(result='UNTRIGGERED_EXPIRED')
            if not hit_trigger:continue
            triggered=True;trigger_at=stamp
            if hit_stop:return dict(result='INTRABAR_PATH_UNKNOWN',trigger_bar_time_ms=stamp)
        if hit_stop and hit_target:return dict(result='INTRABAR_PATH_UNKNOWN',trigger_bar_time_ms=trigger_at,observation_bar_time_ms=stamp)
        if hit_stop:return dict(result='STOP_BEFORE_1R',trigger_bar_time_ms=trigger_at,observation_bar_time_ms=stamp)
        if hit_target:return dict(result='REACHED_1R_BEFORE_STOP',trigger_bar_time_ms=trigger_at,observation_bar_time_ms=stamp)
    complete=len(future)==horizon_bars
    return dict(result='OPEN_AT_HORIZON' if complete and triggered else 'UNTRIGGERED_AT_HORIZON' if complete else 'RIGHT_CENSORED',trigger_bar_time_ms=trigger_at)


def replay(path,*,stride=1,evaluation_bars=192):
    rows=json.loads(path.read_text());symbol=path.name.split('_')[1]
    if len(rows)<400:raise ValueError('At least 400 M15 rows required')
    if any(int(b[0])-int(a[0])!=M15 for a,b in zip(rows,rows[1:])):raise ValueError('Non-contiguous M15 history')
    seen=set();events=[];comparisons=0;alignment_cache={};started=time.monotonic()
    # Every decision recomputes indicators from its causal prefix. Rolling history
    # is the same 220-bar budget used by the current native detector.
    for n in range(max(320,len(rows)-evaluation_bars+1),len(rows)+1,stride):
        prefix=rows[max(0,n-220):n];now=int(rows[n-1][0])+M15
        ind=calculate_indicators(frame(prefix),canonical_indicator_config_from_env());specs=canonical(extract(symbol,ind,'15m'),now)
        if n%40==0:
            # Poisoned future rows must be discarded BEFORE indicator calculation.
            poison=prefix+[[now,1,1e20,.01,1,1e20]]
            closed=[r for r in poison if int(r[0])+M15<=now]
            fresh=canonical(extract(symbol,calculate_indicators(frame(closed),canonical_indicator_config_from_env()),'15m'),now)
            def key(items):return [(s.signal_id,s.signal_bar_time_ms,s.trigger_price,s.protective_reference) for s in items]
            if key(specs)!=key(fresh):raise AssertionError('Future candle leaked into M15 decision')
            comparisons+=1
        for spec in specs:
            if spec.signal_id in seen:continue
            seen.add(spec.signal_id)
            hours=closed_hours(rows[:n],now)
            hour_key=hours[-1][0] if hours else 0
            if hour_key not in alignment_cache:
                h1=calculate_indicators(frame(hours[-220:]),canonical_indicator_config_from_env())
                alignment_cache[hour_key]=(h1,extract(symbol,h1,'1h') if len(h1)>=45 else [])
            h1,h1specs=alignment_cache[hour_key]
            aligned=False
            if len(h1):
                last=h1.iloc[-1];ao=float(last.get('ao',float('nan')))
                aligned=bool(last.get('bullish_alligator' if spec.side=='BUY' else 'bearish_alligator',False)) and (ao>0 if spec.side=='BUY' else ao<0)
            active_h1=any(s.role.value=='ENTRY' and s.side==spec.side and s.strategy_profile==spec.strategy_profile and
                s.signal_type==spec.signal_type and s.signal_bar_time_ms+3_600_000<=now<s.signal_bar_time_ms+18_000_000 for s in h1specs)
            stop=spec.protective_reference;airbag=None
            if spec.strategy_profile==COURSE:
                airbag=course_airbag_stop('LONG' if spec.side=='BUY' else 'SHORT',wave_bars_from_dataframe(frame(hours[-80:])),TICKS[symbol]) if len(hours)>=40 else None
                if airbag is not None:stop=min(stop,airbag) if spec.side=='BUY' else max(stop,airbag)
            result=outcome(spec,rows,now,stop)
            events.append(dict(signal_id=spec.signal_id,profile=spec.strategy_profile,family=spec.signal_type.value,side=spec.side,
                signal_bar_time_ms=spec.signal_bar_time_ms,known_at_ms=spec.signal_bar_time_ms+M15,first_observed_at_ms=now,
                trigger=spec.trigger_price,protective_stop=stop,course_h1_airbag=airbag,
                h1_closed_at_ms=hour_key+3_600_000,h1_alligator_ao_aligned=aligned,
                h1_same_family_already_observed=active_h1,
                minutes_before_next_h1_close=(3_600_000-now%3_600_000)//60_000 if now%3_600_000 else 0,
                can_place_orders=False,**result))
    return dict(file=path.name,symbol=symbol,source_sha256=hashlib.sha256(path.read_bytes()).hexdigest(),source_bars=len(rows),
        source_start_ms=int(rows[0][0]),source_end_ms=int(rows[-1][0])+M15,evaluated_prefixes=len(range(max(320,len(rows)-evaluation_bars+1),len(rows)+1,stride)),
        future_poison_comparisons=comparisons,outcomes=dict(Counter(e['result'] for e in events)),events=events,
        seconds=round(time.monotonic()-started,2))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data-dir',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--stride',type=int,default=1);parser.add_argument('--evaluation-bars',type=int,default=192)
    parser.add_argument('--workers',type=int,default=2);parser.add_argument('--pattern',default='*_15m.rows.json');args=parser.parse_args()
    if min(args.stride,args.evaluation_bars,args.workers)<1:parser.error('stride, evaluation-bars and workers must be positive')
    results=[];args.output.mkdir(parents=True,exist_ok=True)
    from concurrent.futures import ProcessPoolExecutor
    from functools import partial
    paths=sorted(args.data_dir.glob(args.pattern))
    if not paths:parser.error('No input series matched')
    pool=ProcessPoolExecutor(max_workers=args.workers)
    for path,result in zip(paths,pool.map(partial(replay,stride=args.stride,evaluation_bars=args.evaluation_bars),paths)):
        results.append({k:v for k,v in result.items() if k!='events'})
        (args.output/(path.stem+'_research.json')).write_text(json.dumps(result,indent=2)+'\n')
        print(result['file'],result['evaluated_prefixes'],result['outcomes'],result['seconds'],flush=True)
    pool.shutdown()
    counts=Counter()
    for r in results:counts.update(r['outcomes'])
    summary=dict(schema='WILLIAMS_M15_RESEARCH_V1',source='BINANCE_PUBLIC_USD_M_MAINNET_HISTORICAL_KLINES',
        execution_enabled=False,can_place_orders=False,source_series=len(results),input_bars=sum(r['source_bars'] for r in results),
        evaluated_prefixes=sum(r['evaluated_prefixes'] for r in results),future_poison_comparisons=sum(r['future_poison_comparisons'] for r in results),
        outcomes=dict(counts),series=results,stride=args.stride,evaluation_bars_per_series=args.evaluation_bars,
        tick_policy="FIXED_RESEARCH_GRID_NOT_A_CLAIM_ABOUT_HISTORICAL_EXCHANGE_FILTERS",
        conclusion='M15_EXECUTION_NOT_APPROVED_BY_THIS_RESEARCH',
        limitations=['H4/D1, portfolio risk, order book, funding, fees, slippage and exchange admission are not replayed',
            'Level outcomes are OHLC research proxies, not fills or full Williams campaign returns',
            'A formation satisfying detector rules may still lose; no zero-false-signal claim',
            'H1 comparison means same family/direction/source observed at the same time; no cross-timeframe wave identity is inferred'])
    (args.output/'m15_summary.json').write_text(json.dumps(summary,indent=2)+'\n')
    print(json.dumps({k:v for k,v in summary.items() if k!='series'}),flush=True)

if __name__=='__main__':main()
