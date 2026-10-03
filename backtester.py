from dataclasses import dataclass
import numpy as np,pandas as pd
@dataclass
class Trade: entry_time:object;exit_time:object;side:str;entry_price:float;exit_price:float;qty:float;pnl:float;pnl_pct:float;reason:str;fees:float
class Backtester:
 def __init__(self,starting_capital=1000,fee_rate=.001,slippage_rate=.0005,position_fraction=1,stop_loss_pct=.02,take_profit_pct=.04,allow_shorts=False,intrabar_exit_policy='stop_first'):self.starting_capital=float(starting_capital);self.fee=float(fee_rate);self.slippage=float(slippage_rate);self.position_fraction=float(position_fraction);self.stop=float(stop_loss_pct);self.target=float(take_profit_pct);self.allow_shorts=bool(allow_shorts);self.intrabar_policy=intrabar_exit_policy
 def _buy_price(self,x):return x*(1+self.slippage)
 def _sell_price(self,x):return x*(1-self.slippage)
 def run(self,df):
  cash=self.starting_capital;qty=0.;side=None;entry_price=None;entry_time=None;entry_notional=0.;entry_fee=0.;trades=[];equity=[];pending=None
  for i in range(len(df)):
   r=df.iloc[i];ts=df.index[i];o,h,l,c=map(float,[r.open,r.high,r.low,r.close])
   if pending and side is None:
    if pending=='LONG':
     spend=cash*self.position_fraction
     if spend>0:entry_price=self._buy_price(o);qty=spend*(1-self.fee)/entry_price;entry_fee=spend*self.fee;cash-=spend;entry_notional=qty*entry_price;side='LONG';entry_time=ts;pending=None
   if side=='LONG':
    sp=entry_price*(1-self.stop);tp=entry_price*(1+self.target);hs=l<=sp;ht=h>=tp
    ex=reason=None
    if hs and ht:(ex,reason)=(tp,'TARGET') if self.intrabar_policy=='target_first' else (sp,'STOP')
    elif hs:ex,reason=sp,'STOP'
    elif ht:ex,reason=tp,'TARGET'
    if ex is not None:
     ep=self._sell_price(ex);proceeds=qty*ep;ef=proceeds*self.fee;cash+=proceeds-ef;pnl=(ep-entry_price)*qty-entry_fee-ef;trades.append(Trade(entry_time,ts,side,entry_price,ep,qty,pnl,pnl/max(entry_notional,1e-12)*100,reason,entry_fee+ef));qty=0;side=None;entry_price=None;entry_time=None
   equity.append((ts,cash+qty*c if side=='LONG' else cash))
   if side is None and bool(r.get('long_signal',False)):pending='LONG'
  if side is not None:
   ep=self._sell_price(float(df.close.iloc[-1]));proceeds=qty*ep;ef=proceeds*self.fee;cash+=proceeds-ef;pnl=(ep-entry_price)*qty-entry_fee-ef;trades.append(Trade(entry_time,df.index[-1],side,entry_price,ep,qty,pnl,pnl/max(entry_notional,1e-12)*100,'END',entry_fee+ef));equity[-1]=(df.index[-1],cash)
  e=pd.DataFrame(equity,columns=['time','equity']).set_index('time');t=pd.DataFrame([x.__dict__ for x in trades]);
  if t.empty:t=pd.DataFrame(columns=['entry_time','exit_time','side','entry_price','exit_price','qty','pnl','pnl_pct','reason','fees'])
  return e,t
def calculate_metrics(equity,trades,interval='1h'):
 if equity.empty:return {}
 start=float(equity.equity.iloc[0]);end=float(equity.equity.iloc[-1]);ret=end/start-1;dd=(equity.equity/equity.equity.cummax()-1).min();n=len(trades);wins=trades[trades.pnl>0];losses=trades[trades.pnl<0];gp=float(wins.pnl.sum()) if len(wins) else 0;gl=float(losses.pnl.sum()) if len(losses) else 0;pp=gp/abs(gl) if gl else np.inf;periods={'1m':525600,'5m':105120,'15m':35040,'1h':8760,'4h':2190,'1d':365}.get(interval,8760);rets=equity.equity.pct_change().dropna();sh=float(np.sqrt(periods)*rets.mean()/rets.std()) if len(rets)>1 and rets.std() else 0;days=max((equity.index[-1]-equity.index[0]).total_seconds()/86400,1/365);return {'starting_capital':start,'ending_equity':end,'total_return_pct':ret*100,'cagr_pct':((end/start)**(365.25/days)-1)*100,'max_drawdown_pct':dd*100,'trades':n,'wins':len(wins),'losses':len(losses),'win_rate_pct':len(wins)/n*100 if n else 0,'profit_factor':pp,'gross_profit':gp,'gross_loss':gl,'avg_trade':float(trades.pnl.mean()) if n else 0,'expectancy_pct':float(trades.pnl_pct.mean()) if n else 0,'fees':float(trades.fees.sum()) if n else 0,'sharpe':sh}
