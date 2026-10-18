"""Reproducible, read-only historical wave audit; never simulates order fills.

Input is stored public USD-M M15 OHLCV. Higher bars are aggregated from the
same complete source candles. An independent event-based fractal reference
is checked against the production prefix extractor; completed structures are
then audited with their actual lower-frame endpoints and negative controls.
This establishes causal geometry, not a human-labelled Elliott ground truth.
"""
import argparse
from bisect import bisect_right
from collections import Counter,defaultdict
from dataclasses import asdict,replace
import hashlib
import json
from math import isfinite
from pathlib import Path
import sys

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from williams_complex_waves import detect_complex_wave_patterns
from williams_wave_hierarchy import FRAME_MS,WaveBar,WaveAnchor,confirmed_wave_anchors,build_wave_hierarchy
from williams_wave_subdivisions import enrich_complex_subdivisions,prove_wave_subdivisions

FRAMES=("1d","6h","1h","15m")


def aggregate(source,duration):
    groups=defaultdict(list)
    for b in source:groups[b.t//duration*duration].append(b)
    count=duration//FRAME_MS["15m"]
    return [WaveBar(t,g[0].o,max(b.h for b in g),min(b.l for b in g),g[-1].c,sum(b.v for b in g))
            for t,g in sorted(groups.items()) if len(g)==count and g[0].t==t and g[-1].t+FRAME_MS["15m"]==t+duration]


def reference_events(bars,duration):
    """Keep separate high/low confirmation events, including outside bars.

    Each side needs two strictly different neighbours; an equal extreme to
    the right displaces the earlier candidate. Ambiguity is resolved using
    only the events already knowable at the queried instant.
    """
    events=[]
    by_kind={"LOW":[b.l for b in bars],"HIGH":[b.h for b in bars]}
    for i in range(2,len(bars)-2):
        choices=[]
        for kind in ("LOW","HIGH"):
            prices=by_kind[kind]
            price=prices[i]
            previous=[]
            j=i-1
            while j>=0 and len(previous)<2:
                if prices[j]!=price:previous.append(prices[j])
                j-=1
            if len(previous)!=2 or not all((p>price if kind=="LOW" else p<price) for p in previous):continue
            following=[];confirmed=0
            for j in range(i+1,len(bars)):
                if prices[j]==price:break
                following.append(prices[j])
                if len(following)==2:
                    if all((p>price if kind=="LOW" else p<price) for p in following):confirmed=bars[j].t+duration
                    break
            if confirmed:choices.append(WaveAnchor(kind,price,i,bars[i].t,confirmed))
        if choices:events.append(choices)
    return events


def reference_at(events,as_of):
    raw=[]
    for choices in events:
        known=[p for p in choices if p.known_at<=as_of]
        if len(known)==1:raw.append(known[0])
    out=[]
    for p in raw:
        if not out or p.kind!=out[-1].kind:out.append(p)
        elif (p.price>out[-1].price if p.kind=="HIGH" else p.price<out[-1].price):out[-1]=p
    return out


def independent_proof_check(parent,children,proof,duration,as_of):
    """Audit every claimed leg from original prices rather than its label."""
    if proof.status!="PRICE_SUBDIVISIONS_CONFIRMED":return
    assert all(p.known_at<=as_of for p in parent)
    selected=[]
    for p in parent:
        hits=[i for i,c in enumerate(children) if c.kind==p.kind and p.extreme_at<=c.extreme_at<p.extreme_at+duration
              and abs(p.price-c.price)<=max(1e-9,p.price*1e-9)]
        assert len(hits)==1
        selected.append(hits[0])
    actual=tuple(b-a for a,b in zip(selected,selected[1:]))
    assert actual==proof.expected==proof.observed
    assert proof.child_known_at<=as_of
    for a,b,n in zip(selected,selected[1:],proof.expected):
        leg=children[a:b+1]
        assert len(leg)==n+1 and all(p.known_at<=as_of for p in leg)
        assert all(x.extreme_at<y.extreme_at and x.kind!=y.kind for x,y in zip(leg,leg[1:]))
        origin,end=leg[0].price,leg[-1].price
        assert all(min(origin,end)<=p.price<=max(origin,end) for p in leg)
        direction=1 if end>origin else -1
        displacements=[direction*(p.price-origin) for p in leg]
        assert displacements[1]>displacements[2]>0
        if n==3:assert displacements[3]>displacements[1]
        else:
            assert displacements[3]>displacements[4]>displacements[1]
            assert displacements[5]>displacements[3]
            motive=[abs(leg[i+1].price-leg[i].price) for i in (0,2,4)]
            assert motive[1]>=min(motive[0],motive[2])


def audit(root,output):
    pages=sorted(root.glob("*_15m_*.json"))
    assert pages,"No stored historical pages"
    by_symbol=defaultdict(list)
    for page in pages:
        data=json.loads(page.read_text())
        assert data["source"]=="BINANCE_PUBLIC_USDS_M_MAINNET" and data["interval"]=="15m"
        by_symbol[data["symbol"]].extend(data["rows"])
    totals=Counter();coverage=defaultdict(Counter);examples=[];results=[]
    for symbol,rows in sorted(by_symbol.items()):
        rows=sorted(rows,key=lambda r:int(r[0]))
        source=[WaveBar(int(r[0]),*[float(v) for v in r[1:6]]) for r in rows]
        assert len({b.t for b in source})==len(source)
        assert all(b.t%FRAME_MS["15m"]==0 and min(b.o,b.l,b.c)>0 and b.l<=min(b.o,b.c)<=max(b.o,b.c)<=b.h
                   and b.v>=0 and all(isfinite(v) for v in (b.o,b.h,b.l,b.c,b.v)) for b in source)
        assert all(b.t-a.t==FRAME_MS["15m"] for a,b in zip(source,source[1:]))
        frames={f:aggregate(source,FRAME_MS[f]) for f in FRAMES}
        events={f:reference_events(bars,FRAME_MS[f]) for f,bars in frames.items()}
        starts={f:[b.t for b in bars] for f,bars in frames.items()}
        seen=set();symbol_counts=Counter()
        begin=frames["1d"][39].t+FRAME_MS["1d"]
        for bar in frames["1h"]:
            now=bar.t+FRAME_MS["1h"]
            if now<begin:continue
            known={f:reference_at(events[f],now) for f in FRAMES}
            totals["hourly_snapshots"]+=1;symbol_counts["hourly_snapshots"]+=1
            # Daily integration snapshots check all production pivots and the
            # actual D1 -> H6 -> H1 -> M15 graph, without future input candles.
            if now%FRAME_MS["1d"]==0:
                prefix={f:bars[:bisect_right(starts[f],now-FRAME_MS[f])] for f,bars in frames.items()}
                for f in FRAMES:
                    actual=confirmed_wave_anchors(prefix[f],FRAME_MS[f])
                    assert actual==known[f],(symbol,f,now,"REFERENCE_PREFIX_MISMATCH")
                    totals["independent_prefix_checks"]+=1
                graph=build_wave_hierarchy(prefix,now)
                assert not graph.frame_errors,(symbol,now,graph.frame_errors)
                assert all(not p["can_place_orders"] and p["known_at"]<=now for p in graph.complex_structures)
                assert all(p.known_at<=now for s in graph.scenarios for p in s.anchors)
                for s in graph.scenarios:
                    anchor=s.anchors[-1]
                    match=[b for b in prefix["15m"] if anchor.extreme_at<=b.t<anchor.extreme_at+FRAME_MS[s.frame]
                           and (b.h if anchor.kind=="HIGH" else b.l)==anchor.price]
                    assert match
                    after=match[-1].t+FRAME_MS["15m"]
                    assert s.leg_observation_start==after
                    if "LOWER_FRAME_OBSERVED_INVALIDATION" in s.invalid_reasons:
                        observed=[b for b in prefix["15m"] if b.t>=after]
                        assert any(b.l<=s.invalidation if s.direction=="UP" else b.h>=s.invalidation for b in observed)
                    totals["scenario_invalidation_checks"]+=1
                poisoned={f:bars+[WaveBar(now,1,float("nan"),0,1,-1)] for f,bars in prefix.items()}
                assert build_wave_hierarchy(poisoned,now).to_dict()==graph.to_dict(),(symbol,now,"FUTURE_BAR_LEAK")
                totals["future_poison_checks"]+=1
                totals["hierarchy_snapshots"]+=1
                totals["hierarchy_links_checked"]+=len(graph.links)
            for frame,child in zip(FRAMES,FRAMES[1:]):
                points=known[frame]
                for direction in ("UP","DOWN"):
                    for candidate in detect_complex_wave_patterns(points,direction=direction,as_of=now):
                        key=(frame,direction,candidate.pattern,candidate.start_at,candidate.end_at)
                        if key in seen:continue
                        seen.add(key)
                        parent=[p for p in points if candidate.start_at<=p.extreme_at<=candidate.end_at]
                        if candidate.pattern=="REGULAR_FLAT_ABC_CANDIDATE":
                            orientation=1 if direction=="UP" else -1
                            assert orientation*parent[-1].price<=orientation*parent[-3].price,(symbol,frame,now,"REGULAR_FLAT_C_DID_NOT_REACH_A")
                        proof=enrich_complex_subdivisions(candidate.pattern,parent,known[child],parent_duration=FRAME_MS[frame],as_of=now)
                        assert candidate.known_at<=now and not candidate.can_place_orders
                        if "INCOMPLETE_OR_TRUNCATED_C" in candidate.pattern:assert proof.status=="UNRESOLVED"
                        coverage[candidate.pattern][proof.status]+=1
                        coverage[candidate.pattern][proof.reason]+=1
                        totals["unique_candidates"]+=1
                        if proof.status=="PRICE_SUBDIVISIONS_CONFIRMED":
                            p=parent[-len(proof.expected)-1:]
                            independent_proof_check(p,known[child],proof,FRAME_MS[frame],now)
                            # Both controls derive from real observed endpoints.
                            for changed in ([c for c in known[child] if not(c.kind==p[-1].kind and p[-1].extreme_at<=c.extreme_at<p[-1].extreme_at+FRAME_MS[frame] and abs(c.price-p[-1].price)<=max(1e-9,p[-1].price*1e-9))],
                                            [replace(c,known_at=now+FRAME_MS[child]) for c in known[child]]):
                                assert prove_wave_subdivisions(p,changed,proof.expected,parent_duration=FRAME_MS[frame],as_of=now).status=="UNRESOLVED"
                                totals["negative_controls"]+=1
                            totals["confirmed_internal_proofs"]+=1
                            if len(examples)<60:examples.append({"symbol":symbol,"frame":frame,"child_frame":child,"as_of":now,
                                "candidate":candidate.to_dict(),"proof":proof.to_dict(),"parent":[asdict(x) for x in p],
                                "child":[asdict(x) for x in known[child] if p[0].extreme_at<=x.extreme_at<p[-1].extreme_at+FRAME_MS[frame]]})
        results.append({"symbol":symbol,"source_bars":len(source),"first_at":source[0].t,"last_close_at":source[-1].t+FRAME_MS["15m"],
                        "aggregated_bars":{f:len(b) for f,b in frames.items()},**symbol_counts})
        totals["source_bars"]+=len(source)
        print(symbol,len(seen),"unique candidates PASS",flush=True)
    result={"schema":1,"source":"Stored public Binance USD-M MAINNET M15 OHLCV; no authenticated actions",
            "scope":"Causal price/subdivision invariants with an independent OHLC pivot reference; not labelled Elliott accuracy or profitability",
            "totals":dict(totals),"failures":0,"coverage":{k:dict(v) for k,v in sorted(coverage.items())},
            "symbols":results,"examples":examples,"raw_files_sha256":{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in pages}}
    output.write_text(json.dumps(result,ensure_ascii=False,indent=2))
    print(json.dumps(dict(totals)),flush=True)


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-dir",type=Path,required=True)
    parser.add_argument("--output",type=Path,required=True)
    args=parser.parse_args()
    audit(args.data_dir,args.output)
