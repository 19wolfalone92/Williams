#!/usr/bin/env python3
"""Validate exported physical Futures Demo evidence WITHOUT exchange access."""
from __future__ import annotations
import argparse,json
from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from williams_demo_evidence_gate import validate_demo_device_evidence

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--input",type=Path,required=True)
    p.add_argument("--output",type=Path,required=True)
    a=p.parse_args()
    result=validate_demo_device_evidence(json.loads(a.input.read_text()))
    a.output.parent.mkdir(parents=True,exist_ok=True)
    a.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+"\n")
    print(result["status"],result["errors"])
    return 1 if result["status"]=="CONTRACT_FAIL" else 0

if __name__=="__main__":raise SystemExit(main())
