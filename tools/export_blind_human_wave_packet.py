#!/usr/bin/env python3
"""Export sealed chart+blank-label reviewer packet. NO model answers or rule proxies."""
from __future__ import annotations
import argparse,csv,json,shutil
from datetime import datetime,timezone
from pathlib import Path

FIELDS=["symbol","frame","as_of","prefix_sha256","source_window_sha256",
        "reviewer_id","expert_label","degree","direction","pivot_times_utc",
        "alternative_count","confidence_0_1","reason_ru","review_frozen_at_utc"]
def export_packet(blind_source:Path,chart_dir:Path,output:Path):
    source=json.loads(blind_source.read_text(encoding="utf-8"))
    rows=source.get("cases",source.get("snapshots",[]))
    if len(rows) not in (30,90) or len({(r["symbol"],r["as_of"]) for r in rows})!=len(rows):
        raise ValueError("INVALID_BLIND_CASE_SELECTION")
    output.mkdir(parents=True,exist_ok=True)
    dest=output/"charts";dest.mkdir(exist_ok=True)
    with (output/"blank_annotator_form.csv").open("w",newline="",encoding="utf-8") as fh:
        writer=csv.DictWriter(fh,fieldnames=FIELDS)
        writer.writeheader()
        for r in rows:
            src=chart_dir/f'{r["symbol"]}_{r["as_of"]}.png'
            if not src.is_file():raise ValueError(f"MISSING_BLIND_CHART:{src.name}")
            shutil.copyfile(src,dest/src.name)
            d={k:r.get(k,"") for k in FIELDS}
            for k in ("reviewer_id","expert_label","degree","direction","pivot_times_utc",
                      "alternative_count","confidence_0_1","reason_ru","review_frozen_at_utc"):
                d[k]=""
            writer.writerow(d)
    guide=f"""# BLINDED Elliott-wave review — {len(rows)} fixed H1 historical windows

These are **real closed** Binance USD-M Futures price series; the time at
top of each chart is the last information available to the reviewer.
**DO NOT** open model scores / engine diagnostics / future charts while annotating.
A wave that is not uniquely established should be marked NO_VALID_COUNT,
with alternative hypotheses documented, not forced into an ideal picture.

Expert labels: MOTIVE_FIVE, CORRECTION_ABC, COMPLEX_CORRECTION, NO_VALID_COUNT.
Specify wave degree, direction, timestamps of confirmed extrema, alternatives,
confidence and the causal invalidation reason for your chosen count.
Do not invent timepoints from graphical pixels; use the stored OHLC if needed.
Submit a signed frozen CSV **before** running the prediction comparison.
Prefer two independent reviewers and an adjudicator if labels disagree.

This packet is intentionally devoid of model outputs and proxy reference labels.
Automated proxy annotations have NOT been accepted as independent human expertise.
"""
    (output/"INSTRUCTIONS_FOR_INDEPENDENT_ANNOTATOR.md").write_text(guide,encoding="utf-8")
    checklist={"schema":f"WILLIAMS_{len(rows)}_REAL_BLIND_H1_PACKET",
      "closed_market_data":"BINANCE_USDM_FUTURES_PUBLIC_H1",
      "cases":len(rows),"labels_in_packet":0,"includes_model_outputs":False,
      "expert_review_completed":False}
    (output/"packet_manifest.json").write_text(json.dumps(checklist,indent=2)+"\n",encoding="utf-8")
    return checklist

if __name__=="__main__":
    p=argparse.ArgumentParser()
    p.add_argument("--source",type=Path,required=True)
    p.add_argument("--charts",type=Path,required=True)
    p.add_argument("--output",type=Path,required=True)
    x=p.parse_args()
    print(json.dumps(export_packet(x.source,x.charts,x.output)))
