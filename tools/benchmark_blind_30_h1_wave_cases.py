#!/usr/bin/env python3
"""Frozen, blinded 30-panel Binance H1 price-grammar benchmark (read only).

Not a verified independent expert Elliott annotation. Review labels are
second-implementation OHLC PRICE GRAMMAR proxy and must NOT be passed as
INDEPENDENT_HUMAN_OHLC. Intentionally report *exploratory agreement only*.
"""
from __future__ import annotations
import argparse,hashlib,json
from collections import Counter
from datetime import datetime,timezone
from pathlib import Path
import sys
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from wave_blind_reference_price_grammar import blind_reference
from williams_wave_hierarchy import FRAME_MS,WaveBar,build_wave_hierarchy

SYMBOLS=("BTCUSDT","ETHUSDT","BNBUSDT","SOLUSDT","XRPUSDT")
DATES=("2024-11-18T12:00:00+00:00","2024-11-20T12:00:00+00:00",
       "2024-11-22T12:00:00+00:00","2024-11-24T12:00:00+00:00",
       "2024-11-26T12:00:00+00:00","2024-11-28T12:00:00+00:00")
def digest(obj):
    return hashlib.sha256(json.dumps(obj,sort_keys=True,separators=(",",":"),ensure_ascii=True).encode()).hexdigest()

def model_label(rows,now):
    bars=[WaveBar(int(r[0]),float(r[1]),float(r[2]),float(r[3]),float(r[4]),float(r[5])) for r in rows]
    report=build_wave_hierarchy({"1h":bars},now)
    # A uniquely dominant 1h latest completed W5 still requires expert
    # confirmation before considering it a certified Elliott wave.
    candidates=[s for s in report.scenarios if s.frame=="1h" and
        s.status=="PROVISIONAL" and s.anchors and
        s.anchors[-1].known_at<=now and
        0<=now-s.anchors[-1].known_at<=30*3_600_000]
    five=[s for s in candidates if len(s.anchors)==6 and s.label=="W5"]
    # Opposite candidates, ambiguous degrees or partial structures abstain.
    if len({(s.direction,s.anchors[0].extreme_at) for s in five})==1 and five:
        return "MOTIVE_FIVE",{"reason":"CAUSAL_W5_HYPOTHESIS_NOT_EXPERT_CERTIFIED",
              "scenario_ids":[s.scenario_id for s in five],"num_hypotheses":len(report.scenarios)}
    patterns=report.to_dict().get("complex_wave_patterns",[])
    corrections=[x for x in patterns if x.get("frame")=="1h" and
        x.get("known_at",now)<=now and
        0<=now-x.get("known_at",now)<=30*3_600_000 and
        any(y in x.get("pattern","") for y in ("ZIGZAG","FLAT","TRIANGLE","COMBINATION"))]
    if len(corrections)==1:
        pat=corrections[0].get("pattern","")
        return ("COMPLEX_CORRECTION" if "TRIANGLE" in pat or "COMBINATION" in pat
            else "CORRECTION_ABC"),{"reason":"ONE_COMPLEX_PRICE_HYPOTHESIS",
                                    "pattern":pat,"num_hypotheses":len(report.scenarios)}
    return "UNRESOLVED",{"reason":"NO_UNIQUE_CAUSAL_WAVE_HYPOTHESIS",
        "num_hypotheses":len(report.scenarios),"candidate_w5":len(five),
        "complex_correction_candidates":len(corrections)}

def load_source(symbol):
    f=ROOT/f"app/src/test/resources/blind_wave_20241109_29_{symbol}.json"
    s=json.loads(f.read_text(encoding="utf-8"))
    rows=s["rows"]
    assert s["source"]=="BINANCE_PUBLIC_USDM_FUTURES_CLOSED_H1"
    assert s["symbol"]==symbol and len(rows)==480
    assert all(rows[i][0]-rows[i-1][0]==3_600_000 for i in range(1,len(rows)))
    return rows,digest(rows)

def run(output,charts=False):
    output.mkdir(parents=True,exist_ok=True)
    cases=[]
    for symbol in SYMBOLS:
        full,full_hash=load_source(symbol)
        for time in DATES:
            now=int(datetime.fromisoformat(time).timestamp()*1000)
            available=[r for r in full if r[0]+3_600_000<=now]
            assert len(available)>=160
            prefix=available[-160:]
            # Phase I: write BLIND proxy reference without importing/calling
            # Williams inference. Keeping frozen source hash and cutoff.
            reference=blind_reference(prefix)
            cases.append({"symbol":symbol,"frame":"1h","as_of":now,
                "source":"BINANCE_PUBLIC_USDM_FUTURES_CLOSED_H1",
                "source_window_sha256":full_hash,"prefix_sha256":digest(prefix),
                "reference_origin":"SECOND_IMPLEMENTATION_PRICE_GRAMMAR_NOT_HUMAN",
                "human_independent_expert_reviewed":False,
                "reference_label":reference["label"],"reference_reason":reference["reason"],
                "reference_pivots":reference.get("anchors",[]),
                "_prefix":prefix})
    assert len(cases)==30
    reference_export=[{k:v for k,v in c.items() if k!="_prefix"} for c in cases]
    (output/"blind_reference_frozen.json").write_text(json.dumps({
        "status":"EXPLORATORY_RULE_PROXY_NOT_EXPERT_ACCEPTANCE",
        "frozen_selection":list(DATES),"cases":reference_export},ensure_ascii=False,indent=2)+"\n")
    confusion=Counter();abstain=0;matched=0
    for c in cases:
        prediction,evidence=model_label(c["_prefix"],c["as_of"])
        c["model_label"]=prediction
        c["model_evidence"]=evidence
        c.pop("_prefix")
        if prediction=="UNRESOLVED":
            abstain+=1
        else:
            confusion[(c["reference_label"],prediction)]+=1
            matched+=prediction==c["reference_label"]
    comparable=len(cases)-abstain
    metrics={"schema":"WILLIAMS_BLIND_H1_RULE_PROXY_30_V1",
        "source":"REAL_BINANCE_H1_CLOSED_CANDLES_NOT_SYNTHETIC",
        "snapshot_count":len(cases),"instruments":list(SYMBOLS),
        "decision_times_utc":list(DATES),
        "reference_origin":"SECOND_IMPLEMENTATION_PRICE_GRAMMAR_NOT_HUMAN",
        "independent_expert_annotations":0,
        "label_counts":dict(Counter(c["reference_label"] for c in cases)),
        "prediction_counts":dict(Counter(c["model_label"] for c in cases)),
        "classified":comparable,"abstained":abstain,
        "coverage":comparable/len(cases),
        "agreement_on_classified":matched/comparable if comparable else None,
        "accuracy_vs_human_expert":None,"independent_accuracy_certified":False,
        "confusion":[{"reference":k[0],"predicted":k[1],"count":v}
                     for k,v in sorted(confusion.items())],
        "can_place_orders":False,"can_close":False,"can_reverse":False}
    (output/"model_vs_proxy_results.json").write_text(json.dumps({"metrics":metrics,
       "cases":cases},ensure_ascii=False,indent=2)+"\n")
    if charts:
        try:
            import matplotlib
            matplotlib.use("Agg")
            import matplotlib.pyplot as plt
        except ImportError as exc:
            raise RuntimeError("matplotlib required to render blind review charts") from exc
        for c in reference_export:
            full,_=load_source(c["symbol"])
            rows=[r for r in full if r[0]+3_600_000<=c["as_of"]][-160:]
            fig,ax=plt.subplots(figsize=(11,3.6))
            ax.plot(range(len(rows)),[float(x[4]) for x in rows],lw=1.4)
            ax.set_title(f"BLIND {c['symbol']} H1, closed through {datetime.fromtimestamp(c['as_of']/1000,timezone.utc).isoformat()} | 160 bars")
            ax.set_xlabel("Closed H1 bar index; no future bars or model labels")
            ax.set_ylabel("USDT")
            fig.tight_layout()
            name=f"{c['symbol']}_{c['as_of']}.png"
            fig.savefig(output/name,dpi=115)
            plt.close(fig)
    return metrics

if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--output",type=Path,required=True)
    p.add_argument("--charts",action="store_true")
    args=p.parse_args()
    m=run(args.output,charts=args.charts)
    print(json.dumps({k:m[k] for k in ("snapshot_count","instruments","classified",
       "abstained","coverage","agreement_on_classified","label_counts",
       "prediction_counts","independent_expert_annotations",
       "accuracy_vs_human_expert")},ensure_ascii=False))
