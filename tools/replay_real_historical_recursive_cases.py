#!/usr/bin/env python3
"""Execute causal-only real historical nested-wave cases; never label accuracy."""
import json,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from williams_wave_hierarchy import WaveAnchor,FRAME_MS
from williams_wave_subdivisions import trace_recursive_price_grammar
def main():
    data=json.loads((ROOT/"app/src/test/resources/williams_historical_wave_cases.json").read_text())
    rows=[]
    for c in data["cases"]:
        if c["operation"]!="SUBDIVISION": continue
        parents=[WaveAnchor(**v) for v in c["parent"]]
        kids=[WaveAnchor(**v) for v in c["child"]]
        out=trace_recursive_price_grammar(c["candidate"]["pattern"],parents,
            {c["frame"]:parents,c["child_frame"]:kids},FRAME_MS,c["frame"],c["as_of"],depth=1)
        rows.append({"symbol":c["symbol"],"frame":c["frame"],"child_frame":c["child_frame"],
            "as_of":c["as_of"],"pattern":c["candidate"]["pattern"],
            "status":out["status"],"wave_truth_from_independent_human":False,
            "can_place_orders":out["can_place_orders"]})
    passed=bool(rows) and all(x["status"]=="OBSERVED_DEPTH_PRICE_GRAMMAR_CONFIRMED" for x in rows)
    return {"status":"CAUSAL_PRICE_GRAMMAR_PASS_NOT_INDEPENDENT_ACCURACY" if passed else "FAIL",
        "source":data.get("source"),"cases":rows,"count":len(rows),
        "independent_elliott_accuracy":None,"can_authorize_trading":False}
if __name__=="__main__":
    import argparse
    p=argparse.ArgumentParser()
    p.add_argument("--output",type=Path,required=True)
    a=p.parse_args()
    out=main()
    a.output.parent.mkdir(parents=True,exist_ok=True)
    a.output.write_text(json.dumps(out,ensure_ascii=False,indent=2)+"\n")
    print(out["status"],out["count"])
    raise SystemExit(0 if out["status"]=="CAUSAL_PRICE_GRAMMAR_PASS_NOT_INDEPENDENT_ACCURACY" else 1)
