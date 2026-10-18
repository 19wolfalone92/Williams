import sys,json,hashlib,argparse
from pathlib import Path
import pandas as pd
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from strategy import calculate_indicators,canonical_indicator_config_from_env
from williams_profiles import extract_profile_signals
from williams_edition_rules import PROFILES
from williams_wave_hierarchy import WaveBar,build_wave_hierarchy
parser=argparse.ArgumentParser(description="Offline causal-prefix checks for stored Binance USD-M public OHLC; never places orders")
parser.add_argument("--data-dir",type=Path,required=True)
parser.add_argument("--output",type=Path,required=True)
parser.add_argument("--as-of",type=int,required=True)
args=parser.parse_args()
root=args.data_dir
results=[];comparisons=0;signals={p:0 for p in PROFILES}
def normalize(specs):
    out=[]
    for s in specs:
        d=s.to_dict();d.pop("created_at_ms",None);out.append(d)
    return out
for name in sorted(root.glob("*_1h.json")):
    raw=json.loads(name.read_text());symbol=raw["symbol"]
    rows=raw["rows"]
    frame=pd.DataFrame([dict(open_time_ms=int(r[0]),open=float(r[1]),high=float(r[2]),low=float(r[3]),close=float(r[4]),volume=float(r[5])) for r in rows])
    # Fixed representative test parameters, NOT a claim about historical or
    # current exchange filters. Prefix equality is independent of grid choice;
    # no orders, execution simulation or profitability claim are made here.
    tick={"BTCUSDT":.1,"ETHUSDT":.01,"BNBUSDT":.01,"SOLUSDT":.001,"XRPUSDT":.0001,"DOGEUSDT":.00001,"ADAUSDT":.0001,"LINKUSDT":.001,"AVAXUSDT":.001,"LTCUSDT":.01}[symbol]
    full=calculate_indicators(frame,canonical_indicator_config_from_env())
    count=0
    for n in range(80,len(frame)+1,4):
        causal=calculate_indicators(frame.iloc[:n],canonical_indicator_config_from_env())
        for profile in PROFILES:
            for side in ("LONG","SHORT"):
                actual=normalize(extract_profile_signals(symbol,causal,profile=profile,side=side,timeframe="1h",tick_size=tick))
                cached=normalize(extract_profile_signals(symbol,full.iloc[:n],profile=profile,side=side,timeframe="1h",tick_size=tick))
                if actual!=cached:
                    raise AssertionError(json.dumps({"symbol":symbol,"n":n,"profile":profile,"side":side,"causal":actual,"future_cached":cached},ensure_ascii=False))
                comparisons+=1;count+=1;signals[profile]+=len(actual)
    frames={}
    for f in ("1d","6h","1h","15m"):
        data=json.loads((root/f"{symbol}_{f}.json").read_text())
        frames[f]=[WaveBar(int(r[0]),*[float(v) for v in r[1:6]]) for r in data["rows"]]
    report=build_wave_hierarchy(frames,args.as_of)
    if report.frame_errors:raise AssertionError((symbol,report.frame_errors))
    results.append({"symbol":symbol,"prefix_comparisons":count,"scenarios":len(report.scenarios),"links":len(report.links),
        "complex_patterns":len(report.to_dict()["complex_wave_patterns"]),"frame_errors":report.frame_errors})
    print(symbol,count,"PASS",flush=True)
out={"source":"Binance public USD-M MAINNET historical OHLC, not Futures Demo device acceptance", "as_of":args.as_of,
     "tick_policy":"FIXED_REPLAY_TEST_PARAMETERS_NOT_HISTORICAL_OR_CURRENT_FILTER_ASSERTION",
     "series":len(list(root.glob("*.json"))),"bars":sum(len(json.loads(p.read_text())["rows"]) for p in root.glob("*.json")),"prefix_comparisons":comparisons,"signal_observations":signals,"failures":0,"results":results,
     "raw_files_sha256":{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(root.glob("*.json"))}}
args.output.write_text(json.dumps(out,indent=2))
print(json.dumps({"comparisons":comparisons,"signals":signals,"failures":0}),flush=True)
