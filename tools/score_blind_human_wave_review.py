#!/usr/bin/env python3
"""Score TWO independently submitted blind 30/90-chart reviews without faking experts.

Input is the exact blank_annotator_form.csv schema in the published charts-only
packet. Existing model/proxy outputs are NOT opened until BOTH forms pass
causal, cutoff, source SHA and distinct-reviewer validation.

A self-declared reviewer_id or even matching pair of CSV forms is NOT verified
human identity, and cannot certify wave accuracy or enable financial effects.
"""
from __future__ import annotations
import argparse,csv,hashlib,json,sys
from collections import Counter
from datetime import datetime,timezone
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from tools.benchmark_blind_30_h1_wave_cases import SYMBOLS,ALL_15,DATES,digest,load_source,model_label
from williams_wave_label_quality import LABELS

FIELDS={"symbol","frame","as_of","prefix_sha256","source_window_sha256",
 "reviewer_id","expert_label","degree","direction","pivot_times_utc",
 "alternative_count","confidence_0_1","reason_ru","review_frozen_at_utc"}

def iso_to_ms(value:str)->int:
    try:
        dt=datetime.fromisoformat(value.replace("Z","+00:00"))
        if dt.tzinfo is None or dt.utcoffset().total_seconds()!=0:
            raise ValueError("NOT_UTC")
        return int(dt.timestamp()*1000)
    except (ValueError,TypeError,AttributeError,OverflowError) as e:
        raise ValueError("INVALID_UTC_TIMESTAMP") from e

def selected_prefixes(*,all_fifteen:bool=False):
    out={}
    for symbol in (ALL_15 if all_fifteen else SYMBOLS):
        source,source_hash=load_source(symbol)
        for dt in DATES:
            as_of=iso_to_ms(dt)
            prefix=[r for r in source if r[0]+3600000<=as_of][-160:]
            if len(prefix)!=160:raise ValueError("SOURCE_CLOSED_PREFIX_TOO_SHORT")
            out[(symbol,"1h",as_of)]=(prefix,digest(prefix),source_hash)
    assert len(out)==(90 if all_fifteen else 30)
    return out

def load_blind_reviewer_csv(path:Path,expected:dict,compared_at:int):
    with path.open(newline="",encoding="utf-8-sig") as f:
        reader=csv.DictReader(f)
        if not reader.fieldnames or set(reader.fieldnames)!=FIELDS:
            raise ValueError("REVIEW_CSV_SCHEMA_MISMATCH")
        rows=list(reader)
    if len(rows)!=len(expected):raise ValueError("EXPECTED_ALL_FROZEN_ANNOTATIONS")
    output={}
    ids=set()
    for line,row in enumerate(rows,2):
        try:
            key=(row["symbol"],row["frame"],int(row["as_of"]))
            if key not in expected or key in output:
                raise ValueError("UNKNOWN_OR_DUPLICATE_BLIND_CASE")
            prefix,prefix_sha,source_sha=expected[key]
            if row["prefix_sha256"]!=prefix_sha or row["source_window_sha256"]!=source_sha:
                raise ValueError("RAW_CLOSED_OHLC_CHECKSUM_MISMATCH")
            rid=row["reviewer_id"].strip()
            if len(rid)<3:raise ValueError("MISSING_REVIEWER_ID")
            ids.add(rid)
            label=row["expert_label"].strip()
            if label not in LABELS:raise ValueError("UNKNOWN_OR_MISSING_WAVE_LABEL")
            frozen=iso_to_ms(row["review_frozen_at_utc"])
            if not 0<frozen<compared_at:raise ValueError("NOT_FROZEN_BEFORE_COMPARISON")
            confidence=float(row["confidence_0_1"])
            if not 0<=confidence<=1:raise ValueError("INVALID_CONFIDENCE")
            alt=int(row["alternative_count"])
            if alt<0 or alt>100:raise ValueError("INVALID_ALTERNATIVE_COUNT")
            degree=row["degree"].strip()
            direction=row["direction"].strip()
            if direction not in ("UP","DOWN","UNRESOLVED"):
                raise ValueError("INVALID_DIRECTION")
            if not degree:raise ValueError("MISSING_WAVE_DEGREE")
            reason=row["reason_ru"].strip()
            if len(reason)<12:raise ValueError("MISSING_EXPLANATORY_WAVE_REASON")
            # Two closed RIGHT bars need to be available after any claimed pivot:
            # t, t+1h and t+2h; their close at pivot+3h.
            pivots=[p.strip() for p in row["pivot_times_utc"].replace("|",";").split(";") if p.strip()]
            pivot_at=[iso_to_ms(p) for p in pivots]
            available_opens={r[0] for r in prefix}
            if (len(set(pivot_at))!=len(pivot_at)
                or pivot_at!=sorted(pivot_at)
                or any(t not in available_opens or t+3_600_000*3>key[2]
                       for t in pivot_at)):
                raise ValueError("PIVOT_FUTURE_LEAK_OR_NON_CLOSED_CANDLE")
            if label!="NO_VALID_COUNT" and not pivot_at:
                raise ValueError("POSITIVE_WAVE_REQUIRES_CONFIRMED_PIVOT_TIMES")
            if label=="NO_VALID_COUNT" and direction!="UNRESOLVED":
                raise ValueError("UNRESOLVED_WAVE_MUST_NOT_INVENT_DIRECTION")
            output[key]={"label":label,"degree":degree,"direction":direction,
                "pivot_times_utc":pivots,"alternatives":alt,
                "confidence":confidence,"reason_ru":reason,
                "reviewer_id":rid,"frozen_at":frozen}
        except (ValueError,KeyError,TypeError) as e:
            raise ValueError(f"INVALID_BLIND_FORM_ROW_{line}:{e}") from e
    if len(output)!=len(expected) or len(ids)!=1:
        raise ValueError("REVIEWER_NOT_UNIQUE_OR_CASE_MISSING")
    return next(iter(ids)),output,hashlib.sha256(path.read_bytes()).hexdigest()

def compare_reviews(first:Path,second:Path,*, compared_at:int|None=None,all_fifteen:bool=False):
    timestamp=compared_at if compared_at is not None else int(datetime.now(timezone.utc).timestamp()*1000)
    source=selected_prefixes(all_fifteen=all_fifteen)
    ida,a,sha_a=load_blind_reviewer_csv(first,source,timestamp)
    idb,b,sha_b=load_blind_reviewer_csv(second,source,timestamp)
    if ida==idb or sha_a==sha_b:raise ValueError("TWO_DISTINCT_REVIEWERS_REQUIRED")
    confusion=Counter();agree=0;consensus={};disputes=[]
    for key in sorted(source):
        x,y=a[key],b[key]
        confusion[(x["label"],y["label"])]+=1
        if x["label"]==y["label"]:
            agree+=1;consensus[key]=x["label"]
        else:
            disputes.append({"symbol":key[0],"frame":key[1],"as_of":key[2],
                "first":x["label"],"second":y["label"],
                "reason":"INDEPENDENT_EXPERT_ADJUDICATION_REQUIRED"})
    by_a=Counter(x["label"] for x in a.values())
    by_b=Counter(x["label"] for x in b.values())
    n=len(source)
    expected_chance=sum(by_a[z]*by_b[z] for z in LABELS)/(n*n)
    observed=agree/n
    kappa=(observed-expected_chance)/(1-expected_chance) if expected_chance<1 else None
    # ONLY after both forms are frozen / validated: perform production inference.
    matched=0;classified=0;abstained=0;scored=Counter();rows=[]
    for key in sorted(source):
        prefix,prefix_sha,_=source[key]
        label,evidence=model_label(prefix,key[2])
        ref=consensus.get(key)
        if ref is not None:
            if label=="UNRESOLVED":abstained+=1
            else:
                classified+=1;matched+=label==ref;scored[(ref,label)]+=1
        rows.append({"symbol":key[0],"frame":key[1],"as_of":key[2],
             "raw_ohlc_sha256":prefix_sha,
             "consensus_label":ref,"first":a[key]["label"],"second":b[key]["label"],
             "prediction":label,"prediction_evidence":evidence,
             "can_place_orders":False})
    return {"schema":f"WILLIAMS_BLIND_{n}_TWO_REVIEWERS_V1",
        "status":"EXPLORATORY_TWO_SELF_DECLARED_REVIEWERS_NOT_ATTESTED",
        "total_cases":n,"reviewer_ids":[ida,idb],
        "review_csv_sha256":[sha_a,sha_b],
        "agreement_count":agree,"disagreement_count":n-agree,
        "inter_rater_agreement":observed,"cohen_kappa":kappa,
        "consensus_cases":len(consensus),"consensus_conflicts":disputes,
        "classified_consensus":classified,"abstained_consensus":abstained,
        "model_coverage_on_consensus":classified/len(consensus) if consensus else None,
        "exploratory_agreement_on_classified":matched/classified if classified else None,
        "model_vs_consensus_confusion":[{"expert_consensus":a,"model":b,"count":n}
            for (a,b),n in sorted(scored.items())],
        "reviewer_identity_and_independence_verified":False,
        "human_elliott_accuracy_certified":False,
        "wave_degree_accuracy_certified":False,
        "trade_profitability_certified":False,
        "can_place_orders":False,"can_close":False,"can_reverse":False,
        "cases":rows}

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--reviewer-a",required=True,type=Path)
    p.add_argument("--reviewer-b",required=True,type=Path)
    p.add_argument("--output",required=True,type=Path)
    p.add_argument("--all-fifteen",action="store_true",help="90 frozen blind charts on 15 USD-M pairs")
    args=p.parse_args()
    try:result=compare_reviews(args.reviewer_a,args.reviewer_b,all_fifteen=args.all_fifteen)
    except (ValueError,OSError) as e:
        print(f"FAIL_CLOSED_TWO_REVIEWER_CONTRACT:{e}",file=sys.stderr)
        return 2
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps({k:result[k] for k in ("total_cases","agreement_count","cohen_kappa",
      "classified_consensus","exploratory_agreement_on_classified","human_elliott_accuracy_certified")},
      ensure_ascii=False))
    return 0
if __name__=="__main__":raise SystemExit(main())
