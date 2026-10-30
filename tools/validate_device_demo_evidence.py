#!/usr/bin/env python3
"""Validate exported physical Futures Demo evidence WITHOUT exchange access."""
from __future__ import annotations
import argparse,json
from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from williams_demo_evidence_gate import validate_demo_device_evidence, validate_demo_both_directions

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--input",type=Path,required=True)
    p.add_argument("--output",type=Path,required=True)
    p.add_argument("--dual",action="store_true",help="Require independent LONG and SHORT cycles")
    a=p.parse_args()
    payload=json.loads(a.input.read_text(encoding="utf-8"))
    validator=validate_demo_both_directions if a.dual else validate_demo_device_evidence
    result=validator(payload)
    a.output.parent.mkdir(parents=True,exist_ok=True)
    a.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(result["status"],result["errors"])
    print("OFFLINE STRUCTURAL CHECK ONLY — NO PHYSICAL DEVICE OR BINANCE ATTESTATION; APK RELEASE HOLD")
    return 1 if result["status"]=="CONTRACT_FAIL" else 0

if __name__=="__main__":raise SystemExit(main())
