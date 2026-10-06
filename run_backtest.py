import argparse,os
from pathlib import Path
import pandas as pd
from dotenv import load_dotenv
from data import fetch_klines,save_csv
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
 if cache.exists():
  df=pd.read_csv(cache,parse_dates=['open_time'],index_col='open_time')
  df.index=pd.to_datetime(df.index,utc=True)
 else:
  df=fetch_klines(a.symbol,a.interval,a.start,a.end); save_csv(df,cache)
 df=df[(df.index>=pd.Timestamp(a.start,tz='UTC'))&(df.index<pd.Timestamp(a.end,tz='UTC'))]
 ind=calculate_indicators(df,config_from_env()).iloc[max(100,54):].copy()

 bt=Backtester(a.capital,a.fee,a.slippage,a.position_fraction,a.stop,a.target)
 eq,tr=bt.run(ind)
 m=calculate_metrics(eq,tr,a.interval)

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
