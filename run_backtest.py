import argparse,os
from pathlib import Path
import pandas as pd
from dotenv import load_dotenv
from data import fetch_klines,save_csv
from strategy import calculate_indicators,config_from_env
from backtester import Backtester,calculate_metrics
from plotting import plot_backtest
load_dotenv()
def main():
 ap=argparse.ArgumentParser();ap.add_argument('--symbol',default=os.getenv('SYMBOL','BTCUSDT'));ap.add_argument('--interval',default=os.getenv('INTERVAL','1h'));ap.add_argument('--start',default=os.getenv('START_DATE','2024-01-01'));ap.add_argument('--end',default=os.getenv('END_DATE','2026-10-01'));ap.add_argument('--capital',type=float,default=1000);ap.add_argument('--fee',type=float,default=.001);ap.add_argument('--slippage',type=float,default=.0005);ap.add_argument('--position-fraction',type=float,default=1);ap.add_argument('--stop',type=float,default=.02);ap.add_argument('--target',type=float,default=.04);ap.add_argument('--cache',default='');ap.add_argument('--outdir',default='results');a=ap.parse_args();out=Path(a.outdir);out.mkdir(parents=True,exist_ok=True);cache=Path(a.cache) if a.cache else out/f'{a.symbol}_{a.interval}.csv'
 if cache.exists():df=pd.read_csv(cache,parse_dates=['open_time'],index_col='open_time');df.index=pd.to_datetime(df.index,utc=True)
 else:df=fetch_klines(a.symbol,a.interval,a.start,a.end);save_csv(df,cache)
 df=df[(df.index>=pd.Timestamp(a.start,tz='UTC'))&(df.index<pd.Timestamp(a.end,tz='UTC'))];ind=calculate_indicators(df,config_from_env()).iloc[max(100,54):];bt=Backtester(a.capital,a.fee,a.slippage,a.position_fraction,a.stop,a.target);eq,tr=bt.run(ind);m=calculate_metrics(eq,tr,a.interval);print(m);tr.to_csv(out/'trades.csv',index=False);eq.to_csv(out/'equity.csv');pd.DataFrame([m]).to_csv(out/'metrics.csv',index=False);plot_backtest(ind,eq,tr,out/'backtest.png',f'{a.symbol} {a.interval} — Williams')
if __name__=='__main__':main()
