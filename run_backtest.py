import argparse,os
from pathlib import Path
import pandas as pd
from dotenv import load_dotenv
from data import fetch_klines,save_csv,validate_ohlcv_frame,BASE_URL
from strategy import calculate_indicators,config_from_env
from backtester import Backtester,calculate_metrics
from plotting import plot_backtest
from cpcv_backtester import run_cpcv
from stress_suite import run_latency_slippage_stress

load_dotenv()

def _cpcv_signal_score(train, test):
    # Leak-free, model-free validation: signal at t is scored against return t->t+1.
    values = [float(x["next_return"]) for x in test if bool(x["signal"])]
    return sum(values) / len(values) if values else 0.0

def main():
 ap=argparse.ArgumentParser()
 ap.add_argument('--symbol',default=os.getenv('SYMBOL','BTCUSDT'))
 ap.add_argument('--interval',default=os.getenv('INTERVAL','1h'))
 ap.add_argument('--start',default=os.getenv('START_DATE','2024-01-01'))
 ap.add_argument('--end',default=os.getenv('END_DATE','2026-10-01'))
 ap.add_argument('--capital',type=float,default=1000)
 ap.add_argument('--fee',type=float,default=.001)
 ap.add_argument('--slippage',type=float,default=.0005)
 ap.add_argument('--position-fraction',type=float,default=1)
 ap.add_argument('--stop',type=float,default=.02)
 ap.add_argument('--target',type=float,default=.04)
 ap.add_argument('--cache',default='')
 ap.add_argument('--outdir',default='results')
 ap.add_argument('--skip-cpcv',action='store_true')
 ap.add_argument('--cpcv-groups',type=int,default=6)
 ap.add_argument('--cpcv-test-groups',type=int,default=2)
 ap.add_argument('--cpcv-purge',type=int,default=10)
 ap.add_argument('--cpcv-embargo',type=int,default=10)
 ap.add_argument('--stress-cases',type=int,default=100)
 ap.add_argument('--stress-tick-pct',type=float,default=.0001)
 a=ap.parse_args()

 out=Path(a.outdir); out.mkdir(parents=True,exist_ok=True)
 cache=Path(a.cache) if a.cache else out/f'{a.symbol}_{a.interval}.csv'
 cache_hit=cache.exists()
 if cache.exists():
  df=pd.read_csv(cache,parse_dates=['open_time'],index_col='open_time')
  df.index=pd.to_datetime(df.index,utc=True)
 else:
  df=fetch_klines(a.symbol,a.interval,a.start,a.end); save_csv(df,cache)
 df=df[(df.index>=pd.Timestamp(a.start,tz='UTC'))&(df.index<pd.Timestamp(a.end,tz='UTC'))]
 df=validate_ohlcv_frame(df)
 now=pd.Timestamp.now(tz='UTC')
 partial_count=int((df['close_time']>=now).sum()) if 'close_time' in df.columns else 0
 if partial_count:
  df=df[df['close_time']<now]
 if df.empty:
  raise ValueError("No closed, validated OHLCV candles remain in the requested date range")
 cfg=config_from_env()
 ind=calculate_indicators(df,cfg).iloc[max(100,54):].copy()
 if ind.empty:
  raise ValueError("Not enough validated candles to calculate Williams indicators")

 bt=Backtester(a.capital,a.fee,a.slippage,a.position_fraction,a.stop,a.target)
 eq,tr=bt.run(ind)
 m=calculate_metrics(eq,tr,a.interval,starting_capital=a.capital)
 import hashlib,json,subprocess
 from datetime import datetime,timezone
 dataset_hash=hashlib.sha256(df.reset_index().to_csv(index=False).encode("utf-8")).hexdigest()
 try:
  commit_sha=subprocess.run(["git","rev-parse","HEAD"],capture_output=True,text=True,check=True).stdout.strip()
 except Exception:
  commit_sha=os.getenv("GITHUB_SHA","unknown")
 config_payload=json.dumps(cfg,sort_keys=True,default=str,separators=(",",":"))
 params_payload=json.dumps({"symbol":a.symbol,"interval":a.interval,"start_utc":pd.Timestamp(a.start,tz="UTC").isoformat(),"end_utc_exclusive":pd.Timestamp(a.end,tz="UTC").isoformat(),"capital":a.capital,"fee_rate":a.fee,"slippage_rate":a.slippage,"position_fraction":a.position_fraction,"stop_loss_pct":a.stop,"take_profit_pct":a.target,"intrabar_exit_policy":"stop_first","config":cfg},sort_keys=True,default=str,separators=(",",":"))
 manifest={"created_at_utc":datetime.now(timezone.utc).isoformat(),"commit_sha":commit_sha,"symbol":a.symbol.upper(),"interval":a.interval,"date_range_semantics":"[start, end), UTC","start_utc":pd.Timestamp(a.start,tz="UTC").isoformat(),"end_utc_exclusive":pd.Timestamp(a.end,tz="UTC").isoformat(),"data_endpoint":BASE_URL if not cache_hit else "cache file (original source not independently verified)","data_source_verified":not cache_hit,"closed_candles_only":True,"partial_candles_excluded":partial_count,"row_count":int(len(df)),"dataset_sha256":dataset_hash,"config_sha256":hashlib.sha256(config_payload.encode("utf-8")).hexdigest(),"parameters_sha256":hashlib.sha256(params_payload.encode("utf-8")).hexdigest(),"parameters":{"capital":a.capital,"fee_rate":a.fee,"slippage_rate":a.slippage,"position_fraction":a.position_fraction,"stop_loss_pct":a.stop,"take_profit_pct":a.target,"intrabar_exit_policy":"stop_first"}}
 (out/'manifest.json').write_text(json.dumps(manifest,indent=2,sort_keys=True)+"\n",encoding="utf-8")


 if not a.skip_cpcv and len(ind) >= 60:
  samples=[]
  next_close=ind.close.shift(-1)
  for i,row in ind.iloc[:-1].iterrows():
   samples.append({"signal":bool(row.get("long_signal",False)),"next_return":float(next_close.loc[i]/row.close-1.0) if row.close else 0.0})
  cpcv=run_cpcv(samples,_cpcv_signal_score,n_groups=a.cpcv_groups,test_groups=a.cpcv_test_groups,purge_bars=a.cpcv_purge,embargo_bars=a.cpcv_embargo)
  m.update({
   "cpcv_folds":cpcv.folds,
   "cpcv_mean_signal_return_pct":cpcv.mean_score*100.0,
   "cpcv_min_signal_return_pct":cpcv.min_score*100.0,
   "cpcv_max_signal_return_pct":cpcv.max_score*100.0,
  })

 stress=run_latency_slippage_stress(
  expected_reward_pct=a.target,
  fee_pct=2.0*a.fee,
  tick_pct=max(1e-8,a.stress_tick_pct),
  cases=a.stress_cases,
  slippage_ceiling_pct=max(a.slippage,0.0015),
 )
 m.update({
  "stress_pass_rate_pct":stress.pass_rate*100.0,
  "stress_worst_net_edge_pct":stress.worst_net_edge_pct*100.0,
  "stress_p05_net_edge_pct":stress.p05_net_edge_pct*100.0,
  "stress_passed":stress.passed,
 })

 print(m)
 tr.to_csv(out/'trades.csv',index=False)
 eq.to_csv(out/'equity.csv')
 pd.DataFrame([m]).to_csv(out/'metrics.csv',index=False)
 plot_backtest(ind,eq,tr,out/'backtest.png',f'{a.symbol} {a.interval} — Williams')

if __name__=='__main__':
 main()
