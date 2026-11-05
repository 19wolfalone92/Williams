#!/usr/bin/env python3
"""Reproducible, exchange-free research: TC2 Wise-Man opposite EXIT proxies.

2026 frozen USD-M H1/H4/D1 candles, a truly chronological H1 prefix, H4/D1
CLOSED strictly before the trigger hour, and embargoed OOS validation.
This is NOT the Android execution engine, nor a certified book backtest.
No credentials, network, orders, future pivots, or model fitting.
"""
from __future__ import annotations

import argparse
import bisect
import json
import math
import statistics
from collections import Counter
from pathlib import Path

HOUR = 3_600_000
START = 1767225600000  # 2026-01-01 00:00 UTC
END = 1793858400000    # 2026-11-05 06:00 UTC
TRAIN_END = 1785542400000  # 2026-08-01 00:00 UTC
OOS_START = 1788220800000  # 2026-09-01 00:00 UTC; August embargo
SYMBOLS = ("BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT",
           "DOGEUSDT", "ADAUSDT", "LINKUSDT", "AVAXUSDT", "LTCUSDT")
# Observed current symbol metadata approximation; historical Binance tick
# changes are not available in this frozen OHLCV corpus.
TICK_ASSUMED = {"BTCUSDT":.1,"ETHUSDT":.01,"BNBUSDT":.01,"SOLUSDT":.01,
    "XRPUSDT":.0001,"DOGEUSDT":.00001,"ADAUSDT":.0001,
    "LINKUSDT":.001,"AVAXUSDT":.001,"LTCUSDT":.01}
FRAMES={"1h":HOUR,"4h":4*HOUR,"1d":24*HOUR}
COST_SIDE=0.0009  # explicit hypothetical fee + slippage, per execution

def load(symbol:str,root:Path)->dict:
    obj=json.loads((root/f"usdm_closed_2026_{symbol}.json").read_text())
    if (obj.get("schema")!="WILLIAMS_USDM_PUBLIC_FROZEN_CLOSED_OHLCV_V1"
        or obj.get("symbol")!=symbol
        or obj.get("as_of_exclusive_utc")!="2026-11-05T06:00:00Z"
        or obj.get("source_endpoint")!="/fapi/v1/klines"):
        raise ValueError("dataset source identity, timing or venue mismatch")
    result={}
    for frame,span in FRAMES.items():
        rows=obj["frames"][frame]
        expected=(END-START)//span
        if len(rows)!=expected:
            raise ValueError(f"{symbol}/{frame}: missing candles {len(rows)} vs {expected}")
        seq=[]
        for i,row in enumerate(rows):
            if len(row)!=6 or type(row[0])!=int or row[0]!=START+i*span:
                raise ValueError("duplicated, gapped or future candle")
            v=[float(x) for x in row[1:]]
            o,hi,lo,c,vol=v
            if (not all(map(math.isfinite,v)) or lo<=0 or vol<0
                or hi<max(o,lo,c) or lo>min(o,hi,c)):
                raise ValueError("invalid OHLCV")
            seq.append((row[0],o,hi,lo,c,vol))
        result[frame]=seq
    return result

def _sma_ao(mids:list[float])->list[float]:
    acc=[0.0]
    for x in mids: acc.append(acc[-1]+x)
    return [((acc[i+1]-acc[i-4])/5-(acc[i+1]-acc[i-33])/34)
            if i>=33 else math.nan for i in range(len(mids))]

def _smma(mids:list[float],n:int)->list[float]:
    out=[math.nan]*len(mids)
    if len(mids)>=n:
        out[n-1]=sum(mids[:n])/n
        for i in range(n,len(mids)):out[i]=(out[i-1]*(n-1)+mids[i])/n
    return out

def indicators(rows:list[tuple],frame:str)->dict:
    """At index i only bar[0..i], and previously known shifted SMMA values."""
    duration=FRAMES[frame]
    mids=[(x[2]+x[3])/2 for x in rows]
    ao=_sma_ao(mids)
    sm13=_smma(mids,13);sm8=_smma(mids,8);sm5=_smma(mids,5)
    jaw=[sm13[i-8] if i>=8 else math.nan for i in range(len(rows))]
    teeth=[sm8[i-5] if i>=5 else math.nan for i in range(len(rows))]
    lips=[sm5[i-3] if i>=3 else math.nan for i in range(len(rows))]
    bias=["UNVERIFIED"]*len(rows)
    lastHigh=[math.nan]*len(rows);prevHigh=[math.nan]*len(rows)
    lastLow=[math.nan]*len(rows);prevLow=[math.nan]*len(rows)
    lastHi=priorHi=lastLo=priorLo=math.nan
    for i,bar in enumerate(rows):
        if i>=4:
            j=i-2  # the second right bar has JUST CLOSED, no repainting
            if all(rows[j][2]>rows[k][2] for k in (j-2,j-1,j+1,j+2)):
                priorHi,lastHi=lastHi,rows[j][2]
            if all(rows[j][3]<rows[k][3] for k in (j-2,j-1,j+1,j+2)):
                priorLo,lastLo=lastLo,rows[j][3]
        lastHigh[i]=lastHi;prevHigh[i]=priorHi
        lastLow[i]=lastLo;prevLow[i]=priorLo
        if i<39 or not all(map(math.isfinite,(ao[i],jaw[i],teeth[i],lips[i]))):
            continue
        if lips[i]>teeth[i]>jaw[i] and bar[4]>lips[i] and ao[i]>0:
            bias[i]="UP"
        elif lips[i]<teeth[i]<jaw[i] and bar[4]<lips[i] and ao[i]<0:
            bias[i]="DOWN"
        else:bias[i]="MIXED"
    return {"close_times":[x[0]+duration for x in rows],"bias":bias,
        "ao":ao,"jaw":jaw,"teeth":teeth,"lips":lips,
        "last_high":lastHigh,"prev_high":prevHigh,
        "last_low":lastLow,"prev_low":prevLow}

def _frame_at(frame:dict,t:int)->int:
    # No future H4/D1 candle may contribute to a historical H1 decision.
    return bisect.bisect_right(frame["close_times"],t)-1

def signals(rows:list[tuple],indic:dict,tick:float):
    """Conservative CLOSED-H1 book-style candidates; not perfect TC2 WM1 geometry."""
    ao=indic["ao"]
    # Pending WM3 STOP may execute several hours after the confirmed five
    # candle fractal. Twenty H1 hours = declared RESEARCH TTL, not book rule.
    pending_long=[];pending_short=[]
    for i in range(40,len(rows)-25):
        now=rows[i];prior=rows[i-1];a=ao[i]
        pending_long=[x for x in pending_long if x[2]>=i+1]
        pending_short=[x for x in pending_short if x[2]>=i+1]
        long=[];short=[]
        if all(math.isfinite(ao[k]) for k in (i-3,i-2,i-1,i)):
            if (ao[i]>ao[i-1]>ao[i-2]>ao[i-3]
                and not (i>=4 and ao[i-3]>ao[i-4])):
                long.append(("WM2_AO_THIRD",now[2]+tick))
            if (ao[i]<ao[i-1]<ao[i-2]<ao[i-3]
                and not (i>=4 and ao[i-3]<ao[i-4])):
                short.append(("WM2_AO_THIRD",now[3]-tick))
        # Reversal bar with prior AO direction and a *numerical* mouth angle.
        # This is a labelled PROXY; expert Williams chart confirmation absent.
        midpoint=(now[2]+now[3])/2
        if i>=46 and all(math.isfinite(indic[x][k])
                for x in ("jaw","teeth","lips") for k in (i-5,i)):
            mouth=[indic[x][i] for x in ("jaw","teeth","lips")]
            former=[indic[x][i-5] for x in ("jaw","teeth","lips")]
            if (now[3]<prior[3] and now[4]>midpoint and ao[i]<ao[i-1] and
                now[2]<min(mouth) and now[2]-rows[i-5][2]<min(mouth)-max(former)):
                long.append(("WM1_REVERSAL_PROXY",now[2]+tick))
            if (now[2]>prior[2] and now[4]<midpoint and ao[i]>ao[i-1] and
                now[3]>max(mouth) and now[3]-rows[i-5][3]>max(mouth)-min(former)):
                short.append(("WM1_REVERSAL_PROXY",now[3]-tick))
        # Five-bar fractal forms at center i-2 only after BOTH right bars
        # are closed. The executable level has to be beyond H1 Teeth.
        j=i-2
        if math.isfinite(indic["teeth"][i]):
            if (all(rows[j][2]>rows[k][2] for k in (j-2,j-1,j+1,j+2)) and
                rows[j][2]+tick>indic["teeth"][i]):
                pending_long.append(("WM3_FRACTAL",rows[j][2]+tick,i+20))
            if (all(rows[j][3]<rows[k][3] for k in (j-2,j-1,j+1,j+2)) and
                rows[j][3]-tick<indic["teeth"][i]):
                pending_short.append(("WM3_FRACTAL",rows[j][3]-tick,i+20))
        # Only original price boundaries from already-formed fractals.
        long += [(name,price) for name,price,_ in pending_long]
        short += [(name,price) for name,price,_ in pending_short]
        # The next H1 is not usable for signal FORMATION. Its high/low
        # are used only after that hour has elapsed, as trigger/fill proxy.
        later=rows[i+1]
        triggered=[]
        if long and later[2]>=min(x[1] for x in long):
            source,level=min(long,key=lambda x:x[1])
            triggered.append((source,"SHORT",level))
        if short and later[3]<=max(x[1] for x in short):
            source,level=max(short,key=lambda x:x[1])
            triggered.append((source,"LONG",level))
        # Consumed fractals cannot return as previously TRIGGERED exits.
        # Same-hour two-sided triggers are unordered and always excluded.
        pending_long=[x for x in pending_long if later[2]<x[1]]
        pending_short=[x for x in pending_short if later[3]>x[1]]
        if len(triggered)!=1:
            continue  # unknown same-hour event ordering; never invent
        source,owner,level=triggered[0]
        if owner=="LONG":
            fill=min(level,later[1])  # sell stop gap: worse opening price
            stop=min(x[3] for x in rows[i-4:i+1])
        else:
            fill=max(level,later[1])
            stop=max(x[2] for x in rows[i-4:i+1])
        # Record all opposite signals, including WM3 where an existing
        # 5-bar trailing stop may ALREADY have fired. Such candidates
        # cannot enter the HOLD comparison, but must not vanish silently.
        stop_behind= (stop<fill if owner=="LONG" else stop>fill)
        yield {"i":i,"source":source,"owner":owner,
               "decision_at":later[0],"exit_ref":fill,
               "stop_ref":stop,"prior_5bar_stop_behind_trigger":stop_behind,
               "signal_at":now[0]+HOUR}

def regime(event:dict,h1:dict,h4:dict,d1:dict,r4:list,r1:list)->dict:
    """No threshold training; distinguish observed price facts from wave hypotheses."""
    i=event["i"];t=event["decision_at"];h=_frame_at(h4,t);d=_frame_at(d1,t)
    if min(h,d)<40:return {"label":"NO_SENIOR_CAUSAL_HISTORY","hold":False}
    direction=event["owner"];wanted="UP" if direction=="LONG" else "DOWN"
    same=h4["bias"][h]==wanted and d1["bias"][d]==wanted
    counter=h1["bias"][i]!=wanted
    def pivot_bad(frame,idx):
        px=frame["last_low"][idx] if direction=="LONG" else frame["last_high"][idx]
        if not math.isfinite(px):return None
        candle=r4 if frame is h4 else r1
        close=candle[idx][4]
        return close<px if direction=="LONG" else close>px
    b4=pivot_bad(h4,h);bd=pivot_bad(d1,d)
    swing_ok=all(x is False for x in (b4,bd))
    # Last two confirmed fractal swings on H4 must be monotonic in OWNER
    # direction; pure Alligator/AO agreement is not enough.
    if direction=="LONG":
        pairs=[(h4["prev_high"][h],h4["last_high"][h]),
               (h4["prev_low"][h],h4["last_low"][h])]
        structure=all(all(map(math.isfinite,p)) and p[1]>p[0] for p in pairs)
    else:
        pairs=[(h4["prev_high"][h],h4["last_high"][h]),
               (h4["prev_low"][h],h4["last_low"][h])]
        structure=all(all(map(math.isfinite,p)) and p[1]<p[0] for p in pairs)
    if direction=="LONG":
        d1_pairs=[(d1["prev_high"][d],d1["last_high"][d]),
                  (d1["prev_low"][d],d1["last_low"][d])]
        d1_structure=all(all(map(math.isfinite,p)) and p[1]>p[0]
                         for p in d1_pairs)
    else:
        d1_pairs=[(d1["prev_high"][d],d1["last_high"][d]),
                  (d1["prev_low"][d],d1["last_low"][d])]
        d1_structure=all(all(map(math.isfinite,p)) and p[1]<p[0]
                         for p in d1_pairs)
    ao_h4=h4["ao"][h];ao_d1=d1["ao"][d]
    # Full TC2 Elliott degree not verified. No W2/W4/ABC certainty inferred.
    return {"label":"JUNIOR_COUNTERTREND_CANDIDATE" if same and counter and swing_ok
            else "SENIOR_ALIGNED" if same and swing_ok else "CONFLICTING",
        "hold":bool(same and counter and swing_ok),
        "hold_structure":bool(same and counter and swing_ok and structure),
        "hold_h4_and_d1_structure":bool(same and counter and swing_ok and
                                        structure and d1_structure),
        "h4_swing_pattern_confirmed":bool(structure),
        "d1_swing_pattern_confirmed":bool(d1_structure),
        "h4_bias":h4["bias"][h],"d1_bias":d1["bias"][d],
        "h1_bias":h1["bias"][i],"h4_pivot_broken":b4,"d1_pivot_broken":bd,
        "h4_ao":ao_h4,"d1_ao":ao_d1,
        "wave_hypotheses":["W2","W4","ABC","NEW_IMPULSE_UNRESOLVED"],
        "wave_degree_certified":False,"decision_hour_bar_included":False}

def replay(e:dict,rows:list,horizon:int,stop_bars:int=5, *,
           trailing:bool=False)->dict:
    """Causal H1 paper path; source STOP is not an actual exchange order.

    At the first simulated full H1 AFTER the unknown execution hour, the
    original 3/5-closed-H1 stop remains. When trailing=True, each new STOP
    is evaluated solely from candles COMPLETED before the bar that may
    hit it. An existing protective STOP must never widen. A gap through
    the scheduled stop gets the WORSE opening price, never a guaranteed
    execution at the stop price.
    """
    i=e["i"];ref=e["exit_ref"];owner=e["owner"]
    sign=1.0 if owner=="LONG" else -1.0
    if stop_bars not in (3,5) or horizon<=0 or not math.isfinite(ref) or ref<=0:
        return {"valid":False,"reason":"INVALID_REPLAY_ARGUMENTS"}
    initial=(min(x[3] for x in rows[i-stop_bars+1:i+1]) if sign>0
          else max(x[2] for x in rows[i-stop_bars+1:i+1]))
    if sign*(ref-initial)<=0:
        return {"valid":False,"reason":"STOP_ALREADY_PAST_OPPOSITE_TRIGGER"}
    future=rows[i+2:i+2+horizon] # skip trigger hour: unknown intrabar order
    if len(future)!=horizon:return {"valid":False,"reason":"INCOMPLETE_FORWARD_H1"}
    stop=initial
    exit_price=future[-1][4];hit=False;max_adverse=0.0;last_at=None;updates=0
    for k,bar in enumerate(future,i+2):
        # Look ONLY at complete bars with indices strictly less than k.
        if trailing:
            prior=rows[k-stop_bars:k]
            if len(prior)!=stop_bars:return {"valid":False,"reason":"MISSING_TRAIL_HISTORY"}
            new=(min(x[3] for x in prior) if sign>0
                 else max(x[2] for x in prior))
            tightened=max(stop,new) if sign>0 else min(stop,new)
            if tightened!=stop:
                updates+=1
                stop=tightened
        # Using a whole H1 low/high at a stop-hit bar may OVERESTIMATE
        # adverse excursion after the actual stop fill. Explicitly label it.
        max_adverse=max(max_adverse,max(0.0,-sign*(bar[3 if sign>0 else 2]-ref)))
        if (bar[3]<=stop if sign>0 else bar[2]>=stop):
            exit_price=min(stop,bar[1]) if sign>0 else max(stop,bar[1])
            hit=True;last_at=bar[0];break
    # Same initial entry cancels from both paths. Exit fee/slippage
    # model charges each path by exit notional; no real fills known.
    delta=(sign*(exit_price-ref)-COST_SIDE*(exit_price-ref))/ref*10_000
    return {"valid":True,"delta_bps":delta,"stop_hit":hit,
        "adverse_bps":max_adverse/ref*10_000,
        "risk_unit":max_adverse/abs(ref-initial),
        "loss_bps":min(0.0,delta),
        "exit_reference":exit_price,"initial_stop_reference":initial,
        "final_stop_reference":stop,"trailing_tightening_events":updates,
        "exit_bar_open_ms":last_at,
        "stop_model":"TRAILING_3_OR_5_PREVIOUS_CLOSED_H1" if trailing
                     else "STATIC_3_OR_5_PRIOR_H1",
        "no_intrabar_sequence_or_bid_ask":True,
        "adverse_measurement_is_full_bar_upper_bound":True}

def _summary(items:list[dict])->dict:
    if not items:return {"n":0,"win":0,"loss":0,"stop_hits":0,
        "mean_delta_bps":None,"median_delta_bps":None,"p10_delta_bps":None,
        "worst_delta_bps":None,"max_adverse_bps_p90":None,
        "losses_total_bps":0.0}
    v=[x["delta_bps"] for x in items];a=sorted(x["adverse_bps"] for x in items)
    return {"n":len(items),"win":sum(x>0 for x in v),"loss":sum(x<0 for x in v),
        "stop_hits":sum(x["stop_hit"] for x in items),
        "mean_delta_bps":round(statistics.mean(v),4),
        "median_delta_bps":round(statistics.median(v),4),
        "p10_delta_bps":round(sorted(v)[int(.1*(len(v)-1))],4),
        "worst_delta_bps":round(min(v),4),
        "max_adverse_bps_p90":round(a[int(.9*(len(a)-1))],4),
        "losses_total_bps":round(sum(min(0,x) for x in v),4)}

def evaluate(root:Path)->dict:
    all_events=[]
    inputs={}
    for symbol in SYMBOLS:
        frames=load(symbol,root)
        h1=indicators(frames["1h"],"1h")
        h4=indicators(frames["4h"],"4h")
        d1=indicators(frames["1d"],"1d")
        inputs[symbol]={frame:len(frames[frame]) for frame in FRAMES}
        for e in signals(frames["1h"],h1,TICK_ASSUMED[symbol]):
            e["symbol"]=symbol
            e["source_context"]=regime(e,h1,h4,d1,frames["4h"],frames["1d"])
            e["windows"]={}
            for hours in (6,24):
                for stop_bars in (3,5):
                    e["windows"][f"{hours}h_stop{stop_bars}"]=replay(
                        e,frames["1h"],hours,stop_bars)
                    e["windows"][f"{hours}h_trail{stop_bars}"]=replay(
                        e,frames["1h"],hours,stop_bars,trailing=True)
            all_events.append(e)
    def summarize(cohort):
        result={"all_candidates":len(cohort),
            "sources":dict(Counter(x["source"] for x in cohort)),
            "regimes":dict(Counter(x["source_context"]["label"] for x in cohort))}
        for selection,selector in (
            ("h4_d1_only","hold"),
            ("h4_swing_confirmed","hold_structure"),
            ("both_h4_d1_swings_confirmed","hold_h4_and_d1_structure")):
            chosen=[x for x in cohort if x["source_context"].get(selector)]
            result[selection]={"proposed_holds":len(chosen),"by_source":dict(Counter(
                x["source"] for x in chosen))}
            for h in (6,24):
                for stop in (3,5):
                    key=f"{h}h_stop{stop}"
                    data=[x["windows"][key] for x in chosen if x["windows"][key]["valid"]]
                    result[selection][key]=_summary(data)
                    result[selection][key]["ineligible_historic_stop"]=(len(chosen)-len(data))
                    trail_key=f"{h}h_trail{stop}"
                    trail_data=[x["windows"][trail_key] for x in chosen
                        if x["windows"][trail_key]["valid"]]
                    result[selection][trail_key]=_summary(trail_data)
                    result[selection][trail_key]["ineligible_historic_stop"]=(len(chosen)-len(trail_data))
                    result[selection][trail_key]["trailing_stop_tightening_events"] = sum(
                        x["trailing_tightening_events"] for x in trail_data)
        return result
    train=[x for x in all_events if x["decision_at"]<TRAIN_END]
    oos=[x for x in all_events if x["decision_at"]>=OOS_START]
    return {
        "schema":"WILLIAMS_TC2_H1_H4_D1_WALKFORWARD_PROXY_AUDIT_V1",
        "as_of_utc":"2026-11-05T06:00:00Z",
        "data":"10 x (7398 H1 + 1849 H4 + 308 D1) genuine public Binance USD-M frozen candles",
        "period_utc":"2026-01-01..2026-11-05 06:00Z",
        "train_period":"Jan-Jul 2026","embargo":"Aug 2026",
        "out_of_sample":"Sep 2026 to 2026-11-05 06:00Z",
        "historical_williams_signals":"WM2 AO third colour, WM3 confirmed 5-bar fractal plus 20-hour research pending STOP, conservative WM1 proxy; NOT full book or owned campaign",
        "fill_model":"next H1 breakout level or worse gap opening; decision-hour path excluded",
        "stop_model":"static historic last 3 or 5 H1 bars PLUS prebar-closed 3/5 trailing counterfactual (NOT signed actual Binance STOP)",
        "trailing_replay_observation":"STOP can tighten only from H1 bars closed BEFORE next simulated H1, stop gap fills at adverse open",
        "first_trigger_hour_unreplayed":True,
        "h1_h4_d1_feature_lookahead":False,"future_waves_certified":False,
        "fills_certified":False,"profitability_certified":False,
        "execution_authority":False,"no_real_trades":True,
        "instrument_count":len(SYMBOLS),"frame_bar_counts":inputs,
        "all_event_count":len(all_events),
        "development":summarize(train),"holdout":summarize(oos),
        "warnings":["WM1 numeric proxy is not visually certified book angulation",
            "WM3 20-hour pending expiry is a research assumption, NOT a TC2 author rule",
            "NO real campaign ownership, reverse pyramid, funding, liquidation, bid/ask or exchange receipts",
            "Only candidate opposite-entry events; not actual full TC2 book exit trade list",
            "Many triggered WM3 fractals had prior 3/5-bar STOP already breached; they count as candidates but never as valid HOLD samples",
            "Simulated stop uses pre-signal 3/5 H1 lows/highs, NOT signed Binance STOP",
            "Additional 3/5-bar trailing is modelled causally on later CLOSED bars, but NOT Binance STOP acknowledgements",
            "Actual TC2 wave ownership and pyramid scaling remain unverified; all results are independent exit proxies",
            "MAE from OHLC at stop-hit bar may be overstated (unknown intrabar order)",
            "Research outcome excludes unknown trigger-hour intrabar order",
            "Overlapping assets/signals are correlated; no calibrated confidence",
            "No thresholds were optimized on holdout outcomes",
            "A third prespecified H4+D1 both-confirmed swing cohort measures a STRICTER paper-only alternative",
            "Identical 2026 market regime may not generalize to earlier years",
            "Do not replace the real TC2 exit by H4/D1 filter without validated safety improvement"]}
        
def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--data",type=Path,default=Path("research/data"))
    p.add_argument("--output",type=Path,default=Path("build/tc2_context_walkforward.json"))
    a=p.parse_args()
    report=evaluate(a.data)
    a.output.parent.mkdir(parents=True,exist_ok=True)
    a.output.write_text(json.dumps(report,indent=2,ensure_ascii=False,allow_nan=False)+"\n")
    print(json.dumps({"all_event_count":report["all_event_count"],
        "development_candidates":report["development"]["all_candidates"],
        "holdout_candidates":report["holdout"]["all_candidates"],
        "holdout_h4_d1":report["holdout"]["h4_d1_only"],
        "holdout_confirmed_swing":report["holdout"]["h4_swing_confirmed"]},
        ensure_ascii=False,allow_nan=False))
if __name__=="__main__":main()
