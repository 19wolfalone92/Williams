#!/usr/bin/env python3
"""Check source provenance and measured wave-label skill; fail closed without human truth.

Usage: python tools/audit_independent_wave_labels.py --output /tmp/result.json
       python tools/audit_independent_wave_labels.py --annotations frozen.json
           --predictions blind_replay.json --require-independent --output result.json

Without independent hand labels, this DOES NOT produce a fake accuracy number.
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import sys
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from williams_wave_label_quality import evaluate_wave_label_quality

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--annotations",type=Path)
    ap.add_argument("--predictions",type=Path)
    ap.add_argument("--output",type=Path,required=True)
    ap.add_argument("--require-independent",action="store_true")
    args=ap.parse_args()
    if bool(args.annotations)!=bool(args.predictions):
        ap.error("--annotations and --predictions must be supplied together")
    a=json.loads(args.annotations.read_text()) if args.annotations else []
    p=json.loads(args.predictions.read_text()) if args.predictions else []
    if not isinstance(a,list) or not isinstance(p,list):
        ap.error("Expected top-level JSON arrays")
    result=evaluate_wave_label_quality(a,p)
    historical=ROOT/"app/src/test/resources/williams_historical_wave_cases.json"
    if historical.exists():
        source=json.loads(historical.read_text())
        source_cases=source.get("cases",[])
        result["existing_engine_discovered_cases"]=len(source_cases)
        result["existing_engine_discovered_source"]=source.get("source")
        result["existing_engine_discovered_usable_as_independent_truth"]=False
        result["existing_case_symbols"]=sorted({c["symbol"] for c in source_cases})
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+"\n")
    print(json.dumps({k:result[k] for k in ("status","independent_eligible",
        "existing_engine_discovered_cases","accuracy_certified") if k in result},
        ensure_ascii=False))
    return 2 if args.require_independent and result["status"]!="ELIGIBLE_MEASUREMENT_REQUIRES_HUMAN_SIGNOFF" else 0

if __name__=="__main__":
    raise SystemExit(main())
